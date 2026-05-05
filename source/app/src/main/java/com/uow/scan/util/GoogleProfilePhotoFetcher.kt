package com.uow.scan.util

import android.content.Context
import android.util.Log
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.api.signin.GoogleSignIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Fetches the signed-in Google user's profile picture URL via the
 * userinfo endpoint and mirrors it into prefs.
 *
 * Why this exists: FirebaseUser.photoUrl, GoogleSignInAccount.photoUrl, and
 * the ID-token `picture` claim are all silent on some accounts even when a
 * profile photo is set. The userinfo REST endpoint is Google's authoritative
 * live source and surfaces the photo when the others don't.
 */
object GoogleProfilePhotoFetcher {

    private const val TAG = "GooglePhotoFetcher"
    private const val SCOPE = "oauth2:https://www.googleapis.com/auth/userinfo.profile"
    private const val USERINFO_URL = "https://www.googleapis.com/oauth2/v3/userinfo"

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Best-effort. Runs on IO. Returns the photo URL if found, else null.
     * Side effect: persists the URL to prefs when found.
     */
    suspend fun refresh(context: Context): String? = withContext(Dispatchers.IO) {
        runCatching {
            val signedIn = GoogleSignIn.getLastSignedInAccount(context)
                ?: return@runCatching null
            val androidAccount = signedIn.account ?: return@runCatching null
            val accessToken = GoogleAuthUtil.getToken(context, androidAccount, SCOPE)
                ?: return@runCatching null

            val req = Request.Builder()
                .url(USERINFO_URL)
                .header("Authorization", "Bearer $accessToken")
                .get()
                .build()

            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@runCatching null
                val body = resp.body?.string() ?: return@runCatching null
                val picture = JSONObject(body).optString("picture", "")
                if (picture.isBlank()) return@runCatching null
                PreferencesManager.setGoogleProfilePhotoUrl(context.applicationContext, picture)
                picture
            }
        }.onFailure { Log.d(TAG, "userinfo fetch failed: ${it.message}") }.getOrNull()
    }
}
