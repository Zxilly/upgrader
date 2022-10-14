@file:Suppress("unused")

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

class GitHubReleaseMetadataChecker(private val config: GitHubRMCConfig) :
    Checker {
    private var version: Version? = null
    override suspend fun getLatestVersion(): Version {
        if (version != null) return version!!

        val client = HttpClient(OkHttp) {
            install(ContentNegotiation) {
                val j = Json {
                    prettyPrint = true
                    isLenient = true
                    ignoreUnknownKeys = true
                }
                json(j, contentType = ContentType.Application.Json)
                json(j, contentType = ContentType.Application.OctetStream)
                json(j, contentType = ContentType("application", "vnd.github+json"))
            }
            install(HttpRequestRetry) {
                retryOnException(maxRetries = 3)
            }
            defaultRequest {
                header("User-Agent", "Zxilly-Upgrader")
            }
        }

        val releaseInfo: List<GitHubReleaseInfo.Root> =
            client.get(endpoint.format(config.owner, config.repo)) {
                parameter("per_page", 10)
            }.body()

        val release = when (config.upgradeChannel) {
            GitHubRMCConfig.UpgradeChannel.RELEASE -> releaseInfo.firstOrNull { !it.prerelease }
            GitHubRMCConfig.UpgradeChannel.PRE_RELEASE -> releaseInfo.firstOrNull()
        } ?: throw Exception("No release found")

        // find output-metadata.json
        val asset = release.assets.find { it.name == "output-metadata.json" }
            ?: throw Exception("output-metadata.json not found")
        // parse output-metadata.json
        val metadata: MetaDataInfo.Root = client.get(asset.browserDownloadUrl).body()
        val elements = metadata.elements
        if (elements.isEmpty()) throw Exception("No elements found")
        val element = elements[0]
        val versionCode = element.versionCode
        val versionName = element.versionName

        val apkFileName = element.outputFile

        val downloadUrl = release.assets.find { it.name == apkFileName }?.browserDownloadUrl
            ?: throw Exception("APK file not found in release")

        version = Version(
            versionCode,
            versionName,
            release.body,
            downloadUrl,
            apkFileName
        )
        return version!!
    }

    companion object {
        const val endpoint = "https://api.github.com/repos/%s/%s/releases"
    }
}

data class GitHubRMCConfig(
    val owner: String,
    val repo: String,
    val upgradeChannel: UpgradeChannel = UpgradeChannel.RELEASE,

    ) {
    enum class UpgradeChannel {
        RELEASE, PRE_RELEASE
    }
}

private object GitHubReleaseInfo {
    @Serializable
    data class Root(
        val url: String,
        @SerialName("html_url")
        val htmlUrl: String,
        @SerialName("assets_url")
        val assetsUrl: String,
        @SerialName("upload_url")
        val uploadUrl: String,
        @SerialName("tarball_url")
        val tarballUrl: String,
        @SerialName("zipball_url")
        val zipballUrl: String,
        val id: Long,
        @SerialName("node_id")
        val nodeId: String,
        @SerialName("tag_name")
        val tagName: String,
        @SerialName("target_commitish")
        val targetCommitish: String,
        val name: String,
        val body: String,
        val draft: Boolean,
        val prerelease: Boolean,
        @SerialName("created_at")
        val createdAt: String,
        @SerialName("published_at")
        val publishedAt: String,
        val author: Author,
        val assets: List<Asset>,
    )

    @Serializable
    data class Author(
        val login: String,
        val id: Long,
        @SerialName("node_id")
        val nodeId: String,
        @SerialName("avatar_url")
        val avatarUrl: String,
        @SerialName("gravatar_id")
        val gravatarId: String,
        val url: String,
        @SerialName("html_url")
        val htmlUrl: String,
        @SerialName("followers_url")
        val followersUrl: String,
        @SerialName("following_url")
        val followingUrl: String,
        @SerialName("gists_url")
        val gistsUrl: String,
        @SerialName("starred_url")
        val starredUrl: String,
        @SerialName("subscriptions_url")
        val subscriptionsUrl: String,
        @SerialName("organizations_url")
        val organizationsUrl: String,
        @SerialName("repos_url")
        val reposUrl: String,
        @SerialName("events_url")
        val eventsUrl: String,
        @SerialName("received_events_url")
        val receivedEventsUrl: String,
        val type: String,
        @SerialName("site_admin")
        val siteAdmin: Boolean,
    )

    @Serializable
    data class Asset(
        val url: String,
        @SerialName("browser_download_url")
        val browserDownloadUrl: String,
        val id: Long,
        @SerialName("node_id")
        val nodeId: String,
        val name: String,
        val label: String,
        val state: String,
        @SerialName("content_type")
        val contentType: String,
        val size: Long,
        @SerialName("download_count")
        val downloadCount: Long,
        @SerialName("created_at")
        val createdAt: String,
        @SerialName("updated_at")
        val updatedAt: String,
        val uploader: Uploader,
    )

    @Serializable
    data class Uploader(
        val login: String,
        val id: Long,
        @SerialName("node_id")
        val nodeId: String,
        @SerialName("avatar_url")
        val avatarUrl: String,
        @SerialName("gravatar_id")
        val gravatarId: String,
        val url: String,
        @SerialName("html_url")
        val htmlUrl: String,
        @SerialName("followers_url")
        val followersUrl: String,
        @SerialName("following_url")
        val followingUrl: String,
        @SerialName("gists_url")
        val gistsUrl: String,
        @SerialName("starred_url")
        val starredUrl: String,
        @SerialName("subscriptions_url")
        val subscriptionsUrl: String,
        @SerialName("organizations_url")
        val organizationsUrl: String,
        @SerialName("repos_url")
        val reposUrl: String,
        @SerialName("events_url")
        val eventsUrl: String,
        @SerialName("received_events_url")
        val receivedEventsUrl: String,
        val type: String,
        @SerialName("site_admin")
        val siteAdmin: Boolean,
    )

}

object MetaDataInfo {

    @Serializable
    data class Root(
        val version: Long,
        val artifactType: ArtifactType,
        val applicationId: String,
        val variantName: String,
        val elements: List<Element>,
        val elementType: String,
    )

    @Serializable
    data class ArtifactType(
        val type: String,
        val kind: String,
    )

    @Serializable
    data class Element(
        val type: String,
        val filters: List<String>,
        val attributes: List<String>,
        val versionCode: Long,
        val versionName: String,
        val outputFile: String,
    )
}