package ilab.iptv.player.core.log

import ilab.iptv.player.core.common.Clock
import ilab.iptv.player.core.common.LogEvent
import ilab.iptv.player.core.common.Redactor
import ilab.iptv.player.core.log.file.DayKeyFormat
import ilab.iptv.player.core.log.file.JavaIoLogFileSystem
import ilab.iptv.player.core.log.file.LogFilePolicy
import ilab.iptv.player.core.log.file.LogFileSystem
import ilab.iptv.player.core.log.file.LogJsonLine
import java.util.concurrent.atomic.AtomicLong

/**
 * Writes JSONL to the app's external files directory so a log survives the process and can be taken
 * off the TV with `adb pull` (docs/03 §5 `FileSink`, §6 落盘与轮转, L3).
 *
 * Behaviour, all of it from docs/03 §6 unless noted:
 * - **path** `…/files/logs/` (no storage permission needed; `adb pull` works on the app's own
 *   external directory);
 * - **rotation** by day *and* by size: `iptv-20260922.log`; when it passes
 *   [LogFilePolicy.maxFileBytes] it is renamed to `iptv-20260922.1.log` and the base name starts
 *   empty again, so the newest data is always in the base file;
 * - **retention** 7 days or 50 MB, oldest first, with the copy-count ceiling from
 *   [LogFilePolicy.maxFileCount] (not in the docs — a chosen default, recorded in the P1-8 report).
 *   Since the BUG-011 fix the ceiling only reaps **earlier** days: the active base file and today's
 *   segment files are never deleted by it, so the file the tester is about to `adb pull` survives;
 *   today's segments are bounded by the 50 MB budget alone (the manual 清理 button uses the same
 *   rule, because both go through [LogFilePolicy.cleanupPlan]);
 * - **cleanup** on the first write after startup (the "启动时" hook) and after every rotation;
 *   [cleanupNow] is the manual hook the diagnostics page (and, later, "每次刷新结束") calls;
 * - **write failure degrades instead of crashing or spinning**: the error is counted, the reason
 *   kept in [status], and the sink retries at most once per [degradedRetryMs] while a full disk or
 *   a missing permission persists;
 * - **flush contract** (docs/03 §5): [flush] forces the current file's data to the disk, so the
 *   `Logger.flush()` on the exit/crash path cannot lose the tail.
 *
 * All IO runs on the bus' single dispatch thread: the constructor touches nothing, and the first
 * write opens the directory. That keeps `Application.onCreate` (which builds the Hilt graph) free
 * of file work.
 *
 * `enabled` is the "写入日志文件" switch of docs/03 §14. The level knob stays on [LogBus]: the file
 * receives exactly what the bus lets through (INFO+ by default, DEBUG/VERBOSE after the diagnostics
 * switch), which is the "默认落盘" column of docs/03 §3.1.
 *
 * @param policy `null` when the device has no external files directory — the sink then reports
 *   itself unhealthy and drops events instead of throwing.
 */
class FileSink(
    private val policy: LogFilePolicy?,
    private val dayKey: DayKeyFormat,
    private val clock: Clock,
    private val fs: LogFileSystem = JavaIoLogFileSystem(),
    private val redactor: Redactor? = null,
    override val id: String = ID,
    private val degradedRetryMs: Long = DEFAULT_DEGRADED_RETRY_MS,
) : LogSink {

    init {
        require(id.isNotBlank()) { "id must not be blank" }
        require(degradedRetryMs > 0L) { "degradedRetryMs must be positive, was $degradedRetryMs" }
    }

    /** docs/03 §14 "写入日志文件"（默认开）; the settings page (P2-8) flips this. */
    @Volatile
    var enabled: Boolean = true

    private val writtenLines = AtomicLong()
    private val writtenBytes = AtomicLong()
    private val rotations = AtomicLong()
    private val deletedFiles = AtomicLong()
    private val writeFailures = AtomicLong()
    private val syncFailures = AtomicLong()
    private val skippedDisabled = AtomicLong()
    private val skippedNoDirectory = AtomicLong()
    private val skippedDegraded = AtomicLong()

    /** Day key of the open file, e.g. `20260922`. */
    @Volatile
    private var activeDay: String? = null

    @Volatile
    private var activePath: String? = null

    private var activeBytes: Long = 0L

    /** The current problem, cleared by the next successful write; the counters keep the history. */
    @Volatile
    private var lastError: String? = null

    @Volatile
    private var degradedUntilMs: Long = 0L

    /** Body of the sink: called from the bus' dispatch thread only. */
    override fun write(event: LogEvent) {
        val policy = this.policy ?: run {
            skippedNoDirectory.incrementAndGet()
            return
        }
        if (!enabled) {
            skippedDisabled.incrementAndGet()
            return
        }
        val now = clock.nowMs()
        if (now < degradedUntilMs) {
            skippedDegraded.incrementAndGet()
            return
        }
        try {
            if (!openIfNeeded(policy, now)) {
                skippedDegraded.incrementAndGet()
                return
            }
            // The day boundary has to be decided *before* the append: otherwise the first event
            // after midnight would still land in yesterday's file and only then roll over.
            switchDayIfNeeded(policy, now)
            rotateIfOverCeiling(policy, now)
            val path = activePath ?: run {
                skippedDegraded.incrementAndGet()
                return
            }
            val result = fs.append(path, LogJsonLine.encode(event, redactor) + "\n")
            if (!result.ok) {
                degrade(now, "append failed: ${result.reason}")
                return
            }
            activeBytes += result.bytesWritten
            writtenLines.incrementAndGet()
            writtenBytes.addAndGet(result.bytesWritten)
            lastError = null
            degradedUntilMs = 0L
            rotateIfOverCeiling(policy, now)
        } catch (t: Throwable) {
            // A sink must never throw into the bus (docs/03 §5); an unusable card or a full disk
            // becomes a counted, retry-limited degradation instead.
            degrade(now, "unexpected: ${t.javaClass.simpleName}${t.message?.let { ": $it" } ?: ""}")
        }
    }

    /** docs/03 §5: flush is the crash/exit guarantee — force the tail onto the disk. */
    override fun flush() {
        val path = activePath ?: return
        if (!fs.sync(path)) {
            syncFailures.incrementAndGet()
            if (lastError == null) lastError = "sync failed: $path"
        }
    }

    /**
     * Deletes what the retention rules no longer allow (docs/03 §7.3 "立即清理日志" and the
     * "每次刷新结束" hook) and returns how many files went away.
     */
    fun cleanupNow(): Int {
        val policy = this.policy ?: return 0
        return try {
            val removed = deletePlanned(policy)
            deletedFiles.addAndGet(removed.toLong())
            removed
        } catch (t: Throwable) {
            degrade(clock.nowMs(), "cleanup failed: ${t.javaClass.simpleName}")
            0
        }
    }

    /** Live view for the diagnostics page; it lists the directory, so call it at UI cadence. */
    fun status(): FileSinkStatus {
        val now = clock.nowMs()
        val entries = policy?.let { fs.list(it.directory) } ?: emptyList()
        val managed = entries.filter { policy?.isManaged(it.name) == true }
        return FileSinkStatus(
            directory = policy?.directory,
            enabled = enabled,
            healthy = lastError == null,
            activeFile = activePath?.substringAfterLast('/'),
            activeBytes = activeBytes,
            fileCount = managed.size,
            totalBytes = managed.sumOf { it.sizeBytes },
            writtenLines = writtenLines.get(),
            writtenBytes = writtenBytes.get(),
            rotations = rotations.get(),
            deletedFiles = deletedFiles.get(),
            writeFailures = writeFailures.get(),
            syncFailures = syncFailures.get(),
            skippedDisabled = skippedDisabled.get(),
            skippedDegraded = skippedDegraded.get(),
            skippedNoDirectory = skippedNoDirectory.get(),
            lastError = lastError,
            retryInMs = (degradedUntilMs - now).coerceAtLeast(0L),
            maxFileBytes = policy?.maxFileBytes ?: 0L,
            maxTotalBytes = policy?.maxTotalBytes ?: 0L,
            maxFileCount = policy?.maxFileCount ?: 0,
            retentionDays = policy?.retentionDays ?: 0,
        )
    }

    /** Opens today's file (and cleans up) on first use — never in the constructor. */
    private fun openIfNeeded(policy: LogFilePolicy, now: Long): Boolean {
        if (activePath != null) return true
        if (!fs.ensureDirectory(policy.directory)) {
            degrade(now, "cannot create ${policy.directory}")
            return false
        }
        val day = dayKey.key(now)
        val path = policy.activeFilePath(day)
        activeDay = day
        activePath = path
        activeBytes = fs.size(path)
        deletePlanned(policy).also { deletedFiles.addAndGet(it.toLong()) }
        return true
    }

    /** Day boundary → today's file name, so "按天轮转" (docs/03 §6) splits at midnight. */
    private fun switchDayIfNeeded(policy: LogFilePolicy, now: Long) {
        val day = dayKey.key(now)
        if (day == activeDay) return
        activeDay = day
        activePath = policy.activeFilePath(day)
        activeBytes = fs.size(activePath.orEmpty())
        deletePlanned(policy).also { deletedFiles.addAndGet(it.toLong()) }
    }

    /**
     * Rotates as soon as the file on disk reaches [LogFilePolicy.maxFileBytes]. The size is read
     * from the file instead of trusting the counter alone, so a file that grew outside this sink
     * (a script, a previous session) is rotated too; the counter is only a cache for the UI.
     */
    private fun rotateIfOverCeiling(policy: LogFilePolicy, now: Long) {
        val path = activePath ?: return
        activeBytes = fs.size(path)
        if (activeBytes < policy.maxFileBytes) return
        rotateBySize(policy, now)
    }

    /** Renames the full file to a segment and starts the base name again (docs/03 §6). */
    private fun rotateBySize(policy: LogFilePolicy, now: Long) {
        val day = activeDay ?: return
        val from = activePath ?: return
        val to = policy.nextSegmentFilePath(day, fs.list(policy.directory))
        if (!fs.rename(from, to)) {
            degrade(now, "rotate failed: ${from.substringAfterLast('/')} -> ${to.substringAfterLast('/')}")
            return
        }
        rotations.incrementAndGet()
        activePath = policy.activeFilePath(day)
        activeBytes = fs.size(activePath.orEmpty())
        deletePlanned(policy).also { deletedFiles.addAndGet(it.toLong()) }
    }

    private fun deletePlanned(policy: LogFilePolicy): Int {
        val entries = fs.list(policy.directory)
        val plan = policy.cleanupPlan(entries, clock.nowMs(), activePath, dayKey)
        var removed = 0
        plan.forEach { if (fs.delete(it)) removed++ }
        return removed
    }

    private fun degrade(now: Long, reason: String) {
        writeFailures.incrementAndGet()
        lastError = reason
        degradedUntilMs = now + degradedRetryMs
    }

    companion object {
        const val ID = "file"

        /** One retry per minute while the filesystem stays broken: no hot loop, no lost tail. */
        const val DEFAULT_DEGRADED_RETRY_MS = 60_000L
    }
}

/** Everything the diagnostics page shows about the on-disk log (docs/03 §7.1/§7.3). */
data class FileSinkStatus(
    val directory: String?,
    val enabled: Boolean,
    val healthy: Boolean,
    val activeFile: String?,
    val activeBytes: Long,
    val fileCount: Int,
    val totalBytes: Long,
    val writtenLines: Long,
    val writtenBytes: Long,
    val rotations: Long,
    val deletedFiles: Long,
    val writeFailures: Long,
    val syncFailures: Long,
    val skippedDisabled: Long,
    val skippedDegraded: Long,
    val skippedNoDirectory: Long,
    val lastError: String?,
    val retryInMs: Long,
    val maxFileBytes: Long,
    val maxTotalBytes: Long,
    val maxFileCount: Int,
    val retentionDays: Int,
)
