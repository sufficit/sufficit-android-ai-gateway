package com.sufficit.ai.gateway.transcription.local

object LocalWhisperLib {
    init {
        System.loadLibrary("whisper_jni")
    }

    external fun initContext(
        modelPath: String,
        useGpu: Boolean,
        flashAttn: Boolean,
        gpuDevice: Int
    ): Long

    external fun freeContext(contextPtr: Long)

    external fun fullTranscribe(
        contextPtr: Long,
        numThreads: Int,
        language: String,
        audioData: FloatArray
    )

    external fun getTextSegmentCount(contextPtr: Long): Int

    external fun getTextSegment(contextPtr: Long, index: Int): String

    external fun getSystemInfo(): String

    external fun getVulkanDeviceCount(): Int

    external fun getVulkanDeviceDescription(index: Int): String
}
