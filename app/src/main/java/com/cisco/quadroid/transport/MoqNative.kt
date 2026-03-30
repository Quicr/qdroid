package com.cisco.quadroid.transport

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import kotlin.math.pow
import kotlin.random.Random

/**
 * Native implementation of MoqTransport using libquicr via JNI.
 */
class MoqNative : MoqTransport {
    
    companion object {
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
            var attempt = 0
            val baseDelayMs = 1000L
            val maxDelayMs = 32000L

            while (true) {
                _connectionStatus.value = MoqConnectionStatus.CONNECTING
                try {
                    val ptr = nativeConnect(url)
                    if (ptr == 0L) throw Exception("Native init failed")
                    nativePtr = ptr
                    
                    delay(1000) // Simulating handshake

                    // Simulated reliability
                    if (Random.nextFloat() < 0.1f) throw Exception("Handshake failure")

                    _connectionStatus.value = MoqConnectionStatus.CONNECTED
                    break
                } catch (e: Exception) {
                    _connectionStatus.value = MoqConnectionStatus.ERROR
                    val backoff = (baseDelayMs * 2.0.pow(attempt.toDouble())).toLong().coerceAtMost(maxDelayMs)
                    val sleepTime = Random.nextLong(0, backoff)
                    delay(sleepTime)
                    attempt++
                }
            }
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
