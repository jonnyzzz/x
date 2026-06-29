package org.jonnyzzz.xserver

import java.io.InputStream
import java.net.Socket
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class XRandrProtocolTest {
    private companion object {
        const val PrimaryAtom = 1
        const val AtomAtom = 4
        const val StringAtom = 31
    }

    @Test
    fun `RANDR reports read-only single screen resources and recovers after errors`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val out = socket.getOutputStream()
                out.write(queryExtensionRequest("RANDR"))
                out.write(listExtensionsRequest())
                out.write(request(XRandr.MajorOpcode, XRandr.QueryVersion, ByteArray(4)))
                out.write(randrQueryVersionRequest(1, 6))
                out.write(randrQueryVersionRequest(2, 0))
                out.write(request(XRandr.MajorOpcode, XRandr.SelectInput, ByteArray(4)))
                out.write(randrSelectInputRequest(X11Ids.RootWindow, 0x0100))
                out.write(randrSelectInputRequest(X11Ids.RootWindow, XRandr.EventMask))
                out.write(request(XRandr.MajorOpcode, XRandr.GetScreenInfo, ByteArray(0)))
                out.write(u32Request(XRandr.GetScreenInfo, X11Ids.RootWindow))
                out.write(u32Request(XRandr.GetScreenSizeRange, 0x0102_0304))
                out.write(u32Request(XRandr.GetScreenSizeRange, X11Ids.RootWindow))
                out.write(u32Request(XRandr.GetScreenResources, X11Ids.RootWindow))
                out.write(u32Request(XRandr.GetScreenResourcesCurrent, X11Ids.RootWindow))
                out.write(outputInfoRequest(0x0102_0304))
                out.write(outputInfoRequest(XRandr.OutputId))
                out.write(u32Request(XRandr.ListOutputProperties, XRandr.OutputId))
                out.write(crtcInfoRequest(0x0102_0304))
                out.write(crtcInfoRequest(XRandr.CrtcId))
                out.write(u32Request(XRandr.GetCrtcGammaSize, XRandr.CrtcId))
                out.write(u32Request(XRandr.GetCrtcGamma, XRandr.CrtcId))
                out.write(u32Request(XRandr.GetOutputPrimary, X11Ids.RootWindow))
                out.write(u32Request(XRandr.GetProviders, X11Ids.RootWindow))
                out.write(getMonitorsRequest(X11Ids.RootWindow, 2))
                out.write(getMonitorsRequest(X11Ids.RootWindow, 1))
                out.write(queryPointerRequest())
                out.flush()

                val extension = readReply(socket.getInputStream())
                assertEquals(1, extension[8].toInt() and 0xff)
                assertEquals(XRandr.MajorOpcode, extension[9].toInt() and 0xff)
                assertEquals(XRandr.FirstEvent, extension[10].toInt() and 0xff)
                assertEquals(XRandr.FirstError, extension[11].toInt() and 0xff)

                val extensions = readReply(socket.getInputStream())
                assertContains(extensionNames(extensions), "RANDR")

                assertError(socket.getInputStream(), error = 16, badValue = 0, sequence = 3, minorOpcode = XRandr.QueryVersion)

                val version = readReply(socket.getInputStream())
                assertEquals(4, u16le(version, 2))
                assertEquals(XRandr.MajorVersion, u32le(version, 8))
                assertEquals(XRandr.MinorVersion, u32le(version, 12))

                val futureVersion = readReply(socket.getInputStream())
                assertEquals(5, u16le(futureVersion, 2))
                assertEquals(XRandr.MajorVersion, u32le(futureVersion, 8))
                assertEquals(XRandr.MinorVersion, u32le(futureVersion, 12))

                assertError(socket.getInputStream(), error = 16, badValue = 0, sequence = 6, minorOpcode = XRandr.SelectInput)
                assertError(socket.getInputStream(), error = 2, badValue = 0x0100, sequence = 7, minorOpcode = XRandr.SelectInput)
                assertError(socket.getInputStream(), error = 16, badValue = 0, sequence = 9, minorOpcode = XRandr.GetScreenInfo)

                val screenInfo = readReply(socket.getInputStream())
                assertEquals(10, u16le(screenInfo, 2))
                assertEquals(3, u32le(screenInfo, 4))
                assertEquals(XRandr.Rotate0, screenInfo[1].toInt() and 0xff)
                assertEquals(X11Ids.RootWindow, u32le(screenInfo, 8))
                assertEquals(XRandr.ConfigTimestamp, u32le(screenInfo, 16))
                assertEquals(1, u16le(screenInfo, 20))
                assertEquals(0, u16le(screenInfo, 22))
                assertEquals(XRandr.Rotate0, u16le(screenInfo, 24))
                assertEquals(XRandr.RefreshRate, u16le(screenInfo, 26))
                assertEquals(2, u16le(screenInfo, 28))
                assertEquals(120, u16le(screenInfo, 32))
                assertEquals(90, u16le(screenInfo, 34))
                assertEquals(32, u16le(screenInfo, 36))
                assertEquals(24, u16le(screenInfo, 38))
                assertEquals(1, u16le(screenInfo, 40))
                assertEquals(XRandr.RefreshRate, u16le(screenInfo, 42))

                assertError(socket.getInputStream(), error = 3, badValue = 0x0102_0304, sequence = 11, minorOpcode = XRandr.GetScreenSizeRange)

                val range = readReply(socket.getInputStream())
                assertEquals(12, u16le(range, 2))
                assertEquals(120, u16le(range, 8))
                assertEquals(90, u16le(range, 10))
                assertEquals(120, u16le(range, 12))
                assertEquals(90, u16le(range, 14))

                assertScreenResources(readReply(socket.getInputStream()), sequence = 13)
                assertScreenResources(readReply(socket.getInputStream()), sequence = 14)

                assertError(socket.getInputStream(), error = XRandr.BadOutput, badValue = 0x0102_0304, sequence = 15, minorOpcode = XRandr.GetOutputInfo)

                val outputInfo = readReply(socket.getInputStream())
                assertEquals(16, u16le(outputInfo, 2))
                assertEquals(5, u32le(outputInfo, 4))
                assertEquals(XRandr.Success, outputInfo[1].toInt() and 0xff)
                assertEquals(XRandr.CrtcId, u32le(outputInfo, 12))
                assertEquals(32, u32le(outputInfo, 16))
                assertEquals(24, u32le(outputInfo, 20))
                assertEquals(XRandr.Connected, outputInfo[24].toInt() and 0xff)
                assertEquals(XRandr.SubPixelUnknown, outputInfo[25].toInt() and 0xff)
                assertEquals(1, u16le(outputInfo, 26))
                assertEquals(1, u16le(outputInfo, 28))
                assertEquals(1, u16le(outputInfo, 30))
                assertEquals(0, u16le(outputInfo, 32))
                assertEquals(XRandr.OutputName.length, u16le(outputInfo, 34))
                assertEquals(XRandr.CrtcId, u32le(outputInfo, 36))
                assertEquals(XRandr.ModeId, u32le(outputInfo, 40))
                assertEquals(XRandr.OutputName, outputInfo.copyOfRange(44, 44 + XRandr.OutputName.length).decodeToString())

                val outputProperties = readReply(socket.getInputStream())
                assertEquals(17, u16le(outputProperties, 2))
                assertEquals(0, u32le(outputProperties, 4))
                assertEquals(0, u16le(outputProperties, 8))

                assertError(socket.getInputStream(), error = XRandr.BadCrtc, badValue = 0x0102_0304, sequence = 18, minorOpcode = XRandr.GetCrtcInfo)

                val crtcInfo = readReply(socket.getInputStream())
                assertEquals(19, u16le(crtcInfo, 2))
                assertEquals(2, u32le(crtcInfo, 4))
                assertEquals(XRandr.Success, crtcInfo[1].toInt() and 0xff)
                assertEquals(0, u16le(crtcInfo, 12))
                assertEquals(0, u16le(crtcInfo, 14))
                assertEquals(120, u16le(crtcInfo, 16))
                assertEquals(90, u16le(crtcInfo, 18))
                assertEquals(XRandr.ModeId, u32le(crtcInfo, 20))
                assertEquals(XRandr.Rotate0, u16le(crtcInfo, 24))
                assertEquals(XRandr.Rotate0, u16le(crtcInfo, 26))
                assertEquals(1, u16le(crtcInfo, 28))
                assertEquals(1, u16le(crtcInfo, 30))
                assertEquals(XRandr.OutputId, u32le(crtcInfo, 32))
                assertEquals(XRandr.OutputId, u32le(crtcInfo, 36))

                val gammaSize = readReply(socket.getInputStream())
                assertEquals(20, u16le(gammaSize, 2))
                assertEquals(0, u32le(gammaSize, 4))
                assertEquals(XRandr.GammaRampSize, u16le(gammaSize, 8))

                val gamma = readReply(socket.getInputStream())
                assertEquals(21, u16le(gamma, 2))
                assertEquals(0, u32le(gamma, 4))
                assertEquals(XRandr.GammaRampSize, u16le(gamma, 8))

                val primary = readReply(socket.getInputStream())
                assertEquals(22, u16le(primary, 2))
                assertEquals(0, u32le(primary, 8))

                val providers = readReply(socket.getInputStream())
                assertEquals(23, u16le(providers, 2))
                assertEquals(0, u32le(providers, 4))
                assertEquals(0, u16le(providers, 12))

                assertError(socket.getInputStream(), error = 2, badValue = 2, sequence = 24, minorOpcode = XRandr.GetMonitors)

                val monitors = readReply(socket.getInputStream())
                assertEquals(25, u16le(monitors, 2))
                assertEquals(7, u32le(monitors, 4))
                assertEquals(XRandr.Success, monitors[1].toInt() and 0xff)
                assertEquals(1, u32le(monitors, 12))
                assertEquals(1, u32le(monitors, 16))
                assertEquals(0, u32le(monitors, 32))
                assertEquals(0, monitors[36].toInt() and 0xff)
                assertEquals(1, monitors[37].toInt() and 0xff)
                assertEquals(1, u16le(monitors, 38))
                assertEquals(0, u16le(monitors, 40))
                assertEquals(0, u16le(monitors, 42))
                assertEquals(120, u16le(monitors, 44))
                assertEquals(90, u16le(monitors, 46))
                assertEquals(32, u32le(monitors, 48))
                assertEquals(24, u32le(monitors, 52))
                assertEquals(XRandr.OutputId, u32le(monitors, 56))

                val pointer = readReply(socket.getInputStream())
                assertEquals(26, u16le(pointer, 2))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `RANDR validates secondary resource lookups and bad lengths`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val out = socket.getOutputStream()
                out.write(u32Request(XRandr.ListOutputProperties, 0x0102_0304))
                out.write(u32Request(XRandr.GetCrtcGammaSize, 0x0102_0304))
                out.write(u32Request(XRandr.GetCrtcGamma, 0x0102_0304))
                out.write(u32Request(XRandr.GetOutputPrimary, 0x0102_0304))
                out.write(u32Request(XRandr.GetProviders, 0x0102_0304))
                out.write(getMonitorsRequest(0x0102_0304, 1))
                out.write(request(XRandr.MajorOpcode, XRandr.GetOutputInfo, ByteArray(4)))
                out.write(request(XRandr.MajorOpcode, XRandr.GetCrtcInfo, ByteArray(4)))
                out.write(request(XRandr.MajorOpcode, XRandr.GetMonitors, ByteArray(4)))
                out.write(randrQueryVersionRequest(1, 6))
                out.flush()

                assertError(socket.getInputStream(), error = XRandr.BadOutput, badValue = 0x0102_0304, sequence = 1, minorOpcode = XRandr.ListOutputProperties)
                assertError(socket.getInputStream(), error = XRandr.BadCrtc, badValue = 0x0102_0304, sequence = 2, minorOpcode = XRandr.GetCrtcGammaSize)
                assertError(socket.getInputStream(), error = XRandr.BadCrtc, badValue = 0x0102_0304, sequence = 3, minorOpcode = XRandr.GetCrtcGamma)
                assertError(socket.getInputStream(), error = 3, badValue = 0x0102_0304, sequence = 4, minorOpcode = XRandr.GetOutputPrimary)
                assertError(socket.getInputStream(), error = 3, badValue = 0x0102_0304, sequence = 5, minorOpcode = XRandr.GetProviders)
                assertError(socket.getInputStream(), error = 3, badValue = 0x0102_0304, sequence = 6, minorOpcode = XRandr.GetMonitors)
                assertError(socket.getInputStream(), error = 16, badValue = 0, sequence = 7, minorOpcode = XRandr.GetOutputInfo)
                assertError(socket.getInputStream(), error = 16, badValue = 0, sequence = 8, minorOpcode = XRandr.GetCrtcInfo)
                assertError(socket.getInputStream(), error = 16, badValue = 0, sequence = 9, minorOpcode = XRandr.GetMonitors)

                val recovered = readReply(socket.getInputStream())
                assertEquals(10, u16le(recovered, 2))
                assertEquals(XRandr.MajorVersion, u32le(recovered, 8))
                assertEquals(XRandr.MinorVersion, u32le(recovered, 12))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `RANDR provider requests reject missing providers and recover`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val out = socket.getOutputStream()
                val missing = 0x0102_0304
                out.write(request(XRandr.MajorOpcode, XRandr.GetProviderInfo, ByteArray(4)))
                out.write(getProviderInfoRequest(missing))
                out.write(request(XRandr.MajorOpcode, XRandr.SetProviderOffloadSink, ByteArray(8)))
                out.write(setProviderPeerRequest(XRandr.SetProviderOffloadSink, missing))
                out.write(request(XRandr.MajorOpcode, XRandr.SetProviderOutputSource, ByteArray(8)))
                out.write(setProviderPeerRequest(XRandr.SetProviderOutputSource, missing))
                out.write(request(XRandr.MajorOpcode, XRandr.ListProviderProperties, ByteArray(0)))
                out.write(u32Request(XRandr.ListProviderProperties, missing))
                out.write(request(XRandr.MajorOpcode, XRandr.QueryProviderProperty, ByteArray(4)))
                out.write(providerPropertyRequest(XRandr.QueryProviderProperty, missing, PrimaryAtom))
                out.write(request(XRandr.MajorOpcode, XRandr.ConfigureProviderProperty, ByteArray(8)))
                out.write(configureProviderPropertyRequest(missing, PrimaryAtom))
                out.write(request(XRandr.MajorOpcode, XRandr.ChangeProviderProperty, ByteArray(16)))
                out.write(changeProviderPropertyRequest(missing, PrimaryAtom, AtomAtom, format = 8, data = "x".encodeToByteArray()))
                out.write(request(XRandr.MajorOpcode, XRandr.DeleteProviderProperty, ByteArray(4)))
                out.write(providerPropertyRequest(XRandr.DeleteProviderProperty, missing, PrimaryAtom))
                out.write(request(XRandr.MajorOpcode, XRandr.GetProviderProperty, ByteArray(20)))
                out.write(getProviderPropertyRequest(missing, PrimaryAtom, AtomAtom))
                out.write(changeProviderPropertyRequest(missing, PrimaryAtom, AtomAtom, format = 8, mode = 3, data = "x".encodeToByteArray()))
                out.write(changeProviderPropertyRequest(missing, PrimaryAtom, AtomAtom, format = 7, data = "x".encodeToByteArray()))
                out.write(malformedChangeProviderPropertyRequest(missing, PrimaryAtom, AtomAtom, format = 8, unitCount = 5, data = "x".encodeToByteArray()))
                out.write(getProviderPropertyRequest(missing, PrimaryAtom, AtomAtom, delete = 2))
                out.write(getProviderPropertyRequest(missing, PrimaryAtom, AtomAtom, pending = 2))
                out.write(randrQueryVersionRequest(1, 6))
                out.flush()

                assertError(socket.getInputStream(), error = 16, badValue = 0, sequence = 1, minorOpcode = XRandr.GetProviderInfo)
                assertError(socket.getInputStream(), error = XRandr.BadProvider, badValue = missing, sequence = 2, minorOpcode = XRandr.GetProviderInfo)
                assertError(socket.getInputStream(), error = 16, badValue = 0, sequence = 3, minorOpcode = XRandr.SetProviderOffloadSink)
                assertError(socket.getInputStream(), error = XRandr.BadProvider, badValue = missing, sequence = 4, minorOpcode = XRandr.SetProviderOffloadSink)
                assertError(socket.getInputStream(), error = 16, badValue = 0, sequence = 5, minorOpcode = XRandr.SetProviderOutputSource)
                assertError(socket.getInputStream(), error = XRandr.BadProvider, badValue = missing, sequence = 6, minorOpcode = XRandr.SetProviderOutputSource)
                assertError(socket.getInputStream(), error = 16, badValue = 0, sequence = 7, minorOpcode = XRandr.ListProviderProperties)
                assertError(socket.getInputStream(), error = XRandr.BadProvider, badValue = missing, sequence = 8, minorOpcode = XRandr.ListProviderProperties)
                assertError(socket.getInputStream(), error = 16, badValue = 0, sequence = 9, minorOpcode = XRandr.QueryProviderProperty)
                assertError(socket.getInputStream(), error = XRandr.BadProvider, badValue = missing, sequence = 10, minorOpcode = XRandr.QueryProviderProperty)
                assertError(socket.getInputStream(), error = 16, badValue = 0, sequence = 11, minorOpcode = XRandr.ConfigureProviderProperty)
                assertError(socket.getInputStream(), error = XRandr.BadProvider, badValue = missing, sequence = 12, minorOpcode = XRandr.ConfigureProviderProperty)
                assertError(socket.getInputStream(), error = 16, badValue = 0, sequence = 13, minorOpcode = XRandr.ChangeProviderProperty)
                assertError(socket.getInputStream(), error = XRandr.BadProvider, badValue = missing, sequence = 14, minorOpcode = XRandr.ChangeProviderProperty)
                assertError(socket.getInputStream(), error = 16, badValue = 0, sequence = 15, minorOpcode = XRandr.DeleteProviderProperty)
                assertError(socket.getInputStream(), error = XRandr.BadProvider, badValue = missing, sequence = 16, minorOpcode = XRandr.DeleteProviderProperty)
                assertError(socket.getInputStream(), error = 16, badValue = 0, sequence = 17, minorOpcode = XRandr.GetProviderProperty)
                assertError(socket.getInputStream(), error = XRandr.BadProvider, badValue = missing, sequence = 18, minorOpcode = XRandr.GetProviderProperty)
                assertError(socket.getInputStream(), error = 2, badValue = 3, sequence = 19, minorOpcode = XRandr.ChangeProviderProperty)
                assertError(socket.getInputStream(), error = 2, badValue = 7, sequence = 20, minorOpcode = XRandr.ChangeProviderProperty)
                assertError(socket.getInputStream(), error = 16, badValue = 0, sequence = 21, minorOpcode = XRandr.ChangeProviderProperty)
                assertError(socket.getInputStream(), error = 2, badValue = 2, sequence = 22, minorOpcode = XRandr.GetProviderProperty)
                assertError(socket.getInputStream(), error = 2, badValue = 2, sequence = 23, minorOpcode = XRandr.GetProviderProperty)

                val recovered = readReply(socket.getInputStream())
                assertEquals(24, u16le(recovered, 2))
                assertEquals(XRandr.MajorVersion, u32le(recovered, 8))
                assertEquals(XRandr.MinorVersion, u32le(recovered, 12))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `RANDR monitor requests store user monitors emit configure events and recover`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val out = socket.getOutputStream()
                val missing = 0x0102_0304
                out.write(selectInputRequest(X11Ids.RootWindow, XRandr.ScreenChangeNotifyMask))
                out.write(changeWindowEventMaskRequest(X11Ids.RootWindow, XEventMasks.StructureNotify))
                out.write(request(XRandr.MajorOpcode, XRandr.SetMonitor, ByteArray(24)))
                out.write(setMonitorRequest(window = missing, name = PrimaryAtom, width = 50, height = 40, outputs = intArrayOf()))
                out.write(setMonitorRequest(name = missing, width = 50, height = 40, outputs = intArrayOf()))
                out.write(setMonitorRequest(name = PrimaryAtom, outputs = intArrayOf(missing)))
                out.write(setMonitorRequest(name = PrimaryAtom, height = 40, outputs = intArrayOf()))
                out.write(setMonitorRequest(name = PrimaryAtom, primary = 1, x = 10, y = 20, width = 50, height = 40, widthMm = 300, heightMm = 200, outputs = intArrayOf(XRandr.OutputId)))
                out.write(getMonitorsRequest(X11Ids.RootWindow, 1))
                out.write(setMonitorRequest(name = StringAtom, primary = 1, outputs = intArrayOf(XRandr.OutputId)))
                out.write(getMonitorsRequest(X11Ids.RootWindow, 1))
                out.write(request(XRandr.MajorOpcode, XRandr.DeleteMonitor, ByteArray(4)))
                out.write(deleteMonitorRequest(window = missing, name = PrimaryAtom))
                out.write(deleteMonitorRequest(name = missing))
                out.write(deleteMonitorRequest(name = AtomAtom))
                out.write(deleteMonitorRequest(name = PrimaryAtom))
                out.write(deleteMonitorRequest(name = StringAtom))
                out.write(getMonitorsRequest(X11Ids.RootWindow, 1))
                out.write(randrQueryVersionRequest(1, 6))
                out.flush()

                assertError(socket.getInputStream(), error = 16, badValue = 0, sequence = 3, minorOpcode = XRandr.SetMonitor)
                assertError(socket.getInputStream(), error = 3, badValue = missing, sequence = 4, minorOpcode = XRandr.SetMonitor)
                assertError(socket.getInputStream(), error = 5, badValue = missing, sequence = 5, minorOpcode = XRandr.SetMonitor)
                assertError(socket.getInputStream(), error = XRandr.BadOutput, badValue = missing, sequence = 6, minorOpcode = XRandr.SetMonitor)
                assertError(socket.getInputStream(), error = 2, badValue = 0, sequence = 7, minorOpcode = XRandr.SetMonitor)

                assertConfigureNotify(socket.getInputStream().readExactly(32), sequence = 8)
                assertScreenChangeNotify(socket.getInputStream().readExactly(32), sequence = 8)

                val explicit = readReply(socket.getInputStream())
                assertEquals(9, u16le(explicit, 2))
                assertEquals(7, u32le(explicit, 4))
                assertEquals(1, u32le(explicit, 12))
                assertEquals(1, u32le(explicit, 16))
                assertMonitorInfo(
                    explicit,
                    offset = 32,
                    name = PrimaryAtom,
                    primary = 1,
                    automatic = 0,
                    noutput = 1,
                    x = 10,
                    y = 20,
                    width = 50,
                    height = 40,
                    widthMm = 300,
                    heightMm = 200,
                )
                assertEquals(XRandr.OutputId, u32le(explicit, 56))

                assertConfigureNotify(socket.getInputStream().readExactly(32), sequence = 10)
                assertScreenChangeNotify(socket.getInputStream().readExactly(32), sequence = 10)

                val dynamic = readReply(socket.getInputStream())
                assertEquals(11, u16le(dynamic, 2))
                assertEquals(14, u32le(dynamic, 4))
                assertEquals(2, u32le(dynamic, 12))
                assertEquals(2, u32le(dynamic, 16))
                assertMonitorInfo(
                    dynamic,
                    offset = 32,
                    name = PrimaryAtom,
                    primary = 0,
                    automatic = 0,
                    noutput = 1,
                    x = 10,
                    y = 20,
                    width = 50,
                    height = 40,
                    widthMm = 300,
                    heightMm = 200,
                )
                assertMonitorInfo(
                    dynamic,
                    offset = 60,
                    name = StringAtom,
                    primary = 1,
                    automatic = 0,
                    noutput = 1,
                    x = 0,
                    y = 0,
                    width = 120,
                    height = 90,
                    widthMm = 32,
                    heightMm = 24,
                )
                assertEquals(XRandr.OutputId, u32le(dynamic, 56))
                assertEquals(XRandr.OutputId, u32le(dynamic, 84))

                assertError(socket.getInputStream(), error = 16, badValue = 0, sequence = 12, minorOpcode = XRandr.DeleteMonitor)
                assertError(socket.getInputStream(), error = 3, badValue = missing, sequence = 13, minorOpcode = XRandr.DeleteMonitor)
                assertError(socket.getInputStream(), error = 5, badValue = missing, sequence = 14, minorOpcode = XRandr.DeleteMonitor)
                assertError(socket.getInputStream(), error = 2, badValue = AtomAtom, sequence = 15, minorOpcode = XRandr.DeleteMonitor)

                assertConfigureNotify(socket.getInputStream().readExactly(32), sequence = 16)
                assertScreenChangeNotify(socket.getInputStream().readExactly(32), sequence = 16)
                assertConfigureNotify(socket.getInputStream().readExactly(32), sequence = 17)
                assertScreenChangeNotify(socket.getInputStream().readExactly(32), sequence = 17)

                assertMonitorReply(readReply(socket.getInputStream()), sequence = 18, primary = 0)

                val recovered = readReply(socket.getInputStream())
                assertEquals(19, u16le(recovered, 2))
                assertEquals(XRandr.MajorVersion, u32le(recovered, 8))
                assertEquals(XRandr.MinorVersion, u32le(recovered, 12))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `RANDR SetCrtcGamma validates fixed zero gamma ramp and recovers`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val out = socket.getOutputStream()
                out.write(request(XRandr.MajorOpcode, XRandr.SetCrtcGamma, ByteArray(4)))
                out.write(malformedSetCrtcGammaRequest(XRandr.CrtcId, 1))
                out.write(setCrtcGammaRequest(0x0102_0304, 0))
                out.write(setCrtcGammaRequest(XRandr.CrtcId, 1))
                out.write(setCrtcGammaRequest(XRandr.CrtcId, 0))
                out.write(u32Request(XRandr.GetCrtcGammaSize, XRandr.CrtcId))
                out.write(randrQueryVersionRequest(1, 6))
                out.flush()

                assertError(socket.getInputStream(), error = 16, badValue = 0, sequence = 1, minorOpcode = XRandr.SetCrtcGamma)
                assertError(socket.getInputStream(), error = 16, badValue = 0, sequence = 2, minorOpcode = XRandr.SetCrtcGamma)
                assertError(socket.getInputStream(), error = XRandr.BadCrtc, badValue = 0x0102_0304, sequence = 3, minorOpcode = XRandr.SetCrtcGamma)
                assertError(socket.getInputStream(), error = 2, badValue = 1, sequence = 4, minorOpcode = XRandr.SetCrtcGamma)

                val gammaSize = readReply(socket.getInputStream())
                assertEquals(6, u16le(gammaSize, 2))
                assertEquals(0, u32le(gammaSize, 4))
                assertEquals(XRandr.GammaRampSize, u16le(gammaSize, 8))

                val recovered = readReply(socket.getInputStream())
                assertEquals(7, u16le(recovered, 2))
                assertEquals(XRandr.MajorVersion, u32le(recovered, 8))
                assertEquals(XRandr.MinorVersion, u32le(recovered, 12))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `RANDR SetCrtcConfig validates fixed crtc configuration and recovers`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val out = socket.getOutputStream()
                out.write(request(XRandr.MajorOpcode, XRandr.SetCrtcConfig, ByteArray(20)))
                out.write(setCrtcConfigRequest(crtc = 0x0102_0304))
                out.write(setCrtcConfigRequest(configTimestamp = 0))
                out.write(setCrtcConfigRequest())
                out.write(setCrtcConfigRequest(timestamp = XRandr.ConfigTimestamp))
                out.write(setCrtcConfigRequest(x = 1))
                out.write(setCrtcConfigRequest(rotation = 2))
                out.write(setCrtcConfigRequest(mode = 0x0102_0304))
                out.write(setCrtcConfigRequest(outputs = intArrayOf(0x0102_0304)))
                out.write(setCrtcConfigRequest(outputs = intArrayOf()))
                out.write(setCrtcConfigRequest(mode = 0, outputs = intArrayOf()))
                out.write(setCrtcConfigRequest())
                out.write(crtcInfoRequest(XRandr.CrtcId))
                out.write(randrQueryVersionRequest(1, 6))
                out.flush()

                assertError(socket.getInputStream(), error = 16, badValue = 0, sequence = 1, minorOpcode = XRandr.SetCrtcConfig)
                assertError(socket.getInputStream(), error = XRandr.BadCrtc, badValue = 0x0102_0304, sequence = 2, minorOpcode = XRandr.SetCrtcConfig)
                assertSetCrtcConfigReply(readReply(socket.getInputStream()), sequence = 3, status = XRandr.InvalidConfigTime)
                assertSetCrtcConfigReply(readReply(socket.getInputStream()), sequence = 4, status = XRandr.Success)
                assertSetCrtcConfigReply(readReply(socket.getInputStream()), sequence = 5, status = XRandr.InvalidTime)
                assertError(socket.getInputStream(), error = 8, badValue = 0, sequence = 6, minorOpcode = XRandr.SetCrtcConfig)
                assertError(socket.getInputStream(), error = 2, badValue = 2, sequence = 7, minorOpcode = XRandr.SetCrtcConfig)
                assertError(socket.getInputStream(), error = XRandr.BadMode, badValue = 0x0102_0304, sequence = 8, minorOpcode = XRandr.SetCrtcConfig)
                assertError(socket.getInputStream(), error = XRandr.BadOutput, badValue = 0x0102_0304, sequence = 9, minorOpcode = XRandr.SetCrtcConfig)
                assertError(socket.getInputStream(), error = 8, badValue = 0, sequence = 10, minorOpcode = XRandr.SetCrtcConfig)

                assertSetCrtcConfigReply(readReply(socket.getInputStream()), sequence = 11, status = XRandr.Failed)
                assertSetCrtcConfigReply(readReply(socket.getInputStream()), sequence = 12, status = XRandr.Success)

                val crtcInfo = readReply(socket.getInputStream())
                assertEquals(13, u16le(crtcInfo, 2))
                assertEquals(XRandr.Success, crtcInfo[1].toInt() and 0xff)
                assertEquals(0, u16le(crtcInfo, 12))
                assertEquals(0, u16le(crtcInfo, 14))
                assertEquals(120, u16le(crtcInfo, 16))
                assertEquals(90, u16le(crtcInfo, 18))
                assertEquals(XRandr.ModeId, u32le(crtcInfo, 20))
                assertEquals(XRandr.OutputId, u32le(crtcInfo, 32))

                val recovered = readReply(socket.getInputStream())
                assertEquals(14, u16le(recovered, 2))
                assertEquals(XRandr.MajorVersion, u32le(recovered, 8))
                assertEquals(XRandr.MinorVersion, u32le(recovered, 12))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `RANDR CRTC transform reports identity metadata and applies pending transform on config set`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val out = socket.getOutputStream()
                out.write(request(XRandr.MajorOpcode, XRandr.GetCrtcTransform, ByteArray(0)))
                out.write(u32Request(XRandr.GetCrtcTransform, 0x0102_0304))
                out.write(request(XRandr.MajorOpcode, XRandr.SetCrtcTransform, ByteArray(40)))
                out.write(setCrtcTransformRequest(crtc = 0x0102_0304))
                out.write(setCrtcTransformRequest(transform = IdentityTransform.toMutableList().also { it[0] = 0x0002_0000 }))
                out.write(u32Request(XRandr.GetCrtcTransform, XRandr.CrtcId))
                out.write(setCrtcTransformRequest(filter = "nearest", values = intArrayOf(0x0001_0000)))
                out.write(u32Request(XRandr.GetCrtcTransform, XRandr.CrtcId))
                out.write(setCrtcConfigRequest())
                out.write(u32Request(XRandr.GetCrtcTransform, XRandr.CrtcId))
                out.write(randrQueryVersionRequest(1, 6))
                out.flush()

                assertError(socket.getInputStream(), error = 16, badValue = 0, sequence = 1, minorOpcode = XRandr.GetCrtcTransform)
                assertError(socket.getInputStream(), error = XRandr.BadCrtc, badValue = 0x0102_0304, sequence = 2, minorOpcode = XRandr.GetCrtcTransform)
                assertError(socket.getInputStream(), error = 16, badValue = 0, sequence = 3, minorOpcode = XRandr.SetCrtcTransform)
                assertError(socket.getInputStream(), error = XRandr.BadCrtc, badValue = 0x0102_0304, sequence = 4, minorOpcode = XRandr.SetCrtcTransform)
                assertError(socket.getInputStream(), error = 8, badValue = 0, sequence = 5, minorOpcode = XRandr.SetCrtcTransform)

                assertCrtcTransformReply(readReply(socket.getInputStream()), sequence = 6)
                assertCrtcTransformReply(readReply(socket.getInputStream()), sequence = 8, pendingFilter = "nearest", pendingValues = intArrayOf(0x0001_0000))
                assertSetCrtcConfigReply(readReply(socket.getInputStream()), sequence = 9, status = XRandr.Success)
                assertCrtcTransformReply(
                    readReply(socket.getInputStream()),
                    sequence = 10,
                    pendingFilter = "nearest",
                    pendingValues = intArrayOf(0x0001_0000),
                    currentFilter = "nearest",
                    currentValues = intArrayOf(0x0001_0000),
                )

                val recovered = readReply(socket.getInputStream())
                assertEquals(11, u16le(recovered, 2))
                assertEquals(XRandr.MajorVersion, u32le(recovered, 8))
                assertEquals(XRandr.MinorVersion, u32le(recovered, 12))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `RANDR panning reports disabled state and validates no-op updates`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val out = socket.getOutputStream()
                out.write(request(XRandr.MajorOpcode, XRandr.GetPanning, ByteArray(0)))
                out.write(u32Request(XRandr.GetPanning, 0x0102_0304))
                out.write(u32Request(XRandr.GetPanning, XRandr.CrtcId))
                out.write(request(XRandr.MajorOpcode, XRandr.SetPanning, ByteArray(28)))
                out.write(setPanningRequest(crtc = 0x0102_0304))
                out.write(setPanningRequest(width = 1))
                out.write(setPanningRequest())
                out.write(setPanningRequest(timestamp = XRandr.ConfigTimestamp))
                out.write(u32Request(XRandr.GetPanning, XRandr.CrtcId))
                out.write(randrQueryVersionRequest(1, 6))
                out.flush()

                assertError(socket.getInputStream(), error = 16, badValue = 0, sequence = 1, minorOpcode = XRandr.GetPanning)
                assertError(socket.getInputStream(), error = XRandr.BadCrtc, badValue = 0x0102_0304, sequence = 2, minorOpcode = XRandr.GetPanning)

                val initial = readReply(socket.getInputStream())
                val initialTimestamp = assertPanningReply(initial, sequence = 3, timestampAtLeast = XRandr.ConfigTimestamp)

                assertError(socket.getInputStream(), error = 16, badValue = 0, sequence = 4, minorOpcode = XRandr.SetPanning)
                assertError(socket.getInputStream(), error = XRandr.BadCrtc, badValue = 0x0102_0304, sequence = 5, minorOpcode = XRandr.SetPanning)
                assertError(socket.getInputStream(), error = 8, badValue = 0, sequence = 6, minorOpcode = XRandr.SetPanning)

                val successTimestamp = assertRandrStatusReply(readReply(socket.getInputStream()), sequence = 7, status = XRandr.Success)
                assertEquals(true, Integer.compareUnsigned(successTimestamp, initialTimestamp) > 0)
                assertRandrStatusReply(readReply(socket.getInputStream()), sequence = 8, status = XRandr.InvalidTime)

                assertPanningReply(readReply(socket.getInputStream()), sequence = 9, timestampAtLeast = successTimestamp)

                val recovered = readReply(socket.getInputStream())
                assertEquals(10, u16le(recovered, 2))
                assertEquals(XRandr.MajorVersion, u32le(recovered, 8))
                assertEquals(XRandr.MinorVersion, u32le(recovered, 12))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `RANDR output properties store values and report property metadata`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val out = socket.getOutputStream()
                out.write(configureOutputPropertyRequest(XRandr.OutputId, PrimaryAtom, pending = 0, range = 0))
                out.write(queryOutputPropertyRequest(XRandr.OutputId, PrimaryAtom))
                out.write(changeOutputPropertyRequest(XRandr.OutputId, PrimaryAtom, StringAtom, format = 8, mode = 0, data = "hello".encodeToByteArray()))
                out.write(changeOutputPropertyRequest(XRandr.OutputId, PrimaryAtom, StringAtom, format = 8, mode = 1, data = "pre-".encodeToByteArray()))
                out.write(changeOutputPropertyRequest(XRandr.OutputId, PrimaryAtom, StringAtom, format = 8, mode = 2, data = "-post".encodeToByteArray()))
                out.write(u32Request(XRandr.ListOutputProperties, XRandr.OutputId))
                out.write(getOutputPropertyRequest(XRandr.OutputId, PrimaryAtom, StringAtom, longLength = 4))
                out.write(getOutputPropertyRequest(XRandr.OutputId, PrimaryAtom, AtomAtom, longLength = 4))
                out.write(getOutputPropertyRequest(XRandr.OutputId, PrimaryAtom, StringAtom, delete = 1, longLength = 4))
                out.write(getOutputPropertyRequest(XRandr.OutputId, PrimaryAtom, StringAtom, longLength = 4))
                out.write(u32Request(XRandr.ListOutputProperties, XRandr.OutputId))
                out.write(queryOutputPropertyRequest(XRandr.OutputId, PrimaryAtom))
                out.write(randrQueryVersionRequest(1, 6))
                out.flush()

                val query = readReply(socket.getInputStream())
                assertEquals(2, u16le(query, 2))
                assertEquals(0, u32le(query, 4))
                assertEquals(0, query[8].toInt() and 0xff)
                assertEquals(0, query[9].toInt() and 0xff)
                assertEquals(0, query[10].toInt() and 0xff)

                val properties = readReply(socket.getInputStream())
                assertEquals(6, u16le(properties, 2))
                assertEquals(1, u32le(properties, 4))
                assertEquals(1, u16le(properties, 8))
                assertEquals(PrimaryAtom, u32le(properties, 32))

                val full = readReply(socket.getInputStream())
                assertOutputPropertyReply(full, sequence = 7, format = 8, type = StringAtom, bytesAfter = 0, items = 14)
                assertEquals("pre-hello-post", full.copyOfRange(32, 46).decodeToString())

                val mismatch = readReply(socket.getInputStream())
                assertOutputPropertyReply(mismatch, sequence = 8, format = 8, type = StringAtom, bytesAfter = 14, items = 0)

                val deleted = readReply(socket.getInputStream())
                assertOutputPropertyReply(deleted, sequence = 9, format = 8, type = StringAtom, bytesAfter = 0, items = 14)
                assertEquals("pre-hello-post", deleted.copyOfRange(32, 46).decodeToString())

                val absent = readReply(socket.getInputStream())
                assertOutputPropertyReply(absent, sequence = 10, format = 0, type = 0, bytesAfter = 0, items = 0)

                val emptyProperties = readReply(socket.getInputStream())
                assertEquals(11, u16le(emptyProperties, 2))
                assertEquals(0, u32le(emptyProperties, 4))
                assertEquals(0, u16le(emptyProperties, 8))

                assertError(socket.getInputStream(), error = 15, badValue = PrimaryAtom, sequence = 12, minorOpcode = XRandr.QueryOutputProperty)

                val recovered = readReply(socket.getInputStream())
                assertEquals(13, u16le(recovered, 2))
                assertEquals(XRandr.MajorVersion, u32le(recovered, 8))
                assertEquals(XRandr.MinorVersion, u32le(recovered, 12))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `RANDR pending output properties do not replace current value until requested`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val out = socket.getOutputStream()
                out.write(configureOutputPropertyRequest(XRandr.OutputId, PrimaryAtom, pending = 1, range = 0))
                out.write(changeOutputPropertyRequest(XRandr.OutputId, PrimaryAtom, StringAtom, format = 8, data = "pending".encodeToByteArray()))
                out.write(getOutputPropertyRequest(XRandr.OutputId, PrimaryAtom, 0, longLength = 4, pending = 0))
                out.write(getOutputPropertyRequest(XRandr.OutputId, PrimaryAtom, 0, longLength = 4, pending = 1))
                out.write(randrQueryVersionRequest(1, 6))
                out.flush()

                val current = readReply(socket.getInputStream())
                assertOutputPropertyReply(current, sequence = 3, format = 0, type = 0, bytesAfter = 0, items = 0)

                val pending = readReply(socket.getInputStream())
                assertOutputPropertyReply(pending, sequence = 4, format = 8, type = StringAtom, bytesAfter = 0, items = 7)
                assertEquals("pending", pending.copyOfRange(32, 39).decodeToString())

                val recovered = readReply(socket.getInputStream())
                assertEquals(5, u16le(recovered, 2))
                assertEquals(XRandr.MajorVersion, u32le(recovered, 8))
                assertEquals(XRandr.MinorVersion, u32le(recovered, 12))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `RANDR output property requests validate resources atoms values and lengths`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val out = socket.getOutputStream()
                val missing = 0x0102_0304
                out.write(queryOutputPropertyRequest(missing, PrimaryAtom))
                out.write(queryOutputPropertyRequest(XRandr.OutputId, missing))
                out.write(queryOutputPropertyRequest(XRandr.OutputId, StringAtom))
                out.write(configureOutputPropertyRequest(XRandr.OutputId, PrimaryAtom, pending = 2, range = 0))
                out.write(configureOutputPropertyRequest(XRandr.OutputId, PrimaryAtom, pending = 0, range = 1, validValues = intArrayOf(1)))
                out.write(changeOutputPropertyRequest(XRandr.OutputId, PrimaryAtom, StringAtom, format = 7, data = "x".encodeToByteArray()))
                out.write(changeOutputPropertyRequest(missing, PrimaryAtom, StringAtom, format = 8, data = "x".encodeToByteArray()))
                out.write(deleteOutputPropertyRequest(missing, PrimaryAtom))
                out.write(getOutputPropertyRequest(XRandr.OutputId, PrimaryAtom, StringAtom, delete = 2))
                out.write(getOutputPropertyRequest(XRandr.OutputId, missing, StringAtom))
                out.write(request(XRandr.MajorOpcode, XRandr.GetOutputProperty, ByteArray(20)))
                out.write(randrQueryVersionRequest(1, 6))
                out.flush()

                assertError(socket.getInputStream(), error = XRandr.BadOutput, badValue = missing, sequence = 1, minorOpcode = XRandr.QueryOutputProperty)
                assertError(socket.getInputStream(), error = 5, badValue = missing, sequence = 2, minorOpcode = XRandr.QueryOutputProperty)
                assertError(socket.getInputStream(), error = 15, badValue = StringAtom, sequence = 3, minorOpcode = XRandr.QueryOutputProperty)
                assertError(socket.getInputStream(), error = 2, badValue = 2, sequence = 4, minorOpcode = XRandr.ConfigureOutputProperty)
                assertError(socket.getInputStream(), error = 2, badValue = 1, sequence = 5, minorOpcode = XRandr.ConfigureOutputProperty)
                assertError(socket.getInputStream(), error = 2, badValue = 7, sequence = 6, minorOpcode = XRandr.ChangeOutputProperty)
                assertError(socket.getInputStream(), error = XRandr.BadOutput, badValue = missing, sequence = 7, minorOpcode = XRandr.ChangeOutputProperty)
                assertError(socket.getInputStream(), error = XRandr.BadOutput, badValue = missing, sequence = 8, minorOpcode = XRandr.DeleteOutputProperty)
                assertError(socket.getInputStream(), error = 2, badValue = 2, sequence = 9, minorOpcode = XRandr.GetOutputProperty)
                assertError(socket.getInputStream(), error = 5, badValue = missing, sequence = 10, minorOpcode = XRandr.GetOutputProperty)
                assertError(socket.getInputStream(), error = 16, badValue = 0, sequence = 11, minorOpcode = XRandr.GetOutputProperty)

                val recovered = readReply(socket.getInputStream())
                assertEquals(12, u16le(recovered, 2))
                assertEquals(XRandr.MajorVersion, u32le(recovered, 8))
                assertEquals(XRandr.MinorVersion, u32le(recovered, 12))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `RANDR output property configured values are enforced`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val out = socket.getOutputStream()
                out.write(configureOutputPropertyRequest(XRandr.OutputId, PrimaryAtom, pending = 0, range = 0, validValues = intArrayOf(3, 7)))
                out.write(changeOutputPropertyRequest(XRandr.OutputId, PrimaryAtom, AtomAtom, format = 32, data = int32le(3, 7)))
                out.write(changeOutputPropertyRequest(XRandr.OutputId, PrimaryAtom, AtomAtom, format = 32, data = int32le(3, 4)))
                out.write(configureOutputPropertyRequest(XRandr.OutputId, PrimaryAtom, pending = 0, range = 1, validValues = intArrayOf(10, 20)))
                out.write(changeOutputPropertyRequest(XRandr.OutputId, PrimaryAtom, AtomAtom, format = 32, data = int32le(15)))
                out.write(changeOutputPropertyRequest(XRandr.OutputId, PrimaryAtom, AtomAtom, format = 32, data = int32le(21)))
                out.write(randrQueryVersionRequest(1, 6))
                out.flush()

                assertError(socket.getInputStream(), error = 2, badValue = 4, sequence = 3, minorOpcode = XRandr.ChangeOutputProperty)
                assertError(socket.getInputStream(), error = 2, badValue = 21, sequence = 6, minorOpcode = XRandr.ChangeOutputProperty)

                val recovered = readReply(socket.getInputStream())
                assertEquals(7, u16le(recovered, 2))
                assertEquals(XRandr.MajorVersion, u32le(recovered, 8))
                assertEquals(XRandr.MinorVersion, u32le(recovered, 12))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `RANDR output property notify events are delivered for changes and deletes`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val out = socket.getOutputStream()
                out.write(selectInputRequest(X11Ids.RootWindow, XRandr.OutputPropertyNotifyMask))
                out.write(changeOutputPropertyRequest(XRandr.OutputId, PrimaryAtom, StringAtom, format = 8, data = "one".encodeToByteArray()))
                out.write(deleteOutputPropertyRequest(XRandr.OutputId, PrimaryAtom))
                out.write(changeOutputPropertyRequest(XRandr.OutputId, PrimaryAtom, StringAtom, format = 8, data = "two".encodeToByteArray()))
                out.write(getOutputPropertyRequest(XRandr.OutputId, PrimaryAtom, 0, delete = 1, longLength = 1))
                out.write(randrQueryVersionRequest(1, 6))
                out.flush()

                assertOutputPropertyNotify(socket.getInputStream().readExactly(32), sequence = 2, atom = PrimaryAtom, state = XRandr.PropertyNewValue)
                assertOutputPropertyNotify(socket.getInputStream().readExactly(32), sequence = 3, atom = PrimaryAtom, state = XRandr.PropertyDeleted)
                assertOutputPropertyNotify(socket.getInputStream().readExactly(32), sequence = 4, atom = PrimaryAtom, state = XRandr.PropertyNewValue)

                val deleted = readReply(socket.getInputStream())
                assertOutputPropertyReply(deleted, sequence = 5, format = 8, type = StringAtom, bytesAfter = 0, items = 3)
                assertEquals("two", deleted.copyOfRange(32, 35).decodeToString())
                assertOutputPropertyNotify(socket.getInputStream().readExactly(32), sequence = 5, atom = PrimaryAtom, state = XRandr.PropertyDeleted)

                val recovered = readReply(socket.getInputStream())
                assertEquals(6, u16le(recovered, 2))
                assertEquals(XRandr.MajorVersion, u32le(recovered, 8))
                assertEquals(XRandr.MinorVersion, u32le(recovered, 12))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `RANDR output property notify selection is removed with destroyed window`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val child = 0x0020_0101
                val out = socket.getOutputStream()
                out.write(createWindowRequest(child))
                out.write(selectInputRequest(child, XRandr.OutputPropertyNotifyMask))
                out.write(destroyWindowRequest(child))
                out.write(changeOutputPropertyRequest(XRandr.OutputId, PrimaryAtom, StringAtom, format = 8, data = "quiet".encodeToByteArray()))
                out.write(randrQueryVersionRequest(1, 6))
                out.flush()

                val recovered = readReply(socket.getInputStream())
                assertEquals(5, u16le(recovered, 2))
                assertEquals(XRandr.MajorVersion, u32le(recovered, 8))
                assertEquals(XRandr.MinorVersion, u32le(recovered, 12))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `RANDR SetOutputPrimary updates primary output and emits output change notify`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val out = socket.getOutputStream()
                out.write(selectInputRequest(X11Ids.RootWindow, XRandr.ScreenChangeNotifyMask or XRandr.OutputChangeNotifyMask))
                out.write(changeWindowEventMaskRequest(X11Ids.RootWindow, XEventMasks.StructureNotify))
                out.write(u32Request(XRandr.GetOutputPrimary, X11Ids.RootWindow))
                out.write(setOutputPrimaryRequest(X11Ids.RootWindow, XRandr.OutputId))
                out.write(getMonitorsRequest(X11Ids.RootWindow, 1))
                out.write(setOutputPrimaryRequest(X11Ids.RootWindow, XRandr.OutputId))
                out.write(u32Request(XRandr.GetOutputPrimary, X11Ids.RootWindow))
                out.write(setOutputPrimaryRequest(X11Ids.RootWindow, 0))
                out.write(getMonitorsRequest(X11Ids.RootWindow, 1))
                out.write(setOutputPrimaryRequest(X11Ids.RootWindow, 0))
                out.write(u32Request(XRandr.GetOutputPrimary, X11Ids.RootWindow))
                out.write(setOutputPrimaryRequest(0x0102_0304, XRandr.OutputId))
                out.write(setOutputPrimaryRequest(X11Ids.RootWindow, 0x0102_0304))
                out.write(request(XRandr.MajorOpcode, XRandr.SetOutputPrimary, ByteArray(4)))
                out.write(randrQueryVersionRequest(1, 6))
                out.flush()

                val initial = readReply(socket.getInputStream())
                assertEquals(3, u16le(initial, 2))
                assertEquals(0, u32le(initial, 8))

                assertConfigureNotify(socket.getInputStream().readExactly(32), sequence = 4)
                assertScreenChangeNotify(socket.getInputStream().readExactly(32), sequence = 4)
                assertOutputChangeNotify(socket.getInputStream().readExactly(32), sequence = 4)

                assertMonitorReply(readReply(socket.getInputStream()), sequence = 5, primary = 1)

                val primary = readReply(socket.getInputStream())
                assertEquals(7, u16le(primary, 2))
                assertEquals(XRandr.OutputId, u32le(primary, 8))

                assertConfigureNotify(socket.getInputStream().readExactly(32), sequence = 8)
                assertScreenChangeNotify(socket.getInputStream().readExactly(32), sequence = 8)
                assertOutputChangeNotify(socket.getInputStream().readExactly(32), sequence = 8)

                assertMonitorReply(readReply(socket.getInputStream()), sequence = 9, primary = 0)

                val none = readReply(socket.getInputStream())
                assertEquals(11, u16le(none, 2))
                assertEquals(0, u32le(none, 8))

                assertError(socket.getInputStream(), error = 3, badValue = 0x0102_0304, sequence = 12, minorOpcode = XRandr.SetOutputPrimary)
                assertError(socket.getInputStream(), error = XRandr.BadOutput, badValue = 0x0102_0304, sequence = 13, minorOpcode = XRandr.SetOutputPrimary)
                assertError(socket.getInputStream(), error = 16, badValue = 0, sequence = 14, minorOpcode = XRandr.SetOutputPrimary)

                val recovered = readReply(socket.getInputStream())
                assertEquals(15, u16le(recovered, 2))
                assertEquals(XRandr.MajorVersion, u32le(recovered, 8))
                assertEquals(XRandr.MinorVersion, u32le(recovered, 12))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `RANDR SetScreenSize validates and updates physical size metadata`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val out = socket.getOutputStream()
                out.write(selectInputRequest(X11Ids.RootWindow, XRandr.ScreenChangeNotifyMask))
                out.write(changeWindowEventMaskRequest(X11Ids.RootWindow, XEventMasks.StructureNotify))
                out.write(request(XRandr.MajorOpcode, XRandr.SetScreenSize, ByteArray(12)))
                out.write(setScreenSizeRequest(0x0102_0304, width = 120, height = 90, widthMm = 300, heightMm = 200))
                out.write(setScreenSizeRequest(X11Ids.RootWindow, width = 119, height = 90, widthMm = 300, heightMm = 200))
                out.write(setScreenSizeRequest(X11Ids.RootWindow, width = 120, height = 91, widthMm = 300, heightMm = 200))
                out.write(setScreenSizeRequest(X11Ids.RootWindow, width = 120, height = 90, widthMm = 0, heightMm = 200))
                out.write(setScreenSizeRequest(X11Ids.RootWindow, width = 120, height = 90, widthMm = 300, heightMm = 0))
                out.write(setScreenSizeRequest(X11Ids.RootWindow, width = 120, height = 90, widthMm = 300, heightMm = 200))
                out.write(u32Request(XRandr.GetScreenInfo, X11Ids.RootWindow))
                out.write(outputInfoRequest(XRandr.OutputId))
                out.write(setScreenSizeRequest(X11Ids.RootWindow, width = 120, height = 90, widthMm = 300, heightMm = 200))
                out.write(u32Request(XRandr.GetScreenInfo, X11Ids.RootWindow))
                out.write(randrQueryVersionRequest(1, 6))
                out.flush()

                assertError(socket.getInputStream(), error = 16, badValue = 0, sequence = 3, minorOpcode = XRandr.SetScreenSize)
                assertError(socket.getInputStream(), error = 3, badValue = 0x0102_0304, sequence = 4, minorOpcode = XRandr.SetScreenSize)
                assertError(socket.getInputStream(), error = 2, badValue = 119, sequence = 5, minorOpcode = XRandr.SetScreenSize)
                assertError(socket.getInputStream(), error = 2, badValue = 91, sequence = 6, minorOpcode = XRandr.SetScreenSize)
                assertError(socket.getInputStream(), error = 2, badValue = 0, sequence = 7, minorOpcode = XRandr.SetScreenSize)
                assertError(socket.getInputStream(), error = 2, badValue = 0, sequence = 8, minorOpcode = XRandr.SetScreenSize)

                assertConfigureNotify(socket.getInputStream().readExactly(32), sequence = 9)
                assertScreenChangeNotify(socket.getInputStream().readExactly(32), sequence = 9, widthMm = 300, heightMm = 200)

                val screenInfo = readReply(socket.getInputStream())
                assertEquals(10, u16le(screenInfo, 2))
                assertEquals(300, u16le(screenInfo, 36))
                assertEquals(200, u16le(screenInfo, 38))

                val outputInfo = readReply(socket.getInputStream())
                assertEquals(11, u16le(outputInfo, 2))
                assertEquals(300, u32le(outputInfo, 16))
                assertEquals(200, u32le(outputInfo, 20))

                val unchangedScreenInfo = readReply(socket.getInputStream())
                assertEquals(13, u16le(unchangedScreenInfo, 2))
                assertEquals(300, u16le(unchangedScreenInfo, 36))
                assertEquals(200, u16le(unchangedScreenInfo, 38))

                val recovered = readReply(socket.getInputStream())
                assertEquals(14, u16le(recovered, 2))
                assertEquals(XRandr.MajorVersion, u32le(recovered, 8))
                assertEquals(XRandr.MinorVersion, u32le(recovered, 12))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `RANDR swaps replies for big endian clients`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket, byteOrderByte = 0x42)
                val out = socket.getOutputStream()
                out.write(randrQueryVersionRequestBe(1, 6))
                out.write(u32RequestBe(XRandr.GetScreenResources, X11Ids.RootWindow))
                out.write(outputInfoRequestBe(XRandr.OutputId))
                out.write(crtcInfoRequestBe(XRandr.CrtcId))
                out.write(getMonitorsRequestBe(X11Ids.RootWindow, 1))
                out.write(configureOutputPropertyRequestBe(XRandr.OutputId, StringAtom, pending = 0, range = 0, validValues = intArrayOf(0x0102_0304, 0x0a0b_0c0d)))
                out.write(queryOutputPropertyRequestBe(XRandr.OutputId, StringAtom))
                out.write(changeOutputPropertyRequestBe(XRandr.OutputId, PrimaryAtom, AtomAtom, format = 16, data = byteArrayOf(0x11, 0x22, 0x33, 0x44)))
                out.write(getOutputPropertyRequestBe(XRandr.OutputId, PrimaryAtom, AtomAtom, longLength = 1))
                out.write(u32RequestBe(XRandr.GetPanning, XRandr.CrtcId))
                out.write(setPanningRequestBe())
                out.write(setPanningRequestBe(timestamp = XRandr.ConfigTimestamp))
                out.flush()

                val version = readReply(socket.getInputStream())
                assertEquals(1, u16be(version, 2))
                assertEquals(XRandr.MajorVersion, u32be(version, 8))
                assertEquals(XRandr.MinorVersion, u32be(version, 12))

                val resources = readReplyBe(socket.getInputStream())
                assertEquals(2, u16be(resources, 2))
                assertEquals(12, u32be(resources, 4))
                assertEquals(1, u16be(resources, 16))
                assertEquals(1, u16be(resources, 18))
                assertEquals(1, u16be(resources, 20))
                assertEquals(XRandr.CrtcId, u32be(resources, 32))
                assertEquals(XRandr.OutputId, u32be(resources, 36))
                assertEquals(XRandr.ModeId, u32be(resources, 40))
                assertEquals(120, u16be(resources, 44))
                assertEquals(90, u16be(resources, 46))

                val output = readReplyBe(socket.getInputStream())
                assertEquals(3, u16be(output, 2))
                assertEquals(XRandr.CrtcId, u32be(output, 12))
                assertEquals(32, u32be(output, 16))
                assertEquals(24, u32be(output, 20))
                assertEquals(XRandr.CrtcId, u32be(output, 36))
                assertEquals(XRandr.ModeId, u32be(output, 40))

                val crtc = readReplyBe(socket.getInputStream())
                assertEquals(4, u16be(crtc, 2))
                assertEquals(120, u16be(crtc, 16))
                assertEquals(90, u16be(crtc, 18))
                assertEquals(XRandr.ModeId, u32be(crtc, 20))
                assertEquals(XRandr.OutputId, u32be(crtc, 32))

                val monitors = readReplyBe(socket.getInputStream())
                assertEquals(5, u16be(monitors, 2))
                assertEquals(7, u32be(monitors, 4))
                assertEquals(1, u32be(monitors, 12))
                assertEquals(1, u32be(monitors, 16))
                assertEquals(120, u16be(monitors, 44))
                assertEquals(90, u16be(monitors, 46))
                assertEquals(32, u32be(monitors, 48))
                assertEquals(24, u32be(monitors, 52))
                assertEquals(XRandr.OutputId, u32be(monitors, 56))

                val query = readReplyBe(socket.getInputStream())
                assertEquals(7, u16be(query, 2))
                assertEquals(2, u32be(query, 4))
                assertEquals(0, query[8].toInt() and 0xff)
                assertEquals(0, query[9].toInt() and 0xff)
                assertEquals(0x0102_0304, u32be(query, 32))
                assertEquals(0x0a0b_0c0d, u32be(query, 36))

                val property = readReplyBe(socket.getInputStream())
                assertEquals(9, u16be(property, 2))
                assertEquals(1, u32be(property, 4))
                assertEquals(16, property[1].toInt() and 0xff)
                assertEquals(AtomAtom, u32be(property, 8))
                assertEquals(0, u32be(property, 12))
                assertEquals(2, u32be(property, 16))
                assertEquals(0x11, property[32].toInt() and 0xff)
                assertEquals(0x22, property[33].toInt() and 0xff)
                assertEquals(0x33, property[34].toInt() and 0xff)
                assertEquals(0x44, property[35].toInt() and 0xff)

                val initialPanningTimestamp = assertPanningReplyBe(readReplyBe(socket.getInputStream()), sequence = 10, timestampAtLeast = XRandr.ConfigTimestamp)
                val panningTimestamp = assertRandrStatusReplyBe(readReplyBe(socket.getInputStream()), sequence = 11, status = XRandr.Success)
                assertEquals(true, Integer.compareUnsigned(panningTimestamp, initialPanningTimestamp) > 0)
                assertRandrStatusReplyBe(readReplyBe(socket.getInputStream()), sequence = 12, status = XRandr.InvalidTime)
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    private fun assertScreenResources(reply: ByteArray, sequence: Int) {
        assertEquals(sequence, u16le(reply, 2))
        assertEquals(12, u32le(reply, 4))
        assertEquals(1, u16le(reply, 16))
        assertEquals(1, u16le(reply, 18))
        assertEquals(1, u16le(reply, 20))
        assertEquals("120x90".length, u16le(reply, 22))
        assertEquals(XRandr.CrtcId, u32le(reply, 32))
        assertEquals(XRandr.OutputId, u32le(reply, 36))
        assertEquals(XRandr.ModeId, u32le(reply, 40))
        assertEquals(120, u16le(reply, 44))
        assertEquals(90, u16le(reply, 46))
        assertEquals(120 * 90 * XRandr.RefreshRate, u32le(reply, 48))
        assertEquals(120, u16le(reply, 52))
        assertEquals(120, u16le(reply, 54))
        assertEquals(120, u16le(reply, 56))
        assertEquals(0, u16le(reply, 58))
        assertEquals(90, u16le(reply, 60))
        assertEquals(90, u16le(reply, 62))
        assertEquals(90, u16le(reply, 64))
        assertEquals("120x90".length, u16le(reply, 66))
        assertEquals(0, u32le(reply, 68))
        assertEquals("120x90", reply.copyOfRange(72, 78).decodeToString())
    }

    private fun setup(socket: Socket, byteOrderByte: Int = 0x6c) {
        val setup = ByteArray(12)
        setup[0] = byteOrderByte.toByte()
        when (byteOrderByte) {
            0x42 -> put16be(setup, 2, 11)
            else -> put16le(setup, 2, 11)
        }
        socket.getOutputStream().write(setup)
        socket.getOutputStream().flush()
        val prefix = socket.getInputStream().readExactly(8)
        assertEquals(1, prefix[0].toInt())
        val extra = if (byteOrderByte == 0x42) u16be(prefix, 6) else u16le(prefix, 6)
        socket.getInputStream().readExactly(extra * 4)
    }

    private fun queryExtensionRequest(name: String): ByteArray {
        val encoded = name.encodeToByteArray()
        val padded = (encoded.size + 3) and -4
        val body = ByteArray(4 + padded)
        put16le(body, 0, encoded.size)
        encoded.copyInto(body, 4)
        return request(98, 0, body)
    }

    private fun listExtensionsRequest(): ByteArray =
        request(99, 0, ByteArray(0))

    private fun randrQueryVersionRequest(major: Int, minor: Int): ByteArray {
        val body = ByteArray(8)
        put32le(body, 0, major)
        put32le(body, 4, minor)
        return request(XRandr.MajorOpcode, XRandr.QueryVersion, body)
    }

    private fun randrQueryVersionRequestBe(major: Int, minor: Int): ByteArray {
        val body = ByteArray(8)
        put32be(body, 0, major)
        put32be(body, 4, minor)
        return requestBe(XRandr.MajorOpcode, XRandr.QueryVersion, body)
    }

    private fun randrSelectInputRequest(window: Int, enable: Int): ByteArray {
        val body = ByteArray(8)
        put32le(body, 0, window)
        put16le(body, 4, enable)
        return request(XRandr.MajorOpcode, XRandr.SelectInput, body)
    }

    private fun u32Request(minorOpcode: Int, value: Int): ByteArray {
        val body = ByteArray(4)
        put32le(body, 0, value)
        return request(XRandr.MajorOpcode, minorOpcode, body)
    }

    private fun u32RequestBe(minorOpcode: Int, value: Int): ByteArray {
        val body = ByteArray(4)
        put32be(body, 0, value)
        return requestBe(XRandr.MajorOpcode, minorOpcode, body)
    }

    private fun outputInfoRequest(output: Int): ByteArray {
        val body = ByteArray(8)
        put32le(body, 0, output)
        return request(XRandr.MajorOpcode, XRandr.GetOutputInfo, body)
    }

    private fun outputInfoRequestBe(output: Int): ByteArray {
        val body = ByteArray(8)
        put32be(body, 0, output)
        return requestBe(XRandr.MajorOpcode, XRandr.GetOutputInfo, body)
    }

    private fun crtcInfoRequest(crtc: Int): ByteArray {
        val body = ByteArray(8)
        put32le(body, 0, crtc)
        return request(XRandr.MajorOpcode, XRandr.GetCrtcInfo, body)
    }

    private fun crtcInfoRequestBe(crtc: Int): ByteArray {
        val body = ByteArray(8)
        put32be(body, 0, crtc)
        return requestBe(XRandr.MajorOpcode, XRandr.GetCrtcInfo, body)
    }

    private fun getMonitorsRequest(window: Int, getActive: Int): ByteArray {
        val body = ByteArray(8)
        put32le(body, 0, window)
        body[4] = getActive.toByte()
        return request(XRandr.MajorOpcode, XRandr.GetMonitors, body)
    }

    private fun getMonitorsRequestBe(window: Int, getActive: Int): ByteArray {
        val body = ByteArray(8)
        put32be(body, 0, window)
        body[4] = getActive.toByte()
        return requestBe(XRandr.MajorOpcode, XRandr.GetMonitors, body)
    }

    private fun selectInputRequest(window: Int, eventMask: Int): ByteArray {
        val body = ByteArray(8)
        put32le(body, 0, window)
        put16le(body, 4, eventMask)
        return request(XRandr.MajorOpcode, XRandr.SelectInput, body)
    }

    private fun changeWindowEventMaskRequest(window: Int, eventMask: Int): ByteArray {
        val body = ByteArray(12)
        put32le(body, 0, window)
        put32le(body, 4, 1 shl 11)
        put32le(body, 8, eventMask)
        return request(2, 0, body)
    }

    private fun setOutputPrimaryRequest(window: Int, output: Int): ByteArray {
        val body = ByteArray(8)
        put32le(body, 0, window)
        put32le(body, 4, output)
        return request(XRandr.MajorOpcode, XRandr.SetOutputPrimary, body)
    }

    private fun setScreenSizeRequest(window: Int, width: Int, height: Int, widthMm: Int, heightMm: Int): ByteArray {
        val body = ByteArray(16)
        put32le(body, 0, window)
        put16le(body, 4, width)
        put16le(body, 6, height)
        put32le(body, 8, widthMm)
        put32le(body, 12, heightMm)
        return request(XRandr.MajorOpcode, XRandr.SetScreenSize, body)
    }

    private fun setCrtcGammaRequest(crtc: Int, size: Int): ByteArray {
        val body = ByteArray(8 + ((size * 6 + 3) and -4))
        put32le(body, 0, crtc)
        put16le(body, 4, size)
        return request(XRandr.MajorOpcode, XRandr.SetCrtcGamma, body)
    }

    private fun malformedSetCrtcGammaRequest(crtc: Int, size: Int): ByteArray {
        val body = ByteArray(8)
        put32le(body, 0, crtc)
        put16le(body, 4, size)
        return request(XRandr.MajorOpcode, XRandr.SetCrtcGamma, body)
    }

    private fun setCrtcConfigRequest(
        crtc: Int = XRandr.CrtcId,
        timestamp: Int = 0,
        configTimestamp: Int = XRandr.ConfigTimestamp,
        x: Int = 0,
        y: Int = 0,
        mode: Int = XRandr.ModeId,
        rotation: Int = XRandr.Rotate0,
        outputs: IntArray = intArrayOf(XRandr.OutputId),
    ): ByteArray {
        val body = ByteArray(24 + outputs.size * 4)
        put32le(body, 0, crtc)
        put32le(body, 4, timestamp)
        put32le(body, 8, configTimestamp)
        put16le(body, 12, x)
        put16le(body, 14, y)
        put32le(body, 16, mode)
        put16le(body, 20, rotation)
        outputs.forEachIndexed { index, output -> put32le(body, 24 + index * 4, output) }
        return request(XRandr.MajorOpcode, XRandr.SetCrtcConfig, body)
    }

    private fun setCrtcTransformRequest(
        crtc: Int = XRandr.CrtcId,
        transform: List<Int> = IdentityTransform,
        filter: String = "",
        values: IntArray = intArrayOf(),
    ): ByteArray {
        val filterBytes = filter.encodeToByteArray()
        val body = ByteArray(44 + ((filterBytes.size + 3) and -4) + values.size * 4)
        put32le(body, 0, crtc)
        transform.forEachIndexed { index, value -> put32le(body, 4 + index * 4, value) }
        put16le(body, 40, filterBytes.size)
        filterBytes.copyInto(body, 44)
        val valuesOffset = 44 + ((filterBytes.size + 3) and -4)
        values.forEachIndexed { index, value -> put32le(body, valuesOffset + index * 4, value) }
        return request(XRandr.MajorOpcode, XRandr.SetCrtcTransform, body)
    }

    private fun setPanningRequest(
        crtc: Int = XRandr.CrtcId,
        timestamp: Int = 0,
        left: Int = 0,
        top: Int = 0,
        width: Int = 0,
        height: Int = 0,
        trackLeft: Int = 0,
        trackTop: Int = 0,
        trackWidth: Int = 0,
        trackHeight: Int = 0,
        borderLeft: Int = 0,
        borderTop: Int = 0,
        borderRight: Int = 0,
        borderBottom: Int = 0,
    ): ByteArray {
        val body = ByteArray(32)
        put32le(body, 0, crtc)
        put32le(body, 4, timestamp)
        intArrayOf(
            left,
            top,
            width,
            height,
            trackLeft,
            trackTop,
            trackWidth,
            trackHeight,
            borderLeft,
            borderTop,
            borderRight,
            borderBottom,
        ).forEachIndexed { index, value -> put16le(body, 8 + index * 2, value) }
        return request(XRandr.MajorOpcode, XRandr.SetPanning, body)
    }

    private fun setPanningRequestBe(
        crtc: Int = XRandr.CrtcId,
        timestamp: Int = 0,
    ): ByteArray {
        val body = ByteArray(32)
        put32be(body, 0, crtc)
        put32be(body, 4, timestamp)
        return requestBe(XRandr.MajorOpcode, XRandr.SetPanning, body)
    }

    private fun getProviderInfoRequest(provider: Int, configTimestamp: Int = XRandr.ConfigTimestamp): ByteArray {
        val body = ByteArray(8)
        put32le(body, 0, provider)
        put32le(body, 4, configTimestamp)
        return request(XRandr.MajorOpcode, XRandr.GetProviderInfo, body)
    }

    private fun setProviderPeerRequest(minorOpcode: Int, provider: Int, peerProvider: Int = 0, configTimestamp: Int = XRandr.ConfigTimestamp): ByteArray {
        val body = ByteArray(12)
        put32le(body, 0, provider)
        put32le(body, 4, peerProvider)
        put32le(body, 8, configTimestamp)
        return request(XRandr.MajorOpcode, minorOpcode, body)
    }

    private fun providerPropertyRequest(minorOpcode: Int, provider: Int, property: Int): ByteArray {
        val body = ByteArray(8)
        put32le(body, 0, provider)
        put32le(body, 4, property)
        return request(XRandr.MajorOpcode, minorOpcode, body)
    }

    private fun configureProviderPropertyRequest(
        provider: Int,
        property: Int,
        pending: Int = 0,
        range: Int = 0,
        validValues: IntArray = intArrayOf(),
    ): ByteArray {
        val body = ByteArray(12 + validValues.size * 4)
        put32le(body, 0, provider)
        put32le(body, 4, property)
        body[8] = pending.toByte()
        body[9] = range.toByte()
        validValues.forEachIndexed { index, value -> put32le(body, 12 + index * 4, value) }
        return request(XRandr.MajorOpcode, XRandr.ConfigureProviderProperty, body)
    }

    private fun changeProviderPropertyRequest(
        provider: Int,
        property: Int,
        type: Int,
        format: Int,
        mode: Int = 0,
        data: ByteArray,
    ): ByteArray {
        val padded = (data.size + 3) and -4
        val body = ByteArray(20 + padded)
        put32le(body, 0, provider)
        put32le(body, 4, property)
        put32le(body, 8, type)
        body[12] = format.toByte()
        body[13] = mode.toByte()
        put32le(body, 16, when (format) {
            16 -> data.size / 2
            32 -> data.size / 4
            else -> data.size
        })
        data.copyInto(body, 20)
        return request(XRandr.MajorOpcode, XRandr.ChangeProviderProperty, body)
    }

    private fun malformedChangeProviderPropertyRequest(
        provider: Int,
        property: Int,
        type: Int,
        format: Int,
        unitCount: Int,
        data: ByteArray,
    ): ByteArray {
        val padded = (data.size + 3) and -4
        val body = ByteArray(20 + padded)
        put32le(body, 0, provider)
        put32le(body, 4, property)
        put32le(body, 8, type)
        body[12] = format.toByte()
        put32le(body, 16, unitCount)
        data.copyInto(body, 20)
        return request(XRandr.MajorOpcode, XRandr.ChangeProviderProperty, body)
    }

    private fun getProviderPropertyRequest(
        provider: Int,
        property: Int,
        type: Int,
        longOffset: Int = 0,
        longLength: Int = 4,
        delete: Int = 0,
        pending: Int = 0,
    ): ByteArray {
        val body = ByteArray(24)
        put32le(body, 0, provider)
        put32le(body, 4, property)
        put32le(body, 8, type)
        put32le(body, 12, longOffset)
        put32le(body, 16, longLength)
        body[20] = delete.toByte()
        body[21] = pending.toByte()
        return request(XRandr.MajorOpcode, XRandr.GetProviderProperty, body)
    }

    private fun setMonitorRequest(
        window: Int = X11Ids.RootWindow,
        name: Int,
        primary: Int = 0,
        automatic: Int = 0,
        x: Int = 0,
        y: Int = 0,
        width: Int = 0,
        height: Int = 0,
        widthMm: Int = 0,
        heightMm: Int = 0,
        outputs: IntArray = intArrayOf(XRandr.OutputId),
    ): ByteArray {
        val body = ByteArray(28 + outputs.size * 4)
        put32le(body, 0, window)
        put32le(body, 4, name)
        body[8] = primary.toByte()
        body[9] = automatic.toByte()
        put16le(body, 10, outputs.size)
        put16le(body, 12, x)
        put16le(body, 14, y)
        put16le(body, 16, width)
        put16le(body, 18, height)
        put32le(body, 20, widthMm)
        put32le(body, 24, heightMm)
        outputs.forEachIndexed { index, output -> put32le(body, 28 + index * 4, output) }
        return request(XRandr.MajorOpcode, XRandr.SetMonitor, body)
    }

    private fun deleteMonitorRequest(
        window: Int = X11Ids.RootWindow,
        name: Int,
    ): ByteArray {
        val body = ByteArray(8)
        put32le(body, 0, window)
        put32le(body, 4, name)
        return request(XRandr.MajorOpcode, XRandr.DeleteMonitor, body)
    }

    private fun createWindowRequest(id: Int): ByteArray {
        val body = ByteArray(28)
        put32le(body, 0, id)
        put32le(body, 4, X11Ids.RootWindow)
        put16le(body, 12, 20)
        put16le(body, 14, 10)
        put16le(body, 18, 1)
        put32le(body, 20, X11Ids.RootVisual)
        return request(1, 24, body)
    }

    private fun destroyWindowRequest(id: Int): ByteArray {
        val body = ByteArray(4)
        put32le(body, 0, id)
        return request(4, 0, body)
    }

    private fun queryOutputPropertyRequest(output: Int, property: Int): ByteArray {
        val body = ByteArray(8)
        put32le(body, 0, output)
        put32le(body, 4, property)
        return request(XRandr.MajorOpcode, XRandr.QueryOutputProperty, body)
    }

    private fun queryOutputPropertyRequestBe(output: Int, property: Int): ByteArray {
        val body = ByteArray(8)
        put32be(body, 0, output)
        put32be(body, 4, property)
        return requestBe(XRandr.MajorOpcode, XRandr.QueryOutputProperty, body)
    }

    private fun configureOutputPropertyRequest(
        output: Int,
        property: Int,
        pending: Int,
        range: Int,
        validValues: IntArray = intArrayOf(),
    ): ByteArray {
        val body = ByteArray(12 + validValues.size * 4)
        put32le(body, 0, output)
        put32le(body, 4, property)
        body[8] = pending.toByte()
        body[9] = range.toByte()
        validValues.forEachIndexed { index, value -> put32le(body, 12 + index * 4, value) }
        return request(XRandr.MajorOpcode, XRandr.ConfigureOutputProperty, body)
    }

    private fun configureOutputPropertyRequestBe(
        output: Int,
        property: Int,
        pending: Int,
        range: Int,
        validValues: IntArray = intArrayOf(),
    ): ByteArray {
        val body = ByteArray(12 + validValues.size * 4)
        put32be(body, 0, output)
        put32be(body, 4, property)
        body[8] = pending.toByte()
        body[9] = range.toByte()
        validValues.forEachIndexed { index, value -> put32be(body, 12 + index * 4, value) }
        return requestBe(XRandr.MajorOpcode, XRandr.ConfigureOutputProperty, body)
    }

    private fun changeOutputPropertyRequest(
        output: Int,
        property: Int,
        type: Int,
        format: Int,
        mode: Int = 0,
        data: ByteArray,
    ): ByteArray {
        val padded = (data.size + 3) and -4
        val body = ByteArray(20 + padded)
        put32le(body, 0, output)
        put32le(body, 4, property)
        put32le(body, 8, type)
        body[12] = format.toByte()
        body[13] = mode.toByte()
        put32le(body, 16, data.size / propertyUnitSize(format))
        data.copyInto(body, 20)
        return request(XRandr.MajorOpcode, XRandr.ChangeOutputProperty, body)
    }

    private fun changeOutputPropertyRequestBe(
        output: Int,
        property: Int,
        type: Int,
        format: Int,
        mode: Int = 0,
        data: ByteArray,
    ): ByteArray {
        val padded = (data.size + 3) and -4
        val body = ByteArray(20 + padded)
        put32be(body, 0, output)
        put32be(body, 4, property)
        put32be(body, 8, type)
        body[12] = format.toByte()
        body[13] = mode.toByte()
        put32be(body, 16, data.size / propertyUnitSize(format))
        data.copyInto(body, 20)
        return requestBe(XRandr.MajorOpcode, XRandr.ChangeOutputProperty, body)
    }

    private fun propertyUnitSize(format: Int): Int =
        when (format) {
            16 -> 2
            32 -> 4
            else -> 1
        }

    private fun deleteOutputPropertyRequest(output: Int, property: Int): ByteArray {
        val body = ByteArray(8)
        put32le(body, 0, output)
        put32le(body, 4, property)
        return request(XRandr.MajorOpcode, XRandr.DeleteOutputProperty, body)
    }

    private fun getOutputPropertyRequest(
        output: Int,
        property: Int,
        type: Int,
        longOffset: Int = 0,
        longLength: Int = 0,
        delete: Int = 0,
        pending: Int = 0,
    ): ByteArray {
        val body = ByteArray(24)
        put32le(body, 0, output)
        put32le(body, 4, property)
        put32le(body, 8, type)
        put32le(body, 12, longOffset)
        put32le(body, 16, longLength)
        body[20] = delete.toByte()
        body[21] = pending.toByte()
        return request(XRandr.MajorOpcode, XRandr.GetOutputProperty, body)
    }

    private fun getOutputPropertyRequestBe(
        output: Int,
        property: Int,
        type: Int,
        longOffset: Int = 0,
        longLength: Int = 0,
        delete: Int = 0,
        pending: Int = 0,
    ): ByteArray {
        val body = ByteArray(24)
        put32be(body, 0, output)
        put32be(body, 4, property)
        put32be(body, 8, type)
        put32be(body, 12, longOffset)
        put32be(body, 16, longLength)
        body[20] = delete.toByte()
        body[21] = pending.toByte()
        return requestBe(XRandr.MajorOpcode, XRandr.GetOutputProperty, body)
    }

    private fun queryPointerRequest(): ByteArray {
        val body = ByteArray(4)
        put32le(body, 0, X11Ids.RootWindow)
        return request(38, 0, body)
    }

    private fun request(opcode: Int, minorOpcode: Int, body: ByteArray): ByteArray {
        val bytes = ByteArray(4 + body.size)
        bytes[0] = opcode.toByte()
        bytes[1] = minorOpcode.toByte()
        put16le(bytes, 2, bytes.size / 4)
        body.copyInto(bytes, 4)
        return bytes
    }

    private fun requestBe(opcode: Int, minorOpcode: Int, body: ByteArray): ByteArray {
        val bytes = ByteArray(4 + body.size)
        bytes[0] = opcode.toByte()
        bytes[1] = minorOpcode.toByte()
        put16be(bytes, 2, bytes.size / 4)
        body.copyInto(bytes, 4)
        return bytes
    }

    private fun assertError(input: InputStream, error: Int, badValue: Int, sequence: Int, minorOpcode: Int) {
        val bytes = input.readExactly(32)
        assertEquals(0, bytes[0].toInt() and 0xff)
        assertEquals(error, bytes[1].toInt() and 0xff)
        assertEquals(sequence, u16le(bytes, 2))
        assertEquals(badValue, u32le(bytes, 4))
        assertEquals(minorOpcode, u16le(bytes, 8))
        assertEquals(XRandr.MajorOpcode, bytes[10].toInt() and 0xff)
    }

    private fun assertOutputPropertyReply(reply: ByteArray, sequence: Int, format: Int, type: Int, bytesAfter: Int, items: Int) {
        assertEquals(sequence, u16le(reply, 2))
        assertEquals(format, reply[1].toInt() and 0xff)
        assertEquals(type, u32le(reply, 8))
        assertEquals(bytesAfter, u32le(reply, 12))
        assertEquals(items, u32le(reply, 16))
    }

    private fun assertOutputPropertyNotify(bytes: ByteArray, sequence: Int, atom: Int, state: Int) {
        assertEquals(XRandr.FirstEvent + XRandr.Notify, bytes[0].toInt() and 0xff)
        assertEquals(XRandr.NotifyOutputProperty, bytes[1].toInt() and 0xff)
        assertEquals(sequence, u16le(bytes, 2))
        assertEquals(X11Ids.RootWindow, u32le(bytes, 4))
        assertEquals(XRandr.OutputId, u32le(bytes, 8))
        assertEquals(atom, u32le(bytes, 12))
        assertEquals(state, bytes[20].toInt() and 0xff)
    }

    private fun assertOutputChangeNotify(bytes: ByteArray, sequence: Int) {
        assertEquals(XRandr.FirstEvent + XRandr.Notify, bytes[0].toInt() and 0xff)
        assertEquals(XRandr.NotifyOutputChange, bytes[1].toInt() and 0xff)
        assertEquals(sequence, u16le(bytes, 2))
        assertEquals(X11Ids.RootWindow, u32le(bytes, 12))
        assertEquals(XRandr.OutputId, u32le(bytes, 16))
        assertEquals(XRandr.CrtcId, u32le(bytes, 20))
        assertEquals(XRandr.ModeId, u32le(bytes, 24))
        assertEquals(XRandr.Rotate0, u16le(bytes, 28))
        assertEquals(XRandr.Connected, bytes[30].toInt() and 0xff)
        assertEquals(XRandr.SubPixelUnknown, bytes[31].toInt() and 0xff)
    }

    private fun assertScreenChangeNotify(bytes: ByteArray, sequence: Int, widthMm: Int = 32, heightMm: Int = 24) {
        assertEquals(XRandr.FirstEvent + XRandr.ScreenChangeNotify, bytes[0].toInt() and 0xff)
        assertEquals(XRandr.Rotate0, bytes[1].toInt() and 0xff)
        assertEquals(sequence, u16le(bytes, 2))
        assertEquals(X11Ids.RootWindow, u32le(bytes, 12))
        assertEquals(X11Ids.RootWindow, u32le(bytes, 16))
        assertEquals(0, u16le(bytes, 20))
        assertEquals(XRandr.SubPixelUnknown, u16le(bytes, 22))
        assertEquals(120, u16le(bytes, 24))
        assertEquals(90, u16le(bytes, 26))
        assertEquals(widthMm, u16le(bytes, 28))
        assertEquals(heightMm, u16le(bytes, 30))
    }

    private fun assertConfigureNotify(bytes: ByteArray, sequence: Int) {
        assertEquals(22, bytes[0].toInt() and 0xff)
        assertEquals(0, bytes[1].toInt() and 0xff)
        assertEquals(sequence, u16le(bytes, 2))
        assertEquals(X11Ids.RootWindow, u32le(bytes, 4))
        assertEquals(X11Ids.RootWindow, u32le(bytes, 8))
        assertEquals(0, u32le(bytes, 12))
        assertEquals(0, u16le(bytes, 16))
        assertEquals(0, u16le(bytes, 18))
        assertEquals(120, u16le(bytes, 20))
        assertEquals(90, u16le(bytes, 22))
        assertEquals(0, u16le(bytes, 24))
        assertEquals(0, bytes[26].toInt() and 0xff)
    }

    private fun assertSetCrtcConfigReply(bytes: ByteArray, sequence: Int, status: Int) {
        assertEquals(1, bytes[0].toInt() and 0xff)
        assertEquals(status, bytes[1].toInt() and 0xff)
        assertEquals(sequence, u16le(bytes, 2))
        assertEquals(0, u32le(bytes, 4))
    }

    private fun assertRandrStatusReply(bytes: ByteArray, sequence: Int, status: Int): Int {
        assertEquals(1, bytes[0].toInt() and 0xff)
        assertEquals(status, bytes[1].toInt() and 0xff)
        assertEquals(sequence, u16le(bytes, 2))
        assertEquals(0, u32le(bytes, 4))
        return u32le(bytes, 8)
    }

    private fun assertRandrStatusReplyBe(bytes: ByteArray, sequence: Int, status: Int): Int {
        assertEquals(1, bytes[0].toInt() and 0xff)
        assertEquals(status, bytes[1].toInt() and 0xff)
        assertEquals(sequence, u16be(bytes, 2))
        assertEquals(0, u32be(bytes, 4))
        return u32be(bytes, 8)
    }

    private fun assertPanningReply(bytes: ByteArray, sequence: Int, timestampAtLeast: Int): Int {
        assertEquals(1, bytes[0].toInt() and 0xff)
        assertEquals(XRandr.Success, bytes[1].toInt() and 0xff)
        assertEquals(sequence, u16le(bytes, 2))
        assertEquals(1, u32le(bytes, 4))
        val timestamp = u32le(bytes, 8)
        assertEquals(true, Integer.compareUnsigned(timestamp, timestampAtLeast) >= 0)
        for (offset in 12 until 36 step 2) {
            assertEquals(0, u16le(bytes, offset))
        }
        return timestamp
    }

    private fun assertPanningReplyBe(bytes: ByteArray, sequence: Int, timestampAtLeast: Int): Int {
        assertEquals(1, bytes[0].toInt() and 0xff)
        assertEquals(XRandr.Success, bytes[1].toInt() and 0xff)
        assertEquals(sequence, u16be(bytes, 2))
        assertEquals(1, u32be(bytes, 4))
        val timestamp = u32be(bytes, 8)
        assertEquals(true, Integer.compareUnsigned(timestamp, timestampAtLeast) >= 0)
        for (offset in 12 until 36 step 2) {
            assertEquals(0, u16be(bytes, offset))
        }
        return timestamp
    }

    private fun assertCrtcTransformReply(
        bytes: ByteArray,
        sequence: Int,
        pendingFilter: String = "",
        pendingValues: IntArray = intArrayOf(),
        currentFilter: String = "",
        currentValues: IntArray = intArrayOf(),
    ) {
        val pendingFilterBytes = pendingFilter.encodeToByteArray()
        val currentFilterBytes = currentFilter.encodeToByteArray()
        val expectedSize = 96 +
            ((pendingFilterBytes.size + 3) and -4) +
            pendingValues.size * 4 +
            ((currentFilterBytes.size + 3) and -4) +
            currentValues.size * 4
        assertEquals(1, bytes[0].toInt() and 0xff)
        assertEquals(XRandr.Success, bytes[1].toInt() and 0xff)
        assertEquals(sequence, u16le(bytes, 2))
        assertEquals((expectedSize - 32) / 4, u32le(bytes, 4))
        assertTransform(bytes, 8, IdentityTransform)
        assertEquals(1, bytes[44].toInt() and 0xff)
        assertTransform(bytes, 48, IdentityTransform)
        assertEquals(pendingFilterBytes.size, u16le(bytes, 88))
        assertEquals(pendingValues.size, u16le(bytes, 90))
        assertEquals(currentFilterBytes.size, u16le(bytes, 92))
        assertEquals(currentValues.size, u16le(bytes, 94))
        var offset = 96
        assertEquals(pendingFilterBytes.toList(), bytes.copyOfRange(offset, offset + pendingFilterBytes.size).toList())
        offset += (pendingFilterBytes.size + 3) and -4
        pendingValues.forEach { value ->
            assertEquals(value, u32le(bytes, offset))
            offset += 4
        }
        assertEquals(currentFilterBytes.toList(), bytes.copyOfRange(offset, offset + currentFilterBytes.size).toList())
        offset += (currentFilterBytes.size + 3) and -4
        currentValues.forEach { value ->
            assertEquals(value, u32le(bytes, offset))
            offset += 4
        }
    }

    private fun assertTransform(bytes: ByteArray, offset: Int, transform: List<Int>) {
        transform.forEachIndexed { index, value -> assertEquals(value, u32le(bytes, offset + index * 4)) }
    }

    private fun assertMonitorReply(reply: ByteArray, sequence: Int, primary: Int) {
        assertEquals(sequence, u16le(reply, 2))
        assertEquals(7, u32le(reply, 4))
        assertEquals(XRandr.Success, reply[1].toInt() and 0xff)
        assertEquals(1, u32le(reply, 12))
        assertEquals(1, u32le(reply, 16))
        assertEquals(0, u32le(reply, 32))
        assertEquals(primary, reply[36].toInt() and 0xff)
        assertEquals(1, reply[37].toInt() and 0xff)
        assertEquals(1, u16le(reply, 38))
        assertEquals(0, u16le(reply, 40))
        assertEquals(0, u16le(reply, 42))
        assertEquals(120, u16le(reply, 44))
        assertEquals(90, u16le(reply, 46))
        assertEquals(32, u32le(reply, 48))
        assertEquals(24, u32le(reply, 52))
        assertEquals(XRandr.OutputId, u32le(reply, 56))
    }

    private fun assertMonitorInfo(
        reply: ByteArray,
        offset: Int,
        name: Int,
        primary: Int,
        automatic: Int,
        noutput: Int,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        widthMm: Int,
        heightMm: Int,
    ) {
        assertEquals(name, u32le(reply, offset))
        assertEquals(primary, reply[offset + 4].toInt() and 0xff)
        assertEquals(automatic, reply[offset + 5].toInt() and 0xff)
        assertEquals(noutput, u16le(reply, offset + 6))
        assertEquals(x, i16le(reply, offset + 8))
        assertEquals(y, i16le(reply, offset + 10))
        assertEquals(width, u16le(reply, offset + 12))
        assertEquals(height, u16le(reply, offset + 14))
        assertEquals(widthMm, u32le(reply, offset + 16))
        assertEquals(heightMm, u32le(reply, offset + 20))
    }

    private fun readReply(input: InputStream): ByteArray {
        val header = input.readExactly(32)
        assertEquals(1, header[0].toInt() and 0xff)
        val extra = u32le(header, 4) * 4
        return if (extra == 0) header else header + input.readExactly(extra)
    }

    private fun readReplyBe(input: InputStream): ByteArray {
        val header = input.readExactly(32)
        assertEquals(1, header[0].toInt() and 0xff)
        val extra = u32be(header, 4) * 4
        return if (extra == 0) header else header + input.readExactly(extra)
    }

    private fun InputStream.readExactly(size: Int): ByteArray {
        val bytes = ByteArray(size)
        var offset = 0
        while (offset < size) {
            val read = read(bytes, offset, size - offset)
            if (read < 0) error("EOF")
            offset += read
        }
        return bytes
    }

    private fun extensionNames(reply: ByteArray): List<String> {
        val count = reply[1].toInt() and 0xff
        val names = mutableListOf<String>()
        var offset = 32
        repeat(count) {
            val length = reply[offset++].toInt() and 0xff
            names += reply.copyOfRange(offset, offset + length).decodeToString()
            offset += length
        }
        return names
    }

    private fun put16le(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = value.toByte()
        bytes[offset + 1] = (value ushr 8).toByte()
    }

    private fun put16be(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value ushr 8).toByte()
        bytes[offset + 1] = value.toByte()
    }

    private fun put32le(bytes: ByteArray, offset: Int, value: Int) {
        put16le(bytes, offset, value)
        put16le(bytes, offset + 2, value ushr 16)
    }

    private fun put32be(bytes: ByteArray, offset: Int, value: Int) {
        put16be(bytes, offset, value ushr 16)
        put16be(bytes, offset + 2, value)
    }

    private fun int32le(vararg values: Int): ByteArray {
        val bytes = ByteArray(values.size * 4)
        values.forEachIndexed { index, value -> put32le(bytes, index * 4, value) }
        return bytes
    }

    private fun u16le(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or ((bytes[offset + 1].toInt() and 0xff) shl 8)

    private fun u16be(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xff) shl 8) or (bytes[offset + 1].toInt() and 0xff)

    private fun i16le(bytes: ByteArray, offset: Int): Int =
        u16le(bytes, offset).toShort().toInt()

    private fun u32le(bytes: ByteArray, offset: Int): Int =
        u16le(bytes, offset) or (u16le(bytes, offset + 2) shl 16)

    private fun u32be(bytes: ByteArray, offset: Int): Int =
        (u16be(bytes, offset) shl 16) or u16be(bytes, offset + 2)
}
