#!/usr/bin/env bash
# GitNexus quick harness — short aliases to query the code knowledge graph
# from the terminal. Source this file or call it with `g <subcmd>`.
#
# Usage:
#   ./utils/gitnexus-harness.sh q "user login flow"           # query
#   ./utils/gitnexus-harness.sh ctx "JwtDecoder"              # 360° on a symbol
#   ./utils/gitnexus-harness.sh imp "JwtDecoder" -d upstream  # blast radius
#   ./utils/gitnexus-harness.sh dc                            # detect-changes
#   ./utils/gitnexus-harness.sh ls                            # list repos
#   ./utils/gitnexus-harness.sh fresh                         # re-analyze
#   ./utils/gitnexus-harness.sh status                        # index status
#
# Tip: add `alias g='./utils/gitnexus-harness.sh'` to ~/.bashrc.

set -euo pipefail

# ponytail: this exists
# Auto-resolve repo name from the CWD so callers don't pass -r every time.
REPO_NAME="$(basename "$(pwd)")"

cmd="${1:-help}"; shift || true

case "$cmd" in
  q|query)
    gitnexus query -r "$REPO_NAME" "$@"
    ;;
  ctx|context)
    name="${1:?usage: g ctx <symbol> [-f path] [--content]}"
    shift
    gitnexus context -r "$REPO_NAME" "$name" "$@"
    ;;
  imp|impact)
    target="${1:?usage: g imp <symbol> [-d upstream|downstream] [--depth N]}"
    shift
    gitnexus impact -r "$REPO_NAME" "$target" "$@"
    ;;
  dc|detect|detect-changes)
    gitnexus detect-changes -r "$REPO_NAME" "$@"
    ;;
  ls|list)
    gitnexus list "$@"
    ;;
  fresh|reindex|analyze)
    if [[ -x .gitnexus/run.cjs ]]; then
      node .gitnexus/run.cjs analyze "$@"
    else
      gitnexus analyze "$@"
    fi
    ;;
  status|st)
    gitnexus status "$@"
    ;;
  help|--help|-h|"")
    sed -n '2,16p' "$0"
    ;;
  *)
    echo "Unknown subcommand: $cmd" >&2
    exit 2
    ;;
esac