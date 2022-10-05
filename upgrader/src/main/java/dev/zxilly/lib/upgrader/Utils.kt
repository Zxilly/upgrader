package dev.zxilly.lib.upgrader

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.FileProvider
import androidx.core.net.toFile
import androidx.core.net.toUri
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.util.*
import io.ktor.util.cio.*
import io.ktor.utils.io.*
import io.ktor.utils.io.core.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.roundToInt

object Utils {

    object NotificationConstants {
        const val CHANNEL_NAME = "Upgrader"
        const val CHANNEL_DESCRIPTION = "应用内更新通知"
        const val CHANNEL_ID = "download_file_worker"
        const val NOTIFICATION_ID = 613231 // :)
    }

    fun requireNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = NotificationConstants.CHANNEL_NAME
            val description = NotificationConstants.CHANNEL_DESCRIPTION
            val importance = NotificationManager.IMPORTANCE_HIGH
            val channel = NotificationChannel(NotificationConstants.CHANNEL_ID, name, importance)
            channel.description = description

            NotificationManagerCompat.from(context).createNotificationChannel(channel)
        }
    }

    @Suppress("DEPRECATION")
    fun getCurrentVersionCode(context: Context): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode.toInt()
        } else {
            context.packageManager.getPackageInfo(context.packageName, 0).versionCode
        }
    }

    fun getCurrentAppName(context: Context): String {
        return context.packageManager.getApplicationLabel(context.applicationInfo).toString()
    }

    @OptIn(InternalAPI::class)
    suspend fun getSavedApkUri(
        fileName: String,
        fileUrl: String,
        context: Context,
        onProgress: (Int) -> Unit = {}
    ): Uri? {
        val client = HttpClient(OkHttp) {
            install(HttpTimeout) {
                socketTimeoutMillis = 10000
            }
        }

        val target = File(
            context.cacheDir,
            fileName
        )
        if (target.exists()) {
            target.delete()
        }

        val ret = runCatching {
            val response = client.get(fileUrl) {
                onDownload { bytesSentTotal, contentLength ->
                    if (contentLength == -1L) {
                        onProgress(-1)
                    } else {
                        val progress =
                            (bytesSentTotal.toFloat() / contentLength.toFloat() * 100).roundToInt()
                        onProgress(progress)
                    }
                }
            }

            val contentLength = response.contentLength() ?: 0L
            Log.i("download", "getSavedApkUri: Content Size ${contentLength.toFloat() / 1024 / 1024} MB")

            if (!response.status.isSuccess()) {
                Log.e("TAG", "getSavedApkUri: ${response.status.value}")
                return@runCatching null
            }
            response.content.copyAndClose(target.writeChannel())
            target.deleteOnExit()
        }
        if (ret.isFailure) {
            Log.e("TAG", "getSavedApkUri: ", ret.exceptionOrNull())
        }

        return if (ret.isSuccess) target.toUri() else null

    }

    fun checkInstallPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }
    }

    fun installApk(context: Context, installUri: String) {
        val uri = installUri.toUri().toFile().toProviderUri(context)

        val intent = Intent(Intent.ACTION_VIEW)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        intent.setDataAndType(uri, "application/vnd.android.package-archive")
        context.startActivity(intent)
    }

    fun requestInstallPermission(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                data = "package:${context.packageName}".toUri()
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
    }

    @Suppress("DEPRECATION")
    fun File.toProviderUri(context: Context): Uri {
        return context.packageManager.getPackageArchiveInfo(
            this.absolutePath,
            0
        )?.let {
            val authority = "${context.packageName}.provider"
            FileProvider.getUriForFile(context, authority, this)
        } ?: Uri.EMPTY
    }

    fun <T> debounce(
        delayMillis: Long = 300L,
        scope: CoroutineScope,
        action: (T) -> Unit
    ): (T) -> Unit {
        var debounceJob: Job? = null
        return { param: T ->
            if (debounceJob == null) {
                debounceJob = scope.launch {
                    action(param)
                    delay(delayMillis)
                    debounceJob = null
                }
            }
        }
    }
}

