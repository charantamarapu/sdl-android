package com.sdl.grantha.ui.viewmodels

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.sdl.grantha.data.download.GranthaDownloadManager
import com.sdl.grantha.data.local.GranthaEntity
import com.sdl.grantha.data.repository.GranthaRepository
import com.sdl.grantha.service.DownloadService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val repository: GranthaRepository,
    application: Application
) : AndroidViewModel(application) {

    // UI state
    data class UiState(
        val isLoading: Boolean = false,
        val isSyncing: Boolean = false,
        val error: String? = null,
        val showDownloadedOnly: Boolean = false,
        val searchQuery: String = "",
        val selectedGranthas: Set<String> = emptySet(),
        val isSelectionMode: Boolean = false,
        val totalCount: Int = 0,
        val downloadedCount: Int = 0,
        val downloadedSizeMb: String = "0",
        val syncMessage: String? = null
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    // Grantha list — reactive to filter changes
    val granthas: StateFlow<List<GranthaEntity>> = combine(
        _uiState.map { it.showDownloadedOnly }.distinctUntilChanged(),
        _uiState.map { it.searchQuery }.distinctUntilChanged()
    ) { downloadedOnly, query ->
        Pair(downloadedOnly, query)
    }.flatMapLatest { (downloadedOnly, query) ->
        when {
            query.isNotBlank() && downloadedOnly -> repository.searchDownloadedGranthas(query)
            query.isNotBlank() -> repository.searchGranthas(query)
            downloadedOnly -> repository.getDownloadedGranthas()
            else -> repository.getAllGranthas()
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Download progress
    val downloadProgress = repository.getDownloadProgress()
    val bulkProgress = repository.getBulkProgress()

    init {
        refreshCounts()
    }

    fun syncCatalog() {
        viewModelScope.launch {
            _uiState.update { it.copy(isSyncing = true, error = null, syncMessage = null) }
            val result = repository.syncCatalog()
            result.fold(
                onSuccess = { count ->
                    _uiState.update { it.copy(
                        isSyncing = false, 
                        syncMessage = "Library synced: $count books found"
                    ) }
                    refreshCounts()
                },
                onFailure = { e ->
                    _uiState.update { it.copy(isSyncing = false, error = e.message) }
                }
            )
        }
    }

    fun clearSyncMessage() {
        _uiState.update { it.copy(syncMessage = null) }
    }

    fun toggleDownloadedOnly() {
        _uiState.update { it.copy(showDownloadedOnly = !it.showDownloadedOnly) }
    }

    fun setSearchQuery(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
    }

    fun toggleSelection(name: String) {
        _uiState.update { state ->
            val newSelection = state.selectedGranthas.toMutableSet()
            if (name in newSelection) newSelection.remove(name) else newSelection.add(name)
            state.copy(
                selectedGranthas = newSelection,
                isSelectionMode = newSelection.isNotEmpty()
            )
        }
    }

    fun selectAll(names: List<String>) {
        _uiState.update { it.copy(selectedGranthas = names.toSet(), isSelectionMode = true) }
    }

    fun clearSelection() {
        _uiState.update { it.copy(selectedGranthas = emptySet(), isSelectionMode = false) }
    }

    fun downloadSelected() {
        val names = _uiState.value.selectedGranthas.toList()
        if (names.isEmpty()) return

        // Start foreground service for bulk download
        val context = getApplication<Application>()
        val intent = Intent(context, DownloadService::class.java).apply {
            action = DownloadService.ACTION_DOWNLOAD
            putStringArrayListExtra(DownloadService.EXTRA_GRANTHA_NAMES, ArrayList(names))
        }
        context.startForegroundService(intent)
        clearSelection()
    }

    fun downloadSingle(name: String) {
        viewModelScope.launch {
            repository.downloadGrantha(name)
            refreshCounts()
        }
    }

    fun deleteGrantha(name: String) {
        viewModelScope.launch {
            repository.deleteGrantha(name)
            refreshCounts()
        }
    }

    fun downloadAll() {
        viewModelScope.launch {
            val allGranthas = granthas.value.filter { !it.isDownloaded }.map { it.name }
            if (allGranthas.isEmpty()) return@launch

            val context = getApplication<Application>()
            val intent = Intent(context, DownloadService::class.java).apply {
                action = DownloadService.ACTION_DOWNLOAD
                putStringArrayListExtra(DownloadService.EXTRA_GRANTHA_NAMES, ArrayList(allGranthas))
            }
            context.startForegroundService(intent)
        }
    }

    fun cancelDownloads() {
        val context = getApplication<Application>()
        val intent = Intent(context, DownloadService::class.java).apply {
            action = DownloadService.ACTION_CANCEL
        }
        context.startService(intent)
    }

    fun deleteAllDownloads() {
        viewModelScope.launch {
            repository.deleteAllDownloads()
            refreshCounts()
        }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    fun setError(error: String) {
        _uiState.update { it.copy(error = error) }
    }

    private fun refreshCounts() {
        viewModelScope.launch {
            val total = repository.getTotalCount()
            val downloaded = repository.getDownloadedCount()
            val sizeBytes = repository.getDownloadedSizeBytes()
            val sizeMb = String.format("%.1f", sizeBytes / (1024.0 * 1024.0))
            _uiState.update { it.copy(totalCount = total, downloadedCount = downloaded, downloadedSizeMb = sizeMb) }
        }
    }
}
