# Agent Reliability Notes

The X server implementation loop must not run unbounded agent waits. When a run appears stuck, diagnose first, then restart.

## Current Failure Mode

Recent stuck runs were not JVM deadlocks in the X server. The local checks showed:

- no active X server test JVM under `jps`;
- no active `runs/*/pid.txt` agent jobs;
- recent Codex `run-agent.sh` review runs ended with `EXIT_CODE=143`;
- those Codex logs were waiting inside nested collaboration/subagent calls.

The common trigger was review quorum enforcement inside a `codex exec` run. The nested subagent wait is invisible to `run-agent.sh`, so the outer runner only sees a child process that keeps running until manually terminated.

The 2026-06-30 recurrence had a second trigger: built-in subagent lifecycle tools can also block outside the shell timeout model. A bounded `wait_agent` on an old subagent returned no status, but a later `close_agent` call for that same non-responsive agent did not return. Because it was issued inside a parallel tool wrapper, the whole root turn stayed blocked even though the other completed agents closed successfully.

A later 2026-06-30 stall was a different orchestration failure, not an X server hang:

- `jps -lm` showed no active X server test JVM;
- a `run-agent.sh claude ...` child had zero stdout/stderr bytes for multiple minutes;
- `sample <pid>` showed the Claude CLI idle in its event loop / network wait;
- `pid.txt` pointed at a bash subshell instead of the real agent process, which weakened timeout diagnostics and termination.

`run-agent.sh` now `exec`s the agent from the run-directory child process, so `PID=` is the real agent PID. It also writes `heartbeat.txt` while the agent runs and emits one early diagnostics file after a fully silent period. Use `RUN_AGENT_NO_OUTPUT_TIMEOUT_SECONDS` only when a job is expected to print promptly; many `claude -p --output-format text` runs legitimately stay silent until their final answer.

The latest repeat of this pattern was caused by setting `RUN_AGENT_NO_OUTPUT_TIMEOUT_SECONDS=300` on a long `claude -p --output-format text` implementation/review prompt. The runner killed the agent after five silent minutes even though text-mode Claude commonly writes no stdout/stderr until completion. The runner now keeps the wall-clock timeout and silence diagnostics, but disables no-output termination for Claude text-mode runs unless `RUN_AGENT_CLAUDE_ALLOW_TEXT_NO_OUTPUT_TIMEOUT=1` is set.

The 2026-06-30 20:39Z recurrence was another orchestration stall, not an X server hang:

- the heavyweight `IntellijCommunitySmokeTest` passed immediately before the agent stall;
- `jps -lm` showed no active X server test JVM after the smoke finished;
- `run-agent.sh claude ...` stayed at zero stdout/stderr bytes past the 180-second diagnostics point;
- diagnostics showed the Claude process had spawned an MCP Steroid stdio child whose Java thread dump was idle in `McpStdioServer.readChunk` waiting on stdin;
- the scout was terminated manually with `EXIT_CODE=143` after diagnostics were captured.

Root cause: read-only run-agent research prompts inherited "use MCP Steroid where possible" guidance and could enter an MCP/stdin wait that is invisible as useful progress to the outer runner. For run-agent scouts and reviews, prefer shell/read-only source searches (`rg`, `sed`, `git`, bounded Gradle only when requested). Use MCP Steroid from a run-agent only when the prompt explicitly needs IDE semantic APIs, and then keep the wall-clock timeout plus no-output diagnostics enabled.

`run-agent.sh` now prepends a short reliability override to copied prompts when this file is present. That override supersedes older broad "prefer MCP Steroid" role prompts for run-agent work, while still allowing explicit IDE-semantic tasks to opt into MCP Steroid. Set `RUN_AGENT_RELIABILITY_PREAMBLE=0` only for an intentionally isolated run that must receive the prompt byte-for-byte.

Claude run-agents also default to `--safe-mode` (`RUN_AGENT_CLAUDE_SAFE_MODE=1`), because `claude -p --tools default` can spawn configured MCP stdio servers before the prompt text has any effect. Disable safe mode only for a run that explicitly needs Claude plugins/hooks/MCP configuration and keep the usual wall-clock plus no-output diagnostics.

Codex run-agents also default to an isolated config overlay (`RUN_AGENT_CODEX_ISOLATED=1`): the runner keeps the configured provider/auth path, but passes `-c 'mcp_servers={}' -c 'features.hooks=false' -c 'plugins={}'`. A 2026-06-30 bounded read-only scout reproduced the MCP/stdin wait pattern with plain `codex exec`: despite prompt text forbidding MCP use, the Codex process loaded configured MCP servers and spawned an MCP Steroid Java child that sat in `McpStdioServer.readChunk` waiting on stdin until the runner wall-clock timeout fired. `--ignore-user-config` avoided MCP but broke this local provider/auth setup with 401s, so use the narrower overlay. Disable config isolation only for a run that explicitly needs user-level Codex MCP/plugins/hooks.

The overlay alone was still insufficient in this local Codex CLI configuration: a 2026-06-30 read-only Codex scout launched `mcp-steroid` even with `-c mcp_servers={}` and then hit the 120-second wall-clock timeout. `run-agent.sh` now creates a per-run isolated `CODEX_HOME` for Codex jobs, copying only top-level model settings, `[model_providers]`, and `[projects]` trust entries from the user config. It intentionally omits MCP servers, plugins, hooks, and bundled marketplaces. The runner still passes the old `-c` disables as a belt-and-braces guard.

Timeout and stale-agent recovery also terminate the full descendant process tree now. Earlier versions killed only the top-level agent PID, so helper JVMs or MCP stdio children could survive the parent timeout and make later "stuck" diagnosis noisier.

## Required Practice

- Start long commands through `timeout` or with `RUN_AGENT_TIMEOUT_SECONDS` set.
- Before killing a suspected stuck JVM workload, collect `jps -lm` plus `jcmd <pid> Thread.print` or `jstack <pid>`.
- Before restarting a silent run-agent, inspect its `heartbeat.txt`, `run-info.txt`, any `DIAGNOSTICS=...` entries, and stdout/stderr sizes.
- To answer "ping agents" without entering an unbounded monitor loop, run:

  ```bash
  RUN_AGENT_WATCH_ONCE=1 RUN_AGENT_WATCH_LIMIT=20 ./watch-agents.sh
  ```

- To diagnose stale active agents, including JVM thread dumps, without killing them, run:

  ```bash
  RUN_AGENT_WATCH_ONCE=1 RUN_AGENT_DIAGNOSE_STALE=1 ./watch-agents.sh
  ```

- To restart genuinely stale active agents, diagnose first and keep termination/restart opt-in:

  ```bash
  RUN_AGENT_WATCH_ONCE=1 RUN_AGENT_DIAGNOSE_STALE=1 \
  RUN_AGENT_TERMINATE_STALE=1 RUN_AGENT_RESTART_STALE=1 ./watch-agents.sh
  ```

  Destructive recovery flags intentionally require `RUN_AGENT_WATCH_ONCE=1`; do not run terminate/restart in the periodic watcher loop.

- Keep routine run monitoring bounded to recent runs or active PID files. The local `runs/` tree is large enough that whole-history scans can time out.
- Do not let run-agents spawn additional unbounded review subagents. Quorum reviews should be scheduled by the root agent with explicit timeouts, or replaced by a bounded local review for trivial changes.
- Do not ask run-agent research/review scouts to use MCP Steroid by default. Shell-based inspection is enough for most gap selection and avoids MCP stdio waits inside a silent text-mode agent.
- When a run times out, inspect the generated `DIAGNOSTICS=...` file in `run-info.txt` before retrying.
- On macOS, install GNU coreutils or make sure `gtimeout` is available if you need hard time limits around watcher diagnostics. Without either `timeout` or `gtimeout`, the watcher still runs but cannot bound individual `jps`/`jcmd`/`jstack` calls.
- Treat built-in subagents as scarce stateful resources. After a bounded `wait_agent`, close only agents that have returned a final status, and close them individually. Do not call `close_agent` for a non-responsive agent and do not wrap `close_agent` calls in a parallel tool batch; one blocked close can stall the whole root agent.

## Runner Timeout Knobs

Use these defaults unless a specific run justifies changing them:

```bash
RUN_AGENT_TIMEOUT_SECONDS=900 \
RUN_AGENT_NO_OUTPUT_DIAGNOSTICS_SECONDS=180 \
RUN_AGENT_AGENTS=claude \
timeout 960 ./run-agent.sh claude "$PWD" "$PWD/THE_PROMPT_v5_review.md"
```

For short scout prompts that should either answer quickly or fail with evidence, add:

```bash
RUN_AGENT_NO_OUTPUT_TIMEOUT_SECONDS=300
```

For Claude text-mode scout prompts, that kill switch is intentionally ignored unless forced:

```bash
RUN_AGENT_CLAUDE_ALLOW_TEXT_NO_OUTPUT_TIMEOUT=1 \
RUN_AGENT_NO_OUTPUT_TIMEOUT_SECONDS=300
```
