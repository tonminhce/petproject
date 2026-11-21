<!-- gitnexus:start -->
# GitNexus — Code Intelligence

This project is indexed by GitNexus as **petproject** (19227 symbols, 42358 relationships, 300 execution flows). Use the GitNexus MCP tools to understand code, assess impact, and navigate safely.

> Index stale? Run `node .gitnexus/run.cjs analyze` from the project root — it auto-selects an available runner. No `.gitnexus/run.cjs` yet? `npx gitnexus analyze` (npm 11 crash → `npm i -g gitnexus`; #1939).

## Always Do

- **MUST run impact analysis before editing any symbol.** Before modifying a function, class, or method, run `impact({target: "symbolName", direction: "upstream"})` and report the blast radius (direct callers, affected processes, risk level) to the user.
- **MUST run `detect_changes()` before committing** to verify your changes only affect expected symbols and execution flows. For regression review, compare against the default branch: `detect_changes({scope: "compare", base_ref: "main"})`.
- **MUST warn the user** if impact analysis returns HIGH or CRITICAL risk before proceeding with edits.
- When exploring unfamiliar code, use `query({search_query: "concept"})` to find execution flows instead of grepping. It returns process-grouped results ranked by relevance.
- When you need full context on a specific symbol — callers, callees, which execution flows it participates in — use `context({name: "symbolName"})`.
- For security review, `explain({target: "fileOrSymbol"})` lists taint findings (source→sink flows; needs `analyze --pdg`).

## Never Do

- NEVER edit a function, class, or method without first running `impact` on it.
- NEVER ignore HIGH or CRITICAL risk warnings from impact analysis.
- NEVER rename symbols with find-and-replace — use `rename` which understands the call graph.
- NEVER commit changes without running `detect_changes()` to check affected scope.

## Resources

| Resource | Use for |
|----------|---------|
| `gitnexus://repo/petproject/context` | Codebase overview, check index freshness |
| `gitnexus://repo/petproject/clusters` | All functional areas |
| `gitnexus://repo/petproject/processes` | All execution flows |
| `gitnexus://repo/petproject/process/{name}` | Step-by-step execution trace |

## CLI

| Task | Read this skill file |
|------|---------------------|
| Understand architecture / "How does X work?" | `.claude/skills/gitnexus/gitnexus-exploring/SKILL.md` |
| Blast radius / "What breaks if I change X?" | `.claude/skills/gitnexus/gitnexus-impact-analysis/SKILL.md` |
| Trace bugs / "Why is X failing?" | `.claude/skills/gitnexus/gitnexus-debugging/SKILL.md` |
| Rename / extract / split / refactor | `.claude/skills/gitnexus/gitnexus-refactoring/SKILL.md` |
| Tools, resources, schema reference | `.claude/skills/gitnexus/gitnexus-guide/SKILL.md` |
| Index, status, clean, wiki CLI commands | `.claude/skills/gitnexus/gitnexus-cli/SKILL.md` |

<!-- gitnexus:end -->


## Container And Resource Hygiene

The development machine has 16 GB of RAM and 10 cores, and Docker competes with
everything else on it. These rules keep the footprint small enough to work in.

1. One container per service. The compose stack at docker-compose.yml is the only
   source of kafka, redis and postgres. Never start a second one by hand and
   never leave a Testcontainers container behind.
2. Testcontainers are ephemeral. After a test run, and especially after an
   interrupted one, run: docker container prune -f. A run that is killed leaves
   its containers behind and they keep holding memory.
3. The exporters are opt in. postgres-exporter and redis-exporter live in the
   observability profile and no alert rule references them. Do not start them
   unless the audit is specifically about exporter metrics.
4. Never prune volumes. docker volume prune deletes the database data of every
   service whose container happens to be stopped at that moment.
5. Prune the safe things after a build: docker image prune -f for the dangling
   layers and docker builder prune -f for the build cache. Do not run
   docker image prune -a: it deletes the tagged images that the compose stack and
   the integration tests need.
6. One heavy thing at a time. Do not run a maven build while a load profile or a
   suite is running, and do not run two load profiles at once. The build needs the
   memory that the containers hold, which is why the verification pass stops the
   compose services before it builds.
7. Keep the secret hygiene: credentials live in the git-ignored .env and are
   loaded with set -a followed by sourcing .env. Never print one in full and
   never write one into a tracked file.


## Parallel Work With Subagents

Up to five subagents may run at the same time, and one of them must always be
reserved for an independent review of the result before it is declared done.

1. Split the work by module or by gate, never by file, so two agents never edit
   the same file.
2. State the write scope of every agent in its prompt: the exact directories it may
   edit and nothing else.
3. Tell every agent explicitly not to run a maven build and not to recreate
   containers unless its task is the one that owns them, because a build and a load
   profile cannot share the machine with each other.
4. Tell every agent explicitly not to commit. The lead commits once, after the
   review, so the evidence and the commit describe the same revision.
5. Require each agent to run the impact analysis of every symbol it changes and to
   report the callers and the risk.
6. Reserve the fifth agent for the independent review. It must be read only, it
   must write its verdict as the first line of its report, and a verdict of FAIL
   must be answered by fixes and a new review rather than by argument.

## Tool-Call Hygiene

The harness parses every tool call as JSON, so a raw double quote, a stray backslash
or a newline inside a `command` or `pattern` value fails the whole turn. The full
rules live in the user-global `~/.dsh/AGENTS.md`; the short version: prefer the
native `read` / `grep` / `glob` / `edit` tools, never emit a double quote, a
backslash or a newline inside a `bash` command, keep each command to one simple
step, and on a JSON tool-call error resend the identical payload.
