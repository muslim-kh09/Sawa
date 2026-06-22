cmake_minimum_required(VERSION 3.22.1)

project("btl_crypto")

add_library(
        btl_crypto
        SHARED
        btl_crypto.cpp)

find_library(
        log-lib
        log)

find_library(
        android-lib
        android)

target_compile_options(btl_crypto PRIVATE -O3 -Wall -Werror)

target_link_libraries(
        btl_crypto
        ${log-lib}
        ${android-lib})
