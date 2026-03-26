# Quadroid - Low Latency Video Conferencing

Quadroid is a lightweight, real-time video and audio conferencing application built with Jetpack Compose.

## Features
- **Real-time Video/Audio**: Low-latency communication.
- **Jetpack Compose**: Fully declarative UI.
- **Modern Android Stack**: Hilt for DI, Coroutines for concurrency, and ViewModel for state management.
- **Permissions Handling**: Uses Accompanist for seamless permission requests.

## Architecture
- **MainViewModel**: Handles UI state flows and interacts with the WebRTC manager.
- **UI Components**: Reusable Compose components like `VideoRenderer`.

## Tech Stack
- **Hilt**: Dependency injection.
- **Jetpack Compose**: UI framework.
- **Coroutines & Flow**: Reactive state management.

## Getting Started
1. Clone the repository.
2. Sync the project with Gradle.
3. Run the app on an Android device (Physical device recommended for camera).

## Testing
Unit tests for the ViewModel and integration tests for UI flows are included in the `test` and `androidTest` folders respectively.
