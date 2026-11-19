cmake_minimum_required(VERSION 3.22.1)
project(moonlighthost C)

# ENet (cgutman/enet, MIT): reliable UDP for the Moonlight control stream.
set(ENET_NO_INSTALL ON CACHE BOOL "" FORCE)
add_subdirectory(enet ${CMAKE_CURRENT_BINARY_DIR}/enet)

# libopus (Xiph, BSD-3-Clause): 5 ms low-delay audio frames.
set(OPUS_INSTALL_PKG_CONFIG_MODULE OFF CACHE BOOL "" FORCE)
set(OPUS_INSTALL_CMAKE_CONFIG_MODULE OFF CACHE BOOL "" FORCE)
add_subdirectory(opus ${CMAKE_CURRENT_BINARY_DIR}/opus)

# nanors (MIT): Reed-Solomon for video FEC, same sources as moonlight-common-c.
add_library(moonlighthost SHARED
        jni/enet_jni.c
        jni/opus_jni.c
        jni/fec_jni.c
        nanors/rs.c
        nanors/deps/obl/oblas_common.c
        nanors/deps/obl/oblas_lite.c)
target_include_directories(moonlighthost PRIVATE enet/include opus/include nanors nanors/deps nanors/deps/obl)
target_link_libraries(moonlighthost enet opus log)

# Export only the JNIEXPORT entry points. Host apps often ship their own ENet or libopus
# (for netplay, audio...), and exported copies here could be interposed with theirs.
set_target_properties(moonlighthost PROPERTIES C_VISIBILITY_PRESET hidden)
target_link_options(moonlighthost PRIVATE "-Wl,--exclude-libs,ALL")
