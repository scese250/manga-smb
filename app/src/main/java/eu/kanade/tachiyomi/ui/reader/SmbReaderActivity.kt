package eu.kanade.tachiyomi.ui.reader

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import eu.kanade.presentation.theme.TachiyomiTheme
import eu.kanade.tachiyomi.data.smb.SmbClientWrapper
import eu.kanade.tachiyomi.data.smb.SmbPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tachiyomi.presentation.core.components.material.Scaffold
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File

class SmbReaderActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val mangaPath = intent.getStringExtra(EXTRA_MANGA_PATH) ?: run {
            finish()
            return
        }
        val mangaName = intent.getStringExtra(EXTRA_MANGA_NAME) ?: "Reader"

        setContent {
            TachiyomiTheme {
                SmbReaderScreen(
                    mangaPath = mangaPath,
                    mangaName = mangaName,
                    onBack = { finish() },
                )
            }
        }
    }

    companion object {
        private const val EXTRA_MANGA_PATH = "manga_path"
        private const val EXTRA_MANGA_NAME = "manga_name"

        fun newIntent(context: Context, mangaPath: String, mangaName: String): Intent {
            return Intent(context, SmbReaderActivity::class.java).apply {
                putExtra(EXTRA_MANGA_PATH, mangaPath)
                putExtra(EXTRA_MANGA_NAME, mangaName)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SmbReaderScreen(
    mangaPath: String,
    mangaName: String,
    onBack: () -> Unit,
) {
    val smbClient = remember { Injekt.get<SmbClientWrapper>() }
    val context = LocalContext.current

    var isLoading by remember { mutableStateOf(true) }
    val imageFiles = remember { mutableStateListOf<File>() }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(mangaPath) {
        withContext(Dispatchers.IO) {
            try {
                val cacheDir = File(context.cacheDir, "smb_reader/${mangaPath.hashCode()}")
                if (!cacheDir.exists()) cacheDir.mkdirs()

                val imageNames = smbClient.listImageFiles(mangaPath)

                for (imageName in imageNames) {
                    val cachedFile = File(cacheDir, imageName)
                    if (!cachedFile.exists()) {
                        val imagePath = "$mangaPath\\$imageName"
                        val bytes = smbClient.getFileBytes(imagePath)
                        if (bytes != null) {
                            cachedFile.writeBytes(bytes)
                        }
                    }
                    if (cachedFile.exists()) {
                        imageFiles.add(cachedFile)
                    }
                }

                isLoading = false
            } catch (e: Exception) {
                error = "Error: ${e.message}"
                isLoading = false
            }
        }
    }

    Scaffold(
        topBar = { _ ->
            TopAppBar(
                title = {
                    Text(
                        text = if (imageFiles.isNotEmpty()) {
                            mangaName
                        } else {
                            mangaName
                        },
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Black.copy(alpha = 0.7f),
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White,
                ),
            )
        },
    ) { contentPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .padding(contentPadding),
            contentAlignment = Alignment.Center,
        ) {
            when {
                isLoading -> {
                    CircularProgressIndicator(color = Color.White)
                }
                error != null -> {
                    Text(
                        text = error!!,
                        color = Color.White,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
                imageFiles.isEmpty() -> {
                    Text(
                        text = "No se encontraron imagenes",
                        color = Color.White,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
                else -> {
                    val pagerState = rememberPagerState(pageCount = { imageFiles.size })

                    HorizontalPager(
                        state = pagerState,
                        modifier = Modifier.fillMaxSize(),
                    ) { page ->
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) {
                            AsyncImage(
                                model = ImageRequest.Builder(context)
                                    .data(imageFiles[page])
                                    .crossfade(true)
                                    .build(),
                                contentDescription = "Page ${page + 1}",
                                contentScale = ContentScale.Fit,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                }
            }
        }
    }
}
