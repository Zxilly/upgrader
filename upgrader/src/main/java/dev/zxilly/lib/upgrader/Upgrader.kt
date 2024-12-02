package dev.zxilly.lib.upgrader

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.app.Application
import android.app.Application.ActivityLifecycleCallbacks
import android.app.PendingIntent
import android.content.Context
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.annotation.UiThread
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.net.toFile
import androidx.core.net.toUri
import androidx.lifecycle.Observer
import androidx.work.*
import dev.zxilly.lib.upgrader.repo.Repo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import java.lang.ref.WeakReference
import java.util.*
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@Suppress("MemberVisibilityCanBePrivate", "unused")
class Upgrader private constructor(private val app: Application, config: Config) :
    CoroutineScope by CoroutineScope(
        Dispatchers.Main
    ) {
    private val repo = Repo(app)
    private val checkLock = Mutex()
    private var mForegroundActivity: AtomicReference<WeakReference<Activity?>> =
        AtomicReference(WeakReference(null))

    private var pendingAction: AtomicReference<((Activity) -> Unit)?> = AtomicReference(null)

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
                mForegroundActivity.set(WeakReference(activity))

                if (!ignoreActivities.contains(activity::class.java)) {
                    pendingAction
                        .updateAndGet { null }
                        ?.invoke(activity)
                }
            }

            override fun onActivityPaused(activity: Activity) {
                if (mForegroundActivity.get() == activity) {
                    mForegroundActivity.set(WeakReference(null))
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

    private fun tryExecuteForegroundAction(action: (Activity) -> Unit) {
        val activity = mForegroundActivity.get().get()
        if (activity != null) {
            action.invoke(activity)
        } else {
            pendingAction.set(action)
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

            val version = runCatching {
                checker.getLatestVersion()
            }.onFailure {
                Log.e(TAG, "Failed to get latest version", it)
                if (!silent) {
                    Toast.makeText(app, "检查更新失败", Toast.LENGTH_SHORT).show()
                }
            }.getOrNull() ?: return@launch


            if (shouldUpgrade(context = app, version = version)) {
                Log.i(TAG, "Upgrade is available")

                Log.i(TAG, "Latest version is ${version.versionName} (${version.versionCode})")

                tryExecuteForegroundAction {
                    showNoticeDialog(version, it)
                }

            } else {
                Log.i(TAG, "No upgrade available")

                if (!silent) {
                    Toast.makeText(app, "当前版本是最新版", Toast.LENGTH_SHORT).show()
                }
            }
            if (checkLock.isLocked) {
                checkLock.unlock()
            }
        }
    }

    private fun shouldUpgrade(context: Context, version: Version): Boolean {
        val currentVersionCode = Utils.getCurrentVersionCode(context)

        Log.i(
            "Upgrader",
            "Current version: $currentVersionCode, Latest version: ${version.versionCode}"
        )

        return currentVersionCode < version.versionCode
    }


    @UiThread
    private fun showNoticeDialog(version: Version, activity: Activity) {
        AlertDialog.Builder(activity)
            .setTitle("可用的应用更新")
            .setMessage(renderUpdateMessage(app, version))
            .setPositiveButton("更新") { _, _ ->
                launch {
                    tryExecuteForegroundAction {
                        it.runOnUiThread {
                            tryDownLoad(version, it)
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
            .also { alertDialog ->
                if (!version.versionInfo.isNullOrBlank()) {
                    alertDialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                        showVersionInfoDialog(version, alertDialog.context)
                    }
                }
            }
    }

    @UiThread
    private fun showVersionInfoDialog(version: Version, context: Context) {
        AlertDialog.Builder(context)
            .setTitle("发行说明")
            .setMessage(version.versionInfo)
            .setPositiveButton("确定") { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }

    @UiThread
    private fun tryDownLoad(version: Version, activity: Activity) {
        val workerConstraint = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val workManager = WorkManager.getInstance(activity)
        val notificationManager = NotificationManagerCompat.from(app)

        val worker = OneTimeWorkRequestBuilder<DownloadWorker>()
            .addTag(downloadWorkerTag)
            .setConstraints(workerConstraint)
            .setInitialDelay(1, TimeUnit.MILLISECONDS)
            .setBackoffCriteria(
                BackoffPolicy.LINEAR,
                WorkRequest.MIN_BACKOFF_MILLIS,
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

        val observer = object : Observer<WorkInfo?> {
            @SuppressLint("MissingPermission")
            override fun onChanged(it: WorkInfo?) {
                if (it == null) {
                    return
                }

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

                    if (Utils.isForeground(activity)) {
                        AlertDialog.Builder(activity)
                            .setTitle("下载完成")
                            .setMessage("是否安装？")
                            .setPositiveButton("安装") { _, _ ->
                                tryInstall(installUri, activity)
                            }
                            .setNegativeButton("取消") { _, _ ->
                            }
                            .show()
                            .also {
                                it.setCancelable(false)
                            }
                    } else {
                        pendingAction.set {
                            tryInstall(installUri, it)
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
    private fun tryInstall(installUri: String, activity: Activity) {
        if (Utils.checkInstallPermission(app)) {
            Utils.installApk(activity, installUri)
        } else {
            showPermissionDialog(activity)
            pendingAction.set {
                tryInstall(installUri, it)
            }
        }
    }

    private fun showPermissionDialog(activity: Activity) {
        AlertDialog.Builder(activity)
            .setTitle("安装未知应用权限")
            .setMessage("为了安全起见，设备设置为阻止安装从未知来源获取的应用。")
            .setPositiveButton("设置") { _, _ ->
                Utils.requestInstallPermission(activity)
            }
            .setNegativeButton("取消") { _, _ ->
                pendingAction.set(null)
            }
            .show()
            .also {
                it.setCancelable(false)
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