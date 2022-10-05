package dev.zxilly.lib.upgrader

import dev.zxilly.lib.upgrader.checker.GitHubReleaseMetadataChecker
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GitHubReleaseMetadataCheckerTest {

    @Test
    fun init() = runTest {
        GitHubReleaseMetadataChecker(
            GitHubReleaseMetadataChecker.Config(
                "ZNotify",
                "android",
                GitHubReleaseMetadataChecker.Config.UpgradeChannel.PRE_RELEASE
            )
        )
    }

    @Test
    fun getLatestVersion() = runTest {
        GitHubReleaseMetadataChecker(
            GitHubReleaseMetadataChecker.Config(
                "ZNotify",
                "android",
                GitHubReleaseMetadataChecker.Config.UpgradeChannel.PRE_RELEASE
            )
        ).getLatestVersion()
    }
}