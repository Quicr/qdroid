package com.cisco.quadroid

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cisco.quadroid.ui.components.VideoRenderer
import com.cisco.quadroid.ui.theme.QuadroidTheme
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay
import org.webrtc.EglBase
import org.webrtc.VideoTrack

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

    override fun onStart() {
        super.onStart()
        viewModel.onStart()
    }

    override fun onStop() {
        super.onStop()
        viewModel.onStop()
    }
}

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun MainScreen(viewModel: MainViewModel) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val localVideoTrack by viewModel.localVideoTrack.collectAsStateWithLifecycle()
    val remoteVideoTracks by viewModel.remoteVideoTracks.collectAsStateWithLifecycle()

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> viewModel.onStart()
                Lifecycle.Event.ON_STOP -> viewModel.onStop()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

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
                                viewModel.startCall()
                            } else {
                                permissionsState.launchMultiplePermissionRequest()
                            }
                        },
                        onNavigateToSettings = { viewModel.navigateToSettings() }
                    )
                }
                is CallUiState.InCall -> {
                    InCallScreen(
                        localVideoTrack = localVideoTrack,
                        remoteVideoTracks = remoteVideoTracks,
                        eglBaseContext = viewModel.getEglBaseContext(),
                        onSimulateParticipant = { viewModel.simulateParticipant() },
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
    localVideoTrack: VideoTrack?,
    remoteVideoTracks: List<VideoTrack>,
    eglBaseContext: EglBase.Context,
    onSimulateParticipant: () -> Unit,
    onEndCall: () -> Unit
) {
    var isMicOn by remember { mutableStateOf(true) }
    var isVideoOn by remember { mutableStateOf(true) }
    var showLocalPip by remember { mutableStateOf(true) }

    LaunchedEffect(remoteVideoTracks.size) {
        if (remoteVideoTracks.isNotEmpty()) {
            delay(5000)
            showLocalPip = false
        } else {
            showLocalPip = true
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        // 1. Remote Grid (Bottom Layer)
        if (remoteVideoTracks.isNotEmpty()) {
            val columns = if (remoteVideoTracks.size > 1) 2 else 1
            LazyVerticalGrid(
                columns = GridCells.Fixed(columns),
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                items(remoteVideoTracks) { track ->
                    VideoRenderer(
                        videoTrack = track,
                        eglBaseContext = eglBaseContext,
                        modifier = Modifier.fillMaxHeight().aspectRatio(if (columns == 1) 0.7f else 1f)
                    )
                }
            }
        } else {
            localVideoTrack?.let {
                VideoRenderer(
                    videoTrack = it,
                    eglBaseContext = eglBaseContext,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        // 2. Local PIP (Top Layer)
        AnimatedVisibility(
            visible = showLocalPip && remoteVideoTracks.isNotEmpty(),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(16.dp)
                .zIndex(1f), // Ensure PIP is above the grid
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            Box(
                modifier = Modifier
                    .size(100.dp, 150.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .border(1.dp, Color.White.copy(alpha = 0.4f), RoundedCornerShape(16.dp))
                    .background(Color.Black.copy(alpha = 0.2f))
            ) {
                localVideoTrack?.let {
                    VideoRenderer(
                        videoTrack = it,
                        eglBaseContext = eglBaseContext,
                        modifier = Modifier.fillMaxSize(),
                        zOrderMediaOverlay = true // Required for native views to overlap correctly
                    )
                }
            }
        }

        // 3. Control Bar (Top-most Layer)
        Surface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 48.dp)
                .clip(RoundedCornerShape(40.dp))
                .border(0.5.dp, Color.White.copy(alpha = 0.3f), RoundedCornerShape(40.dp))
                .zIndex(2f), // Top layer
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
                    Icon(Icons.Default.PersonAdd, contentDescription = "Add Simulation")
                }

                IconButton(
                    onClick = { isMicOn = !isMicOn },
                    colors = IconButtonDefaults.iconButtonColors(
                        containerColor = if (isMicOn) Color.White.copy(alpha = 0.1f) else MaterialTheme.colorScheme.error.copy(alpha = 0.8f),
                        contentColor = Color.White
                    )
                ) {
                    Icon(imageVector = if (isMicOn) Icons.Default.Mic else Icons.Default.MicOff, contentDescription = null)
                }

                IconButton(
                    onClick = { isVideoOn = !isVideoOn },
                    colors = IconButtonDefaults.iconButtonColors(
                        containerColor = if (isVideoOn) Color.White.copy(alpha = 0.1f) else MaterialTheme.colorScheme.error.copy(alpha = 0.8f),
                        contentColor = Color.White
                    )
                ) {
                    Icon(imageVector = if (isVideoOn) Icons.Default.Videocam else Icons.Default.VideocamOff, contentDescription = null)
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
