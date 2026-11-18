#!/usr/bin/env bash

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