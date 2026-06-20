# Roadmap

## Milestone 0: Repository And Baseline

- MIT license, README, Gradle Kotlin JVM build.
- Vendored X11 core protocol spec in `docs/spec`.
- Protocol review, implementation survey, AI observation design.
- Raw setup-handshake tests.
- Testcontainers/Xvfb baseline smoke test.

## Milestone 1: Protocol Skeleton

- Request loop and opcode table. Done for the first core subset.
- Sequence numbers and basic error packets. Started.
- Request length validation. Started.
- Unsupported core request behavior. Started.
- Protocol trace hooks. Started with `-Dx.trace=true`.

## Milestone 2: Minimal Server Model

- One screen and root window. Done.
- Shared early resource table for windows, pixmaps, GCs, fonts, cursors, and colormaps. Started.
- Atoms/properties. Started.
- Windows and map/unmap/configure/query requests. Started.
- Event masks and structure/property/expose events. Started with basic map/expose.
- Hierarchy snapshots for AI observation. Started via HTTP SVG/text/state endpoints.

## Milestone 3: Framebuffer And Drawing

- ARGB framebuffer.
- Pixmaps and graphics contexts.
- `PutImage`, `GetImage`, `ClearArea`, `CopyArea`.
- Deterministic pixel snapshots and frame diffs.

Current drawing behavior accepts many core drawing and text opcodes as no-ops so simple apps can stay alive. Replace those no-ops with framebuffer mutations in this milestone.

## Milestone 3a: HTTP Observation

- Same-port HTTP routing alongside X11 setup. Done.
- SVG screen view derived from X server state. Done.
- Textual explanation of windows, focus, stacking, and overlaps. Done.
- JSON snapshot for agents/tools. Started.

## Milestone 4: Compatibility Matrix

- `xdpyinfo`, `xprop`, `xwininfo`.
- `xlogo`, `xclock`, `xeyes`.
- `xterm`, `xcalc`.
- Differential runs against Xvfb.

## Milestone 5: Toolkit Smoke

- JBR/AWT/Swing smoke in Docker with required X client libraries and fonts.
- IntelliJ startup smoke once extension probing and basic rendering are sufficient.
- Add extensions only when the matrix proves they are needed.
