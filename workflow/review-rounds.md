# Review Rounds

## Round 1: GBrain Planning

Outcome:

- Build the MVP as a headless, JVM-only X11 core-protocol server.
- Treat Xvfb as the first compatibility oracle, not a runtime dependency.
- Prioritize wire exactness, resource ownership, event semantics, and deterministic framebuffer behavior.
- Expose AI observation as pixels, events, and hierarchy.

## Round 2: Implementation Bootstrap

Outcome:

- Create the repository skeleton and executable Kotlin project.
- Implement setup-handshake success path.
- Add raw protocol tests and an Xvfb/Testcontainers baseline.
- Park Wayland and Projector outside the implementation path.

## Next Review Command

When available, run:

```bash
RUN_AGENT_TIMEOUT_SECONDS=1800 ./run-agent.sh claude /Users/jonnyzzz/Work/jonnyzzz-x /Users/jonnyzzz/Work/jonnyzzz-x/THE_PROMPT_v5_review.md
```

or the local equivalent provided by the environment, and append findings here before changing the roadmap.

Do not ask a review run-agent to spawn its own unbounded review quorum. Schedule each quorum member as a separate root-level run with an explicit timeout, then consolidate the results in the root agent. If using built-in subagents instead of `run-agent.sh`, wait with a bounded timeout and close only agents that returned a final status; never batch lifecycle cleanup. See `workflow/agent-reliability.md`.
