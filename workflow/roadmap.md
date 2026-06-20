# Roadmap

## Milestone 0: Repository And Baseline

- MIT license, README, Gradle Kotlin JVM build.
- Vendored X11 core protocol spec in `docs/spec`.
- Protocol review, implementation survey, AI observation design.
- Raw setup-handshake tests.
- Testcontainers/Xvfb baseline smoke test.

## Milestone 1: Protocol Skeleton

- Request loop and opcode table.
- Sequence numbers and exact error packets.
- Request length validation.
- Unsupported core request behavior.
- Protocol trace hooks.

## Milestone 2: Minimal Server Model

- One screen and root window.
- Client-scoped resource table.
- Atoms/properties.
- Windows and map/unmap/configure/query requests.
- Event masks and structure/property/expose events.
- Hierarchy snapshots for AI observation.

## Milestone 3: Framebuffer And Drawing

- ARGB framebuffer.
- Pixmaps and graphics contexts.
- `PutImage`, `GetImage`, `ClearArea`, `CopyArea`.
- Deterministic pixel snapshots and frame diffs.

## Milestone 4: Compatibility Matrix

- `xdpyinfo`, `xprop`, `xwininfo`.
- `xlogo`, `xclock`, `xeyes`.
- `xterm`, `xcalc`.
- Differential runs against Xvfb.

## Milestone 5: Toolkit Smoke

- JBR/AWT/Swing smoke in Docker with required X client libraries and fonts.
- IntelliJ startup smoke once extension probing and basic rendering are sufficient.
- Add extensions only when the matrix proves they are needed.
