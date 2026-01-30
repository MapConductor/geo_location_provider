package com.mapconductor.plugin.provider.geolocation.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

// TEMP_BATTERY_LOGGER_REMOVE_BEFORE_SDK_RELEASE
// Temporary battery percent CSV logger for experiment runs.
// This is intended to be removed before SDK/library release.
internal class TempBatteryPercentCsvLogger(
    private val context: Context,
    private val scope: CoroutineScope,
    private val intervalMs: Long = DEFAULT_INTERVAL_MS,
    private val maxFiles: Int = DEFAULT_MAX_FILES
) {

    companion object {
        private const val TAG = "TempBatteryCsvLogger"

        private const val DEFAULT_INTERVAL_MS: Long = 15L * 60L * 1000L
        private const val DEFAULT_MAX_FILES: Int = 10

        private val CSV_TIME_FORMAT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

        private val FILE_TIME_FORMAT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")
    }

    private data class BatterySnapshot(val percent: Int, val isCharging: Boolean)

    @Volatile
    private var tracking: Boolean = false

    @Volatile
    private var lastIsCharging: Boolean? = null

    @Volatile
    private var lastPercent: Int = 0

    private var receiverRegistered: Boolean = false
    private var tickerJob: Job? = null
    private var writer: BufferedWriter? = null
    private var currentFile: File? = null
    private val sessionLock = Any()
    @Volatile private var sessionToken: Long = 0L

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null) return
            val snap = parseBatteryChangedIntent(intent)
            onBatterySnapshot(snap, System.currentTimeMillis())
        }
    }

    fun onTrackingStarted() {
        tracking = true
        val snap = registerBatteryReceiverAndReadInitial() ?: readBatterySnapshotFallback()
        if (snap != null) {
            onBatterySnapshot(snap, System.currentTimeMillis())
        }
    }

    fun onTrackingStopped() {
        tracking = false
        endSession("tracking_stopped")
        unregisterBatteryReceiver()
    }

    fun shutdown() {
        tracking = false
        endSession("shutdown")
        unregisterBatteryReceiver()
    }

    private fun onBatterySnapshot(snap: BatterySnapshot, nowMillis: Long) {
        lastPercent = snap.percent.coerceIn(0, 100)

        val prev = lastIsCharging
        lastIsCharging = snap.isCharging

        if (!tracking) {
            return
        }

        when {
            prev == null && !snap.isCharging -> {
                // Tracking started while already discharging.
                startNewSession(nowMillis, "initial_discharging")
            }
            prev == true && !snap.isCharging -> {
                // Cable unplugged while tracking: start a new session.
                startNewSession(nowMillis, "unplugged")
            }
            prev == false && snap.isCharging -> {
                // B plan: end the session on charging start.
                endSession("charging_started")
            }
        }
    }

    private fun startNewSession(nowMillis: Long, reason: String) {
        if (!tracking) return
        if (lastIsCharging == true) return

        endSession("restart:$reason")

        val token: Long = synchronized(sessionLock) {
            sessionToken += 1L
            sessionToken
        }

        scope.launch(Dispatchers.IO) {
            if (!tracking || lastIsCharging != false) {
                return@launch
            }
            val dir = getLogDir()
            if (dir == null) {
                Log.w(TAG, "startNewSession: no log dir")
                return@launch
            }
            if (!dir.exists()) {
                runCatching { dir.mkdirs() }.onFailure { t ->
                    Log.w(TAG, "mkdirs failed", t)
                }
            }

            val file = createNewLogFile(dir, nowMillis)
            currentFile = file

            val w = runCatching {
                BufferedWriter(FileWriter(file, true))
            }.getOrElse { t ->
                Log.w(TAG, "open writer failed", t)
                currentFile = null
                return@launch
            }

            // Ensure this session is still current before taking ownership.
            val stillCurrent = synchronized(sessionLock) { token == sessionToken }
            if (!stillCurrent || !tracking || lastIsCharging != false) {
                runCatching { w.close() }
                currentFile = null
                return@launch
            }

            writer = w

            // Create file first, then rotate so the new file is included.
            rotateFiles(dir, keep = maxFiles)

            // Header line.
            if (file.length() <= 0L) {
                runCatching {
                    w.write("timestamp,batteryPercent\n")
                    w.flush()
                }.onFailure { t ->
                    Log.w(TAG, "header write failed", t)
                }
            }

            // First row immediately.
            appendRow(nowMillis)

            tickerJob = scope.launch(Dispatchers.IO) {
                while (tracking && lastIsCharging == false) {
                    delay(intervalMs)
                    if (!tracking || lastIsCharging != false) {
                        break
                    }
                    appendRow(System.currentTimeMillis())
                }
            }

            Log.d(TAG, "session started reason=$reason file=${file.name}")
        }
    }

    private fun appendRow(nowMillis: Long) {
        val w = writer ?: return
        val percent = lastPercent.coerceIn(0, 100)
        val textTime = formatCsvTime(nowMillis)
        runCatching {
            w.write(textTime)
            w.write(",")
            w.write(percent.toString())
            w.write("\n")
            w.flush()
        }.onFailure { t ->
            Log.w(TAG, "appendRow failed", t)
        }
    }

    private fun endSession(reason: String) {
        synchronized(sessionLock) {
            sessionToken += 1L
        }
        tickerJob?.cancel()
        tickerJob = null

        val w = writer
        writer = null
        currentFile = null

        if (w != null) {
            runCatching { w.close() }.onFailure { t ->
                Log.w(TAG, "close writer failed", t)
            }
            Log.d(TAG, "session ended reason=$reason")
        }
    }

    private fun registerBatteryReceiverAndReadInitial(): BatterySnapshot? {
        if (receiverRegistered) {
            return null
        }
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val intent: Intent? =
            if (Build.VERSION.SDK_INT >= 33) {
                context.registerReceiver(batteryReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                context.registerReceiver(batteryReceiver, filter)
            }
        receiverRegistered = true
        return intent?.let { parseBatteryChangedIntent(it) }
    }

    private fun unregisterBatteryReceiver() {
        if (!receiverRegistered) return
        receiverRegistered = false
        runCatching {
            context.unregisterReceiver(batteryReceiver)
        }.onFailure { t ->
            Log.w(TAG, "unregisterReceiver failed", t)
        }
    }

    private fun parseBatteryChangedIntent(intent: Intent): BatterySnapshot {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val pct = if (level >= 0 && scale > 0) {
            ((level * 100f) / scale).toInt().coerceIn(0, 100)
        } else {
            readBatteryPercentFallback()
        }

        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
        val charging =
            status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL ||
                plugged != 0

        return BatterySnapshot(percent = pct, isCharging = charging)
    }

    private fun readBatterySnapshotFallback(): BatterySnapshot? {
        val percent = readBatteryPercentFallback().coerceIn(0, 100)
        val charging = readChargingFallback()
        if (percent <= 0 && charging == null) {
            return null
        }
        return BatterySnapshot(percent = percent, isCharging = charging ?: false)
    }

    private fun readBatteryPercentFallback(): Int {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val pct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        return if (pct in 1..100) pct else 0
    }

    private fun readChargingFallback(): Boolean? {
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val intent: Intent? =
            if (Build.VERSION.SDK_INT >= 33) {
                context.registerReceiver(null, filter, Context.RECEIVER_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                context.registerReceiver(null, filter)
            }
        intent ?: return null
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
        return status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL ||
            plugged != 0
    }

    private fun getLogDir(): File? {
        return context.applicationContext.getExternalFilesDir("battery_logs")
            ?: context.applicationContext.filesDir
    }

    private fun createNewLogFile(dir: File, nowMillis: Long): File {
        val zone = ZoneId.systemDefault()
        val time = LocalDateTime.ofInstant(Instant.ofEpochMilli(nowMillis), zone)
        val name = "battery_" + FILE_TIME_FORMAT.format(time) + ".csv"
        return File(dir, name)
    }

    private fun rotateFiles(dir: File, keep: Int) {
        if (keep <= 0) return
        val files = dir.listFiles { f ->
            f.isFile && f.name.startsWith("battery_") && f.name.endsWith(".csv")
        }?.toList().orEmpty()
        if (files.size <= keep) return

        val sorted = files.sortedByDescending { it.lastModified() }
        val toDelete = sorted.drop(keep)
        for (f in toDelete) {
            runCatching { f.delete() }.onFailure { t ->
                Log.w(TAG, "delete failed name=${f.name}", t)
            }
        }
    }

    private fun formatCsvTime(nowMillis: Long): String {
        val zone = ZoneId.systemDefault()
        val time = LocalDateTime.ofInstant(Instant.ofEpochMilli(nowMillis), zone)
        return CSV_TIME_FORMAT.format(time)
    }
}
