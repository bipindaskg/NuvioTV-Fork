package com.nuvio.tv.data.simkl

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SimklAuthRepository(
    private val apiClient: SimklApiClient,
    private val configuration: SimklApiConfiguration,
    private val storage: SimklAuthStorage,
    private val nowEpochMs: () -> Long = System::currentTimeMillis
) {
    @Inject
    constructor(
        apiClient: SimklApiClient,
        configuration: SimklApiConfiguration,
        storage: SimklAuthStorage
    ) : this(apiClient, configuration, storage, System::currentTimeMillis)

    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true }

    val state: StateFlow<SimklAuthState> = storage.state

    fun hasRequiredCredentials(): Boolean = configuration.clientId.isNotBlank()

    suspend fun startPinAuth(): SimklPinSession = mutex.withLock {
        val authScope = storage.currentScope()
        if (!hasRequiredCredentials()) throw SimklAuthException(SimklAuthError.MISSING_CLIENT_ID)
        // New Simkl apps use AUTH V2 (device flow). Older AUTH V1 client IDs are refused there with
        // 401 and keep working through the legacy PIN flow.
        val session = startDeviceAuthV2() ?: startPinAuthV1()
        if (!storage.savePinSession(session, authScope)) {
            throw SimklAuthException(SimklAuthError.PIN_INVALIDATED)
        }
        session
    }

    private suspend fun startDeviceAuthV2(): SimklPinSession? {
        val response = try {
            apiClient.execute(
                SimklApiRequest(
                    method = SimklHttpMethod.POST,
                    path = "/oauth2/device",
                    body = simklFormBody(
                        "client_id" to configuration.clientId,
                        "scope" to "media:read media:write"
                    ),
                    requiresAuthentication = false,
                    contentType = SIMKL_FORM_CONTENT_TYPE
                )
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: SimklAuthException) {
            throw error
        } catch (error: SimklApiException) {
            if (error.status == 401 || error.errorCode == "invalid_client") return null
            throw SimklAuthException(SimklAuthError.NETWORK, error)
        } catch (error: Throwable) {
            throw SimklAuthException(SimklAuthError.NETWORK, error)
        }
        val payload = runCatching { json.decodeFromString<SimklDeviceCodeResponse>(response.body) }
            .getOrElse { throw SimklAuthException(SimklAuthError.INVALID_PIN_RESPONSE, it) }
        val deviceCode = payload.deviceCode?.trim()?.takeIf(String::isNotBlank)
        val userCode = payload.userCode?.trim()?.takeIf(String::isNotBlank)
        val verificationUri = payload.verificationUri?.trim()?.takeIf(String::isNotBlank)
        val expiresIn = payload.expiresIn?.takeIf { it > 0L }
        if (deviceCode == null || userCode == null || verificationUri == null || expiresIn == null) {
            throw SimklAuthException(SimklAuthError.INVALID_PIN_RESPONSE)
        }
        return SimklPinSession(
            userCode = userCode,
            verificationUri = verificationUri,
            expiresAtEpochMs = nowEpochMs() + expiresIn * 1_000L,
            intervalSeconds = (payload.interval ?: DEFAULT_POLL_INTERVAL_SECONDS).coerceAtLeast(1),
            deviceCode = deviceCode,
            verificationUriComplete = payload.verificationUriComplete?.trim()?.takeIf(String::isNotBlank)
        )
    }

    private suspend fun startPinAuthV1(): SimklPinSession {
        val response = executeAuthRequest(SimklApiRequest(SimklHttpMethod.GET, "/oauth/pin", requiresAuthentication = false))
        val payload = decodePinResponse(response.body)
        val userCode = payload.userCode?.trim()?.takeIf(String::isNotBlank)
        val verificationUri = (payload.verificationUri ?: payload.verificationUrl)
            ?.trim()
            ?.takeIf(String::isNotBlank)
        val expiresIn = payload.expiresIn?.takeIf { it > 0L }
        val interval = payload.interval?.coerceAtLeast(1)
        if (payload.result != "OK" || userCode == null || verificationUri == null || expiresIn == null || interval == null) {
            throw SimklAuthException(SimklAuthError.INVALID_PIN_RESPONSE)
        }
        return SimklPinSession(
            userCode = userCode,
            verificationUri = verificationUri,
            expiresAtEpochMs = nowEpochMs() + expiresIn * 1_000L,
            intervalSeconds = interval
        )
    }

    suspend fun pollPin(): SimklPinPollResult = mutex.withLock {
        val authScope = storage.currentScope()
        val session = storage.state.value.pinSession ?: return@withLock SimklPinPollResult.Invalidated
        if (nowEpochMs() >= session.expiresAtEpochMs) {
            storage.clearPinSession(SimklAuthError.PIN_EXPIRED, authScope)
            return@withLock SimklPinPollResult.Expired
        }
        if (!session.deviceCode.isNullOrBlank()) {
            return@withLock pollDeviceAuthV2(session, session.deviceCode, authScope)
        }
        if (!PIN_PATTERN.matches(session.userCode)) {
            storage.clearPinSession(SimklAuthError.PIN_INVALIDATED, authScope)
            return@withLock SimklPinPollResult.Invalidated
        }
        val response = executeAuthRequest(
            SimklApiRequest(
                method = SimklHttpMethod.GET,
                path = "/oauth/pin/${session.userCode}",
                requiresAuthentication = false
            )
        )
        val payload = decodePinResponse(response.body)
        if (!payload.deviceCode.isNullOrBlank()) {
            storage.clearPinSession(SimklAuthError.PIN_INVALIDATED, authScope)
            return@withLock SimklPinPollResult.Invalidated
        }
        val token = payload.accessToken?.trim()?.takeIf(String::isNotBlank)
        if (payload.result == "OK" && token != null) {
            if (!storage.completePinAuthorization(token, authScope)) {
                return@withLock SimklPinPollResult.Invalidated
            }
            try {
                refreshUserSettings(scope = authScope)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                Unit
            }
            return@withLock SimklPinPollResult.Authorized
        }
        if (payload.result == "KO") return@withLock SimklPinPollResult.Pending
        throw SimklAuthException(SimklAuthError.INVALID_PIN_RESPONSE)
    }

    private suspend fun pollDeviceAuthV2(
        session: SimklPinSession,
        deviceCode: String,
        authScope: SimklAuthScope
    ): SimklPinPollResult {
        val response = try {
            apiClient.execute(
                SimklApiRequest(
                    method = SimklHttpMethod.POST,
                    path = "/oauth2/token",
                    body = simklFormBody(
                        "grant_type" to "urn:ietf:params:oauth:grant-type:device_code",
                        "client_id" to configuration.clientId,
                        "device_code" to deviceCode
                    ),
                    requiresAuthentication = false,
                    retryPolicy = SimklRetryPolicy.NEVER,
                    contentType = SIMKL_FORM_CONTENT_TYPE
                )
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: SimklAuthException) {
            throw error
        } catch (error: SimklApiException) {
            return when (error.errorCode) {
                // Not approved yet (also what a declined request looks like): keep polling.
                "authorization_pending" -> SimklPinPollResult.Pending
                // Polled too fast: wait 5 seconds longer between polls from now on.
                "slow_down" -> {
                    storage.savePinSession(
                        session.copy(intervalSeconds = session.intervalSeconds + SLOW_DOWN_STEP_SECONDS),
                        authScope
                    )
                    SimklPinPollResult.Pending
                }
                "expired_token", "invalid_grant" -> {
                    storage.clearPinSession(SimklAuthError.PIN_EXPIRED, authScope)
                    SimklPinPollResult.Expired
                }
                "invalid_client" -> throw SimklAuthException(SimklAuthError.INVALID_CLIENT, error)
                else -> if (error.status == 401) {
                    throw SimklAuthException(SimklAuthError.INVALID_CLIENT, error)
                } else {
                    throw SimklAuthException(SimklAuthError.NETWORK, error)
                }
            }
        } catch (error: Throwable) {
            throw SimklAuthException(SimklAuthError.NETWORK, error)
        }
        val tokens = runCatching { json.decodeFromString<SimklOAuth2TokenResponse>(response.body) }
            .getOrNull()
            ?.toTokenSet(nowEpochMs())
            ?: throw SimklAuthException(SimklAuthError.INVALID_PIN_RESPONSE)
        if (!storage.completeOAuth2Authorization(tokens, authScope)) {
            return SimklPinPollResult.Invalidated
        }
        try {
            refreshUserSettings(scope = authScope)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            Unit
        }
        return SimklPinPollResult.Authorized
    }

    suspend fun refreshUserSettings(
        activityWatermark: String? = null,
        scope: SimklAuthScope = storage.currentScope()
    ): String? {
        if (!storage.isCurrent(scope) || !storage.state.value.isAuthenticated) return null
        val response = apiClient.execute(
            request = SimklApiRequest(SimklHttpMethod.POST, "/users/settings"),
            expectedAuthScope = scope
        )
        val settings = runCatching { json.decodeFromString<SimklUserSettingsResponse>(response.body) }.getOrNull()
            ?: return null
        val username = settings.user?.name?.trim()?.takeIf(String::isNotBlank)
        val saved = storage.saveIdentity(
            username = username,
            accountId = settings.account?.id,
            settingsActivityWatermark = activityWatermark,
            scope = scope
        )
        return username.takeIf { saved }
    }

    suspend fun synchronizeUserSettings(activityWatermark: String?) {
        val authScope = storage.currentScope()
        if (!storage.state.value.isAuthenticated) return
        try {
            when (simklSettingsRefreshAction(storage.state.value, activityWatermark)) {
                SimklSettingsRefreshAction.NONE -> Unit
                SimklSettingsRefreshAction.RECORD_WATERMARK -> {
                    storage.recordSettingsActivityWatermark(requireNotNull(activityWatermark), authScope)
                }
                SimklSettingsRefreshAction.FETCH -> refreshUserSettings(activityWatermark, authScope)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            Unit
        }
    }

    fun cancelPinAuth() = storage.clearPinSession()

    fun disconnect() = storage.clearAuth()

    private suspend fun executeAuthRequest(request: SimklApiRequest): SimklApiResponse = try {
        apiClient.execute(request)
    } catch (error: CancellationException) {
        throw error
    } catch (error: SimklAuthException) {
        throw error
    } catch (error: Throwable) {
        throw SimklAuthException(SimklAuthError.NETWORK, error)
    }

    private fun decodePinResponse(body: String): SimklPinResponse =
        runCatching { json.decodeFromString<SimklPinResponse>(body) }
            .getOrElse { throw SimklAuthException(SimklAuthError.INVALID_PIN_RESPONSE, it) }

    private companion object {
        val PIN_PATTERN = Regex("[A-Za-z0-9]{4,12}")
        const val DEFAULT_POLL_INTERVAL_SECONDS = 5
        const val SLOW_DOWN_STEP_SECONDS = 5
    }
}
