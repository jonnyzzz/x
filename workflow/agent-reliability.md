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

## Required Practice

- Start long commands through `timeout` or with `RUN_AGENT_TIMEOUT_SECONDS` set.
- Before killing a suspected stuck JVM workload, collect `jps -lm` plus `jcmd <pid> Thread.print` or `jstack <pid>`.
- Keep routine run monitoring bounded to recent runs or active PID files. The local `runs/` tree is large enough that whole-history scans can time out.
- Do not let run-agents spawn additional unbounded review subagents. Quorum reviews should be scheduled by the root agent with explicit timeouts, or replaced by a bounded local review for trivial changes.
- When a run times out, inspect the generated `DIAGNOSTICS=...` file in `run-info.txt` before retrying.
- Treat built-in subagents as scarce stateful resources. After a bounded `wait_agent`, close only agents that have returned a final status, and close them individually. Do not call `close_agent` for a non-responsive agent and do not wrap `close_agent` calls in a parallel tool batch; one blocked close can stall the whole root agent.
