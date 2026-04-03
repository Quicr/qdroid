package com.cisco.quadroid.transport

import android.util.Log
import com.cisco.quadroid.util.TrackUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentMap

/**
 * Native implementation of MoqTransport using libquicr via JNI.
 */
class MoqNative(override val trackCallbacks: ConcurrentMap<String, MoqObjectCallback>) : MoqTransport, MoqObjectCallback {
    
    companion object {
        private const val TAG = "MoqNative"
        init {
            System.loadLibrary("moq_jni")
        }
    }

    private var nativePtr: Long = 0
    private val _connectionStatus = MutableStateFlow(MoqConnectionStatus.IDLE)
    override val connectionStatus: StateFlow<MoqConnectionStatus> = _connectionStatus.asStateFlow()
    private var connectionJob: Job? = null

    override var discoveryListener: MoqDiscoveryListener? = null
    
    override fun connect(url: String, deviceId: String) {
        connectionJob?.cancel()
        connectionJob = CoroutineScope(Dispatchers.IO).launch {
            _connectionStatus.value = MoqConnectionStatus.CONNECTING
            try {
                val ptr = nativeConnect(url, deviceId)
                if (ptr == 0L) {
                    Log.e(TAG, "Native connect failed to initialize")
                    _connectionStatus.value = MoqConnectionStatus.ERROR
                } else {
                    nativePtr = ptr
                    Log.i(TAG, "Native client initialized, connection in progress")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Exception during connect: ${e.message}")
                _connectionStatus.value = MoqConnectionStatus.ERROR
            }
        }
    }

    @Suppress("unused") // Called from native code
    private fun onConnectionStatusChanged(status: Int) {
        Log.i(TAG, "Connection status changed to: $status")
        _connectionStatus.value = when (status) {
            0 -> MoqConnectionStatus.IDLE
            1 -> MoqConnectionStatus.CONNECTING
            2 -> MoqConnectionStatus.CONNECTED
            3 -> MoqConnectionStatus.DISCONNECTED
            4 -> MoqConnectionStatus.ERROR
            else -> MoqConnectionStatus.ERROR
        }
    }

    @Suppress("unused") // Called from native code
    override fun onObject(trackName: String, groupId: Long, objectId: Long, payload: ByteBuffer) {
        val trackKey = TrackUtil.generateTrackKeyFromFullName(trackName)

        val callback = trackCallbacks[trackKey]
        if (callback != null) {
            callback.onObject(trackName, groupId, objectId, payload)
        } else {
            Log.w(TAG, "No callback registered for trackKey=$trackKey (trackName=$trackName). Available keys: ${trackCallbacks.keys}")
            // WebRTC-style media discovery: notify listener that an unknown track is sending media
            discoveryListener?.onTrackDiscovered(trackName, groupId, objectId, payload)
        }
    }

    override fun disconnect() {
        connectionJob?.cancel()
        if (nativePtr != 0L) {
            nativeDisconnect(nativePtr)
            nativePtr = 0
        }
        _connectionStatus.value = MoqConnectionStatus.DISCONNECTED
        trackCallbacks.clear()
    }

    override fun publishNamespace(namespacePrefix: String) {
        if (nativePtr != 0L) nativePublishNamespace(nativePtr, namespacePrefix)
    }

    override fun publish(trackName: String, options: PublishOptions) {
        if (nativePtr != 0L) nativePublish(nativePtr, trackName, options.priority, options.useDatagram)
    }

    override fun sendObject(
        trackName: String,
        groupId: Long,
        objectId: Long,
        payload: ByteBuffer,
        priority: Int,
        deliveryTimeoutMs: Long,
        useDatagram: Boolean
    ) {
        if (nativePtr == 0L) return
        if (!payload.isDirect) {
            throw IllegalArgumentException("Payload must be a direct ByteBuffer for zero-copy")
        }
        nativeSendObject(
            nativePtr,
            trackName,
            groupId,
            objectId,
            payload,
            payload.remaining(),
            priority,
            deliveryTimeoutMs,
            useDatagram
        )
    }

    override fun subscribe(trackName: String, callback: MoqObjectCallback) {
        trackCallbacks[trackKey(trackName)] = callback
        if (nativePtr != 0L) nativeSubscribe(nativePtr, trackName, callback)
    }

    override fun subscribeNamespace(namespacePrefix: String, callback: NamespaceSubscriptionCallback) {
        if (nativePtr != 0L) nativeSubscribeNamespace(nativePtr, namespacePrefix, callback)
    }

    override fun setNamespaceDefaultBehavior(acceptAll: Boolean) {
        if (nativePtr != 0L) nativeSetNamespaceDefaultBehavior(nativePtr, acceptAll)
    }

    private fun trackKey(fullName: String): String = TrackUtil.generateTrackKeyFromFullName(fullName)

    private external fun nativeConnect(url: String, deviceId: String): Long
    private external fun nativeDisconnect(ptr: Long)
    private external fun nativePublishNamespace(ptr: Long, namespacePrefix: String)
    private external fun nativePublish(ptr: Long, trackName: String, priority: Int, useDatagram: Boolean)
    private external fun nativeUnpublish(ptr: Long, trackName: String)
    private external fun nativeSendObject(
        ptr: Long,
        trackName: String,
        groupId: Long,
        objectId: Long,
        payload: ByteBuffer,
        payloadSize: Int,
        priority: Int,
        deliveryTimeoutMs: Long,
        useDatagram: Boolean
    )
    private external fun nativeSubscribe(ptr: Long, trackName: String, callback: MoqObjectCallback)
    private external fun nativeUnsubscribe(ptr: Long, trackName: String)
    private external fun nativeSubscribeNamespace(ptr: Long, namespacePrefix: String, callback: NamespaceSubscriptionCallback)
    private external fun nativeSetNamespaceDefaultBehavior(ptr: Long, acceptAll: Boolean)
}
