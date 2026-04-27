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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class BulkDownloadState(
    val names: List<String>,
    val totalSizeMb: String
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val repository: GranthaRepository,
    application: Application
) : AndroidViewModel(application) {

    enum class SortOption { NAME, SIZE, PAGES }

    // UI state
    data class UiState(
        val isLoading: Boolean = false,
        val isSyncing: Boolean = false,
        val error: String? = null,
        val sortOption: SortOption = SortOption.NAME,
        val isAscending: Boolean = true,
        val showDownloadedOnly: Boolean = false,
        val searchQuery: String = "",
        val selectedGranthas: Set<String> = emptySet(),
        val isSelectionMode: Boolean = false,
        val totalCount: Int = 0,
        val downloadedCount: Int = 0,
        val downloadedSizeMb: String = "0",
        val syncMessage: String? = null,
        val isBackingUp: Boolean = false,
        val isRestoring: Boolean = false,
        val isHealthChecking: Boolean = false,
        val anyDownloadedSelected: Boolean = false,
        val anyNotDownloadedSelected: Boolean = false
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val _bulkDownloadConfirm = MutableStateFlow<BulkDownloadState?>(null)
    val bulkDownloadConfirm: StateFlow<BulkDownloadState?> = _bulkDownloadConfirm.asStateFlow()

    // Grantha list — reactive to search, sort and filter changes
    val granthas: StateFlow<List<GranthaEntity>> = combine(
        _uiState.map { it.sortOption }.distinctUntilChanged(),
        _uiState.map { it.isAscending }.distinctUntilChanged(),
        _uiState.map { it.showDownloadedOnly }.distinctUntilChanged(),
        _uiState.map { it.searchQuery }.distinctUntilChanged()
    ) { sort, asc, downloadedOnly, query ->
        StateParams(sort, asc, downloadedOnly, query)
    }.flatMapLatest { params ->
        val baseFlow = if (params.query.isNotBlank()) {
            repository.searchGranthas(params.query)
        } else {
            repository.getAllGranthas()
        }

        baseFlow.map { list ->
            val filteredList = if (params.downloadedOnly) {
                list.filter { it.isDownloaded }
            } else {
                list
            }

            // Apply sorting
            when (params.sort) {
                SortOption.NAME -> if (params.asc) filteredList.sortedBy { it.name } else filteredList.sortedByDescending { it.name }
                SortOption.SIZE -> if (params.asc) filteredList.sortedBy { it.sizeBytes } else filteredList.sortedByDescending { it.sizeBytes }
                SortOption.PAGES -> if (params.asc) filteredList.sortedBy { it.pageCount } else filteredList.sortedByDescending { it.pageCount }
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private data class StateParams(val sort: SortOption, val asc: Boolean, val downloadedOnly: Boolean, val query: String)

    private data class Quadruple<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)

    // Download progress
    val downloadProgress = repository.getDownloadProgress()
    val bulkProgress = repository.getBulkProgress()

    // Suggestions
    val suggestions: StateFlow<List<String>> = _uiState.map { it.searchQuery }
        .distinctUntilChanged()
        .flatMapLatest { query ->
            flow {
                val allTags = repository.getAllTags()
                if (query.isBlank()) {
                    emit(allTags.take(15))
                } else {
                    val filtered = allTags.filter { it.contains(query, ignoreCase = true) }
                    emit(filtered.take(15))
                }
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    companion object {
        private var hasAutoSynced = false
    }

    init {
        // Observe library stats reactively
        repository.getTotalCountFlow()
            .onEach { count -> _uiState.update { it.copy(totalCount = count) } }
            .launchIn(viewModelScope)

        repository.getDownloadedCountFlow()
            .onEach { count -> _uiState.update { it.copy(downloadedCount = count) } }
            .launchIn(viewModelScope)

        repository.getDownloadedSizeBytesFlow()
            .onEach { bytes -> 
                val sizeMb = String.format("%.1f", bytes / (1024.0 * 1024.0))
                _uiState.update { it.copy(downloadedSizeMb = sizeMb) } 
            }
            .launchIn(viewModelScope)

        // Sync catalog automatically ONCE when the app/viewmodel starts for the first time
        if (!hasAutoSynced) {
            syncCatalog(silent = false)
            hasAutoSynced = true
        }
    }

    fun syncCatalog(silent: Boolean = false) {
        viewModelScope.launch {
            _uiState.update { it.copy(isSyncing = true, error = null, syncMessage = null) }
            val result = repository.syncCatalog()
            result.fold(
                onSuccess = { count ->
                    _uiState.update { it.copy(
                        isSyncing = false, 
                        syncMessage = if (silent) null else "Library synced: $count books found"
                    ) }
                    refreshCounts()
                },
                onFailure = { e ->
                    _uiState.update { it.copy(
                        isSyncing = false, 
                        error = if (silent) null else e.message
                    ) }
                }
            )
        }
    }

    fun clearSyncMessage() {
        _uiState.update { it.copy(syncMessage = null) }
    }

    fun setSortOption(option: SortOption) {
        _uiState.update { it.copy(sortOption = option) }
    }

    fun toggleSortDirection() {
        _uiState.update { it.copy(isAscending = !it.isAscending) }
    }

    fun toggleDownloadedOnly() {
        _uiState.update { 
            val newValue = !it.showDownloadedOnly
            it.copy(
                showDownloadedOnly = newValue,
                syncMessage = if (newValue) "Showing downloaded books only" else "Showing all books"
            )
        }
    }

    fun showSelectionInfo() {
        _uiState.update { it.copy(syncMessage = "Long press a book to select multiple items for bulk operations.") }
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
        updateSelectionFlags()
    }

    fun selectAll(names: List<String>) {
        _uiState.update { it.copy(selectedGranthas = names.toSet(), isSelectionMode = true) }
        updateSelectionFlags()
    }

    fun clearSelection() {
        _uiState.update { it.copy(selectedGranthas = emptySet(), isSelectionMode = false) }
        updateSelectionFlags()
    }

    private fun updateSelectionFlags() {
        viewModelScope.launch {
            val selectedNames = _uiState.value.selectedGranthas
            if (selectedNames.isEmpty()) {
                _uiState.update { it.copy(anyDownloadedSelected = false, anyNotDownloadedSelected = false) }
                return@launch
            }
            val all = repository.getAllGranthasOnce()
            val selected = all.filter { it.name in selectedNames }
            _uiState.update { 
                it.copy(
                    anyDownloadedSelected = selected.any { g -> g.isDownloaded },
                    anyNotDownloadedSelected = selected.any { g -> !g.isDownloaded }
                )
            }
        }
    }

    fun downloadSelected() {
        viewModelScope.launch {
            val selectedNames = _uiState.value.selectedGranthas
            val all = repository.getAllGranthasOnce()
            val toDownload = all.filter { it.name in selectedNames && !it.isDownloaded }.map { it.name }
            prepareBulkDownload(toDownload)
            clearSelection()
        }
    }

    fun deleteSelected() {
        viewModelScope.launch {
            val selectedNames = _uiState.value.selectedGranthas
            val all = repository.getAllGranthasOnce()
            val toDelete = all.filter { it.name in selectedNames && it.isDownloaded }.map { it.name }
            toDelete.forEach { repository.deleteGrantha(it) }
            refreshCounts()
            clearSelection()
        }
    }

    fun prepareBulkDownload(names: List<String>) {
        if (names.isEmpty()) return
        viewModelScope.launch {
            val size = calculateTotalSizeMb(names)
            _bulkDownloadConfirm.value = BulkDownloadState(names, size)
        }
    }

    fun dismissBulkDownload() {
        _bulkDownloadConfirm.value = null
    }

    fun downloadMultiple(names: List<String>) {
        if (names.isEmpty()) return
        
        viewModelScope.launch {
            val context = getApplication<Application>()
            val intent = Intent(context, DownloadService::class.java).apply {
                action = DownloadService.ACTION_DOWNLOAD
                putStringArrayListExtra(DownloadService.EXTRA_GRANTHA_NAMES, ArrayList(names))
            }
            context.startForegroundService(intent)
            dismissBulkDownload()
        }
    }

    suspend fun calculateTotalSizeMb(names: List<String>): String {
        val all = repository.getAllGranthasOnce()
        val target = all.filter { it.name in names }
        val totalBytes = target.sumOf { it.sizeBytes }
        return "%.2f".format(totalBytes / (1024.0 * 1024.0))
    }

    fun downloadSingle(name: String) {
        viewModelScope.launch {
            repository.resetCancel()
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
            val all = repository.getAllGranthasOnce()
            val names = all.filter { !it.isDownloaded }.map { it.name }
            prepareBulkDownload(names)
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

    fun runHealthCheck() {
        viewModelScope.launch {
            _uiState.update { it.copy(isSyncing = true, isHealthChecking = true, syncMessage = "Running health check...") }
            val result = repository.libraryHealthCheck()
            result.fold(
                onSuccess = { health ->
                    val msg = if (health.totalFixed == 0) {
                        "Health check complete. No issues found."
                    } else {
                        "Health check complete. Fixed ${health.missingFilesFixed} missing entries and removed ${health.orphanedFilesDeleted} orphaned files."
                    }
                    _uiState.update { it.copy(isSyncing = false, isHealthChecking = false, syncMessage = msg) }
                    syncCatalog(silent = true)
                },
                onFailure = { e ->
                    _uiState.update { it.copy(isSyncing = false, isHealthChecking = false, error = "Health check failed: ${e.message}") }
                }
            )
        }
    }

    fun backupLibrary() {
        viewModelScope.launch {
            _uiState.update { it.copy(isSyncing = true, isBackingUp = true, syncMessage = "Backing up...") }
            val result = repository.backupLibrary()
            result.fold(
                onSuccess = { _ ->
                    _uiState.update { it.copy(isSyncing = false, isBackingUp = false, syncMessage = "Backup successful to SDL_Backup folder") }
                    syncCatalog(silent = true)
                },
                onFailure = { e ->
                    _uiState.update { it.copy(isSyncing = false, isBackingUp = false, error = "Backup failed: ${e.message}") }
                }
            )
        }
    }

    fun restoreLibrary() {
        viewModelScope.launch {
            _uiState.update { it.copy(isSyncing = true, isRestoring = true, syncMessage = "Restoring...") }
            val result = repository.restoreLibrary()
            result.fold(
                onSuccess = { count ->
                    _uiState.update { it.copy(isSyncing = false, isRestoring = false, syncMessage = "Restore successful. $count books recovered.") }
                    syncCatalog(silent = true)
                },
                onFailure = { e ->
                    _uiState.update { it.copy(isSyncing = false, isRestoring = false, error = "Restore failed: ${e.message}") }
                }
            )
        }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    fun setError(error: String) {
        _uiState.update { it.copy(error = error) }
    }

    private fun refreshCounts() {
        // Now handled reactively by init flows
    }
}
