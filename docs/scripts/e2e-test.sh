#!/usr/bin/env bash
# e2e-test.sh — End-to-end test for auth-service + product-service on Docker
set -e

readonly AUTH_BASE="${AUTH_BASE:-http://localhost:8088/api/v1}"
readonly PROD_BASE="${PROD_BASE:-http://localhost:8086/api/v1}"

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

curl_code() {
    local method=$1 url=$2 token=$3 body=$4
    if [[ -n "$token" ]]; then
        if [[ -n "$body" ]]; then
            curl -s -o /tmp/last_body -w '%{http_code}' \
                -X "$method" "$url" \
                -H "Content-Type: application/json" \
                -H "Authorization: Bearer $token" \
                -d "$body"
        else
            curl -s -o /tmp/last_body -w '%{http_code}' \
                -X "$method" "$url" \
                -H "Authorization: Bearer $token"
        fi
    else
        if [[ -n "$body" ]]; then
            curl -s -o /tmp/last_body -w '%{http_code}' \
                -X "$method" "$url" \
                -H "Content-Type: application/json" \
                -d "$body"
        else
            curl -s -o /tmp/last_body -w '%{http_code}' -X "$method" "$url"
        fi
    fi
}

banner() { printf '\n%b== %s ==%b\n' "$blue" "$*" "$yellow"; }

# Resolve a JSON path from /tmp/last_body
json_get() { python3 -c "import sys,json; print(json.load(open('/tmp/last_body'))$1)"; }

##############################################################################
banner "PHASE 1: Health Checks"
code=$(curl -s -o /dev/null -w '%{http_code}' "http://localhost:8088/actuator/health")
assert_http "Auth Service Health"   200 "$code"
code=$(curl -s -o /dev/null -w '%{http_code}' "http://localhost:8086/actuator/health")
assert_http "Product Service Health" 200 "$code"

##############################################################################
banner "PHASE 2: Auth login + wrong-password (KEYCLOAK-SDK-001: wrong password returns 500 instead of 401 - KNOWN BUG)"
USER_RESP=$(curl -s -X POST "$AUTH_BASE/auth/login" -H "Content-Type: application/json" \
    -d '{"username":"testuser","password":"testpass"}')
USER_TOKEN=$(echo "$USER_RESP" | python3 -c 'import sys,json; print(json.load(sys.stdin)["data"]["accessToken"])')
REFRESH_TOKEN=$(echo "$USER_RESP" | python3 -c 'import sys,json; print(json.load(sys.stdin)["data"]["refreshToken"])')
green "  USER token: ${USER_TOKEN:0:40}..."
green "  REFRESH:    ${REFRESH_TOKEN:0:40}..."

ADMIN_RESP=$(curl -s -X POST "$AUTH_BASE/auth/login" -H "Content-Type: application/json" \
    -d '{"username":"adminuser","password":"adminpass"}')
ADMIN_TOKEN=$(echo "$ADMIN_RESP" | python3 -c 'import sys,json; print(json.load(sys.stdin)["data"]["accessToken"])')
green "  ADMIN token: ${ADMIN_TOKEN:0:40}..."

# Bug: KeycloakClientException for 401 not mapped — falls through to generic 500
code=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$AUTH_BASE/auth/login" \
    -H "Content-Type: application/json" \
    -d '{"username":"testuser","password":"WRONG"}')
if [[ "$code" == "401" ]]; then
    green "  PASS [Login wrong password] HTTP 401"
    PASS=$((PASS+1))
else
    yellow "  INFO [Login wrong password] HTTP $code (known bug: KeycloakClientException not mapped to 401 in ApiExceptionHandler)"
    FAIL=$((FAIL+1))
fi

##############################################################################
banner "PHASE 3: Auth self-service"
code=$(curl_code GET "$AUTH_BASE/users/me" "$USER_TOKEN")
# testuser exists in Keycloak but not necessarily in local DB shadow table.
# The local DB only has users that went through /api/v1/auth/sign-up.
# So 404 here is EXPECTED. To test 200, register first.
if [[ "$code" == "200" || "$code" == "404" ]]; then
    if [[ "$code" == "200" ]]; then
        green "  PASS [GET /users/me] HTTP 200 (testuser in local DB)"
    else
        yellow "  INFO [GET /users/me] HTTP 404 - testuser only in Keycloak, not in local DB shadow table"
        yellow "         This is expected: testuser was seeded into Keycloak but never registered through this service"
    fi
    PASS=$((PASS+1))
else
    red "  FAIL [GET /users/me] HTTP $code"
    FAIL=$((FAIL+1))
fi

code=$(curl_code GET "$AUTH_BASE/users/00000000-0000-0000-0000-000000000000" "$USER_TOKEN")
assert_http "GET /users/{id} with USER (expect 403)" 403 "$code"

code=$(curl_code GET "$AUTH_BASE/users" "$ADMIN_TOKEN")
assert_http "GET /users (admin list)" 200 "$code"

code=$(curl_code GET "$AUTH_BASE/users/me")
assert_http "Anonymous /users/me (expect 401)" 401 "$code"

##############################################################################
banner "PHASE 4: Register a NEW user via /api/v1/auth/sign-up (full saga: Keycloak + local DB)"
TS=$(date +%s)
NEW_USER="newuser-$TS"
code=$(curl_code POST "$AUTH_BASE/auth/sign-up" "" \
    "{\"fullName\":\"New User\",\"username\":\"$NEW_USER\",\"password\":\"Newpass123\",\"email\":\"$NEW_USER@example.com\",\"gender\":\"male\",\"phone\":\"0901234567\",\"roles\":[\"USER\"]}")
if [[ "$code" == "200" ]]; then
    green "  PASS [Sign-up new user] HTTP 200 — user created in Keycloak + local DB"
    PASS=$((PASS+1))
    
    # Now login with the new user
    NEW_RESP=$(curl -s -X POST "$AUTH_BASE/auth/login" -H "Content-Type: application/json" \
        -d "{\"username\":\"$NEW_USER\",\"password\":\"Newpass123\"}")
    NEW_TOKEN=$(echo "$NEW_RESP" | python3 -c 'import sys,json; print(json.load(sys.stdin)["data"]["accessToken"])')
    
    code=$(curl_code GET "$AUTH_BASE/users/me" "$NEW_TOKEN")
    assert_http "GET /users/me (newly registered)" 200 "$code"
    assert_contains "GET /users/me has correct username" "$(cat /tmp/last_body)" "$NEW_USER"
else
    yellow "  INFO [Sign-up new user] HTTP $code - testing with existing users only"
    FAIL=$((FAIL+1))
fi

##############################################################################
banner "PHASE 5: Product anonymous reads (public paths via SecurityProperties)"
code=$(curl_code GET "$PROD_BASE/products?page=0&size=20")
assert_http "List products anonymous" 200 "$code"
code=$(curl_code GET "$PROD_BASE/categories")
assert_http "List categories anonymous" 200 "$code"
code=$(curl_code GET "$PROD_BASE/categories/tree")
assert_http "Get category tree anonymous" 200 "$code"
code=$(curl_code GET "$PROD_BASE/brands?page=0&size=10")
assert_http "List brands anonymous" 200 "$code"
code=$(curl_code GET "$PROD_BASE/products/00000000-0000-0000-0000-000000000000")
assert_http "GET non-existent product (expect 404)" 404 "$code"

##############################################################################
banner "PHASE 6: Admin creates catalog (writes to outbox_events)"
TS=$(date +%s%N)
code=$(curl_code POST "$PROD_BASE/brands" "$ADMIN_TOKEN" \
    "{\"name\":\"Apple\",\"slug\":\"apple-$TS\",\"description\":\"Premium electronics\"}")
assert_http "Create brand (ADMIN)" 200 "$code"
BRAND_ID=$(cat /tmp/last_body | python3 -c 'import sys,json; print(json.load(sys.stdin)["data"]["id"])')
green "  Brand ID: $BRAND_ID"

code=$(curl_code POST "$PROD_BASE/categories" "$ADMIN_TOKEN" \
    "{\"title\":\"Smartphones\",\"slug\":\"smartphones-$TS\",\"imageUrl\":\"https://x.com/c.png\"}")
assert_http "Create category (ADMIN)" 200 "$code"
CAT_ID=$(cat /tmp/last_body | python3 -c 'import sys,json; print(json.load(sys.stdin)["data"]["id"])')
green "  Category ID: $CAT_ID"

code=$(curl_code POST "$PROD_BASE/products" "$ADMIN_TOKEN" \
    "{\"title\":\"iPhone 15 Pro\",\"slug\":\"iphone-15-pro-$TS\",\"description\":\"Flagship phone\",\"sku\":\"IP15-$TS\",\"priceUnit\":999.00,\"quantity\":50,\"status\":\"ACTIVE\",\"imageUrl\":\"https://x.com/p.png\",\"weight\":0.187,\"dimensions\":\"146x70x8 mm\",\"categoryId\":\"$CAT_ID\",\"brandId\":\"$BRAND_ID\"}")
assert_http "Create product (ADMIN)" 200 "$code"
PROD_ID=$(cat /tmp/last_body | python3 -c 'import sys,json; print(json.load(sys.stdin)["data"]["id"])')
PROD_SLUG=$(cat /tmp/last_body | python3 -c 'import sys,json; print(json.load(sys.stdin)["data"]["slug"])')
green "  Product ID:   $PROD_ID"
green "  Product Slug: $PROD_SLUG"

code=$(curl_code POST "$PROD_BASE/products" "$USER_TOKEN" \
    "{\"title\":\"Hack\",\"slug\":\"hack-$TS\",\"sku\":\"HACK-$TS\",\"priceUnit\":1.00,\"quantity\":1,\"status\":\"ACTIVE\"}")
assert_http "Create product with USER (expect 403)" 403 "$code"

code=$(curl_code POST "$PROD_BASE/products" "$ADMIN_TOKEN" \
    "{\"title\":\"Dup\",\"slug\":\"$PROD_SLUG\",\"sku\":\"DUP-$TS\",\"priceUnit\":1.00,\"quantity\":1,\"status\":\"ACTIVE\"}")
assert_http "Duplicate slug (expect 409)" 409 "$code"
assert_contains "Duplicate slug error code" "$(cat /tmp/last_body)" 'PRD-2004'

code=$(curl_code POST "$PROD_BASE/products" "$ADMIN_TOKEN" \
    "{\"title\":\"\",\"slug\":\"\",\"sku\":\"\",\"priceUnit\":-1,\"quantity\":-1}")
assert_http "Invalid body (expect 400)" 400 "$code"
assert_contains "Validation error code" "$(cat /tmp/last_body)" 'ERR-0422-V'

##############################################################################
banner "PHASE 7: Product reads (Spring Cache populates Redis)"
code=$(curl_code GET "$PROD_BASE/products/$PROD_ID")
assert_http "Get product by ID (cache MISS first call)" 200 "$code"

code=$(curl_code GET "$PROD_BASE/products/slug/$PROD_SLUG")
assert_http "Get product by slug" 200 "$code"

code=$(curl_code GET "$PROD_BASE/products/$PROD_ID")
assert_http "Get product again (cache HIT)" 200 "$code"

code=$(curl_code GET "$PROD_BASE/products?categoryId=$CAT_ID&page=0&size=5")
assert_http "List filtered by category" 200 "$code"

code=$(curl_code GET "$PROD_BASE/products?brandId=$BRAND_ID&status=ACTIVE&page=0&size=5")
assert_http "List filtered by brand+status" 200 "$code"

##############################################################################
banner "PHASE 8: Product update (cache eviction via @CacheEvict allEntries=true)"
code=$(curl_code PUT "$PROD_BASE/products/$PROD_ID" "$ADMIN_TOKEN" \
    '{"priceUnit":1099.00,"quantity":45,"description":"Updated - new camera"}')
assert_http "Update product (ADMIN)" 200 "$code"

body=$(cat /tmp/last_body)
NEW_PRICE=$(echo "$body" | python3 -c 'import sys,json; print(json.load(sys.stdin)["data"]["priceUnit"])')
green "  New price: $NEW_PRICE (was 999.00)"

code=$(curl_code GET "$PROD_BASE/products/$PROD_ID")
assert_http "Get updated (cache repopulated)" 200 "$code"

##############################################################################
banner "PHASE 9: Product soft-delete (outbox event ProductDeleted to Kafka)"
code=$(curl_code DELETE "$PROD_BASE/products/$PROD_ID" "$ADMIN_TOKEN")
assert_http "Soft-delete product" 200 "$code"

code=$(curl_code GET "$PROD_BASE/products/$PROD_ID")
assert_http "Get deleted (expect 404, soft-delete filter)" 404 "$code"

##############################################################################
banner "PHASE 10: Refresh token + Logout"
code=$(curl_code POST "$AUTH_BASE/auth/refresh" "" "{\"refreshToken\":\"$REFRESH_TOKEN\"}")
assert_http "Refresh token" 200 "$code"

code=$(curl_code POST "$AUTH_BASE/auth/logout" "" "{\"refreshToken\":\"$REFRESH_TOKEN\"}")
assert_http "Logout (revoke refresh)" 200 "$code"

##############################################################################
echo ""
green "================================================"
green "  E2E Summary: $PASS passed, $FAIL failed"
green "================================================"
[[ $FAIL -gt 0 ]] && exit 1 || exit 0
