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
https://run-agent.sh/run-agent.sh
```

or the local equivalent provided by the environment, and append findings here before changing the roadmap.
