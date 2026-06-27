package eu.kanade.tachiyomi.data.smb

import android.app.Application
import tachiyomi.core.common.util.system.logcat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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

class SmbSyncManager(
    private val application: Application = Injekt.get(),
    private val smbPreferences: SmbPreferences = Injekt.get(),
    private val smbClient: SmbClientWrapper = Injekt.get(),
    private val mangaRepository: MangaRepository = Injekt.get(),
    private val categoryRepository: CategoryRepository = Injekt.get(),
    private val chapterRepository: ChapterRepository = Injekt.get(),
) {

    suspend fun syncLibrary() = withContext(Dispatchers.IO) {
        try {
            val enabledFolders = smbPreferences.enabledFolders.get()
            if (enabledFolders.isEmpty()) return@withContext

            val coversDir = File(application.filesDir, "smb_covers")
            if (!coversDir.exists()) coversDir.mkdirs()

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

                // 2. Scan mangas in this folder
                val mangaFolders = smbClient.listFolders(folder)
                
                for (mangaName in mangaFolders) {
                    val mangaPath = "$folder\\$mangaName"
                    val smbSourceId = SmbSource.ID

                    // 3. Ensure manga exists
                    var dbManga = mangaRepository.getMangaByUrlAndSourceId(mangaPath, smbSourceId)
                    var coverPath: String? = null

                    // 4. Download cover
                    val coverFile = File(coversDir, "${mangaPath.hashCode()}.jpg")
                    if (!coverFile.exists()) {
                        try {
                            val images = smbClient.listImageFiles(mangaPath)
                            if (images.isNotEmpty()) {
                                val firstImage = images.first()
                                val imagePath = "$mangaPath\\$firstImage"
                                val bytes = smbClient.getFileBytes(imagePath)
                                if (bytes != null) {
                                    coverFile.writeBytes(bytes)
                                }
                            }
                        } catch (e: Exception) {
                            logcat(LogPriority.ERROR, e) { "Failed to get cover for $mangaName" }
                        }
                    }
                    if (coverFile.exists()) {
                        coverPath = coverFile.absolutePath
                    }

                    if (dbManga == null) {
                        val newManga = Manga.create().copy(
                            source = smbSourceId,
                            url = mangaPath,
                            title = mangaName,
                            thumbnailUrl = coverPath,
                            favorite = true, // Force it to appear in library
                            dateAdded = System.currentTimeMillis(),
                            initialized = true
                        )
                        dbManga = mangaRepository.insertNetworkManga(listOf(newManga)).firstOrNull()
                    } else {
                        // Update if needed
                        var changed = false
                        var updatedManga = dbManga
                        if (!dbManga.favorite) {
                            updatedManga = updatedManga.copy(favorite = true, dateAdded = System.currentTimeMillis())
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

                    // 6. Ensure dummy chapter exists with image count in scanlator
                    val imageCount = try {
                        smbClient.listImageFiles(mangaPath).size.toLong()
                    } catch (e: Exception) {
                        0L
                    }
                    val dbChapter = chapterRepository.getChapterByUrlAndMangaId(mangaPath, dbManga.id)
                    if (dbChapter == null) {
                        val newChapter = Chapter.create().copy(
                            mangaId = dbManga.id,
                            url = mangaPath,
                            name = mangaName,
                            chapterNumber = 1.0,
                            dateUpload = System.currentTimeMillis(),
                            scanlator = if (imageCount > 0) "$imageCount" else null,
                        )
                        chapterRepository.addAll(listOf(newChapter))
                    } else if (imageCount > 0 && dbChapter.scanlator != "$imageCount") {
                        // Update total page count if it changed
                        chapterRepository.update(
                            dbChapter.toChapterUpdate().copy(scanlator = "$imageCount")
                        )
                    }
                }
            }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "SMB Sync failed" }
        }
    }
}
