cmake_minimum_required(VERSION 3.22)
project(espeak_jni)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_library(espeak-jni SHARED espeak_jni.cc)

target_include_directories(espeak-jni PRIVATE
        ${CMAKE_SOURCE_DIR}/include
)

# 静态库所在目录：app/src/main/cpp/lib/${ANDROID_ABI}
set(ESPEAK_LIB_DIR ${CMAKE_SOURCE_DIR}/lib/${ANDROID_ABI})

add_library(espeak-ng STATIC IMPORTED)
set_target_properties(espeak-ng PROPERTIES IMPORTED_LOCATION
        ${ESPEAK_LIB_DIR}/libespeak-ng.a)

add_library(speechPlayer STATIC IMPORTED)
set_target_properties(speechPlayer PROPERTIES IMPORTED_LOCATION
        ${ESPEAK_LIB_DIR}/libspeechPlayer.a)

add_library(ucd STATIC IMPORTED)
set_target_properties(ucd PROPERTIES IMPORTED_LOCATION
        ${ESPEAK_LIB_DIR}/libucd.a)

find_library(log-lib log)

target_compile_options(espeak-jni PRIVATE -fexceptions -frtti)

target_link_libraries(espeak-jni
        -Wl,--start-group
        espeak-ng
        speechPlayer
        ucd
        -Wl,--end-group
        ${log-lib}
)
