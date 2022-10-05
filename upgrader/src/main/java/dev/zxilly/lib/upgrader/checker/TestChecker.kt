package dev.zxilly.lib.upgrader.checker


class TestChecker : Checker {
    override suspend fun getLatestVersion(): Version {
        return Version(
            1994896132,
            "1.0.0",
            "测试版本",
            "https://github.com/ZNotify/android/releases/download/20221003T153803/app-release.apk",
            null
        )
    }
}