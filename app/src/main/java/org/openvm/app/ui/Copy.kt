package org.openvm.app.ui

import org.openvm.app.settings.LanguageMode
import org.openvm.app.settings.OpenVmSettings

data class LocalizedCopy(val english: String, val cantonese: String) {
    fun render(settings: OpenVmSettings): String {
        val english = when {
            settings.englishFunnyLevel >= 5 -> "$english — the tiny computer is wearing its loudest socks."
            settings.englishFunnyLevel >= 4 -> "$english — the tiny computer is ready for a snack break."
            else -> english
        }
        val cantonese = when {
            settings.cantoneseFunnyLevel >= 5 -> "$cantonese——部小電腦著晒最搶眼嘅襪。"
            settings.cantoneseFunnyLevel >= 4 -> "$cantonese——部小電腦準備好食茶點喇。"
            else -> cantonese
        }
        return when (settings.languageMode) {
            LanguageMode.ENGLISH -> english
            LanguageMode.CANTONESE -> cantonese
            LanguageMode.BILINGUAL -> "$english\n$cantonese"
        }
    }
}

object Copy {
    val emptyProfiles = LocalizedCopy("No virtual machines yet", "仲未有虛擬機")
    val runtimeNotReady = LocalizedCopy("A native runtime adapter is not installed", "未安裝原生執行環境配接器")
    val imageRequired = LocalizedCopy("Import a guest image before starting this profile", "開始之前請先匯入客戶系統映像")
    val saved = LocalizedCopy("Profile saved", "設定檔已儲存")
    val deleted = LocalizedCopy("Profile deleted", "設定檔已刪除")
}

