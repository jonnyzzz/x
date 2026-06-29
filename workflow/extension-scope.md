# Extension Scope

The compatibility target is not full Xvfb parity. Implement and deepen X11 extensions only when they are needed to keep IntelliJ IDEA, VSCode, or the existing smoke matrix running with matching visible output.

## Decision Rule

Before adding a new extension, or before implementing a new request inside an advertised extension, collect at least one piece of evidence:

- an IntelliJ IDEA or VSCode smoke trace shows `QueryExtension` or an extension request followed by disconnect, startup failure, missing input, or a visible SVG/HTML rendering difference from Xvfb;
- an existing matrix client fails because the extension behavior blocks the IntelliJ/VSCode path;
- a protocol review proves the request is required to make already-advertised extension behavior internally consistent for those clients.

Record the evidence in `MESSAGE-BUS.md`, add or update a focused protocol/integration test, then implement the smallest compatible slice.

## Current Allowlist

These extensions may be advertised and maintained because they are already in the server or are directly plausible for JVM/AWT/Swing, JBR/JCEF, GTK/Electron, or window-manager compatibility:

| Extension | Scope |
| --- | --- |
| `BIG-REQUESTS` | Large requests needed by real toolkits and pixmap/render payloads. |
| `RENDER` | Primary Java2D/JBR/Electron-compatible rendering path; keep semantic drawable/picture state. |
| `MIT-SHM` | Shared-image probing and fallback compatibility; do not require real shared memory until a client trace needs it. |
| `XFIXES` | Cursor, selection, and region behavior used by modern toolkits. |
| `SHAPE` | Window/input shape behavior for toolkit and window-manager compatibility. |
| `XKEYBOARD` | Keyboard discovery and map compatibility for JVM and Electron clients. |
| `RANDR` | Fixed-screen monitor, DPI, output, and size reporting. |
| `SYNC` | Counters, alarms, and fences when clients use them for presentation or coordination. |
| `XC-MISC` | XID range allocation compatibility. |
| `XINERAMA` | Legacy single-screen geometry fallback. |
| `MIT-SCREEN-SAVER` | Query/suspend compatibility for desktop toolkits. |
| `GLX` | Probe and lifecycle surface only; keep real GL rendering out of scope unless IntelliJ/JCEF or VSCode cannot run with software/XRender paths. |
| `XTEST` | Test and automation support only; do not expand it as a runtime requirement without trace evidence. |
| `MIT-SUNDRY-NONSTANDARD` | Legacy probe compatibility only; avoid new work without trace evidence. |

## Parked Until Proven

Do not implement or advertise additional extensions such as `Composite`, `DAMAGE`, `Present`, `XInputExtension`/XI2, `DRI2`, `DRI3`, or desktop-environment-specific extensions unless an IntelliJ IDEA or VSCode trace proves that absence blocks startup, input, or visual parity.

When a parked extension becomes necessary, start with discovery replies and the exact requests observed in the trace. Keep rendering and semantic state preservation ahead of broad protocol coverage.
