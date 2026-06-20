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
| `xdpyinfo` | Setup, screens, visuals, extensions | Passing |
| `xprop -root` | Atoms and properties | Passing |
| `xwininfo -root` | Window tree and geometry | Passing |
| `xset q` | Basic server state queries | Later milestone |
| `xlogo` | Window, expose, drawing | Stays running |
| `xclock` | Drawing, timer updates, events | Stays running |
| `xeyes` | Pointer motion and drawing | Stays running |
| `xcalc` | Widgets, cursors, fonts, text drawing | Stays running |
| `xterm` | Text, keyboard, properties | Later milestone |
| JBR Swing sample | Java GUI client behavior | Toolkit milestone |
| IntelliJ smoke | Real-world target | Final early acceptance |

`Stays running` means the app remains connected under `timeout` and does not exit early with an X protocol error. It does not imply correct pixels yet.

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
