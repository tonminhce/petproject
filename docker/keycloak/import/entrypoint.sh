#!/bin/sh
# C8 fix — substitute per-client client-secrets and the test user password from
# environment variables before Keycloak reads the realm JSON. We use a portable
# sed loop instead of `envsubst` because the UBI-minimal keycloak image does
# not ship gettext-base.
#
# Required env vars (set by docker-compose, no defaults):
#   ORDER_SERVICE_CLIENT_SECRET, RATING_SERVICE_CLIENT_SECRET,
#   SEARCH_SERVICE_CLIENT_SECRET, PRODUCT_SERVICE_CLIENT_SECRET,
#   MEDIA_SERVICE_CLIENT_SECRET, KEYCLOAK_TEST_USER_PASSWORD

set -eu

src=/opt/keycloak/data/import/ecommerce-realm.json
tmp=/tmp/realm-substituted.json

# Bail out loudly if any required var is missing — fail-fast beats a silent
# "changeme" boot.
for v in ORDER_SERVICE_CLIENT_SECRET RATING_SERVICE_CLIENT_SECRET SEARCH_SERVICE_CLIENT_SECRET PRODUCT_SERVICE_CLIENT_SECRET MEDIA_SERVICE_CLIENT_SECRET KEYCLOAK_TEST_USER_PASSWORD; do
    eval val="\${$v:-}"
    if [ -z "$val" ]; then
        echo "ERROR: $v is not set; refusing to boot Keycloak with default secrets." >&2
        exit 1
    fi
done

cp "$src" "$tmp"

# In-place substitution of ${VAR} → $VAR for each required var. Sed delimiter is
# | because secrets may contain / or other URL chars.
for v in ORDER_SERVICE_CLIENT_SECRET RATING_SERVICE_CLIENT_SECRET SEARCH_SERVICE_CLIENT_SECRET PRODUCT_SERVICE_CLIENT_SECRET MEDIA_SERVICE_CLIENT_SECRET KEYCLOAK_TEST_USER_PASSWORD; do
    eval val="\${$v}"
    # Escape &, |, \ in the secret for the sed replacement.
    escaped=$(printf '%s' "$val" | sed -e 's/[&|\\]/\\&/g')
    sed -i "s|\${$v}|$escaped|g" "$tmp"
done

# Drop the substituted file next to the source so Keycloak's --import-realm
# picks it up.
cp "$tmp" /opt/keycloak/data/import/ecommerce-realm.json

exec /opt/keycloak/bin/kc.sh "$@"
