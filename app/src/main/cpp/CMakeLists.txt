cmake_minimum_required(VERSION 3.22.1)
project(wattscope)
add_executable(wattscope_collector collector.cpp)
target_compile_options(wattscope_collector PRIVATE -O2 -Wall -Wextra)
target_link_libraries(wattscope_collector android log)
# Package the standalone PIE executable via the APK native-library mechanism.
set_target_properties(wattscope_collector PROPERTIES OUTPUT_NAME "libcollector.so" RUNTIME_OUTPUT_DIRECTORY "${CMAKE_LIBRARY_OUTPUT_DIRECTORY}")
