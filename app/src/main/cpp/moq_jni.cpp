#include <jni.h>
#include <string>
#include <memory>
#include <thread>
#include <unordered_map>
#include <mutex>
#include <android/log.h>
#include <quicr/client.h>
#include <quicr/object.h>

#define LOG_TAG "MoqJni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// Global JVM reference
static JavaVM* g_jvm = nullptr;

// Forward declarations
class AndroidPublishTrackHandler;
class AndroidSubscribeTrackHandler;
class AndroidPublishNamespaceHandler;
class AndroidSubscribeNamespaceHandler;
class AndroidMoqClient;

//==============================================================================
// Helper Functions
//==============================================================================

static std::string jstring_to_string(JNIEnv* env, jstring jstr)
{
    if (!jstr) return "";
    const char* chars = env->GetStringUTFChars(jstr, nullptr);
    std::string result(chars);
    env->ReleaseStringUTFChars(jstr, chars);
    return result;
}

static quicr::FullTrackName make_full_track_name(const std::string& track_name_str)
{
    // Parse track name in format "namespace/name" or just "name"
    // Namespace entries are separated by "/"
    std::vector<std::string> parts;
    std::string current;

    for (char c : track_name_str) {
        if (c == '/') {
            if (!current.empty()) {
                parts.push_back(current);
                current.clear();
            }
        } else {
            current += c;
        }
    }

    if (!current.empty()) {
        parts.push_back(current);
    }

    if (parts.empty()) {
        parts.push_back("default");
    }

    // Last part is the track name, everything before is namespace
    std::vector<quicr::Bytes> ns_entries;
    for (size_t i = 0; i + 1 < parts.size(); ++i) {
        ns_entries.emplace_back(parts[i].begin(), parts[i].end());
    }

    if (ns_entries.empty()) {
        ns_entries.emplace_back(std::vector<uint8_t>{'d', 'e', 'f', 'a', 'u', 'l', 't'});
    }

    quicr::TrackNamespace track_ns(ns_entries);
    quicr::Bytes track_name(parts.back().begin(), parts.back().end());

    return quicr::FullTrackName{ track_ns, track_name };
}

static quicr::TrackNamespace make_track_namespace(const std::string& namespace_str)
{
    std::vector<std::string> parts;
    std::string current;

    for (char c : namespace_str) {
        if (c == '/') {
            if (!current.empty()) {
                parts.push_back(current);
                current.clear();
            }
        } else {
            current += c;
        }
    }

    if (!current.empty()) {
        parts.push_back(current);
    }

    std::vector<quicr::Bytes> ns_entries;
    for (const auto& part : parts) {
        ns_entries.emplace_back(part.begin(), part.end());
    }

    if (ns_entries.empty()) {
        ns_entries.emplace_back(std::vector<uint8_t>{'d', 'e', 'f', 'a', 'u', 'l', 't'});
    }

    return quicr::TrackNamespace(ns_entries);
}

//==============================================================================
// Publish Track Handler
//==============================================================================

class AndroidPublishTrackHandler : public quicr::PublishTrackHandler
{
public:
    AndroidPublishTrackHandler(const quicr::FullTrackName& full_track_name,
                               quicr::TrackMode track_mode,
                               uint8_t default_priority,
                               uint32_t default_ttl)
      : quicr::PublishTrackHandler(full_track_name, track_mode, default_priority, default_ttl)
    {
        LOGI("Created AndroidPublishTrackHandler");
    }

    void StatusChanged(Status status) override
    {
        auto track_alias_opt = GetTrackAlias();
        if (!track_alias_opt.has_value()) {
            LOGI("PublishTrack StatusChanged but no alias yet, status: %d", static_cast<int>(status));
            return;
        }

        const auto alias = track_alias_opt.value();

        switch (status) {
            case Status::kOk:
                LOGI("PublishTrack alias %llu is ready to send", alias);
                break;
            case Status::kNoSubscribers:
                LOGI("PublishTrack alias %llu has no subscribers", alias);
                break;
            case Status::kNewGroupRequested:
                LOGI("PublishTrack alias %llu has new group request", alias);
                break;
            case Status::kSubscriptionUpdated:
                LOGI("PublishTrack alias %llu subscription updated", alias);
                break;
            default:
                LOGI("PublishTrack alias %llu status: %d", alias, static_cast<int>(status));
                break;
        }
    }
};

//==============================================================================
// Subscribe Track Handler
//==============================================================================

class AndroidSubscribeTrackHandler : public quicr::SubscribeTrackHandler
{
public:
    AndroidSubscribeTrackHandler(const quicr::FullTrackName& full_track_name,
                                 jobject callback_ref)
      : quicr::SubscribeTrackHandler(full_track_name,
                                      128, // priority
                                      quicr::messages::GroupOrder::kAscending,
                                      quicr::messages::FilterType::kLargestObject,
                                      std::nullopt, // no joining fetch
                                      false) // not publisher-initiated
      , callback_ref_(callback_ref)
    {
        LOGI("Created AndroidSubscribeTrackHandler");
    }

    void ObjectReceived(const quicr::ObjectHeaders& hdr, quicr::BytesSpan data) override
    {
        if (!callback_ref_) {
            LOGE("No callback reference for ObjectReceived");
            return;
        }

        JNIEnv* env = nullptr;
        bool detach = false;

        int getEnvResult = g_jvm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6);
        if (getEnvResult == JNI_EDETACHED) {
            if (g_jvm->AttachCurrentThread(&env, nullptr) != JNI_OK) {
                LOGE("Failed to attach thread for ObjectReceived callback");
                return;
            }
            detach = true;
        }

        // Create DirectByteBuffer from data
        void* data_ptr = const_cast<uint8_t*>(data.data());
        jobject buffer = env->NewDirectByteBuffer(data_ptr, data.size());

        if (!buffer) {
            LOGE("Failed to create DirectByteBuffer");
            if (detach) g_jvm->DetachCurrentThread();
            return;
        }

        // Get track name string
        const auto& ftn = GetFullTrackName();
        std::string track_name_str;
        for (const auto& entry : ftn.name_space.GetEntries()) {
            if (!track_name_str.empty()) track_name_str += "/";
            track_name_str += std::string(entry.begin(), entry.end());
        }
        track_name_str += "/";
        track_name_str += std::string(ftn.name.begin(), ftn.name.end());

        jstring jtrack_name = env->NewStringUTF(track_name_str.c_str());

        // Call Kotlin callback: onObject(trackName: String, groupId: Long, objectId: Long, payload: ByteBuffer)
        jclass callbackClass = env->GetObjectClass(callback_ref_);
        jmethodID methodId = env->GetMethodID(callbackClass, "onObject",
                                              "(Ljava/lang/String;JJLjava/nio/ByteBuffer;)V");

        if (methodId != nullptr) {
            env->CallVoidMethod(callback_ref_, methodId, jtrack_name,
                              static_cast<jlong>(hdr.group_id),
                              static_cast<jlong>(hdr.object_id),
                              buffer);
        } else {
            LOGE("Failed to find onObject method");
        }

        env->DeleteLocalRef(callbackClass);
        env->DeleteLocalRef(jtrack_name);
        env->DeleteLocalRef(buffer);

        if (detach) {
            g_jvm->DetachCurrentThread();
        }
    }

    void StatusChanged(Status status) override
    {
        switch (status) {
            case Status::kOk: {
                if (auto track_alias = GetTrackAlias(); track_alias.has_value()) {
                    LOGI("SubscribeTrack alias %llu is ready to read", track_alias.value());
                }
            } break;
            default:
                LOGI("SubscribeTrack status: %d", static_cast<int>(status));
                break;
        }
    }

private:
    jobject callback_ref_;
};

//==============================================================================
// Publish Namespace Handler
//==============================================================================

class AndroidPublishNamespaceHandler : public quicr::PublishNamespaceHandler
{
public:
    using quicr::PublishNamespaceHandler::PublishNamespaceHandler;

    static std::shared_ptr<AndroidPublishNamespaceHandler> Create(const quicr::TrackNamespace& prefix)
    {
        return std::shared_ptr<AndroidPublishNamespaceHandler>(new AndroidPublishNamespaceHandler(prefix));
    }
};

//==============================================================================
// Subscribe Namespace Handler
//==============================================================================

class AndroidSubscribeNamespaceHandler : public quicr::SubscribeNamespaceHandler
{
public:
    AndroidSubscribeNamespaceHandler(const quicr::TrackNamespace& prefix,
                                     jobject callback_ref)
      : quicr::SubscribeNamespaceHandler(prefix)
      , callback_ref_(callback_ref)
    {
        LOGI("Created AndroidSubscribeNamespaceHandler");
    }

    static std::shared_ptr<AndroidSubscribeNamespaceHandler> Create(const quicr::TrackNamespace& prefix,
                                                                      jobject callback_ref)
    {
        return std::shared_ptr<AndroidSubscribeNamespaceHandler>(
            new AndroidSubscribeNamespaceHandler(prefix, callback_ref));
    }

private:
    jobject callback_ref_;
};

//==============================================================================
// Android MoQ Client
//==============================================================================

class AndroidMoqClient : public quicr::Client
{
public:
    AndroidMoqClient(const quicr::ClientConfig& cfg, jobject kotlin_callback)
      : quicr::Client(cfg)
      , kotlin_callback_ref_(kotlin_callback)
    {
        LOGI("AndroidMoqClient created with URL: %s EndpointID: %s",
             cfg.connect_uri.c_str(), cfg.endpoint_id.c_str());
    }

    void StatusChanged(Status status) override
    {
        LOGI("Connection status changed: %d", static_cast<int>(status));

        if (!kotlin_callback_ref_) {
            LOGE("No Kotlin callback reference available");
            return;
        }

        JNIEnv* env = nullptr;
        bool detach = false;

        int getEnvResult = g_jvm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6);
        if (getEnvResult == JNI_EDETACHED) {
            if (g_jvm->AttachCurrentThread(&env, nullptr) != JNI_OK) {
                LOGE("Failed to attach thread for status callback");
                return;
            }
            detach = true;
        }

        int kotlin_status = mapStatusToKotlin(status);

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

    void PublishNamespaceReceived(const quicr::TrackNamespace& track_namespace,
                                  const quicr::PublishNamespaceAttributes&) override
    {
        auto th = quicr::TrackHash({ track_namespace, {} });
        LOGI("Received ANNOUNCE for namespace_hash: %llu", th.track_namespace_hash);
    }

    void PublishNamespaceDoneReceived(quicr::messages::RequestID rid) override
    {
        LOGI("Received UNANNOUNCE for request_id: %llu", rid);
    }

    void PublishReceived(quicr::ConnectionHandle connection_handle,
                         uint64_t request_id,
                         const quicr::messages::PublishAttributes& publish_attributes,
                         [[maybe_unused]] std::weak_ptr<quicr::SubscribeNamespaceHandler> ns_handler) override
    {
        auto th = quicr::TrackHash(publish_attributes.track_full_name);
        LOGI("Received PUBLISH from relay for track namespace_hash: %llu name_hash: %llu request_id: %llu",
             th.track_namespace_hash, th.track_name_hash, request_id);

        // Auto-accept publishes for now
        // In a full implementation, we would check with the NamespaceSubscriptionCallback

        // Get track name string for callback lookup
        const auto& ftn = publish_attributes.track_full_name;
        std::string track_name_str;
        for (const auto& entry : ftn.name_space.GetEntries()) {
            if (!track_name_str.empty()) track_name_str += "/";
            track_name_str += std::string(entry.begin(), entry.end());
        }
        track_name_str += "/";
        track_name_str += std::string(ftn.name.begin(), ftn.name.end());

        // Check if we should accept this
        std::lock_guard<std::mutex> lock(handlers_mutex_);
        jobject track_callback = kotlin_callback_ref_; // Use main callback for now

        auto handler = std::make_shared<AndroidSubscribeTrackHandler>(
            publish_attributes.track_full_name, track_callback);

        // Store the handler
        subscribe_handlers_[track_name_str] = handler;

        // Accept the PUBLISH
        ResolvePublish(*GetConnectionHandle(),
                     request_id,
                     publish_attributes,
                     { .reason_code = quicr::PublishResponse::ReasonCode::kOk },
                     std::move(handler));

        LOGI("Accepted PUBLISH for track: %s", track_name_str.c_str());
    }

    // Store track handlers
    void add_publish_handler(const std::string& track_name,
                            std::shared_ptr<AndroidPublishTrackHandler> handler)
    {
        std::lock_guard<std::mutex> lock(handlers_mutex_);
        publish_handlers_[track_name] = handler;
    }

    void add_subscribe_handler(const std::string& track_name,
                              std::shared_ptr<AndroidSubscribeTrackHandler> handler)
    {
        std::lock_guard<std::mutex> lock(handlers_mutex_);
        subscribe_handlers_[track_name] = handler;
    }

    void remove_publish_handler(const std::string& track_name)
    {
        std::lock_guard<std::mutex> lock(handlers_mutex_);
        publish_handlers_.erase(track_name);
    }

    void remove_subscribe_handler(const std::string& track_name)
    {
        std::lock_guard<std::mutex> lock(handlers_mutex_);
        subscribe_handlers_.erase(track_name);
    }

    std::shared_ptr<AndroidPublishTrackHandler> get_publish_handler(const std::string& track_name)
    {
        std::lock_guard<std::mutex> lock(handlers_mutex_);
        auto it = publish_handlers_.find(track_name);
        return (it != publish_handlers_.end()) ? it->second : nullptr;
    }

    std::shared_ptr<AndroidSubscribeTrackHandler> get_subscribe_handler(const std::string& track_name)
    {
        std::lock_guard<std::mutex> lock(handlers_mutex_);
        auto it = subscribe_handlers_.find(track_name);
        return (it != subscribe_handlers_.end()) ? it->second : nullptr;
    }

private:
    int mapStatusToKotlin(Status status)
    {
        switch (status) {
            case Status::kNotConnected:
                return 0; // IDLE
            case Status::kConnecting:
                return 1; // CONNECTING
            case Status::kPendingServerSetup:
                return 1; // Still connecting
            case Status::kReady:
                return 2; // CONNECTED
            case Status::kDisconnecting:
                return 3; // DISCONNECTED
            default:
                return 4; // ERROR
        }
    }

    jobject kotlin_callback_ref_;
    std::mutex handlers_mutex_;
    std::unordered_map<std::string, std::shared_ptr<AndroidPublishTrackHandler>> publish_handlers_;
    std::unordered_map<std::string, std::shared_ptr<AndroidSubscribeTrackHandler>> subscribe_handlers_;
};

//==============================================================================
// MoQ Context
//==============================================================================

struct MoqContext {
    std::shared_ptr<AndroidMoqClient> client;
    std::shared_ptr<AndroidPublishNamespaceHandler> publish_ns_handler;
    std::shared_ptr<AndroidSubscribeNamespaceHandler> subscribe_ns_handler;
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

//==============================================================================
// JNI Functions
//==============================================================================

JNIEXPORT jint JNI_OnLoad(JavaVM* vm, void* reserved)
{
    g_jvm = vm;
    LOGI("JNI_OnLoad: JavaVM stored");
    return JNI_VERSION_1_6;
}

extern "C"
JNIEXPORT jlong JNICALL
Java_com_cisco_quadroid_transport_MoqNative_nativeConnect(JNIEnv *env, jobject thiz, jstring url, jstring device_id)
{
    std::string native_url = jstring_to_string(env, url);
    std::string native_device_id = jstring_to_string(env, device_id);
    LOGI("nativeConnect: Connecting to MoQ relay: %s DeviceID: %s", native_url.c_str(), native_device_id.c_str());

    auto context = new MoqContext();
    context->kotlin_callback_ref = env->NewGlobalRef(thiz);

    quicr::ClientConfig config;
    config.connect_uri = native_url;
    config.endpoint_id = native_device_id;
    config.transport_config.debug = true;
    config.transport_config.time_queue_max_duration = 5000;
    config.transport_config.use_reset_wait_strategy = false;
    config.transport_config.tls_cert_filename = "";
    config.transport_config.tls_key_filename = "";

    context->client = std::make_shared<AndroidMoqClient>(config, context->kotlin_callback_ref);

    auto status = context->client->Connect();
    LOGI("nativeConnect: Connect() returned status: %d", static_cast<int>(status));

    if (status != quicr::Transport::Status::kConnecting) {
        LOGE("nativeConnect: Failed to initiate connection");
        delete context;
        return 0;
    }

    return reinterpret_cast<jlong>(context);
}

extern "C"
JNIEXPORT void JNICALL
Java_com_cisco_quadroid_transport_MoqNative_nativeDisconnect(JNIEnv *env, jobject thiz, jlong ptr)
{
    auto context = reinterpret_cast<MoqContext *>(ptr);

    if (context && context->client) {
        LOGI("nativeDisconnect: Disconnecting client");
        context->client->Disconnect();
    }

    delete context;
}

extern "C"
JNIEXPORT void JNICALL
Java_com_cisco_quadroid_transport_MoqNative_nativePublishNamespace(JNIEnv *env, jobject thiz,
                                                                   jlong ptr,
                                                                   jstring namespace_prefix)
{
    auto context = reinterpret_cast<MoqContext *>(ptr);
    std::string prefix = jstring_to_string(env, namespace_prefix);

    if (!context || !context->client) {
        LOGE("nativePublishNamespace: Invalid context");
        return;
    }

    // Wait for connection to be ready
    int retries = 50; // 5 seconds
    while (context->client->GetStatus() != quicr::Transport::Status::kReady && retries-- > 0) {
        std::this_thread::sleep_for(std::chrono::milliseconds(100));
    }

    if (context->client->GetStatus() != quicr::Transport::Status::kReady) {
        LOGE("nativePublishNamespace: Client not ready");
        return;
    }

    LOGI("nativePublishNamespace: prefix=%s", prefix.c_str());

    auto track_ns = make_track_namespace(prefix);
    context->publish_ns_handler = AndroidPublishNamespaceHandler::Create(track_ns);
    context->client->PublishNamespace(context->publish_ns_handler);


    // Wait for OK status
    while (context->publish_ns_handler->GetStatus() != quicr::PublishNamespaceHandler::Status::kOk) {
        std::this_thread::sleep_for(std::chrono::milliseconds(100));
        if (context->publish_ns_handler->GetStatus() == quicr::PublishNamespaceHandler::Status::kError) {
            LOGE("nativePublishNamespace: PublishNamespace failed with error: %d",
                 static_cast<int>(context->publish_ns_handler->GetStatus()));
            return;
        }
    }
    LOGI("nativePublishNamespace: Announced namespace %s", prefix.c_str());
}

extern "C"
JNIEXPORT void JNICALL
Java_com_cisco_quadroid_transport_MoqNative_nativePublish(JNIEnv *env, jobject thiz, jlong ptr,
                                                        jstring track_name, jint priority,
                                                        jboolean use_datagram)
{
    auto context = reinterpret_cast<MoqContext *>(ptr);
    std::string name = jstring_to_string(env, track_name);

    if (!context || !context->client) {
        LOGE("nativePublish: Invalid context");
        return;
    }

    // Wait for connection to be ready
    int retries = 50; // 5 seconds
    while (context->client->GetStatus() != quicr::Transport::Status::kReady && retries-- > 0) {
        std::this_thread::sleep_for(std::chrono::milliseconds(100));
    }

    if (context->client->GetStatus() != quicr::Transport::Status::kReady) {
        LOGE("nativePublish: Client not ready");
        return;
    }

    LOGI("nativePublish: track=%s priority=%d datagram=%d", name.c_str(), priority, use_datagram);

    auto full_track_name = make_full_track_name(name);
    auto track_mode = use_datagram ? quicr::TrackMode::kDatagram : quicr::TrackMode::kStream;

    auto handler = std::make_shared<AndroidPublishTrackHandler>(
        full_track_name,
        track_mode,
        static_cast<uint8_t>(priority),
        3000 // default TTL
    );

    context->client->add_publish_handler(name, handler);
    context->client->PublishTrack(handler);

    LOGI("nativePublish: Published track %s", name.c_str());
}

extern "C"
JNIEXPORT void JNICALL
Java_com_cisco_quadroid_transport_MoqNative_nativeUnpublish(JNIEnv *env, jobject thiz, jlong ptr,
                                                          jstring track_name)
{
    auto context = reinterpret_cast<MoqContext *>(ptr);
    std::string name = jstring_to_string(env, track_name);

    if (!context || !context->client) {
        LOGE("nativeUnpublish: Invalid context");
        return;
    }

    auto handler = context->client->get_publish_handler(name);
    if (handler) {
        context->client->UnpublishTrack(handler);
        context->client->remove_publish_handler(name);
        LOGI("nativeUnpublish: Unpublished track %s", name.c_str());
    }
}

extern "C"
JNIEXPORT void JNICALL
Java_com_cisco_quadroid_transport_MoqNative_nativeSendObject(JNIEnv *env, jobject thiz, jlong ptr,
                                                           jstring track_name, jlong group_id,
                                                           jlong object_id, jobject payload,
                                                           jint payload_size, jint priority,
                                                           jlong delivery_timeout_ms,
                                                           jboolean use_datagram)
{
    auto context = reinterpret_cast<MoqContext *>(ptr);
    std::string name = jstring_to_string(env, track_name);

    if (!context || !context->client) {
        LOGE("nativeSendObject: Invalid context");
        return;
    }

    uint8_t* data = static_cast<uint8_t*>(env->GetDirectBufferAddress(payload));
    if (!data) {
        LOGE("nativeSendObject: Failed to get direct buffer address");
        return;
    }

    auto handler = context->client->get_publish_handler(name);
    if (!handler) {
        LOGE("nativeSendObject: No publish handler for track %s", name.c_str());
        return;
    }

    if (!handler->CanPublish()) {
        // Not ready yet
        return;
    }

    std::optional<uint32_t> ttl_opt;
    if (delivery_timeout_ms > 0) {
        ttl_opt = static_cast<uint32_t>(delivery_timeout_ms);
    }

    quicr::ObjectHeaders headers = {
        .group_id = static_cast<uint64_t>(group_id),
        .object_id = static_cast<uint64_t>(object_id),
        .subgroup_id = 0,
        .payload_length = static_cast<uint64_t>(payload_size),
        .status = quicr::ObjectStatus::kAvailable,
        .priority = static_cast<uint8_t>(priority),
        .ttl = ttl_opt,
        .track_mode = std::nullopt,
        .extensions = std::nullopt,
        .immutable_extensions = std::nullopt
    };

    quicr::BytesSpan data_span(data, payload_size);
    auto status = handler->PublishObject(headers, data_span);

    if (status != quicr::PublishTrackHandler::PublishObjectStatus::kOk) {
        LOGE("nativeSendObject: PublishObject failed with status %d", static_cast<int>(status));
    }
}

extern "C"
JNIEXPORT void JNICALL
Java_com_cisco_quadroid_transport_MoqNative_nativeSubscribe(JNIEnv *env, jobject thiz, jlong ptr,
                                                          jstring track_name, jobject callback)
{
    auto context = reinterpret_cast<MoqContext *>(ptr);
    std::string name = jstring_to_string(env, track_name);

    if (!context || !context->client) {
        LOGE("nativeSubscribe: Invalid context");
        return;
    }

    // Wait for connection to be ready
    int retries = 50; // 5 seconds
    while (context->client->GetStatus() != quicr::Transport::Status::kReady && retries-- > 0) {
        std::this_thread::sleep_for(std::chrono::milliseconds(100));
    }

    if (context->client->GetStatus() != quicr::Transport::Status::kReady) {
        LOGE("nativeSubscribe: Client not ready");
        return;
    }

    LOGI("nativeSubscribe: track=%s", name.c_str());

    auto full_track_name = make_full_track_name(name);
    jobject callback_ref = env->NewGlobalRef(callback);

    auto handler = std::make_shared<AndroidSubscribeTrackHandler>(full_track_name, callback_ref);

    context->client->add_subscribe_handler(name, handler);
    context->client->SubscribeTrack(handler);

    LOGI("nativeSubscribe: Subscribed to track %s", name.c_str());
}

extern "C"
JNIEXPORT void JNICALL
Java_com_cisco_quadroid_transport_MoqNative_nativeUnsubscribe(JNIEnv *env, jobject thiz, jlong ptr,
                                                            jstring track_name)
{
    auto context = reinterpret_cast<MoqContext *>(ptr);
    std::string name = jstring_to_string(env, track_name);

    if (!context || !context->client) {
        LOGE("nativeUnsubscribe: Invalid context");
        return;
    }

    auto handler = context->client->get_subscribe_handler(name);
    if (handler) {
        context->client->UnsubscribeTrack(handler);
        context->client->remove_subscribe_handler(name);
        LOGI("nativeUnsubscribe: Unsubscribed from track %s", name.c_str());
    }
}

extern "C"
JNIEXPORT void JNICALL
Java_com_cisco_quadroid_transport_MoqNative_nativeSubscribeNamespace(JNIEnv *env, jobject thiz,
                                                                   jlong ptr,
                                                                   jstring namespace_prefix,
                                                                   jobject callback)
{
    auto context = reinterpret_cast<MoqContext *>(ptr);
    std::string prefix = jstring_to_string(env, namespace_prefix);

    if (!context || !context->client) {
        LOGE("nativeSubscribeNamespace: Invalid context");
        return;
    }

    LOGI("nativeSubscribeNamespace: prefix=%s", prefix.c_str());

    auto track_ns = make_track_namespace(prefix);
    jobject callback_ref = env->NewGlobalRef(callback);

    context->subscribe_ns_handler = AndroidSubscribeNamespaceHandler::Create(track_ns, callback_ref);
    context->client->SubscribeNamespace(context->subscribe_ns_handler);

    // Wait for OK status
    while (context->subscribe_ns_handler->GetStatus() != quicr::SubscribeNamespaceHandler::Status::kOk) {
        std::this_thread::sleep_for(std::chrono::milliseconds(100));
        if (context->subscribe_ns_handler->GetStatus() == quicr::SubscribeNamespaceHandler::Status::kError) {
            LOGE("nativePublishNamespace: SubscribeNameSpace failed with error: %d",
                 static_cast<int>(context->subscribe_ns_handler->GetStatus()));
            return;
        }
    }
    LOGI("nativeSubscribeNamespace: Subscribed to namespace %s", prefix.c_str());
}

extern "C"
JNIEXPORT void JNICALL
Java_com_cisco_quadroid_transport_MoqNative_nativeSetNamespaceDefaultBehavior(JNIEnv *env,
                                                                            jobject thiz, jlong ptr,
                                                                            jboolean accept_all)
{
    auto context = reinterpret_cast<MoqContext *>(ptr);

    if (!context || !context->client) {
        LOGE("nativeSetNamespaceDefaultBehavior: Invalid context");
        return;
    }

    LOGI("nativeSetNamespaceDefaultBehavior: accept_all=%d", accept_all);
}
