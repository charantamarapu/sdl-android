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
        val grantha: GranthaEntity? = null,
        val fullText: String? = null,
        val pageMap: List<Pair<Int, Int>>? = null,
        val highlightQuery: String? = null
    )

    data class PageContent(
        val pageNumber: Int,
        val text: String
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    fun loadGrantha(name: String, startPage: Int = 1, highlight: String? = null) {
        viewModelScope.launch {
            _uiState.update { it.copy(granthaName = name, highlightQuery = highlight, isLoading = true, error = null) }

            try {
                val grantha = repository.getGranthaByName(name)
                if (grantha == null) {
                    _uiState.update { it.copy(isLoading = false, error = "Grantha not found") }
                    return@launch
                }

                val subBooks = SearchEngine.parseSubBooks(grantha.booksRaw)

                if (grantha.isDownloaded) {
                    withContext(Dispatchers.IO) {
                        val text = repository.getGranthaText(name)
                        if (text != null) {
                            // High-performance preparation (reuse scanner logic)
                            val prepared = SearchEngine.prepareText(text)
                            val currentSubBook = SearchEngine.getSubBookForPage(startPage, subBooks)
                            
                            // Extract only the current page text for display
                            val pages = List(prepared.pageMap.size + 1) { index ->
                                // Lazy-calculate page text when needed, but for now we'll 
                                // just pass the indices or extract the current one.
                                // To minimize memory, we extract only the current one.
                                if (index + 1 == startPage) {
                                    PageContent(index + 1, extractPageText(text, index + 1, prepared.pageMap))
                                } else {
                                    PageContent(index + 1, "") // Placeholder
                                }
                            }

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
                                    grantha = grantha,
                                    fullText = text, // Cache text for navigation
                                    pageMap = prepared.pageMap
                                )
                            }
                        } else {
                            _uiState.update { it.copy(isLoading = false, error = "Could not read book text") }
                        }
                    }
                } else {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            currentPage = startPage,
                            identifier = grantha.identifier,
                            sourceUrl = grantha.sourceUrl,
                            subBooks = subBooks,
                            grantha = grantha
                        )
                    }
                }
            } catch (e: Throwable) {
                _uiState.update { it.copy(isLoading = false, error = e.message ?: "Unknown Error") }
            }
        }
    }

    private var fullTextCache: String? = null
    private var pageMapCache: List<Pair<Int, Int>>? = null

    fun goToPage(page: Int) {
        val state = _uiState.value
        val maxPage = if (state.grantha?.isDownloaded == true) state.totalPages else 2000
        val clampedPage = page.coerceIn(1, maxPage.coerceAtLeast(1))
        
        val subBook = SearchEngine.getSubBookForPage(clampedPage, state.subBooks)
        
        // Update current page text lazily if downloaded
        if (state.grantha?.isDownloaded == true && state.fullText != null && state.pageMap != null) {
            val updatedPages = state.pages.map { 
                if (it.pageNumber == clampedPage) {
                    it.copy(text = extractPageText(state.fullText, clampedPage, state.pageMap))
                } else it
            }
            _uiState.update { it.copy(currentPage = clampedPage, currentSubBook = subBook, pages = updatedPages) }
        } else {
            _uiState.update { it.copy(currentPage = clampedPage, currentSubBook = subBook) }
        }
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

    private fun extractPageText(text: String, pageNumber: Int, pageMap: List<Pair<Int, Int>>): String {
        if (pageMap.isEmpty()) return text
        
        // Find index of current page marker
        val markerIdx = pageMap.indexOfFirst { it.second == pageNumber }
        if (markerIdx == -1) return ""
        
        val startPos = pageMap[markerIdx].first
        val endPos = if (markerIdx + 1 < pageMap.size) {
            // Find start of next page marker
            // The marker text itself is {[(N)]} which we want to exclude
            // The pageMap stores the position AFTER the marker.
            // We need to find the start of the next marker.
            val nextMarkerPageNum = pageMap[markerIdx + 1].second
            val searchStr = "{[($nextMarkerPageNum)]}"
            val nextStart = text.indexOf(searchStr, startPos)
            if (nextStart != -1) nextStart else text.length
        } else {
            text.length
        }
        
        return text.substring(startPos, endPos).trim()
    }
}
