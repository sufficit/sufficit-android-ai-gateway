#include <jni.h>
#include <android/log.h>
#include <cstdlib>
#include <exception>
#include "whisper.h"
#include "ggml-vulkan.h"

#define TAG "OpenClawWhisperJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static void set_env_if_missing(const char *name, const char *value) {
    if (getenv(name) == nullptr) {
        setenv(name, value, 1);
    }
}

static void configure_safe_vulkan_profile(bool use_gpu) {
    if (!use_gpu) {
        return;
    }

    // Keep only stability-oriented toggles that are known by ggml-vulkan.
    // Over-constraining the backend (e.g. forcing no-fp16) can increase memory
    // pressure and trigger DeviceLost on mobile GPUs.
    set_env_if_missing("GGML_VK_DISABLE_ASYNC", "1");
    set_env_if_missing("GGML_VK_DISABLE_MMVQ", "1");
    set_env_if_missing("GGML_VK_DISABLE_FUSION", "1");
    set_env_if_missing("GGML_VK_ALLOW_SYSMEM_FALLBACK", "1");

    LOGI("Applied Vulkan profile for mobile GPU (minimal constraints)");
}

static void throw_runtime_exception(JNIEnv *env, const char *message) {
    jclass runtime_exception = env->FindClass("java/lang/RuntimeException");
    if (runtime_exception != nullptr) {
        env->ThrowNew(runtime_exception, message);
    }
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_sufficit_openclaw_gateway_transcription_local_LocalWhisperLib_initContext(
        JNIEnv *env,
        jobject /*thiz*/,
        jstring model_path_str,
        jboolean use_gpu,
        jboolean /*flash_attn*/,
        jint gpu_device) {
    const char *model_path = env->GetStringUTFChars(model_path_str, nullptr);
    const bool requested_gpu = use_gpu == JNI_TRUE;
    configure_safe_vulkan_profile(requested_gpu);

    try {
        struct whisper_context_params params = whisper_context_default_params();
        params.use_gpu = requested_gpu;
        params.flash_attn = false;
        params.gpu_device = gpu_device;

        LOGI(
                "Initializing whisper context. requested_gpu=%d effective_gpu=%d flash_attn=%d gpu_device=%d path=%s",
                requested_gpu, params.use_gpu, params.flash_attn, params.gpu_device, model_path
        );

        struct whisper_context *context =
                whisper_init_from_file_with_params(model_path, params);

        env->ReleaseStringUTFChars(model_path_str, model_path);
        return reinterpret_cast<jlong>(context);
    } catch (const std::exception &ex) {
        LOGE("initContext failed: %s", ex.what());
        env->ReleaseStringUTFChars(model_path_str, model_path);
        throw_runtime_exception(env, ex.what());
        return 0L;
    } catch (...) {
        LOGE("initContext failed with unknown native exception");
        env->ReleaseStringUTFChars(model_path_str, model_path);
        throw_runtime_exception(env, "unknown native error while initializing Whisper");
        return 0L;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_sufficit_openclaw_gateway_transcription_local_LocalWhisperLib_freeContext(
        JNIEnv *env,
        jobject /*thiz*/,
        jlong context_ptr) {
    if (context_ptr != 0L) {
        try {
            whisper_free(reinterpret_cast<struct whisper_context *>(context_ptr));
        } catch (const std::exception &ex) {
            LOGE("freeContext failed: %s", ex.what());
            throw_runtime_exception(env, ex.what());
        } catch (...) {
            LOGE("freeContext failed with unknown native exception");
            throw_runtime_exception(env, "unknown native error while freeing Whisper");
        }
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_sufficit_openclaw_gateway_transcription_local_LocalWhisperLib_fullTranscribe(
        JNIEnv *env,
        jobject /*thiz*/,
        jlong context_ptr,
        jint num_threads,
        jstring language_str,
        jfloatArray audio_data) {
    struct whisper_context *context = reinterpret_cast<struct whisper_context *>(context_ptr);
    if (context == nullptr) {
        LOGE("Cannot transcribe because context is null");
        throw_runtime_exception(env, "Whisper context is null");
        return;
    }

    const char *language = env->GetStringUTFChars(language_str, nullptr);
    jfloat *audio_data_arr = env->GetFloatArrayElements(audio_data, nullptr);
    const jsize audio_data_length = env->GetArrayLength(audio_data);

    try {
        struct whisper_full_params params =
                whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
        params.print_realtime = false;
        params.print_progress = false;
        params.print_timestamps = false;
        params.print_special = false;
        params.translate = false;
        params.language = language;
        params.n_threads = num_threads;
        params.offset_ms = 0;
        params.no_context = true;
        params.no_timestamps = true;
        params.single_segment = true;
        params.audio_ctx = 0;
        params.duration_ms = 0;

        whisper_reset_timings(context);

        if (whisper_full(context, params, audio_data_arr, audio_data_length) != 0) {
            LOGE("whisper_full failed");
            throw_runtime_exception(env, "whisper_full failed");
        } else {
            whisper_print_timings(context);
        }
    } catch (const std::exception &ex) {
        LOGE("fullTranscribe failed: %s", ex.what());
        throw_runtime_exception(env, ex.what());
    } catch (...) {
        LOGE("fullTranscribe failed with unknown native exception");
        throw_runtime_exception(env, "unknown native error while running Whisper");
    }

    env->ReleaseFloatArrayElements(audio_data, audio_data_arr, JNI_ABORT);
    env->ReleaseStringUTFChars(language_str, language);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_sufficit_openclaw_gateway_transcription_local_LocalWhisperLib_getTextSegmentCount(
        JNIEnv * /*env*/,
        jobject /*thiz*/,
        jlong context_ptr) {
    return whisper_full_n_segments(reinterpret_cast<struct whisper_context *>(context_ptr));
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_sufficit_openclaw_gateway_transcription_local_LocalWhisperLib_getTextSegment(
        JNIEnv *env,
        jobject /*thiz*/,
        jlong context_ptr,
        jint index) {
    const char *text = whisper_full_get_segment_text(reinterpret_cast<struct whisper_context *>(context_ptr), index);
    return env->NewStringUTF(text);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_sufficit_openclaw_gateway_transcription_local_LocalWhisperLib_getSystemInfo(
        JNIEnv *env,
        jobject /*thiz*/) {
    const char *sysinfo = whisper_print_system_info();
    return env->NewStringUTF(sysinfo);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_sufficit_openclaw_gateway_transcription_local_LocalWhisperLib_getVulkanDeviceCount(
        JNIEnv * /*env*/,
        jobject /*thiz*/) {
    return ggml_backend_vk_get_device_count();
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_sufficit_openclaw_gateway_transcription_local_LocalWhisperLib_getVulkanDeviceDescription(
        JNIEnv *env,
        jobject /*thiz*/,
        jint index) {
    char description[512] = {0};
    ggml_backend_vk_get_device_description(index, description, sizeof(description));
    return env->NewStringUTF(description);
}
