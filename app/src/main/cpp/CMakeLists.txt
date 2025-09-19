cmake_minimum_required(VERSION 3.22.1)
project(sample)

add_library(sample SHARED sample.cpp)

find_library(log-lib log)
target_link_libraries(sample ${log-lib})
