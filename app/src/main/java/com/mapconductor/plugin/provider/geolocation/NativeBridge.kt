package com.mapconductor.plugin.provider.geolocation

object NativeBridge {
    init {
        // CMake の target 名と一致させる
        System.loadLibrary("sample")
    }

    /** JNI 側: Java_com_mapconductor_plugin_provider_geolocation_NativeBridge_concatWorld */
    @JvmStatic external fun concatWorld(input: String): String
}
