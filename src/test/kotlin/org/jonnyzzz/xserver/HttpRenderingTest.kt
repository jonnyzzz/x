package org.jonnyzzz.xserver

import java.net.Socket
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class HttpRenderingTest {
    @Test
    fun `same socket serves svg and textual snapshots from x11 state`() {
        XServer(ServerOptions(port = 0, width = 3840, height = 2160, dpi = 100)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            createMappedWindow(server.localPort, 0x0020_0001, "one", x = 20, y = 30, width = 120, height = 90).use {
                createMappedWindow(server.localPort, 0x0020_0002, "two", x = 80, y = 70, width = 140, height = 100).use {
                    val html = httpGet(server.localPort, "/")
                    assertContains(html.headers, "${RenderCredit.HeaderName}: ${RenderCredit.Text}")
                    assertContains(html.body, "<!--${RenderCredit.Text}-->")
                    assertContains(html.body, "<svg")
                    assertContains(html.body, "one")
                    assertContains(html.body, "two")
                    assertContains(html.body, """class="window-map"""")
                    assertContains(html.body, """class="window-contents"""")
                    assertContains(html.body, """grid-template-columns: minmax(180px, 21vw) minmax(640px, 1fr)""")
                    assertContains(html.body, """class="screen-map-svg"""")
                    assertContains(html.body, """class="window-preview-svg"""")
                    assertContains(html.body, """class="window-background"""")
                    assertContains(html.body, """data-input-surface="true"""")
                    assertContains(html.body, "fetch('/input/click'")
                    assertContains(html.body, """button: 'left'""")
                    assertContains(html.body, """data-origin-x="80"""")
                    assertContains(html.body, """data-origin-x="20"""")
                    assertContains(html.body, "<strong>two</strong>")
                    assertContains(html.body, "<span>0x200002</span>")
                    assertContains(html.body, "140x100 focused")
                    assertContains(html.body, "<strong>one</strong>")
                    assertContains(html.body, "<span>0x200001</span>")
                    assertContains(html.body, "120x90 overlaps=")
                    assertEquals(
                        true,
                        html.body.indexOf("<strong>two</strong>") < html.body.indexOf("<strong>one</strong>"),
                        "Focused window preview should be rendered before the overlapped non-focused window",
                    )
                    assertContains(html.body, """<footer>by <a href="https://github.com/jonnyzzz/x">@jonnyzzz</a> <a href="https://linkedin.com/in/jonnyzzz">https://linkedin.com/in/jonnyzzz</a></footer>""")
                    assertContains(html.body, "3840 x 2160")
                    assertContains(html.body, "<dt>DPI</dt><dd>100</dd>")

                    val svg = httpGet(server.localPort, "/screen.svg")
                    assertContains(svg.headers, "${RenderCredit.HeaderName}: ${RenderCredit.Text}")
                    assertContains(svg.body, "<!--${RenderCredit.Text}-->")
                    assertContains(svg.body, "0x200001")
                    assertContains(svg.body, "0x200002")
                    assertContains(svg.body, """data-window-id="0x200001"""")
                    assertContains(svg.body, """class="framebuffer-image"""")
                    assertContains(svg.body, """href="data:image/png;base64,""")
                    assertFalse(svg.body.contains("""width="65533""""))
                    assertFalse(svg.body.contains("<polyline"), "Framebuffer-backed core lines should not be double-rendered as SVG overlays")
                    assertContains(svg.body, RenderCredit.Text)

                    val text = httpGet(server.localPort, "/text.txt")
                    assertContains(text.headers, "${RenderCredit.HeaderName}: ${RenderCredit.Text}")
                    assertContains(text.body, "Focus: 0x200002")
                    assertContains(text.body, "Screen: 3840 x 2160")
                    assertContains(text.body, "DPI: 100")
                    assertContains(text.body, "0x200002 overlaps 0x200001")
                    assertContains(text.body, RenderCredit.Text)

                    val json = httpGet(server.localPort, "/state.json")
                    assertContains(json.body, """"drawings":2""")
                    assertContains(json.body, """"inputOperations":[]""")

                    val textHtml = httpGet(server.localPort, "/text")
                    assertContains(textHtml.body, "<!--${RenderCredit.Text}-->")
                    assertContains(textHtml.body, """<footer>by <a href="https://github.com/jonnyzzz/x">@jonnyzzz</a> <a href="https://linkedin.com/in/jonnyzzz">https://linkedin.com/in/jonnyzzz</a></footer>""")
                }
            }

            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `svg does not invent fill for ParentRelative child of root background None`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                val out = socket.getOutputStream()
                setup(out, socket.getInputStream())
                out.write(changeWindowBackgroundPixmapRequest(X11Ids.RootWindow, XWindowBackground.None))
                out.write(
                    createWindowRequest(
                        id = 0x0020_0001,
                        x = 10,
                        y = 10,
                        width = 70,
                        height = 65,
                        backgroundPixmap = XWindowBackground.ParentRelative,
                    ),
                )
                out.write(mapWindowRequest(0x0020_0001))
                out.flush()
                Thread.sleep(100)

                val json = httpGet(server.localPort, "/state.json")
                val rootJson = Regex("""\{"id":"0x${X11Ids.RootWindow.toUInt().toString(16)}".*?\}""").find(json.body)?.value.orEmpty()
                val childJson = Regex("""\{"id":"0x200001".*?\}""").find(json.body)?.value.orEmpty()
                assertContains(rootJson, """"backgroundPixmap":"0x0"""")
                assertContains(childJson, """"backgroundPixmap":"0x1"""")
                assertContains(childJson, """"parent":"0x${X11Ids.RootWindow.toUInt().toString(16)}"""")

                val svg = httpGet(server.localPort, "/screen.svg")
                val childRect = Regex("""<rect\b(?=[^>]*\bdata-window-id="0x200001")[^>]*/>""").find(svg.body)?.value.orEmpty()
                assertContains(childRect, """fill="none"""")
                assertFalse(svg.body.contains("""fill="#20242c""""), "Standalone screen SVG must not paint viewer chrome behind root None")

                val html = httpGet(server.localPort, "/")
                assertContains(html.body, """class="window-preview-svg"""")
                assertContains(html.body, """data-origin-x="10"""")
                val syntheticPreviewBackground = Regex("""<rect\b(?=[^>]*\bclass="window-background")(?=[^>]*\bwidth="70")(?=[^>]*\bheight="65")(?=[^>]*\bfill="#ffffff")[^>]*/>""")
                assertFalse(
                    syntheticPreviewBackground.containsMatchIn(html.body),
                    "ParentRelative child of root None must not get a synthetic white preview background",
                )
                assertFalse(
                    Regex("""\.window-map svg \{[^}]*background:""").containsMatchIn(html.body),
                    "Screen-map SVG CSS must not paint viewer chrome behind root None",
                )
                assertFalse(
                    Regex("""\.preview svg \{[^}]*background:""").containsMatchIn(html.body),
                    "Window preview SVG CSS must not paint viewer chrome behind root None",
                )
            }

            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `copy area from pixmap renders stored pixmap image into window preview`() {
        XServer(ServerOptions(port = 0, width = 640, height = 480)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                val out = socket.getOutputStream()
                val input = socket.getInputStream()
                setup(out, input)

                out.write(createWindowRequest(0x0020_0001, 10, 20, 160, 120))
                out.write(changePropertyRequest(0x0020_0001, "pixmap target"))
                out.write(createPixmapRequest(0x0020_0100, width = 64, height = 64))
                out.write(createGcRequest(0x0020_1001, 0x0020_0001))
                out.write(putImageRequest(0x0020_0100, 0x0020_1001))
                out.write(copyAreaRequest(0x0020_0100, 0x0020_0001, 0x0020_1001))
                out.write(mapWindowRequest(0x0020_0001))
                out.flush()
                Thread.sleep(100)

                val html = httpGet(server.localPort, "/")
                assertContains(html.body, "pixmap target")
                assertContains(html.body, """data-window-id="0x200001"""")
                assertContains(html.body, """<image""")
                assertContains(html.body, """class="framebuffer-image"""")
                assertContains(html.body, """width="160"""")
                assertContains(html.body, """height="120"""")
                assertContains(html.body, """href="data:image/png;base64,""")
                assertContains(httpGet(server.localPort, "/state.json").body, """"drawings":2""")
            }

            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `painted pixmap without window copy is exposed as offscreen surface`() {
        XServer(ServerOptions(port = 0, width = 640, height = 480)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                val out = socket.getOutputStream()
                val input = socket.getInputStream()
                setup(out, input)

                out.write(createWindowRequest(0x0020_0001, 10, 20, 64, 64))
                out.write(changePropertyRequest(0x0020_0001, "offscreen target"))
                out.write(createPixmapRequest(0x0020_0100, width = 64, height = 64))
                out.write(createGcRequest(0x0020_1001, 0x0020_0100))
                out.write(putImageRequest(0x0020_0100, 0x0020_1001))
                out.write(mapWindowRequest(0x0020_0001))
                out.flush()
                Thread.sleep(100)

                val html = httpGet(server.localPort, "/")
                assertContains(html.body, """class="offscreen-surfaces"""")
                assertContains(html.body, """class="primary-surface"""")
                assertContains(html.body, "Best painted surface")
                assertEquals(
                    true,
                    html.body.indexOf("Best painted surface") < html.body.indexOf("""class="window-preview-svg""""),
                    "Matching painted backing pixmap should be the primary window preview before the raw window framebuffer",
                )
                assertContains(html.body, "Pixmap 0x200100")
                assertContains(html.body, """class="pixmap-framebuffer-image"""")
                assertContains(html.body, """data-pixmap-id="0x200100"""")
                assertContains(html.body, "candidate-for=0x200001")

                val text = httpGet(server.localPort, "/text.txt").body
                assertContains(text, "Offscreen pixmaps:")
                assertContains(text, "0x200100 geometry=64x64 depth=24 painted=true candidate-for=0x200001")

                val json = httpGet(server.localPort, "/state.json").body
                assertContains(json, """"pixmaps":[{"id":"0x200100","width":64,"height":64,"depth":24,"painted":true""")
                assertContains(json, """"matchingWindows":["0x200001"]""")
            }

            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `screen svg presents exact painted backing pixmap for otherwise unpainted window`() {
        XServer(ServerOptions(port = 0, width = 640, height = 480)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                val out = socket.getOutputStream()
                val input = socket.getInputStream()
                setup(out, input)

                out.write(createWindowRequest(0x0020_0001, 10, 20, 64, 64))
                out.write(changePropertyRequest(0x0020_0001, "backing pixmap target"))
                out.write(createPixmapRequest(0x0020_0100, width = 64, height = 64))
                out.write(createGcRequest(0x0020_1001, 0x0020_0100))
                out.write(putImageRequest(0x0020_0100, 0x0020_1001))
                out.write(mapWindowRequest(0x0020_0001))
                out.flush()
                Thread.sleep(100)

                val svg = httpGet(server.localPort, "/screen.svg").body
                assertContains(svg, "backing pixmap target")
                assertContains(svg, """class="framebuffer-image backing-pixmap-image"""")
                assertContains(svg, """data-window-id="0x200001"""")
                assertContains(svg, """data-pixmap-id="0x200100"""")
                assertContains(svg, """data-source="matching-pixmap"""")
                assertContains(svg, """href="data:image/png;base64,""")
            }

            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `screen svg presents most recently painted matching backing pixmap`() {
        XServer(ServerOptions(port = 0, width = 640, height = 480)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                val out = socket.getOutputStream()
                val input = socket.getInputStream()
                setup(out, input)

                out.write(createWindowRequest(0x0020_0001, 10, 20, 64, 64))
                out.write(changePropertyRequest(0x0020_0001, "double buffered target"))
                out.write(createPixmapRequest(0x0020_0100, width = 64, height = 64))
                out.write(createPixmapRequest(0x0020_0101, width = 64, height = 64))
                out.write(createGcRequest(0x0020_1001, 0x0020_0100))
                out.write(putImageRequest(0x0020_0100, 0x0020_1001))
                out.write(createGcRequest(0x0020_1002, 0x0020_0101))
                out.write(putImageRequest(0x0020_0101, 0x0020_1002))
                out.write(mapWindowRequest(0x0020_0001))
                out.flush()
                Thread.sleep(100)

                val svg = httpGet(server.localPort, "/screen.svg").body
                assertContains(svg, "double buffered target")
                assertContains(svg, """data-source="matching-pixmap"""")
                assertContains(svg, """data-pixmap-id="0x200101"""")
                val html = httpGet(server.localPort, "/").body
                assertContains(html, "Pixmap 0x200100")
                assertContains(html, "Pixmap 0x200101")
                assertEquals(
                    true,
                    html.indexOf("Pixmap 0x200101") < html.indexOf("Pixmap 0x200100"),
                    "Most recently painted matching pixmap should be listed before stale same-size candidates",
                )
            }

            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `screen svg keeps direct window framebuffer ahead of matching backing pixmap`() {
        XServer(ServerOptions(port = 0, width = 640, height = 480)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                val out = socket.getOutputStream()
                val input = socket.getInputStream()
                setup(out, input)

                out.write(createWindowRequest(0x0020_0001, 10, 20, 64, 64))
                out.write(changePropertyRequest(0x0020_0001, "direct window target"))
                out.write(createPixmapRequest(0x0020_0100, width = 64, height = 64))
                out.write(createGcRequest(0x0020_1001, 0x0020_0100))
                out.write(putImageRequest(0x0020_0100, 0x0020_1001))
                out.write(createGcRequest(0x0020_1002, 0x0020_0001))
                out.write(putImageRequest(0x0020_0001, 0x0020_1002))
                out.write(mapWindowRequest(0x0020_0001))
                out.flush()
                Thread.sleep(100)

                val svg = httpGet(server.localPort, "/screen.svg").body
                assertContains(svg, "direct window target")
                assertContains(svg, """class="framebuffer-image"""")
                assertContains(svg, """data-window-id="0x200001"""")
                assertContains(svg, """data-source="window-framebuffer"""")
                assertFalse(svg.contains("""class="framebuffer-image backing-pixmap-image""""))
                assertFalse(svg.contains("""data-source="matching-pixmap""""))
            }

            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `screen svg presents matching backing pixmap painted after direct window framebuffer`() {
        XServer(ServerOptions(port = 0, width = 640, height = 480)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                val out = socket.getOutputStream()
                val input = socket.getInputStream()
                setup(out, input)

                out.write(createWindowRequest(0x0020_0001, 10, 20, 64, 64))
                out.write(changePropertyRequest(0x0020_0001, "late backing pixmap target"))
                out.write(createGcRequest(0x0020_1001, 0x0020_0001))
                out.write(putImageRequest(0x0020_0001, 0x0020_1001))
                out.write(createPixmapRequest(0x0020_0100, width = 64, height = 64))
                out.write(createGcRequest(0x0020_1002, 0x0020_0100))
                out.write(putImageRequest(0x0020_0100, 0x0020_1002))
                out.write(mapWindowRequest(0x0020_0001))
                out.flush()
                Thread.sleep(100)

                val svg = httpGet(server.localPort, "/screen.svg").body
                assertContains(svg, "late backing pixmap target")
                assertContains(svg, """class="framebuffer-image backing-pixmap-image"""")
                assertContains(svg, """data-window-id="0x200001"""")
                assertContains(svg, """data-pixmap-id="0x200100"""")
                assertContains(svg, """data-source="matching-pixmap"""")
                assertFalse(svg.contains("""data-source="window-framebuffer""""))
            }

            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `screen svg presents retained render picture surface after FreePixmap`() {
        XServer(ServerOptions(port = 0, width = 640, height = 480)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                val out = socket.getOutputStream()
                val input = socket.getInputStream()
                setup(out, input)

                out.write(createWindowRequest(0x0020_0001, 10, 20, 64, 64))
                out.write(changePropertyRequest(0x0020_0001, "retained picture target"))
                out.write(createPixmapRequest(0x0020_0100, width = 64, height = 64))
                out.write(renderCreatePictureRequest(0x0020_0200, 0x0020_0100, XRender.Rgb24Format))
                out.write(renderFillRectanglesRequest(0x0020_0200))
                out.write(freePixmapRequest(0x0020_0100))
                out.write(mapWindowRequest(0x0020_0001))
                out.flush()
                Thread.sleep(100)

                val svg = httpGet(server.localPort, "/screen.svg").body
                assertContains(svg, "retained picture target")
                assertContains(svg, """class="framebuffer-image backing-pixmap-image"""")
                assertContains(svg, """data-window-id="0x200001"""")
                assertContains(svg, """data-pixmap-id="0x200100"""")
                assertContains(svg, """data-picture-id="0x200200"""")
                assertContains(svg, """data-source="retained-picture"""")

                val html = httpGet(server.localPort, "/").body
                assertContains(html, "Retained picture 0x200200")
                assertContains(html, """data-source="retained-picture"""")

                val text = httpGet(server.localPort, "/text.txt").body
                assertContains(
                    text,
                    "0x200100 geometry=64x64 depth=24 painted=true retained-picture=0x200200 pictures=0x200200 candidate-for=0x200001",
                )

                val json = httpGet(server.localPort, "/state.json").body
                assertContains(
                    json,
                    """"id":"0x200100","width":64,"height":64,"depth":24,"painted":true,"pictures":["0x200200"],"matchingWindows":["0x200001"],"retainedPicture":"0x200200"""",
                )
            }

            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `screen svg prefers live pixmap over retained render picture after pixmap id reuse`() {
        XServer(ServerOptions(port = 0, width = 640, height = 480)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                val out = socket.getOutputStream()
                val input = socket.getInputStream()
                setup(out, input)

                out.write(createWindowRequest(0x0020_0001, 10, 20, 64, 64))
                out.write(changePropertyRequest(0x0020_0001, "reused pixmap target"))
                out.write(createPixmapRequest(0x0020_0100, width = 64, height = 64))
                out.write(renderCreatePictureRequest(0x0020_0200, 0x0020_0100, XRender.Rgb24Format))
                out.write(renderFillRectanglesRequest(0x0020_0200))
                out.write(freePixmapRequest(0x0020_0100))
                out.write(createPixmapRequest(0x0020_0100, width = 64, height = 64))
                out.write(createGcRequest(0x0020_1001, 0x0020_0100))
                out.write(putImageRequest(0x0020_0100, 0x0020_1001))
                out.write(mapWindowRequest(0x0020_0001))
                out.flush()
                Thread.sleep(100)

                val svg = httpGet(server.localPort, "/screen.svg").body
                assertContains(svg, "reused pixmap target")
                assertContains(svg, """data-window-id="0x200001"""")
                assertContains(svg, """data-pixmap-id="0x200100"""")
                assertContains(svg, """data-source="matching-pixmap"""")
                assertFalse(
                    Regex("""<image\b(?=[^>]*\bdata-window-id="0x200001")(?=[^>]*\bdata-source="retained-picture")""").containsMatchIn(svg),
                    "Live reused pixmap should stay ahead of the stale retained picture surface",
                )

                val html = httpGet(server.localPort, "/").body
                assertContains(html, "Retained picture 0x200200")
                assertContains(html, "Pixmap 0x200100")

                val json = httpGet(server.localPort, "/state.json").body
                assertContains(json, """"id":"0x200100","width":64,"height":64,"depth":24,"painted":true,"pictures":[],"matchingWindows":["0x200001"],"retainedPicture":null""")
                assertContains(json, """"id":"0x200100","width":64,"height":64,"depth":24,"painted":true,"pictures":["0x200200"],"matchingWindows":["0x200001"],"retainedPicture":"0x200200"""")
            }

            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `unsupported copy area does not draw diagnostic rectangle artifacts`() {
        XServer(ServerOptions(port = 0, width = 640, height = 480)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                val out = socket.getOutputStream()
                val input = socket.getInputStream()
                setup(out, input)

                out.write(createWindowRequest(0x0020_0001, 10, 20, 160, 120))
                out.write(createWindowRequest(0x0020_0002, 30, 40, 160, 120))
                out.write(changePropertyRequest(0x0020_0002, "copy target"))
                out.write(createGcRequest(0x0020_1001, 0x0020_0002))
                out.write(copyAreaRequest(0x0020_0001, 0x0020_0002, 0x0020_1001))
                out.write(mapWindowRequest(0x0020_0001))
                out.write(mapWindowRequest(0x0020_0002))
                out.flush()
                Thread.sleep(100)

                val html = httpGet(server.localPort, "/")
                assertContains(html.body, "copy target")
                assertEquals(
                    false,
                    html.body.contains("""data-drawable-id="0x200002"><rect x="50" y="60""""),
                    "Unsupported CopyArea must not render fake diagnostic rectangle outlines into app previews",
                )
            }

            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `svg suppresses fill poly diagnostics after framebuffer paint`() {
        XServer(ServerOptions(port = 0, width = 640, height = 480)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                val out = socket.getOutputStream()
                val input = socket.getInputStream()
                setup(out, input)

                out.write(createWindowRequest(0x0020_0001, 10, 20, 160, 120))
                out.write(changePropertyRequest(0x0020_0001, "fill poly target"))
                out.write(createGcRequest(0x0020_1001, 0x0020_0001))
                out.write(putImageRequest(0x0020_0001, 0x0020_1001))
                out.write(fillPolyRequest(0x0020_0001, 0x0020_1001))
                out.write(mapWindowRequest(0x0020_0001))
                out.flush()
                Thread.sleep(100)

                val svg = httpGet(server.localPort, "/screen.svg").body
                assertContains(svg, "fill poly target")
                assertContains(svg, """class="framebuffer-image"""")
                assertEquals(false, svg.contains("""data-drawable-id="0x200001""""))
                assertEquals(false, svg.contains("<polyline"))
                assertEquals(false, svg.contains("<polygon"))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `screen svg paints each window layer in stacking order`() {
        XServer(ServerOptions(port = 0, width = 640, height = 480)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                val out = socket.getOutputStream()
                val input = socket.getInputStream()
                setup(out, input)

                val lower = 0x0020_0001
                val upper = 0x0020_0002
                out.write(createWindowRequest(lower, x = 10, y = 20, width = 80, height = 70))
                out.write(changePropertyRequest(lower, "lower framebuffer"))
                out.write(createGcRequest(0x0020_1001, lower))
                out.write(putImageRequest(lower, 0x0020_1001))
                out.write(createWindowRequest(upper, x = 30, y = 40, width = 80, height = 70))
                out.write(changePropertyRequest(upper, "upper background"))
                out.write(mapWindowRequest(lower))
                out.write(mapWindowRequest(upper))
                out.flush()
                Thread.sleep(100)

                val svg = httpGet(server.localPort, "/screen.svg").body
                val lowerFramebuffer = svg.indexOf("""data-window-id="0x200001"""", svg.indexOf("""class="framebuffer-image""""))
                val upperBackground = svg.indexOf("""<rect data-window-id="0x200002"""")
                assertContains(svg, "lower framebuffer")
                assertContains(svg, "upper background")
                assertEquals(true, lowerFramebuffer >= 0, "Lower window framebuffer image must be present")
                assertEquals(true, upperBackground >= 0, "Upper window background must be present")
                assertEquals(
                    true,
                    lowerFramebuffer < upperBackground,
                    "Lower window framebuffer must be emitted before the upper overlapping window background",
                )
            }

            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `window preview svg paints each child window layer in stacking order`() {
        XServer(ServerOptions(port = 0, width = 640, height = 480)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                val out = socket.getOutputStream()
                val input = socket.getInputStream()
                setup(out, input)

                val parent = 0x0020_0010
                val lower = 0x0020_0011
                val upper = 0x0020_0012
                out.write(createWindowRequest(parent, x = 5, y = 5, width = 120, height = 100))
                out.write(changePropertyRequest(parent, "parent preview"))
                out.write(createWindowRequest(lower, x = 10, y = 10, width = 60, height = 50, parent = parent))
                out.write(changePropertyRequest(lower, "lower preview framebuffer"))
                out.write(createGcRequest(0x0020_1011, lower))
                out.write(putImageRequest(lower, 0x0020_1011))
                out.write(createWindowRequest(upper, x = 20, y = 20, width = 60, height = 50, parent = parent))
                out.write(changePropertyRequest(upper, "upper preview background"))
                out.write(mapWindowRequest(parent))
                out.write(mapWindowRequest(lower))
                out.write(mapWindowRequest(upper))
                out.flush()
                Thread.sleep(100)

                val html = httpGet(server.localPort, "/").body
                val labelIndex = html.indexOf("""aria-label="parent preview"""")
                assertEquals(true, labelIndex >= 0, "Parent window preview SVG must be present")
                val previewStart = html.lastIndexOf("<svg", labelIndex)
                val previewEnd = html.indexOf("</svg>", labelIndex)
                assertEquals(true, previewStart >= 0 && previewEnd > previewStart, "Parent preview SVG must be extractable")
                val preview = html.substring(previewStart, previewEnd)
                val lowerFramebuffer = preview.indexOf("""data-window-id="0x200011"""", preview.indexOf("""class="framebuffer-image""""))
                assertContains(preview, "0x200011")
                assertContains(preview, "0x200012")
                assertEquals(true, lowerFramebuffer >= 0, "Lower child framebuffer image must be present in parent preview")
                val upperBackground = preview.indexOf("""class="window-background"""", lowerFramebuffer)
                assertEquals(true, upperBackground >= 0, "Upper child background must be present in parent preview")
                assertEquals(
                    true,
                    lowerFramebuffer < upperBackground,
                    "Lower child framebuffer must be emitted before the upper overlapping child background in the parent preview",
                )
            }

            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `text report includes request diagnostics`() {
        XServer(ServerOptions(port = 0, width = 640, height = 480)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                val out = socket.getOutputStream()
                val input = socket.getInputStream()
                setup(out, input)

                out.write(queryExtensionRequest("GLX"))
                out.write(unsupportedRequest())
                out.flush()
                input.readExactly(32)
                input.readExactly(32)

                val text = httpGet(server.localPort, "/text.txt").body
                assertContains(text, "Request counts:")
                assertContains(text, "QueryExtension: 1")
                assertContains(text, "Extension queries:")
                assertContains(text, "GLX supported=true")
                assertContains(text, "Unsupported requests:")
                assertContains(text, "Opcode200/0 opcode=200 minor=0")
            }

            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `client windows are removed when x11 connection closes`() {
        XServer(ServerOptions(port = 0, width = 640, height = 480)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            val socket = Socket("127.0.0.1", server.localPort)
            val out = socket.getOutputStream()
            setup(out, socket.getInputStream())

            out.write(createWindowRequest(0x0020_0001, 10, 20, 160, 120))
            out.write(createWindowRequest(0x0020_0002, 3, 4, 40, 30, parent = 0x0020_0001))
            out.write(changePropertyRequest(0x0020_0001, "disconnect target"))
            out.write(createPixmapRequest(0x0020_0100, width = 32, height = 32))
            out.write(createGcRequest(0x0020_1001, 0x0020_0001))
            out.write(mapWindowRequest(0x0020_0001))
            out.write(mapWindowRequest(0x0020_0002))
            out.flush()
            Thread.sleep(100)

            assertContains(httpGet(server.localPort, "/state.json").body, "0x200001")
            socket.close()

            waitUntil {
                val body = httpGet(server.localPort, "/state.json").body
                !body.contains("0x200001") && !body.contains("0x200002")
            }
            assertFalse(httpGet(server.localPort, "/").body.contains("disconnect target"))

            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `get property rejects overflowing offset`() {
        XServer(ServerOptions(port = 0, width = 640, height = 480)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                val out = socket.getOutputStream()
                val input = socket.getInputStream()
                setup(out, input)

                out.write(createWindowRequest(0x0020_0001, 10, 20, 160, 120))
                out.write(changePropertyRequest(0x0020_0001, "short"))
                out.write(getPropertyRequest(0x0020_0001, longOffset = 0x4000_0000, longLength = 0x7fff_ffff))
                out.flush()

                val error = input.readExactly(32)
                assertEquals(0, error[0].toInt())
                assertEquals(2, error[1].toInt() and 0xff)
                assertEquals(3, u16le(error, 2))
                assertEquals(20, error[10].toInt() and 0xff)
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    private fun createMappedWindow(
        port: Int,
        id: Int,
        name: String,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
    ): Socket {
        val socket = Socket("127.0.0.1", port)
        val out = socket.getOutputStream()
        val input = socket.getInputStream()
        out.write(byteArrayOf(0x6c, 0, 11, 0, 0, 0, 0, 0, 0, 0, 0, 0))
        out.flush()
        val prefix = input.readExactly(8)
        assertEquals(1, prefix[0].toInt())
        input.readExactly(u16le(prefix, 6) * 4)

        out.write(createWindowRequest(id, x, y, width, height))
        out.write(changePropertyRequest(id, name))
        if (id == 0x0020_0001) {
            out.write(createGcRequest(0x0020_1001, id))
            out.write(polyLineRequest(id, 0x0020_1001))
            out.write(putImageRequest(id, 0x0020_1001))
            out.write(createWindowRequest(0x0020_0003, 3, 3, 65_533, 65_533, parent = id))
            out.write(mapWindowRequest(0x0020_0003))
        }
        out.write(mapWindowRequest(id))
        out.flush()
        Thread.sleep(100)
        return socket
    }

    private fun createWindowRequest(
        id: Int,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        parent: Int = X11Ids.RootWindow,
        backgroundPixmap: Int? = null,
    ): ByteArray {
        val bytes = ByteArray(32 + if (backgroundPixmap == null) 0 else 4)
        bytes[0] = 1
        bytes[1] = 24
        put16le(bytes, 2, bytes.size / 4)
        put32le(bytes, 4, id)
        put32le(bytes, 8, parent)
        put16le(bytes, 12, x)
        put16le(bytes, 14, y)
        put16le(bytes, 16, width)
        put16le(bytes, 18, height)
        put16le(bytes, 20, 1)
        put16le(bytes, 22, 1)
        put32le(bytes, 24, X11Ids.RootVisual)
        if (backgroundPixmap != null) {
            put32le(bytes, 28, 1)
            put32le(bytes, 32, backgroundPixmap)
        }
        return bytes
    }

    private fun changeWindowBackgroundPixmapRequest(id: Int, pixmap: Int): ByteArray {
        val bytes = ByteArray(16)
        bytes[0] = 2
        put16le(bytes, 2, bytes.size / 4)
        put32le(bytes, 4, id)
        put32le(bytes, 8, 1)
        put32le(bytes, 12, pixmap)
        return bytes
    }

    private fun createGcRequest(gc: Int, drawable: Int): ByteArray {
        val bytes = ByteArray(24)
        bytes[0] = 55
        put16le(bytes, 2, bytes.size / 4)
        put32le(bytes, 4, gc)
        put32le(bytes, 8, drawable)
        put32le(bytes, 12, 0x0000_0014)
        put32le(bytes, 16, 0x0000_0000)
        put32le(bytes, 20, 8)
        return bytes
    }

    private fun setup(out: java.io.OutputStream, input: java.io.InputStream) {
        out.write(byteArrayOf(0x6c, 0, 11, 0, 0, 0, 0, 0, 0, 0, 0, 0))
        out.flush()
        val prefix = input.readExactly(8)
        assertEquals(1, prefix[0].toInt())
        input.readExactly(u16le(prefix, 6) * 4)
    }

    private fun createPixmapRequest(id: Int, width: Int, height: Int): ByteArray {
        val bytes = ByteArray(16)
        bytes[0] = 53
        bytes[1] = 24
        put16le(bytes, 2, 4)
        put32le(bytes, 4, id)
        put32le(bytes, 8, X11Ids.RootWindow)
        put16le(bytes, 12, width)
        put16le(bytes, 14, height)
        return bytes
    }

    private fun freePixmapRequest(id: Int): ByteArray {
        val bytes = ByteArray(8)
        bytes[0] = 54
        put16le(bytes, 2, bytes.size / 4)
        put32le(bytes, 4, id)
        return bytes
    }

    private fun renderCreatePictureRequest(picture: Int, drawable: Int, format: Int): ByteArray {
        val bytes = ByteArray(20)
        bytes[0] = XRender.MajorOpcode.toByte()
        bytes[1] = 4
        put16le(bytes, 2, bytes.size / 4)
        put32le(bytes, 4, picture)
        put32le(bytes, 8, drawable)
        put32le(bytes, 12, format)
        return bytes
    }

    private fun renderFillRectanglesRequest(picture: Int): ByteArray {
        val bytes = ByteArray(28)
        bytes[0] = XRender.MajorOpcode.toByte()
        bytes[1] = 26
        put16le(bytes, 2, bytes.size / 4)
        bytes[4] = XRender.OpSrc.toByte()
        put32le(bytes, 8, picture)
        put16le(bytes, 12, 0xffff)
        put16le(bytes, 14, 0)
        put16le(bytes, 16, 0)
        put16le(bytes, 18, 0xffff)
        put16le(bytes, 20, 0)
        put16le(bytes, 22, 0)
        put16le(bytes, 24, 64)
        put16le(bytes, 26, 64)
        return bytes
    }

    private fun copyAreaRequest(source: Int, destination: Int, gc: Int): ByteArray {
        val bytes = ByteArray(28)
        bytes[0] = 62
        put16le(bytes, 2, bytes.size / 4)
        put32le(bytes, 4, source)
        put32le(bytes, 8, destination)
        put32le(bytes, 12, gc)
        put16le(bytes, 16, 40)
        put16le(bytes, 18, 30)
        put16le(bytes, 20, 50)
        put16le(bytes, 22, 60)
        put16le(bytes, 24, 2)
        put16le(bytes, 26, 2)
        return bytes
    }

    private fun polyLineRequest(drawable: Int, gc: Int): ByteArray {
        val bytes = ByteArray(24)
        bytes[0] = 65
        put16le(bytes, 2, bytes.size / 4)
        put32le(bytes, 4, drawable)
        put32le(bytes, 8, gc)
        put16le(bytes, 12, 12)
        put16le(bytes, 14, 12)
        put16le(bytes, 16, 96)
        put16le(bytes, 18, 70)
        put16le(bytes, 20, 24)
        put16le(bytes, 22, 78)
        return bytes
    }

    private fun fillPolyRequest(drawable: Int, gc: Int): ByteArray {
        val bytes = ByteArray(28)
        bytes[0] = 69
        put16le(bytes, 2, bytes.size / 4)
        put32le(bytes, 4, drawable)
        put32le(bytes, 8, gc)
        bytes[12] = 2
        bytes[13] = 0
        put16le(bytes, 16, 20)
        put16le(bytes, 18, 20)
        put16le(bytes, 20, 70)
        put16le(bytes, 22, 30)
        put16le(bytes, 24, 30)
        put16le(bytes, 26, 70)
        return bytes
    }

    private fun putImageRequest(drawable: Int, gc: Int): ByteArray {
        val bytes = ByteArray(40)
        bytes[0] = 72
        bytes[1] = 2
        put16le(bytes, 2, bytes.size / 4)
        put32le(bytes, 4, drawable)
        put32le(bytes, 8, gc)
        put16le(bytes, 12, 2)
        put16le(bytes, 14, 2)
        put16le(bytes, 16, 40)
        put16le(bytes, 18, 30)
        bytes[21] = 24
        put32le(bytes, 24, 0x00ff_0000)
        put32le(bytes, 28, 0x0000_ff00)
        put32le(bytes, 32, 0x0000_00ff)
        return bytes
    }

    private fun changePropertyRequest(window: Int, value: String): ByteArray {
        val data = value.encodeToByteArray()
        val padded = (data.size + 3) and -4
        val bytes = ByteArray(24 + padded)
        bytes[0] = 18
        bytes[1] = 0
        put16le(bytes, 2, bytes.size / 4)
        put32le(bytes, 4, window)
        put32le(bytes, 8, 39)
        put32le(bytes, 12, 31)
        bytes[16] = 8
        put32le(bytes, 20, data.size)
        data.copyInto(bytes, 24)
        return bytes
    }

    private fun getPropertyRequest(window: Int, longOffset: Int, longLength: Int): ByteArray {
        val bytes = ByteArray(24)
        bytes[0] = 20
        bytes[1] = 0
        put16le(bytes, 2, bytes.size / 4)
        put32le(bytes, 4, window)
        put32le(bytes, 8, 39)
        put32le(bytes, 12, 31)
        put32le(bytes, 16, longOffset)
        put32le(bytes, 20, longLength)
        return bytes
    }

    private fun queryExtensionRequest(name: String): ByteArray {
        val encoded = name.encodeToByteArray()
        val padded = (encoded.size + 3) and -4
        val bytes = ByteArray(8 + padded)
        bytes[0] = 98.toByte()
        put16le(bytes, 2, bytes.size / 4)
        put16le(bytes, 4, encoded.size)
        encoded.copyInto(bytes, 8)
        return bytes
    }

    private fun unsupportedRequest(): ByteArray {
        val bytes = ByteArray(4)
        bytes[0] = 200.toByte()
        put16le(bytes, 2, 1)
        return bytes
    }

    private fun mapWindowRequest(id: Int): ByteArray {
        val bytes = ByteArray(8)
        bytes[0] = 8
        put16le(bytes, 2, 2)
        put32le(bytes, 4, id)
        return bytes
    }

    private fun httpGet(port: Int, path: String): HttpResponse =
        Socket("127.0.0.1", port).use { socket ->
            socket.getOutputStream().write("GET $path HTTP/1.1\r\nHost: localhost\r\n\r\n".encodeToByteArray())
            socket.getOutputStream().flush()
            val response = socket.getInputStream().readBytes().decodeToString()
            HttpResponse(
                headers = response.substringBefore("\r\n\r\n"),
                body = response.substringAfter("\r\n\r\n"),
            )
        }

    private fun waitUntil(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 2_000
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(20)
        }
        assertEquals(true, condition(), "Condition did not become true before timeout")
    }

    private fun java.io.InputStream.readExactly(size: Int): ByteArray {
        val bytes = ByteArray(size)
        var offset = 0
        while (offset < size) {
            val read = read(bytes, offset, size - offset)
            if (read == -1) error("Expected $size bytes, got $offset")
            offset += read
        }
        return bytes
    }

    private fun put16le(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = value.toByte()
        bytes[offset + 1] = (value ushr 8).toByte()
    }

    private fun put32le(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = value.toByte()
        bytes[offset + 1] = (value ushr 8).toByte()
        bytes[offset + 2] = (value ushr 16).toByte()
        bytes[offset + 3] = (value ushr 24).toByte()
    }

    private fun u16le(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or ((bytes[offset + 1].toInt() and 0xff) shl 8)

    private data class HttpResponse(
        val headers: String,
        val body: String,
    )
}
