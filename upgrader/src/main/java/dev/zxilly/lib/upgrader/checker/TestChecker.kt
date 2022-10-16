package dev.zxilly.lib.upgrader.checker

import dev.zxilly.lib.upgrader.Checker
import dev.zxilly.lib.upgrader.Version


class TestChecker : Checker {
    override suspend fun getLatestVersion(): Version {
        return Version(
            1664782713,
            "0.1.1.8a4d417.Release",
            "测试用Checker",
            "https://github.com/ZNotify/android/releases/download/20221003T153803/app-release.apk",
            null
        )
    }
}