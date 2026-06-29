// SPDX-FileCopyrightText: Copyright (c) 2025 Cisco Systems
// SPDX-License-Identifier: BSD-2-Clause

#include <jni.h>
#include <string>
#include <memory>
#include <thread>
#include <unordered_map>
#include <mutex>
#include <vector>
#include <android/log.h>
#include <quicr/client.h>
#include <quicr/object.h>
#include "moq_util.h"
#include "video_jitter_buffer.h"
#include "audio_jitter_buffer.h"
#include "video_framer.h"
#include "audio_framer.h"
#include "loc_wrapper.h"

#define LOG_TAG "MoqJni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// Global JVM reference (non-static to allow access from video_jitter_buffer.cpp)
JavaVM* g_jvm = nullptr;

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

    void EndSubgroup(uint64_t group_id, uint64_t subgroup_id, bool completed) override{
        LOGI("PublishTrack EndSubgroup: group_id=%llu subgroup_id=%llu completed=%d",
             group_id, subgroup_id, completed);

        // Call base class implementation to properly signal QUICR
        quicr::PublishTrackHandler::EndSubgroup(group_id, subgroup_id, completed);
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
                                      std::monostate{}, // no filter (latest objects)
                                      std::nullopt, // no joining fetch
                                      false) // not publisher-initiated
      , callback_ref_(callback_ref)
    {
        LOGI("Created AndroidSubscribeTrackHandler");
    }

    void ObjectReceived(const quicr::ObjectHeaders& hdr, quicr::BytesSpan data,
                       std::optional<quicr::messages::StreamHeaderProperties> = std::nullopt) override
    {
        LOGI("ObjectReceived: group_id=%llu object_id=%llu payload_length=%llu",
             hdr.group_id, hdr.object_id, hdr.payload_length);

        if (!callback_ref_) {
            LOGE("No callback reference for ObjectReceived");
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

        // Unwrap LOC container
        LocUnwrapResult locResult = LocWrapper::unwrap(data.data(), data.size());

        if (!locResult.success) {
            LOGW("LOC unwrap failed for %s: %s",
                 track_name_str.c_str(), locResult.errorMessage.c_str());
        }

        // Use unwrapped data or fall back to original
        const uint8_t* payload_data = locResult.success ?
            locResult.payload.data() : data.data();
        size_t payload_size = locResult.success ?
            locResult.payload.size() : data.size();

        // Check if this is a video track - route to jitter buffer
        if (track_name_str.find("video") != std::string::npos) {
            LOGI("Video track detected: %s", track_name_str.c_str());
            auto& manager = VideoJitterBufferManager::getInstance();
            if (auto* buffer = manager.getBuffer(track_name_str)) {
                // Video frame goes directly to jitter buffer (C++ optimization)
                // Use LOC metadata if available, otherwise derive from MoQ headers
                bool isKeyframe = locResult.success ?
                    locResult.metadata.isKeyframe : (hdr.object_id == 0);
                buffer->addFrame(hdr.group_id, hdr.object_id,
                               payload_data, payload_size, isKeyframe);
                return;  // Don't call Kotlin callback for video
            } else {
                LOGW("No jitter buffer found for video track: %s", track_name_str.c_str());
            }
            // If no jitter buffer, fall through to old path (for compatibility)
        }

        // Check if this is an audio track - route to jitter buffer
        if (track_name_str.find("audio") != std::string::npos) {
            LOGI("Audio track detected: %s", track_name_str.c_str());
            auto& manager = AudioJitterBufferManager::getInstance();
            if (auto* buffer = manager.getBuffer(track_name_str)) {
                // Audio packet goes directly to jitter buffer (C++ optimization)
                buffer->addPacket(hdr.group_id, hdr.object_id,
                                payload_data, payload_size);
                return;  // Don't call Kotlin callback for audio
            } else {
                LOGW("No jitter buffer found for audio track: %s", track_name_str.c_str());
            }
            // If no jitter buffer, fall through to old path (for compatibility)
        }

        // Fallback path: call Kotlin callback immediately (for non-jitter-buffered tracks)
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

        // Create DirectByteBuffer from unwrapped data
        void* data_ptr = const_cast<uint8_t*>(payload_data);
        jobject buffer = env->NewDirectByteBuffer(data_ptr, payload_size);

        if (!buffer) {
            LOGE("Failed to create DirectByteBuffer");
            if (detach) g_jvm->DetachCurrentThread();
            return;
        }

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
      , callback_valid_(true)
    {
        LOGI("Created AndroidSubscribeNamespaceHandler");
    }

    static std::shared_ptr<AndroidSubscribeNamespaceHandler> Create(const quicr::TrackNamespace& prefix,
                                                                      jobject callback_ref)
    {
        return std::shared_ptr<AndroidSubscribeNamespaceHandler>(
            new AndroidSubscribeNamespaceHandler(prefix, callback_ref));
    }

    jobject GetCallbackRef() const {
        return callback_valid_.load() ? callback_ref_ : nullptr;
    }

    void InvalidateCallback() {
        callback_valid_.store(false);
    }

private:
    jobject callback_ref_;
    std::atomic<bool> callback_valid_;
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

        // Invoke onMatch if ns_handler is available
        if (auto shared_ns_handler = ns_handler.lock()) {
            auto android_ns_handler = std::static_pointer_cast<AndroidSubscribeNamespaceHandler>(shared_ns_handler);
            jobject cb_ref = android_ns_handler->GetCallbackRef();
            if (cb_ref) {
                JNIEnv* env = nullptr;
                bool detach = false;
                if (g_jvm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) == JNI_EDETACHED) {
                    g_jvm->AttachCurrentThread(&env, nullptr);
                    detach = true;
                }

                if (env) {
                    jclass callbackClass = env->GetObjectClass(cb_ref);
                    jmethodID methodId = env->GetMethodID(callbackClass, "onMatch", "(Ljava/lang/String;)Z");
                    if (methodId) {
                        jstring jtrack_name = env->NewStringUTF(track_name_str.c_str());
                        env->CallBooleanMethod(cb_ref, methodId, jtrack_name);
                        env->DeleteLocalRef(jtrack_name);
                    }
                    env->DeleteLocalRef(callbackClass);
                }

                if (detach) g_jvm->DetachCurrentThread();
            }
        }

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
    std::unordered_map<std::string, std::shared_ptr<AndroidPublishNamespaceHandler>> publish_ns_handlers;
    std::unordered_map<std::string, std::shared_ptr<AndroidSubscribeNamespaceHandler>> subscribe_ns_handlers;
    std::mutex ns_handlers_mutex;
    jobject kotlin_callback_ref = nullptr;

    // Framer management
    std::unordered_map<std::string, std::unique_ptr<VideoFramer>> videoFramers;
    std::unordered_map<std::string, std::unique_ptr<AudioFramer>> audioFramers;
    std::mutex framers_mutex;

    ~MoqContext() {
        JNIEnv* env = nullptr;
        if (g_jvm && g_jvm->AttachCurrentThread(&env, nullptr) == JNI_OK) {
            if (kotlin_callback_ref) {
                env->DeleteGlobalRef(kotlin_callback_ref);
            }

            // Clean up subscribe namespace handler callbacks
            for (auto& [ns_prefix, handler] : subscribe_ns_handlers) {
                if (handler && handler->GetCallbackRef()) {
                    env->DeleteGlobalRef(handler->GetCallbackRef());
                }
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
    auto handler = AndroidPublishNamespaceHandler::Create(track_ns);

    {
        std::lock_guard<std::mutex> lock(context->ns_handlers_mutex);
        context->publish_ns_handlers[prefix] = handler;
    }

    context->client->PublishNamespace(handler);

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

    // Prepare LOC metadata
    LocMetadata locMeta;
    locMeta.groupId = static_cast<uint64_t>(group_id);
    locMeta.objectId = static_cast<uint32_t>(object_id);
    locMeta.captureTimestampUs = std::chrono::duration_cast<std::chrono::microseconds>(
        std::chrono::system_clock::now().time_since_epoch()
    ).count();

    // Determine media type from track name
    if (name.find("video") != std::string::npos) {
        locMeta.mediaType = MediaType::Video;
        locMeta.isKeyframe = (object_id == 0);  // Object 0 is keyframe
    } else if (name.find("audio") != std::string::npos) {
        locMeta.mediaType = MediaType::Audio;
        locMeta.isKeyframe = false;
    } else {
        // Default to video
        locMeta.mediaType = MediaType::Video;
        locMeta.isKeyframe = false;
    }

    // Wrap codec data with LOC
    std::vector<uint8_t> locWrappedData = LocWrapper::wrap(data, payload_size, locMeta);

    std::optional<uint32_t> ttl_opt;
    if (delivery_timeout_ms > 0) {
        ttl_opt = static_cast<uint32_t>(delivery_timeout_ms);
    }

    quicr::ObjectHeaders headers = {
        .group_id = static_cast<uint64_t>(group_id),
        .object_id = static_cast<uint64_t>(object_id),
        .subgroup_id = 0,
        .payload_length = static_cast<uint64_t>(locWrappedData.size()),  // Use LOC-wrapped size
        .status = quicr::ObjectStatus::kAvailable,
        .priority = static_cast<uint8_t>(priority),
        .ttl = ttl_opt,
        .track_mode = std::nullopt,
        .extensions = std::nullopt,
        .immutable_extensions = std::nullopt
    };

    try {
        quicr::BytesSpan data_span(locWrappedData.data(), locWrappedData.size());  // Use LOC-wrapped data
        auto status = handler->PublishObject(headers, data_span);

        if (status != quicr::PublishTrackHandler::PublishObjectStatus::kOk) {
            LOGE("nativeSendObject: PublishObject failed with status %d", static_cast<int>(status));
        } else {
            // Log only occasionally to avoid spamming
            if (object_id % 100 == 0) {
                LOGI("nativeSendObject: Sent object to track %s, with status kOK", name.c_str());
            }
        }
    } catch (const std::exception& e) {
        LOGE("nativeSendObject: Exception in PublishObject: %s", e.what());
    } catch (...) {
        LOGE("nativeSendObject: Unknown exception in PublishObject");
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

    auto handler = AndroidSubscribeNamespaceHandler::Create(track_ns, callback_ref);

    {
        std::lock_guard<std::mutex> lock(context->ns_handlers_mutex);
        context->subscribe_ns_handlers[prefix] = handler;
    }

    context->client->SubscribeNamespace(handler);

    // Wait for OK status
    int retries = 50; // 5 seconds
    while (handler->GetStatus() != quicr::SubscribeNamespaceHandler::Status::kOk && retries-- > 0) {
        std::this_thread::sleep_for(std::chrono::milliseconds(100));
        if (handler->GetStatus() == quicr::SubscribeNamespaceHandler::Status::kError) {
            LOGE("nativeSubscribeNamespace: SubscribeNamespace failed with error status");
            return;
        }
    }

    if (handler->GetStatus() == quicr::SubscribeNamespaceHandler::Status::kOk) {
        LOGI("nativeSubscribeNamespace: Status is OK");
    } else {
        LOGE("nativeSubscribeNamespace: SubscribeNamespace failed to reach OK status");
        return;
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

extern "C"
JNIEXPORT void JNICALL
Java_com_cisco_quadroid_transport_MoqNative_nativeUnpublishNamespace(JNIEnv *env, jobject thiz,
                                                                     jlong ptr,
                                                                     jstring namespace_prefix)
{
    auto context = reinterpret_cast<MoqContext *>(ptr);
    std::string prefix = jstring_to_string(env, namespace_prefix);

    if (!context || !context->client) {
        LOGE("nativeUnpublishNamespace: Invalid context");
        return;
    }

    std::shared_ptr<AndroidPublishNamespaceHandler> handler;

    {
        std::lock_guard<std::mutex> lock(context->ns_handlers_mutex);
        auto it = context->publish_ns_handlers.find(prefix);
        if (it != context->publish_ns_handlers.end()) {
            handler = it->second;
            context->publish_ns_handlers.erase(it);
        }
    }

    if (handler) {
        LOGI("nativeUnpublishNamespace: Unpublishing namespace %s", prefix.c_str());
        context->client->PublishNamespaceDone(handler);
        LOGI("nativeUnpublishNamespace: Successfully unpublished namespace %s", prefix.c_str());
    } else {
        LOGE("nativeUnpublishNamespace: No active namespace publish for prefix: %s", prefix.c_str());
    }
}
extern "C"
JNIEXPORT void JNICALL
Java_com_cisco_quadroid_transport_MoqNative_nativeUnsubscribeNamespace(JNIEnv *env, jobject thiz,
                                                                      jlong ptr,
                                                                      jstring namespace_prefix)
{
    auto context = reinterpret_cast<MoqContext *>(ptr);
    std::string prefix = jstring_to_string(env, namespace_prefix);

    if (!context || !context->client) {
        LOGE("nativeUnsubscribeNamespace: Invalid context");
        return;
    }

    std::shared_ptr<AndroidSubscribeNamespaceHandler> handler;

    {
        std::lock_guard<std::mutex> lock(context->ns_handlers_mutex);
        auto it = context->subscribe_ns_handlers.find(prefix);
        if (it != context->subscribe_ns_handlers.end()) {
            handler = it->second;
            context->subscribe_ns_handlers.erase(it);
        }
    }

    if (handler) {
        LOGI("nativeUnsubscribeNamespace: Unsubscribing from namespace %s", prefix.c_str());

        // Get the callback ref before invalidating
        jobject callback_to_delete = handler->GetCallbackRef();

        // Invalidate the callback first so pending messages won't try to use it
        handler->InvalidateCallback();

        // Unsubscribe from the namespace
        context->client->UnsubscribeNamespace(handler);

        // Clean up the callback reference
        if (callback_to_delete) {
            JNIEnv* env_for_cleanup = nullptr;
            bool detach = false;
            if (g_jvm->GetEnv(reinterpret_cast<void**>(&env_for_cleanup), JNI_VERSION_1_6) == JNI_EDETACHED) {
                g_jvm->AttachCurrentThread(&env_for_cleanup, nullptr);
                detach = true;
            }

            if (env_for_cleanup) {
                env_for_cleanup->DeleteGlobalRef(callback_to_delete);
            }

            if (detach) g_jvm->DetachCurrentThread();
        }

        LOGI("nativeUnsubscribeNamespace: Successfully unsubscribed from namespace %s", prefix.c_str());
    } else {
        LOGE("nativeUnsubscribeNamespace: No active namespace subscription for prefix: %s", prefix.c_str());
    }
}

extern "C"
JNIEXPORT void JNICALL
Java_com_cisco_quadroid_transport_MoqNative_nativeEndSubGroup(JNIEnv *env, jobject thiz, jlong ptr, jstring track_name, jlong group_id,
                                                              jlong subgroup_id, jboolean completed) {
    auto context = reinterpret_cast<MoqContext *>(ptr);

    if (!context || !context->client) {
        LOGE("nativeEndSubGroup: Invalid context");
        return;
    }
    std::string name = jstring_to_string(env, track_name);

    auto handler = context->client->get_publish_handler(name);
    if (!handler) {
        LOGE("nativeEndSubGroup: No publish handler for track %s", name.c_str());
        return;
    }
    handler->EndSubgroup(group_id, subgroup_id, completed);
}

//==============================================================================
// Video Jitter Buffer JNI Methods
//==============================================================================

extern "C"
JNIEXPORT void JNICALL
Java_com_cisco_quadroid_transport_MoqNative_nativeCreateVideoJitterBuffer(
    JNIEnv* env, jobject thiz, jstring track_name, jobject callback)
{
    std::string trackName = jstring_to_string(env, track_name);
    LOGI("nativeCreateVideoJitterBuffer: track=%s", trackName.c_str());

    if (!callback) {
        LOGE("nativeCreateVideoJitterBuffer: Null callback provided");
        return;
    }

    // Create global reference for callback (will be used by decode thread)
    jobject callbackGlobalRef = env->NewGlobalRef(callback);
    if (!callbackGlobalRef) {
        LOGE("nativeCreateVideoJitterBuffer: Failed to create global ref");
        return;
    }

    // Create jitter buffer via manager
    auto& manager = VideoJitterBufferManager::getInstance();
    manager.createBuffer(trackName, callbackGlobalRef);

    LOGI("nativeCreateVideoJitterBuffer: Created buffer for track %s", trackName.c_str());
}

extern "C"
JNIEXPORT void JNICALL
Java_com_cisco_quadroid_transport_MoqNative_nativeDestroyVideoJitterBuffer(
    JNIEnv* env, jobject thiz, jstring track_name)
{
    std::string trackName = jstring_to_string(env, track_name);
    LOGI("nativeDestroyVideoJitterBuffer: track=%s", trackName.c_str());

    auto& manager = VideoJitterBufferManager::getInstance();

    // Get buffer to retrieve callback ref for cleanup BEFORE destroying the buffer
    jobject callbackRef = nullptr;
    if (auto* buffer = manager.getBuffer(trackName)) {
        callbackRef = buffer->getCallbackRef();
    }

    // Destroy the buffer (stops decode thread, cleans up resources)
    manager.destroyBuffer(trackName);

    // Delete the global reference we created in nativeCreateVideoJitterBuffer
    if (callbackRef) {
        env->DeleteGlobalRef(callbackRef);
        LOGI("nativeDestroyVideoJitterBuffer: Deleted global callback ref for track %s", trackName.c_str());
    }

    LOGI("nativeDestroyVideoJitterBuffer: Destroyed buffer for track %s", trackName.c_str());
}

extern "C"
JNIEXPORT jobject JNICALL
Java_com_cisco_quadroid_transport_MoqNative_nativeGetVideoJitterBufferStats(
    JNIEnv* env, jobject thiz, jstring track_name)
{
    std::string trackName = jstring_to_string(env, track_name);

    auto& manager = VideoJitterBufferManager::getInstance();
    auto* buffer = manager.getBuffer(trackName);

    if (!buffer) {
        LOGW("nativeGetVideoJitterBufferStats: No buffer for track %s", trackName.c_str());
        return nullptr;
    }

    auto stats = buffer->getStats();

    // Create VideoJitterBufferStats object
    jclass statsClass = env->FindClass("com/cisco/quadroid/transport/VideoJitterBufferStats");
    if (!statsClass) {
        LOGE("nativeGetVideoJitterBufferStats: Failed to find VideoJitterBufferStats class");
        return nullptr;
    }

    jmethodID statsCtor = env->GetMethodID(statsClass, "<init>", "(JJJJJJ)V");
    if (!statsCtor) {
        LOGE("nativeGetVideoJitterBufferStats: Failed to find constructor");
        env->DeleteLocalRef(statsClass);
        return nullptr;
    }

    jobject statsObj = env->NewObject(statsClass, statsCtor,
        static_cast<jlong>(stats.framesReceived),
        static_cast<jlong>(stats.framesOutput),
        static_cast<jlong>(stats.framesDropped),
        static_cast<jlong>(stats.groupsSkipped),
        static_cast<jlong>(stats.avgLatencyUs),
        static_cast<jlong>(stats.maxLatencyUs)
    );

    env->DeleteLocalRef(statsClass);
    return statsObj;
}

//==============================================================================
// Audio Jitter Buffer JNI Methods
//==============================================================================

extern "C"
JNIEXPORT void JNICALL
Java_com_cisco_quadroid_transport_MoqNative_nativeCreateAudioJitterBuffer(
    JNIEnv* env, jobject thiz, jstring track_name, jstring track_key)
{
    std::string trackName = jstring_to_string(env, track_name);
    std::string trackKeyStr = jstring_to_string(env, track_key);
    LOGI("nativeCreateAudioJitterBuffer: track=%s, key=%s", trackName.c_str(), trackKeyStr.c_str());

    // Create jitter buffer via manager
    auto& manager = AudioJitterBufferManager::getInstance();
    manager.createBuffer(trackName, trackKeyStr);

    LOGI("nativeCreateAudioJitterBuffer: Created buffer for track %s", trackName.c_str());
}

extern "C"
JNIEXPORT void JNICALL
Java_com_cisco_quadroid_transport_MoqNative_nativeDestroyAudioJitterBuffer(
    JNIEnv* env, jobject thiz, jstring track_name)
{
    std::string trackName = jstring_to_string(env, track_name);
    LOGI("nativeDestroyAudioJitterBuffer: track=%s", trackName.c_str());

    auto& manager = AudioJitterBufferManager::getInstance();
    manager.destroyBuffer(trackName);

    LOGI("nativeDestroyAudioJitterBuffer: Destroyed buffer for track %s", trackName.c_str());
}

extern "C"
JNIEXPORT jobject JNICALL
Java_com_cisco_quadroid_transport_MoqNative_nativeGetAudioJitterBufferStats(
    JNIEnv* env, jobject thiz, jstring track_name)
{
    std::string trackName = jstring_to_string(env, track_name);

    auto& manager = AudioJitterBufferManager::getInstance();
    auto* buffer = manager.getBuffer(trackName);

    if (!buffer) {
        LOGW("nativeGetAudioJitterBufferStats: No buffer for track %s", trackName.c_str());
        return nullptr;
    }

    auto stats = buffer->getStats();

    // Create AudioJitterBufferStats object
    jclass statsClass = env->FindClass("com/cisco/quadroid/transport/AudioJitterBufferStats");
    if (!statsClass) {
        LOGE("nativeGetAudioJitterBufferStats: Failed to find AudioJitterBufferStats class");
        return nullptr;
    }

    jmethodID statsCtor = env->GetMethodID(statsClass, "<init>", "(JJJJJJ)V");
    if (!statsCtor) {
        LOGE("nativeGetAudioJitterBufferStats: Failed to find constructor");
        env->DeleteLocalRef(statsClass);
        return nullptr;
    }

    jobject statsObj = env->NewObject(statsClass, statsCtor,
        static_cast<jlong>(stats.packetsReceived),
        static_cast<jlong>(stats.packetsOutput),
        static_cast<jlong>(stats.packetsDropped),
        static_cast<jlong>(stats.groupsSkipped),
        static_cast<jlong>(stats.avgLatencyUs),
        static_cast<jlong>(stats.maxLatencyUs)
    );

    env->DeleteLocalRef(statsClass);
    return statsObj;
}

//==============================================================================
// Video Framer JNI Methods
//==============================================================================

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_cisco_quadroid_transport_MoqNative_nativeCreateVideoFramer(
    JNIEnv* env, jobject thiz, jlong ptr, jstring track_name)
{
    auto context = reinterpret_cast<MoqContext*>(ptr);
    std::string trackName = jstring_to_string(env, track_name);

    if (!context || !context->client) {
        LOGE("nativeCreateVideoFramer: Invalid context");
        return JNI_FALSE;
    }

    // Get publish handler for this track
    auto handler = context->client->get_publish_handler(trackName);
    if (!handler) {
        LOGE("nativeCreateVideoFramer: No publish handler for track %s", trackName.c_str());
        return JNI_FALSE;
    }

    std::lock_guard<std::mutex> lock(context->framers_mutex);

    // Create video framer
    try {
        auto framer = std::make_unique<VideoFramer>(trackName, handler);
        context->videoFramers[trackName] = std::move(framer);
        LOGI("nativeCreateVideoFramer: Created framer for track %s", trackName.c_str());
        return JNI_TRUE;
    } catch (const std::exception& e) {
        LOGE("nativeCreateVideoFramer: Exception: %s", e.what());
        return JNI_FALSE;
    }
}

extern "C"
JNIEXPORT void JNICALL
Java_com_cisco_quadroid_transport_MoqNative_nativeProcessVideoFrame(
    JNIEnv* env, jobject thiz, jlong ptr, jstring track_name,
    jobject buffer, jint size, jboolean is_keyframe, jlong timestamp_us)
{
    auto context = reinterpret_cast<MoqContext*>(ptr);
    std::string trackName = jstring_to_string(env, track_name);

    if (!context) {
        LOGE("nativeProcessVideoFrame: Invalid context");
        return;
    }

    // Get direct buffer address (zero-copy)
    uint8_t* data = static_cast<uint8_t*>(env->GetDirectBufferAddress(buffer));
    if (!data) {
        LOGE("nativeProcessVideoFrame: Failed to get direct buffer address");
        return;
    }

    // Get video framer
    VideoFramer* framer = nullptr;
    {
        std::lock_guard<std::mutex> lock(context->framers_mutex);
        auto it = context->videoFramers.find(trackName);
        if (it != context->videoFramers.end()) {
            framer = it->second.get();
        }
    }

    if (!framer) {
        LOGE("nativeProcessVideoFrame: No framer found for track %s", trackName.c_str());
        return;
    }

    // Process the frame
    framer->processFrame(data, static_cast<size_t>(size),
                        is_keyframe == JNI_TRUE,
                        static_cast<uint64_t>(timestamp_us));
}

extern "C"
JNIEXPORT void JNICALL
Java_com_cisco_quadroid_transport_MoqNative_nativeDestroyVideoFramer(
    JNIEnv* env, jobject thiz, jlong ptr, jstring track_name)
{
    auto context = reinterpret_cast<MoqContext*>(ptr);
    std::string trackName = jstring_to_string(env, track_name);

    if (!context) {
        LOGE("nativeDestroyVideoFramer: Invalid context");
        return;
    }

    std::lock_guard<std::mutex> lock(context->framers_mutex);
    auto it = context->videoFramers.find(trackName);
    if (it != context->videoFramers.end()) {
        context->videoFramers.erase(it);
        LOGI("nativeDestroyVideoFramer: Destroyed framer for track %s", trackName.c_str());
    } else {
        LOGW("nativeDestroyVideoFramer: No framer found for track %s", trackName.c_str());
    }
}

//==============================================================================
// Audio Framer JNI Methods
//==============================================================================

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_cisco_quadroid_transport_MoqNative_nativeCreateAudioFramer(
    JNIEnv* env, jobject thiz, jlong ptr, jstring track_name, jboolean vad_enabled)
{
    auto context = reinterpret_cast<MoqContext*>(ptr);
    std::string trackName = jstring_to_string(env, track_name);

    if (!context || !context->client) {
        LOGE("nativeCreateAudioFramer: Invalid context");
        return JNI_FALSE;
    }

    // Get publish handler for this track
    auto handler = context->client->get_publish_handler(trackName);
    if (!handler) {
        LOGE("nativeCreateAudioFramer: No publish handler for track %s", trackName.c_str());
        return JNI_FALSE;
    }

    std::lock_guard<std::mutex> lock(context->framers_mutex);

    // Create audio framer
    try {
        auto framer = std::make_unique<AudioFramer>(trackName, handler,
                                                     vad_enabled == JNI_TRUE);
        context->audioFramers[trackName] = std::move(framer);
        LOGI("nativeCreateAudioFramer: Created framer for track %s (VAD: %d)",
             trackName.c_str(), vad_enabled);
        return JNI_TRUE;
    } catch (const std::exception& e) {
        LOGE("nativeCreateAudioFramer: Exception: %s", e.what());
        return JNI_FALSE;
    }
}

// Callback wrapper for AudioFramer::processPacket (called from nativeaudio module)
extern "C" void audioFramerProcessPacketCallback(void* framer, const uint8_t* data, size_t size, uint64_t timestamp_us) {
    AudioFramer* audioFramer = static_cast<AudioFramer*>(framer);
    if (audioFramer) {
        audioFramer->processPacket(data, size, timestamp_us);
    }
}

extern "C"
JNIEXPORT jlong JNICALL
Java_com_cisco_quadroid_transport_MoqNative_nativeLinkAudioFramer(
    JNIEnv* env, jobject thiz, jlong ptr, jstring track_name)
{
    auto context = reinterpret_cast<MoqContext*>(ptr);
    std::string trackName = jstring_to_string(env, track_name);

    if (!context) {
        LOGE("nativeLinkAudioFramer: Invalid context");
        return 0;
    }

    std::lock_guard<std::mutex> lock(context->framers_mutex);
    auto it = context->audioFramers.find(trackName);
    if (it != context->audioFramers.end()) {
        // Return raw pointer to AudioFramer for nativeaudio.cpp to use
        AudioFramer* framer = it->second.get();
        LOGI("nativeLinkAudioFramer: Linked framer for track %s (ptr: %p)",
             trackName.c_str(), static_cast<void*>(framer));
        return reinterpret_cast<jlong>(framer);
    } else {
        LOGE("nativeLinkAudioFramer: No framer found for track %s", trackName.c_str());
        return 0;
    }
}

extern "C"
JNIEXPORT jlong JNICALL
Java_com_cisco_quadroid_transport_MoqNative_nativeGetAudioFramerCallback(
    JNIEnv* env, jobject thiz)
{
    // Return the function pointer to the callback wrapper
    return reinterpret_cast<jlong>(&audioFramerProcessPacketCallback);
}

extern "C"
JNIEXPORT void JNICALL
Java_com_cisco_quadroid_transport_MoqNative_nativeSetAudioVad(
    JNIEnv* env, jobject thiz, jlong ptr, jstring track_name, jboolean enabled)
{
    auto context = reinterpret_cast<MoqContext*>(ptr);
    std::string trackName = jstring_to_string(env, track_name);

    if (!context) {
        LOGE("nativeSetAudioVad: Invalid context");
        return;
    }

    std::lock_guard<std::mutex> lock(context->framers_mutex);
    auto it = context->audioFramers.find(trackName);
    if (it != context->audioFramers.end()) {
        it->second->setVadEnabled(enabled == JNI_TRUE);
        LOGI("nativeSetAudioVad: Set VAD to %d for track %s", enabled, trackName.c_str());
    } else {
        LOGW("nativeSetAudioVad: No framer found for track %s", trackName.c_str());
    }
}

extern "C"
JNIEXPORT void JNICALL
Java_com_cisco_quadroid_transport_MoqNative_nativeDestroyAudioFramer(
    JNIEnv* env, jobject thiz, jlong ptr, jstring track_name)
{
    auto context = reinterpret_cast<MoqContext*>(ptr);
    std::string trackName = jstring_to_string(env, track_name);

    if (!context) {
        LOGE("nativeDestroyAudioFramer: Invalid context");
        return;
    }

    std::lock_guard<std::mutex> lock(context->framers_mutex);
    auto it = context->audioFramers.find(trackName);
    if (it != context->audioFramers.end()) {
        context->audioFramers.erase(it);
        LOGI("nativeDestroyAudioFramer: Destroyed framer for track %s", trackName.c_str());
    } else {
        LOGW("nativeDestroyAudioFramer: No framer found for track %s", trackName.c_str());
    }
}