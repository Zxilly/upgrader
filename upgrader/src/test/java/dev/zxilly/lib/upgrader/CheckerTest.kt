package dev.zxilly.lib.upgrader

import dev.zxilly.lib.upgrader.checker.AppCenterChecker
import dev.zxilly.lib.upgrader.checker.GitHubReleaseMetadataChecker
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CheckerTest {
    @Test
    fun gitHubReleaseMetadataCheckerInit() = runTest {
        GitHubReleaseMetadataChecker(
            GitHubReleaseMetadataChecker.Config(
                "ZNotify",
                "android",
                GitHubReleaseMetadataChecker.Config.UpgradeChannel.PRE_RELEASE
            )
        )
    }

    @Test
    fun gitHubReleaseMetadataCheckerGetLatestVersion() = runTest {
        GitHubReleaseMetadataChecker(
            GitHubReleaseMetadataChecker.Config(
                "ZNotify",
                "android",
                GitHubReleaseMetadataChecker.Config.UpgradeChannel.PRE_RELEASE
            )
        ).getLatestVersion()
    }

    @Test
    fun appCenterInit() = runTest {
        AppCenterChecker(
            "0c045975-212b-441d-9ee4-e6ab9c76f8a3"
        )
    }

    @Test
    fun appCenterGetLatestVersion() = runTest {
        AppCenterChecker(
            "0c045975-212b-441d-9ee4-e6ab9c76f8a3"
        ).getLatestVersion()
    }
}