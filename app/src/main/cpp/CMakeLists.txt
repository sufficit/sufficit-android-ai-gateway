cmake_minimum_required(VERSION 3.22.1)

project(openclaw_gateway_native C CXX)

find_library(log-lib log)
find_library(android-lib android)

set(JNI_LIBS_DIR ${CMAKE_CURRENT_LIST_DIR}/../jniLibs/${ANDROID_ABI})
set(JNI_INCLUDE_DIR ${CMAKE_CURRENT_LIST_DIR}/include)

add_library(whisper SHARED IMPORTED)
set_target_properties(whisper PROPERTIES IMPORTED_LOCATION ${JNI_LIBS_DIR}/libwhisper.so)

add_library(ggml SHARED IMPORTED)
set_target_properties(ggml PROPERTIES IMPORTED_LOCATION ${JNI_LIBS_DIR}/libggml.so)

add_library(ggml-base SHARED IMPORTED)
set_target_properties(ggml-base PROPERTIES IMPORTED_LOCATION ${JNI_LIBS_DIR}/libggml-base.so)

add_library(ggml-cpu SHARED IMPORTED)
set_target_properties(ggml-cpu PROPERTIES IMPORTED_LOCATION ${JNI_LIBS_DIR}/libggml-cpu.so)

add_library(ggml-vulkan SHARED IMPORTED)
set_target_properties(ggml-vulkan PROPERTIES IMPORTED_LOCATION ${JNI_LIBS_DIR}/libggml-vulkan.so)

add_library(whisper_jni SHARED whisper_jni.cpp)
target_include_directories(whisper_jni PRIVATE ${JNI_INCLUDE_DIR})
target_link_libraries(
    whisper_jni
    ${log-lib}
    ${android-lib}
    whisper
    ggml
    ggml-base
    ggml-cpu
    ggml-vulkan
)
