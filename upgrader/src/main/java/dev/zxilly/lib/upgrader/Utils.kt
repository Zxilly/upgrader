package dev.zxilly.lib.upgrader

import android.app.*
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
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
import kotlinx.coroutines.*
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
    suspend fun fetchApkFile(
        fileName: String,
        fileUrl: String,
        context: Context,
        onProgress: (Int) -> Unit = {}
    ): File? {
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
            Log.i(
                "download",
                "getSavedApkUri: Content Size ${contentLength.toFloat() / 1024 / 1024} MB"
            )

            if (!response.status.isSuccess()) {
                Log.e("TAG", "getSavedApkUri: ${response.status.value}")
                return@runCatching null
            }
            response.content.copyAndClose(target.writeChannel())
        }
        if (ret.isFailure) {
            Log.e("TAG", "getSavedApkUri: ", ret.exceptionOrNull())
        }

        return if (ret.isSuccess) target else null

    }

    fun checkInstallPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }
    }

    @Suppress("DEPRECATION")
    fun installApk(activity: Activity, installUri: String) {
        val file = installUri.toUri().toFile()
        val uri = file.toProviderUri(activity)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // show progress dialog
            ProgressDialog(activity).apply {
                setMessage("正在安装...")
                setCancelable(false)
                show()
            }

            CoroutineScope(Dispatchers.IO).launch {
                val resolver = activity.contentResolver
                resolver.openInputStream(uri)?.use { apkStream ->
                    val length = file.length()
                    if (length == 0L) {
                        throw Exception("apk file not exist")
                    }
                    val params = PackageInstaller.SessionParams(
                        PackageInstaller.SessionParams.MODE_FULL_INSTALL
                    )
                    params.setSize(length)
                    val sessionId = activity.packageManager.packageInstaller.createSession(params)
                    val session = activity.packageManager.packageInstaller.openSession(sessionId)
                    session.openWrite("Upgrader", 0, length).use { out ->
                        apkStream.copyTo(out)
                        session.fsync(out)
                    }
                    val intent = Intent(activity, InstallReceiver::class.java)
                    val pi = PendingIntent.getBroadcast(
                        activity,
                        sessionId,
                        intent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                    session.commit(pi.intentSender)
                    session.close()
                }
            }
        } else {
            val intent = Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                setDataAndType(uri, "application/vnd.android.package-archive")
            }

            try {
                activity.startActivity(intent)
            } catch (e: ActivityNotFoundException) {
                Log.e("TAG", "installApk: ", e)
            }
        }
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
        } ?: let {
            Log.e("TAG", "toProviderUri: no package info")
            Uri.EMPTY
        }
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

    @Suppress("DEPRECATION")
    fun checkApk(context: Context, file: File, version: Version): Boolean {
        var flag: Boolean
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            val info = context.packageManager.getPackageArchiveInfo(file.absolutePath, 0)
                ?: return false

            flag = info.versionCode.toLong() == version.versionCode
            flag = flag and (info.versionName != version.versionName)

            return flag

        } else {
            val info = context.packageManager.getPackageArchiveInfo(
                file.absolutePath,
                PackageManager.GET_SIGNING_CERTIFICATES
            )
                ?: return false
            flag = info.longVersionCode == version.versionCode
            flag = flag and (info.versionName == version.versionName)

            val currentSignature = context.packageManager.getPackageInfo(
                context.packageName,
                PackageManager.GET_SIGNING_CERTIFICATES
            )
                .signingInfo

            if (currentSignature.hasMultipleSigners()) {
                return flag
            }

            val currentSignatures = currentSignature
                .apkContentsSigners.map { it.toCharsString() }

            val apkSignature = info.signingInfo.signingCertificateHistory[0].toCharsString()

            flag = flag and currentSignatures.contains(apkSignature)

            return flag
        }
    }

    class InstallReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)) {
                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    val activityIntent = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)

                    context.startActivity(activityIntent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }
        }
    }
}

