package com.example.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.LocationOff
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.R
import com.example.model.ExifGpsData
import com.example.model.SamplePhotoPreset
import com.example.model.ZoomPreset
import kotlin.math.roundToInt

private enum class FullscreenImageTarget {
    ORIGINAL,
    MAP
}

@Composable
fun GeoSnapScreen(
    viewModel: GeoSnapViewModel = viewModel()
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var fullscreenTarget by remember { mutableStateOf<FullscreenImageTarget?>(null) }
    var pendingActionAfterPermission by remember { mutableStateOf<(() -> Unit)?>(null) }

    // Check initial ACCESS_MEDIA_LOCATION permission state
    LaunchedEffect(Unit) {
        val granted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_MEDIA_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
        viewModel.setMediaLocationPermissionGranted(granted)
    }

    // Show Snackbar messages
    LaunchedEffect(uiState.statusSnackbarMessage) {
        val msg = uiState.statusSnackbarMessage
        if (!msg.isNullOrBlank()) {
            snackbarHostState.showSnackbar(msg)
            viewModel.clearSnackbarMessage()
        }
    }

    // Photo Picker launcher (JPEG & HEIC)
    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            viewModel.onPhotoPicked(context, uri)
        }
    }

    // Document Picker launcher (alternative direct file access for JPEG & HEIC)
    val documentPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            viewModel.onPhotoPicked(context, uri)
        }
    }

    // Runtime permission launcher for ACCESS_MEDIA_LOCATION (Android 10+)
    val mediaLocationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        viewModel.setMediaLocationPermissionGranted(isGranted)
        val nextAction = pendingActionAfterPermission
        pendingActionAfterPermission = null
        nextAction?.invoke()
    }

    val launchGalleryPickerWithPermission = {
        val openPicker = {
            photoPickerLauncher.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
            )
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !uiState.mediaLocationPermissionGranted) {
            pendingActionAfterPermission = openPicker
            mediaLocationPermissionLauncher.launch(Manifest.permission.ACCESS_MEDIA_LOCATION)
        } else {
            openPicker()
        }
    }

    val launchDocumentPickerWithPermission = {
        val openDoc = {
            documentPickerLauncher.launch(
                arrayOf("image/jpeg", "image/heic", "image/heif", "image/*")
            )
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !uiState.mediaLocationPermissionGranted) {
            pendingActionAfterPermission = openDoc
            mediaLocationPermissionLauncher.launch(Manifest.permission.ACCESS_MEDIA_LOCATION)
        } else {
            openDoc()
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentAlignment = Alignment.TopCenter
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 680.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Hero Header Banner
                HeroHeaderCard(
                    mediaLocationGranted = uiState.mediaLocationPermissionGranted,
                    onRequestPermission = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            mediaLocationPermissionLauncher.launch(Manifest.permission.ACCESS_MEDIA_LOCATION)
                        }
                    }
                )

                // Step 1: Zoom Selector (Street level = 15, City = 12, Custom = 3..18)
                ZoomSelectorCard(
                    selectedPreset = uiState.selectedZoomPreset,
                    activeZoom = uiState.activeZoom,
                    customSliderValue = uiState.customZoomSliderValue,
                    isLiveUpdating = uiState.isRenderingMap,
                    hasLoadedPhoto = uiState.exifGpsData != null,
                    onSelectPreset = viewModel::selectZoomPreset,
                    onCustomSliderChange = viewModel::updateCustomZoomSlider
                )

                // Step 2: Photo Selection Card (Gallery Picker + Original File Picker)
                PhotoPickerCard(
                    isReadingPhoto = uiState.isReadingPhoto,
                    onPickFromGallery = launchGalleryPickerWithPermission,
                    onBrowseOriginalFile = launchDocumentPickerWithPermission
                )

                // Requirement 3: If no GPS data: show a clear message and stop
                AnimatedVisibility(
                    visible = uiState.noGpsErrorMessage != null,
                    enter = fadeIn(),
                    exit = fadeOut()
                ) {
                    uiState.noGpsErrorMessage?.let { errorMsg ->
                        NoGpsErrorCard(
                            errorMessage = errorMsg,
                            photoBitmap = uiState.originalBitmap,
                            fileName = uiState.noGpsFileName,
                            mimeType = uiState.noGpsMimeType,
                            permissionGranted = uiState.mediaLocationPermissionGranted,
                            onPickAnotherPhoto = launchGalleryPickerWithPermission,
                            onBrowseOriginalFile = launchDocumentPickerWithPermission
                        )
                    }
                }

                // Requirement 7: Side-by-side preview of Original Photo and 1080x1080 Map Image
                // with live update on zoom change, plus "Save map to gallery" and "Share" buttons
                AnimatedVisibility(
                    visible = uiState.exifGpsData != null && uiState.originalBitmap != null,
                    enter = fadeIn(),
                    exit = fadeOut()
                ) {
                    val gpsData = uiState.exifGpsData
                    val origBmp = uiState.originalBitmap
                    if (gpsData != null && origBmp != null) {
                        SideBySidePreviewAndActionsCard(
                            originalBitmap = origBmp,
                            mapBitmap = uiState.generatedMapBitmap,
                            exifGpsData = gpsData,
                            placeName = uiState.reverseGeocodedPlace,
                            activeZoom = uiState.activeZoom,
                            isRenderingMap = uiState.isRenderingMap,
                            isSavingToGallery = uiState.isSavingToGallery,
                            onInspectOriginal = { fullscreenTarget = FullscreenImageTarget.ORIGINAL },
                            onInspectMap = {
                                if (uiState.generatedMapBitmap != null) {
                                    fullscreenTarget = FullscreenImageTarget.MAP
                                }
                            },
                            onRegenerateMap = viewModel::regenerateMapNow,
                            onSaveToGallery = { viewModel.saveGeneratedMapToGallery(context) },
                            onShareMap = {
                                viewModel.shareGeneratedMap(context) { chooserIntent ->
                                    context.startActivity(chooserIntent)
                                }
                            }
                        )
                    }
                }

                // Helper Card for Emulator Testing: Seed Real EXIF-Geotagged Photos into Gallery
                EmulatorTestSamplesCard(
                    isSeeding = uiState.isSeedingSample,
                    onSeedAndLoad = { preset ->
                        viewModel.seedAndLoadSamplePhoto(
                            context = context,
                            preset = preset,
                            loadImmediately = true
                        )
                    },
                    onSeedToGalleryOnly = { preset ->
                        viewModel.seedAndLoadSamplePhoto(
                            context = context,
                            preset = preset,
                            loadImmediately = false
                        )
                    }
                )

                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }

    // Full-screen lightbox dialog for inspecting the Original Photo or 1080x1080 Map Card
    fullscreenTarget?.let { target ->
        val bitmapToShow = when (target) {
            FullscreenImageTarget.ORIGINAL -> uiState.originalBitmap
            FullscreenImageTarget.MAP -> uiState.generatedMapBitmap
        }
        val title = when (target) {
            FullscreenImageTarget.ORIGINAL -> "Original Photo (Unmodified)"
            FullscreenImageTarget.MAP -> "Generated Map (1080×1080 px JPEG • Zoom ${uiState.renderedZoom})"
        }
        if (bitmapToShow != null) {
            FullscreenImageDialog(
                bitmap = bitmapToShow,
                title = title,
                onDismiss = { fullscreenTarget = null }
            )
        } else {
            fullscreenTarget = null
        }
    }
}

@Composable
private fun HeroHeaderCard(
    mediaLocationGranted: Boolean,
    onRequestPermission: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("hero_header_card"),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color.Transparent
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    brush = Brush.linearGradient(
                        colors = listOf(
                            Color(0xFF0F172A),
                            Color(0xFF1E3A5F)
                        )
                    )
                )
                .padding(20.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(Color(0xFFE11D48)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.LocationOn,
                            contentDescription = stringResource(R.string.app_name),
                            tint = Color.White,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.app_name),
                            style = MaterialTheme.typography.headlineMedium,
                            color = Color.White
                        )
                        Text(
                            text = stringResource(R.string.app_subtitle),
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color(0xFFCBD5E1)
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        color = if (mediaLocationGranted) {
                            Color(0xFF065F46)
                        } else {
                            Color(0xFF78350F)
                        },
                        shape = RoundedCornerShape(50),
                        modifier = Modifier
                            .clickable(enabled = !mediaLocationGranted) { onRequestPermission() }
                            .testTag("media_location_status_badge")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = if (mediaLocationGranted) {
                                    Icons.Outlined.VerifiedUser
                                } else {
                                    Icons.Outlined.Lock
                                },
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(15.dp)
                            )
                            Text(
                                text = if (mediaLocationGranted) {
                                    "ACCESS_MEDIA_LOCATION Ready"
                                } else {
                                    "Tap to grant ACCESS_MEDIA_LOCATION"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.White,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }

                    Surface(
                        color = Color.White.copy(alpha = 0.14f),
                        shape = RoundedCornerShape(50)
                    ) {
                        Text(
                            text = "JPEG & HEIC",
                            style = MaterialTheme.typography.labelMedium,
                            color = Color.White,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ZoomSelectorCard(
    selectedPreset: ZoomPreset,
    activeZoom: Int,
    customSliderValue: Float,
    isLiveUpdating: Boolean,
    hasLoadedPhoto: Boolean,
    onSelectPreset: (ZoomPreset) -> Unit,
    onCustomSliderChange: (Float) -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("zoom_selector_card"),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.ZoomIn,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = stringResource(R.string.zoom_level_title),
                        style = MaterialTheme.typography.titleMedium
                    )
                }

                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = RoundedCornerShape(50)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        if (isLiveUpdating) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(12.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        }
                        Text(
                            text = stringResource(R.string.zoom_value_label, activeZoom),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.testTag("active_zoom_badge")
                        )
                    }
                }
            }

            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ZoomPreset.entries.forEach { preset ->
                    val isSelected = selectedPreset == preset
                    val chipLabel = when (preset) {
                        ZoomPreset.STREET_LEVEL -> "Street level (15)"
                        ZoomPreset.CITY -> "City (12)"
                        ZoomPreset.CUSTOM -> if (selectedPreset == ZoomPreset.CUSTOM) {
                            "Custom ($activeZoom)"
                        } else {
                            "Custom (3–18)"
                        }
                    }
                    val tag = when (preset) {
                        ZoomPreset.STREET_LEVEL -> "zoom_preset_street"
                        ZoomPreset.CITY -> "zoom_preset_city"
                        ZoomPreset.CUSTOM -> "zoom_preset_custom"
                    }

                    FilterChip(
                        selected = isSelected,
                        onClick = { onSelectPreset(preset) },
                        label = {
                            Text(
                                text = chipLabel,
                                style = MaterialTheme.typography.labelLarge
                            )
                        },
                        leadingIcon = if (isSelected) {
                            {
                                Icon(
                                    imageVector = Icons.Default.CheckCircle,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        } else null,
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            selectedLeadingIconColor = MaterialTheme.colorScheme.primary
                        ),
                        modifier = Modifier.testTag(tag)
                    )
                }
            }

            AnimatedVisibility(visible = selectedPreset == ZoomPreset.CUSTOM) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Continent (3)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "Selected: Zoom ${customSliderValue.roundToInt()}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = "Block (18)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Slider(
                        value = customSliderValue,
                        onValueChange = onCustomSliderChange,
                        valueRange = 3f..18f,
                        steps = 14,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("custom_zoom_slider")
                    )
                }
            }

            if (hasLoadedPhoto) {
                Text(
                    text = "Live update active: changing zoom immediately re-renders the 1080×1080 map preview below.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun PhotoPickerCard(
    isReadingPhoto: Boolean,
    onPickFromGallery: () -> Unit,
    onBrowseOriginalFile: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("photo_picker_card"),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.PhotoLibrary,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "Select Geotagged Photo",
                    style = MaterialTheme.typography.titleMedium
                )
            }

            Button(
                onClick = onPickFromGallery,
                enabled = !isReadingPhoto,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .testTag("pick_photo_button"),
                shape = RoundedCornerShape(14.dp)
            ) {
                if (isReadingPhoto) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(text = stringResource(R.string.status_extracting_exif))
                } else {
                    Icon(
                        imageVector = Icons.Default.AddPhotoAlternate,
                        contentDescription = null
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = stringResource(R.string.pick_photo_gallery),
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }

            OutlinedButton(
                onClick = onBrowseOriginalFile,
                enabled = !isReadingPhoto,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .testTag("browse_document_button"),
                shape = RoundedCornerShape(14.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.FolderOpen,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(text = stringResource(R.string.pick_photo_file))
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    imageVector = Icons.Outlined.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    text = stringResource(R.string.supported_formats_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun NoGpsErrorCard(
    errorMessage: String,
    photoBitmap: Bitmap?,
    fileName: String?,
    mimeType: String?,
    permissionGranted: Boolean,
    onPickAnotherPhoto: () -> Unit,
    onBrowseOriginalFile: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("no_gps_error_card"),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        ),
        border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.error)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.error),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.LocationOff,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onError
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.no_gps_title),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Stopped — No map image was generated.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (photoBitmap != null) {
                    Image(
                        bitmap = photoBitmap.asImageBitmap(),
                        contentDescription = "Selected photo without GPS",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(76.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .border(
                                1.dp,
                                MaterialTheme.colorScheme.error.copy(alpha = 0.5f),
                                RoundedCornerShape(12.dp)
                            )
                    )
                }

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = errorMessage,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                    if (!fileName.isNullOrBlank() || !mimeType.isNullOrBlank()) {
                        Text(
                            text = listOfNotNull(fileName, mimeType).joinToString(" • "),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.8f)
                        )
                    }
                    if (!permissionGranted) {
                        Text(
                            text = stringResource(R.string.permission_media_location_denied),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onPickAnotherPhoto,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    ),
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Pick Another Photo")
                }
                OutlinedButton(
                    onClick = onBrowseOriginalFile,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Browse Original File")
                }
            }
        }
    }
}

@Composable
private fun SideBySidePreviewAndActionsCard(
    originalBitmap: Bitmap,
    mapBitmap: Bitmap?,
    exifGpsData: ExifGpsData,
    placeName: String?,
    activeZoom: Int,
    isRenderingMap: Boolean,
    isSavingToGallery: Boolean,
    onInspectOriginal: () -> Unit,
    onInspectMap: () -> Unit,
    onRegenerateMap: () -> Unit,
    onSaveToGallery: () -> Unit,
    onShareMap: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("side_by_side_preview_card"),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Top bar of Preview section
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Map,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Column {
                        Text(
                            text = stringResource(R.string.preview_section_title),
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = "Tap either image to inspect full screen",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                IconButton(
                    onClick = onRegenerateMap,
                    enabled = !isRenderingMap,
                    modifier = Modifier.testTag("regenerate_map_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = stringResource(R.string.generate_map_button)
                    )
                }
            }

            // Side-by-Side Row: Original Photo (left) and 1080x1080 Map Image (right)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("side_by_side_preview"),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Left Column: Original Photo (Unmodified)
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = stringResource(R.string.preview_original_label),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(16.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .border(
                                1.dp,
                                MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
                                RoundedCornerShape(16.dp)
                            )
                            .clickable { onInspectOriginal() }
                            .testTag("original_photo_preview"),
                        contentAlignment = Alignment.Center
                    ) {
                        Image(
                            bitmap = originalBitmap.asImageBitmap(),
                            contentDescription = stringResource(R.string.preview_original_label),
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                        Surface(
                            color = Color.Black.copy(alpha = 0.65f),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .padding(8.dp)
                        ) {
                            Text(
                                text = "Original • Unmodified",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.White,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }
                    }
                }

                // Right Column: Generated 1080x1080 Square Map JPEG
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = "Map (1080×1080 • z$activeZoom)",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(16.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .border(
                                1.5.dp,
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
                                RoundedCornerShape(16.dp)
                            )
                            .clickable(enabled = mapBitmap != null) { onInspectMap() }
                            .testTag("generated_map_preview"),
                        contentAlignment = Alignment.Center
                    ) {
                        if (mapBitmap != null) {
                            Image(
                                bitmap = mapBitmap.asImageBitmap(),
                                contentDescription = stringResource(R.string.preview_map_label),
                                contentScale = ContentScale.Fit,
                                modifier = Modifier.fillMaxSize()
                            )
                        }

                        if (isRenderingMap) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(Color.Black.copy(alpha = if (mapBitmap == null) 0.65f else 0.35f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.padding(12.dp)
                                ) {
                                    CircularProgressIndicator(
                                        color = Color.White,
                                        strokeWidth = 3.dp,
                                        modifier = Modifier.size(28.dp)
                                    )
                                    Text(
                                        text = "Updating z$activeZoom…",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Color.White,
                                        textAlign = TextAlign.Center
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Telemetry & Caption Preview Box
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = "CAPTION BAR DETAILS (1080×1080 PX JPEG)",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.secondary
                    )
                    if (!placeName.isNullOrBlank()) {
                        Text(
                            text = placeName,
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.testTag("caption_place_name_text")
                        )
                    } else if (!isRenderingMap) {
                        Text(
                            text = "Coordinates-only mode (Nominatim place name unavailable)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(
                        text = exifGpsData.formatCoordinates5Decimals(),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.testTag("caption_coordinates_text")
                    )
                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 4.dp),
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = if (exifGpsData.readFromOriginalFile) {
                                "Source: Unredacted Original File"
                            } else {
                                "Source: EXIF Stream"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = exifGpsData.mimeType ?: "image/jpeg",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Action Buttons: "Save map to gallery" and "Share"
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(
                    onClick = onSaveToGallery,
                    enabled = mapBitmap != null && !isRenderingMap && !isSavingToGallery,
                    modifier = Modifier
                        .weight(1f)
                        .height(50.dp)
                        .testTag("save_map_button"),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    if (isSavingToGallery) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                    } else {
                        Icon(
                            imageVector = Icons.Default.Download,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Text(
                        text = stringResource(R.string.save_map_to_gallery),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                OutlinedButton(
                    onClick = onShareMap,
                    enabled = mapBitmap != null && !isRenderingMap,
                    modifier = Modifier
                        .weight(0.75f)
                        .height(50.dp)
                        .testTag("share_map_button"),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Share,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = stringResource(R.string.share_map))
                }
            }
        }
    }
}

@Composable
private fun EmulatorTestSamplesCard(
    isSeeding: Boolean,
    onSeedAndLoad: (SamplePhotoPreset) -> Unit,
    onSeedToGalleryOnly: (SamplePhotoPreset) -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("emulator_samples_card"),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = stringResource(R.string.seed_sample_title),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = stringResource(R.string.seed_sample_description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            SamplePhotoPreset.entries.forEach { preset ->
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = preset.title,
                                style = MaterialTheme.typography.titleSmall
                            )
                            Text(
                                text = preset.subtitle,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            OutlinedButton(
                                onClick = { onSeedToGalleryOnly(preset) },
                                enabled = !isSeeding,
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                                modifier = Modifier.testTag("seed_gallery_${preset.name.lowercase()}")
                            ) {
                                Text("Add to Gallery", style = MaterialTheme.typography.bodySmall)
                            }
                            Button(
                                onClick = { onSeedAndLoad(preset) },
                                enabled = !isSeeding,
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                modifier = Modifier.testTag("try_sample_${preset.name.lowercase()}")
                            ) {
                                Text("Try Now", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FullscreenImageDialog(
    bitmap: Bitmap,
    title: String,
    onDismiss: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        BackHandler(onBack = onDismiss)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.92f))
                .padding(16.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = stringResource(R.string.close_fullscreen),
                            tint = Color.White
                        )
                    }
                }

                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = title,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(16.dp))
                )
            }
        }
    }
}
