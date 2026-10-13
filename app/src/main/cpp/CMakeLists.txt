cmake_minimum_required(VERSION 3.4.1)

project(svnnode CXX)

set(CMAKE_CXX_STANDARD 14)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_library(svnnode SHARED native-lib.cpp)

# libnode is bound at runtime via dlopen/dlsym, so we only need the system
# log + dl libs at build time. To link libnode statically instead, drop the
# nodejs-mobile headers into app/libnode/include and add:
#   include_directories(${CMAKE_SOURCE_DIR}/../libnode/include/node)
#   add_library(libnode SHARED IMPORTED)
#   set_target_properties(libnode PROPERTIES IMPORTED_LOCATION
#       ${CMAKE_SOURCE_DIR}/../libnode/bin/${ANDROID_ABI}/libnode.so)
#   target_link_libraries(svnnode libnode)
find_library(log-lib log)
target_link_libraries(svnnode ${log-lib} dl)
