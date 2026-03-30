package com.cisco.quadroid.transport

import java.nio.ByteBuffer

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

    override fun connect(url: String) {
        nativePtr = nativeConnect(url)
    }

    override fun disconnect() {
        if (nativePtr != 0L) {
            nativeDisconnect(nativePtr)
            nativePtr = 0
        }
    }

    override fun publish(trackName: String, options: PublishOptions) {
        nativePublish(nativePtr, trackName, options.priority, options.useDatagram)
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
        nativeSubscribe(nativePtr, trackName, callback)
    }

    override fun subscribeNamespace(namespacePrefix: String, callback: NamespaceSubscriptionCallback) {
        nativeSubscribeNamespace(nativePtr, namespacePrefix, callback)
    }

    override fun setNamespaceDefaultBehavior(acceptAll: Boolean) {
        nativeSetNamespaceDefaultBehavior(nativePtr, acceptAll)
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
