package com.cisco.quadroid

import android.Manifest
import android.content.res.Configuration
import android.os.Bundle
import android.util.Log
import android.view.Surface
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.FlipCameraAndroid
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cisco.quadroid.mediacodec.ParticipantStream
import com.cisco.quadroid.transport.MoqConnectionStatus
import com.cisco.quadroid.ui.components.NativeVideoRenderer
import com.cisco.quadroid.ui.components.PreviewNativeVideoRenderer
import com.cisco.quadroid.ui.theme.QuadroidTheme
import com.cisco.quadroid.util.DeviceIdentifier
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Initialize/Restore Device ID
        DeviceIdentifier.get(this)

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
    val isMicEnabled by viewModel.isMicEnabled.collectAsStateWithLifecycle()
    val isVideoEnabled by viewModel.isVideoEnabled.collectAsStateWithLifecycle()
    val videoToggleCount by viewModel.videoToggleCount.collectAsStateWithLifecycle()
    val isFrontCamera by viewModel.isFrontCamera.collectAsStateWithLifecycle()
    val videoAspectRatio by viewModel.videoAspectRatio.collectAsStateWithLifecycle()
    val connectionStatus by viewModel.connectionStatus.collectAsStateWithLifecycle()

    val lifecycleOwner = LocalLifecycleOwner.current
    val context = LocalContext.current

    val permissionsState = rememberMultiplePermissionsState(
        permissions = listOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        )
    )

    // Flag to track if we should start the call once permissions are granted
    var startCallRequested by remember { mutableStateOf(false) }

    LaunchedEffect(permissionsState.allPermissionsGranted) {
        if (permissionsState.allPermissionsGranted && startCallRequested) {
            startCallRequested = false
            val rotation = context.display?.rotation ?: 0
            viewModel.startCall(lifecycleOwner, rotation)
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
                    LobbyScreen(
                        connectionStatus = connectionStatus,
                        onStartCall = {
                            if (permissionsState.allPermissionsGranted) {
                                val rotation = context.display?.rotation ?: 0
                                viewModel.startCall(lifecycleOwner, rotation)
                            } else {
                                startCallRequested = true
                                permissionsState.launchMultiplePermissionRequest()
                            }
                        },
                        onNavigateToSettings = { viewModel.navigateToSettings() }
                    )
                }
                is CallUiState.InCall -> {
                    InCallScreen(
                        viewModel = viewModel,
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
                    SettingsScreen(
                        relayUrl = viewModel.relay_url,
                        onRelayUrlChange = { viewModel.relay_url = it },
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
fun LobbyScreen(
    connectionStatus: MoqConnectionStatus,
    onStartCall: () -> Unit,
    onNavigateToSettings: () -> Unit
) {
    val isConnected = connectionStatus == MoqConnectionStatus.CONNECTED
    val isConnecting = connectionStatus == MoqConnectionStatus.CONNECTING || connectionStatus == MoqConnectionStatus.IDLE

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
                onClick = onStartCall,
                enabled = isConnected,
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
            if (!isConnected) {
                Spacer(modifier = Modifier.height(32.dp))
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(32.dp),
                        strokeWidth = 3.dp,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = if (isConnecting) "Connecting to MoQ Relay..." else "Connection failed. Retrying...",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    )
                }
            }
        }
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
                if (isVideoEnabled) {
                    key(videoToggleCount) {
                        PreviewNativeVideoRenderer(
                            onSurfaceCreated = onLocalPreviewSurfaceReady,
                            onSurfaceDestroyed = { },
                            modifier = Modifier.fillMaxSize(),
                            mirrorHorizontal = false, // Explicitly disabled mirroring
                            aspectRatio = localAspectRatio
                        )
                    }
                } else {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.VideocamOff, contentDescription = null, tint = Color.White.copy(alpha = 0.3f), modifier = Modifier.size(64.dp))
                    }
                }
            } else {
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
                        .size(if (isLandscape) 160.dp else 120.dp, if (isLandscape) 90.dp else 180.dp)
                        .clip(RoundedCornerShape(24.dp))
                        .border(1.dp, Color.White.copy(alpha = 0.4f), RoundedCornerShape(24.dp))
                        .background(Color.Black.copy(alpha = 0.2f))
                ) {
                    if (isVideoEnabled) {
                        key(videoToggleCount) {
                            PreviewNativeVideoRenderer(
                                onSurfaceCreated = onLocalPreviewSurfaceReady,
                                onSurfaceDestroyed = { },
                                modifier = Modifier.fillMaxSize(),
                                mirrorHorizontal = false, // Explicitly disabled mirroring
                                aspectRatio = localAspectRatio
                            )
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
            visible = showControls,
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
                    Box(modifier = Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
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
                            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
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
                    Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        key(participants[0].id) {
                            NativeVideoRenderer(
                                trackKey = participants[0].id,
                                viewModel = viewModel,
                                modifier = Modifier.fillMaxSize(),
                                mirrorHorizontal = false
                            )
                        }
                    }
                    Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        Box(modifier = Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                            key(participants[1].id) {
                                NativeVideoRenderer(
                                    trackKey = participants[1].id,
                                    viewModel = viewModel,
                                    modifier = Modifier.fillMaxSize(),
                                    mirrorHorizontal = false
                                )
                            }
                        }
                        Box(modifier = Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    relayUrl: String,
    onRelayUrlChange: (String) -> Unit,
    onSave: () -> Unit
) {
    val predefinedUrls = listOf(
        "moq://eng-1.us-west-2.m10x.org:33440",
        "moq://eng-3.us-west-2.m10x.org:33550",
        "moq://eng-3.us-west-2.m10x.org:33660",
        "moq://relay.us-west-2.m10x.org:33437"
    )
    
    var expanded by remember { mutableStateOf(false) }
    var isCustomUrl by remember { mutableStateOf(!predefinedUrls.contains(relayUrl)) }
    var customUrlText by remember { mutableStateOf(if (isCustomUrl) relayUrl else "") }

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
            SettingsCardSection(header = "Relay URL") {
                Column(modifier = Modifier.padding(16.dp)) {
                    ExposedDropdownMenuBox(
                        expanded = expanded,
                        onExpandedChange = { expanded = !expanded }
                    ) {
                        OutlinedTextField(
                            value = if (isCustomUrl) "Custom URL" else relayUrl,
                            onValueChange = {},
                            readOnly = true,
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                            modifier = Modifier.menuAnchor().fillMaxWidth(),
                            label = { Text("Select Relay") }
                        )
                        ExposedDropdownMenu(
                            expanded = expanded,
                            onDismissRequest = { expanded = false }
                        ) {
                            predefinedUrls.forEach { url ->
                                DropdownMenuItem(
                                    text = { Text(url) },
                                    onClick = {
                                        onRelayUrlChange(url)
                                        isCustomUrl = false
                                        expanded = false
                                    }
                                )
                            }
                            DropdownMenuItem(
                                text = { Text("Custom URL...") },
                                onClick = {
                                    isCustomUrl = true
                                    expanded = false
                                }
                            )
                        }
                    }

                    if (isCustomUrl) {
                        Spacer(modifier = Modifier.height(16.dp))
                        OutlinedTextField(
                            value = customUrlText,
                            onValueChange = {
                                customUrlText = it
                                onRelayUrlChange(it)
                            },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Enter Custom Relay URL") },
                            placeholder = { Text("moq://...") }
                        )
                    }
                }
            }

            SettingsCardSection(header = "Settings 2") {
                OutlinedTextField(
                    value = "",
                    onValueChange = { },
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    label = { Text("Meeting ID") }
                )
            }

            SettingsCardSection(header = "Connection") {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = true, onClick = { })
                        Text("Peer-to-Peer")
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = false, onClick = { })
                        Text("Server-Relay")
                    }
                }
            }

            SettingsCardSection(header = "Preferences") {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Noise Cancellation")
                    Switch(checked = true, onCheckedChange = { })
                }
            }

            Button(onClick = onSave, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                Text("Save")
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
