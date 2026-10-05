// SPDX-FileCopyrightText: Copyright (c) 2025 Cisco Systems
// SPDX-License-Identifier: BSD-2-Clause

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

    /**
     * Latch set once the session reaches CONNECTED. While latched, a spurious
     * native kNotConnected (mapped to IDLE) is ignored, because libquicr can emit
     * a transient kNotConnected after a healthy connect while media keeps flowing.
     * Only a genuine terminal signal (DISCONNECTED/ERROR) or an explicit
     * disconnect() clears the latch.
     */
    private var connectionLatched = false

    override fun connect(url: String, deviceId: String) {
        connectionJob?.cancel()
        connectionLatched = false
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
        val mapped = when (status) {
            0 -> MoqConnectionStatus.IDLE
            1 -> MoqConnectionStatus.CONNECTING
            2 -> MoqConnectionStatus.CONNECTED
            3 -> MoqConnectionStatus.DISCONNECTED
            4 -> MoqConnectionStatus.ERROR
            else -> MoqConnectionStatus.ERROR
        }

        // Once CONNECTED, ignore an uncorroborated downgrade to IDLE/CONNECTING
        // (libquicr emits a transient kNotConnected after a healthy connect while
        // media keeps flowing). Only DISCONNECTED/ERROR can clear the latch.
        if (connectionLatched &&
            (mapped == MoqConnectionStatus.IDLE || mapped == MoqConnectionStatus.CONNECTING)
        ) {
            Log.w(TAG, "Ignoring uncorroborated status $mapped (native=$status) while connection is latched")
            return
        }

        if (mapped == MoqConnectionStatus.CONNECTED) {
            connectionLatched = true
        } else if (mapped == MoqConnectionStatus.DISCONNECTED || mapped == MoqConnectionStatus.ERROR) {
            connectionLatched = false
        }

        _connectionStatus.value = mapped
    }

    @Suppress("unused") // Called from native code
    override fun onObject(trackName: String, groupId: Long, objectId: Long, payload: ByteBuffer) {
        val trackKey = TrackUtil.generateTrackKeyFromFullName(trackName)

        val callback = trackCallbacks[trackKey]
        if (callback != null) {
            callback.onObject(trackName, groupId, objectId, payload)
        } else {
            Log.w(TAG, "No callback registered for trackKey=$trackKey (trackName=$trackName). Available keys: ${trackCallbacks.keys}")
        }
    }

    override fun disconnect() {
        connectionJob?.cancel()
        connectionLatched = false
        if (nativePtr != 0L) {
            nativeDisconnect(nativePtr)
            nativePtr = 0
        }
        _connectionStatus.value = MoqConnectionStatus.DISCONNECTED
        trackCallbacks.clear()
    }

    override fun publishNamespace(namespacePrefix: String) {
        /*if (nativePtr != 0L) nativePublishNamespace(nativePtr, namespacePrefix)*/
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

    override fun unsubscribeNamespace(namespacePrefix: String) {
        if (nativePtr != 0L) nativeUnsubscribeNamespace(nativePtr, namespacePrefix)
    }

    override fun unpublishTrack(trackName: String) {
        if (nativePtr != 0L) nativeUnpublish(nativePtr, trackName)
    }

    override fun unpublishNamespace(namespacePrefix: String) {
        if (nativePtr != 0L) nativeUnpublishNamespace(nativePtr, namespacePrefix)
    }

    override fun unsubscribeTrack(trackName: String) {
        //trackCallbacks.remove(trackKey(trackName))
        if (nativePtr != 0L) nativeUnsubscribe(nativePtr, trackName)
    }

    override fun endSubgroup(trackName: String, groupId: Long, subgroupId: Long, completed: Boolean) {
        if (nativePtr != 0L) nativeEndSubGroup(nativePtr, trackName, groupId, 0 , completed = true)
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
    private external fun nativeSubscribeNamespace(ptr: Long, namespacePrefix: String, callback: NamespaceSubscriptionCallback)
    private external fun nativeUnpublishNamespace(ptr: Long, namespacePrefix: String)
    private external fun nativeUnsubscribeNamespace(ptr: Long, namespacePrefix: String)
    private external fun nativeSetNamespaceDefaultBehavior(ptr: Long, acceptAll: Boolean)
    private external fun nativeUnsubscribe(ptr: Long, trackName: String)

    private external fun nativeEndSubGroup(ptr: Long, trackName: String, groupId: Long, subgroupId: Long, completed: Boolean)




    // Video jitter buffer methods
    external fun nativeCreateVideoJitterBuffer(trackName: String, callback: VideoJitterBufferCallback)
    external fun nativeDestroyVideoJitterBuffer(trackName: String)
    external fun nativeGetVideoJitterBufferStats(trackName: String): VideoJitterBufferStats?

    // Audio jitter buffer methods
    external fun nativeCreateAudioJitterBuffer(trackName: String, trackKey: String)
    external fun nativeDestroyAudioJitterBuffer(trackName: String)
    external fun nativeGetAudioJitterBufferStats(trackName: String): AudioJitterBufferStats?
}
