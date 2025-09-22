package com.mapconductor.plugin.provider.geolocation

/**
 * アップロードの実行エンジンを切り替えるトグル。
 * - none   : 生成のみ（アップロードをしない）
 * - kotlin : Kotlin/OkHttp 経路
 * - jni    : C++(JNI) -> Kotlin 経路
 * - native : C++(libcurl等) で完結
 */
enum class UploadEngine(val wire: String) {
    NONE("none"),
    KOTLIN("kotlin");

    companion object {
        fun fromString(s: String?): UploadEngine =
            entries.firstOrNull { it.wire.equals(s, ignoreCase = true) } ?: NONE
    }
}
