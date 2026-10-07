package dev.zxilly.lib.upgrader

import dev.zxilly.lib.upgrader.checker.GitHubRMCConfig
import dev.zxilly.lib.upgrader.checker.GitHubReleaseMetadataChecker
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

class CheckerTest {
    @Test
    fun gitHubReleaseMetadataCheckerInit() {
        assertNotNull(GitHubReleaseMetadataChecker(GitHubRMCConfig("owner", "repo")))
    }

    @Test
    fun getLatestVersionParsesMetadataAndApk() = runTest {
        val version = checker(releases(release())).getLatestVersion()
        assertEquals(42L, version.versionCode)
        assertEquals("1.2.3", version.versionName)
        assertEquals("Release notes", version.versionInfo)
        assertEquals("https://example.test/app.apk", version.downloadUrl)
        assertEquals("app.apk", version.downloadFileName)
    }

    @Test
    fun stableChannelSkipsPrereleases() = runTest {
        val version = checker(releases(release(true, "Preview"), release())).getLatestVersion()
        assertEquals("Release notes", version.versionInfo)
    }

    @Test
    fun prereleaseChannelSelectsNewestRelease() = runTest {
        val version = checker(
            releases(release(true, "Preview"), release()),
            channel = GitHubRMCConfig.UpgradeChannel.PRE_RELEASE
        ).getLatestVersion()
        assertEquals("Preview", version.versionInfo)
    }

    @Test
    fun emptyReleasesAreReported() = runTest {
        assertEquals("No release found", assertFailsWith<Exception> {
            checker("[]").getLatestVersion()
        }.message)
    }

    @Test
    fun missingMetadataIsReported() = runTest {
        assertEquals("output-metadata.json not found", assertFailsWith<Exception> {
            checker(releases(release(assets = listOf(asset("app.apk"))))).getLatestVersion()
        }.message)
    }

    @Test
    fun emptyMetadataElementsAreReported() = runTest {
        assertEquals("No elements found", assertFailsWith<Exception> {
            checker(releases(release()), metadata = metadata("[]")).getLatestVersion()
        }.message)
    }

    @Test
    fun missingApkIsReported() = runTest {
        assertEquals("APK file not found in release", assertFailsWith<Exception> {
            checker(releases(release(assets = listOf(asset("output-metadata.json"))))).getLatestVersion()
        }.message)
    }

    private fun checker(
        releases: String,
        metadata: String = metadata(),
        channel: GitHubRMCConfig.UpgradeChannel = GitHubRMCConfig.UpgradeChannel.RELEASE
    ): GitHubReleaseMetadataChecker = GitHubReleaseMetadataChecker(
        GitHubRMCConfig("owner", "repo", channel)
    ) {
        MockEngine { request ->
            val body = when (request.url.host) {
                "api.github.com" -> {
                    assertEquals("/repos/owner/repo/releases", request.url.encodedPath)
                    assertEquals("10", request.url.parameters["per_page"])
                    releases
                }
                "example.test" -> {
                    assertEquals("/output-metadata.json", request.url.encodedPath)
                    metadata
                }
                else -> error("Unexpected request: ${request.url}")
            }
            respond(body, headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }
    }

    private fun releases(vararg release: JsonObject) = JsonArray(release.toList()).toString()

    private fun release(
        prerelease: Boolean = false,
        body: String = "Release notes",
        assets: List<JsonObject> = listOf(asset("output-metadata.json"), asset("app.apk"))
    ) = buildJsonObject {
        listOf("url", "html_url", "assets_url", "upload_url", "tarball_url", "zipball_url",
            "node_id", "tag_name", "target_commitish", "name", "created_at", "published_at")
            .forEach { put(it, "fixture") }
        put("id", 1)
        put("body", body)
        put("draft", false)
        put("prerelease", prerelease)
        put("author", user())
        put("assets", JsonArray(assets))
        put("unknown_field", "ignored")
    }

    private fun asset(name: String) = buildJsonObject {
        listOf("url", "node_id", "label", "state", "content_type", "created_at", "updated_at")
            .forEach { put(it, "fixture") }
        put("browser_download_url", "https://example.test/$name")
        put("name", name)
        put("id", 1)
        put("size", 1)
        put("download_count", 0)
        put("uploader", user())
    }

    private fun user() = buildJsonObject {
        listOf("login", "node_id", "avatar_url", "gravatar_id", "url", "html_url", "followers_url",
            "following_url", "gists_url", "starred_url", "subscriptions_url", "organizations_url",
            "repos_url", "events_url", "received_events_url", "type").forEach { put(it, "fixture") }
        put("id", 1)
        put("site_admin", false)
    }

    private fun metadata(elements: String = """[{"type":"SINGLE","filters":[],"attributes":[],"versionCode":42,"versionName":"1.2.3","outputFile":"app.apk"}]""") =
        """{"version":3,"artifactType":{"type":"APK","kind":"Directory"},"applicationId":"test.app","variantName":"release","elements":$elements,"elementType":"File"}"""
}
