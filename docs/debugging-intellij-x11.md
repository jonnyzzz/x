# Debugging IntelliJ on the JVM X Server

The Docker IntelliJ launcher has an opt-in X11/JBR/JCEF debug mode:

```bash
IDEA_X11_DEBUG=true run-intellij
```

For the full Docker demo:

```bash
docker run -d --name x-demo-idea \
  -v "$PWD:/workspace/jonnyzzz-x" \
  -e IDEA_X11_DEBUG=true \
  -e SKIKO_RENDER_API=SOFTWARE_FAST \
  jonnyzzz-x/x11-client:latest \
  sh -lc 'touch /tmp/idea-run.log; DISPLAY=host.docker.internal:0 IDEA_PROJECT=/workspace/jonnyzzz-x run-intellij >>/tmp/idea-run.log 2>&1 & tail -f /tmp/idea-run.log'
```

The mode appends VM options to `/tmp/idea-extra.vmoptions`. If `IDEA_VM_OPTIONS`
already points to a file, that file is copied first and the debug options are
appended after it.

## What It Enables

- `-Dsun.java2d.xrender=True`: forces and prints the JBR XRender pipeline probe.
- `-Dsun.java2d.opengl=false`: keeps Java2D off the GLX path while debugging XRender.
- `-Dsun.awt.x11.trace=log,timestamp,stats,out:/tmp/idea-log/xawt-trace.log,td=1`: records XToolkit AWT-lock traces and shutdown statistics.
- `-Didea.log.debug.categories=...` and `-Didea.log.trace.categories=...`: enables IntelliJ/JUL categories for AWT windowing, focus, XEmbed, JCEF, Compose/Skiko, and window manager paths.
- `-Didea.log.separate.file.categories=...`: writes the most important categories into separate `idea_*.log` files under `/tmp/idea-log`.
- `-Dide.browser.jcef.log.level=verbose`, `ide.browser.jcef.log.path`, `ide.browser.jcef.log_chromium.path`, and `ide.browser.jcef.log.extended=true`: enables verbose JCEF and Chromium logs.

The source points used for these knobs are:

- IntelliJ log category properties: `LoggerConfigFromSystemProperties.kt`.
- JBR XRender/OpenGL pipeline flags: `X11GraphicsEnvironment.java`.
- JBR XToolkit trace syntax: `XToolkit.java`.
- JCEF verbose log properties: `SettingsHelper.java` and `JBCefApp.java`.

## What To Inspect

```bash
docker logs --tail 400 x-demo-idea
docker exec x-demo-idea sh -lc 'ls -lah /tmp/idea-log && tail -200 /tmp/idea-log/idea.log'
docker exec x-demo-idea sh -lc 'tail -200 /tmp/idea-log/xawt-trace.log 2>/dev/null || true'
docker exec x-demo-idea sh -lc 'tail -200 /tmp/idea-log/jcef.log 2>/dev/null || true'
docker exec x-demo-idea sh -lc 'tail -200 /tmp/idea-log/jcef_chromium.log 2>/dev/null || true'
docker logs --tail 400 x-demo-server
docker exec x-demo-server curl --max-time 10 -fsS http://127.0.0.1:6000/text.txt
```

The server text report is the fastest correlation point. It shows unsupported
requests, top request counts, recent GLX operations, recent RENDER operations,
input events, focus, overlap, and the current window tree.
