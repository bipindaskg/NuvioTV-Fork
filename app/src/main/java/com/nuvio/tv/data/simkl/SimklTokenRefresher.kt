package com.nuvio.tv.data.simkl

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json

/**
 * Renews Simkl AUTH V2 access tokens with the stored refresh token.
 *
 * It talks to the HTTP engine directly instead of going through [SimklApiClient], because it is
 * called from inside the client while the client's request lock is held. All callers are therefore
 * already serialised, which matters: a grant supports exactly one live access token, so two
 * concurrent refreshes would invalidate each other.
 */
class SimklTokenRefresher(
    private val engine: SimklHttpEngine,
    private val configuration: SimklApiConfiguration,
    private val storage: SimklAuthStorage,
    private val nowEpochMs: () -> Long = System::currentTimeMillis
) {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Returns the renewed authorization, or null when it could not be renewed. When Simkl says the
     * grant is gone (revoked or expired), the sign-in is cleared so the user is asked to reconnect.
     */
    suspend fun refresh(current: SimklAuthorization): SimklAuthorization? {
        // Someone may already have renewed the token while this call was waiting.
        storage.authorization()?.let { latest ->
            if (latest.scope == current.scope && latest.accessToken != current.accessToken) return latest
        }
        val refreshToken = storage.refreshToken(current.scope)?.takeIf(String::isNotBlank) ?: return null

        val response = try {
            engine.execute(
                method = SimklHttpMethod.POST.name,
                url = buildSimklApiUrl(configuration, "/oauth2/token"),
                headers = simklRequestHeaders(
                    configuration = configuration,
                    accessToken = null,
                    contentTypeJson = true,
                    contentType = SIMKL_FORM_CONTENT_TYPE
                ),
                body = simklFormBody(
                    "grant_type" to "refresh_token",
                    "client_id" to configuration.clientId,
                    "refresh_token" to refreshToken
                )
            )
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            return null // Offline or timed out: keep the current sign-in and try again later.
        }

        return when {
            response.status in 200..299 -> {
                val tokens = runCatching { json.decodeFromString<SimklOAuth2TokenResponse>(response.body) }
                    .getOrNull()
                    ?.toTokenSet(nowEpochMs())
                    ?: return null
                if (!storage.updateOAuth2Tokens(tokens, current.scope, current.accessToken)) {
                    // The sign-in changed underneath us; use whatever is current now.
                    return storage.authorization()?.takeIf { it.scope == current.scope }
                }
                storage.authorization()?.takeIf { it.scope == current.scope }
            }

            response.status == 401 || (response.status == 400 && response.errorCode() == "invalid_grant") -> {
                storage.clearAuth(
                    error = SimklAuthError.AUTHORIZATION_REVOKED,
                    scope = current.scope,
                    expectedAccessToken = current.accessToken
                )
                null
            }

            else -> null // 429 / 5xx and anything unexpected: transient, try again on the next request.
        }
    }

    private fun SimklRawHttpResponse.errorCode(): String? =
        runCatching { json.decodeFromString<SimklOAuth2ErrorBody>(body).error }.getOrNull()
}
