package com.localvoicetv

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23], manifest = Config.NONE)
class WeatherSettingsTest {
    @Test fun configurationIsStoredPrivatelyAndExcludedFromBackups() {
        val context = RuntimeEnvironment.getApplication()
        val store = WeatherSettingsStore(context)
        store.save(WeatherSettings("https://test.re.qweatherapi.com/", "test-key", ""))
        val loaded = store.load()
        assertEquals("test.re.qweatherapi.com", loaded.host)
        assertEquals("test-key", loaded.apiKey)
        assertEquals("", loaded.defaultCity)
        assertTrue(File(context.noBackupFilesDir, "weather.json").isFile)
        assertFalse(File(context.filesDir, "weather.json").exists())
        assertFalse(loaded.toString().contains("test-key"))
    }

    @Test fun malformedSettingsFailClearlyAndCanBeReplaced() {
        val context = RuntimeEnvironment.getApplication()
        File(context.noBackupFilesDir, "weather.json").writeText("broken")
        val store = WeatherSettingsStore(context)
        assertThrows(IllegalStateException::class.java) { store.load() }
        store.save(WeatherSettings(apiKey = "test-key"))
        assertEquals("test-key", store.load().apiKey)
    }
}
