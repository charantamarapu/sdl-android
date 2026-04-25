package com.sdl.grantha.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sdl.grantha.domain.search.SearchResult
import com.sdl.grantha.ui.theme.*

@Composable
fun SearchResultCard(
    result: SearchResult,
    onClick: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            // Header: book name + page
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        Icons.Filled.MenuBook,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = result.granthaName,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // Page badge
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.padding(start = 8.dp)
                ) {
                    Text(
                        text = "p.${result.page}",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }

            // Sub-book name if present
            if (result.subBook != null) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "📖 ${result.subBook}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Context text with highlights
            // The highlighted text uses 【】 markers from SearchEngine
            val displayText = formatHighlightedText(
                if (result.highlightedText.isNotBlank()) result.highlightedText
                else result.contextText
            )

            Text(
                text = displayText,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * Convert 【highlighted】 text markers to AnnotatedString with background highlights.
 */
@Composable
private fun formatHighlightedText(text: String): AnnotatedString {
    val highlightColor = HighlightYellow
    val parts = text.split("【", "】")

    return buildAnnotatedString {
        var isHighlight = false
        // The text is split such that odd indices (after 【) are highlights
        var index = 0
        var remaining = text
        while (remaining.isNotEmpty()) {
            val startMarker = remaining.indexOf("【")
            if (startMarker < 0) {
                append(remaining)
                break
            }

            // Text before marker
            if (startMarker > 0) {
                append(remaining.substring(0, startMarker))
            }

            val endMarker = remaining.indexOf("】", startMarker + 1)
            if (endMarker < 0) {
                append(remaining.substring(startMarker))
                break
            }

            // Highlighted text
            val highlighted = remaining.substring(startMarker + 1, endMarker)
            withStyle(
                SpanStyle(
                    background = highlightColor,
                    fontWeight = FontWeight.Bold
                )
            ) {
                append(highlighted)
            }

            remaining = remaining.substring(endMarker + 1)
        }
    }
}
