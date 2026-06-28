package eu.kanade.presentation.library.components

import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.PreviewLightDark
import eu.kanade.presentation.theme.TachiyomiPreviewTheme
import tachiyomi.presentation.core.components.Badge
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Modifier

@Composable
internal fun DownloadsBadge(count: Int) {
    if (count > 0) {
        Badge(
            text = "$count",
            color = MaterialTheme.colorScheme.tertiary,
            textColor = MaterialTheme.colorScheme.onTertiary,
        )
    }
}

@Composable
internal fun UnreadBadge(count: Long, readCount: Long = 0L, totalChapters: Long = 0L) {
    when {
        totalChapters > 0 && readCount > 0 -> Badge(text = "$readCount/$totalChapters")
        totalChapters == 0L && count > 0 -> Badge(text = "$count")
        else -> Unit
    }
}

@Composable
internal fun LanguageBadge(
    isLocal: Boolean,
    sourceLanguage: String,
) {
    if (isLocal) {
        Badge(
            imageVector = Icons.Outlined.Folder,
            color = MaterialTheme.colorScheme.tertiary,
            iconColor = MaterialTheme.colorScheme.onTertiary,
        )
    } else if (sourceLanguage.isNotEmpty()) {
        Badge(
            text = sourceLanguage.uppercase(),
            color = MaterialTheme.colorScheme.tertiary,
            textColor = MaterialTheme.colorScheme.onTertiary,
        )
    }
}

@Composable
internal fun EnglishBadge(title: String) {
    if (
        title.contains("[English]", ignoreCase = true) ||
        title.contains("[Eng]", ignoreCase = true) ||
        title.contains("(English)", ignoreCase = true) ||
        title.contains("(Eng)", ignoreCase = true)
    ) {
        val neonGreen = Color(0xFF39FF14)
        Text(
            text = "English",
            modifier = Modifier
                .background(Color(0x80000000))
                .padding(horizontal = 4.dp, vertical = 2.dp),
            color = neonGreen,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            style = MaterialTheme.typography.bodySmall.copy(
                shadow = Shadow(
                    color = neonGreen,
                    blurRadius = 12f
                )
            ),
        )
    }
}

@PreviewLightDark
@Composable
private fun BadgePreview() {
    TachiyomiPreviewTheme {
        Column {
            DownloadsBadge(count = 10)
            UnreadBadge(count = 10)
            LanguageBadge(isLocal = true, sourceLanguage = "EN")
            LanguageBadge(isLocal = false, sourceLanguage = "EN")
        }
    }
}
