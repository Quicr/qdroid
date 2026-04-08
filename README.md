# 📱 QDroid - Media over QUIC Video Conferencing

> A next-generation, low-latency video conferencing application powered by Media over QUIC (MoQ) protocol

QDroid is a cutting-edge Android video conferencing application that leverages the **Media over QUIC (MoQ)** protocol for ultra-low latency real-time communication. Built with modern Android development practices and featuring a stunning glassmorphic UI, QDroid delivers enterprise-grade video calling with exceptional performance.

---

## ✨ Features

- 🚀 **Ultra-Low Latency** - Powered by Media over QUIC (MoQ) protocol for sub-second latency
- 🎥 **Real-time Video/Audio** - High-quality H.264 video encoding with Opus audio codec
- 🔄 **Adaptive Grid Layout** - Dynamic participant layout supporting portrait and landscape modes
- 📹 **Camera Control** - Seamless front/back camera switching with live preview
- 🎤 **Audio Management** - Toggle microphone with native Oboe audio processing
- 🌐 **Multi-Relay Support** - Connect to different MoQ relays with configurable URLs
- 💎 **Glassmorphic UI** - Modern, animated Material 3 design with liquid glass effects
- 📊 **Connection Status** - Real-time relay connection monitoring
- 🔐 **Permission Management** - Seamless camera and microphone permission handling
- 🎨 **Native Video Rendering** - Hardware-accelerated video decoding with MediaCodec

---

## 🏗️ Architecture

QDroid follows **Clean Architecture** principles with a clear separation of concerns:

```
┌─────────────────────────────────────────┐
│           UI Layer (Compose)            │
└────────────────┬────────────────────────┘
                 │
┌────────────────▼────────────────────────┐
│        Presentation Layer (VM)          │
└────────────────┬────────────────────────┘
                 │
┌────────────────▼────────────────────────┐
│          Domain Layer                   │
└────────────────┬────────────────────────┘
                 │
┌────────────────▼────────────────────────┐
│          Data/Native Layer              │
└─────────────────────────────────────────┘
```

---

## 🛠️ Tech Stack

### Frontend
- **Jetpack Compose** - Modern declarative UI framework
- **Material 3** - Latest Material Design components with dynamic theming
- **Compose Navigation** - Type-safe navigation between screens
- **Accompanist Permissions** - Runtime permission handling

### Android Framework
- **CameraX** - Camera lifecycle management and capture
- **MediaCodec** - Hardware video encoding/decoding (H.264)
- **Hilt** - Dependency injection
- **Kotlin Coroutines & Flow** - Asynchronous programming and reactive state management
- **ViewModel** - Lifecycle-aware state management
- **AndroidX Lifecycle** - Lifecycle-aware components

### Native Layer (C++)
- **libquicr** - MoQ protocol implementation (QUIC-based media transport)
- **BoringSSL** - Cryptographic operations for QUIC
- **Oboe** - High-performance audio I/O library
- **CMake** - Native build system
- **JNI** - Java/Kotlin to C++ bridge

### Media Processing
- **H.264** - Video codec with hardware acceleration
- **Opus** - High-quality audio codec
- **NAL Unit Parser** - H.264 bitstream parsing
- **SPS/PPS Parser** - Video configuration parsing

### Testing
- **JUnit** - Unit testing framework
- **MockK** - Kotlin mocking library
- **Turbine** - Flow testing library
- **Espresso** - UI testing framework
- **Compose UI Test** - Composable testing

---

## 🚀 Getting Started

### Prerequisites

- **Android Studio** Hedgehog (2023.1.1) or later
- **Android SDK** API 30 or higher (targetSdk 35)
- **NDK** version 25.1.8937393 or later
- **CMake** 3.22.1 or later
- **JDK** 17 or higher
- **Physical Android device** (recommended for camera/audio testing)

### Installation

1. **Clone the repository**
   ```bash
   git clone https://github.com/your-org/quadroid.git
   cd quadroid
   ```

2. **Open in Android Studio**
   - Launch Android Studio
   - Select "Open an Existing Project"
   - Navigate to the cloned `Quadroid` directory

3. **Sync Gradle dependencies**
   ```bash
   ./gradlew build
   ```

   Or use Android Studio's "Sync Project with Gradle Files" button

4. **Build native libraries** (automatic via CMake)
   - The native MoQ transport layer will be built automatically
   - libquicr and dependencies are fetched via CMake FetchContent

5. **Connect your Android device**
   - Enable USB debugging in Developer Options
   - Connect via USB or use Wireless ADB

6. **Run the app**
   ```bash
   ./gradlew installDebug
   ```

   Or click the "Run" button in Android Studio

### Configuration

Configure the MoQ relay server in the app settings:

- Open the app
- Tap the settings icon (top-right)
- Select or enter a relay URL (e.g., `moq://relay.m10x.org:33440`)
- Save settings

---

## 🧪 Testing

QDroid includes comprehensive test coverage across multiple layers:

### Unit Tests

Located in `app/src/test/`:

```bash
# Run all unit tests
./gradlew test

# Run with coverage
./gradlew testDebugUnitTest jacocoTestReport
```

### Instrumented Tests

Located in `app/src/androidTest/`:

```bash
# Run on connected device/emulator
./gradlew connectedAndroidTest
```

**Testing Tools:**
- Espresso for UI interaction testing
- Compose UI Test for Composable testing
- MockK Android for Android-specific mocking

### Running Specific Tests

```bash
# Run specific test class
./gradlew test --tests "com.cisco.quadroid.MainViewModelTest"

# Run specific test method
./gradlew test --tests "com.cisco.quadroid.MainViewModelTest.testStartCall"

# Run instrumented tests on specific device
adb devices  # Get device ID
./gradlew connectedAndroidTest -Pandroid.testInstrumentationRunnerArguments.device=<DEVICE_ID>
```

---

## 🔧 Build Configuration

### Gradle Modules

- **app**: Main application module with UI and business logic
- **nativeaudio**: Native audio library using Oboe

### Native Build

The native layer is built using CMake with the following components:

- **libquicr**: Fetched from GitHub (boring2 branch)
- **BoringSSL**: Crypto backend for QUIC
- **Oboe**: Low-latency audio I/O

Native build is triggered automatically during Gradle sync:

```bash
# Force native rebuild
./gradlew clean
./gradlew externalNativeBuildDebug
```

---

## 📝 License

This project is licensed under the MIT License - see the LICENSE file for details.

---

## 🤝 Contributing

Contributions are welcome! Please feel free to submit a Pull Request.

1. Fork the repository
2. Create your feature branch (`git checkout -b feature/AmazingFeature`)
3. Commit your changes (`git commit -m 'Add some AmazingFeature'`)
4. Push to the branch (`git push origin feature/AmazingFeature`)
5. Open a Pull Request

---

## 📧 Contact

For questions or support, please open an issue on GitHub.

---

## 🙏 Acknowledgments

- [libquicr](https://github.com/Quicr/libquicr) - MoQ protocol implementation
- [Google Oboe](https://github.com/google/oboe) - High-performance audio
- [Jetpack Compose](https://developer.android.com/jetpack/compose) - Modern UI toolkit
- [Material 3](https://m3.material.io/) - Design system

---

<div align="center">
  <strong>Built with ❤️ using Kotlin and Jetpack Compose</strong>
</div>
