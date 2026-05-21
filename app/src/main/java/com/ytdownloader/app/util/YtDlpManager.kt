package com.ytdownloader.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class YTDownloaderApp : Application(), Configuration.Provider {

    // Hilt injects this after super.onCreate() — it is safe to use in
    // workManagerConfiguration because WorkManager reads that lazily,
    // after the Application is fully initialized.
    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
    }

    // Configuration.Provider — WorkManager calls this lazily when it first
    // needs a worker, not during Application.onCreate(), so workerFactory
    // is guaranteed to be injected by then.
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .setMinimumLoggingLevel(android.util.Log.INFO)
            .build()

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)

            nm.createNotificationChannels(listOf(
                NotificationChannel(CHANNEL_DOWNLOAD, "Downloads",
                    NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Shows download progress"
                    setShowBadge(false)
                },
                NotificationChannel(CHANNEL_COMPLETE, "Download Complete",
                    NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "Notifies when downloads are complete"
                    setShowBadge(true)
                },
                NotificationChannel(CHANNEL_ERROR, "Download Errors",
                    NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Notifies when downloads fail"
                }
            ))
        }
    }

    companion object {
        const val CHANNEL_DOWNLOAD = "channel_download"
        const val CHANNEL_COMPLETE = "channel_complete"
        const val CHANNEL_ERROR    = "channel_error"
    }
}
