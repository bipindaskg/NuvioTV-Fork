package com.nuvio.tv.data.mediaserver

import android.content.Context
import com.nuvio.tv.core.profile.ProtectedProfilePreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import org.json.JSONObject

/**
 * Remembers the last username and password used for each media server type (Emby, Jellyfin) so the
 * sign-in dialog can fill them in next time. Everything is encrypted with a key kept in the Android
 * Keystore and never leaves this device, which also means it is not part of a settings backup.
 */
@Singleton
class SavedLoginStore @Inject constructor(
    @ApplicationContext context: Context
) {
    data class Login(val username: String, val password: String)

    private val preferences = ProtectedProfilePreferences(
        context,
        "media_server_logins",
        "com.nuvio.tv.mediaserverlogins.v1"
    )

    private fun readAll(): JSONObject = try {
        preferences.read(SLOT)?.let(::JSONObject) ?: JSONObject()
    } catch (_: Exception) {
        JSONObject()
    }

    fun get(providerId: String): Login? {
        val entry = readAll().optJSONObject(providerId) ?: return null
        val username = entry.optString("u")
        if (username.isBlank()) return null
        return Login(username = username, password = entry.optString("p"))
    }

    fun save(providerId: String, username: String, password: String) {
        runCatching {
            val all = readAll()
            all.put(providerId, JSONObject().put("u", username).put("p", password))
            preferences.write(SLOT, all.toString())
        }
    }

    fun forget(providerId: String) {
        runCatching {
            val all = readAll()
            if (!all.has(providerId)) return
            all.remove(providerId)
            preferences.write(SLOT, if (all.length() == 0) null else all.toString())
        }
    }

    private companion object {
        const val SLOT = 0
    }
}
