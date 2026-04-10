# MoqCatalog Module

This module provides support for parsing and managing MSF (MOQT Streaming Format) catalogs as defined in the IETF draft specification at `/Users/chaya/Github/msf`.

## Features

- **Lenient JSON Parsing**: Automatically ignores unknown fields and handles missing optional fields
- **Version-Based Refresh**: Automatically detects version changes and refreshes the catalog when needed
- **Delta Updates**: Supports incremental catalog updates (add, remove, clone tracks)
- **Track Management**: Query and manage tracks by name and namespace
- **Type-Safe**: Full Kotlin type safety with data classes

## Usage

### Basic Usage

```kotlin
import com.cisco.catalog.MoqCatalog

// Create a catalog instance
val moqCatalog = MoqCatalog()

// Parse and update catalog from JSON string
val jsonString = """
{
  "version": 1,
  "generatedAt": 1746104606044,
  "tracks": [
    {
      "name": "1080p-video",
      "namespace": "conference.example.com/conference123/alice",
      "packaging": "loc",
      "isLive": true,
      "targetLatency": 2000,
      "role": "video",
      "codec": "av01.0.08M.10.0.110.09",
      "width": 1920,
      "height": 1080,
      "framerate": 30,
      "bitrate": 1500000
    }
  ]
}
"""

val result = moqCatalog.updateCatalog(jsonString)
if (result.isSuccess) {
    val updateResult = result.getOrNull()
    println("Catalog updated successfully!")
    println("  Version: ${updateResult?.version}")
    println("  Total tracks: ${updateResult?.totalTracks}")
    println("  Tracks added: ${updateResult?.tracksAdded}")
    println("  Refreshed: ${updateResult?.refreshed}")
}
```

### Querying Tracks

```kotlin
// Get all tracks
val allTracks = moqCatalog.getTracks()

// Get a specific track by name
val videoTrack = moqCatalog.getTrack("1080p-video")

// Get a track with namespace
val namespacedTrack = moqCatalog.getTrack(
    name = "1080p-video",
    namespace = "conference.example.com/conference123/alice"
)

// Access track properties
videoTrack?.let { track ->
    println("Track: ${track.name}")
    println("Codec: ${track.codec}")
    println("Resolution: ${track.width}x${track.height}")
    println("Bitrate: ${track.bitrate}")
}
```

### Delta Updates

```kotlin
// Apply a delta update
val deltaJson = """
{
  "version": 1,
  "deltaUpdate": true,
  "generatedAt": 1746104606044,
  "addTracks": [
    {
      "name": "slides",
      "packaging": "loc",
      "isLive": true,
      "role": "video",
      "width": 1920,
      "height": 1080
    }
  ],
  "removeTracks": [
    {
      "name": "old-track"
    }
  ]
}
"""

val deltaResult = moqCatalog.updateCatalog(deltaJson)
if (deltaResult.isSuccess) {
    val update = deltaResult.getOrNull()
    println("Delta update applied!")
    println("  Tracks added: ${update?.tracksAdded}")
    println("  Tracks removed: ${update?.tracksRemoved}")
}
```

### Version-Based Refresh

When a catalog with a different version number is received, the catalog automatically refreshes:

```kotlin
// First catalog with version 1
val v1Json = """{"version": 1, "tracks": [...]}"""
moqCatalog.updateCatalog(v1Json)

// Later, catalog with version 2 arrives
val v2Json = """{"version": 2, "tracks": [...]}"""
val result = moqCatalog.updateCatalog(v2Json)

if (result.isSuccess) {
    val update = result.getOrNull()
    if (update?.refreshed == true) {
        println("Catalog was refreshed due to version change!")
    }
}
```

### Static Parsing

For one-off parsing without state management:

```kotlin
// Parse without managing state
val parseResult = MoqCatalog.parse(jsonString)
if (parseResult.isSuccess) {
    val catalog = parseResult.getOrNull()
    println("Catalog version: ${catalog?.version}")
    println("Number of tracks: ${catalog?.getAllTracks()?.size}")
}

// Serialize a catalog to JSON
val serializedResult = MoqCatalog.serialize(catalog)
if (serializedResult.isSuccess) {
    val json = serializedResult.getOrNull()
    println(json)
}
```

## Data Classes

### MsfCatalog

Main catalog data class with the following fields:

- `version: Int` (required) - MSF version number
- `generatedAt: Long?` - Timestamp when catalog was generated
- `isComplete: Boolean?` - Whether the broadcast is complete
- `tracks: List<MsfTrack>?` - Array of track objects
- `deltaUpdate: Boolean?` - Whether this is a delta update
- `addTracks: List<MsfTrack>?` - Tracks to add (delta update)
- `removeTracks: List<MsfTrack>?` - Tracks to remove (delta update)
- `cloneTracks: List<MsfTrack>?` - Tracks to clone (delta update)

### MsfTrack

Track data class with fields including:

**Required:**
- `name: String` - Track name
- `packaging: String` - Packaging type (e.g., "loc", "mediatimeline", "eventtimeline")

**Optional:**
- `namespace: String?` - Track namespace
- `isLive: Boolean?` - Whether track is live
- `role: String?` - Track role (e.g., "video", "audio", "caption")
- `codec: String?` - Codec identifier
- `width: Int?`, `height: Int?` - Video dimensions
- `bitrate: Int?` - Bitrate in bps
- `framerate: Double?` - Framerate in fps
- And many more fields as defined in the MSF specification

### CatalogUpdateResult

Result of a catalog update operation:

- `refreshed: Boolean` - Whether catalog was refreshed due to version change
- `isDelta: Boolean` - Whether this was a delta update
- `tracksAdded: Int` - Number of tracks added
- `tracksRemoved: Int` - Number of tracks removed
- `totalTracks: Int` - Total number of tracks after update
- `version: Int` - Catalog version
- `isComplete: Boolean` - Whether broadcast is complete

## Lenient Parsing

The parser is configured to be lenient with JSON input:

- **Ignores unknown fields**: Any extra fields in the JSON are silently ignored
- **Uses default values**: Missing optional fields use their default values (usually `null`)
- **Flexible JSON**: Allows comments and trailing commas (isLenient = true)

This means you can safely parse MSF catalogs even if:
- The specification adds new fields you don't know about yet
- Some optional fields are missing
- Custom fields are added by specific implementations

## Error Handling

All operations return `Result<T>` types for safe error handling:

```kotlin
val result = moqCatalog.updateCatalog(jsonString)

result.onSuccess { updateResult ->
    println("Success: ${updateResult.totalTracks} tracks")
}

result.onFailure { exception ->
    println("Error: ${exception.message}")
}
```

## Dependencies

- `kotlinx-serialization-json` - For JSON parsing and serialization

## MSF Specification

This implementation follows the MOQT Streaming Format specification from:
`/Users/chaya/Github/msf/draft-ietf-moq-msf.md`
