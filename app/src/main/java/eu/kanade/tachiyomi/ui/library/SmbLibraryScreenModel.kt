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

import android.content.Intent
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.toMangaUpdate
import tachiyomi.domain.manga.repository.MangaRepository

class SmbLibraryScreenModel(
    private val smbPreferences: SmbPreferences = Injekt.get(),
    private val smbClient: SmbClientWrapper = Injekt.get(),
    private val libraryCache: SmbLibraryCache = Injekt.get(),
    private val mangaRepository: MangaRepository = Injekt.get(),
    private val chapterRepository: ChapterRepository = Injekt.get(),
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
                    val mangaFolders = smbClient.listFolders(folder)
                    val mangaItems = mangaFolders.map { mangaName ->
                        val mangaPath = "$folder\\$mangaName"
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

    fun openManga(context: Context, mangaItem: SmbMangaItem) {
        screenModelScope.launchIO {
            // 1. Check or insert Manga
            val smbSourceId = eu.kanade.tachiyomi.data.smb.SmbSource.ID
            var dbManga = mangaRepository.getMangaByUrlAndSourceId(mangaItem.smbPath, smbSourceId)
            if (dbManga == null) {
                val newManga = Manga.create().copy(
                    source = smbSourceId,
                    url = mangaItem.smbPath,
                    title = mangaItem.name,
                    thumbnailUrl = mangaItem.coverCachePath,
                    initialized = true
                )
                dbManga = mangaRepository.insertNetworkManga(listOf(newManga)).first()
            } else if (dbManga.thumbnailUrl != mangaItem.coverCachePath) {
                // Update cover if changed
                val updatedManga = dbManga.copy(thumbnailUrl = mangaItem.coverCachePath)
                mangaRepository.update(updatedManga.toMangaUpdate())
            }

            // 2. Check or insert Chapter
            // SMB chapters act as a single chapter named after the manga
            var dbChapter = chapterRepository.getChapterByUrlAndMangaId(mangaItem.smbPath, dbManga.id)
            if (dbChapter == null) {
                val newChapter = Chapter.create().copy(
                    mangaId = dbManga.id,
                    url = mangaItem.smbPath,
                    name = mangaItem.name,
                    chapterNumber = 1.0,
                    dateUpload = System.currentTimeMillis()
                )
                dbChapter = chapterRepository.addAll(listOf(newChapter)).first()
            }

            // 3. Launch ReaderActivity with Manga ID and Chapter ID
            withContext(Dispatchers.Main) {
                val intent = ReaderActivity.newIntent(context, dbManga.id, dbChapter.id)
                context.startActivity(intent)
            }
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
