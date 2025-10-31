package com.mapconductor.plugin.provider.storageservice.domain

data class LocationLog(
    val timestampMillis: Long,
    val lat: Double,
    val lon: Double,
    val accuracyM: Float? = null,
    val speedMps: Float? = null,
    val provider: Provider = Provider.GPS,        // ★ 追加: GPS/IMU
    val horizontalStdM: Float? = null             // ★ 追加: 予測の等方1σ(平面合成)
) {
    enum class Provider { GPS, IMU }
}
