package com.cisco.quadroid.transport

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.nio.ByteBuffer

/**
 * Native implementation of MoqTransport using libquicr via JNI.
 */
class MoqNative : MoqTransport {
    
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

    override fun connect(url: String) {
        connectionJob?.cancel()
        connectionJob = CoroutineScope(Dispatchers.IO).launch {
            _connectionStatus.value = MoqConnectionStatus.CONNECTING
            try {
                // Call native connect which will:
                // 1. Create AndroidMoqClient
                // 2. Call Connect() on libquicr client
                // 3. Status updates will come via onConnectionStatusChanged callback
                val ptr = nativeConnect(url)
                if (ptr == 0L) {
                    Log.e(TAG, "Native connect failed to initialize")
                    _connectionStatus.value = MoqConnectionStatus.ERROR
                } else {
                    nativePtr = ptr
                    Log.i(TAG, "Native client initialized, connection in progress")
                    // Status will be updated via onConnectionStatusChanged callback
                }
            } catch (e: Exception) {
                Log.e(TAG, "Exception during connect: ${e.message}")
                _connectionStatus.value = MoqConnectionStatus.ERROR
            }
        }
    }

    // Called by JNI when connection status changes
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

    override fun disconnect() {
        connectionJob?.cancel()
        if (nativePtr != 0L) {
            nativeDisconnect(nativePtr)
            nativePtr = 0
        }
        _connectionStatus.value = MoqConnectionStatus.DISCONNECTED
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
        if (nativePtr != 0L) nativeSubscribe(nativePtr, trackName, callback)
    }

    override fun subscribeNamespace(namespacePrefix: String, callback: NamespaceSubscriptionCallback) {
        if (nativePtr != 0L) nativeSubscribeNamespace(nativePtr, namespacePrefix, callback)
    }

    override fun setNamespaceDefaultBehavior(acceptAll: Boolean) {
        if (nativePtr != 0L) nativeSetNamespaceDefaultBehavior(nativePtr, acceptAll)
    }

    // Native methods
    private external fun nativeConnect(url: String): Long
    private external fun nativeDisconnect(ptr: Long)
    private external fun nativePublish(ptr: Long, trackName: String, priority: Int, useDatagram: Boolean)
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
    private external fun nativeSetNamespaceDefaultBehavior(ptr: Long, acceptAll: Boolean)
}
