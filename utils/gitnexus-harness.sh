#!/usr/bin/env bash
# GitNexus quick harness — terminal-friendly wrapper around the GitNexus CLI.
#
# Usage:
#   g <subcmd> [args...]
#
# Subcommands (auto-resolves -r so you don't have to):
#   status / st           Index freshness for the CWD repo
#   fresh / analyze       Re-index the CWD repo (writes .gitnexus/)
#   list / ls             List all indexed repos
#   flow <concept>        Query execution flows (processes) for a concept
#   symbol <name>         Find a symbol by substring across the index (path:line)
#   here <name>           360° on a symbol — callers/callees/processes
#   blast <name>          Blast radius — what breaks if you change a symbol
#   changed               Map current git diff → affected symbols + flows
#   review                show this file
#
# Flags after the subcommand are passed through to gitnexus.
# Examples:
#   g flow "user registration"
#   g symbol JwtDecoder
#   g here LazyJwtDecoder
#   g blast AdminIpAllowlistFilter -d upstream --depth 2
#   g changed

set -euo pipefail

REPO_NAME="${GITNEXUS_REPO:-}"
if [[ -z "$REPO_NAME" && -f .gitnexus/meta.json ]]; then
  REPO_NAME=$(node -e 'try { const m = JSON.parse(require("fs").readFileSync(".gitnexus/meta.json")); const r = m.remoteUrl ? m.remoteUrl.split("/").pop().replace(/\.git$/, "") : ""; console.log(r); } catch(e){}' 2>/dev/null || true)
fi
if [[ -z "$REPO_NAME" ]]; then
  REPO_NAME="$(basename "$(pwd)")"
fi

usage() {
  sed -n '2,21p' "$0"
}

cmd="${1:-help}"; shift || true

# ponytail: this is the routing table. Each branch is one line — the work happens
# inside gitnexus, not here. The harness adds nothing of its own.
case "$cmd" in
  status|st)
    gitnexus status "$@"
    ;;
  fresh|reindex|analyze)
    if [[ -x .gitnexus/run.cjs ]]; then
      node .gitnexus/run.cjs analyze "$@"
    else
      gitnexus analyze "$@"
    fi
    ;;
  list|ls)
    gitnexus list "$@"
    ;;
  flow|q|query)
    gitnexus query -r "$REPO_NAME" "$@"
    ;;
  symbol|sym|find)
    # Symbol search: BM25 over names. Returns ranked list of matches.
    gitnexus query -r "$REPO_NAME" "$@" --json 2>/dev/null \
      || gitnexus query -r "$REPO_NAME" "$@"
    ;;
  here|ctx|context)
    name="${1:?usage: g here <symbol> [-f path] [--content]}"
    shift
    gitnexus context -r "$REPO_NAME" "$name" "$@"
    ;;
  blast|imp|impact)
    target="${1:?usage: g blast <symbol> [-d upstream|downstream] [--depth N]}"
    shift
    gitnexus impact -r "$REPO_NAME" "$target" "$@"
    ;;
  changed|dc|detect-changes|diff)
    gitnexus detect-changes -r "$REPO_NAME" "$@"
    ;;
  help|--help|-h|"")
    usage
    ;;
  *)
    echo "Unknown subcommand: $cmd" >&2
    usage >&2
    exit 2
    ;;
esac