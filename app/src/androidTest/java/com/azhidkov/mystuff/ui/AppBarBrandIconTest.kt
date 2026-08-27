package com.azhidkov.mystuff.ui

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppBarBrandIconTest {
    @Test
    fun adaptiveLauncherIconIsNotUsedAsComposePainter() {
        val scenario = ActivityScenario.launch(AppBarBrandIconTestActivity::class.java)
        assertEquals(
            androidx.lifecycle.Lifecycle.State.RESUMED,
            scenario.state,
        )
        scenario.close()
    }
}
