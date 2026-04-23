cmake_minimum_required(VERSION 3.22.1)

Project(Pluvia)

set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -O2 -Wno-unused-function -Wimplicit-function-declaration")

add_subdirectory(adrenotools)

add_library(extras SHARED
        gpu_image.c
        vulkan.c)

target_link_libraries(extras
        log
        android
        EGL
        GLESv2
        GLESv3
        adrenotools)

add_library(evshim SHARED evshim.c)
target_include_directories(evshim PRIVATE sdl2_stub)
target_link_libraries(evshim dl)
set_target_properties(evshim PROPERTIES LINK_FLAGS "-Wl,--as-needed")
