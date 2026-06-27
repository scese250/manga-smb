package eu.kanade.tachiyomi.ui.reader.loader

import eu.kanade.tachiyomi.data.smb.SmbClientWrapper
import eu.kanade.tachiyomi.data.smb.SmbPreferences
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.util.lang.compareToCaseInsensitiveNaturalOrder
import tachiyomi.core.common.util.system.ImageUtil

/**
 * Loader used to load a chapter from an SMB share.
 */
internal class SmbPageLoader(
    val chapter: ReaderChapter,
    val smbPreferences: SmbPreferences
) : PageLoader() {

    override var isLocal: Boolean = true
    private var smbClient: SmbClientWrapper? = null

    override suspend fun getPages(): List<ReaderPage> {
        if (smbClient == null) {
            smbClient = SmbClientWrapper(smbPreferences)
        }
        
        val chapterUrl = chapter.chapter.url // This is the SMB path to the folder
        val images = smbClient?.listImageFiles(chapterUrl) ?: emptyList()
        
        return images
            .mapIndexed { i, fileName ->
                val fullPath = "$chapterUrl\\$fileName"
                val streamFn = { 
                    // We must create a new client or use the existing one to open stream.
                    // Returning an InputStream directly allows ReaderPage to read the bytes.
                    // runBlocking because getFileInputStream is a suspend function
                    kotlinx.coroutines.runBlocking {
                        SmbClientWrapper(smbPreferences).getFileInputStream(fullPath)
                            ?: throw Exception("Could not open file")
                    }
                }
                ReaderPage(i).apply {
                    stream = streamFn
                    status = Page.State.Ready
                }
            }
    }

    override fun recycle() {
        super.recycle()
        smbClient?.disconnect()
        smbClient = null
    }
}
