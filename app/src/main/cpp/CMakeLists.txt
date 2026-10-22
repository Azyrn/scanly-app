cmake_minimum_required(VERSION 3.22.1)
project(scanlypdf CXX C)

include(ExternalProject)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Pinned rather than floating: a qpdf bump can change output bytes for identical input, and
# signature validation compares byte ranges. Bump deliberately, then re-run PdfSecurityTest.
set(QPDF_VERSION 12.4.0)
set(JPEG_TURBO_VERSION 3.2.0)

set(DEPS_PREFIX ${CMAKE_CURRENT_BINARY_DIR}/deps)
set(JPEG_PREFIX ${DEPS_PREFIX}/jpeg)
set(QPDF_PREFIX ${DEPS_PREFIX}/qpdf)
set(JPEG_LIB ${JPEG_PREFIX}/lib/libjpeg.a)
set(QPDF_LIB ${QPDF_PREFIX}/lib/libqpdf.a)

# Both dependencies are built as ExternalProjects rather than pulled in with FetchContent.
# libjpeg-turbo hard-fails with FATAL_ERROR when it detects add_subdirectory(), and qpdf's
# top-level CMakeLists unconditionally adds its CLI, tests, examples and fuzzers — neither is
# usable as a subdirectory. As separate top-level builds they behave exactly as upstream expects.
# Deliberately minimal: the NDK toolchain file derives CMAKE_SYSTEM_NAME/VERSION and the arch
# from ANDROID_ABI and ANDROID_PLATFORM. Forwarding the outer build's CMAKE_SYSTEM_VERSION instead
# passes down a literal 1 and breaks the sub-configure. CMAKE_MAKE_PROGRAM has to be explicit
# because ExternalProject inherits the Ninja *generator* but not the path to the ninja binary,
# which lives inside the Android SDK rather than on PATH.
set(TOOLCHAIN_ARGS
    -DCMAKE_TOOLCHAIN_FILE=${CMAKE_TOOLCHAIN_FILE}
    -DCMAKE_MAKE_PROGRAM=${CMAKE_MAKE_PROGRAM}
    -DANDROID_ABI=${ANDROID_ABI}
    -DANDROID_PLATFORM=${ANDROID_PLATFORM}
    -DANDROID_NDK=${ANDROID_NDK}
    -DCMAKE_BUILD_TYPE=Release
    -DCMAKE_POSITION_INDEPENDENT_CODE=ON)

ExternalProject_Add(jpeg_ext
    URL https://github.com/libjpeg-turbo/libjpeg-turbo/releases/download/${JPEG_TURBO_VERSION}/libjpeg-turbo-${JPEG_TURBO_VERSION}.tar.gz
    PREFIX ${CMAKE_CURRENT_BINARY_DIR}/jpeg-build
    CMAKE_ARGS
        ${TOOLCHAIN_ARGS}
        -DCMAKE_INSTALL_PREFIX=${JPEG_PREFIX}
        -DENABLE_SHARED=OFF
        -DENABLE_STATIC=ON
        -DWITH_TURBOJPEG=OFF
        -DWITH_JAVA=OFF
    BUILD_BYPRODUCTS ${JPEG_LIB}
    UPDATE_COMMAND "")

ExternalProject_Add(qpdf_ext
    DEPENDS jpeg_ext
    URL https://github.com/qpdf/qpdf/releases/download/v${QPDF_VERSION}/qpdf-${QPDF_VERSION}.tar.gz
    PREFIX ${CMAKE_CURRENT_BINARY_DIR}/qpdf-build
    CMAKE_ARGS
        ${TOOLCHAIN_ARGS}
        -DCMAKE_INSTALL_PREFIX=${QPDF_PREFIX}
        -DBUILD_SHARED_LIBS=OFF
        -DBUILD_STATIC_LIBS=ON
        # qpdf hardcodes POSITION_INDEPENDENT_CODE OFF for static builds, which overrides
        # CMAKE_POSITION_INDEPENDENT_CODE and makes the archive unlinkable into a shared object
        # ("relocation R_AARCH64_* cannot be used against symbol ...; recompile with -fPIC").
        # CMake only ever *adds* -fPIC for that property and never adds -fno-pic, so putting the
        # flag in CXXFLAGS is enough to override it. The NDK toolchain appends its own flags after
        # these, so nothing is lost.
        -DCMAKE_C_FLAGS=-fPIC
        -DCMAKE_CXX_FLAGS=-fPIC
        -DBUILD_DOC=OFF
        -DBUILD_DOC_PDF=OFF
        -DBUILD_DOC_HTML=OFF
        -DBUILD_TESTING=OFF
        -DINSTALL_EXAMPLES=OFF
        -DINSTALL_MANUAL=OFF
        -DINSTALL_CMAKE_PACKAGE=OFF
        -DINSTALL_PKGCONFIG=OFF
        -DOSS_FUZZ=OFF
        # qpdf ships its own AES/RC4/SHA2, so the NDK needs neither OpenSSL nor GnuTLS, and
        # encryption behaves identically on every ABI.
        -DREQUIRE_CRYPTO_NATIVE=ON
        -DUSE_IMPLICIT_CRYPTO=OFF
        -DREQUIRE_CRYPTO_OPENSSL=OFF
        -DREQUIRE_CRYPTO_GNUTLS=OFF
        # qpdf prefers pkg-config for zlib and libjpeg and only falls back to find_path/
        # find_library. pkg-config ignores CMAKE_FIND_ROOT_PATH, so on a Linux host it reports the
        # host's own x86_64 libjpeg and qpdf silently cross-links it into an arm64 build. Forcing
        # the executable to NOTFOUND disables that branch so the seeded paths below are used.
        # CMAKE_DISABLE_FIND_PACKAGE_PkgConfig does not work here: qpdf calls
        # include(FindPkgConfig) rather than find_package(PkgConfig).
        -DPKG_CONFIG_EXECUTABLE=PKG_CONFIG_EXECUTABLE-NOTFOUND
        -DLIBJPEG_H_PATH=${JPEG_PREFIX}/include
        -DLIBJPEG_LIB_PATH=${JPEG_LIB}
    BUILD_BYPRODUCTS ${QPDF_LIB}
    UPDATE_COMMAND "")

add_library(qpdf_static STATIC IMPORTED)
set_target_properties(qpdf_static PROPERTIES IMPORTED_LOCATION ${QPDF_LIB})
add_dependencies(qpdf_static qpdf_ext)

add_library(jpeg_static STATIC IMPORTED)
set_target_properties(jpeg_static PROPERTIES IMPORTED_LOCATION ${JPEG_LIB})
add_dependencies(jpeg_static jpeg_ext)

add_library(scanlypdf SHARED qpdf_jni.cpp)
add_dependencies(scanlypdf qpdf_ext)

# The install tree does not exist at configure time; CMake needs to be told the include
# directory is expected rather than missing.
file(MAKE_DIRECTORY ${QPDF_PREFIX}/include)
target_include_directories(scanlypdf PRIVATE ${QPDF_PREFIX}/include)

target_link_libraries(scanlypdf
    PRIVATE
    qpdf_static
    jpeg_static
    z
    android
    log)

target_compile_options(scanlypdf PRIVATE -fvisibility=hidden -Oz)
