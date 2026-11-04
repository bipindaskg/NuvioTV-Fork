package com.nuvio.tv.core.backup

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import com.nuvio.tv.BuildConfig
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

private const val TAG = "NuvioBackup"

/** A backup file found in shared storage. */
data class BackupFile(
    val name: String,
    val uri: Uri,
    val sizeBytes: Long,
    val modifiedMs: Long
)

/** What a staged restore contains, for the confirmation message. */
data class RestoreSummary(val datastoreFiles: Int, val pluginFiles: Int, val otherFiles: Int)

/**
 * Backup and restore of everything the app keeps in its private files:
 *  - the datastore folder (all .preferences_pb files): settings, add-ons, collections, watch progress (Continue Watching), library...
 *  - `plugin_code*`               : plugin scripts
 *  - `cw_enrichment/`             : Continue Watching snapshots
 *
 * A backup is one zip. Restoring does NOT touch live data: the zip is unpacked into
 * `filesDir/pending_restore/` and swapped in by [applyPendingRestoreIfAny] on the next app start,
 * before any data store has been opened.
 */
object NuvioBackup {
    const val BACKUP_FOLDER = "NuvioBackup"
    private const val MANIFEST_ENTRY = "manifest.json"
    private const val FILES_PREFIX = "files/"
    private const val PENDING_DIR = "pending_restore"
    private const val FORMAT_VERSION = 1

    private fun isBackedUpTopLevel(name: String): Boolean =
        name == "datastore" || name == "cw_enrichment" || name == "plugin_code" || name.startsWith("plugin_code_p")

    private fun isSkippableFile(file: File): Boolean {
        val n = file.name
        return n.endsWith(".tmp") || n.endsWith(".bak") || n.endsWith(".part") || n.endsWith(".lock")
    }

    fun newBackupFileName(): String =
        "NuvioArc-backup-" + SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.US).format(Date()) + ".zip"

    // ---------------------------------------------------------------- create

    /** Writes a backup zip of the current data to [out]. Does not close [out]. */
    fun writeBackup(context: Context, out: OutputStream) {
        val zip = ZipOutputStream(out.buffered())
        val manifest = buildString {
            append("{\"format\":").append(FORMAT_VERSION)
            append(",\"createdAt\":").append(System.currentTimeMillis())
            append(",\"appVersion\":\"").append(BuildConfig.VERSION_NAME).append("\"")
            append(",\"package\":\"").append(context.packageName).append("\"}")
        }
        zip.putNextEntry(ZipEntry(MANIFEST_ENTRY))
        zip.write(manifest.toByteArray(Charsets.UTF_8))
        zip.closeEntry()

        val root = context.filesDir
        root.listFiles()?.filter { isBackedUpTopLevel(it.name) }?.forEach { top ->
            addToZip(zip, top, FILES_PREFIX + top.name, onlyDataStoreFiles = top.name == "datastore")
        }
        zip.finish()
        zip.flush()
    }

    private fun addToZip(zip: ZipOutputStream, file: File, entryName: String, onlyDataStoreFiles: Boolean) {
        if (file.isDirectory) {
            file.listFiles()?.forEach { child ->
                addToZip(zip, child, "$entryName/${child.name}", onlyDataStoreFiles)
            }
            return
        }
        if (isSkippableFile(file)) return
        if (onlyDataStoreFiles && !file.name.endsWith(".preferences_pb")) return
        runCatching {
            zip.putNextEntry(ZipEntry(entryName))
            file.inputStream().use { it.copyTo(zip) }
            zip.closeEntry()
        }.onFailure { Log.w(TAG, "Skipping unreadable file $entryName", it) }
    }

    // ----------------------------------------------------- shared storage I/O

    /** Saves a new backup into Downloads/NuvioBackup and returns a readable location. */
    fun saveToDownloads(context: Context): String {
        val name = newBackupFileName()
        return if (Build.VERSION.SDK_INT >= 29) {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "application/zip")
                put(MediaStore.Downloads.RELATIVE_PATH, "Download/$BACKUP_FOLDER")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: error("Could not create a file in Downloads")
            try {
                resolver.openOutputStream(uri)?.use { writeBackup(context, it) }
                    ?: error("Could not open the backup file for writing")
                val done = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
                resolver.update(uri, done, null, null)
            } catch (t: Throwable) {
                runCatching { resolver.delete(uri, null, null) }
                throw t
            }
            "Download/$BACKUP_FOLDER/$name"
        } else {
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), BACKUP_FOLDER)
            if (!dir.exists() && !dir.mkdirs()) error("Could not create ${dir.absolutePath}")
            val file = File(dir, name)
            file.outputStream().use { writeBackup(context, it) }
            "Download/$BACKUP_FOLDER/$name"
        }
    }

    /** Backups this app can see in Downloads/NuvioBackup, newest first. */
    fun listBackups(context: Context): List<BackupFile> {
        val found = mutableListOf<BackupFile>()
        if (Build.VERSION.SDK_INT >= 29) {
            val projection = arrayOf(
                MediaStore.Downloads._ID,
                MediaStore.Downloads.DISPLAY_NAME,
                MediaStore.Downloads.SIZE,
                MediaStore.Downloads.DATE_MODIFIED
            )
            val selection = "${MediaStore.Downloads.RELATIVE_PATH} LIKE ?"
            val args = arrayOf("%$BACKUP_FOLDER%")
            runCatching {
                context.contentResolver.query(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI, projection, selection, args, null
                )?.use { c ->
                    while (c.moveToNext()) {
                        val name = c.getString(1) ?: continue
                        if (!name.endsWith(".zip", ignoreCase = true)) continue
                        val uri = Uri.withAppendedPath(MediaStore.Downloads.EXTERNAL_CONTENT_URI, c.getLong(0).toString())
                        found += BackupFile(name, uri, c.getLong(2), c.getLong(3) * 1000L)
                    }
                }
            }.onFailure { Log.w(TAG, "MediaStore query failed", it) }
        } else {
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), BACKUP_FOLDER)
            dir.listFiles()?.filter { it.isFile && it.name.endsWith(".zip", ignoreCase = true) }?.forEach { f ->
                found += BackupFile(f.name, Uri.fromFile(f), f.length(), f.lastModified())
            }
        }
        return found.sortedByDescending { it.modifiedMs }
    }

    // ---------------------------------------------------------------- restore

    /**
     * Validates the zip and unpacks it into the pending-restore folder. Live data is untouched;
     * call [applyPendingRestoreIfAny] on the next start (done by the Application).
     */
    fun stageRestore(context: Context, input: InputStream): RestoreSummary {
        val pending = File(context.filesDir, PENDING_DIR)
        pending.deleteRecursively()
        pending.mkdirs()
        var sawManifest = false
        var datastore = 0
        var plugin = 0
        var other = 0
        try {
            ZipInputStream(input.buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val name = entry.name
                    if (entry.isDirectory) continue
                    if (name == MANIFEST_ENTRY) {
                        val text = zip.readBytes().toString(Charsets.UTF_8)
                        val format = Regex("\"format\"\\s*:\\s*(\\d+)").find(text)?.groupValues?.get(1)?.toIntOrNull()
                        if (format == null || format > FORMAT_VERSION) error("This backup was made by a newer version")
                        sawManifest = true
                        continue
                    }
                    if (!name.startsWith(FILES_PREFIX)) continue
                    val rel = name.removePrefix(FILES_PREFIX)
                    val parts = rel.split('/')
                    if (parts.any { it.isEmpty() || it == "." || it == ".." } || !isBackedUpTopLevel(parts[0])) continue
                    val target = File(pending, rel)
                    // Zip-slip guard.
                    if (!target.canonicalPath.startsWith(pending.canonicalPath + File.separator)) continue
                    target.parentFile?.mkdirs()
                    target.outputStream().use { zip.copyTo(it) }
                    when {
                        parts[0] == "datastore" -> datastore++
                        parts[0].startsWith("plugin_code") -> plugin++
                        else -> other++
                    }
                }
            }
            if (!sawManifest || datastore == 0) error("This file is not a Nuvio backup")
        } catch (t: Throwable) {
            pending.deleteRecursively()
            throw t
        }
        return RestoreSummary(datastore, plugin, other)
    }

    /** Drops a staged restore without applying it. */
    fun discardPendingRestore(context: Context) {
        runCatching { File(context.filesDir, PENDING_DIR).deleteRecursively() }
    }

    /** True when a restore is staged and will be applied on the next start. */
    fun hasPendingRestore(context: Context): Boolean = File(context.filesDir, PENDING_DIR).isDirectory

    /**
     * Swaps a staged restore into place. MUST run before any data store is opened, i.e. from
     * Application.attachBaseContext. Never throws.
     */
    fun applyPendingRestoreIfAny(context: Context) {
        try {
            val root = context.filesDir
            val pending = File(root, PENDING_DIR)
            if (!pending.isDirectory) return
            val staged = pending.listFiles()?.filter { isBackedUpTopLevel(it.name) }.orEmpty()
            if (staged.none { it.name == "datastore" }) {
                pending.deleteRecursively()
                return
            }
            // Replace each backed-up area wholesale so no stale or shadow files survive.
            root.listFiles()?.filter { isBackedUpTopLevel(it.name) }?.forEach { it.deleteRecursively() }
            staged.forEach { src ->
                val dst = File(root, src.name)
                if (!src.renameTo(dst)) {
                    src.copyRecursively(dst, overwrite = true)
                    src.deleteRecursively()
                }
            }
            pending.deleteRecursively()
            Log.i(TAG, "Restore applied")
        } catch (t: Throwable) {
            Log.e(TAG, "Applying restore failed", t)
            runCatching { File(context.filesDir, PENDING_DIR).deleteRecursively() }
        }
    }
}
