package eu.kanade.tachiyomi.data.smb

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2CreateOptions
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.share.DiskShare
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.util.EnumSet
import java.util.concurrent.TimeUnit

class SmbClientWrapper(
    private val preferences: SmbPreferences,
) {

    private val imageExtensions = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "avif")

    private fun buildConfig(): SmbConfig {
        return SmbConfig.builder()
            .withTimeout(15, TimeUnit.SECONDS)
            .withReadTimeout(30, TimeUnit.SECONDS)
            .withWriteTimeout(30, TimeUnit.SECONDS)
            .withSoTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Crea una conexion fresca, ejecuta el bloque y la cierra siempre.
     * Cada operacion es independiente: no hay estado compartido que pueda quedarse muerto.
     */
    private suspend fun <T> withFreshConnection(block: (DiskShare) -> T): T = withContext(Dispatchers.IO) {
        val host = preferences.smbHost.get()
        val user = preferences.smbUser.get()
        val password = preferences.smbPassword.get()
        val shareName = preferences.smbShareName.get()

        val client = SMBClient(buildConfig())
        val connection = client.connect(host)
        try {
            val authContext = if (user.isNotBlank()) {
                AuthenticationContext(user, password.toCharArray(), "")
            } else {
                AuthenticationContext.guest()
            }
            val session = connection.authenticate(authContext)
            val diskShare = session.connectShare(shareName) as DiskShare
            try {
                block(diskShare)
            } finally {
                try { diskShare.close() } catch (_: Exception) {}
                try { session.close() } catch (_: Exception) {}
            }
        } finally {
            try { connection.close() } catch (_: Exception) {}
            try { client.close() } catch (_: Exception) {}
        }
    }

    suspend fun testConnection(): Result<String> = withContext(Dispatchers.IO) {
        try {
            val folderCount = withFreshConnection { diskShare ->
                diskShare.list("").count {
                    (it.fileAttributes and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value) != 0L &&
                        it.fileName != "." && it.fileName != ".."
                }
            }
            Result.success("Conexion exitosa. $folderCount carpetas encontradas.")
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun listFolders(path: String): List<String> = withContext(Dispatchers.IO) {
        try {
            withFreshConnection { diskShare ->
                diskShare.list(path)
                    .filter {
                        val isDir = (it.fileAttributes and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value) != 0L
                        val isZip = (it.fileAttributes and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value) == 0L &&
                            (it.fileName.endsWith(".cbz", true) || it.fileName.endsWith(".zip", true))
                        (isDir || isZip) && it.fileName != "." && it.fileName != ".."
                    }
                    .map { it.fileName }
                    .sorted()
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Returns a list of (folderName, lastModifiedMs) pairs.
     * lastModifiedMs is milliseconds since Unix epoch from the SMB server's last write time.
     */
    suspend fun listFoldersWithDate(path: String): List<Pair<String, Long>> = withContext(Dispatchers.IO) {
        try {
            withFreshConnection { diskShare ->
                diskShare.list(path)
                    .filter {
                        val isDir = (it.fileAttributes and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value) != 0L
                        val isZip = (it.fileAttributes and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value) == 0L &&
                            (it.fileName.endsWith(".cbz", true) || it.fileName.endsWith(".zip", true))
                        (isDir || isZip) && it.fileName != "." && it.fileName != ".."
                    }
                    .map { entry ->
                        val lastWriteMs = try {
                            entry.lastWriteTime.toDate().time
                        } catch (_: Exception) {
                            0L
                        }
                        entry.fileName to lastWriteMs
                    }
                    .sortedBy { it.first }
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    suspend fun listImageFiles(path: String): List<String> = withContext(Dispatchers.IO) {
        try {
            withFreshConnection { diskShare ->
                diskShare.list(path)
                    .filter {
                        (it.fileAttributes and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value) == 0L &&
                            it.fileName.substringAfterLast('.', "").lowercase() in imageExtensions
                    }
                    .map { it.fileName }
                    .sortedWith(NaturalOrderComparator)
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    suspend fun getFileInputStream(path: String): InputStream? = withContext(Dispatchers.IO) {
        try {
            val host = preferences.smbHost.get()
            val user = preferences.smbUser.get()
            val password = preferences.smbPassword.get()
            val shareName = preferences.smbShareName.get()

            val client = SMBClient(buildConfig())
            val connection = client.connect(host)
            val authContext = if (user.isNotBlank()) {
                AuthenticationContext(user, password.toCharArray(), "")
            } else {
                AuthenticationContext.guest()
            }
            val session = connection.authenticate(authContext)
            val diskShare = session.connectShare(shareName) as DiskShare

            val file = diskShare.openFile(
                path,
                EnumSet.of(AccessMask.GENERIC_READ),
                null,
                SMB2ShareAccess.ALL,
                SMB2CreateDisposition.FILE_OPEN,
                EnumSet.noneOf(SMB2CreateOptions::class.java),
            )

            // Envolvemos el InputStream para cerrar la conexion al finalizar
            object : InputStream() {
                private val inner = file.inputStream
                override fun read(): Int = inner.read()
                override fun read(b: ByteArray, off: Int, len: Int): Int = inner.read(b, off, len)
                override fun close() {
                    try { inner.close() } catch (_: Exception) {}
                    try { file.close() } catch (_: Exception) {}
                    try { diskShare.close() } catch (_: Exception) {}
                    try { session.close() } catch (_: Exception) {}
                    try { connection.close() } catch (_: Exception) {}
                    try { client.close() } catch (_: Exception) {}
                }
            }
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

    // Mantenido por compatibilidad con el codigo existente
    suspend fun disconnect() { /* sin estado persistente, no hay nada que cerrar */ }

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
