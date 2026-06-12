#include <jni.h>
#include <android/log.h>
#include <stdlib.h>
#include <string.h>
#include "whisper.h"
#include "ggml-vulkan.h"

#define TAG "OpenClawWhisperJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)
#define UNUSED(x) (void)(x)

static void set_env_if_missing(const char *name, const char *value) {
    if (getenv(name) == NULL) {
        setenv(name, value, 1);
    }
}

static void configure_safe_vulkan_profile(bool use_gpu) {
    if (!use_gpu) {
        return;
    }

    // Use upstream/default Vulkan profile first; custom disables can be reintroduced per-device if needed.
    set_env_if_missing("GGML_VK_SYNC_LOGGER", "1");
    set_env_if_missing("GGML_VK_MEMORY_LOGGER", "1");
    LOGI("Using default Vulkan profile");
}

JNIEXPORT jlong JNICALL
Java_com_sufficit_openclaw_gateway_transcription_local_LocalWhisperLib_initContext(
        JNIEnv *env,
        jobject thiz,
        jstring model_path_str,
        jboolean use_gpu,
        jboolean flash_attn,
        jint gpu_device) {
    UNUSED(thiz);

    const char *model_path = (*env)->GetStringUTFChars(env, model_path_str, NULL);
    struct whisper_context_params params = whisper_context_default_params();
    const bool requested_gpu = use_gpu == JNI_TRUE;
    configure_safe_vulkan_profile(requested_gpu);
    params.use_gpu = requested_gpu;
    params.flash_attn = false;
    params.gpu_device = gpu_device;

    LOGI(
            "Initializing whisper context. requested_gpu=%d effective_gpu=%d flash_attn=%d gpu_device=%d path=%s",
            requested_gpu, params.use_gpu, params.flash_attn, params.gpu_device, model_path
    );

    struct whisper_context *context =
            whisper_init_from_file_with_params(model_path, params);

    (*env)->ReleaseStringUTFChars(env, model_path_str, model_path);
    return (jlong) context;
}

JNIEXPORT void JNICALL
Java_com_sufficit_openclaw_gateway_transcription_local_LocalWhisperLib_freeContext(
        JNIEnv *env,
        jobject thiz,
        jlong context_ptr) {
    UNUSED(env);
    UNUSED(thiz);
    if (context_ptr != 0L) {
        whisper_free((struct whisper_context *) context_ptr);
    }
}

JNIEXPORT void JNICALL
Java_com_sufficit_openclaw_gateway_transcription_local_LocalWhisperLib_fullTranscribe(
        JNIEnv *env,
        jobject thiz,
        jlong context_ptr,
        jint num_threads,
        jstring language_str,
        jfloatArray audio_data) {
    UNUSED(thiz);

    struct whisper_context *context = (struct whisper_context *) context_ptr;
    if (context == NULL) {
        LOGE("Cannot transcribe because context is null");
        return;
    }

    const char *language = (*env)->GetStringUTFChars(env, language_str, NULL);
    jfloat *audio_data_arr = (*env)->GetFloatArrayElements(env, audio_data, NULL);
    const jsize audio_data_length = (*env)->GetArrayLength(env, audio_data);

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
    params.single_segment = true;

    whisper_reset_timings(context);

    if (whisper_full(context, params, audio_data_arr, audio_data_length) != 0) {
        LOGE("whisper_full failed");
    } else {
        whisper_print_timings(context);
    }

    (*env)->ReleaseFloatArrayElements(env, audio_data, audio_data_arr, JNI_ABORT);
    (*env)->ReleaseStringUTFChars(env, language_str, language);
}

JNIEXPORT jint JNICALL
Java_com_sufficit_openclaw_gateway_transcription_local_LocalWhisperLib_getTextSegmentCount(
        JNIEnv *env,
        jobject thiz,
        jlong context_ptr) {
    UNUSED(env);
    UNUSED(thiz);
    return whisper_full_n_segments((struct whisper_context *) context_ptr);
}

JNIEXPORT jstring JNICALL
Java_com_sufficit_openclaw_gateway_transcription_local_LocalWhisperLib_getTextSegment(
        JNIEnv *env,
        jobject thiz,
        jlong context_ptr,
        jint index) {
    UNUSED(thiz);
    const char *text = whisper_full_get_segment_text((struct whisper_context *) context_ptr, index);
    return (*env)->NewStringUTF(env, text);
}

JNIEXPORT jstring JNICALL
Java_com_sufficit_openclaw_gateway_transcription_local_LocalWhisperLib_getSystemInfo(
        JNIEnv *env,
        jobject thiz) {
    UNUSED(thiz);
    const char *sysinfo = whisper_print_system_info();
    return (*env)->NewStringUTF(env, sysinfo);
}

JNIEXPORT jint JNICALL
Java_com_sufficit_openclaw_gateway_transcription_local_LocalWhisperLib_getVulkanDeviceCount(
        JNIEnv *env,
        jobject thiz) {
    UNUSED(env);
    UNUSED(thiz);
    return ggml_backend_vk_get_device_count();
}

JNIEXPORT jstring JNICALL
Java_com_sufficit_openclaw_gateway_transcription_local_LocalWhisperLib_getVulkanDeviceDescription(
        JNIEnv *env,
        jobject thiz,
        jint index) {
    UNUSED(thiz);

    char description[512] = {0};
    ggml_backend_vk_get_device_description(index, description, sizeof(description));
    return (*env)->NewStringUTF(env, description);
}
