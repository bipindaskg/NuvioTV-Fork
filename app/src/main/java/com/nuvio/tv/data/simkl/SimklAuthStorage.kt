package com.nuvio.tv.data.simkl

import kotlinx.coroutines.flow.StateFlow

data class SimklAuthScope(
    val profileId: Int,
    val generation: Long
)

data class SimklAuthorization(
    val scope: SimklAuthScope,
    val accessToken: String,
    /** AUTH V2 tokens expire; null for long-lived AUTH V1 tokens. */
    val expiresAtEpochMs: Long? = null,
    val canRefresh: Boolean = false
)

private const val REFRESH_MARGIN_MS = 24L * 60L * 60L * 1_000L

/** True when the access token is a day or less from expiring and can be renewed. */
fun SimklAuthorization.needsRefresh(nowEpochMs: Long, marginMs: Long = REFRESH_MARGIN_MS): Boolean {
    val expiresAt = expiresAtEpochMs ?: return false
    return canRefresh && nowEpochMs >= expiresAt - marginMs
}

interface SimklAuthStorage {
    val state: StateFlow<SimklAuthState>

    fun currentScope(): SimklAuthScope
    fun isCurrent(scope: SimklAuthScope): Boolean
    fun authorization(): SimklAuthorization?
    fun accessToken(): String? = authorization()?.accessToken
    fun savePinSession(session: SimklPinSession, scope: SimklAuthScope = currentScope()): Boolean
    fun clearPinSession(
        error: SimklAuthError? = null,
        scope: SimklAuthScope = currentScope()
    ): Boolean
    fun completePinAuthorization(token: String, scope: SimklAuthScope): Boolean

    /** AUTH V2: refresh token for [scope], or null when there is none (AUTH V1 sign-in). */
    fun refreshToken(scope: SimklAuthScope = currentScope()): String? = null

    /** AUTH V2: stores the first token pair after the user approved the device code. */
    fun completeOAuth2Authorization(tokens: SimklTokenSet, scope: SimklAuthScope): Boolean =
        completePinAuthorization(tokens.accessToken, scope)

    /** AUTH V2: stores renewed tokens, only if [expectedAccessToken] is still the current one. */
    fun updateOAuth2Tokens(
        tokens: SimklTokenSet,
        scope: SimklAuthScope,
        expectedAccessToken: String
    ): Boolean = false
    fun saveIdentity(
        username: String?,
        accountId: Long?,
        settingsActivityWatermark: String? = null,
        scope: SimklAuthScope = currentScope()
    ): Boolean
    fun recordSettingsActivityWatermark(
        watermark: String,
        scope: SimklAuthScope = currentScope()
    ): Boolean
    fun clearAuth(
        error: SimklAuthError? = null,
        scope: SimklAuthScope = currentScope(),
        expectedAccessToken: String? = null
    ): Boolean
    fun removeProfile(profileId: Int)
    fun clearAllProfiles()
}
