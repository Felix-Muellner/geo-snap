package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.MapCardGenerator
import com.example.model.ExifGpsData
import com.example.model.ZoomPreset
import com.example.ui.GeoSnapUiState
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    @Test
    fun `read app_name from context`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("GeoSnap", appName)
    }

    @Test
    fun `formatCoordinates5Decimals formats to 5 decimal places`() {
        val gps = ExifGpsData(latitude = 48.2084912, longitude = 16.3720849)
        assertEquals("48.20849, 16.37208", gps.formatCoordinates5Decimals())
    }

    @Test
    fun `zoom presets default to street level 15 and support city 12 and custom 3 to 18`() {
        val defaultState = GeoSnapUiState()
        assertEquals(ZoomPreset.STREET_LEVEL, defaultState.selectedZoomPreset)
        assertEquals(15, defaultState.activeZoom)

        val cityState = defaultState.copy(selectedZoomPreset = ZoomPreset.CITY)
        assertEquals(12, cityState.activeZoom)

        val customState = defaultState.copy(
            selectedZoomPreset = ZoomPreset.CUSTOM,
            customZoomSliderValue = 8f
        )
        assertEquals(8, customState.activeZoom)
    }

    @Test
    fun `parseConcisePlaceName formats Nominatim jsonv2 response cleanly`() {
        val generator = MapCardGenerator()
        val json = JSONObject(
            """
            {
              "name": "Stephansplatz",
              "address": {
                "square": "Stephansplatz",
                "city": "Vienna",
                "country": "Austria"
              }
            }
            """.trimIndent()
        )
        assertEquals("Stephansplatz, Vienna, Austria", generator.parseConcisePlaceName(json))
    }
}
