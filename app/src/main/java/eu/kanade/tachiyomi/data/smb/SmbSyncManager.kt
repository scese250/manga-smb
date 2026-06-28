package eu.kanade.tachiyomi.data.smb

import android.app.Application
import tachiyomi.core.common.util.system.logcat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import logcat.LogPriority
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.category.repository.CategoryRepository
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.model.toChapterUpdate
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.toMangaUpdate
import tachiyomi.domain.manga.repository.MangaRepository
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File

sealed class SyncState {
    object Idle : SyncState()
    object Syncing : SyncState()
    data class Done(val mangaCount: Int) : SyncState()
    data class Error(val message: String) : SyncState()
}

class SmbSyncManager(
    private val application: Application = Injekt.get(),
    private val smbPreferences: SmbPreferences = Injekt.get(),
    private val smbClient: SmbClientWrapper = Injekt.get(),
    private val mangaRepository: MangaRepository = Injekt.get(),
    private val categoryRepository: CategoryRepository = Injekt.get(),
    private val chapterRepository: ChapterRepository = Injekt.get(),
) {
    companion object {
        private val _stateFlow = MutableStateFlow<SyncState>(SyncState.Idle)
        val stateFlow: StateFlow<SyncState> = _stateFlow
    }

    suspend fun syncLibrary() = withContext(Dispatchers.IO) {
        _stateFlow.value = SyncState.Syncing
        try {
            var mangaCount = 0
            val enabledFolders = smbPreferences.enabledFolders.get()
            if (enabledFolders.isEmpty()) return@withContext

            // Delete old smb_covers folder if it exists (migration to dynamic coil cache)
            val coversDir = File(application.filesDir, "smb_covers")
            if (coversDir.exists()) coversDir.deleteRecursively()

            // Get existing categories to avoid duplicates
            val dbCategories = categoryRepository.getAll()

            for (folder in enabledFolders) {
                val folderName = folder.substringAfterLast("\\").takeIf { it.isNotEmpty() } ?: folder

                // 1. Ensure category exists
                var category = dbCategories.find { it.name == folderName }
                if (category == null) {
                    val maxOrder = dbCategories.maxOfOrNull { it.order } ?: 0
                    val newCategory = Category(
                        id = 0, // DB will auto-generate if 0 or -1? In Mihon it's 0 usually
                        name = folderName,
                        order = maxOrder + 1,
                        flags = 0
                    )
                    categoryRepository.insert(newCategory)
                    // Refresh categories to get the newly inserted one with its ID
                    val updatedCategories = categoryRepository.getAll()
                    category = updatedCategories.find { it.name == folderName }
                }
                
                if (category == null) continue

                // 2. Scan mangas in this folder with modification date
                val mangaFoldersWithDate = smbClient.listFoldersWithDate(folder)
                mangaCount += mangaFoldersWithDate.size

                for ((mangaName, folderModifiedAt) in mangaFoldersWithDate) {
                    val mangaPath = "$folder\\$mangaName"
                    val smbSourceId = SmbSource.ID
                    val dateAdded = if (folderModifiedAt > 0) folderModifiedAt else System.currentTimeMillis()

                    // 3. Ensure manga exists
                    var dbManga = mangaRepository.getMangaByUrlAndSourceId(mangaPath, smbSourceId)
                    // 4. Set thumbnail URL to SMB path for dynamic loading
                    val coverPath = "smb://$mangaPath"

                    if (dbManga == null) {
                        val newManga = Manga.create().copy(
                            source = smbSourceId,
                            url = mangaPath,
                            title = mangaName,
                            thumbnailUrl = coverPath,
                            favorite = true,
                            dateAdded = dateAdded,
                            initialized = true
                        )
                        dbManga = mangaRepository.insertNetworkManga(listOf(newManga)).firstOrNull()
                    } else {
                        // Update if needed
                        var changed = false
                        var updatedManga = dbManga
                        if (!dbManga.favorite) {
                            updatedManga = updatedManga.copy(favorite = true, dateAdded = dateAdded)
                            changed = true
                        }
                        if (dbManga.dateAdded != dateAdded && dateAdded > 0) {
                            updatedManga = updatedManga.copy(dateAdded = dateAdded)
                            changed = true
                        }
                        if (dbManga.thumbnailUrl != coverPath) {
                            updatedManga = updatedManga.copy(thumbnailUrl = coverPath)
                            changed = true
                        }
                        if (changed) {
                            mangaRepository.update(updatedManga.toMangaUpdate())
                            dbManga = updatedManga
                        }
                    }

                    if (dbManga == null) continue

                    // 5. Associate Manga with Category
                    val currentCategories = categoryRepository.getCategoriesByMangaId(dbManga.id)
                    if (currentCategories.none { it.id == category.id }) {
                        val newCategoryList = currentCategories.map { it.id } + category.id
                        mangaRepository.setMangaCategories(dbManga.id, newCategoryList)
                    }

                    // 6. Ensure dummy chapter exists
                    // Image count (scanlator) will be populated dynamically when the chapter is loaded
                    val dbChapter = chapterRepository.getChapterByUrlAndMangaId(mangaPath, dbManga.id)
                    if (dbChapter == null) {
                        val newChapter = Chapter.create().copy(
                            mangaId = dbManga.id,
                            url = mangaPath,
                            name = mangaName,
                            chapterNumber = 1.0,
                            dateUpload = System.currentTimeMillis(),
                        )
                        chapterRepository.addAll(listOf(newChapter))
                    }
                }
            }
            _stateFlow.value = SyncState.Done(mangaCount)
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "SMB Sync failed" }
            _stateFlow.value = SyncState.Error(e.message ?: "Error desconocido")
            return@withContext
        }
    }
}
