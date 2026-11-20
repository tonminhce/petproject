#!/usr/bin/env sh
set -eu

token="${TELEGRAM_BOT_TOKEN:-}"
chat_id="${TELEGRAM_CI_CHAT_ID:-${TELEGRAM_CHAT_ID:-}}"

if [ -z "${token}" ] || [ -z "${chat_id}" ]; then
  echo "telegram notification failed: TELEGRAM_BOT_TOKEN or TELEGRAM_CHAT_ID is not set, so the CI failure was not delivered" >&2
  exit 1
fi

if ! printf '%s' "${token}" | grep -Eq '^[0-9]{6,12}:[A-Za-z0-9_-]{20,}$'; then
  echo "telegram notification failed: the bot token does not match the expected format, so the CI failure was not delivered" >&2
  exit 1
fi

if ! printf '%s' "${chat_id}" | grep -Eq '^-?[0-9]{1,20}$'; then
  echo "telegram notification failed: the chat identifier is not a valid Telegram chat identifier, so the CI failure was not delivered" >&2
  exit 1
fi

repository="${GITHUB_REPOSITORY:-petproject/petproject}"
environment="${ALERT_ENVIRONMENT:-ci}"
branch="${GITHUB_REF_NAME:-unknown}"
commit_sha="${GITHUB_SHA:-unknown}"
workflow="${GITHUB_WORKFLOW:-unknown}"
job="${GITHUB_JOB:-unknown}"
stage="${FAILED_STAGE:-${job}}"
category="${FAILURE_CATEGORY:-CI_FAILURE}"
run_id="${GITHUB_RUN_ID:-unknown}"
server_url="${GITHUB_SERVER_URL:-https://github.com}"
attempt="${GITHUB_RUN_ATTEMPT:-1}"

timestamp="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
run_url="${server_url}/${repository}/actions/runs/${run_id}"
logs_url="${run_url}#attempt/${attempt}"

message="<b>CI FAILURE</b>
<b>Severity:</b> Critical
<b>Environment:</b> ${environment}
<b>Project:</b> ${repository}
<b>Branch:</b> ${branch}
<b>Commit SHA:</b> ${commit_sha}
<b>Workflow:</b> ${workflow}
<b>Job:</b> ${job}
<b>Failed stage:</b> ${stage}
<b>Failure category:</b> ${category}
<b>Timestamp:</b> ${timestamp}
<b>Task ID:</b> ${run_id}
<b>CI run:</b> ${run_url}
<b>Logs:</b> ${logs_url}
<b>Status:</b> firing"

response="$(curl --silent --show-error --fail-with-body \
  --request POST \
  --data-urlencode "chat_id=${chat_id}" \
  --data-urlencode "text=${message}" \
  --data-urlencode "parse_mode=HTML" \
  "https://api.telegram.org/bot${token}/sendMessage")" || {
  echo "telegram notification failed: the Telegram API rejected or could not serve the request" >&2
  exit 1
}

if printf "%s" "${response}" | grep -q "\"ok\":true"; then
  echo "telegram notification delivered to the configured chat"
  exit 0
fi

echo "telegram notification was not accepted by the API" >&2
exit 1
