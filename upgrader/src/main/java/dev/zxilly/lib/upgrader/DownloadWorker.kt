package dev.zxilly.lib.upgrader

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dev.zxilly.lib.upgrader.Utils.NotificationConstants
import dev.zxilly.lib.upgrader.Utils.debounce
import dev.zxilly.lib.upgrader.Utils.getSavedApkUri
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
        val fileUrl = inputData.getString(FileParams.KEY_FILE_URL) ?: ""
        val fileName =
            inputData.getString(FileParams.KEY_FILE_NAME) ?: fileUrl.substringAfterLast("/")

        Log.d("TAG", "doWork: $fileUrl | $fileName")


        if (fileName.isEmpty()
            || fileUrl.isEmpty()
        ) {
            Result.failure(workDataOf("error" to "fileUrl or fileName is empty"))
        }

        if (!fileName.endsWith(".apk")) {
            Result.failure(workDataOf("error" to "File name must end with .apk"))
        }

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


        val notificationDebounce = debounce<Int>(scope = CoroutineScope(Dispatchers.IO)) {
            if (it != -1) {
                val notification = getBuilder().setProgress(100, it, false).build()
                notificationManager.notify(NotificationConstants.NOTIFICATION_ID, notification)
            }
        }
        val uri = getSavedApkUri(
            fileName = fileName,
            fileUrl = fileUrl,
            context = context
        ) { progress ->
            notificationDebounce(progress)
        }

        notificationManager.cancel(NotificationConstants.NOTIFICATION_ID)
        return if (uri != null) {
            Result.success(workDataOf(FileParams.KEY_FILE_URI to uri.toString()))
        } else {
            Result.failure()
        }

    }

    object FileParams {
        const val KEY_FILE_URL = "key_file_url"
        const val KEY_FILE_NAME = "key_file_name"
        const val KEY_FILE_URI = "key_file_uri"
    }

}