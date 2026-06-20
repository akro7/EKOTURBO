cmake_minimum_required(VERSION 3.22.1)

project("EKO_TURBO_ENGINE" LANGUAGES CXX C)

set(CMAKE_CXX_STANDARD 23)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)

# ─── Include Paths ────────────────────────────────────────────────────────────
set(EKO_INCLUDES
    EKO/src
    EKO/src/third_party/lz4
    EKO/src/third_party/md5
    EKO/src/third_party/xxhash
    EKO/src/third_party/fmt/include
    EKO/src/third_party/spdlog/include
)

# ─── Sources ──────────────────────────────────────────────────────────────────
file(GLOB_RECURSE CORE_SOURCES     "EKO/src/core/*.cpp"     "EKO/src/core/*.c")
file(GLOB_RECURSE APP_SOURCES      "EKO/src/app/*.cpp")
file(GLOB_RECURSE IO_SOURCES       "EKO/src/io/*.cpp")
file(GLOB_RECURSE PROTOCOL_SOURCES "EKO/src/protocol/odin/*.cpp")

if(ANDROID)
    file(GLOB_RECURSE PLATFORM_SOURCES
        "EKO/src/platform/linux/*.cpp"
        "EKO/src/platform/posix-common/*.cpp"
    )
elseif(WIN32)
    file(GLOB_RECURSE PLATFORM_SOURCES
        "EKO/src/platform/windows/*.cpp"
    )
elseif(APPLE)
    file(GLOB_RECURSE PLATFORM_SOURCES
        "EKO/src/platform/macos/*.cpp"
        "EKO/src/platform/posix-common/*.cpp"
    )
else()
    # Generic Linux/POSIX
    file(GLOB_RECURSE PLATFORM_SOURCES
        "EKO/src/platform/linux/*.cpp"
        "EKO/src/platform/posix-common/*.cpp"
    )
endif()

file(GLOB THIRD_PARTY_SOURCES
    "EKO/src/third_party/lz4/*.c"
    "EKO/src/third_party/md5/*.c"
)

set(ALL_SOURCES
    native-lib.cpp
    ${CORE_SOURCES}
    ${APP_SOURCES}
    ${IO_SOURCES}
    ${PROTOCOL_SOURCES}
    ${PLATFORM_SOURCES}
    ${THIRD_PARTY_SOURCES}
)

# ─── Library Target ───────────────────────────────────────────────────────────
add_library(turbo_engine SHARED ${ALL_SOURCES})

target_include_directories(turbo_engine PRIVATE ${EKO_INCLUDES})

# ─── Android System Libraries ─────────────────────────────────────────────────
find_library(log-lib     log)
find_library(android-lib android)
find_library(z-lib       z)

target_link_libraries(turbo_engine PRIVATE
    ${log-lib}
    ${android-lib}
    ${z-lib}
    atomic
    m
    # FIX: c++_shared يُربط عبر find_library لا اسم مجرد — NDK يوفره تلقائياً عبر ANDROID_STL
)

# ─── Features & Definitions ───────────────────────────────────────────────────
target_compile_features(turbo_engine PRIVATE cxx_std_23 c_std_11)

target_compile_definitions(turbo_engine PRIVATE
    XXH_INLINE_ALL
    FMT_HEADER_ONLY=1
    SPDLOG_FMT_EXTERNAL=1
    _LIBCPP_ENABLE_EXPERIMENTAL_FEATURES=1
    _LIBCPP_DISABLE_DEPRECATION_WARNINGS=1
    _LIBCPP_ENABLE_CXX20_SUPPORT=1
    # FIX: تعريف المنصة — مطلوب لـ platform_all.hpp يختار linux/android headers
    BROKKR_PLATFORM_LINUX=1
    BROKKR_VERSION="EKO-TURBO-3.2-SPEED"
    BROKKR_BUILD_TYPE="$<IF:$<CONFIG:Debug>,debug,release>"
    BROKKR_COMMIT_COUNT=0
    $<$<CONFIG:Release>:NDEBUG>
    $<$<CONFIG:Debug>:EKO_DEBUG_LOG>
)

# ─── Compile Options ──────────────────────────────────────────────────────────
target_compile_options(turbo_engine PRIVATE
    -Wall
    -Wextra
    -Wno-unused-parameter
    -Wno-unused-variable
    -Wno-error=deprecated-declarations
    -Wno-error=unused-result
    -Wno-error=unused-function
    -Wno-unknown-pragmas
    -fexceptions
    -frtti
    -fvisibility=hidden
    -fvisibility-inlines-hidden
    -ffunction-sections
    -fdata-sections
    # ── Android OTG Speed Optimizations ──────────────────────────────────────
    # O3: أقصى تحسين للـ compiler — يُسرّع LZ4 decompress + URB packaging
    $<$<CONFIG:Release>:-O3>
    # march=native غير متاح على NDK — نستخدم armv8-a+crypto+simd
    $<$<AND:$<CONFIG:Release>,$<STREQUAL:${ANDROID_ABI},arm64-v8a>>:-march=armv8-a+crypto>
    $<$<AND:$<CONFIG:Release>,$<STREQUAL:${ANDROID_ABI},arm64-v8a>>:-mtune=cortex-a55>
    # LTO: يُصغّر الكود ويزيد الـ inlining عبر الـ translation units
    $<$<CONFIG:Release>:-flto=thin>
    $<$<CONFIG:Debug>:-g>
    $<$<CONFIG:Debug>:-O0>
)

# ─── Link Options ─────────────────────────────────────────────────────────────
target_link_options(turbo_engine PRIVATE
    -Wl,--as-needed
    -Wl,--gc-sections
    $<$<CONFIG:Release>:-Wl,--strip-all>
    $<$<CONFIG:Release>:-flto=thin>
)

# ─── Status ───────────────────────────────────────────────────────────────────
message(STATUS "╔══════════════════════════════════════╗")
message(STATUS "║       EKO TURBO ENGINE — BUILD       ║")
message(STATUS "╠══════════════════════════════════════╣")
message(STATUS "║  ABI    : ${ANDROID_ABI}")
message(STATUS "║  API    : ${ANDROID_PLATFORM}")
message(STATUS "║  Type   : ${CMAKE_BUILD_TYPE}")
message(STATUS "║  C++    : ${CMAKE_CXX_STANDARD}")
message(STATUS "╚══════════════════════════════════════╝")
