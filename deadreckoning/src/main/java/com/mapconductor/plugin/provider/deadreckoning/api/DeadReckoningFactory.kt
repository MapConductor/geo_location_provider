package com.mapconductor.plugin.provider.geolocation.deadreckoning.api

import android.content.Context
import com.mapconductor.plugin.provider.deadreckoning.impl.DeadReckoningImpl

/**
 * DeadReckoning の生成を行うファクトリ。
 *
 * ■役割
 * - ライブラリ利用側が DeadReckoningImpl などの実装クラスを直接
 *   参照せずに済むよう、API パッケージから生成関数を提供する。
 * - 将来実装を差し替える場合も、このファクトリの戻り値型
 *   (DeadReckoning) だけを安定させておけば互換性を保ちやすい。
 */
object DeadReckoningFactory {

    /**
     * DeadReckoning 実装を生成する。
     *
     * @param context Application / Service コンテキスト
     * @param config  DeadReckoning の挙動を調整する設定値
     * @return DeadReckoning の実装インスタンス
     */
    fun create(context: Context, config: DeadReckoningConfig = DeadReckoningConfig()): DeadReckoning {
        val appContext = context.applicationContext
        return DeadReckoningImpl(appContext, config)
    }
}
