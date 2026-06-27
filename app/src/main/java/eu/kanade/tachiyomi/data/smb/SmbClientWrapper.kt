package eu.kanade.tachiyomi.data.smb

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2CreateOptions
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.util.EnumSet
import java.util.concurrent.TimeUnit

class SmbClientWrapper(
    private val preferences: SmbPreferences,
) {

    private val mutex = Mutex()
    private var client: SMBClient? = null
    private var connection: Connection? = null
    private var session: Session? = null
    private var share: DiskShare? = null

    private val imageExtensions = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "avif")

    private fun buildConfig(): SmbConfig {
        return SmbConfig.builder()
            .withTimeout(15, TimeUnit.SECONDS)
            .withReadTimeout(30, TimeUnit.SECONDS)
            .withWriteTimeout(30, TimeUnit.SECONDS)
            .withSoTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    private suspend fun ensureConnected(): DiskShare = mutex.withLock {
        val currentShare = share
        if (currentShare != null && currentShare.isConnected) {
            return@withLock currentShare
        }

        disconnect()

        val host = preferences.smbHost.get()
        val user = preferences.smbUser.get()
        val password = preferences.smbPassword.get()
        val shareName = preferences.smbShareName.get()

        val newClient = SMBClient(buildConfig())
        val newConnection = newClient.connect(host)
        val authContext = if (user.isNotBlank()) {
            AuthenticationContext(user, password.toCharArray(), "")
        } else {
            AuthenticationContext.guest()
        }
        val newSession = newConnection.authenticate(authContext)
        val newShare = newSession.connectShare(shareName) as DiskShare

        client = newClient
        connection = newConnection
        session = newSession
        share = newShare

        newShare
    }

    suspend fun testConnection(): Result<String> = withContext(Dispatchers.IO) {
        try {
            val diskShare = ensureConnected()
            val basePath = preferences.smbBasePath.get()
            val path = basePath.ifBlank { "" }
            val entries = diskShare.list(path)
            val folderCount = entries.count {
                (it.fileAttributes and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value) != 0L &&
                    it.fileName != "." && it.fileName != ".."
            }
            Result.success("Conexion exitosa. $folderCount carpetas encontradas.")
        } catch (e: Exception) {
            disconnect()
            Result.failure(e)
        }
    }

    suspend fun listFolders(path: String): List<String> = withContext(Dispatchers.IO) {
        try {
            val diskShare = ensureConnected()
            val entries = diskShare.list(path)
            entries
                .filter {
                    (it.fileAttributes and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value) != 0L &&
                        it.fileName != "." && it.fileName != ".."
                }
                .map { it.fileName }
                .sorted()
        } catch (e: Exception) {
            disconnect()
            emptyList()
        }
    }

    suspend fun listImageFiles(path: String): List<String> = withContext(Dispatchers.IO) {
        try {
            val diskShare = ensureConnected()
            val entries = diskShare.list(path)
            entries
                .filter {
                    (it.fileAttributes and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value) == 0L &&
                        it.fileName.substringAfterLast('.', "").lowercase() in imageExtensions
                }
                .map { it.fileName }
                .sortedWith(NaturalOrderComparator)
        } catch (e: Exception) {
            disconnect()
            emptyList()
        }
    }

    suspend fun getFileInputStream(path: String): InputStream? = withContext(Dispatchers.IO) {
        try {
            val diskShare = ensureConnected()
            val file = diskShare.openFile(
                path,
                EnumSet.of(AccessMask.GENERIC_READ),
                null,
                SMB2ShareAccess.ALL,
                SMB2CreateDisposition.FILE_OPEN,
                EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE),
            )
            file.inputStream
        } catch (e: Exception) {
            null
        }
    }

    suspend fun getFileBytes(path: String): ByteArray? = withContext(Dispatchers.IO) {
        try {
            val inputStream = getFileInputStream(path)
            inputStream?.use { it.readBytes() }
        } catch (e: Exception) {
            null
        }
    }

    fun disconnect() {
        try { share?.close() } catch (_: Exception) {}
        try { session?.close() } catch (_: Exception) {}
        try { connection?.close() } catch (_: Exception) {}
        try { client?.close() } catch (_: Exception) {}
        share = null
        session = null
        connection = null
        client = null
    }

    private object NaturalOrderComparator : Comparator<String> {
        override fun compare(a: String, b: String): Int {
            val aChunks = splitIntoChunks(a)
            val bChunks = splitIntoChunks(b)

            for (i in 0 until minOf(aChunks.size, bChunks.size)) {
                val aChunk = aChunks[i]
                val bChunk = bChunks[i]
                val aIsDigit = aChunk.firstOrNull()?.isDigit() == true
                val bIsDigit = bChunk.firstOrNull()?.isDigit() == true

                val result = when {
                    aIsDigit && bIsDigit -> {
                        val numCompare = aChunk.toBigInteger().compareTo(bChunk.toBigInteger())
                        if (numCompare != 0) numCompare else aChunk.length - bChunk.length
                    }
                    else -> aChunk.compareTo(bChunk, ignoreCase = true)
                }

                if (result != 0) return result
            }

            return aChunks.size - bChunks.size
        }

        private fun splitIntoChunks(s: String): List<String> {
            val chunks = mutableListOf<String>()
            val current = StringBuilder()
            var wasDigit = false

            for (c in s) {
                val isDigit = c.isDigit()
                if (current.isNotEmpty() && isDigit != wasDigit) {
                    chunks.add(current.toString())
                    current.clear()
                }
                current.append(c)
                wasDigit = isDigit
            }
            if (current.isNotEmpty()) {
                chunks.add(current.toString())
            }

            return chunks
        }
    }
}
