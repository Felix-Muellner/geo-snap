package com.example.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.ExifLocationReader
import com.example.data.MapCardGenerator
import com.example.model.ExifGpsData
import com.example.model.ExifReadResult
import com.example.model.SamplePhotoPreset
import com.example.model.ZoomPreset
import kotlin.math.roundToInt
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class GeoSnapUiState(
    val selectedZoomPreset: ZoomPreset = ZoomPreset.STREET_LEVEL,
    val customZoomSliderValue: Float = 15f,
    val mediaLocationPermissionGranted: Boolean = false,
    val isReadingPhoto: Boolean = false,
    val isRenderingMap: Boolean = false,
    val isSavingToGallery: Boolean = false,
    val isSeedingSample: Boolean = false,
    val originalPhotoUri: Uri? = null,
    val originalBitmap: Bitmap? = null,
    val exifGpsData: ExifGpsData? = null,
    val reverseGeocodedPlace: String? = null,
    val generatedMapBitmap: Bitmap? = null,
    val generatedMapJpegBytes: ByteArray? = null,
    val renderedZoom: Int = 15,
    val noGpsErrorMessage: String? = null,
    val noGpsFileName: String? = null,
    val noGpsMimeType: String? = null,
    val statusSnackbarMessage: String? = null
) {
    val activeZoom: Int
        get() = when (selectedZoomPreset) {
            ZoomPreset.STREET_LEVEL -> 15
            ZoomPreset.CITY -> 12
            ZoomPreset.CUSTOM -> customZoomSliderValue.roundToInt().coerceIn(3, 18)
        }
}

class GeoSnapViewModel(
    private val exifReader: ExifLocationReader = ExifLocationReader(),
    private val mapGenerator: MapCardGenerator = MapCardGenerator()
) : ViewModel() {

    private val _uiState = MutableStateFlow(GeoSnapUiState())
    val uiState: StateFlow<GeoSnapUiState> = _uiState.asStateFlow()

    private var mapRenderJob: Job? = null

    fun setMediaLocationPermissionGranted(granted: Boolean) {
        _uiState.update { it.copy(mediaLocationPermissionGranted = granted) }
    }

    fun selectZoomPreset(preset: ZoomPreset) {
        val currentState = _uiState.value
        val updatedSlider = when (preset) {
            ZoomPreset.STREET_LEVEL -> 15f
            ZoomPreset.CITY -> 12f
            ZoomPreset.CUSTOM -> currentState.customZoomSliderValue
        }
        _uiState.update {
            it.copy(
                selectedZoomPreset = preset,
                customZoomSliderValue = updatedSlider
            )
        }
        // Live update the map image if a valid geotagged photo is already loaded
        triggerLiveMapUpdate(debounceMs = 0L)
    }

    fun updateCustomZoomSlider(value: Float) {
        val clamped = value.coerceIn(3f, 18f)
        val newZoomInt = clamped.roundToInt()
        val previousZoomInt = _uiState.value.activeZoom
        _uiState.update {
            it.copy(
                selectedZoomPreset = ZoomPreset.CUSTOM,
                customZoomSliderValue = clamped
            )
        }
        if (newZoomInt != previousZoomInt || _uiState.value.generatedMapBitmap == null) {
            triggerLiveMapUpdate(debounceMs = 120L)
        }
    }

    fun onPhotoPicked(context: Context, uri: Uri) {
        mapRenderJob?.cancel()
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isReadingPhoto = true,
                    isRenderingMap = false,
                    noGpsErrorMessage = null,
                    noGpsFileName = null,
                    noGpsMimeType = null,
                    generatedMapBitmap = null,
                    generatedMapJpegBytes = null,
                    reverseGeocodedPlace = null,
                    exifGpsData = null
                )
            }

            when (val result = exifReader.readPhotoAndExif(context.applicationContext, uri)) {
                is ExifReadResult.DecodeError -> {
                    _uiState.update {
                        it.copy(
                            isReadingPhoto = false,
                            originalPhotoUri = null,
                            originalBitmap = null,
                            noGpsErrorMessage = result.message
                        )
                    }
                }

                is ExifReadResult.NoGpsFound -> {
                    // Requirement 3: If no GPS data: show a clear message and stop.
                    _uiState.update {
                        it.copy(
                            isReadingPhoto = false,
                            originalPhotoUri = result.uri,
                            originalBitmap = result.originalBitmap,
                            exifGpsData = null,
                            generatedMapBitmap = null,
                            generatedMapJpegBytes = null,
                            noGpsErrorMessage = result.message,
                            noGpsFileName = result.fileName,
                            noGpsMimeType = result.mimeType
                        )
                    }
                }

                is ExifReadResult.Success -> {
                    _uiState.update {
                        it.copy(
                            isReadingPhoto = false,
                            originalPhotoUri = result.uri,
                            originalBitmap = result.originalBitmap,
                            exifGpsData = result.gpsData,
                            noGpsErrorMessage = null
                        )
                    }
                    // Immediately reverse-geocode and generate the 1080x1080 map at selected zoom
                    generateMapForCurrentState( debounceMs = 0L)
                }
            }
        }
    }

    fun regenerateMapNow() {
        generateMapForCurrentState(debounceMs = 0L)
    }

    private fun triggerLiveMapUpdate(debounceMs: Long) {
        if (_uiState.value.exifGpsData != null) {
            generateMapForCurrentState(debounceMs = debounceMs)
        }
    }

    private fun generateMapForCurrentState(debounceMs: Long) {
        val gpsData = _uiState.value.exifGpsData ?: return
        val targetZoom = _uiState.value.activeZoom

        mapRenderJob?.cancel()
        mapRenderJob = viewModelScope.launch {
            if (debounceMs > 0L) {
                delay(debounceMs)
            }
            _uiState.update { it.copy(isRenderingMap = true) }

            // 1. Obtain Nominatim reverse-geocoded place name (cached by lat/lon across zoom changes)
            val placeName = _uiState.value.reverseGeocodedPlace
                ?: mapGenerator.reverseGeocodeNominatim(gpsData.latitude, gpsData.longitude)

            // 2. Render 1080x1080 square JPEG map card
            val generated = mapGenerator.generateSquareMapCard(
                latitude = gpsData.latitude,
                longitude = gpsData.longitude,
                zoom = targetZoom,
                placeName = placeName
            )

            _uiState.update {
                it.copy(
                    isRenderingMap = false,
                    reverseGeocodedPlace = generated.placeName,
                    generatedMapBitmap = generated.bitmap,
                    generatedMapJpegBytes = generated.jpegBytes,
                    renderedZoom = generated.zoom
                )
            }
        }
    }

    fun saveGeneratedMapToGallery(context: Context) {
        val state = _uiState.value
        val jpegBytes = state.generatedMapJpegBytes ?: return
        val gpsData = state.exifGpsData ?: return

        viewModelScope.launch {
            _uiState.update { it.copy(isSavingToGallery = true) }
            val result = mapGenerator.saveMapToGallery(
                context = context.applicationContext,
                jpegBytes = jpegBytes,
                latitude = gpsData.latitude,
                longitude = gpsData.longitude,
                zoom = state.renderedZoom
            )
            _uiState.update {
                it.copy(
                    isSavingToGallery = false,
                    statusSnackbarMessage = if (result.isSuccess) {
                        "Saved 1080×1080 JPEG map to Gallery (Pictures/GeoSnap)!"
                    } else {
                        "Failed to save map: ${result.exceptionOrNull()?.localizedMessage ?: "Unknown error"}"
                    }
                )
            }
        }
    }

    fun shareGeneratedMap(context: Context, onLaunchIntent: (Intent) -> Unit) {
        val state = _uiState.value
        val jpegBytes = state.generatedMapJpegBytes ?: return
        val gpsData = state.exifGpsData ?: return

        viewModelScope.launch {
            val result = mapGenerator.prepareShareIntent(
                context = context.applicationContext,
                jpegBytes = jpegBytes,
                placeName = state.reverseGeocodedPlace,
                coordinatesText = gpsData.formatCoordinates5Decimals()
            )
            result.onSuccess { chooserIntent ->
                onLaunchIntent(chooserIntent)
            }.onFailure { err ->
                _uiState.update {
                    it.copy(statusSnackbarMessage = "Unable to share image: ${err.localizedMessage}")
                }
            }
        }
    }

    fun seedAndLoadSamplePhoto(
        context: Context,
        preset: SamplePhotoPreset,
        loadImmediately: Boolean
    ) {
        viewModelScope.launch {
            _uiState.update { it.copy(isSeedingSample = true) }
            val uri = exifReader.createSamplePhotoInGallery(context.applicationContext, preset)
            _uiState.update {
                it.copy(
                    isSeedingSample = false,
                    statusSnackbarMessage = if (uri != null) {
                        "Added \"${preset.title}\" to Device Gallery! You can also pick it via Gallery Picker."
                    } else {
                        "Could not add sample photo to Gallery."
                    }
                )
            }
            if (uri != null && loadImmediately) {
                onPhotoPicked(context, uri)
            }
        }
    }

    fun clearSnackbarMessage() {
        _uiState.update { it.copy(statusSnackbarMessage = null) }
    }
}
