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
import android.app.Application
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.zip.ZipFile

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
    private val context: Application by injectLazy()
    
    private var localZipFile: File? = null
    private var zipArchive: ZipFile? = null
    
    private var prefetchJob: Job? = null
    private var chapterCacheDir: File? = null

    override suspend fun getPages(): List<ReaderPage> {
        val chapterUrl = chapter.chapter.url // This is the SMB path to the folder
        val isZip = chapterUrl.endsWith(".cbz", true) || chapterUrl.endsWith(".zip", true)
        
        if (isZip) {
            return getZipPages(chapterUrl)
        }
        
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
        
        val prefetchEnabled = smbPreferences.prefetchEnabled.get()
        
        if (prefetchEnabled) {
            val cacheDir = File(context.cacheDir, "smb_images_cache/${chapter.chapter.id ?: chapter.chapter.url.hashCode()}")
            cacheDir.mkdirs()
            chapterCacheDir = cacheDir
            
            val mutexes = Array(images.size) { Mutex() }
            
            val pages = images.mapIndexed { i, fileName ->
                val fullPath = "$chapterUrl\\$fileName"
                val localFile = File(cacheDir, fileName)
                val tmpFile = File(cacheDir, "$fileName.tmp")
                
                val streamFn = { 
                    kotlinx.coroutines.runBlocking {
                        mutexes[i].withLock {
                            if (!localFile.exists() || localFile.length() == 0L) {
                                smbClient.getFileInputStream(fullPath)?.use { input ->
                                    tmpFile.outputStream().use { output ->
                                        input.copyTo(output)
                                    }
                                }
                                tmpFile.renameTo(localFile)
                            }
                        }
                        if (localFile.exists() && localFile.length() > 0L) {
                            localFile.inputStream()
                        } else {
                            throw Exception("Could not download file")
                        }
                    }
                }
                ReaderPage(i).apply {
                    stream = streamFn
                    status = Page.State.Ready
                }
            }

            prefetchJob = GlobalScope.launch(Dispatchers.IO) {
                for (i in images.indices) {
                    val fullPath = "$chapterUrl\\${images[i]}"
                    val localFile = File(cacheDir, images[i])
                    val tmpFile = File(cacheDir, "${images[i]}.tmp")

                    try {
                        mutexes[i].withLock {
                            if (!localFile.exists() || localFile.length() == 0L) {
                                smbClient.getFileInputStream(fullPath)?.use { input ->
                                    tmpFile.outputStream().use { output ->
                                        input.copyTo(output)
                                    }
                                }
                                tmpFile.renameTo(localFile)
                            }
                        }
                    } catch (e: Exception) {
                        // Ignore errors in prefetch, the reader will retry if the user reaches this page
                    }
                }
            }

            return pages
        } else {
            val pages = images.mapIndexed { i, fileName ->
                val fullPath = "$chapterUrl\\$fileName"
                
                val streamFn = { 
                    kotlinx.coroutines.runBlocking {
                        smbClient.getFileInputStream(fullPath) ?: throw Exception("Could not open file")
                    }
                }
                ReaderPage(i).apply {
                    stream = streamFn
                    status = Page.State.Ready
                }
            }
            return pages
        }
    }

    override fun retryPage(page: ReaderPage) {
        page.status = Page.State.Queue
        page.status = Page.State.Ready
    }

    override fun recycle() {
        super.recycle()
        prefetchJob?.cancel()
        try { chapterCacheDir?.deleteRecursively() } catch (e: Exception) {}
        try { zipArchive?.close() } catch (e: Exception) {}
        zipArchive = null
        try { localZipFile?.delete() } catch (e: Exception) {}
        localZipFile = null
    }

    private suspend fun getZipPages(chapterUrl: String): List<ReaderPage> {
        val cacheDir = File(context.cacheDir, "smb_zip_cache")
        cacheDir.mkdirs()
        
        val fileName = chapterUrl.substringAfterLast('\\').substringAfterLast('/')
        val localFile = File(cacheDir, fileName)
        
        val tmpFile = File(cacheDir, fileName + ".tmp")
        
        // Descargar si no existe localmente o si está vacío
        if (!localFile.exists() || localFile.length() == 0L) {
            smbClient.getFileInputStream(chapterUrl)?.use { input ->
                tmpFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            tmpFile.renameTo(localFile)
        }
        
        localZipFile = localFile
        val zip = ZipFile(localFile)
        zipArchive = zip
        
        val imageExtensions = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "avif")
        
        val entries = zip.entries().asSequence()
            .filter { !it.isDirectory && it.name.substringAfterLast('.', "").lowercase() in imageExtensions }
            .sortedWith { f1, f2 -> f1.name.compareToCaseInsensitiveNaturalOrder(f2.name) }
            .toList()
            
        val imageCountStr = entries.size.toString()
        if (entries.isNotEmpty() && chapter.chapter.scanlator != imageCountStr) {
            val chapterId = chapter.chapter.id
            if (chapterId != null) {
                val dbChapter = chapterRepository.getChapterById(chapterId)
                if (dbChapter != null) {
                    chapterRepository.update(dbChapter.toChapterUpdate().copy(scanlator = imageCountStr))
                    chapter.chapter.scanlator = imageCountStr
                }
            }
        }
        
        return entries.mapIndexed { i, entry ->
            ReaderPage(i).apply {
                stream = { zip.getInputStream(entry) }
                status = Page.State.Ready
            }
        }
    }
}
