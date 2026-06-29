package org.jonnyzzz.xserver

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.floor
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.roundToInt
import kotlin.math.sqrt

internal data class XWindowRemoval(
    val removedResources: Set<Int>,
    val destroyNotifyDispatches: List<XDestroyNotifyDispatch>,
    val xfixesSelectionNotifyDispatches: List<XXFixesSelectionNotifyDispatch> = emptyList(),
    val xfixesCursorNotifyDispatches: List<XXFixesCursorNotifyDispatch> = emptyList(),
)

internal data class XResourceRemoval(
    val destroyNotifyDispatches: List<XDestroyNotifyDispatch>,
    val xfixesSelectionNotifyDispatches: List<XXFixesSelectionNotifyDispatch>,
    val xfixesCursorNotifyDispatches: List<XXFixesCursorNotifyDispatch> = emptyList(),
)

internal data class XEventSinkRemoval(
    val xfixesSelectionNotifyDispatches: List<XXFixesSelectionNotifyDispatch>,
    val xfixesCursorNotifyDispatches: List<XXFixesCursorNotifyDispatch> = emptyList(),
)

internal data class XCursorIdentity(
    val id: Int,
    val generation: Long,
)

internal data class XCursorName(
    val atom: Int,
    val name: String,
)

internal data class XClientResourceIdRange(
    val startId: Int,
    val count: Int,
)

internal class X11State(
    val width: Int,
    val height: Int,
    val dpi: Int = 96,
) {
    var widthMillimeters: Int = pixelsToMillimeters(width, dpi)
        private set
    var heightMillimeters: Int = pixelsToMillimeters(height, dpi)
        private set
    private val windows = linkedMapOf<Int, XWindow>()
    private val pixmaps = linkedMapOf<Int, XPixmap>()
    private val gcs = linkedMapOf<Int, XGraphicsContext>()
    private val fonts = linkedSetOf<Int>()
    private val cursors = linkedMapOf<Int, XCursor>()
    private var nextCursorGeneration: Long = 1
    private val colormaps = linkedSetOf(X11Ids.DefaultColormap)
    private val installedColormaps = linkedSetOf(X11Ids.DefaultColormap)
    private val pictures = linkedMapOf<Int, XPicture>()
    private val glyphSets = linkedMapOf<Int, XGlyphSet>()
    private val xfixesRegions = linkedMapOf<Int, XFixesRegion>()
    private val atomIds = linkedMapOf<String, Int>()
    private val atomNames = linkedMapOf<Int, String>()
    private val selectionOwners = linkedMapOf<Int, XSelectionOwner>()
    private val selectionLastChangeTimes = linkedMapOf<Int, Int>()
    private val xfixesSelectionInputs = linkedMapOf<XEventSink, LinkedHashMap<XXFixesSelectionInputKey, Int>>()
    private val xfixesCursorInputs = linkedMapOf<XEventSink, LinkedHashMap<Int, Int>>()
    private val shapeInputs = linkedMapOf<XEventSink, LinkedHashSet<Int>>()
    private val randrInputs = linkedMapOf<XEventSink, LinkedHashMap<Int, Int>>()
    private val screenSaverInputs = linkedMapOf<XEventSink, Int>()
    private val windowOwners = linkedMapOf<Int, XEventSink>()
    private val resourceOwners = linkedMapOf<Int, XEventSink>()
    private val eventSinks = linkedMapOf<XEventSink, MutableMap<Int, Int>>()
    private val saveSets = linkedMapOf<XEventSink, LinkedHashMap<Int, XSaveSetEntry>>()
    private val retainedClients = linkedMapOf<Int, XRetainedClientResources>()
    private var nextRetainedClientId = 1
    private var nextAtomId = 69
    private var focusWindowId: Int = X11Ids.RootWindow
    private var focusRevertTo: Int = 0
    private var lastInputFocusChangeTime: Int = 0
    private var pointerX: Int = 0
    private var pointerY: Int = 0
    private var pointerState: Int = 0
    private var keyboardModifierState: Int = 0
    private val pressedKeycodes = mutableSetOf<Int>()
    private val pressedLogicalButtons = mutableSetOf<Int>()
    private var inputTime: Int = 1
    private var cursorSerial: Int = 1
    private val motionHistory = mutableListOf<XMotionHistoryEntry>()
    private var lastPointerGrabTime: Int = 0
    private var lastKeyboardGrabTime: Int = 0
    private var nextInputOperationId: Int = 1
    private val inputOperations = mutableListOf<XInputOperation>()
    private var nextInputControlOperationId: Int = 1
    private val inputControlOperations = mutableListOf<XInputControlOperation>()
    private val glxContexts = linkedMapOf<Int, XGlxContext>()
    private val glxLargeRenders = linkedMapOf<Int, XGlxLargeRenderState>()
    private val glxPixmaps = linkedMapOf<Int, XGlxPixmap>()
    private val glxWindows = linkedMapOf<Int, XGlxWindow>()
    private val glxPbuffers = linkedMapOf<Int, XGlxPbuffer>()
    private var nextGlxOperationId: Int = 1
    private val glxOperations = mutableListOf<XGlxOperation>()
    private var nextRenderOperationId: Int = 1
    private val renderOperations = mutableListOf<XRenderOperation>()
    private val requestCounts = linkedMapOf<String, Int>()
    private val extensionQueries = mutableListOf<XExtensionQuery>()
    private var nextExtensionQueryId: Int = 1
    private val unsupportedRequests = mutableListOf<XUnsupportedRequest>()
    private var nextUnsupportedRequestId: Int = 1
    private var screenSaver = XScreenSaverSettings()
    private var screenSaverAttributes: XScreenSaverAttributes? = null
    private val screenSaverSuspensions = linkedMapOf<XEventSink, Int>()
    private val syncCounters = linkedMapOf<Int, XSyncCounter>()
    private val syncAlarms = linkedMapOf<Int, XSyncAlarm>()
    private val syncFences = linkedMapOf<Int, XSyncFence>()
    private val syncPriorities = linkedMapOf<XEventSink, Int>()
    private val syncCounterWaiters = mutableListOf<XSyncCounterWaiter>()
    private var fontPath: List<String> = emptyList()
    private var pointerControl = XPointerControlSettings()
    private var pointerMapping = XPointerMapping.Default
    private val randrOutputProperties = linkedMapOf<Int, XRandrOutputProperty>()
    private var randrPrimaryOutput = 0
    private var randrLastCrtcConfigTime = XRandr.ConfigTimestamp
    private var randrPendingCrtcTransform = XRandrCrtcTransform.Identity
    private var randrCurrentCrtcTransform = XRandrCrtcTransform.Identity
    private var randrLastPanningTime = XRandr.ConfigTimestamp
    private var randrLastMonitorChangeTime = XRandr.ConfigTimestamp
    private val randrUserMonitors = linkedMapOf<Int, XRandrMonitor>()
    private val xkbButtonActions = linkedMapOf<Int, ByteArray>()
    private var modifierMapping = XModifierMapping.Default
    private var keyboardMapping = XKeyboardMapping.Default
    private var keyboardControl = XKeyboardControlSettings.Default
    private var activePointerGrab: XInputGrab? = null
    private var activeKeyboardGrab: XInputGrab? = null
    private val passiveButtonGrabs = mutableListOf<XPassiveButtonGrab>()
    private val passiveKeyGrabs = mutableListOf<XPassiveKeyGrab>()
    private val requestProcessingLock = ReentrantLock()
    private val serverGrabLock = ReentrantLock()
    private val serverGrabReleased = serverGrabLock.newCondition()
    private var serverGrabOwner: XEventSink? = null
    private val serverGrabImperviousClients = mutableSetOf<XEventSink>()
    private var accessControlEnabled = false
    private val accessHosts = linkedSetOf<XAccessHost>()
    private var nextClientResourceOffset = 0
    private val clientResourceReservations = linkedMapOf<Int, XEventSink>()
    private val serverStartMillis = System.currentTimeMillis()

    val extensions = listOf(
        XExtension(
            name = "GLX",
            majorOpcode = XGlx.MajorOpcode,
            firstEvent = XGlx.FirstEvent,
            firstError = XGlx.FirstError,
            aliases = setOf("SGI-GLX"),
        ),
        XExtension(
            name = "BIG-REQUESTS",
            majorOpcode = XBigRequests.MajorOpcode,
            firstEvent = XBigRequests.FirstEvent,
            firstError = XBigRequests.FirstError,
        ),
        XExtension(
            name = "RENDER",
            majorOpcode = XRender.MajorOpcode,
            firstEvent = XRender.FirstEvent,
            firstError = XRender.FirstError,
        ),
        XExtension(
            name = "MIT-SHM",
            majorOpcode = XShm.MajorOpcode,
            firstEvent = XShm.FirstEvent,
            firstError = XShm.FirstError,
        ),
        XExtension(
            name = "XFIXES",
            majorOpcode = XFixes.MajorOpcode,
            firstEvent = XFixes.FirstEvent,
            firstError = XFixes.FirstError,
        ),
        XExtension(
            name = "SHAPE",
            majorOpcode = XShape.MajorOpcode,
            firstEvent = XShape.FirstEvent,
            firstError = XShape.FirstError,
        ),
        XExtension(
            name = "XKEYBOARD",
            majorOpcode = XXkb.MajorOpcode,
            firstEvent = XXkb.FirstEvent,
            firstError = XXkb.FirstError,
            aliases = setOf("XKB"),
        ),
        XExtension(
            name = "XINERAMA",
            majorOpcode = XXinerama.MajorOpcode,
            firstEvent = XXinerama.FirstEvent,
            firstError = XXinerama.FirstError,
        ),
        XExtension(
            name = "XTEST",
            majorOpcode = XXTest.MajorOpcode,
            firstEvent = XXTest.FirstEvent,
            firstError = XXTest.FirstError,
        ),
        XExtension(
            name = "XC-MISC",
            majorOpcode = XXCMisc.MajorOpcode,
            firstEvent = XXCMisc.FirstEvent,
            firstError = XXCMisc.FirstError,
        ),
        XExtension(
            name = "MIT-SUNDRY-NONSTANDARD",
            majorOpcode = XXMitMisc.MajorOpcode,
            firstEvent = XXMitMisc.FirstEvent,
            firstError = XXMitMisc.FirstError,
            aliases = setOf("MIT-MISC"),
        ),
        XExtension(
            name = "MIT-SCREEN-SAVER",
            majorOpcode = XScreenSaver.MajorOpcode,
            firstEvent = XScreenSaver.FirstEvent,
            firstError = XScreenSaver.FirstError,
            aliases = setOf("SCREEN-SAVER"),
        ),
        XExtension(
            name = "SYNC",
            majorOpcode = XSync.MajorOpcode,
            firstEvent = XSync.FirstEvent,
            firstError = XSync.FirstError,
        ),
        XExtension(
            name = "RANDR",
            majorOpcode = XRandr.MajorOpcode,
            firstEvent = XRandr.FirstEvent,
            firstError = XRandr.FirstError,
        ),
    )

    init {
        putWindow(
            XWindow(
                id = X11Ids.RootWindow,
                parentId = 0,
                windowClass = XWindowClass.InputOutput,
                depth = X11Ids.RootDepth,
                visual = X11Ids.RootVisual,
                x = 0,
                y = 0,
                width = width,
                height = height,
                borderWidth = 0,
                mapped = true,
            ),
        )
        PredefinedAtoms.forEachIndexed { index, name ->
            val id = index + 1
            atomIds[name] = id
            atomNames[id] = name
        }
        window(X11Ids.RootWindow)?.properties?.put(
            atomIds.getValue("RESOURCE_MANAGER"),
            XProperty(
                type = atomIds.getValue("STRING"),
                format = 8,
                data = ByteArray(0),
            ),
        )
    }

    @Synchronized
    fun putWindow(window: XWindow, owner: XEventSink? = null) {
        windows[window.id] = window
        if (owner != null) {
            windowOwners[window.id] = owner
        }
    }

    @Synchronized
    fun removeWindow(id: Int): Set<Int> =
        removeWindow(id, sendDestroyNotify = false, excludedSink = null).removedResources

    @Synchronized
    fun removeWindowWithDestroyNotify(id: Int): XWindowRemoval =
        removeWindow(id, sendDestroyNotify = true, excludedSink = null)

    private fun removeWindow(id: Int, sendDestroyNotify: Boolean, excludedSink: XEventSink?): XWindowRemoval {
        val initialRemoved = windowSubtreeIds(id)
        val xfixesCursorNotifyDispatches = mutableListOf<XXFixesCursorNotifyDispatch>()
        xfixesCursorNotifyDispatches += processRetainedSaveSetsForWindowSubtree(initialRemoved)
        val previousCursor = displayedCursorIdentity()
        val removed = windowSubtreeIds(id)
        if (removed.isEmpty()) return XWindowRemoval(removedResources = emptySet(), destroyNotifyDispatches = emptyList())
        val destroyNotifyDispatches = if (sendDestroyNotify) {
            destroyNotifySinksInDestroyOrder(id, excludedSink)
        } else {
            emptyList()
        }
        for (windowId in removed) {
            windows.remove(windowId)
            windowOwners.remove(windowId)
            resourceOwners.remove(windowId)
        }
        val removedGlxWindows = glxWindows.values
            .filter { it.windowId in removed || it.id in removed }
            .map { it.id }
            .toSet()
        for (glxWindowId in removedGlxWindows) {
            glxWindows.remove(glxWindowId)
            resourceOwners.remove(glxWindowId)
        }
        val xfixesSelectionNotifyDispatches = selectionOwners
            .filterValues { it.windowId in removed }
            .flatMap { (selection) ->
                xfixesSelectionOwnerLostDispatches(
                    selection = selection,
                    subtype = XFixes.SelectionWindowDestroyNotify,
                )
            }
        releaseInputGrabsForResources(removed)
        selectionOwners.entries.removeIf { it.value.windowId in removed }
        xfixesSelectionInputs.values.forEach { selections -> selections.keys.removeIf { it.windowId in removed } }
        xfixesSelectionInputs.entries.removeIf { it.value.isEmpty() }
        xfixesCursorInputs.values.forEach { windows -> windows.keys.removeIf { it in removed } }
        xfixesCursorInputs.entries.removeIf { it.value.isEmpty() }
        shapeInputs.values.forEach { windows -> windows.removeAll(removed) }
        shapeInputs.entries.removeIf { it.value.isEmpty() }
        randrInputs.values.forEach { windows -> removed.forEach { windows.remove(it) } }
        randrInputs.entries.removeIf { it.value.isEmpty() }
        saveSets.values.forEach { saveSet -> removed.forEach { saveSet.remove(it) } }
        saveSets.entries.removeIf { it.value.isEmpty() }
        removeEventSelections(removed)
        if (focusWindowId in removed) focusWindowId = X11Ids.RootWindow
        val removedResources = removed + removedGlxWindows
        discardRetainedResourceIds(removedResources)
        return XWindowRemoval(
            removedResources = removedResources,
            destroyNotifyDispatches = destroyNotifyDispatches,
            xfixesSelectionNotifyDispatches = xfixesSelectionNotifyDispatches,
            xfixesCursorNotifyDispatches = xfixesCursorNotifyDispatches + xfixesCursorNotifyDispatchesIfChanged(previousCursor),
        )
    }

    @Synchronized
    fun removeClientResources(owner: XEventSink, resourceIds: Set<Int>): XResourceRemoval {
        val currentResourceIds = currentResourceIdsOwnedBy(owner, resourceIds)
        val xfixesCursorNotifyDispatches = processSaveSet(owner, currentResourceIds)
        val removal = removeClientResources(currentResourceIds, excludedSink = owner)
        saveSets.remove(owner)
        val cursorNotifyDispatches = releaseInputGrabs(owner)
        return removal.copy(
            xfixesCursorNotifyDispatches = xfixesCursorNotifyDispatches + removal.xfixesCursorNotifyDispatches + cursorNotifyDispatches,
        )
    }

    @Synchronized
    fun removeClientResources(resourceIds: Set<Int>): XResourceRemoval =
        removeClientResources(resourceIds, excludedSink = null)

    private fun removeClientResources(resourceIds: Set<Int>, excludedSink: XEventSink?): XResourceRemoval {
        if (resourceIds.isEmpty()) {
            return XResourceRemoval(destroyNotifyDispatches = emptyList(), xfixesSelectionNotifyDispatches = emptyList())
        }
        val removedWindows = linkedSetOf<Int>()
        val destroyNotifyDispatches = mutableListOf<XDestroyNotifyDispatch>()
        val xfixesSelectionNotifyDispatches = mutableListOf<XXFixesSelectionNotifyDispatch>()
        val xfixesCursorNotifyDispatches = mutableListOf<XXFixesCursorNotifyDispatch>()
        for (id in resourceIds) {
            if (id != X11Ids.RootWindow && windows.containsKey(id)) {
                val removal = removeWindow(id, sendDestroyNotify = true, excludedSink = excludedSink)
                removedWindows += removal.removedResources
                destroyNotifyDispatches += removal.destroyNotifyDispatches
                xfixesSelectionNotifyDispatches += removal.xfixesSelectionNotifyDispatches
                xfixesCursorNotifyDispatches += removal.xfixesCursorNotifyDispatches
            }
        }
        val ids = resourceIds - removedWindows
        val previousCursor = displayedCursorIdentity()
        for (id in ids) {
            pixmaps.remove(id)
            gcs.remove(id)
            fonts.remove(id)
            cursors.remove(id)
            if (id != X11Ids.DefaultColormap) {
                colormaps.remove(id)
                installedColormaps.remove(id)
            }
            glxContexts.remove(id)
            glxLargeRenders.remove(id)
            glxPixmaps.remove(id)
            glxWindows.remove(id)
            glxPbuffers.remove(id)
            pictures.remove(id)
            glyphSets.remove(id)
            xfixesRegions.remove(id)
            removeSyncCounter(id)
            removeSyncAlarm(id)
            removeSyncFence(id)
        }
        notifySyncWaiters()
        releaseInputGrabsForResources(resourceIds)
        xfixesCursorNotifyDispatches += xfixesCursorNotifyDispatchesIfChanged(previousCursor)
        discardRetainedResourceIds(resourceIds)
        ensureDefaultColormapInstalled()
        return XResourceRemoval(
            destroyNotifyDispatches = destroyNotifyDispatches,
            xfixesSelectionNotifyDispatches = xfixesSelectionNotifyDispatches,
            xfixesCursorNotifyDispatches = xfixesCursorNotifyDispatches,
        )
    }

    @Synchronized
    fun retainClientResources(owner: XEventSink, resourceIds: Set<Int>, closeDownMode: Int) {
        val currentResourceIds = currentResourceIdsOwnedBy(owner, resourceIds)
        if (currentResourceIds.isEmpty()) return
        retainedClients[nextRetainedClientId++] = XRetainedClientResources(
            closeDownMode = closeDownMode,
            resourceIds = currentResourceIds,
            saveSet = saveSets[owner]?.values?.toList().orEmpty(),
        )
    }

    @Synchronized
    fun liveClientOwningResource(resourceId: Int): XEventSink? =
        resourceOwners[resourceId]?.takeIf { it in eventSinks && !it.isKilled() }

    @Synchronized
    fun markResourceOwner(resourceId: Int, owner: XEventSink) {
        if (clientResourceReservations[resourceId] == owner) {
            clientResourceReservations.remove(resourceId)
        }
        resourceOwners[resourceId] = owner
    }

    @Synchronized
    fun destroyRetainedClientByResource(resourceId: Int): XResourceRemoval? {
        val retained = retainedClients.entries.firstOrNull { resourceId in it.value.resourceIds } ?: return null
        retainedClients.remove(retained.key)
        val xfixesCursorNotifyDispatches = processRetainedSaveSet(retained.value)
        val removal = removeClientResources(retained.value.resourceIds)
        return removal.copy(
            xfixesCursorNotifyDispatches = xfixesCursorNotifyDispatches + removal.xfixesCursorNotifyDispatches,
        )
    }

    @Synchronized
    fun destroyTemporaryRetainedClients(): XResourceRemoval {
        val temporaryIds = retainedClients
            .filterValues { it.closeDownMode == XCloseDownMode.RetainTemporary }
            .keys
            .toList()
        val destroyNotifyDispatches = mutableListOf<XDestroyNotifyDispatch>()
        val xfixesSelectionNotifyDispatches = mutableListOf<XXFixesSelectionNotifyDispatch>()
        val xfixesCursorNotifyDispatches = mutableListOf<XXFixesCursorNotifyDispatch>()
        for (id in temporaryIds) {
            val retained = retainedClients.remove(id) ?: continue
            xfixesCursorNotifyDispatches += processRetainedSaveSet(retained)
            val removal = removeClientResources(retained.resourceIds)
            destroyNotifyDispatches += removal.destroyNotifyDispatches
            xfixesSelectionNotifyDispatches += removal.xfixesSelectionNotifyDispatches
            xfixesCursorNotifyDispatches += removal.xfixesCursorNotifyDispatches
        }
        return XResourceRemoval(
            destroyNotifyDispatches = destroyNotifyDispatches,
            xfixesSelectionNotifyDispatches = xfixesSelectionNotifyDispatches,
            xfixesCursorNotifyDispatches = xfixesCursorNotifyDispatches,
        )
    }

    @Synchronized
    fun window(id: Int): XWindow? = windows[id]

    @Synchronized
    fun windowOwner(id: Int): XEventSink? = windowOwners[id]

    @Synchronized
    fun changeSaveSet(owner: XEventSink, windowId: Int, insert: Boolean) {
        changeSaveSet(
            owner = owner,
            windowId = windowId,
            insert = insert,
            target = XSaveSetTarget.Nearest,
            map = XSaveSetMap.Map,
        )
    }

    @Synchronized
    fun changeSaveSet(owner: XEventSink, windowId: Int, insert: Boolean, target: Int, map: Int) {
        if (insert) {
            saveSets.getOrPut(owner) { linkedMapOf() }[windowId] = XSaveSetEntry(windowId, target, map)
        } else {
            saveSets[owner]?.remove(windowId)
            if (saveSets[owner]?.isEmpty() == true) saveSets.remove(owner)
        }
    }

    @Synchronized
    fun selectXFixesSelectionInput(owner: XEventSink, windowId: Int, selection: Int, eventMask: Int) {
        val key = XXFixesSelectionInputKey(windowId, selection)
        if (eventMask == 0) {
            xfixesSelectionInputs[owner]?.remove(key)
            if (xfixesSelectionInputs[owner]?.isEmpty() == true) xfixesSelectionInputs.remove(owner)
        } else {
            xfixesSelectionInputs.getOrPut(owner) { linkedMapOf() }[key] = eventMask
        }
    }

    @Synchronized
    fun selectXFixesCursorInput(owner: XEventSink, windowId: Int, eventMask: Int) {
        if (eventMask == 0) {
            xfixesCursorInputs[owner]?.remove(windowId)
            if (xfixesCursorInputs[owner]?.isEmpty() == true) xfixesCursorInputs.remove(owner)
        } else {
            xfixesCursorInputs.getOrPut(owner) { linkedMapOf() }[windowId] = eventMask
        }
    }

    @Synchronized
    fun selectRandrInput(owner: XEventSink, windowId: Int, eventMask: Int) {
        if (eventMask == 0) {
            randrInputs[owner]?.remove(windowId)
            if (randrInputs[owner]?.isEmpty() == true) randrInputs.remove(owner)
        } else {
            randrInputs.getOrPut(owner) { linkedMapOf() }[windowId] = eventMask
        }
    }

    @Synchronized
    fun cursorSerial(): Int = cursorSerial

    @Synchronized
    fun cursorImage(id: Int): XCursorImage? =
        cursors[id]?.image

    @Synchronized
    fun cursorGeneration(id: Int): Long? =
        cursors[id]?.generation

    @Synchronized
    fun cursorName(id: Int): XCursorName? =
        cursors[id]?.name

    @Synchronized
    fun displayedCursorImage(): XCursorImage? {
        activePointerGrab?.cursor?.let { return cursors[it]?.image }
        val pointerWindow = windowAt(pointerX, pointerY) ?: windows[X11Ids.RootWindow] ?: return null
        return windowPathToRoot(pointerWindow.id).firstOrNull { it.cursorId != null }?.cursorImage
    }

    @Synchronized
    fun displayedCursorName(): XCursorName? {
        activePointerGrab?.cursor?.let { return cursors[it]?.name }
        val pointerWindow = windowAt(pointerX, pointerY) ?: windows[X11Ids.RootWindow] ?: return null
        val cursorWindow = windowPathToRoot(pointerWindow.id).firstOrNull { it.cursorId != null } ?: return null
        val id = cursorWindow.cursorId ?: return null
        val cursor = cursors[id]
        return if (cursor != null && cursorWindow.cursorGeneration == cursor.generation) {
            cursor.name
        } else {
            cursorWindow.cursorName
        }
    }

    @Synchronized
    fun displayedCursorSnapshot(): XCursorIdentity? = displayedCursorIdentity()

    @Synchronized
    fun windowCursorMatches(windowId: Int, cursor: Int): Boolean? {
        val window = windows[windowId] ?: return null
        val windowCursor = window.cursorId
        return when (cursor) {
            XXTest.CursorNone -> windowCursor == null
            XXTest.CursorCurrent -> windowCursor == displayedCursorId()
            else -> windowCursor == cursor
        }
    }

    @Synchronized
    fun cursorNotifyDispatchesIfDisplayChanged(previousCursor: XCursorIdentity?): List<XXFixesCursorNotifyDispatch> =
        xfixesCursorNotifyDispatchesIfChanged(previousCursor)

    @Synchronized
    fun windowIsViewable(id: Int): Boolean {
        val window = windows[id] ?: return false
        if (!window.mapped) return false
        if (id == X11Ids.RootWindow) return true
        var parentId = window.parentId
        val visited = mutableSetOf(id)
        while (parentId != 0 && visited.add(parentId)) {
            val parent = windows[parentId] ?: return false
            if (!parent.mapped) return false
            if (parent.id == X11Ids.RootWindow) return true
            parentId = parent.parentId
        }
        return false
    }

    @Synchronized
    fun windowEventMask(id: Int): Int =
        eventSinks.values.fold(0) { acc, selections -> acc or (selections[id] ?: 0) }

    @Synchronized
    fun windowEventMaskForSink(sink: XEventSink, id: Int): Int =
        eventSinks[sink]?.get(id) ?: 0

    @Synchronized
    fun childrenOf(id: Int): List<XWindow> = windows.values.filter { it.parentId == id }

    @Synchronized
    fun canReparentWindow(id: Int, parentId: Int): Boolean {
        if (id == X11Ids.RootWindow) return false
        val window = windows[id] ?: return false
        val parent = windows[parentId] ?: return false
        if (window.windowClass == XWindowClass.InputOutput && parent.windowClass == XWindowClass.InputOnly) return false
        return !windowIsAncestorOrSelf(id, parentId)
    }

    @Synchronized
    fun circulateWindow(id: Int, direction: Int): XCirculateResult? {
        val result = circulateWindowTarget(id, direction) ?: return null
        restackChild(result.window, result.place)
        return result
    }

    @Synchronized
    fun circulateWindowTarget(id: Int, direction: Int): XCirculateResult? {
        if (!windows.containsKey(id)) return null
        val children = childrenOf(id)
        val target = when (direction) {
            XCirculateResult.RaiseLowest -> children.firstOrNull { child ->
                child.mapped && childrenAfter(children, child).any { it.mapped && windowsOverlap(child, it) }
            }
            XCirculateResult.LowerHighest -> children.asReversed().firstOrNull { child ->
                child.mapped && childrenBefore(children, child).any { it.mapped && windowsOverlap(child, it) }
            }
            else -> null
        } ?: return null

        val place = if (direction == XCirculateResult.RaiseLowest) XCirculateResult.Top else XCirculateResult.Bottom
        return XCirculateResult(parentId = id, window = target, place = place)
    }

    @Synchronized
    fun reparentWindow(id: Int, parentId: Int, x: Int, y: Int): XWindow? {
        val window = windows[id] ?: return null
        if (!canReparentWindow(id, parentId)) return null
        if (window.mapped) releaseActiveGrabsForAutomaticUnmap(id)
        window.parentId = parentId
        window.x = x
        window.y = y
        releaseActiveGrabsForVisibilityChanges()
        return window
    }

    @Synchronized
    fun mapWindow(id: Int): XWindow? {
        val window = windows[id] ?: return null
        window.mapped = true
        if (windowIsViewable(id)) focusWindowId = id
        return window
    }

    @Synchronized
    fun unmapWindow(id: Int) {
        windows[id]?.mapped = false
        if (focusWindowId == id) focusWindowId = X11Ids.RootWindow
        releaseActiveGrabsForVisibilityChanges()
    }

    @Synchronized
    fun setInputFocus(focusWindowId: Int, revertTo: Int, time: Int): List<XFocusDispatch> {
        val serverTime = currentServerTime(lastInputFocusChangeTime)
        if (time != 0 &&
            (
                Integer.compareUnsigned(time, lastInputFocusChangeTime) < 0 ||
                    Integer.compareUnsigned(time, serverTime) > 0
                )
        ) {
            return emptyList()
        }
        val previousFocusWindowId = this.focusWindowId
        lastInputFocusChangeTime = if (time == 0) serverTime else time
        this.focusWindowId = focusWindowId
        this.focusRevertTo = revertTo
        if (previousFocusWindowId == focusWindowId) return emptyList()
        return focusChangeDispatches(previousFocusWindowId, XFocusEventType.FocusOut) +
            focusChangeDispatches(focusWindowId, XFocusEventType.FocusIn)
    }

    private fun focusChangeDispatches(windowId: Int, type: XFocusEventType): List<XFocusDispatch> {
        if (windowId !in windows || windowId == X11Ids.RootWindow) return emptyList()
        return eventSelectionsForWindow(windowId, XEventMasks.FocusChange).map { sink ->
            XFocusDispatch(
                sink = sink,
                event = XFocusEvent(type = type, windowId = windowId),
            )
        }
    }

    @Synchronized
    fun inputFocus(): Pair<Int, Int> = focusWindowId to focusRevertTo

    @Synchronized
    fun screenSaver(): XScreenSaverSettings = screenSaver

    @Synchronized
    fun setScreenSaver(timeout: Int, interval: Int, preferBlanking: Int, allowExposures: Int) {
        screenSaver = XScreenSaverSettings(
            timeout = if (timeout == -1) XScreenSaverSettings.DefaultTimeout else timeout,
            interval = if (interval == -1) XScreenSaverSettings.DefaultInterval else interval,
            preferBlanking = if (preferBlanking == 2) XScreenSaverSettings.DefaultPreferBlanking else preferBlanking,
            allowExposures = if (allowExposures == 2) XScreenSaverSettings.DefaultAllowExposures else allowExposures,
        )
    }

    @Synchronized
    fun pointerControl(): XPointerControlSettings = pointerControl

    @Synchronized
    fun setPointerControl(accelerationNumerator: Int?, accelerationDenominator: Int?, threshold: Int?) {
        pointerControl = XPointerControlSettings(
            accelerationNumerator = accelerationNumerator ?: pointerControl.accelerationNumerator,
            accelerationDenominator = accelerationDenominator ?: pointerControl.accelerationDenominator,
            threshold = threshold ?: pointerControl.threshold,
        )
    }

    @Synchronized
    fun pointerMapping(): List<Int> = pointerMapping.toList()

    @Synchronized
    fun xkbButtonActions(firstButton: Int, count: Int): List<ByteArray> =
        List(count) { index ->
            xkbButtonActions[firstButton + index]?.copyOf() ?: ByteArray(8)
        }

    @Synchronized
    fun setXkbButtonActions(firstButton: Int, actions: List<ByteArray>) {
        val totalButtons = pointerMapping.size
        actions.forEachIndexed { index, action ->
            val button = firstButton + index
            if (button in 1..totalButtons) {
                xkbButtonActions[button] = action.copyOf(8)
            }
        }
    }

    @Synchronized
    fun setPointerMappingIfIdle(mapping: List<Int>): Boolean {
        val alteredDownButton = pointerMapping
            .zip(mapping)
            .any { (current, updated) -> current != updated && current != 0 && current in pressedLogicalButtons }
        if (alteredDownButton) return false
        pointerMapping = mapping.toList()
        return true
    }

    @Synchronized
    fun mappingNotifySinks(): List<XEventSink> = eventSinks.keys.toList()

    @Synchronized
    fun propertyNotifySinks(windowId: Int): List<XEventSink> =
        eventSelectionsForWindow(windowId, XEventMasks.PropertyChange)

    @Synchronized
    fun mapNotifySinks(window: XWindow): List<XMapNotifyDispatch> =
        eventSelectionsForWindow(window.id, XEventMasks.StructureNotify).map { sink ->
            XMapNotifyDispatch(
                sink = sink,
                event = XMapNotifyEvent(eventWindowId = window.id, windowId = window.id, overrideRedirect = window.overrideRedirect),
            )
        } + eventSelectionsForWindow(window.parentId, XEventMasks.SubstructureNotify).map { sink ->
            XMapNotifyDispatch(
                sink = sink,
                event = XMapNotifyEvent(eventWindowId = window.parentId, windowId = window.id, overrideRedirect = window.overrideRedirect),
            )
        }

    @Synchronized
    fun mapRequestSinks(requester: XEventSink, window: XWindow): List<XMapRequestDispatch> =
        eventSelectionsForWindow(window.parentId, XEventMasks.SubstructureRedirect)
            .filter { sink -> sink != requester }
            .map { sink ->
                XMapRequestDispatch(
                    sink = sink,
                    event = XMapRequestEvent(parentId = window.parentId, windowId = window.id),
                )
            }

    @Synchronized
    fun resizeRequestSinks(requester: XEventSink, window: XWindow, width: Int, height: Int): List<XResizeRequestDispatch> =
        eventSelectionsForWindow(window.id, XEventMasks.ResizeRedirect)
            .filter { sink -> sink != requester }
            .map { sink ->
                XResizeRequestDispatch(
                    sink = sink,
                    event = XResizeRequestEvent(windowId = window.id, width = width, height = height),
                )
            }

    @Synchronized
    fun createNotifySinks(window: XWindow): List<XCreateNotifyDispatch> =
        eventSelectionsForWindow(window.parentId, XEventMasks.SubstructureNotify).map { sink ->
            XCreateNotifyDispatch(
                sink = sink,
                event = XCreateNotifyEvent(
                    parentId = window.parentId,
                    windowId = window.id,
                    x = window.x,
                    y = window.y,
                    width = window.width,
                    height = window.height,
                    borderWidth = window.borderWidth,
                    overrideRedirect = window.overrideRedirect,
                ),
            )
        }

    @Synchronized
    fun destroyNotifySinks(window: XWindow): List<XDestroyNotifyDispatch> =
        eventSelectionsForWindow(window.id, XEventMasks.StructureNotify).map { sink ->
            XDestroyNotifyDispatch(
                sink = sink,
                event = XDestroyNotifyEvent(
                    eventWindowId = window.id,
                    windowId = window.id,
                ),
            )
        } + eventSelectionsForWindow(window.parentId, XEventMasks.SubstructureNotify).map { sink ->
            XDestroyNotifyDispatch(
                sink = sink,
                event = XDestroyNotifyEvent(
                    eventWindowId = window.parentId,
                    windowId = window.id,
                ),
            )
        }

    @Synchronized
    fun unmapNotifySinks(window: XWindow): List<XUnmapNotifyDispatch> =
        eventSelectionsForWindow(window.id, XEventMasks.StructureNotify).map { sink ->
            XUnmapNotifyDispatch(
                sink = sink,
                event = XUnmapNotifyEvent(eventWindowId = window.id, windowId = window.id),
            )
        } + eventSelectionsForWindow(window.parentId, XEventMasks.SubstructureNotify).map { sink ->
            XUnmapNotifyDispatch(
                sink = sink,
                event = XUnmapNotifyEvent(eventWindowId = window.parentId, windowId = window.id),
            )
        }

    @Synchronized
    fun reparentNotifySinks(window: XWindow, oldParentId: Int): List<XReparentNotifyDispatch> {
        val event = XReparentNotifyEvent(
            eventWindowId = window.id,
            windowId = window.id,
            parentId = window.parentId,
            x = window.x,
            y = window.y,
            overrideRedirect = window.overrideRedirect,
        )
        val parentIds = if (oldParentId == window.parentId) {
            listOf(oldParentId)
        } else {
            listOf(oldParentId, window.parentId)
        }
        return eventSelectionsForWindow(window.id, XEventMasks.StructureNotify).map { sink ->
            XReparentNotifyDispatch(sink = sink, event = event)
        } + parentIds.flatMap { parentId ->
            eventSelectionsForWindow(parentId, XEventMasks.SubstructureNotify).map { sink ->
                XReparentNotifyDispatch(
                    sink = sink,
                    event = event.copy(eventWindowId = parentId),
                )
            }
        }
    }

    @Synchronized
    fun circulateNotifySinks(result: XCirculateResult): List<XCirculateNotifyDispatch> =
        eventSelectionsForWindow(result.window.id, XEventMasks.StructureNotify).map { sink ->
            XCirculateNotifyDispatch(
                sink = sink,
                event = XCirculateNotifyEvent(eventWindowId = result.window.id, windowId = result.window.id, place = result.place),
            )
        } + eventSelectionsForWindow(result.parentId, XEventMasks.SubstructureNotify).map { sink ->
            XCirculateNotifyDispatch(
                sink = sink,
                event = XCirculateNotifyEvent(eventWindowId = result.parentId, windowId = result.window.id, place = result.place),
            )
        }

    @Synchronized
    fun circulateRequestSinks(requester: XEventSink, result: XCirculateResult): List<XCirculateRequestDispatch> =
        eventSelectionsForWindow(result.parentId, XEventMasks.SubstructureRedirect)
            .filter { sink -> sink != requester }
            .map { sink ->
                XCirculateRequestDispatch(
                    sink = sink,
                    event = XCirculateRequestEvent(parentId = result.parentId, windowId = result.window.id, place = result.place),
                )
            }

    @Synchronized
    fun configureNotifySinks(result: XConfigureWindowResult): List<XConfigureNotifyDispatch> =
        eventSelectionsForWindow(result.window.id, XEventMasks.StructureNotify).map { sink ->
            XConfigureNotifyDispatch(
                sink = sink,
                event = result.configureNotifyEvent(eventWindowId = result.window.id),
            )
        } + eventSelectionsForWindow(result.window.parentId, XEventMasks.SubstructureNotify).map { sink ->
            XConfigureNotifyDispatch(
                sink = sink,
                event = result.configureNotifyEvent(eventWindowId = result.window.parentId),
            )
        }

    @Synchronized
    fun configureRequestSinks(
        requester: XEventSink,
        window: XWindow,
        x: Int?,
        y: Int?,
        width: Int?,
        height: Int?,
        borderWidth: Int?,
        siblingId: Int?,
        stackMode: Int?,
        valueMask: Int,
    ): List<XConfigureRequestDispatch> =
        eventSelectionsForWindow(window.parentId, XEventMasks.SubstructureRedirect)
            .filter { sink -> sink != requester }
            .map { sink ->
                XConfigureRequestDispatch(
                    sink = sink,
                    event = XConfigureRequestEvent(
                        parentId = window.parentId,
                        windowId = window.id,
                        siblingId = siblingId ?: 0,
                        x = x ?: window.x,
                        y = y ?: window.y,
                        width = width ?: window.width,
                        height = height ?: window.height,
                        borderWidth = borderWidth ?: window.borderWidth,
                        stackMode = stackMode ?: XStackMode.Above,
                        valueMask = valueMask,
                    ),
                )
            }

    @Synchronized
    fun pointerLogicalButton(physicalButton: Int): Int =
        pointerMapping.getOrNull(physicalButton - 1) ?: physicalButton

    @Synchronized
    fun modifierMapping(): List<Int> = modifierMapping.toList()

    @Synchronized
    fun setModifierMappingIfIdle(mapping: List<Int>): Boolean {
        val currentKeycodesPerModifier = modifierMapping.size / 8
        val updatedKeycodesPerModifier = mapping.size / 8
        val alteredDownKey = (0 until 8).any { modifier ->
            // Modifier membership ignores zero slots and order; the stored layout still follows the request on success.
            val current = modifierKeycodes(modifierMapping, currentKeycodesPerModifier, modifier)
            val updated = modifierKeycodes(mapping, updatedKeycodesPerModifier, modifier)
            current != updated && (current + updated).any { it in pressedKeycodes }
        }
        if (alteredDownKey) return false
        modifierMapping = mapping.toList()
        return true
    }

    private fun modifierKeycodes(mapping: List<Int>, keycodesPerModifier: Int, modifier: Int): Set<Int> {
        if (keycodesPerModifier == 0) return emptySet()
        val start = modifier * keycodesPerModifier
        return mapping
            .asSequence()
            .drop(start)
            .take(keycodesPerModifier)
            .filter { it != 0 }
            .toSet()
    }

    @Synchronized
    fun pointerMask(): Int = pointerState or keyboardModifierState

    @Synchronized
    fun keyboardPointerState(): XKeyboardPointerState =
        XKeyboardPointerState(
            modifiers = keyboardModifierState,
            pointerButtons = pointerState,
        )

    @Synchronized
    fun queryKeymap(): ByteArray {
        val keys = ByteArray(32)
        for (keycode in pressedKeycodes) {
            keys[keycode / 8] = (keys[keycode / 8].toInt() or (1 shl (keycode % 8))).toByte()
        }
        return keys
    }

    @Synchronized
    fun keyboardMapping(firstKeycode: Int, count: Int): XKeyboardMapping {
        val rows = linkedMapOf<Int, List<Int>>()
        for (keycode in firstKeycode until firstKeycode + count) {
            rows[keycode] = keyboardMapping.keysymsFor(keycode)
        }
        return XKeyboardMapping(
            keysymsPerKeycode = keyboardMapping.keysymsPerKeycode,
            keysymsByKeycode = rows,
        )
    }

    @Synchronized
    fun setKeyboardMapping(firstKeycode: Int, keysymsPerKeycode: Int, keysyms: List<Int>): Int {
        val keycodeCount = keysyms.size / keysymsPerKeycode
        val effectiveKeysymsPerKeycode = maxOf(keyboardMapping.keysymsPerKeycode, keysymsPerKeycode)
        val rows = keyboardMapping.keysymsByKeycode
            .mapValues { (_, existing) -> List(effectiveKeysymsPerKeycode) { index -> existing.getOrElse(index) { 0 } } }
            .toMutableMap()
        for (index in 0 until keycodeCount) {
            val keycode = firstKeycode + index
            val start = index * keysymsPerKeycode
            val row = keysyms.subList(start, start + keysymsPerKeycode)
            rows[keycode] = List(effectiveKeysymsPerKeycode) { keysymIndex -> row.getOrElse(keysymIndex) { 0 } }
        }
        keyboardMapping = XKeyboardMapping(
            keysymsPerKeycode = effectiveKeysymsPerKeycode,
            keysymsByKeycode = rows.toSortedMap(),
        )
        return keycodeCount
    }

    @Synchronized
    fun keyboardControl(): XKeyboardControlSettings = keyboardControl.copy(
        autoRepeats = keyboardControl.autoRepeats.copyOf(),
    )

    @Synchronized
    fun updateKeyboardControl(update: XKeyboardControlUpdate) {
        var next = keyboardControl
        update.keyClickPercent?.let {
            next = next.copy(keyClickPercent = if (it == -1) XKeyboardControlSettings.DefaultKeyClickPercent else it)
        }
        update.bellPercent?.let {
            next = next.copy(bellPercent = if (it == -1) XKeyboardControlSettings.DefaultBellPercent else it)
        }
        update.bellPitch?.let {
            next = next.copy(bellPitch = if (it == -1) XKeyboardControlSettings.DefaultBellPitch else it)
        }
        update.bellDuration?.let {
            next = next.copy(bellDuration = if (it == -1) XKeyboardControlSettings.DefaultBellDuration else it)
        }
        update.ledMode?.let { mode ->
            next = if (update.led != null) {
                val bit = 1 shl (update.led - 1)
                next.copy(ledMask = if (mode == XKeyboardLedMode.On) next.ledMask or bit else next.ledMask and bit.inv())
            } else {
                next.copy(ledMask = if (mode == XKeyboardLedMode.On) -1 else 0)
            }
        }
        update.autoRepeatMode?.let { mode ->
            if (update.key != null) {
                val autoRepeats = next.autoRepeats.copyOf()
                val enabled = mode != XKeyboardAutoRepeatMode.Off
                val index = update.key / 8
                val bit = 1 shl (update.key % 8)
                autoRepeats[index] = if (enabled) {
                    (autoRepeats[index].toInt() or bit).toByte()
                } else {
                    (autoRepeats[index].toInt() and bit.inv()).toByte()
                }
                next = next.copy(autoRepeats = autoRepeats)
            } else {
                next = next.copy(globalAutoRepeat = mode != XKeyboardAutoRepeatMode.Off)
            }
        }
        keyboardControl = next
    }

    @Synchronized
    fun fontPath(): List<String> = fontPath.toList()

    @Synchronized
    fun setFontPath(path: List<String>) {
        fontPath = path.toList()
    }

    @Synchronized
    fun grabPointer(grab: XInputGrab): XPointerGrabResult {
        if (!windowIsViewable(grab.windowId)) {
            return XPointerGrabResult(status = XGrabStatus.NotViewable, cursorNotifyDispatches = emptyList())
        }
        if (grab.confineTo?.let { !windowIsViewable(it) || !windowIntersectsRootBounds(it) } == true) {
            return XPointerGrabResult(status = XGrabStatus.NotViewable, cursorNotifyDispatches = emptyList())
        }
        val serverTime = currentServerTime(lastPointerGrabTime)
        if (grab.time != 0 &&
            (
                Integer.compareUnsigned(grab.time, lastPointerGrabTime) < 0 ||
                    Integer.compareUnsigned(grab.time, serverTime) > 0
                )
        ) {
            return XPointerGrabResult(status = XGrabStatus.InvalidTime, cursorNotifyDispatches = emptyList())
        }
        if (activePointerGrab?.owner != null && activePointerGrab?.owner != grab.owner) {
            return XPointerGrabResult(status = XGrabStatus.AlreadyGrabbed, cursorNotifyDispatches = emptyList())
        }
        val previousCursor = displayedCursorIdentity()
        val effectiveTime = if (grab.time == 0) serverTime else grab.time
        lastPointerGrabTime = effectiveTime
        activePointerGrab = grab.copy(time = effectiveTime)
        return XPointerGrabResult(
            status = XGrabStatus.Success,
            cursorNotifyDispatches = xfixesCursorNotifyDispatchesIfChanged(previousCursor),
        )
    }

    @Synchronized
    fun ungrabPointer(owner: XEventSink, time: Int): List<XXFixesCursorNotifyDispatch> {
        val grab = activePointerGrab
        if (grab?.owner == owner && validUngrabTime(time, grab.time)) {
            val previousCursor = displayedCursorIdentity()
            activePointerGrab = null
            return xfixesCursorNotifyDispatchesIfChanged(previousCursor)
        }
        return emptyList()
    }

    @Synchronized
    fun changeActivePointerGrab(owner: XEventSink, eventMask: Int, cursor: Int?, time: Int): List<XXFixesCursorNotifyDispatch> {
        val grab = activePointerGrab
        if (grab?.owner == owner && validChangeActivePointerGrabTime(time, grab.time)) {
            val previousCursor = displayedCursorIdentity()
            activePointerGrab = grab.copy(eventMask = eventMask, cursor = cursor)
            return xfixesCursorNotifyDispatchesIfChanged(previousCursor)
        }
        return emptyList()
    }

    @Synchronized
    fun grabButton(grab: XPassiveButtonGrab): Boolean {
        if (passiveButtonGrabs.any { it.owner != grab.owner && passiveButtonGrabConflicts(it, grab) }) return false
        var index = passiveButtonGrabs.lastIndex
        while (index >= 0) {
            val existing = passiveButtonGrabs[index]
            if (existing.owner == grab.owner && existing.windowId == grab.windowId) {
                val releasedCombinations = buttonGrabReleasedCombinations(existing, grab.button, grab.modifiers)
                if (releasedCombinations != null) {
                    if (buttonGrabFullyReleased(existing, releasedCombinations)) {
                        passiveButtonGrabs.removeAt(index)
                    } else {
                        passiveButtonGrabs[index] = existing.copy(releasedCombinations = releasedCombinations)
                    }
                }
            }
            index--
        }
        passiveButtonGrabs += grab
        return true
    }

    @Synchronized
    fun ungrabButton(owner: XEventSink, windowId: Int, button: Int, modifiers: Int) {
        var index = passiveButtonGrabs.lastIndex
        while (index >= 0) {
            val grab = passiveButtonGrabs[index]
            if (grab.owner == owner && grab.windowId == windowId) {
                val releasedCombinations = buttonGrabReleasedCombinations(grab, button, modifiers)
                if (releasedCombinations != null) {
                    if (buttonGrabFullyReleased(grab, releasedCombinations)) {
                        passiveButtonGrabs.removeAt(index)
                    } else {
                        passiveButtonGrabs[index] = grab.copy(releasedCombinations = releasedCombinations)
                    }
                }
            }
            index--
        }
    }

    @Synchronized
    fun grabKey(grab: XPassiveKeyGrab): Boolean {
        if (passiveKeyGrabs.any { it.owner != grab.owner && passiveKeyGrabConflicts(it, grab) }) return false
        var index = passiveKeyGrabs.lastIndex
        while (index >= 0) {
            val existing = passiveKeyGrabs[index]
            if (existing.owner == grab.owner && existing.windowId == grab.windowId) {
                val releasedCombinations = keyGrabReleasedCombinations(existing, grab.key, grab.modifiers)
                if (releasedCombinations != null) {
                    if (keyGrabFullyReleased(existing, releasedCombinations)) {
                        passiveKeyGrabs.removeAt(index)
                    } else {
                        passiveKeyGrabs[index] = existing.copy(releasedCombinations = releasedCombinations)
                    }
                }
            }
            index--
        }
        passiveKeyGrabs += grab
        return true
    }

    @Synchronized
    fun ungrabKey(owner: XEventSink, windowId: Int, key: Int, modifiers: Int) {
        var index = passiveKeyGrabs.lastIndex
        while (index >= 0) {
            val grab = passiveKeyGrabs[index]
            if (grab.owner == owner && grab.windowId == windowId) {
                val releasedCombinations = keyGrabReleasedCombinations(grab, key, modifiers)
                if (releasedCombinations != null) {
                    if (keyGrabFullyReleased(grab, releasedCombinations)) {
                        passiveKeyGrabs.removeAt(index)
                    } else {
                        passiveKeyGrabs[index] = grab.copy(releasedCombinations = releasedCombinations)
                    }
                }
            }
            index--
        }
    }

    @Synchronized
    fun grabKeyboard(grab: XInputGrab): Int {
        if (!windowIsViewable(grab.windowId)) return XGrabStatus.NotViewable
        val serverTime = currentServerTime(lastKeyboardGrabTime)
        if (grab.time != 0 &&
            (
                Integer.compareUnsigned(grab.time, lastKeyboardGrabTime) < 0 ||
                    Integer.compareUnsigned(grab.time, serverTime) > 0
                )
        ) {
            return XGrabStatus.InvalidTime
        }
        if (activeKeyboardGrab?.owner != null && activeKeyboardGrab?.owner != grab.owner) return XGrabStatus.AlreadyGrabbed
        val effectiveTime = if (grab.time == 0) serverTime else grab.time
        lastKeyboardGrabTime = effectiveTime
        activeKeyboardGrab = grab.copy(time = effectiveTime)
        return XGrabStatus.Success
    }

    @Synchronized
    fun ungrabKeyboard(owner: XEventSink, time: Int) {
        val grab = activeKeyboardGrab
        if (grab?.owner == owner && validUngrabTime(time, grab.time)) activeKeyboardGrab = null
    }

    @Synchronized
    fun allowEvents(owner: XEventSink, mode: Int, time: Int) {
        var lastGrabTime = 0
        val pointerGrab = activePointerGrab
        if (pointerGrab?.owner == owner) lastGrabTime = pointerGrab.time
        val keyboardGrab = activeKeyboardGrab
        if (keyboardGrab?.owner == owner && Integer.compareUnsigned(keyboardGrab.time, lastGrabTime) > 0) {
            lastGrabTime = keyboardGrab.time
        }
        val serverTime = currentServerTime(lastGrabTime)
        if (time != 0 &&
            (
                Integer.compareUnsigned(time, lastGrabTime) < 0 ||
                    Integer.compareUnsigned(time, serverTime) > 0
                )
        ) {
            return
        }
        recordInputControlOperation(
            operation = "AllowEvents",
            mode = mode,
            time = if (time == 0) serverTime else time,
        )
    }

    @Synchronized
    fun releaseInputGrabs(owner: XEventSink): List<XXFixesCursorNotifyDispatch> {
        val previousCursor = displayedCursorIdentity()
        if (activePointerGrab?.owner == owner) activePointerGrab = null
        if (activeKeyboardGrab?.owner == owner) activeKeyboardGrab = null
        passiveButtonGrabs.removeIf { it.owner == owner }
        passiveKeyGrabs.removeIf { it.owner == owner }
        return xfixesCursorNotifyDispatchesIfChanged(previousCursor)
    }

    private fun releaseInputGrabsForResources(resourceIds: Set<Int>) {
        val pointerGrab = activePointerGrab
        if (
            pointerGrab != null &&
            (pointerGrab.windowId in resourceIds ||
                pointerGrab.confineTo?.let { it in resourceIds } == true ||
                pointerGrab.cursor?.let { it in resourceIds } == true)
        ) {
            activePointerGrab = null
        }
        val keyboardGrab = activeKeyboardGrab
        if (keyboardGrab != null && keyboardGrab.windowId in resourceIds) {
            activeKeyboardGrab = null
        }
        passiveButtonGrabs.removeIf {
            it.windowId in resourceIds ||
                it.confineTo?.let { id -> id in resourceIds } == true ||
                it.cursor?.let { id -> id in resourceIds } == true
        }
        passiveKeyGrabs.removeIf { it.windowId in resourceIds }
    }

    private fun releaseActiveGrabsForAutomaticUnmap(windowId: Int) {
        val unmappedIds = subtreeIds(windowId)
        val pointerGrab = activePointerGrab
        if (
            pointerGrab != null &&
            (
                pointerGrab.windowId in unmappedIds ||
                    pointerGrab.confineTo?.let { it in unmappedIds } == true
                )
        ) {
            activePointerGrab = null
        }
        val keyboardGrab = activeKeyboardGrab
        if (keyboardGrab != null && keyboardGrab.windowId in unmappedIds) {
            activeKeyboardGrab = null
        }
    }

    private fun releaseActiveGrabsForVisibilityChanges() {
        val pointerGrab = activePointerGrab
        if (
            pointerGrab != null &&
            (
                !windowIsViewable(pointerGrab.windowId) ||
                    pointerGrab.confineTo?.let { !windowIsViewable(it) || !windowIntersectsRootBounds(it) } == true
                )
        ) {
            activePointerGrab = null
        }
        val keyboardGrab = activeKeyboardGrab
        if (keyboardGrab != null && !windowIsViewable(keyboardGrab.windowId)) {
            activeKeyboardGrab = null
        }
    }

    private fun validUngrabTime(requestTime: Int, grabTime: Int): Boolean =
        requestTime == 0 ||
            (
                Integer.compareUnsigned(requestTime, grabTime) >= 0 &&
                    Integer.compareUnsigned(requestTime, currentServerTime(grabTime)) <= 0
                )

    private fun validChangeActivePointerGrabTime(requestTime: Int, grabTime: Int): Boolean =
        requestTime == 0 ||
            (
                Integer.compareUnsigned(requestTime, grabTime) >= 0 &&
                    Integer.compareUnsigned(requestTime, currentServerTime(grabTime)) <= 0
                )

    private fun currentServerTime(floor: Int): Int =
        if (Integer.compareUnsigned(inputTime, floor) < 0) floor else inputTime

    @Synchronized
    fun windowIntersectsRootBounds(id: Int): Boolean {
        val window = windows[id] ?: return false
        val absolute = absolutePosition(window)
        return absolute.first < width &&
            absolute.second < height &&
            absolute.first + window.width > 0 &&
            absolute.second + window.height > 0
    }

    private fun passiveButtonGrabConflicts(left: XPassiveButtonGrab, right: XPassiveButtonGrab): Boolean =
        left.windowId == right.windowId && concreteButtonGrabOverlap(left, right)

    private fun concreteButtonGrabOverlap(left: XPassiveButtonGrab, right: XPassiveButtonGrab): Boolean {
        val buttons = intersectRanges(buttonRange(left.button), buttonRange(right.button)) ?: return false
        val modifiers = intersectRanges(modifierRange(left.modifiers), modifierRange(right.modifiers)) ?: return false
        for (button in buttons) {
            for (modifier in modifiers) {
                if (!buttonGrabCombinationReleased(left, button, modifier) && !buttonGrabCombinationReleased(right, button, modifier)) return true
            }
        }
        return false
    }

    private fun buttonGrabReleasedCombinations(grab: XPassiveButtonGrab, button: Int, modifiers: Int): Set<XPassiveButtonGrabCombination>? {
        val releasedButton = intersectButtonPattern(grab.button, button) ?: return null
        val releasedModifiers = intersectModifierPattern(grab.modifiers, modifiers) ?: return null
        return addButtonGrabRelease(
            grab.releasedCombinations,
            XPassiveButtonGrabCombination(releasedButton, releasedModifiers),
        )
    }

    private fun buttonGrabFullyReleased(grab: XPassiveButtonGrab, releasedCombinations: Set<XPassiveButtonGrabCombination>): Boolean {
        for (button in buttonRange(grab.button)) {
            for (modifier in modifierRange(grab.modifiers)) {
                if (!buttonGrabCombinationReleased(releasedCombinations, button, modifier)) return false
            }
        }
        return true
    }

    private fun buttonGrabCombinationReleased(grab: XPassiveButtonGrab, button: Int, modifiers: Int): Boolean =
        buttonGrabCombinationReleased(grab.releasedCombinations, button, modifiers)

    private fun matchingPassiveButtonGrab(path: List<XWindow>, button: Int, modifiers: Int): XPassiveButtonGrab? {
        for (window in path.asReversed()) {
            passiveButtonGrabs.firstOrNull { grab ->
                grab.windowId == window.id &&
                    buttonPatternCovers(grab.button, button) &&
                    modifierPatternCovers(grab.modifiers, modifiers) &&
                    !buttonGrabCombinationReleased(grab, button, modifiers)
            }?.let { grab ->
                return if (grab.confineTo?.let { windowIsViewable(it) && windowIntersectsRootBounds(it) } != false) {
                    grab
                } else {
                    null
                }
            }
        }
        return null
    }

    private fun buttonGrabCombinationReleased(
        releasedCombinations: Set<XPassiveButtonGrabCombination>,
        button: Int,
        modifiers: Int,
    ): Boolean =
        releasedCombinations.any {
            buttonPatternCovers(it.button, button) && modifierPatternCovers(it.modifiers, modifiers)
        }

    private fun addButtonGrabRelease(
        releasedCombinations: Set<XPassiveButtonGrabCombination>,
        release: XPassiveButtonGrabCombination,
    ): Set<XPassiveButtonGrabCombination> {
        if (releasedCombinations.any { buttonGrabPatternCovers(it, release) }) return releasedCombinations
        return releasedCombinations
            .filterNot { buttonGrabPatternCovers(release, it) }
            .toSet() + release
    }

    private fun buttonGrabPatternCovers(left: XPassiveButtonGrabCombination, right: XPassiveButtonGrabCombination): Boolean =
        buttonPatternCovers(left.button, right.button) && modifierPatternCovers(left.modifiers, right.modifiers)

    private fun intersectButtonPattern(left: Int, right: Int): Int? =
        when {
            left == right -> left
            left == AnyButton -> right
            right == AnyButton -> left
            else -> null
        }

    private fun buttonPatternCovers(pattern: Int, concreteButton: Int): Boolean =
        pattern == AnyButton || pattern == concreteButton

    private fun buttonRange(button: Int): IntRange =
        if (button == AnyButton) 1..255 else button..button

    private fun passiveKeyGrabConflicts(left: XPassiveKeyGrab, right: XPassiveKeyGrab): Boolean =
        left.windowId == right.windowId && concreteKeyGrabOverlap(left, right)

    private fun concreteKeyGrabOverlap(left: XPassiveKeyGrab, right: XPassiveKeyGrab): Boolean {
        val keys = intersectRanges(keyRange(left.key), keyRange(right.key)) ?: return false
        val modifiers = intersectRanges(modifierRange(left.modifiers), modifierRange(right.modifiers)) ?: return false
        for (key in keys) {
            for (modifier in modifiers) {
                if (!keyGrabCombinationReleased(left, key, modifier) && !keyGrabCombinationReleased(right, key, modifier)) return true
            }
        }
        return false
    }

    private fun keyGrabReleasedCombinations(grab: XPassiveKeyGrab, key: Int, modifiers: Int): Set<XPassiveKeyGrabCombination>? {
        val releasedKey = intersectKeyPattern(grab.key, key) ?: return null
        val releasedModifiers = intersectModifierPattern(grab.modifiers, modifiers) ?: return null
        return addKeyGrabRelease(
            grab.releasedCombinations,
            XPassiveKeyGrabCombination(releasedKey, releasedModifiers),
        )
    }

    private fun keyGrabFullyReleased(grab: XPassiveKeyGrab, releasedCombinations: Set<XPassiveKeyGrabCombination>): Boolean {
        for (key in keyRange(grab.key)) {
            for (modifier in modifierRange(grab.modifiers)) {
                if (!keyGrabCombinationReleased(releasedCombinations, key, modifier)) return false
            }
        }
        return true
    }

    private fun keyGrabCombinationReleased(grab: XPassiveKeyGrab, key: Int, modifiers: Int): Boolean =
        keyGrabCombinationReleased(grab.releasedCombinations, key, modifiers)

    private fun keyGrabCombinationReleased(
        releasedCombinations: Set<XPassiveKeyGrabCombination>,
        key: Int,
        modifiers: Int,
    ): Boolean =
        releasedCombinations.any {
            keyPatternCovers(it.key, key) && modifierPatternCovers(it.modifiers, modifiers)
        }

    private fun matchingPassiveKeyGrab(path: List<XWindow>, key: Int, modifiers: Int): XPassiveKeyGrab? {
        for (window in path.asReversed()) {
            passiveKeyGrabs.firstOrNull { grab ->
                grab.windowId == window.id &&
                    keyPatternCovers(grab.key, key) &&
                    modifierPatternCovers(grab.modifiers, modifiers) &&
                    !keyGrabCombinationReleased(grab, key, modifiers)
            }?.let { grab ->
                return if (windowIsViewable(grab.windowId)) grab else null
            }
        }
        return null
    }

    private fun addKeyGrabRelease(
        releasedCombinations: Set<XPassiveKeyGrabCombination>,
        release: XPassiveKeyGrabCombination,
    ): Set<XPassiveKeyGrabCombination> {
        if (releasedCombinations.any { keyGrabPatternCovers(it, release) }) return releasedCombinations
        return releasedCombinations
            .filterNot { keyGrabPatternCovers(release, it) }
            .toSet() + release
    }

    private fun keyGrabPatternCovers(left: XPassiveKeyGrabCombination, right: XPassiveKeyGrabCombination): Boolean =
        keyPatternCovers(left.key, right.key) && modifierPatternCovers(left.modifiers, right.modifiers)

    private fun intersectKeyPattern(left: Int, right: Int): Int? =
        when {
            left == right -> left
            left == AnyKey -> right
            right == AnyKey -> left
            else -> null
        }

    private fun intersectModifierPattern(left: Int, right: Int): Int? =
        when {
            left == right -> left
            left == AnyModifier -> right
            right == AnyModifier -> left
            else -> null
        }

    private fun keyPatternCovers(pattern: Int, concreteKey: Int): Boolean =
        pattern == AnyKey || pattern == concreteKey

    private fun modifierPatternCovers(pattern: Int, concreteModifiers: Int): Boolean =
        pattern == AnyModifier || pattern == concreteModifiers

    private fun keyRange(key: Int): IntRange =
        if (key == AnyKey) XKeyboard.MinKeycode..XKeyboard.MaxKeycode else key..key

    private fun modifierRange(modifiers: Int): IntRange =
        if (modifiers == AnyModifier) 0..KeyModifierMask else modifiers..modifiers

    private fun intersectRanges(left: IntRange, right: IntRange): IntRange? {
        val start = maxOf(left.first, right.first)
        val endInclusive = minOf(left.last, right.last)
        return if (start <= endInclusive) start..endInclusive else null
    }

    fun processWhenServerGrabAllows(owner: XEventSink, beforeProcess: () -> Unit = {}, process: () -> Unit) {
        while (true) {
            serverGrabLock.withLock {
                while (!owner.isKilled() && serverGrabOwner != null && serverGrabOwner != owner && owner !in serverGrabImperviousClients) {
                    serverGrabReleased.await()
                }
            }

            if (owner.isKilled()) return
            beforeProcess()
            requestProcessingLock.lock()
            try {
                val allowed = serverGrabLock.withLock {
                    owner.isKilled() || serverGrabOwner == null || serverGrabOwner == owner || owner in serverGrabImperviousClients
                }
                if (allowed) {
                    if (!owner.isKilled()) {
                        process()
                    }
                    return
                }
            } finally {
                requestProcessingLock.unlock()
            }
        }
    }

    fun setServerGrabImpervious(owner: XEventSink, impervious: Boolean) {
        serverGrabLock.withLock {
            if (impervious) {
                serverGrabImperviousClients += owner
                serverGrabReleased.signalAll()
            } else {
                serverGrabImperviousClients -= owner
            }
        }
    }

    fun grabServer(owner: XEventSink) {
        serverGrabLock.withLock {
            serverGrabOwner = owner
        }
    }

    fun ungrabServer(owner: XEventSink) {
        serverGrabLock.withLock {
            if (serverGrabOwner == owner) {
                serverGrabOwner = null
                serverGrabReleased.signalAll()
            }
        }
    }

    fun releaseServerGrab(owner: XEventSink) {
        serverGrabLock.withLock {
            if (serverGrabOwner == owner) {
                serverGrabOwner = null
                serverGrabReleased.signalAll()
            }
        }
    }

    fun signalClientKilled(owner: XEventSink) {
        serverGrabLock.withLock {
            if (serverGrabOwner == owner) {
                serverGrabOwner = null
            }
            serverGrabImperviousClients -= owner
            serverGrabReleased.signalAll()
        }
        synchronized(this) {
            notifySyncWaiters()
        }
    }

    fun serverGrabbed(): Boolean =
        serverGrabLock.withLock {
            serverGrabOwner != null
        }

    @Synchronized
    fun accessControlEnabled(): Boolean = accessControlEnabled

    @Synchronized
    fun setAccessControlEnabled(enabled: Boolean) {
        accessControlEnabled = enabled
    }

    @Synchronized
    fun accessHosts(): List<XAccessHost> = accessHosts.toList()

    @Synchronized
    fun insertAccessHost(host: XAccessHost) {
        accessHosts += host
    }

    @Synchronized
    fun deleteAccessHost(host: XAccessHost) {
        accessHosts -= host
    }

    @Synchronized
    fun acceptsHostAddress(address: ByteArray): Boolean {
        if (!accessControlEnabled) return true
        val family = when (address.size) {
            4 -> XAccessHost.FamilyInternet
            16 -> XAccessHost.FamilyInternetV6
            else -> return false
        }
        val host = XAccessHost(
            family = family,
            address = address.map { it.toInt() and 0xff },
        )
        return host in accessHosts
    }

    @Synchronized
    fun registerEventSink(sink: XEventSink) {
        eventSinks.putIfAbsent(sink, linkedMapOf())
    }

    @Synchronized
    fun unregisterEventSink(sink: XEventSink): XEventSinkRemoval {
        setServerGrabImpervious(sink, impervious = false)
        val xfixesSelectionNotifyDispatches = selectionOwners
            .filterValues { it.sink == sink }
            .flatMap { (selection) ->
                xfixesSelectionOwnerLostDispatches(
                    selection = selection,
                    subtype = XFixes.SelectionClientCloseNotify,
                )
            }
        eventSinks.remove(sink)
        selectionOwners.entries.removeIf { it.value.sink == sink }
        xfixesSelectionInputs.remove(sink)
        xfixesCursorInputs.remove(sink)
        shapeInputs.remove(sink)
        randrInputs.remove(sink)
        screenSaverInputs.remove(sink)
        if (screenSaverAttributes?.owner == sink) screenSaverAttributes = null
        screenSaverSuspensions.remove(sink)
        syncPriorities.remove(sink)
        windowOwners.entries.removeIf { it.value == sink }
        saveSets.remove(sink)
        val xfixesCursorNotifyDispatches = releaseInputGrabs(sink)
        releaseServerGrab(sink)
        return XEventSinkRemoval(
            xfixesSelectionNotifyDispatches = xfixesSelectionNotifyDispatches,
            xfixesCursorNotifyDispatches = xfixesCursorNotifyDispatches,
        )
    }

    @Synchronized
    fun canSelectEvents(sink: XEventSink, windowId: Int, eventMask: Int): Boolean {
        if (!windows.containsKey(windowId)) return false
        val exclusiveMask = eventMask and (XEventMasks.ButtonPress or XEventMasks.ResizeRedirect or XEventMasks.SubstructureRedirect)
        if (exclusiveMask == 0) return true
        return eventSinks.none { (otherSink, selections) ->
            otherSink != sink && (selections[windowId]?.let { it and exclusiveMask } ?: 0) != 0
        }
    }

    @Synchronized
    fun selectEvents(sink: XEventSink, windowId: Int, eventMask: Int) {
        if (!canSelectEvents(sink, windowId, eventMask)) return
        val selections = eventSinks.getOrPut(sink) { linkedMapOf() }
        if (eventMask == 0) {
            selections.remove(windowId)
        } else {
            selections[windowId] = eventMask
        }
    }

    fun pointerButton(x: Int, y: Int, button: Int, pressed: Boolean): XPointerDispatch {
        val deliveries = mutableListOf<Pair<XEventSink, XPointerEvent>>()
        val xfixesCursorNotifyDispatches = mutableListOf<XXFixesCursorNotifyDispatch>()
        val targetId: Int?
        synchronized(this) {
            val previousCursor = displayedCursorIdentity()
            val previousX = pointerX
            val previousY = pointerY
            pointerX = x.coerceIn(0, width - 1)
            pointerY = y.coerceIn(0, height - 1)
            val previousState = pointerMask()
            val logicalButton = pointerLogicalButton(button)
            val type = if (pressed) XPointerEventType.ButtonPress else XPointerEventType.ButtonRelease
            val mask = XEventMasks.forPointerType(type)
            targetId = windowAt(pointerX, pointerY)?.id
            val path = targetId?.let { windowPathToRoot(it) }.orEmpty()
            val absoluteById = windows.values.associate { window -> window.id to absolutePosition(window) }
            val childByAncestor = childByAncestor(path)
            val time = inputTime++
            if (pointerX != previousX || pointerY != previousY) {
                recordMotionHistory(time, pointerX, pointerY)
            }

            if (pressed && logicalButton != 0 && activePointerGrab == null && pressedLogicalButtons.isEmpty()) {
                matchingPassiveButtonGrab(path, logicalButton, previousState and KeyModifierMask)?.let { grab ->
                    activePointerGrab = XInputGrab(
                        owner = grab.owner,
                        kind = "pointer",
                        windowId = grab.windowId,
                        ownerEvents = grab.ownerEvents,
                        eventMask = grab.eventMask,
                        pointerMode = grab.pointerMode,
                        keyboardMode = grab.keyboardMode,
                        confineTo = grab.confineTo,
                        cursor = grab.cursor,
                        time = time,
                        activatedByPassiveGrab = true,
                    )
                    lastPointerGrabTime = time
                }
            }

            if (logicalButton != 0) {
                val grab = activePointerGrab
                if (grab != null) {
                    val ownerSelections = eventSinks[grab.owner]
                    val ownerEventWindow = if (grab.ownerEvents) {
                        path.firstOrNull { window -> ((ownerSelections?.get(window.id) ?: 0) and mask) != 0 }
                    } else {
                        null
                    }
                    if (ownerEventWindow != null || (grab.eventMask and mask) != 0) {
                        val eventWindowId = ownerEventWindow?.id ?: grab.windowId
                        val absolute = windows[eventWindowId]?.let { absoluteById[it.id] } ?: (0 to 0)
                        deliveries += grab.owner to XPointerEvent(
                            type = type,
                            button = logicalButton,
                            rootX = pointerX,
                            rootY = pointerY,
                            eventWindowId = eventWindowId,
                            childWindowId = childByAncestor[eventWindowId] ?: 0,
                            eventX = pointerX - absolute.first,
                            eventY = pointerY - absolute.second,
                            state = previousState,
                            time = time,
                        )
                    }
                } else {
                    for ((sink, selections) in eventSinks) {
                        for (window in path) {
                            val selectedMask = selections[window.id] ?: continue
                            if ((selectedMask and mask) == 0) continue
                            val absolute = absoluteById.getValue(window.id)
                            deliveries += sink to XPointerEvent(
                                type = type,
                                button = logicalButton,
                                rootX = pointerX,
                                rootY = pointerY,
                                eventWindowId = window.id,
                                childWindowId = childByAncestor[window.id] ?: 0,
                                eventX = pointerX - absolute.first,
                                eventY = pointerY - absolute.second,
                                state = previousState,
                                time = time,
                            )
                        }
                    }
                }
            }

            if (logicalButton != 0) {
                val buttonMask = buttonMask(logicalButton)
                pointerState = if (pressed) {
                    pressedLogicalButtons += logicalButton
                    pointerState or buttonMask
                } else {
                    pressedLogicalButtons -= logicalButton
                    pointerState and buttonMask.inv()
                }
            }
            if (!pressed && activePointerGrab?.activatedByPassiveGrab == true && pressedLogicalButtons.isEmpty()) {
                activePointerGrab = null
            }
            xfixesCursorNotifyDispatches += xfixesCursorNotifyDispatchesIfChanged(previousCursor, timestamp = time)
            if (targetId != null) focusWindowId = targetId
        }

        for ((sink, event) in deliveries) {
            sink.sendPointerEvent(event)
        }
        sendXFixesCursorNotify(xfixesCursorNotifyDispatches)
        return XPointerDispatch(targetWindowId = targetId, deliveredEvents = deliveries.size)
    }

    fun keyboardKey(keycode: Int, modifiers: Int, pressed: Boolean): XKeyDispatch {
        val deliveries = mutableListOf<Pair<XEventSink, XKeyEvent>>()
        val targetId: Int?
        synchronized(this) {
            targetId = sendEventInputFocusWindowId()
            val type = if (pressed) XKeyEventType.KeyPress else XKeyEventType.KeyRelease
            val mask = XEventMasks.forKeyType(type)
            val path = targetId?.let { windowPathToRoot(it) }.orEmpty()
            val selectionPath = keyEventSelectionPath(path)
            val absoluteById = windows.values.associate { window -> window.id to absolutePosition(window) }
            val pointerPath = (pointerWindowId() ?: X11Ids.RootWindow).let { windowPathToRoot(it) }
            val childByPointerAncestor = childByAncestor(pointerPath)
            val modifierState = modifiers and KeyModifierMask
            val state = pointerState or modifierState
            keyboardModifierState = modifierState
            if (pressed) {
                pressedKeycodes += keycode
            } else {
                pressedKeycodes -= keycode
            }
            val time = inputTime++
            val normalSelection = firstKeyEventSelection(selectionPath, mask)

            if (pressed && targetId != null && activeKeyboardGrab == null) {
                matchingPassiveKeyGrab(path, keycode, modifiers and KeyModifierMask)?.let { grab ->
                    activeKeyboardGrab = XInputGrab(
                        owner = grab.owner,
                        kind = "keyboard",
                        windowId = grab.windowId,
                        ownerEvents = grab.ownerEvents,
                        eventMask = 0,
                        pointerMode = grab.pointerMode,
                        keyboardMode = grab.keyboardMode,
                        confineTo = null,
                        cursor = null,
                        time = time,
                        activatedByPassiveGrab = true,
                        passiveGrabKey = keycode,
                    )
                    lastKeyboardGrabTime = time
                }
            }

            val grab = activeKeyboardGrab
            if (grab != null) {
                val ownerEventWindow = if (grab.ownerEvents) {
                    normalSelection?.first?.takeIf { normalSelection.second.contains(grab.owner) }
                } else {
                    null
                }
                val eventWindowId = ownerEventWindow?.id ?: grab.windowId
                val absolute = windows[eventWindowId]?.let { absoluteById[it.id] } ?: (0 to 0)
                deliveries += grab.owner to XKeyEvent(
                    type = type,
                    keycode = keycode,
                    rootX = pointerX,
                    rootY = pointerY,
                    eventWindowId = eventWindowId,
                    childWindowId = childByPointerAncestor[eventWindowId] ?: 0,
                    eventX = pointerX - absolute.first,
                    eventY = pointerY - absolute.second,
                    state = state,
                    time = time,
                )
            } else if (normalSelection != null) {
                val window = normalSelection.first
                val absolute = absoluteById.getValue(window.id)
                for (sink in normalSelection.second) {
                    deliveries += sink to XKeyEvent(
                        type = type,
                        keycode = keycode,
                        rootX = pointerX,
                        rootY = pointerY,
                        eventWindowId = window.id,
                        childWindowId = childByPointerAncestor[window.id] ?: 0,
                        eventX = pointerX - absolute.first,
                        eventY = pointerY - absolute.second,
                        state = state,
                        time = time,
                    )
                }
            }

            if (!pressed && activeKeyboardGrab?.activatedByPassiveGrab == true && activeKeyboardGrab?.passiveGrabKey == keycode) {
                activeKeyboardGrab = null
            }
        }

        for ((sink, event) in deliveries) {
            sink.sendKeyEvent(event)
        }
        return XKeyDispatch(targetWindowId = targetId, deliveredEvents = deliveries.size)
    }

    private fun firstKeyEventSelection(path: List<XWindow>, mask: Int): Pair<XWindow, List<XEventSink>>? {
        var remainingMask = mask
        for (window in path) {
            val sinks = eventSelectionsForWindow(window.id, remainingMask)
            if (sinks.isNotEmpty()) return window to sinks
            remainingMask = remainingMask and window.doNotPropagateMask.inv()
            if (remainingMask == 0) return null
        }
        return null
    }

    private fun keyEventSelectionPath(path: List<XWindow>): List<XWindow> {
        if (focusWindowId in 0..1) return path
        val focusIndex = path.indexOfFirst { it.id == focusWindowId }
        return if (focusIndex >= 0) path.take(focusIndex + 1) else path
    }

    fun warpPointer(
        sourceWindowId: Int,
        destinationWindowId: Int,
        sourceX: Int,
        sourceY: Int,
        sourceWidth: Int,
        sourceHeight: Int,
        destinationX: Int,
        destinationY: Int,
    ): XPointerDispatch {
        val deliveries = mutableListOf<Pair<XEventSink, XPointerEvent>>()
        val xfixesCursorNotifyDispatches = mutableListOf<XXFixesCursorNotifyDispatch>()
        val targetId: Int?
        synchronized(this) {
            val previousCursor = displayedCursorIdentity()
            val sourceWindow = sourceWindowId.takeIf { it != 0 }?.let { windows[it] }
            if (sourceWindow != null && !sourceWindowContainsPointer(sourceWindow, sourceX, sourceY, sourceWidth, sourceHeight)) {
                return XPointerDispatch(targetWindowId = windowAt(pointerX, pointerY)?.id, deliveredEvents = 0)
            }

            val rawPosition = if (destinationWindowId == 0) {
                pointerX + destinationX to pointerY + destinationY
            } else {
                val destinationWindow = windows[destinationWindowId] ?: return XPointerDispatch(
                    targetWindowId = windowAt(pointerX, pointerY)?.id,
                    deliveredEvents = 0,
                )
                val absolute = absolutePosition(destinationWindow)
                absolute.first + destinationX to absolute.second + destinationY
            }
            val (newX, newY) = confinedPointerPosition(rawPosition.first, rawPosition.second)
            if (newX == pointerX && newY == pointerY) {
                return XPointerDispatch(targetWindowId = windowAt(pointerX, pointerY)?.id, deliveredEvents = 0)
            }

            pointerX = newX
            pointerY = newY
            targetId = windowAt(pointerX, pointerY)?.id
            val path = targetId?.let { windowPathToRoot(it) }.orEmpty()
            val absoluteById = windows.values.associate { window -> window.id to absolutePosition(window) }
            val childByAncestor = childByAncestor(path)
            val time = inputTime++
            recordMotionHistory(time, pointerX, pointerY)

            val grab = activePointerGrab
            if (grab != null) {
                val ownerSelections = eventSinks[grab.owner]
                val ownerEventWindow = if (grab.ownerEvents) {
                    path.firstOrNull { window -> ((ownerSelections?.get(window.id) ?: 0) and XEventMasks.PointerMotion) != 0 }
                } else {
                    null
                }
                if (ownerEventWindow != null || (grab.eventMask and XEventMasks.PointerMotion) != 0) {
                    val eventWindowId = ownerEventWindow?.id ?: grab.windowId
                    val absolute = windows[eventWindowId]?.let { absoluteById[it.id] } ?: (0 to 0)
                    deliveries += grab.owner to XPointerEvent(
                        type = XPointerEventType.MotionNotify,
                        button = 0,
                        rootX = pointerX,
                        rootY = pointerY,
                        eventWindowId = eventWindowId,
                        childWindowId = childByAncestor[eventWindowId] ?: 0,
                        eventX = pointerX - absolute.first,
                        eventY = pointerY - absolute.second,
                        state = pointerMask(),
                        time = time,
                    )
                }
            } else {
                for ((sink, selections) in eventSinks) {
                    for (window in path) {
                        val selectedMask = selections[window.id] ?: continue
                        if ((selectedMask and XEventMasks.PointerMotion) == 0) continue
                        val absolute = absoluteById.getValue(window.id)
                        deliveries += sink to XPointerEvent(
                            type = XPointerEventType.MotionNotify,
                            button = 0,
                            rootX = pointerX,
                            rootY = pointerY,
                            eventWindowId = window.id,
                            childWindowId = childByAncestor[window.id] ?: 0,
                            eventX = pointerX - absolute.first,
                            eventY = pointerY - absolute.second,
                            state = pointerMask(),
                            time = time,
                        )
                    }
                }
            }
            xfixesCursorNotifyDispatches += xfixesCursorNotifyDispatchesIfChanged(previousCursor, timestamp = time)
        }

        for ((sink, event) in deliveries) {
            sink.sendPointerEvent(event)
        }
        sendXFixesCursorNotify(xfixesCursorNotifyDispatches)
        return XPointerDispatch(targetWindowId = targetId, deliveredEvents = deliveries.size)
    }

    @Synchronized
    fun pointerWindowId(): Int? = windowAt(pointerX, pointerY)?.id

    @Synchronized
    fun queryPointer(windowId: Int): XPointerQuery? {
        val window = windows[windowId] ?: return null
        val absolute = absolutePosition(window)
        val pointerWindow = windowAt(pointerX, pointerY)
        val pointerPath = pointerWindow?.let { windowPathToRoot(it.id) }.orEmpty()
        val childWindowId = pointerPath.firstOrNull { it.parentId == windowId }?.id ?: 0
        return XPointerQuery(
            childWindowId = childWindowId,
            rootX = pointerX,
            rootY = pointerY,
            windowX = pointerX - absolute.first,
            windowY = pointerY - absolute.second,
            mask = pointerMask(),
        )
    }

    @Synchronized
    fun translateCoordinates(sourceWindowId: Int, destinationWindowId: Int, sourceX: Int, sourceY: Int): XTranslatedCoordinates? {
        val sourceWindow = windows[sourceWindowId] ?: return null
        val destinationWindow = windows[destinationWindowId] ?: return null
        val sourceAbsolute = absolutePosition(sourceWindow)
        val destinationAbsolute = absolutePosition(destinationWindow)
        val rootX = sourceAbsolute.first + sourceX
        val rootY = sourceAbsolute.second + sourceY
        val childWindowId = mappedChildContaining(destinationWindowId, rootX, rootY)?.id ?: 0
        return XTranslatedCoordinates(
            childWindowId = childWindowId,
            destinationX = rootX - destinationAbsolute.first,
            destinationY = rootY - destinationAbsolute.second,
        )
    }

    @Synchronized
    fun sendEventInputFocusWindowId(): Int? {
        val pointerWindowId = pointerWindowId()
        return when (focusWindowId) {
            0 -> null
            1 -> pointerWindowId ?: X11Ids.RootWindow
            else -> if (pointerWindowId != null && windowIsAncestorOrSelf(focusWindowId, pointerWindowId)) {
                pointerWindowId
            } else {
                focusWindowId
            }
        }
    }

    @Synchronized
    fun sendEventSinks(destination: Int, eventMask: Int, propagate: Boolean, inputFocusDestination: Boolean): List<XEventSink> {
        if (!windows.containsKey(destination)) return emptyList()
        if (eventMask == 0) return windowOwners[destination]?.let { listOf(it) }.orEmpty()
        val targets = eventSelectionsForWindow(destination, eventMask)
        if (targets.isNotEmpty() || !propagate) return targets

        var remainingMask = eventMask and windows[destination]!!.doNotPropagateMask.inv()
        if (remainingMask == 0) return emptyList()
        var currentId = destination
        var parentId = windows[currentId]?.parentId ?: return emptyList()
        val visited = mutableSetOf(destination)
        while (parentId != 0 && visited.add(parentId)) {
            val ancestorTargets = eventSelectionsForWindow(parentId, remainingMask)
            if (ancestorTargets.isNotEmpty()) {
                if (inputFocusDestination && focusWindowId !in 0..1 && parentId != focusWindowId && windowIsAncestorOrSelf(parentId, focusWindowId)) {
                    return emptyList()
                }
                return ancestorTargets
            }
            val parent = windows[parentId] ?: return emptyList()
            remainingMask = remainingMask and parent.doNotPropagateMask.inv()
            if (remainingMask == 0) return emptyList()
            currentId = parentId
            parentId = windows[currentId]?.parentId ?: return emptyList()
        }
        return emptyList()
    }

    @Synchronized
    fun exposureSinks(windowId: Int): List<XEventSink> =
        eventSelectionsForWindow(windowId, XEventMasks.Exposure)

    @Synchronized
    fun recordInputOperation(
        kind: String,
        x: Int,
        y: Int,
        button: String,
        targetWindowId: Int?,
        deliveredEvents: Int,
    ) {
        inputOperations += XInputOperation(
            id = nextInputOperationId++,
            kind = kind,
            x = x,
            y = y,
            button = button,
            targetWindowId = targetWindowId,
            deliveredEvents = deliveredEvents,
        )
        if (inputOperations.size > MaxInputOperations) {
            inputOperations.removeAt(0)
        }
    }

    @Synchronized
    fun motionEvents(windowId: Int, start: Int, stop: Int): List<XMotionHistoryEntry> {
        val window = windows[windowId] ?: return emptyList()
        val currentTime = currentServerTime(0)
        val startTime = if (start == 0) currentTime else start
        val stopTime = if (stop == 0 || Integer.compareUnsigned(stop, currentTime) > 0) currentTime else stop
        if (
            Integer.compareUnsigned(startTime, stopTime) > 0 ||
            Integer.compareUnsigned(startTime, currentTime) > 0
        ) {
            return emptyList()
        }

        val absolute = absolutePosition(window)
        val left = absolute.first - window.borderWidth
        val top = absolute.second - window.borderWidth
        val right = absolute.first + window.width + window.borderWidth
        val bottom = absolute.second + window.height + window.borderWidth
        return motionHistory
            .asSequence()
            .filter { event ->
                Integer.compareUnsigned(event.time, startTime) >= 0 &&
                    Integer.compareUnsigned(event.time, stopTime) <= 0 &&
                    event.rootX >= left &&
                    event.rootY >= top &&
                    event.rootX < right &&
                    event.rootY < bottom
            }
            .map { event ->
                event.copy(
                    x = event.rootX - absolute.first,
                    y = event.rootY - absolute.second,
                )
            }
            .toList()
    }

    @Synchronized
    fun recordInputControlOperation(operation: String, mode: Int, time: Int) {
        inputControlOperations += XInputControlOperation(
            id = nextInputControlOperationId++,
            operation = operation,
            mode = mode,
            time = time,
        )
        if (inputControlOperations.size > MaxInputOperations) {
            inputControlOperations.removeAt(0)
        }
    }

    @Synchronized
    fun configureWindow(
        id: Int,
        x: Int? = null,
        y: Int? = null,
        width: Int? = null,
        height: Int? = null,
        borderWidth: Int? = null,
        siblingId: Int? = null,
        stackMode: Int? = null,
    ): XConfigureWindowResult? {
        val window = windows[id] ?: return null
        val oldX = window.x
        val oldY = window.y
        val oldWidth = window.width
        val oldHeight = window.height
        val oldBorderWidth = window.borderWidth
        x?.let { window.x = it }
        y?.let { window.y = it }
        width?.let { window.width = it }
        height?.let { window.height = it }
        borderWidth?.let { window.borderWidth = it }
        val geometryChanged = oldX != window.x || oldY != window.y || oldWidth != window.width || oldHeight != window.height ||
            oldBorderWidth != window.borderWidth
        val sizeChanged = oldWidth != window.width || oldHeight != window.height
        if (sizeChanged) {
            window.framebuffer.resize(window.width, window.height, window.backgroundPixel)
        }
        val stackChanged = if (stackMode != null) {
            restackConfiguredWindow(window, siblingId, stackMode)
        } else {
            false
        }
        val changed = geometryChanged || stackChanged
        if (changed) releaseActiveGrabsForVisibilityChanges()
        return XConfigureWindowResult(
            window = window,
            changed = changed,
            sizeChanged = sizeChanged,
            aboveSiblingId = siblingBelow(window),
        )
    }

    @Synchronized
    fun updateWindowAttributes(
        id: Int,
        backgroundPixel: Int? = null,
        backgroundPixmapId: Int? = null,
        borderPixel: Int? = null,
        borderPixmapId: Int? = null,
        borderPixmapIdChanged: Boolean = false,
        bitGravity: Int? = null,
        winGravity: Int? = null,
        backingStore: Int? = null,
        backingPlanes: Int? = null,
        backingPixel: Int? = null,
        overrideRedirect: Boolean? = null,
        saveUnder: Boolean? = null,
        doNotPropagateMask: Int? = null,
        colormapId: Int? = null,
        colormapIdChanged: Boolean = false,
        cursorId: Int? = null,
        cursorImage: XCursorImage? = null,
        cursorGeneration: Long? = null,
        cursorName: XCursorName? = null,
        cursorIdChanged: Boolean = false,
    ): XWindow? {
        val window = windows[id] ?: return null
        backgroundPixel?.let {
            window.backgroundPixel = it
            window.backgroundPixmapId = null
        }
        if (backgroundPixmapId != null) {
            window.backgroundPixmapId = backgroundPixmapId
        }
        borderPixel?.let {
            window.borderPixel = it
        }
        if (borderPixmapIdChanged) {
            window.borderPixmapId = borderPixmapId
        }
        bitGravity?.let {
            window.bitGravity = it
        }
        winGravity?.let {
            window.winGravity = it
        }
        backingStore?.let {
            window.backingStore = it
        }
        backingPlanes?.let {
            window.backingPlanes = it
        }
        backingPixel?.let {
            window.backingPixel = it
        }
        overrideRedirect?.let {
            window.overrideRedirect = it
        }
        saveUnder?.let {
            window.saveUnder = it
        }
        doNotPropagateMask?.let {
            window.doNotPropagateMask = it
        }
        if (colormapIdChanged) {
            window.colormapId = colormapId
        }
        if (cursorIdChanged) {
            window.cursorId = cursorId
            window.cursorImage = cursorImage
            window.cursorGeneration = cursorGeneration
            window.cursorName = cursorName
        }
        return window
    }

    @Synchronized
    fun updateWindowCursor(id: Int, cursorId: Int?): List<XXFixesCursorNotifyDispatch> {
        val window = windows[id] ?: return emptyList()
        val previousCursor = displayedCursorIdentity()
        val cursor = cursorId?.let { cursors[it] }
        window.cursorId = cursorId
        window.cursorImage = cursor?.image
        window.cursorGeneration = cursor?.generation
        window.cursorName = cursor?.name
        return xfixesCursorNotifyDispatchesIfChanged(previousCursor)
    }

    @Synchronized
    fun paintWindowBackground(windowId: Int, rectangle: XRectangleCommand? = null): Boolean {
        val window = windows[windowId] ?: return false
        val target = rectangle ?: XRectangleCommand(0, 0, window.width, window.height)
        val effectiveClip = effectiveDrawableClip(windowId, listOf(target))
        return when (val background = resolveWindowBackground(window)) {
            XResolvedWindowBackground.None -> false

            is XResolvedWindowBackground.Pixmap -> window.framebuffer.fillPattern(
                x = target.x,
                y = target.y,
                width = target.width,
                height = target.height,
                patternWidth = background.pixmap.width,
                patternHeight = background.pixmap.height,
                patternXOrigin = background.xOrigin,
                patternYOrigin = background.yOrigin,
                clipRectangles = effectiveClip,
            ) { x, y -> background.pixmap.framebuffer.pixelAt(x, y) }

            is XResolvedWindowBackground.Pixel -> window.framebuffer.fill(
                target.x,
                target.y,
                target.width,
                target.height,
                background.pixel,
                clipRectangles = effectiveClip,
            )
        }
    }

    private fun resolveWindowBackground(window: XWindow): XResolvedWindowBackground {
        val backgroundPixmapId = window.backgroundPixmapId
        if (backgroundPixmapId == XWindowBackground.None) return XResolvedWindowBackground.None
        if (backgroundPixmapId == XWindowBackground.ParentRelative) {
            val parent = windows[window.parentId] ?: return XResolvedWindowBackground.Pixel(window.backgroundPixel)
            return when (val parentBackground = resolveWindowBackground(parent)) {
                XResolvedWindowBackground.None -> XResolvedWindowBackground.None

                is XResolvedWindowBackground.Pixmap -> parentBackground.copy(
                    xOrigin = parentBackground.xOrigin - window.x,
                    yOrigin = parentBackground.yOrigin - window.y,
                )

                is XResolvedWindowBackground.Pixel -> parentBackground
            }
        }
        val backgroundPixmap = backgroundPixmapId?.let { pixmaps[it] }
        if (backgroundPixmap != null) {
            return XResolvedWindowBackground.Pixmap(backgroundPixmap, xOrigin = 0, yOrigin = 0)
        }
        return XResolvedWindowBackground.Pixel(window.backgroundPixel)
    }

    @Synchronized
    fun snapshot(): XScreenSnapshot {
        val windowSnapshots = windows.values.mapIndexed { index, window ->
            val absolute = absolutePosition(window)
            val visible = visibleBounds(window, absolute.first, absolute.second)
            XWindowSnapshot(
                id = window.id,
                parentId = window.parentId,
                x = absolute.first,
                y = absolute.second,
                localX = window.x,
                localY = window.y,
                width = window.width,
                height = window.height,
                borderWidth = window.borderWidth,
                mapped = window.mapped,
                focused = window.id == focusWindowId,
                stackingIndex = index,
                label = window.label(),
                visibleX = visible?.x ?: 0,
                visibleY = visible?.y ?: 0,
                visibleWidth = visible?.width ?: 0,
                visibleHeight = visible?.height ?: 0,
                backgroundPixel = window.backgroundPixel,
                backgroundPixmapId = window.backgroundPixmapId,
                borderPixel = window.borderPixel,
                borderPixmapId = window.borderPixmapId,
                framebufferDataUri = window.framebuffer.toDataUri(),
                windowClass = window.windowClass,
                depth = window.depth,
                visual = window.visual,
                bitGravity = window.bitGravity,
                winGravity = window.winGravity,
                backingStore = window.backingStore,
                backingPlanes = window.backingPlanes,
                backingPixel = window.backingPixel,
                overrideRedirect = window.overrideRedirect,
                saveUnder = window.saveUnder,
                colormapId = window.colormapId,
                cursorId = window.cursorId,
            )
        }
        val pixmapSnapshots = pixmaps.values.map { pixmap ->
            XPixmapSnapshot(
                id = pixmap.id,
                width = pixmap.width,
                height = pixmap.height,
                depth = pixmap.depth,
                painted = pixmap.framebuffer.hasPaintedContent(),
                framebufferDataUri = pixmap.framebuffer.toDataUri(),
                pictureIds = pictures.values
                    .filter { it.drawableId == pixmap.id }
                    .map { it.id },
                matchingWindowIds = windowSnapshots
                    .filter { it.mapped && it.width == pixmap.width && it.height == pixmap.height }
                    .map { it.id },
            )
        }
        return XScreenSnapshot(
            width = width,
            height = height,
            dpi = dpi,
            widthMillimeters = widthMillimeters,
            heightMillimeters = heightMillimeters,
            focusWindowId = focusWindowId,
            pointer = XPointerStateSnapshot(
                x = pointerX,
                y = pointerY,
                mask = pointerMask(),
                logicalButtonsDown = pressedLogicalButtons.sorted(),
                windowId = windowAt(pointerX, pointerY)?.id ?: 0,
            ),
            keyboardState = XKeyboardStateSnapshot(
                modifierMask = keyboardModifierState,
                keycodesDown = pressedKeycodes.sorted(),
            ),
            fontPath = fontPath.toList(),
            keyboardMapping = keyboardMapping.snapshot(),
            keyboardControl = keyboardControl.snapshot(),
            windows = windowSnapshots,
            pixmaps = pixmapSnapshots,
            cursors = cursors.values.map { it.snapshot() },
            glxPixmaps = glxPixmaps.values.map { pixmap ->
                XGlxPixmapSnapshot(
                    id = pixmap.id,
                    pixmapId = pixmap.pixmapId,
                    visualId = pixmap.visualId,
                    fbConfigId = pixmap.fbConfigId,
                    screen = pixmap.screen,
                    width = pixmap.width,
                    height = pixmap.height,
                    depth = pixmap.depth,
                    eventMask = pixmap.eventMask,
                    textureTarget = pixmap.textureTarget,
                )
            },
            glxWindows = glxWindows.values.mapNotNull { glxWindow ->
                val window = windows[glxWindow.windowId] ?: return@mapNotNull null
                XGlxWindowSnapshot(
                    id = glxWindow.id,
                    windowId = glxWindow.windowId,
                    fbConfigId = glxWindow.fbConfigId,
                    screen = glxWindow.screen,
                    width = window.width,
                    height = window.height,
                    eventMask = glxWindow.eventMask,
                )
            },
            glxPbuffers = glxPbuffers.values.map { pbuffer ->
                XGlxPbufferSnapshot(
                    id = pbuffer.id,
                    fbConfigId = pbuffer.fbConfigId,
                    screen = pbuffer.screen,
                    width = pbuffer.width,
                    height = pbuffer.height,
                    eventMask = pbuffer.eventMask,
                )
            },
            overlaps = overlaps(windowSnapshots.filter { it.windowClass == XWindowClass.InputOutput }),
            drawings = drawings.toList(),
            inputOperations = inputOperations.toList(),
            inputControlOperations = inputControlOperations.toList(),
            inputGrabs = listOfNotNull(
                activePointerGrab?.snapshot(),
                activeKeyboardGrab?.snapshot(),
            ),
            passiveButtonGrabs = passiveButtonGrabs.map { it.snapshot() },
            passiveKeyGrabs = passiveKeyGrabs.map { it.snapshot() },
            serverGrabbed = serverGrabbed(),
            glxOperations = glxOperations.toList(),
            renderOperations = renderOperations.toList(),
            renderPictures = pictures.values.map { picture ->
                XRenderPictureSnapshot(
                    id = picture.id,
                    drawableId = picture.drawableId,
                    drawableKind = picture.drawableId?.let { drawableId ->
                        when {
                            windows.containsKey(drawableId) -> "window"
                            pixmaps.containsKey(drawableId) -> "pixmap"
                            else -> "missing"
                        }
                    } ?: when {
                        picture.linearGradient != null -> "linear-gradient"
                        picture.radialGradient != null -> "radial-gradient"
                        picture.conicalGradient != null -> "conical-gradient"
                        else -> "solid"
                    },
                    format = picture.format,
                    solidPixel = picture.solidPixel,
                    linearGradient = picture.linearGradient?.let { gradient ->
                        XLinearGradientSnapshot(
                            p1 = gradient.p1,
                            p2 = gradient.p2,
                            stops = gradient.stops,
                            colors = gradient.colors,
                        )
                    },
                    radialGradient = picture.radialGradient?.let { gradient ->
                        XRadialGradientSnapshot(
                            inner = gradient.inner,
                            outer = gradient.outer,
                            stops = gradient.stops,
                            colors = gradient.colors,
                        )
                    },
                    conicalGradient = picture.conicalGradient?.let { gradient ->
                        XConicalGradientSnapshot(
                            center = gradient.center,
                            angle = gradient.angle,
                            stops = gradient.stops,
                            colors = gradient.colors,
                        )
                    },
                    repeat = picture.repeat,
                    alphaMap = picture.alphaMap,
                    alphaXOrigin = picture.alphaXOrigin,
                    alphaYOrigin = picture.alphaYOrigin,
                    clipXOrigin = picture.clipXOrigin,
                    clipYOrigin = picture.clipYOrigin,
                    clipMask = picture.clipMask,
                    clipRectangles = picture.clipRectangles?.size ?: 0,
                    graphicsExposure = picture.graphicsExposure,
                    subwindowMode = picture.subwindowMode,
                    polyEdge = picture.polyEdge,
                    polyMode = picture.polyMode,
                    dither = picture.dither,
                    componentAlpha = picture.componentAlpha,
                    transform = picture.transform,
                    filterName = picture.filterName,
                    filterValues = picture.filterValues,
                )
            },
            accessControl = XAccessControlSnapshot(
                enabled = accessControlEnabled,
                hosts = accessHosts.toList(),
            ),
            requestCounts = requestCounts.toList().map { XRequestCount(it.first, it.second) },
            extensionQueries = extensionQueries.toList(),
            unsupportedRequests = unsupportedRequests.toList(),
        )
    }

    @Synchronized
    fun recordRequest(name: String) {
        requestCounts[name] = (requestCounts[name] ?: 0) + 1
        if (requestCounts.size > MaxRequestCounts) {
            val first = requestCounts.keys.firstOrNull()
            if (first != null) requestCounts.remove(first)
        }
    }

    @Synchronized
    fun recordExtensionQuery(name: String, supported: Boolean) {
        extensionQueries += XExtensionQuery(
            id = nextExtensionQueryId++,
            name = name,
            supported = supported,
        )
        if (extensionQueries.size > MaxExtensionQueries) {
            extensionQueries.removeAt(0)
        }
    }

    @Synchronized
    fun recordUnsupportedRequest(opcode: Int, minorOpcode: Int, name: String) {
        unsupportedRequests += XUnsupportedRequest(
            id = nextUnsupportedRequestId++,
            opcode = opcode,
            minorOpcode = minorOpcode,
            name = name,
        )
        if (unsupportedRequests.size > MaxUnsupportedRequests) {
            unsupportedRequests.removeAt(0)
        }
    }

    @Synchronized
    fun putGlxContext(context: XGlxContext) {
        glxContexts[context.id] = context
    }

    @Synchronized
    fun removeGlxContext(id: Int) {
        glxContexts.remove(id)
        glxLargeRenders.remove(id)
        discardRetainedResourceIds(setOf(id))
    }

    @Synchronized
    fun glxContext(id: Int): XGlxContext? = glxContexts[id]

    @Synchronized
    fun glxLargeRender(contextTag: Int): XGlxLargeRenderState? = glxLargeRenders[contextTag]

    @Synchronized
    fun putGlxLargeRender(state: XGlxLargeRenderState) {
        glxLargeRenders[state.contextTag] = state
    }

    @Synchronized
    fun removeGlxLargeRender(contextTag: Int) {
        glxLargeRenders.remove(contextTag)
    }

    @Synchronized
    fun putGlxPixmap(pixmap: XGlxPixmap) {
        glxPixmaps[pixmap.id] = pixmap
    }

    @Synchronized
    fun removeGlxPixmap(id: Int) {
        glxPixmaps.remove(id)
        discardRetainedResourceIds(setOf(id))
    }

    @Synchronized
    fun hasGlxPixmap(id: Int): Boolean = glxPixmaps.containsKey(id)

    @Synchronized
    fun glxPixmap(id: Int): XGlxPixmap? = glxPixmaps[id]

    @Synchronized
    fun putGlxWindow(window: XGlxWindow) {
        glxWindows[window.id] = window
    }

    @Synchronized
    fun removeGlxWindow(id: Int) {
        glxWindows.remove(id)
        discardRetainedResourceIds(setOf(id))
    }

    @Synchronized
    fun hasGlxWindow(id: Int): Boolean = glxWindows.containsKey(id)

    @Synchronized
    fun hasGlxWindowForWindow(windowId: Int): Boolean = glxWindows.values.any { it.windowId == windowId }

    @Synchronized
    fun glxWindow(id: Int): XGlxWindow? = glxWindows[id]

    @Synchronized
    fun putGlxPbuffer(pbuffer: XGlxPbuffer) {
        glxPbuffers[pbuffer.id] = pbuffer
    }

    @Synchronized
    fun removeGlxPbuffer(id: Int) {
        glxPbuffers.remove(id)
        discardRetainedResourceIds(setOf(id))
    }

    @Synchronized
    fun hasGlxPbuffer(id: Int): Boolean = glxPbuffers.containsKey(id)

    @Synchronized
    fun glxPbuffer(id: Int): XGlxPbuffer? = glxPbuffers[id]

    @Synchronized
    fun recordGlxOperation(
        minorOpcode: Int,
        operation: String,
        detail: String = "",
    ) {
        glxOperations += XGlxOperation(
            id = nextGlxOperationId++,
            minorOpcode = minorOpcode,
            operation = operation,
            detail = detail,
        )
        if (glxOperations.size > MaxGlxOperations) {
            glxOperations.removeAt(0)
        }
    }

    @Synchronized
    fun putPicture(picture: XPicture) {
        pictures[picture.id] = picture
    }

    @Synchronized
    fun updatePicture(
        id: Int,
        valueMask: Int,
        repeat: Int? = null,
        alphaMap: Int? = null,
        alphaXOrigin: Int? = null,
        alphaYOrigin: Int? = null,
        clipXOrigin: Int? = null,
        clipYOrigin: Int? = null,
        clipMask: Int? = null,
        graphicsExposure: Boolean? = null,
        subwindowMode: Int? = null,
        polyEdge: Int? = null,
        polyMode: Int? = null,
        dither: Int? = null,
        componentAlpha: Boolean? = null,
    ) {
        pictures[id]?.valueMask = valueMask
        repeat?.let { pictures[id]?.repeat = it }
        alphaMap?.let { pictures[id]?.alphaMap = it }
        alphaXOrigin?.let { pictures[id]?.alphaXOrigin = it }
        alphaYOrigin?.let { pictures[id]?.alphaYOrigin = it }
        clipXOrigin?.let { pictures[id]?.clipXOrigin = it }
        clipYOrigin?.let { pictures[id]?.clipYOrigin = it }
        clipMask?.let {
            pictures[id]?.let { picture ->
                picture.clipMask = it
                picture.clipMaskImage = if (it == 0) null else pixmaps[it]?.framebuffer?.snapshot()
                picture.clipRectangles = null
            }
        }
        graphicsExposure?.let { pictures[id]?.graphicsExposure = it }
        subwindowMode?.let { pictures[id]?.subwindowMode = it }
        polyEdge?.let { pictures[id]?.polyEdge = it }
        polyMode?.let { pictures[id]?.polyMode = it }
        dither?.let { pictures[id]?.dither = it }
        componentAlpha?.let { pictures[id]?.componentAlpha = it }
    }

    @Synchronized
    fun updatePictureClip(id: Int, rectangles: List<XRectangleCommand>) {
        pictures[id]?.clipMask = 0
        pictures[id]?.clipMaskImage = null
        pictures[id]?.clipRectangles = rectangles
    }

    @Synchronized
    fun setPictureClipRegion(id: Int, originX: Int, originY: Int, rectangles: List<XRectangleCommand>?) {
        pictures[id]?.clipXOrigin = originX
        pictures[id]?.clipYOrigin = originY
        pictures[id]?.clipMask = 0
        pictures[id]?.clipMaskImage = null
        pictures[id]?.clipRectangles = rectangles
    }

    @Synchronized
    fun updatePictureTransform(id: Int, transform: List<Int>) {
        pictures[id]?.transform = transform
    }

    @Synchronized
    fun updatePictureFilter(id: Int, name: String, values: List<Int>) {
        pictures[id]?.filterName = name
        pictures[id]?.filterValues = values
    }

    @Synchronized
    fun removePicture(id: Int) {
        pictures.remove(id)
        discardRetainedResourceIds(setOf(id))
    }

    @Synchronized
    fun picture(id: Int): XPicture? = pictures[id]

    @Synchronized
    fun putXFixesRegion(region: XFixesRegion) {
        xfixesRegions[region.id] = region
    }

    @Synchronized
    fun xfixesRegion(id: Int): XFixesRegion? = xfixesRegions[id]

    @Synchronized
    fun updateXFixesRegion(id: Int, rectangles: List<XRectangleCommand>) {
        xfixesRegions[id] = XFixesRegion(id, rectangles)
    }

    @Synchronized
    fun removeXFixesRegion(id: Int) {
        xfixesRegions.remove(id)
        discardRetainedResourceIds(setOf(id))
    }

    @Synchronized
    fun setWindowShapeRegion(
        windowId: Int,
        kind: Int,
        rectangles: List<XRectangleCommand>?,
        notifyWhenUnchanged: Boolean = false,
    ): List<XShapeNotifyDispatch> {
        val window = windows[windowId] ?: return emptyList()
        val previous = windowClientShapeRegion(window, kind)
        if (previous == null && rectangles == null && !notifyWhenUnchanged) return emptyList()
        val copy = rectangles?.map { it.copy() }
        when (kind) {
            XFixes.ShapeBounding -> window.boundingShape = copy
            XFixes.ShapeClip -> window.clipShape = copy
            XFixes.ShapeInput -> window.inputShape = copy
            else -> return emptyList()
        }
        return shapeNotifyDispatches(windowId, kind)
    }

    @Synchronized
    fun windowShapeRegion(windowId: Int, kind: Int): List<XRectangleCommand> {
        val window = windows[windowId] ?: return emptyList()
        val shape = when (kind) {
            XFixes.ShapeBounding -> window.boundingShape
            XFixes.ShapeClip -> window.clipShape
            XFixes.ShapeInput -> window.inputShape
            else -> null
        }
        return (shape ?: defaultWindowShapeRegion(window, kind)).map { it.copy() }
    }

    @Synchronized
    fun windowClientShapeRegion(windowId: Int, kind: Int): List<XRectangleCommand>? {
        val window = windows[windowId] ?: return null
        return windowClientShapeRegion(window, kind)?.map { it.copy() }
    }

    @Synchronized
    fun windowShapeNotifyDispatches(windowId: Int, kind: Int): List<XShapeNotifyDispatch> =
        shapeNotifyDispatches(windowId, kind)

    @Synchronized
    fun windowShapeIsSet(windowId: Int, kind: Int): Boolean {
        val window = windows[windowId] ?: return false
        return when (kind) {
            XFixes.ShapeBounding -> window.boundingShape != null
            XFixes.ShapeClip -> window.clipShape != null
            XFixes.ShapeInput -> window.inputShape != null
            else -> false
        }
    }

    @Synchronized
    fun offsetWindowShapeRegion(windowId: Int, kind: Int, dx: Int, dy: Int): List<XShapeNotifyDispatch> {
        val window = windows[windowId] ?: return emptyList()
        val shape = when (kind) {
            XFixes.ShapeBounding -> window.boundingShape
            XFixes.ShapeClip -> window.clipShape
            XFixes.ShapeInput -> window.inputShape
            else -> return emptyList()
        } ?: return shapeNotifyDispatches(windowId, kind)
        val translated = shape.map { rectangle -> rectangle.copy(x = rectangle.x + dx, y = rectangle.y + dy) }
        when (kind) {
            XFixes.ShapeBounding -> window.boundingShape = translated
            XFixes.ShapeClip -> window.clipShape = translated
            XFixes.ShapeInput -> window.inputShape = translated
            else -> return emptyList()
        }
        return shapeNotifyDispatches(windowId, kind)
    }

    @Synchronized
    fun selectShapeInput(sink: XEventSink, windowId: Int, enabled: Boolean) {
        if (!windows.containsKey(windowId)) return
        if (!enabled) {
            shapeInputs[sink]?.remove(windowId)
            if (shapeInputs[sink]?.isEmpty() == true) shapeInputs.remove(sink)
            return
        }
        shapeInputs.getOrPut(sink) { linkedSetOf() } += windowId
    }

    @Synchronized
    fun shapeInputSelected(sink: XEventSink, windowId: Int): Boolean =
        shapeInputs[sink]?.contains(windowId) == true

    @Synchronized
    fun selectScreenSaverInput(sink: XEventSink, eventMask: Int) {
        if (eventMask == 0) {
            screenSaverInputs.remove(sink)
        } else {
            screenSaverInputs[sink] = eventMask
        }
    }

    @Synchronized
    fun screenSaverEventMask(sink: XEventSink): Int = screenSaverInputs[sink] ?: 0

    @Synchronized
    fun setScreenSaverAttributes(attributes: XScreenSaverAttributes): Boolean {
        val currentOwner = screenSaverAttributes?.owner
        if (currentOwner != null && currentOwner != attributes.owner) return false
        screenSaverAttributes = attributes
        return true
    }

    @Synchronized
    fun unsetScreenSaverAttributes(sink: XEventSink) {
        if (screenSaverAttributes?.owner == sink) screenSaverAttributes = null
    }

    @Synchronized
    fun screenSaverAttributes(): XScreenSaverAttributes? = screenSaverAttributes

    @Synchronized
    fun suspendScreenSaver(sink: XEventSink, suspend: Boolean) {
        val current = screenSaverSuspensions[sink] ?: 0
        if (suspend) {
            screenSaverSuspensions[sink] = current + 1
        } else if (current <= 1) {
            screenSaverSuspensions.remove(sink)
        } else {
            screenSaverSuspensions[sink] = current - 1
        }
    }

    @Synchronized
    fun screenSaverSuspensionCount(): Int = screenSaverSuspensions.values.sum()

    @Synchronized
    fun putSyncCounter(counter: XSyncCounter) {
        syncCounters[counter.id] = counter
        notifySyncWaiters()
    }

    @Synchronized
    fun syncCounter(id: Int): XSyncCounter? =
        if (id == XSync.ServerTimeCounter) {
            val value = serverTimeCounterValue()
            XSyncCounter(id = id, value = value, previousValue = value, generation = value, system = true)
        } else {
            syncCounters[id]
        }

    @Synchronized
    fun setSyncCounterValue(id: Int, value: Long): List<XSyncAlarmNotifyDispatch>? {
        val counter = syncCounters[id] ?: return null
        val updated = counter.copy(value = value, previousValue = counter.value, generation = counter.generation + 1)
        syncCounters[id] = updated
        latchSyncCounterWaiters()
        val notifications = updateSyncAlarmsForCounter(id)
        notifySyncWaiters()
        return notifications
    }

    @Synchronized
    fun changeSyncCounterValue(id: Int, delta: Long): XSyncCounterChangeResult {
        val counter = syncCounters[id] ?: return XSyncCounterChangeResult.Missing
        val value = try {
            Math.addExact(counter.value, delta)
        } catch (_: ArithmeticException) {
            return XSyncCounterChangeResult.Overflow
        }
        val updated = counter.copy(value = value, previousValue = counter.value, generation = counter.generation + 1)
        syncCounters[id] = updated
        latchSyncCounterWaiters()
        val notifications = updateSyncAlarmsForCounter(id)
        notifySyncWaiters()
        return XSyncCounterChangeResult.Changed(notifications)
    }

    @Synchronized
    fun removeSyncCounter(id: Int): List<XSyncAlarmNotifyDispatch>? {
        val removed = syncCounters.remove(id) != null
        if (removed) {
            val notifications = mutableListOf<XSyncAlarmNotifyDispatch>()
            syncAlarms.replaceAll { _, alarm ->
                if (alarm.counterId == id) {
                    val updated = alarm.copy(counterId = 0, state = XSync.AlarmInactive)
                    if (alarm.events) {
                        notifications += XSyncAlarmNotifyDispatch(
                            sink = alarm.owner,
                            event = XSyncAlarmNotifyEvent(
                                alarmId = alarm.id,
                                counterValue = 0,
                                alarmValue = alarm.testValue,
                                timestamp = syncServerTime(),
                                state = XSync.AlarmInactive,
                            ),
                        )
                    }
                    updated
                } else {
                    alarm
                }
            }
            latchSyncCounterWaiters()
            notifySyncWaiters()
            return notifications
        }
        return null
    }

    @Synchronized
    fun putSyncAlarm(alarm: XSyncAlarm): List<XSyncAlarmNotifyDispatch> {
        syncAlarms[alarm.id] = alarm
        val notifications = if (alarm.counterId == 0) emptyList() else updateSyncAlarmsForCounter(alarm.counterId)
        notifySyncWaiters()
        return notifications
    }

    @Synchronized
    fun syncAlarm(id: Int): XSyncAlarm? = syncAlarms[id]

    @Synchronized
    fun syncServerTime(): Int = serverTimeCounterValue().toInt()

    @Synchronized
    fun removeSyncAlarm(id: Int): Boolean {
        val removed = syncAlarms.remove(id) != null
        if (removed) notifySyncWaiters()
        return removed
    }

    @Synchronized
    fun putSyncFence(fence: XSyncFence) {
        syncFences[fence.id] = fence
        notifySyncWaiters()
    }

    @Synchronized
    fun syncFence(id: Int): XSyncFence? = syncFences[id]

    @Synchronized
    fun setSyncFenceTriggered(id: Int, triggered: Boolean): Boolean {
        val fence = syncFences[id] ?: return false
        syncFences[id] = fence.copy(triggered = triggered)
        notifySyncWaiters()
        return true
    }

    @Synchronized
    fun removeSyncFence(id: Int): Boolean {
        val removed = syncFences.remove(id) != null
        if (removed) notifySyncWaiters()
        return removed
    }

    @Synchronized
    fun syncPriorityClient(requester: XEventSink, id: Int): XEventSink? =
        if (id == 0) requester else resourceOwners[id]?.takeIf { hasResource(id) && it in eventSinks && !it.isKilled() }

    @Synchronized
    fun setSyncPriority(sink: XEventSink, priority: Int) {
        syncPriorities[sink] = priority
    }

    @Synchronized
    fun syncPriority(sink: XEventSink): Int = syncPriorities[sink] ?: 0

    @Synchronized
    fun syncCounterAwaitSatisfied(conditions: List<XSyncWaitCondition>): Boolean =
        conditions.any { condition -> syncCounterTriggerSatisfied(condition) }

    @Synchronized
    fun syncCounterNotifyEvents(conditions: List<XSyncWaitCondition>): List<XSyncCounterNotifyEvent> =
        syncCounterNotifyEventsIfSatisfied(conditions) ?: emptyList()

    @Synchronized
    fun awaitSyncCounters(sink: XEventSink, conditions: List<XSyncWaitCondition>): List<XSyncCounterNotifyEvent> {
        syncCounterNotifyEventsIfSatisfied(conditions)?.let { return it }
        val waiter = XSyncCounterWaiter(conditions)
        syncCounterWaiters += waiter
        try {
            while (!sink.isKilled() && waiter.events == null) {
                syncCounterNotifyEventsIfSatisfied(conditions)?.let { events ->
                    waiter.events = events
                    break
                }
                waitForSyncChange()
            }
            return waiter.events ?: syncCounterNotifyEventsIfSatisfied(conditions) ?: emptyList()
        } finally {
            syncCounterWaiters.remove(waiter)
        }
    }

    @Synchronized
    fun syncFenceAwaitSatisfied(fenceIds: List<Int>): Boolean =
        fenceIds.any { id -> syncFences[id]?.triggered ?: true }

    @Synchronized
    fun awaitSyncFences(sink: XEventSink, fenceIds: List<Int>) {
        while (!sink.isKilled() && !syncFenceAwaitSatisfied(fenceIds)) {
            waitForSyncChange()
        }
    }

    private fun syncCounterTriggerSatisfied(condition: XSyncWaitCondition): Boolean {
        if (condition.counterId == 0) return true
        val counter = syncCounter(condition.counterId) ?: return true
        return syncTriggerSatisfied(counter, condition.testValue, condition.testType, condition.counterGeneration)
    }

    private fun syncCounterNotifyEventsIfSatisfied(conditions: List<XSyncWaitCondition>): List<XSyncCounterNotifyEvent>? {
        if (!syncCounterAwaitSatisfied(conditions)) return null
        return conditions.mapNotNull { condition -> syncCounterNotifyEvent(condition) }
    }

    private fun syncCounterNotifyEvent(condition: XSyncWaitCondition): XSyncCounterNotifyEvent? {
        val counter = syncCounter(condition.counterId)
        val destroyed = condition.counterId != 0 && counter == null
        val counterValue = counter?.value ?: 0
        if (!destroyed && condition.counterId != 0 && !syncCounterNotifyThresholdReached(condition, counterValue)) {
            return null
        }
        return XSyncCounterNotifyEvent(
            counterId = condition.counterId,
            waitValue = condition.testValue,
            counterValue = counterValue,
            timestamp = syncServerTime(),
            count = 0,
            destroyed = destroyed,
        )
    }

    private fun syncCounterNotifyThresholdReached(condition: XSyncWaitCondition, counterValue: Long): Boolean {
        val difference = try {
            Math.subtractExact(counterValue, condition.testValue)
        } catch (_: ArithmeticException) {
            return false
        }
        return when (condition.testType) {
            XSync.PositiveTransition, XSync.PositiveComparison -> difference >= condition.eventThreshold
            XSync.NegativeTransition, XSync.NegativeComparison -> difference <= condition.eventThreshold
            else -> false
        }
    }

    private fun latchSyncCounterWaiters() {
        syncCounterWaiters.forEach { waiter ->
            if (waiter.events == null) {
                waiter.events = syncCounterNotifyEventsIfSatisfied(waiter.conditions)
            }
        }
    }

    private fun serverTimeCounterValue(): Long =
        (System.currentTimeMillis() - serverStartMillis).coerceAtLeast(0L)

    private fun updateSyncAlarmsForCounter(counterId: Int): List<XSyncAlarmNotifyDispatch> {
        val counter = syncCounter(counterId) ?: return emptyList()
        val notifications = mutableListOf<XSyncAlarmNotifyDispatch>()
        syncAlarms.replaceAll { _, alarm ->
            if (alarm.counterId == counterId && alarm.state == XSync.AlarmActive && syncTriggerSatisfied(counter, alarm.testValue, alarm.testType, alarm.counterGeneration)) {
                val updated = advanceSyncAlarmAfterTrigger(alarm, counter)
                if (alarm.events) {
                    notifications += XSyncAlarmNotifyDispatch(
                        sink = alarm.owner,
                        event =
                            XSyncAlarmNotifyEvent(
                                alarmId = alarm.id,
                                counterValue = counter.value,
                                alarmValue = alarm.testValue,
                                timestamp = syncServerTime(),
                                state = updated.state,
                            ),
                    )
                }
                updated
            } else {
                alarm
            }
        }
        return notifications
    }

    private fun advanceSyncAlarmAfterTrigger(alarm: XSyncAlarm, counter: XSyncCounter): XSyncAlarm {
        if (
            alarm.delta == 0L &&
            (alarm.testType == XSync.PositiveComparison || alarm.testType == XSync.NegativeComparison)
        ) {
            return alarm.copy(state = XSync.AlarmInactive, counterGeneration = counter.generation)
        }
        val testValue = when (alarm.testType) {
            XSync.PositiveComparison, XSync.PositiveTransition ->
                advanceSyncComparisonAlarmValue(alarm.testValue, counter.value, alarm.delta, positive = true)
            XSync.NegativeComparison, XSync.NegativeTransition ->
                advanceSyncComparisonAlarmValue(alarm.testValue, counter.value, alarm.delta, positive = false)
            else ->
                try {
                    Math.addExact(alarm.testValue, alarm.delta)
                } catch (_: ArithmeticException) {
                    null
                }
        } ?: return alarm.copy(state = XSync.AlarmInactive, counterGeneration = counter.generation)
        return if (!syncTriggerSatisfied(counter, testValue, alarm.testType, counter.generation)) {
            alarm.copy(
                valueType = XSync.Absolute,
                waitValue = testValue,
                testValue = testValue,
                counterGeneration = counter.generation,
                state = XSync.AlarmActive,
            )
        } else {
            alarm.copy(state = XSync.AlarmInactive, counterGeneration = counter.generation)
        }
    }

    private fun advanceSyncComparisonAlarmValue(testValue: Long, counterValue: Long, delta: Long, positive: Boolean): Long? {
        if (delta == 0L) return null
        val distance = if (positive) {
            java.math.BigInteger.valueOf(counterValue).subtract(java.math.BigInteger.valueOf(testValue))
        } else {
            java.math.BigInteger.valueOf(testValue).subtract(java.math.BigInteger.valueOf(counterValue))
        }
        val steps = if (distance.signum() < 0) {
            java.math.BigInteger.ONE
        } else {
            distance.divide(java.math.BigInteger.valueOf(delta).abs()).add(java.math.BigInteger.ONE)
        }
        val nextValue = java.math.BigInteger.valueOf(testValue).add(java.math.BigInteger.valueOf(delta).multiply(steps))
        return if (
            nextValue < java.math.BigInteger.valueOf(Long.MIN_VALUE) ||
            nextValue > java.math.BigInteger.valueOf(Long.MAX_VALUE)
        ) {
            null
        } else {
            nextValue.toLong()
        }
    }

    private fun syncTriggerSatisfied(counter: XSyncCounter, testValue: Long, testType: Int, sinceGeneration: Long): Boolean =
        when (testType) {
            XSync.PositiveComparison -> counter.value >= testValue
            XSync.NegativeComparison -> counter.value <= testValue
            XSync.PositiveTransition ->
                if (counter.system) {
                    sinceGeneration < testValue && counter.value >= testValue
                } else {
                    counter.generation > sinceGeneration && counter.previousValue < testValue && counter.value >= testValue
                }
            XSync.NegativeTransition ->
                if (counter.system) {
                    sinceGeneration > testValue && counter.value <= testValue
                } else {
                    counter.generation > sinceGeneration && counter.previousValue > testValue && counter.value <= testValue
                }
            else -> false
        }

    @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
    private fun waitForSyncChange() {
        (this as java.lang.Object).wait(50)
    }

    @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
    private fun notifySyncWaiters() {
        (this as java.lang.Object).notifyAll()
    }

    private fun defaultWindowShapeRegion(window: XWindow, kind: Int): List<XRectangleCommand> =
        when (kind) {
            XFixes.ShapeBounding, XFixes.ShapeInput -> {
                val borderWidth = window.borderWidth
                listOf(
                    XRectangleCommand(
                        x = -borderWidth,
                        y = -borderWidth,
                        width = window.width + borderWidth * 2,
                        height = window.height + borderWidth * 2,
                    ),
                )
            }
            XFixes.ShapeClip -> listOf(XRectangleCommand(0, 0, window.width, window.height))
            else -> emptyList()
        }

    private fun windowClientShapeRegion(window: XWindow, kind: Int): List<XRectangleCommand>? =
        when (kind) {
            XFixes.ShapeBounding -> window.boundingShape
            XFixes.ShapeClip -> window.clipShape
            XFixes.ShapeInput -> window.inputShape
            else -> null
        }

    private fun shapeNotifyDispatches(windowId: Int, kind: Int): List<XShapeNotifyDispatch> {
        val extents = windowShapeExtents(windowId, kind) ?: XRectangleCommand(0, 0, 0, 0)
        val shaped = windowShapeIsSet(windowId, kind)
        val timestamp = currentServerTime(inputTime)
        return shapeInputs.flatMap { (sink, windows) ->
            if (windowId !in windows) return@flatMap emptyList()
            listOf(
                XShapeNotifyDispatch(
                    sink = sink,
                    event = XShapeNotifyEvent(
                        kind = kind,
                        windowId = windowId,
                        x = extents.x,
                        y = extents.y,
                        width = extents.width,
                        height = extents.height,
                        timestamp = timestamp,
                        shaped = shaped,
                    ),
                ),
            )
        }
    }

    private fun windowShapeExtents(windowId: Int, kind: Int): XRectangleCommand? =
        regionExtents(windowShapeRegion(windowId, kind))

    private fun regionExtents(rectangles: List<XRectangleCommand>): XRectangleCommand? {
        val nonEmpty = rectangles.filter { it.width > 0 && it.height > 0 }
        if (nonEmpty.isEmpty()) return null
        val minX = nonEmpty.minOf { it.x }
        val minY = nonEmpty.minOf { it.y }
        val maxX = nonEmpty.maxOf { it.x + it.width }
        val maxY = nonEmpty.maxOf { it.y + it.height }
        return XRectangleCommand(minX, minY, maxX - minX, maxY - minY)
    }

    @Synchronized
    fun putGlyphSet(glyphSet: XGlyphSet) {
        glyphSets[glyphSet.id] = glyphSet
    }

    @Synchronized
    fun referenceGlyphSet(id: Int, existingId: Int) {
        val existing = glyphSets[existingId] ?: return
        glyphSets[id] = existing.copy(id = id)
    }

    @Synchronized
    fun removeGlyphSet(id: Int) {
        glyphSets.remove(id)
        discardRetainedResourceIds(setOf(id))
    }

    @Synchronized
    fun glyphSetFormat(id: Int): Int? = glyphSets[id]?.format

    @Synchronized
    fun hasGlyphSet(id: Int): Boolean = glyphSets.containsKey(id)

    @Synchronized
    fun glyph(glyphSetId: Int, glyphId: Int): XGlyph? = glyphSets[glyphSetId]?.glyphs?.get(glyphId)

    @Synchronized
    fun addGlyphs(glyphSetId: Int, glyphs: List<XGlyph>) {
        val glyphSet = glyphSets[glyphSetId] ?: return
        for (glyph in glyphs) {
            glyphSet.glyphs[glyph.id] = glyph
        }
    }

    @Synchronized
    fun addGlyphsFromPicture(glyphSetId: Int, source: XPicture, glyphs: List<XPictureGlyph>) {
        val glyphSet = glyphSets[glyphSetId] ?: return
        val sourcePixelAt = source.sourcePixelSampler() ?: return
        for (glyph in glyphs) {
            glyphSet.glyphs[glyph.id] = XGlyph(
                id = glyph.id,
                width = glyph.width,
                height = glyph.height,
                x = glyph.x,
                y = glyph.y,
                xOff = glyph.xOff,
                yOff = glyph.yOff,
                mask = glyphMaskFromPicture(
                    format = glyphSet.format,
                    width = glyph.width,
                    height = glyph.height,
                    sourceX = glyph.sourceX,
                    sourceY = glyph.sourceY,
                    sourcePixelAt = sourcePixelAt,
                ),
            )
        }
    }

    @Synchronized
    fun removeGlyphs(glyphSetId: Int, glyphIds: List<Int>) {
        val glyphSet = glyphSets[glyphSetId] ?: return
        for (id in glyphIds) {
            glyphSet.glyphs.remove(id)
        }
    }

    @Synchronized
    fun removeGlyph(glyphSetId: Int, glyphId: Int): Boolean {
        val glyphSet = glyphSets[glyphSetId] ?: return false
        return glyphSet.glyphs.remove(glyphId) != null
    }

    @Synchronized
    fun recordRenderOperation(
        minorOpcode: Int,
        operation: String,
        detail: String = "",
    ) {
        renderOperations += XRenderOperation(
            id = nextRenderOperationId++,
            minorOpcode = minorOpcode,
            operation = operation,
            detail = detail,
        )
        if (renderOperations.size > MaxRenderOperations) {
            renderOperations.removeAt(0)
        }
    }

    @Synchronized
    fun drawable(id: Int): XDrawable? =
        windows[id]?.takeIf { it.windowClass == XWindowClass.InputOutput }
            ?.let { XDrawable(it.x, it.y, it.width, it.height, it.borderWidth, X11Ids.RootWindow, it.depth) }
            ?: pixmaps[id]?.let { XDrawable(0, 0, it.width, it.height, 0, it.rootId, it.depth) }

    @Synchronized
    fun drawableGeometry(id: Int): XDrawable? =
        windows[id]?.let { XDrawable(it.x, it.y, it.width, it.height, it.borderWidth, X11Ids.RootWindow, it.depth) }
            ?: pixmaps[id]?.let { XDrawable(0, 0, it.width, it.height, 0, it.rootId, it.depth) }

    @Synchronized
    fun putPixmap(pixmap: XPixmap) {
        pixmaps[pixmap.id] = pixmap
    }

    @Synchronized
    fun pixmapImage(id: Int): XImagePixels? =
        pixmaps[id]?.framebuffer?.snapshot()

    @Synchronized
    fun putImage(
        drawableId: Int,
        x: Int,
        y: Int,
        image: XImagePixels,
        clipRectangles: List<XRectangleCommand>? = null,
        function: Int = XGraphicsContext.GXcopy,
        planeMask: Int = -1,
    ): Boolean {
        val framebuffer = windows[drawableId]?.framebuffer ?: pixmaps[drawableId]?.framebuffer ?: return false
        val effectiveClip = effectiveDrawableClip(drawableId, clipRectangles)
        return framebuffer.putImage(x, y, image, effectiveClip, function, planeMask)
    }

    @Synchronized
    fun copyArea(
        sourceDrawableId: Int,
        destinationDrawableId: Int,
        sourceX: Int,
        sourceY: Int,
        destinationX: Int,
        destinationY: Int,
        width: Int,
        height: Int,
        clipRectangles: List<XRectangleCommand>? = null,
        function: Int = XGraphicsContext.GXcopy,
        planeMask: Int = -1,
    ): XCopyResult? {
        val source = windows[sourceDrawableId]?.framebuffer ?: pixmaps[sourceDrawableId]?.framebuffer ?: return null
        val destination = windows[destinationDrawableId]?.framebuffer ?: pixmaps[destinationDrawableId]?.framebuffer ?: return null
        val effectiveClip = effectiveDrawableClip(destinationDrawableId, clipRectangles)
        return source.copyAreaTo(
            destination = destination,
            sourceX = sourceX,
            sourceY = sourceY,
            destinationX = destinationX,
            destinationY = destinationY,
            width = width,
            height = height,
            clipRectangles = effectiveClip,
            function = function,
            planeMask = planeMask,
        )
    }

    @Synchronized
    fun copyPlane(
        sourceDrawableId: Int,
        destinationDrawableId: Int,
        sourceX: Int,
        sourceY: Int,
        destinationX: Int,
        destinationY: Int,
        width: Int,
        height: Int,
        bitPlane: Int,
        foreground: Int,
        background: Int,
        clipRectangles: List<XRectangleCommand>? = null,
        function: Int = XGraphicsContext.GXcopy,
        planeMask: Int = -1,
    ): XCopyResult? {
        val source = windows[sourceDrawableId]?.framebuffer ?: pixmaps[sourceDrawableId]?.framebuffer ?: return null
        val destination = windows[destinationDrawableId]?.framebuffer ?: pixmaps[destinationDrawableId]?.framebuffer ?: return null
        val effectiveClip = effectiveDrawableClip(destinationDrawableId, clipRectangles)
        return source.copyPlaneTo(
            destination = destination,
            sourceX = sourceX,
            sourceY = sourceY,
            destinationX = destinationX,
            destinationY = destinationY,
            width = width,
            height = height,
            bitPlane = bitPlane,
            foreground = foreground,
            background = background,
            clipRectangles = effectiveClip,
            function = function,
            planeMask = planeMask,
        )
    }

    @Synchronized
    fun composite(
        operation: Int,
        source: XPicture,
        mask: XPicture?,
        destination: XPicture,
        sourceX: Int,
        sourceY: Int,
        maskX: Int,
        maskY: Int,
        destinationX: Int,
        destinationY: Int,
        width: Int,
        height: Int,
    ): XImagePixels? {
        val destinationDrawableId = destination.drawableId ?: return null
        val destinationFramebuffer = windows[destinationDrawableId]?.framebuffer ?: pixmaps[destinationDrawableId]?.framebuffer ?: return null
        if (operation == XRender.OpDst || operation == XRender.OpDisjointDst || operation == XRender.OpConjointDst) {
            return destinationFramebuffer.snapshotRegion(destinationX, destinationY, width, height)
        }
        val destinationClipMask = destination.clipMaskPredicate()
        val maskFramebuffer = mask?.drawableId?.let { windows[it]?.framebuffer ?: pixmaps[it]?.framebuffer }
        val maskPixelAt = mask?.takeIf { it.componentAlpha }?.componentMaskSampler()
        val maskAlphaAt = mask?.maskAlphaSampler()
        if (maskPixelAt != null) {
            val sourcePixelAt: (x: Int, y: Int) -> Int? = (if (source.alphaMap != 0) {
                source.compositeSourcePixelSamplerOptional(destinationDrawableId)
            } else if (source.hasPictureClip()) {
                source.sourcePixelSamplerOptional(snapshotDrawableId = destinationDrawableId)
            } else {
                source.sourcePixelSampler(snapshotDrawableId = destinationDrawableId)?.let { sampler -> { x: Int, y: Int -> sampler(x, y) } }
            }) ?: return null
            return destinationFramebuffer.compositeGeneratedOptional(
                sourceX = sourceX,
                sourceY = sourceY,
                destinationX = destinationX,
                destinationY = destinationY,
                width = width,
                height = height,
                operation = operation,
                clipRectangles = effectivePictureClip(destination),
                clipMask = destinationClipMask,
                maskX = maskX,
                maskY = maskY,
                maskPixelAt = maskPixelAt,
            ) { x, y ->
                sourcePixelAt(x, y)
            }
        }
        if (source.alphaMap != 0) {
            val sourcePixelAt = source.compositeSourcePixelSamplerOptional(destinationDrawableId) ?: return null
            return destinationFramebuffer.compositeGeneratedOptional(
                sourceX = sourceX,
                sourceY = sourceY,
                destinationX = destinationX,
                destinationY = destinationY,
                width = width,
                height = height,
                operation = operation,
                clipRectangles = effectivePictureClip(destination),
                clipMask = destinationClipMask,
                mask = maskFramebuffer,
                maskX = maskX,
                maskY = maskY,
                maskAlphaAt = maskAlphaAt,
            ) { x, y ->
                sourcePixelAt(x, y)
            }
        }
        if (source.hasPictureClip()) {
            val sourcePixelAt = source.sourcePixelSamplerOptional(snapshotDrawableId = destinationDrawableId) ?: return null
            return destinationFramebuffer.compositeGeneratedOptional(
                sourceX = sourceX,
                sourceY = sourceY,
                destinationX = destinationX,
                destinationY = destinationY,
                width = width,
                height = height,
                operation = operation,
                clipRectangles = effectivePictureClip(destination),
                clipMask = destinationClipMask,
                mask = maskFramebuffer,
                maskX = maskX,
                maskY = maskY,
                maskAlphaAt = maskAlphaAt,
            ) { x, y ->
                sourcePixelAt(x, y)
            }
        }
        val gradientSampler = source.gradientSampler()
        if (gradientSampler != null) {
            return destinationFramebuffer.compositeGenerated(
                sourceX = sourceX,
                sourceY = sourceY,
                destinationX = destinationX,
                destinationY = destinationY,
                width = width,
                height = height,
                operation = operation,
                clipRectangles = effectivePictureClip(destination),
                clipMask = destinationClipMask,
                mask = maskFramebuffer,
                maskX = maskX,
                maskY = maskY,
                maskAlphaAt = maskAlphaAt,
            ) { x, y ->
                gradientSampler(x, y)
            }
        }
        val solid = source.solidPixel
        if (solid != null) {
            return when (operation) {
                XRender.OpClear, XRender.OpDisjointClear, XRender.OpConjointClear -> {
                    if (maskFramebuffer == null && maskAlphaAt == null) {
                        destinationFramebuffer.fill(
                            destinationX,
                            destinationY,
                            width,
                            height,
                            0,
                            preserveAlpha = true,
                            clipRectangles = effectivePictureClip(destination),
                            clipMask = destinationClipMask,
                        )
                    } else {
                        destinationFramebuffer.copyFrom(
                            source = XFramebuffer(width, height, painted = true),
                            sourceX = 0,
                            sourceY = 0,
                            destinationX = destinationX,
                            destinationY = destinationY,
                            width = width,
                            height = height,
                            operation = operation,
                            clipRectangles = effectivePictureClip(destination),
                            clipMask = destinationClipMask,
                            mask = maskFramebuffer,
                            maskX = maskX,
                            maskY = maskY,
                            maskAlphaAt = maskAlphaAt,
                        )
                    }
                    XImagePixels(width, height, IntArray(width * height))
                }
                XRender.OpSrc, XRender.OpDisjointSrc, XRender.OpConjointSrc -> {
                    if (maskFramebuffer == null && maskAlphaAt == null) {
                        destinationFramebuffer.fill(
                            destinationX,
                            destinationY,
                            width,
                            height,
                            solid,
                            preserveAlpha = true,
                            clipRectangles = effectivePictureClip(destination),
                            clipMask = destinationClipMask,
                        )
                    } else {
                        destinationFramebuffer.copyFrom(
                            source = XFramebuffer(width, height, painted = true).also { it.fill(0, 0, width, height, solid, preserveAlpha = true) },
                            sourceX = 0,
                            sourceY = 0,
                            destinationX = destinationX,
                            destinationY = destinationY,
                            width = width,
                            height = height,
                            operation = operation,
                            clipRectangles = effectivePictureClip(destination),
                            clipMask = destinationClipMask,
                            mask = maskFramebuffer,
                            maskX = maskX,
                            maskY = maskY,
                            maskAlphaAt = maskAlphaAt,
                        )
                    }
                    XImagePixels(width, height, IntArray(width * height) { solid })
                }
                XRender.OpAdd -> {
                    destinationFramebuffer.blendSolidAdd(
                        pixel = solid,
                        destinationX = destinationX,
                        destinationY = destinationY,
                        width = width,
                        height = height,
                        clipRectangles = effectivePictureClip(destination),
                        clipMask = destinationClipMask,
                        mask = maskFramebuffer,
                        maskX = maskX,
                        maskY = maskY,
                        maskAlphaAt = maskAlphaAt,
                    )
                    XImagePixels(width, height, IntArray(width * height) { solid })
                }
                XRender.OpBlendMultiply -> {
                    destinationFramebuffer.blendSolidMultiply(
                        pixel = solid,
                        destinationX = destinationX,
                        destinationY = destinationY,
                        width = width,
                        height = height,
                        clipRectangles = effectivePictureClip(destination),
                        clipMask = destinationClipMask,
                        mask = maskFramebuffer,
                        maskX = maskX,
                        maskY = maskY,
                        maskAlphaAt = maskAlphaAt,
                    )
                    destinationFramebuffer.snapshotRegion(destinationX, destinationY, width, height)
                }
                XRender.OpBlendScreen -> {
                    destinationFramebuffer.blendSolidScreen(
                        pixel = solid,
                        destinationX = destinationX,
                        destinationY = destinationY,
                        width = width,
                        height = height,
                        clipRectangles = effectivePictureClip(destination),
                        clipMask = destinationClipMask,
                        mask = maskFramebuffer,
                        maskX = maskX,
                        maskY = maskY,
                        maskAlphaAt = maskAlphaAt,
                    )
                    destinationFramebuffer.snapshotRegion(destinationX, destinationY, width, height)
                }
                XRender.OpBlendOverlay -> {
                    destinationFramebuffer.blendSolidOverlay(
                        pixel = solid,
                        destinationX = destinationX,
                        destinationY = destinationY,
                        width = width,
                        height = height,
                        clipRectangles = effectivePictureClip(destination),
                        clipMask = destinationClipMask,
                        mask = maskFramebuffer,
                        maskX = maskX,
                        maskY = maskY,
                        maskAlphaAt = maskAlphaAt,
                    )
                    destinationFramebuffer.snapshotRegion(destinationX, destinationY, width, height)
                }
                XRender.OpBlendDarken -> {
                    destinationFramebuffer.blendSolidDarken(
                        pixel = solid,
                        destinationX = destinationX,
                        destinationY = destinationY,
                        width = width,
                        height = height,
                        clipRectangles = effectivePictureClip(destination),
                        clipMask = destinationClipMask,
                        mask = maskFramebuffer,
                        maskX = maskX,
                        maskY = maskY,
                        maskAlphaAt = maskAlphaAt,
                    )
                    destinationFramebuffer.snapshotRegion(destinationX, destinationY, width, height)
                }
                XRender.OpBlendLighten -> {
                    destinationFramebuffer.blendSolidLighten(
                        pixel = solid,
                        destinationX = destinationX,
                        destinationY = destinationY,
                        width = width,
                        height = height,
                        clipRectangles = effectivePictureClip(destination),
                        clipMask = destinationClipMask,
                        mask = maskFramebuffer,
                        maskX = maskX,
                        maskY = maskY,
                        maskAlphaAt = maskAlphaAt,
                    )
                    destinationFramebuffer.snapshotRegion(destinationX, destinationY, width, height)
                }
                XRender.OpBlendColorDodge -> {
                    destinationFramebuffer.blendSolidColorDodge(
                        pixel = solid,
                        destinationX = destinationX,
                        destinationY = destinationY,
                        width = width,
                        height = height,
                        clipRectangles = effectivePictureClip(destination),
                        clipMask = destinationClipMask,
                        mask = maskFramebuffer,
                        maskX = maskX,
                        maskY = maskY,
                        maskAlphaAt = maskAlphaAt,
                    )
                    destinationFramebuffer.snapshotRegion(destinationX, destinationY, width, height)
                }
                XRender.OpBlendColorBurn -> {
                    destinationFramebuffer.blendSolidColorBurn(
                        pixel = solid,
                        destinationX = destinationX,
                        destinationY = destinationY,
                        width = width,
                        height = height,
                        clipRectangles = effectivePictureClip(destination),
                        clipMask = destinationClipMask,
                        mask = maskFramebuffer,
                        maskX = maskX,
                        maskY = maskY,
                        maskAlphaAt = maskAlphaAt,
                    )
                    destinationFramebuffer.snapshotRegion(destinationX, destinationY, width, height)
                }
                XRender.OpBlendHardLight -> {
                    destinationFramebuffer.blendSolidHardLight(
                        pixel = solid,
                        destinationX = destinationX,
                        destinationY = destinationY,
                        width = width,
                        height = height,
                        clipRectangles = effectivePictureClip(destination),
                        clipMask = destinationClipMask,
                        mask = maskFramebuffer,
                        maskX = maskX,
                        maskY = maskY,
                        maskAlphaAt = maskAlphaAt,
                    )
                    destinationFramebuffer.snapshotRegion(destinationX, destinationY, width, height)
                }
                XRender.OpBlendSoftLight -> {
                    destinationFramebuffer.blendSolidSoftLight(
                        pixel = solid,
                        destinationX = destinationX,
                        destinationY = destinationY,
                        width = width,
                        height = height,
                        clipRectangles = effectivePictureClip(destination),
                        clipMask = destinationClipMask,
                        mask = maskFramebuffer,
                        maskX = maskX,
                        maskY = maskY,
                        maskAlphaAt = maskAlphaAt,
                    )
                    destinationFramebuffer.snapshotRegion(destinationX, destinationY, width, height)
                }
                XRender.OpBlendDifference -> {
                    destinationFramebuffer.blendSolidDifference(
                        pixel = solid,
                        destinationX = destinationX,
                        destinationY = destinationY,
                        width = width,
                        height = height,
                        clipRectangles = effectivePictureClip(destination),
                        clipMask = destinationClipMask,
                        mask = maskFramebuffer,
                        maskX = maskX,
                        maskY = maskY,
                        maskAlphaAt = maskAlphaAt,
                    )
                    destinationFramebuffer.snapshotRegion(destinationX, destinationY, width, height)
                }
                XRender.OpBlendExclusion -> {
                    destinationFramebuffer.blendSolidExclusion(
                        pixel = solid,
                        destinationX = destinationX,
                        destinationY = destinationY,
                        width = width,
                        height = height,
                        clipRectangles = effectivePictureClip(destination),
                        clipMask = destinationClipMask,
                        mask = maskFramebuffer,
                        maskX = maskX,
                        maskY = maskY,
                        maskAlphaAt = maskAlphaAt,
                    )
                    destinationFramebuffer.snapshotRegion(destinationX, destinationY, width, height)
                }
                XRender.OpBlendHSLHue -> {
                    destinationFramebuffer.blendSolidHslHue(
                        pixel = solid,
                        destinationX = destinationX,
                        destinationY = destinationY,
                        width = width,
                        height = height,
                        clipRectangles = effectivePictureClip(destination),
                        clipMask = destinationClipMask,
                        mask = maskFramebuffer,
                        maskX = maskX,
                        maskY = maskY,
                        maskAlphaAt = maskAlphaAt,
                    )
                    destinationFramebuffer.snapshotRegion(destinationX, destinationY, width, height)
                }
                XRender.OpBlendHSLSaturation -> {
                    destinationFramebuffer.blendSolidHslSaturation(
                        pixel = solid,
                        destinationX = destinationX,
                        destinationY = destinationY,
                        width = width,
                        height = height,
                        clipRectangles = effectivePictureClip(destination),
                        clipMask = destinationClipMask,
                        mask = maskFramebuffer,
                        maskX = maskX,
                        maskY = maskY,
                        maskAlphaAt = maskAlphaAt,
                    )
                    destinationFramebuffer.snapshotRegion(destinationX, destinationY, width, height)
                }
                XRender.OpBlendHSLColor -> {
                    destinationFramebuffer.blendSolidHslColor(
                        pixel = solid,
                        destinationX = destinationX,
                        destinationY = destinationY,
                        width = width,
                        height = height,
                        clipRectangles = effectivePictureClip(destination),
                        clipMask = destinationClipMask,
                        mask = maskFramebuffer,
                        maskX = maskX,
                        maskY = maskY,
                        maskAlphaAt = maskAlphaAt,
                    )
                    destinationFramebuffer.snapshotRegion(destinationX, destinationY, width, height)
                }
                XRender.OpBlendHSLLuminosity -> {
                    destinationFramebuffer.blendSolidHslLuminosity(
                        pixel = solid,
                        destinationX = destinationX,
                        destinationY = destinationY,
                        width = width,
                        height = height,
                        clipRectangles = effectivePictureClip(destination),
                        clipMask = destinationClipMask,
                        mask = maskFramebuffer,
                        maskX = maskX,
                        maskY = maskY,
                        maskAlphaAt = maskAlphaAt,
                    )
                    destinationFramebuffer.snapshotRegion(destinationX, destinationY, width, height)
                }
                XRender.OpSaturate, XRender.OpDisjointOverReverse -> {
                    destinationFramebuffer.blendSolidSaturate(
                        pixel = solid,
                        destinationX = destinationX,
                        destinationY = destinationY,
                        width = width,
                        height = height,
                        clipRectangles = effectivePictureClip(destination),
                        clipMask = destinationClipMask,
                        mask = maskFramebuffer,
                        maskX = maskX,
                        maskY = maskY,
                        maskAlphaAt = maskAlphaAt,
                    )
                    destinationFramebuffer.snapshotRegion(destinationX, destinationY, width, height)
                }
                XRender.OpOverReverse -> {
                    destinationFramebuffer.blendSolidOverReverse(
                        pixel = solid,
                        destinationX = destinationX,
                        destinationY = destinationY,
                        width = width,
                        height = height,
                        clipRectangles = effectivePictureClip(destination),
                        clipMask = destinationClipMask,
                        mask = maskFramebuffer,
                        maskX = maskX,
                        maskY = maskY,
                        maskAlphaAt = maskAlphaAt,
                    )
                    destinationFramebuffer.snapshotRegion(destinationX, destinationY, width, height)
                }
                XRender.OpDisjointOver -> {
                    destinationFramebuffer.blendSolidDisjointOver(
                        pixel = solid,
                        destinationX = destinationX,
                        destinationY = destinationY,
                        width = width,
                        height = height,
                        clipRectangles = effectivePictureClip(destination),
                        clipMask = destinationClipMask,
                        mask = maskFramebuffer,
                        maskX = maskX,
                        maskY = maskY,
                        maskAlphaAt = maskAlphaAt,
                    )
                    destinationFramebuffer.snapshotRegion(destinationX, destinationY, width, height)
                }
                XRender.OpConjointOver -> {
                    destinationFramebuffer.blendSolidConjointOver(
                        pixel = solid,
                        destinationX = destinationX,
                        destinationY = destinationY,
                        width = width,
                        height = height,
                        clipRectangles = effectivePictureClip(destination),
                        clipMask = destinationClipMask,
                        mask = maskFramebuffer,
                        maskX = maskX,
                        maskY = maskY,
                        maskAlphaAt = maskAlphaAt,
                    )
                    destinationFramebuffer.snapshotRegion(destinationX, destinationY, width, height)
                }
                XRender.OpConjointOverReverse -> {
                    destinationFramebuffer.blendSolidConjointOverReverse(
                        pixel = solid,
                        destinationX = destinationX,
                        destinationY = destinationY,
                        width = width,
                        height = height,
                        clipRectangles = effectivePictureClip(destination),
                        clipMask = destinationClipMask,
                        mask = maskFramebuffer,
                        maskX = maskX,
                        maskY = maskY,
                        maskAlphaAt = maskAlphaAt,
                    )
                    destinationFramebuffer.snapshotRegion(destinationX, destinationY, width, height)
                }
                XRender.OpDisjointIn -> {
                    destinationFramebuffer.blendSolidDisjointIn(
                        pixel = solid,
                        destinationX = destinationX,
                        destinationY = destinationY,
                        width = width,
                        height = height,
                        clipRectangles = effectivePictureClip(destination),
                        clipMask = destinationClipMask,
                        mask = maskFramebuffer,
                        maskX = maskX,
                        maskY = maskY,
                        maskAlphaAt = maskAlphaAt,
                    )
                    XImagePixels(width, height, IntArray(width * height) { solid })
                }
                XRender.OpConjointIn -> {
                    destinationFramebuffer.blendSolidConjointIn(
                        pixel = solid,
                        destinationX = destinationX,
                        destinationY = destinationY,
                        width = width,
                        height = height,
                        clipRectangles = effectivePictureClip(destination),
                        clipMask = destinationClipMask,
                        mask = maskFramebuffer,
                        maskX = maskX,
                        maskY = maskY,
                        maskAlphaAt = maskAlphaAt,
                    )
                    XImagePixels(width, height, IntArray(width * height) { solid })
                }
                XRender.OpIn -> {
                    destinationFramebuffer.blendSolidIn(
                        pixel = solid,
                        destinationX = destinationX,
                        destinationY = destinationY,
                        width = width,
                        height = height,
                        clipRectangles = effectivePictureClip(destination),
                        clipMask = destinationClipMask,
                        mask = maskFramebuffer,
                        maskX = maskX,
                        maskY = maskY,
                        maskAlphaAt = maskAlphaAt,
                    )
                    XImagePixels(width, height, IntArray(width * height) { solid })
                }
                XRender.OpOut -> {
                    destinationFramebuffer.blendSolidOut(
                        pixel = solid,
                        destinationX = destinationX,
                        destinationY = destinationY,
                        width = width,
                        height = height,
                        clipRectangles = effectivePictureClip(destination),
                        clipMask = destinationClipMask,
                        mask = maskFramebuffer,
                        maskX = maskX,
                        maskY = maskY,
                        maskAlphaAt = maskAlphaAt,
                    )
                    XImagePixels(width, height, IntArray(width * height) { solid })
                }
                XRender.OpDisjointOut -> {
                    destinationFramebuffer.blendSolidDisjointOut(
                        pixel = solid,
                        destinationX = destinationX,
                        destinationY = destinationY,
                        width = width,
                        height = height,
                        clipRectangles = effectivePictureClip(destination),
                        clipMask = destinationClipMask,
                        mask = maskFramebuffer,
                        maskX = maskX,
                        maskY = maskY,
                        maskAlphaAt = maskAlphaAt,
                    )
                    XImagePixels(width, height, IntArray(width * height) { solid })
                }
                XRender.OpConjointOut -> {
                    destinationFramebuffer.blendSolidConjointOut(
                        pixel = solid,
                        destinationX = destinationX,
                        destinationY = destinationY,
                        width = width,
                        height = height,
                        clipRectangles = effectivePictureClip(destination),
                        clipMask = destinationClipMask,
                        mask = maskFramebuffer,
                        maskX = maskX,
                        maskY = maskY,
                        maskAlphaAt = maskAlphaAt,
                    )
                    XImagePixels(width, height, IntArray(width * height) { solid })
                }
                XRender.OpInReverse -> {
                    destinationFramebuffer.blendSolidInReverse(
                        pixel = solid,
                        destinationX = destinationX,
                        destinationY = destinationY,
                        width = width,
                        height = height,
                        clipRectangles = effectivePictureClip(destination),
                        clipMask = destinationClipMask,
                        mask = maskFramebuffer,
                        maskX = maskX,
                        maskY = maskY,
                        maskAlphaAt = maskAlphaAt,
                    )
                    destinationFramebuffer.snapshotRegion(destinationX, destinationY, width, height)
                }
                XRender.OpDisjointInReverse -> {
                    destinationFramebuffer.blendSolidDisjointInReverse(
                        pixel = solid,
                        destinationX = destinationX,
                        destinationY = destinationY,
                        width = width,
                        height = height,
                        clipRectangles = effectivePictureClip(destination),
                        clipMask = destinationClipMask,
                        mask = maskFramebuffer,
                        maskX = maskX,
                        maskY = maskY,
                        maskAlphaAt = maskAlphaAt,
                    )
                    destinationFramebuffer.snapshotRegion(destinationX, destinationY, width, height)
                }
                XRender.OpConjointInReverse -> {
                    destinationFramebuffer.blendSolidConjointInReverse(
                        pixel = solid,
                        destinationX = destinationX,
                        destinationY = destinationY,
                        width = width,
                        height = height,
                        clipRectangles = effectivePictureClip(destination),
                        clipMask = destinationClipMask,
                        mask = maskFramebuffer,
                        maskX = maskX,
                        maskY = maskY,
                        maskAlphaAt = maskAlphaAt,
                    )
                    destinationFramebuffer.snapshotRegion(destinationX, destinationY, width, height)
                }
                XRender.OpOutReverse -> {
                    destinationFramebuffer.blendSolidOutReverse(
                        pixel = solid,
                        destinationX = destinationX,
                        destinationY = destinationY,
                        width = width,
                        height = height,
                        clipRectangles = effectivePictureClip(destination),
                        clipMask = destinationClipMask,
                        mask = maskFramebuffer,
                        maskX = maskX,
                        maskY = maskY,
                        maskAlphaAt = maskAlphaAt,
                    )
                    destinationFramebuffer.snapshotRegion(destinationX, destinationY, width, height)
                }
                XRender.OpDisjointOutReverse -> {
                    destinationFramebuffer.blendSolidDisjointOutReverse(
                        pixel = solid,
                        destinationX = destinationX,
                        destinationY = destinationY,
                        width = width,
                        height = height,
                        clipRectangles = effectivePictureClip(destination),
                        clipMask = destinationClipMask,
                        mask = maskFramebuffer,
                        maskX = maskX,
                        maskY = maskY,
                        maskAlphaAt = maskAlphaAt,
                    )
                    destinationFramebuffer.snapshotRegion(destinationX, destinationY, width, height)
                }
                XRender.OpConjointOutReverse -> {
                    destinationFramebuffer.blendSolidConjointOutReverse(
                        pixel = solid,
                        destinationX = destinationX,
                        destinationY = destinationY,
                        width = width,
                        height = height,
                        clipRectangles = effectivePictureClip(destination),
                        clipMask = destinationClipMask,
                        mask = maskFramebuffer,
                        maskX = maskX,
                        maskY = maskY,
                        maskAlphaAt = maskAlphaAt,
                    )
                    destinationFramebuffer.snapshotRegion(destinationX, destinationY, width, height)
                }
                XRender.OpConjointAtop -> {
                    destinationFramebuffer.blendSolidConjointAtop(
                        pixel = solid,
                        destinationX = destinationX,
                        destinationY = destinationY,
                        width = width,
                        height = height,
                        clipRectangles = effectivePictureClip(destination),
                        clipMask = destinationClipMask,
                        mask = maskFramebuffer,
                        maskX = maskX,
                        maskY = maskY,
                        maskAlphaAt = maskAlphaAt,
                    )
                    destinationFramebuffer.snapshotRegion(destinationX, destinationY, width, height)
                }
                XRender.OpConjointAtopReverse -> {
                    destinationFramebuffer.blendSolidConjointAtopReverse(
                        pixel = solid,
                        destinationX = destinationX,
                        destinationY = destinationY,
                        width = width,
                        height = height,
                        clipRectangles = effectivePictureClip(destination),
                        clipMask = destinationClipMask,
                        mask = maskFramebuffer,
                        maskX = maskX,
                        maskY = maskY,
                        maskAlphaAt = maskAlphaAt,
                    )
                    destinationFramebuffer.snapshotRegion(destinationX, destinationY, width, height)
                }
                XRender.OpAtop -> {
                    destinationFramebuffer.blendSolidAtop(
                        pixel = solid,
                        destinationX = destinationX,
                        destinationY = destinationY,
                        width = width,
                        height = height,
                        clipRectangles = effectivePictureClip(destination),
                        clipMask = destinationClipMask,
                        mask = maskFramebuffer,
                        maskX = maskX,
                        maskY = maskY,
                        maskAlphaAt = maskAlphaAt,
                    )
                    destinationFramebuffer.snapshotRegion(destinationX, destinationY, width, height)
                }
                XRender.OpDisjointAtop -> {
                    destinationFramebuffer.blendSolidDisjointAtop(
                        pixel = solid,
                        destinationX = destinationX,
                        destinationY = destinationY,
                        width = width,
                        height = height,
                        clipRectangles = effectivePictureClip(destination),
                        clipMask = destinationClipMask,
                        mask = maskFramebuffer,
                        maskX = maskX,
                        maskY = maskY,
                        maskAlphaAt = maskAlphaAt,
                    )
                    destinationFramebuffer.snapshotRegion(destinationX, destinationY, width, height)
                }
                XRender.OpAtopReverse -> {
                    destinationFramebuffer.blendSolidAtopReverse(
                        pixel = solid,
                        destinationX = destinationX,
                        destinationY = destinationY,
                        width = width,
                        height = height,
                        clipRectangles = effectivePictureClip(destination),
                        clipMask = destinationClipMask,
                        mask = maskFramebuffer,
                        maskX = maskX,
                        maskY = maskY,
                        maskAlphaAt = maskAlphaAt,
                    )
                    destinationFramebuffer.snapshotRegion(destinationX, destinationY, width, height)
                }
                XRender.OpDisjointAtopReverse -> {
                    destinationFramebuffer.blendSolidDisjointAtopReverse(
                        pixel = solid,
                        destinationX = destinationX,
                        destinationY = destinationY,
                        width = width,
                        height = height,
                        clipRectangles = effectivePictureClip(destination),
                        clipMask = destinationClipMask,
                        mask = maskFramebuffer,
                        maskX = maskX,
                        maskY = maskY,
                        maskAlphaAt = maskAlphaAt,
                    )
                    destinationFramebuffer.snapshotRegion(destinationX, destinationY, width, height)
                }
                XRender.OpDisjointXor -> {
                    destinationFramebuffer.blendSolidDisjointXor(
                        pixel = solid,
                        destinationX = destinationX,
                        destinationY = destinationY,
                        width = width,
                        height = height,
                        clipRectangles = effectivePictureClip(destination),
                        clipMask = destinationClipMask,
                        mask = maskFramebuffer,
                        maskX = maskX,
                        maskY = maskY,
                        maskAlphaAt = maskAlphaAt,
                    )
                    destinationFramebuffer.snapshotRegion(destinationX, destinationY, width, height)
                }
                XRender.OpConjointXor -> {
                    destinationFramebuffer.blendSolidConjointXor(
                        pixel = solid,
                        destinationX = destinationX,
                        destinationY = destinationY,
                        width = width,
                        height = height,
                        clipRectangles = effectivePictureClip(destination),
                        clipMask = destinationClipMask,
                        mask = maskFramebuffer,
                        maskX = maskX,
                        maskY = maskY,
                        maskAlphaAt = maskAlphaAt,
                    )
                    destinationFramebuffer.snapshotRegion(destinationX, destinationY, width, height)
                }
                XRender.OpXor -> {
                    destinationFramebuffer.blendSolidXor(
                        pixel = solid,
                        destinationX = destinationX,
                        destinationY = destinationY,
                        width = width,
                        height = height,
                        clipRectangles = effectivePictureClip(destination),
                        clipMask = destinationClipMask,
                        mask = maskFramebuffer,
                        maskX = maskX,
                        maskY = maskY,
                        maskAlphaAt = maskAlphaAt,
                    )
                    destinationFramebuffer.snapshotRegion(destinationX, destinationY, width, height)
                }
                else -> {
                    destinationFramebuffer.blendSolidOver(
                        pixel = solid,
                        destinationX = destinationX,
                        destinationY = destinationY,
                        width = width,
                        height = height,
                        clipRectangles = effectivePictureClip(destination),
                        clipMask = destinationClipMask,
                        mask = maskFramebuffer,
                        maskX = maskX,
                        maskY = maskY,
                        maskAlphaAt = maskAlphaAt,
                    )
                    XImagePixels(width, height, IntArray(width * height) { solid })
                }
            }
        }
        val sourceDrawableId = source.drawableId ?: return null
        val sourceFramebuffer = windows[sourceDrawableId]?.framebuffer ?: pixmaps[sourceDrawableId]?.framebuffer ?: return null
        if (source.transform != IdentityTransform || source.repeat != XRender.RepeatNone) {
            return destinationFramebuffer.compositeGenerated(
                sourceX = sourceX,
                sourceY = sourceY,
                destinationX = destinationX,
                destinationY = destinationY,
                width = width,
                height = height,
                operation = operation,
                clipRectangles = effectivePictureClip(destination),
                clipMask = destinationClipMask,
                mask = maskFramebuffer,
                maskX = maskX,
                maskY = maskY,
                maskAlphaAt = maskAlphaAt,
            ) { x, y ->
                source.sampleDrawablePixel(sourceFramebuffer, x + 0.5, y + 0.5, source.filterName) ?: 0
            }
        }
        val sourceSnapshot = sourceFramebuffer.snapshot().takeIf { sourceDrawableId == destinationDrawableId }
        return destinationFramebuffer.compositeGenerated(
            sourceX = sourceX,
            sourceY = sourceY,
            destinationX = destinationX,
            destinationY = destinationY,
            width = width,
            height = height,
            operation = operation,
            clipRectangles = effectivePictureClip(destination),
            clipMask = destinationClipMask,
            mask = maskFramebuffer,
            maskX = maskX,
            maskY = maskY,
            maskAlphaAt = maskAlphaAt,
        ) { x, y ->
            if (sourceSnapshot != null) {
                if (x in 0 until sourceSnapshot.width && y in 0 until sourceSnapshot.height) {
                    source.withAlphaMap(sourceSnapshot.pixels[y * sourceSnapshot.width + x], x + 0.5, y + 0.5) ?: 0
                } else {
                    0
                }
            } else {
                sourceFramebuffer.pixelAt(x, y)?.let { source.withAlphaMap(it, x + 0.5, y + 0.5) } ?: 0
            }
        }
    }

    @Synchronized
    fun scale(
        colorScale: Int,
        alphaScale: Int,
        source: XPicture,
        destination: XPicture,
        sourceX: Int,
        sourceY: Int,
        destinationX: Int,
        destinationY: Int,
        width: Int,
        height: Int,
    ): XImagePixels? {
        val destinationDrawableId = destination.drawableId ?: return null
        val destinationFramebuffer = windows[destinationDrawableId]?.framebuffer ?: pixmaps[destinationDrawableId]?.framebuffer ?: return null
        val sourcePixelAt = scaledSourcePixelAt(source, colorScale, alphaScale) ?: return null
        return destinationFramebuffer.compositeGeneratedOptional(
            sourceX = sourceX,
            sourceY = sourceY,
            destinationX = destinationX,
            destinationY = destinationY,
            width = width,
            height = height,
            operation = XRender.OpSrc,
            clipRectangles = effectivePictureClip(destination),
            clipMask = destination.clipMaskPredicate(),
        ) { x, y ->
            sourcePixelAt(x, y)
        }
    }

    private fun scaledSourcePixelAt(source: XPicture, colorScale: Int, alphaScale: Int): ((x: Int, y: Int) -> Int?)? {
        val gradientSampler = source.gradientSampler()
        if (gradientSampler != null) {
            return { x, y ->
                if (source.insidePictureClip(x, y)) {
                    source.withAlphaMap(gradientSampler(x, y), x + 0.5, y + 0.5)
                        ?.let { scaledPixel(it, colorScale, alphaScale) }
                } else {
                    null
                }
            }
        }
        val solid = source.solidPixel
        if (solid != null) {
            return { x, y -> if (source.insidePictureClip(x, y)) source.withAlphaMap(solid, x + 0.5, y + 0.5)?.let { scaledPixel(it, colorScale, alphaScale) } else null }
        }
        val sourceDrawableId = source.drawableId ?: return null
        val sourceFramebuffer = windows[sourceDrawableId]?.framebuffer ?: pixmaps[sourceDrawableId]?.framebuffer ?: return null
        return { x, y ->
            if (source.insidePictureClip(x, y)) {
                if (source.alphaMap == 0) {
                    val sample = transformedPoint(x + 0.5, y + 0.5, source.transform)
                    if (source.scaleClipsBaseDrawable(sample.first, sample.second, sourceFramebuffer.width, sourceFramebuffer.height)) {
                        null
                    } else {
                        sourceFramebuffer.samplePixelAt(sample.first, sample.second, source.repeat, source.filterName)
                            ?.let { scaledPixel(it, colorScale, alphaScale) }
                    }
                } else {
                    source.sampleDrawablePixel(sourceFramebuffer, x + 0.5, y + 0.5, source.filterName)
                        ?.let { scaledPixel(it, colorScale, alphaScale) }
                }
            } else {
                null
            }
        }
    }

    private fun XPicture.scaleClipsBaseDrawable(x: Double, y: Double, width: Int, height: Int): Boolean =
        repeat == XRender.RepeatNone &&
            (x < 0.5 || x >= width + 0.5 || y < 0.5 || y >= height + 0.5)

    private fun scaledPixel(pixel: Int, colorScale: Int, alphaScale: Int): Int {
        fun unsigned(scale: Int): Long = Integer.toUnsignedLong(scale)
        fun channel(shift: Int, scale: Int): Int =
            ((((pixel ushr shift) and 0xff).toLong() * unsigned(scale)) / 65_536L)
                .coerceIn(0L, 255L)
                .toInt()
        val alpha = channel(24, alphaScale)
        val red = channel(16, colorScale)
        val green = channel(8, colorScale)
        val blue = channel(0, colorScale)
        return (alpha shl 24) or (red shl 16) or (green shl 8) or blue
    }

    private fun XPicture.maskAlphaSampler(): ((x: Int, y: Int) -> Int?)? {
        solidPixel?.let { pixel ->
            return { x, y ->
                if (insidePictureClip(x, y)) {
                    withAlphaMap(pixel, x + 0.5, y + 0.5)?.let { (it ushr 24) and 0xff }
                } else {
                    0
                }
            }
        }
        gradientSampler()?.let { sampler ->
            return { x, y ->
                if (insidePictureClip(x, y)) {
                    withAlphaMap(sampler(x, y), x + 0.5, y + 0.5)?.let { (it ushr 24) and 0xff }
                } else {
                    0
                }
            }
        }
        if (transform == IdentityTransform && repeat == XRender.RepeatNone && !hasPictureClip() && alphaMap == 0) return null
        val framebuffer = drawableId?.let { windows[it]?.framebuffer ?: pixmaps[it]?.framebuffer } ?: return null
        return { x, y ->
            if (insidePictureClip(x, y)) {
                sampleMaskDrawablePixel(framebuffer, x + 0.5, y + 0.5, filterName)?.let { (it ushr 24) and 0xff }
            } else {
                0
            }
        }
    }

    private fun XPicture.componentMaskSampler(): ((x: Int, y: Int) -> Int?)? {
        solidPixel?.let { pixel -> return { x, y -> if (insidePictureClip(x, y)) withAlphaMap(pixel, x + 0.5, y + 0.5) else 0 } }
        gradientSampler()?.let { sampler ->
            return { x, y -> if (insidePictureClip(x, y)) withAlphaMap(sampler(x, y), x + 0.5, y + 0.5) else 0 }
        }
        val framebuffer = drawableId?.let { windows[it]?.framebuffer ?: pixmaps[it]?.framebuffer } ?: return null
        return { x, y -> if (insidePictureClip(x, y)) sampleMaskDrawablePixel(framebuffer, x + 0.5, y + 0.5, filterName) else 0 }
    }

    private fun XFramebuffer.samplePixelAt(x: Double, y: Double, repeat: Int, filterName: String?): Int? {
        if (isBilinearFilter(filterName)) {
            return bilinearPixelAt(x, y, repeat)
        }
        if (repeat == XRender.RepeatNone && (x < 0.0 || x >= width || y < 0.0 || y >= height)) {
            return null
        }
        val sampleX = repeatedPixelCoordinate(x, width, repeat) ?: return null
        val sampleY = repeatedPixelCoordinate(y, height, repeat) ?: return null
        return pixelAt(sampleX, sampleY)
    }

    private fun XImagePixels.samplePixelAt(x: Double, y: Double, repeat: Int, filterName: String?): Int? {
        fun snapshotPixelAt(px: Int, py: Int): Int? =
            if (px in 0 until width && py in 0 until height) {
                pixels[py * width + px]
            } else {
                null
            }
        if (isBilinearFilter(filterName)) {
            return bilinearPixelAt(x, y, repeat, width, height, ::snapshotPixelAt)
        }
        if (repeat == XRender.RepeatNone && (x < 0.0 || x >= width || y < 0.0 || y >= height)) {
            return null
        }
        val sampleX = repeatedPixelCoordinate(x, width, repeat) ?: return null
        val sampleY = repeatedPixelCoordinate(y, height, repeat) ?: return null
        return snapshotPixelAt(sampleX, sampleY)
    }

    private fun XPicture.sampleDrawablePixel(framebuffer: XFramebuffer, x: Double, y: Double, effectiveFilterName: String?): Int? {
        val sample = transformedPoint(x, y, transform)
        if (alphaMapClipsBaseDrawable(sample.first, sample.second, framebuffer.width, framebuffer.height)) return null
        val pixel = framebuffer.samplePixelAt(sample.first, sample.second, repeat, effectiveFilterName)
            ?: return if (alphaMap == 0) 0 else null
        return withAlphaMap(pixel, sample.first, sample.second)
    }

    private fun XPicture.sampleDrawablePixel(snapshot: XImagePixels, x: Double, y: Double, effectiveFilterName: String?): Int? {
        val sample = transformedPoint(x, y, transform)
        if (alphaMapClipsBaseDrawable(sample.first, sample.second, snapshot.width, snapshot.height)) return null
        val pixel = snapshot.samplePixelAt(sample.first, sample.second, repeat, effectiveFilterName)
            ?: return if (alphaMap == 0) 0 else null
        return withAlphaMap(pixel, sample.first, sample.second)
    }

    private fun XPicture.sampleMaskDrawablePixel(framebuffer: XFramebuffer, x: Double, y: Double, effectiveFilterName: String?): Int? {
        val sample = transformedPoint(x, y, transform)
        if (alphaMapClipsBaseDrawable(sample.first, sample.second, framebuffer.width, framebuffer.height)) return null
        val pixel = framebuffer.samplePixelAt(sample.first, sample.second, repeat, effectiveFilterName)
            ?: return if (alphaMap == 0) 0 else null
        return withAlphaMap(pixel, sample.first, sample.second)
    }

    private fun XPicture.alphaMapClipsBaseDrawable(x: Double, y: Double, width: Int, height: Int): Boolean =
        alphaMap != 0 &&
            repeat == XRender.RepeatNone &&
            (x < 0.0 || x >= width || y < 0.0 || y >= height)

    private fun XPicture.withAlphaMap(pixel: Int, x: Double, y: Double): Int? {
        val alphaMapId = alphaMap
        if (alphaMapId == 0) return pixel
        val alphaPicture = pictures[alphaMapId] ?: return null
        val mapX = x - alphaXOrigin
        val mapY = y - alphaYOrigin
        if (!alphaPicture.insidePictureClip(mapX, mapY)) return null
        val alphaFramebuffer = alphaPicture.drawableId?.let { windows[it]?.framebuffer ?: pixmaps[it]?.framebuffer } ?: return null
        val alphaPixel = alphaFramebuffer.samplePixelAt(mapX, mapY, XRender.RepeatNone, filterName = null)
            ?: return null
        val alpha = (alphaPixel ushr 24) and 0xff
        return (pixel and 0x00ff_ffff) or (alpha shl 24)
    }

    private fun XFramebuffer.bilinearPixelAt(x: Double, y: Double, repeat: Int): Int {
        return bilinearPixelAt(x, y, repeat, width, height, ::pixelAt)
    }

    private fun bilinearPixelAt(
        x: Double,
        y: Double,
        repeat: Int,
        width: Int,
        height: Int,
        pixelAt: (Int, Int) -> Int?,
    ): Int {
        val sourceX = x - 0.5
        val sourceY = y - 0.5
        val x0 = floor(sourceX).toInt()
        val y0 = floor(sourceY).toInt()
        val fx = sourceX - x0
        val fy = sourceY - y0
        val p00 = repeatedPixelAt(x0, y0, repeat, width, height, pixelAt)
        val p10 = repeatedPixelAt(x0 + 1, y0, repeat, width, height, pixelAt)
        val p01 = repeatedPixelAt(x0, y0 + 1, repeat, width, height, pixelAt)
        val p11 = repeatedPixelAt(x0 + 1, y0 + 1, repeat, width, height, pixelAt)
        return bilinearPixel(p00, p10, p01, p11, fx, fy)
    }

    private fun repeatedPixelAt(
        x: Int,
        y: Int,
        repeat: Int,
        width: Int,
        height: Int,
        pixelAt: (Int, Int) -> Int?,
    ): Int {
        val sampleX = repeatedPixelIndex(x, width, repeat) ?: return 0
        val sampleY = repeatedPixelIndex(y, height, repeat) ?: return 0
        return pixelAt(sampleX, sampleY) ?: 0
    }

    private fun repeatedPixelIndex(index: Int, size: Int, repeat: Int): Int? {
        if (size <= 0) return null
        if (repeat == XRender.RepeatNone) return index.takeIf { it in 0 until size }
        return when (repeat) {
            XRender.RepeatPad -> index.coerceIn(0, size - 1)
            XRender.RepeatNormal -> ((index % size) + size) % size
            XRender.RepeatReflect -> {
                val period = size * 2
                val offset = ((index % period) + period) % period
                if (offset < size) offset else period - offset - 1
            }
            else -> index.takeIf { it in 0 until size }
        }
    }

    private fun bilinearPixel(p00: Int, p10: Int, p01: Int, p11: Int, fx: Double, fy: Double): Int {
        fun interpolate(value00: Double, value10: Double, value01: Double, value11: Double): Double {
            val top = value00 + (value10 - value00) * fx
            val bottom = value01 + (value11 - value01) * fx
            return top + (bottom - top) * fy
        }
        fun alpha(pixel: Int): Int = (pixel ushr 24) and 0xff
        fun premultiplied(pixel: Int, shift: Int): Double = ((pixel ushr shift) and 0xff) * alpha(pixel) / 255.0
        val outAlpha = interpolate(alpha(p00).toDouble(), alpha(p10).toDouble(), alpha(p01).toDouble(), alpha(p11).toDouble())
            .roundToInt()
            .coerceIn(0, 255)
        if (outAlpha == 0) return 0
        fun channel(shift: Int): Int {
            val premultipliedChannel = interpolate(
                premultiplied(p00, shift),
                premultiplied(p10, shift),
                premultiplied(p01, shift),
                premultiplied(p11, shift),
            )
            return (premultipliedChannel * 255.0 / outAlpha).roundToInt().coerceIn(0, 255)
        }
        return (outAlpha shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    }

    // XRender exposes good/best as higher-quality filters; use bilinear until convolution filters are implemented.
    private fun isBilinearFilter(filterName: String?): Boolean =
        filterName == "bilinear" || filterName == "good" || filterName == "best"

    private fun repeatedPixelCoordinate(coordinate: Double, size: Int, repeat: Int): Int? {
        if (size <= 0) return null
        if (repeat == XRender.RepeatNone) {
            val pixel = floor(coordinate).toInt()
            return pixel.takeIf { it in 0 until size }
        }
        val repeated = when (repeat) {
            XRender.RepeatPad -> coordinate.coerceIn(0.0, size.toDouble())
            XRender.RepeatNormal -> wrapCoordinate(coordinate, size)
            XRender.RepeatReflect -> reflectCoordinate(coordinate, size)
            else -> coordinate
        }
        return floor(repeated).toInt().coerceIn(0, size - 1)
    }

    private fun wrapCoordinate(coordinate: Double, size: Int): Double =
        ((coordinate % size) + size) % size

    private fun reflectCoordinate(coordinate: Double, size: Int): Double {
        val period = size * 2.0
        val offset = ((coordinate % period) + period) % period
        return if (offset <= size) offset else period - offset
    }

    private fun XPicture.gradientSampler(): ((x: Int, y: Int) -> Int)? =
        linearGradient?.pixelSampler(repeat, transform)
            ?: radialGradient?.pixelSampler(repeat, transform)
            ?: conicalGradient?.pixelSampler(repeat, transform)

    private fun XPicture.sourcePixelSampler(snapshotDrawableId: Int? = null): ((x: Int, y: Int) -> Int)? {
        solidPixel?.let { pixel -> return { x, y -> withAlphaMap(pixel, x + 0.5, y + 0.5) ?: 0 } }
        gradientSampler()?.let { sampler -> return { x, y -> withAlphaMap(sampler(x, y), x + 0.5, y + 0.5) ?: 0 } }
        val sourceDrawableId = drawableId ?: return null
        val framebuffer = windows[sourceDrawableId]?.framebuffer ?: pixmaps[sourceDrawableId]?.framebuffer ?: return null
        val snapshot = if (sourceDrawableId == snapshotDrawableId) framebuffer.snapshot() else null
        if (snapshot != null) {
            return { x, y -> sampleDrawablePixel(snapshot, x + 0.5, y + 0.5, filterName) ?: 0 }
        }
        return { x, y -> sampleDrawablePixel(framebuffer, x + 0.5, y + 0.5, filterName) ?: 0 }
    }

    private fun XPicture.sourcePixelSamplerOptional(snapshotDrawableId: Int? = null): ((x: Int, y: Int) -> Int?)? {
        solidPixel?.let { pixel -> return { x, y -> if (insidePictureClip(x, y)) withAlphaMap(pixel, x + 0.5, y + 0.5) else null } }
        gradientSampler()?.let { sampler ->
            return { x, y -> if (insidePictureClip(x, y)) withAlphaMap(sampler(x, y), x + 0.5, y + 0.5) else null }
        }
        val sourceDrawableId = drawableId ?: return null
        val framebuffer = windows[sourceDrawableId]?.framebuffer ?: pixmaps[sourceDrawableId]?.framebuffer ?: return null
        val snapshot = if (sourceDrawableId == snapshotDrawableId) framebuffer.snapshot() else null
        return if (snapshot != null) {
            { x, y -> if (insidePictureClip(x, y)) sampleDrawablePixel(snapshot, x + 0.5, y + 0.5, filterName) else null }
        } else {
            { x, y -> if (insidePictureClip(x, y)) sampleDrawablePixel(framebuffer, x + 0.5, y + 0.5, filterName) else null }
        }
    }

    private fun XPicture.compositeSourcePixelSamplerOptional(destinationDrawableId: Int): ((x: Int, y: Int) -> Int?)? {
        solidPixel?.let { pixel -> return { x, y -> if (insidePictureClip(x, y)) withAlphaMap(pixel, x + 0.5, y + 0.5) else null } }
        gradientSampler()?.let { sampler ->
            return { x, y -> if (insidePictureClip(x, y)) withAlphaMap(sampler(x, y), x + 0.5, y + 0.5) else null }
        }
        val sourceDrawableId = drawableId ?: return null
        val framebuffer = windows[sourceDrawableId]?.framebuffer ?: pixmaps[sourceDrawableId]?.framebuffer ?: return null
        val snapshot = if (sourceDrawableId == destinationDrawableId) framebuffer.snapshot() else null
        if (snapshot != null) {
            return { x, y ->
                if (insidePictureClip(x, y)) {
                    sampleDrawablePixel(snapshot, x + 0.5, y + 0.5, filterName)
                } else {
                    null
                }
            }
        }
        return { x, y -> if (insidePictureClip(x, y)) sampleDrawablePixel(framebuffer, x + 0.5, y + 0.5, filterName) else null }
    }

    private fun XPicture.insidePictureClip(x: Int, y: Int): Boolean {
        clipRectangles?.let { rectangles ->
            val insideRectangle = rectangles.any { rectangle ->
                x >= rectangle.x &&
                    y >= rectangle.y &&
                    x < rectangle.x + rectangle.width &&
                    y < rectangle.y + rectangle.height
            }
            if (!insideRectangle) return false
        }
        return clipMaskPredicate()?.invoke(x, y) ?: true
    }

    private fun XPicture.insidePictureClip(x: Double, y: Double): Boolean =
        insidePictureClip(floor(x).toInt(), floor(y).toInt())

    private fun XPicture.hasPictureClip(): Boolean =
        clipRectangles != null || clipMask != 0

    private fun XPicture.clipMaskPredicate(): XClipMask? {
        val maskId = clipMask
        if (maskId == 0) return null
        val maskImage = clipMaskImage ?: return { _, _ -> false }
        val originX = clipXOrigin
        val originY = clipYOrigin
        return { x, y -> maskImage.alphaAt(x - originX, y - originY) != 0 }
    }

    private fun XImagePixels.alphaAt(x: Int, y: Int): Int =
        if (x in 0 until width && y in 0 until height) {
            (pixels[y * width + x] ushr 24) and 0xff
        } else {
            0
        }

    private fun glyphMaskFromPicture(
        format: Int,
        width: Int,
        height: Int,
        sourceX: Int,
        sourceY: Int,
        sourcePixelAt: (x: Int, y: Int) -> Int,
    ): XFramebuffer? {
        if (width <= 0 || height <= 0) return null
        if (width.toLong() * height.toLong() > MaxGlyphMaskPixels) return null
        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val alpha = (sourcePixelAt(sourceX + x, sourceY + y) ushr 24) and 0xff
                pixels[y * width + x] = when (format) {
                    XRender.A1Format -> if (alpha >= 0x80) 0xff shl 24 else 0
                    else -> alpha shl 24
                }
            }
        }
        return XFramebuffer(width, height, painted = true).also { framebuffer ->
            framebuffer.putImage(0, 0, XImagePixels(width, height, pixels))
        }
    }

    private fun XPicture.sourcePixelSamplerAt(snapshotDrawableId: Int? = null, filterNameOverride: String? = null): ((x: Double, y: Double) -> Int?)? {
        solidPixel?.let { pixel -> return { x, y -> if (insidePictureClip(x, y)) withAlphaMap(pixel, x, y) else null } }
        gradientSampler()?.let { sampler ->
            return { x, y ->
                if (insidePictureClip(x, y)) {
                    withAlphaMap(sampler(floor(x).toInt(), floor(y).toInt()), x, y)
                } else {
                    null
                }
            }
        }
        val sourceDrawableId = drawableId ?: return null
        val framebuffer = windows[sourceDrawableId]?.framebuffer ?: pixmaps[sourceDrawableId]?.framebuffer ?: return null
        val effectiveFilterName = filterNameOverride ?: filterName
        val snapshot = if (sourceDrawableId == snapshotDrawableId) framebuffer.snapshot() else null
        return if (snapshot != null) {
            { x, y -> if (insidePictureClip(x, y)) sampleDrawablePixel(snapshot, x, y, effectiveFilterName) else null }
        } else {
            { x, y -> if (insidePictureClip(x, y)) sampleDrawablePixel(framebuffer, x, y, effectiveFilterName) else null }
        }
    }

    private fun XFixedLine.xAt(y: Double): Double {
        val y1 = p1.y.fixedToDouble()
        val y2 = p2.y.fixedToDouble()
        val x1 = p1.x.fixedToDouble()
        val x2 = p2.x.fixedToDouble()
        if (y2 == y1) return x1
        return x1 + (x2 - x1) * ((y - y1) / (y2 - y1))
    }

    private fun XLinearGradient.pixelSampler(repeat: Int, transform: List<Int>): (x: Int, y: Int) -> Int {
        val pairs = stops.zip(colors).sortedBy { it.first }
        if (pairs.isEmpty()) return { _, _ -> 0xff00_0000.toInt() }
        val x1 = p1.x.fixedToDouble()
        val y1 = p1.y.fixedToDouble()
        val x2 = p2.x.fixedToDouble()
        val y2 = p2.y.fixedToDouble()
        val dx = x2 - x1
        val dy = y2 - y1
        val denominator = dx * dx + dy * dy
        val fixedStops = pairs.map { it.first.fixedToDouble() }
        return { x, y ->
            val position = if (denominator == 0.0) {
                0.0
            } else {
                val sample = transformedPoint(x + 0.5, y + 0.5, transform)
                val sampleX = sample.first
                val sampleY = sample.second
                ((sampleX - x1) * dx + (sampleY - y1) * dy) / denominator
            }
            sampleGradientPosition(position, pairs, fixedStops, repeat)
        }
    }

    private fun XRadialGradient.pixelSampler(repeat: Int, transform: List<Int>): (x: Int, y: Int) -> Int {
        val pairs = stops.zip(colors).sortedBy { it.first }
        if (pairs.isEmpty()) return { _, _ -> 0xff00_0000.toInt() }
        val x1 = inner.center.x.fixedToDouble()
        val y1 = inner.center.y.fixedToDouble()
        val r1 = inner.radius.fixedToDouble()
        val x2 = outer.center.x.fixedToDouble()
        val y2 = outer.center.y.fixedToDouble()
        val r2 = outer.radius.fixedToDouble()
        val dx = x2 - x1
        val dy = y2 - y1
        val dr = r2 - r1
        val a = dx * dx + dy * dy - dr * dr
        val fixedStops = pairs.map { it.first.fixedToDouble() }
        return { x, y ->
            val sample = transformedPoint(x + 0.5, y + 0.5, transform)
            val pdx = sample.first - x1
            val pdy = sample.second - y1
            val b = pdx * dx + pdy * dy + r1 * dr
            val c = pdx * pdx + pdy * pdy - r1 * r1
            sampleGradientPosition(radialPosition(a, b, c, r1, dr, repeat), pairs, fixedStops, repeat)
        }
    }

    private fun radialPosition(a: Double, b: Double, c: Double, r1: Double, dr: Double, repeat: Int): Double? {
        fun valid(position: Double): Boolean =
            if (repeat == XRender.RepeatNone) {
                position in 0.0..1.0
            } else {
                r1 + position * dr >= 0.0
            }
        if (a == 0.0) {
            if (b == 0.0) return null
            val position = c / (2.0 * b)
            return position.takeIf(::valid)
        }
        val discriminant = b * b - a * c
        if (discriminant < 0.0) return null
        val root = sqrt(discriminant)
        val first = (b + root) / a
        val second = (b - root) / a
        return when {
            valid(first) -> first
            valid(second) -> second
            else -> null
        }
    }

    private fun XConicalGradient.pixelSampler(repeat: Int, transform: List<Int>): (x: Int, y: Int) -> Int {
        val pairs = stops.zip(colors).sortedBy { it.first }
        if (pairs.isEmpty()) return { _, _ -> 0xff00_0000.toInt() }
        val centerX = center.x.fixedToDouble()
        val centerY = center.y.fixedToDouble()
        val angleRadians = angle.fixedToDouble() / 180.0 * PI
        val fixedStops = pairs.map { it.first.fixedToDouble() }
        return { x, y ->
            val sample = transformedPoint(x + 0.5, y + 0.5, transform)
            val radians = normalizeRadians(atan2(sample.second - centerY, sample.first - centerX) + angleRadians)
            val position = 1.0 - radians / (2.0 * PI)
            sampleGradientPosition(position, pairs, fixedStops, repeat)
        }
    }

    private fun normalizeRadians(radians: Double): Double =
        ((radians % (2.0 * PI)) + (2.0 * PI)) % (2.0 * PI)

    private fun transformedPoint(x: Double, y: Double, transform: List<Int>): Pair<Double, Double> {
        if (transform.size != 9 || transform == IdentityTransform) return x to y
        val m00 = transform[0].fixedToDouble()
        val m01 = transform[1].fixedToDouble()
        val m02 = transform[2].fixedToDouble()
        val m10 = transform[3].fixedToDouble()
        val m11 = transform[4].fixedToDouble()
        val m12 = transform[5].fixedToDouble()
        val m20 = transform[6].fixedToDouble()
        val m21 = transform[7].fixedToDouble()
        val m22 = transform[8].fixedToDouble()
        val w = m20 * x + m21 * y + m22
        if (w == 0.0) return x to y
        return ((m00 * x + m01 * y + m02) / w) to ((m10 * x + m11 * y + m12) / w)
    }

    private fun sampleGradientPosition(position: Double?, pairs: List<Pair<Int, Int>>, fixedStops: List<Double>, repeat: Int): Int {
        val repeatedPosition = position?.let { repeatPosition(it, fixedStops.first(), fixedStops.last(), repeat) }
        return if (repeatedPosition == null) {
            0
        } else if (repeat == XRender.RepeatNormal) {
            normalRepeatPixel(repeatedPosition, pairs, fixedStops)
        } else {
            stopPixel(repeatedPosition, pairs, fixedStops)
        }
    }

    private fun normalRepeatPixel(position: Double, pairs: List<Pair<Int, Int>>, fixedStops: List<Double>): Int {
        if (position < fixedStops.first()) {
            val startStop = fixedStops.last() - 1.0
            val endStop = fixedStops.first()
            return interpolateStopPixel(position, startStop, endStop, pairs.last().second, pairs.first().second)
        }
        if (position > fixedStops.last()) {
            val startStop = fixedStops.last()
            val endStop = fixedStops.first() + 1.0
            return interpolateStopPixel(position, startStop, endStop, pairs.last().second, pairs.first().second)
        }
        return stopPixel(position, pairs, fixedStops)
    }

    private fun stopPixel(position: Double, pairs: List<Pair<Int, Int>>, fixedStops: List<Double>): Int {
        if (position <= fixedStops.first()) return pairs.first().second
        var pixel = pairs.last().second
        for (index in 0 until pairs.lastIndex) {
            val startStop = fixedStops[index]
            val endStop = fixedStops[index + 1]
            if (position <= endStop) {
                pixel = interpolateStopPixel(position, startStop, endStop, pairs[index].second, pairs[index + 1].second)
                break
            }
        }
        return pixel
    }

    private fun interpolateStopPixel(position: Double, startStop: Double, endStop: Double, startPixel: Int, endPixel: Int): Int {
        if (endStop <= startStop) return endPixel
        val ratio = (position - startStop) / (endStop - startStop)
        return interpolatePixel(startPixel, endPixel, ratio)
    }

    private fun repeatPosition(position: Double, start: Double, end: Double, repeat: Int): Double? {
        if (end <= start) return end
        return when (repeat) {
            XRender.RepeatPad -> position.coerceIn(start, end)
            XRender.RepeatNormal -> wrapPosition(position)
            XRender.RepeatReflect -> reflectPosition(position)
            else -> position.takeIf { it in start..end }
        }
    }

    private fun wrapPosition(position: Double): Double =
        ((position % 1.0) + 1.0) % 1.0

    private fun reflectPosition(position: Double): Double {
        val offset = ((position % 2.0) + 2.0) % 2.0
        return if (offset <= 1.0) offset else 2.0 - offset
    }

    private fun interpolatePixel(start: Int, end: Int, ratio: Double): Int {
        fun channel(shift: Int): Int {
            val a = (start ushr shift) and 0xff
            val b = (end ushr shift) and 0xff
            return (a + (b - a) * ratio).roundToInt().coerceIn(0, 255)
        }
        return (channel(24) shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    }

    private fun Int.fixedToDouble(): Double = this / 65_536.0

    @Synchronized
    fun getImage(
        drawableId: Int,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
    ): XImagePixels? {
        if (width <= 0 || height <= 0) return XImagePixels(0, 0, IntArray(0))
        if (width.toLong() * height.toLong() > MaxGetImagePixels) return null
        val framebuffer = windows[drawableId]?.framebuffer ?: pixmaps[drawableId]?.framebuffer ?: return null
        val pixels = IntArray(width * height)
        for (row in 0 until height) {
            for (column in 0 until width) {
                pixels[row * width + column] = framebuffer.pixelAt(x + column, y + row) ?: 0
            }
        }
        return XImagePixels(width, height, pixels)
    }

    @Synchronized
    fun fillRectangles(
        drawableId: Int,
        pixel: Int,
        rectangles: List<XRectangleCommand>,
        preserveAlpha: Boolean = false,
        clipRectangles: List<XRectangleCommand>? = null,
        function: Int = XGraphicsContext.GXcopy,
        planeMask: Int = -1,
        fillStyle: Int = XGraphicsContext.FillSolid,
        background: Int = 0x00ff_ffff,
        tilePixmap: XImagePixels? = null,
        stipplePixmap: XImagePixels? = null,
        tileStippleXOrigin: Int = 0,
        tileStippleYOrigin: Int = 0,
    ): Boolean {
        val framebuffer = windows[drawableId]?.framebuffer ?: pixmaps[drawableId]?.framebuffer ?: return false
        val effectiveClip = effectiveDrawableClip(drawableId, clipRectangles)
        val pattern = fillPattern(fillStyle, pixel, background, tilePixmap, stipplePixmap)
        var painted = false
        for (rectangle in rectangles) {
            painted = if (pattern != null) {
                framebuffer.fillPattern(
                    x = rectangle.x,
                    y = rectangle.y,
                    width = rectangle.width,
                    height = rectangle.height,
                    patternWidth = pattern.width,
                    patternHeight = pattern.height,
                    patternXOrigin = tileStippleXOrigin,
                    patternYOrigin = tileStippleYOrigin,
                    clipRectangles = effectiveClip,
                    function = function,
                    planeMask = planeMask,
                ) { sourceX, sourceY ->
                    pattern.pixelAt(sourceX, sourceY)
                }
            } else {
                framebuffer.fill(rectangle.x, rectangle.y, rectangle.width, rectangle.height, pixel, preserveAlpha, effectiveClip, function, planeMask)
            } || painted
        }
        return painted
    }

    private fun fillPattern(
        fillStyle: Int,
        foreground: Int,
        background: Int,
        tilePixmap: XImagePixels?,
        stipplePixmap: XImagePixels?,
    ): XFillPattern? =
        when (fillStyle) {
            XGraphicsContext.FillTiled -> tilePixmap?.let {
                XFillPattern(it, foreground = foreground, background = background, style = fillStyle)
            }
            XGraphicsContext.FillStippled,
            XGraphicsContext.FillOpaqueStippled,
            -> stipplePixmap?.let {
                XFillPattern(it, foreground = foreground, background = background, style = fillStyle)
            }
            else -> null
        }

    @Synchronized
    fun effectiveDrawableClip(drawableId: Int, clipRectangles: List<XRectangleCommand>?): List<XRectangleCommand>? {
        val window = windows[drawableId] ?: return clipRectangles
        val shapeClip = intersectClips(window.boundingShape, window.clipShape)
        return intersectClips(clipRectangles, shapeClip)
    }

    private fun effectivePictureClip(picture: XPicture): List<XRectangleCommand>? {
        val drawableId = picture.drawableId ?: return picture.clipRectangles
        return effectiveDrawableClip(drawableId, picture.clipRectangles)
    }

    private fun intersectClips(
        first: List<XRectangleCommand>?,
        second: List<XRectangleCommand>?,
    ): List<XRectangleCommand>? =
        when {
            first == null -> second?.map { it.copy() }
            second == null -> first
            else -> first.flatMap { left ->
                second.mapNotNull { right -> intersectRectangles(left, right) }
            }
        }

    private fun intersectRectangles(first: XRectangleCommand, second: XRectangleCommand): XRectangleCommand? {
        val left = maxOf(first.x, second.x)
        val top = maxOf(first.y, second.y)
        val right = minOf(first.x + first.width, second.x + second.width)
        val bottom = minOf(first.y + first.height, second.y + second.height)
        if (right <= left || bottom <= top) return null
        return XRectangleCommand(left, top, right - left, bottom - top)
    }

    @Synchronized
    fun drawPoints(
        drawableId: Int,
        pixel: Int,
        points: List<XPoint>,
        lineWidth: Int,
        clipRectangles: List<XRectangleCommand>? = null,
        function: Int = XGraphicsContext.GXcopy,
        planeMask: Int = -1,
    ): Boolean {
        val framebuffer = windows[drawableId]?.framebuffer ?: pixmaps[drawableId]?.framebuffer ?: return false
        val effectiveClip = effectiveDrawableClip(drawableId, clipRectangles)
        var painted = false
        for (point in points) {
            painted = framebuffer.drawPoint(point.x, point.y, pixel, lineWidth, effectiveClip, function, planeMask) || painted
        }
        return painted
    }

    @Synchronized
    fun drawPolyline(
        drawableId: Int,
        pixel: Int,
        background: Int,
        points: List<XPoint>,
        lineWidth: Int,
        lineStyle: Int,
        dashOffset: Int,
        dashes: List<Int>,
        clipRectangles: List<XRectangleCommand>? = null,
        function: Int = XGraphicsContext.GXcopy,
        planeMask: Int = -1,
    ): Boolean {
        val framebuffer = windows[drawableId]?.framebuffer ?: pixmaps[drawableId]?.framebuffer ?: return false
        val effectiveClip = effectiveDrawableClip(drawableId, clipRectangles)
        var painted = false
        val dashPattern = XDashPattern.create(lineStyle, dashOffset, dashes, foreground = pixel, background = background)
        for (index in 0 until points.lastIndex) {
            val start = points[index]
            val end = points[index + 1]
            painted = framebuffer.drawLine(
                start.x,
                start.y,
                end.x,
                end.y,
                pixel,
                lineWidth,
                effectiveClip,
                function,
                planeMask,
                dashPattern,
                includeFirstPoint = index == 0,
            ) || painted
        }
        return painted
    }

    @Synchronized
    fun drawSegments(
        drawableId: Int,
        pixel: Int,
        background: Int,
        points: List<XPoint>,
        lineWidth: Int,
        lineStyle: Int,
        dashOffset: Int,
        dashes: List<Int>,
        clipRectangles: List<XRectangleCommand>? = null,
        function: Int = XGraphicsContext.GXcopy,
        planeMask: Int = -1,
    ): Boolean {
        val framebuffer = windows[drawableId]?.framebuffer ?: pixmaps[drawableId]?.framebuffer ?: return false
        val effectiveClip = effectiveDrawableClip(drawableId, clipRectangles)
        var painted = false
        var index = 0
        while (index + 1 < points.size) {
            val start = points[index]
            val end = points[index + 1]
            val dashPattern = XDashPattern.create(lineStyle, dashOffset, dashes, foreground = pixel, background = background)
            painted = framebuffer.drawLine(start.x, start.y, end.x, end.y, pixel, lineWidth, effectiveClip, function, planeMask, dashPattern) || painted
            index += 2
        }
        return painted
    }

    @Synchronized
    fun drawRectangleOutlines(
        drawableId: Int,
        pixel: Int,
        rectangles: List<XRectangleCommand>,
        lineWidth: Int,
        clipRectangles: List<XRectangleCommand>? = null,
        function: Int = XGraphicsContext.GXcopy,
        planeMask: Int = -1,
    ): Boolean {
        val framebuffer = windows[drawableId]?.framebuffer ?: pixmaps[drawableId]?.framebuffer ?: return false
        val effectiveClip = effectiveDrawableClip(drawableId, clipRectangles)
        var painted = false
        for (rectangle in rectangles) {
            painted = framebuffer.drawRectangleOutline(rectangle.x, rectangle.y, rectangle.width, rectangle.height, pixel, lineWidth, effectiveClip, function, planeMask) || painted
        }
        return painted
    }

    @Synchronized
    fun drawArcs(
        drawableId: Int,
        pixel: Int,
        arcs: List<XArcCommand>,
        lineWidth: Int,
        clipRectangles: List<XRectangleCommand>? = null,
        function: Int = XGraphicsContext.GXcopy,
        planeMask: Int = -1,
    ): Boolean {
        val framebuffer = windows[drawableId]?.framebuffer ?: pixmaps[drawableId]?.framebuffer ?: return false
        val effectiveClip = effectiveDrawableClip(drawableId, clipRectangles)
        var painted = false
        for (arc in arcs) {
            painted = framebuffer.drawArc(arc, pixel, lineWidth, effectiveClip, function, planeMask) || painted
        }
        return painted
    }

    @Synchronized
    fun fillArcs(
        drawableId: Int,
        pixel: Int,
        arcs: List<XArcCommand>,
        arcMode: Int,
        clipRectangles: List<XRectangleCommand>? = null,
        function: Int = XGraphicsContext.GXcopy,
        planeMask: Int = -1,
        fillStyle: Int = XGraphicsContext.FillSolid,
        background: Int = 0x00ff_ffff,
        tilePixmap: XImagePixels? = null,
        stipplePixmap: XImagePixels? = null,
        tileStippleXOrigin: Int = 0,
        tileStippleYOrigin: Int = 0,
    ): Boolean {
        val framebuffer = windows[drawableId]?.framebuffer ?: pixmaps[drawableId]?.framebuffer ?: return false
        val effectiveClip = effectiveDrawableClip(drawableId, clipRectangles)
        val pattern = fillPattern(fillStyle, pixel, background, tilePixmap, stipplePixmap)
        var painted = false
        for (arc in arcs) {
            painted = if (pattern != null) {
                framebuffer.fillArcPattern(
                    arc = arc,
                    arcMode = arcMode,
                    patternXOrigin = tileStippleXOrigin,
                    patternYOrigin = tileStippleYOrigin,
                    patternWidth = pattern.width,
                    patternHeight = pattern.height,
                    clipRectangles = effectiveClip,
                    function = function,
                    planeMask = planeMask,
                ) { sourceX, sourceY ->
                    pattern.pixelAt(sourceX, sourceY)
                }
            } else {
                framebuffer.fillArc(arc, pixel, arcMode, effectiveClip, function, planeMask)
            } || painted
        }
        return painted
    }

    @Synchronized
    fun fillPolygon(
        drawableId: Int,
        pixel: Int,
        points: List<XPoint>,
        fillRule: Int,
        clipRectangles: List<XRectangleCommand>? = null,
        function: Int = XGraphicsContext.GXcopy,
        planeMask: Int = -1,
        fillStyle: Int = XGraphicsContext.FillSolid,
        background: Int = 0x00ff_ffff,
        tilePixmap: XImagePixels? = null,
        stipplePixmap: XImagePixels? = null,
        tileStippleXOrigin: Int = 0,
        tileStippleYOrigin: Int = 0,
    ): Boolean {
        val framebuffer = windows[drawableId]?.framebuffer ?: pixmaps[drawableId]?.framebuffer ?: return false
        val effectiveClip = effectiveDrawableClip(drawableId, clipRectangles)
        val pattern = fillPattern(fillStyle, pixel, background, tilePixmap, stipplePixmap)
        return if (pattern != null) {
            framebuffer.fillPolygonPattern(
                points = points,
                fillRule = fillRule,
                patternXOrigin = tileStippleXOrigin,
                patternYOrigin = tileStippleYOrigin,
                patternWidth = pattern.width,
                patternHeight = pattern.height,
                clipRectangles = effectiveClip,
                function = function,
                planeMask = planeMask,
            ) { sourceX, sourceY ->
                pattern.pixelAt(sourceX, sourceY)
            }
        } else {
            framebuffer.fillPolygon(points, pixel, fillRule, effectiveClip, function, planeMask)
        }
    }

    @Synchronized
    fun drawText(
        drawableId: Int,
        x: Int,
        baselineY: Int,
        text: String,
        foreground: Int,
        background: Int? = null,
        clipRectangles: List<XRectangleCommand>? = null,
        function: Int = XGraphicsContext.GXcopy,
        planeMask: Int = -1,
    ): Boolean {
        val framebuffer = windows[drawableId]?.framebuffer ?: pixmaps[drawableId]?.framebuffer ?: return false
        val effectiveClip = effectiveDrawableClip(drawableId, clipRectangles)
        return framebuffer.drawText(
            x = x,
            baselineY = baselineY,
            text = text,
            foreground = foreground,
            background = background,
            clipRectangles = effectiveClip,
            function = function,
            planeMask = planeMask,
        )
    }

    @Synchronized
    fun renderFillRectangles(
        operation: Int,
        destination: XPicture,
        pixel: Int,
        rectangles: List<XRectangleCommand>,
    ): Boolean {
        val drawableId = destination.drawableId ?: return false
        val framebuffer = windows[drawableId]?.framebuffer ?: pixmaps[drawableId]?.framebuffer ?: return false
        val destinationClipMask = destination.clipMaskPredicate()
        var painted = false
        for (rectangle in rectangles) {
            painted = when (operation) {
                XRender.OpDst, XRender.OpDisjointDst, XRender.OpConjointDst -> false
                XRender.OpClear, XRender.OpDisjointClear, XRender.OpConjointClear -> framebuffer.fill(
                    rectangle.x,
                    rectangle.y,
                    rectangle.width,
                    rectangle.height,
                    0,
                    preserveAlpha = true,
                    clipRectangles = effectivePictureClip(destination),
                    clipMask = destinationClipMask,
                )
                XRender.OpSrc, XRender.OpDisjointSrc, XRender.OpConjointSrc -> framebuffer.fill(
                    rectangle.x,
                    rectangle.y,
                    rectangle.width,
                    rectangle.height,
                    pixel,
                    preserveAlpha = true,
                    clipRectangles = effectivePictureClip(destination),
                    clipMask = destinationClipMask,
                )
                XRender.OpOver -> framebuffer.blendSolidOver(
                    pixel = pixel,
                    destinationX = rectangle.x,
                    destinationY = rectangle.y,
                    width = rectangle.width,
                    height = rectangle.height,
                    clipRectangles = effectivePictureClip(destination),
                    clipMask = destinationClipMask,
                )
                XRender.OpAdd -> framebuffer.blendSolidAdd(
                    pixel = pixel,
                    destinationX = rectangle.x,
                    destinationY = rectangle.y,
                    width = rectangle.width,
                    height = rectangle.height,
                    clipRectangles = effectivePictureClip(destination),
                    clipMask = destinationClipMask,
                )
                XRender.OpBlendMultiply -> framebuffer.blendSolidMultiply(
                    pixel = pixel,
                    destinationX = rectangle.x,
                    destinationY = rectangle.y,
                    width = rectangle.width,
                    height = rectangle.height,
                    clipRectangles = effectivePictureClip(destination),
                    clipMask = destinationClipMask,
                )
                XRender.OpBlendScreen -> framebuffer.blendSolidScreen(
                    pixel = pixel,
                    destinationX = rectangle.x,
                    destinationY = rectangle.y,
                    width = rectangle.width,
                    height = rectangle.height,
                    clipRectangles = effectivePictureClip(destination),
                    clipMask = destinationClipMask,
                )
                XRender.OpBlendOverlay -> framebuffer.blendSolidOverlay(
                    pixel = pixel,
                    destinationX = rectangle.x,
                    destinationY = rectangle.y,
                    width = rectangle.width,
                    height = rectangle.height,
                    clipRectangles = effectivePictureClip(destination),
                    clipMask = destinationClipMask,
                )
                XRender.OpBlendDarken -> framebuffer.blendSolidDarken(
                    pixel = pixel,
                    destinationX = rectangle.x,
                    destinationY = rectangle.y,
                    width = rectangle.width,
                    height = rectangle.height,
                    clipRectangles = effectivePictureClip(destination),
                    clipMask = destinationClipMask,
                )
                XRender.OpBlendLighten -> framebuffer.blendSolidLighten(
                    pixel = pixel,
                    destinationX = rectangle.x,
                    destinationY = rectangle.y,
                    width = rectangle.width,
                    height = rectangle.height,
                    clipRectangles = effectivePictureClip(destination),
                    clipMask = destinationClipMask,
                )
                XRender.OpBlendColorDodge -> framebuffer.blendSolidColorDodge(
                    pixel = pixel,
                    destinationX = rectangle.x,
                    destinationY = rectangle.y,
                    width = rectangle.width,
                    height = rectangle.height,
                    clipRectangles = effectivePictureClip(destination),
                    clipMask = destinationClipMask,
                )
                XRender.OpBlendColorBurn -> framebuffer.blendSolidColorBurn(
                    pixel = pixel,
                    destinationX = rectangle.x,
                    destinationY = rectangle.y,
                    width = rectangle.width,
                    height = rectangle.height,
                    clipRectangles = effectivePictureClip(destination),
                    clipMask = destinationClipMask,
                )
                XRender.OpBlendHardLight -> framebuffer.blendSolidHardLight(
                    pixel = pixel,
                    destinationX = rectangle.x,
                    destinationY = rectangle.y,
                    width = rectangle.width,
                    height = rectangle.height,
                    clipRectangles = effectivePictureClip(destination),
                    clipMask = destinationClipMask,
                )
                XRender.OpBlendSoftLight -> framebuffer.blendSolidSoftLight(
                    pixel = pixel,
                    destinationX = rectangle.x,
                    destinationY = rectangle.y,
                    width = rectangle.width,
                    height = rectangle.height,
                    clipRectangles = effectivePictureClip(destination),
                    clipMask = destinationClipMask,
                )
                XRender.OpBlendDifference -> framebuffer.blendSolidDifference(
                    pixel = pixel,
                    destinationX = rectangle.x,
                    destinationY = rectangle.y,
                    width = rectangle.width,
                    height = rectangle.height,
                    clipRectangles = effectivePictureClip(destination),
                    clipMask = destinationClipMask,
                )
                XRender.OpBlendExclusion -> framebuffer.blendSolidExclusion(
                    pixel = pixel,
                    destinationX = rectangle.x,
                    destinationY = rectangle.y,
                    width = rectangle.width,
                    height = rectangle.height,
                    clipRectangles = effectivePictureClip(destination),
                    clipMask = destinationClipMask,
                )
                XRender.OpBlendHSLHue -> framebuffer.blendSolidHslHue(
                    pixel = pixel,
                    destinationX = rectangle.x,
                    destinationY = rectangle.y,
                    width = rectangle.width,
                    height = rectangle.height,
                    clipRectangles = effectivePictureClip(destination),
                    clipMask = destinationClipMask,
                )
                XRender.OpBlendHSLSaturation -> framebuffer.blendSolidHslSaturation(
                    pixel = pixel,
                    destinationX = rectangle.x,
                    destinationY = rectangle.y,
                    width = rectangle.width,
                    height = rectangle.height,
                    clipRectangles = effectivePictureClip(destination),
                    clipMask = destinationClipMask,
                )
                XRender.OpBlendHSLColor -> framebuffer.blendSolidHslColor(
                    pixel = pixel,
                    destinationX = rectangle.x,
                    destinationY = rectangle.y,
                    width = rectangle.width,
                    height = rectangle.height,
                    clipRectangles = effectivePictureClip(destination),
                    clipMask = destinationClipMask,
                )
                XRender.OpBlendHSLLuminosity -> framebuffer.blendSolidHslLuminosity(
                    pixel = pixel,
                    destinationX = rectangle.x,
                    destinationY = rectangle.y,
                    width = rectangle.width,
                    height = rectangle.height,
                    clipRectangles = effectivePictureClip(destination),
                    clipMask = destinationClipMask,
                )
                XRender.OpSaturate, XRender.OpDisjointOverReverse -> framebuffer.blendSolidSaturate(
                    pixel = pixel,
                    destinationX = rectangle.x,
                    destinationY = rectangle.y,
                    width = rectangle.width,
                    height = rectangle.height,
                    clipRectangles = effectivePictureClip(destination),
                    clipMask = destinationClipMask,
                )
                XRender.OpOverReverse -> framebuffer.blendSolidOverReverse(
                    pixel = pixel,
                    destinationX = rectangle.x,
                    destinationY = rectangle.y,
                    width = rectangle.width,
                    height = rectangle.height,
                    clipRectangles = effectivePictureClip(destination),
                    clipMask = destinationClipMask,
                )
                XRender.OpDisjointOver -> framebuffer.blendSolidDisjointOver(
                    pixel = pixel,
                    destinationX = rectangle.x,
                    destinationY = rectangle.y,
                    width = rectangle.width,
                    height = rectangle.height,
                    clipRectangles = effectivePictureClip(destination),
                    clipMask = destinationClipMask,
                )
                XRender.OpConjointOver -> framebuffer.blendSolidConjointOver(
                    pixel = pixel,
                    destinationX = rectangle.x,
                    destinationY = rectangle.y,
                    width = rectangle.width,
                    height = rectangle.height,
                    clipRectangles = effectivePictureClip(destination),
                    clipMask = destinationClipMask,
                )
                XRender.OpConjointOverReverse -> framebuffer.blendSolidConjointOverReverse(
                    pixel = pixel,
                    destinationX = rectangle.x,
                    destinationY = rectangle.y,
                    width = rectangle.width,
                    height = rectangle.height,
                    clipRectangles = effectivePictureClip(destination),
                    clipMask = destinationClipMask,
                )
                XRender.OpDisjointIn -> framebuffer.blendSolidDisjointIn(
                    pixel = pixel,
                    destinationX = rectangle.x,
                    destinationY = rectangle.y,
                    width = rectangle.width,
                    height = rectangle.height,
                    clipRectangles = effectivePictureClip(destination),
                    clipMask = destinationClipMask,
                )
                XRender.OpConjointIn -> framebuffer.blendSolidConjointIn(
                    pixel = pixel,
                    destinationX = rectangle.x,
                    destinationY = rectangle.y,
                    width = rectangle.width,
                    height = rectangle.height,
                    clipRectangles = effectivePictureClip(destination),
                    clipMask = destinationClipMask,
                )
                XRender.OpIn -> framebuffer.blendSolidIn(
                    pixel = pixel,
                    destinationX = rectangle.x,
                    destinationY = rectangle.y,
                    width = rectangle.width,
                    height = rectangle.height,
                    clipRectangles = effectivePictureClip(destination),
                    clipMask = destinationClipMask,
                )
                XRender.OpOut -> framebuffer.blendSolidOut(
                    pixel = pixel,
                    destinationX = rectangle.x,
                    destinationY = rectangle.y,
                    width = rectangle.width,
                    height = rectangle.height,
                    clipRectangles = effectivePictureClip(destination),
                    clipMask = destinationClipMask,
                )
                XRender.OpDisjointOut -> framebuffer.blendSolidDisjointOut(
                    pixel = pixel,
                    destinationX = rectangle.x,
                    destinationY = rectangle.y,
                    width = rectangle.width,
                    height = rectangle.height,
                    clipRectangles = effectivePictureClip(destination),
                    clipMask = destinationClipMask,
                )
                XRender.OpConjointOut -> framebuffer.blendSolidConjointOut(
                    pixel = pixel,
                    destinationX = rectangle.x,
                    destinationY = rectangle.y,
                    width = rectangle.width,
                    height = rectangle.height,
                    clipRectangles = effectivePictureClip(destination),
                    clipMask = destinationClipMask,
                )
                XRender.OpInReverse -> framebuffer.blendSolidInReverse(
                    pixel = pixel,
                    destinationX = rectangle.x,
                    destinationY = rectangle.y,
                    width = rectangle.width,
                    height = rectangle.height,
                    clipRectangles = effectivePictureClip(destination),
                    clipMask = destinationClipMask,
                )
                XRender.OpDisjointInReverse -> framebuffer.blendSolidDisjointInReverse(
                    pixel = pixel,
                    destinationX = rectangle.x,
                    destinationY = rectangle.y,
                    width = rectangle.width,
                    height = rectangle.height,
                    clipRectangles = effectivePictureClip(destination),
                    clipMask = destinationClipMask,
                )
                XRender.OpConjointInReverse -> framebuffer.blendSolidConjointInReverse(
                    pixel = pixel,
                    destinationX = rectangle.x,
                    destinationY = rectangle.y,
                    width = rectangle.width,
                    height = rectangle.height,
                    clipRectangles = effectivePictureClip(destination),
                    clipMask = destinationClipMask,
                )
                XRender.OpOutReverse -> framebuffer.blendSolidOutReverse(
                    pixel = pixel,
                    destinationX = rectangle.x,
                    destinationY = rectangle.y,
                    width = rectangle.width,
                    height = rectangle.height,
                    clipRectangles = effectivePictureClip(destination),
                    clipMask = destinationClipMask,
                )
                XRender.OpDisjointOutReverse -> framebuffer.blendSolidDisjointOutReverse(
                    pixel = pixel,
                    destinationX = rectangle.x,
                    destinationY = rectangle.y,
                    width = rectangle.width,
                    height = rectangle.height,
                    clipRectangles = effectivePictureClip(destination),
                    clipMask = destinationClipMask,
                )
                XRender.OpConjointOutReverse -> framebuffer.blendSolidConjointOutReverse(
                    pixel = pixel,
                    destinationX = rectangle.x,
                    destinationY = rectangle.y,
                    width = rectangle.width,
                    height = rectangle.height,
                    clipRectangles = effectivePictureClip(destination),
                    clipMask = destinationClipMask,
                )
                XRender.OpConjointAtop -> framebuffer.blendSolidConjointAtop(
                    pixel = pixel,
                    destinationX = rectangle.x,
                    destinationY = rectangle.y,
                    width = rectangle.width,
                    height = rectangle.height,
                    clipRectangles = effectivePictureClip(destination),
                    clipMask = destinationClipMask,
                )
                XRender.OpConjointAtopReverse -> framebuffer.blendSolidConjointAtopReverse(
                    pixel = pixel,
                    destinationX = rectangle.x,
                    destinationY = rectangle.y,
                    width = rectangle.width,
                    height = rectangle.height,
                    clipRectangles = effectivePictureClip(destination),
                    clipMask = destinationClipMask,
                )
                XRender.OpAtop -> framebuffer.blendSolidAtop(
                    pixel = pixel,
                    destinationX = rectangle.x,
                    destinationY = rectangle.y,
                    width = rectangle.width,
                    height = rectangle.height,
                    clipRectangles = effectivePictureClip(destination),
                    clipMask = destinationClipMask,
                )
                XRender.OpDisjointAtop -> framebuffer.blendSolidDisjointAtop(
                    pixel = pixel,
                    destinationX = rectangle.x,
                    destinationY = rectangle.y,
                    width = rectangle.width,
                    height = rectangle.height,
                    clipRectangles = effectivePictureClip(destination),
                    clipMask = destinationClipMask,
                )
                XRender.OpAtopReverse -> framebuffer.blendSolidAtopReverse(
                    pixel = pixel,
                    destinationX = rectangle.x,
                    destinationY = rectangle.y,
                    width = rectangle.width,
                    height = rectangle.height,
                    clipRectangles = effectivePictureClip(destination),
                    clipMask = destinationClipMask,
                )
                XRender.OpDisjointAtopReverse -> framebuffer.blendSolidDisjointAtopReverse(
                    pixel = pixel,
                    destinationX = rectangle.x,
                    destinationY = rectangle.y,
                    width = rectangle.width,
                    height = rectangle.height,
                    clipRectangles = effectivePictureClip(destination),
                    clipMask = destinationClipMask,
                )
                XRender.OpDisjointXor -> framebuffer.blendSolidDisjointXor(
                    pixel = pixel,
                    destinationX = rectangle.x,
                    destinationY = rectangle.y,
                    width = rectangle.width,
                    height = rectangle.height,
                    clipRectangles = effectivePictureClip(destination),
                    clipMask = destinationClipMask,
                )
                XRender.OpConjointXor -> framebuffer.blendSolidConjointXor(
                    pixel = pixel,
                    destinationX = rectangle.x,
                    destinationY = rectangle.y,
                    width = rectangle.width,
                    height = rectangle.height,
                    clipRectangles = effectivePictureClip(destination),
                    clipMask = destinationClipMask,
                )
                XRender.OpXor -> framebuffer.blendSolidXor(
                    pixel = pixel,
                    destinationX = rectangle.x,
                    destinationY = rectangle.y,
                    width = rectangle.width,
                    height = rectangle.height,
                    clipRectangles = effectivePictureClip(destination),
                    clipMask = destinationClipMask,
                )
                else -> framebuffer.blendSolidOver(
                    pixel = pixel,
                    destinationX = rectangle.x,
                    destinationY = rectangle.y,
                    width = rectangle.width,
                    height = rectangle.height,
                    clipRectangles = effectivePictureClip(destination),
                    clipMask = destinationClipMask,
                )
            } || painted
        }
        return painted
    }

    @Synchronized
    fun renderTrapezoids(
        operation: Int,
        source: XPicture,
        destination: XPicture,
        maskFormat: Int,
        sourceX: Int,
        sourceY: Int,
        trapezoids: List<XTrapezoidCommand>,
    ): Boolean {
        val drawableId = destination.drawableId ?: return false
        val framebuffer = windows[drawableId]?.framebuffer ?: pixmaps[drawableId]?.framebuffer ?: return false
        val sourcePixelAt: (x: Int, y: Int) -> Int? = (if (source.alphaMap != 0) {
            source.compositeSourcePixelSamplerOptional(drawableId)
        } else if (source.hasPictureClip()) {
            source.sourcePixelSamplerOptional(snapshotDrawableId = drawableId)
        } else {
            source.sourcePixelSampler(snapshotDrawableId = drawableId)?.let { sampler -> { x: Int, y: Int -> sampler(x, y) } }
        }) ?: return false
        val first = trapezoids.firstOrNull() ?: return false
        val originY = floor(first.top.fixedToDouble()).toInt()
        val originX = floor(first.left.xAt(first.top.fixedToDouble())).toInt()
        return framebuffer.compositeTrapezoids(
            operation = operation,
            trapezoids = trapezoids,
            maskFormat = maskFormat,
            clipRectangles = effectivePictureClip(destination),
            clipMask = destination.clipMaskPredicate(),
        ) { x, y ->
            sourcePixelAt(sourceX + x - originX, sourceY + y - originY)
        }
    }

    @Synchronized
    fun addTraps(destination: XPicture, trapezoids: List<XTrapezoidCommand>): Boolean {
        if (!XRender.isAlphaMaskFormat(destination.format)) return false
        val drawableId = destination.drawableId ?: return false
        val framebuffer = windows[drawableId]?.framebuffer ?: pixmaps[drawableId]?.framebuffer ?: return false
        return framebuffer.addTrapezoids(
            trapezoids = trapezoids,
            maskFormat = destination.format,
            clipRectangles = effectivePictureClip(destination),
            clipMask = destination.clipMaskPredicate(),
        )
    }

    @Synchronized
    fun renderTriangles(
        operation: Int,
        source: XPicture,
        destination: XPicture,
        maskFormat: Int,
        sourceX: Int,
        sourceY: Int,
        triangles: List<XTriangleCommand>,
    ): Boolean {
        val drawableId = destination.drawableId ?: return false
        val framebuffer = windows[drawableId]?.framebuffer ?: pixmaps[drawableId]?.framebuffer ?: return false
        val sourcePixelAt: (x: Int, y: Int) -> Int? = (if (source.alphaMap != 0) {
            source.compositeSourcePixelSamplerOptional(drawableId)
        } else if (source.hasPictureClip()) {
            source.sourcePixelSamplerOptional(snapshotDrawableId = drawableId)
        } else {
            source.sourcePixelSampler(snapshotDrawableId = drawableId)?.let { sampler -> { x: Int, y: Int -> sampler(x, y) } }
        }) ?: return false
        val first = triangles.firstOrNull() ?: return false
        val originX = floor(first.p1.x.fixedToDouble()).toInt()
        val originY = floor(first.p1.y.fixedToDouble()).toInt()
        return framebuffer.compositeTriangles(
            operation = operation,
            triangles = triangles,
            maskFormat = maskFormat,
            clipRectangles = effectivePictureClip(destination),
            clipMask = destination.clipMaskPredicate(),
        ) { x, y ->
            sourcePixelAt(sourceX + x - originX, sourceY + y - originY)
        }
    }

    @Synchronized
    fun renderColorTriangles(
        operation: Int,
        destination: XPicture,
        triangles: List<XColorTriangleCommand>,
    ): Boolean {
        val drawableId = destination.drawableId ?: return false
        val framebuffer = windows[drawableId]?.framebuffer ?: pixmaps[drawableId]?.framebuffer ?: return false
        return framebuffer.compositeColoredTriangles(
            operation = operation,
            triangles = triangles,
            clipRectangles = effectivePictureClip(destination),
            clipMask = destination.clipMaskPredicate(),
        )
    }

    @Synchronized
    fun renderColorTrapezoids(
        operation: Int,
        destination: XPicture,
        trapezoids: List<XColorTrapCommand>,
    ): Boolean {
        val drawableId = destination.drawableId ?: return false
        val framebuffer = windows[drawableId]?.framebuffer ?: pixmaps[drawableId]?.framebuffer ?: return false
        return framebuffer.compositeColoredTrapezoids(
            operation = operation,
            trapezoids = trapezoids,
            clipRectangles = effectivePictureClip(destination),
            clipMask = destination.clipMaskPredicate(),
        )
    }

    @Synchronized
    fun renderTransform(
        operation: Int,
        source: XPicture,
        destination: XPicture,
        sourceQuad: XFixedQuad,
        destinationQuad: XFixedQuad,
        filterName: String,
    ): Boolean {
        val drawableId = destination.drawableId ?: return false
        val framebuffer = windows[drawableId]?.framebuffer ?: pixmaps[drawableId]?.framebuffer ?: return false
        val sourcePixelAt = source.sourcePixelSamplerAt(snapshotDrawableId = drawableId, filterNameOverride = filterName) ?: return false
        return framebuffer.compositeTransformedQuad(
            operation = operation,
            sourceQuad = sourceQuad,
            destinationQuad = destinationQuad,
            clipRectangles = effectivePictureClip(destination),
            clipMask = destination.clipMaskPredicate(),
            sourcePixelAt = sourcePixelAt,
        )
    }

    @Synchronized
    fun compositeGlyphs(
        operation: Int,
        source: XPicture,
        destination: XPicture,
        glyphSetId: Int,
        sourceX: Int,
        sourceY: Int,
        originX: Int,
        originY: Int,
        placements: List<XGlyphPlacement>,
    ): Boolean {
        val destinationDrawableId = destination.drawableId ?: return false
        val destinationFramebuffer = windows[destinationDrawableId]?.framebuffer ?: pixmaps[destinationDrawableId]?.framebuffer ?: return false
        val glyphSet = glyphSets[glyphSetId] ?: return false
        val destinationClipMask = destination.clipMaskPredicate()
        val sourcePixelAt: (x: Int, y: Int) -> Int? = (if (source.alphaMap != 0) {
            source.compositeSourcePixelSamplerOptional(destinationDrawableId)
        } else if (source.hasPictureClip()) {
            source.sourcePixelSamplerOptional(snapshotDrawableId = destinationDrawableId)
        } else {
            source.sourcePixelSampler(snapshotDrawableId = destinationDrawableId)?.let { sampler -> { x: Int, y: Int -> sampler(x, y) } }
        }) ?: return false
        var painted = false
        for (placement in placements) {
            val glyph = glyphSet.glyphs[placement.glyphId] ?: continue
            val mask = glyph.mask ?: continue
            val destinationX = placement.x - glyph.x
            val destinationY = placement.y - glyph.y
            painted = destinationFramebuffer.compositeSourceOverMask(
                sourceX = sourceX,
                sourceY = sourceY,
                originX = originX,
                originY = originY,
                destinationX = destinationX,
                destinationY = destinationY,
                width = glyph.width,
                height = glyph.height,
                operation = operation,
                clipRectangles = effectivePictureClip(destination),
                clipMask = destinationClipMask,
                mask = mask,
                sourcePixelAt = sourcePixelAt,
            ) || painted
        }
        return painted
    }

    @Synchronized
    fun putGc(gc: XGraphicsContext) {
        gcs[gc.id] = gc
    }

    @Synchronized
    fun hasGc(id: Int): Boolean = gcs.containsKey(id)

    @Synchronized
    fun hasPixmap(id: Int): Boolean = pixmaps.containsKey(id)

    @Synchronized
    fun pixmap(id: Int): XPixmap? = pixmaps[id]

    @Synchronized
    fun hasFont(id: Int): Boolean = fonts.contains(id)

    @Synchronized
    fun hasFontable(id: Int): Boolean = fonts.contains(id) || gcs.containsKey(id)

    @Synchronized
    fun canCopyGc(sourceId: Int, destinationId: Int): Boolean {
        val source = gcs[sourceId] ?: return false
        val destination = gcs[destinationId] ?: return false
        return source.drawableRootId == destination.drawableRootId &&
            source.drawableDepth == destination.drawableDepth
    }

    @Synchronized
    fun copyGc(sourceId: Int, destinationId: Int, mask: Int) {
        val source = gcs[sourceId] ?: return
        val destination = gcs[destinationId] ?: return
        for (bit in 0..22) {
            if ((mask and (1 shl bit)) == 0) continue
            when (bit) {
                0 -> destination.function = source.function
                1 -> destination.planeMask = source.planeMask
                2 -> destination.foreground = source.foreground
                3 -> destination.background = source.background
                4 -> destination.lineWidth = source.lineWidth
                5 -> destination.lineStyle = source.lineStyle
                6 -> destination.capStyle = source.capStyle
                7 -> destination.joinStyle = source.joinStyle
                8 -> destination.fillStyle = source.fillStyle
                9 -> destination.fillRule = source.fillRule
                10 -> {
                    destination.tilePixmapId = source.tilePixmapId
                    destination.tilePixmap = source.tilePixmap
                }
                11 -> {
                    destination.stipplePixmapId = source.stipplePixmapId
                    destination.stipplePixmap = source.stipplePixmap
                }
                12 -> destination.tileStippleXOrigin = source.tileStippleXOrigin
                13 -> destination.tileStippleYOrigin = source.tileStippleYOrigin
                14 -> destination.fontId = source.fontId
                16 -> destination.graphicsExposures = source.graphicsExposures
                17 -> destination.clipXOrigin = source.clipXOrigin
                18 -> destination.clipYOrigin = source.clipYOrigin
                19 -> destination.clipRectangles = source.clipRectangles?.toList()
                20 -> destination.dashOffset = source.dashOffset
                21 -> destination.dashes = source.dashes.toList()
                22 -> destination.arcMode = source.arcMode
            }
        }
    }

    @Synchronized
    fun hasResource(id: Int): Boolean =
        windows.containsKey(id) ||
            pixmaps.containsKey(id) ||
            gcs.containsKey(id) ||
            fonts.contains(id) ||
            cursors.containsKey(id) ||
            colormaps.contains(id) ||
            pictures.containsKey(id) ||
            glyphSets.containsKey(id) ||
            xfixesRegions.containsKey(id) ||
            syncCounters.containsKey(id) ||
            syncAlarms.containsKey(id) ||
            syncFences.containsKey(id) ||
            glxContexts.containsKey(id) ||
            glxPixmaps.containsKey(id) ||
            glxWindows.containsKey(id) ||
            glxPbuffers.containsKey(id)

    @Synchronized
    fun allocateClientResourceIdRange(owner: XEventSink, maxCount: Int = XXCMisc.MaxIdsPerReply): XClientResourceIdRange {
        val first = allocateNextClientResourceId(owner) ?: return XClientResourceIdRange(startId = 0, count = 0)
        var count = 1
        while (count < maxCount) {
            val candidate = clientResourceIdForOffset(nextClientResourceOffset)
            if (candidate != first + count || clientResourceReservations.containsKey(candidate) || hasResource(candidate)) break
            clientResourceReservations[candidate] = owner
            nextClientResourceOffset = (nextClientResourceOffset + 1) and X11Ids.ResourceIdMask
            count++
        }
        return XClientResourceIdRange(startId = first, count = count)
    }

    @Synchronized
    fun allocateClientResourceIds(owner: XEventSink, requestedCount: Long, maxCount: Int = XXCMisc.MaxIdsPerReply): IntArray {
        if (requestedCount <= 0L || maxCount <= 0) return IntArray(0)
        val ids = IntArray(minOf(requestedCount, maxCount.toLong()).toInt())
        var count = 0
        while (count < ids.size) {
            ids[count] = allocateNextClientResourceId(owner) ?: break
            count++
        }
        return ids.copyOf(count)
    }

    @Synchronized
    fun resourceIdAvailableFor(owner: XEventSink, id: Int): Boolean =
        !hasResource(id) && clientResourceReservations[id].let { it == null || it == owner }

    @Synchronized
    fun releaseClientResourceReservations(owner: XEventSink) {
        clientResourceReservations.entries.removeIf { it.value == owner }
    }

    private fun allocateNextClientResourceId(owner: XEventSink): Int? {
        repeat(X11Ids.ResourceIdMask + 1) {
            val id = clientResourceIdForOffset(nextClientResourceOffset)
            nextClientResourceOffset = (nextClientResourceOffset + 1) and X11Ids.ResourceIdMask
            if (!clientResourceReservations.containsKey(id) && !hasResource(id)) {
                clientResourceReservations[id] = owner
                return id
            }
        }
        return null
    }

    private fun clientResourceIdForOffset(offset: Int): Int =
        X11Ids.ResourceIdBase or (offset and X11Ids.ResourceIdMask)

    @Synchronized
    fun updateGc(
        id: Int,
        foreground: Int? = null,
        background: Int? = null,
        lineWidth: Int? = null,
        lineStyle: Int? = null,
        capStyle: Int? = null,
        joinStyle: Int? = null,
        function: Int? = null,
        planeMask: Int? = null,
        fontId: Int? = null,
        clipXOrigin: Int? = null,
        clipYOrigin: Int? = null,
        fillStyle: Int? = null,
        fillRule: Int? = null,
        tilePixmapId: Int? = null,
        stipplePixmapId: Int? = null,
        tilePixmap: XImagePixels? = null,
        stipplePixmap: XImagePixels? = null,
        tileStippleXOrigin: Int? = null,
        tileStippleYOrigin: Int? = null,
        dashOffset: Int? = null,
        dashes: List<Int>? = null,
        arcMode: Int? = null,
        graphicsExposures: Boolean? = null,
    ) {
        val gc = gcs.getOrPut(id) { XGraphicsContext(id) }
        foreground?.let { gc.foreground = it }
        background?.let { gc.background = it }
        lineWidth?.let { gc.lineWidth = it }
        lineStyle?.let { gc.lineStyle = it }
        capStyle?.let { gc.capStyle = it }
        joinStyle?.let { gc.joinStyle = it }
        function?.let { gc.function = it }
        planeMask?.let { gc.planeMask = it }
        fontId?.let { gc.fontId = it }
        clipXOrigin?.let { gc.clipXOrigin = it }
        clipYOrigin?.let { gc.clipYOrigin = it }
        fillStyle?.let { gc.fillStyle = it }
        fillRule?.let { gc.fillRule = it }
        tilePixmapId?.let {
            gc.tilePixmapId = it
            gc.tilePixmap = tilePixmap
        }
        stipplePixmapId?.let {
            gc.stipplePixmapId = it
            gc.stipplePixmap = stipplePixmap
        }
        tileStippleXOrigin?.let { gc.tileStippleXOrigin = it }
        tileStippleYOrigin?.let { gc.tileStippleYOrigin = it }
        dashOffset?.let { gc.dashOffset = it }
        dashes?.let { gc.dashes = it }
        arcMode?.let { gc.arcMode = it }
        graphicsExposures?.let { gc.graphicsExposures = it }
    }

    @Synchronized
    fun updateGcClip(
        id: Int,
        clipXOrigin: Int? = null,
        clipYOrigin: Int? = null,
        clipRectangles: List<XRectangleCommand>? = null,
    ) {
        val gc = gcs.getOrPut(id) { XGraphicsContext(id) }
        clipXOrigin?.let { gc.clipXOrigin = it }
        clipYOrigin?.let { gc.clipYOrigin = it }
        gc.clipRectangles = clipRectangles
    }

    @Synchronized
    fun gc(id: Int): XGraphicsContext = gcs[id] ?: XGraphicsContext(id)

    @Synchronized
    fun draw(command: XDrawingCommand) {
        drawings += command
        if (drawings.size > MaxDrawingCommands) {
            drawings.removeAt(0)
        }
    }

    @Synchronized
    fun putFont(id: Int) {
        fonts += id
    }

    @Synchronized
    fun putCursor(cursor: XCursor) {
        cursors[cursor.id] = cursor.copy(generation = nextCursorGeneration++)
    }

    @Synchronized
    fun hasCursor(id: Int): Boolean = cursors.containsKey(id)

    @Synchronized
    fun setCursorName(id: Int, name: XCursorName): Boolean {
        val cursor = cursors[id] ?: return false
        val namedCursor = cursor.copy(name = name)
        cursors[id] = namedCursor
        for (window in windows.values) {
            if (window.cursorId == id && window.cursorGeneration == namedCursor.generation) {
                window.cursorName = name
            }
        }
        return true
    }

    @Synchronized
    fun changeCursor(sourceId: Int, destinationId: Int): List<XXFixesCursorNotifyDispatch>? {
        val source = cursors[sourceId] ?: return null
        val destination = cursors[destinationId] ?: return null
        if (sourceId == destinationId) return emptyList()
        val previousCursor = displayedCursorIdentity()
        val changedIdentity = XCursorIdentity(id = destinationId, generation = destination.generation)
        val changedCursor = source.copy(
            id = destination.id,
            generation = destination.generation,
            name = destination.name,
        )
        cursors[destinationId] = changedCursor
        for (window in windows.values) {
            if (window.cursorId == destinationId && window.cursorGeneration == destination.generation) {
                window.cursorImage = changedCursor.image
                window.cursorGeneration = changedCursor.generation
                window.cursorName = changedCursor.name
            }
        }
        return if (previousCursor == changedIdentity) {
            cursorSerial += 1
            xfixesCursorNotifyDispatches(cursorSerial = cursorSerial, timestamp = currentServerTime(inputTime))
        } else {
            emptyList()
        }
    }

    @Synchronized
    fun changeCursorsByName(sourceId: Int, name: String): List<XXFixesCursorNotifyDispatch>? {
        val source = cursors[sourceId] ?: return null
        val previousCursor = displayedCursorIdentity()
        val changedIdentities = mutableSetOf<XCursorIdentity>()
        for ((id, cursor) in cursors.toList()) {
            if (cursor.name?.name != name) continue
            if (id == sourceId) continue
            val changedCursor = source.copy(
                id = id,
                generation = cursor.generation,
                name = cursor.name,
            )
            cursors[id] = changedCursor
            changedIdentities += XCursorIdentity(id = id, generation = cursor.generation)
            for (window in windows.values) {
                if (window.cursorId == id && window.cursorGeneration == cursor.generation) {
                    window.cursorImage = changedCursor.image
                    window.cursorGeneration = changedCursor.generation
                    window.cursorName = changedCursor.name
                }
            }
        }
        return if (previousCursor != null && previousCursor in changedIdentities) {
            cursorSerial += 1
            xfixesCursorNotifyDispatches(cursorSerial = cursorSerial, timestamp = currentServerTime(inputTime))
        } else {
            emptyList()
        }
    }

    @Synchronized
    fun recolorCursor(
        id: Int,
        foregroundRed: Int,
        foregroundGreen: Int,
        foregroundBlue: Int,
        backgroundRed: Int,
        backgroundGreen: Int,
        backgroundBlue: Int,
    ): List<XXFixesCursorNotifyDispatch> {
        var recoloredIdentity: XCursorIdentity? = null
        cursors[id]?.let {
            val recoloredImage = it.image?.recolored(
                foregroundRed = foregroundRed,
                foregroundGreen = foregroundGreen,
                foregroundBlue = foregroundBlue,
                backgroundRed = backgroundRed,
                backgroundGreen = backgroundGreen,
                backgroundBlue = backgroundBlue,
            )
            val recoloredCursor = it.copy(
                image = recoloredImage,
                foregroundRed = foregroundRed,
                foregroundGreen = foregroundGreen,
                foregroundBlue = foregroundBlue,
                backgroundRed = backgroundRed,
                backgroundGreen = backgroundGreen,
                backgroundBlue = backgroundBlue,
            )
            cursors[id] = recoloredCursor
            recoloredIdentity = XCursorIdentity(id = id, generation = recoloredCursor.generation)
            for (window in windows.values) {
                if (window.cursorId == id && window.cursorGeneration == recoloredCursor.generation) {
                    window.cursorImage = recoloredImage
                }
            }
        }
        return if (displayedCursorIdentity() == recoloredIdentity) {
            cursorSerial += 1
            xfixesCursorNotifyDispatches(cursorSerial = cursorSerial, timestamp = currentServerTime(inputTime))
        } else {
            emptyList()
        }
    }

    @Synchronized
    fun putColormap(id: Int) {
        colormaps += id
    }

    @Synchronized
    fun hasColormap(id: Int): Boolean = colormaps.contains(id)

    @Synchronized
    fun isColormapInstalled(id: Int): Boolean = installedColormaps.contains(id)

    @Synchronized
    fun installColormap(id: Int) {
        if (!colormaps.contains(id)) return
        installedColormaps.clear()
        installedColormaps += id
    }

    @Synchronized
    fun uninstallColormap(id: Int) {
        if (id != X11Ids.DefaultColormap) installedColormaps.remove(id)
        ensureDefaultColormapInstalled()
    }

    @Synchronized
    fun installedColormaps(): List<Int> = installedColormaps.toList()

    @Synchronized
    fun removeResource(id: Int): List<XXFixesCursorNotifyDispatch> {
        val previousCursor = displayedCursorIdentity()
        pixmaps.remove(id)
        gcs.remove(id)
        fonts.remove(id)
        cursors.remove(id)
        if (id != X11Ids.DefaultColormap) {
            colormaps.remove(id)
            installedColormaps.remove(id)
        }
        glxContexts.remove(id)
        glxPixmaps.remove(id)
        pictures.remove(id)
        glyphSets.remove(id)
        xfixesRegions.remove(id)
        discardRetainedResourceIds(setOf(id))
        releaseInputGrabsForResources(setOf(id))
        ensureDefaultColormapInstalled()
        return xfixesCursorNotifyDispatchesIfChanged(previousCursor)
    }

    private fun ensureDefaultColormapInstalled() {
        if (installedColormaps.isEmpty()) installedColormaps += X11Ids.DefaultColormap
    }

    @Synchronized
    fun internAtom(name: String, onlyIfExists: Boolean): Int {
        atomIds[name]?.let { return it }
        if (onlyIfExists) return 0
        val id = nextAtomId++
        atomIds[name] = id
        atomNames[id] = name
        return id
    }

    @Synchronized
    fun atomName(id: Int): String? = atomNames[id]

    @Synchronized
    fun randrOutputPropertyNames(): List<Int> = randrOutputProperties.keys.toList()

    @Synchronized
    fun randrOutputProperty(property: Int): XRandrOutputProperty? = randrOutputProperties[property]

    @Synchronized
    fun putRandrOutputProperty(property: Int, value: XRandrOutputProperty) {
        randrOutputProperties[property] = value
    }

    @Synchronized
    fun removeRandrOutputProperty(property: Int): Boolean =
        randrOutputProperties.remove(property) != null

    @Synchronized
    fun configureRandrOutputProperty(property: Int, config: XRandrOutputPropertyConfig) {
        randrOutputProperties[property] = (randrOutputProperties[property] ?: XRandrOutputProperty.Empty).copy(config = config)
    }

    @Synchronized
    fun randrOutputPropertyNotifySinks(): List<XRandrOutputPropertyNotifyDispatch> =
        randrInputs.flatMap { (sink, selections) ->
            selections
                .filter { (_, eventMask) -> (eventMask and XRandr.OutputPropertyNotifyMask) != 0 }
                .map { (windowId) -> XRandrOutputPropertyNotifyDispatch(sink = sink, windowId = windowId) }
        }

    @Synchronized
    fun randrPrimaryOutput(): Int = randrPrimaryOutput

    @Synchronized
    fun randrLastCrtcConfigTime(): Int = randrLastCrtcConfigTime

    @Synchronized
    fun markRandrCrtcConfigSet(): Int {
        // The fixed display configuration is unchanged, but RANDR still advances
        // the last successful set time for later InvalidTime checks.
        val now = syncServerTime()
        val timestamp = if (Integer.compareUnsigned(now, randrLastCrtcConfigTime) <= 0) {
            randrLastCrtcConfigTime + 1
        } else {
            now
        }
        randrLastCrtcConfigTime = timestamp
        return timestamp
    }

    @Synchronized
    fun setRandrPendingCrtcTransform(transform: XRandrCrtcTransform) {
        randrPendingCrtcTransform = transform.snapshot()
    }

    @Synchronized
    fun applyRandrPendingCrtcTransform() {
        randrCurrentCrtcTransform = randrPendingCrtcTransform.snapshot()
    }

    @Synchronized
    fun randrCrtcTransforms(): Pair<XRandrCrtcTransform, XRandrCrtcTransform> =
        randrPendingCrtcTransform.snapshot() to randrCurrentCrtcTransform.snapshot()

    @Synchronized
    fun randrLastPanningTime(): Int = randrLastPanningTime

    @Synchronized
    fun markRandrPanningSet(): Int {
        val now = syncServerTime()
        val timestamp = if (Integer.compareUnsigned(now, randrLastPanningTime) <= 0) {
            randrLastPanningTime + 1
        } else {
            now
        }
        randrLastPanningTime = timestamp
        return timestamp
    }

    @Synchronized
    fun randrMonitorSnapshot(): XRandrMonitorSnapshot {
        val monitors = if (randrUserMonitors.isEmpty()) {
            listOf(
                XRandrMonitor(
                    name = 0,
                    primary = randrPrimaryOutput == XRandr.OutputId,
                    automatic = true,
                    x = 0,
                    y = 0,
                    width = width,
                    height = height,
                    widthMillimeters = widthMillimeters,
                    heightMillimeters = heightMillimeters,
                    outputs = listOf(XRandr.OutputId),
                ),
            )
        } else {
            randrUserMonitors.values.map { it.snapshot() }
        }
        return XRandrMonitorSnapshot(timestamp = randrLastMonitorChangeTime, monitors = monitors)
    }

    @Synchronized
    fun setRandrMonitor(monitor: XRandrMonitor): XRandrMonitorChange {
        val stored = monitor.snapshot()
        if (stored.primary) {
            randrUserMonitors.replaceAll { _, existing -> existing.copy(primary = false) }
        }
        randrUserMonitors[stored.name] = stored
        val timestamp = syncServerTime()
        randrLastMonitorChangeTime = timestamp
        return XRandrMonitorChange(
            configureNotifyDispatches = rootConfigureNotifySinks(),
            screenChangeNotifyDispatches = randrScreenChangeNotifySinks(timestamp),
        )
    }

    @Synchronized
    fun deleteRandrMonitor(name: Int): XRandrMonitorChange? {
        randrUserMonitors.remove(name) ?: return null
        val timestamp = syncServerTime()
        randrLastMonitorChangeTime = timestamp
        return XRandrMonitorChange(
            configureNotifyDispatches = rootConfigureNotifySinks(),
            screenChangeNotifyDispatches = randrScreenChangeNotifySinks(timestamp),
        )
    }

    @Synchronized
    fun setRandrScreenSize(widthMillimeters: Int, heightMillimeters: Int): XRandrScreenSizeChange {
        if (this.widthMillimeters == widthMillimeters && this.heightMillimeters == heightMillimeters) {
            return XRandrScreenSizeChange.Empty
        }
        this.widthMillimeters = widthMillimeters
        this.heightMillimeters = heightMillimeters
        val timestamp = syncServerTime()
        return XRandrScreenSizeChange(
            configureNotifyDispatches = rootConfigureNotifySinks(),
            screenChangeNotifyDispatches = randrScreenChangeNotifySinks(timestamp),
        )
    }

    @Synchronized
    fun setRandrPrimaryOutput(output: Int): XRandrPrimaryOutputChange {
        if (randrPrimaryOutput == output) return XRandrPrimaryOutputChange.Empty
        randrPrimaryOutput = output
        val timestamp = syncServerTime()
        return XRandrPrimaryOutputChange(
            configureNotifyDispatches = rootConfigureNotifySinks(),
            screenChangeNotifyDispatches = randrScreenChangeNotifySinks(timestamp),
            outputChangeNotifyDispatches = randrOutputChangeNotifySinks(timestamp),
        )
    }

    private fun rootConfigureNotifySinks(): List<XConfigureNotifyDispatch> {
        val root = windows[X11Ids.RootWindow] ?: return emptyList()
        val event = XConfigureNotifyEvent(
            eventWindowId = root.id,
            windowId = root.id,
            aboveSiblingId = 0,
            x = root.x,
            y = root.y,
            width = root.width,
            height = root.height,
            borderWidth = root.borderWidth,
            overrideRedirect = root.overrideRedirect,
        )
        return eventSelectionsForWindow(root.id, XEventMasks.StructureNotify).map { sink ->
            XConfigureNotifyDispatch(sink = sink, event = event)
        }
    }

    private fun randrScreenChangeNotifySinks(timestamp: Int): List<XRandrScreenChangeNotifyDispatch> =
        randrInputs.flatMap { (sink, selections) ->
            selections
                .filter { (_, eventMask) -> (eventMask and XRandr.ScreenChangeNotifyMask) != 0 }
                .map { (windowId) ->
                    XRandrScreenChangeNotifyDispatch(
                        sink = sink,
                        event = XRandrScreenChangeNotifyEvent(
                            windowId = windowId,
                            timestamp = timestamp,
                            configTimestamp = timestamp,
                            width = width,
                            height = height,
                            widthMillimeters = widthMillimeters,
                            heightMillimeters = heightMillimeters,
                            rotation = XRandr.Rotate0,
                            subpixelOrder = XRandr.SubPixelUnknown,
                        ),
                    )
                }
        }

    private fun randrOutputChangeNotifySinks(timestamp: Int): List<XRandrOutputChangeNotifyDispatch> =
        randrInputs.flatMap { (sink, selections) ->
            selections
                .filter { (_, eventMask) -> (eventMask and XRandr.OutputChangeNotifyMask) != 0 }
                .map { (windowId) ->
                    XRandrOutputChangeNotifyDispatch(
                        sink = sink,
                        event = XRandrOutputChangeNotifyEvent(
                            windowId = windowId,
                            output = XRandr.OutputId,
                            crtc = XRandr.CrtcId,
                            mode = XRandr.ModeId,
                            timestamp = timestamp,
                            configTimestamp = timestamp,
                            rotation = XRandr.Rotate0,
                            connection = XRandr.Connected,
                            subpixelOrder = XRandr.SubPixelUnknown,
                        ),
                    )
                }
        }

    @Synchronized
    fun setSelectionOwner(selection: Int, owner: Int, sink: XEventSink, time: Int): XSelectionOwnerUpdate? {
        val current = selectionOwners[selection]
        val lastChangeTime = selectionLastChangeTimes[selection] ?: 0
        val serverTime = currentServerTime(lastChangeTime)
        if (time != 0 &&
            (
                Integer.compareUnsigned(time, lastChangeTime) < 0 ||
                    Integer.compareUnsigned(time, serverTime) > 0
                )
        ) {
            return null
        }
        val effectiveTime = if (time == 0) serverTime else time
        selectionLastChangeTimes[selection] = effectiveTime
        if (owner == 0) {
            selectionOwners.remove(selection)
        } else {
            selectionOwners[selection] = XSelectionOwner(owner, sink)
        }
        val clear = if (current == null || (owner != 0 && current.sink == sink)) {
            null
        } else {
            XSelectionClearDispatch(
                sink = current.sink,
                event = XSelectionClearEvent(
                    time = effectiveTime,
                    ownerWindowId = current.windowId,
                    selection = selection,
                ),
            )
        }
        val selectionNotify = xfixesSelectionNotifyDispatches(
            selection = selection,
            subtype = XFixes.SetSelectionOwnerNotify,
            owner = owner,
            timestamp = serverTime,
            selectionTimestamp = effectiveTime,
        )
        return XSelectionOwnerUpdate(clear = clear, selectionNotify = selectionNotify)
    }

    @Synchronized
    fun selectionOwner(selection: Int): Int = selectionOwners[selection]?.windowId ?: 0

    @Synchronized
    fun selectionRequestDispatch(
        selection: Int,
        requestor: Int,
        target: Int,
        property: Int,
        time: Int,
    ): XSelectionRequestDispatch? {
        val owner = selectionOwners[selection] ?: return null
        if (!windows.containsKey(owner.windowId)) {
            selectionOwners.remove(selection)
            return null
        }
        return XSelectionRequestDispatch(
            sink = owner.sink,
            event = XSelectionRequestEvent(
                time = time,
                ownerWindowId = owner.windowId,
                requestorWindowId = requestor,
                selection = selection,
                target = target,
                property = property,
            ),
        )
    }

    fun extension(name: String): XExtension? = extensions.firstOrNull { it.name == name || name in it.aliases }

    fun extensionByMajorOpcode(majorOpcode: Int): XExtension? = extensions.firstOrNull { it.majorOpcode == majorOpcode }

    companion object {
        private const val AnyButton = 0
        private const val AnyKey = 0
        private const val AnyModifier = 0x8000
        private const val KeyModifierMask = 0x00ff
        private const val MaxDrawingCommands = 10_000
        private const val MaxMotionHistory = 256
        private const val MaxInputOperations = 200
        private const val MaxGlxOperations = 200
        private const val MaxRenderOperations = 400
        private const val MaxGetImagePixels = 16_777_216
        private const val MaxGlyphMaskPixels = 16_777_216
        private const val MaxRequestCounts = 256
        private const val MaxExtensionQueries = 200
        private const val MaxUnsupportedRequests = 200

        private fun pixelsToMillimeters(pixels: Int, dpi: Int): Int =
            ((pixels * 25.4) / dpi).roundToInt().coerceAtLeast(1)

        private val PredefinedAtoms = listOf(
            "PRIMARY",
            "SECONDARY",
            "ARC",
            "ATOM",
            "BITMAP",
            "CARDINAL",
            "COLORMAP",
            "CURSOR",
            "CUT_BUFFER0",
            "CUT_BUFFER1",
            "CUT_BUFFER2",
            "CUT_BUFFER3",
            "CUT_BUFFER4",
            "CUT_BUFFER5",
            "CUT_BUFFER6",
            "CUT_BUFFER7",
            "DRAWABLE",
            "FONT",
            "INTEGER",
            "PIXMAP",
            "POINT",
            "RECTANGLE",
            "RESOURCE_MANAGER",
            "RGB_COLOR_MAP",
            "RGB_BEST_MAP",
            "RGB_BLUE_MAP",
            "RGB_DEFAULT_MAP",
            "RGB_GRAY_MAP",
            "RGB_GREEN_MAP",
            "RGB_RED_MAP",
            "STRING",
            "VISUALID",
            "WINDOW",
            "WM_COMMAND",
            "WM_HINTS",
            "WM_CLIENT_MACHINE",
            "WM_ICON_NAME",
            "WM_ICON_SIZE",
            "WM_NAME",
            "WM_NORMAL_HINTS",
            "WM_SIZE_HINTS",
            "WM_ZOOM_HINTS",
            "MIN_SPACE",
            "NORM_SPACE",
            "MAX_SPACE",
            "END_SPACE",
            "SUPERSCRIPT_X",
            "SUPERSCRIPT_Y",
            "SUBSCRIPT_X",
            "SUBSCRIPT_Y",
            "UNDERLINE_POSITION",
            "UNDERLINE_THICKNESS",
            "STRIKEOUT_ASCENT",
            "STRIKEOUT_DESCENT",
            "ITALIC_ANGLE",
            "X_HEIGHT",
            "QUAD_WIDTH",
            "WEIGHT",
            "POINT_SIZE",
            "RESOLUTION",
            "COPYRIGHT",
            "NOTICE",
            "FONT_NAME",
            "FAMILY_NAME",
            "FULL_NAME",
            "CAP_HEIGHT",
            "WM_CLASS",
            "WM_TRANSIENT_FOR",
        )
    }

    private fun XWindow.label(): String {
        val wmName = atomIds["WM_NAME"] ?: return id.toHex()
        val string = atomIds["STRING"] ?: return id.toHex()
        val property = properties[wmName]
        if (property?.type == string && property.format == 8 && property.data.isNotEmpty()) {
            return property.data.decodeToString()
        }
        return if (id == X11Ids.RootWindow) "root" else id.toHex()
    }

    private fun windowSubtreeIds(rootId: Int): Set<Int> {
        if (rootId == X11Ids.RootWindow || !windows.containsKey(rootId)) return emptySet()
        val removed = linkedSetOf(rootId)
        var changed: Boolean
        do {
            changed = false
            for (window in windows.values) {
                if (window.parentId in removed && removed.add(window.id)) {
                    changed = true
                }
            }
        } while (changed)
        return removed
    }

    private fun processSaveSet(owner: XEventSink, resourceIds: Set<Int>): List<XXFixesCursorNotifyDispatch> {
        val saveSet = saveSets[owner]?.values?.toList().orEmpty()
        return processSaveSet(saveSet, resourceIds)
    }

    private fun processSaveSet(saveSet: List<XSaveSetEntry>, resourceIds: Set<Int>): List<XXFixesCursorNotifyDispatch> {
        if (saveSet.isEmpty()) return emptyList()
        val previousCursor = displayedCursorIdentity()
        val ownedWindows = resourceIds.filterTo(linkedSetOf()) { it != X11Ids.RootWindow && windows.containsKey(it) }
        for (entry in saveSet) {
            val windowId = entry.windowId
            val window = windows[windowId] ?: continue
            val absolute = absolutePosition(window)
            if (isInferiorOfAny(windowId, ownedWindows)) {
                val parentId = if (entry.target == XSaveSetTarget.Root) {
                    X11Ids.RootWindow
                } else {
                    closestNonOwnedAncestor(window.parentId, ownedWindows)
                }
                val parentAbsolute = windows[parentId]?.let { absolutePosition(it) } ?: (0 to 0)
                window.parentId = parentId
                window.x = absolute.first - parentAbsolute.first
                window.y = absolute.second - parentAbsolute.second
            }
            if (entry.map == XSaveSetMap.Map && !window.mapped) {
                mapWindow(windowId)
            } else if (entry.map == XSaveSetMap.Unmap && window.mapped) {
                unmapWindow(windowId)
            }
        }
        return xfixesCursorNotifyDispatchesIfChanged(previousCursor)
    }

    private fun processRetainedSaveSetsForWindowSubtree(windowIds: Set<Int>): List<XXFixesCursorNotifyDispatch> {
        if (windowIds.isEmpty()) return emptyList()
        val retained = retainedClients.values
            .filter { resources -> resources.resourceIds.any { it in windowIds && windows.containsKey(it) } }
            .toList()
        val xfixesCursorNotifyDispatches = mutableListOf<XXFixesCursorNotifyDispatch>()
        for (resources in retained) {
            xfixesCursorNotifyDispatches += processRetainedSaveSet(resources)
        }
        return xfixesCursorNotifyDispatches
    }

    private fun processRetainedSaveSet(resources: XRetainedClientResources): List<XXFixesCursorNotifyDispatch> {
        val saveSet = resources.saveSet
        if (saveSet.isEmpty()) return emptyList()
        resources.saveSet = emptyList()
        return processSaveSet(saveSet, resources.resourceIds)
    }

    private fun discardRetainedResourceIds(resourceIds: Set<Int>) {
        if (resourceIds.isEmpty()) return
        for (id in resourceIds) {
            resourceOwners.remove(id)
        }
        val emptyRetainedClients = mutableListOf<Int>()
        for ((id, retained) in retainedClients) {
            retained.resourceIds.removeAll(resourceIds)
            retained.saveSet = retained.saveSet.filter { it.windowId !in resourceIds }
            if (retained.resourceIds.isEmpty()) {
                emptyRetainedClients += id
            }
        }
        for (id in emptyRetainedClients) {
            retainedClients.remove(id)
        }
    }

    private fun currentResourceIdsOwnedBy(owner: XEventSink, resourceIds: Set<Int>): LinkedHashSet<Int> =
        resourceIds.filterTo(linkedSetOf()) { resourceOwners[it] == owner }

    private fun isInferiorOfAny(windowId: Int, ancestorIds: Set<Int>): Boolean {
        var parentId = windows[windowId]?.parentId ?: return false
        val visited = mutableSetOf(windowId)
        while (parentId != 0 && visited.add(parentId)) {
            if (parentId in ancestorIds) return true
            parentId = windows[parentId]?.parentId ?: return false
        }
        return false
    }

    private fun closestNonOwnedAncestor(parentId: Int, ownedWindows: Set<Int>): Int {
        var current = parentId
        var reparentTo = parentId
        val visited = mutableSetOf<Int>()
        while (current != 0 && visited.add(current)) {
            val parent = windows[current] ?: break
            if (current in ownedWindows) {
                reparentTo = parent.parentId
            }
            current = parent.parentId
        }
        return reparentTo.takeIf { it in windows } ?: X11Ids.RootWindow
    }

    private fun removeEventSelections(windowIds: Set<Int>) {
        for (selections in eventSinks.values) {
            for (windowId in windowIds) {
                selections.remove(windowId)
            }
        }
    }

    private fun childrenBefore(children: List<XWindow>, child: XWindow): List<XWindow> =
        children.takeWhile { it.id != child.id }

    private fun childrenAfter(children: List<XWindow>, child: XWindow): List<XWindow> =
        children.dropWhile { it.id != child.id }.drop(1)

    private fun windowsOverlap(first: XWindow, second: XWindow): Boolean {
        val firstRight = first.x + first.width + first.borderWidth * 2
        val firstBottom = first.y + first.height + first.borderWidth * 2
        val secondRight = second.x + second.width + second.borderWidth * 2
        val secondBottom = second.y + second.height + second.borderWidth * 2
        return first.x < secondRight && second.x < firstRight && first.y < secondBottom && second.y < firstBottom
    }

    private fun restackChild(child: XWindow, place: Int) {
        val siblings = childrenOf(child.parentId)
        val anchorId = when (place) {
            XCirculateResult.Top -> siblings.lastOrNull()?.id
            else -> siblings.firstOrNull()?.id
        }
        val movingIds = subtreeIds(child.id)
        if (anchorId == null || anchorId in movingIds) return

        val entries = windows.entries.map { it.key to it.value }
        val moving = entries.filter { (id, _) -> id in movingIds }
        val insertAfterId = if (place == XCirculateResult.Top) lastSubtreeEntryId(anchorId, entries) else anchorId
        windows.clear()
        for ((id, window) in entries) {
            if (id in movingIds) continue
            if (place == XCirculateResult.Bottom && id == anchorId) {
                moving.forEach { (movingId, movingWindow) -> windows[movingId] = movingWindow }
            }
            windows[id] = window
            if (place == XCirculateResult.Top && id == insertAfterId) {
                moving.forEach { (movingId, movingWindow) -> windows[movingId] = movingWindow }
            }
        }
    }

    private fun restackConfiguredWindow(window: XWindow, siblingId: Int?, stackMode: Int): Boolean {
        val before = childrenOf(window.parentId).map { it.id }
        val siblings = childrenOf(window.parentId)
        val sibling = siblingId?.let { id -> siblings.firstOrNull { it.id == id } }
        when (stackMode) {
            XStackMode.Above -> {
                if (sibling != null) restackChildRelativeTo(window, sibling, above = true) else restackChild(window, XCirculateResult.Top)
            }
            XStackMode.Below -> {
                if (sibling != null) restackChildRelativeTo(window, sibling, above = false) else restackChild(window, XCirculateResult.Bottom)
            }
            XStackMode.TopIf -> {
                val occluded = if (sibling != null) {
                    siblingOccludesWindow(siblings, sibling, window)
                } else {
                    childrenAfter(siblings, window).any { it.occludes(window) }
                }
                if (occluded) restackChild(window, XCirculateResult.Top)
            }
            XStackMode.BottomIf -> {
                val occludes = if (sibling != null) {
                    windowOccludesSibling(siblings, window, sibling)
                } else {
                    childrenBefore(siblings, window).any { window.occludes(it) }
                }
                if (occludes) restackChild(window, XCirculateResult.Bottom)
            }
            XStackMode.Opposite -> {
                val occluded = if (sibling != null) {
                    siblingOccludesWindow(siblings, sibling, window)
                } else {
                    childrenAfter(siblings, window).any { it.occludes(window) }
                }
                if (occluded) {
                    restackChild(window, XCirculateResult.Top)
                } else {
                    val occludes = if (sibling != null) {
                        windowOccludesSibling(siblings, window, sibling)
                    } else {
                        childrenBefore(siblings, window).any { window.occludes(it) }
                    }
                    if (occludes) restackChild(window, XCirculateResult.Bottom)
                }
            }
        }
        return childrenOf(window.parentId).map { it.id } != before
    }

    private fun siblingOccludesWindow(siblings: List<XWindow>, sibling: XWindow, window: XWindow): Boolean =
        siblingIsAbove(siblings, sibling, window) && sibling.occludes(window)

    private fun windowOccludesSibling(siblings: List<XWindow>, window: XWindow, sibling: XWindow): Boolean =
        siblingIsAbove(siblings, window, sibling) && window.occludes(sibling)

    private fun siblingIsAbove(siblings: List<XWindow>, upper: XWindow, lower: XWindow): Boolean =
        siblings.indexOfFirst { it.id == upper.id } > siblings.indexOfFirst { it.id == lower.id }

    private fun XWindow.occludes(other: XWindow): Boolean =
        mapped && other.mapped && windowsOverlap(this, other)

    private fun restackChildRelativeTo(child: XWindow, sibling: XWindow, above: Boolean) {
        val movingIds = subtreeIds(child.id)
        if (sibling.id in movingIds) return

        val entries = windows.entries.map { it.key to it.value }
        val moving = entries.filter { (id, _) -> id in movingIds }
        val insertAfterId = if (above) lastSubtreeEntryId(sibling.id, entries) else sibling.id
        windows.clear()
        for ((id, window) in entries) {
            if (id in movingIds) continue
            if (!above && id == sibling.id) {
                moving.forEach { (movingId, movingWindow) -> windows[movingId] = movingWindow }
            }
            windows[id] = window
            if (above && id == insertAfterId) {
                moving.forEach { (movingId, movingWindow) -> windows[movingId] = movingWindow }
            }
        }
    }

    private fun lastSubtreeEntryId(rootId: Int, entries: List<Pair<Int, XWindow>>): Int {
        val subtree = subtreeIds(rootId)
        return entries.last { (id, _) -> id in subtree }.first
    }

    private fun siblingBelow(window: XWindow): Int {
        val siblings = childrenOf(window.parentId)
        val index = siblings.indexOfFirst { it.id == window.id }
        return if (index > 0) siblings[index - 1].id else 0
    }

    private fun subtreeIds(rootId: Int): Set<Int> {
        val result = linkedSetOf<Int>()
        fun visit(id: Int) {
            result += id
            windows.values.filter { it.parentId == id }.forEach { visit(it.id) }
        }
        visit(rootId)
        return result
    }

    private fun eventSelectionsForWindow(windowId: Int, eventMask: Int): List<XEventSink> =
        eventSinks.mapNotNull { (sink, selections) ->
            val selectedMask = selections[windowId] ?: return@mapNotNull null
            sink.takeIf { (selectedMask and eventMask) != 0 }
        }

    private fun xfixesSelectionNotifyDispatches(
        selection: Int,
        subtype: Int,
        owner: Int,
        timestamp: Int,
        selectionTimestamp: Int,
    ): List<XXFixesSelectionNotifyDispatch> {
        val eventMask = 1 shl subtype
        return xfixesSelectionInputs.flatMap { (sink, selections) ->
            selections.mapNotNull { (key, selectedMask) ->
                if (key.selection != selection || (selectedMask and eventMask) == 0) return@mapNotNull null
                XXFixesSelectionNotifyDispatch(
                    sink = sink,
                    event = XXFixesSelectionNotifyEvent(
                        subtype = subtype,
                        windowId = key.windowId,
                        ownerWindowId = owner,
                        selection = selection,
                        timestamp = timestamp,
                        selectionTimestamp = selectionTimestamp,
                    ),
                )
            }
        }
    }

    private fun xfixesSelectionOwnerLostDispatches(
        selection: Int,
        subtype: Int,
    ): List<XXFixesSelectionNotifyDispatch> {
        val selectionTimestamp = selectionLastChangeTimes[selection] ?: 0
        return xfixesSelectionNotifyDispatches(
            selection = selection,
            subtype = subtype,
            owner = 0,
            timestamp = currentServerTime(selectionTimestamp),
            selectionTimestamp = selectionTimestamp,
        )
    }

    private fun displayedCursorId(): Int? {
        activePointerGrab?.cursor?.let { return it }
        val pointerWindow = windowAt(pointerX, pointerY) ?: windows[X11Ids.RootWindow] ?: return null
        return windowPathToRoot(pointerWindow.id).firstNotNullOfOrNull { it.cursorId }
    }

    private fun displayedCursorIdentity(): XCursorIdentity? {
        activePointerGrab?.cursor?.let { id ->
            return cursors[id]?.let { XCursorIdentity(id = id, generation = it.generation) }
        }
        val pointerWindow = windowAt(pointerX, pointerY) ?: windows[X11Ids.RootWindow] ?: return null
        return windowPathToRoot(pointerWindow.id).firstNotNullOfOrNull { window ->
            val id = window.cursorId ?: return@firstNotNullOfOrNull null
            val generation = window.cursorGeneration ?: return@firstNotNullOfOrNull null
            XCursorIdentity(id = id, generation = generation)
        }
    }

    private fun xfixesCursorNotifyDispatchesIfChanged(
        previousCursor: XCursorIdentity?,
        timestamp: Int = currentServerTime(inputTime),
    ): List<XXFixesCursorNotifyDispatch> {
        if (displayedCursorIdentity() == previousCursor) return emptyList()
        cursorSerial += 1
        return xfixesCursorNotifyDispatches(cursorSerial = cursorSerial, timestamp = timestamp)
    }

    private fun xfixesCursorNotifyDispatches(cursorSerial: Int, timestamp: Int): List<XXFixesCursorNotifyDispatch> =
        xfixesCursorInputs.flatMap { (sink, windows) ->
            windows.mapNotNull { (windowId, selectedMask) ->
                if ((selectedMask and XFixes.DisplayCursorNotifyMask) == 0) return@mapNotNull null
                XXFixesCursorNotifyDispatch(
                    sink = sink,
                    event = XXFixesCursorNotifyEvent(
                        subtype = XFixes.DisplayCursorNotify,
                        windowId = windowId,
                        cursorSerial = cursorSerial,
                        timestamp = timestamp,
                        name = displayedCursorName()?.atom ?: 0,
                    ),
                )
            }
        }

    private fun sendXFixesCursorNotify(notifications: List<XXFixesCursorNotifyDispatch>) {
        for (notification in notifications) {
            runCatching { notification.sink.sendXFixesCursorNotifyEvent(notification.event) }
        }
    }

    private fun destroyNotifySinksInDestroyOrder(rootId: Int, excludedSink: XEventSink?): List<XDestroyNotifyDispatch> =
        windowsInDestroyNotifyOrder(rootId).flatMap { window ->
            destroyNotifySinks(window).filter { dispatch -> dispatch.sink != excludedSink }
        }

    private fun windowsInDestroyNotifyOrder(rootId: Int): List<XWindow> {
        val window = windows[rootId] ?: return emptyList()
        return childrenOf(rootId).flatMap { windowsInDestroyNotifyOrder(it.id) } + window
    }

    private fun windowIsAncestorOrSelf(ancestorId: Int, windowId: Int): Boolean {
        var current = windows[windowId] ?: return false
        val visited = mutableSetOf<Int>()
        while (visited.add(current.id)) {
            if (current.id == ancestorId) return true
            current = windows[current.parentId] ?: return false
        }
        return false
    }

    private fun absolutePosition(window: XWindow): Pair<Int, Int> {
        var x = window.x
        var y = window.y
        var parentId = window.parentId
        val visited = mutableSetOf(window.id)
        while (parentId != 0 && parentId != X11Ids.RootWindow && visited.add(parentId)) {
            val parent = windows[parentId] ?: break
            x += parent.x
            y += parent.y
            parentId = parent.parentId
        }
        return x to y
    }

    private fun sourceWindowContainsPointer(window: XWindow, sourceX: Int, sourceY: Int, sourceWidth: Int, sourceHeight: Int): Boolean {
        val absolute = absolutePosition(window)
        val localX = pointerX - absolute.first
        val localY = pointerY - absolute.second
        if (localX !in 0 until window.width || localY !in 0 until window.height) return false
        val effectiveWidth = if (sourceWidth == 0) window.width - sourceX else sourceWidth
        val effectiveHeight = if (sourceHeight == 0) window.height - sourceY else sourceHeight
        if (effectiveWidth <= 0 || effectiveHeight <= 0) return false
        return localX >= sourceX &&
            localY >= sourceY &&
            localX < sourceX + effectiveWidth &&
            localY < sourceY + effectiveHeight
    }

    private fun confinedPointerPosition(x: Int, y: Int): Pair<Int, Int> {
        var clampedX = x.coerceIn(0, width - 1)
        var clampedY = y.coerceIn(0, height - 1)
        val confineWindow = activePointerGrab?.confineTo?.let { windows[it] } ?: return clampedX to clampedY
        val absolute = absolutePosition(confineWindow)
        val bounds = visibleBounds(confineWindow, absolute.first, absolute.second) ?: return clampedX to clampedY
        if (bounds.width <= 0 || bounds.height <= 0) return clampedX to clampedY
        clampedX = clampedX.coerceIn(bounds.x, bounds.x + bounds.width - 1)
        clampedY = clampedY.coerceIn(bounds.y, bounds.y + bounds.height - 1)
        return clampedX to clampedY
    }

    private fun visibleBounds(window: XWindow, absoluteX: Int, absoluteY: Int): XRectangle? {
        var left = absoluteX
        var top = absoluteY
        var right = absoluteX + window.width
        var bottom = absoluteY + window.height
        var parentId = window.parentId
        val visited = mutableSetOf(window.id)
        while (parentId != 0 && visited.add(parentId)) {
            val parent = windows[parentId] ?: break
            val parentAbsolute = absolutePosition(parent)
            left = maxOf(left, parentAbsolute.first)
            top = maxOf(top, parentAbsolute.second)
            right = minOf(right, parentAbsolute.first + parent.width)
            bottom = minOf(bottom, parentAbsolute.second + parent.height)
            parentId = parent.parentId
        }
        left = left.coerceIn(0, width)
        top = top.coerceIn(0, height)
        right = right.coerceIn(0, width)
        bottom = bottom.coerceIn(0, height)
        return if (right > left && bottom > top) {
            XRectangle(left, top, right - left, bottom - top)
        } else {
            null
        }
    }

    private fun overlaps(windows: List<XWindowSnapshot>): List<XWindowOverlap> {
        val mapped = windows.filter { it.mapped && it.id != X11Ids.RootWindow && it.visibleWidth > 0 && it.visibleHeight > 0 }
        val result = mutableListOf<XWindowOverlap>()
        for (lowerIndex in mapped.indices) {
            for (upperIndex in lowerIndex + 1 until mapped.size) {
                val lower = mapped[lowerIndex]
                val upper = mapped[upperIndex]
                val left = maxOf(lower.visibleX, upper.visibleX)
                val top = maxOf(lower.visibleY, upper.visibleY)
                val right = minOf(lower.visibleX + lower.visibleWidth, upper.visibleX + upper.visibleWidth)
                val bottom = minOf(lower.visibleY + lower.visibleHeight, upper.visibleY + upper.visibleHeight)
                if (right > left && bottom > top) {
                    result += XWindowOverlap(
                        lowerWindowId = lower.id,
                        upperWindowId = upper.id,
                        x = left,
                        y = top,
                        width = right - left,
                        height = bottom - top,
                    )
                }
            }
        }
        return result
    }

    private fun Int.toHex(): String = "0x${toUInt().toString(16)}"

    private fun mappedChildContaining(parentId: Int, rootX: Int, rootY: Int): XWindow? =
        windows.values.toList()
            .asReversed()
            .firstOrNull { window ->
                if (window.parentId != parentId || !window.mapped) return@firstOrNull false
                val absolute = absolutePosition(window)
                rootX >= absolute.first &&
                    rootY >= absolute.second &&
                    rootX < absolute.first + window.width &&
                    rootY < absolute.second + window.height &&
                    windowInputShapeContains(window, rootX, rootY, absolute)
            }

    private fun windowAt(x: Int, y: Int): XWindow? =
        windows.values.toList()
            .asReversed()
            .firstOrNull { window ->
                val absolute = absolutePosition(window)
                window.mapped &&
                    windowIsViewable(window.id) &&
                    visibleBounds(window, absolute.first, absolute.second)?.let { bounds ->
                        x >= bounds.x &&
                            y >= bounds.y &&
                            x < bounds.x + bounds.width &&
                            y < bounds.y + bounds.height &&
                            windowInputShapeContains(window, x, y, absolute)
                    } == true
            }

    private fun windowInputShapeContains(window: XWindow, rootX: Int, rootY: Int, absolute: Pair<Int, Int>): Boolean {
        val inputClip = intersectClips(window.boundingShape, window.inputShape) ?: return true
        val localX = rootX - absolute.first
        val localY = rootY - absolute.second
        return inputClip.any { rectangle ->
            localX >= rectangle.x &&
                localY >= rectangle.y &&
                localX < rectangle.x + rectangle.width &&
                localY < rectangle.y + rectangle.height
        }
    }

    private fun windowPathToRoot(windowId: Int): List<XWindow> {
        val path = mutableListOf<XWindow>()
        var current = windows[windowId]
        val visited = mutableSetOf<Int>()
        while (current != null && visited.add(current.id)) {
            path += current
            current = windows[current.parentId]
        }
        return path
    }

    private fun childByAncestor(pathFromTarget: List<XWindow>): Map<Int, Int> {
        val result = linkedMapOf<Int, Int>()
        for (index in 1 until pathFromTarget.size) {
            result[pathFromTarget[index].id] = pathFromTarget[index - 1].id
        }
        return result
    }

    private fun recordMotionHistory(time: Int, rootX: Int, rootY: Int) {
        motionHistory += XMotionHistoryEntry(
            time = time,
            rootX = rootX,
            rootY = rootY,
            x = rootX,
            y = rootY,
        )
        if (motionHistory.size > MaxMotionHistory) {
            motionHistory.removeAt(0)
        }
    }

    private fun buttonMask(button: Int): Int =
        if (button in 1..5) 1 shl (7 + button) else 0

    private val drawings = mutableListOf<XDrawingCommand>()

    private data class XRectangle(
        val x: Int,
        val y: Int,
        val width: Int,
        val height: Int,
    )

}

private data class XSelectionOwner(
    val windowId: Int,
    val sink: XEventSink,
)

internal data class XSelectionOwnerUpdate(
    val clear: XSelectionClearDispatch?,
    val selectionNotify: List<XXFixesSelectionNotifyDispatch>,
)

private data class XXFixesSelectionInputKey(
    val windowId: Int,
    val selection: Int,
)

private data class XRetainedClientResources(
    val closeDownMode: Int,
    val resourceIds: LinkedHashSet<Int>,
    var saveSet: List<XSaveSetEntry>,
)

private data class XSaveSetEntry(
    val windowId: Int,
    val target: Int,
    val map: Int,
)

private object XSaveSetTarget {
    const val Nearest = 0
    const val Root = 1
}

private object XSaveSetMap {
    const val Map = 0
    const val Unmap = 1
}

internal data class XScreenSaverSettings(
    val timeout: Int = DefaultTimeout,
    val interval: Int = DefaultInterval,
    val preferBlanking: Int = DefaultPreferBlanking,
    val allowExposures: Int = DefaultAllowExposures,
) {
    companion object {
        const val DefaultTimeout = 0
        const val DefaultInterval = 0
        const val DefaultPreferBlanking = 0
        const val DefaultAllowExposures = 0
    }
}

internal data class XScreenSaverAttributes(
    val owner: XEventSink,
    val drawableId: Int,
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
    val borderWidth: Int,
    val windowClass: Int,
    val depth: Int,
    val visual: Int,
    val valueMask: Int,
    val valueList: List<Int>,
)

internal data class XSyncCounter(
    val id: Int,
    val value: Long,
    val previousValue: Long = value,
    val generation: Long = 0,
    val system: Boolean = false,
)

internal sealed interface XSyncCounterChangeResult {
    data object Missing : XSyncCounterChangeResult
    data object Overflow : XSyncCounterChangeResult
    data class Changed(val alarmNotifications: List<XSyncAlarmNotifyDispatch>) : XSyncCounterChangeResult
}

internal data class XSyncWaitCondition(
    val counterId: Int,
    val valueType: Int,
    val waitValue: Long,
    val testType: Int,
    val testValue: Long,
    val counterGeneration: Long = 0,
    val eventThreshold: Long = 0,
)

internal data class XSyncCounterNotifyEvent(
    val counterId: Int,
    val waitValue: Long,
    val counterValue: Long,
    val timestamp: Int,
    val count: Int,
    val destroyed: Boolean,
)

private data class XSyncCounterWaiter(
    val conditions: List<XSyncWaitCondition>,
    var events: List<XSyncCounterNotifyEvent>? = null,
)

internal data class XSyncAlarm(
    val id: Int,
    val owner: XEventSink,
    val counterId: Int = 0,
    val valueType: Int = XSync.Absolute,
    val waitValue: Long = 0,
    val testType: Int = XSync.PositiveComparison,
    val testValue: Long = 0,
    val counterGeneration: Long = 0,
    val delta: Long = 1,
    val events: Boolean = true,
    val state: Int = XSync.AlarmInactive,
)

internal data class XSyncAlarmNotifyEvent(
    val alarmId: Int,
    val counterValue: Long,
    val alarmValue: Long,
    val timestamp: Int,
    val state: Int,
)

internal data class XSyncAlarmNotifyDispatch(
    val sink: XEventSink,
    val event: XSyncAlarmNotifyEvent,
)

internal data class XSyncFence(
    val id: Int,
    val drawableId: Int,
    val triggered: Boolean,
)

internal data class XPointerControlSettings(
    val accelerationNumerator: Int = DefaultAccelerationNumerator,
    val accelerationDenominator: Int = DefaultAccelerationDenominator,
    val threshold: Int = DefaultThreshold,
) {
    companion object {
        const val DefaultAccelerationNumerator = 2
        const val DefaultAccelerationDenominator = 1
        const val DefaultThreshold = 4
    }
}

internal object XPointerMapping {
    val Default = (1..255).toList()
}

internal object XModifierMapping {
    val Default = emptyList<Int>()
}

internal object XKeyboard {
    const val MinKeycode = 8
    const val MaxKeycode = 255
    const val ControlKeyClickPercent = 1 shl 0
    const val ControlBellPercent = 1 shl 1
    const val ControlBellPitch = 1 shl 2
    const val ControlBellDuration = 1 shl 3
    const val ControlLed = 1 shl 4
    const val ControlLedMode = 1 shl 5
    const val ControlKey = 1 shl 6
    const val ControlAutoRepeatMode = 1 shl 7
    const val ControlMask =
        ControlKeyClickPercent or
            ControlBellPercent or
            ControlBellPitch or
            ControlBellDuration or
            ControlLed or
            ControlLedMode or
            ControlKey or
            ControlAutoRepeatMode
}

internal data class XKeyboardMapping(
    val keysymsPerKeycode: Int,
    val keysymsByKeycode: Map<Int, List<Int>>,
) {
    fun keysymsFor(keycode: Int): List<Int> {
        val keysyms = keysymsByKeycode[keycode].orEmpty()
        return List(keysymsPerKeycode) { index -> keysyms.getOrElse(index) { 0 } }
    }

    fun snapshot(): XKeyboardMappingSnapshot =
        XKeyboardMappingSnapshot(
            keysymsPerKeycode = keysymsPerKeycode,
            keycodes = keysymsByKeycode.toSortedMap().map { (keycode, keysyms) ->
                XKeycodeMappingSnapshot(keycode = keycode, keysyms = keysyms.toList())
            },
        )

    companion object {
        val Default = XKeyboardMapping(
            keysymsPerKeycode = 1,
            keysymsByKeycode = emptyMap(),
        )
    }
}

internal data class XKeyboardControlSettings(
    val keyClickPercent: Int = DefaultKeyClickPercent,
    val bellPercent: Int = DefaultBellPercent,
    val bellPitch: Int = DefaultBellPitch,
    val bellDuration: Int = DefaultBellDuration,
    val ledMask: Int = 0,
    val globalAutoRepeat: Boolean = true,
    val autoRepeats: ByteArray = ByteArray(32) { 0xff.toByte() },
) {
    fun snapshot(): XKeyboardControlSnapshot =
        XKeyboardControlSnapshot(
            keyClickPercent = keyClickPercent,
            bellPercent = bellPercent,
            bellPitch = bellPitch,
            bellDuration = bellDuration,
            ledMask = ledMask,
            globalAutoRepeat = globalAutoRepeat,
            autoRepeats = autoRepeats.map { it.toInt() and 0xff },
        )

    companion object {
        const val DefaultKeyClickPercent = 0
        const val DefaultBellPercent = 50
        const val DefaultBellPitch = 400
        const val DefaultBellDuration = 100
        val Default = XKeyboardControlSettings()
    }
}

internal data class XKeyboardControlUpdate(
    val keyClickPercent: Int? = null,
    val bellPercent: Int? = null,
    val bellPitch: Int? = null,
    val bellDuration: Int? = null,
    val led: Int? = null,
    val ledMode: Int? = null,
    val key: Int? = null,
    val autoRepeatMode: Int? = null,
)

internal object XKeyboardLedMode {
    const val Off = 0
    const val On = 1
}

internal object XKeyboardAutoRepeatMode {
    const val Off = 0
    const val On = 1
    const val Default = 2
}

internal data class XAccessHost(
    val family: Int,
    val address: List<Int>,
) {
    companion object {
        const val FamilyInternet = 0
        const val FamilyDECnet = 1
        const val FamilyChaos = 2
        const val FamilyServerInterpreted = 5
        const val FamilyInternetV6 = 6
    }
}

internal data class XCirculateResult(
    val parentId: Int,
    val window: XWindow,
    val place: Int,
) {
    companion object {
        const val RaiseLowest = 0
        const val LowerHighest = 1
        const val Top = 0
        const val Bottom = 1
    }
}

internal data class XConfigureWindowResult(
    val window: XWindow,
    val changed: Boolean,
    val sizeChanged: Boolean,
    val aboveSiblingId: Int,
) {
    fun configureNotifyEvent(eventWindowId: Int): XConfigureNotifyEvent =
        XConfigureNotifyEvent(
            eventWindowId = eventWindowId,
            windowId = window.id,
            aboveSiblingId = aboveSiblingId,
            x = window.x,
            y = window.y,
            width = window.width,
            height = window.height,
            borderWidth = window.borderWidth,
            overrideRedirect = window.overrideRedirect,
        )
}

internal object XStackMode {
    const val Above = 0
    const val Below = 1
    const val TopIf = 2
    const val BottomIf = 3
    const val Opposite = 4
}

internal data class XWindow(
    val id: Int,
    var parentId: Int,
    val windowClass: Int = XWindowClass.InputOutput,
    val depth: Int = X11Ids.RootDepth,
    val visual: Int = X11Ids.RootVisual,
    var x: Int,
    var y: Int,
    var width: Int,
    var height: Int,
    var borderWidth: Int,
    var mapped: Boolean = false,
    var backgroundPixel: Int = 0x00ff_ffff,
    var backgroundPixmapId: Int? = null,
    var borderPixel: Int = 0,
    var borderPixmapId: Int? = null,
    var bitGravity: Int = XWindowGravity.Forget,
    var winGravity: Int = XWindowGravity.NorthWest,
    var backingStore: Int = XBackingStore.NotUseful,
    var backingPlanes: Int = -1,
    var backingPixel: Int = 0,
    var overrideRedirect: Boolean = false,
    var saveUnder: Boolean = false,
    var doNotPropagateMask: Int = 0,
    var colormapId: Int? = X11Ids.DefaultColormap,
    var cursorId: Int? = null,
    var cursorImage: XCursorImage? = null,
    var cursorGeneration: Long? = null,
    var cursorName: XCursorName? = null,
    var boundingShape: List<XRectangleCommand>? = null,
    var clipShape: List<XRectangleCommand>? = null,
    var inputShape: List<XRectangleCommand>? = null,
    val properties: MutableMap<Int, XProperty> = linkedMapOf(),
    val framebuffer: XFramebuffer = XFramebuffer(width, height, backgroundPixel),
)

internal data class XPixmap(
    val id: Int,
    val width: Int,
    val height: Int,
    val depth: Int,
    val rootId: Int = X11Ids.RootWindow,
    val framebuffer: XFramebuffer = XFramebuffer(width, height),
)

private sealed interface XResolvedWindowBackground {
    data object None : XResolvedWindowBackground
    data class Pixel(val pixel: Int) : XResolvedWindowBackground
    data class Pixmap(val pixmap: XPixmap, val xOrigin: Int, val yOrigin: Int) : XResolvedWindowBackground
}

internal data class XDrawable(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
    val borderWidth: Int,
    val rootId: Int,
    val depth: Int,
)

private data class XFillPattern(
    val image: XImagePixels,
    val foreground: Int,
    val background: Int,
    val style: Int,
) {
    val width: Int get() = image.width
    val height: Int get() = image.height

    fun pixelAt(x: Int, y: Int): Int? {
        val pixel = image.pixels[y * width + x]
        return when (style) {
            XGraphicsContext.FillTiled -> pixel
            XGraphicsContext.FillStippled -> foreground.takeIf { patternBit(pixel) }
            XGraphicsContext.FillOpaqueStippled -> if (patternBit(pixel)) foreground else background
            else -> null
        }
    }

    private fun patternBit(pixel: Int): Boolean =
        ((pixel ushr 24) and 0xff) != 0 || (pixel and 0x00ff_ffff) != 0
}

internal data class XGraphicsContext(
    val id: Int,
    val drawableRootId: Int = X11Ids.RootWindow,
    val drawableDepth: Int = 24,
    var foreground: Int = 0,
    var background: Int = 0x00ff_ffff,
    var lineWidth: Int = 1,
    var lineStyle: Int = LineSolid,
    var capStyle: Int = CapButt,
    var joinStyle: Int = JoinMiter,
    var function: Int = GXcopy,
    var planeMask: Int = -1,
    var fontId: Int = 0,
) {
    var clipXOrigin: Int = 0
    var clipYOrigin: Int = 0
    var clipRectangles: List<XRectangleCommand>? = null
    var fillStyle: Int = FillSolid
    var fillRule: Int = EvenOddRule
    var tilePixmapId: Int? = null
    var stipplePixmapId: Int? = null
    var tilePixmap: XImagePixels? = null
    var stipplePixmap: XImagePixels? = null
    var tileStippleXOrigin: Int = 0
    var tileStippleYOrigin: Int = 0
    var dashOffset: Int = 0
    var dashes: List<Int> = listOf(4)
    var arcMode: Int = ArcPieSlice
    var graphicsExposures: Boolean = true

    fun effectiveClipRectangles(): List<XRectangleCommand>? =
        clipRectangles?.map { rectangle ->
            XRectangleCommand(
                x = rectangle.x + clipXOrigin,
                y = rectangle.y + clipYOrigin,
                width = rectangle.width,
                height = rectangle.height,
            )
        }

    companion object {
        const val GXclear = 0x0
        const val GXand = 0x1
        const val GXandReverse = 0x2
        const val GXcopy = 0x3
        const val GXandInverted = 0x4
        const val GXnoop = 0x5
        const val GXxor = 0x6
        const val GXor = 0x7
        const val GXnor = 0x8
        const val GXequiv = 0x9
        const val GXinvert = 0xa
        const val GXorReverse = 0xb
        const val GXcopyInverted = 0xc
        const val GXorInverted = 0xd
        const val GXnand = 0xe
        const val GXset = 0xf
        const val LineSolid = 0
        const val LineOnOffDash = 1
        const val LineDoubleDash = 2
        const val CapNotLast = 0
        const val CapButt = 1
        const val CapRound = 2
        const val CapProjecting = 3
        const val JoinMiter = 0
        const val JoinRound = 1
        const val JoinBevel = 2
        const val FillSolid = 0
        const val FillTiled = 1
        const val FillStippled = 2
        const val FillOpaqueStippled = 3
        const val EvenOddRule = 0
        const val WindingRule = 1
        const val ArcChord = 0
        const val ArcPieSlice = 1
    }
}

internal data class XPicture(
    val id: Int,
    val drawableId: Int?,
    val format: Int,
    var valueMask: Int = 0,
    val solidPixel: Int? = null,
    val linearGradient: XLinearGradient? = null,
    val radialGradient: XRadialGradient? = null,
    val conicalGradient: XConicalGradient? = null,
    var repeat: Int = XRender.RepeatNone,
    var alphaMap: Int = 0,
    var alphaXOrigin: Int = 0,
    var alphaYOrigin: Int = 0,
    var clipXOrigin: Int = 0,
    var clipYOrigin: Int = 0,
    var clipMask: Int = 0,
    var clipMaskImage: XImagePixels? = null,
    var clipRectangles: List<XRectangleCommand>? = null,
    var graphicsExposure: Boolean = false,
    var subwindowMode: Int = 0,
    var polyEdge: Int = 0,
    var polyMode: Int = 0,
    var dither: Int = 0,
    var componentAlpha: Boolean = false,
    var transform: List<Int> = IdentityTransform,
    var filterName: String? = null,
    var filterValues: List<Int> = emptyList(),
)

internal data class XFixesRegion(
    val id: Int,
    val rectangles: List<XRectangleCommand>,
)

internal data class XLinearGradient(
    val p1: XFixedPoint,
    val p2: XFixedPoint,
    val stops: List<Int>,
    val colors: List<Int>,
)

internal data class XFixedCircle(
    val center: XFixedPoint,
    val radius: Int,
)

internal data class XRadialGradient(
    val inner: XFixedCircle,
    val outer: XFixedCircle,
    val stops: List<Int>,
    val colors: List<Int>,
)

internal data class XConicalGradient(
    val center: XFixedPoint,
    val angle: Int,
    val stops: List<Int>,
    val colors: List<Int>,
)

internal val IdentityTransform: List<Int> = listOf(
    0x0001_0000,
    0,
    0,
    0,
    0x0001_0000,
    0,
    0,
    0,
    0x0001_0000,
)

internal class XRandrCrtcTransform(
    val transform: List<Int>,
    val filter: ByteArray,
    val values: List<Int>,
) {
    fun snapshot(): XRandrCrtcTransform =
        XRandrCrtcTransform(
            transform = transform.toList(),
            filter = filter.copyOf(),
            values = values.toList(),
        )

    companion object {
        val Identity = XRandrCrtcTransform(
            transform = IdentityTransform,
            filter = ByteArray(0),
            values = emptyList(),
        )
    }
}

internal data class XGlyphSet(
    val id: Int,
    val format: Int,
    val glyphs: MutableMap<Int, XGlyph> = linkedMapOf(),
)

internal data class XGlyph(
    val id: Int,
    val width: Int,
    val height: Int,
    val x: Int,
    val y: Int,
    val xOff: Int,
    val yOff: Int,
    val mask: XFramebuffer? = null,
)

internal data class XPictureGlyph(
    val id: Int,
    val width: Int,
    val height: Int,
    val x: Int,
    val y: Int,
    val xOff: Int,
    val yOff: Int,
    val sourceX: Int,
    val sourceY: Int,
)

internal data class XGlyphPlacement(
    val glyphId: Int,
    val x: Int,
    val y: Int,
)

internal enum class XDrawingKind {
    Clear,
    Line,
    Segment,
    Rectangle,
    FillPoly,
    FillRectangle,
    Arc,
    FillArc,
    Text,
    PutImage,
    CopyArea,
    CopyPlane,
}

internal data class XPoint(
    val x: Int,
    val y: Int,
)

internal data class XDrawingCommand(
    val drawableId: Int,
    val kind: XDrawingKind,
    val foreground: Int,
    val background: Int = 0x00ff_ffff,
    val lineWidth: Int = 1,
    val lineStyle: Int = XGraphicsContext.LineSolid,
    val capStyle: Int = XGraphicsContext.CapButt,
    val joinStyle: Int = XGraphicsContext.JoinMiter,
    val dashOffset: Int = 0,
    val dashes: List<Int> = emptyList(),
    val points: List<XPoint> = emptyList(),
    val rectangles: List<XRectangleCommand> = emptyList(),
    val arcs: List<XArcCommand> = emptyList(),
    val text: String = "",
    val imageDataUri: String? = null,
    val sourceDrawableId: Int? = null,
    val framebufferBacked: Boolean = false,
)

internal data class XRectangleCommand(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
)

internal data class XArcCommand(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
    val angle1: Int,
    val angle2: Int,
)

internal data class XFixedPoint(
    val x: Int,
    val y: Int,
)

internal data class XFixedLine(
    val p1: XFixedPoint,
    val p2: XFixedPoint,
)

internal data class XTrapezoidCommand(
    val top: Int,
    val bottom: Int,
    val left: XFixedLine,
    val right: XFixedLine,
)

internal data class XTriangleCommand(
    val p1: XFixedPoint,
    val p2: XFixedPoint,
    val p3: XFixedPoint,
)

internal data class XFixedQuad(
    val p1: XFixedPoint,
    val p2: XFixedPoint,
    val p3: XFixedPoint,
    val p4: XFixedPoint,
) {
    val points: List<XFixedPoint> get() = listOf(p1, p2, p3, p4)
}

internal data class XRenderColor(
    val red: Int,
    val green: Int,
    val blue: Int,
    val alpha: Int,
) {
    fun toPixel(): Int =
        XRender.argb32Pixel(red = red, green = green, blue = blue, alpha = alpha)
}

internal data class XColorPoint(
    val point: XFixedPoint,
    val color: XRenderColor,
)

internal data class XColorTriangleCommand(
    val p1: XColorPoint,
    val p2: XColorPoint,
    val p3: XColorPoint,
)

internal data class XColorSpanFix(
    val left: Int,
    val right: Int,
    val y: Int,
    val leftColor: XRenderColor,
    val rightColor: XRenderColor,
)

internal data class XColorTrapCommand(
    val top: XColorSpanFix,
    val bottom: XColorSpanFix,
) {
    fun toTrapezoid(): XTrapezoidCommand =
        XTrapezoidCommand(
            top = top.y,
            bottom = bottom.y,
            left = XFixedLine(
                p1 = XFixedPoint(top.left, top.y),
                p2 = XFixedPoint(bottom.left, bottom.y),
            ),
            right = XFixedLine(
                p1 = XFixedPoint(top.right, top.y),
                p2 = XFixedPoint(bottom.right, bottom.y),
            ),
        )
}

internal data class XProperty(
    val type: Int,
    val format: Int,
    val data: ByteArray,
)

internal data class XRandrOutputProperty(
    val config: XRandrOutputPropertyConfig = XRandrOutputPropertyConfig(),
    val current: XProperty? = null,
    val pending: XProperty? = null,
) {
    companion object {
        private val EmptyValue = XProperty(type = 0, format = 0, data = ByteArray(0))
        val Empty = XRandrOutputProperty(current = EmptyValue, pending = EmptyValue)
    }
}

internal data class XRandrOutputPropertyConfig(
    val pending: Boolean = false,
    val range: Boolean = false,
    val validValues: ByteArray = ByteArray(0),
)

internal data class XRandrMonitor(
    val name: Int,
    val primary: Boolean,
    val automatic: Boolean,
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
    val widthMillimeters: Int,
    val heightMillimeters: Int,
    val outputs: List<Int>,
) {
    fun snapshot(): XRandrMonitor = copy(outputs = outputs.toList())
}

internal data class XRandrMonitorSnapshot(
    val timestamp: Int,
    val monitors: List<XRandrMonitor>,
)

internal data class XRandrMonitorChange(
    val configureNotifyDispatches: List<XConfigureNotifyDispatch>,
    val screenChangeNotifyDispatches: List<XRandrScreenChangeNotifyDispatch>,
)

internal data class XExtension(
    val name: String,
    val majorOpcode: Int,
    val firstEvent: Int,
    val firstError: Int,
    val aliases: Set<String> = emptySet(),
)

internal data class XScreenSnapshot(
    val width: Int,
    val height: Int,
    val dpi: Int,
    val widthMillimeters: Int,
    val heightMillimeters: Int,
    val focusWindowId: Int,
    val pointer: XPointerStateSnapshot,
    val keyboardState: XKeyboardStateSnapshot,
    val fontPath: List<String>,
    val keyboardMapping: XKeyboardMappingSnapshot,
    val keyboardControl: XKeyboardControlSnapshot,
    val windows: List<XWindowSnapshot>,
    val pixmaps: List<XPixmapSnapshot>,
    val cursors: List<XCursorSnapshot>,
    val glxPixmaps: List<XGlxPixmapSnapshot>,
    val glxWindows: List<XGlxWindowSnapshot>,
    val glxPbuffers: List<XGlxPbufferSnapshot>,
    val overlaps: List<XWindowOverlap>,
    val drawings: List<XDrawingCommand>,
    val inputOperations: List<XInputOperation>,
    val inputControlOperations: List<XInputControlOperation>,
    val inputGrabs: List<XInputGrabSnapshot>,
    val passiveButtonGrabs: List<XPassiveButtonGrabSnapshot>,
    val passiveKeyGrabs: List<XPassiveKeyGrabSnapshot>,
    val serverGrabbed: Boolean,
    val glxOperations: List<XGlxOperation>,
    val renderOperations: List<XRenderOperation>,
    val renderPictures: List<XRenderPictureSnapshot>,
    val accessControl: XAccessControlSnapshot,
    val requestCounts: List<XRequestCount>,
    val extensionQueries: List<XExtensionQuery>,
    val unsupportedRequests: List<XUnsupportedRequest>,
)

internal data class XKeyboardMappingSnapshot(
    val keysymsPerKeycode: Int,
    val keycodes: List<XKeycodeMappingSnapshot>,
)

internal data class XKeycodeMappingSnapshot(
    val keycode: Int,
    val keysyms: List<Int>,
) {
    val keysymHexes: List<String> get() = keysyms.map { "0x${it.toUInt().toString(16)}" }
}

internal data class XKeyboardStateSnapshot(
    val modifierMask: Int,
    val keycodesDown: List<Int>,
)

internal data class XKeyboardControlSnapshot(
    val keyClickPercent: Int,
    val bellPercent: Int,
    val bellPitch: Int,
    val bellDuration: Int,
    val ledMask: Int,
    val globalAutoRepeat: Boolean,
    val autoRepeats: List<Int>,
) {
    val ledMaskHex: String get() = "0x${ledMask.toUInt().toString(16)}"
    val autoRepeatsHex: List<String> get() = autoRepeats.map { "0x${it.toString(16)}" }
}

internal data class XKeyboardPointerState(
    val modifiers: Int,
    val pointerButtons: Int,
)

internal data class XPointerStateSnapshot(
    val x: Int,
    val y: Int,
    val mask: Int,
    val logicalButtonsDown: List<Int>,
    val windowId: Int,
) {
    val windowIdHex: String get() = "0x${windowId.toUInt().toString(16)}"
}

internal data class XAccessControlSnapshot(
    val enabled: Boolean,
    val hosts: List<XAccessHost>,
)

internal data class XRequestCount(
    val name: String,
    val count: Int,
)

internal data class XExtensionQuery(
    val id: Int,
    val name: String,
    val supported: Boolean,
)

internal data class XUnsupportedRequest(
    val id: Int,
    val opcode: Int,
    val minorOpcode: Int,
    val name: String,
)

internal data class XInputOperation(
    val id: Int,
    val kind: String,
    val x: Int,
    val y: Int,
    val button: String,
    val targetWindowId: Int?,
    val deliveredEvents: Int,
) {
    val targetWindowIdHex: String? get() = targetWindowId?.let { "0x${it.toUInt().toString(16)}" }
}

internal data class XMotionHistoryEntry(
    val time: Int,
    val rootX: Int,
    val rootY: Int,
    val x: Int,
    val y: Int,
)

internal data class XInputControlOperation(
    val id: Int,
    val operation: String,
    val mode: Int,
    val time: Int,
) {
    val modeName: String get() = when (mode) {
        0 -> "AsyncPointer"
        1 -> "SyncPointer"
        2 -> "ReplayPointer"
        3 -> "AsyncKeyboard"
        4 -> "SyncKeyboard"
        5 -> "ReplayKeyboard"
        6 -> "AsyncBoth"
        7 -> "SyncBoth"
        else -> "Unknown"
    }
    val timeUnsigned: Long get() = time.toUInt().toLong()
}

internal data class XInputGrab(
    val owner: XEventSink,
    val kind: String,
    val windowId: Int,
    val ownerEvents: Boolean,
    val eventMask: Int,
    val pointerMode: Int,
    val keyboardMode: Int,
    val confineTo: Int?,
    val cursor: Int?,
    val time: Int,
    val activatedByPassiveGrab: Boolean = false,
    val passiveGrabKey: Int? = null,
) {
    fun snapshot(): XInputGrabSnapshot =
        XInputGrabSnapshot(
            kind = kind,
            windowId = windowId,
            ownerEvents = ownerEvents,
            eventMask = eventMask,
            pointerMode = pointerMode,
            keyboardMode = keyboardMode,
            confineTo = confineTo,
            cursor = cursor,
            time = time,
        )
}

internal data class XInputGrabSnapshot(
    val kind: String,
    val windowId: Int,
    val ownerEvents: Boolean,
    val eventMask: Int,
    val pointerMode: Int,
    val keyboardMode: Int,
    val confineTo: Int?,
    val cursor: Int?,
    val time: Int,
) {
    val windowIdHex: String get() = "0x${windowId.toUInt().toString(16)}"
    val eventMaskHex: String get() = "0x${eventMask.toUInt().toString(16)}"
    val confineToHex: String? get() = confineTo?.let { "0x${it.toUInt().toString(16)}" }
    val cursorHex: String? get() = cursor?.let { "0x${it.toUInt().toString(16)}" }
    val timeUnsigned: Long get() = time.toUInt().toLong()
}

internal data class XPointerGrabResult(
    val status: Int,
    val cursorNotifyDispatches: List<XXFixesCursorNotifyDispatch>,
)

internal object XGrabStatus {
    const val Success = 0
    const val AlreadyGrabbed = 1
    const val Frozen = 2
    const val InvalidTime = 3
    const val NotViewable = 4
}

internal data class XPassiveButtonGrab(
    val owner: XEventSink,
    val windowId: Int,
    val ownerEvents: Boolean,
    val eventMask: Int,
    val pointerMode: Int,
    val keyboardMode: Int,
    val confineTo: Int?,
    val cursor: Int?,
    val button: Int,
    val modifiers: Int,
    val releasedCombinations: Set<XPassiveButtonGrabCombination> = emptySet(),
) {
    fun snapshot(): XPassiveButtonGrabSnapshot =
        XPassiveButtonGrabSnapshot(
            windowId = windowId,
            ownerEvents = ownerEvents,
            eventMask = eventMask,
            pointerMode = pointerMode,
            keyboardMode = keyboardMode,
            confineTo = confineTo,
            cursor = cursor,
            button = button,
            modifiers = modifiers,
            releasedCombinations = releasedCombinations.sortedWith(
                compareBy<XPassiveButtonGrabCombination> { it.button }.thenBy { it.modifiers },
            ),
        )
}

internal data class XPassiveButtonGrabCombination(
    val button: Int,
    val modifiers: Int,
) {
    val buttonName: String get() = if (button == 0) "AnyButton" else button.toString()
    val modifiersName: String get() = if (modifiers == 0x8000) "AnyModifier" else "0x${modifiers.toUInt().toString(16)}"
}

internal data class XPassiveButtonGrabSnapshot(
    val windowId: Int,
    val ownerEvents: Boolean,
    val eventMask: Int,
    val pointerMode: Int,
    val keyboardMode: Int,
    val confineTo: Int?,
    val cursor: Int?,
    val button: Int,
    val modifiers: Int,
    val releasedCombinations: List<XPassiveButtonGrabCombination>,
) {
    val windowIdHex: String get() = "0x${windowId.toUInt().toString(16)}"
    val eventMaskHex: String get() = "0x${eventMask.toUInt().toString(16)}"
    val confineToHex: String? get() = confineTo?.let { "0x${it.toUInt().toString(16)}" }
    val cursorHex: String? get() = cursor?.let { "0x${it.toUInt().toString(16)}" }
    val buttonName: String get() = if (button == 0) "AnyButton" else button.toString()
    val modifiersName: String get() = if (modifiers == 0x8000) "AnyModifier" else "0x${modifiers.toUInt().toString(16)}"
}

internal data class XPassiveKeyGrab(
    val owner: XEventSink,
    val windowId: Int,
    val ownerEvents: Boolean,
    val modifiers: Int,
    val key: Int,
    val pointerMode: Int,
    val keyboardMode: Int,
    val releasedCombinations: Set<XPassiveKeyGrabCombination> = emptySet(),
) {
    fun snapshot(): XPassiveKeyGrabSnapshot =
        XPassiveKeyGrabSnapshot(
            windowId = windowId,
            ownerEvents = ownerEvents,
            modifiers = modifiers,
            key = key,
            pointerMode = pointerMode,
            keyboardMode = keyboardMode,
            releasedCombinations = releasedCombinations.sortedWith(
                compareBy<XPassiveKeyGrabCombination> { it.key }.thenBy { it.modifiers },
            ),
        )
}

internal data class XPassiveKeyGrabCombination(
    val key: Int,
    val modifiers: Int,
) {
    val keyName: String get() = if (key == 0) "AnyKey" else key.toString()
    val modifiersName: String get() = if (modifiers == 0x8000) "AnyModifier" else "0x${modifiers.toUInt().toString(16)}"
}

internal data class XPassiveKeyGrabSnapshot(
    val windowId: Int,
    val ownerEvents: Boolean,
    val modifiers: Int,
    val key: Int,
    val pointerMode: Int,
    val keyboardMode: Int,
    val releasedCombinations: List<XPassiveKeyGrabCombination>,
) {
    val windowIdHex: String get() = "0x${windowId.toUInt().toString(16)}"
    val keyName: String get() = if (key == 0) "AnyKey" else key.toString()
    val modifiersName: String get() = if (modifiers == 0x8000) "AnyModifier" else "0x${modifiers.toUInt().toString(16)}"
}

internal data class XGlxContext(
    val id: Int,
    val fbConfigId: Int,
    val screen: Int,
    val renderType: Int,
    val direct: Boolean,
)

internal data class XGlxLargeRenderState(
    val contextTag: Int,
    val requestTotal: Int,
    val requestsSoFar: Int,
    val bytesSoFar: Long,
    val paddedTotalBytes: Long,
)

internal data class XGlxPixmap(
    val id: Int,
    val pixmapId: Int,
    val visualId: Int,
    val fbConfigId: Int,
    val screen: Int,
    val width: Int,
    val height: Int,
    val depth: Int,
    val textureTarget: Int,
    val eventMask: Int = 0,
)

internal data class XGlxPixmapSnapshot(
    val id: Int,
    val pixmapId: Int,
    val visualId: Int,
    val fbConfigId: Int,
    val screen: Int,
    val width: Int,
    val height: Int,
    val depth: Int,
    val eventMask: Int,
    val textureTarget: Int,
) {
    val idHex: String get() = "0x${id.toUInt().toString(16)}"
    val pixmapIdHex: String get() = "0x${pixmapId.toUInt().toString(16)}"
    val visualIdHex: String get() = "0x${visualId.toUInt().toString(16)}"
    val fbConfigIdHex: String get() = "0x${fbConfigId.toUInt().toString(16)}"
}

internal data class XGlxWindow(
    val id: Int,
    val windowId: Int,
    val fbConfigId: Int,
    val screen: Int,
    val eventMask: Int = 0,
)

internal data class XGlxWindowSnapshot(
    val id: Int,
    val windowId: Int,
    val fbConfigId: Int,
    val screen: Int,
    val width: Int,
    val height: Int,
    val eventMask: Int,
) {
    val idHex: String get() = "0x${id.toUInt().toString(16)}"
    val windowIdHex: String get() = "0x${windowId.toUInt().toString(16)}"
    val fbConfigIdHex: String get() = "0x${fbConfigId.toUInt().toString(16)}"
}

internal data class XGlxPbuffer(
    val id: Int,
    val fbConfigId: Int,
    val screen: Int,
    val width: Int,
    val height: Int,
    val eventMask: Int = 0,
)

internal data class XGlxPbufferSnapshot(
    val id: Int,
    val fbConfigId: Int,
    val screen: Int,
    val width: Int,
    val height: Int,
    val eventMask: Int,
) {
    val idHex: String get() = "0x${id.toUInt().toString(16)}"
    val fbConfigIdHex: String get() = "0x${fbConfigId.toUInt().toString(16)}"
}

internal data class XGlxOperation(
    val id: Int,
    val minorOpcode: Int,
    val operation: String,
    val detail: String,
)

internal data class XRenderOperation(
    val id: Int,
    val minorOpcode: Int,
    val operation: String,
    val detail: String,
)

internal data class XRenderPictureSnapshot(
    val id: Int,
    val drawableId: Int?,
    val drawableKind: String,
    val format: Int,
    val solidPixel: Int?,
    val linearGradient: XLinearGradientSnapshot?,
    val radialGradient: XRadialGradientSnapshot?,
    val conicalGradient: XConicalGradientSnapshot?,
    val repeat: Int,
    val alphaMap: Int,
    val alphaXOrigin: Int,
    val alphaYOrigin: Int,
    val clipXOrigin: Int,
    val clipYOrigin: Int,
    val clipMask: Int,
    val clipRectangles: Int,
    val graphicsExposure: Boolean,
    val subwindowMode: Int,
    val polyEdge: Int,
    val polyMode: Int,
    val dither: Int,
    val componentAlpha: Boolean,
    val transform: List<Int>,
    val filterName: String?,
    val filterValues: List<Int>,
) {
    val idHex: String get() = "0x${id.toUInt().toString(16)}"
    val drawableIdHex: String get() = drawableId?.let { "0x${it.toUInt().toString(16)}" } ?: "none"
    val repeatName: String get() = XRender.repeatName(repeat)
    val alphaMapHex: String get() = if (alphaMap == 0) "none" else "0x${alphaMap.toUInt().toString(16)}"
    val clipMaskHex: String get() = if (clipMask == 0) "none" else "0x${clipMask.toUInt().toString(16)}"
    val ditherHex: String get() = if (dither == 0) "none" else "0x${dither.toUInt().toString(16)}"
    val transformHex: List<String> get() = transform.map { "0x${it.toUInt().toString(16)}" }
    val filterValueHex: List<String> get() = filterValues.map { "0x${it.toUInt().toString(16)}" }
}

internal data class XLinearGradientSnapshot(
    val p1: XFixedPoint,
    val p2: XFixedPoint,
    val stops: List<Int>,
    val colors: List<Int>,
) {
    val p1Hex: String get() = pointHex(p1)
    val p2Hex: String get() = pointHex(p2)
    val stopHex: List<String> get() = stops.map { "0x${it.toUInt().toString(16)}" }
    val colorHex: List<String> get() = colors.map { "0x${it.toUInt().toString(16).padStart(8, '0')}" }

}

internal data class XRadialGradientSnapshot(
    val inner: XFixedCircle,
    val outer: XFixedCircle,
    val stops: List<Int>,
    val colors: List<Int>,
) {
    val innerHex: String get() = circleHex(inner)
    val outerHex: String get() = circleHex(outer)
    val stopHex: List<String> get() = stops.map { "0x${it.toUInt().toString(16)}" }
    val colorHex: List<String> get() = colors.map { "0x${it.toUInt().toString(16).padStart(8, '0')}" }

    private fun circleHex(circle: XFixedCircle): String =
        "${pointHex(circle.center)},r=0x${circle.radius.toUInt().toString(16)}"
}

internal data class XConicalGradientSnapshot(
    val center: XFixedPoint,
    val angle: Int,
    val stops: List<Int>,
    val colors: List<Int>,
) {
    val centerHex: String get() = pointHex(center)
    val angleHex: String get() = "0x${angle.toUInt().toString(16)}"
    val stopHex: List<String> get() = stops.map { "0x${it.toUInt().toString(16)}" }
    val colorHex: List<String> get() = colors.map { "0x${it.toUInt().toString(16).padStart(8, '0')}" }
}

private fun pointHex(point: XFixedPoint): String =
    "0x${point.x.toUInt().toString(16)},0x${point.y.toUInt().toString(16)}"

internal data class XPixmapSnapshot(
    val id: Int,
    val width: Int,
    val height: Int,
    val depth: Int,
    val painted: Boolean,
    val framebufferDataUri: String?,
    val pictureIds: List<Int>,
    val matchingWindowIds: List<Int>,
) {
    val idHex: String get() = "0x${id.toUInt().toString(16)}"
    val pictureIdHexes: List<String> get() = pictureIds.map { "0x${it.toUInt().toString(16)}" }
    val matchingWindowIdHexes: List<String> get() = matchingWindowIds.map { "0x${it.toUInt().toString(16)}" }
}

internal data class XCursor(
    val id: Int,
    val generation: Long = 0,
    val kind: String,
    val sourcePixmapId: Int? = null,
    val maskPixmapId: Int? = null,
    val sourceFontId: Int? = null,
    val maskFontId: Int? = null,
    val sourceChar: Int? = null,
    val maskChar: Int? = null,
    val sourcePictureId: Int? = null,
    val animationElements: List<XAnimatedCursorElement> = emptyList(),
    val image: XCursorImage? = null,
    val name: XCursorName? = null,
    val hotspotX: Int? = null,
    val hotspotY: Int? = null,
    val foregroundRed: Int = 0,
    val foregroundGreen: Int = 0,
    val foregroundBlue: Int = 0,
    val backgroundRed: Int = 0xffff,
    val backgroundGreen: Int = 0xffff,
    val backgroundBlue: Int = 0xffff,
) {
    fun snapshot(): XCursorSnapshot =
        XCursorSnapshot(
            id = id,
            kind = kind,
            sourcePixmapId = sourcePixmapId,
            maskPixmapId = maskPixmapId,
            sourceFontId = sourceFontId,
            maskFontId = maskFontId,
            sourceChar = sourceChar,
            maskChar = maskChar,
            sourcePictureId = sourcePictureId,
            animationElements = animationElements,
            nameAtom = name?.atom,
            name = name?.name,
            hotspotX = hotspotX,
            hotspotY = hotspotY,
            foregroundRed = foregroundRed,
            foregroundGreen = foregroundGreen,
            foregroundBlue = foregroundBlue,
            backgroundRed = backgroundRed,
            backgroundGreen = backgroundGreen,
            backgroundBlue = backgroundBlue,
        )
}

internal data class XCursorImage(
    val width: Int,
    val height: Int,
    val hotspotX: Int,
    val hotspotY: Int,
    val sourceBits: BooleanArray,
    val maskBits: BooleanArray,
    val pixels: IntArray,
) {
    fun recolored(
        foregroundRed: Int,
        foregroundGreen: Int,
        foregroundBlue: Int,
        backgroundRed: Int,
        backgroundGreen: Int,
        backgroundBlue: Int,
    ): XCursorImage =
        copy(
            pixels = pixelsFor(
                sourceBits = sourceBits,
                maskBits = maskBits,
                foregroundRed = foregroundRed,
                foregroundGreen = foregroundGreen,
                foregroundBlue = foregroundBlue,
                backgroundRed = backgroundRed,
                backgroundGreen = backgroundGreen,
                backgroundBlue = backgroundBlue,
            ),
        )

    companion object {
        fun fromBits(
            width: Int,
            height: Int,
            hotspotX: Int,
            hotspotY: Int,
            sourceBits: BooleanArray,
            maskBits: BooleanArray,
            foregroundRed: Int,
            foregroundGreen: Int,
            foregroundBlue: Int,
            backgroundRed: Int,
            backgroundGreen: Int,
            backgroundBlue: Int,
        ): XCursorImage =
            XCursorImage(
                width = width,
                height = height,
                hotspotX = hotspotX,
                hotspotY = hotspotY,
                sourceBits = sourceBits.copyOf(),
                maskBits = maskBits.copyOf(),
                pixels = pixelsFor(
                    sourceBits = sourceBits,
                    maskBits = maskBits,
                    foregroundRed = foregroundRed,
                    foregroundGreen = foregroundGreen,
                    foregroundBlue = foregroundBlue,
                    backgroundRed = backgroundRed,
                    backgroundGreen = backgroundGreen,
                    backgroundBlue = backgroundBlue,
                ),
            )

        private fun pixelsFor(
            sourceBits: BooleanArray,
            maskBits: BooleanArray,
            foregroundRed: Int,
            foregroundGreen: Int,
            foregroundBlue: Int,
            backgroundRed: Int,
            backgroundGreen: Int,
            backgroundBlue: Int,
        ): IntArray {
            val foreground = XRender.argb32Pixel(
                red = foregroundRed,
                green = foregroundGreen,
                blue = foregroundBlue,
                alpha = 0xffff,
            )
            val background = XRender.argb32Pixel(
                red = backgroundRed,
                green = backgroundGreen,
                blue = backgroundBlue,
                alpha = 0xffff,
            )
            return IntArray(sourceBits.size) { index ->
                when {
                    !maskBits[index] -> 0
                    sourceBits[index] -> foreground
                    else -> background
                }
            }
        }
    }
}

internal data class XCursorSnapshot(
    val id: Int,
    val kind: String,
    val sourcePixmapId: Int?,
    val maskPixmapId: Int?,
    val sourceFontId: Int?,
    val maskFontId: Int?,
    val sourceChar: Int?,
    val maskChar: Int?,
    val sourcePictureId: Int?,
    val animationElements: List<XAnimatedCursorElement>,
    val nameAtom: Int?,
    val name: String?,
    val hotspotX: Int?,
    val hotspotY: Int?,
    val foregroundRed: Int,
    val foregroundGreen: Int,
    val foregroundBlue: Int,
    val backgroundRed: Int,
    val backgroundGreen: Int,
    val backgroundBlue: Int,
) {
    val idHex: String get() = "0x${id.toUInt().toString(16)}"
    val sourcePixmapIdHex: String? get() = sourcePixmapId?.let { "0x${it.toUInt().toString(16)}" }
    val maskPixmapIdHex: String? get() = maskPixmapId?.let { "0x${it.toUInt().toString(16)}" }
    val sourceFontIdHex: String? get() = sourceFontId?.let { "0x${it.toUInt().toString(16)}" }
    val maskFontIdHex: String? get() = maskFontId?.let { "0x${it.toUInt().toString(16)}" }
    val sourcePictureIdHex: String? get() = sourcePictureId?.let { "0x${it.toUInt().toString(16)}" }
    val nameAtomHex: String? get() = nameAtom?.let { "0x${it.toUInt().toString(16)}" }
    val foregroundHex: String get() = rgb16Hex(foregroundRed, foregroundGreen, foregroundBlue)
    val backgroundHex: String get() = rgb16Hex(backgroundRed, backgroundGreen, backgroundBlue)
}

internal data class XAnimatedCursorElement(
    val cursorId: Int,
    val delayMilliseconds: Long,
) {
    val cursorIdHex: String get() = "0x${cursorId.toUInt().toString(16)}"
}

private fun rgb16Hex(red: Int, green: Int, blue: Int): String =
    "0x${red.toUInt().toString(16).padStart(4, '0')}${green.toUInt().toString(16).padStart(4, '0')}${blue.toUInt().toString(16).padStart(4, '0')}"

internal data class XWindowSnapshot(
    val id: Int,
    val parentId: Int,
    val x: Int,
    val y: Int,
    val localX: Int,
    val localY: Int,
    val width: Int,
    val height: Int,
    val borderWidth: Int,
    val mapped: Boolean,
    val focused: Boolean,
    val stackingIndex: Int,
    val label: String,
    val visibleX: Int,
    val visibleY: Int,
    val visibleWidth: Int,
    val visibleHeight: Int,
    val backgroundPixel: Int,
    val backgroundPixmapId: Int?,
    val borderPixel: Int,
    val borderPixmapId: Int?,
    val framebufferDataUri: String?,
    val windowClass: Int,
    val depth: Int,
    val visual: Int,
    val bitGravity: Int,
    val winGravity: Int,
    val backingStore: Int,
    val backingPlanes: Int,
    val backingPixel: Int,
    val overrideRedirect: Boolean,
    val saveUnder: Boolean,
    val colormapId: Int?,
    val cursorId: Int?,
) {
    val idHex: String get() = "0x${id.toUInt().toString(16)}"
    val parentIdHex: String get() = "0x${parentId.toUInt().toString(16)}"
    val backgroundPixmapIdHex: String? get() = backgroundPixmapId?.let { "0x${it.toUInt().toString(16)}" }
    val borderPixmapIdHex: String? get() = borderPixmapId?.let { "0x${it.toUInt().toString(16)}" }
    val className: String get() = when (windowClass) {
        XWindowClass.InputOutput -> "InputOutput"
        XWindowClass.InputOnly -> "InputOnly"
        else -> "Class$windowClass"
    }
    val visualHex: String get() = "0x${visual.toUInt().toString(16)}"
    val colormapIdHex: String? get() = colormapId?.let { "0x${it.toUInt().toString(16)}" }
    val cursorIdHex: String? get() = cursorId?.let { "0x${it.toUInt().toString(16)}" }
}

internal data class XWindowOverlap(
    val lowerWindowId: Int,
    val upperWindowId: Int,
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
) {
    val lowerWindowIdHex: String get() = "0x${lowerWindowId.toUInt().toString(16)}"
    val upperWindowIdHex: String get() = "0x${upperWindowId.toUInt().toString(16)}"
}
