package dev.zxilly.lib.upgrader

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.net.toUri
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dev.zxilly.lib.upgrader.Utils.NotificationConstants
import dev.zxilly.lib.upgrader.Utils.debounce
import dev.zxilly.lib.upgrader.Utils.fetchApkFile
import dev.zxilly.lib.upgrader.Utils.requireNotificationChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

class DownloadWorker(
    private val context: Context,
    workerParameters: WorkerParameters
) : CoroutineWorker(context, workerParameters) {

    private val notificationManager = NotificationManagerCompat.from(context)

    @SuppressLint("MissingPermission")
    override suspend fun doWork(): Result {
        val versionString = inputData.getString(Params.KEY_VERSION) ?: return Result.failure(
            workDataOf(Params.KEY_ERROR to "No file url provided")
        )

        val version = versionString.toVersion()

        if (version.downloadUrl.isBlank()) {
            return Result.failure(workDataOf(Params.KEY_ERROR to "No file url provided"))
        }

        var fileName = version.downloadFileName
        if (fileName.isNullOrBlank()) {
            fileName = "${version.versionCode}-${version.versionName}.apk"
        }

        Log.d("TAG", "doWork: ${version.downloadUrl} | ${version.downloadFileName}")

        requireNotificationChannel(context)

        fun getBuilder(): NotificationCompat.Builder {
            return NotificationCompat.Builder(context, NotificationConstants.CHANNEL_ID)
                .setSmallIcon(R.drawable.download)
                .setContentTitle("下载中...")
                .setOngoing(true)
                .setOnlyAlertOnce(true)
        }

        notificationManager
            .notify(
                NotificationConstants.NOTIFICATION_ID,
                getBuilder()
                    .setProgress(0, 0, true)
                    .build()
            )


        val notificationDebounce = debounce<Int>(scope = CoroutineScope(Dispatchers.Default)) {
            if (it != -1) {
                val notification = getBuilder().setProgress(100, it, false).build()
                notificationManager.notify(NotificationConstants.NOTIFICATION_ID, notification)
            }
        }
        val file = fetchApkFile(
            fileName = fileName,
            fileUrl = version.downloadUrl,
            context = context
        ) { progress ->
            notificationDebounce(progress)
        }

        notificationManager.cancel(NotificationConstants.NOTIFICATION_ID)

        return if (file != null) {
            Result.success(
                workDataOf(
                    Params.KEY_INSTALL_URI to file.toUri().toString(),
                    Params.KEY_VERSION to version.serialize()
                )
            )
        } else {
            Result.failure(
                workDataOf(
                    Params.KEY_ERROR to "Download failed",
                )
            )
        }

    }

    object Params {
        const val KEY_VERSION = "version"
        const val KEY_ERROR = "error"

        const val KEY_INSTALL_URI = "install_uri"
    }

}