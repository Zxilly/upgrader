package dev.zxilly.lib.upgrader.checker

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

class AppCenterChecker(appSecret: String) : Checker {
    private var version: Version? = null

    override suspend fun getLatestVersion(): Version {
        if (version != null) return version!!

        val client = HttpClient(OkHttp) {
            defaultRequest {
                header("User-Agent", "Zxilly-Upgrader")
            }
            install(HttpRequestRetry) {
                retryOnException(maxRetries = 3)
            }
            install(ContentNegotiation) {
                json(Json {
                    prettyPrint = true
                    isLenient = true
                    ignoreUnknownKeys = true
                }, contentType = ContentType.Application.Json)
            }
        }

        val resp = client.get(endpoint)
        if (resp.status == HttpStatusCode.NotFound) {
            throw Exception("Release not found")
        }
        val releaseInfo: AppCenterInfo.Root = resp.body()
        val versionName = releaseInfo.shortVersion
        val versionCode = releaseInfo.version.toLong()
        val versionInfo = releaseInfo.releaseNotes
        val downloadUrl = releaseInfo.releaseNotesUrl
        version = Version(versionCode, versionName, versionInfo, downloadUrl, "$versionCode.apk")
        return version!!
    }

    private val endpoint =
        "https://api.appcenter.ms/v0.1/public/sdk/apps/${appSecret}/releases/latest"
}
private object AppCenterInfo {
    @Serializable
    data class Root(
        @SerialName("app_name")
        val appName: String,
        @SerialName("app_display_name")
        val appDisplayName: String,
        @SerialName("app_os")
        val appOs: String,
        @SerialName("app_icon_url")
        val appIconUrl: String,
        @SerialName("release_notes_url")
        val releaseNotesUrl: String,
        val owner: Owner,
        @SerialName("is_external_build")
        val isExternalBuild: Boolean,
        val origin: String,
        val id: Long,
        val version: String,
        @SerialName("short_version")
        val shortVersion: String,
        val size: Long,
        @SerialName("min_os")
        val minOs: String,
        @SerialName("android_min_api_level")
        val androidMinApiLevel: String,
        @SerialName("device_family")
        val deviceFamily: String?,
        @SerialName("bundle_identifier")
        val bundleIdentifier: String,
        val fingerprint: String,
        @SerialName("uploaded_at")
        val uploadedAt: String,
        @SerialName("download_url")
        val downloadUrl: String,
        @SerialName("install_url")
        val installUrl: String,
        @SerialName("mandatory_update")
        val mandatoryUpdate: Boolean,
        val enabled: Boolean,
        val fileExtension: String,
        @SerialName("is_latest")
        val isLatest: Boolean,
        @SerialName("release_notes")
        val releaseNotes: String,
        @SerialName("can_resign")
        val canResign: Boolean?,
        @SerialName("package_hashes")
        val packageHashes: List<String>,
        @SerialName("destination_type")
        val destinationType: String,
        val status: String,
        @SerialName("distribution_group_id")
        val distributionGroupId: String,
        @SerialName("distribution_groups")
        val distributionGroups: List<DistributionGroup>,
    )

    @Serializable
    data class Owner(
        val name: String,
        @SerialName("display_name")
        val displayName: String,
    )

    @Serializable
    data class DistributionGroup(
        val id: String,
        val name: String,
        val origin: String,
        @SerialName("display_name")
        val displayName: String,
        @SerialName("is_public")
        val isPublic: Boolean,
    )
}