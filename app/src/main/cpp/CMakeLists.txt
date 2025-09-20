# Minimum CMake version: Android Gradle Plugin 8.x 既定の 3.22.x を想定
cmake_minimum_required(VERSION 3.22.1)

project(geolocationprovider_native LANGUAGES C CXX)

# ビルド設定（必要に応じて調整）
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# ===== ライブラリ構成 =====
# sample.cpp は削除済み。以後は drive_uploader.cpp のみをビルド対象にする。
add_library(
        driveuploader
        SHARED
        drive_uploader.cpp
)

# Android の log をリンク（__android_log_print 用）
find_library(log-lib log)
target_link_libraries(driveuploader ${log-lib})

# 追加の警告抑止や最適化を入れたい場合はここに追記
# target_compile_options(driveuploader PRIVATE -Wall -Wextra)
