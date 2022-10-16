package dev.zxilly.lib.upgrader

import android.content.Context
import dev.zxilly.lib.upgrader.Utils.getCurrentVersionCode
import kotlinx.serialization.json.Json
import java.io.Serializable
@kotlinx.serialization.Serializable
data class Version(
    val versionCode: Long,
    val versionName: String,
    val versionInfo: String?,

    val downloadUrl: String,
    val downloadFileName: String?
) : Serializable{
    //encode to json string
    fun serialize(): String {
        return Json.encodeToString(serializer(), this)
    }
}

fun String.toVersion(): Version {
    return Json.decodeFromString(Version.serializer(), this)
}


@Suppress("unused")
interface Checker {

    suspend fun shouldUpgrade(context: Context): Boolean {
        return getCurrentVersionCode(context) < getLatestVersion().versionCode
    }

    suspend fun getLatestVersion(): Version

    suspend fun getLatestVersionCode(): Long {
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