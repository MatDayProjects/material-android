package org.openvm.app.model

import android.content.Context
import kotlinx.serialization.json.Json
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID

class VmProfileStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }
    private val profilesFlow = MutableStateFlow(load())

    val profiles: StateFlow<List<VmProfile>> = profilesFlow

    fun upsert(profile: VmProfile) {
        val next = profilesFlow.value.toMutableList().apply {
            val index = indexOfFirst { it.id == profile.id }
            if (index >= 0) set(index, profile) else add(profile)
        }.sortedBy { it.createdAt }
        persist(next)
    }

    fun create(
        name: String,
        androidVersion: String,
        memoryMb: Int,
        storageGb: Int,
        vcpus: Int,
        imageUri: String?,
    ): VmProfile = VmProfile(
        id = UUID.randomUUID().toString(),
        name = name.trim(),
        androidVersion = androidVersion.trim(),
        memoryMb = memoryMb,
        storageGb = storageGb,
        vcpus = vcpus,
        imageUri = imageUri,
    ).also(::upsert)

    fun delete(id: String) {
        persist(profilesFlow.value.filterNot { it.id == id })
    }

    fun updateStatus(id: String, status: VmStatus) {
        profilesFlow.value.firstOrNull { it.id == id }?.let { upsert(it.copy(status = status, updatedAt = System.currentTimeMillis())) }
    }

    fun exportJson(): String = json.encodeToString(VmProfileDocument.serializer(), VmProfileDocument(profiles = profilesFlow.value))

    fun importJson(payload: String): Int {
        val document = json.decodeFromString(VmProfileDocument.serializer(), payload)
        require(document.schemaVersion == 1) { "Unsupported configuration schema ${document.schemaVersion}" }
        // Lifecycle is runtime-owned state, not portable profile metadata. Never
        // resurrect a guest as RUNNING just because an old export said so.
        val valid = document.profiles
            .map(VmProfile::restoredForHost)
            .filter { it.validationErrors().isEmpty() }
        require(valid.size == document.profiles.size) { "The configuration contains an invalid VM profile" }
        persist((profilesFlow.value + valid).distinctBy { it.id }.sortedBy { it.createdAt })
        return valid.size
    }

    private fun load(): List<VmProfile> {
        val payload = preferences.getString(PROFILES_KEY, null) ?: return emptyList()
        return runCatching {
            json.decodeFromString(VmProfileDocument.serializer(), payload).profiles.map(VmProfile::restoredForHost)
        }.getOrDefault(emptyList())
    }

    private fun persist(profiles: List<VmProfile>) {
        profilesFlow.value = profiles
        preferences.edit()
            .putString(PROFILES_KEY, json.encodeToString(VmProfileDocument.serializer(), VmProfileDocument(profiles = profiles)))
            .apply()
    }

    companion object {
        private const val PREFERENCES_NAME = "openvm_profiles"
        private const val PROFILES_KEY = "profiles_json"
    }
}
