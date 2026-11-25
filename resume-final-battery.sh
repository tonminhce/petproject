#!/bin/sh
set -u
cd /Users/tonminh-mac/IdeaProjects/untitled5
failures=0
last_code=0
cleaned=0
: > /tmp/fb2-failures.txt

cleanup() {
  if [ "$cleaned" -eq 0 ]; then
    cleanup_failed=0
    python3 production-audit/bin/harness e2e teardown > /tmp/fb2-teardown-trap.json 2>&1
    code=$?
    if [ "$code" -ne 0 ]; then
      cleanup_failed=1
      failures=$((failures + 1))
      printf 'cleanup-compose %s\n' "$code" >> /tmp/fb2-failures.txt
    fi
    for container in stripe-mock-acceptance payment-service-stripe-acceptance; do
      output=$(docker rm -f "$container" 2>&1)
      code=$?
      printf '%s\n' "$output" > /tmp/fb2-standalone-cleanup.log
      case "$output" in
        *"No such container"*) ;;
        *)
          if [ "$code" -ne 0 ]; then
            cleanup_failed=1
            failures=$((failures + 1))
            printf 'cleanup-%s %s\n' "$container" "$code" >> /tmp/fb2-failures.txt
          fi
          ;;
      esac
    done
    if [ "$cleanup_failed" -eq 0 ]; then
      cleaned=1
    fi
  fi
}
trap cleanup EXIT
trap 'cleanup; exit 130' INT
trap 'cleanup; exit 143' TERM

run_step() {
  name=$1
  log=$2
  shift 2
  echo "=== $name ==="
  "$@" > "$log" 2>&1
  code=$?
  last_code=$code
  echo "$name exit=$code"
  if [ "$code" -ne 0 ]; then
    failures=$((failures + 1))
    printf '%s %s\n' "$name" "$code" >> /tmp/fb2-failures.txt
  fi
  return 0
}

run_step reconcile /tmp/fb2-reconcile.json python3 production-audit/bin/harness reconcile
if [ "$failures" -ne 0 ]; then
  echo 'reconciliation failed; review and re-mark drifted source before running the battery'
  exit 1
fi

run_step token-residue /tmp/fb2-token-residue.json python3 -c "import sys; sys.path.insert(0,'production-audit'); from harness.loadtesting.runner import purge_token_residue; print(purge_token_residue())"
run_step pre-build-down /tmp/fb2-pre-down.log docker compose down
if [ "$failures" -ne 0 ]; then
  echo 'compose shutdown failed; refusing to run Maven while the stack may still be running'
  exit 1
fi
run_step build /tmp/fbuild2.json python3 production-audit/bin/harness build
if [ "$failures" -ne 0 ]; then
  echo 'reactor build failed; refusing to redeploy or test stale images'
  exit 1
fi
run_step redeploy /tmp/fup2.log docker compose up -d --force-recreate
run_step observability /tmp/fb2-observability.log docker compose --profile observability up -d --force-recreate --remove-orphans otel-collector prometheus alertmanager tempo loki alloy grafana
sleep 60

for suite in unit happy edge security payment gateway kafka concurrency_inventory concurrency integration; do
  run_step "suite-$suite" "/tmp/fb2-$suite.json" python3 production-audit/bin/harness test --suite "$suite"
done

run_step operations /tmp/fb2-ops.json python3 production-audit/bin/harness operations check
run_step e2e-setup /tmp/fb2-e2e-setup.json python3 production-audit/bin/harness e2e setup
run_step e2e /tmp/fb2-e2e.log python3 production-audit/bin/harness e2e run --scope all
run_step e2e-report /tmp/fb2-e2e-report.json python3 production-audit/bin/harness e2e report
run_step load-data-reset /tmp/fb2-load-data-reset.json python3 production-audit/bin/harness load reset-data
run_step alerts /tmp/fb2-alerts.json python3 production-audit/bin/harness alerts validate
run_step k8s /tmp/fb2-k8s.json python3 production-audit/bin/harness k8s validate
run_step stripe /tmp/fb2-stripe.json python3 production-audit/bin/harness stripe acceptance

for profile in baseline ramp spike sustained-peak flash-sale promotion-storm kafka-backlog failure-during-peak recovery soak; do
  run_step "load-$profile" "/tmp/fb2-$profile.json" python3 production-audit/bin/harness load "$profile"
done

run_step load-report /tmp/fb2-load-report.json python3 production-audit/bin/harness load report
run_step capacity /tmp/fb2-capacity.txt python3 -c "import sys; sys.path.insert(0,'production-audit'); from harness.loadtesting.capacity import record_capacity_limit, write_capacity_report; d=record_capacity_limit(); print('binding', d.get('binding',{}).get('binds_to_current_source')); write_capacity_report()"
run_step manifest /tmp/fb2-manifest.json python3 production-audit/bin/harness inventory
run_step final-reconcile /tmp/fb2-final-reconcile.json python3 production-audit/bin/harness reconcile
run_step verify /tmp/fb2-verify.json python3 production-audit/bin/harness verify
run_step report /tmp/fb2-report.json python3 production-audit/bin/harness report
run_step final-report /tmp/fb2-final-report.json python3 -c "import sys; sys.path.insert(0,'production-audit'); from harness.reporting.final_report import write_final_report; print(write_final_report())"
run_step teardown /tmp/fb2-teardown.json python3 production-audit/bin/harness e2e teardown
cleanup

echo "=== FAILURES $failures ==="
if [ "$failures" -ne 0 ]; then
  exit 1
fi
echo '=== ALL DONE ==='
