package com.example.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.exifinterface.media.ExifInterface
import com.example.model.ExifGpsData
import com.example.model.ExifReadResult
import com.example.model.SamplePhotoPreset
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ExifLocationReader {

    /**
     * Reads EXIF GPS latitude/longitude and decodes a preview Bitmap from the picked URI
     * without ever modifying the original photo.
     *
     * On Android 10+ (API 29+), uses MediaStore.setRequireOriginal(uri) (converting picker URIs
     * via MediaStore.getMediaUri on Android 12+ first) so that location tags are not stripped
     * when ACCESS_MEDIA_LOCATION is granted.
     */
    suspend fun readPhotoAndExif(
        context: Context,
        uri: Uri
    ): ExifReadResult = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val mimeType = resolver.getType(uri) ?: guessMimeTypeFromUri(uri)
        val fileName = queryDisplayName(context, uri)

        val (exifStream, usedOriginalUri) = openOriginalOrFallbackStream(context, uri)
        if (exifStream == null) {
            return@withContext ExifReadResult.DecodeError(
                "Unable to open the selected photo stream. Please select a valid JPEG or HEIC image."
            )
        }

        var latLong: DoubleArray? = null
        var altitude: Double? = null
        var dateTime: String? = null
        var cameraModel: String? = null
        var orientation = ExifInterface.ORIENTATION_NORMAL

        try {
            exifStream.use { stream ->
                val exif = ExifInterface(stream)
                latLong = exif.latLong
                val alt = exif.getAltitude(Double.NaN)
                if (!alt.isNaN()) {
                    altitude = alt
                }
                dateTime = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
                    ?: exif.getAttribute(ExifInterface.TAG_DATETIME)
                val make = exif.getAttribute(ExifInterface.TAG_MAKE)?.trim()
                val model = exif.getAttribute(ExifInterface.TAG_MODEL)?.trim()
                cameraModel = listOfNotNull(make, model)
                    .filter { it.isNotEmpty() }
                    .distinct()
                    .joinToString(" ")
                    .ifBlank { null }
                orientation = exif.getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL
                )
            }
        } catch (e: Exception) {
            // Continue to decode bitmap even if ExifInterface threw on malformed header
        }

        // If primary stream had no latLong and we didn't get original stream on first try,
        // try fallback stream or direct MediaStore URI resolution
        if (latLong == null) {
            runCatching {
                resolver.openInputStream(uri)?.use { fallbackStream ->
                    val exif = ExifInterface(fallbackStream)
                    latLong = exif.latLong
                    if (orientation == ExifInterface.ORIENTATION_NORMAL) {
                        orientation = exif.getAttributeInt(
                            ExifInterface.TAG_ORIENTATION,
                            ExifInterface.ORIENTATION_NORMAL
                        )
                    }
                }
            }
        }

        val bitmap = decodeDownsampledBitmap(context, uri, orientation, maxDimension = 1440)
            ?: return@withContext ExifReadResult.DecodeError(
                "Could not decode the selected image. Supported formats are JPEG and HEIC."
            )

        val validLatLong = latLong?.takeIf { coords ->
            coords.size >= 2 &&
                !coords[0].isNaN() && !coords[1].isNaN() &&
                !(coords[0] == 0.0 && coords[1] == 0.0) &&
                coords[0] in -90.0..90.0 &&
                coords[1] in -180.0..180.0
        }

        if (validLatLong == null) {
            return@withContext ExifReadResult.NoGpsFound(
                uri = uri,
                originalBitmap = bitmap,
                fileName = fileName,
                mimeType = mimeType,
                readFromOriginalFile = usedOriginalUri,
                message = "No GPS latitude/longitude found in this photo's EXIF metadata."
            )
        }

        ExifReadResult.Success(
            uri = uri,
            originalBitmap = bitmap,
            gpsData = ExifGpsData(
                latitude = validLatLong[0],
                longitude = validLatLong[1],
                altitudeMeters = altitude,
                dateTime = dateTime,
                cameraModel = cameraModel,
                mimeType = mimeType,
                fileName = fileName,
                readFromOriginalFile = usedOriginalUri
            )
        )
    }

    private fun openOriginalOrFallbackStream(
        context: Context,
        uri: Uri
    ): Pair<InputStream?, Boolean> {
        val resolver = context.contentResolver
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // On Android 12+ (API 31+), Photo Picker URIs (content://media/picker/...) can be
            // resolved to their underlying MediaStore URI before calling setRequireOriginal.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                runCatching {
                    val mediaUri = MediaStore.getMediaUri(context, uri)
                    if (mediaUri != null) {
                        val originalUri = MediaStore.setRequireOriginal(mediaUri)
                        resolver.openInputStream(originalUri)?.let { return it to true }
                    }
                }
            }
            // Direct MediaStore.setRequireOriginal(uri)
            runCatching {
                val originalUri = MediaStore.setRequireOriginal(uri)
                resolver.openInputStream(originalUri)?.let { return it to true }
            }
        }
        val fallback = runCatching { resolver.openInputStream(uri) }.getOrNull()
        return fallback to false
    }

    private fun decodeDownsampledBitmap(
        context: Context,
        uri: Uri,
        orientation: Int,
        maxDimension: Int
    ): Bitmap? {
        val resolver = context.contentResolver
        val boundsOptions = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        runCatching {
            resolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, boundsOptions)
            }
        }

        val width = boundsOptions.outWidth
        val height = boundsOptions.outHeight
        if (width <= 0 || height <= 0) return null

        var sampleSize = 1
        while (max(width / sampleSize, height / sampleSize) > maxDimension) {
            sampleSize *= 2
        }

        val decodeOptions = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }

        val rawBitmap = runCatching {
            resolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, decodeOptions)
            }
        }.getOrNull() ?: return null

        return applyExifOrientation(rawBitmap, orientation)
    }

    private fun applyExifOrientation(bitmap: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.preScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.preScale(1f, -1f)
            else -> return bitmap
        }
        return runCatching {
            val rotated = Bitmap.createBitmap(
                bitmap,
                0,
                0,
                bitmap.width,
                bitmap.height,
                matrix,
                true
            )
            if (rotated != bitmap) {
                bitmap.recycle()
            }
            rotated
        }.getOrDefault(bitmap)
    }

    private fun queryDisplayName(context: Context, uri: Uri): String? {
        var cursor: Cursor? = null
        return try {
            cursor = context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null
            )
            if (cursor != null && cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0) cursor.getString(index) else null
            } else {
                uri.lastPathSegment
            }
        } catch (_: Exception) {
            uri.lastPathSegment
        } finally {
            cursor?.close()
        }
    }

    private fun guessMimeTypeFromUri(uri: Uri): String? {
        val path = uri.toString().lowercase(Locale.US)
        return when {
            path.endsWith(".heic") -> "image/heic"
            path.endsWith(".heif") -> "image/heif"
            path.endsWith(".jpg") || path.endsWith(".jpeg") -> "image/jpeg"
            else -> null
        }
    }

    /**
     * Generates a real JPEG image with embedded EXIF GPS metadata (or without GPS for the test
     * error preset) and inserts it into the device Gallery (MediaStore.Images.Media) so users
     * can test picking real geotagged photos from the gallery in an emulator.
     */
    suspend fun createSamplePhotoInGallery(
        context: Context,
        preset: SamplePhotoPreset
    ): Uri? = withContext(Dispatchers.IO) {
        val tempFile = File(context.cacheDir, preset.fileName)
        val bitmap = renderSamplePhotoBitmap(preset)

        FileOutputStream(tempFile).use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
        }
        bitmap.recycle()

        // Write EXIF metadata to the JPEG file before copying to MediaStore
        val exif = ExifInterface(tempFile.absolutePath)
        exif.setAttribute(ExifInterface.TAG_DATETIME, "2026:10:31 14:22:10")
        exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, "2026:10:31 14:22:10")
        exif.setAttribute(ExifInterface.TAG_MAKE, "GeoSnap")
        exif.setAttribute(ExifInterface.TAG_MODEL, "Pixel 9 Pro")
        exif.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())

        if (preset.latitude != null && preset.longitude != null) {
            exif.setAttribute(
                ExifInterface.TAG_GPS_LATITUDE,
                decimalToDmsRational(abs(preset.latitude))
            )
            exif.setAttribute(
                ExifInterface.TAG_GPS_LATITUDE_REF,
                if (preset.latitude >= 0) "N" else "S"
            )
            exif.setAttribute(
                ExifInterface.TAG_GPS_LONGITUDE,
                decimalToDmsRational(abs(preset.longitude))
            )
            exif.setAttribute(
                ExifInterface.TAG_GPS_LONGITUDE_REF,
                if (preset.longitude >= 0) "E" else "W"
            )
        }
        exif.saveAttributes()

        // Insert into MediaStore.Images.Media so it appears in the system Gallery / Photo Picker
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "${System.currentTimeMillis()}_${preset.fileName}")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/GeoSnapSamples")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }

        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }

        val insertedUri = resolver.insert(collection, values) ?: return@withContext null
        resolver.openOutputStream(insertedUri)?.use { outputStream ->
            tempFile.inputStream().use { inputStream ->
                inputStream.copyTo(outputStream)
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val completeValues = ContentValues().apply {
                put(MediaStore.Images.Media.IS_PENDING, 0)
            }
            resolver.update(insertedUri, completeValues, null, null)
        }

        tempFile.delete()
        insertedUri
    }

    private fun decimalToDmsRational(decimalDegrees: Double): String {
        val degrees = decimalDegrees.toInt()
        val minutesFull = (decimalDegrees - degrees) * 60.0
        val minutes = minutesFull.toInt()
        val secondsMilli = ((minutesFull - minutes) * 60.0 * 10000.0).toInt()
        return "$degrees/1,$minutes/1,$secondsMilli/10000"
    }

    private fun renderSamplePhotoBitmap(preset: SamplePhotoPreset): Bitmap {
        val size = 1080
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        // Sky gradient
        paint.shader = LinearGradient(
            0f,
            0f,
            0f,
            size.toFloat(),
            preset.primaryColorHex,
            preset.accentColorHex,
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, size.toFloat(), size.toFloat(), paint)
        paint.shader = null

        // Sun / glow
        paint.color = Color.argb(65, 255, 245, 210)
        canvas.drawCircle(size * 0.75f, size * 0.26f, 170f, paint)
        paint.color = Color.argb(140, 255, 250, 230)
        canvas.drawCircle(size * 0.75f, size * 0.26f, 95f, paint)

        // Architectural silhouette / travel postcard motif
        val silhouettePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(220, 15, 23, 42)
            style = Paint.Style.FILL
        }

        val spirePath = Path().apply {
            moveTo(0f, size.toFloat())
            lineTo(0f, size * 0.64f)
            lineTo(size * 0.22f, size * 0.56f)
            lineTo(size * 0.36f, size * 0.62f)
            lineTo(size * 0.48f, size * 0.22f) // Tall cathedral spire / monument
            lineTo(size * 0.60f, size * 0.60f)
            lineTo(size * 0.78f, size * 0.52f)
            lineTo(size.toFloat(), size * 0.65f)
            lineTo(size.toFloat(), size.toFloat())
            close()
        }
        canvas.drawPath(spirePath, silhouettePaint)

        // Badge card at top-left
        val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(190, 15, 23, 42)
        }
        canvas.drawRoundRect(RectF(64f, 64f, 920f, 250f), 28f, 28f, cardPaint)

        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 48f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        canvas.drawText(preset.title, 104f, 142f, titlePaint)

        val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(220, 203, 213, 225)
            textSize = 32f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
        }
        canvas.drawText(preset.subtitle, 104f, 204f, subPaint)

        return bmp
    }
}
