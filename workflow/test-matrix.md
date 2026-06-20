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

Run each client against Xvfb first, then against the Kotlin server. Xvfb belongs only in
the `jonnyzzz-x/x11-reference:latest` comparison image; the normal
`jonnyzzz-x/x11-client:latest` demo/client image must stay free of Xvfb.

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
| `twm` + `xlogo` + `xclock` | Window manager, independent windows, overlap/focus state | Passing |
| `xterm` | Text, keyboard, properties | Later milestone |
| JBR Swing sample | Java GUI client behavior | Toolkit milestone |
| IntelliJ IDEA Community GitHub release | Real-world target, heavyweight opt-in smoke | Passing opt-in |

`Stays running` means the app remains connected under `timeout` and does not exit early with an X protocol error. It does not imply complete rendering parity yet.

The IntelliJ smoke is excluded from default `test` because it downloads the current GitHub release tarball.
Build `jonnyzzz-x/x11-client:latest` first with `./gradlew dockerBuildX11Client`, then run it explicitly with `-Dx.intellijSmoke=true`.

Build all local Docker images before the default Docker-backed test matrix:

```bash
./gradlew dockerBuildX11Images
./gradlew test
```

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
