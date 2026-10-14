package com.maxmfonseca.pontinho

import android.graphics.drawable.AdaptiveIconDrawable
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LauncherIconTest {
    @Test fun launcherUsesAdaptiveMipmapIcon() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val applicationInfo = context.packageManager.getApplicationInfo(context.packageName, 0)

        assertEquals("mipmap", context.resources.getResourceTypeName(applicationInfo.icon))
        assertTrue(context.getDrawable(applicationInfo.icon) is AdaptiveIconDrawable)
    }
}
