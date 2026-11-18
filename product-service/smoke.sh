#!/usr/bin/env bash

set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$PROJECT_ROOT"

COMPOSE="docker compose"
BASE_URL="http://localhost:8080"
SVC_URL="http://localhost:8086"
GATEWAY_TOKEN_ENDPOINT="$BASE_URL/api/v1/auth/login"
ADMIN_USER="${ADMIN_USER:-admin}"
ADMIN_PASS="${ADMIN_PASS:-admin}"

step() { echo; echo "==> $*"; }
fail() { echo "FAIL: $*" >&2; exit 1; }

step "Step 1 — Build product-service JAR"
./mvnw -q -pl product-service -am clean package -DskipTests \
  || fail "maven build failed"

JAR=$(ls product-service/target/product-service-*.jar | head -1)
[ -n "$JAR" ] || fail "JAR not found"
echo "Built: $JAR"

step "Step 2 — Tear down any prior state (containers + volumes)"

$COMPOSE down -v --remove-orphans 2>/dev/null || true

step "Step 3 — Bring up infra + product-service"
$COMPOSE up -d postgres redis kafka keycloak elasticsearch rustfs \
                 gateway-service auth-service product-service \
  || fail "docker compose up failed"

echo "Waiting 90s for containers to become healthy (postgres init + keycloak realm import + first Liquibase)..."
sleep 90

step "Step 4 — Verify Liquibase ran"
$COMPOSE logs product-service 2>/dev/null \
  | grep -E "Successfully acquired change log lock|Update committed" \
  | head -5 || fail "Liquibase log not found"

step "Step 5 — Direct health endpoint"
HEALTH=$(curl -fsS "$SVC_URL/actuator/health" || true)
echo "$HEALTH"
echo "$HEALTH" | grep -q '"status":"UP"' || fail "product-service not UP"

step "Step 6 — Get admin token via gateway → auth-service"
LOGIN_RESPONSE=$(curl -fsS -X POST "$GATEWAY_TOKEN_ENDPOINT" \
  -H "Content-Type: application/json" \
  -d "{\"username\":\"$ADMIN_USER\",\"password\":\"$ADMIN_PASS\"}" \
  || true)
TOKEN=$(echo "$LOGIN_RESPONSE" | jq -r '.data.accessToken // empty')
[ -n "$TOKEN" ] || fail "could not extract accessToken from: $LOGIN_RESPONSE"
echo "Token len: ${#TOKEN}"

AUTH="Authorization: Bearer $TOKEN"
JSON="Content-Type: application/json"

step "Step 7 — Create category"
CATEGORY_RES=$(curl -fsS -X POST "$BASE_URL/api/v1/categories" \
  -H "$AUTH" -H "$JSON" \
  -d '{"title":"Phones","slug":"phones"}' \
  || true)
echo "$CATEGORY_RES" | jq . 2>/dev/null || echo "$CATEGORY_RES"
echo "$CATEGORY_RES" | jq -e '.success == true' >/dev/null \
  || fail "category create failed"
CATEGORY_ID=$(echo "$CATEGORY_RES" | jq -r '.data.id')
[ -n "$CATEGORY_ID" ] || fail "could not extract category id"

step "Step 8 — Create brand"
BRAND_RES=$(curl -fsS -X POST "$BASE_URL/api/v1/brands" \
  -H "$AUTH" -H "$JSON" \
  -d '{"name":"Acme","slug":"acme"}' \
  || true)
echo "$BRAND_RES" | jq . 2>/dev/null || echo "$BRAND_RES"
echo "$BRAND_RES" | jq -e '.success == true' >/dev/null \
  || fail "brand create failed"
BRAND_ID=$(echo "$BRAND_RES" | jq -r '.data.id')
[ -n "$BRAND_ID" ] || fail "could not extract brand id"

step "Step 9 — Create product"
PRODUCT_BODY=$(jq -n \
  --arg categoryId "$CATEGORY_ID" \
  --arg brandId "$BRAND_ID" \
  '{title:"iPhone 15",slug:"iphone-15",description:"Latest iPhone",sku:"IP15-001",priceUnit:999.00,quantity:10,status:"ACTIVE",categoryId:$categoryId,brandId:$brandId}')
PROD_RES=$(curl -fsS -X POST "$BASE_URL/api/v1/products" \
  -H "$AUTH" -H "$JSON" \
  -d "$PRODUCT_BODY" \
  || true)
echo "$PROD_RES" | jq . 2>/dev/null || echo "$PROD_RES"
echo "$PROD_RES" | jq -e '.success == true' >/dev/null \
  || fail "product create failed"

step "Step 10 — Read product back (anonymous, via gateway)"
PRODUCT_ID=$(echo "$PROD_RES" | jq -r '.data.id')
[ -n "$PRODUCT_ID" ] || fail "could not extract product id"
GET_RES=$(curl -fsS "$BASE_URL/api/v1/products/$PRODUCT_ID" || true)
echo "$GET_RES" | jq . 2>/dev/null || echo "$GET_RES"
echo "$GET_RES" | jq -e '.data.title == "iPhone 15"' >/dev/null \
  || fail "anonymous GET did not return expected product"

step "Step 11 — Verify Kafka event published"
$COMPOSE exec -T kafka kafka-console-consumer \
  --bootstrap-server kafka:9092 \
  --topic shop.product.lifecycle.v1 \
  --from-beginning \
  --max-messages 1 \
  --timeout-ms 10000 \
  2>/dev/null | tee /tmp/outbox-event.json
grep -q "ProductCreated" /tmp/outbox-event.json \
  || fail "kafka event payload not found"

step "Step 12 — Verify Prometheus metrics"
PROM=$(curl -fsS "$SVC_URL/actuator/prometheus" || true)
echo "$PROM" | grep -E "^product_(events_published|outbox_pending_count|cache_hit|cache_miss)" \
  | head -20
echo "$PROM" | grep -q "product_events_published_total" \
  || fail "product_events_published_total not exposed"
echo "$PROM" | grep -q "product_outbox_pending_count" \
  || fail "product_outbox_pending_count not exposed"

step "Step 13 — Stop product-service (keep infra running)"
$COMPOSE stop product-service || true

echo
echo "SMOKE OK"
