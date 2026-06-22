cmake_minimum_required(VERSION 3.22.1)
project(btl_crypto)

include(FetchContent)
FetchContent_Declare(
    libsodium
    GIT_REPOSITORY https://github.com/robinlinden/libsodium-cmake.git
)
set(LIBSODIUM_BUILD_TESTS OFF CACHE BOOL "" FORCE)
FetchContent_MakeAvailable(libsodium)

add_library(btl_crypto SHARED btl_crypto.cpp)
target_link_libraries(btl_crypto PRIVATE sodium log android)
