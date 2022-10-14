package dev.zxilly.lib.upgrader

import android.content.Context
import java.util.*

class Repo(context: Context) {
    private val sharedPref by lazy {
        context.getSharedPreferences("upgrader", Context.MODE_PRIVATE)
    }

    fun getAutoCheck(): Boolean {
        return sharedPref.getBoolean(AUTO_CHECK_KEY, true)
    }

    fun setAutoCheck(value: Boolean) {
        sharedPref.edit().putBoolean(AUTO_CHECK_KEY, value).apply()
    }

    fun getCheckDeadLine(): Date? {
        val time = sharedPref.getLong(CHECK_DEADLINE_KEY, 0)
        return if (time == 0L) {
            null
        } else {
            Date(time)
        }
    }

    fun setCheckDeadLine(value: Date) {
        sharedPref.edit().putLong(CHECK_DEADLINE_KEY, value.time).apply()
    }


    companion object {
        const val AUTO_CHECK_KEY = "auto_check"
        const val CHECK_DEADLINE_KEY = "check_deadline"
    }
}