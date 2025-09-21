// app/src/main/java/com/mapconductor/plugin/provider/geolocation/DriveAuthActions.kt
package com.mapconductor.plugin.provider.geolocation

import android.app.Activity
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInStatusCodes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope

@Composable
fun DriveAuthActions() {
    val ctx = LocalContext.current
    val activity = ctx as? Activity ?: return
    val scope: CoroutineScope = rememberCoroutineScope()
    val gms = remember { GoogleApiAvailability.getInstance() }

    val signInLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
        try {
            val acc = task.getResult(ApiException::class.java)
            Toast.makeText(ctx, "Signed in: ${acc.email}", Toast.LENGTH_SHORT).show()
            if (!GoogleAuthRepository.hasDriveScope(acc)) {
                GoogleAuthRepository.requestDriveScope(activity, acc)
            }
        } catch (e: ApiException) {
            val code = e.statusCode
            val readable = GoogleSignInStatusCodes.getStatusCodeString(code)
            android.util.Log.e("DriveAuth", "Sign-in failed: code=$code ($readable)", e)
            Toast.makeText(ctx, "Sign-in failed: $code ($readable)", Toast.LENGTH_LONG).show()
        }
    }

    TextButton(onClick = {
        val code = gms.isGooglePlayServicesAvailable(ctx)
        if (code != ConnectionResult.SUCCESS) {
            gms.getErrorDialog(activity, code, 1001)?.show()
        } else {
            signInLauncher.launch(GoogleAuthRepository.signInIntent(ctx))
        }
    }) { Text("Sign in") }

    TextButton(onClick = {
        scope.launch(Dispatchers.IO) {
            val token = GoogleAuthRepository.getAccessToken(ctx)
            val msg = if (token != null) "Token: ${token.take(14)}…" else "Token: null"
            activity.runOnUiThread {
                Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
            }
        }
    }) { Text("Get Token") }
}
