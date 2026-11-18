package com.skeler.scanely.core.reports

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.core.content.edit
import com.skeler.scanely.BuildConfig
import com.skeler.scanely.core.network.AppReportDto
import com.skeler.scanely.core.network.ScanlyPublicApi
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "FailureReporter"

/**
 * Crash and conversion-failure reports for the alert chat (Worker `/v1/reports`).
 *
 * A report is fixed fields only: its kind, the tool, an error *type* (a class or failure name, never
 * a message), the app frame it was thrown from, timing and page count, and the app/Android
 * version. No document content, file names, paths, messages or user id ever go in — the Worker
 * drops a report with anything else in those fields.
 *
 * Every report is written to disk first and sent from there, so one made offline, or by a crash
 * that kills the process, goes out on the next launch. Native crashes and ANRs are not seen by the
 * JVM handler; Android records them, and they are read back on the next launch (API 30+). A
 * conversion that was running when the process died leaves its marker behind, so it is reported
 * as that conversion failing.
 */
@Singleton
class FailureReporter @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val api: ScanlyPublicApi
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
    private val dir get() = File(context.filesDir, "reports").apply { mkdirs() }
    private val inflight get() = File(dir, INFLIGHT)

    /** From Application.onCreate: the crash handler first, then whatever the last run left. */
    fun install() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                val marker = readInflight()
                write(
                    report(
                        kind = if (marker != null) KIND_CONVERSION else KIND_CRASH,
                        direction = marker?.tool,
                        error = rootType(error),
                        where = appFrame(error),
                        durationMs = marker?.let { SystemClock.elapsedRealtime() - it.startedElapsed }
                            ?.takeIf { it >= 0 },
                        pages = marker?.pages
                    )
                )
                inflight.delete()
            } catch (@Suppress("TooGenericExceptionCaught") e: Throwable) {
                Log.w(TAG, "Crash report not written: ${e.javaClass.simpleName}")
            }
            previous?.uncaughtException(thread, error)
        }
        scope.launch {
            collectLastRun()
            flush()
        }
    }

    /** Written before a conversion runs, so a process death during it is reported as that conversion. */
    fun conversionStarted(tool: String, pages: Int?) {
        runCatching {
            val marker = Inflight(tool, pages, System.currentTimeMillis(), SystemClock.elapsedRealtime())
            inflight.writeText(json.encodeToString(Inflight.serializer(), marker))
        }
    }

    fun conversionEnded() {
        inflight.delete()
    }

    /** A conversion that failed with a message on screen: [error] is the failure's name. */
    fun conversionFailed(tool: String, error: String, durationMs: Long, pages: Int?, where: String? = null) {
        scope.launch {
            write(report(KIND_CONVERSION, tool, error, where, durationMs, pages))
            flush()
        }
    }

    private fun collectLastRun() {
        val marker = readInflight()
        val exit = lastExit()
        when {
            marker != null -> {
                write(diedDuring(marker, exit?.takeIf { it.timestamp >= marker.startedWall }))
                inflight.delete()
            }
            exit?.reason == ApplicationExitInfo.REASON_CRASH_NATIVE ->
                write(report(KIND_NATIVE, null, "signal_${exit.status}", null, null, null))
            exit?.reason == ApplicationExitInfo.REASON_ANR -> write(report(KIND_ANR, null, "ANR", null, null, null))
        }
    }

    /** A conversion the process died in, with how it died when Android recorded it. */
    private fun diedDuring(marker: Inflight, died: LastExit?): AppReportDto = report(
        kind = KIND_CONVERSION,
        direction = marker.tool,
        error = when (died?.reason) {
            null -> "ProcessDied"
            ApplicationExitInfo.REASON_CRASH_NATIVE -> "NativeCrash"
            ApplicationExitInfo.REASON_ANR -> "ANR"
            ApplicationExitInfo.REASON_LOW_MEMORY -> "LowMemoryKill"
            else -> "ProcessDied_${died.reason}"
        },
        where = null,
        durationMs = died?.let { it.timestamp - marker.startedWall }?.takeIf { it >= 0 },
        pages = marker.pages
    )

    /** The newest exit Android recorded since the last launch looked, or null. */
    private fun lastExit(): LastExit? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return try {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val seen = prefs.getLong(KEY_SEEN, 0L)
            val manager = context.getSystemService(ActivityManager::class.java)
            val newest = manager.getHistoricalProcessExitReasons(context.packageName, 0, 1).firstOrNull()
            if (newest != null) prefs.edit { putLong(KEY_SEEN, newest.timestamp) }
            newest?.takeIf { seen != 0L && it.timestamp > seen }?.let { LastExit(it.reason, it.status, it.timestamp) }
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            Log.w(TAG, "Exit reasons unreadable: ${e.javaClass.simpleName}")
            null
        }
    }

    private suspend fun flush() {
        val pending = dir.listFiles { file -> file.name.startsWith(PENDING) }?.sortedBy { it.name }.orEmpty()
        for (file in pending) {
            val report = runCatching { json.decodeFromString(AppReportDto.serializer(), file.readText()) }.getOrNull()
            if (report == null) {
                file.delete()
                continue
            }
            try {
                // A non-2xx (an older server without the route, an outage) keeps it for the next launch.
                if (!api.report(report).isSuccessful) return
                file.delete()
            } catch (e: CancellationException) {
                throw e
            } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                // Offline or the server is down: kept for the next launch.
                Log.w(TAG, "Report not sent yet: ${e.javaClass.simpleName}")
                return
            }
        }
    }

    private fun write(report: AppReportDto) {
        val existing = dir.listFiles { file -> file.name.startsWith(PENDING) }.orEmpty()
        if (existing.size >= MAX_PENDING) return
        File(dir, "$PENDING${System.currentTimeMillis()}-${existing.size}.json")
            .writeText(json.encodeToString(AppReportDto.serializer(), report))
    }

    private fun readInflight(): Inflight? =
        runCatching { json.decodeFromString(Inflight.serializer(), inflight.readText()) }.getOrNull()

    @Suppress("LongParameterList")
    private fun report(
        kind: String,
        direction: String?,
        error: String,
        where: String?,
        durationMs: Long?,
        pages: Int?
    ) = AppReportDto(
        kind = kind,
        direction = direction?.let { safe(it.lowercase(), DIRECTION_CHARS, DIRECTION_MAX) },
        error = safe(error, ERROR_CHARS, ERROR_MAX),
        where = where?.let { safe(it, WHERE_CHARS, WHERE_MAX) },
        durationMs = durationMs,
        pages = pages,
        appVersion = safe(BuildConfig.VERSION_NAME, VERSION_CHARS, VERSION_MAX),
        versionCode = BuildConfig.VERSION_CODE,
        sdk = Build.VERSION.SDK_INT
    )

    /** ApplicationExitInfo's fields, copied inside the API 30 check so nothing else touches the class. */
    private data class LastExit(val reason: Int, val status: Int, val timestamp: Long)

    @Serializable
    private data class Inflight(val tool: String, val pages: Int?, val startedWall: Long, val startedElapsed: Long)

    companion object {
        const val KIND_CRASH = "crash"
        const val KIND_NATIVE = "native_crash"
        const val KIND_ANR = "anr"
        const val KIND_CONVERSION = "conversion"
        private const val INFLIGHT = "inflight.json"
        private const val PENDING = "pending-"
        private const val PREFS = "failure_reports"
        private const val KEY_SEEN = "exit_seen"
        private const val MAX_PENDING = 20
        private const val DIRECTION_MAX = 40
        private const val ERROR_MAX = 80
        private const val WHERE_MAX = 160
        private const val VERSION_MAX = 32

        // What the Worker accepts in each field (worker/src/reports.ts); anything else becomes `_`.
        private val DIRECTION_CHARS = Regex("[^a-z0-9_]")
        private val ERROR_CHARS = Regex("[^A-Za-z0-9_.$]")
        private val WHERE_CHARS = Regex("[^A-Za-z0-9_.$<>]")
        private val VERSION_CHARS = Regex("[^0-9A-Za-z.+-]")

        fun safe(value: String, disallowed: Regex, max: Int): String =
            value.replace(disallowed, "_").take(max).ifEmpty { "_" }

        /** The deepest cause's class: the type of what actually went wrong, never its message. */
        fun rootType(error: Throwable): String {
            var cause = error
            while (cause.cause != null && cause.cause !== cause) cause = cause.cause!!
            return cause.javaClass.simpleName.ifEmpty { cause.javaClass.name.substringAfterLast('.') }
        }

        /** The first frame in Scanly's own code, as `Class.method`: where, not what. */
        fun appFrame(error: Throwable): String? =
            error.stackTrace.firstOrNull { it.className.startsWith("com.skeler.") }
                ?.let { "${it.className.substringAfterLast('.')}.${it.methodName}" }
    }
}
