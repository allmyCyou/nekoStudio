cmake_minimum_required(VERSION 4.4.2)

project(nekoStudio)

# 设置 C 标准（可选：90, 99, 11, 17, 23)
set(CMAKE_C_STANDARD 17)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED True)

set(OPENSSL_DIR ${CMAKE_CURRENT_SOURCE_DIR}/openssl/${ANDROID_ABI})

add_library(crypto STATIC IMPORTED)
set_target_properties(crypto PROPERTIES
    IMPORTED_LOCATION "${OPENSSL_DIR}/lib/libcrypto.a"
    INTERFACE_INCLUDE_DIRECTORIES "${OPENSSL_DIR}/include"
)

add_library(ssl STATIC IMPORTED)
set_target_properties(ssl PROPERTIES
    IMPORTED_LOCATION "${OPENSSL_DIR}/lib/libssl.a"
    INTERFACE_INCLUDE_DIRECTORIES "${OPENSSL_DIR}/include"
)

add_library(native-lib SHARED
      native-lib.cpp
      common.cpp
      textlogs/native_engine.cpp
      uid/linux_uid.cpp
)

target_link_libraries(native-lib PRIVATE
    ssl
    crypto
    log
    android
)

target_link_options(native-lib PRIVATE "-Wl,-z,max-page-size=65536")

# 4096 16384 65536

add_executable(android_prop prop/android_prop.cpp)

set_target_properties(android_prop PROPERTIES
    PREFIX "lib"
    SUFFIX ".so"
)

target_link_libraries(android_prop android)
target_link_options(android_prop PRIVATE "-Wl,-z,max-page-size=65536")