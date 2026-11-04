package com.nuvio.tv.ui.screens.settings

import android.Manifest
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.core.backup.BackupFile
import com.nuvio.tv.core.backup.NuvioBackup
import com.nuvio.tv.core.backup.RestoreSummary
import com.nuvio.tv.ui.components.NuvioDialog
import com.nuvio.tv.ui.components.PanelActionRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

private sealed interface BackupUi {
    data object Idle : BackupUi
    data class Working(val label: String) : BackupUi
    data class Message(val title: String, val text: String) : BackupUi
    data class Pick(val files: List<BackupFile>) : BackupUi
    data class Confirm(val summary: RestoreSummary) : BackupUi
}

/**
 * Settings > Advanced > Backup & restore.
 * Backup writes one zip to Downloads/NuvioBackup. Restore stages the chosen zip, asks for
 * confirmation, then restarts the app, which swaps the data in before anything is loaded.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun BackupRestoreCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var ui by remember { mutableStateOf<BackupUi>(BackupUi.Idle) }
    var afterPermission by remember { mutableStateOf<(() -> Unit)?>(null) }

    fun hasStoragePermission(): Boolean =
        Build.VERSION.SDK_INT >= 29 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
            PackageManager.PERMISSION_GRANTED

    fun runBackup() {
        ui = BackupUi.Working("Creating backup\u2026")
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { NuvioBackup.saveToDownloads(context) } }
            ui = result.fold(
                onSuccess = { path ->
                    BackupUi.Message(
                        "Backup saved",
                        "Saved to $path on this device.\n\nThe backup includes the sign-in tokens of your connected services, so keep the file private."
                    )
                },
                onFailure = { BackupUi.Message("Backup failed", it.message ?: "Unknown error") }
            )
        }
    }

    fun openRestoreList() {
        ui = BackupUi.Working("Looking for backups\u2026")
        scope.launch {
            val files = withContext(Dispatchers.IO) {
                runCatching { NuvioBackup.listBackups(context) }.getOrDefault(emptyList())
            }
            ui = BackupUi.Pick(files)
        }
    }

    fun stageFrom(uri: Uri) {
        ui = BackupUi.Working("Reading backup\u2026")
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.use { NuvioBackup.stageRestore(context, it) }
                        ?: error("Could not open the file")
                }
            }
            ui = result.fold(
                onSuccess = { BackupUi.Confirm(it) },
                onFailure = { BackupUi.Message("Can't use this file", it.message ?: "Unknown error") }
            )
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val action = afterPermission
        afterPermission = null
        if (granted) {
            action?.invoke()
        } else {
            ui = BackupUi.Message(
                "Storage permission needed",
                "Without storage permission the Downloads/NuvioBackup folder can't be used."
            )
        }
        Unit
    }

    val documentPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            stageFrom(uri)
        } else {
            ui = BackupUi.Idle
        }
        Unit
    }

    fun withStoragePermission(action: () -> Unit) {
        if (hasStoragePermission()) {
            action()
        } else {
            afterPermission = action
            permissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }

    SettingsGroupCard(modifier = modifier.fillMaxWidth(), title = "Backup & restore") {
        SettingsActionRow(
            title = "Back up to this device",
            subtitle = "Saves settings, add-ons, plugins, collections and Continue Watching to Downloads/NuvioBackup",
            onClick = { withStoragePermission { runBackup() } }
        )
        SettingsActionRow(
            title = "Restore from a backup",
            subtitle = "Replaces everything with a backup you saved earlier. The app restarts to finish.",
            onClick = { withStoragePermission { openRestoreList() } }
        )
    }

    when (val state = ui) {
        BackupUi.Idle -> Unit

        is BackupUi.Working -> NuvioDialog(onDismiss = {}, title = state.label) {
            Text(text = "Please wait\u2026", style = MaterialTheme.typography.bodyMedium)
        }

        is BackupUi.Message -> NuvioDialog(onDismiss = { ui = BackupUi.Idle }, title = state.title) {
            Text(text = state.text, style = MaterialTheme.typography.bodyMedium)
            PanelActionRow(label = "Close", onClick = { ui = BackupUi.Idle })
        }

        is BackupUi.Pick -> NuvioDialog(
            onDismiss = { ui = BackupUi.Idle },
            title = "Choose a backup",
            subtitle = "Downloads/${NuvioBackup.BACKUP_FOLDER}"
        ) {
            if (state.files.isEmpty()) {
                Text(
                    text = "No backups found in Downloads/${NuvioBackup.BACKUP_FOLDER}.",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            state.files.take(6).forEach { file ->
                PanelActionRow(label = backupLabel(file), onClick = { stageFrom(file.uri) })
            }
            if (Build.VERSION.SDK_INT >= 29) {
                PanelActionRow(
                    label = "Browse for a file\u2026",
                    onClick = {
                        try {
                            documentPicker.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
                        } catch (_: ActivityNotFoundException) {
                            ui = BackupUi.Message(
                                "No file picker",
                                "This device has no file picker. Put the backup in Downloads/${NuvioBackup.BACKUP_FOLDER} instead."
                            )
                        }
                    }
                )
            }
            PanelActionRow(label = "Cancel", onClick = { ui = BackupUi.Idle })
        }

        is BackupUi.Confirm -> {
            val cancel = {
                NuvioBackup.discardPendingRestore(context)
                ui = BackupUi.Idle
            }
            NuvioDialog(onDismiss = cancel, title = "Restore this backup?") {
                Text(
                    text = "This replaces ALL current settings, add-ons, plugins, collections and Continue Watching with the backup. " +
                        "The app will close and reopen to finish; if it doesn't reopen by itself, open Nuvio again.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = "Backup contains ${state.summary.datastoreFiles} data files and ${state.summary.pluginFiles} plugin files.",
                    style = MaterialTheme.typography.bodySmall
                )
                PanelActionRow(label = "Restore and restart", onClick = { restartApp(context) })
                PanelActionRow(label = "Cancel", onClick = cancel)
            }
        }
    }
}

private fun backupLabel(file: BackupFile): String {
    val size = if (file.sizeBytes >= 1_048_576L) {
        "%.1f MB".format(file.sizeBytes / 1_048_576.0)
    } else {
        "${(file.sizeBytes / 1024L).coerceAtLeast(1L)} KB"
    }
    val date = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(file.modifiedMs))
    return "${file.name}  \u00B7  $size  \u00B7  $date"
}

/** Closes the app and asks the system to reopen it, so the staged restore is applied at startup. */
private fun restartApp(context: Context) {
    val pm = context.packageManager
    val launch: Intent? = pm.getLaunchIntentForPackage(context.packageName)
        ?: pm.getLeanbackLaunchIntentForPackage(context.packageName)
    if (launch != null) {
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        val pending = PendingIntent.getActivity(
            context, 7731, launch, PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        (context.getSystemService(Context.ALARM_SERVICE) as AlarmManager)
            .set(AlarmManager.RTC, System.currentTimeMillis() + 800L, pending)
    }
    Runtime.getRuntime().exit(0)
}
