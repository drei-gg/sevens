package gg.seven.alarm

import gg.seven.alarm.core.*
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Alarm & Loops' model without its screens (iOS `AlarmModel`): the archive, reconciling it with the
 * system's alarms, and every action a screen, a ringing alarm or a boot can take. One per process,
 * owned by [AlarmApplication], so the receivers and the ringing service use the same state as the
 * UI. Rules come from the core; this keeps the order of operations iOS uses.
 */
class AlarmEngine(
    val repository: ArchiveRepository,
    val driver: AlarmDriver,
    val isPreview: Boolean = false,
    private val widgetSink: suspend (AlarmWidgetSnapshot?) -> Unit = {},
) {
    private val _archive = MutableStateFlow(newLoopArchive())
    val archive: StateFlow<LoopArchive> = _archive.asStateFlow()
    private val _ready = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = _ready.asStateFlow()
    val loadError = MutableStateFlow<String?>(null)
    val error = MutableStateFlow<String?>(null)
    val scheduling = MutableStateFlow("Checking alarm registrations…")
    val busy = MutableStateFlow(false)

    /**
     * The mission of the alarm that is ringing or was opened (iOS `mission`), shown full screen.
     */
    val mission = MutableStateFlow<Registration?>(null)

    /** A mission tried from a routine card or the editor; nothing rings (iOS `previewMission`). */
    val previewMission = MutableStateFlow<Routine?>(null)

    private val syncLock = Mutex()
    private val actionLock = Mutex()

    val widgetError = MutableStateFlow<String?>(null)

    private suspend fun publishWidget(verifiedIds: Set<UUID> = emptySet()) {
        try {
            widgetSink(
                if (loadError.value != null) null
                else
                    alarmWidgetSnapshot(
                        archive.value,
                        verifiedIds.toList(),
                        Instant.now(),
                        deviceCalendar(),
                    )
            )
            widgetError.value = null
        } catch (cancel: kotlinx.coroutines.CancellationException) {
            throw cancel
        } catch (_: Exception) {
            // An unprojectable schedule must not leave a previously checked widget looking current.
            runCatching { widgetSink(null) }
            widgetError.value =
                "The Home Screen widget could not refresh. Open the app for the latest schedule."
        }
    }

    private fun liveWidgetIds(excluded: Set<UUID> = emptySet()): Set<UUID> =
        archive.value.installed
            .filter { it.id !in excluded && driver.widgetIsLive(it) }
            .mapTo(HashSet()) { it.id }

    private suspend fun publishVerifiedWidget(excluded: Set<UUID> = emptySet()) {
        val ids = runCatching { liveWidgetIds(excluded) }.getOrDefault(emptySet())
        publishWidget(ids)
    }

    suspend fun reload() {
        try {
            _archive.value = repository.read()
            _ready.value = true
            loadError.value = null
            _archive.value.pending?.let { mission.value = it }
        } catch (e: Exception) {
            loadError.value = e.sentence
        }
        publishWidget()
    }

    suspend fun mutate(change: (LoopArchive) -> LoopArchive): Boolean =
        try {
            val before = archive.value
            _archive.value = repository.change(change)
            if (
                before.routines != archive.value.routines ||
                    (!syncLock.isLocked && before.installed != archive.value.installed)
            )
                publishWidget()
            true
        } catch (e: Exception) {
            error.value = e.sentence
            false
        }

    /** Like [mutate], for callers that must stop on failure. */
    private suspend fun mutateOrThrow(failure: String, change: (LoopArchive) -> LoopArchive) {
        if (!mutate(change)) throw LoopFailure(failure)
    }

    private suspend fun <T> exclusive(block: suspend () -> T): T? {
        if (!actionLock.tryLock()) return null
        busy.value = true
        try {
            return block()
        } finally {
            busy.value = false
            actionLock.unlock()
        }
    }

    suspend fun save(routine: Routine, paid: Boolean): Boolean {
        if (!paid) {
            error.value =
                "Start the trial or unlock in Settings to create or edit schedules. Existing enabled alarms keep running."
            return false
        }
        return exclusive {
            try {
                core { routineCheckSave(routine, Instant.now()) }
            } catch (e: Exception) {
                error.value = e.sentence
                return@exclusive false
            }
            if (
                !mutate { a ->
                    a.copy(routines = a.routines.filter { it.id != routine.id } + routine)
                }
            )
                return@exclusive false
            synchronize()
            true
        } ?: false
    }

    suspend fun toggle(routine: Routine, enabled: Boolean, paid: Boolean) {
        if (enabled && !paid) {
            error.value = "Unlock to re-enable this routine. Existing enabled alarms keep running."
            return
        }
        mutate { a ->
            a.copy(
                routines =
                    a.routines.map { if (it.id == routine.id) it.copy(enabled = enabled) else it }
            )
        }
        synchronize()
    }

    suspend fun duplicate(routine: Routine, paid: Boolean) = save(duplicateRoutine(routine), paid)

    fun newRoutine(kind: RoutineKind = RoutineKind.ALARM): Routine =
        alarmDefaultsRoutine(
            archive.value.alarmDefaults ?: defaultAlarmDefaults(),
            kind,
            Instant.now(),
        )

    suspend fun saveDefaults(defaults: AlarmDefaults): Boolean {
        try {
            core { alarmDefaultsValidate(defaults) }
        } catch (e: Exception) {
            error.value = e.sentence
            return false
        }
        return mutate { it.copy(alarmDefaults = defaults) }
    }

    suspend fun nap(minutes: Int, paid: Boolean): Boolean {
        val routine =
            try {
                core { napRoutine(archive.value.alarmDefaults, minutes, Instant.now()) }
            } catch (e: Exception) {
                error.value = e.sentence
                return false
            }
        return save(routine, paid)
    }

    suspend fun toggleGroup(name: String, enabled: Boolean, paid: Boolean) {
        if (enabled && !paid) {
            error.value = "Unlock to re-enable routines."
            return
        }
        mutate { a ->
            a.copy(
                routines =
                    a.routines.map {
                        if (it.group?.trim() == name) it.copy(enabled = enabled) else it
                    }
            )
        }
        synchronize()
    }

    suspend fun clearWakeUpChecks() {
        try {
            for (row in archive.value.installed.filter { it.purpose == "followUp" }) {
                // A check that already rang has no native alarm left to cancel; drop its receipt
                // anyway.
                if (driver.isLive(row)) driver.cancel(row)
                if (!mutate { a -> a.copy(installed = a.installed.filter { it.id != row.id }) })
                    return
            }
            synchronize()
        } catch (e: Exception) {
            error.value = e.sentence
        }
    }

    suspend fun delete(routine: Routine) {
        mutate { a -> a.copy(routines = a.routines.filter { it.id != routine.id }) }
        synchronize()
    }

    fun desired(): List<Registration> = core { desiredRegistrations(archive.value, Instant.now()) }

    /**
     * Reconciles the system with the archive (iOS `synchronize`). Receipts are stored before
     * installing, so a later write failure is recoverable; a failed addition leaves old
     * registrations alone. [rearm] reinstalls every registration even if it looks live: after the
     * clock or time zone changed, an armed alarm's instant is stale.
     */
    suspend fun synchronize(rearm: Boolean = false) {
        if (!ready.value) return
        syncLock.withLock {
            try {
                val now = Instant.now()
                // A one-off that has rung is finished: show it as off rather than enabled with a
                // past date.
                val finished = routinesFinishPastOnce(archive.value.routines, now)
                if (finished != archive.value.routines)
                    mutate { it.copy(routines = routinesFinishPastOnce(it.routines, now)) }
                val desired = desired()
                val failures = mutableListOf<String>()
                val unverified = mutableSetOf<UUID>()
                for (row in desired) {
                    val old = archive.value.installed.firstOrNull { it.id == row.id }
                    if (!rearm && old == row && driver.isLive(row)) continue
                    try {
                        if (old == null)
                            mutateOrThrow("Could not save alarm receipt.") {
                                it.copy(installed = it.installed + row)
                            }
                        driver.schedule(row)
                        mutateOrThrow(
                            "Alarm scheduled, but its receipt could not be updated. Retry before relying on it."
                        ) { a ->
                            a.copy(installed = a.installed.filter { it.id != row.id } + row)
                        }
                    } catch (e: Exception) {
                        unverified += row.id
                        failures += "${row.routine.name}: ${e.sentence}"
                    }
                }
                val wanted = desired.mapTo(HashSet()) { it.id }
                if (failures.isEmpty())
                    for (row in archive.value.installed.filter { it.id !in wanted }) {
                        val live = driver.isLive(row)
                        if (receiptKeep(row, archive.value, live, Instant.now())) {
                            // A kept extra may have lost its alarm (a reboot): put it back.
                            if (rearm || (!live && row.date?.isAfter(Instant.now()) == true))
                                runCatching { driver.schedule(row) }
                            continue
                        }
                        try {
                            if (live) driver.cancel(row)
                            mutate { a ->
                                a.copy(installed = a.installed.filter { it.id != row.id })
                            }
                        } catch (e: Exception) {
                            failures += "Could not remove ${row.routine.name}: ${e.sentence}"
                        }
                    }
                publishVerifiedWidget(unverified)
                scheduling.value =
                    when {
                        failures.isNotEmpty() -> failures.joinToString("\n")
                        isPreview -> driver.status
                        else -> "${timesRegistered(desired.size)} ${driver.status}."
                    }
            } catch (e: Exception) {
                scheduling.value = e.sentence
                publishWidget()
            }
        }
    }

    /** Opens the mission of a ringing alarm (iOS `openNative`): it becomes the pending mission. */
    suspend fun openNative(id: UUID) {
        reload()
        var row =
            archive.value.installed.firstOrNull { it.id == id }
                ?: throw LoopFailure("The alarm is no longer in your saved schedule.")
        archive.value.pending?.takeIf { it.id == row.id }?.let { row = it }
        // Each ringing is its own occurrence, so snoozes and checks from different days never share
        // IDs.
        if (row.occurrenceId == null) row = row.copy(occurrenceId = UUID.randomUUID())
        val opened = row
        mutateOrThrow("Could not save the active mission.") { it.copy(pending = opened) }
        mission.value = opened
    }

    /**
     * The ringing alarm's own Stop (iOS `dismissNative`): a dismissal, not a completed mission, and
     * the wake-up check if the routine has one.
     */
    suspend fun dismissNative(id: UUID) {
        reload()
        var row = archive.value.installed.firstOrNull { it.id == id } ?: return
        archive.value.pending?.takeIf { it.id == row.id }?.let { row = it }
        if (row.purpose == TEST) {
            // The Settings test is not a routine: no record, no wake-up check.
            val rowId = row.id
            mutateOrThrow("Could not stop the test alarm.") { a ->
                a.copy(pending = a.pending.takeUnless { it?.id == rowId })
            }
            if (mission.value?.id == rowId) mission.value = null
            return
        }
        if (row.occurrenceId == null) row = row.copy(occurrenceId = UUID.randomUUID())
        val event =
            newLoopEvent(
                row.routine,
                EventKind.DISMISSED,
                Instant.now(),
                "Stopped with the system control; mission completion is unconfirmed.",
            )
        val rowId = row.id
        mutateOrThrow("Could not record dismissal.") { a ->
            a.copy(events = a.events + event, pending = a.pending.takeUnless { it?.id == rowId })
        }
        if (mission.value?.id == rowId) mission.value = null
        if (row.purpose != "followUp" && row.routine.followUpMinutes > 0)
            extra(row, row.routine.followUpMinutes, "followUp")
    }

    suspend fun extra(row: Registration, minutes: Int, purpose: String) {
        val extra = followOnRegistration(row, minutes, purpose, Instant.now())
        mutateOrThrow("Could not save follow-up alarm.") { a ->
            a.copy(installed = a.installed.filter { it.id != extra.id } + extra)
        }
        try {
            driver.schedule(extra)
            publishVerifiedWidget()
        } catch (e: Exception) {
            mutate { a -> a.copy(installed = a.installed.filter { it.id != extra.id }) }
            throw e
        }
    }

    /** Ends a mission (iOS `finish`): completed, snoozed or emergency stop. */
    suspend fun finish(row: Registration, kind: EventKind): Boolean =
        exclusive {
            try {
                // The Settings test alarm never writes History or schedules a follow-up.
                val record = row.purpose != TEST
                if (kind == EventKind.SNOOZED) {
                    if (row.snoozes >= row.routine.snoozeLimit)
                        throw LoopFailure("Snooze limit reached.")
                    extra(row, row.routine.snoozeMinutes, "snooze")
                } else if (
                    record &&
                        kind == EventKind.COMPLETED &&
                        row.purpose != "followUp" &&
                        row.routine.followUpMinutes > 0
                )
                    extra(row, row.routine.followUpMinutes, "followUp")
                if (
                    kind == EventKind.EMERGENCY_STOP ||
                        (kind == EventKind.COMPLETED && row.purpose == "followUp")
                ) {
                    for (extra in
                        archive.value.installed.filter {
                            it.routine.id == row.routine.id &&
                                it.purpose != "schedule" &&
                                it.id != row.id
                        }) {
                        if (driver.isLive(extra)) driver.cancel(extra)
                        mutate { a -> a.copy(installed = a.installed.filter { it.id != extra.id }) }
                    }
                }
                driver.stop(row)
                val event = newLoopEvent(row.routine, kind, Instant.now(), "")
                if (
                    !mutate { a ->
                        a.copy(events = if (record) a.events + event else a.events, pending = null)
                    }
                )
                    return@exclusive false
                mission.value = null
                publishVerifiedWidget()
                true
            } catch (e: Exception) {
                error.value = e.sentence
                false
            }
        } ?: false

    suspend fun manual(routine: Routine, date: Instant) {
        val event = newLoopEvent(routine, EventKind.MANUAL, date, "")
        mutate { it.copy(events = it.events + event) }
    }

    /**
     * Settings' ten-second test: the selected default tone, and nothing else from the defaults. It
     * has no snooze or wake-up check, and Stop or Open never writes History.
     */
    suspend fun testAlarm() {
        try {
            val routine =
                newRoutine()
                    .copy(
                        name = "Test alarm",
                        timing = Timing.ONCE,
                        once = Instant.now().plusSeconds(10),
                        snoozeLimit = 0,
                        followUpMinutes = 0,
                    )
            val row =
                newRegistration(UUID.randomUUID(), routine)
                    .copy(date = routine.once, purpose = TEST)
            if (!mutate { it.copy(installed = it.installed + row) }) return
            driver.schedule(row)
            scheduling.value = "Test alarm scheduled for 10 seconds from now."
        } catch (e: Exception) {
            error.value = e.sentence
        }
    }

    /** The receipt a ringing alarm belongs to, if it is still saved. */
    suspend fun receipt(id: UUID): Registration? {
        reload()
        return archive.value.installed.firstOrNull { it.id == id }
    }

    companion object {
        /** The purpose of Settings' test alarm's registration (the core keeps its receipt). */
        const val TEST = "test"

        fun timesRegistered(count: Int) =
            if (count == 1) "1 alarm time registered." else "$count alarm times registered."
    }
}
