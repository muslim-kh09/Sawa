cmake_minimum_required(VERSION 3.22.1)
project(btl_crypto)

# 1. Fetch libsodium dynamically
include(FetchContent)
FetchContent_Declare(
    libsodium
    GIT_REPOSITORY https://github.com/robinlinden/libsodium-cmake.git
    GIT_TAG        v1.0.18
)
set(LIBSODIUM_BUILD_TESTS OFF CACHE BOOL "" FORCE)
FetchContent_MakeAvailable(libsodium)

# 2. Declare our native library
add_library(btl_crypto SHARED btl_crypto.cpp)

# 3. Link libsodium and Android system libraries
target_link_libraries(btl_crypto PRIVATE sodium log android)
