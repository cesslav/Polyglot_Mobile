package com.example.polyglotapp
// This file is distributed under the open license AGPLv3, source code: https://github.com/cesslav/Polyglot_Mobile.

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.*
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import java.io.File


private val mainHandler = Handler(Looper.getMainLooper())

class DownloadService : Service() {
    data class DownloadState(val progress: Int = 0, val installing: Boolean = false)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val jobs = mutableMapOf<String, Job>()
    private val states = mutableMapOf<String, DownloadState>()
    var onProgress: ((file: String, progress: Int?, isInstalling: Boolean) -> Unit)? = null
    var onComplete: ((file: String, success: Boolean, error: String?) -> Unit)? = null
    inner class LocalBinder : Binder() {
        fun getService(): DownloadService = this@DownloadService
    }

    private val binder = LocalBinder()
    override fun onBind(intent: Intent): IBinder = binder
    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int =
        START_NOT_STICKY

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    fun enqueueDownload(model: ModelInfo, destDir: File) {
        if (jobs[model.file]?.isActive == true) return
        startForegroundCompat()

        jobs[model.file] = scope.launch {
            try {
                var lastPostedProgress = -1
                ModelDownloadManager.downloadAndExtract(model, destDir) { progress, isInstalling ->
                    states[model.file] = DownloadState(
                        progress = progress ?: states[model.file]?.progress ?: 0,
                        installing = isInstalling
                    )
                    if (!isInstalling && progress != null && progress == lastPostedProgress) return@downloadAndExtract
                    if (progress != null) lastPostedProgress = progress

                    val notifText = when {
                        isInstalling -> "${model.name}: установка…"
                        progress != null -> "${model.name}: $progress%"
                        else -> model.name
                    }
                    mainHandler.post {
                        onProgress?.invoke(model.file, progress, isInstalling)
                        updateNotification(text = notifText, progress = if (isInstalling) null else progress)
                    }
                }
                states.remove(model.file)
                withContext(Dispatchers.Main) {
                    onComplete?.invoke(model.file, true, null)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Download failed: ${model.file}", e)
                states.remove(model.file)
                withContext(Dispatchers.Main) {
                    onComplete?.invoke(model.file, false, e.message)
                }
            } finally {
                jobs.remove(model.file)
                withContext(Dispatchers.Main) {
                    if (jobs.isEmpty()) stopForegroundCompat()
                }
            }
        }
    }

    fun getActiveStates(): Map<String, DownloadState> = states.toMap()
    private fun startForegroundCompat() {
        val notification = buildNotification("Загрузка языкового пакета…", null)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    private fun updateNotification(text: String, progress: Int?) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification(text, progress))
    }

    private fun buildNotification(text: String, progress: Int?): Notification {
        val pi = PendingIntent.getActivity(
            this, 0,
            packageManager.getLaunchIntentForPackage(packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Полиглот")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentIntent(pi)
            .setOngoing(true)
            .apply {
                if (progress != null) setProgress(100, progress, false)
                else setProgress(0, 0, true)
            }
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID,
                "Загрузки языковых пакетов",
                NotificationManager.IMPORTANCE_LOW
            ).also { it.description = "Фоновая загрузка пакетов Полиглот" }
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(ch)
        }
    }

    companion object {
        private const val TAG = "DownloadService"
        const val NOTIF_ID = 1001
        const val CHANNEL_ID = "polyglot_downloads"
    }
}
