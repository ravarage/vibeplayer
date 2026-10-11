cmake_minimum_required(VERSION 3.22)
project(player_jni C)

set(PREFIX_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../../../native/mpv-android/buildscripts/prefix/arm64")

include_directories(
    "${PREFIX_DIR}/include"
)

add_library(mpv SHARED IMPORTED)
set_target_properties(mpv PROPERTIES
    IMPORTED_LOCATION "${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libmpv.so"
)

add_library(avcodec SHARED IMPORTED)
set_target_properties(avcodec PROPERTIES
    IMPORTED_LOCATION "${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libavcodec.so"
)

add_library(player_jni SHARED
    mpv_jni.c
)

target_link_libraries(player_jni
    mpv
    avcodec
    log
    android
)
