@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.core.network.CertificateProblem
import com.nuvio.tv.core.network.ServerCertificateInfo
import com.nuvio.tv.core.network.displayFingerprint
import com.nuvio.tv.data.mediaserver.DiscoveredServer
import com.nuvio.tv.data.mediaserver.ServerConnection
import com.nuvio.tv.data.mediaserver.ServerDiscovery
import com.nuvio.tv.data.mediaserver.ServerFailure
import com.nuvio.tv.data.mediaserver.ServerProvider
import com.nuvio.tv.data.mediaserver.ServerSignInIssue
import com.nuvio.tv.data.mediaserver.silo.SiloProvider
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.screens.account.InputField
import com.nuvio.tv.ui.theme.NuvioTheme
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal data class ServerSignInRequest(
    val provider: ServerProvider,
    val address: String = "",
    val username: String = ""
)

@Composable
internal fun ServerSignInDialog(
    request: ServerSignInRequest,
    viewModel: MediaServersViewModel,
    onConnected: (ServerConnection) -> Unit,
    onDismiss: () -> Unit
) {
    val state by viewModel.signIn.collectAsStateWithLifecycle()
    val quickConnect by viewModel.quickConnect.collectAsStateWithLifecycle()
    val saved = remember(request.provider.id) { viewModel.savedLogin(request.provider.id) }
    var address by remember { mutableStateOf(request.address) }
    var username by remember { mutableStateOf(request.username.ifBlank { saved?.username.orEmpty() }) }
    var password by remember { mutableStateOf(saved?.takeIf { it.username == username }?.password.orEmpty()) }
    var rememberLogin by remember { mutableStateOf(true) }
    val provider = request.provider
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var searching by remember { mutableStateOf(false) }
    var foundServers by remember { mutableStateOf<List<DiscoveredServer>?>(null) }
    val findOnNetwork = {
        if (!searching) {
            searching = true
            foundServers = null
            scope.launch {
                val servers = ServerDiscovery.find(context, provider.id)
                foundServers = servers
                searching = false
            }
        }
    }
    val canConnect = !state.connecting && address.isNotBlank() && username.isNotBlank()
    val connectTrusting = { trusted: ServerCertificateInfo? ->
        if (canConnect) {
            viewModel.connect(provider, address, username, password, trusted) { connection ->
                if (rememberLogin) {
                    viewModel.rememberLogin(provider.id, username.trim(), password)
                } else {
                    viewModel.forgetLogin(provider.id)
                }
                password = ""
                onConnected(connection)
            }
        }
    }
    val connect = { connectTrusting(null) }

    val codeShown = quickConnect.phase != QuickConnectPhase.IDLE && quickConnect.phase != QuickConnectPhase.DISABLED
    val startQuickConnectTrusting = { trusted: ServerCertificateInfo? ->
        if (address.isNotBlank()) viewModel.startQuickConnect(provider, address, trusted, onConnected)
    }
    val startQuickConnect = { startQuickConnectTrusting(null) }
    val dismiss = {
        viewModel.cancelQuickConnect()
        onDismiss()
    }

    LaunchedEffect(Unit) {
        viewModel.resetSignIn()
        viewModel.cancelQuickConnect()
    }

    NuvioDialog(
        onDismiss = { if (!state.connecting) dismiss() },
        title = stringResource(R.string.servers_sign_in_title, provider.displayName),
        subtitle = stringResource(R.string.servers_sign_in_subtitle, provider.displayName),
        width = 640.dp,
        backgroundContent = {
            val solid = NuvioTheme.colors.BackgroundElevated.copy(alpha = 0.94f)
            Box(Modifier.matchParentSize().background(solid))
        }
    ) {
        SignInField(label = stringResource(R.string.servers_address)) {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                InputField(
                    value = address,
                    onValueChange = { address = it },
                    placeholder = stringResource(R.string.servers_address_hint),
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Next,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
        if (ServerDiscovery.isSupported(provider.id)) {
            SettingsDialogActionRow(horizontalAlignment = Alignment.Start) {
                SettingsDialogActionButton(
                    text = if (searching) "Searching your network\u2026" else "Find server on network",
                    onClick = findOnNetwork,
                    enabled = !state.connecting && !searching
                )
            }
            foundServers?.let { servers ->
                if (servers.isEmpty()) {
                    Text(
                        text = "No server found. Make sure it is switched on and on the same network, or type the address.",
                        style = MaterialTheme.typography.bodySmall,
                        color = NuvioTheme.colors.TextSecondary
                    )
                } else {
                    Text(
                        text = "Choose your server to use its address:",
                        style = MaterialTheme.typography.bodySmall,
                        color = NuvioTheme.colors.TextSecondary
                    )
                    servers.forEach { server ->
                        SettingsDialogActionButton(
                            text = server.name + "  \u00B7  " + server.address,
                            onClick = {
                                address = server.address
                                foundServers = null
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }
        if (provider.id == SiloProvider.ID) {
            Text(
                text = stringResource(R.string.servers_silo_hint),
                style = MaterialTheme.typography.bodySmall,
                color = NuvioTheme.colors.TextSecondary
            )
        }
        state.certificate?.let { certificate ->
            CertificatePanel(
                certificate = certificate,
                onTrust = {
                    if (state.certificateForCode) startQuickConnectTrusting(certificate) else connectTrusting(certificate)
                },
                onCancel = viewModel::dismissCertificate
            )
            return@NuvioDialog
        }
        if (codeShown) {
            QuickConnectPanel(
                state = quickConnect,
                onNewCode = startQuickConnect,
                onUsePassword = viewModel::cancelQuickConnect,
                onCancel = dismiss
            )
            return@NuvioDialog
        }
        SignInField(label = stringResource(R.string.servers_username)) {
            InputField(
                value = username,
                onValueChange = { username = it },
                placeholder = stringResource(R.string.servers_username),
                imeAction = ImeAction.Next,
                modifier = Modifier.fillMaxWidth()
            )
        }
        SignInField(label = stringResource(R.string.servers_password)) {
            InputField(
                value = password,
                onValueChange = { password = it },
                placeholder = stringResource(R.string.servers_password),
                keyboardType = KeyboardType.Password,
                isPassword = true,
                imeAction = ImeAction.Done,
                onImeAction = connect,
                modifier = Modifier.fillMaxWidth()
            )
        }
        SettingsDialogActionRow(horizontalAlignment = Alignment.Start) {
            SettingsDialogActionButton(
                text = if (rememberLogin) "Remember login on this device: On" else "Remember login on this device: Off",
                onClick = { rememberLogin = !rememberLogin }
            )
        }
        state.error?.let { failure ->
            Text(
                text = state.issue?.message() ?: failure.signInMessage(provider),
                style = MaterialTheme.typography.bodySmall,
                color = NuvioTheme.colors.Error
            )
        }
        if (quickConnect.phase == QuickConnectPhase.DISABLED) {
            Text(
                text = stringResource(R.string.servers_quick_connect_disabled),
                style = MaterialTheme.typography.bodySmall,
                color = NuvioTheme.colors.TextSecondary
            )
        }
        SettingsDialogActionRow {
            SettingsDialogActionButton(
                text = stringResource(R.string.action_cancel),
                onClick = dismiss,
                enabled = !state.connecting
            )
            if (provider.supportsQuickConnect) {
                SettingsDialogActionButton(
                    text = stringResource(R.string.servers_quick_connect),
                    onClick = startQuickConnect,
                    enabled = !state.connecting && address.isNotBlank()
                )
            }
            SettingsDialogActionButton(
                text = stringResource(if (state.connecting) R.string.servers_connecting else R.string.servers_connect),
                onClick = connect,
                primary = true,
                enabled = canConnect
            )
        }
    }
}

@Composable
private fun QuickConnectPanel(
    state: QuickConnectState,
    onNewCode: () -> Unit,
    onUsePassword: () -> Unit,
    onCancel: () -> Unit
) {
    val code = state.code
    when {
        state.phase == QuickConnectPhase.STARTING -> QuickConnectLine(stringResource(R.string.servers_quick_connect_starting))
        state.phase == QuickConnectPhase.EXPIRED -> QuickConnectLine(stringResource(R.string.servers_quick_connect_expired))
        state.phase == QuickConnectPhase.FAILED -> QuickConnectLine(
            (state.error ?: ServerFailure.FAILED).quickConnectMessage(),
            color = NuvioTheme.colors.Error
        )
        code != null -> {
            Text(
                text = code.chunked(3).joinToString(" "),
                style = MaterialTheme.typography.displaySmall,
                color = NuvioTheme.colors.TextPrimary,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center
            )
            QuickConnectLine(stringResource(R.string.servers_quick_connect_instructions, state.serverName.orEmpty()))
            if (state.phase == QuickConnectPhase.SIGNING_IN) {
                QuickConnectLine(stringResource(R.string.servers_quick_connect_signing_in))
            } else {
                QuickConnectCountdown(state.expiresAtMs)
            }
        }
    }
    SettingsDialogActionRow {
        SettingsDialogActionButton(text = stringResource(R.string.action_cancel), onClick = onCancel)
        SettingsDialogActionButton(
            text = stringResource(R.string.servers_quick_connect_use_password),
            onClick = onUsePassword,
            enabled = state.phase != QuickConnectPhase.SIGNING_IN
        )
        if (state.phase == QuickConnectPhase.EXPIRED || state.phase == QuickConnectPhase.FAILED) {
            SettingsDialogActionButton(
                text = stringResource(R.string.servers_quick_connect_new_code),
                onClick = onNewCode,
                primary = true
            )
        }
    }
}

@Composable
private fun CertificatePanel(certificate: ServerCertificateInfo, onTrust: () -> Unit, onCancel: () -> Unit) {
    val dates = remember { DateFormat.getDateInstance(DateFormat.MEDIUM) }
    Text(
        text = stringResource(R.string.servers_certificate_title),
        style = MaterialTheme.typography.titleMedium,
        color = NuvioTheme.colors.TextPrimary
    )
    CertificateLine(stringResource(R.string.servers_certificate_intro, certificate.host))
    CertificateLine(
        stringResource(
            when (certificate.problem) {
                CertificateProblem.UNTRUSTED -> R.string.servers_certificate_untrusted
                CertificateProblem.NAME_MISMATCH -> R.string.servers_certificate_name_mismatch
                CertificateProblem.EXPIRED -> R.string.servers_certificate_expired
            }
        )
    )
    CertificateLine(stringResource(R.string.servers_certificate_advice))
    CertificateLine(
        stringResource(
            R.string.servers_certificate_details,
            certificate.subject,
            certificate.issuer,
            dates.format(Date(certificate.validFromMs)),
            dates.format(Date(certificate.validUntilMs)),
            displayFingerprint(certificate.fingerprint)
        ),
        color = NuvioTheme.colors.TextPrimary
    )
    SettingsDialogActionRow {
        SettingsDialogActionButton(text = stringResource(R.string.action_cancel), onClick = onCancel)
        SettingsDialogActionButton(
            text = stringResource(R.string.servers_certificate_trust),
            onClick = onTrust
        )
    }
}

@Composable
private fun CertificateLine(text: String, color: Color = NuvioTheme.colors.TextSecondary) {
    Text(text = text, style = MaterialTheme.typography.bodySmall, color = color)
}

@Composable
private fun QuickConnectCountdown(expiresAtMs: Long) {
    var remainingMs by remember(expiresAtMs) { mutableLongStateOf(expiresAtMs - System.currentTimeMillis()) }
    LaunchedEffect(expiresAtMs) {
        while (remainingMs > 0L) {
            delay(1_000L)
            remainingMs = expiresAtMs - System.currentTimeMillis()
        }
    }
    val seconds = (remainingMs.coerceAtLeast(0L) / 1_000L).toInt()
    QuickConnectLine(
        stringResource(R.string.servers_quick_connect_valid_for, "%d:%02d".format(seconds / 60, seconds % 60))
    )
}

@Composable
private fun QuickConnectLine(text: String, color: Color = NuvioTheme.colors.TextSecondary) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = color,
        modifier = Modifier.fillMaxWidth(),
        textAlign = TextAlign.Center
    )
}

@Composable
private fun ServerFailure.quickConnectMessage(): String = when (this) {
    ServerFailure.NOT_FOUND -> stringResource(R.string.servers_error_address)
    ServerFailure.UNREACHABLE -> stringResource(R.string.servers_error_unreachable)
    ServerFailure.FORBIDDEN -> stringResource(R.string.servers_error_forbidden)
    ServerFailure.CERTIFICATE -> stringResource(R.string.servers_certificate_unavailable)
    else -> stringResource(R.string.servers_error_failed)
}

@Composable
private fun SignInField(label: String, field: @Composable () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xs)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = NuvioTheme.colors.TextSecondary
        )
        field()
    }
}

@Composable
private fun ServerSignInIssue.message(): String = stringResource(
    when (this) {
        ServerSignInIssue.WEBSITE -> R.string.servers_error_website
        ServerSignInIssue.SILO_WEBSITE -> R.string.servers_error_silo_website
        ServerSignInIssue.SILO_JELLYFIN_OFF -> R.string.servers_error_silo_jellyfin_off
        ServerSignInIssue.PROFILE_NEEDED -> R.string.servers_error_profile_needed
        ServerSignInIssue.PROFILE_NOT_FOUND -> R.string.servers_error_profile_not_found
        ServerSignInIssue.PROFILE_PIN -> R.string.servers_error_profile_pin
        ServerSignInIssue.PROFILE_AND_PIN -> R.string.servers_error_profile_and_pin
        ServerSignInIssue.PIN_WRONG -> R.string.servers_error_pin_wrong
        ServerSignInIssue.TEMPORARY_PASSWORD -> R.string.servers_error_temporary_password
        ServerSignInIssue.PASSWORD_SIGN_IN_OFF -> R.string.servers_error_password_sign_in_off
        ServerSignInIssue.TOO_MANY_TRIES -> R.string.servers_error_too_many_tries
        ServerSignInIssue.SILO_NOT_NATIVE -> R.string.servers_error_silo_not_native
        ServerSignInIssue.SERVER_MESSAGE -> R.string.servers_error_failed
    }
)

@Composable
private fun ServerFailure.signInMessage(provider: ServerProvider): String = when (this) {
    ServerFailure.AUTH_REQUIRED -> stringResource(R.string.servers_error_auth)
    ServerFailure.NOT_FOUND -> stringResource(R.string.servers_error_address)
    ServerFailure.UNREACHABLE -> stringResource(R.string.servers_error_unreachable)
    ServerFailure.UNSUPPORTED -> if (provider.id == SiloProvider.ID) {
        stringResource(R.string.servers_error_unsupported_server)
    } else {
        stringResource(R.string.servers_error_unsupported, provider.displayName, provider.minimumVersion)
    }
    ServerFailure.FORBIDDEN -> stringResource(R.string.servers_error_forbidden)
    ServerFailure.CERTIFICATE -> stringResource(R.string.servers_certificate_unavailable)
    ServerFailure.STREAM_LIMIT,
    ServerFailure.INCOMPLETE,
    ServerFailure.FAILED -> stringResource(R.string.servers_error_failed)
}
