package com.example.model

import android.graphics.Bitmap
import android.net.Uri
import java.util.Locale

enum class ZoomPreset(val displayName: String, val fixedZoom: Int?) {
    STREET_LEVEL("Street level", 15),
    CITY("City", 12),
    CUSTOM("Custom", null)
}

data class ExifGpsData(
    val latitude: Double,
    val longitude: Double,
    val altitudeMeters: Double? = null,
    val dateTime: String? = null,
    val cameraModel: String? = null,
    val mimeType: String? = null,
    val fileName: String? = null,
    val readFromOriginalFile: Boolean = false
) {
    fun formatCoordinates5Decimals(): String {
        return String.format(Locale.US, "%.5f, %.5f", latitude, longitude)
    }
}

sealed interface ExifReadResult {
    data class Success(
        val uri: Uri,
        val originalBitmap: Bitmap,
        val gpsData: ExifGpsData
    ) : ExifReadResult

    data class NoGpsFound(
        val uri: Uri,
        val originalBitmap: Bitmap?,
        val fileName: String?,
        val mimeType: String?,
        val readFromOriginalFile: Boolean,
        val message: String
    ) : ExifReadResult

    data class DecodeError(
        val message: String
    ) : ExifReadResult
}

enum class SamplePhotoPreset(
    val title: String,
    val subtitle: String,
    val fileName: String,
    val latitude: Double?,
    val longitude: Double?,
    val primaryColorHex: Int,
    val accentColorHex: Int
) {
    VIENNA_STEPHANSPLATZ(
        title = "Stephansplatz, Vienna",
        subtitle = "48.20849, 16.37208 • EXIF GPS",
        fileName = "Sample_Stephansplatz_Vienna_GPS.jpg",
        latitude = 48.20849,
        longitude = 16.37208,
        primaryColorHex = 0xFF1E3A5F.toInt(),
        accentColorHex = 0xFFE11D48.toInt()
    ),
    KYOTO_FUSHIMI(
        title = "Fushimi Inari, Kyoto",
        subtitle = "34.96714, 135.77267 • EXIF GPS",
        fileName = "Sample_Fushimi_Inari_Kyoto_GPS.jpg",
        latitude = 34.96714,
        longitude = 135.77267,
        primaryColorHex = 0xFF7C2D12.toInt(),
        accentColorHex = 0xFFEA580C.toInt()
    ),
    NO_GPS_PHOTO(
        title = "Studio Portrait (No GPS)",
        subtitle = "Tests missing EXIF GPS stop state",
        fileName = "Sample_Studio_No_GPS.jpg",
        latitude = null,
        longitude = null,
        primaryColorHex = 0xFF334155.toInt(),
        accentColorHex = 0xFF64748B.toInt()
    )
}
