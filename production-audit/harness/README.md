# Production Audit Harness

Executable harness for the Petproject production-readiness program.

## Run

```bash
python production-audit/harness/harness.py inventory
python production-audit/harness/harness.py audit source
python production-audit/harness/harness.py audit architecture
python production-audit/harness/harness.py audit distributed-systems
python production-audit/harness/harness.py audit security
python production-audit/harness/harness.py audit performance
python production-audit/harness/harness.py audit database
python production-audit/harness/harness.py tasks list
python production-audit/harness/harness.py tasks next
python production-audit/harness/harness.py tasks status
python production-audit/harness/harness.py verify --suite checkout
python production-audit/harness/harness.py verify --suite kafka
python production-audit/harness/harness.py verify --suite security
python production-audit/harness/harness.py verify --suite performance
python production-audit/harness/harness.py reconcile
python production-audit/harness/harness.py run --max-iterations 20
python production-audit/harness/harness.py resume
python production-audit/harness/harness.py report

# findings register
python production-audit/harness/harness.py findings list
python production-audit/harness/harness.py findings summary
python production-audit/harness/harness.py findings add <file.json>
python production-audit/harness/harness.py findings remediate <file.json>

# task registry
python production-audit/harness/harness.py tasks import-findings
python production-audit/harness/harness.py mark-reviewed <path...> --reviewer A13 --batch B01
```

## Layout

- `harness_lib/inventory.py`  - file discovery, classification, hashing, coverage reconciliation
- `harness_lib/analysis.py`   - Maven graph, Kafka topology, annotation & secret scans
- `harness_lib/task_registry.py` - atomic task lifecycle with dependency ordering
- `harness_lib/requirements.py` - requirements registry and coverage
- `harness_lib/verification.py` - executable verification suites producing evidence
- `harness_lib/scheduler.py`  - DISCOVER->PLAN->IMPLEMENT->TEST->VERIFY->REOPEN loop
- `harness_lib/report.py`     - Markdown report generation from persisted state

## State

All state is persisted under `production-audit/state/` with atomic writes:
`tasks.json`, `requirements.json`, `findings.json`, `dependencies.json`,
`evidence.json`, `events.jsonl`.

## Source-coverage accounting

`mark-reviewed` records a FULL_READ entry (path, sha256, line count, reviewer,
batch) in `production-audit/control/source-coverage.json`. `harness inventory`
recomputes the manifest and INVALIDATES every review whose content hash no
longer matches, so a stale review can never be counted towards coverage.

Coverage is reported two ways: all Java scope files (main + test) and the
production main source alone. The objective's GATE 1 requires the production
implementation to be read in full; the test corpus is read on demand, when a
finding or a fix requires it.

## Semantics

- A verification suite never reports PASS without observable evidence.
- A missing tool (maven, docker, network) yields BLOCKED, never PASS.
- Source changes invalidate stale review evidence automatically (`audit source`).
