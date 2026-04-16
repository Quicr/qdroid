<p align="center">
  <img src="app/src/main/ic_launcher-playstore.png" alt="QDroid Logo" width="120" height="120">
</p>

# QDroid - Media over QUIC Video Conferencing

> A next-generation, low-latency video conferencing application powered by Media over QUIC (MoQ) protocol

QDroid is a cutting-edge Android video conferencing application that leverages the **Media over QUIC (MoQ)** protocol for ultra-low latency real-time communication. Built with modern Android development practices and featuring a stunning glassmorphic UI, QDroid delivers enterprise-grade video calling with exceptional performance.

---

## ✨ Features

- 🚀 **Ultra-Low Latency** - Powered by Media over QUIC (MoQ) protocol for sub-second latency
- 🎥 **Real-time Video/Audio** - High-quality H.264 video encoding with Opus audio codec
- 🎙️ **Voice Activity Detection (VAD)** - Intelligent audio transmission with WebRTC VAD to reduce bandwidth
- 🔄 **Adaptive Grid Layout** - Dynamic participant layout supporting portrait and landscape modes
- 📹 **Camera Control** - Seamless front/back camera switching with live preview
- 🎤 **Audio Management** - Toggle microphone with native Oboe audio processing
- 🌐 **Multi-Relay Support** - Connect to different MoQ relays with configurable URLs
- 💎 **Glassmorphic UI** - Modern, animated Material 3 design with liquid glass effects
- 📊 **Connection Status** - Real-time relay connection monitoring
- 🔐 **Permission Management** - Seamless camera and microphone permission handling
- 🎨 **Native Video Rendering** - Hardware-accelerated video decoding with MediaCodec

---
## 🚀 Getting Started

### Prerequisites

- **Android Studio** Ladybug (2024.2.1) or later
- **Android SDK** API 30 or higher (compileSdk 35, targetSdk 35)
- **NDK** version 27.0.12077973 or later
- **CMake** 3.22.1 (provided by NDK)
- **JDK** 17 or higher
- **Git** with SSH access configured for GitHub (required for submodules)
- **Physical Android device** running Android 11 (API 30) or higher (recommended for camera/audio testing)

### Installation

1. **Clone the repository with submodules**
   ```bash
   git clone --recursive git@github.com:Quicr/MoqAndroid.git
   cd MoqAndroid/qdroid
   ```

   Or if you've already cloned the repository:
   ```bash
   cd MoqAndroid/qdroid
   git submodule update --init --recursive
   ```

   > **Note:** The `--recursive` flag is essential to fetch the Oboe audio library submodule in `nativeaudio/src/main/cpp/oboe`

2. **Open in Android Studio**
   - Launch Android Studio
   - Select "Open an Existing Project"
   - Navigate to the cloned `qdroid` directory

3. **Sync Gradle dependencies**
   ```bash
   ./gradlew build
   ```

   Or use Android Studio's "Sync Project with Gradle Files" button

4. **Build native libraries** (automatic via CMake)
   - The native MoQ transport layer will be built automatically
   - Dependencies (libquicr, BoringSSL, Picotls, loc-cpp, Opus) are fetched via CMake FetchContent
   - Oboe audio library is built from the git submodule

5. **Connect your Android device**
   - Enable USB debugging in Developer Options
   - Connect via USB or use Wireless ADB

6. **Run the app**
   ```bash
   ./gradlew installDebug
   ```

   Or click the "Run" button in Android Studio

### Configuration

Configure the MoQ relay server and Voice Activity Detection in the app settings:

- Open the app
- Tap the settings icon (top-right)
- Select or enter a relay URL (e.g., `moq://eng-3.us-west-2.m10x.org:33550`)
- Toggle Voice Activity Detection on/off (enabled by default)
- Save settings

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
- [Google Oboe](https://github.com/google/oboe) - High-performance audio I/O
- [android-vad](https://github.com/gkonovalov/android-vad) - Voice Activity Detection library
- [Jetpack Compose](https://developer.android.com/jetpack/compose) - Modern UI toolkit

---
