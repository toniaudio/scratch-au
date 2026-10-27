cmake_minimum_required(VERSION 3.22.1)
project(djaaay CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Motore audio: C++ puro in ../../../../engine (testato anche su Linux in tests/)
set(ENGINE_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../../engine)
file(GLOB ENGINE_SOURCES ${ENGINE_DIR}/*.cpp)

find_package(oboe REQUIRED CONFIG)

add_library(djaaay SHARED djaaay_jni.cpp ${ENGINE_SOURCES})
target_include_directories(djaaay PRIVATE ${ENGINE_DIR})
target_compile_options(djaaay PRIVATE -Wall -Wextra -ffast-math)
target_link_libraries(djaaay oboe::oboe android log)
