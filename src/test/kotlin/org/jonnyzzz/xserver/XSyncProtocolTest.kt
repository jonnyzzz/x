package org.jonnyzzz.xserver

import java.io.InputStream
import java.net.Socket
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class XSyncProtocolTest {
    @Test
    fun `SYNC exposes version system counter client counters and priorities`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val counter = X11Ids.ResourceIdBase + 0x101
                val out = socket.getOutputStream()
                out.write(queryExtensionRequest("SYNC"))
                out.write(listExtensionsRequest())
                out.write(request(XSync.MajorOpcode, XSync.Initialize, ByteArray(0)))
                out.write(request(XSync.MajorOpcode, XSync.Initialize, byteArrayOf(99, 99, 0, 0)))
                out.write(request(XSync.MajorOpcode, XSync.ListSystemCounters, u32leBytes(0)))
                out.write(request(XSync.MajorOpcode, XSync.ListSystemCounters, ByteArray(0)))
                out.write(request(XSync.MajorOpcode, XSync.QueryCounter, u32leBytes(XSync.ServerTimeCounter)))
                out.write(syncCounterRequest(XSync.SetCounter, XSync.ServerTimeCounter, 0))
                out.write(request(XSync.MajorOpcode, XSync.CreateCounter, ByteArray(4)))
                out.write(syncCounterRequest(XSync.CreateCounter, counter, 0x0000_0001_0000_0002L))
                out.write(request(XSync.MajorOpcode, XSync.QueryCounter, u32leBytes(counter)))
                out.write(syncCounterRequest(XSync.ChangeCounter, counter, 5))
                out.write(request(XSync.MajorOpcode, XSync.QueryCounter, u32leBytes(counter)))
                out.write(syncCounterRequest(XSync.SetCounter, counter, -7))
                out.write(request(XSync.MajorOpcode, XSync.QueryCounter, u32leBytes(counter)))
                out.write(syncPriorityRequest(XSync.SetPriority, counter, -3))
                out.write(request(XSync.MajorOpcode, XSync.GetPriority, u32leBytes(counter)))
                out.write(request(XSync.MajorOpcode, XSync.DestroyCounter, u32leBytes(counter)))
                out.write(request(XSync.MajorOpcode, XSync.QueryCounter, u32leBytes(counter)))
                out.write(request(XSync.MajorOpcode, 99, ByteArray(0)))
                out.write(request(XSync.MajorOpcode, XSync.Initialize, byteArrayOf(3, 1, 0, 0)))
                out.flush()

                val extension = readReply(socket.getInputStream())
                assertEquals(1, extension[8].toInt() and 0xff)
                assertEquals(XSync.MajorOpcode, extension[9].toInt() and 0xff)
                assertEquals(XSync.FirstEvent, extension[10].toInt() and 0xff)
                assertEquals(XSync.FirstError, extension[11].toInt() and 0xff)

                assertContains(extensionNames(readReply(socket.getInputStream())), "SYNC")

                assertError(socket.getInputStream(), error = 16, badValue = 0, sequence = 3, minorOpcode = XSync.Initialize)

                val version = readReply(socket.getInputStream())
                assertEquals(4, u16le(version, 2))
                assertEquals(0, u32le(version, 4))
                assertEquals(XSync.MajorVersion, version[8].toInt() and 0xff)
                assertEquals(XSync.MinorVersion, version[9].toInt() and 0xff)

                assertError(socket.getInputStream(), error = 16, badValue = 0, sequence = 5, minorOpcode = XSync.ListSystemCounters)

                val counters = readReply(socket.getInputStream())
                assertEquals(6, u16le(counters, 2))
                assertEquals(6, u32le(counters, 4))
                assertEquals(1, u32le(counters, 8))
                assertEquals(XSync.ServerTimeCounter, u32le(counters, 32))
                assertEquals(0, u32le(counters, 36))
                assertEquals(1, u32le(counters, 40))
                assertEquals(XSync.ServerTimeName.length, u16le(counters, 44))
                assertEquals(XSync.ServerTimeName, counters.copyOfRange(46, 46 + XSync.ServerTimeName.length).decodeToString())

                val serverTime = readReply(socket.getInputStream())
                assertEquals(7, u16le(serverTime, 2))
                assertTrue(syncValue(serverTime, 8) >= 0)

                assertError(socket.getInputStream(), error = 10, badValue = XSync.ServerTimeCounter, sequence = 8, minorOpcode = XSync.SetCounter)
                assertError(socket.getInputStream(), error = 16, badValue = 0, sequence = 9, minorOpcode = XSync.CreateCounter)

                val initial = readReply(socket.getInputStream())
                assertEquals(11, u16le(initial, 2))
                assertEquals(0x0000_0001_0000_0002L, syncValue(initial, 8))

                val changed = readReply(socket.getInputStream())
                assertEquals(13, u16le(changed, 2))
                assertEquals(0x0000_0001_0000_0007L, syncValue(changed, 8))

                val set = readReply(socket.getInputStream())
                assertEquals(15, u16le(set, 2))
                assertEquals(-7, syncValue(set, 8))

                val priority = readReply(socket.getInputStream())
                assertEquals(17, u16le(priority, 2))
                assertEquals(-3, u32le(priority, 8))

                assertError(socket.getInputStream(), error = XSync.BadCounter, badValue = counter, sequence = 19, minorOpcode = XSync.QueryCounter)
                assertError(socket.getInputStream(), error = 1, badValue = XSync.MajorOpcode, sequence = 20, minorOpcode = 99)

                val recovered = readReply(socket.getInputStream())
                assertEquals(21, u16le(recovered, 2))
                assertEquals(XSync.MajorVersion, recovered[8].toInt() and 0xff)
                assertEquals(XSync.MinorVersion, recovered[9].toInt() and 0xff)
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `SYNC validates fence lifecycle`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val fence = X11Ids.ResourceIdBase + 0x202
                val out = socket.getOutputStream()
                out.write(request(XSync.MajorOpcode, XSync.CreateFence, ByteArray(8)))
                out.write(createFenceRequest(fence, drawable = 0x0102_0304, initiallyTriggered = 0))
                out.write(createFenceRequest(fence, drawable = X11Ids.RootWindow, initiallyTriggered = 2))
                out.write(createFenceRequest(fence, drawable = X11Ids.RootWindow, initiallyTriggered = 0))
                out.write(request(XSync.MajorOpcode, XSync.QueryFence, u32leBytes(fence)))
                out.write(request(XSync.MajorOpcode, XSync.ResetFence, u32leBytes(fence)))
                out.write(request(XSync.MajorOpcode, XSync.QueryFence, u32leBytes(fence)))
                out.write(request(XSync.MajorOpcode, XSync.TriggerFence, u32leBytes(fence)))
                out.write(request(XSync.MajorOpcode, XSync.ResetFence, u32leBytes(fence)))
                out.write(request(XSync.MajorOpcode, XSync.QueryFence, u32leBytes(fence)))
                out.write(request(XSync.MajorOpcode, XSync.DestroyFence, u32leBytes(fence)))
                out.write(request(XSync.MajorOpcode, XSync.QueryFence, u32leBytes(fence)))
                out.write(request(XSync.MajorOpcode, XSync.Initialize, byteArrayOf(3, 1, 0, 0)))
                out.flush()

                assertError(socket.getInputStream(), error = 16, badValue = 0, sequence = 1, minorOpcode = XSync.CreateFence)
                assertError(socket.getInputStream(), error = 9, badValue = 0x0102_0304, sequence = 2, minorOpcode = XSync.CreateFence)
                assertError(socket.getInputStream(), error = 2, badValue = 2, sequence = 3, minorOpcode = XSync.CreateFence)

                val untriggered = readReply(socket.getInputStream())
                assertEquals(5, u16le(untriggered, 2))
                assertEquals(0, untriggered[8].toInt() and 0xff)

                assertError(socket.getInputStream(), error = 8, badValue = fence, sequence = 6, minorOpcode = XSync.ResetFence)

                val stillUntriggered = readReply(socket.getInputStream())
                assertEquals(7, u16le(stillUntriggered, 2))
                assertEquals(0, stillUntriggered[8].toInt() and 0xff)

                val reset = readReply(socket.getInputStream())
                assertEquals(10, u16le(reset, 2))
                assertEquals(0, reset[8].toInt() and 0xff)

                assertError(socket.getInputStream(), error = XSync.BadFence, badValue = fence, sequence = 12, minorOpcode = XSync.QueryFence)

                val recovered = readReply(socket.getInputStream())
                assertEquals(13, u16le(recovered, 2))
                assertEquals(XSync.MajorVersion, recovered[8].toInt() and 0xff)
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `SYNC AwaitFence blocks only the waiting client until another client triggers`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { owner ->
                Socket("127.0.0.1", server.localPort).use { trigger ->
                    owner.soTimeout = 500
                    trigger.soTimeout = 2_000
                    setup(owner)
                    setup(trigger)
                    val fence = X11Ids.ResourceIdBase + 0x222
                    owner.getOutputStream().apply {
                        write(createFenceRequest(fence, drawable = X11Ids.RootWindow, initiallyTriggered = 0))
                        write(request(XSync.MajorOpcode, XSync.AwaitFence, u32leBytes(fence)))
                        write(request(XSync.MajorOpcode, XSync.QueryFence, u32leBytes(fence)))
                        flush()
                    }
                    assertTrue(runCatching { owner.getInputStream().readExactly(1) }.isFailure)

                    trigger.getOutputStream().apply {
                        write(request(XSync.MajorOpcode, XSync.TriggerFence, u32leBytes(fence)))
                        flush()
                    }

                    owner.soTimeout = 2_000
                    val queried = readReply(owner.getInputStream())
                    assertEquals(3, u16le(queried, 2))
                    assertEquals(1, queried[8].toInt() and 0xff)
                }
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `SYNC Await blocks counter client until another client satisfies condition`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { owner ->
                Socket("127.0.0.1", server.localPort).use { changer ->
                    owner.soTimeout = 500
                    changer.soTimeout = 2_000
                    setup(owner)
                    setup(changer)
                    val counter = X11Ids.ResourceIdBase + 0x333
                    owner.getOutputStream().apply {
                        write(syncCounterRequest(XSync.CreateCounter, counter, 0))
                        write(syncAwaitRequest(counter, XSync.Absolute, 5, XSync.PositiveComparison, 0))
                        write(request(XSync.MajorOpcode, XSync.QueryCounter, u32leBytes(counter)))
                        flush()
                    }
                    assertTrue(runCatching { owner.getInputStream().readExactly(1) }.isFailure)

                    changer.getOutputStream().apply {
                        write(syncCounterRequest(XSync.ChangeCounter, counter, 5))
                        flush()
                    }

                    owner.soTimeout = 2_000
                    val event = owner.getInputStream().readExactly(32)
                    assertEquals(XSync.FirstEvent, event[0].toInt() and 0xff)
                    assertEquals(0, event[1].toInt() and 0xff)
                    assertEquals(counter, u32le(event, 4))
                    assertEquals(5, syncValue(event, 8))
                    assertEquals(5, syncValue(event, 16))

                    val queried = readReply(owner.getInputStream())
                    assertEquals(3, u16le(queried, 2))
                    assertEquals(5, syncValue(queried, 8))
                }
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `SYNC Await emits CounterNotify when condition is already satisfied`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val counter = X11Ids.ResourceIdBase + 0x334
                socket.getOutputStream().apply {
                    write(syncCounterRequest(XSync.CreateCounter, counter, 10))
                    write(syncAwaitRequest(counter, XSync.Absolute, 5, XSync.PositiveComparison, 0))
                    write(request(XSync.MajorOpcode, XSync.QueryCounter, u32leBytes(counter)))
                    flush()
                }

                val event = socket.getInputStream().readExactly(32)
                assertEquals(XSync.FirstEvent, event[0].toInt() and 0xff)
                assertEquals(0, event[1].toInt() and 0xff)
                assertEquals(2, u16le(event, 2))
                assertEquals(counter, u32le(event, 4))
                assertEquals(5, syncValue(event, 8))
                assertEquals(10, syncValue(event, 16))

                val queried = readReply(socket.getInputStream())
                assertEquals(3, u16le(queried, 2))
                assertEquals(10, syncValue(queried, 8))
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `SYNC Await on SERVERTIME resumes as time advances`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val out = socket.getOutputStream()
                out.write(request(XSync.MajorOpcode, XSync.QueryCounter, u32leBytes(XSync.ServerTimeCounter)))
                out.flush()
                val now = syncValue(readReply(socket.getInputStream()), 8)

                out.write(syncAwaitRequest(XSync.ServerTimeCounter, XSync.Absolute, now + 30, XSync.PositiveComparison, 0))
                out.write(request(XSync.MajorOpcode, XSync.QueryCounter, u32leBytes(XSync.ServerTimeCounter)))
                out.flush()

                val event = socket.getInputStream().readExactly(32)
                assertEquals(XSync.FirstEvent, event[0].toInt() and 0xff)
                assertEquals(0, event[1].toInt() and 0xff)
                assertEquals(XSync.ServerTimeCounter, u32le(event, 4))

                val later = readReply(socket.getInputStream())
                assertEquals(3, u16le(later, 2))
                assertTrue(syncValue(later, 8) >= now + 30)
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `SYNC positive transition await on SERVERTIME resumes when time crosses target`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val out = socket.getOutputStream()
                out.write(request(XSync.MajorOpcode, XSync.QueryCounter, u32leBytes(XSync.ServerTimeCounter)))
                out.flush()
                val now = syncValue(readReply(socket.getInputStream()), 8)

                out.write(syncAwaitRequest(XSync.ServerTimeCounter, XSync.Absolute, now + 30, XSync.PositiveTransition, 0))
                out.write(request(XSync.MajorOpcode, XSync.QueryCounter, u32leBytes(XSync.ServerTimeCounter)))
                out.flush()

                val event = socket.getInputStream().readExactly(32)
                assertEquals(XSync.FirstEvent, event[0].toInt() and 0xff)
                assertEquals(0, event[1].toInt() and 0xff)
                assertEquals(XSync.ServerTimeCounter, u32le(event, 4))
                assertEquals(now + 30, syncValue(event, 8))
                assertTrue(syncValue(event, 16) >= now + 30)

                val later = readReply(socket.getInputStream())
                assertEquals(3, u16le(later, 2))
                assertTrue(syncValue(later, 8) >= now + 30)
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `SYNC positive transition await waits for a later crossing`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { owner ->
                Socket("127.0.0.1", server.localPort).use { changer ->
                    owner.soTimeout = 500
                    changer.soTimeout = 2_000
                    setup(owner)
                    setup(changer)
                    val counter = X11Ids.ResourceIdBase + 0x444
                    owner.getOutputStream().apply {
                        write(syncCounterRequest(XSync.CreateCounter, counter, 10))
                        write(syncAwaitRequest(counter, XSync.Absolute, 5, XSync.PositiveTransition, 0))
                        write(request(XSync.MajorOpcode, XSync.QueryCounter, u32leBytes(counter)))
                        flush()
                    }
                    assertTrue(runCatching { owner.getInputStream().readExactly(1) }.isFailure)

                    changer.getOutputStream().apply {
                        write(syncCounterRequest(XSync.SetCounter, counter, 11))
                        flush()
                    }
                    assertTrue(runCatching { owner.getInputStream().readExactly(1) }.isFailure)

                    changer.getOutputStream().apply {
                        write(syncCounterRequest(XSync.SetCounter, counter, 0))
                        flush()
                    }
                    assertTrue(runCatching { owner.getInputStream().readExactly(1) }.isFailure)

                    changer.getOutputStream().apply {
                        write(syncCounterRequest(XSync.SetCounter, counter, 5))
                        flush()
                    }

                    owner.soTimeout = 2_000
                    val event = owner.getInputStream().readExactly(32)
                    assertEquals(XSync.FirstEvent, event[0].toInt() and 0xff)
                    assertEquals(counter, u32le(event, 4))
                    assertEquals(5, syncValue(event, 8))
                    assertEquals(5, syncValue(event, 16))

                    val queried = readReply(owner.getInputStream())
                    assertEquals(3, u16le(queried, 2))
                    assertEquals(5, syncValue(queried, 8))
                }
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `SYNC positive transition await latches a crossing before later counter changes`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { owner ->
                Socket("127.0.0.1", server.localPort).use { changer ->
                    owner.soTimeout = 500
                    changer.soTimeout = 2_000
                    setup(owner)
                    setup(changer)
                    val counter = X11Ids.ResourceIdBase + 0x445
                    owner.getOutputStream().apply {
                        write(syncCounterRequest(XSync.CreateCounter, counter, 0))
                        write(syncAwaitRequest(counter, XSync.Absolute, 5, XSync.PositiveTransition, 0))
                        write(request(XSync.MajorOpcode, XSync.QueryCounter, u32leBytes(counter)))
                        flush()
                    }
                    assertTrue(runCatching { owner.getInputStream().readExactly(1) }.isFailure)

                    changer.getOutputStream().apply {
                        write(syncCounterRequest(XSync.SetCounter, counter, 5))
                        write(syncCounterRequest(XSync.SetCounter, counter, 0))
                        flush()
                    }

                    owner.soTimeout = 2_000
                    val event = owner.getInputStream().readExactly(32)
                    assertEquals(XSync.FirstEvent, event[0].toInt() and 0xff)
                    assertEquals(counter, u32le(event, 4))
                    assertEquals(5, syncValue(event, 8))
                    assertEquals(5, syncValue(event, 16))

                    val queried = readReply(owner.getInputStream())
                    assertEquals(3, u16le(queried, 2))
                    assertTrue(syncValue(queried, 8) == 0L || syncValue(queried, 8) == 5L)
                }
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `SYNC alarm requests store attributes and validate alarm ids`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val counter = X11Ids.ResourceIdBase + 0x404
                val alarm = X11Ids.ResourceIdBase + 0x405
                val out = socket.getOutputStream()
                out.write(syncCounterRequest(XSync.CreateCounter, counter, 10))
                out.write(createAlarmRequest(alarm, counter, XSync.Absolute, 12, XSync.PositiveComparison, 2, events = true))
                out.write(request(XSync.MajorOpcode, XSync.QueryAlarm, u32leBytes(alarm)))
                out.write(syncCounterRequest(XSync.ChangeCounter, counter, 2))
                out.write(changeAlarmEventsRequest(alarm, events = false))
                out.write(request(XSync.MajorOpcode, XSync.QueryAlarm, u32leBytes(alarm)))
                out.write(request(XSync.MajorOpcode, XSync.DestroyAlarm, u32leBytes(alarm)))
                out.write(request(XSync.MajorOpcode, XSync.QueryAlarm, u32leBytes(alarm)))
                out.write(request(XSync.MajorOpcode, XSync.Initialize, byteArrayOf(3, 1, 0, 0)))
                out.flush()

                val initial = readReply(socket.getInputStream())
                assertEquals(3, u16le(initial, 2))
                assertEquals(2, u32le(initial, 4))
                assertEquals(counter, u32le(initial, 8))
                assertEquals(XSync.Absolute, u32le(initial, 12))
                assertEquals(12, syncValue(initial, 16))
                assertEquals(XSync.PositiveComparison, u32le(initial, 24))
                assertEquals(2, syncValue(initial, 28))
                assertEquals(1, initial[36].toInt() and 0xff)
                assertEquals(XSync.AlarmActive, initial[37].toInt() and 0xff)

                val alarmEvent = socket.getInputStream().readExactly(32)
                assertEquals(XSync.FirstEvent + 1, alarmEvent[0].toInt() and 0xff)
                assertEquals(1, alarmEvent[1].toInt() and 0xff)
                assertEquals(alarm, u32le(alarmEvent, 4))
                assertEquals(12, syncValue(alarmEvent, 8))
                assertEquals(12, syncValue(alarmEvent, 16))
                assertEquals(XSync.AlarmActive, alarmEvent[28].toInt() and 0xff)

                val changed = readReply(socket.getInputStream())
                assertEquals(6, u16le(changed, 2))
                assertEquals(0, changed[36].toInt() and 0xff)

                assertError(socket.getInputStream(), error = XSync.BadAlarm, badValue = alarm, sequence = 8, minorOpcode = XSync.QueryAlarm)

                val recovered = readReply(socket.getInputStream())
                assertEquals(9, u16le(recovered, 2))
                assertEquals(XSync.MajorVersion, recovered[8].toInt() and 0xff)
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `SYNC DestroyAlarm emits AlarmNotify when events are enabled`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val counter = X11Ids.ResourceIdBase + 0x406
                val alarm = X11Ids.ResourceIdBase + 0x407
                socket.getOutputStream().apply {
                    write(syncCounterRequest(XSync.CreateCounter, counter, 10))
                    write(createAlarmRequest(alarm, counter, XSync.Absolute, 20, XSync.PositiveComparison, 2, events = true))
                    write(request(XSync.MajorOpcode, XSync.DestroyAlarm, u32leBytes(alarm)))
                    write(request(XSync.MajorOpcode, XSync.QueryAlarm, u32leBytes(alarm)))
                    flush()
                }

                val destroyedEvent = socket.getInputStream().readExactly(32)
                assertEquals(XSync.FirstEvent + 1, destroyedEvent[0].toInt() and 0xff)
                assertEquals(1, destroyedEvent[1].toInt() and 0xff)
                assertEquals(3, u16le(destroyedEvent, 2))
                assertEquals(alarm, u32le(destroyedEvent, 4))
                assertEquals(10, syncValue(destroyedEvent, 8))
                assertEquals(20, syncValue(destroyedEvent, 16))
                assertEquals(XSync.AlarmDestroyed, destroyedEvent[28].toInt() and 0xff)

                assertError(socket.getInputStream(), error = XSync.BadAlarm, badValue = alarm, sequence = 4, minorOpcode = XSync.QueryAlarm)
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `SYNC implicit counter cleanup emits AlarmNotify when alarm owner survives`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { counterOwner ->
                Socket("127.0.0.1", server.localPort).use { alarmOwner ->
                    counterOwner.soTimeout = 2_000
                    alarmOwner.soTimeout = 2_000
                    setup(counterOwner)
                    setup(alarmOwner)
                    val counter = X11Ids.ResourceIdBase + 0x40a
                    val alarm = X11Ids.ResourceIdBase + 0x40b

                    counterOwner.getOutputStream().apply {
                        write(syncCounterRequest(XSync.CreateCounter, counter, 10))
                        write(request(XSync.MajorOpcode, XSync.QueryCounter, u32leBytes(counter)))
                        flush()
                    }
                    val counterReply = readReply(counterOwner.getInputStream())
                    assertEquals(2, u16le(counterReply, 2))
                    assertEquals(10, syncValue(counterReply, 8))

                    alarmOwner.getOutputStream().apply {
                        write(createAlarmRequest(alarm, counter, XSync.Absolute, 20, XSync.PositiveComparison, 2, events = true))
                        write(request(XSync.MajorOpcode, XSync.QueryAlarm, u32leBytes(alarm)))
                        flush()
                    }
                    val initialAlarm = readReply(alarmOwner.getInputStream())
                    assertEquals(2, u16le(initialAlarm, 2))
                    assertEquals(counter, u32le(initialAlarm, 8))
                    assertEquals(XSync.AlarmActive, initialAlarm[37].toInt() and 0xff)

                    counterOwner.close()
                    val inactiveEvent = alarmOwner.getInputStream().readExactly(32)
                    assertEquals(XSync.FirstEvent + 1, inactiveEvent[0].toInt() and 0xff)
                    assertEquals(1, inactiveEvent[1].toInt() and 0xff)
                    assertEquals(2, u16le(inactiveEvent, 2))
                    assertEquals(alarm, u32le(inactiveEvent, 4))
                    assertEquals(0, syncValue(inactiveEvent, 8))
                    assertEquals(20, syncValue(inactiveEvent, 16))
                    assertEquals(XSync.AlarmInactive, inactiveEvent[28].toInt() and 0xff)

                    alarmOwner.getOutputStream().apply {
                        write(request(XSync.MajorOpcode, XSync.QueryAlarm, u32leBytes(alarm)))
                        flush()
                    }
                    val inactiveAlarm = readReply(alarmOwner.getInputStream())
                    assertEquals(3, u16le(inactiveAlarm, 2))
                    assertEquals(0, u32le(inactiveAlarm, 8))
                    assertEquals(XSync.AlarmInactive, inactiveAlarm[37].toInt() and 0xff)
                }
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `SYNC alarm delta advances past large counter jumps without deactivating`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val counter = X11Ids.ResourceIdBase + 0x408
                val alarm = X11Ids.ResourceIdBase + 0x409
                socket.getOutputStream().apply {
                    write(syncCounterRequest(XSync.CreateCounter, counter, 0))
                    write(createAlarmRequest(alarm, counter, XSync.Absolute, 1, XSync.PositiveComparison, 1, events = true))
                    write(syncCounterRequest(XSync.ChangeCounter, counter, 2_000))
                    write(request(XSync.MajorOpcode, XSync.QueryAlarm, u32leBytes(alarm)))
                    flush()
                }

                val alarmEvent = socket.getInputStream().readExactly(32)
                assertEquals(XSync.FirstEvent + 1, alarmEvent[0].toInt() and 0xff)
                assertEquals(1, alarmEvent[1].toInt() and 0xff)
                assertEquals(3, u16le(alarmEvent, 2))
                assertEquals(alarm, u32le(alarmEvent, 4))
                assertEquals(2_000, syncValue(alarmEvent, 8))
                assertEquals(1, syncValue(alarmEvent, 16))
                assertEquals(XSync.AlarmActive, alarmEvent[28].toInt() and 0xff)

                val queried = readReply(socket.getInputStream())
                assertEquals(4, u16le(queried, 2))
                assertEquals(2, u32le(queried, 4))
                assertEquals(counter, u32le(queried, 8))
                assertEquals(2_001, syncValue(queried, 16))
                assertEquals(XSync.AlarmActive, queried[37].toInt() and 0xff)
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `SYNC transition alarm delta advances past large counter jumps`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket)
                val counter = X11Ids.ResourceIdBase + 0x40a
                val alarm = X11Ids.ResourceIdBase + 0x40b
                socket.getOutputStream().apply {
                    write(syncCounterRequest(XSync.CreateCounter, counter, 0))
                    write(createAlarmRequest(alarm, counter, XSync.Absolute, 10, XSync.PositiveTransition, 10, events = true))
                    write(syncCounterRequest(XSync.ChangeCounter, counter, 25))
                    write(request(XSync.MajorOpcode, XSync.QueryAlarm, u32leBytes(alarm)))
                    flush()
                }

                val alarmEvent = socket.getInputStream().readExactly(32)
                assertEquals(XSync.FirstEvent + 1, alarmEvent[0].toInt() and 0xff)
                assertEquals(1, alarmEvent[1].toInt() and 0xff)
                assertEquals(3, u16le(alarmEvent, 2))
                assertEquals(alarm, u32le(alarmEvent, 4))
                assertEquals(25, syncValue(alarmEvent, 8))
                assertEquals(10, syncValue(alarmEvent, 16))
                assertEquals(XSync.AlarmActive, alarmEvent[28].toInt() and 0xff)

                val queried = readReply(socket.getInputStream())
                assertEquals(4, u16le(queried, 2))
                assertEquals(2, u32le(queried, 4))
                assertEquals(counter, u32le(queried, 8))
                assertEquals(30, syncValue(queried, 16))
                assertEquals(XSync.AlarmActive, queried[37].toInt() and 0xff)
            }
            server.close()
            serverThread.join(1_000)
        }
    }

    @Test
    fun `SYNC swaps replies for big endian clients`() {
        XServer(ServerOptions(port = 0, width = 120, height = 90)).use { server ->
            val serverThread = thread(start = true, isDaemon = true) { server.serveForever() }
            Socket("127.0.0.1", server.localPort).use { socket ->
                socket.soTimeout = 2_000
                setup(socket, byteOrderByte = 0x42)
                val counter = X11Ids.ResourceIdBase + 0x303
                val out = socket.getOutputStream()
                out.write(requestBe(XSync.MajorOpcode, XSync.Initialize, byteArrayOf(3, 1, 0, 0)))
                out.write(syncCounterRequestBe(XSync.CreateCounter, counter, 0x0000_0001_0000_0002L))
                out.write(requestBe(XSync.MajorOpcode, XSync.QueryCounter, u32beBytes(counter)))
                out.write(requestBe(XSync.MajorOpcode, XSync.ListSystemCounters, ByteArray(0)))
                out.flush()

                val version = readReply(socket.getInputStream(), byteOrderByte = 0x42)
                assertEquals(1, u16be(version, 2))
                assertEquals(0, u32be(version, 4))
                assertEquals(XSync.MajorVersion, version[8].toInt() and 0xff)
                assertEquals(XSync.MinorVersion, version[9].toInt() and 0xff)

                val queried = readReply(socket.getInputStream(), byteOrderByte = 0x42)
                assertEquals(3, u16be(queried, 2))
                assertEquals(0x0000_0001_0000_0002L, syncValueBe(queried, 8))

                val counters = readReply(socket.getInputStream(), byteOrderByte = 0x42)
                assertEquals(4, u16be(counters, 2))
                assertEquals(6, u32be(counters, 4))
                assertEquals(1, u32be(counters, 8))
                assertEquals(XSync.ServerTimeCounter, u32be(counters, 32))
                assertEquals(1, u32be(counters, 40))
                assertEquals(XSync.ServerTimeName.length, u16be(counters, 44))
            }
            server.close()
            serverThread.join(1_000)
        }
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
        val payloadUnits = if (byteOrderByte == 0x42) u16be(prefix, 6) else u16le(prefix, 6)
        socket.getInputStream().readExactly(payloadUnits * 4)
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

    private fun syncCounterRequest(minorOpcode: Int, counter: Int, value: Long): ByteArray {
        val body = ByteArray(12)
        put32le(body, 0, counter)
        putSyncValue(body, 4, value)
        return request(XSync.MajorOpcode, minorOpcode, body)
    }

    private fun syncCounterRequestBe(minorOpcode: Int, counter: Int, value: Long): ByteArray {
        val body = ByteArray(12)
        put32be(body, 0, counter)
        putSyncValueBe(body, 4, value)
        return requestBe(XSync.MajorOpcode, minorOpcode, body)
    }

    private fun syncPriorityRequest(minorOpcode: Int, id: Int, priority: Int): ByteArray {
        val body = ByteArray(8)
        put32le(body, 0, id)
        put32le(body, 4, priority)
        return request(XSync.MajorOpcode, minorOpcode, body)
    }

    private fun syncAwaitRequest(counter: Int, valueType: Int, waitValue: Long, testType: Int, eventThreshold: Long): ByteArray {
        val body = ByteArray(28)
        put32le(body, 0, counter)
        put32le(body, 4, valueType)
        putSyncValue(body, 8, waitValue)
        put32le(body, 16, testType)
        putSyncValue(body, 20, eventThreshold)
        return request(XSync.MajorOpcode, XSync.Await, body)
    }

    private fun createAlarmRequest(
        alarm: Int,
        counter: Int,
        valueType: Int,
        waitValue: Long,
        testType: Int,
        delta: Long,
        events: Boolean,
    ): ByteArray {
        val valueMask = XSync.CACounter or XSync.CAValueType or XSync.CAValue or XSync.CATestType or XSync.CADelta or XSync.CAEvents
        val body = ByteArray(40)
        put32le(body, 0, alarm)
        put32le(body, 4, valueMask)
        put32le(body, 8, counter)
        put32le(body, 12, valueType)
        putSyncValue(body, 16, waitValue)
        put32le(body, 24, testType)
        putSyncValue(body, 28, delta)
        put32le(body, 36, if (events) 1 else 0)
        return request(XSync.MajorOpcode, XSync.CreateAlarm, body)
    }

    private fun changeAlarmEventsRequest(alarm: Int, events: Boolean): ByteArray {
        val body = ByteArray(12)
        put32le(body, 0, alarm)
        put32le(body, 4, XSync.CAEvents)
        put32le(body, 8, if (events) 1 else 0)
        return request(XSync.MajorOpcode, XSync.ChangeAlarm, body)
    }

    private fun createFenceRequest(fence: Int, drawable: Int, initiallyTriggered: Int): ByteArray {
        val body = ByteArray(12)
        put32le(body, 0, drawable)
        put32le(body, 4, fence)
        body[8] = initiallyTriggered.toByte()
        return request(XSync.MajorOpcode, XSync.CreateFence, body)
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

    private fun readReply(input: InputStream, byteOrderByte: Int = 0x6c): ByteArray {
        val header = input.readExactly(32)
        if (header[0].toInt() != 1) return header
        val extra = when (byteOrderByte) {
            0x42 -> u32be(header, 4)
            else -> u32le(header, 4)
        } * 4
        return header + input.readExactly(extra)
    }

    private fun assertError(input: InputStream, error: Int, badValue: Int, sequence: Int, minorOpcode: Int) {
        val reply = input.readExactly(32)
        assertEquals(0, reply[0].toInt())
        assertEquals(error, reply[1].toInt() and 0xff)
        assertEquals(sequence, u16le(reply, 2))
        assertEquals(badValue, u32le(reply, 4))
        assertEquals(minorOpcode, u16le(reply, 8))
        assertEquals(XSync.MajorOpcode, reply[10].toInt() and 0xff)
    }

    private fun extensionNames(reply: ByteArray): List<String> {
        val names = mutableListOf<String>()
        var offset = 32
        repeat(reply[1].toInt() and 0xff) {
            val length = reply[offset++].toInt() and 0xff
            names += reply.copyOfRange(offset, offset + length).decodeToString()
            offset += length
        }
        return names
    }

    private fun u32leBytes(value: Int): ByteArray =
        ByteArray(4).also { put32le(it, 0, value) }

    private fun u32beBytes(value: Int): ByteArray =
        ByteArray(4).also { put32be(it, 0, value) }

    private fun syncValue(bytes: ByteArray, offset: Int): Long =
        (u32le(bytes, offset).toLong() shl 32) or (u32le(bytes, offset + 4).toLong() and 0xffff_ffffL)

    private fun syncValueBe(bytes: ByteArray, offset: Int): Long =
        (u32be(bytes, offset).toLong() shl 32) or (u32be(bytes, offset + 4).toLong() and 0xffff_ffffL)

    private fun putSyncValue(bytes: ByteArray, offset: Int, value: Long) {
        put32le(bytes, offset, (value shr 32).toInt())
        put32le(bytes, offset + 4, value.toInt())
    }

    private fun putSyncValueBe(bytes: ByteArray, offset: Int, value: Long) {
        put32be(bytes, offset, (value shr 32).toInt())
        put32be(bytes, offset + 4, value.toInt())
    }

    private fun u16le(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or ((bytes[offset + 1].toInt() and 0xff) shl 8)

    private fun u16be(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xff) shl 8) or (bytes[offset + 1].toInt() and 0xff)

    private fun u32le(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or
            ((bytes[offset + 1].toInt() and 0xff) shl 8) or
            ((bytes[offset + 2].toInt() and 0xff) shl 16) or
            ((bytes[offset + 3].toInt() and 0xff) shl 24)

    private fun u32be(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xff) shl 24) or
            ((bytes[offset + 1].toInt() and 0xff) shl 16) or
            ((bytes[offset + 2].toInt() and 0xff) shl 8) or
            (bytes[offset + 3].toInt() and 0xff)

    private fun put16le(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = value.toByte()
        bytes[offset + 1] = (value ushr 8).toByte()
    }

    private fun put16be(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value ushr 8).toByte()
        bytes[offset + 1] = value.toByte()
    }

    private fun put32le(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = value.toByte()
        bytes[offset + 1] = (value ushr 8).toByte()
        bytes[offset + 2] = (value ushr 16).toByte()
        bytes[offset + 3] = (value ushr 24).toByte()
    }

    private fun put32be(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value ushr 24).toByte()
        bytes[offset + 1] = (value ushr 16).toByte()
        bytes[offset + 2] = (value ushr 8).toByte()
        bytes[offset + 3] = value.toByte()
    }

    private fun InputStream.readExactly(size: Int): ByteArray {
        val bytes = ByteArray(size)
        var offset = 0
        while (offset < size) {
            val read = read(bytes, offset, size - offset)
            if (read < 0) error("EOF after $offset of $size bytes")
            offset += read
        }
        return bytes
    }
}
