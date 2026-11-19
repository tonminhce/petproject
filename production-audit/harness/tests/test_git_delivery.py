from __future__ import annotations

import subprocess
import tempfile
import unittest
from pathlib import Path

from harness.git_delivery.delivery import (
    DeliveryRecord,
    allowed_tokens_for,
    commit_verified,
    current_branch,
    detect_secrets,
    is_protected_branch,
    push_verified,
    run_git,
    scan_paths_for_secrets,
)


SYNTHETIC_STRIPE_KEY = "sk_" + "live_" + "abcdefghijklmnopqrstuvwx"
SYNTHETIC_PRIVATE_KEY_BLOCK = "-----BEGIN RSA " + "PRIVATE KEY-----\nMIIabc\n"


def make_repository(root: Path) -> None:
    run_git(root, ["init", "-b", "main"])
    run_git(root, ["config", "user.email", "harness@example.com"])
    run_git(root, ["config", "user.name", "Harness"])
    (root / "file.txt").write_text("alpha\n", encoding="utf-8")
    run_git(root, ["add", "file.txt"])
    run_git(root, ["commit", "-m", "initial"])


class BranchProtectionTest(unittest.TestCase):
    def test_protected_branch_names(self) -> None:
        for name in ("main", "master", "production", "release", "develop"):
            self.assertTrue(is_protected_branch(name), name)

    def test_protected_branch_prefixes(self) -> None:
        self.assertTrue(is_protected_branch("main/hotfix"))
        self.assertTrue(is_protected_branch("release/1.0"))

    def test_audit_branch_is_allowed(self) -> None:
        self.assertFalse(is_protected_branch("audit/production-hardening-run-1"))

    def test_origin_prefixed_protected_branch_is_detected(self) -> None:
        self.assertTrue(is_protected_branch("origin/main"))


class SecretDetectionTest(unittest.TestCase):
    def test_stripe_secret_key_is_detected(self) -> None:
        payload = "STRIPE_SECRET_KEY=" + SYNTHETIC_STRIPE_KEY
        self.assertTrue(detect_secrets(payload))

    def test_private_key_block_is_detected(self) -> None:
        payload = SYNTHETIC_PRIVATE_KEY_BLOCK
        self.assertTrue(detect_secrets(payload))

    def test_ordinary_configuration_is_not_flagged(self) -> None:
        payload = "spring.datasource.url=jdbc:postgresql://localhost:5432/shop\n"
        self.assertFalse(detect_secrets(payload))

    def test_secret_file_name_is_flagged(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / ".env").write_text("A=1\n", encoding="utf-8")
            findings = scan_paths_for_secrets(root, [".env"])
            self.assertIn(".env", findings)

    def test_regular_source_file_without_secrets_is_clean(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / "Service.java").write_text("class Service {}\n", encoding="utf-8")
            self.assertEqual(scan_paths_for_secrets(root, ["Service.java"]), {})


    def test_documented_exception_is_honoured_in_the_path_scan(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / "contract.py").write_text(
                "STRIPE_WEBHOOK_SECRET = \"whsec_local_e2e_only\"\n", encoding="utf-8"
            )
            allowlist = {"contract.py": ["whsec_local_e2e_only"]}
            self.assertEqual(scan_paths_for_secrets(root, ["contract.py"], allowlist), {})

    def test_a_different_secret_in_an_excepted_path_is_still_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / "contract.py").write_text(
                "STRIPE_WEBHOOK_SECRET = \"whsec_" + "live_" + "abcdefghijklmnopqrstuvwx\"\n",
                encoding="utf-8",
            )
            allowlist = {"contract.py": ["whsec_local_e2e_only"]}
            self.assertIn("contract.py", scan_paths_for_secrets(root, ["contract.py"], allowlist))

    def test_a_prefix_exception_covers_every_file_under_the_prefix(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / "reports").mkdir()
            (root / "reports" / "run.json").write_text(
                "{\"stripeWebhookSecret\": \"whsec_local_e2e_only\"}\n", encoding="utf-8"
            )
            allowlist = {"reports/*": ["whsec_local_e2e_only"]}
            self.assertEqual(scan_paths_for_secrets(root, ["reports/run.json"], allowlist), {})

    def test_allowed_tokens_for_ignores_an_unrelated_path(self) -> None:
        allowlist = {"reports/*": ["whsec_local_e2e_only"]}
        self.assertEqual(allowed_tokens_for(allowlist, "src/main/Service.java"), [])
        self.assertEqual(allowed_tokens_for(allowlist, "reports/run.json"), ["whsec_local_e2e_only"])


class CommitRulesTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        make_repository(self.root)

    def tearDown(self) -> None:
        self.temporary.cleanup()

    def test_commit_on_protected_branch_is_rejected(self) -> None:
        (self.root / "file.txt").write_text("beta\n", encoding="utf-8")
        record = commit_verified(self.root, "chore: change", ["file.txt"])
        self.assertEqual(record.state, "REJECTED_BY_GATES")
        self.assertIn("protected branch", record.detail)

    def test_commit_without_paths_is_rejected(self) -> None:
        record = commit_verified(self.root, "chore: change", [])
        self.assertEqual(record.state, "REJECTED_BY_GATES")

    def test_commit_outside_allow_list_is_rejected(self) -> None:
        (self.root / "file.txt").write_text("beta\n", encoding="utf-8")
        (self.root / "other.txt").write_text("gamma\n", encoding="utf-8")
        record = commit_verified(self.root, "chore: change", ["file.txt", "other.txt"], allow_paths=["file.txt"])
        self.assertEqual(record.state, "REJECTED_BY_GATES")

    def test_commit_on_audit_branch_succeeds(self) -> None:
        run_git(self.root, ["checkout", "-b", "audit/production-hardening-test"])
        (self.root / "file.txt").write_text("beta\n", encoding="utf-8")
        record = commit_verified(self.root, "fix(test): change", ["file.txt"])
        self.assertEqual(record.state, "COMMITTED")
        self.assertEqual(len(record.commit_sha), 40)

    def test_commit_with_secret_is_rejected(self) -> None:
        run_git(self.root, ["checkout", "-b", "audit/secret-test"])
        (self.root / "creds.txt").write_text(SYNTHETIC_STRIPE_KEY + "\n", encoding="utf-8")
        record = commit_verified(self.root, "chore: secret", ["creds.txt"])
        self.assertEqual(record.state, "REJECTED_BY_GATES")
        self.assertIn("secret", record.detail.lower())

    def test_unrelated_unstaged_files_are_not_committed(self) -> None:
        run_git(self.root, ["checkout", "-b", "audit/staging-test"])
        (self.root / "file.txt").write_text("beta\n", encoding="utf-8")
        (self.root / "unrelated.txt").write_text("gamma\n", encoding="utf-8")
        record = commit_verified(self.root, "fix(test): change", ["file.txt"])
        self.assertEqual(record.state, "COMMITTED")
        committed = run_git(self.root, ["show", "--name-only", "--format=", "HEAD"]).stdout
        self.assertIn("file.txt", committed)
        self.assertNotIn("unrelated.txt", committed)


class PushRulesTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        make_repository(self.root)

    def tearDown(self) -> None:
        self.temporary.cleanup()

    def test_push_to_protected_branch_is_rejected(self) -> None:
        record = push_verified(self.root, "origin")
        self.assertEqual(record.state, "REJECTED_BY_GATES")

    def test_push_without_remote_is_blocked(self) -> None:
        run_git(self.root, ["checkout", "-b", "audit/push-test"])
        record = push_verified(self.root, "origin")
        self.assertEqual(record.state, "PUSH_BLOCKED")
        self.assertIn("remote not configured", record.detail)

    def test_push_to_unreachable_remote_is_blocked(self) -> None:
        run_git(self.root, ["checkout", "-b", "audit/push-test"])
        run_git(self.root, ["remote", "add", "origin", str(Path(self.temporary.name) / "missing.git")])
        record = push_verified(self.root, "origin")
        self.assertEqual(record.state, "PUSH_BLOCKED")

    def test_push_to_a_real_bare_remote_succeeds(self) -> None:
        bare = Path(self.temporary.name) / "remote.git"
        subprocess.run(["git", "init", "--bare", "-b", "main", str(bare)], check=True, capture_output=True)
        run_git(self.root, ["checkout", "-b", "audit/push-success"])
        run_git(self.root, ["remote", "add", "origin", str(bare)])
        record = push_verified(self.root, "origin")
        self.assertEqual(record.state, "PUSHED")
        self.assertEqual(record.remote_branch, "audit/push-success")


class DeliveryRecordTest(unittest.TestCase):
    def test_record_serialises_all_tracking_fields(self) -> None:
        record = DeliveryRecord(action="commit", branch="audit/x", commit_sha="a" * 40, state="COMMITTED")
        document = record.to_document()
        for key in ("action", "branch", "commit_sha", "state", "remote", "remote_branch", "exit_code"):
            self.assertIn(key, document)


if __name__ == "__main__":
    unittest.main()
