package eu.kanade.tachiyomi.data.smb

import android.content.Context
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.UnmeteredSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate

class SmbSource(
    private val context: Context,
    val smbPreferences: SmbPreferences
) : Source, UnmeteredSource {

    override val id: Long = ID

    override val name: String = "SMB Lector"

    override val lang: String = "other"

    override fun toString() = name

    override val supportsLatest: Boolean = false

    // We do not need to implement these for direct reading, 
    // but we return empty to satisfy interface.
    override suspend fun getPopularManga(page: Int): MangasPage = MangasPage(emptyList(), false)

    override suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage = MangasPage(emptyList(), false)

    override suspend fun getLatestUpdates(page: Int): MangasPage = MangasPage(emptyList(), false)

    override suspend fun getMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val newChapters = if (fetchChapters) {
            listOf(
                SChapter.create().apply {
                    url = manga.url
                    name = manga.title
                    chapter_number = 1.0f
                    date_upload = System.currentTimeMillis()
                }
            )
        } else {
            chapters
        }
        return SMangaUpdate(manga, newChapters)
    }

    @Suppress("DEPRECATION")
    override suspend fun getPageList(chapter: SChapter): List<Page> = throw UnsupportedOperationException("Unused")

    override fun getFilterList(): FilterList = FilterList()

    companion object {
        const val ID = 6969696969L
    }
}
