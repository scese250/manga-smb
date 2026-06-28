package eu.kanade.presentation.more.settings.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.icerock.moko.resources.StringResource
import eu.kanade.presentation.more.settings.Preference
import eu.kanade.tachiyomi.data.smb.SmbClientWrapper
import eu.kanade.tachiyomi.data.smb.SmbPreferences
import eu.kanade.tachiyomi.data.smb.SmbSyncManager
import eu.kanade.tachiyomi.data.smb.SyncState
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.util.collectAsState
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import androidx.compose.runtime.collectAsState as collectStateFlow
import eu.kanade.tachiyomi.data.cache.CoverCache
import coil3.ImageLoader
import coil3.imageLoader
object SettingsSmbScreen : SearchableSettings {

    @Composable
    @ReadOnlyComposable
    override fun getTitleRes(): StringResource = MR.strings.label_settings

    @Composable
    override fun getPreferences(): List<Preference> {
        val smbPreferences = remember { Injekt.get<SmbPreferences>() }
        val scope = rememberCoroutineScope()

        var testResult by remember { mutableStateOf<String?>(null) }
        var isTesting by remember { mutableStateOf(false) }
        var availableFolders by remember { mutableStateOf<List<String>>(emptyList()) }
        var isFetchingFolders by remember { mutableStateOf(false) }

        val enabledFolders by smbPreferences.enabledFolders.collectAsState()
        val context = androidx.compose.ui.platform.LocalContext.current
        val host by smbPreferences.smbHost.collectAsState()
        val coverCache = remember { Injekt.get<CoverCache>() }
        val imageLoader = context.imageLoader
        var isClearingCache by remember { mutableStateOf(false) }
        var cacheClearResult by remember { mutableStateOf<String?>(null) }
        var currentCacheSize by remember { mutableStateOf(0L) }

        androidx.compose.runtime.LaunchedEffect(Unit) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val coilSize = imageLoader.diskCache?.size ?: 0L
                val smbSize = coverCache.getCacheSize()
                currentCacheSize = coilSize + smbSize
            }
        }

        return listOf(
            Preference.PreferenceGroup(
                title = "Conexion SMB",
                preferenceItems = listOf(
                    Preference.PreferenceItem.EditTextPreference(
                        preference = smbPreferences.smbHost,
                        title = "Host / IP",
                        subtitle = smbPreferences.smbHost.get().ifBlank { "ej. 192.168.0.100" },
                    ),
                    Preference.PreferenceItem.EditTextPreference(
                        preference = smbPreferences.smbUser,
                        title = "Usuario",
                        subtitle = smbPreferences.smbUser.get().ifBlank { "Dejar vacio para acceso anonimo" },
                    ),
                    Preference.PreferenceItem.EditTextPreference(
                        preference = smbPreferences.smbPassword,
                        title = "Contrasena",
                        subtitle = if (smbPreferences.smbPassword.get().isNotBlank()) "********" else "Dejar vacio para acceso anonimo",
                    ),
                    Preference.PreferenceItem.EditTextPreference(
                        preference = smbPreferences.smbShareName,
                        title = "Nombre del recurso compartido",
                        subtitle = smbPreferences.smbShareName.get().ifBlank { "El nombre del share en Windows, ej: Exhentai" },
                    ),
                ),
            ),
            Preference.PreferenceGroup(
                title = "Acciones",
                preferenceItems = listOf(
                    Preference.PreferenceItem.CustomPreference(
                        title = "smb_actions",
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Button(
                                    onClick = {
                                        scope.launch {
                                            isTesting = true
                                            testResult = null
                                            val client = SmbClientWrapper(smbPreferences)
                                            val result = client.testConnection()
                                            testResult = result.fold(
                                                onSuccess = { it },
                                                onFailure = { "Error: ${it.message}" },
                                            )
                                            client.disconnect()
                                            isTesting = false
                                        }
                                    },
                                    enabled = !isTesting && host.isNotBlank(),
                                ) {
                                    Text("Probar conexion")
                                }
                                if (isTesting) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.padding(start = 8.dp),
                                        strokeWidth = 2.dp,
                                    )
                                }
                            }
                            if (testResult != null) {
                                Text(
                                    text = testResult!!,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (testResult!!.startsWith("Error")) {
                                        MaterialTheme.colorScheme.error
                                    } else {
                                        MaterialTheme.colorScheme.primary
                                    },
                                )
                            }

                            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                            Button(
                                onClick = {
                                    scope.launch {
                                        isFetchingFolders = true
                                        val client = SmbClientWrapper(smbPreferences)
                                        availableFolders = client.listFolders("")
                                        client.disconnect()
                                        isFetchingFolders = false
                                    }
                                },
                                enabled = !isFetchingFolders && host.isNotBlank(),
                            ) {
                                Text("Agregar carpetas")
                            }
                            if (isFetchingFolders) {
                                CircularProgressIndicator(
                                    modifier = Modifier.padding(start = 8.dp),
                                    strokeWidth = 2.dp,
                                )
                            }
                            
                            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                            
                            val syncState by SmbSyncManager.stateFlow.collectStateFlow()
                            val isSyncing = syncState is SyncState.Syncing
                            Button(
                                onClick = {
                                    scope.launch {
                                        eu.kanade.tachiyomi.data.smb.SmbSyncManager().syncLibrary()
                                    }
                                },
                                enabled = !isSyncing && enabledFolders.isNotEmpty(),
                            ) {
                                Text(if (isSyncing) "Sincronizando..." else "Sincronizar Biblioteca SMB")
                            }
                            when (val state = syncState) {
                                is SyncState.Syncing -> Text(
                                    text = "Sincronizando mangas...",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                is SyncState.Done -> Text(
                                    text = "Listo. ${state.mangaCount} mangas sincronizados.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                is SyncState.Error -> Text(
                                    text = "Error: ${state.message}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                                else -> Unit
                            }

                            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Button(
                                    onClick = {
                                        scope.launch {
                                            isClearingCache = true
                                            cacheClearResult = null
                                            imageLoader.diskCache?.clear()
                                            val deleted = coverCache.clear()
                                            
                                            // Recalculate
                                            val coilSize = imageLoader.diskCache?.size ?: 0L
                                            val smbSize = coverCache.getCacheSize()
                                            currentCacheSize = coilSize + smbSize
                                            
                                            cacheClearResult = "Cache limpiada. $deleted archivos eliminados."
                                            isClearingCache = false
                                        }
                                    },
                                    enabled = !isClearingCache,
                                ) {
                                    Text("Limpiar cache de miniaturas")
                                }
                                if (isClearingCache) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.padding(start = 8.dp),
                                        strokeWidth = 2.dp,
                                    )
                                }
                            }
                            
                            // Formateo del tamaño (e.g. "15.24 MB")
                            val mbSize = currentCacheSize / 1024.0 / 1024.0
                            val formattedSize = if (mbSize > 0.0) String.format(java.util.Locale.US, "%.2f MB", mbSize) else "0 MB"
                            Text(
                                text = "Peso actual de la cache: $formattedSize",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )

                            if (cacheClearResult != null) {
                                Text(
                                    text = cacheClearResult!!,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    },
                ),
            ),
            Preference.PreferenceGroup(
                title = "Carpetas disponibles",
                enabled = availableFolders.isNotEmpty(),
                preferenceItems = if (availableFolders.isEmpty()) {
                    listOf(
                        Preference.PreferenceItem.InfoPreference(
                            title = "Presiona 'Cargar carpetas' para ver las carpetas disponibles",
                        ),
                    )
                } else {
                    listOf(
                        Preference.PreferenceItem.CustomPreference(
                            title = "smb_folders_list",
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp),
                            ) {
                                availableFolders.forEach { folder ->
                                    val isEnabled = enabledFolders.contains(folder)
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 2.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Checkbox(
                                            checked = isEnabled,
                                            onCheckedChange = { checked ->
                                                val current = smbPreferences.enabledFolders.get().toMutableSet()
                                                if (checked) {
                                                    current.add(folder)
                                                } else {
                                                    current.remove(folder)
                                                }
                                                smbPreferences.enabledFolders.set(current)
                                            },
                                        )
                                        Text(
                                            text = folder,
                                            style = MaterialTheme.typography.bodyMedium,
                                            modifier = Modifier.padding(start = 8.dp),
                                        )
                                    }
                                }
                            }
                        },
                    )
                },
            ),
        )
    }
}
