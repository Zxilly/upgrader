package dev.zxilly.lib.upgrader

import android.app.Activity
import android.app.AlertDialog
import android.app.Application
import android.app.Application.ActivityLifecycleCallbacks
import android.content.Context
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.annotation.UiThread
import androidx.work.*
import dev.zxilly.lib.upgrader.checker.Checker
import dev.zxilly.lib.upgrader.checker.Version
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import java.lang.ref.WeakReference
import java.util.*
import java.util.concurrent.TimeUnit

@Suppress("MemberVisibilityCanBePrivate", "unused")
class Upgrader(private val checker: Checker, private val app: Application) :
    CoroutineScope by CoroutineScope(
        Dispatchers.Main
    ) {
    private val repo = Repo(app)
    private val checkLock = Mutex()
    private var mForegroundActivity: WeakReference<Activity?> = WeakReference(null)

    private var pendingAction: Queue<() -> Unit> = LinkedList()

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
                while (pendingAction.isNotEmpty()) {
                    pendingAction.remove().invoke()
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
    }

    private fun tryExecuteForegroundAction(action: () -> Unit) {
        if (mForegroundActivity.get() != null) {
            action.invoke()
        } else {
            pendingAction.offer(action)
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

            if (checker.shouldUpgrade(app)) {
                Log.i(TAG, "Upgrade is available")

                val version = checker.getLatestVersion()
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

    fun setAutoCheck(value: Boolean) {
        repo.setAutoCheck(value)
    }

    fun getAutoCheck(): Boolean {
        return repo.getAutoCheck()
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

        val worker = OneTimeWorkRequestBuilder<DownloadWorker>()
            .addTag(downloadWorkerTag)
            .setConstraints(workerConstraint)
            .setInitialDelay(1, TimeUnit.MILLISECONDS)
            .setBackoffCriteria(
                BackoffPolicy.LINEAR,
                OneTimeWorkRequest.MIN_BACKOFF_MILLIS,
                TimeUnit.MILLISECONDS
            )
            .setInputData(workDataOf(
                DownloadWorker.FileParams.KEY_FILE_NAME to (version.downloadFileName ?: ""),
                DownloadWorker.FileParams.KEY_FILE_URL to version.downloadUrl,
            ))
            .build()

        workManager.enqueueUniqueWork(
            downloadWorkerTag,
            ExistingWorkPolicy.KEEP,
            worker
        )

        val observer = object : androidx.lifecycle.Observer<WorkInfo> {
            override fun onChanged(it: WorkInfo) {
                if (it.state == WorkInfo.State.SUCCEEDED) {
                    it.outputData.getString(DownloadWorker.FileParams.KEY_FILE_URI)?.let { uri ->
                        tryExecuteForegroundAction {
                            tryInstall(uri)
                        }
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

        fun renderUpdateMessage(context: Context, version: Version): String {
            return "${Utils.getCurrentAppName(context)} ${version.versionName} (${version.versionCode}) 可供下载和安装。"
        }

        private const val downloadWorkerTag = "download_worker_tag"
    }
}