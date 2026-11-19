cmake_minimum_required(VERSION 3.18)
project(q25touch C)

# A standalone executable run as root by the app's helper. It is named like a library so
# the build packages it into the APK's native library folder, where it is extracted as an
# executable file.
add_executable(q25touch q25touch.c)
set_target_properties(q25touch PROPERTIES
    OUTPUT_NAME "libq25touch.so"
    SUFFIX ""
    RUNTIME_OUTPUT_DIRECTORY "${CMAKE_LIBRARY_OUTPUT_DIRECTORY}")
target_compile_options(q25touch PRIVATE -O2 -Wall)
target_link_libraries(q25touch log)
