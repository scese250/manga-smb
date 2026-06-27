package eu.kanade.tachiyomi.ui.library

import android.content.Context
import android.graphics.BitmapFactory
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.tachiyomi.data.smb.CachedLibrary
import eu.kanade.tachiyomi.data.smb.SmbClientWrapper
import eu.kanade.tachiyomi.data.smb.SmbLibraryCache
import eu.kanade.tachiyomi.data.smb.SmbMangaItem
import eu.kanade.tachiyomi.data.smb.SmbPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tachiyomi.core.common.util.lang.launchIO
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File

class SmbLibraryScreenModel(
    private val smbPreferences: SmbPreferences = Injekt.get(),
    private val smbClient: SmbClientWrapper = Injekt.get(),
    private val libraryCache: SmbLibraryCache = Injekt.get(),
) : StateScreenModel<SmbLibraryScreenModel.State>(State()) {

    init {
        screenModelScope.launchIO {
            loadFromCache()
            loadCoversProgressively()
        }
    }

    private suspend fun loadFromCache() {
        val cached = libraryCache.load()
        if (cached != null) {
            val enabledFolders = smbPreferences.enabledFolders.get()
            val filteredCategories = cached.categories.filter { it.key in enabledFolders }
            mutableState.update { state ->
                state.copy(
                    categories = filteredCategories.keys.toList(),
                    mangaByCategory = filteredCategories,
                    isLoading = false,
                )
            }
        } else {
            val enabledFolders = smbPreferences.enabledFolders.get()
            if (enabledFolders.isEmpty()) {
                mutableState.update { it.copy(isLoading = false) }
            } else {
                refresh()
            }
        }
    }

    fun refresh() {
        screenModelScope.launchIO {
            mutableState.update { it.copy(isRefreshing = true) }

            try {
                val basePath = smbPreferences.smbBasePath.get()
                val enabledFolders = smbPreferences.enabledFolders.get()

                if (enabledFolders.isEmpty()) {
                    mutableState.update {
                        it.copy(
                            categories = emptyList(),
                            mangaByCategory = emptyMap(),
                            isLoading = false,
                            isRefreshing = false,
                        )
                    }
                    return@launchIO
                }

                val newCategories = mutableMapOf<String, List<SmbMangaItem>>()

                for (folder in enabledFolders) {
                    val folderPath = if (basePath.isBlank()) folder else "$basePath\\$folder"
                    val mangaFolders = smbClient.listFolders(folderPath)
                    val mangaItems = mangaFolders.map { mangaName ->
                        val mangaPath = "$folderPath\\$mangaName"
                        SmbMangaItem(
                            name = mangaName,
                            smbPath = mangaPath,
                        )
                    }
                    newCategories[folder] = mangaItems
                }

                val cachedLibrary = CachedLibrary(categories = newCategories)
                libraryCache.save(cachedLibrary)

                mutableState.update { state ->
                    state.copy(
                        categories = newCategories.keys.toList(),
                        mangaByCategory = newCategories,
                        isLoading = false,
                        isRefreshing = false,
                    )
                }

                loadCoversProgressively()
            } catch (e: Exception) {
                mutableState.update {
                    it.copy(
                        isRefreshing = false,
                        isLoading = false,
                        error = "Error al cargar: ${e.message}",
                    )
                }
            }
        }
    }

    private suspend fun loadCoversProgressively() {
        val currentState = state.value
        val context = Injekt.get<android.app.Application>()
        val coversDir = File(context.filesDir, "smb_covers")
        if (!coversDir.exists()) coversDir.mkdirs()

        for ((category, mangas) in currentState.mangaByCategory) {
            for (manga in mangas) {
                val coverFile = File(coversDir, "${manga.smbPath.hashCode()}.jpg")

                if (coverFile.exists() && manga.coverCachePath != null) continue

                if (coverFile.exists()) {
                    updateMangaCover(category, manga.name, coverFile.absolutePath)
                    continue
                }

                screenModelScope.launch(Dispatchers.IO) {
                    try {
                        val images = smbClient.listImageFiles(manga.smbPath)
                        if (images.isNotEmpty()) {
                            val firstImage = images.first()
                            val imagePath = "${manga.smbPath}\\$firstImage"
                            val bytes = smbClient.getFileBytes(imagePath)
                            if (bytes != null) {
                                withContext(Dispatchers.IO) {
                                    coverFile.writeBytes(bytes)
                                }
                                updateMangaCover(category, manga.name, coverFile.absolutePath)
                            }
                        }
                    } catch (_: Exception) {
                        // Cover loading failed silently
                    }
                }
            }
        }
    }

    private fun updateMangaCover(category: String, mangaName: String, coverPath: String) {
        mutableState.update { state ->
            val updatedCategories = state.mangaByCategory.toMutableMap()
            val mangaList = updatedCategories[category]?.toMutableList() ?: return@update state
            val index = mangaList.indexOfFirst { it.name == mangaName }
            if (index >= 0) {
                mangaList[index] = mangaList[index].copy(coverCachePath = coverPath)
                updatedCategories[category] = mangaList
            }
            state.copy(mangaByCategory = updatedCategories)
        }

        screenModelScope.launchIO {
            val cached = CachedLibrary(categories = state.value.mangaByCategory)
            libraryCache.save(cached)
        }
    }

    data class State(
        val categories: List<String> = emptyList(),
        val mangaByCategory: Map<String, List<SmbMangaItem>> = emptyMap(),
        val isLoading: Boolean = true,
        val isRefreshing: Boolean = false,
        val activeCategoryIndex: Int = 0,
        val error: String? = null,
    ) {
        val activeCategory: String?
            get() = categories.getOrNull(activeCategoryIndex)
    }
}
