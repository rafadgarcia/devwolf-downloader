package com.devwolf.downloader

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

data class StoredInstagramSession(
    val cookieHeader: String,
    val userAgent: String?,
    val csrfToken: String?,
    val hasSessionId: Boolean,
    val updatedAt: String?,
    val source: String,
)

/**
 * Android Keystore-backed encrypted storage for optional per-platform session cookies,
 * WebView Instagram session metadata, and local engine updater state.
 * Cookies never leave the device and are never logged.
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
            val cookie = encryptedPrefs.getString("session_cookie_$platform", null)
            val configured = !cookie.isNullOrBlank()
            val source = encryptedPrefs.getString("session_source_$platform", null)
            arr.put(
                JSONObject().apply {
                    put("platform", platform)
                    put("configured", configured)
                    put("updatedAt", updatedAt ?: JSONObject.NULL)
                    if (platform == "instagram" && configured) {
                        put("hasSessionId", hasCookieKey(cookie, "sessionid"))
                        put("source", source ?: "manual")
                    }
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
            .putString("session_source_$normalized", "manual")
            .apply()
        return now
    }

    fun saveInstagramSession(
        cookieHeader: String,
        userAgent: String? = null,
        source: String = "webview",
    ): String {
        val cleanCookie = cookieHeader.trim()
        require(cleanCookie.isNotEmpty() && !cleanCookie.contains('\n') && !cleanCookie.contains('\r')) {
            "Cookie de sessão do Instagram inválido"
        }
        val now = Instant.now().toString()
        val editor = encryptedPrefs.edit()
            .putString("session_cookie_instagram", cleanCookie)
            .putString("session_updated_instagram", now)
            .putString("session_source_instagram", source)

        val cleanUa = userAgent?.trim()?.takeIf { it.isNotEmpty() && !it.contains('\n') && !it.contains('\r') }
        if (cleanUa != null) {
            editor.putString("session_ua_instagram", cleanUa)
        } else {
            editor.remove("session_ua_instagram")
        }
        editor.apply()
        return now
    }

    fun getInstagramSession(): StoredInstagramSession? {
        val cookie = encryptedPrefs.getString("session_cookie_instagram", null)
            ?.takeIf { it.isNotBlank() } ?: return null
        val ua = encryptedPrefs.getString("session_ua_instagram", null)?.takeIf { it.isNotBlank() }
        val updatedAt = encryptedPrefs.getString("session_updated_instagram", null)
        val source = encryptedPrefs.getString("session_source_instagram", null) ?: "manual"
        return StoredInstagramSession(
            cookieHeader = cookie,
            userAgent = ua,
            csrfToken = extractCookieValue(cookie, "csrftoken"),
            hasSessionId = hasCookieKey(cookie, "sessionid"),
            updatedAt = updatedAt,
            source = source,
        )
    }

    fun revokePlatformSession(platform: String): Boolean {
        val normalized = platform.trim().lowercase()
        if (normalized !in UrlExtractor.ALLOWED_VAULT_PLATFORMS) return false
        encryptedPrefs.edit()
            .remove("session_cookie_$normalized")
            .remove("session_updated_$normalized")
            .remove("session_ua_$normalized")
            .remove("session_source_$normalized")
            .apply()
        return true
    }

    fun revokeSession(platform: String): Boolean = revokePlatformSession(platform)

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

    var updateChannel: String
        get() = metaPrefs.getString("ytdlp_update_channel", "stable") ?: "stable"
        set(value) {
            val clean = if (value.equals("nightly", ignoreCase = true)) "nightly" else "stable"
            metaPrefs.edit().putString("ytdlp_update_channel", clean).apply()
        }

    companion object {
        fun extractCookieValue(cookieHeader: String?, name: String): String? {
            if (cookieHeader.isNullOrBlank()) return null
            val target = name.trim().lowercase()
            for (part in cookieHeader.split(';')) {
                val trimmed = part.trim()
                val eq = trimmed.indexOf('=')
                if (eq <= 0) continue
                val key = trimmed.substring(0, eq).trim().lowercase()
                if (key == target) {
                    val value = trimmed.substring(eq + 1).trim().trim('"')
                    if (value.isNotEmpty()) return value
                }
            }
            return null
        }

        fun hasCookieKey(cookieHeader: String?, name: String): Boolean {
            return extractCookieValue(cookieHeader, name) != null
        }
    }
}
