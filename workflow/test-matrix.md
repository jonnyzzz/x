# Test Matrix

## Raw Protocol

- little-endian setup success,
- big-endian setup success,
- authorization name/data padding,
- invalid byte order failure,
- malformed short setup request,
- request length validation,
- unsupported opcode error.

## Docker Clients

Run each client against Xvfb first, then against the Kotlin server.

| Client | Purpose | First Expected Kotlin Result |
| --- | --- | --- |
| `xdpyinfo` | Setup, screens, visuals, extensions | Later milestone |
| `xprop` | Atoms and properties | Later milestone |
| `xwininfo` | Window tree and geometry | Later milestone |
| `xset q` | Basic server state queries | Later milestone |
| `xlogo` | Window, expose, drawing | Later milestone |
| `xclock` | Drawing, timer updates, events | Later milestone |
| `xeyes` | Pointer motion and drawing | Later milestone |
| `xterm` | Text, keyboard, properties | Later milestone |
| JBR Swing sample | Java GUI client behavior | Toolkit milestone |
| IntelliJ smoke | Real-world target | Final early acceptance |

## Differential Output

Normalize before comparing:

- display names,
- vendor strings,
- timestamps,
- resource ids when not semantically important,
- ordering that the X11 spec leaves unspecified.

Compare:

- exit code,
- stderr category,
- normalized stdout,
- protocol trace,
- hierarchy snapshot,
- deterministic pixel checks.
