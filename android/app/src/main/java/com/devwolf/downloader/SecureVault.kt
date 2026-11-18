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

data class StoredPlatformSession(
    val platform: String,
    val configured: Boolean,
    val authType: String,
    val username: String? = null,
    val cookieHeader: String? = null,
    val accessToken: String? = null,
    val refreshToken: String? = null,
    val clientId: String? = null,
    val expiresAtEpochMs: Long? = null,
    val userAgent: String? = null,
    val updatedAt: String? = null,
    val source: String = "manual",
    val hasSessionId: Boolean = false,
)

/**
 * Android Keystore-backed encrypted storage for isolated per-platform session cookies,
 * OAuth2 tokens (Reddit Installed App PKCE), WebView session metadata, and local engine updater state.
 * Credentials never leave the device and are never logged or exposed to the JS bridge.
 */
class SecureVault internal constructor(
    private val encryptedPrefsProvider: () -> SharedPreferences,
    private val metaPrefsProvider: () -> SharedPreferences,
) {
    constructor(context: Context) : this(
        encryptedPrefsProvider = {
            val appCtx = context.applicationContext
            val masterKey = MasterKey.Builder(appCtx)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                appCtx,
                "devwolf_secure_vault",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        },
        metaPrefsProvider = {
            context.applicationContext.getSharedPreferences("devwolf_engine_meta", Context.MODE_PRIVATE)
        },
    )

    private val encryptedPrefs: SharedPreferences by lazy { encryptedPrefsProvider() }
    private val metaPrefs: SharedPreferences by lazy { metaPrefsProvider() }

    fun getAllPlatformSessions(): List<StoredPlatformSession> {
        return UrlExtractor.ALLOWED_VAULT_PLATFORMS.sorted().map { platform ->
            readPlatformSession(platform)
        }
    }

    fun getPlatformSession(platform: String): StoredPlatformSession? {
        val normalized = platform.trim().lowercase()
        if (normalized !in UrlExtractor.ALLOWED_VAULT_PLATFORMS) return null
        return readPlatformSession(normalized).takeIf { it.configured }
    }

    private fun readPlatformSession(platform: String): StoredPlatformSession {
        val cookie = encryptedPrefs.getString("session_cookie_$platform", null)?.takeIf { it.isNotBlank() }
        val accessToken = encryptedPrefs.getString("session_access_token_$platform", null)?.takeIf { it.isNotBlank() }
        val refreshToken = encryptedPrefs.getString("session_refresh_token_$platform", null)?.takeIf { it.isNotBlank() }
        val clientId = encryptedPrefs.getString("session_client_id_$platform", null)?.takeIf { it.isNotBlank() }
        val username = encryptedPrefs.getString("session_username_$platform", null)?.takeIf { it.isNotBlank() }
        val userAgent = encryptedPrefs.getString("session_ua_$platform", null)?.takeIf { it.isNotBlank() }
        val updatedAt = encryptedPrefs.getString("session_updated_$platform", null)?.takeIf { it.isNotBlank() }
        val source = encryptedPrefs.getString("session_source_$platform", null)?.takeIf { it.isNotBlank() } ?: "manual"
        val expiresAtRaw = encryptedPrefs.getLong("session_expires_at_$platform", -1L)
        val expiresAtEpochMs = if (expiresAtRaw > 0L) expiresAtRaw else null
        val configured = !cookie.isNullOrBlank() || !accessToken.isNullOrBlank()
        val storedAuthType = encryptedPrefs.getString("session_authtype_$platform", null)?.takeIf { it.isNotBlank() }
        val authType = storedAuthType ?: when {
            !accessToken.isNullOrBlank() -> AUTH_TYPE_OAUTH_TOKEN
            source == "webview" -> AUTH_TYPE_WEBVIEW_COOKIE
            else -> AUTH_TYPE_COOKIE
        }
        return StoredPlatformSession(
            platform = platform,
            configured = configured,
            authType = authType,
            username = username,
            cookieHeader = cookie,
            accessToken = accessToken,
            refreshToken = refreshToken,
            clientId = clientId,
            expiresAtEpochMs = expiresAtEpochMs,
            userAgent = userAgent,
            updatedAt = updatedAt,
            source = source,
            hasSessionId = hasCookieKey(cookie, "sessionid"),
        )
    }

    /**
     * Returns sanitized session summaries (without raw cookies or OAuth tokens) for the UI bridge.
     */
    fun getPlatformSessions(): JSONArray {
        val arr = JSONArray()
        for (session in getAllPlatformSessions()) {
            arr.put(
                JSONObject().apply {
                    put("platform", session.platform)
                    put("configured", session.configured)
                    put("authType", session.authType)
                    put("username", session.username ?: JSONObject.NULL)
                    put("clientId", session.clientId ?: JSONObject.NULL)
                    put("expiresAtEpochMs", session.expiresAtEpochMs ?: JSONObject.NULL)
                    put("updatedAt", session.updatedAt ?: JSONObject.NULL)
                    put("source", session.source)
                    put("hasCookie", !session.cookieHeader.isNullOrBlank())
                    put("hasAccessToken", !session.accessToken.isNullOrBlank())
                    if (session.platform == "instagram" && session.configured) {
                        put("hasSessionId", session.hasSessionId)
                    }
                },
            )
        }
        return arr
    }

    fun savePlatformSession(platform: String, cookieHeader: String): String {
        return savePlatformSession(
            platform = platform,
            cookieHeader = cookieHeader,
            authType = AUTH_TYPE_COOKIE,
            source = "manual",
        )
    }

    fun savePlatformCookieSession(
        platform: String,
        cookieHeader: String,
        userAgent: String? = null,
        source: String = "webview",
        username: String? = null,
    ): String {
        val authType = if (source == "webview") AUTH_TYPE_WEBVIEW_COOKIE else AUTH_TYPE_COOKIE
        return savePlatformSession(
            platform = platform,
            cookieHeader = cookieHeader,
            userAgent = userAgent,
            username = username,
            authType = authType,
            source = source,
        )
    }

    fun savePlatformOAuthSession(
        platform: String,
        accessToken: String,
        refreshToken: String? = null,
        clientId: String? = null,
        username: String? = null,
        expiresAtEpochMs: Long? = null,
        source: String = "oauth",
    ): String {
        return savePlatformSession(
            platform = platform,
            accessToken = accessToken,
            refreshToken = refreshToken,
            clientId = clientId,
            username = username,
            expiresAtEpochMs = expiresAtEpochMs,
            authType = AUTH_TYPE_OAUTH_TOKEN,
            source = source,
        )
    }

    fun savePlatformSession(
        platform: String,
        cookieHeader: String? = null,
        accessToken: String? = null,
        refreshToken: String? = null,
        clientId: String? = null,
        expiresAtEpochMs: Long? = null,
        username: String? = null,
        userAgent: String? = null,
        authType: String? = null,
        source: String = "manual",
    ): String {
        val normalized = platform.trim().lowercase()
        require(normalized in UrlExtractor.ALLOWED_VAULT_PLATFORMS) {
            "Plataforma não suportada no cofre: $platform"
        }
        val cleanCookie = cookieHeader?.trim()?.takeIf { it.isNotEmpty() }
        val cleanAccess = accessToken?.trim()?.takeIf { it.isNotEmpty() }
        val cleanRefresh = refreshToken?.trim()?.takeIf { it.isNotEmpty() }
        val cleanClientId = clientId?.trim()?.takeIf { it.isNotEmpty() }
        val cleanUsername = username?.trim()?.takeIf { it.isNotEmpty() }
        val cleanUa = userAgent?.trim()?.takeIf { it.isNotEmpty() }

        require(cleanCookie != null || cleanAccess != null) {
            "Credencial de sessão inválida"
        }
        listOfNotNull(cleanCookie, cleanAccess, cleanRefresh, cleanClientId, cleanUsername, cleanUa).forEach { value ->
            require(!value.contains('\n') && !value.contains('\r')) {
                "Credencial contém caracteres de controle inválidos"
            }
        }

        val effectiveAuthType = when (authType?.trim()?.lowercase()) {
            AUTH_TYPE_WEBVIEW_COOKIE -> AUTH_TYPE_WEBVIEW_COOKIE
            AUTH_TYPE_OAUTH_TOKEN -> AUTH_TYPE_OAUTH_TOKEN
            AUTH_TYPE_COOKIE -> AUTH_TYPE_COOKIE
            else -> when {
                cleanAccess != null -> AUTH_TYPE_OAUTH_TOKEN
                source == "webview" -> AUTH_TYPE_WEBVIEW_COOKIE
                else -> AUTH_TYPE_COOKIE
            }
        }

        val now = Instant.now().toString()
        val editor = encryptedPrefs.edit()
            .putString("session_updated_$normalized", now)
            .putString("session_source_$normalized", source)
            .putString("session_authtype_$normalized", effectiveAuthType)

        if (cleanCookie != null) {
            editor.putString("session_cookie_$normalized", cleanCookie)
        } else {
            editor.remove("session_cookie_$normalized")
        }
        if (cleanAccess != null) {
            editor.putString("session_access_token_$normalized", cleanAccess)
        } else {
            editor.remove("session_access_token_$normalized")
        }
        if (cleanRefresh != null) {
            editor.putString("session_refresh_token_$normalized", cleanRefresh)
        } else {
            editor.remove("session_refresh_token_$normalized")
        }
        if (cleanClientId != null) {
            editor.putString("session_client_id_$normalized", cleanClientId)
        } else {
            editor.remove("session_client_id_$normalized")
        }
        if (cleanUsername != null) {
            editor.putString("session_username_$normalized", cleanUsername)
        } else {
            editor.remove("session_username_$normalized")
        }
        if (cleanUa != null) {
            editor.putString("session_ua_$normalized", cleanUa)
        } else {
            editor.remove("session_ua_$normalized")
        }
        if (expiresAtEpochMs != null && expiresAtEpochMs > 0L) {
            editor.putLong("session_expires_at_$normalized", expiresAtEpochMs)
        } else {
            editor.remove("session_expires_at_$normalized")
        }

        editor.apply()
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
        val authType = if (source == "webview") AUTH_TYPE_WEBVIEW_COOKIE else AUTH_TYPE_COOKIE
        return savePlatformSession(
            platform = "instagram",
            cookieHeader = cleanCookie,
            userAgent = userAgent,
            authType = authType,
            source = source,
        )
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
            .remove("session_authtype_$normalized")
            .remove("session_username_$normalized")
            .remove("session_access_token_$normalized")
            .remove("session_refresh_token_$normalized")
            .remove("session_client_id_$normalized")
            .remove("session_expires_at_$normalized")
            .apply()
        return true
    }

    fun revokeSession(platform: String): Boolean = revokePlatformSession(platform)

    fun getCookieForPlatform(platform: String): String? {
        val normalized = platform.trim().lowercase()
        if (normalized !in UrlExtractor.ALLOWED_VAULT_PLATFORMS) return null
        return encryptedPrefs.getString("session_cookie_$normalized", null)?.takeIf { it.isNotBlank() }
    }

    fun getAccessTokenForPlatform(platform: String): String? {
        val normalized = platform.trim().lowercase()
        if (normalized !in UrlExtractor.ALLOWED_VAULT_PLATFORMS) return null
        return encryptedPrefs.getString("session_access_token_$normalized", null)?.takeIf { it.isNotBlank() }
    }

    fun getUserAgentForPlatform(platform: String): String? {
        val normalized = platform.trim().lowercase()
        if (normalized !in UrlExtractor.ALLOWED_VAULT_PLATFORMS) return null
        return encryptedPrefs.getString("session_ua_$normalized", null)?.takeIf { it.isNotBlank() }
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
        const val AUTH_TYPE_WEBVIEW_COOKIE = "webview_cookie"
        const val AUTH_TYPE_OAUTH_TOKEN = "oauth_token"
        const val AUTH_TYPE_COOKIE = "cookie"

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
