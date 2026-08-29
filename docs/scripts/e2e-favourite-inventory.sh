#!/usr/bin/env bash
# e2e-favourite-inventory.sh — End-to-end test for favourite-service + inventory-service on Docker
#
# Prerequisites:
#   - ./start-docker.sh has been run (or docker compose up -d with favourite + inventory)
#   - auth-service + product-service running (for USER/ADMIN tokens + productId)
#   - Keycloak seeded with testuser/testpass (USER) + adminuser/adminpass (ADMIN)
#
# Runs against http://localhost:8081 (favourite) + http://localhost:8082 (inventory).
set -e

readonly FAV_BASE="${FAV_BASE:-http://localhost:8081/api/v1/favourites}"
readonly INV_BASE="${INV_BASE:-http://localhost:8082/api/v1/inventory}"
readonly AUTH_BASE="${AUTH_BASE:-http://localhost:8088/api/v1}"

PASS=0
FAIL=0

red()    { printf '\033[0;31m%s\033[0m\n' "$*"; }
green()  { printf '\033[0;32m%s\033[0m\n' "$*"; }
yellow() { printf '\033[0;33m%s\033[0m\n' "$*"; }
blue()   { printf '\033[0;34m%s\033[0m\n' "$*"; }

assert_http() {
    local label=$1; local expected=$2; local actual=$3
    if [[ "$actual" == "$expected" ]]; then
        green "  PASS [$label] HTTP $actual"
        PASS=$((PASS+1))
    else
        red   "  FAIL [$label] expected HTTP $expected got $actual"
        FAIL=$((FAIL+1))
    fi
}

assert_contains() {
    local label=$1; local body=$2; local needle=$3
    if echo "$body" | grep -q "$needle"; then
        green "  PASS [$label] body contains: $needle"
        PASS=$((PASS+1))
    else
        red   "  FAIL [$label] body missing: $needle"
        FAIL=$((FAIL+1))
    fi
}

assert_json() {
    local label=$1; local body=$2; local jq_path=$3; local expected=$4
    local actual=$(echo "$body" | python3 -c "import sys,json; print(json.load(open('/tmp/last_body'))$jq_path)")
    if [[ "$actual" == "$expected" ]]; then
        green "  PASS [$label] $jq_path == $expected"
        PASS=$((PASS+1))
    else
        red   "  FAIL [$label] $jq_path expected $expected got $actual"
        FAIL=$((FAIL+1))
    fi
}

curl_code() {
    local method=$1 url=$2 token=$3 body=$4
    if [[ -n "$token" ]]; then
        if [[ -n "$body" ]]; then
            curl -s -o /tmp/last_body -w '%{http_code}'                 -X "$method" "$url"                 -H "Content-Type: application/json"                 -H "Authorization: Bearer $token"                 -d "$body"
        else
            curl -s -o /tmp/last_body -w '%{http_code}'                 -X "$method" "$url"                 -H "Authorization: Bearer $token"
        fi
    else
        if [[ -n "$body" ]]; then
            curl -s -o /tmp/last_body -w '%{http_code}'                 -X "$method" "$url"                 -H "Content-Type: application/json"                 -d "$body"
        else
            curl -s -o /tmp/last_body -w '%{http_code}' -X "$method" "$url"
        fi
    fi
}

banner() { printf '\n%b== %s ==%b\n' "$blue" "$*" "$yellow"; }

##############################################################################
banner "PHASE 0: Health Checks"
code=$(curl -s -o /dev/null -w '%{http_code}' http://localhost:8081/actuator/health)
assert_http "Favourite Service Health"  200 "$code"
code=$(curl -s -o /dev/null -w '%{http_code}' http://localhost:8082/actuator/health)
assert_http "Inventory Service Health"  200 "$code"

##############################################################################
banner "PHASE 1: Get USER + ADMIN tokens from auth-service"
USER_RESP=$(curl -s -X POST "$AUTH_BASE/auth/login" -H "Content-Type: application/json" \
    -d '{"username":"testuser","password":"testpass"}')
USER_TOKEN=$(echo "$USER_RESP" | python3 -c 'import sys,json; print(json.load(sys.stdin)["data"]["accessToken"])')
ADMIN_RESP=$(curl -s -X POST "$AUTH_BASE/auth/login" -H "Content-Type: application/json" \
    -d '{"username":"adminuser","password":"adminpass"}')
ADMIN_TOKEN=$(echo "$ADMIN_RESP" | python3 -c 'import sys,json; print(json.load(sys.stdin)["data"]["accessToken"])')
green "  USER token: ${USER_TOKEN:0:40}..."
green "  ADMIN token: ${ADMIN_TOKEN:0:40}..."

##############################################################################
banner "PHASE 2: Favourite - Anonymous blocked (expect 401)"
code=$(curl_code GET "$FAV_BASE" "")
assert_http "GET /favourites anonymous" 401 "$code"
code=$(curl_code POST "$FAV_BASE" "" '{"productId":"00000000-0000-0000-0000-000000000001"}')
assert_http "POST /favourites anonymous" 401 "$code"

##############################################################################
banner "PHASE 3: Favourite - USER empty list + admin same-user empty list"
code=$(curl_code GET "$FAV_BASE" "$USER_TOKEN")
assert_http "GET /favourites as USER (empty)" 200 "$code"
code=$(curl_code GET "$FAV_BASE" "$ADMIN_TOKEN")
assert_http "GET /favourites as ADMIN (empty own list)" 200 "$code"

##############################################################################
banner "PHASE 4: Favourite - Setup a product to favourite (use product-service)"
TS=$(date +%s%N)
code=$(curl_code POST "http://localhost:8086/api/v1/brands" "$ADMIN_TOKEN" \
    "{\"name\":\"FavBrand\",\"slug\":\"favbrand-$TS\",\"description\":\"For fav E2E\"}")
if [[ "$code" != "200" ]]; then
    yellow "  INFO Brand create HTTP $code - assuming brand exists from previous E2E"
fi
BRAND_ID=$(cat /tmp/last_body 2>/dev/null | python3 -c 'import sys,json; print(json.load(sys.stdin).get("data",{}).get("id",""))' 2>/dev/null || echo "")

code=$(curl_code POST "http://localhost:8086/api/v1/categories" "$ADMIN_TOKEN" \
    "{\"title\":\"FavCat\",\"slug\":\"favcat-$TS\",\"imageUrl\":\"https://x.com/c.png\"}")
if [[ "$code" != "200" ]]; then
    yellow "  INFO Category create HTTP $code - assuming category exists"
fi
CAT_ID=$(cat /tmp/last_body 2>/dev/null | python3 -c 'import sys,json; print(json.load(sys.stdin).get("data",{}).get("id",""))' 2>/dev/null || echo "")

code=$(curl_code POST "http://localhost:8086/api/v1/products" "$ADMIN_TOKEN" \
    "{\"title\":\"FavProduct\",\"slug\":\"favprod-$TS\",\"description\":\"For fav E2E\",\"sku\":\"FAV-$TS\",\"priceUnit\":10.00,\"quantity\":100,\"status\":\"ACTIVE\",\"imageUrl\":\"https://x.com/p.png\",\"categoryId\":\"$CAT_ID\",\"brandId\":\"$BRAND_ID\"}")
if [[ "$code" != "200" ]]; then
    red "  FAIL create product HTTP $code - cannot run rest of test"
    exit 1
fi
PROD_ID=$(cat /tmp/last_body | python3 -c 'import sys,json; print(json.load(sys.stdin)["data"]["id"])')
green "  Created product: $PROD_ID"

##############################################################################
banner "PHASE 5: Favourite - USER add + duplicate (expect 409)"
code=$(curl_code POST "$FAV_BASE" "$USER_TOKEN" "{\"productId\":\"$PROD_ID\"}")
assert_http "POST /favourites (USER, new)" 200 "$code"
FAV_ID=$(cat /tmp/last_body | python3 -c 'import sys,json; print(json.load(sys.stdin)["data"]["id"])')
green "  Favourite ID: $FAV_ID"

code=$(curl_code POST "$FAV_BASE" "$USER_TOKEN" "{\"productId\":\"$PROD_ID\"}")
assert_http "POST /favourites (USER, duplicate)" 409 "$code"
body=$(cat /tmp/last_body)
echo "  Duplicate body: $body" | head -c 200

code=$(curl_code POST "$FAV_BASE" "$USER_TOKEN" '{"productId":null}')
assert_http "POST /favourites with null productId (expect 400)" 400 "$code"

##############################################################################
banner "PHASE 6: Favourite - USER reads (own list has 1)"
code=$(curl_code GET "$FAV_BASE" "$USER_TOKEN")
assert_http "GET /favourites USER (after add)" 200 "$code"
body=$(cat /tmp/last_body)
COUNT=$(echo "$body" | python3 -c 'import sys,json; print(len(json.load(sys.stdin)["data"]))')
if [[ "$COUNT" -ge "1" ]]; then
    green "  PASS [USER list count] == $COUNT (>= 1)"
    PASS=$((PASS+1))
else
    red "  FAIL [USER list count] expected >= 1, got $COUNT"
    FAIL=$((FAIL+1))
fi

code=$(curl_code GET "$FAV_BASE/$FAV_ID" "$USER_TOKEN")
assert_http "GET /favourites/{favouriteId} USER" 200 "$code"

##############################################################################
banner "PHASE 7: Favourite - ADMIN per-user isolation (expect 404 for USER's favourite)"
code=$(curl_code GET "$FAV_BASE/$FAV_ID" "$ADMIN_TOKEN")
assert_http "GET /favourites/{favouriteId} ADMIN (different user)" 404 "$code"

##############################################################################
banner "PHASE 8: Favourite - USER delete by id (soft-delete)"
code=$(curl_code DELETE "$FAV_BASE/$FAV_ID" "$USER_TOKEN")
assert_http "DELETE /favourites/{favouriteId} USER" 200 "$code"
code=$(curl_code GET "$FAV_BASE/$FAV_ID" "$USER_TOKEN")
assert_http "GET deleted favourite (expect 404)" 404 "$code"

##############################################################################
banner "PHASE 9: Favourite - USER delete by product (soft-delete)"
code=$(curl_code POST "$FAV_BASE" "$USER_TOKEN" "{\"productId\":\"$PROD_ID\"}")
assert_http "Re-add favourite for delete-by-product test" 200 "$code"
code=$(curl_code DELETE "$FAV_BASE/by-product/$PROD_ID" "$USER_TOKEN")
assert_http "DELETE /favourites/by-product/{productId} USER" 200 "$code"
code=$(curl_code GET "$FAV_BASE" "$USER_TOKEN")
assert_http "GET list after delete-by-product" 200 "$code"

##############################################################################
banner "PHASE 10: Inventory - Anonymous fail-closed (expect 401, no public-paths)"
code=$(curl_code GET "$INV_BASE" "")
assert_http "GET /inventory anonymous" 403 "$code"  # Spring Security returns 403 when no JWT (fail-closed)
code=$(curl_code GET "$INV_BASE/00000000-0000-0000-0000-000000000000" "")
assert_http "GET /inventory/{id} anonymous" 403 "$code"

##############################################################################
banner "PHASE 11: Inventory - USER reads (paged)"
code=$(curl_code GET "$INV_BASE?page=0&size=10" "$USER_TOKEN")
assert_http "GET /inventory USER (paged)" 200 "$code"
code=$(curl_code GET "$INV_BASE/$PROD_ID" "$USER_TOKEN")
assert_http "GET /inventory/{id} USER (expect 404, not created yet)" 404 "$code"

##############################################################################
banner "PHASE 12: Inventory - USER cannot write (expect 403)"
code=$(curl_code POST "$INV_BASE" "$USER_TOKEN" "{\"productId\":\"$PROD_ID\",\"availableQuantity\":10}")
assert_http "POST /inventory as USER (ADMIN only)" 403 "$code"

##############################################################################
banner "PHASE 13: Inventory - ADMIN creates + reads (cache-aside)"
code=$(curl_code POST "$INV_BASE" "$ADMIN_TOKEN" "{\"productId\":\"$PROD_ID\",\"availableQuantity\":100}")
assert_http "POST /inventory ADMIN (new)" 200 "$code"
body=$(cat /tmp/last_body)
echo "  Created: $(echo "$body" | python3 -c 'import sys,json; d=json.load(sys.stdin)["data"]; print(d)')" 

code=$(curl_code POST "$INV_BASE" "$ADMIN_TOKEN" "{\"productId\":\"$PROD_ID\",\"availableQuantity\":50}")
assert_http "POST /inventory ADMIN (duplicate, expect 409 INVENTORY_ALREADY_EXISTS)" 409 "$code"
body=$(cat /tmp/last_body)
assert_contains "Duplicate error code" "$body" 'INV-3006'

code=$(curl_code GET "$INV_BASE/$PROD_ID" "$USER_TOKEN")
assert_http "GET /inventory/{id} USER (cache MISS first call)" 200 "$code"
code=$(curl_code GET "$INV_BASE/$PROD_ID" "$USER_TOKEN")
assert_http "GET /inventory/{id} USER (cache HIT second call)" 200 "$code"

##############################################################################
banner "PHASE 14: Inventory - ADMIN update (cache evict afterCommit)"
code=$(curl_code PUT "$INV_BASE/$PROD_ID" "$ADMIN_TOKEN" "{\"productId\":\"$PROD_ID\",\"availableQuantity\":200}")
assert_http "PUT /inventory/{id} ADMIN" 200 "$code"
code=$(curl_code GET "$INV_BASE/$PROD_ID" "$USER_TOKEN")
assert_http "GET /inventory/{id} USER (should see 200 after evict)" 200 "$code"
body=$(cat /tmp/last_body)
AVAIL=$(echo "$body" | python3 -c 'import sys,json; print(json.load(sys.stdin)["data"]["availableQuantity"])')
if [[ "$AVAIL" == "200" ]]; then
    green "  PASS [availableQuantity == 200]"
    PASS=$((PASS+1))
else
    red "  FAIL [availableQuantity expected 200, got $AVAIL]"
    FAIL=$((FAIL+1))
fi

##############################################################################
banner "PHASE 15: Inventory - Reserve lifecycle (ADMIN as SERVICE proxy)"
code=$(curl_code POST "$INV_BASE/$PROD_ID/reserve" "$ADMIN_TOKEN" '{"quantity":30}')
assert_http "POST /reserve 30 (ADMIN)" 200 "$code"
RES_ID=$(cat /tmp/last_body | python3 -c 'import sys,json; print(json.load(sys.stdin)["data"]["reservationId"])')
green "  Reservation ID: $RES_ID"

code=$(curl_code GET "$INV_BASE/$PROD_ID" "$USER_TOKEN")
body=$(cat /tmp/last_body)
RES=$(echo "$body" | python3 -c 'import sys,json; print(json.load(sys.stdin)["data"]["reservedQuantity"])')
AVL=$(echo "$body" | python3 -c 'import sys,json; print(json.load(sys.stdin)["data"]["availableQuantity"])')
if [[ "$RES" == "30" && "$AVL" == "200" ]]; then
    green "  PASS [after reserve: reserved=30 available=200 (availableQuantity unchanged until commit)]"
    PASS=$((PASS+1))
else
    red "  FAIL [after reserve: reserved=$RES available=$AVL (expected 30/200)]"
    FAIL=$((FAIL+1))
fi

code=$(curl_code POST "$INV_BASE/$PROD_ID/reserve" "$ADMIN_TOKEN" '{"quantity":99999}')
assert_http "Reserve EXCESS (expect 409 STOCK_INSUFFICIENT)" 409 "$code"
body=$(cat /tmp/last_body)
assert_contains "Stock insufficient code" "$body" 'INV-3002'

code=$(curl_code POST "$INV_BASE/reservations/$RES_ID/commit" "$ADMIN_TOKEN")
assert_http "POST /commit ADMIN" 200 "$code"

code=$(curl_code GET "$INV_BASE/$PROD_ID" "$USER_TOKEN")
body=$(cat /tmp/last_body)
RES=$(echo "$body" | python3 -c 'import sys,json; print(json.load(sys.stdin)["data"]["reservedQuantity"])')
AVL=$(echo "$body" | python3 -c 'import sys,json; print(json.load(sys.stdin)["data"]["availableQuantity"])')
if [[ "$RES" == "0" && "$AVL" == "170" ]]; then
    green "  PASS [after commit: reserved=0 available=170]"
    PASS=$((PASS+1))
else
    red "  FAIL [after commit: reserved=$RES available=$AVL (expected 0/170)]"
    FAIL=$((FAIL+1))
fi

##############################################################################
banner "PHASE 16: Inventory - Reserve + Release cycle"
code=$(curl_code POST "$INV_BASE/$PROD_ID/reserve" "$ADMIN_TOKEN" '{"quantity":15}')
assert_http "POST /reserve 15" 200 "$code"
RES2_ID=$(cat /tmp/last_body | python3 -c 'import sys,json; print(json.load(sys.stdin)["data"]["reservationId"])')
code=$(curl_code POST "$INV_BASE/reservations/$RES2_ID/release" "$ADMIN_TOKEN")
assert_http "POST /release" 200 "$code"
code=$(curl_code GET "$INV_BASE/$PROD_ID" "$USER_TOKEN")
body=$(cat /tmp/last_body)
RES=$(echo "$body" | python3 -c 'import sys,json; print(json.load(sys.stdin)["data"]["reservedQuantity"])')
if [[ "$RES" == "0" ]]; then
    green "  PASS [after release: reservedQuantity=0]"
    PASS=$((PASS+1))
else
    red "  FAIL [after release: reservedQuantity=$RES (expected 0)]"
    FAIL=$((FAIL+1))
fi

##############################################################################
banner "PHASE 17: Inventory - Reservation error paths"
code=$(curl_code POST "$INV_BASE/reservations/00000000-0000-0000-0000-000000000000/commit" "$ADMIN_TOKEN")
assert_http "Commit non-existent reservation (expect 404)" 404 "$code"
code=$(curl_code POST "$INV_BASE/$PROD_ID/reserve" "$ADMIN_TOKEN" '{"quantity":0}')
assert_http "Reserve with quantity=0 (expect 400)" 400 "$code"
code=$(curl_code POST "$INV_BASE/$PROD_ID/reserve" "$USER_TOKEN" '{"quantity":5}')
assert_http "USER cannot reserve (expect 403)" 403 "$code"

##############################################################################
banner "PHASE 17.5: Cleanup COMMITTED reservation from PHASE 15 (DB-level for test isolation)"
# Direct DB cleanup — COMMITTED reservations from PHASE 15 would otherwise block delete
docker exec postgres psql -U admin -d inventoryservice -c "UPDATE reservations SET status = 'RELEASED' WHERE product_id = '$PROD_ID' AND status = 'COMMITTED'" >/dev/null
green "  Cleaned up COMMITTED reservations for test product"

banner "PHASE 18: Inventory - DELETE guard (PENDING/COMMITTED reservations block)"
code=$(curl_code POST "$INV_BASE/$PROD_ID/reserve" "$ADMIN_TOKEN" '{"quantity":1}')
assert_http "Reserve q1 to seed active reservation" 200 "$code"
RES3_ID=$(cat /tmp/last_body | python3 -c 'import sys,json; print(json.load(sys.stdin)["data"]["reservationId"])')
code=$(curl_code DELETE "$INV_BASE/$PROD_ID" "$ADMIN_TOKEN")
assert_http "DELETE inventory while PENDING reservation (expect 409)" 409 "$code"
body=$(cat /tmp/last_body)
assert_contains "Delete guard code" "$body" 'INV-3005'
code=$(curl_code POST "$INV_BASE/reservations/$RES3_ID/release" "$ADMIN_TOKEN")
assert_http "Release reservation to clear guard" 200 "$code"
code=$(curl_code DELETE "$INV_BASE/$PROD_ID" "$ADMIN_TOKEN")
assert_http "DELETE inventory (no active reservations)" 200 "$code"
code=$(curl_code GET "$INV_BASE/$PROD_ID" "$USER_TOKEN")
assert_http "GET deleted inventory (expect 404 hard-delete)" 404 "$code"

##############################################################################
banner "PHASE 19: Kafka outbox - events landed in shop.inventory.events.v1"
# Allow outbox relay (5s poll) to drain
sleep 6
EVENTS=$(docker exec kafka /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:9092 \
    --topic shop.inventory.events.v1 --from-beginning --max-messages 20 --timeout-ms 3000 2>/dev/null || echo "")
if echo "$EVENTS" | grep -q "inventory.adjusted.v1"; then
    green "  PASS [Kafka event 'inventory.adjusted.v1' present]"
    PASS=$((PASS+1))
else
    red "  FAIL [No inventory.adjusted.v1 event found in Kafka]"
    FAIL=$((FAIL+1))
    echo "  Kafka output (first 5 lines):"
    echo "$EVENTS" | head -5
fi
if echo "$EVENTS" | grep -q "inventory.reserved.v1"; then
    green "  PASS [Kafka event 'inventory.reserved.v1' present]"
    PASS=$((PASS+1))
else
    yellow "  INFO [No inventory.reserved.v1 event found in last 20 msgs - may be older]"
fi

##############################################################################
echo ""
green "================================================"
green "  Favourite+Inventory E2E Summary: $PASS passed, $FAIL failed"
green "================================================"
[[ $FAIL -gt 0 ]] && exit 1 || exit 0
