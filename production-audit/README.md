# Production Audit Harness

Executable engineering harness for the Petproject Microservices Platform production audit.

The harness owns source discovery, the source-review coverage ledger, the atomic task
registry, the comment-policy gate, the verification gates, the end-to-end environment
lifecycle and the git delivery rules. It contains no simulated success paths: every
command reads real repository state, writes machine-readable evidence, and returns an
exit code that reflects what actually happened.

## Runtime

- Python 3.11+ standard library only. No third-party packages.
- JDK 25 is selected through the Maven toolchain declared in `~/.m2/toolchains.xml`.
- Entry point: `production-audit/bin/harness`.

```bash
cd <repository root>
./production-audit/bin/harness status
```

## Layout

```text
production-audit/
├── README.md
├── bin/harness                     executable entry point
├── config/
│   ├── comment-policy.json         comment exceptions and preservation rules
│   └── suites.json                  test suite definitions
├── control/
│   ├── source-manifest.json        every discovered first-party file with hash
│   ├── source-coverage.json         per-file review state and reviewed ranges
│   └── reconciliation.json          revision reconciliation result
├── harness/
│   ├── cli/                         argument parsing and command dispatch
│   ├── config.py                    path resolution and exit codes
│   ├── dependency_graph/            internal module dependency graph and cycles
│   ├── e2e/                         docker compose lifecycle and collection discovery
│   ├── git_delivery/                branch rules, staging rules, secret scan, push
│   ├── inventory/                   source scanner and coverage ledger
│   ├── persistence/                 atomic state writes with file locking
│   ├── reconciliation/              revision drift and regression reopening
│   ├── reporting/                   status and gate reporting
│   ├── research/                    research index and version notes
│   ├── scheduler/                   dependency-ready task selection
│   ├── source_policy/               Java lexer, policy classification, safe cleaner
│   ├── task_registry/               task model, state machine, transitions
│   ├── tests/                       harness self-verification suite
│   └── verification/                gate evaluation, maven runners, suite runner
├── state/                           task, finding, verification and delivery ledgers
├── architecture/                    current and target architecture, ADRs
├── runs/<RUN_ID>/                   per-run evidence artefacts
└── reports/                         generated reports
```

Python package directories use underscores instead of the hyphens in the proposed
layout because hyphens are not valid Python identifiers and the modules must be
importable for the self-verification suite.

## Commands

| Command | Purpose | Exit codes |
| --- | --- | --- |
| `harness inventory` | Rebuild the first-party source manifest with hashes and line counts | 0 |
| `harness coverage` | Print the review coverage ledger; `--mark PATH --state STATE` records a review | 0 |
| `harness audit <topic>` | Report whether the evidence artefact for a topic exists | 0 present, 2 missing |
| `harness research refresh` | Seed the research index and report missing topics | 0 |
| `harness comments scan` | Detect prohibited comments with file and line numbers | 0 |
| `harness comments clean` | Remove prohibited comments using the language-aware lexer | 0 |
| `harness verify --suite comments` | Comment-policy gate | 0 pass, 1 fail |
| `harness verify` | Evaluate all twenty-two production-readiness gates | 0 pass, 1 fail, 2 pending or blocked |
| `harness build` | Run the Maven reactor build; `--quick` runs `validate` only | 0 pass, 1 fail |
| `harness test --suite <name>` | Run a configured test suite and interpret the exit code | 0 pass, 1 fail |
| `harness e2e setup` | Start the isolated infrastructure through docker compose | 0 ready, 2 blocked |
| `harness e2e run [--scope S]` | Execute the end-to-end collections against running services | 0 completed, 2 blocked, 1 failed |
| `harness e2e report` | Collect end-to-end artefacts | 0 |
| `harness e2e teardown` | Stop the isolated infrastructure | 0 |
| `harness tasks list\|next\|status` | Task registry queries | 0, 2 when no task is ready |
| `harness tasks add` | Register a discovered finding as an atomic task | 0 |
| `harness tasks transition` | Move a task through the state machine | 0, 1 on illegal transition |
| `harness reconcile` | Detect revision drift and reopen affected tasks | 0 stable, 2 drifted |
| `harness git status` | Branch, remotes, working tree and upstream state | 0 |
| `harness git commit-verified` | Commit an explicit path allow-list after secret scanning | 0 committed, 1 rejected |
| `harness git push-verified` | Push the current branch without force | 0 pushed, 2 blocked |
| `harness git delivery-report` | Summarise the delivery ledger | 0 |
| `harness run --max-iterations N` | Refresh state, evaluate gates, select the next task | 0 |
| `harness resume` | Continue the loop from persisted state | 0 |
| `harness status` | Full status with gates and completion predicate | 0 |
| `harness report` | Write the status reports | 0 |

Exit codes are stable: `0` success, `1` gate failure, `2` blocked or unavailable
dependency, `3` usage error. A skipped verification is never reported as a pass.

## Comment policy enforcement

Java source is never rewritten with a regular expression. `harness/source_policy/java_lexer.py`
implements a character-level state machine that distinguishes code, line comments, block
comments, string literals, text blocks and character literals. It honours escape
sequences and Java unicode escapes, so a `//` inside a string, a `/*` inside a text
block, or a `//` inside embedded SQL is never treated as a comment.

Preserved categories are declared in `config/comment-policy.json`:

- mandatory copyright and licence notices inside the header budget,
- generated-file markers such as `Generated by` and `DO NOT EDIT`,
- compiler, formatter and linter directives such as `CHECKSTYLE:OFF`, `spotless:off`,
  `eslint-disable`, `prettier-ignore`, `noinspection`, `nosec` and `noqa`,
- source-map directives such as `sourceMappingURL`,
- interpreter shebangs,
- vendored, generated and build-output paths.

Everything else is prohibited. `harness comments clean` removes prohibited comments only,
drops lines that become empty, and archives the removed text to
`architecture/comment-rationale.md` so design rationale is preserved outside the source.

## Self-verification

```bash
cd production-audit
python3 -m unittest discover -s harness/tests -t .
```

The suite covers source inventory, file hash change detection, task id validation, task
state transitions, persistent state, crash recovery, concurrent registry writes, comment
detection, safe comment cleanup, comment exception handling, Maven command execution,
test exit-code interpretation, end-to-end environment lifecycle, end-to-end result
collection, gate evaluation, git branch restrictions, git staging rules, secret detection,
commit status tracking, push failure handling, regression invalidation and the completion
predicate.

## Evidence levels

Findings are recorded with an explicit evidence level. `SOURCE_VERIFIED` means the
behaviour was read in source. `TEST_EXECUTED` means a test ran. `INTEGRATION_VERIFIED`
means a test ran against real infrastructure. `E2E_VERIFIED` means a business flow ran
against running services and durable state was inspected. `CONCURRENCY_VERIFIED` means a
concurrent scenario was executed. Unit-test results are never presented as end-to-end
evidence.
