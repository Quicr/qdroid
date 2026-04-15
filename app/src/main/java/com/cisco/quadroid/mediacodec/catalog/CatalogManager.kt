package com.cisco.quadroid.mediacodec.catalog

import android.util.Log
import com.cisco.catalog.CatalogUpdateResult
import com.cisco.catalog.MoqCatalog
import com.cisco.catalog.MoqNameUtils
import com.cisco.catalog.MsfTrack
import com.cisco.quadroid.transport.MoqObjectCallback
import com.cisco.quadroid.transport.MoqTransport
import com.cisco.quadroid.util.TrackUtil
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

class CatalogManager(
    private val moqTransport: MoqTransport,
    private val deviceId: String,
    private val meetingId: String
) {
    private val tag = "CatalogManager"
    private val moqCatalog = MoqCatalog()

    var catalogVersion: Int = 0
        private set

    val catalogTracks = mutableListOf<MsfTrack>()
    val catalogTrackNamesUrl = mutableListOf<String>()
    val catalogNamespacesUrl = mutableListOf<String>()

    var onCatalogReady: (() -> Unit)? = null
    private var catalogSubscribed = false

    fun subscribeToCatalogTrack() {
        Log.i(
            tag,
            "Subscribing to catalog track: ${moqCatalog.catalogTrackUrl} (safe form: ${moqCatalog.catalogTrackSafeForm})"
        )

        // Generate track key for the catalog track
        val catalogTrackKey = TrackUtil.generateTrackKeyFromFullName(moqCatalog.catalogTrackUrl)

        moqTransport.trackCallbacks[catalogTrackKey] = object : MoqObjectCallback {
            override fun onObject(
                trackName: String,
                groupId: Long,
                objectId: Long,
                payload: ByteBuffer
            ) {
                Log.d(
                    tag,
                    "Received catalog object: trackName=$trackName, groupId=$groupId, objectId=$objectId, size=${payload.remaining()}"
                )

                // Parse the JSON payload
                val bytes = ByteArray(payload.remaining())
                payload.get(bytes)
                val jsonString = String(bytes, StandardCharsets.UTF_8)

                Log.d(tag, "Catalog JSON: $jsonString")

                // Update catalog with the JSON
                val updateResult = moqCatalog.updateCatalog(jsonString)

                if (updateResult.isSuccess) {
                    val result = updateResult.getOrNull()!!
                    handleCatalogUpdate(result)
                } else {
                    Log.e(
                        tag,
                        "Failed to parse catalog: ${updateResult.exceptionOrNull()?.message}"
                    )
                }
            }
        }

        // Subscribe to the catalog track
        moqTransport.subscribe(moqCatalog.catalogTrackUrl, callback = object : MoqObjectCallback {
            override fun onObject(
                trackName: String,
                groupId: Long,
                objectId: Long,
                payload: ByteBuffer
            ) {
                Log.d(
                    tag,
                    "Received catalog object: trackName=$trackName, groupId=$groupId, objectId=$objectId, size=${payload.remaining()}"
                )

                // Parse the JSON payload
                val bytes = ByteArray(payload.remaining())
                payload.get(bytes)
                val jsonString = String(bytes, StandardCharsets.UTF_8)

                Log.d(tag, "Catalog JSON: $jsonString")

                // Update catalog with the JSON
                val updateResult = moqCatalog.updateCatalog(jsonString)

                if (updateResult.isSuccess) {
                    val result = updateResult.getOrNull()!!
                    handleCatalogUpdate(result)
                } else {
                    Log.e(
                        tag,
                        "Failed to parse catalog: ${updateResult.exceptionOrNull()?.message}"
                    )
                }
            }
        })

        catalogSubscribed = true
    }

    private fun handleCatalogUpdate(result: CatalogUpdateResult) {
        Log.i(
            tag, "Catalog updated: version=${result.version}, tracks=${result.totalTracks}, " +
                    "added=${result.tracksAdded}, removed=${result.tracksRemoved}, " +
                    "refreshed=${result.refreshed}, isDelta=${result.isDelta}"
        )

        // Check if version changed
        val versionChanged = catalogVersion != result.version
        catalogVersion = result.version

        if (versionChanged || result.refreshed) {
            // Update catalog tracks and namespaces
            updateCatalogTracksAndNamespaces()
        }

        // Notify that catalog is ready
        onCatalogReady?.invoke()
        Log.i(tag, "Catalog is now ready")
    }

    fun updateCatalogTracksAndNamespaces() {
        val tracks = moqCatalog.getTracks()

        // Clear existing lists
        catalogTracks.clear()
        catalogTrackNamesUrl.clear()
        catalogNamespacesUrl.clear()

        // Collect unique namespaces
        val namespacesSet = mutableSetOf<String>()

        // Process tracks
        tracks.forEach { track ->
            catalogTracks.add(track)

            // Build track name in URL format
            val namespace = track.namespace
            val trackName = track.name

            if (namespace != null) {
                var namespaceUrl = MoqNameUtils.safeFormToUrl(namespace)

                // Replace publisher_{id} with publisher_{device_id} for local tracks (not catalog track)
                // Catalog track is identified by having "catalog" as the track name
                if (trackName != "catalog") {
                    // Replace publisher_<any_id> with publisher_<device_id>
                    namespaceUrl = namespaceUrl.replace(
                        Regex("publisher_[^/]+"),
                        "publisher_$deviceId"
                    )
                }

                val trackFullNameUrl = "$namespaceUrl/$trackName"
                catalogTrackNamesUrl.add(trackFullNameUrl)
                val subscribeNamespace = namespaceUrl.substringBeforeLast("/")
                namespacesSet.add(subscribeNamespace)
            } else {
                catalogTrackNamesUrl.add(trackName)
            }

            Log.d(
                tag,
                "Catalog track: $trackName, namespace: $namespace, codec: ${track.codec}, role: ${track.role}, url: ${catalogTrackNamesUrl.lastOrNull()}"
            )
        }

        // Store namespaces
        catalogNamespacesUrl.addAll(namespacesSet)

        Log.i(
            tag,
            "Updated catalog: ${catalogTracks.size} tracks, ${catalogNamespacesUrl.size} namespaces"
        )
        Log.i(tag, "Device ID: $deviceId")
        Log.i(tag, "Local track URLs: ${catalogTrackNamesUrl.filter { !it.contains("catalog") }.joinToString(", ")}")

        // Log all catalog tracks for debugging
        catalogTracks.forEachIndexed { index, track ->
            Log.d(tag, "Catalog[$index]: name=${track.name}, role=${track.role}, codec=${track.codec}, ${track.width}x${track.height}, namespace=${track.namespace}")
        }
    }

    fun reset() {
        // Clear catalog state
        if (catalogSubscribed) {
            moqTransport.unsubscribeTrack(moqCatalog.catalogTrackUrl)
            catalogSubscribed = false
        }
        catalogVersion = 0
        catalogTracks.clear()
        catalogTrackNamesUrl.clear()
        catalogNamespacesUrl.clear()
        moqCatalog.clear()
    }

    fun clear() {
        catalogTracks.clear()
        catalogTrackNamesUrl.clear()
        catalogNamespacesUrl.clear()
    }
}
