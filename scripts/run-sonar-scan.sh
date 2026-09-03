#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

ENV_FILE="$REPO_ROOT/.env.sonar"
if [ -f "$ENV_FILE" ]; then
    # shellcheck disable=SC1090
    source "$ENV_FILE"
fi

export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-25-openjdk}"
SONAR_HOST_URL="${SONAR_HOST_URL:-http://localhost:9000}"
SONAR_PROJECT_KEY="${SONAR_PROJECT_KEY:-petproject}"
SONAR_TOKEN="${SONAR_TOKEN:-}"

if [ -z "$SONAR_TOKEN" ]; then
    echo "ERROR: SONAR_TOKEN is not set. Run ./scripts/setup-sonar-project.sh first or provide SONAR_TOKEN in .env.sonar."
    exit 1
fi

echo "==> Running Maven verify and SonarQube scan for project '$SONAR_PROJECT_KEY' against $SONAR_HOST_URL..."

"$REPO_ROOT/mvnw" -B -T1C clean verify sonar:sonar \
    -Dsonar.host.url="$SONAR_HOST_URL" \
    -Dsonar.projectKey="$SONAR_PROJECT_KEY" \
    -Dsonar.token="$SONAR_TOKEN" \
    -Dsonar.qualitygate.wait=true \
    -Dsonar.qualitygate.timeout=300
