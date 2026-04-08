package com.cisco.quadroid.transport

import kotlinx.coroutines.flow.StateFlow
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentMap

enum class MoqConnectionStatus {
    IDLE, CONNECTING, CONNECTED, DISCONNECTED, ERROR
}

/**
 * High-level interface for Media over QUIC (MoQ) transport.
 */
interface MoqTransport {

    /**
     * Map of track names to their specific callbacks for routing.
     * Implementations should provide a thread-safe map.
     */
    val trackCallbacks: ConcurrentMap<String, MoqObjectCallback>


    /**
     * Current connection status to the MoQ relay.
     */
    val connectionStatus: StateFlow<MoqConnectionStatus>

    /**
     * Connects to a MoQ relay.
     * @param url The relay URL (e.g., moq://relay.example.com:443)
     * @param deviceId Unique identifier for this device/endpoint.
     */
    fun connect(url: String, deviceId: String)

    /**
     * Disconnects from the relay.
     */
    fun disconnect()

    /**
     * Announces a namespace to the relay.
     * Should be called before publishing tracks within that namespace.
     * @param namespacePrefix The namespace prefix (e.g., "quadroid/video")
     */
    fun publishNamespace(namespacePrefix: String)

    /**
     * Publishes a track.
     * @param trackName Full track name.
     * @param options Publishing options including priority and delivery mode.
     */
    fun publish(trackName: String, options: PublishOptions = PublishOptions())

    /**
     * Sends an object (encoded frame) on a published track.
     * @param trackName Full track name.
     * @param groupId Group ID (e.g., IDR interval).
     * @param objectId Object ID (e.g., frame index within group).
     * @param payload The encoded frame data. Uses DirectByteBuffer for zero-copy.
     */
    fun sendObject(
        trackName: String,
        groupId: Long,
        objectId: Long,
        payload: ByteBuffer,
        priority: Int = 0,
        deliveryTimeoutMs: Long = 0,
        useDatagram: Boolean = false
    )

    /**
     * Subscribes to a track.
     * @param trackName Full track name.
     * @param callback Callback for received objects.
     */
    fun subscribe(trackName: String, callback: MoqObjectCallback)

    /**
     * Subscribes to a namespace prefix.
     * @param namespacePrefix The prefix to watch for matching publishes.
     * @param callback Callback to handle new matching tracks.
     */
    fun subscribeNamespace(namespacePrefix: String, callback: NamespaceSubscriptionCallback)

    /**
     * Configures default behavior for namespace matches.
     */
    fun setNamespaceDefaultBehavior(acceptAll: Boolean)

    /**
     * Unsubscribes from a namespace.
     * @param namespacePrefix The namespace prefix to unsubscribe from.
     */
    fun unsubscribeNamespace(namespacePrefix: String)

    /**
     * Unpublishes (stops publishing) a track.
     * @param trackName The track name to unpublish.
     */
    fun unpublishTrack(trackName: String)

    /**
     * Unpublishes (stops announcing) a namespace.
     * @param namespacePrefix The namespace prefix to unpublish.
     */
    fun unpublishNamespace(namespacePrefix: String)
}

data class PublishOptions(
    val priority: Int = 0,
    val useDatagram: Boolean = false
)

interface MoqObjectCallback {
    /**
     * Called when a new object is received.
     * @param payload DirectByteBuffer containing the frame data.
     */
    fun onObject(trackName: String, groupId: Long, objectId: Long, payload: ByteBuffer)
}

interface NamespaceSubscriptionCallback {
    /**
     * Called when a relay reports a matching publish for a subscribed namespace.
     * @return true to accept and subscribe to the track, false to reject.
     */
    fun onMatch(trackName: String): Boolean
}
