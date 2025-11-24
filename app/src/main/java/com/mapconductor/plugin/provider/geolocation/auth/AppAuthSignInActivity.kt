package com.mapconductor.plugin.provider.geolocation.auth

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

/**
 * AppAuth のサインインフローを開始し、結果を受け取ってトークン交換まで行うための Activity。
 *
 * - 起動と同時に AppAuthTokenProvider.buildAuthorizationIntent() を呼び出し、
 *   ブラウザ / Custom Tab を開く。
 * - コールバックで戻ってきた Intent を handleAuthorizationResponse() に渡し、
 *   トークン取得が終わったら自動的に finish() する。
 *
 * UI には何も描画せず、あくまでフロー専用の透明な Activity として扱う想定。
 */
@Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
class AppAuthSignInActivity : ComponentActivity() {

    companion object {
        private const val REQUEST_CODE_AUTH = 1001
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (savedInstanceState == null) {
            val provider = AppAuthAuth.get(this)
            val intent = provider.buildAuthorizationIntent()
            startActivityForResult(intent, REQUEST_CODE_AUTH)
        } else {
            // すでにフロー中であれば何もしない
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == REQUEST_CODE_AUTH) {
            val provider = AppAuthAuth.get(this)
            lifecycleScope.launch {
                // 成否はログや呼び出し側の getAccessToken() で確認してもらう
                runCatching { provider.handleAuthorizationResponse(data) }
                finish()
            }
        }
    }
}

