# X11 Implementation Survey

The project studies existing implementations for externally observable behavior, architecture, and test ideas. It does not copy implementation code.

## 1. X.Org Server

The canonical modern X11 server family. It is the best reference for edge-case semantics, extension behavior, and the split between device-independent core and platform-specific backends. It is too large to use as an architecture template for the MVP.

## 2. Xvfb

The closest baseline for this project. Xvfb is an X server with a virtual framebuffer and no physical display requirement. Use it as the first oracle for Docker integration tests and differential behavior.

For GLX, Xvfb is not a small independent implementation to mirror. The Xorg `hw/vfb` startup path wires the virtual framebuffer server into the shared Xorg GLX vendor stack with `xorgGlxCreateVendor()`. The protocol entry points then live in the common GLX dispatcher (`glxext.c`) and command handlers (`glxcmds.c`), while real context/rendering behavior depends on the native GL/Mesa/backend machinery. For this Kotlin server, the practical first step is therefore a transparent GLX protocol probe: expose the extension, answer version/string/config discovery, model context lifecycle requests, and log/render no-op GLX commands until we know exactly what clients such as IntelliJ/JCEF require.

## 3. Xephyr

A nested X server useful for studying expose/update flow, screen modeling, and interactions between a contained server and a host display. Useful after the core model exists.

## 4. Xnest

An older nested server. It is less important than Xephyr but useful for simpler historical nested-server behavior and core protocol assumptions.

## 5. Xwayland

An X server that lets X11 clients run in Wayland sessions. Keep it parked for now; it is useful later for understanding modern toolkit expectations and extension probing.

## 6. XQuartz

The macOS X11 implementation based on X.Org. It is useful for studying platform integration and rootless behavior, not for the headless MVP.

## 7. VcXsrv And Cygwin/X

Windows X server ports. They are useful for packaging and host-integration ideas. Their code and licenses are not a source for this MIT Kotlin implementation.

## 8. Xvnc / TigerVNC

An X server coupled to VNC-style framebuffer export. Useful for thinking about remote observation, framebuffer capture, and deterministic pixel testing.

## 9. WeirdX

A pure Java X server and the most relevant JVM precedent. It is old and GPL-licensed, so treat it as prior art for risks and scope only.

## 10. XLibre

A modern X.Org fork to watch for active X11 compatibility work. It is not a useful MVP architecture due to inherited X.Org scale.

## Copying Policy

Use official specifications, black-box client behavior, and differential tests. Do not transliterate code from GPL projects or large native servers. Even license-compatible sources should be treated as behavioral references rather than direct implementation sources.
