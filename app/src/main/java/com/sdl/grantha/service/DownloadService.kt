package com.sdl.grantha.service

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.sdl.grantha.MainActivity
import com.sdl.grantha.R
import com.sdl.grantha.data.repository.GranthaRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import javax.inject.Inject

/**
 * Foreground service for downloading granthas in the background.
 * Shows a persistent notification with progress.
 */
@AndroidEntryPoint
class DownloadService : Service() {

    @Inject
    lateinit var repository: GranthaRepository

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    companion object {
        const val ACTION_DOWNLOAD = "com.sdl.grantha.DOWNLOAD"
        const val ACTION_CANCEL = "com.sdl.grantha.CANCEL_DOWNLOAD"
        const val EXTRA_GRANTHA_NAMES = "grantha_names"
        const val CHANNEL_ID = "sdl_download_channel"
        const val NOTIFICATION_ID = 1001
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_DOWNLOAD -> {
                val names = intent.getStringArrayListExtra(EXTRA_GRANTHA_NAMES) ?: return START_NOT_STICKY
                
                // Reset cancellation state before starting new download
                repository.resetCancel()

                val notification = buildNotification("Preparing download...", 0, names.size)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
                } else {
                    startForeground(NOTIFICATION_ID, notification)
                }

                serviceScope.launch {
                    // Update notification from progress flow
                    val progressJob = launch {
                        repository.getBulkProgress().collect { progress ->
                            if (progress != null && !progress.isComplete) {
                                updateNotification(
                                    "Downloading: ${progress.currentGrantha} (${progress.currentIndex + 1}/${progress.totalCount})",
                                    progress.currentIndex,
                                    progress.totalCount
                                )
                            }
                        }
                    }

                    repository.downloadMultiple(names)
                    progressJob.cancel()

                    // Show final status
                    val finalProgress = repository.getBulkProgress().first { it?.isComplete == true }
                    val completed = (finalProgress?.totalCount ?: 0) - (finalProgress?.failedCount ?: 0)
                    val failed = finalProgress?.failedCount ?: 0
                    
                    val msg = if (failed == 0) "Downloaded $completed granthas"
                    else "Downloaded $completed, failed $failed"
                    updateNotification(msg, names.size, names.size)

                    delay(2000) // Show completion briefly
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
            ACTION_CANCEL -> {
                repository.cancelDownloads()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        repository.clearProgress()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Grantha Downloads",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Download progress for granthas"
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(text: String, current: Int, total: Int): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        val cancelIntent = PendingIntent.getService(
            this, 1,
            Intent(this, DownloadService::class.java).apply { action = ACTION_CANCEL },
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Sanskrit Digital Library")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setProgress(total, current, current == 0 && total > 0)
            .setContentIntent(pendingIntent)
            .addAction(android.R.drawable.ic_delete, "Cancel", cancelIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String, current: Int, total: Int) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(text, current, total))
    }
}
