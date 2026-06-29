package org.jonnyzzz.xserver

import java.io.InputStream
import java.net.Socket
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class XXkbProtocolTest {
    @Test
    fun `XKEYBOARD exposes query-only UseExtension metadata`() {
        withServer { socket, port ->
            socket.getOutputStream().write(queryExtensionRequest("XKEYBOARD"))
            socket.getOutputStream().write(useExtensionRequest())
            socket.getOutputStream().flush()

            val extension = readReply(socket.getInputStream())
            assertEquals(1, extension[8].toInt())
            assertEquals(XXkb.MajorOpcode, extension[9].toInt() and 0xff)
            assertEquals(XXkb.FirstEvent, extension[10].toInt() and 0xff)
            assertEquals(XXkb.FirstError, extension[11].toInt() and 0xff)

            val version = readReply(socket.getInputStream())
            assertEquals(1, version[1].toInt() and 0xff)
            assertEquals(0, u32le(version, 4))
            assertEquals(XXkb.MajorVersion, u16le(version, 8))
            assertEquals(XXkb.MinorVersion, u16le(version, 10))

            assertContains(httpGet(port, "/text.txt"), "XKEYBOARD supported=true")
        }
    }

    @Test
    fun `XKEYBOARD alias resolves through QueryExtension`() {
        withServer { socket, _ ->
            socket.getOutputStream().write(queryExtensionRequest("XKB"))
            socket.getOutputStream().flush()

            val extension = readReply(socket.getInputStream())
            assertEquals(1, extension[8].toInt())
            assertEquals(XXkb.MajorOpcode, extension[9].toInt() and 0xff)
            assertEquals(XXkb.FirstEvent, extension[10].toInt() and 0xff)
            assertEquals(XXkb.FirstError, extension[11].toInt() and 0xff)
        }
    }

    @Test
    fun `XKEYBOARD UseExtension validates request length and recovers stream`() {
        withServer { socket, _ ->
            val out = socket.getOutputStream()
            out.write(request(XXkb.MajorOpcode, XXkb.UseExtension, ByteArray(0)))
            out.write(useExtensionRequest())
            out.flush()

            assertError(socket.getInputStream(), error = 16, opcode = XXkb.MajorOpcode, badValue = 0, sequence = 1, minorOpcode = XXkb.UseExtension)
            val version = readReply(socket.getInputStream())
            assertEquals(1, version[1].toInt() and 0xff)
            assertEquals(XXkb.MajorVersion, u16le(version, 8))
            assertEquals(XXkb.MinorVersion, u16le(version, 10))
        }
    }

    @Test
    fun `XKEYBOARD SelectEvents accepts fixed prefix no-op and recovers stream`() {
        withServer { socket, port ->
            val out = socket.getOutputStream()
            out.write(selectEventsRequest())
            out.write(useExtensionRequest())
            out.flush()

            val version = readReply(socket.getInputStream())
            assertEquals(2, u16le(version, 2))
            assertEquals(1, version[1].toInt() and 0xff)
            assertEquals(XXkb.MajorVersion, u16le(version, 8))
            assertEquals(XXkb.MinorVersion, u16le(version, 10))

            assertContains(httpGet(port, "/text.txt"), "XKEYBOARD.SelectEvents: 1")
        }
    }

    @Test
    fun `XKEYBOARD SelectEvents validates fixed prefix length and recovers stream`() {
        withServer { socket, _ ->
            val out = socket.getOutputStream()
            out.write(request(XXkb.MajorOpcode, XXkb.SelectEvents, ByteArray(8)))
            out.write(useExtensionRequest())
            out.flush()

            assertError(socket.getInputStream(), error = 16, opcode = XXkb.MajorOpcode, badValue = 0, sequence = 1, minorOpcode = XXkb.SelectEvents)
            val version = readReply(socket.getInputStream())
            assertEquals(2, u16le(version, 2))
            assertEquals(1, version[1].toInt() and 0xff)
            assertEquals(XXkb.MajorVersion, u16le(version, 8))
        }
    }

    @Test
    fun `XKEYBOARD SelectEvents validates variable details and recovers stream`() {
        withServer { socket, _ ->
            val out = socket.getOutputStream()
            out.write(selectEventsRequest(details = ByteArray(4)))
            out.write(
                selectEventsRequest(
                    affectWhich = 1 shl 12,
                ),
            )
            out.write(
                selectEventsRequest(
                    affectWhich = XXkb.EventMapNotify,
                    affectMap = 0,
                    map = XXkb.MapPartKeyTypes,
                ),
            )
            out.write(
                selectEventsRequest(
                    affectWhich = XXkb.EventStateNotify,
                    details = selectEvents16Details(0x0001, 0x0002),
                ),
            )
            out.write(
                selectEventsRequest(
                    affectWhich = XXkb.EventStateNotify,
                    details = selectEvents16Details(0x0003, 0x0002),
                ),
            )
            out.write(
                selectEventsRequest(
                    affectWhich = XXkb.EventBellNotify,
                    details = selectEvents8Details(0x01, 0x01),
                ),
            )
            out.write(
                selectEventsRequest(
                    affectWhich = XXkb.EventBellNotify or XXkb.EventAccessXNotify,
                    details = selectEvents8Details(0x01, 0x01) + selectEvents16Details(0x0003, 0x0002),
                ),
            )
            out.write(
                selectEventsRequest(
                    affectWhich = XXkb.EventBellNotify or XXkb.EventAccessXNotify,
                    details = selectEvents8Details(0x01, 0x01) + selectEvents16Details(0x0001, 0x0002),
                ),
            )
            out.write(useExtensionRequest())
            out.flush()

            assertError(socket.getInputStream(), error = 16, opcode = XXkb.MajorOpcode, badValue = 0, sequence = 1, minorOpcode = XXkb.SelectEvents)
            assertError(socket.getInputStream(), error = 2, opcode = XXkb.MajorOpcode, badValue = 1 shl 12, sequence = 2, minorOpcode = XXkb.SelectEvents)
            assertError(socket.getInputStream(), error = 8, opcode = XXkb.MajorOpcode, badValue = 0, sequence = 3, minorOpcode = XXkb.SelectEvents)
            assertError(socket.getInputStream(), error = 8, opcode = XXkb.MajorOpcode, badValue = 0, sequence = 4, minorOpcode = XXkb.SelectEvents)
            assertError(socket.getInputStream(), error = 8, opcode = XXkb.MajorOpcode, badValue = 0, sequence = 8, minorOpcode = XXkb.SelectEvents)
            val version = readReply(socket.getInputStream())
            assertEquals(9, u16le(version, 2))
            assertEquals(1, version[1].toInt() and 0xff)
            assertEquals(XXkb.MajorVersion, u16le(version, 8))
        }
    }

    @Test
    fun `XKEYBOARD Bell accepts valid signed percent and recovers stream`() {
        withServer { socket, port ->
            val out = socket.getOutputStream()
            out.write(xkbBellRequest(percent = 50))
            out.write(xkbBellRequest(percent = -100))
            out.write(useExtensionRequest())
            out.flush()

            val version = readReply(socket.getInputStream())
            assertEquals(3, u16le(version, 2))
            assertEquals(1, version[1].toInt() and 0xff)
            assertEquals(XXkb.MajorVersion, u16le(version, 8))
            assertEquals(XXkb.MinorVersion, u16le(version, 10))

            assertContains(httpGet(port, "/text.txt"), "XKEYBOARD.Bell: 2")
        }
    }

    @Test
    fun `XKEYBOARD Bell validates fixed length and signed percent range`() {
        withServer { socket, _ ->
            val out = socket.getOutputStream()
            out.write(xkbBellRequest(percent = 101))
            out.write(xkbBellRequest(percent = -101))
            out.write(request(XXkb.MajorOpcode, XXkb.Bell, ByteArray(20)))
            out.write(request(XXkb.MajorOpcode, XXkb.Bell, ByteArray(28)))
            out.write(useExtensionRequest())
            out.flush()

            assertError(socket.getInputStream(), error = 2, opcode = XXkb.MajorOpcode, badValue = 101, sequence = 1, minorOpcode = XXkb.Bell)
            assertError(socket.getInputStream(), error = 2, opcode = XXkb.MajorOpcode, badValue = -101, sequence = 2, minorOpcode = XXkb.Bell)
            assertError(socket.getInputStream(), error = 16, opcode = XXkb.MajorOpcode, badValue = 0, sequence = 3, minorOpcode = XXkb.Bell)
            assertError(socket.getInputStream(), error = 16, opcode = XXkb.MajorOpcode, badValue = 0, sequence = 4, minorOpcode = XXkb.Bell)
            val version = readReply(socket.getInputStream())
            assertEquals(5, u16le(version, 2))
            assertEquals(1, version[1].toInt() and 0xff)
            assertEquals(XXkb.MajorVersion, u16le(version, 8))
        }
    }

    @Test
    fun `XKEYBOARD GetState returns default core keyboard state`() {
        withServer { socket, _ ->
            val out = socket.getOutputStream()
            out.write(getStateRequest())
            out.flush()

            val state = readReply(socket.getInputStream())
            assertEquals(0, state[1].toInt() and 0xff)
            assertEquals(1, u16le(state, 2))
            assertEquals(0, u32le(state, 4))
            assertEquals(0, state[8].toInt() and 0xff)
            assertEquals(0, state[9].toInt() and 0xff)
            assertEquals(0, state[10].toInt() and 0xff)
            assertEquals(0, state[11].toInt() and 0xff)
            assertEquals(0, state[12].toInt() and 0xff)
            assertEquals(0, state[13].toInt() and 0xff)
            assertEquals(0, u16le(state, 14))
            assertEquals(0, u16le(state, 16))
            assertEquals(0, state[18].toInt() and 0xff)
            assertEquals(0, state[19].toInt() and 0xff)
            assertEquals(0, state[20].toInt() and 0xff)
            assertEquals(0, state[21].toInt() and 0xff)
            assertEquals(0, state[22].toInt() and 0xff)
            assertEquals(0, u16le(state, 24))
        }
    }

    @Test
    fun `XKEYBOARD GetState separates pointer buttons from core aggregate modifier state`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val buttonMask = 0x100
                server.input.pointerDown(10, 10, button = 1)
                server.input.keyDown(10, modifiers = 5)

                val out = socket.getOutputStream()
                out.write(getStateRequest())
                out.write(queryPointerRequest())
                out.flush()

                val state = readReply(socket.getInputStream())
                assertEquals(5, state[8].toInt() and 0xff)
                assertEquals(5, state[9].toInt() and 0xff)
                assertEquals(0, state[10].toInt() and 0xff)
                assertEquals(0, state[11].toInt() and 0xff)
                assertEquals(5, state[18].toInt() and 0xff)
                assertEquals(5, state[19].toInt() and 0xff)
                assertEquals(5, state[20].toInt() and 0xff)
                assertEquals(5, state[21].toInt() and 0xff)
                assertEquals(5, state[22].toInt() and 0xff)
                assertEquals(buttonMask, u16le(state, 24))

                val pointer = readReply(socket.getInputStream())
                assertEquals(buttonMask or 5, u16le(pointer, 24))

                server.input.keyUp(10, modifiers = 0)
                out.write(getStateRequest())
                out.write(queryPointerRequest())
                out.flush()

                val releasedState = readReply(socket.getInputStream())
                assertEquals(0, releasedState[8].toInt() and 0xff)
                assertEquals(0, releasedState[9].toInt() and 0xff)
                assertEquals(0, releasedState[18].toInt() and 0xff)
                assertEquals(0, releasedState[19].toInt() and 0xff)
                assertEquals(0, releasedState[20].toInt() and 0xff)
                assertEquals(0, releasedState[21].toInt() and 0xff)
                assertEquals(0, releasedState[22].toInt() and 0xff)
                assertEquals(buttonMask, u16le(releasedState, 24))

                val releasedPointer = readReply(socket.getInputStream())
                assertEquals(buttonMask, u16le(releasedPointer, 24))

                server.input.pointerUp(10, 10, button = 1)
                out.write(getStateRequest())
                out.write(queryPointerRequest())
                out.flush()

                val finalState = readReply(socket.getInputStream())
                assertEquals(0, u16le(finalState, 24))

                val finalPointer = readReply(socket.getInputStream())
                assertEquals(0, u16le(finalPointer, 24))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `XKEYBOARD GetState validates request length and recovers stream`() {
        withServer { socket, _ ->
            val out = socket.getOutputStream()
            out.write(request(XXkb.MajorOpcode, XXkb.GetState, ByteArray(0)))
            out.write(getStateRequest())
            out.flush()

            assertError(socket.getInputStream(), error = 16, opcode = XXkb.MajorOpcode, badValue = 0, sequence = 1, minorOpcode = XXkb.GetState)
            val state = readReply(socket.getInputStream())
            assertEquals(2, u16le(state, 2))
            assertEquals(0, state[1].toInt() and 0xff)
            assertEquals(0, u16le(state, 24))
        }
    }

    @Test
    fun `XKEYBOARD LatchLockState accepts fixed no-op and recovers stream`() {
        withServer { socket, port ->
            val out = socket.getOutputStream()
            out.write(latchLockStateRequest(modLocks = 0x05, groupLock = 1, latchGroup = true, groupLatch = -1))
            out.write(latchLockStateRequest(modLocks = 0, groupLock = 0, latchGroup = false, groupLatch = 0))
            out.write(useExtensionRequest())
            out.flush()

            val version = readReply(socket.getInputStream())
            assertEquals(3, u16le(version, 2))
            assertEquals(1, version[1].toInt() and 0xff)
            assertEquals(XXkb.MajorVersion, u16le(version, 8))
            assertEquals(XXkb.MinorVersion, u16le(version, 10))

            assertContains(httpGet(port, "/text.txt"), "XKEYBOARD.LatchLockState: 2")
        }
    }

    @Test
    fun `XKEYBOARD LatchLockState validates fixed request length and recovers stream`() {
        withServer { socket, _ ->
            val out = socket.getOutputStream()
            out.write(request(XXkb.MajorOpcode, XXkb.LatchLockState, ByteArray(8)))
            out.write(request(XXkb.MajorOpcode, XXkb.LatchLockState, ByteArray(16)))
            out.write(latchLockStateRequest(modLocks = 0, groupLock = 0, latchGroup = false, groupLatch = 0))
            out.write(useExtensionRequest())
            out.flush()

            assertError(socket.getInputStream(), error = 16, opcode = XXkb.MajorOpcode, badValue = 0, sequence = 1, minorOpcode = XXkb.LatchLockState)
            assertError(socket.getInputStream(), error = 16, opcode = XXkb.MajorOpcode, badValue = 0, sequence = 2, minorOpcode = XXkb.LatchLockState)
            val version = readReply(socket.getInputStream())
            assertEquals(4, u16le(version, 2))
            assertEquals(1, version[1].toInt() and 0xff)
            assertEquals(XXkb.MajorVersion, u16le(version, 8))
        }
    }

    @Test
    fun `XKEYBOARD GetControls returns core keyboard controls`() {
        withServer { socket, _ ->
            val out = socket.getOutputStream()
            out.write(getControlsRequest())
            out.flush()

            val controls = readReply(socket.getInputStream())
            assertGetControls(controls, sequence = 1, enabledControls = XXkb.BoolCtrlRepeatKeys)
            assertEquals(0xff, controls[60 + 5].toInt() and 0xff)
        }
    }

    @Test
    fun `XKEYBOARD GetControls reflects core auto repeat changes`() {
        withServer { socket, _ ->
            val out = socket.getOutputStream()
            out.write(changeKeyboardControlRequest(0x40 to 40, 0x80 to 0))
            out.write(getControlsRequest())
            out.write(changeKeyboardControlRequest(0x80 to 0))
            out.write(getControlsRequest())
            out.flush()

            val perKeyControls = readReply(socket.getInputStream())
            assertGetControls(perKeyControls, sequence = 2, enabledControls = XXkb.BoolCtrlRepeatKeys)
            assertEquals(0xfe, perKeyControls[60 + 5].toInt() and 0xff)

            val globalControls = readReply(socket.getInputStream())
            assertGetControls(globalControls, sequence = 4, enabledControls = 0)
            assertEquals(0xfe, globalControls[60 + 5].toInt() and 0xff)
        }
    }

    @Test
    fun `XKEYBOARD GetControls validates request length and recovers stream`() {
        withServer { socket, _ ->
            val out = socket.getOutputStream()
            out.write(request(XXkb.MajorOpcode, XXkb.GetControls, ByteArray(0)))
            out.write(getControlsRequest())
            out.flush()

            assertError(socket.getInputStream(), error = 16, opcode = XXkb.MajorOpcode, badValue = 0, sequence = 1, minorOpcode = XXkb.GetControls)
            val controls = readReply(socket.getInputStream())
            assertGetControls(controls, sequence = 2, enabledControls = XXkb.BoolCtrlRepeatKeys)
        }
    }

    @Test
    fun `XKEYBOARD SetControls updates supported RepeatKeys enabled state`() {
        withServer { socket, _ ->
            val out = socket.getOutputStream()
            out.write(getControlsRequest())
            out.write(setControlsRequest(affectEnabledControls = XXkb.BoolCtrlRepeatKeys, enabledControls = 0))
            out.write(getControlsRequest())
            out.write(getKeyboardControlRequest())
            out.write(setControlsRequest(affectEnabledControls = XXkb.BoolCtrlRepeatKeys, enabledControls = XXkb.BoolCtrlRepeatKeys))
            out.write(getControlsRequest())
            out.write(getKeyboardControlRequest())
            out.flush()

            assertGetControls(readReply(socket.getInputStream()), sequence = 1, enabledControls = XXkb.BoolCtrlRepeatKeys)
            assertGetControls(readReply(socket.getInputStream()), sequence = 3, enabledControls = 0)
            val disabledCore = readReply(socket.getInputStream())
            assertEquals(4, u16le(disabledCore, 2))
            assertEquals(0, disabledCore[1].toInt() and 0xff)
            assertGetControls(readReply(socket.getInputStream()), sequence = 6, enabledControls = XXkb.BoolCtrlRepeatKeys)
            val enabledCore = readReply(socket.getInputStream())
            assertEquals(7, u16le(enabledCore, 2))
            assertEquals(1, enabledCore[1].toInt() and 0xff)
        }
    }

    @Test
    fun `XKEYBOARD SetControls ignores unsupported controls and validates fixed length`() {
        withServer { socket, _ ->
            val out = socket.getOutputStream()
            out.write(setControlsRequest(affectEnabledControls = 1 shl 9, enabledControls = 0))
            out.write(getControlsRequest())
            out.write(request(XXkb.MajorOpcode, XXkb.SetControls, ByteArray(92)))
            out.write(request(XXkb.MajorOpcode, XXkb.SetControls, ByteArray(100)))
            out.write(getControlsRequest())
            out.flush()

            assertGetControls(readReply(socket.getInputStream()), sequence = 2, enabledControls = XXkb.BoolCtrlRepeatKeys)
            assertError(socket.getInputStream(), error = 16, opcode = XXkb.MajorOpcode, badValue = 0, sequence = 3, minorOpcode = XXkb.SetControls)
            assertError(socket.getInputStream(), error = 16, opcode = XXkb.MajorOpcode, badValue = 0, sequence = 4, minorOpcode = XXkb.SetControls)
            assertGetControls(readReply(socket.getInputStream()), sequence = 5, enabledControls = XXkb.BoolCtrlRepeatKeys)
        }
    }

    @Test
    fun `XKEYBOARD GetMap reports key range with no map parts`() {
        withServer { socket, _ ->
            val out = socket.getOutputStream()
            out.write(getMapRequest(full = -1, partial = -1))
            out.flush()

            val map = readReply(socket.getInputStream())
            assertEquals(1, map[0].toInt())
            assertEquals(0, map[1].toInt() and 0xff)
            assertEquals(1, u16le(map, 2))
            assertEquals(2, u32le(map, 4))
            assertEquals(0, u16le(map, 8))
            assertEquals(XKeyboard.MinKeycode, map[10].toInt() and 0xff)
            assertEquals(XKeyboard.MaxKeycode, map[11].toInt() and 0xff)
            assertEquals(0, u16le(map, 12))
            assertEquals(0, map[14].toInt() and 0xff)
            assertEquals(0, map[15].toInt() and 0xff)
            assertEquals(0, map[16].toInt() and 0xff)
            assertEquals(0, map[17].toInt() and 0xff)
            assertEquals(0, u16le(map, 18))
            assertEquals(0, map[20].toInt() and 0xff)
            assertEquals(0, map[21].toInt() and 0xff)
            assertEquals(0, u16le(map, 22))
            assertEquals(0, map[24].toInt() and 0xff)
            assertEquals(0, map[25].toInt() and 0xff)
            assertEquals(0, map[26].toInt() and 0xff)
            assertEquals(0, map[27].toInt() and 0xff)
            assertEquals(0, map[28].toInt() and 0xff)
            assertEquals(0, map[29].toInt() and 0xff)
            assertEquals(0, map[30].toInt() and 0xff)
            assertEquals(0, map[31].toInt() and 0xff)
            assertEquals(0, map[32].toInt() and 0xff)
            assertEquals(0, map[33].toInt() and 0xff)
            assertEquals(0, map[34].toInt() and 0xff)
            assertEquals(0, map[35].toInt() and 0xff)
            assertEquals(0, map[36].toInt() and 0xff)
            assertEquals(0, map[37].toInt() and 0xff)
            assertEquals(0, u16le(map, 38))
            assertEquals(40, map.size)
        }
    }

    @Test
    fun `XKEYBOARD GetMap validates request length and recovers stream`() {
        withServer { socket, _ ->
            val out = socket.getOutputStream()
            out.write(request(XXkb.MajorOpcode, XXkb.GetMap, ByteArray(20)))
            out.write(getMapRequest(full = 0, partial = 0))
            out.flush()

            assertError(socket.getInputStream(), error = 16, opcode = XXkb.MajorOpcode, badValue = 0, sequence = 1, minorOpcode = XXkb.GetMap)
            val map = readReply(socket.getInputStream())
            assertEquals(2, u16le(map, 2))
            assertEquals(2, u32le(map, 4))
            assertEquals(XKeyboard.MinKeycode, map[10].toInt() and 0xff)
            assertEquals(XKeyboard.MaxKeycode, map[11].toInt() and 0xff)
            assertEquals(0, u16le(map, 12))
            assertEquals(40, map.size)
        }
    }

    @Test
    fun `XKEYBOARD SetMap accepts full map payload without changing empty map`() {
        withServer { socket, _ ->
            val out = socket.getOutputStream()
            out.write(setMapRequest(includeAllParts = true))
            out.write(getMapRequest(full = -1, partial = -1))
            out.flush()

            val map = readReply(socket.getInputStream())
            assertEquals(2, u16le(map, 2))
            assertEquals(2, u32le(map, 4))
            assertEquals(0, u16le(map, 8))
            assertEquals(XKeyboard.MinKeycode, map[10].toInt() and 0xff)
            assertEquals(XKeyboard.MaxKeycode, map[11].toInt() and 0xff)
            assertEquals(0, u16le(map, 12))
            assertEquals(0, map[14].toInt() and 0xff)
            assertEquals(0, map[15].toInt() and 0xff)
            assertEquals(40, map.size)
        }
    }

    @Test
    fun `XKEYBOARD SetMap accepts odd explicit and modifier map counts with padding`() {
        withServer { socket, _ ->
            val out = socket.getOutputStream()
            out.write(setMapRequest(includeAllParts = true, oddExplicitAndModifierMapCounts = true))
            out.write(getMapRequest(full = -1, partial = -1))
            out.flush()

            val map = readReply(socket.getInputStream())
            assertEquals(2, u16le(map, 2))
            assertEquals(2, u32le(map, 4))
            assertEquals(0, u16le(map, 8))
            assertEquals(0, u16le(map, 12))
            assertEquals(40, map.size)
        }
    }

    @Test
    fun `XKEYBOARD SetMap validates variable payload length and recovers stream`() {
        withServer { socket, _ ->
            val out = socket.getOutputStream()
            out.write(request(XXkb.MajorOpcode, XXkb.SetMap, ByteArray(28)))
            out.write(setMapRequest(includeAllParts = true, bodySize = setMapBodySize(includeAllParts = true) - 4))
            out.write(setMapRequest(includeAllParts = true, bodySize = setMapBodySize(includeAllParts = true) + 4))
            out.write(setMapRequest(includeAllParts = false))
            out.write(getMapRequest(full = 0, partial = 0))
            out.flush()

            assertError(socket.getInputStream(), error = 16, opcode = XXkb.MajorOpcode, badValue = 0, sequence = 1, minorOpcode = XXkb.SetMap)
            assertError(socket.getInputStream(), error = 16, opcode = XXkb.MajorOpcode, badValue = 0, sequence = 2, minorOpcode = XXkb.SetMap)
            assertError(socket.getInputStream(), error = 16, opcode = XXkb.MajorOpcode, badValue = 0, sequence = 3, minorOpcode = XXkb.SetMap)
            val map = readReply(socket.getInputStream())
            assertEquals(5, u16le(map, 2))
            assertEquals(2, u32le(map, 4))
            assertEquals(XKeyboard.MinKeycode, map[10].toInt() and 0xff)
            assertEquals(XKeyboard.MaxKeycode, map[11].toInt() and 0xff)
            assertEquals(0, u16le(map, 12))
            assertEquals(40, map.size)
        }
    }

    @Test
    fun `XKEYBOARD GetCompatMap returns empty compatibility map`() {
        withServer { socket, _ ->
            val out = socket.getOutputStream()
            out.write(getCompatMapRequest(groups = -1, getAllSI = true, firstSI = 0, nSI = 0xffff))
            out.flush()

            val compatMap = readReply(socket.getInputStream())
            assertEquals(1, compatMap[0].toInt())
            assertEquals(0, compatMap[1].toInt() and 0xff)
            assertEquals(1, u16le(compatMap, 2))
            assertEquals(0, u32le(compatMap, 4))
            assertEquals(0, compatMap[8].toInt() and 0xff)
            assertEquals(0, u16le(compatMap, 10))
            assertEquals(0, u16le(compatMap, 12))
            assertEquals(0, u16le(compatMap, 14))
            assertEquals(32, compatMap.size)
        }
    }

    @Test
    fun `XKEYBOARD GetCompatMap validates request length and recovers stream`() {
        withServer { socket, _ ->
            val out = socket.getOutputStream()
            out.write(request(XXkb.MajorOpcode, XXkb.GetCompatMap, ByteArray(4)))
            out.write(getCompatMapRequest(groups = 0, getAllSI = false, firstSI = 0, nSI = 0))
            out.flush()

            assertError(socket.getInputStream(), error = 16, opcode = XXkb.MajorOpcode, badValue = 0, sequence = 1, minorOpcode = XXkb.GetCompatMap)
            val compatMap = readReply(socket.getInputStream())
            assertEquals(2, u16le(compatMap, 2))
            assertEquals(0, compatMap[1].toInt() and 0xff)
            assertEquals(0, u16le(compatMap, 10))
            assertEquals(0, u16le(compatMap, 12))
            assertEquals(0, u16le(compatMap, 14))
        }
    }

    @Test
    fun `XKEYBOARD SetCompatMap accepts compat payload without changing empty map`() {
        withServer { socket, _ ->
            val out = socket.getOutputStream()
            out.write(setCompatMapRequest(groups = 0x5, nSI = 2))
            out.write(getCompatMapRequest(groups = 0, getAllSI = false, firstSI = 0, nSI = 0))
            out.flush()

            val compatMap = readReply(socket.getInputStream())
            assertEquals(2, u16le(compatMap, 2))
            assertEquals(0, compatMap[1].toInt() and 0xff)
            assertEquals(0, u16le(compatMap, 10))
            assertEquals(0, u16le(compatMap, 12))
            assertEquals(0, u16le(compatMap, 14))
        }
    }

    @Test
    fun `XKEYBOARD SetCompatMap validates variable payload length and recovers stream`() {
        withServer { socket, _ ->
            val out = socket.getOutputStream()
            out.write(request(XXkb.MajorOpcode, XXkb.SetCompatMap, ByteArray(8)))
            out.write(setCompatMapRequest(groups = 0x3, nSI = 1, bodySize = 28))
            out.write(setCompatMapRequest(groups = 0x3, nSI = 1, bodySize = 40))
            out.write(setCompatMapRequest(groups = 0, nSI = 0))
            out.write(getCompatMapRequest(groups = 0, getAllSI = false, firstSI = 0, nSI = 0))
            out.flush()

            assertError(socket.getInputStream(), error = 16, opcode = XXkb.MajorOpcode, badValue = 0, sequence = 1, minorOpcode = XXkb.SetCompatMap)
            assertError(socket.getInputStream(), error = 16, opcode = XXkb.MajorOpcode, badValue = 0, sequence = 2, minorOpcode = XXkb.SetCompatMap)
            assertError(socket.getInputStream(), error = 16, opcode = XXkb.MajorOpcode, badValue = 0, sequence = 3, minorOpcode = XXkb.SetCompatMap)
            val compatMap = readReply(socket.getInputStream())
            assertEquals(5, u16le(compatMap, 2))
            assertEquals(0, u16le(compatMap, 10))
            assertEquals(0, u16le(compatMap, 12))
            assertEquals(0, u16le(compatMap, 14))
        }
    }

    @Test
    fun `XKEYBOARD indicator queries return empty state and map`() {
        withServer { socket, _ ->
            val out = socket.getOutputStream()
            out.write(getIndicatorStateRequest())
            out.write(getIndicatorMapRequest(which = -1))
            out.flush()

            val state = readReply(socket.getInputStream())
            assertEquals(0, state[1].toInt() and 0xff)
            assertEquals(1, u16le(state, 2))
            assertEquals(0, u32le(state, 4))
            assertEquals(0, u32le(state, 8))

            val map = readReply(socket.getInputStream())
            assertEquals(0, map[1].toInt() and 0xff)
            assertEquals(2, u16le(map, 2))
            assertEquals(0, u32le(map, 4))
            assertEquals(0, u32le(map, 8))
            assertEquals(0, u32le(map, 12))
            assertEquals(0, map[16].toInt() and 0xff)
            assertEquals(32, map.size)
        }
    }

    @Test
    fun `XKEYBOARD indicator queries validate request lengths and recover stream`() {
        withServer { socket, _ ->
            val out = socket.getOutputStream()
            out.write(request(XXkb.MajorOpcode, XXkb.GetIndicatorState, ByteArray(0)))
            out.write(getIndicatorStateRequest())
            out.write(request(XXkb.MajorOpcode, XXkb.GetIndicatorMap, ByteArray(4)))
            out.write(getIndicatorMapRequest(which = 1))
            out.flush()

            assertError(socket.getInputStream(), error = 16, opcode = XXkb.MajorOpcode, badValue = 0, sequence = 1, minorOpcode = XXkb.GetIndicatorState)
            val state = readReply(socket.getInputStream())
            assertEquals(2, u16le(state, 2))
            assertEquals(0, u32le(state, 8))

            assertError(socket.getInputStream(), error = 16, opcode = XXkb.MajorOpcode, badValue = 0, sequence = 3, minorOpcode = XXkb.GetIndicatorMap)
            val map = readReply(socket.getInputStream())
            assertEquals(4, u16le(map, 2))
            assertEquals(0, u32le(map, 8))
            assertEquals(0, map[16].toInt() and 0xff)
        }
    }

    @Test
    fun `XKEYBOARD SetIndicatorMap accepts map records without creating indicators`() {
        withServer { socket, _ ->
            val out = socket.getOutputStream()
            out.write(setIndicatorMapRequest(which = 0x3))
            out.write(getIndicatorMapRequest(which = 0x3))
            out.write(getIndicatorStateRequest())
            out.flush()

            val map = readReply(socket.getInputStream())
            assertEquals(2, u16le(map, 2))
            assertEquals(0, u32le(map, 8))
            assertEquals(0, u32le(map, 12))
            assertEquals(0, map[16].toInt() and 0xff)

            val state = readReply(socket.getInputStream())
            assertEquals(3, u16le(state, 2))
            assertEquals(0, u32le(state, 8))
        }
    }

    @Test
    fun `XKEYBOARD SetIndicatorMap validates variable map length and recovers stream`() {
        withServer { socket, _ ->
            val out = socket.getOutputStream()
            out.write(request(XXkb.MajorOpcode, XXkb.SetIndicatorMap, ByteArray(4)))
            out.write(setIndicatorMapRequest(which = 0x3, bodySize = 20))
            out.write(setIndicatorMapRequest(which = 0x3, bodySize = 44))
            out.write(setIndicatorMapRequest(which = 0))
            out.write(getIndicatorMapRequest(which = 0x3))
            out.flush()

            assertError(socket.getInputStream(), error = 16, opcode = XXkb.MajorOpcode, badValue = 0, sequence = 1, minorOpcode = XXkb.SetIndicatorMap)
            assertError(socket.getInputStream(), error = 16, opcode = XXkb.MajorOpcode, badValue = 0, sequence = 2, minorOpcode = XXkb.SetIndicatorMap)
            assertError(socket.getInputStream(), error = 16, opcode = XXkb.MajorOpcode, badValue = 0, sequence = 3, minorOpcode = XXkb.SetIndicatorMap)
            val map = readReply(socket.getInputStream())
            assertEquals(5, u16le(map, 2))
            assertEquals(0, u32le(map, 8))
            assertEquals(0, map[16].toInt() and 0xff)
        }
    }

    @Test
    fun `XKEYBOARD GetNamedIndicator reports queried indicator absent`() {
        withServer { socket, _ ->
            val indicator = 0x0020_0400
            val out = socket.getOutputStream()
            out.write(getNamedIndicatorRequest(indicator))
            out.flush()

            val reply = readReply(socket.getInputStream())
            assertEquals(0, reply[1].toInt() and 0xff)
            assertEquals(1, u16le(reply, 2))
            assertEquals(0, u32le(reply, 4))
            assertEquals(indicator, u32le(reply, 8))
            assertEquals(0, reply[12].toInt() and 0xff)
            assertEquals(0, reply[13].toInt() and 0xff)
            assertEquals(0, reply[14].toInt() and 0xff)
            assertEquals(0, reply[15].toInt() and 0xff)
            assertEquals(0, reply[16].toInt() and 0xff)
            assertEquals(0, reply[17].toInt() and 0xff)
            assertEquals(0, reply[18].toInt() and 0xff)
            assertEquals(0, reply[19].toInt() and 0xff)
            assertEquals(0, reply[20].toInt() and 0xff)
            assertEquals(0, reply[21].toInt() and 0xff)
            assertEquals(0, u16le(reply, 22))
            assertEquals(0, u32le(reply, 24))
            assertEquals(0, reply[28].toInt() and 0xff)
        }
    }

    @Test
    fun `XKEYBOARD GetNamedIndicator validates request length and recovers stream`() {
        withServer { socket, _ ->
            val out = socket.getOutputStream()
            out.write(request(XXkb.MajorOpcode, XXkb.GetNamedIndicator, ByteArray(8)))
            out.write(getNamedIndicatorRequest(0x0020_0400))
            out.flush()

            assertError(socket.getInputStream(), error = 16, opcode = XXkb.MajorOpcode, badValue = 0, sequence = 1, minorOpcode = XXkb.GetNamedIndicator)
            val reply = readReply(socket.getInputStream())
            assertEquals(2, u16le(reply, 2))
            assertEquals(0x0020_0400, u32le(reply, 8))
            assertEquals(0, reply[12].toInt() and 0xff)
            assertEquals(0, reply[28].toInt() and 0xff)
        }
    }

    @Test
    fun `XKEYBOARD SetNamedIndicator accepts fixed request without creating indicators`() {
        withServer { socket, _ ->
            val indicator = 0x0020_0400
            val out = socket.getOutputStream()
            out.write(setNamedIndicatorRequest(indicator, setState = true, on = true, setMap = true, createMap = true))
            out.write(getNamedIndicatorRequest(indicator))
            out.write(getIndicatorStateRequest())
            out.flush()

            val named = readReply(socket.getInputStream())
            assertEquals(2, u16le(named, 2))
            assertEquals(indicator, u32le(named, 8))
            assertEquals(0, named[12].toInt() and 0xff)
            assertEquals(0, named[13].toInt() and 0xff)
            assertEquals(0, named[28].toInt() and 0xff)

            val state = readReply(socket.getInputStream())
            assertEquals(3, u16le(state, 2))
            assertEquals(0, u32le(state, 8))
        }
    }

    @Test
    fun `XKEYBOARD SetNamedIndicator validates request length and recovers stream`() {
        withServer { socket, _ ->
            val indicator = 0x0020_0400
            val out = socket.getOutputStream()
            out.write(request(XXkb.MajorOpcode, XXkb.SetNamedIndicator, ByteArray(24)))
            out.write(request(XXkb.MajorOpcode, XXkb.SetNamedIndicator, ByteArray(32)))
            out.write(setNamedIndicatorRequest(indicator, setState = false, on = false, setMap = false, createMap = false))
            out.write(getNamedIndicatorRequest(indicator))
            out.flush()

            assertError(socket.getInputStream(), error = 16, opcode = XXkb.MajorOpcode, badValue = 0, sequence = 1, minorOpcode = XXkb.SetNamedIndicator)
            assertError(socket.getInputStream(), error = 16, opcode = XXkb.MajorOpcode, badValue = 0, sequence = 2, minorOpcode = XXkb.SetNamedIndicator)
            val reply = readReply(socket.getInputStream())
            assertEquals(4, u16le(reply, 2))
            assertEquals(indicator, u32le(reply, 8))
            assertEquals(0, reply[12].toInt() and 0xff)
            assertEquals(0, reply[28].toInt() and 0xff)
        }
    }

    @Test
    fun `XKEYBOARD GetNames reports key range with no requested named atoms`() {
        withServer { socket, _ ->
            val out = socket.getOutputStream()
            out.write(getNamesRequest(which = 0))
            out.flush()

            val reply = readReply(socket.getInputStream())
            assertEquals(1, reply[0].toInt())
            assertEquals(0, reply[1].toInt() and 0xff)
            assertEquals(1, u16le(reply, 2))
            assertEquals(0, u32le(reply, 4))
            assertEquals(0, u32le(reply, 8))
            assertEquals(XKeyboard.MinKeycode, reply[12].toInt() and 0xff)
            assertEquals(XKeyboard.MaxKeycode, reply[13].toInt() and 0xff)
            assertEquals(0, reply[14].toInt() and 0xff)
            assertEquals(0, reply[15].toInt() and 0xff)
            assertEquals(0, u16le(reply, 16))
            assertEquals(0, reply[18].toInt() and 0xff)
            assertEquals(0, reply[19].toInt() and 0xff)
            assertEquals(0, u32le(reply, 20))
            assertEquals(0, reply[24].toInt() and 0xff)
            assertEquals(0, reply[25].toInt() and 0xff)
            assertEquals(0, u16le(reply, 26))
            assertEquals(32, reply.size)
        }
    }

    @Test
    fun `XKEYBOARD GetNames reports component name atoms`() {
        withServer { socket, _ ->
            val out = socket.getOutputStream()
            val requested = XXkb.ComponentNameDetails
            out.write(getNamesRequest(which = requested))
            out.flush()

            val reply = readReply(socket.getInputStream())
            assertEquals(1, reply[0].toInt())
            assertEquals(0, reply[1].toInt() and 0xff)
            assertEquals(1, u16le(reply, 2))
            assertEquals(6, u32le(reply, 4))
            assertEquals(requested, u32le(reply, 8))
            assertEquals(XKeyboard.MinKeycode, reply[12].toInt() and 0xff)
            assertEquals(XKeyboard.MaxKeycode, reply[13].toInt() and 0xff)
            assertEquals(0, reply[14].toInt() and 0xff)
            assertEquals(0, reply[15].toInt() and 0xff)
            assertEquals(0, u16le(reply, 16))
            assertEquals(0, reply[18].toInt() and 0xff)
            assertEquals(0, reply[19].toInt() and 0xff)
            assertEquals(0, u32le(reply, 20))
            assertEquals(0, reply[24].toInt() and 0xff)
            assertEquals(0, reply[25].toInt() and 0xff)
            assertEquals(0, u16le(reply, 26))
            assertEquals(56, reply.size)

            val atoms = List(6) { index -> u32le(reply, 32 + index * 4) }
            assertEquals(listOf("evdev", "pc(pc105)", "us", "us", "complete", "complete"), atoms.map { atomName(socket, it) })
        }
    }

    @Test
    fun `XKEYBOARD GetNames all bits only reports implemented component name atoms`() {
        withServer { socket, _ ->
            val out = socket.getOutputStream()
            out.write(getNamesRequest(which = -1))
            out.flush()

            val reply = readReply(socket.getInputStream())
            assertEquals(1, reply[0].toInt())
            assertEquals(0, reply[1].toInt() and 0xff)
            assertEquals(1, u16le(reply, 2))
            assertEquals(6, u32le(reply, 4))
            assertEquals(XXkb.ComponentNameDetails, u32le(reply, 8))
            assertEquals(0, reply[14].toInt() and 0xff)
            assertEquals(0, reply[15].toInt() and 0xff)
            assertEquals(0, u16le(reply, 16))
            assertEquals(0, reply[18].toInt() and 0xff)
            assertEquals(0, reply[19].toInt() and 0xff)
            assertEquals(0, u32le(reply, 20))
            assertEquals(0, reply[24].toInt() and 0xff)
            assertEquals(0, reply[25].toInt() and 0xff)
            assertEquals(0, u16le(reply, 26))
            assertEquals(56, reply.size)
        }
    }

    @Test
    fun `XKEYBOARD GetNames reports component atoms in wire order for sparse mask`() {
        withServer { socket, _ ->
            val out = socket.getOutputStream()
            val requested = XXkb.NameDetailSymbols or XXkb.NameDetailTypes
            out.write(getNamesRequest(which = requested))
            out.flush()

            val reply = readReply(socket.getInputStream())
            assertEquals(1, reply[0].toInt())
            assertEquals(0, reply[1].toInt() and 0xff)
            assertEquals(1, u16le(reply, 2))
            assertEquals(2, u32le(reply, 4))
            assertEquals(requested, u32le(reply, 8))
            assertEquals(XKeyboard.MinKeycode, reply[12].toInt() and 0xff)
            assertEquals(XKeyboard.MaxKeycode, reply[13].toInt() and 0xff)
            assertEquals(40, reply.size)

            val atoms = List(2) { index -> u32le(reply, 32 + index * 4) }
            assertEquals(listOf("us", "complete"), atoms.map { atomName(socket, it) })
        }
    }

    @Test
    fun `XKEYBOARD GetNames swaps component atoms for big endian clients`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setupBigEndian(socket)

                val body = ByteArray(8)
                put16be(body, 0, 0x0100)
                put32be(body, 4, XXkb.NameDetailSymbols or XXkb.NameDetailTypes)
                socket.getOutputStream().write(requestBigEndian(XXkb.MajorOpcode, XXkb.GetNames, body))
                socket.getOutputStream().flush()

                val reply = readReplyBigEndian(socket.getInputStream())
                assertEquals(1, reply[0].toInt())
                assertEquals(0, reply[1].toInt() and 0xff)
                assertEquals(1, u16be(reply, 2))
                assertEquals(2, u32be(reply, 4))
                assertEquals(XXkb.NameDetailSymbols or XXkb.NameDetailTypes, u32be(reply, 8))
                assertEquals(XKeyboard.MinKeycode, reply[12].toInt() and 0xff)
                assertEquals(XKeyboard.MaxKeycode, reply[13].toInt() and 0xff)
                assertEquals(40, reply.size)
                assertEquals(listOf("us", "complete"), listOf(u32be(reply, 32), u32be(reply, 36)).map { atomNameBigEndian(socket, it) })
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `XKEYBOARD GetNames validates request length and recovers stream`() {
        withServer { socket, _ ->
            val out = socket.getOutputStream()
            out.write(request(XXkb.MajorOpcode, XXkb.GetNames, ByteArray(4)))
            out.write(getNamesRequest(which = 0))
            out.flush()

            assertError(socket.getInputStream(), error = 16, opcode = XXkb.MajorOpcode, badValue = 0, sequence = 1, minorOpcode = XXkb.GetNames)
            val reply = readReply(socket.getInputStream())
            assertEquals(2, u16le(reply, 2))
            assertEquals(0, u32le(reply, 8))
            assertEquals(XKeyboard.MinKeycode, reply[12].toInt() and 0xff)
            assertEquals(XKeyboard.MaxKeycode, reply[13].toInt() and 0xff)
        }
    }

    @Test
    fun `XKEYBOARD SetNames accepts full names payload without changing empty names`() {
        withServer { socket, _ ->
            val out = socket.getOutputStream()
            out.write(setNamesRequest(includeAllDetails = true))
            out.write(getNamesRequest(which = 0))
            out.flush()

            val reply = readReply(socket.getInputStream())
            assertEquals(2, u16le(reply, 2))
            assertEquals(0, u32le(reply, 8))
            assertEquals(XKeyboard.MinKeycode, reply[12].toInt() and 0xff)
            assertEquals(XKeyboard.MaxKeycode, reply[13].toInt() and 0xff)
            assertEquals(0, reply[14].toInt() and 0xff)
            assertEquals(0, reply[15].toInt() and 0xff)
            assertEquals(0, u16le(reply, 16))
            assertEquals(32, reply.size)
        }
    }

    @Test
    fun `XKEYBOARD SetNames validates variable payload length and recovers stream`() {
        withServer { socket, _ ->
            val out = socket.getOutputStream()
            out.write(request(XXkb.MajorOpcode, XXkb.SetNames, ByteArray(20)))
            out.write(setNamesRequest(includeAllDetails = true, bodySize = setNamesBodySize(includeAllDetails = true) - 4))
            out.write(setNamesRequest(includeAllDetails = true, bodySize = setNamesBodySize(includeAllDetails = true) + 4))
            out.write(setNamesRequest(includeAllDetails = false))
            out.write(getNamesRequest(which = 0))
            out.flush()

            assertError(socket.getInputStream(), error = 16, opcode = XXkb.MajorOpcode, badValue = 0, sequence = 1, minorOpcode = XXkb.SetNames)
            assertError(socket.getInputStream(), error = 16, opcode = XXkb.MajorOpcode, badValue = 0, sequence = 2, minorOpcode = XXkb.SetNames)
            assertError(socket.getInputStream(), error = 16, opcode = XXkb.MajorOpcode, badValue = 0, sequence = 3, minorOpcode = XXkb.SetNames)
            val reply = readReply(socket.getInputStream())
            assertEquals(5, u16le(reply, 2))
            assertEquals(0, u32le(reply, 8))
            assertEquals(XKeyboard.MinKeycode, reply[12].toInt() and 0xff)
            assertEquals(XKeyboard.MaxKeycode, reply[13].toInt() and 0xff)
        }
    }

    @Test
    fun `XKEYBOARD GetGeometry reports empty geometry`() {
        withServer { socket, _ ->
            val out = socket.getOutputStream()
            out.write(getGeometryRequest(name = 0x1122_3344))
            out.flush()

            val reply = readReply(socket.getInputStream())
            assertEquals(1, reply[0].toInt())
            assertEquals(0, reply[1].toInt() and 0xff)
            assertEquals(1, u16le(reply, 2))
            assertEquals(0, u32le(reply, 4))
            assertEquals(0x1122_3344, u32le(reply, 8))
            assertEquals(0, reply[12].toInt() and 0xff)
            assertEquals(0, reply[13].toInt() and 0xff)
            assertEquals(0, u16le(reply, 14))
            assertEquals(0, u16le(reply, 16))
            assertEquals(0, u16le(reply, 18))
            assertEquals(0, u16le(reply, 20))
            assertEquals(0, u16le(reply, 22))
            assertEquals(0, u16le(reply, 24))
            assertEquals(0, u16le(reply, 26))
            assertEquals(0, reply[28].toInt() and 0xff)
            assertEquals(0, reply[29].toInt() and 0xff)
            assertEquals(32, reply.size)
        }
    }

    @Test
    fun `XKEYBOARD GetGeometry validates request length and recovers stream`() {
        withServer { socket, _ ->
            val out = socket.getOutputStream()
            out.write(request(XXkb.MajorOpcode, XXkb.GetGeometry, ByteArray(4)))
            out.write(getGeometryRequest(name = 0))
            out.flush()

            assertError(socket.getInputStream(), error = 16, opcode = XXkb.MajorOpcode, badValue = 0, sequence = 1, minorOpcode = XXkb.GetGeometry)
            val reply = readReply(socket.getInputStream())
            assertEquals(2, u16le(reply, 2))
            assertEquals(0, u32le(reply, 4))
            assertEquals(0, u32le(reply, 8))
            assertEquals(0, reply[12].toInt() and 0xff)
            assertEquals(32, reply.size)
        }
    }

    @Test
    fun `XKEYBOARD SetGeometry accepts full geometry payload without changing empty geometry`() {
        withServer { socket, _ ->
            val out = socket.getOutputStream()
            out.write(setGeometryRequest())
            out.write(getGeometryRequest(name = 0x0102_0304))
            out.flush()

            val reply = readReply(socket.getInputStream())
            assertEquals(2, u16le(reply, 2))
            assertEquals(0, u32le(reply, 4))
            assertEquals(0x0102_0304, u32le(reply, 8))
            assertEquals(0, reply[12].toInt() and 0xff)
            assertEquals(32, reply.size)
        }
    }

    @Test
    fun `XKEYBOARD SetGeometry validates variable payload length and recovers stream`() {
        withServer { socket, _ ->
            val out = socket.getOutputStream()
            out.write(request(XXkb.MajorOpcode, XXkb.SetGeometry, ByteArray(20)))
            out.write(setGeometryRequest(bodySize = setGeometryBodySize() - 4))
            out.write(setGeometryRequest(bodySize = setGeometryBodySize() + 4))
            out.write(setGeometryRequest())
            out.write(getGeometryRequest(name = 0))
            out.flush()

            assertError(socket.getInputStream(), error = 16, opcode = XXkb.MajorOpcode, badValue = 0, sequence = 1, minorOpcode = XXkb.SetGeometry)
            assertError(socket.getInputStream(), error = 16, opcode = XXkb.MajorOpcode, badValue = 0, sequence = 2, minorOpcode = XXkb.SetGeometry)
            assertError(socket.getInputStream(), error = 16, opcode = XXkb.MajorOpcode, badValue = 0, sequence = 3, minorOpcode = XXkb.SetGeometry)
            val reply = readReply(socket.getInputStream())
            assertEquals(5, u16le(reply, 2))
            assertEquals(0, u32le(reply, 4))
            assertEquals(0, u32le(reply, 8))
            assertEquals(0, reply[12].toInt() and 0xff)
            assertEquals(32, reply.size)
        }
    }

    @Test
    fun `XKEYBOARD PerClientFlags reports no supported per-client flags`() {
        withServer { socket, _ ->
            val out = socket.getOutputStream()
            out.write(perClientFlagsRequest(change = -1, value = -1, ctrlsToChange = -1, autoCtrls = -1, autoCtrlsValues = -1))
            out.flush()

            val reply = readReply(socket.getInputStream())
            assertEquals(0, reply[1].toInt() and 0xff)
            assertEquals(1, u16le(reply, 2))
            assertEquals(0, u32le(reply, 4))
            assertEquals(0, u32le(reply, 8))
            assertEquals(0, u32le(reply, 12))
            assertEquals(0, u32le(reply, 16))
            assertEquals(0, u32le(reply, 20))
        }
    }

    @Test
    fun `XKEYBOARD PerClientFlags validates request length and recovers stream`() {
        withServer { socket, _ ->
            val out = socket.getOutputStream()
            out.write(request(XXkb.MajorOpcode, XXkb.PerClientFlags, ByteArray(20)))
            out.write(perClientFlagsRequest(change = 1, value = 1, ctrlsToChange = XXkb.BoolCtrlRepeatKeys, autoCtrls = 0, autoCtrlsValues = 0))
            out.flush()

            assertError(socket.getInputStream(), error = 16, opcode = XXkb.MajorOpcode, badValue = 0, sequence = 1, minorOpcode = XXkb.PerClientFlags)
            val reply = readReply(socket.getInputStream())
            assertEquals(2, u16le(reply, 2))
            assertEquals(0, u32le(reply, 8))
            assertEquals(0, u32le(reply, 12))
            assertEquals(0, u32le(reply, 16))
            assertEquals(0, u32le(reply, 20))
        }
    }

    @Test
    fun `XKEYBOARD ListComponents without trailing specs returns built-in component names`() {
        withServer { socket, _ ->
            val out = socket.getOutputStream()
            out.write(listComponentsRequest(maxNames = 64))
            out.flush()

            val reply = readReply(socket.getInputStream())
            assertEquals(1, reply[0].toInt())
            assertEquals(0, reply[1].toInt() and 0xff)
            assertEquals(1, u16le(reply, 2))
            assertEquals(17, u32le(reply, 4))
            assertEquals(1, u16le(reply, 8))
            assertEquals(1, u16le(reply, 10))
            assertEquals(1, u16le(reply, 12))
            assertEquals(1, u16le(reply, 14))
            assertEquals(1, u16le(reply, 16))
            assertEquals(1, u16le(reply, 18))
            assertEquals(0, u16le(reply, 20))
            assertEquals(
                listOf(
                    listOf(XXkb.ListComponentDefault to "base"),
                    listOf(XXkb.ListComponentDefault to "evdev"),
                    listOf(XXkb.ListComponentDefault to "complete"),
                    listOf(XXkb.ListComponentDefault to "complete"),
                    listOf(XXkb.ListComponentDefault to "us"),
                    listOf(XXkb.ListComponentDefault to "pc(pc105)"),
                ),
                xkbListingsByCategory(reply),
            )
            assertEquals(100, reply.size)
        }
    }

    @Test
    fun `XKEYBOARD ListComponents with explicit empty specs reports no component names`() {
        withServer { socket, _ ->
            val out = socket.getOutputStream()
            out.write(listComponentsRequest(maxNames = 64, trailingPatterns = xkbComponentSpecs()))
            out.flush()

            val reply = readReply(socket.getInputStream())
            assertEquals(1, reply[0].toInt())
            assertEquals(0, reply[1].toInt() and 0xff)
            assertEquals(1, u16le(reply, 2))
            assertEquals(0, u32le(reply, 4))
            assertEquals(0, u16le(reply, 8))
            assertEquals(0, u16le(reply, 10))
            assertEquals(0, u16le(reply, 12))
            assertEquals(0, u16le(reply, 14))
            assertEquals(0, u16le(reply, 16))
            assertEquals(0, u16le(reply, 18))
            assertEquals(0, u16le(reply, 20))
            assertEquals(emptyList(), xkbListingsByCategory(reply).flatten())
            assertEquals(32, reply.size)
        }
    }

    @Test
    fun `XKEYBOARD ListComponents accepts trailing pattern data and throttles matches`() {
        withServer { socket, _ ->
            val out = socket.getOutputStream()
            val trailingPatterns = xkbComponentSpecs("base", "evdev", "complete", "complete", "us", "pc(pc105)")
            out.write(listComponentsRequest(maxNames = 1, trailingPatterns = trailingPatterns))
            out.flush()

            val reply = readReply(socket.getInputStream())
            assertEquals(1, reply[0].toInt())
            assertEquals(0, reply[1].toInt() and 0xff)
            assertEquals(1, u16le(reply, 2))
            assertEquals(2, u32le(reply, 4))
            assertEquals(1, u16le(reply, 8))
            assertEquals(0, u16le(reply, 10))
            assertEquals(5, u16le(reply, 20))
            assertEquals(
                listOf(
                    listOf(XXkb.ListComponentDefault to "base"),
                    emptyList(),
                    emptyList(),
                    emptyList(),
                    emptyList(),
                    emptyList(),
                ),
                xkbListingsByCategory(reply),
            )
            assertEquals(40, reply.size)
        }
    }

    @Test
    fun `XKEYBOARD ListComponents returns built-in component names matching patterns`() {
        withServer { socket, _ ->
            val out = socket.getOutputStream()
            val trailingPatterns = xkbComponentSpecs("base", "evdev", "complete", "complete", "u+s", "p c(*)")
            out.write(listComponentsRequest(maxNames = 64, trailingPatterns = trailingPatterns))
            out.flush()

            val reply = readReply(socket.getInputStream())
            assertEquals(1, reply[0].toInt())
            assertEquals(0, reply[1].toInt() and 0xff)
            assertEquals(1, u16le(reply, 2))
            assertEquals(17, u32le(reply, 4))
            assertEquals(1, u16le(reply, 8))
            assertEquals(1, u16le(reply, 10))
            assertEquals(1, u16le(reply, 12))
            assertEquals(1, u16le(reply, 14))
            assertEquals(1, u16le(reply, 16))
            assertEquals(1, u16le(reply, 18))
            assertEquals(0, u16le(reply, 20))
            assertEquals(
                listOf(
                    listOf(XXkb.ListComponentDefault to "base"),
                    listOf(XXkb.ListComponentDefault to "evdev"),
                    listOf(XXkb.ListComponentDefault to "complete"),
                    listOf(XXkb.ListComponentDefault to "complete"),
                    listOf(XXkb.ListComponentDefault to "us"),
                    listOf(XXkb.ListComponentDefault to "pc(pc105)"),
                ),
                xkbListingsByCategory(reply),
            )
            assertEquals(100, reply.size)
        }
    }

    @Test
    fun `XKEYBOARD ListComponents throttles names and reports extra matches`() {
        withServer { socket, _ ->
            val out = socket.getOutputStream()
            val trailingPatterns = xkbComponentSpecs("*", "*", "*", "*", "*", "pc(*)")
            out.write(listComponentsRequest(maxNames = 3, trailingPatterns = trailingPatterns))
            out.flush()

            val reply = readReply(socket.getInputStream())
            assertEquals(1, reply[0].toInt())
            assertEquals(0, reply[1].toInt() and 0xff)
            assertEquals(1, u16le(reply, 2))
            assertEquals(8, u32le(reply, 4))
            assertEquals(1, u16le(reply, 8))
            assertEquals(1, u16le(reply, 10))
            assertEquals(1, u16le(reply, 12))
            assertEquals(0, u16le(reply, 14))
            assertEquals(0, u16le(reply, 16))
            assertEquals(0, u16le(reply, 18))
            assertEquals(3, u16le(reply, 20))
            assertEquals(
                listOf(
                    listOf(XXkb.ListComponentDefault to "base"),
                    listOf(XXkb.ListComponentDefault to "evdev"),
                    listOf(XXkb.ListComponentDefault to "complete"),
                    emptyList(),
                    emptyList(),
                    emptyList(),
                ),
                xkbListingsByCategory(reply),
            )
            assertEquals(64, reply.size)
        }
    }

    @Test
    fun `XKEYBOARD ListComponents validates component pattern lengths and recovers stream`() {
        withServer { socket, _ ->
            val out = socket.getOutputStream()
            out.write(listComponentsRequest(maxNames = 1, trailingPatterns = byteArrayOf(5, 'b'.code.toByte(), 0, 0)))
            out.write(listComponentsRequest(maxNames = 1, trailingPatterns = xkbComponentSpecs("base")))
            out.flush()

            assertError(socket.getInputStream(), error = 16, opcode = XXkb.MajorOpcode, badValue = 0, sequence = 1, minorOpcode = XXkb.ListComponents)
            val reply = readReply(socket.getInputStream())
            assertEquals(2, u16le(reply, 2))
            assertEquals(1, u16le(reply, 8))
            assertEquals(0, u16le(reply, 20))
            assertEquals(
                listOf(
                    listOf(XXkb.ListComponentDefault to "base"),
                    emptyList(),
                    emptyList(),
                    emptyList(),
                    emptyList(),
                    emptyList(),
                ),
                xkbListingsByCategory(reply),
            )
            assertEquals(40, reply.size)
        }
    }

    @Test
    fun `XKEYBOARD ListComponents validates fixed prefix length and recovers stream`() {
        withServer { socket, _ ->
            val out = socket.getOutputStream()
            out.write(request(XXkb.MajorOpcode, XXkb.ListComponents, ByteArray(0)))
            out.write(listComponentsRequest(maxNames = 1))
            out.flush()

            assertError(socket.getInputStream(), error = 16, opcode = XXkb.MajorOpcode, badValue = 0, sequence = 1, minorOpcode = XXkb.ListComponents)
            val reply = readReply(socket.getInputStream())
            assertEquals(2, u16le(reply, 2))
            assertEquals(1, u16le(reply, 8))
            assertEquals(5, u16le(reply, 20))
            assertEquals(
                listOf(
                    listOf(XXkb.ListComponentDefault to "base"),
                    emptyList(),
                    emptyList(),
                    emptyList(),
                    emptyList(),
                    emptyList(),
                ),
                xkbListingsByCategory(reply),
            )
            assertEquals(40, reply.size)
        }
    }

    @Test
    fun `XKEYBOARD GetKbdByName reports key range with no loaded components`() {
        withServer { socket, _ ->
            val out = socket.getOutputStream()
            out.write(getKbdByNameRequest(need = -1, want = -1, load = true))
            out.flush()

            val reply = readReply(socket.getInputStream())
            assertEquals(1, reply[0].toInt())
            assertEquals(0, reply[1].toInt() and 0xff)
            assertEquals(1, u16le(reply, 2))
            assertEquals(0, u32le(reply, 4))
            assertEquals(XKeyboard.MinKeycode, reply[8].toInt() and 0xff)
            assertEquals(XKeyboard.MaxKeycode, reply[9].toInt() and 0xff)
            assertEquals(0, reply[10].toInt() and 0xff)
            assertEquals(0, reply[11].toInt() and 0xff)
            assertEquals(0, u16le(reply, 12))
            assertEquals(0, u16le(reply, 14))
            assertEquals(32, reply.size)
        }
    }

    @Test
    fun `XKEYBOARD GetKbdByName accepts and ignores trailing component names`() {
        withServer { socket, _ ->
            val out = socket.getOutputStream()
            val trailingNames = xkbComponentSpecs("base", "evdev", "complete", "pc", "us", "pc105")
            out.write(getKbdByNameRequest(need = 0, want = 0, load = false, trailingNames = trailingNames))
            out.flush()

            val reply = readReply(socket.getInputStream())
            assertEquals(1, reply[0].toInt())
            assertEquals(0, reply[1].toInt() and 0xff)
            assertEquals(1, u16le(reply, 2))
            assertEquals(0, u32le(reply, 4))
            assertEquals(XKeyboard.MinKeycode, reply[8].toInt() and 0xff)
            assertEquals(XKeyboard.MaxKeycode, reply[9].toInt() and 0xff)
            assertEquals(0, u16le(reply, 12))
            assertEquals(0, u16le(reply, 14))
            assertEquals(32, reply.size)
        }
    }

    @Test
    fun `XKEYBOARD GetKbdByName validates component name lengths and recovers stream`() {
        withServer { socket, _ ->
            val out = socket.getOutputStream()
            out.write(getKbdByNameRequest(need = 0, want = 0, load = false, trailingNames = byteArrayOf(5, 'b'.code.toByte(), 0, 0)))
            out.write(getKbdByNameRequest(need = 0, want = 0, load = false, trailingNames = xkbComponentSpecs("base")))
            out.flush()

            assertError(socket.getInputStream(), error = 16, opcode = XXkb.MajorOpcode, badValue = 0, sequence = 1, minorOpcode = XXkb.GetKbdByName)
            val reply = readReply(socket.getInputStream())
            assertEquals(2, u16le(reply, 2))
            assertEquals(XKeyboard.MinKeycode, reply[8].toInt() and 0xff)
            assertEquals(XKeyboard.MaxKeycode, reply[9].toInt() and 0xff)
            assertEquals(0, u16le(reply, 12))
            assertEquals(0, u16le(reply, 14))
        }
    }

    @Test
    fun `XKEYBOARD GetKbdByName validates fixed prefix length and recovers stream`() {
        withServer { socket, _ ->
            val out = socket.getOutputStream()
            out.write(request(XXkb.MajorOpcode, XXkb.GetKbdByName, ByteArray(4)))
            out.write(getKbdByNameRequest(need = 0, want = 0, load = false))
            out.flush()

            assertError(socket.getInputStream(), error = 16, opcode = XXkb.MajorOpcode, badValue = 0, sequence = 1, minorOpcode = XXkb.GetKbdByName)
            val reply = readReply(socket.getInputStream())
            assertEquals(2, u16le(reply, 2))
            assertEquals(XKeyboard.MinKeycode, reply[8].toInt() and 0xff)
            assertEquals(XKeyboard.MaxKeycode, reply[9].toInt() and 0xff)
            assertEquals(0, u16le(reply, 12))
            assertEquals(0, u16le(reply, 14))
        }
    }

    @Test
    fun `XKEYBOARD GetDeviceInfo reports unsupported XI features and pointer button count`() {
        withServer { socket, _ ->
            val wanted = XXkb.XiFeatureButtonActions or XXkb.XiFeatureIndicatorNames or XXkb.XiFeatureIndicatorMaps or XXkb.XiFeatureIndicatorState
            val out = socket.getOutputStream()
            out.write(getDeviceInfoRequest(wanted = wanted, allButtons = true, firstButton = 1, nButtons = 3, ledClass = 0x0300, ledId = 0x0400))
            out.flush()

            val reply = readReply(socket.getInputStream())
            assertEquals(1, reply[0].toInt())
            assertEquals(0, reply[1].toInt() and 0xff)
            assertEquals(1, u16le(reply, 2))
            assertEquals(511, u32le(reply, 4))
            assertEquals(XXkb.XiFeatureButtonActions, u16le(reply, 8))
            assertEquals(XXkb.XiFeatureButtonActions, u16le(reply, 10))
            assertEquals(wanted and XXkb.XiFeatureButtonActions.inv(), u16le(reply, 12))
            assertEquals(0, u16le(reply, 14))
            assertEquals(1, reply[16].toInt() and 0xff)
            assertEquals(3, reply[17].toInt() and 0xff)
            assertEquals(1, reply[18].toInt() and 0xff)
            assertEquals(255, reply[19].toInt() and 0xff)
            assertEquals(255, reply[20].toInt() and 0xff)
            assertEquals(0, reply[21].toInt() and 0xff)
            assertEquals(0, u16le(reply, 22))
            assertEquals(0, u16le(reply, 24))
            assertEquals(0, u32le(reply, 28))
            assertEquals(0, u16le(reply, 32))
            assertEquals(true, reply.copyOfRange(36, reply.size).all { it == 0.toByte() })
            assertEquals(2076, reply.size)
        }
    }

    @Test
    fun `XKEYBOARD GetDeviceInfo only reports button actions for core pointer device`() {
        withServer { socket, _ ->
            val out = socket.getOutputStream()
            out.write(
                getDeviceInfoRequest(
                    wanted = XXkb.XiFeatureButtonActions,
                    allButtons = false,
                    firstButton = 1,
                    nButtons = 1,
                    deviceSpec = XXkb.DeviceSpecUseCoreKeyboard,
                ),
            )
            out.flush()

            val reply = readReply(socket.getInputStream())
            assertEquals(1, u16le(reply, 2))
            assertEquals(1, u32le(reply, 4))
            assertEquals(0, u16le(reply, 8))
            assertEquals(0, u16le(reply, 10))
            assertEquals(XXkb.XiFeatureButtonActions, u16le(reply, 12))
            assertEquals(0, reply[18].toInt() and 0xff)
            assertEquals(0, reply[19].toInt() and 0xff)
            assertEquals(0, reply[20].toInt() and 0xff)
            assertEquals(0, u16le(reply, 32))
            assertEquals(36, reply.size)
        }
    }

    @Test
    fun `XKEYBOARD GetDeviceInfo validates request length and recovers stream`() {
        withServer { socket, _ ->
            val out = socket.getOutputStream()
            out.write(request(XXkb.MajorOpcode, XXkb.GetDeviceInfo, ByteArray(8)))
            out.write(getDeviceInfoRequest(wanted = XXkb.XiFeatureButtonActions, allButtons = false, firstButton = 2, nButtons = 1))
            out.flush()

            assertError(socket.getInputStream(), error = 16, opcode = XXkb.MajorOpcode, badValue = 0, sequence = 1, minorOpcode = XXkb.GetDeviceInfo)
            val reply = readReply(socket.getInputStream())
            assertEquals(2, u16le(reply, 2))
            assertEquals(3, u32le(reply, 4))
            assertEquals(XXkb.XiFeatureButtonActions, u16le(reply, 8))
            assertEquals(XXkb.XiFeatureButtonActions, u16le(reply, 10))
            assertEquals(0, u16le(reply, 12))
            assertEquals(2, reply[16].toInt() and 0xff)
            assertEquals(1, reply[17].toInt() and 0xff)
            assertEquals(2, reply[18].toInt() and 0xff)
            assertEquals(1, reply[19].toInt() and 0xff)
            assertEquals(255, reply[20].toInt() and 0xff)
            assertEquals(0, u16le(reply, 32))
            assertEquals(true, reply.copyOfRange(36, reply.size).all { it == 0.toByte() })
            assertEquals(44, reply.size)
        }
    }

    @Test
    fun `XKEYBOARD SetDeviceInfo accepts button actions and LED feedback without changing empty device info`() {
        withServer { socket, _ ->
            val wanted = XXkb.XiFeatureButtonActions or XXkb.XiFeatureIndicatorNames or XXkb.XiFeatureIndicatorMaps
            val out = socket.getOutputStream()
            out.write(setDeviceInfoRequest(nButtons = 2, nDeviceLedFeedbacks = 1))
            out.write(getDeviceInfoRequest(wanted = wanted, allButtons = false, firstButton = 1, nButtons = 2))
            out.flush()

            val reply = readReply(socket.getInputStream())
            assertEquals(2, u16le(reply, 2))
            assertEquals(5, u32le(reply, 4))
            assertEquals(XXkb.XiFeatureButtonActions, u16le(reply, 8))
            assertEquals(XXkb.XiFeatureButtonActions, u16le(reply, 10))
            assertEquals(wanted and XXkb.XiFeatureButtonActions.inv(), u16le(reply, 12))
            assertEquals(1, reply[16].toInt() and 0xff)
            assertEquals(2, reply[17].toInt() and 0xff)
            assertEquals(1, reply[18].toInt() and 0xff)
            assertEquals(2, reply[19].toInt() and 0xff)
            assertEquals(255, reply[20].toInt() and 0xff)
            assertEquals(0, u16le(reply, 32))
            assertEquals(1, reply[36].toInt() and 0xff)
            assertEquals(true, reply.copyOfRange(37, 44).all { it == 0.toByte() })
            assertEquals(1, reply[44].toInt() and 0xff)
            assertEquals(true, reply.copyOfRange(45, 52).all { it == 0.toByte() })
            assertEquals(52, reply.size)
        }
    }

    @Test
    fun `XKEYBOARD SetDeviceInfo validates variable payload length and recovers stream`() {
        withServer { socket, _ ->
            val out = socket.getOutputStream()
            out.write(request(XXkb.MajorOpcode, XXkb.SetDeviceInfo, ByteArray(4)))
            out.write(setDeviceInfoRequest(nButtons = 1, nDeviceLedFeedbacks = 1, bodySize = 48))
            out.write(setDeviceInfoRequest(nButtons = 1, nDeviceLedFeedbacks = 1, bodySize = 56))
            out.write(setDeviceInfoRequest(nButtons = 2, nDeviceLedFeedbacks = 2, change = 0))
            out.write(getDeviceInfoRequest(wanted = XXkb.XiFeatureButtonActions, allButtons = false, firstButton = 2, nButtons = 1))
            out.flush()

            assertError(socket.getInputStream(), error = 16, opcode = XXkb.MajorOpcode, badValue = 0, sequence = 1, minorOpcode = XXkb.SetDeviceInfo)
            assertError(socket.getInputStream(), error = 16, opcode = XXkb.MajorOpcode, badValue = 0, sequence = 2, minorOpcode = XXkb.SetDeviceInfo)
            assertError(socket.getInputStream(), error = 16, opcode = XXkb.MajorOpcode, badValue = 0, sequence = 3, minorOpcode = XXkb.SetDeviceInfo)
            val reply = readReply(socket.getInputStream())
            assertEquals(5, u16le(reply, 2))
            assertEquals(3, u32le(reply, 4))
            assertEquals(XXkb.XiFeatureButtonActions, u16le(reply, 8))
            assertEquals(XXkb.XiFeatureButtonActions, u16le(reply, 10))
            assertEquals(0, u16le(reply, 12))
            assertEquals(2, reply[16].toInt() and 0xff)
            assertEquals(1, reply[17].toInt() and 0xff)
            assertEquals(2, reply[18].toInt() and 0xff)
            assertEquals(1, reply[19].toInt() and 0xff)
            assertEquals(255, reply[20].toInt() and 0xff)
            assertEquals(0, u16le(reply, 32))
            assertEquals(true, reply.copyOfRange(36, reply.size).all { it == 0.toByte() })
            assertEquals(44, reply.size)
        }
    }

    @Test
    fun `XKEYBOARD SetDebuggingFlags reports no supported debugging flags`() {
        withServer { socket, _ ->
            val out = socket.getOutputStream()
            out.write(setDebuggingFlagsRequest(message = "trace", affectFlags = -1, flags = -1, affectCtrls = -1, ctrls = -1))
            out.flush()

            val reply = readReply(socket.getInputStream())
            assertEquals(1, reply[0].toInt())
            assertEquals(0, reply[1].toInt() and 0xff)
            assertEquals(1, u16le(reply, 2))
            assertEquals(0, u32le(reply, 4))
            assertEquals(0, u32le(reply, 8))
            assertEquals(0, u32le(reply, 12))
            assertEquals(0, u32le(reply, 16))
            assertEquals(0, u32le(reply, 20))
            assertEquals(32, reply.size)
        }
    }

    @Test
    fun `XKEYBOARD SetDebuggingFlags validates padded message length and recovers stream`() {
        withServer { socket, _ ->
            val out = socket.getOutputStream()
            val malformed = ByteArray(24)
            put16le(malformed, 0, 5)
            out.write(request(XXkb.MajorOpcode, XXkb.SetDebuggingFlags, ByteArray(16)))
            out.write(request(XXkb.MajorOpcode, XXkb.SetDebuggingFlags, malformed))
            out.write(setDebuggingFlagsRequest(message = "trace", affectFlags = -1, flags = -1, affectCtrls = -1, ctrls = -1, extraBytes = 4))
            out.write(setDebuggingFlagsRequest(message = "", affectFlags = 1, flags = 1, affectCtrls = 1, ctrls = 1))
            out.flush()

            assertError(socket.getInputStream(), error = 16, opcode = XXkb.MajorOpcode, badValue = 0, sequence = 1, minorOpcode = XXkb.SetDebuggingFlags)
            assertError(socket.getInputStream(), error = 16, opcode = XXkb.MajorOpcode, badValue = 0, sequence = 2, minorOpcode = XXkb.SetDebuggingFlags)
            assertError(socket.getInputStream(), error = 16, opcode = XXkb.MajorOpcode, badValue = 0, sequence = 3, minorOpcode = XXkb.SetDebuggingFlags)
            val reply = readReply(socket.getInputStream())
            assertEquals(4, u16le(reply, 2))
            assertEquals(0, u32le(reply, 8))
            assertEquals(0, u32le(reply, 12))
            assertEquals(0, u32le(reply, 16))
            assertEquals(0, u32le(reply, 20))
        }
    }

    @Test
    fun `XKEYBOARD unimplemented requests return BadImplementation and recover stream`() {
        withServer { socket, port ->
            val out = socket.getOutputStream()
            out.write(request(XXkb.MajorOpcode, 26, ByteArray(0)))
            out.write(useExtensionRequest())
            out.flush()

            assertError(socket.getInputStream(), error = 17, opcode = XXkb.MajorOpcode, badValue = 0, sequence = 1, minorOpcode = 26)
            val version = readReply(socket.getInputStream())
            assertEquals(1, version[1].toInt() and 0xff)
            assertEquals(XXkb.MajorVersion, u16le(version, 8))

            assertContains(httpGet(port, "/text.txt"), "XKEYBOARD.Unknown:")
        }
    }

    private fun withServer(block: (Socket, Int) -> Unit) {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                block(socket, server.localPort)
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    private fun setup(socket: Socket) {
        val out = socket.getOutputStream()
        val input = socket.getInputStream()
        out.write(byteArrayOf(0x6c, 0, 11, 0, 0, 0, 0, 0, 0, 0, 0, 0))
        out.flush()
        val prefix = input.readExactly(8)
        assertEquals(1, prefix[0].toInt())
        input.readExactly(u16le(prefix, 6) * 4)
    }

    private fun setupBigEndian(socket: Socket) {
        val out = socket.getOutputStream()
        val input = socket.getInputStream()
        val setup = ByteArray(12)
        setup[0] = 0x42
        put16be(setup, 2, 11)
        out.write(setup)
        out.flush()
        val prefix = input.readExactly(8)
        assertEquals(1, prefix[0].toInt())
        input.readExactly(u16be(prefix, 6) * 4)
    }

    private fun queryExtensionRequest(name: String): ByteArray {
        val nameBytes = name.encodeToByteArray()
        val body = ByteArray(4 + ((nameBytes.size + 3) and -4))
        put16le(body, 0, nameBytes.size)
        nameBytes.copyInto(body, 4)
        return request(98, 0, body)
    }

    private fun useExtensionRequest(): ByteArray {
        val body = ByteArray(4)
        put16le(body, 0, XXkb.MajorVersion)
        put16le(body, 2, XXkb.MinorVersion)
        return request(XXkb.MajorOpcode, XXkb.UseExtension, body)
    }

    private fun selectEventsRequest(
        affectWhich: Int = 0,
        clear: Int = 0,
        selectAll: Int = 0,
        affectMap: Int = 0,
        map: Int = 0,
        details: ByteArray = ByteArray(0),
    ): ByteArray {
        val body = ByteArray(paddedSize(12 + details.size))
        put16le(body, 0, 0x0100)
        put16le(body, 2, affectWhich)
        put16le(body, 4, clear)
        put16le(body, 6, selectAll)
        put16le(body, 8, affectMap)
        put16le(body, 10, map)
        details.copyInto(body, 12)
        return request(XXkb.MajorOpcode, XXkb.SelectEvents, body)
    }

    private fun selectEvents16Details(affect: Int, selected: Int): ByteArray =
        ByteArray(4).also {
            put16le(it, 0, affect)
            put16le(it, 2, selected)
        }

    private fun selectEvents8Details(affect: Int, selected: Int): ByteArray =
        byteArrayOf(affect.toByte(), selected.toByte())

    private fun xkbBellRequest(percent: Int): ByteArray {
        val body = ByteArray(24)
        put16le(body, 0, 0x0100)
        put16le(body, 2, 0)
        put16le(body, 4, 0)
        body[6] = percent.toByte()
        body[7] = 0
        body[8] = 0
        put16le(body, 10, 0)
        put16le(body, 12, 0)
        put32le(body, 16, 0)
        put32le(body, 20, 0)
        return request(XXkb.MajorOpcode, XXkb.Bell, body)
    }

    private fun getStateRequest(): ByteArray {
        val body = ByteArray(4)
        put16le(body, 0, 0x0100)
        return request(XXkb.MajorOpcode, XXkb.GetState, body)
    }

    private fun queryPointerRequest(): ByteArray {
        val body = ByteArray(4)
        put32le(body, 0, X11Ids.RootWindow)
        return request(38, 0, body)
    }

    private fun latchLockStateRequest(modLocks: Int, groupLock: Int, latchGroup: Boolean, groupLatch: Int): ByteArray {
        val body = ByteArray(12)
        put16le(body, 0, 0x0100)
        body[2] = 0xff.toByte()
        body[3] = modLocks.toByte()
        body[4] = 1
        body[5] = groupLock.toByte()
        body[6] = 0xff.toByte()
        body[7] = 0
        body[9] = if (latchGroup) 1 else 0
        put16le(body, 10, groupLatch)
        return request(XXkb.MajorOpcode, XXkb.LatchLockState, body)
    }

    private fun getControlsRequest(): ByteArray {
        val body = ByteArray(4)
        put16le(body, 0, 0x0100)
        return request(XXkb.MajorOpcode, XXkb.GetControls, body)
    }

    private fun getKeyboardControlRequest(): ByteArray =
        request(103, 0, ByteArray(0))

    private fun setControlsRequest(affectEnabledControls: Int, enabledControls: Int): ByteArray {
        val body = ByteArray(96) { 0 }
        put16le(body, 0, 0x0100)
        body[14] = XXkb.DefaultMouseKeysButton.toByte()
        body[15] = XXkb.DefaultGroupCount.toByte()
        put32le(body, 20, affectEnabledControls)
        put32le(body, 24, enabledControls)
        put32le(body, 28, affectEnabledControls)
        put16le(body, 32, XXkb.DefaultRepeatDelay)
        put16le(body, 34, XXkb.DefaultRepeatInterval)
        for (index in 64 until 96) {
            body[index] = 0xff.toByte()
        }
        return request(XXkb.MajorOpcode, XXkb.SetControls, body)
    }

    private fun getMapRequest(full: Int, partial: Int): ByteArray {
        val body = ByteArray(24)
        put16le(body, 0, 0x0100)
        put16le(body, 2, full)
        put16le(body, 4, partial)
        body[6] = 0
        body[7] = 0xff.toByte()
        body[8] = XKeyboard.MinKeycode.toByte()
        body[9] = 0xff.toByte()
        body[10] = XKeyboard.MinKeycode.toByte()
        body[11] = 0xff.toByte()
        body[12] = XKeyboard.MinKeycode.toByte()
        body[13] = 0xff.toByte()
        put16le(body, 14, 0xffff)
        body[16] = XKeyboard.MinKeycode.toByte()
        body[17] = 0xff.toByte()
        body[18] = XKeyboard.MinKeycode.toByte()
        body[19] = 0xff.toByte()
        body[20] = XKeyboard.MinKeycode.toByte()
        body[21] = 0xff.toByte()
        return request(XXkb.MajorOpcode, XXkb.GetMap, body)
    }

    private fun setMapRequest(
        includeAllParts: Boolean,
        oddExplicitAndModifierMapCounts: Boolean = false,
        bodySize: Int = setMapBodySize(includeAllParts),
    ): ByteArray {
        val body = ByteArray(bodySize)
        val present = if (includeAllParts) {
            XXkb.MapPartKeyTypes or
                XXkb.MapPartKeySyms or
                XXkb.MapPartModifierMap or
                XXkb.MapPartExplicitComponents or
                XXkb.MapPartKeyActions or
                XXkb.MapPartKeyBehaviors or
                XXkb.MapPartVirtualMods or
                XXkb.MapPartVirtualModMap
        } else {
            0
        }
        put16le(body, 0, 0x0100)
        put16le(body, 2, present)
        put16le(body, 4, 0)
        body[6] = XKeyboard.MinKeycode.toByte()
        body[7] = XKeyboard.MaxKeycode.toByte()
        if (includeAllParts) {
            val explicitCount = if (oddExplicitAndModifierMapCounts) 1 else 2
            val modMapCount = if (oddExplicitAndModifierMapCounts) 1 else 2
            body[8] = 0
            body[9] = 2
            body[10] = XKeyboard.MinKeycode.toByte()
            body[11] = 2
            put16le(body, 12, 3)
            body[14] = XKeyboard.MinKeycode.toByte()
            body[15] = 3
            put16le(body, 16, 2)
            body[18] = XKeyboard.MinKeycode.toByte()
            body[19] = 2
            body[20] = 2
            body[21] = XKeyboard.MinKeycode.toByte()
            body[22] = 2
            body[23] = explicitCount.toByte()
            body[24] = XKeyboard.MinKeycode.toByte()
            body[25] = 2
            body[26] = modMapCount.toByte()
            body[27] = XKeyboard.MinKeycode.toByte()
            body[28] = 2
            body[29] = 2
            put16le(body, 30, 0x0003)
        }

        var offset = 32
        fun write(size: Int, block: (Int) -> Unit = {}) {
            if (offset + size <= body.size) block(offset)
            offset += size
        }
        fun align4() {
            offset = (offset + 3) and -4
        }
        if (includeAllParts) {
            write(24) {
                body[it + 4] = 2
                body[it + 5] = 2
                body[it + 6] = 1
            }
            write(12) {
                body[it + 4] = 1
                body[it + 5] = 1
            }
            write(16) { put16le(body, it + 6, 2) }
            write(12) { put16le(body, it + 6, 1) }
            write(3)
            align4()
            write(16)
            write(8)
            write(if (oddExplicitAndModifierMapCounts) 1 * 2 else 2 * 2)
            align4()
            write(if (oddExplicitAndModifierMapCounts) 1 * 2 else 2 * 2)
            align4()
            write(8)
        }
        return request(XXkb.MajorOpcode, XXkb.SetMap, body)
    }

    private fun setMapBodySize(includeAllParts: Boolean): Int =
        if (includeAllParts) 144 else 32

    private fun getCompatMapRequest(groups: Int, getAllSI: Boolean, firstSI: Int, nSI: Int): ByteArray {
        val body = ByteArray(8)
        put16le(body, 0, 0x0100)
        body[2] = groups.toByte()
        body[3] = if (getAllSI) 1 else 0
        put16le(body, 4, firstSI)
        put16le(body, 6, nSI)
        return request(XXkb.MajorOpcode, XXkb.GetCompatMap, body)
    }

    private fun setCompatMapRequest(groups: Int, nSI: Int, bodySize: Int = 12 + nSI * 16 + Integer.bitCount(groups) * 4): ByteArray {
        val body = ByteArray(bodySize)
        put16le(body, 0, 0x0100)
        if (body.size > 3) body[3] = 1
        if (body.size > 4) body[4] = 1
        if (body.size > 5) body[5] = groups.toByte()
        if (body.size >= 10) put16le(body, 8, nSI)
        for (index in 0 until nSI) {
            val offset = 12 + index * 16
            if (offset + 16 > body.size) break
            put32le(body, offset, 0)
            body[offset + 4] = 1
            body[offset + 5] = 1
            body[offset + 7] = 1
        }
        var offset = 12 + nSI * 16
        repeat(Integer.bitCount(groups)) {
            if (offset + 4 > body.size) return@repeat
            body[offset] = 1
            body[offset + 1] = 1
            put16le(body, offset + 2, 1)
            offset += 4
        }
        return request(XXkb.MajorOpcode, XXkb.SetCompatMap, body)
    }

    private fun getIndicatorStateRequest(): ByteArray {
        val body = ByteArray(4)
        put16le(body, 0, 0x0100)
        return request(XXkb.MajorOpcode, XXkb.GetIndicatorState, body)
    }

    private fun getIndicatorMapRequest(which: Int): ByteArray {
        val body = ByteArray(8)
        put16le(body, 0, 0x0100)
        put32le(body, 4, which)
        return request(XXkb.MajorOpcode, XXkb.GetIndicatorMap, body)
    }

    private fun setIndicatorMapRequest(which: Int, bodySize: Int = 8 + Integer.bitCount(which) * 12): ByteArray {
        val body = ByteArray(bodySize)
        put16le(body, 0, 0x0100)
        if (body.size >= 8) {
            put32le(body, 4, which)
        }
        for (index in 0 until Integer.bitCount(which)) {
            val offset = 8 + index * 12
            if (offset + 12 > body.size) break
            body[offset] = 1
            body[offset + 1] = 1
            body[offset + 2] = 1
            body[offset + 3] = 1
            body[offset + 4] = 1
            body[offset + 5] = 1
            put16le(body, offset + 6, 1)
            put32le(body, offset + 8, XXkb.BoolCtrlRepeatKeys)
        }
        return request(XXkb.MajorOpcode, XXkb.SetIndicatorMap, body)
    }

    private fun getNamedIndicatorRequest(indicator: Int): ByteArray {
        val body = ByteArray(12)
        put16le(body, 0, 0x0100)
        put16le(body, 2, 0)
        put16le(body, 4, 0)
        put32le(body, 8, indicator)
        return request(XXkb.MajorOpcode, XXkb.GetNamedIndicator, body)
    }

    private fun setNamedIndicatorRequest(indicator: Int, setState: Boolean, on: Boolean, setMap: Boolean, createMap: Boolean): ByteArray {
        val body = ByteArray(28)
        put16le(body, 0, 0x0100)
        put16le(body, 2, 0)
        put16le(body, 4, 0)
        put32le(body, 8, indicator)
        body[12] = if (setState) 1 else 0
        body[13] = if (on) 1 else 0
        body[14] = if (setMap) 1 else 0
        body[15] = if (createMap) 1 else 0
        put16le(body, 22, 0)
        put32le(body, 24, 0)
        return request(XXkb.MajorOpcode, XXkb.SetNamedIndicator, body)
    }

    private fun getNamesRequest(which: Int): ByteArray {
        val body = ByteArray(8)
        put16le(body, 0, 0x0100)
        put32le(body, 4, which)
        return request(XXkb.MajorOpcode, XXkb.GetNames, body)
    }

    private fun atomName(socket: Socket, atom: Int): String {
        val body = ByteArray(4)
        put32le(body, 0, atom)
        socket.getOutputStream().write(request(17, 0, body))
        socket.getOutputStream().flush()
        val reply = readReply(socket.getInputStream())
        val length = u16le(reply, 8)
        return reply.copyOfRange(32, 32 + length).decodeToString()
    }

    private fun atomNameBigEndian(socket: Socket, atom: Int): String {
        val body = ByteArray(4)
        put32be(body, 0, atom)
        socket.getOutputStream().write(requestBigEndian(17, 0, body))
        socket.getOutputStream().flush()
        val reply = readReplyBigEndian(socket.getInputStream())
        val length = u16be(reply, 8)
        return reply.copyOfRange(32, 32 + length).decodeToString()
    }

    private fun setNamesRequest(includeAllDetails: Boolean, bodySize: Int = setNamesBodySize(includeAllDetails)): ByteArray {
        val body = ByteArray(bodySize)
        val which = if (includeAllDetails) {
            XXkb.NameDetailKeycodes or
                XXkb.NameDetailGeometry or
                XXkb.NameDetailSymbols or
                XXkb.NameDetailPhysSymbols or
                XXkb.NameDetailTypes or
                XXkb.NameDetailCompat or
                XXkb.NameDetailKeyTypeNames or
                XXkb.NameDetailKtLevelNames or
                XXkb.NameDetailIndicatorNames or
                XXkb.NameDetailKeyNames or
                XXkb.NameDetailKeyAliases or
                XXkb.NameDetailVirtualModNames or
                XXkb.NameDetailGroupNames or
                XXkb.NameDetailRgNames
        } else {
            0
        }
        put16le(body, 0, 0x0100)
        put16le(body, 2, if (includeAllDetails) 0x0003 else 0)
        put32le(body, 4, which)
        if (includeAllDetails) {
            body[8] = 0
            body[9] = 2
            body[10] = 0
            body[11] = 3
            put32le(body, 12, 0x0000_0003)
            body[16] = 0x3
            body[17] = 2
            body[18] = XKeyboard.MinKeycode.toByte()
            body[19] = 2
            body[20] = 1
            put16le(body, 22, 4)
        }

        var offset = 24
        fun write(size: Int) {
            offset += size
        }
        fun align4() {
            offset = (offset + 3) and -4
        }
        if (includeAllDetails) {
            write(24)
            write(8)
            write(3)
            align4()
            write(16)
            write(8)
            write(8)
            write(8)
            write(8)
            write(8)
            write(8)
        }
        return request(XXkb.MajorOpcode, XXkb.SetNames, body)
    }

    private fun setNamesBodySize(includeAllDetails: Boolean): Int =
        if (includeAllDetails) 124 else 24

    private fun getGeometryRequest(name: Int): ByteArray {
        val body = ByteArray(8)
        put16le(body, 0, 0x0100)
        put32le(body, 4, name)
        return request(XXkb.MajorOpcode, XXkb.GetGeometry, body)
    }

    private fun setGeometryRequest(bodySize: Int = setGeometryBodySize()): ByteArray {
        val full = ByteArray(setGeometryBodySize())
        put16le(full, 0, 0x0100)
        full[2] = 1
        full[3] = 0
        put32le(full, 4, 0x0102_0304)
        put16le(full, 8, 320)
        put16le(full, 10, 240)
        put16le(full, 12, 1)
        put16le(full, 14, 2)
        put16le(full, 16, 1)
        put16le(full, 18, 1)
        full[20] = 0
        full[21] = 1

        var offset = 24
        fun writeCounted(value: String) {
            val bytes = value.encodeToByteArray()
            put16le(full, offset, bytes.size)
            bytes.copyInto(full, offset + 2)
            offset += countedGeometryStringSize(value)
        }

        writeCounted("label")
        writeCounted("prop")
        writeCounted("value")
        writeCounted("black")
        writeCounted("white")

        put32le(full, offset, 0x0203_0405)
        full[offset + 4] = 1
        full[offset + 5] = 0
        full[offset + 6] = 0
        offset += 8
        full[offset] = 2
        offset += 4
        put16le(full, offset, 0)
        put16le(full, offset + 2, 0)
        put16le(full, offset + 4, 10)
        put16le(full, offset + 6, 10)
        offset += 8

        put32le(full, offset, 0x0304_0506)
        full[offset + 4] = 3
        full[offset + 16] = 0
        offset += 20
        writeCounted("text")
        writeCounted("font")

        "REALALIS".encodeToByteArray().copyInto(full, offset)
        offset += 8
        assertEquals(full.size, offset)

        val body = if (bodySize == full.size) full else ByteArray(bodySize).also {
            full.copyInto(it, endIndex = minOf(full.size, bodySize))
        }
        return request(XXkb.MajorOpcode, XXkb.SetGeometry, body)
    }

    private fun setGeometryBodySize(): Int =
        24 +
            countedGeometryStringSize("label") +
            countedGeometryStringSize("prop") +
            countedGeometryStringSize("value") +
            countedGeometryStringSize("black") +
            countedGeometryStringSize("white") +
            8 + 4 + 8 +
            20 + countedGeometryStringSize("text") + countedGeometryStringSize("font") +
            8

    private fun countedGeometryStringSize(value: String): Int =
        (2 + value.encodeToByteArray().size + 3) and -4

    private fun perClientFlagsRequest(change: Int, value: Int, ctrlsToChange: Int, autoCtrls: Int, autoCtrlsValues: Int): ByteArray {
        val body = ByteArray(24)
        put16le(body, 0, 0x0100)
        put32le(body, 4, change)
        put32le(body, 8, value)
        put32le(body, 12, ctrlsToChange)
        put32le(body, 16, autoCtrls)
        put32le(body, 20, autoCtrlsValues)
        return request(XXkb.MajorOpcode, XXkb.PerClientFlags, body)
    }

    private fun listComponentsRequest(maxNames: Int, trailingPatterns: ByteArray = ByteArray(0)): ByteArray {
        val body = ByteArray(4 + trailingPatterns.size)
        put16le(body, 0, 0x0100)
        put16le(body, 2, maxNames)
        trailingPatterns.copyInto(body, 4)
        return request(XXkb.MajorOpcode, XXkb.ListComponents, body)
    }

    private fun getKbdByNameRequest(need: Int, want: Int, load: Boolean, trailingNames: ByteArray = ByteArray(0)): ByteArray {
        val body = ByteArray(8 + trailingNames.size)
        put16le(body, 0, 0x0100)
        put16le(body, 2, need)
        put16le(body, 4, want)
        body[6] = if (load) 1 else 0
        trailingNames.copyInto(body, 8)
        return request(XXkb.MajorOpcode, XXkb.GetKbdByName, body)
    }

    private fun xkbComponentSpecs(vararg names: String): ByteArray {
        val specs = names.toList() + List(6 - names.size) { "" }
        val size = (specs.sumOf { 1 + it.encodeToByteArray().size } + 3) and -4
        val bytes = ByteArray(size)
        var offset = 0
        specs.forEach { name ->
            val nameBytes = name.encodeToByteArray()
            bytes[offset++] = nameBytes.size.toByte()
            nameBytes.copyInto(bytes, offset)
            offset += nameBytes.size
        }
        return bytes
    }

    private fun xkbListingsByCategory(reply: ByteArray): List<List<Pair<Int, String>>> {
        val counts = listOf(
            u16le(reply, 8),
            u16le(reply, 10),
            u16le(reply, 12),
            u16le(reply, 14),
            u16le(reply, 16),
            u16le(reply, 18),
        )
        var offset = 32
        return counts.map { count ->
            List(count) {
                val flags = u16le(reply, offset)
                val length = u16le(reply, offset + 2)
                val name = reply.copyOfRange(offset + 4, offset + 4 + length).decodeToString()
                offset += (4 + length + 1) and -2
                flags to name
            }.also {
                offset = paddedSize(offset)
            }
        }
    }

    private fun getDeviceInfoRequest(
        wanted: Int,
        allButtons: Boolean,
        firstButton: Int,
        nButtons: Int,
        ledClass: Int = 0,
        ledId: Int = 0,
        deviceSpec: Int = XXkb.DeviceSpecUseCorePointer,
    ): ByteArray {
        val body = ByteArray(12)
        put16le(body, 0, deviceSpec)
        put16le(body, 2, wanted)
        body[4] = if (allButtons) 1 else 0
        body[5] = firstButton.toByte()
        body[6] = nButtons.toByte()
        put16le(body, 8, ledClass)
        put16le(body, 10, ledId)
        return request(XXkb.MajorOpcode, XXkb.GetDeviceInfo, body)
    }

    private fun setDeviceInfoRequest(
        nButtons: Int,
        nDeviceLedFeedbacks: Int,
        change: Int = XXkb.XiFeatureButtonActions or XXkb.XiFeatureIndicatorNames or XXkb.XiFeatureIndicatorMaps,
        ledNamesPresent: Int = 0x0000_0001,
        ledMapsPresent: Int = 0x0000_0001,
        bodySize: Int = setDeviceInfoBodySize(nButtons, nDeviceLedFeedbacks, change, ledNamesPresent, ledMapsPresent),
        deviceSpec: Int = XXkb.DeviceSpecUseCorePointer,
    ): ByteArray {
        val body = ByteArray(bodySize)
        put16le(body, 0, deviceSpec)
        if (body.size > 2) body[2] = 1
        if (body.size > 3) body[3] = nButtons.toByte()
        if (body.size >= 6) put16le(body, 4, change)
        if (body.size >= 8) put16le(body, 6, nDeviceLedFeedbacks)
        var offset = 8
        if (change and XXkb.XiFeatureButtonActions != 0) {
            repeat(nButtons) {
                if (offset + 8 <= body.size) {
                    body[offset] = 1
                }
                offset += 8
            }
        }
        if (change and XXkb.XiFeatureIndicators != 0) {
            repeat(nDeviceLedFeedbacks) {
                if (offset + 20 <= body.size) {
                    put16le(body, offset, 0x0300)
                    put16le(body, offset + 2, 0x0400)
                    put32le(body, offset + 4, ledNamesPresent)
                    put32le(body, offset + 8, ledMapsPresent)
                }
                offset += 20
                repeat(Integer.bitCount(ledNamesPresent)) { index ->
                    if (offset + 4 <= body.size) put32le(body, offset, 0x40 + index)
                    offset += 4
                }
                repeat(Integer.bitCount(ledMapsPresent)) {
                    if (offset + 12 <= body.size) {
                        body[offset] = 1
                        body[offset + 1] = 1
                        body[offset + 2] = 1
                        body[offset + 3] = 1
                        body[offset + 4] = 1
                        body[offset + 5] = 1
                        put16le(body, offset + 6, 1)
                        put32le(body, offset + 8, XXkb.BoolCtrlRepeatKeys)
                    }
                    offset += 12
                }
            }
        }
        return request(XXkb.MajorOpcode, XXkb.SetDeviceInfo, body)
    }

    private fun setDeviceInfoBodySize(
        nButtons: Int,
        nDeviceLedFeedbacks: Int,
        change: Int,
        ledNamesPresent: Int,
        ledMapsPresent: Int,
    ): Int {
        var size = 8
        if (change and XXkb.XiFeatureButtonActions != 0) size += nButtons * 8
        if (change and XXkb.XiFeatureIndicators != 0) {
            size += nDeviceLedFeedbacks * (20 + Integer.bitCount(ledNamesPresent) * 4 + Integer.bitCount(ledMapsPresent) * 12)
        }
        return size
    }

    private fun setDebuggingFlagsRequest(message: String, affectFlags: Int, flags: Int, affectCtrls: Int, ctrls: Int, extraBytes: Int = 0): ByteArray {
        val messageBytes = message.encodeToByteArray()
        val body = ByteArray(20 + ((messageBytes.size + 3) and -4) + extraBytes)
        put16le(body, 0, messageBytes.size)
        put32le(body, 4, affectFlags)
        put32le(body, 8, flags)
        put32le(body, 12, affectCtrls)
        put32le(body, 16, ctrls)
        messageBytes.copyInto(body, 20)
        return request(XXkb.MajorOpcode, XXkb.SetDebuggingFlags, body)
    }

    private fun changeKeyboardControlRequest(vararg values: Pair<Int, Int>): ByteArray {
        val mask = values.fold(0) { acc, (bit, _) -> acc or bit }
        val body = ByteArray(4 + values.size * 4)
        put32le(body, 0, mask)
        values.forEachIndexed { index, (_, value) ->
            put32le(body, 4 + index * 4, value)
        }
        return request(102, 0, body)
    }

    private fun request(opcode: Int, minorOpcode: Int, body: ByteArray): ByteArray {
        val bytes = ByteArray(4 + body.size)
        bytes[0] = opcode.toByte()
        bytes[1] = minorOpcode.toByte()
        put16le(bytes, 2, bytes.size / 4)
        body.copyInto(bytes, 4)
        return bytes
    }

    private fun requestBigEndian(opcode: Int, minorOpcode: Int, body: ByteArray): ByteArray {
        val bytes = ByteArray(4 + body.size)
        bytes[0] = opcode.toByte()
        bytes[1] = minorOpcode.toByte()
        put16be(bytes, 2, bytes.size / 4)
        body.copyInto(bytes, 4)
        return bytes
    }

    private fun paddedSize(size: Int): Int = (size + 3) and -4

    private fun assertError(input: InputStream, error: Int, opcode: Int, badValue: Int, sequence: Int, minorOpcode: Int) {
        val reply = input.readExactly(32)
        assertEquals(0, reply[0].toInt())
        assertEquals(error, reply[1].toInt() and 0xff)
        assertEquals(sequence, u16le(reply, 2))
        assertEquals(badValue, u32le(reply, 4))
        assertEquals(minorOpcode, u16le(reply, 8))
        assertEquals(opcode, reply[10].toInt() and 0xff)
    }

    private fun assertGetControls(reply: ByteArray, sequence: Int, enabledControls: Int) {
        assertEquals(1, reply[0].toInt())
        assertEquals(0, reply[1].toInt() and 0xff)
        assertEquals(sequence, u16le(reply, 2))
        assertEquals(15, u32le(reply, 4))
        assertEquals(XXkb.DefaultMouseKeysButton, reply[8].toInt() and 0xff)
        assertEquals(XXkb.DefaultGroupCount, reply[9].toInt() and 0xff)
        assertEquals(0, reply[10].toInt() and 0xff)
        assertEquals(0, reply[11].toInt() and 0xff)
        assertEquals(0, reply[12].toInt() and 0xff)
        assertEquals(0, reply[13].toInt() and 0xff)
        assertEquals(0, reply[14].toInt() and 0xff)
        assertEquals(0, reply[15].toInt() and 0xff)
        assertEquals(0, u16le(reply, 16))
        assertEquals(0, u16le(reply, 18))
        assertEquals(XXkb.DefaultRepeatDelay, u16le(reply, 20))
        assertEquals(XXkb.DefaultRepeatInterval, u16le(reply, 22))
        assertEquals(0, u16le(reply, 24))
        assertEquals(0, u16le(reply, 26))
        assertEquals(0, u16le(reply, 28))
        assertEquals(0, u16le(reply, 30))
        assertEquals(0, u16le(reply, 32))
        assertEquals(0, u16le(reply, 34))
        assertEquals(0, u16le(reply, 36))
        assertEquals(0, u16le(reply, 38))
        assertEquals(0, u16le(reply, 40))
        assertEquals(0, u16le(reply, 42))
        assertEquals(0, u16le(reply, 44))
        assertEquals(0, u16le(reply, 46))
        assertEquals(0, u32le(reply, 48))
        assertEquals(0, u32le(reply, 52))
        assertEquals(enabledControls, u32le(reply, 56))
        assertEquals(92, reply.size)
    }

    private fun readReply(input: InputStream): ByteArray {
        val header = input.readExactly(32)
        val payload = input.readExactly(u32le(header, 4) * 4)
        return header + payload
    }

    private fun readReplyBigEndian(input: InputStream): ByteArray {
        val header = input.readExactly(32)
        val payload = input.readExactly(u32be(header, 4) * 4)
        return header + payload
    }

    private fun httpGet(port: Int, path: String): String =
        Socket("127.0.0.1", port).use { socket ->
            socket.getOutputStream().write("GET $path HTTP/1.1\r\nHost: localhost\r\n\r\n".encodeToByteArray())
            socket.getOutputStream().flush()
            socket.getInputStream().readBytes().decodeToString().substringAfter("\r\n\r\n")
        }

    private fun InputStream.readExactly(size: Int): ByteArray {
        val bytes = ByteArray(size)
        var offset = 0
        while (offset < size) {
            val read = read(bytes, offset, size - offset)
            check(read >= 0) { "unexpected end of stream" }
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

    private fun put16be(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value ushr 8).toByte()
        bytes[offset + 1] = value.toByte()
    }

    private fun put32be(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value ushr 24).toByte()
        bytes[offset + 1] = (value ushr 16).toByte()
        bytes[offset + 2] = (value ushr 8).toByte()
        bytes[offset + 3] = value.toByte()
    }

    private fun u16le(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or ((bytes[offset + 1].toInt() and 0xff) shl 8)

    private fun u32le(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or
            ((bytes[offset + 1].toInt() and 0xff) shl 8) or
            ((bytes[offset + 2].toInt() and 0xff) shl 16) or
            ((bytes[offset + 3].toInt() and 0xff) shl 24)

    private fun u16be(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xff) shl 8) or (bytes[offset + 1].toInt() and 0xff)

    private fun u32be(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xff) shl 24) or
            ((bytes[offset + 1].toInt() and 0xff) shl 16) or
            ((bytes[offset + 2].toInt() and 0xff) shl 8) or
            (bytes[offset + 3].toInt() and 0xff)
}
