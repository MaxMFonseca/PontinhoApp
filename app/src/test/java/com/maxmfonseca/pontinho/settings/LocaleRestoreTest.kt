package com.maxmfonseca.pontinho.settings

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.app.AppLocalesMetadataHolderService
import androidx.core.os.LocaleListCompat
import androidx.test.core.app.ApplicationProvider
import com.maxmfonseca.pontinho.R
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32])
class LocaleRestoreTest {
    @After fun resetLocale() {
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList())
    }

    @Test fun androidXAutoStorageIsEnabledForPre33ProcessRestore() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val service = context.packageManager.getServiceInfo(
            ComponentName(context, AppLocalesMetadataHolderService::class.java),
            PackageManager.GET_META_DATA or PackageManager.MATCH_DISABLED_COMPONENTS,
        )

        assertFalse(service.enabled)
        assertTrue(service.metaData.getBoolean("autoStoreLocales"))
    }

    @Test fun appCompatLocaleIsAppliedAcrossActivityRecreationOnApi32() {
        val controller = Robolectric.buildActivity(LocaleHostActivity::class.java).setup()
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("en-US"))
        shadowOf(android.os.Looper.getMainLooper()).idle()

        controller.recreate()

        assertEquals("New match", controller.get().getString(R.string.home_new_match))
    }

    class LocaleHostActivity : AppCompatActivity() {
        override fun onCreate(savedInstanceState: Bundle?) {
            setTheme(R.style.Theme_Pontinho)
            super.onCreate(savedInstanceState)
        }
    }
}
