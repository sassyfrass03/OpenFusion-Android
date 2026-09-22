cmake_minimum_required(VERSION 3.13)
project(ffrunner VERSION 0.1.0 LANGUAGES C)

if (NOT CMAKE_SIZEOF_VOID_P EQUAL 4)
    message(FATAL_ERROR "Use an x86 toolchain: the supplied Unity Web Player DLLs are 32-bit.")
endif()

add_executable(ffrunner ffrunner.c graphics.c controller.c requests.c logging.c ffrunner.rc)

if (MSVC)
    target_link_options(ffrunner PRIVATE /LARGEADDRESSAWARE /SUBSYSTEM:WINDOWS)
else()
    target_compile_options(ffrunner PRIVATE -O2 -fno-strict-aliasing -fno-tree-vectorize)
    target_link_options(ffrunner PRIVATE -mwindows -static-libgcc -Wl,--large-address-aware,--dynamicbase,--nxcompat)
endif()

set_target_properties(ffrunner PROPERTIES C_STANDARD 99)

target_link_libraries(ffrunner windowscodecs wininet dxguid gdi32 winmm)
