package com.devwolf.downloader

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

/**
 * Android Keystore-backed encrypted storage for optional per-platform session cookies
 * and local engine updater metadata. Cookies never leave the device.
 */
class SecureVault(context: Context) {
    private val appContext = context.applicationContext

    private val encryptedPrefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(appContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            appContext,
            "devwolf_secure_vault",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    private val metaPrefs: SharedPreferences by lazy {
        appContext.getSharedPreferences("devwolf_engine_meta", Context.MODE_PRIVATE)
    }

    fun getPlatformSessions(): JSONArray {
        val arr = JSONArray()
        for (platform in UrlExtractor.ALLOWED_VAULT_PLATFORMS.sorted()) {
            val updatedAt = encryptedPrefs.getString("session_updated_$platform", null)
            val configured = !encryptedPrefs.getString("session_cookie_$platform", null).isNullOrBlank()
            arr.put(
                JSONObject().apply {
                    put("platform", platform)
                    put("configured", configured)
                    put("updatedAt", updatedAt ?: JSONObject.NULL)
                },
            )
        }
        return arr
    }

    fun savePlatformSession(platform: String, cookieHeader: String): String {
        val normalized = platform.trim().lowercase()
        require(normalized in UrlExtractor.ALLOWED_VAULT_PLATFORMS) {
            "Plataforma não suportada no cofre: $platform"
        }
        val cleanCookie = cookieHeader.trim()
        require(cleanCookie.isNotEmpty() && !cleanCookie.contains('\n') && !cleanCookie.contains('\r')) {
            "Cookie de sessão inválido"
        }
        val now = Instant.now().toString()
        encryptedPrefs.edit()
            .putString("session_cookie_$normalized", cleanCookie)
            .putString("session_updated_$normalized", now)
            .apply()
        return now
    }

    fun revokePlatformSession(platform: String): Boolean {
        val normalized = platform.trim().lowercase()
        if (normalized !in UrlExtractor.ALLOWED_VAULT_PLATFORMS) return false
        encryptedPrefs.edit()
            .remove("session_cookie_$normalized")
            .remove("session_updated_$normalized")
            .apply()
        return true
    }

    fun getCookieForPlatform(platform: String): String? {
        val normalized = platform.trim().lowercase()
        if (normalized !in UrlExtractor.ALLOWED_VAULT_PLATFORMS) return null
        return encryptedPrefs.getString("session_cookie_$normalized", null)?.takeIf { it.isNotBlank() }
    }

    var previousYtdlpVersion: String?
        get() = metaPrefs.getString("previous_ytdlp_version", null)
        set(value) = metaPrefs.edit().putString("previous_ytdlp_version", value).apply()

    var lastCheckAt: String?
        get() = metaPrefs.getString("last_check_at", null)
        set(value) = metaPrefs.edit().putString("last_check_at", value).apply()

    var lastUpdateAt: String?
        get() = metaPrefs.getString("last_update_at", null)
        set(value) = metaPrefs.edit().putString("last_update_at", value).apply()

    var lastEngineError: String?
        get() = metaPrefs.getString("last_engine_error", null)
        set(value) = metaPrefs.edit().putString("last_engine_error", value).apply()

    var latestKnownYtdlpVersion: String?
        get() = metaPrefs.getString("latest_known_ytdlp_version", null)
        set(value) = metaPrefs.edit().putString("latest_known_ytdlp_version", value).apply()
}
