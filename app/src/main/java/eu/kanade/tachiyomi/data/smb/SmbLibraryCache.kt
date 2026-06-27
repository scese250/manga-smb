package eu.kanade.tachiyomi.data.smb

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

class SmbLibraryCache(
    private val context: Context,
) {

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = false
    }

    private val cacheFile: File
        get() = File(context.filesDir, "smb_library_cache.json")

    suspend fun save(data: CachedLibrary) = withContext(Dispatchers.IO) {
        val content = json.encodeToString(CachedLibrary.serializer(), data)
        cacheFile.writeText(content)
    }

    suspend fun load(): CachedLibrary? = withContext(Dispatchers.IO) {
        try {
            if (!cacheFile.exists()) return@withContext null
            val content = cacheFile.readText()
            json.decodeFromString(CachedLibrary.serializer(), content)
        } catch (e: Exception) {
            null
        }
    }

    fun hasCache(): Boolean = cacheFile.exists()
}

@Serializable
data class CachedLibrary(
    val categories: Map<String, List<SmbMangaItem>>,
)

@Serializable
data class SmbMangaItem(
    val name: String,
    val smbPath: String,
    val coverCachePath: String? = null,
)
