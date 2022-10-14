package dev.zxilly.lib.upgrader

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import dev.zxilly.lib.upgrader.Upgrader.Companion.Config
import dev.zxilly.lib.upgrader.checker.TestChecker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.doReturn

import org.mockito.kotlin.mock
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class UpgradeTest {


    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun initTest() {
        assertNull(Upgrader.getInstance(), "Upgrader should be null before init")
        val sharedPref = mock<SharedPreferences> {
            on { getBoolean(Repo.AUTO_CHECK_KEY, true) } doReturn true
            on { getLong(Repo.CHECK_DEADLINE_KEY, 0) } doReturn 0
        }
        val app = mock<Application> {
            on { getSharedPreferences("upgrader", Context.MODE_PRIVATE) } doReturn sharedPref
        }
        Upgrader.init(app, Config(TestChecker(), emptyList()))
        assertNotNull(Upgrader.getInstance(), "Upgrader should not be null after init")
        assert(Upgrader.getInstance() is Upgrader) {
            "Upgrader should be Upgrader"
        }
    }
}