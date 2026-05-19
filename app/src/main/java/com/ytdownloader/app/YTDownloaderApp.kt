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

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = getSystemService(NotificationManager::class.java)

            // Download progress channel
            val downloadChannel = NotificationChannel(
                CHANNEL_DOWNLOAD,
                "Downloads",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows download progress"
                setShowBadge(false)
            }

            // Download complete channel
            val completeChannel = NotificationChannel(
                CHANNEL_COMPLETE,
                "Download Complete",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Notifies when downloads are complete"
                setShowBadge(true)
            }

            // Error channel
            val errorChannel = NotificationChannel(
                CHANNEL_ERROR,
                "Download Errors",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifies when downloads fail"
            }

            notificationManager.createNotificationChannels(
                listOf(downloadChannel, completeChannel, errorChannel)
            )
        }
    }

    companion object {
        const val CHANNEL_DOWNLOAD = "channel_download"
        const val CHANNEL_COMPLETE = "channel_complete"
        const val CHANNEL_ERROR = "channel_error"
    }
}
