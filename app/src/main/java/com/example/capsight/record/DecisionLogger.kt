package com.example.capsight.record

import android.content.Context
import android.util.Log
import com.example.capsight.TAG
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.BufferedWriter
import java.io.File
import java.io.IOException
import java.util.TimeZone

/** Decision log status for the debug view. */
data class DecisionLogState(val file: String? = null, val rows: Int = 0, val error: String? = null)

/**
 * FR-APP-07: while navigation runs, appends one CSV line per spoken decision and per landmark
 * announcement to `files/decision_logs/` (app-specific storage: no permission, removed with the
 * app, excluded from backup). One file per navigation session; the newest [DecisionCsv.KEEP_FILES]
 * are kept. Camera frames are never stored.
 *
 * [record] only puts the entry in a bounded channel, so the guidance loop never waits for the disk
 * and never sees a logging error; [run] writes on Dispatchers.IO.
 */
class DecisionLogger(context: Context) {

    val dir = File(context.applicationContext.filesDir, DIR)

    private val queue = Channel<Pair<DecisionEntry, Long>>(QUEUE_SIZE, BufferOverflow.DROP_OLDEST)

    private val _state = MutableStateFlow(DecisionLogState())
    val state: StateFlow<DecisionLogState> = _state.asStateFlow()

    /** Queues one entry; never blocks, never throws. */
    fun record(entry: DecisionEntry) {
        queue.trySend(entry to System.currentTimeMillis())
    }

    /**
     * Writes one session file until cancelled. Failures are logged and shown, never thrown.
     * [first] are the session's opening rows (e.g. the volume-key gesture that started it); rows
     * still queued when the session is cancelled (e.g. the stop gesture) are written before closing.
     */
    suspend fun run(first: List<DecisionEntry> = emptyList()) = withContext(Dispatchers.IO) {
        while (queue.tryReceive().isSuccess) Unit // stale entries from before this session
        first.forEach(::record)
        var writer: BufferedWriter? = null
        var rows = 0
        _state.value = DecisionLogState()
        try {
            val now = System.currentTimeMillis()
            val file = File(dir, DecisionCsv.fileName(now, tzOffset(now)))
            writer = try {
                openSession(file)
            } catch (e: IOException) {
                fail("cannot create log: ${e.message}", e)
                null
            } catch (e: SecurityException) {
                fail("cannot create log: ${e.message}", e)
                null
            }
            if (writer != null) _state.value = DecisionLogState(file = file.name)
            for ((entry, wallMs) in queue) {
                val w = writer ?: continue // keep draining so the channel never fills up
                try {
                    w.write(DecisionCsv.row(entry, wallMs, tzOffset(wallMs)))
                    w.newLine()
                    w.flush() // a killed process still leaves every line written so far
                    rows++
                    _state.value = DecisionLogState(file = file.name, rows = rows)
                } catch (e: IOException) {
                    fail("write failed: ${e.message}", e)
                    closeQuietly(w)
                    writer = null
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            fail("stopped: ${e.message}", e)
        } finally {
            writer?.let { w ->
                // Rows recorded just before the stop (the stop itself) are still in the queue.
                try {
                    while (true) {
                        val (entry, wallMs) = queue.tryReceive().getOrNull() ?: break
                        w.write(DecisionCsv.row(entry, wallMs, tzOffset(wallMs)))
                        w.newLine()
                    }
                    w.flush()
                } catch (e: IOException) {
                    Log.w(TAG, "decision log: last rows not written", e)
                }
                closeQuietly(w)
            }
        }
    }

    /** The newest session log, or null if there is none yet. */
    fun newestFile(): File? = try {
        DecisionCsv.newest(dir.list()?.toList().orEmpty())?.let { File(dir, it) }
    } catch (e: SecurityException) {
        null
    }

    private fun openSession(file: File): BufferedWriter {
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("cannot create $dir")
        for (old in DecisionCsv.filesToDelete(dir.list()?.toList().orEmpty(), DecisionCsv.KEEP_FILES - 1)) {
            if (!File(dir, old).delete()) Log.w(TAG, "could not delete old decision log $old")
        }
        val w = file.bufferedWriter()
        w.write(DecisionCsv.HEADER)
        w.newLine()
        w.flush()
        Log.i(TAG, "decision log: ${file.name}")
        return w
    }

    private fun fail(message: String, e: Throwable) {
        Log.w(TAG, "decision log $message", e)
        _state.value = _state.value.copy(error = message)
    }

    private fun closeQuietly(w: BufferedWriter) {
        try {
            w.close()
        } catch (e: IOException) {
            Log.w(TAG, "decision log close failed", e)
        }
    }

    private fun tzOffset(wallMs: Long) = TimeZone.getDefault().getOffset(wallMs)

    companion object {
        /** Under filesDir; must match res/xml/file_paths.xml and the backup exclusions. */
        const val DIR = "decision_logs"
        private const val QUEUE_SIZE = 256
    }
}
