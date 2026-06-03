package com.uow.scan.util

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Provably-private password breach check — HIBP "Pwned Passwords" range API via
 * **k-anonymity**. SHA-1 is computed on-device; only the first 5 hex characters
 * of the hash are sent (with `Add-Padding`), and ~hundreds of candidate suffixes
 * come back so the server cannot tell which one is being checked. The password
 * and its full hash never leave the device. **No API key required.**
 *
 * [count] is a blocking network call — invoke it from a background dispatcher.
 */
object PwnedPasswords {

    private const val RANGE_URL = "https://api.pwnedpasswords.com/range/"

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    private fun sha1Hex(s: String): String {
        val digest = MessageDigest.getInstance("SHA-1").digest(s.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02X".format(it) }
    }

    /**
     * @return how many times [password] appears across known breaches (0 = none found).
     * @throws IOException on any network/transport failure → caller shows the offline state.
     */
    @Throws(IOException::class)
    fun count(password: String): Int {
        val hash = sha1Hex(password)
        val prefix = hash.substring(0, 5)        // the ONLY thing that leaves the device
        val suffix = hash.substring(5)

        val request = Request.Builder()
            .url(RANGE_URL + prefix)
            .header("Add-Padding", "true")
            .header("User-Agent", "SCAN-Android-PasswordCheck")
            .build()

        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("Pwned Passwords HTTP ${resp.code}")
            val body = resp.body?.string() ?: throw IOException("Empty response")
            for (line in body.lineSequence()) {
                val idx = line.indexOf(':')
                if (idx <= 0) continue
                if (line.substring(0, idx).trim().equals(suffix, ignoreCase = true)) {
                    return line.substring(idx + 1).trim().toIntOrNull() ?: 0
                }
            }
        }
        return 0
    }
}
