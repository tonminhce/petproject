---
name: gitnexus-harness
description: "Use when the user wants to query, search, or navigate the codebase with GitNexus from the terminal. The `g` wrapper handles `flow`/`symbol`/`here`/`blast`/`changed`/`fresh`/`status`. Use MCP gitnexus tools when paginating large results or when you need flags the CLI doesn't expose (`--kind`, `api_impact`, `route_map`, `shape_check`)."
---

# gitnexus harness — when and how

The wrapper at `utils/gitnexus-harness.sh` shortens the most common look-ups. Use it instead of raw `gitnexus ...` for one-shots.

> Index stale? Run `g fresh` first.

## Quick aliases (from project root)

| Alias      | What it does                                            | Equivalent to                              |
| ---------- | ------------------------------------------------------- | ------------------------------------------ |
| `g q ...`  | Query execution flows for a concept                     | `gitnexus query -r <repo> <concept>`       |
| `g ctx X`  | 360° view of a symbol — callers, callees, processes     | `gitnexus context -r <repo> X`             |
| `g imp X`  | Blast radius — what breaks if X changes                 | `gitnexus impact -r <repo> X -d upstream`  |
| `g dc`     | Map current git diff to affected symbols + flows        | `gitnexus detect-changes -r <repo>`        |
| `g ls`     | List all indexed repos                                  | `gitnexus list`                            |
| `g fresh`  | Re-analyze the index                                    | `node .gitnexus/run.cjs analyze`           |
| `g status` | Check index freshness                                   | `gitnexus status`                          |

## Picking CLI vs MCP

- **CLI (the harness):** fastest for one-off lookups from the terminal — `g imp AdminIpAllowlistFilter` → instant blast radius. No tool-call overhead.
- **MCP (`mcp__gitnexus__*`):** required when the agent is in plan mode, when paginating large results (`limit`/`offset` on `list_repos`), or when you need flags the CLI doesn't expose (`--kind`, `--relationTypes`, `--crossDepth`, `--api_impact` route pre-check).
- **Rule of thumb:** if the agent is the consumer and the result feeds back into tool calls, prefer the CLI for one-shots and MCP when pagination/schema matters.

## When to use which subcommand

| Goal                                                | Subcommand                                |
| --------------------------------------------------- | ----------------------------------------- |
| "How does X work?" / "Find the auth flow"           | `g q "<concept>"` → `g ctx <symbol>`      |
| "What breaks if I change X?"                        | `g imp X -d upstream`                     |
| "What depends on X?" / "Reverse blast radius"       | `g imp X -d upstream`                     |
| "What does X call?"                                 | `g imp X -d downstream`                   |
| "Just before I commit, what did my diff touch?"     | `g dc`                                    |
| "What did I change vs main?"                        | `g dc --scope compare --base_ref main`    |
| "Find every Routes/Channels cross-link"             | MCP `route_map` / `shape_check` / `api_impact` |
| "Symbol of a file path disambiguates common names"  | `g ctx X -f <relative/path>`              |
| "Search by camelCase keyword (e.g. `webhook`)"      | `g q webhook`                             |

## Conventions in this project

- Repo name is auto-resolved from `basename $(pwd)`; `-r` is pre-filled.
- The harness auto-selects between the project runner (`.gitnexus/run.cjs`) and the global `gitnexus` binary — no need to choose.
- For multi-repo workspaces, override with `-r <name>` (run `g ls` to see names).

## Common recipe: edit-then-commit safely

```bash
# 1) understand what you're about to touch
g ctx <SymbolYoureChanging>

# 2) blast radius
g imp <SymbolYoureChanging> -d upstream --depth 2

# 3) edit, then verify
g dc                       # shows what your diff actually affected
g fresh                    # refresh the index
```

## Troubleshooting

- **`Multiple repositories indexed`** — fixed in this harness by auto-passing `-r $(basename $PWD)`. Override with `-r <name>` for multi-repo workspaces.
- **`Symbol not found`** — disambiguate by file path: `g ctx <name> -f <relative/path>` or use the qualified name from a previous `g ctx`/`g q` result.
- **Stale results after a commit** — the PostToolUse hook reminds you; run `g fresh` to re-index.