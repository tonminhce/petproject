#!/bin/sh

set -eu

src=/opt/keycloak/realm-template/ecommerce-realm.json
import_dir=/opt/keycloak/data/import
dst=$import_dir/ecommerce-realm.json
tmp=/tmp/realm-substituted.json

for v in ORDER_SERVICE_CLIENT_SECRET RATING_SERVICE_CLIENT_SECRET SEARCH_SERVICE_CLIENT_SECRET PRODUCT_SERVICE_CLIENT_SECRET MEDIA_SERVICE_CLIENT_SECRET ADMIN_SERVICE_CLIENT_SECRET KEYCLOAK_TEST_USER_PASSWORD KEYCLOAK_ADMIN_USER_PASSWORD; do
    eval val="\${$v:-}"
    if [ -z "$val" ]; then
        echo "ERROR: $v is not set; refusing to boot Keycloak with default secrets." >&2
        exit 1
    fi
    case "$(printf '%s' "$val" | tr '[:upper:]' '[:lower:]')" in
        change_me|change-me|changeme|change_in_prod|generate-me|generate_me|placeholder|xxx)
            echo "ERROR: $v still carries a placeholder value; refusing to boot Keycloak with default secrets." >&2
            exit 1
            ;;
    esac
done

if [ ! -f "$src" ]; then
    echo "ERROR: realm template $src is missing; refusing to boot Keycloak without a realm." >&2
    exit 1
fi

mkdir -p "$import_dir"
cp "$src" "$tmp"

for v in ORDER_SERVICE_CLIENT_SECRET RATING_SERVICE_CLIENT_SECRET SEARCH_SERVICE_CLIENT_SECRET PRODUCT_SERVICE_CLIENT_SECRET MEDIA_SERVICE_CLIENT_SECRET ADMIN_SERVICE_CLIENT_SECRET KEYCLOAK_TEST_USER_PASSWORD KEYCLOAK_ADMIN_USER_PASSWORD; do
    eval val="\${$v}"
    escaped=$(printf '%s' "$val" | sed -e 's/[&|\\]/\\&/g')
    sed -i "s|\${$v}|$escaped|g" "$tmp"
done

if grep -q '\${[A-Z_]*}' "$tmp"; then
    echo "ERROR: the substituted realm still contains an unsubstituted placeholder:" >&2
    grep -o '\${[A-Z_]*}' "$tmp" | sort -u >&2
    exit 1
fi

cp "$tmp" "$dst"

exec /opt/keycloak/bin/kc.sh "$@"
