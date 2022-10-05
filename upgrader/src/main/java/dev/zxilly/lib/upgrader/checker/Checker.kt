package dev.zxilly.lib.upgrader.checker

import android.content.Context
import dev.zxilly.lib.upgrader.Utils.getCurrentVersionCode
import java.io.Serializable

data class Version(
    val versionCode: Int,
    val versionName: String,
    val versionInfo: String?,

    val downloadUrl: String,
    val downloadFileName: String?
) : Serializable


@Suppress("unused")
interface Checker {

    suspend fun shouldUpgrade(context: Context): Boolean {
        return getCurrentVersionCode(context) < getLatestVersion().versionCode
    }

    suspend fun getLatestVersion(): Version

    suspend fun getLatestVersionCode(): Int {
        return getLatestVersion().versionCode
    }

    suspend fun getLatestVersionName(): String {
        return getLatestVersion().versionName
    }

    suspend fun getLatestVersionInfo(): String? {
        return getLatestVersion().versionInfo
    }

    suspend fun getLatestDownloadUrl(): String {
        return getLatestVersion().downloadUrl
    }
}