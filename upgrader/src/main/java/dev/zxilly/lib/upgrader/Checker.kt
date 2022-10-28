package dev.zxilly.lib.upgrader

import kotlinx.serialization.json.Json
import java.io.Serializable

@kotlinx.serialization.Serializable
data class Version(
    val versionCode: Long,
    val versionName: String,
    val versionInfo: String?,

    val downloadUrl: String,
    val downloadFileName: String?
) : Serializable {
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

    suspend fun getLatestVersion(): Version
}