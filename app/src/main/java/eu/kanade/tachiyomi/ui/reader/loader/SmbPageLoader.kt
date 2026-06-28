package eu.kanade.tachiyomi.ui.reader.loader

import eu.kanade.tachiyomi.data.smb.SmbClientWrapper
import eu.kanade.tachiyomi.data.smb.SmbPreferences
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.util.lang.compareToCaseInsensitiveNaturalOrder
import tachiyomi.core.common.util.system.ImageUtil
import tachiyomi.domain.chapter.repository.ChapterRepository
import tachiyomi.domain.chapter.model.toChapterUpdate
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy

/**
 * Loader used to load a chapter from an SMB share.
 */
internal class SmbPageLoader(
    val chapter: ReaderChapter,
    val smbPreferences: SmbPreferences
) : PageLoader() {

    override var isLocal: Boolean = true
    private val smbClient: SmbClientWrapper by injectLazy()
    private val chapterRepository: ChapterRepository by injectLazy()

    override suspend fun getPages(): List<ReaderPage> {
        val chapterUrl = chapter.chapter.url // This is the SMB path to the folder
        val images = smbClient.listImageFiles(chapterUrl) ?: emptyList()
        
        // Update chapter scanlator with image count dynamically if needed
        val imageCountStr = images.size.toString()
        if (images.isNotEmpty() && chapter.chapter.scanlator != imageCountStr) {
            val chapterId = chapter.chapter.id
            if (chapterId != null) {
                val dbChapter = chapterRepository.getChapterById(chapterId)
                if (dbChapter != null) {
                    chapterRepository.update(dbChapter.toChapterUpdate().copy(scanlator = imageCountStr))
                    chapter.chapter.scanlator = imageCountStr
                }
            }
        }
        
        return images
            .mapIndexed { i, fileName ->
                val fullPath = "$chapterUrl\\$fileName"
                val streamFn = { 
                    // We must create a new client or use the existing one to open stream.
                    // Returning an InputStream directly allows ReaderPage to read the bytes.
                    // runBlocking because getFileInputStream is a suspend function
                    kotlinx.coroutines.runBlocking {
                        smbClient.getFileInputStream(fullPath)
                            ?: throw Exception("Could not open file")
                    }
                }
                ReaderPage(i).apply {
                    stream = streamFn
                    status = Page.State.Ready
                }
            }
    }

    override fun retryPage(page: ReaderPage) {
        page.status = Page.State.Queue
        page.status = Page.State.Ready
    }

    override fun recycle() {
        super.recycle()
    }
}
