package dev.zxilly.lib.upgrader

import dev.zxilly.lib.upgrader.checker.GitHubRMCConfig
import dev.zxilly.lib.upgrader.checker.GitHubReleaseMetadataChecker
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CheckerTest {

    private val githubToken = System.getenv("GITHUB_TOKEN")

    @Test
    fun gitHubReleaseMetadataCheckerInit() = runTest {
        GitHubReleaseMetadataChecker(
            GitHubRMCConfig(
                "ZNotify",
                "android",
                GitHubRMCConfig.UpgradeChannel.PRE_RELEASE,
                githubToken
            )
        )
    }

    @Test
    fun gitHubReleaseMetadataCheckerGetLatestVersion() = runTest {
        GitHubReleaseMetadataChecker(
            GitHubRMCConfig(
                "ZNotify",
                "android",
                GitHubRMCConfig.UpgradeChannel.PRE_RELEASE,
                githubToken
            )
        ).getLatestVersion()
    }
}