package dev.zxilly.lib.upgrader

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.app.Application
import android.app.Application.ActivityLifecycleCallbacks
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.annotation.UiThread
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.net.toFile
import androidx.core.net.toUri
import androidx.work.*
import dev.zxilly.lib.upgrader.Utils.toProviderUri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import java.lang.ref.WeakReference
import java.util.*
import java.util.concurrent.TimeUnit

@Suppress("MemberVisibilityCanBePrivate", "unused", "SpellCheckingInspection")
class Upgrader private constructor(private val app: Application, config: Config) :
    CoroutineScope by CoroutineScope(
        Dispatchers.Main
    ) {
    private val repo = Repo(app)
    private val checkLock = Mutex()
    private var mForegroundActivity: WeakReference<Activity?> = WeakReference(null)

    private var pendingAction: Queue<() -> Unit> = LinkedList()

    private val checker = config.checker
    private val ignoreActivities = config.ignoreActivities

    init {
        if (repo.getAutoCheck()) {
            Log.i(TAG, "Auto check is enabled")
            if (repo.getCheckDeadLine() == null || repo.getCheckDeadLine()!! < Date()) {
                Log.i(TAG, "Check deadline is expired, start checking")
                tryUpgrade(true)
            } else {
                Log.i(TAG, "Check deadline is not reached")
            }
        }

        app.registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
            }

            override fun onActivityStarted(activity: Activity) {
            }

            override fun onActivityResumed(activity: Activity) {
                mForegroundActivity = WeakReference(activity)

                if (!ignoreActivities.contains(activity::class.java)) {
                    while (pendingAction.isNotEmpty()) {
                        pendingAction.remove().invoke()
                    }
                }
            }

            override fun onActivityPaused(activity: Activity) {
                if (mForegroundActivity.get() == activity) {
                    mForegroundActivity = WeakReference(null)
                }
            }

            override fun onActivityStopped(activity: Activity) {
            }

            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {
            }

            override fun onActivityDestroyed(activity: Activity) {
            }

        })
        sInstance = this
    }

    private fun tryExecuteForegroundAction(action: () -> Unit) {
        if (mForegroundActivity.get() != null) {
            action.invoke()
        } else {
            pendingAction.offer(action)
        }
    }

    private fun tryExecuteBackgroundAction(action: () -> Unit) {
        if (mForegroundActivity.get() == null) {
            action.invoke()
        }
    }

    fun tryUpgrade(silent: Boolean = false) {
        if (!silent) {
            Toast.makeText(app, "检查中...", Toast.LENGTH_SHORT).show()
        }
        Log.i(TAG, "Start checking at ${Date()}")
        launch {
            if (!checkLock.tryLock()) {
                Log.i("Upgrader", "Check already in progress")
                return@launch
            }

            val shouldUpgrade = runCatching {
                checker.shouldUpgrade(app)
            }
                .onFailure {
                    Log.e(TAG, "Failed to check upgrade", it)
                }
                .getOrDefault(false)

            if (shouldUpgrade) {
                Log.i(TAG, "Upgrade is available")

                val version = runCatching {
                    checker.getLatestVersion()
                }
                    .onFailure {
                        Log.e(TAG, "Failed to get latest version", it)
                    }
                    .getOrNull() ?: return@launch

                Log.i(TAG, "Latest version is ${version.versionName} (${version.versionCode})")

                tryExecuteForegroundAction {
                    showNoticeDialog(version)
                }

            } else {
                if (!silent) {
                    Toast.makeText(app, "当前版本是最新版", Toast.LENGTH_SHORT).show()
                }
            }
            if (checkLock.isLocked) {
                checkLock.unlock()
            }
        }
    }


    @UiThread
    private fun showNoticeDialog(version: Version) {
        AlertDialog.Builder(mForegroundActivity.get()!!)
            .setTitle("可用的应用更新")
            .setMessage(renderUpdateMessage(app, version))
            .setPositiveButton("更新") { _, _ ->
                launch {
                    tryExecuteForegroundAction {
                        mForegroundActivity.get()!!.runOnUiThread {
                            tryDownLoad(version)
                        }
                    }
                }
            }
            .also {
                if (!version.versionInfo.isNullOrBlank()) {
                    it.setNeutralButton("查看发行说明", null)
                }
            }
            .setNegativeButton("一天后询问我") { _, _ ->
                repo.setCheckDeadLine(Date().apply { time += 24 * 60 * 60 * 1000 })
            }
            .show()
            .also {
                if (!version.versionInfo.isNullOrBlank()) {
                    it.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                        showVersionInfoDialog(version)
                    }
                }
            }
    }

    @UiThread
    private fun showVersionInfoDialog(version: Version) {
        AlertDialog.Builder(mForegroundActivity.get()!!)
            .setTitle("发行说明")
            .setMessage(version.versionInfo)
            .setPositiveButton("确定") { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }

    @UiThread
    private fun tryDownLoad(version: Version) {
        val workerConstraint = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val workManager = WorkManager.getInstance(mForegroundActivity.get()!!)
        val notificationManager = NotificationManagerCompat.from(app)

        val worker = OneTimeWorkRequestBuilder<DownloadWorker>()
            .addTag(downloadWorkerTag)
            .setConstraints(workerConstraint)
            .setInitialDelay(1, TimeUnit.MILLISECONDS)
            .setBackoffCriteria(
                BackoffPolicy.LINEAR,
                OneTimeWorkRequest.MIN_BACKOFF_MILLIS,
                TimeUnit.MILLISECONDS
            )
            .setInputData(
                workDataOf(
                    DownloadWorker.Params.KEY_VERSION to version.serialize()
                )
            )
            .build()

        workManager.enqueueUniqueWork(
            downloadWorkerTag,
            ExistingWorkPolicy.KEEP,
            worker
        )

        val observer = object : androidx.lifecycle.Observer<WorkInfo> {
            @SuppressLint("MissingPermission")
            override fun onChanged(it: WorkInfo) {
                fun infoLack() {
                    Log.e(TAG, "Download info lack")
                }

                if (it.state == WorkInfo.State.SUCCEEDED) {
                    val installUri = it.outputData.getString(DownloadWorker.Params.KEY_INSTALL_URI)

                    if (installUri == null) {
                        infoLack()
                        return
                    }

                    val file = installUri.toUri().toFile()
                    if (!Utils.checkApk(app, file, version)) {
                        Log.e(TAG, "Downloaded apk is invalid")
                        Toast.makeText(app, "下载的APK文件无效", Toast.LENGTH_SHORT).show()
                        return
                    }

                    // if is foreground, install through notification
                    if (mForegroundActivity.get() != null) {
                        if (Utils.checkInstallPermission(app)) {
                            val notification =
                                NotificationCompat.Builder(
                                    app,
                                    Utils.NotificationConstants.CHANNEL_ID
                                )
                                    .setSmallIcon(R.drawable.install)
                                    .setContentTitle("安装更新")
                                    .setContentText("点击安装更新")
                                    .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                                    .setContentIntent(
                                        PendingIntent.getActivity(
                                            app,
                                            0,
                                            Intent(Intent.ACTION_VIEW).apply {
                                                setDataAndType(
                                                    file.toProviderUri(app),
                                                    "application/vnd.android.package-archive"
                                                )
                                                flags =
                                                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
                                            },
                                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                                        )
                                    )
                                    .build()
                            notificationManager.notify(Random().nextInt(), notification)
                        } else {
                            tryInstall(installUri)
                        }
                    } else {
                        tryExecuteForegroundAction {
                            tryInstall(installUri)
                        }

                        val notification =
                            NotificationCompat.Builder(app, Utils.NotificationConstants.CHANNEL_ID)
                                .setSmallIcon(R.drawable.install)
                                .setContentTitle("安装更新")
                                .setContentText("点击安装更新")
                                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                                .setContentIntent(
                                    app.packageManager.getLaunchIntentForPackage(app.packageName)
                                        ?.let {
                                            PendingIntent.getActivity(
                                                app,
                                                0,
                                                it,
                                                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                                            )
                                        }
                                )
                                .build()
                        notificationManager.notify(Random().nextInt(), notification)
                    }

                } else {
                    Log.i(TAG, "Download state ${it.state.name}")
                }
                if (it.state.isFinished) {
                    workManager.getWorkInfoByIdLiveData(worker.id).removeObserver(this)
                }
            }
        }

        workManager.getWorkInfoByIdLiveData(worker.id).observeForever(observer)
    }

    @UiThread
    private fun tryInstall(installUri: String) {
        if (Utils.checkInstallPermission(app)) {
            Utils.installApk(app, installUri)
        } else {
            var retry = true
            AlertDialog.Builder(mForegroundActivity.get()!!)
                .setTitle("安装未知应用权限")
                .setMessage("为了安全起见，设备设置为阻止安装从未知来源获取的应用。")
                .setPositiveButton("设置") { _, _ ->
                    Utils.requestInstallPermission(app)
                }
                .setNegativeButton("取消") { _, _ -> retry = false }
                .show()
                .also {
                    it.setCancelable(false)
                }
            if (retry) {
                pendingAction.offer { tryInstall(installUri) }
            }
        }
    }

    companion object {
        private const val TAG = "Upgrader"

        private fun renderUpdateMessage(context: Context, version: Version): String {
            return "${Utils.getCurrentAppName(context)} ${version.versionName} (${version.versionCode}) 可供下载和安装。"
        }

        fun setAutoCheck(context: Context, value: Boolean) {
            val repo = Repo(context)
            repo.setAutoCheck(value)
        }

        fun getAutoCheck(context: Context): Boolean {
            val repo = Repo(context)
            return repo.getAutoCheck()
        }

        private var sInstance: Upgrader? = null
        fun getInstance(): Upgrader? {
            return sInstance
        }

        fun init(app: Application, config: Config) {
            sInstance = Upgrader(app, config)
        }

        data class Config(
            val checker: Checker,
            val ignoreActivities: List<Class<out Activity>> = emptyList(),
        )

        private const val downloadWorkerTag = "download_worker_tag"
    }
}