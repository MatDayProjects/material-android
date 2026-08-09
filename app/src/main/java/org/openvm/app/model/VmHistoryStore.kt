package org.openvm.app.model

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class VmHistoryEntry(
    val id: String,
    val action: String,
    val profileName: String? = null,
    val timestamp: Long = System.currentTimeMillis(),
)

@Serializable
data class VmHistoryDocument(
    val schemaVersion: Int = 1,
    val entries: List<VmHistoryEntry> = emptyList(),
)

class VmHistoryStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    fun entries(): List<VmHistoryEntry> = runCatching {
        val value = preferences.getString(HISTORY_KEY, null) ?: return emptyList()
        json.decodeFromString(VmHistoryDocument.serializer(), value).entries
    }.getOrDefault(emptyList())

    fun record(action: String, profileName: String? = null) {
        val next = (entries() + VmHistoryEntry(java.util.UUID.randomUUID().toString(), action, profileName)).takeLast(MAX_ENTRIES)
        preferences.edit()
            .putString(HISTORY_KEY, json.encodeToString(VmHistoryDocument.serializer(), VmHistoryDocument(entries = next)))
            .apply()
    }

    companion object {
        private const val PREFERENCES_NAME = "openvm_history"
        private const val HISTORY_KEY = "entries_json"
        private const val MAX_ENTRIES = 500
    }
}

