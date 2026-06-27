package eu.kanade.tachiyomi.data.smb

import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore

class SmbPreferences(
    private val preferenceStore: PreferenceStore,
) {

    val smbHost: Preference<String> = preferenceStore.getString("smb_host", "")

    val smbUser: Preference<String> = preferenceStore.getString("smb_user", "")

    val smbPassword: Preference<String> = preferenceStore.getString("smb_password", "")

    val smbShareName: Preference<String> = preferenceStore.getString("smb_share_name", "")

    val enabledFolders: Preference<Set<String>> = preferenceStore.getStringSet("smb_enabled_folders", emptySet())

    fun isConfigured(): Boolean {
        return smbHost.get().isNotBlank() &&
            smbShareName.get().isNotBlank()
    }
}
