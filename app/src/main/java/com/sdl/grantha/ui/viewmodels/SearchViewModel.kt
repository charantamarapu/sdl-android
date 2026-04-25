package com.sdl.grantha.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sdl.grantha.data.repository.GranthaRepository
import com.sdl.grantha.domain.search.SearchEngine
import com.sdl.grantha.domain.search.SearchResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class SearchViewModel @Inject constructor(
    private val repository: GranthaRepository,
    private val application: android.app.Application
) : ViewModel() {

    data class UiState(
        val textQuery: String = "",
        val tagsQuery: String = "",
        val textLogic: String = "or",
        val tagsLogic: String = "or",
        val fuzzyPct: Int = 0,
        val isSearching: Boolean = false,
        val searchProgress: Float = 0f,
        val currentBook: String = "",
        val results: List<SearchResult> = emptyList(),
        val error: String? = null,
        val hasSearched: Boolean = false,
        val availableTags: List<String> = emptyList(),
        val selectedTags: Set<String> = emptySet(),
        val customRules: Map<String, String> = emptyMap(),
        val customRulesText: String = "",
        val maxPerBook: Int = 10,
        val negativeTagsQuery: String = "",
        val basicResults: List<com.sdl.grantha.data.local.GranthaEntity> = emptyList()
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    init {
        loadAvailableTags()
    }

    fun setTextQuery(query: String) {
        _uiState.update { it.copy(textQuery = query) }
    }

    fun setTagsQuery(query: String) {
        _uiState.update { it.copy(tagsQuery = query) }
    }

    fun setNegativeTagsQuery(query: String) {
        _uiState.update { it.copy(negativeTagsQuery = query) }
    }

    fun setTextLogic(logic: String) {
        _uiState.update { it.copy(textLogic = logic) }
    }

    fun setTagsLogic(logic: String) {
        _uiState.update { it.copy(tagsLogic = logic) }
    }

    fun setFuzzyPct(pct: Int) {
        _uiState.update { it.copy(fuzzyPct = pct.coerceIn(0, 50)) }
    }

    fun setMaxPerBook(max: Int) {
        _uiState.update { it.copy(maxPerBook = max) }
    }

    fun toggleTag(tag: String) {
        _uiState.update { state ->
            val newTags = state.selectedTags.toMutableSet()
            if (tag in newTags) newTags.remove(tag) else newTags.add(tag)
            state.copy(
                selectedTags = newTags,
                tagsQuery = newTags.joinToString(",")
            )
        }
    }

    fun setCustomRulesText(text: String) {
        _uiState.update { state ->
            val rules = parseCustomRules(text)
            state.copy(customRulesText = text, customRules = rules)
        }
    }

    private fun parseCustomRules(text: String): Map<String, String> {
        if (text.isBlank()) return emptyMap()
        val rules = mutableMapOf<String, String>()
        for (line in text.split("\n", ",")) {
            val parts = line.trim().split("=", limit = 2)
            if (parts.size == 2 && parts[0].isNotBlank() && parts[1].isNotBlank()) {
                rules[parts[0].trim()] = parts[1].trim()
            }
        }
        return rules
    }

    /**
     * Execute search across all downloaded granthas.
     */
    fun search(isAdvanced: Boolean) {
        val state = _uiState.value
        val query = state.textQuery.trim()

        if (query.isBlank() && state.tagsQuery.isBlank()) {
            _uiState.update { it.copy(error = "Please enter a search query") }
            return
        }

        if (!isAdvanced) {
            // BASIC SEARCH: Search book metadata (title, tags)
            executeBasicSearch(query)
        } else {
            // ADVANCED SEARCH: Search inside books (OCR)
            executeAdvancedSearch()
        }
    }

    private fun executeBasicSearch(query: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isSearching = true, error = null, basicResults = emptyList(), hasSearched = false) }
            try {
                // Search across the FULL catalog (downloaded or not)
                val allGranthas = repository.getAllGranthas().first()
                val q = query.lowercase()
                
                val results = allGranthas.filter { grantha ->
                    grantha.name.lowercase().contains(q) || 
                    grantha.tags.lowercase().contains(q) ||
                    grantha.booksRaw.lowercase().contains(q)
                }
                
                _uiState.update { 
                    it.copy(
                        isSearching = false, 
                        basicResults = results, 
                        hasSearched = true,
                        results = emptyList() // Clear OCR results if any
                    ) 
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(isSearching = false, error = e.message ?: "Basic search failed") }
            }
        }
    }

    private fun executeAdvancedSearch() {
        val state = _uiState.value
        val textQueries = state.textQuery.split(",").map { it.trim() }.filter { it.isNotBlank() }

        if (textQueries.any { it.length < 2 }) {
            _uiState.update { it.copy(error = "Each search term must be at least 2 characters") }
            return
        }

        viewModelScope.launch {
            _uiState.update { 
                it.copy(
                    isSearching = true, 
                    error = null, 
                    results = emptyList(), 
                    basicResults = emptyList(), // Clear book results if any
                    searchProgress = 0f 
                ) 
            }

            try {
                val results = withContext(Dispatchers.Default) {
                    val downloadedFlow = repository.getDownloadedGranthas()
                    val downloaded = downloadedFlow.first()

                    val tagQueries = state.tagsQuery.split(",")
                        .map { it.trim().lowercase() }
                        .filter { it.isNotBlank() }

                    val negTagQueries = state.negativeTagsQuery.split(",")
                        .map { it.trim().lowercase() }
                        .filter { it.isNotBlank() }

                    val allResults = mutableListOf<SearchResult>()

                    for ((index, grantha) in downloaded.withIndex()) {
                        _uiState.update {
                            it.copy(
                                searchProgress = index.toFloat() / downloaded.size,
                                currentBook = "Searching: ${grantha.name}"
                            )
                        }

                        val text = repository.getGranthaText(grantha.name)
                        if (text == null) continue

                        val singleMap = mapOf(grantha.name to Triple(text, grantha.tags, grantha.booksRaw))

                        val partialResults = SearchEngine.searchAll(
                            granthaTexts = singleMap,
                            textQueries = textQueries,
                            textLogic = state.textLogic,
                            fuzzyPct = state.fuzzyPct,
                            tagQueries = tagQueries,
                            tagsLogic = state.tagsLogic,
                            negativeTagQueries = negTagQueries,
                            customRules = state.customRules.ifEmpty { null },
                            maxPerBook = state.maxPerBook,
                            onProgress = null // Progress is handled by the outer loop now
                        )
                        allResults.addAll(partialResults)
                    }
                    allResults
                }

                // Add highlighting to results
                val highlightedResults = results.map { result ->
                    val highlighted = SearchEngine.highlightText(
                        result.contextText,
                        textQueries,
                        state.fuzzyPct,
                        state.customRules.ifEmpty { null }
                    )
                    result.copy(highlightedText = highlighted)
                }

                _uiState.update {
                    it.copy(
                        isSearching = false,
                        results = highlightedResults,
                        hasSearched = true,
                        searchProgress = 1f
                    )
                }
            } catch (e: Throwable) {
                _uiState.update {
                    it.copy(isSearching = false, error = e.message ?: "Search failed: Error")
                }
            }
        }
    }

    fun clearResults() {
        _uiState.update { it.copy(results = emptyList(), hasSearched = false) }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    fun downloadGrantha(name: String) {
        viewModelScope.launch {
            val intent = android.content.Intent(application, com.sdl.grantha.service.DownloadService::class.java).apply {
                action = com.sdl.grantha.service.DownloadService.ACTION_DOWNLOAD
                putStringArrayListExtra(com.sdl.grantha.service.DownloadService.EXTRA_GRANTHA_NAMES, arrayListOf(name))
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                application.startForegroundService(intent)
            } else {
                application.startService(intent)
            }
        }
    }

    fun deleteGrantha(name: String) {
        viewModelScope.launch {
            repository.deleteGrantha(name)
        }
    }

    private fun loadAvailableTags() {
        viewModelScope.launch {
            try {
                val tags = repository.getAllDownloadedTags()
                _uiState.update { it.copy(availableTags = tags) }
            } catch (_: Exception) { }
        }
    }
}
