# X

X is a Kotlin/JVM implementation of a headless X11 server.

The project has two equal goals:

1. Implement a valid X11 server over the wire, starting with the smallest core protocol subset that real clients can exercise.
2. Expose the display as useful "eyes" for AI agents: pixels, frame changes, input/events, and a structured hierarchy of screens, windows, drawables, and resources.

This repository intentionally targets X11 first. Wayland, Projector-style remote Swing transports, and desktop/window-manager policy are out of scope for the initial milestones.

## Current Status

The current server accepts X11 TCP connections, returns a deterministic one-screen setup reply, and implements a small core request subset for atoms/properties, root/window queries, simple window lifecycle, colors, cursors, fonts, and no-op drawing requests.

It can run the first Docker smoke matrix against real X clients:

- `xdpyinfo`
- `xwininfo -root`
- `xprop -root`
- `xlogo`
- `xclock`
- `xeyes`
- `xcalc`

The graphical apps are currently "runs without protocol failure" smoke tests. Rendering is not complete yet; many drawing requests are accepted as no-ops until the framebuffer milestone.

The test suite starts with:

- raw socket protocol tests for the setup handshake,
- a Testcontainers/Xvfb smoke test that proves the Docker compatibility harness can run real X clients,
- a Testcontainers smoke test that runs real X11 tools and simple apps against the Kotlin server.

## Development

```bash
./gradlew test
```

Docker integration tests require Docker to be available to the current user.

Run the prototype server:

```bash
./gradlew run --args='--host 0.0.0.0 --port 6000'
```

Then point simple X clients at it with `DISPLAY=host:0`. Most clients will fail after setup until the dispatcher and core requests are implemented.

## Roadmap

The first compatibility milestone is deliberately smaller than full Xvfb parity:

1. X11 setup handshake, endian handling, sequence numbers, request length validation, replies, errors, and connection close.
2. One-screen server model with root window, fixed true-color visual/depth, atoms/properties, resource IDs, and basic window tree operations.
3. Events and hierarchy snapshots before broad rendering, because clients often fail early on event semantics.
4. Framebuffer, pixmaps, graphics contexts, `PutImage`, `GetImage`, `ClearArea`, and `CopyArea`.
5. Dockerized differential tests against Xvfb for `xdpyinfo`, `xprop`, `xwininfo`, `xlogo`, `xclock`, and then JBR/IntelliJ smoke tests.

See `workflow/roadmap.md` and `workflow/test-matrix.md`.

## License

The project source is MIT licensed. Vendored specifications and third-party reference material keep their original notices.
