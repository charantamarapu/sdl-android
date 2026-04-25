package com.sdl.grantha.ui.viewmodels

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sdl.grantha.data.local.GranthaEntity
import com.sdl.grantha.data.repository.GranthaRepository
import com.sdl.grantha.domain.search.SearchEngine
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class ReaderViewModel @Inject constructor(
    private val repository: GranthaRepository
) : ViewModel() {

    data class UiState(
        val granthaName: String = "",
        val isLoading: Boolean = true,
        val error: String? = null,
        val pages: List<PageContent> = emptyList(),
        val currentPage: Int = 1,
        val totalPages: Int = 0,
        val identifier: String = "",
        val sourceUrl: String = "",
        val subBooks: List<SearchEngine.SubBookInfo> = emptyList(),
        val currentSubBook: String? = null,
        val grantha: GranthaEntity? = null
    )

    data class PageContent(
        val pageNumber: Int,
        val text: String
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    fun loadGrantha(name: String, startPage: Int = 1) {
        viewModelScope.launch {
            _uiState.update { it.copy(granthaName = name, isLoading = true, error = null) }

            try {
                val grantha = repository.getGranthaByName(name)
                if (grantha == null) {
                    _uiState.update { it.copy(isLoading = false, error = "Grantha not found") }
                    return@launch
                }

                val subBooks = SearchEngine.parseSubBooks(grantha.booksRaw)

                val text = if (grantha.isDownloaded) {
                    withContext(Dispatchers.IO) { repository.getGranthaText(name) }
                } else {
                    null
                }

                // Parse text into pages using {[(N)]} markers if available
                val pages = text?.let { parsePages(it) } ?: emptyList()
                val currentSubBook = SearchEngine.getSubBookForPage(startPage, subBooks)

                _uiState.update {
                    it.copy(
                        isLoading = false,
                        pages = pages,
                        currentPage = startPage,
                        totalPages = pages.size,
                        identifier = grantha.identifier,
                        sourceUrl = grantha.sourceUrl,
                        subBooks = subBooks,
                        currentSubBook = currentSubBook,
                        grantha = grantha
                    )
                }
            } catch (e: Throwable) {
                _uiState.update { it.copy(isLoading = false, error = e.message ?: "Unknown Error") }
            }
        }
    }

    fun goToPage(page: Int) {
        val state = _uiState.value
        val maxPage = if (state.grantha?.isDownloaded == true) state.totalPages else 2000
        val clampedPage = page.coerceIn(1, maxPage.coerceAtLeast(1))
        val subBook = SearchEngine.getSubBookForPage(clampedPage, state.subBooks)
        _uiState.update { it.copy(currentPage = clampedPage, currentSubBook = subBook) }
    }

    fun nextPage() {
        val state = _uiState.value
        val maxPage = if (state.grantha?.isDownloaded == true) state.totalPages else 2000
        if (state.currentPage < maxPage) {
            goToPage(state.currentPage + 1)
        }
    }

    fun previousPage() {
        val state = _uiState.value
        if (state.currentPage > 1) {
            goToPage(state.currentPage - 1)
        }
    }

    /**
     * Get the archive.org page image URL for the current page.
     * Uses the same URL pattern as the web app's reader.
     */
    fun getPageImageUrl(page: Int): String? {
        val state = _uiState.value
        val identifier = state.identifier
        if (identifier.isBlank()) return null

        // Archive.org leaf URL pattern
        // The page parameter in our system is 1-based, archive.org leaf is 0-based
        val leaf = page - 1
        return "https://ia800${(identifier.hashCode() % 10 + 10) % 10}.us.archive.org/BookReader/BookReaderImages.php?zip=/0/items/$identifier/${identifier}_jp2.zip&file=${identifier}_jp2/${identifier}_${String.format("%04d", leaf)}.jp2&id=$identifier&scale=4&rotate=0"
    }

    /**
     * Get the embedded reader URL for viewing page images via archive.org
     */
    fun getArchiveReaderUrl(page: Int): String {
        val state = _uiState.value
        val identifier = state.identifier
        if (identifier.isBlank()) return ""

        // Parse filename from sourceUrl if available
        val sourceUrl = state.sourceUrl
        var filename = ""
        if (sourceUrl.isNotBlank()) {
            val url = java.net.URL(sourceUrl)
            val queryParams = url.query?.split("&")?.associate {
                val parts = it.split("=", limit = 2)
                if (parts.size == 2) parts[0] to java.net.URLDecoder.decode(parts[1], "UTF-8") else parts[0] to ""
            } ?: emptyMap()

            filename = queryParams["file"] ?: ""
            if (filename.isBlank()) {
                val pathParts = url.path.trimEnd('/').split("/")
                val detailsIdx = pathParts.indexOf("details")
                if (detailsIdx >= 0 && detailsIdx + 2 < pathParts.size) {
                    filename = pathParts[detailsIdx + 2]
                }
            }
        }

        return buildString {
            append("https://archive.org/embed/$identifier")
            if (filename.isNotBlank()) append("/$filename")
            append("?ui=embed#page/n${page - 1}/mode/1up")
        }
    }

    private fun parsePages(text: String): List<PageContent> {
        val pagePattern = Regex("\\{\\[\\((\\d+)\\)\\]\\}")
        val pages = mutableListOf<PageContent>()

        val matches = pagePattern.findAll(text)
        var lastStart = 0
        var lastPageNum = 1

        for (match in matches) {
            val content = text.substring(lastStart, match.range.first).trim()
            if (content.isNotEmpty() || pages.isNotEmpty()) {
                pages.add(PageContent(lastPageNum, content))
            }
            lastStart = match.range.last + 1
            lastPageNum = match.groupValues[1].toIntOrNull() ?: (lastPageNum + 1)
        }

        // Add final page
        if (lastStart < text.length) {
            pages.add(PageContent(lastPageNum, text.substring(lastStart).trim()))
        }

        return if (pages.isEmpty()) listOf(PageContent(1, text)) else pages
    }
}
