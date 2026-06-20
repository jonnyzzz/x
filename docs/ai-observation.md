# AI Observation Surface

The X server should become useful "eyes" for AI agents without compromising X11 correctness.

## Initial API Shape

Keep the first API internal to Kotlin. Add HTTP/WebSocket only after the state model is stable.

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
