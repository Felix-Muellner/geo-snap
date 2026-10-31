package com.example.data

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.text.TextPaint
import android.text.TextUtils
import android.util.LruCache
import androidx.core.content.FileProvider
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.tan
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

data class GeneratedMapCard(
    val bitmap: Bitmap,
    val jpegBytes: ByteArray,
    val placeName: String?,
    val coordinatesText: String,
    val zoom: Int
)

class MapCardGenerator {

    companion object {
        const val MAP_IMAGE_SIZE_PX = 1080
        // Each 256x256 OSM tile is drawn at 512x512 px on the 1080x1080 canvas so street names
        // and map labels are crisp and readable at 1080p resolution.
        private const val RENDERED_TILE_SIZE_PX = 512.0
        private const val CAPTION_BAR_HEIGHT_RATIO = 0.12f // ~12% height (130 px)
        private const val USER_AGENT = "GeoSnapMapPrototype/1.0 (Android; OpenStreetMap & Nominatim Client)"
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .build()

    // In-memory LRU tile cache so live zoom updates are fast and gentle on OSM servers
    private val tileCache = object : LruCache<String, Bitmap>(72) {}

    // Cache reverse geocoding result by 5-decimal coordinate key
    private val reverseGeocodeCache = ConcurrentHashMap<String, String>()

    /**
     * Queries Nominatim reverse geocoding API for a human-readable place name
     * (e.g. "Stephansplatz, Vienna, Austria").
     * Returns null if reverse geocoding fails so the caller displays coordinates only.
     */
    suspend fun reverseGeocodeNominatim(
        latitude: Double,
        longitude: Double
    ): String? = withContext(Dispatchers.IO) {
        val coordKey = String.format(Locale.US, "%.5f,%.5f", latitude, longitude)
        reverseGeocodeCache[coordKey]?.let { cached ->
            return@withContext cached.ifEmpty { null }
        }

        val url = String.format(
            Locale.US,
            "https://nominatim.openstreetmap.org/reverse?format=jsonv2&lat=%.6f&lon=%.6f&zoom=18&addressdetails=1",
            latitude,
            longitude
        )

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept-Language", "en")
            .get()
            .build()

        return@withContext try {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val bodyStr = response.body?.string() ?: return@use null
                val json = JSONObject(bodyStr)
                val formatted = parseConcisePlaceName(json)
                if (!formatted.isNullOrBlank()) {
                    reverseGeocodeCache[coordKey] = formatted
                }
                formatted
            }
        } catch (_: Exception) {
            null
        }
    }

    internal fun parseConcisePlaceName(json: JSONObject): String? {
        val address = json.optJSONObject("address")
        val rawName = json.optString("name").trim()

        if (address != null) {
            val poiOrStreet = listOf(
                rawName,
                address.optString("pedestrian"),
                address.optString("square"),
                address.optString("amenity"),
                address.optString("attraction"),
                address.optString("tourism"),
                address.optString("historic"),
                address.optString("road"),
                address.optString("neighbourhood"),
                address.optString("suburb")
            ).map { it.trim() }.firstOrNull { it.isNotEmpty() }

            val cityOrTown = listOf(
                address.optString("city"),
                address.optString("town"),
                address.optString("village"),
                address.optString("municipality"),
                address.optString("county"),
                address.optString("state")
            ).map { it.trim() }.firstOrNull { it.isNotEmpty() }

            val country = address.optString("country").trim().takeIf { it.isNotEmpty() }

            val parts = mutableListOf<String>()
            if (!poiOrStreet.isNullOrEmpty()) parts.add(poiOrStreet)
            if (!cityOrTown.isNullOrEmpty() && !parts.any { it.equals(cityOrTown, ignoreCase = true) }) {
                parts.add(cityOrTown)
            }
            if (!country.isNullOrEmpty() && !parts.any { it.equals(country, ignoreCase = true) }) {
                parts.add(country)
            }

            if (parts.isNotEmpty()) {
                return parts.joinToString(", ")
            }
        }

        val displayName = json.optString("display_name").trim()
        if (displayName.isNotEmpty()) {
            return displayName.split(",")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .take(3)
                .joinToString(", ")
                .ifEmpty { null }
        }
        return null
    }

    /**
     * Generates a separate 1080x1080 px square JPEG map image:
     * - OpenStreetMap tiles centered on (latitude, longitude) at [zoom] (3..18)
     * - Red pin centered on the capture location
     * - Bottom caption bar (~12% height, semi-transparent dark background, white text):
     *   Line 1: place name via Nominatim reverse geocoding (if available)
     *   Line 2: coordinates in decimal degrees, 5 decimals (e.g. 48.20849, 16.37208)
     *   If reverse geocoding fails, shows coordinates only.
     */
    suspend fun generateSquareMapCard(
        latitude: Double,
        longitude: Double,
        zoom: Int,
        placeName: String?
    ): GeneratedMapCard = withContext(Dispatchers.IO) {
        val clampedZoom = zoom.coerceIn(3, 18)
        val n = 2.0.pow(clampedZoom.toDouble())
        val nInt = 1 shl clampedZoom

        val exactTileX = ((longitude + 180.0) / 360.0) * n
        val latRad = Math.toRadians(latitude.coerceIn(-85.0511, 85.0511))
        val exactTileY = ((1.0 - ln(tan(latRad) + 1.0 / cos(latRad)) / Math.PI) / 2.0) * n

        val centerCanvasX = MAP_IMAGE_SIZE_PX / 2.0
        val centerCanvasY = MAP_IMAGE_SIZE_PX / 2.0

        val worldCenterX = exactTileX * RENDERED_TILE_SIZE_PX
        val worldCenterY = exactTileY * RENDERED_TILE_SIZE_PX

        val worldLeft = worldCenterX - centerCanvasX
        val worldTop = worldCenterY - centerCanvasY
        val worldRight = worldCenterX + centerCanvasX
        val worldBottom = worldCenterY + centerCanvasY

        val minTileX = floor(worldLeft / RENDERED_TILE_SIZE_PX).toInt()
        val maxTileX = floor(worldRight / RENDERED_TILE_SIZE_PX).toInt()
        val minTileY = floor(worldTop / RENDERED_TILE_SIZE_PX).toInt()
        val maxTileY = floor(worldBottom / RENDERED_TILE_SIZE_PX).toInt()

        data class TileRequest(val gridX: Int, val gridY: Int, val wrappedX: Int, val tileY: Int)

        val requests = mutableListOf<TileRequest>()
        for (gx in minTileX..maxTileX) {
            for (gy in minTileY..maxTileY) {
                if (gy in 0 until nInt) {
                    val wrappedX = ((gx % nInt) + nInt) % nInt
                    requests.add(TileRequest(gx, gy, wrappedX, gy))
                }
            }
        }

        val fetchedTiles = coroutineScope {
            requests.map { req ->
                async {
                    req to fetchOsmTile(clampedZoom, req.wrappedX, req.tileY)
                }
            }.awaitAll()
        }

        val outputBitmap = Bitmap.createBitmap(
            MAP_IMAGE_SIZE_PX,
            MAP_IMAGE_SIZE_PX,
            Bitmap.Config.ARGB_8888
        )
        val canvas = Canvas(outputBitmap)

        // Base map background color (matching OSM land tint)
        canvas.drawColor(Color.rgb(242, 239, 233))

        val tilePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

        for ((req, tileBitmap) in fetchedTiles) {
            val dstLeft = (req.gridX * RENDERED_TILE_SIZE_PX - worldLeft).toFloat()
            val dstTop = (req.gridY * RENDERED_TILE_SIZE_PX - worldTop).toFloat()
            val dstRect = RectF(
                dstLeft,
                dstTop,
                (dstLeft + RENDERED_TILE_SIZE_PX).toFloat(),
                (dstTop + RENDERED_TILE_SIZE_PX).toFloat()
            )
            if (tileBitmap != null) {
                canvas.drawBitmap(tileBitmap, null, dstRect, tilePaint)
            } else {
                drawFallbackTile(canvas, dstRect, clampedZoom, req.wrappedX, req.tileY)
            }
        }

        // Draw subtle OpenStreetMap attribution pill at top-right
        drawOsmAttribution(canvas)

        // Draw red pin at the exact center (540, 540)
        drawRedLocationPin(
            canvas = canvas,
            tipX = (MAP_IMAGE_SIZE_PX / 2f),
            tipY = (MAP_IMAGE_SIZE_PX / 2f)
        )

        val coordinatesText = String.format(Locale.US, "%.5f, %.5f", latitude, longitude)

        // Draw bottom caption bar (~12% height = 130px, semi-transparent dark background, white text)
        drawBottomCaptionBar(
            canvas = canvas,
            placeName = placeName?.takeIf { it.isNotBlank() },
            coordinatesText = coordinatesText
        )

        val jpegStream = ByteArrayOutputStream()
        outputBitmap.compress(Bitmap.CompressFormat.JPEG, 95, jpegStream)
        val jpegBytes = jpegStream.toByteArray()

        GeneratedMapCard(
            bitmap = outputBitmap,
            jpegBytes = jpegBytes,
            placeName = placeName?.takeIf { it.isNotBlank() },
            coordinatesText = coordinatesText,
            zoom = clampedZoom
        )
    }

    private fun fetchOsmTile(zoom: Int, x: Int, y: Int): Bitmap? {
        val key = "$zoom/$x/$y"
        synchronized(tileCache) {
            tileCache.get(key)?.let { return it }
        }

        val url = "https://tile.openstreetmap.org/$zoom/$x/$y.png"
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .get()
            .build()

        return try {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val bytes = response.body?.bytes() ?: return@use null
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                if (bitmap != null) {
                    synchronized(tileCache) {
                        tileCache.put(key, bitmap)
                    }
                }
                bitmap
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun drawFallbackTile(
        canvas: Canvas,
        rect: RectF,
        zoom: Int,
        x: Int,
        y: Int
    ) {
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(232, 236, 241)
            style = Paint.Style.FILL
        }
        canvas.drawRect(rect, bgPaint)

        val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(203, 213, 225)
            style = Paint.Style.STROKE
            strokeWidth = 2f
        }
        canvas.drawRect(rect, gridPaint)
        canvas.drawLine(rect.left, rect.centerY(), rect.right, rect.centerY(), gridPaint)
        canvas.drawLine(rect.centerX(), rect.top, rect.centerX(), rect.bottom, gridPaint)

        val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(148, 163, 184)
            textSize = 22f
            typeface = Typeface.MONOSPACE
        }
        canvas.drawText("OSM $zoom/$x/$y", rect.left + 20f, rect.top + 40f, labelPaint)
    }

    private fun drawOsmAttribution(canvas: Canvas) {
        val text = "© OpenStreetMap contributors"
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(215, 30, 41, 59)
            textSize = 20f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        }
        val textWidth = textPaint.measureText(text)
        val padH = 16f
        val padV = 10f
        val margin = 18f
        val bgRect = RectF(
            MAP_IMAGE_SIZE_PX - textWidth - padH * 2 - margin,
            margin,
            MAP_IMAGE_SIZE_PX - margin,
            margin + 24f + padV * 2
        )
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(190, 255, 255, 255)
        }
        canvas.drawRoundRect(bgRect, 12f, 12f, bgPaint)
        canvas.drawText(text, bgRect.left + padH, bgRect.bottom - padV - 3f, textPaint)
    }

    private fun drawRedLocationPin(canvas: Canvas, tipX: Float, tipY: Float) {
        // Ground shadow ellipse right under the pin tip
        val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(95, 15, 23, 42)
            style = Paint.Style.FILL
        }
        canvas.drawOval(
            RectF(tipX - 24f, tipY - 8f, tipX + 24f, tipY + 10f),
            shadowPaint
        )

        val pinRadius = 38f
        val circleCenterY = tipY - 68f

        val pinPath = Path().apply {
            // Teardrop pin shape pointing to (tipX, tipY)
            moveTo(tipX, tipY)
            cubicTo(
                tipX - 18f, tipY - 26f,
                tipX - pinRadius, circleCenterY + 16f,
                tipX - pinRadius, circleCenterY
            )
            arcTo(
                RectF(
                    tipX - pinRadius,
                    circleCenterY - pinRadius,
                    tipX + pinRadius,
                    circleCenterY + pinRadius
                ),
                180f,
                180f,
                false
            )
            cubicTo(
                tipX + pinRadius, circleCenterY + 16f,
                tipX + 18f, tipY - 26f,
                tipX, tipY
            )
            close()
        }

        // White outer stroke for crisp contrast over any map tile
        val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 8f
            strokeJoin = Paint.Join.ROUND
        }
        canvas.drawPath(pinPath, strokePaint)

        // Bold red fill
        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(229, 57, 53) // #E53935 vibrant red pin
            style = Paint.Style.FILL
        }
        canvas.drawPath(pinPath, fillPaint)

        // Inner white circle
        val innerDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
        }
        canvas.drawCircle(tipX, circleCenterY, 14f, innerDotPaint)
    }

    private fun drawBottomCaptionBar(
        canvas: Canvas,
        placeName: String?,
        coordinatesText: String
    ) {
        val barHeight = MAP_IMAGE_SIZE_PX * CAPTION_BAR_HEIGHT_RATIO // 129.6px (~12% of 1080px)
        val barTop = MAP_IMAGE_SIZE_PX - barHeight

        val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(205, 15, 20, 28) // Semi-transparent dark background
            style = Paint.Style.FILL
        }
        canvas.drawRect(0f, barTop, MAP_IMAGE_SIZE_PX.toFloat(), MAP_IMAGE_SIZE_PX.toFloat(), barPaint)

        val horizontalPadding = 44f
        val availableWidth = MAP_IMAGE_SIZE_PX - (horizontalPadding * 2f)

        val line1Paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 36f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }

        val line2Paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(235, 255, 255, 255)
            textSize = 30f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
        }

        if (!placeName.isNullOrBlank()) {
            // Line 1: Place name via reverse geocoding (ellipsized if extremely long)
            val ellipsizedPlace = TextUtils.ellipsize(
                placeName,
                line1Paint,
                availableWidth,
                TextUtils.TruncateAt.END
            ).toString()

            val line1Y = barTop + (barHeight * 0.44f)
            val line2Y = barTop + (barHeight * 0.80f)

            canvas.drawText(ellipsizedPlace, horizontalPadding, line1Y, line1Paint)
            canvas.drawText(coordinatesText, horizontalPadding, line2Y, line2Paint)
        } else {
            // If reverse geocoding fails, show coordinates only (centered vertically)
            val singleLinePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = 36f
                typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            }
            val textBounds = Rect()
            singleLinePaint.getTextBounds(coordinatesText, 0, coordinatesText.length, textBounds)
            val centerY = barTop + (barHeight / 2f) - textBounds.exactCenterY()
            canvas.drawText(coordinatesText, horizontalPadding, centerY, singleLinePaint)
        }
    }

    /**
     * Saves the generated 1080x1080 JPEG map image to the device Gallery.
     */
    suspend fun saveMapToGallery(
        context: Context,
        jpegBytes: ByteArray,
        latitude: Double,
        longitude: Double,
        zoom: Int
    ): Result<Uri> = withContext(Dispatchers.IO) {
        runCatching {
            val resolver = context.contentResolver
            val fileName = String.format(
                Locale.US,
                "GeoSnap_Map_%.5f_%.5f_z%d_%d.jpg",
                latitude,
                longitude,
                zoom,
                System.currentTimeMillis()
            )
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.WIDTH, MAP_IMAGE_SIZE_PX)
                put(MediaStore.Images.Media.HEIGHT, MAP_IMAGE_SIZE_PX)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/GeoSnap")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
            }

            val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            } else {
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            }

            val uri = resolver.insert(collection, values)
                ?: throw IllegalStateException("Failed to create MediaStore entry for map image.")

            resolver.openOutputStream(uri)?.use { out ->
                out.write(jpegBytes)
                out.flush()
            } ?: throw IllegalStateException("Failed to open output stream for MediaStore entry.")

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val completeValues = ContentValues().apply {
                    put(MediaStore.Images.Media.IS_PENDING, 0)
                }
                resolver.update(uri, completeValues, null, null)
            }

            uri
        }
    }

    /**
     * Writes the generated 1080x1080 JPEG map to the cache directory and launches the Android
     * system Share chooser via FileProvider.
     */
    suspend fun prepareShareIntent(
        context: Context,
        jpegBytes: ByteArray,
        placeName: String?,
        coordinatesText: String
    ): Result<Intent> = withContext(Dispatchers.IO) {
        runCatching {
            val sharedDir = File(context.cacheDir, "shared_maps").apply { mkdirs() }
            val outFile = File(sharedDir, "GeoSnap_Map_1080x1080.jpg")
            FileOutputStream(outFile).use { out ->
                out.write(jpegBytes)
                out.flush()
            }

            val contentUri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                outFile
            )

            val caption = if (!placeName.isNullOrBlank()) {
                "$placeName ($coordinatesText)"
            } else {
                coordinatesText
            }

            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                type = "image/jpeg"
                putExtra(Intent.EXTRA_STREAM, contentUri)
                putExtra(Intent.EXTRA_TEXT, caption)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            Intent.createChooser(sendIntent, "Share 1080×1080 Map Image")
        }
    }
}
