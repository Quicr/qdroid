package com.cisco.quadroid

import android.Manifest
import android.content.res.Configuration
import android.os.Bundle
import android.view.Surface
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.camera.core.Preview
import androidx.camera.core.CameraSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cisco.quadroid.mediacodec.ParticipantStream
import com.cisco.quadroid.ui.components.NativeVideoRenderer
import com.cisco.quadroid.ui.theme.QuadroidTheme
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
        setContent {
            QuadroidTheme {
                MainScreen(viewModel = viewModel)
            }
        }
    }
}

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun MainScreen(viewModel: MainViewModel) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val remoteParticipants by viewModel.remoteParticipants.collectAsStateWithLifecycle()
    val isMicEnabled by viewModel.isMicEnabled.collectAsStateWithLifecycle()
    val isVideoEnabled by viewModel.isVideoEnabled.collectAsStateWithLifecycle()

    val lifecycleOwner = LocalLifecycleOwner.current

    val permissionsState = rememberMultiplePermissionsState(
        permissions = listOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        )
    )

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            when (uiState) {
                is CallUiState.Lobby -> {
                    LobbyScreen(
                        onStartCall = {
                            if (permissionsState.allPermissionsGranted) {
                                viewModel.startCall(lifecycleOwner)
                            } else {
                                permissionsState.launchMultiplePermissionRequest()
                            }
                        },
                        onNavigateToSettings = { viewModel.navigateToSettings() }
                    )
                }
                is CallUiState.InCall -> {
                    InCallScreen(
                        remoteParticipants = remoteParticipants,
                        isMicEnabled = isMicEnabled,
                        isVideoEnabled = isVideoEnabled,
                        onRemoteSurfaceReady = { id, surface -> viewModel.onRemoteSurfaceReady(id, surface) },
                        onSimulateParticipant = { viewModel.simulateParticipant() },
                        onToggleVideo = { viewModel.toggleVideo(

                        ) },
                        onToggleAudio = { viewModel.toggleAudio() },
                        onEndCall = { viewModel.endCall() }
                    )
                }
                is CallUiState.Settings -> {
                    SettingsScreen(
                        onSave = { viewModel.saveSettings() }
                    )
                }
            }
        }
    }
}

@Composable
fun LobbyScreen(
    onStartCall: () -> Unit,
    onNavigateToSettings: () -> Unit
) {
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
                tint = MaterialTheme.colorScheme.primary
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
            
            Spacer(modifier = Modifier.height(80.dp))

            GlassCard(
                onClick = onStartCall,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp)
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Videocam,
                            contentDescription = null,
                            modifier = Modifier.size(32.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        Text(
                            text = "Join Meeting",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun InCallScreen(
    remoteParticipants: List<ParticipantStream>,
    isMicEnabled: Boolean,
    isVideoEnabled: Boolean,
    onRemoteSurfaceReady: (String, Surface) -> Unit,
    onSimulateParticipant: () -> Unit,
    onToggleVideo: () -> Unit,
    onToggleAudio: () -> Unit,
    onEndCall: () -> Unit
) {
    var showControls by remember { mutableStateOf(true) }
    
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val lifecycleOwner = LocalLifecycleOwner.current

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
        // 1. Video Participants
        Box(modifier = Modifier.fillMaxSize()) {
            if (remoteParticipants.isEmpty()) {
                if (isVideoEnabled) {
                    CameraPreview(lifecycleOwner = lifecycleOwner, modifier = Modifier.fillMaxSize())
                } else {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.VideocamOff, contentDescription = null, tint = Color.White.copy(alpha = 0.5f), modifier = Modifier.size(64.dp))
                    }
                }
            } else {
                AdaptiveNativeGrid(
                    participants = remoteParticipants,
                    onSurfaceReady = onRemoteSurfaceReady,
                    isLandscape = isLandscape
                )
            }
        }

        // 2. Local PIP
        if (remoteParticipants.isNotEmpty()) {
            AnimatedVisibility(
                visible = showControls,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(16.dp)
                    .zIndex(1f),
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                Box(
                    modifier = Modifier
                        .size(if (isLandscape) 140.dp else 100.dp, if (isLandscape) 90.dp else 150.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .border(1.dp, Color.White.copy(alpha = 0.4f), RoundedCornerShape(16.dp))
                        .background(Color.Black.copy(alpha = 0.2f))
                ) {
                    if (isVideoEnabled) {
                        CameraPreview(lifecycleOwner = lifecycleOwner, modifier = Modifier.fillMaxSize())
                    } else {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.VideocamOff, contentDescription = null, tint = Color.White.copy(alpha = 0.5f))
                        }
                    }
                }
            }
        }

        // 3. Floating Control Bar
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
                        onClick = onSimulateParticipant,
                        colors = IconButtonDefaults.iconButtonColors(contentColor = Color.White)
                    ) {
                        Icon(Icons.Default.PersonAdd, contentDescription = "Add Participant")
                    }

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
fun CameraPreview(
    lifecycleOwner: LifecycleOwner,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val previewView = remember { PreviewView(context) }

    LaunchedEffect(lifecycleOwner) {
        val cameraProvider = ProcessCameraProvider.getInstance(context).get()
        val preview = Preview.Builder().build().also {
            it.setSurfaceProvider(previewView.surfaceProvider)
        }
        val cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA

        try {
            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(
                lifecycleOwner, cameraSelector, preview
            )
        } catch(exc: Exception) {
            // Log error
        }
    }

    AndroidView({ previewView }, modifier = modifier)
}

@Composable
fun AdaptiveNativeGrid(
    participants: List<ParticipantStream>,
    onSurfaceReady: (String, Surface) -> Unit,
    isLandscape: Boolean
) {
    if (isLandscape) {
        Row(modifier = Modifier.fillMaxSize()) {
            participants.forEach { participant ->
                key(participant.id) {
                    Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                        NativeVideoRenderer(
                            onSurfaceCreated = { onSurfaceReady(participant.id, it) },
                            onSurfaceDestroyed = { },
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
            }
        }
    } else {
        Column(modifier = Modifier.fillMaxSize()) {
            when (participants.size) {
                1 -> {
                    NativeVideoRenderer(
                        onSurfaceCreated = { onSurfaceReady(participants[0].id, it) },
                        onSurfaceDestroyed = { },
                        modifier = Modifier.fillMaxSize()
                    )
                }
                2 -> {
                    participants.forEach { participant ->
                        key(participant.id) {
                            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                                NativeVideoRenderer(
                                    onSurfaceCreated = { onSurfaceReady(participant.id, it) },
                                    onSurfaceDestroyed = { },
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                        }
                    }
                }
                else -> {
                    Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        NativeVideoRenderer(
                            onSurfaceCreated = { onSurfaceReady(participants[0].id, it) },
                            onSurfaceDestroyed = { },
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                    Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                            NativeVideoRenderer(
                                onSurfaceCreated = { onSurfaceReady(participants[1].id, it) },
                                onSurfaceDestroyed = { },
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                        Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                            NativeVideoRenderer(
                                onSurfaceCreated = { onSurfaceReady(participants[2].id, it) },
                                onSurfaceDestroyed = { },
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SettingsScreen(onSave: () -> Unit) {
    var setting1 by remember { mutableStateOf("") }
    var setting2 by remember { mutableStateOf("") }
    var radioOption by remember { mutableStateOf("Peer-to-Peer") }
    var toggleState by remember { mutableStateOf(true) }
    var expanded by remember { mutableStateOf(false) }
    val qualityOptions = listOf("Standard", "High Definition", "Ultra HD")
    var selectedQuality by remember { mutableStateOf(qualityOptions[0]) }

    Scaffold(
        topBar = {
            @OptIn(ExperimentalMaterial3Api::class)
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
            SettingsCardSection(header = "Settings 1") {
                OutlinedTextField(
                    value = setting1,
                    onValueChange = { setting1 = it },
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    label = { Text("User Name") }
                )
            }

            SettingsCardSection(header = "Settings 2") {
                OutlinedTextField(
                    value = setting2,
                    onValueChange = { setting2 = it },
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    label = { Text("Meeting ID") }
                )
            }

            SettingsCardSection(header = "Connection") {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = radioOption == "Peer-to-Peer", onClick = { radioOption = "Peer-to-Peer" })
                        Text("Peer-to-Peer")
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = radioOption == "Server-Relay", onClick = { radioOption = "Server-Relay" })
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
                    Switch(checked = toggleState, onCheckedChange = { toggleState = it })
                }
            }

            SettingsCardSection(header = "Quality") {
                Box(modifier = Modifier.padding(16.dp)) {
                    @OptIn(ExperimentalMaterial3Api::class)
                    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = !expanded }) {
                        OutlinedTextField(
                            value = selectedQuality,
                            onValueChange = {},
                            readOnly = true,
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                            modifier = Modifier.menuAnchor().fillMaxWidth()
                        )
                        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                            qualityOptions.forEach { option ->
                                DropdownMenuItem(text = { Text(option) }, onClick = { selectedQuality = option; expanded = false })
                            }
                        }
                    }
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

@Composable
fun GlassCard(onClick: () -> Unit, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Card(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(32.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
    ) {
        Box(modifier = Modifier.fillMaxSize().border(1.dp, Color.White.copy(alpha = 0.2f), RoundedCornerShape(32.dp))) {
            content()
        }
    }
}
