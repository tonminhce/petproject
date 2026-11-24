#!/bin/sh
set -u
cd /Users/tonminh-mac/IdeaProjects/untitled5
echo '=== BUILD ==='
python3 production-audit/bin/harness build > /tmp/fbuild2.json 2>&1
echo "build exit=$?"
echo '=== REDEPLOY ==='
docker compose up -d --force-recreate > /tmp/fup2.log 2>&1
echo "redeploy exit=$?"
sleep 60
set -a
. ./.env
set +a
for suite in unit happy edge security payment gateway kafka concurrency_inventory concurrency integration; do
  echo "=== SUITE $suite ==="
  python3 production-audit/bin/harness test --suite "$suite" > "/tmp/fb2-$suite.json" 2>&1
  echo "suite $suite exit=$?"
done
echo '=== OPERATIONS ==='
python3 production-audit/bin/harness operations check > /tmp/fb2-ops.json 2>&1
echo "operations exit=$?"
echo '=== E2E ==='
python3 production-audit/bin/harness e2e run --scope all > /tmp/fb2-e2e.log 2>&1
echo "e2e run exit=$?"
python3 production-audit/bin/harness e2e report > /tmp/fb2-e2e-report.json 2>&1
echo "e2e report exit=$?"
echo '=== ALERTS ==='
python3 production-audit/bin/harness alerts validate > /tmp/fb2-alerts.json 2>&1
echo "alerts exit=$?"
echo '=== K8S ==='
python3 production-audit/bin/harness k8s validate > /tmp/fb2-k8s.json 2>&1
echo "k8s exit=$?"
echo '=== STRIPE ==='
python3 production-audit/bin/harness stripe acceptance > /tmp/fb2-stripe.json 2>&1
echo "stripe exit=$?"
echo '=== RAMP ==='
python3 production-audit/bin/harness load ramp > /tmp/fb2-ramp.json 2>&1
echo "ramp exit=$?"
echo '=== CAPACITY ==='
cd production-audit && python3 -c "import sys; sys.path.insert(0,'.'); from harness.loadtesting.capacity import record_capacity_limit, write_capacity_report; d=record_capacity_limit(); print('binding', d.get('binding',{}).get('binds_to_current_source')); write_capacity_report()" > /tmp/fb2-capacity.txt 2>&1
echo "capacity exit=$?"
echo '=== MANIFEST ==='
python3 bin/harness inventory > /dev/null 2>&1
echo "manifest exit=$?"
echo '=== ALL DONE ==='