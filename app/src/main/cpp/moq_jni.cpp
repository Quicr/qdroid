#include <jni.h>
#include <string>
#include <memory>
#include <thread>
#include <android/log.h>
#include <quicr/client.h>

#define LOG_TAG "MoqJni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// Global JVM reference to call back into Kotlin
static JavaVM* g_jvm = nullptr;

// Custom MoQ client that extends quicr::Client and handles status callbacks
class AndroidMoqClient : public quicr::Client {
public:
    AndroidMoqClient(const quicr::ClientConfig& cfg, jobject kotlin_callback)
        : quicr::Client(cfg)
        , kotlin_callback_ref_(kotlin_callback)
    {
        LOGI("AndroidMoqClient created with URL: %s", cfg.connect_uri.c_str());
    }

    // Override StatusChanged to bubble status up to Kotlin
    void StatusChanged(Status status) override {
        LOGI("Connection status changed: %d", static_cast<int>(status));

        if (!kotlin_callback_ref_) {
            LOGE("No Kotlin callback reference available");
            return;
        }

        JNIEnv* env = nullptr;
        bool detach = false;

        // Get JNI environment for the current thread
        int getEnvResult = g_jvm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6);
        if (getEnvResult == JNI_EDETACHED) {
            if (g_jvm->AttachCurrentThread(&env, nullptr) != JNI_OK) {
                LOGE("Failed to attach thread for status callback");
                return;
            }
            detach = true;
        }

        // Map libquicr status to MoqConnectionStatus values
        int kotlin_status = mapStatusToKotlin(status);

        // Call onConnectionStatusChanged(status: Int)
        jclass callbackClass = env->GetObjectClass(kotlin_callback_ref_);
        jmethodID methodId = env->GetMethodID(callbackClass, "onConnectionStatusChanged", "(I)V");

        if (methodId != nullptr) {
            env->CallVoidMethod(kotlin_callback_ref_, methodId, kotlin_status);
            LOGI("Called Kotlin status callback with status: %d", kotlin_status);
        } else {
            LOGE("Failed to find onConnectionStatusChanged method");
        }

        env->DeleteLocalRef(callbackClass);

        if (detach) {
            g_jvm->DetachCurrentThread();
        }
    }

private:
    // Map quicr::Transport::Status to MoqConnectionStatus enum values
    int mapStatusToKotlin(Status status) {
        // MoqConnectionStatus: IDLE(0), CONNECTING(1), CONNECTED(2), DISCONNECTED(3), ERROR(4)
        switch (status) {
            case Status::kNotConnected:
                return 0; // IDLE
            case Status::kConnecting:
                return 1; // CONNECTING
            case Status::kPendingServerSetup:
                return 1; // Still connecting, waiting for server
            case Status::kReady:
                return 2; // CONNECTED
            case Status::kDisconnecting:
                return 3; // DISCONNECTED
            default:
                return 4; // ERROR
        }
    }

    jobject kotlin_callback_ref_;
};

// Context to manage the client lifecycle
struct MoqContext {
    std::shared_ptr<AndroidMoqClient> client;
    jobject kotlin_callback_ref = nullptr;

    ~MoqContext() {
        JNIEnv* env = nullptr;
        if (g_jvm && g_jvm->AttachCurrentThread(&env, nullptr) == JNI_OK) {
            if (kotlin_callback_ref) {
                env->DeleteGlobalRef(kotlin_callback_ref);
            }
        }
    }
};

JNIEXPORT jint JNI_OnLoad(JavaVM* vm, void* reserved) {
    g_jvm = vm;
    LOGI("JNI_OnLoad: JavaVM stored");
    return JNI_VERSION_1_6;
}

extern "C"
JNIEXPORT jlong JNICALL
Java_com_cisco_quadroid_transport_MoqNative_nativeConnect(JNIEnv *env, jobject thiz, jstring url) {
    const char *native_url = env->GetStringUTFChars(url, nullptr);
    LOGI("nativeConnect: Connecting to MoQ relay: %s", native_url);

    auto context = new MoqContext();

    // Create a global reference to the Kotlin object for callbacks
    context->kotlin_callback_ref = env->NewGlobalRef(thiz);

    // Configure the libquicr client
    quicr::ClientConfig config;
    config.connect_uri = std::string(native_url);
    config.endpoint_id = "android-moq-client";
    config.transport_config.debug = true;
    config.transport_config.time_queue_max_duration = 5000;
    config.transport_config.use_reset_wait_strategy = false;
    config.transport_config.tls_cert_filename = "";
    config.transport_config.tls_key_filename = "";

    // Create the custom client
    context->client = std::make_shared<AndroidMoqClient>(config, context->kotlin_callback_ref);

    // Actually connect to the relay
    auto status = context->client->Connect();
    LOGI("nativeConnect: Connect() returned status: %d", static_cast<int>(status));

    if (status != quicr::Transport::Status::kConnecting) {
        LOGE("nativeConnect: Failed to initiate connection");
        env->ReleaseStringUTFChars(url, native_url);
        delete context;
        return 0;
    }

    env->ReleaseStringUTFChars(url, native_url);
    return reinterpret_cast<jlong>(context);
}

extern "C"
JNIEXPORT void JNICALL
Java_com_cisco_quadroid_transport_MoqNative_nativeDisconnect(JNIEnv *env, jobject thiz, jlong ptr) {
    auto context = reinterpret_cast<MoqContext *>(ptr);

    if (context && context->client) {
        LOGI("nativeDisconnect: Disconnecting client");
        context->client->Disconnect();
    }

    delete context;
}

extern "C"
JNIEXPORT void JNICALL
Java_com_cisco_quadroid_transport_MoqNative_nativePublish(JNIEnv *env, jobject thiz, jlong ptr,
                                                        jstring track_name, jint priority,
                                                        jboolean use_datagram) {
    auto context = reinterpret_cast<MoqContext *>(ptr);
    const char *name = env->GetStringUTFChars(track_name, nullptr);

    // TODO: Implement publish with correct boring2 branch API
    LOGI("nativePublish: track=%s priority=%d datagram=%d", name, priority, use_datagram);

    env->ReleaseStringUTFChars(track_name, name);
}

extern "C"
JNIEXPORT void JNICALL
Java_com_cisco_quadroid_transport_MoqNative_nativeSendObject(JNIEnv *env, jobject thiz, jlong ptr,
                                                           jstring track_name, jlong group_id,
                                                           jlong object_id, jobject payload,
                                                           jint payload_size, jint priority,
                                                           jlong delivery_timeout_ms,
                                                           jboolean use_datagram) {
    auto context = reinterpret_cast<MoqContext *>(ptr);
    const char *name = env->GetStringUTFChars(track_name, nullptr);

    uint8_t* data = static_cast<uint8_t*>(env->GetDirectBufferAddress(payload));
    if (!data) {
        LOGE("nativeSendObject: Failed to get direct buffer address");
        env->ReleaseStringUTFChars(track_name, name);
        return;
    }

    // TODO: Implement send object with correct API
    LOGI("nativeSendObject: track=%s group=%lld obj=%lld size=%d",
         name, (long long)group_id, (long long)object_id, payload_size);

    env->ReleaseStringUTFChars(track_name, name);
}

extern "C"
JNIEXPORT void JNICALL
Java_com_cisco_quadroid_transport_MoqNative_nativeSubscribe(JNIEnv *env, jobject thiz, jlong ptr,
                                                          jstring track_name, jobject callback) {
    auto context = reinterpret_cast<MoqContext *>(ptr);
    const char *name = env->GetStringUTFChars(track_name, nullptr);

    // TODO: Implement subscribe with correct API
    LOGI("nativeSubscribe: track=%s", name);

    env->ReleaseStringUTFChars(track_name, name);
}

extern "C"
JNIEXPORT void JNICALL
Java_com_cisco_quadroid_transport_MoqNative_nativeSubscribeNamespace(JNIEnv *env, jobject thiz,
                                                                   jlong ptr,
                                                                   jstring namespace_prefix,
                                                                   jobject callback) {
    auto context = reinterpret_cast<MoqContext *>(ptr);
    const char *prefix = env->GetStringUTFChars(namespace_prefix, nullptr);

    // TODO: Implement subscribe namespace with correct API
    LOGI("nativeSubscribeNamespace: prefix=%s", prefix);

    env->ReleaseStringUTFChars(namespace_prefix, prefix);
}

extern "C"
JNIEXPORT void JNICALL
Java_com_cisco_quadroid_transport_MoqNative_nativeSetNamespaceDefaultBehavior(JNIEnv *env,
                                                                            jobject thiz, jlong ptr,
                                                                            jboolean accept_all) {
    auto context = reinterpret_cast<MoqContext *>(ptr);

    // TODO: Implement with correct API
    LOGI("nativeSetNamespaceDefaultBehavior: accept_all=%d", accept_all);
}
