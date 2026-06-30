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

## Required Practice

- Start long commands through `timeout` or with `RUN_AGENT_TIMEOUT_SECONDS` set.
- Before killing a suspected stuck JVM workload, collect `jps -lm` plus `jcmd <pid> Thread.print` or `jstack <pid>`.
- Before restarting a silent run-agent, inspect its `heartbeat.txt`, `run-info.txt`, any `DIAGNOSTICS=...` entries, and stdout/stderr sizes.
- Keep routine run monitoring bounded to recent runs or active PID files. The local `runs/` tree is large enough that whole-history scans can time out.
- Do not let run-agents spawn additional unbounded review subagents. Quorum reviews should be scheduled by the root agent with explicit timeouts, or replaced by a bounded local review for trivial changes.
- When a run times out, inspect the generated `DIAGNOSTICS=...` file in `run-info.txt` before retrying.
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
