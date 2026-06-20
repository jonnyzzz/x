# AI Observation Surface

The X server should become useful "eyes" for AI agents without compromising X11 correctness.

## Initial API Shape

The first API is available over the same TCP port as the X11 server by sniffing HTTP requests before X11 setup:

- `/` returns an HTML page with embedded SVG.
- `/screen.svg` returns the SVG screen.
- `/text` returns an HTML text report.
- `/text.txt` returns a plain text report.
- `/state.json` returns a compact machine-readable snapshot.

### Snapshot

- screen id,
- width and height,
- ARGB framebuffer,
- timestamp,
- cursor position and shape id,
- focused window id.

### Hierarchy

- screen/root window,
- parent/child window tree,
- geometry and border width,
- mapped/viewable state,
- focus and stacking order,
- overlap rectangles between mapped non-root windows,
- event masks,
- selected properties,
- drawable/resource ids.

### Events

- normalized X events delivered to clients,
- input injections,
- protocol errors,
- frame invalidation rectangles,
- client connect/disconnect lifecycle.

## Design Rules

- Observation is derived from authoritative server state.
- Protocol traces are debugging data, not the main semantic model.
- Pixel snapshots and hierarchy snapshots should share timestamps so an agent can correlate what it sees with the window tree.
- The server must be deterministic enough for tests: stable resource ids, stable snapshot order, and normalized timestamps in golden outputs.
