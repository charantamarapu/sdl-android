package com.sdl.grantha.ui.screens

import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.LibraryBooks
import androidx.compose.material.icons.automirrored.outlined.LibraryBooks
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupProperties
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sdl.grantha.ui.components.DownloadProgressBar
import com.sdl.grantha.ui.components.GranthaCard
import com.sdl.grantha.ui.theme.*
import com.sdl.grantha.ui.viewmodels.LibraryViewModel
import java.util.*
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    onNavigateToReader: (String, Int) -> Unit = { _, _ -> },
    viewModel: LibraryViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val granthas by viewModel.granthas.collectAsStateWithLifecycle()
    val downloadProgress by viewModel.downloadProgress.collectAsStateWithLifecycle(initialValue = null)
    val bulkProgress by viewModel.bulkProgress.collectAsStateWithLifecycle(initialValue = null)


    // Show download errors
    LaunchedEffect(downloadProgress?.error) {
        downloadProgress?.error?.let { error ->
            viewModel.setError(error)
        }
    }

    Scaffold(
        topBar = {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding(),
                tonalElevation = 2.dp,
                shadowElevation = 2.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "Sanskrit Digital Library",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            "${uiState.downloadedCount}/${uiState.totalCount} books • ${uiState.downloadedSizeMb} MB",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    
                    Row {
                        // Info action
                        IconButton(onClick = { viewModel.showSelectionInfo() }) {
                            Icon(Icons.Filled.Info, contentDescription = "Selection Info")
                        }
                        // Toggle downloaded only
                        IconButton(onClick = { viewModel.toggleDownloadedOnly() }) {
                            Icon(
                                if (uiState.showDownloadedOnly) Icons.Filled.FilterList else Icons.Outlined.FilterList,
                                contentDescription = "Filter downloaded",
                                tint = if (uiState.showDownloadedOnly) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        // Sync catalog
                        IconButton(onClick = { viewModel.syncCatalog() }) {
                            if (uiState.isSyncing) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Icon(
                                    Icons.Filled.Sync, 
                                    contentDescription = "Sync catalog",
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                }
            }
        },
        floatingActionButton = {
            val isDownloading = (bulkProgress != null && !bulkProgress!!.isComplete) ||
                    (downloadProgress != null && !downloadProgress!!.isComplete && downloadProgress!!.error == null)
            
            val visibleNotDownloaded = granthas.filter { !it.isDownloaded }.map { it.name }
            val allNotDownloadedCount = uiState.totalCount - uiState.downloadedCount

            val isFiltered = uiState.searchQuery.isNotBlank() || uiState.showDownloadedOnly
            
            if (isDownloading) {
                ExtendedFloatingActionButton(
                    onClick = { viewModel.cancelDownloads() },
                    icon = { Icon(Icons.Filled.Stop, contentDescription = null) },
                    text = { Text("Stop Download") },
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer
                )
            } else if (uiState.isSelectionMode) {
                // Actions are in the top toolbar now
            } else if (isFiltered) {
                // Only show download button for the filtered results if there's something to download
                if (visibleNotDownloaded.isNotEmpty()) {
                    ExtendedFloatingActionButton(
                        onClick = { viewModel.prepareBulkDownload(visibleNotDownloaded) },
                        icon = { Icon(Icons.Filled.CloudDownload, contentDescription = null) },
                        text = { Text("Download ${visibleNotDownloaded.size} Filtered") },
                        containerColor = MaterialTheme.colorScheme.primary
                    )
                }
            } else if (allNotDownloadedCount > 0) {
                // Only show "Download All" when not filtering
                ExtendedFloatingActionButton(
                    onClick = { viewModel.downloadAll() },
                    icon = { Icon(Icons.Filled.CloudDownload, contentDescription = null) },
                    text = { Text("Download All ($allNotDownloadedCount)") },
                    containerColor = MaterialTheme.colorScheme.primary
                )
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Search bar & Suggestions
            var showSuggestions by remember { mutableStateOf(false) }
            val suggestions by viewModel.suggestions.collectAsStateWithLifecycle()

            // Reactive expansion: Show suggestions only when there is a query and matching results
            LaunchedEffect(suggestions, uiState.searchQuery) {
                showSuggestions = uiState.searchQuery.isNotBlank() && suggestions.isNotEmpty()
            }

            Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                OutlinedTextField(
                    value = uiState.searchQuery,
                    onValueChange = { 
                        viewModel.setSearchQuery(it)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Search by bookname or tag...") },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    trailingIcon = {
                        if (uiState.searchQuery.isNotBlank()) {
                            IconButton(onClick = { 
                                viewModel.setSearchQuery("") 
                                showSuggestions = false
                            }) {
                                Icon(Icons.Filled.Clear, contentDescription = "Clear")
                            }
                        }
                    },
                    singleLine = true,
                    shape = MaterialTheme.shapes.medium
                )

                DropdownMenu(
                    expanded = showSuggestions,
                    onDismissRequest = { showSuggestions = false },
                    properties = PopupProperties(focusable = false),
                    modifier = Modifier.fillMaxWidth(0.85f)
                ) {
                    suggestions.forEach { tag ->
                        DropdownMenuItem(
                            text = { Text(tag) },
                            onClick = {
                                viewModel.setSearchQuery(tag)
                                showSuggestions = false
                            }
                        )
                    }
                }
            }

            // Sort Row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.Start,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Sort Menu
                var showSortMenu by remember { mutableStateOf(false) }
                Box(modifier = Modifier.wrapContentSize()) {
                    FilterChip(
                        selected = true,
                        onClick = { showSortMenu = true },
                        label = { Text("Sort: ${uiState.sortOption.name.lowercase().replaceFirstChar { it.uppercase() }}") },
                        trailingIcon = { Icon(Icons.Filled.ArrowDropDown, null) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primary,
                            selectedLabelColor = Color.White,
                            selectedTrailingIconColor = Color.White
                        )
                    )
                    DropdownMenu(expanded = showSortMenu, onDismissRequest = { showSortMenu = false }) {
                        LibraryViewModel.SortOption.entries.forEach { option ->
                            DropdownMenuItem(
                                text = { Text(option.name.lowercase().replace("_", " ").replaceFirstChar { it.uppercase() }) },
                                onClick = {
                                    viewModel.setSortOption(option)
                                    showSortMenu = false
                                }
                            )
                        }
                    }
                }

                // Ascending/Descending Toggle
                IconButton(onClick = { viewModel.toggleSortDirection() }) {
                    Icon(
                        if (uiState.isAscending) Icons.Filled.South else Icons.Filled.North,
                        contentDescription = if (uiState.isAscending) "Descending" else "Ascending",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }

                // Selection count at the absolute right
                if (uiState.selectedGranthas.isNotEmpty()) {
                    Spacer(modifier = Modifier.weight(1f))
                    Text(
                        "${uiState.selectedGranthas.size} selected",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // Selection mode toolbar
            AnimatedVisibility(visible = uiState.isSelectionMode) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row {
                            if (uiState.anyNotDownloadedSelected) {
                                TextButton(onClick = { viewModel.downloadSelected() }) {
                                    Text("Download")
                                }
                            }
                            if (uiState.anyDownloadedSelected) {
                                var showDeleteConfirm by remember { mutableStateOf(false) }
                                TextButton(
                                    onClick = { showDeleteConfirm = true },
                                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                                ) {
                                    Text("Delete")
                                }

                                if (showDeleteConfirm) {
                                    AlertDialog(
                                        onDismissRequest = { showDeleteConfirm = false },
                                        title = { Text("Delete Selected?") },
                                        text = { Text("Are you sure you want to delete the selected downloaded books?") },
                                        confirmButton = {
                                            TextButton(
                                                onClick = {
                                                    viewModel.deleteSelected()
                                                    showDeleteConfirm = false
                                                },
                                                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                                            ) { Text("Delete") }
                                        },
                                        dismissButton = {
                                            TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancel") }
                                        }
                                    )
                                }
                            }
                            TextButton(onClick = {
                                viewModel.selectAll(granthas.map { it.name })
                            }) {
                                Text("Select All")
                            }
                            TextButton(onClick = { viewModel.clearSelection() }) {
                                Text("Cancel")
                            }
                        }
                    }
                }
            }

            // Download progress
            bulkProgress?.let { progress ->
                if (!progress.isComplete) {
                    DownloadProgressBar(
                        progress = progress.overallProgress,
                        label = "Downloading: ${progress.currentGrantha} (${progress.currentIndex + 1}/${progress.totalCount})"
                    )
                }
            }

            downloadProgress?.let { progress ->
                if (!progress.isComplete && progress.error == null) {
                    val p = if (progress.totalBytes > 0) progress.bytesDownloaded.toFloat() / progress.totalBytes else 0f
                    DownloadProgressBar(
                        progress = p,
                        label = progress.granthaName
                    )
                }
            }

            // Sync status message
            uiState.syncMessage?.let { message ->
                Snackbar(
                    modifier = Modifier.padding(16.dp),
                    action = {
                        TextButton(onClick = { viewModel.clearSyncMessage() }) {
                            Text("OK")
                        }
                    }
                ) {
                    Text(message)
                }
            }

            // Error message
            uiState.error?.let { error ->
                Snackbar(
                    modifier = Modifier.padding(16.dp),
                    action = {
                        TextButton(onClick = { viewModel.clearError() }) {
                            Text("Dismiss")
                        }
                    }
                ) {
                    Text(error)
                }
            }

            // Loading state
            if (uiState.isSyncing && granthas.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Spacer(modifier = Modifier.height(16.dp))
                        Text("Loading catalog from server...")
                    }
                }
            } else if (granthas.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.AutoMirrored.Outlined.LibraryBooks,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            "No granthas found",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(onClick = { viewModel.syncCatalog() }) {
                            Text("Sync from Server")
                        }
                    }
                }
            } else {
                    // Grantha list
                    var bookToDelete by remember { mutableStateOf<String?>(null) }
                    
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(
                            items = granthas,
                            key = { it.name }
                        ) { grantha ->
                            GranthaCard(
                                grantha = grantha,
                                isSelected = grantha.name in uiState.selectedGranthas,
                                isSelectionMode = uiState.isSelectionMode,
                                onClick = {
                                    if (uiState.isSelectionMode) {
                                        viewModel.toggleSelection(grantha.name)
                                    } else {
                                        onNavigateToReader(grantha.name, 1)
                                    }
                                },
                                onLongClick = { viewModel.toggleSelection(grantha.name) },
                                onDownloadClick = { viewModel.downloadSingle(grantha.name) },
                                onDeleteClick = { bookToDelete = grantha.name }
                            )
                        }

                        // Bottom spacer for FAB
                        item { Spacer(modifier = Modifier.height(80.dp)) }
                    }

                    if (bookToDelete != null) {
                        AlertDialog(
                            onDismissRequest = { bookToDelete = null },
                            title = { Text("Delete Book?") },
                            text = { Text("Are you sure you want to delete '${bookToDelete}'? You will need to download it again for offline access.") },
                            confirmButton = {
                                TextButton(
                                    onClick = {
                                        bookToDelete?.let { viewModel.deleteGrantha(it) }
                                        bookToDelete = null
                                    },
                                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                                ) {
                                    Text("Delete")
                                }
                            },
                            dismissButton = {
                                TextButton(onClick = { bookToDelete = null }) {
                                    Text("Cancel")
                                }
                            }
                        )
                    }

                    // Bulk Download Confirmation Dialog
                    val bulkConfirm by viewModel.bulkDownloadConfirm.collectAsStateWithLifecycle()
                    if (bulkConfirm != null) {
                        AlertDialog(
                            onDismissRequest = { viewModel.dismissBulkDownload() },
                            title = { Text("Download Books") },
                            text = { Text("You are about to download ${bulkConfirm!!.names.size} books.\n\nEstimated Size: ${bulkConfirm!!.totalSizeMb} MB\n\nContinue?") },
                            confirmButton = {
                                TextButton(onClick = { viewModel.downloadMultiple(bulkConfirm!!.names) }) {
                                    Text("Download")
                                }
                            },
                            dismissButton = {
                                TextButton(onClick = { viewModel.dismissBulkDownload() }) {
                                    Text("Cancel")
                                }
                            }
                        )
                    }
            }
        }
    }
}
