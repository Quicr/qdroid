// SPDX-FileCopyrightText: Copyright (c) 2025 Cisco Systems
// SPDX-License-Identifier: BSD-2-Clause

#include <jni.h>
#include <string>
#include <memory>
#include <thread>
#include <unordered_map>
#include <mutex>
#include <vector>
#include <chrono>
#include <android/log.h>
#include <quicr/session.h>
#include <quicr/session_manager.h>
#include <quicr/session_callbacks.h>
#include <quicr/messages/object.h>
#include "moq_util.h"
#include "video_jitter_buffer.h"
#include "audio_jitter_buffer.h"
#include "loc_wrapper.h"

#define LOG_TAG "MoqJni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// Global JVM reference (non-static to allow access from video_jitter_buffer.cpp)
JavaVM* g_jvm = nullptr;

// Per-object MoQ extension header id the relay ranks on for Top-N active-speaker
// filtering. Carries the 3-value voice-activity state (0=speechEnd, 1=continuousSpeech,
// 2=speechStart). Must match the relay's expected property type and the Kotlin
// AppHeaders.AUDIO_ACTIVITY_INDICATOR constant.
static constexpr uint64_t AUDIO_ACTIVITY_INDICATOR = 12;

// Per received VIDEO track (keyed by full track name): the latest Top-N voice-activity value
// (0/1/2, -1 if no header seen) and the monotonic time of the last object received. Written on the
// transport thread in ObjectReceived; read by Kotlin via nativeGetTrackActivity / nativeGetTrackIdleMs
// so the subscriber can both (a) rank display tiles by activity and (b) tell which tracks the relay
// is actually forwarding right now (recent objects), independent of whether a tile/jitter-buffer
// exists yet. This decouples display selection from the lazy per-tile jitter buffer.
struct RxTrackState { int activity = -1; int64_t lastSeenMs = 0; };
static std::mutex g_track_activity_mutex;
static std::unordered_map<std::string, RxTrackState> g_track_activity;

static int64_t steady_now_ms() {
    return std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::steady_clock::now().time_since_epoch()).count();
}

// Forward declarations
class AndroidPublishTrackHandler;
class AndroidSubscribeTrackHandler;
class AndroidPublishNamespaceHandler;
class AndroidSubscribeNamespaceHandler;
class AndroidClientCallbacks;

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
        // Allow this subscription to send MoQT new-group (keyframe) requests to the relay. Used on
        // a Top-N swap-in when we join a track mid-group (see ObjectReceived).
        SupportNewGroupRequest(true);
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

        // Top-N swap-in keyframe request: when the relay starts forwarding this VIDEO track to us
        // mid-group — i.e. the first object after (re)entry has object_id > 0 — we've missed the
        // group's keyframe, so the decoder can't start. Ask the relay/publisher for a new group
        // (IDR) now instead of waiting up to a full keyframe interval. A forwarding gap (relay
        // swapped us out then back in) or a fresh subscription both count as (re)entry.
        if (track_name_str.find("video") != std::string::npos) {
            const int64_t now_ms = steady_now_ms();
            if (now_ms - last_object_ms_ > kForwardingGapMs) {
                seen_group_start_ = false;      // (re)entry: relay just started forwarding to us
                requested_new_group_ = false;
            }
            last_object_ms_ = now_ms;
            if (hdr.object_id == 0) {
                seen_group_start_ = true;       // clean group start — nothing to request
            } else if (!seen_group_start_ && !requested_new_group_) {
                LOGI("Top-N swap-in mid-group (obj=%llu) for %s: requesting new group (keyframe)",
                     (unsigned long long)hdr.object_id, track_name_str.c_str());
                RequestNewGroup();              // libquicr: SUBSCRIBE_UPDATE new-group request
                requested_new_group_ = true;
            }
        }

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

            // Record the Top-N activity value (header 12) and mark this track as freshly forwarded.
            // Value shape mirrors the publish side: extensions[12] = {{ byte }}. Any received video
            // object (header or not) refreshes lastSeenMs so the client knows the relay is currently
            // forwarding this track.
            int observed_activity = -1;
            if (hdr.extensions.has_value()) {
                auto ext = hdr.extensions->find(AUDIO_ACTIVITY_INDICATOR);
                if (ext != hdr.extensions->end() && !ext->second.empty() && !ext->second[0].empty()) {
                    observed_activity = static_cast<int>(ext->second[0][0]);
                }
            }
            {
                std::lock_guard<std::mutex> lk(g_track_activity_mutex);
                auto& st = g_track_activity[track_name_str];
                if (observed_activity >= 0) st.activity = observed_activity; // keep last-known on gaps
                st.lastSeenMs = steady_now_ms();
            }
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

    // Swap-in keyframe-request state (video). A forwarding gap longer than this is treated as a
    // (re)entry — the relay started forwarding this track to us (Top-N swap-in or fresh subscribe).
    static constexpr int64_t kForwardingGapMs = 500;
    bool seen_group_start_ = false;     // have we seen object_id==0 (group start) since entry?
    bool requested_new_group_ = false;  // have we already asked for a new group this entry?
    int64_t last_object_ms_ = 0;        // monotonic time of the last received object
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
                                     jobject callback_ref,
                                     const quicr::messages::Filter& filter = std::monostate{})
      : quicr::SubscribeNamespaceHandler(prefix, quicr::SubscribeNamespaceHandler::Mode::kTracks, filter)
      , callback_ref_(callback_ref)
      , callback_valid_(true)
    {
        LOGI("Created AndroidSubscribeNamespaceHandler");
    }

    static std::shared_ptr<AndroidSubscribeNamespaceHandler> Create(const quicr::TrackNamespace& prefix,
                                                                      jobject callback_ref,
                                                                      const quicr::messages::Filter& filter = std::monostate{})
    {
        return std::shared_ptr<AndroidSubscribeNamespaceHandler>(
            new AndroidSubscribeNamespaceHandler(prefix, callback_ref, filter));
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
// Android Client Callbacks
//==============================================================================

class AndroidClientCallbacks : public quicr::Session::ClientCallbacks
{
public:
    explicit AndroidClientCallbacks(jobject kotlin_callback)
      : kotlin_callback_ref_(kotlin_callback)
    {
        LOGI("AndroidClientCallbacks created");
    }

    static std::shared_ptr<AndroidClientCallbacks> Create(jobject kotlin_callback)
    {
        return std::shared_ptr<AndroidClientCallbacks>(new AndroidClientCallbacks(kotlin_callback));
    }

    void StatusChanged([[maybe_unused]] const std::shared_ptr<quicr::Session>& session,
                       quicr::Session::Status status) override
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

    quicr::Reply<void, quicr::PublishNamespaceErrorCode> PublishNamespaceReceived(
      [[maybe_unused]] const std::shared_ptr<quicr::Session>& session,
      const quicr::TrackNamespace& track_namespace,
      [[maybe_unused]] const quicr::PublishNamespaceAttributes& publish_namespace_attributes) override
    {
        auto th = quicr::TrackHash({ track_namespace, {} });
        LOGI("Received ANNOUNCE for namespace_hash: %llu", th.track_namespace_hash);
        return {};
    }

    quicr::Reply<const quicr::PublishResponse, quicr::PublishErrorCode> PublishReceived(
      [[maybe_unused]] const std::shared_ptr<quicr::Session>& session,
      std::uint64_t request_id,
      const quicr::PublishAttributes& publish_attributes,
      std::weak_ptr<quicr::SubscribeNamespaceHandler> ns_handler) override
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

        // Accept the PUBLISH by returning the track handler
        return quicr::PublishResponse{ {}, std::move(handler) };
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
    int mapStatusToKotlin(quicr::Session::Status status)
    {
        switch (status) {
            case quicr::Session::Status::kNotConnected:
                return 0; // IDLE
            case quicr::Session::Status::kConnecting:
                return 1; // CONNECTING
            case quicr::Session::Status::kPendingPeerSetup:
                return 1; // Still connecting
            case quicr::Session::Status::kReady:
                return 2; // CONNECTED
            case quicr::Session::Status::kDisconnecting:
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
    quicr::SessionManager session_mgr;
    std::shared_ptr<AndroidClientCallbacks> callbacks;
    std::shared_ptr<quicr::Session> session;
    std::unordered_map<std::string, std::shared_ptr<AndroidPublishNamespaceHandler>> publish_ns_handlers;
    std::unordered_map<std::string, std::shared_ptr<AndroidSubscribeNamespaceHandler>> subscribe_ns_handlers;
    std::mutex ns_handlers_mutex;
    jobject kotlin_callback_ref = nullptr;

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
    config.transport_config.tls_cert_filename = "";
    config.transport_config.tls_key_filename = "";

    context->callbacks = AndroidClientCallbacks::Create(context->kotlin_callback_ref);

    auto weak_session = context->session_mgr.AddTransport(config, context->callbacks);
    context->session = weak_session.lock();

    if (!context->session) {
        LOGE("nativeConnect: Failed to initiate connection");
        delete context;
        return 0;
    }

    LOGI("nativeConnect: Session created, status: %d",
         static_cast<int>(context->session->GetStatus()));

    return reinterpret_cast<jlong>(context);
}

extern "C"
JNIEXPORT void JNICALL
Java_com_cisco_quadroid_transport_MoqNative_nativeDisconnect(JNIEnv *env, jobject thiz, jlong ptr)
{
    auto context = reinterpret_cast<MoqContext *>(ptr);

    if (context && context->session) {
        LOGI("nativeDisconnect: Disconnecting client");
        context->session->Disconnect();
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

    if (!context || !context->session) {
        LOGE("nativePublishNamespace: Invalid context");
        return;
    }

    // Wait for connection to be ready
    int retries = 50; // 5 seconds
    while (context->session->GetStatus() != quicr::Session::Status::kReady && retries-- > 0) {
        std::this_thread::sleep_for(std::chrono::milliseconds(100));
    }

    if (context->session->GetStatus() != quicr::Session::Status::kReady) {
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

    // PublishNamespace can create a QUIC stream via CreateStreamOnPqThread, which throws
    // PicoQuicException on a transient 100ms stream-creation miss. Guard + retry so a flaky
    // Join does not abort the process.
    bool announced = false;
    for (int attempt = 0; attempt < 5 && !announced; ++attempt) {
        try {
            context->session->PublishNamespace(handler);
            announced = true;
        } catch (const std::exception& e) {
            LOGW("nativePublishNamespace: threw on attempt %d for %s: %s", attempt, prefix.c_str(), e.what());
            std::this_thread::sleep_for(std::chrono::milliseconds(100));
        } catch (...) {
            LOGW("nativePublishNamespace: threw (unknown) on attempt %d for %s", attempt, prefix.c_str());
            std::this_thread::sleep_for(std::chrono::milliseconds(100));
        }
    }
    if (!announced) {
        LOGE("nativePublishNamespace: giving up announcing %s after retries", prefix.c_str());
        return;
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

    if (!context || !context->session) {
        LOGE("nativePublish: Invalid context");
        return;
    }

    // Wait for connection to be ready
    int retries = 50; // 5 seconds
    while (context->session->GetStatus() != quicr::Session::Status::kReady && retries-- > 0) {
        std::this_thread::sleep_for(std::chrono::milliseconds(100));
    }

    if (context->session->GetStatus() != quicr::Session::Status::kReady) {
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

    context->callbacks->add_publish_handler(name, handler);

    // PublishTrack can create a QUIC stream via CreateStreamOnPqThread, which throws
    // PicoQuicException on a transient 100ms stream-creation miss. Guard + retry so a flaky
    // Join degrades to a failed publish instead of aborting the process.
    bool published = false;
    for (int attempt = 0; attempt < 5 && !published; ++attempt) {
        try {
            context->session->PublishTrack(handler);
            published = true;
        } catch (const std::exception& e) {
            LOGW("nativePublish: PublishTrack threw on attempt %d for %s: %s", attempt, name.c_str(), e.what());
            std::this_thread::sleep_for(std::chrono::milliseconds(100));
        } catch (...) {
            LOGW("nativePublish: PublishTrack threw (unknown) on attempt %d for %s", attempt, name.c_str());
            std::this_thread::sleep_for(std::chrono::milliseconds(100));
        }
    }
    if (!published) {
        LOGE("nativePublish: giving up publishing %s after retries", name.c_str());
        context->callbacks->remove_publish_handler(name);
        return;
    }

    LOGI("nativePublish: Published track %s", name.c_str());
}

extern "C"
JNIEXPORT void JNICALL
Java_com_cisco_quadroid_transport_MoqNative_nativeUnpublish(JNIEnv *env, jobject thiz, jlong ptr,
                                                          jstring track_name)
{
    auto context = reinterpret_cast<MoqContext *>(ptr);
    std::string name = jstring_to_string(env, track_name);

    if (!context || !context->session) {
        LOGE("nativeUnpublish: Invalid context");
        return;
    }

    auto handler = context->callbacks->get_publish_handler(name);
    if (handler) {
        context->session->UnpublishTrack(handler);
        context->callbacks->remove_publish_handler(name);
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
                                                           jboolean use_datagram,
                                                           jint activity_value)
{
    auto context = reinterpret_cast<MoqContext *>(ptr);
    std::string name = jstring_to_string(env, track_name);

    if (!context || !context->session) {
        LOGE("nativeSendObject: Invalid context");
        return;
    }

    uint8_t* data = static_cast<uint8_t*>(env->GetDirectBufferAddress(payload));
    if (!data) {
        LOGE("nativeSendObject: Failed to get direct buffer address");
        return;
    }

    auto handler = context->callbacks->get_publish_handler(name);
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
    if (locWrappedData.empty()) {
        LOGE("nativeSendObject: Failed to wrap data with LOC");
        return;
    }
    std::optional<uint16_t> ttl_opt;

    if (delivery_timeout_ms > 0) {
        ttl_opt = static_cast<uint16_t>(delivery_timeout_ms);
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

    // Top-N: stamp the current 3-value voice-activity state as a per-object MoQ extension
    // header so the relay can rank this publisher. activity_value < 0 means "no header"
    // (Top-N disabled) — the object is then byte-for-byte identical to the legacy path.
    if (activity_value >= 0) {
        quicr::Extensions extensions;
        extensions[AUDIO_ACTIVITY_INDICATOR] = { { static_cast<uint8_t>(activity_value) } };
        headers.extensions = std::move(extensions);
    }

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

    if (!context || !context->session) {
        LOGE("nativeSubscribe: Invalid context");
        return;
    }

    // Wait for connection to be ready
    int retries = 50; // 5 seconds
    while (context->session->GetStatus() != quicr::Session::Status::kReady && retries-- > 0) {
        std::this_thread::sleep_for(std::chrono::milliseconds(100));
    }

    if (context->session->GetStatus() != quicr::Session::Status::kReady) {
        LOGE("nativeSubscribe: Client not ready");
        return;
    }

    LOGI("nativeSubscribe: track=%s", name.c_str());

    auto full_track_name = make_full_track_name(name);
    jobject callback_ref = env->NewGlobalRef(callback);

    auto handler = std::make_shared<AndroidSubscribeTrackHandler>(full_track_name, callback_ref);

    context->callbacks->add_subscribe_handler(name, handler);
    context->session->SubscribeTrack(handler);

    LOGI("nativeSubscribe: Subscribed to track %s", name.c_str());
}

extern "C"
JNIEXPORT void JNICALL
Java_com_cisco_quadroid_transport_MoqNative_nativeUnsubscribe(JNIEnv *env, jobject thiz, jlong ptr,
                                                            jstring track_name)
{
    auto context = reinterpret_cast<MoqContext *>(ptr);
    std::string name = jstring_to_string(env, track_name);

    if (!context || !context->session) {
        LOGE("nativeUnsubscribe: Invalid context");
        return;
    }

    auto handler = context->callbacks->get_subscribe_handler(name);
    if (handler) {
        context->session->UnsubscribeTrack(handler);
        context->callbacks->remove_subscribe_handler(name);
        LOGI("nativeUnsubscribe: Unsubscribed from track %s", name.c_str());
    }
}

extern "C"
JNIEXPORT void JNICALL
Java_com_cisco_quadroid_transport_MoqNative_nativeSubscribeNamespace(JNIEnv *env, jobject thiz,
                                                                   jlong ptr,
                                                                   jstring namespace_prefix,
                                                                   jobject callback,
                                                                   jlong property_type,
                                                                   jlong max_tracks,
                                                                   jlong timeout_ms)
{
    auto context = reinterpret_cast<MoqContext *>(ptr);
    std::string prefix = jstring_to_string(env, namespace_prefix);

    if (!context || !context->session) {
        LOGE("nativeSubscribeNamespace: Invalid context");
        return;
    }

    LOGI("nativeSubscribeNamespace: prefix=%s", prefix.c_str());

    auto track_ns = make_track_namespace(prefix);
    jobject callback_ref = env->NewGlobalRef(callback);

    // Top-N: when property_type >= 0 and max_tracks > 0, attach a relay-side TrackFilter so the
    // relay forwards only the top max_tracks publishers ranked by the property. Otherwise no
    // filter (std::monostate) — unchanged behavior, all matching tracks delivered.
    quicr::messages::Filter filter = std::monostate{};
    if (property_type >= 0 && max_tracks > 0) {
        filter = quicr::messages::TrackFilter{
            static_cast<std::uint64_t>(property_type),
            static_cast<std::uint64_t>(max_tracks),
            static_cast<std::uint64_t>(timeout_ms)
        };
        LOGI("nativeSubscribeNamespace: attaching TrackFilter(property=%lld, maxTracks=%lld, timeoutMs=%lld)",
             (long long)property_type, (long long)max_tracks, (long long)timeout_ms);
    }

    auto handler = AndroidSubscribeNamespaceHandler::Create(track_ns, callback_ref, filter);

    {
        std::lock_guard<std::mutex> lock(context->ns_handlers_mutex);
        context->subscribe_ns_handlers[prefix] = handler;
    }

    // SubscribeNamespace creates a QUIC request stream via CreateStreamOnPqThread, which waits
    // only 100ms for the picoquic thread and throws PicoQuicException on a transient miss. An
    // uncaught throw here aborts the whole process (SIGABRT), which showed up as a flaky crash
    // on Join. Catch and retry a few times so a transient stream-creation timeout degrades to a
    // failed subscribe instead of killing the app. The throw happens while creating the request
    // stream (before the SUBSCRIBE_NAMESPACE is sent), so retrying is safe.
    bool subscribed = false;
    for (int attempt = 0; attempt < 5 && !subscribed; ++attempt) {
        try {
            context->session->SubscribeNamespace(handler);
            subscribed = true;
        } catch (const std::exception& e) {
            LOGW("nativeSubscribeNamespace: SubscribeNamespace threw on attempt %d for %s: %s",
                 attempt, prefix.c_str(), e.what());
            std::this_thread::sleep_for(std::chrono::milliseconds(100));
        } catch (...) {
            LOGW("nativeSubscribeNamespace: SubscribeNamespace threw (unknown) on attempt %d for %s",
                 attempt, prefix.c_str());
            std::this_thread::sleep_for(std::chrono::milliseconds(100));
        }
    }
    if (!subscribed) {
        LOGE("nativeSubscribeNamespace: giving up subscribing to %s after retries", prefix.c_str());
        return;
    }

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

    if (!context || !context->session) {
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

    if (!context || !context->session) {
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
        context->session->PublishNamespaceDone(handler);
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

    if (!context || !context->session) {
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
        context->session->UnsubscribeNamespace(handler);

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

    if (!context || !context->session) {
        LOGE("nativeEndSubGroup: Invalid context");
        return;
    }
    std::string name = jstring_to_string(env, track_name);

    auto handler = context->callbacks->get_publish_handler(name);
    if (!handler) {
        LOGE("nativeEndSubGroup: No publish handler for track %s", name.c_str());
        return;
    }
    handler->EndSubgroup(group_id, subgroup_id, completed);
}

//==============================================================================
// Video Jitter Buffer JNI Methods
//==============================================================================

// Returns the latest Top-N activity value (0/1/2) observed on the given video track's objects,
// or -1 if none has been seen. Used by the subscriber to rank display tiles by activity.
extern "C"
JNIEXPORT jint JNICALL
Java_com_cisco_quadroid_transport_MoqNative_nativeGetTrackActivity(
    JNIEnv* env, jobject /*thiz*/, jstring track_name)
{
    std::string name = jstring_to_string(env, track_name);
    std::lock_guard<std::mutex> lk(g_track_activity_mutex);
    auto it = g_track_activity.find(name);
    return it != g_track_activity.end() ? static_cast<jint>(it->second.activity) : static_cast<jint>(-1);
}

// Returns milliseconds since the last object was received on the given video track (i.e. how long
// the relay has NOT been forwarding it). Returns a large value if never seen. Lets the subscriber
// display exactly the tracks the relay is currently forwarding, regardless of jitter-buffer state.
extern "C"
JNIEXPORT jlong JNICALL
Java_com_cisco_quadroid_transport_MoqNative_nativeGetTrackIdleMs(
    JNIEnv* env, jobject /*thiz*/, jstring track_name)
{
    std::string name = jstring_to_string(env, track_name);
    std::lock_guard<std::mutex> lk(g_track_activity_mutex);
    auto it = g_track_activity.find(name);
    if (it == g_track_activity.end()) return static_cast<jlong>(24L * 60L * 60L * 1000L); // ~1 day
    return static_cast<jlong>(steady_now_ms() - it->second.lastSeenMs);
}

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