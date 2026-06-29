// SPDX-FileCopyrightText: Copyright (c) 2025 Cisco Systems
// SPDX-License-Identifier: BSD-2-Clause

package com.cisco.quadroid

import android.Manifest
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Bundle
import android.util.Log
import android.view.Surface
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FlipCameraAndroid
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import coil.decode.SvgDecoder
import coil.request.ImageRequest
import com.cisco.quadroid.mediacodec.model.ParticipantStream
import com.cisco.quadroid.transport.MoqConnectionStatus
import com.cisco.quadroid.CameraSource
import com.meta.wearable.dat.core.types.PermissionStatus
import com.meta.wearable.dat.core.types.RegistrationState
import com.meta.wearable.dat.core.Wearables
import com.cisco.quadroid.ui.components.NativeVideoRenderer
import com.cisco.quadroid.ui.components.PreviewNativeVideoRenderer
import com.cisco.quadroid.ui.theme.QuadroidTheme
import com.cisco.quadroid.util.DeviceIdentifier
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import dagger.hilt.android.AndroidEntryPoint
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    private val datCameraPermissionLauncher = registerForActivityResult(
        Wearables.RequestPermissionContract()
    ) { result ->
        val granted = result.getOrNull() is PermissionStatus.Granted
        Log.d("MainActivity", "DAT camera permission result: granted=$granted")
        if (granted) {
            viewModel.startGlassesStream()
        }
    }

    private val bluetoothPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        Log.d("MainActivity", "BLUETOOTH_CONNECT granted=$granted")
        if (granted) {
            // Permission was just granted; kick the DAT SDK to re-check devices.
            viewModel.reinitializeWearables()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // BLUETOOTH_CONNECT must be granted before Wearables can enumerate paired devices.
        // Request it immediately so the SDK has it before the first registration check.
        if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            bluetoothPermissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
        }

        viewModel.setDatCameraPermissionLauncher { datCameraPermissionLauncher.launch(it) }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.registrationState.collect { state ->
                    if (state == RegistrationState.AVAILABLE) {
                        Log.d("MainActivity", "registrationState=AVAILABLE — auto-launching registration")
                        viewModel.launchGlassesRegistration(this@MainActivity)
                    }
                }
            }
        }

        // Initialize/Restore Device ID
        DeviceIdentifier.get(this)

        // Load relay URL from preferences
        viewModel.relay_url = com.cisco.quadroid.util.PreferencesManager.getRelayUrl(this)

        // 8.1 OnCreate - Connect to relay
        viewModel.connectToRelay()

        setContent {
            QuadroidTheme {
                MainScreen(viewModel = viewModel)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Ensure Device ID is initialized/restored
        DeviceIdentifier.get(this)
        
        // 8.3 Ensure connection is maintained on resume
        viewModel.connectToRelay()
    }

    override fun onPause() {
        super.onPause()
        // Save Device ID to persistent storage
        DeviceIdentifier.saveToPrefs(this)
    }

    override fun onDestroy() {
        // 8.2 OnDestroy - Disconnect from the relay
        viewModel.disconnectFromRelay()
        super.onDestroy()
    }
}

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun MainScreen(viewModel: MainViewModel) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val remoteParticipants by viewModel.remoteParticipants.collectAsStateWithLifecycle()

    // Debug logging for remote participants
    LaunchedEffect(remoteParticipants.size) {
        Log.i("MainActivity", "Remote participants changed: count=${remoteParticipants.size}, ids=${remoteParticipants.map { it.id }.joinToString()}")
    }
    val isMicEnabled by viewModel.isMicEnabled.collectAsStateWithLifecycle()
    val isVideoEnabled by viewModel.isVideoEnabled.collectAsStateWithLifecycle()
    val videoToggleCount by viewModel.videoToggleCount.collectAsStateWithLifecycle()
    val isFrontCamera by viewModel.isFrontCamera.collectAsStateWithLifecycle()
    val videoAspectRatio by viewModel.videoAspectRatio.collectAsStateWithLifecycle()
    val connectionStatus by viewModel.connectionStatus.collectAsStateWithLifecycle()
    val isCatalogReady by viewModel.isCatalogReady.collectAsStateWithLifecycle()
    val registrationState by viewModel.registrationState.collectAsStateWithLifecycle()
    val cameraSource by viewModel.cameraSource.collectAsStateWithLifecycle()

    val lifecycleOwner = LocalLifecycleOwner.current
    val context = LocalContext.current

    val permissionsState = rememberMultiplePermissionsState(
        permissions = listOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.BLUETOOTH_CONNECT,
        )
    )

    // Pending camera source selection waiting for permissions to be granted
    var pendingCameraSource by remember { mutableStateOf<CameraSource?>(null) }

    LaunchedEffect(permissionsState.allPermissionsGranted) {
        val pending = pendingCameraSource
        if (permissionsState.allPermissionsGranted && pending != null) {
            pendingCameraSource = null
            val rotation = context.display?.rotation ?: 0
            viewModel.startCall(lifecycleOwner, rotation, pending)
        }
    }

    var toastMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(connectionStatus) {
        toastMessage = "Relay Status: ${connectionStatus.name}"
        delay(3000)
        toastMessage = null
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            when (uiState) {
                is CallUiState.Lobby -> {
                    val activity = context as? androidx.activity.ComponentActivity
                    LobbyScreen(
                        connectionStatus = connectionStatus,
                        isCatalogReady = isCatalogReady,
                        registrationState = registrationState,
                        onStartCall = { source ->
                            if (permissionsState.allPermissionsGranted) {
                                val rotation = context.display?.rotation ?: 0
                                viewModel.startCall(lifecycleOwner, rotation, source)
                            } else {
                                pendingCameraSource = source
                                permissionsState.launchMultiplePermissionRequest()
                            }
                        },
                        onConnectGlasses = {
                            if (activity != null) viewModel.launchGlassesRegistration(activity)
                        },
                        onNavigateToSettings = { viewModel.navigateToSettings() }
                    )
                }
                is CallUiState.InCall -> {
                    InCallScreen(
                        viewModel = viewModel,
                        cameraSource = cameraSource,
                        connectionStatus = connectionStatus,
                        remoteParticipants = remoteParticipants,
                        isMicEnabled = isMicEnabled,
                        isVideoEnabled = isVideoEnabled,
                        videoToggleCount = videoToggleCount,
                        isFrontCamera = isFrontCamera,
                        localAspectRatio = videoAspectRatio,
                        onLocalPreviewSurfaceReady = { surface -> viewModel.onLocalPreviewSurfaceReady(surface) },
                        onToggleVideo = { viewModel.toggleVideo(lifecycleOwner) },
                        onSwitchCamera = { viewModel.switchCamera(lifecycleOwner) },
                        onToggleAudio = { viewModel.toggleAudio() },
                        onEndCall = { viewModel.endCall() }
                    )
                }
                is CallUiState.Settings -> {
                    val settingsContext = LocalContext.current
                    SettingsScreen(
                        viewModel = viewModel,
                        relayUrl = viewModel.relay_url,
                        onRelayUrlChange = {
                            viewModel.relay_url = it
                            com.cisco.quadroid.util.PreferencesManager.setRelayUrl(settingsContext, it)
                        },
                        onSave = { viewModel.saveSettings() }
                    )
                }
            }

            // 3. Connection Toast (Transparent Glass UX) - Bottom Center
            AnimatedVisibility(
                visible = toastMessage != null,
                enter = fadeIn() + slideInVertically(initialOffsetY = { it }),
                exit = fadeOut() + slideOutVertically(targetOffsetY = { it }),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 100.dp)
                    .zIndex(10f)
            ) {
                toastMessage?.let { msg ->
                    GlassToast(message = msg)
                }
            }
        }
    }
}

@Composable
fun GlassToast(message: String) {
    // 2. Add more blur effect simulation for GlassToast
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(24.dp))
            .background(Color.White.copy(alpha = 0.05f))
            .border(0.5.dp, Color.White.copy(alpha = 0.2f), RoundedCornerShape(24.dp))
    ) {
        Box(
            modifier = Modifier
                .background(Color.White.copy(alpha = 0.25f)) // Increased opacity for better frost effect
                .padding(horizontal = 24.dp, vertical = 12.dp)
        ) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.9f),
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
fun LiquidGlassButton(
    onClick: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition(label = "liquid_transition")
    val phase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(4000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "phase"
    )

    // Pulse animation for the border thickness and glow
    val pulse by infiniteTransition.animateFloat(
        initialValue = 0.6f,
        targetValue = 1.2f,
        animationSpec = infiniteRepeatable(
            animation = tween(2500, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )

    // AI Mode Colors
    val aiColors = listOf(
        Color(0xFF4285F4), // Blue
        Color(0xFF9171E5), // Purple
        Color(0xFFF24E1E), // Red
        Color(0xFFF9D523), // Yellow
        Color(0xFF4285F4)  // Blue again
    )

    // Refined minimalistic glass palette: "Arctic Frost"
    val backgroundBrush = if (enabled) {
        Brush.linearGradient(
            colors = listOf(
                Color.White.copy(alpha = 0.12f + (0.08f * phase)),
                Color.White.copy(alpha = 0.04f + (0.02f * (1f - phase)))
            )
        )
    } else {
        Brush.linearGradient(
            colors = listOf(
                Color.Gray.copy(alpha = 0.1f),
                Color.Gray.copy(alpha = 0.05f)
            )
        )
    }

    val contentAlpha by animateFloatAsState(if (enabled) 1f else 0.4f, label = "contentAlpha")

    Box(
        modifier = modifier
            .height(64.dp)
            .clip(RoundedCornerShape(32.dp))
            .background(backgroundBrush)
            .then(
                if (enabled) {
                    Modifier.drawWithContent {
                        drawContent()
                        // Pulsing stroke width - Doubled base thickness
                        val strokeWidth = (3.0.dp * pulse).toPx()
                        val brush = Brush.sweepGradient(
                            colors = aiColors.map { it.copy(alpha = pulse.coerceIn(0.5f, 1f)) },
                            center = Offset(size.width / 2, size.height / 2)
                        )
                        // Removed rotation to keep border from moving around
                        drawOutline(
                            outline = Outline.Rounded(
                                RoundRect(
                                    rect = Rect(Offset.Zero, size),
                                    cornerRadius = CornerRadius(32.dp.toPx())
                                )
                            ),
                            brush = brush,
                            style = Stroke(width = strokeWidth)
                        )
                    }
                } else {
                    Modifier.border(
                        width = 0.5.dp,
                        color = Color.White.copy(alpha = 0.1f),
                        shape = RoundedCornerShape(32.dp)
                    )
                }
            )
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 24.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface.copy(alpha = contentAlpha)) {
                content()
            }
        }
    }
}

@Composable
fun GlassesRow(
    registrationState: RegistrationState,
    onConnectGlasses: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Color.White.copy(alpha = 0.06f))
            .border(0.5.dp, Color.White.copy(alpha = 0.15f), RoundedCornerShape(16.dp))
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column {
            Text(
                text = "Ray-Ban Glasses",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = when (registrationState) {
                    RegistrationState.UNAVAILABLE   -> "Meta AI app not installed"
                    RegistrationState.AVAILABLE     -> "Tap to register"
                    RegistrationState.REGISTERING   -> "Registering…"
                    RegistrationState.REGISTERED    -> "Ready"
                    RegistrationState.UNREGISTERING -> "Unregistering…"
                },
                style = MaterialTheme.typography.labelSmall,
                color = when (registrationState) {
                    RegistrationState.REGISTERED  -> Color(0xFF4CAF50)
                    RegistrationState.UNAVAILABLE -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                    else                          -> Color(0xFFFFC107)
                },
            )
        }

        when (registrationState) {
            RegistrationState.AVAILABLE ->
                Button(onClick = onConnectGlasses) { Text("Connect") }
            RegistrationState.REGISTERING, RegistrationState.UNREGISTERING ->
                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
            else -> {}
        }
    }
}

@Composable
fun AutoSlidingBanner(
    modifier: Modifier = Modifier,
    slideDurationMillis: Long = 3000
) {
    val context = LocalContext.current

    // Determine if Cloudflare should be shown
    val cloudflareOverride = com.cisco.quadroid.util.PreferencesManager.getCloudflareOverride(context)
    val showCloudflare = com.cisco.quadroid.BuildConfig.ENABLE_CLOUDFLARE || cloudflareOverride

    val logoFiles = remember(showCloudflare) {
        if (showCloudflare) {
            listOf(
                "cisco.svg",
                "cloudflare.svg"
            )
        } else {
            listOf(
                "openmoq.svg",
                "cdn77.svg",
                "cisco.svg",
                "download.svg",
                "synamedia.svg"
            )
        }
    }

    var currentIndex by remember { mutableStateOf(0) }

    // Auto-slide effect
    LaunchedEffect(Unit) {
        while (true) {
            delay(slideDurationMillis)
            currentIndex = (currentIndex + 1) % logoFiles.size
        }
    }

    Box(
        modifier = modifier
            .height(60.dp)
            .fillMaxWidth(),
        contentAlignment = Alignment.Center
    ) {
        logoFiles.forEachIndexed { index, logoFile ->
            val visible = index == currentIndex
            androidx.compose.animation.AnimatedVisibility(
                visible = visible,
                enter = fadeIn(animationSpec = tween(800)),
                exit = fadeOut(animationSpec = tween(800))
            ) {
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data("file:///android_asset/logos/$logoFile")
                        .decoderFactory(SvgDecoder.Factory())
                        .build(),
                    contentDescription = null,
                    modifier = Modifier
                        .height(50.dp)
                        .padding(horizontal = 32.dp)
                        .drawBehind {
                            drawIntoCanvas { canvas ->
                                val paint = Paint()
                                val frameworkPaint = paint.asFrameworkPaint()
                                // The blur radius for the shadow
                                frameworkPaint.maskFilter = android.graphics.BlurMaskFilter(
                                    20f, // Blur amount
                                    android.graphics.BlurMaskFilter.Blur.NORMAL
                                )
                                frameworkPaint.color = Color.Black.copy(alpha = 0.1f).toArgb()

                                // Draw a rounded rect shadow that is slightly smaller than the container
                                canvas.drawRoundRect(
                                    left = 10f,
                                    top = 10f,
                                    right = size.width - 10f,
                                    bottom = size.height - 10f,
                                    radiusX = 8.dp.toPx(),
                                    radiusY = 8.dp.toPx(),
                                    paint = paint
                                )
                            }
                        }
                        .padding(8.dp)
                )
            }
        }
    }
}

@Composable
fun LobbyScreen(
    connectionStatus: MoqConnectionStatus,
    isCatalogReady: Boolean,
    registrationState: RegistrationState,
    onStartCall: (CameraSource) -> Unit,
    onConnectGlasses: () -> Unit,
    onNavigateToSettings: () -> Unit
) {
    var showCameraSourceDialog by remember { mutableStateOf(false) }

    if (showCameraSourceDialog) {
        AlertDialog(
            onDismissRequest = { showCameraSourceDialog = false },
            title = { Text("Camera Source") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Choose which camera to use for this call.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    showCameraSourceDialog = false
                    onStartCall(CameraSource.PHONE)
                }) { Text("Phone Camera") }
            },
            dismissButton = {
                val glassesEnabled = registrationState == RegistrationState.REGISTERED
                TextButton(
                    onClick = {
                        showCameraSourceDialog = false
                        onStartCall(CameraSource.GLASSES)
                    },
                    enabled = glassesEnabled,
                ) {
                    Text(
                        if (glassesEnabled) "Glasses Camera"
                        else "Glasses Camera (not registered)"
                    )
                }
            }
        )
    }
    val isConnected = connectionStatus == MoqConnectionStatus.CONNECTED
    val isConnecting = connectionStatus == MoqConnectionStatus.CONNECTING || connectionStatus == MoqConnectionStatus.IDLE
    val canJoinMeeting = isConnected && isCatalogReady

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.2f),
                        MaterialTheme.colorScheme.background
                    )
                )
            )
    ) {
        IconButton(
            onClick = onNavigateToSettings,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(16.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Settings,
                contentDescription = "Settings",
                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
            )
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .padding(32.dp)
                .align(Alignment.Center)
        ) {
            Text(
                text = "QDroid",
                style = MaterialTheme.typography.displayMedium.copy(
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = (-1).sp
                ),
                color = MaterialTheme.colorScheme.onBackground
            )

            Text(
                text = "Media over QUIC Video",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f)
            )

            Spacer(modifier = Modifier.height(100.dp))

            // 1, 4 & 5. Liquid Glass Button: Material You, Minimalistic
            LiquidGlassButton(
                onClick = { showCameraSourceDialog = true },
                enabled = canJoinMeeting,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = Icons.Default.Videocam,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = "Join Meeting",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }

            // Revolving progress bar below button
            if (!canJoinMeeting) {
                Spacer(modifier = Modifier.height(32.dp))
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(32.dp),
                        strokeWidth = 3.dp,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = when {
                            !isConnected && isConnecting -> "Connecting to MoQ Relay..."
                            !isConnected -> "Connection failed. Retrying..."
                            isConnected && !isCatalogReady -> "Loading catalog..."
                            else -> "Preparing..."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Glasses row
            GlassesRow(
                registrationState = registrationState,
                onConnectGlasses = onConnectGlasses,
            )
        }

        // Auto-sliding logo banner at bottom center
        AutoSlidingBanner(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 16.dp)
        )
    }
}

@Composable
fun ConnectionStatusIcon(status: MoqConnectionStatus, modifier: Modifier = Modifier) {
    val color by animateColorAsState(
        targetValue = when (status) {
            MoqConnectionStatus.CONNECTED -> Color(0xFF4CAF50)
            MoqConnectionStatus.CONNECTING -> Color(0xFFFFC107)
            MoqConnectionStatus.ERROR -> Color(0xFFF44336)
            else -> Color.Gray
        },
        label = "connection_color"
    )

    val icon = when (status) {
        MoqConnectionStatus.CONNECTED -> Icons.Default.CloudDone
        MoqConnectionStatus.CONNECTING -> Icons.Default.Sync
        MoqConnectionStatus.ERROR -> Icons.Default.CloudOff
        else -> Icons.Default.CloudQueue
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Color.White.copy(alpha = 0.15f))
            .border(0.5.dp, Color.White.copy(alpha = 0.2f), RoundedCornerShape(16.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(14.dp)
            )
            Text(
                text = status.name,
                style = MaterialTheme.typography.labelSmall,
                color = color.copy(alpha = 0.9f),
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = 0.5.sp
            )
        }
    }
}

@Composable
fun InCallScreen(
    viewModel: MainViewModel,
    cameraSource: CameraSource,
    connectionStatus: MoqConnectionStatus,
    remoteParticipants: List<ParticipantStream>,
    isMicEnabled: Boolean,
    isVideoEnabled: Boolean,
    videoToggleCount: Int,
    isFrontCamera: Boolean,
    localAspectRatio: Float,
    onLocalPreviewSurfaceReady: (Surface) -> Unit,
    onToggleVideo: () -> Unit,
    onSwitchCamera: () -> Unit,
    onToggleAudio: () -> Unit,
    onEndCall: () -> Unit
) {
    var showControls by remember { mutableStateOf(true) }
    
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    // Debug logging for layout decisions
    LaunchedEffect(remoteParticipants.size) {
        Log.i("InCallScreen", "Remote participants: ${remoteParticipants.size}, showing ${if (remoteParticipants.isEmpty()) "local preview fullscreen" else "grid + PIP"}")
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) {
                showControls = !showControls
            }
    ) {
        // Video Participants Grid (Layer 0)
        Box(modifier = Modifier.fillMaxSize()) {
            if (remoteParticipants.isEmpty()) {
                Log.d("InCallScreen", "Rendering local preview fullscreen (no remote participants)")
                if (isVideoEnabled) {
                    if (cameraSource == CameraSource.GLASSES) {
                        GlassesPreviewRenderer(
                            viewModel = viewModel,
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        key(videoToggleCount) {
                            PreviewNativeVideoRenderer(
                                onSurfaceCreated = onLocalPreviewSurfaceReady,
                                onSurfaceDestroyed = { },
                                modifier = Modifier.fillMaxSize(),
                                mirrorHorizontal = false,
                                aspectRatio = localAspectRatio
                            )
                        }
                    }
                } else {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.VideocamOff, contentDescription = null, tint = Color.White.copy(alpha = 0.3f), modifier = Modifier.size(64.dp))
                    }
                }
            } else {
                Log.d("InCallScreen", "Rendering AdaptiveNativeGrid with ${remoteParticipants.size} participants")
                AdaptiveNativeGrid(
                    viewModel = viewModel,
                    participants = remoteParticipants,
                    isLandscape = isLandscape
                )
            }
        }

        // 2. Local PIP (Layer 1) - Higher zIndex and Rounded Clipping
        if (remoteParticipants.isNotEmpty()) {
            AnimatedVisibility(
                visible = showControls,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(16.dp)
                    .zIndex(5f),
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                Box(
                    modifier = Modifier
                        .size(
                            if (isLandscape) 160.dp else 120.dp,
                            if (isLandscape) 90.dp else 180.dp
                        )
                        .clip(RoundedCornerShape(24.dp))
                        .border(1.dp, Color.White.copy(alpha = 0.4f), RoundedCornerShape(24.dp))
                        .background(Color.Black.copy(alpha = 0.2f))
                ) {
                    if (isVideoEnabled) {
                        if (cameraSource == CameraSource.GLASSES) {
                            GlassesPreviewRenderer(
                                viewModel = viewModel,
                                modifier = Modifier.fillMaxSize(),
                            )
                        } else {
                            key(videoToggleCount) {
                                PreviewNativeVideoRenderer(
                                    onSurfaceCreated = onLocalPreviewSurfaceReady,
                                    onSurfaceDestroyed = { },
                                    modifier = Modifier.fillMaxSize(),
                                    mirrorHorizontal = false,
                                    aspectRatio = localAspectRatio
                                )
                            }
                        }
                    } else {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.VideocamOff, contentDescription = null, tint = Color.White.copy(alpha = 0.5f))
                        }
                    }
                }
            }
        }

        // 6. Connection Status (Layer 2) - Topmost Z-Index
        ConnectionStatusIcon(
            status = connectionStatus,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(16.dp)
                .zIndex(10f)
        )

        // Floating Control Bar
        AnimatedVisibility(
            visible = true,
            enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .zIndex(2f)
        ) {
            Surface(
                modifier = Modifier
                    .padding(bottom = 48.dp)
                    .clip(RoundedCornerShape(40.dp))
                    .border(0.5.dp, Color.White.copy(alpha = 0.3f), RoundedCornerShape(40.dp)),
                color = Color.Black.copy(alpha = 0.7f),
                tonalElevation = 16.dp
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(20.dp)
                ) {
                    IconButton(
                        onClick = onToggleAudio,
                        colors = IconButtonDefaults.iconButtonColors(
                            containerColor = if (isMicEnabled) Color.White.copy(alpha = 0.1f) else MaterialTheme.colorScheme.error.copy(alpha = 0.8f),
                            contentColor = Color.White
                        )
                    ) {
                        Icon(imageVector = if (isMicEnabled) Icons.Default.Mic else Icons.Default.MicOff, contentDescription = null)
                    }

                    IconButton(
                        onClick = onToggleVideo,
                        colors = IconButtonDefaults.iconButtonColors(
                            containerColor = if (isVideoEnabled) Color.White.copy(alpha = 0.1f) else MaterialTheme.colorScheme.error.copy(alpha = 0.8f),
                            contentColor = Color.White
                        )
                    ) {
                        Icon(imageVector = if (isVideoEnabled) Icons.Default.Videocam else Icons.Default.VideocamOff, contentDescription = null)
                    }

                    IconButton(
                        onClick = onSwitchCamera,
                        colors = IconButtonDefaults.iconButtonColors(contentColor = Color.White)
                    ) {
                        Icon(Icons.Default.FlipCameraAndroid, contentDescription = "Switch Camera")
                    }

                    IconButton(
                        onClick = onEndCall,
                        colors = IconButtonDefaults.iconButtonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = Color.White
                        ),
                        modifier = Modifier.size(56.dp)
                    ) {
                        Icon(imageVector = Icons.Default.CallEnd, contentDescription = null, modifier = Modifier.size(28.dp))
                    }
                }
            }
        }
    }
}

/**
 * Renders frames from the glasses camera using the same VideoSurfaceView decoder path
 * as remote participants. Registers/unregisters the frame listener with the ViewModel.
 */
@Composable
fun GlassesPreviewRenderer(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier,
) {
    val surfaceViewRef = remember { androidx.compose.runtime.mutableStateOf<com.cisco.quadroid.ui.components.VideoSurfaceView?>(null) }

    DisposableEffect(Unit) {
        viewModel.setGlassesPreviewListener { data, pts ->
            surfaceViewRef.value?.feedFrame(data, pts)
        }
        onDispose {
            viewModel.clearGlassesPreviewListener()
            surfaceViewRef.value = null
        }
    }

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        AndroidView(
            factory = { context ->
                val layout = com.cisco.quadroid.ui.components.AspectRatioFrameLayout(context).apply {
                    scaleType = com.cisco.quadroid.ui.components.AspectRatioFrameLayout.ScaleType.FIT
                }
                val view = com.cisco.quadroid.ui.components.VideoSurfaceView(context).apply {
                    rotationAngle = 0f
                    mirrorHorizontal = false
                    callback = object : com.cisco.quadroid.ui.components.VideoSurfaceView.Callback {
                        override fun onSurfaceCreated(surface: Surface) {}
                        override fun onSurfaceDestroyed() { surfaceViewRef.value = null }
                        override fun onVideoSizeChanged(width: Int, height: Int, rotation: Float) {
                            val ratio = width.toFloat() / height.toFloat()
                            layout.setAspectRatio(ratio)
                        }
                    }
                }
                surfaceViewRef.value = view
                layout.addView(
                    view,
                    android.widget.FrameLayout.LayoutParams(
                        android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                        android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                        android.view.Gravity.CENTER
                    )
                )
                layout
            },
            modifier = Modifier.fillMaxSize()
        )
    }
}

@Composable
fun AdaptiveNativeGrid(
    viewModel: MainViewModel,
    participants: List<ParticipantStream>,
    isLandscape: Boolean
) {
    Log.d("AdaptiveNativeGrid", "Rendering grid with ${participants.size} participants")
    if (isLandscape) {
        Row(modifier = Modifier.fillMaxSize()) {
            participants.forEach { participant ->
                key(participant.id) {
                    Box(modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(), contentAlignment = Alignment.Center) {
                        NativeVideoRenderer(
                            trackKey = participant.id,
                            viewModel = viewModel,
                            modifier = Modifier.fillMaxSize(),
                            mirrorHorizontal = false // Explicitly disabled mirroring
                        )
                    }
                }
            }
        }
    } else {
        Column(modifier = Modifier.fillMaxSize()) {
            when (participants.size) {
                1 -> {
                    key(participants[0].id) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            NativeVideoRenderer(
                                trackKey = participants[0].id,
                                viewModel = viewModel,
                                modifier = Modifier.fillMaxSize(),
                                mirrorHorizontal = false
                            )
                        }
                    }
                }
                2 -> {
                    participants.forEach { participant ->
                        key(participant.id) {
                            Box(modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(), contentAlignment = Alignment.Center) {
                                NativeVideoRenderer(
                                    trackKey = participant.id,
                                    viewModel = viewModel,
                                    modifier = Modifier.fillMaxSize(),
                                    mirrorHorizontal = false
                                )
                            }
                        }
                    }
                }
                else -> {
                    Box(modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(), contentAlignment = Alignment.Center) {
                        key(participants[0].id) {
                            NativeVideoRenderer(
                                trackKey = participants[0].id,
                                viewModel = viewModel,
                                modifier = Modifier.fillMaxSize(),
                                mirrorHorizontal = false
                            )
                        }
                    }
                    Row(modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()) {
                        Box(modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(), contentAlignment = Alignment.Center) {
                            key(participants[1].id) {
                                NativeVideoRenderer(
                                    trackKey = participants[1].id,
                                    viewModel = viewModel,
                                    modifier = Modifier.fillMaxSize(),
                                    mirrorHorizontal = false
                                )
                            }
                        }
                        Box(modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(), contentAlignment = Alignment.Center) {
                            key(participants[2].id) {
                                NativeVideoRenderer(
                                    trackKey = participants[2].id,
                                    viewModel = viewModel,
                                    modifier = Modifier.fillMaxSize(),
                                    mirrorHorizontal = false
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// Data class for relay URL sections
data class RelaySection(
    val title: String,
    val urls: List<String>
)

@Composable
fun ExpandableRelaySection(
    section: RelaySection,
    selectedUrl: String,
    onUrlSelected: (String) -> Unit
) {
    // Expand section if it contains the selected URL
    val containsSelectedUrl = section.urls.contains(selectedUrl)
    var expanded by remember(containsSelectedUrl) { mutableStateOf(containsSelectedUrl) }

    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        // Section header
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded },
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
            shape = RoundedCornerShape(12.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = section.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (expanded) "Collapse" else "Expand",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        // URLs list (shown when expanded)
        AnimatedVisibility(visible = expanded) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 8.dp, top = 4.dp, bottom = 4.dp)
            ) {
                section.urls.forEach { url ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(
                                onClick = { onUrlSelected(url) },
                                indication = null,
                                interactionSource = remember { MutableInteractionSource() }
                            )
                            .padding(vertical = 8.dp, horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = selectedUrl == url,
                            onClick = { onUrlSelected(url) }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = url,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (selectedUrl == url) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                            fontWeight = if (selectedUrl == url) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ExpandableCustomUrlSection(
    customUrls: List<String>,
    selectedUrl: String,
    onUrlSelected: (String) -> Unit,
    onAddUrl: (String) -> Unit,
    onDeleteUrl: (String) -> Unit
) {
    val hasCustomUrls = customUrls.isNotEmpty()
    val containsSelectedUrl = customUrls.contains(selectedUrl)
    var expanded by remember(containsSelectedUrl) { mutableStateOf(containsSelectedUrl || hasCustomUrls) }
    var newUrlText by remember { mutableStateOf("") }

    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        // Section header
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded },
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
            shape = RoundedCornerShape(12.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Custom Relay URL",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (expanded) "Collapse" else "Expand",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        // Custom URLs list and add input (shown when expanded)
        AnimatedVisibility(visible = expanded) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 8.dp, top = 4.dp, end = 8.dp, bottom = 4.dp)
            ) {
                // Display existing custom URLs
                customUrls.forEach { url ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(
                                onClick = { onUrlSelected(url) },
                                indication = null,
                                interactionSource = remember { MutableInteractionSource() }
                            )
                            .padding(vertical = 8.dp, horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = selectedUrl == url,
                            onClick = { onUrlSelected(url) }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = url,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (selectedUrl == url) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                            fontWeight = if (selectedUrl == url) FontWeight.Bold else FontWeight.Normal,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(
                            onClick = { onDeleteUrl(url) },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = "Delete",
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }

                // Add new custom URL input
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = newUrlText,
                        onValueChange = { newUrlText = it },
                        modifier = Modifier.weight(1f),
                        label = { Text("Add Custom URL") },
                        placeholder = { Text("moq://...") },
                        singleLine = true
                    )
                    IconButton(
                        onClick = {
                            if (newUrlText.isNotBlank()) {
                                onAddUrl(newUrlText)
                                onUrlSelected(newUrlText)
                                newUrlText = ""
                            }
                        },
                        modifier = Modifier.size(48.dp),
                        colors = IconButtonDefaults.iconButtonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = "Add",
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun SettingsScreen(
    viewModel: MainViewModel,
    relayUrl: String,
    onRelayUrlChange: (String) -> Unit,
    onSave: () -> Unit
) {
    val context = LocalContext.current

    // Use local state to track the selected URL for immediate UI updates
    var selectedRelayUrl by remember { mutableStateOf(relayUrl) }

    // Load custom URLs from preferences
    var customUrls by remember { mutableStateOf(com.cisco.quadroid.util.PreferencesManager.getCustomRelayUrls(context)) }

    // Define relay sections
    val relaySections = listOf(
        RelaySection(
            title = "LAPS",
            urls = listOf(
                "moq://eng-1.us-west-2.m10x.org:33440",
                "moq://eng-3.us-west-2.m10x.org:33550",
                "moq://eng-3.us-west-2.m10x.org:33660",
                "moq://relay.us-west-2.m10x.org:33435",
                "moq://relay.us-east-2.m10x.org:33437",
                "moq://relay.eu-west-2.m10x.org:33437"
            )
        ),
        RelaySection(
            title = "NAB_CISCO",
            urls = listOf(
                "moq://lax1.cisco.moqx.akaleapi.net:9667/",
                "moq://lax1.cisco.moqx.akaleapi.net:9668/",
                "moq://lax2.cisco.moqx.akaleapi.net:9667/",
                "moq://lax2.cisco.moqx.akaleapi.net:9668/"
            )
        ),
        RelaySection(
            title = "DEV",
            urls = listOf("moq://suhas-build-vm.akaleapi.net:443/moq-relay")
        ),
        RelaySection(
            title = "CLOUDFLARE",
            urls = listOf("moq://cisco-nab.cloudflare.mediaoverquic.com")
        )
    )

    // Load VAD preference from SharedPreferences
    var vadEnabled by remember { mutableStateOf(com.cisco.quadroid.util.PreferencesManager.getVadEnabled(context)) }

    // Developer mode state
    var developerMode by remember { mutableStateOf(com.cisco.quadroid.util.PreferencesManager.getDeveloperMode(context)) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onSave) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Cloudflare override state (only relevant in developer mode)
            var cloudflareOverride by remember { mutableStateOf(com.cisco.quadroid.util.PreferencesManager.getCloudflareOverride(context)) }

            // Determine if Cloudflare should be shown
            val showCloudflare = com.cisco.quadroid.BuildConfig.ENABLE_CLOUDFLARE || cloudflareOverride

            // Filter relay sections based on Cloudflare flag
            val filteredRelaySections = relaySections.filter { section ->
                section.title != "CLOUDFLARE" || showCloudflare
            }

            SettingsCardSection(header = "Relay URL") {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Display filtered sections
                    filteredRelaySections.forEach { section ->
                        ExpandableRelaySection(
                            section = section,
                            selectedUrl = selectedRelayUrl,
                            onUrlSelected = { url ->
                                selectedRelayUrl = url
                                onRelayUrlChange(url)
                            }
                        )
                    }

                    // Custom URL Section
                    ExpandableCustomUrlSection(
                        customUrls = customUrls,
                        selectedUrl = selectedRelayUrl,
                        onUrlSelected = { url ->
                            selectedRelayUrl = url
                            onRelayUrlChange(url)
                        },
                        onAddUrl = { url ->
                            com.cisco.quadroid.util.PreferencesManager.addCustomRelayUrl(context, url)
                            customUrls = com.cisco.quadroid.util.PreferencesManager.getCustomRelayUrls(context)
                        },
                        onDeleteUrl = { url ->
                            com.cisco.quadroid.util.PreferencesManager.removeCustomRelayUrl(context, url)
                            customUrls = com.cisco.quadroid.util.PreferencesManager.getCustomRelayUrls(context)
                        }
                    )
                }
            }

            SettingsCardSection(header = "Preferences") {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Voice Activity Detection")
                        Switch(
                            checked = vadEnabled,
                            onCheckedChange = { enabled ->
                                vadEnabled = enabled
                                // Save to preferences
                                com.cisco.quadroid.util.PreferencesManager.setVadEnabled(context, enabled)
                                // Apply to audio manager
                                viewModel.setVadEnabled(enabled)
                            }
                        )
                    }

                    // Cloudflare toggle (only shown in developer mode)
                    if (developerMode) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp)
                                .padding(bottom = 16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Enable Cloudflare Relay")
                                Text(
                                    text = "Developer Option",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
                                )
                            }
                            Switch(
                                checked = cloudflareOverride,
                                onCheckedChange = { enabled ->
                                    cloudflareOverride = enabled
                                    com.cisco.quadroid.util.PreferencesManager.setCloudflareOverride(context, enabled)
                                }
                            )
                        }
                    }
                }
            }

            SettingsCardSection(header = "About") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .combinedClickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = { },
                                onLongClick = {
                                    developerMode = !developerMode
                                    com.cisco.quadroid.util.PreferencesManager.setDeveloperMode(context, developerMode)

                                    // When disabling dev mode, also disable Cloudflare override
                                    if (!developerMode) {
                                        cloudflareOverride = false
                                        com.cisco.quadroid.util.PreferencesManager.setCloudflareOverride(context, false)
                                    }
                                }
                            ),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Version",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                            fontFamily = androidx.compose.ui.text.font.FontFamily.SansSerif
                        )
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = com.cisco.quadroid.BuildConfig.VERSION_NAME,
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontSize = 14.sp
                                ),
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                                fontFamily = androidx.compose.ui.text.font.FontFamily.SansSerif
                            )
                            // Show DEV badge when developer mode is enabled
                            if (developerMode) {
                                Text(
                                    text = "DEV",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier
                                        .background(
                                            MaterialTheme.colorScheme.primary.copy(alpha = 0.1f),
                                            RoundedCornerShape(4.dp)
                                        )
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SettingsCardSection(header: String, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
    ) {
        Column {
            Text(header, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(16.dp), color = MaterialTheme.colorScheme.primary)
            content()
        }
    }
}
