package com.sdl.grantha.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sdl.grantha.data.repository.GranthaRepository
import com.sdl.grantha.domain.search.SearchEngine
import com.sdl.grantha.domain.search.SearchResult
import com.sdl.grantha.domain.search.SanskritUtils
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.yield
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
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
        val isSearching: Boolean = false,
        val isDeepSearching: Boolean = false,
        val searchProgress: Float = 0f,
        val currentBook: String = "",
        val results: List<SearchResult> = emptyList(), // Main results (one per book)
        val selectedBookResults: String? = null, // Which book is currently "opened"
        val bookSnippets: List<SearchResult> = emptyList(), // All snippets for selected book
        val cachedBookSnippets: Map<String, List<SearchResult>> = emptyMap(), // Cache for book results
        val error: String? = null,
        val hasSearched: Boolean = false,
        val availableTags: List<String> = emptyList(),
        val selectedTags: Set<String> = emptySet(),
        val customRules: List<Pair<String, String>> = listOf(
            "ः" to "र्",
            "ः" to "ो",
            "ः" to "स्",
            "ऽ" to "अ"
        ),
        val customRulesText: String = "ः=र्\nः=ो\nः=स्\nऽ=अ",
        val sanskritNormalize: Boolean = false,
        val maxPerBook: Int = 20,
        val isAdvancedMode: Boolean = false,
        val isOptionsExpanded: Boolean = false,
        val searchMode: SanskritUtils.SanskritSearchMode = SanskritUtils.SanskritSearchMode.CONTAINS,
        val negativeTagsQuery: String = "",
        val basicResults: List<com.sdl.grantha.data.local.GranthaEntity> = emptyList(),
        val suggestions: List<Suggestion> = emptyList()
    )

    data class Suggestion(
        val value: String,
        val type: SuggestionType
    )

    enum class SuggestionType { BOOK, TAG }

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private var searchJob: Job? = null

    private var allGranthas: List<com.sdl.grantha.data.local.GranthaEntity> = emptyList()

    init {
        loadAvailableTags()
        viewModelScope.launch {
            repository.getAllGranthas().collect { allGranthas = it }
        }
    }

    fun updateSuggestions(query: String, target: String) {
        val cleanQuery = if (target == "text") query else query.split(",").last().trim()
        
        if (cleanQuery.length < 2) {
            _uiState.update { it.copy(suggestions = emptyList()) }
            return
        }

        val q = cleanQuery.lowercase()
        val suggestions = mutableListOf<Suggestion>()

        // Add book suggestions
        val bookSuggestions = allGranthas
            .filter { it.name.lowercase().contains(q) }
            .map { Suggestion(it.name, SuggestionType.BOOK) }
        suggestions.addAll(bookSuggestions)

        // Add tag suggestions
        val tagSuggestions = allGranthas
            .flatMap { it.tags.split(",").map { t -> t.trim() } }
            .filter { it.isNotBlank() && it.lowercase().contains(q) }
            .distinct()
            .map { Suggestion(it, SuggestionType.TAG) }
        suggestions.addAll(tagSuggestions)

        _uiState.update { it.copy(suggestions = suggestions.take(10)) }
    }

    fun selectSuggestion(suggestion: Suggestion, target: String) {
        _uiState.update { state ->
            when (target) {
                "text" -> state.copy(textQuery = suggestion.value, suggestions = emptyList())
                "tags" -> {
                    val parts = state.tagsQuery.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()
                    if (parts.isNotEmpty()) parts.removeAt(parts.size - 1)
                    parts.add(suggestion.value)
                    state.copy(tagsQuery = parts.joinToString(", ") + ", ", suggestions = emptyList())
                }
                "negative" -> {
                    val parts = state.negativeTagsQuery.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()
                    if (parts.isNotEmpty()) parts.removeAt(parts.size - 1)
                    parts.add(suggestion.value)
                    state.copy(negativeTagsQuery = parts.joinToString(", ") + ", ", suggestions = emptyList())
                }
                else -> state
            }
        }
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

    fun setMaxPerBook(max: Int) {
        _uiState.update { it.copy(maxPerBook = max) }
    }

    fun toggleSanskritNormalize() {
        _uiState.update { it.copy(sanskritNormalize = !it.sanskritNormalize) }
    }

    fun setSearchMode(mode: SanskritUtils.SanskritSearchMode) {
        _uiState.update { it.copy(searchMode = mode) }
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

    private fun parseCustomRules(text: String): List<Pair<String, String>> {
        if (text.isBlank()) return emptyList()
        val rules = mutableListOf<Pair<String, String>>()
        for (line in text.split("\n", ",")) {
            val parts = line.trim().split("=", limit = 2)
            if (parts.size == 2 && parts[0].isNotBlank() && parts[1].isNotBlank()) {
                rules.add(parts[0].trim() to parts[1].trim())
            }
        }
        return rules
    }

    fun search(isAdvanced: Boolean) {
        val state = _uiState.value
        val query = state.textQuery.trim()

        if (query.isBlank() && state.tagsQuery.isBlank()) {
            _uiState.update { it.copy(error = "Please enter a search query") }
            return
        }

        if (!isAdvanced) {
            executeBasicSearch(query)
        } else {
            executeAdvancedSearch()
        }
    }

    private fun executeBasicSearch(query: String) {
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            val startTime = System.currentTimeMillis()
            _uiState.update { 
                it.copy(
                    isSearching = true, 
                    error = null, 
                    basicResults = emptyList(), 
                    hasSearched = false,
                    cachedBookSnippets = emptyMap(),
                    selectedBookResults = null,
                    bookSnippets = emptyList(),
                    isOptionsExpanded = false
                ) 
            }
            try {
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
                        results = emptyList()
                    ) 
                }
                val duration = System.currentTimeMillis() - startTime
                val seconds = duration / 1000.0
                android.widget.Toast.makeText(application, "Basic search took ${String.format("%.1f", seconds)}s", android.widget.Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                if (e !is CancellationException) {
                    _uiState.update { it.copy(isSearching = false, error = e.message ?: "Basic search failed") }
                }
            }
        }
    }

    private fun executeAdvancedSearch() {
        val state = _uiState.value
        val textQueriesRaw = state.textQuery.split(",").map { it.trim() }.filter { it.isNotBlank() }
        val textQueries = textQueriesRaw

        if (textQueries.any { it.length < 2 }) {
            _uiState.update { it.copy(error = "Each search term must be at least 2 characters") }
            return
        }

        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            val startTime = System.currentTimeMillis()
            _uiState.update { 
                it.copy(
                    isSearching = true, 
                    error = null, 
                    results = emptyList(), 
                    basicResults = emptyList(),
                    searchProgress = 0f,
                    cachedBookSnippets = emptyMap(),
                    selectedBookResults = null,
                    bookSnippets = emptyList(),
                    hasSearched = false,
                    isOptionsExpanded = false
                ) 
            }

            try {
                withContext(Dispatchers.Default) {
                    val downloaded = repository.getDownloadedGranthas().first()

                    if (downloaded.isEmpty()) {
                        withContext(Dispatchers.Main) {
                            _uiState.update { it.copy(isSearching = false, error = "Please download some books first.") }
                        }
                        return@withContext
                    }

                    val tagQueries = state.tagsQuery.split(",").map { it.trim().lowercase() }.filter { it.isNotBlank() }
                    val negTagQueries = state.negativeTagsQuery.split(",").map { it.trim().lowercase() }.filter { it.isNotBlank() }

                    val totalBooks = downloaded.size
                    var booksProcessed = 0

                    val concurrency = Runtime.getRuntime().availableProcessors()
                    downloaded.asFlow().flatMapMerge(concurrency = concurrency) { grantha ->
                        flow {
                            yield()
                            val text = if (SearchEngine.hasCache(grantha.name)) "" else repository.getGranthaText(grantha.name)
                            if (text == null) {
                                synchronized(this@SearchViewModel) {
                                    booksProcessed++
                                    _uiState.update { it.copy(searchProgress = booksProcessed.toFloat() / totalBooks) }
                                }
                                emit(Unit)
                                return@flow
                            }

                            val singleMap = mapOf(grantha.name to Triple(text, grantha.tags, grantha.booksRaw))
                            val partialResults = SearchEngine.searchAll(
                                granthaTexts = singleMap,
                                textQueries = textQueries,
                                textLogic = state.textLogic,
                                tagQueries = tagQueries,
                                tagsLogic = state.tagsLogic,
                                negativeTagQueries = negTagQueries,
                                customRules = if (state.sanskritNormalize) state.customRules.ifEmpty { null } else null,
                                maxPerBook = state.maxPerBook,
                                stopAtFirstMatch = true,
                                searchMode = state.searchMode
                            )

                            val highlighted = partialResults.map { result ->
                                var finalResult = result
                                if (result.contextText == "MATCH_FOUND_WITHOUT_TEXT") {
                                    val realText = repository.getGranthaText(grantha.name)
                                    if (realText != null) {
                                        val cleanContext = SearchEngine.extractContext(realText, result.matchOffset)
                                        finalResult = result.copy(contextText = "...$cleanContext...")
                                    }
                                }

                                val h = SearchEngine.highlightText(
                                    finalResult.contextText,
                                    textQueries,
                                    state.customRules.ifEmpty { null },
                                    state.searchMode
                                )
                                finalResult.copy(highlightedText = h)
                            }

                            synchronized(this@SearchViewModel) {
                                booksProcessed++
                                _uiState.update { 
                                    it.copy(
                                        searchProgress = booksProcessed.toFloat() / totalBooks,
                                        currentBook = grantha.name,
                                        results = it.results + highlighted
                                    ) 
                                }
                            }
                            emit(Unit)
                        }
                    }.collect()
                }

                _uiState.update { it.copy(isSearching = false, hasSearched = true, searchProgress = 1f) }
                val duration = System.currentTimeMillis() - startTime
                android.widget.Toast.makeText(application, "Search took ${String.format("%.1f", duration / 1000.0)}s", android.widget.Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                if (e !is CancellationException) {
                    _uiState.update { it.copy(isSearching = false, error = e.message ?: "Search failed") }
                }
            } finally {
                _uiState.update { it.copy(isSearching = false) }
            }
        }
    }

    fun stopSearch() {
        searchJob?.cancel()
        searchJob = null
        _uiState.update { it.copy(isSearching = false, currentBook = "Search stopped") }
    }

    fun clearResults() {
        _uiState.update { 
            it.copy(
                results = emptyList(), 
                basicResults = emptyList(), 
                hasSearched = false, 
                selectedBookResults = null,
                bookSnippets = emptyList(),
                cachedBookSnippets = emptyMap()
            ) 
        }
    }

    fun selectBookForDeepSearch(bookName: String) {
        val state = _uiState.value
        if (state.cachedBookSnippets.containsKey(bookName)) {
            _uiState.update { it.copy(
                selectedBookResults = bookName,
                bookSnippets = it.cachedBookSnippets[bookName] ?: emptyList()
            ) }
            return
        }

        val textQueriesRaw = state.textQuery.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        val textQueries = textQueriesRaw
        if (textQueries.isEmpty()) return

        viewModelScope.launch {
            _uiState.update { it.copy(isDeepSearching = true, selectedBookResults = bookName, bookSnippets = emptyList()) }
            try {
                withContext(Dispatchers.IO) {
                    val grantha = repository.getGranthaByName(bookName)
                    val text = if (SearchEngine.hasCache(bookName)) "" else repository.getGranthaText(bookName)
                    
                    if (grantha != null && text != null) {
                        val subBooks = SearchEngine.parseSubBooks(grantha.booksRaw)
                        val prepared = SearchEngine.prepareText(bookName, text)
                        
                        val allSnippets = mutableListOf<SearchResult>()
                        for (q in textQueries) {
                             val activeRules = if (state.sanskritNormalize) state.customRules.ifEmpty { null } else null
                             val matches = SearchEngine.smartSearchInternal(prepared, text, q, bookName, subBooks, activeRules, state.maxPerBook, state.searchMode)
                             allSnippets.addAll(matches)
                        }
                        
                        var finalSnippets = allSnippets.sortedBy { it.page }
                        if (finalSnippets.any { it.contextText == "MATCH_FOUND_WITHOUT_TEXT" }) {
                            val realText = repository.getGranthaText(bookName)
                            if (realText != null) {
                                finalSnippets = finalSnippets.map { 
                                    if (it.contextText == "MATCH_FOUND_WITHOUT_TEXT") {
                                        it.copy(contextText = "...${SearchEngine.extractContext(realText, it.matchOffset)}...")
                                    } else it
                                }
                            }
                        }

                        val highlighted = finalSnippets.map { result ->
                            val h = SearchEngine.highlightText(
                                result.contextText,
                                textQueries,
                                state.customRules.ifEmpty { null },
                                state.searchMode
                            )
                            result.copy(highlightedText = h)
                        }

                        _uiState.update { 
                            it.copy(
                                isDeepSearching = false, 
                                bookSnippets = highlighted,
                                cachedBookSnippets = it.cachedBookSnippets + (bookName to highlighted)
                            ) 
                        }
                    } else {
                        _uiState.update { it.copy(isDeepSearching = false, error = "Book text not found") }
                    }
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(isDeepSearching = false, error = e.message) }
            }
        }
    }

    fun deselectBook() {
        _uiState.update { it.copy(selectedBookResults = null, bookSnippets = emptyList()) }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    fun setAdvancedMode(isAdvanced: Boolean) {
        _uiState.update { 
            it.copy(
                isAdvancedMode = isAdvanced,
                isOptionsExpanded = if (isAdvanced && it.results.isEmpty()) true else it.isOptionsExpanded
            ) 
        }
    }

    fun toggleOptionsExpanded() {
        _uiState.update { it.copy(isOptionsExpanded = !it.isOptionsExpanded) }
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
