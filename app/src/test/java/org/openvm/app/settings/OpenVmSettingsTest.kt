package org.openvm.app.settings

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class OpenVmSettingsTest {
    @Test
    fun settingsRoundTripPreservesLanguageAndFunnyLevels() {
        val settings = OpenVmSettings(
            languageMode = LanguageMode.BILINGUAL,
            englishFunnyLevel = 5,
            cantoneseFunnyLevel = 4,
            displayName = "My OpenVM",
        )
        val json = Json { encodeDefaults = true }

        val decoded = json.decodeFromString(OpenVmSettings.serializer(), json.encodeToString(OpenVmSettings.serializer(), settings))

        assertEquals(settings, decoded)
    }
}

