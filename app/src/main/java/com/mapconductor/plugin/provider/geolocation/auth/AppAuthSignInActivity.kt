package com.mapconductor.plugin.provider.geolocation.auth

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

/**
 * Transparent Activity that drives the AppAuth sign-in flow and exchanges the token.
 *
 * - On creation it calls AppAuthTokenProvider.buildAuthorizationIntent() and opens
 *   the browser / Custom Tab.
 * - When the callback Intent returns, it forwards it to handleAuthorizationResponse(),
 *   then finishes itself.
 *
 * No UI is drawn; this Activity exists only to host the OAuth flow.
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
            // Already in the middle of the flow; nothing to do here
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == REQUEST_CODE_AUTH) {
            val provider = AppAuthAuth.get(this)
            lifecycleScope.launch {
                // Result is consumed and the actual getAccessToken() call is made by the caller
                runCatching { provider.handleAuthorizationResponse(data) }
                finish()
            }
        }
    }
}

