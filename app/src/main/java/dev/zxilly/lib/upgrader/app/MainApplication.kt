package dev.zxilly.lib.upgrader.app

import android.app.Application
import dev.zxilly.lib.upgrader.Upgrader
import dev.zxilly.lib.upgrader.checker.TestChecker

class MainApplication: Application() {
    override fun onCreate() {
        super.onCreate()
        Upgrader.init(this, Upgrader.Companion.Config(
            TestChecker()
        ))
    }
}
