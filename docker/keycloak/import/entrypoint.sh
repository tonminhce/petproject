#!/bin/sh
# Substitute the per-client secrets and the seeded user passwords from the
# environment before Keycloak reads the realm JSON. We use a portable sed loop
# instead of `envsubst` because the UBI-minimal keycloak image does not ship
# gettext-base.
#
# F-080: every placeholder below MUST exist in the realm JSON. The
# placeholders were once reverted to the literal "changeme" while this script
# kept requiring the variables, so the substitution had nothing to replace:
# the realm booted with the well-known secrets while the deployment believed
# it had rotated them.
#
# F-081: the substituted realm MUST NOT be written back into the mounted
# source directory. The source is bind-mounted from the repository, so a
# write-back persisted the rotated secrets into the developer's working tree
# (and into the next commit). The template is mounted READ-ONLY and the
# substituted copy is written to the container-local import directory that
# Keycloak actually reads.
#
# Required env vars (set by docker-compose, no defaults):
#   ORDER_SERVICE_CLIENT_SECRET, RATING_SERVICE_CLIENT_SECRET,
#   SEARCH_SERVICE_CLIENT_SECRET, PRODUCT_SERVICE_CLIENT_SECRET,
#   MEDIA_SERVICE_CLIENT_SECRET, ADMIN_SERVICE_CLIENT_SECRET,
#   KEYCLOAK_TEST_USER_PASSWORD, KEYCLOAK_ADMIN_USER_PASSWORD

set -eu

# The read-only template that ships in the repository.
src=/opt/keycloak/realm-template/ecommerce-realm.json
# The container-local directory Keycloak imports from. It must NOT be the
# bind-mounted template directory.
import_dir=/opt/keycloak/data/import
dst=$import_dir/ecommerce-realm.json
tmp=/tmp/realm-substituted.json

# Bail out loudly if any required var is missing — fail-fast beats a silent
# "changeme" boot.
for v in ORDER_SERVICE_CLIENT_SECRET RATING_SERVICE_CLIENT_SECRET SEARCH_SERVICE_CLIENT_SECRET PRODUCT_SERVICE_CLIENT_SECRET MEDIA_SERVICE_CLIENT_SECRET ADMIN_SERVICE_CLIENT_SECRET KEYCLOAK_TEST_USER_PASSWORD KEYCLOAK_ADMIN_USER_PASSWORD; do
    eval val="\${$v:-}"
    if [ -z "$val" ]; then
        echo "ERROR: $v is not set; refusing to boot Keycloak with default secrets." >&2
        exit 1
    fi
done

if [ ! -f "$src" ]; then
    echo "ERROR: realm template $src is missing; refusing to boot Keycloak without a realm." >&2
    exit 1
fi

mkdir -p "$import_dir"
cp "$src" "$tmp"

# In-place substitution of ${VAR} → $VAR for each required var. Sed delimiter is
# | because secrets may contain / or other URL chars.
for v in ORDER_SERVICE_CLIENT_SECRET RATING_SERVICE_CLIENT_SECRET SEARCH_SERVICE_CLIENT_SECRET PRODUCT_SERVICE_CLIENT_SECRET MEDIA_SERVICE_CLIENT_SECRET ADMIN_SERVICE_CLIENT_SECRET KEYCLOAK_TEST_USER_PASSWORD KEYCLOAK_ADMIN_USER_PASSWORD; do
    eval val="\${$v}"
    # Escape &, |, \ in the secret for the sed replacement.
    escaped=$(printf '%s' "$val" | sed -e 's/[&|\\]/\\&/g')
    sed -i "s|\${$v}|$escaped|g" "$tmp"
done

# Fail fast when a placeholder survived the substitution: a realm that still
# carries "${VAR}" would boot with the literal placeholder as the secret.
if grep -q '\${[A-Z_]*}' "$tmp"; then
    echo "ERROR: the substituted realm still contains an unsubstituted placeholder:" >&2
    grep -o '\${[A-Z_]*}' "$tmp" | sort -u >&2
    exit 1
fi

# Drop the substituted file where Keycloak's --import-realm looks for it.
cp "$tmp" "$dst"

exec /opt/keycloak/bin/kc.sh "$@"
