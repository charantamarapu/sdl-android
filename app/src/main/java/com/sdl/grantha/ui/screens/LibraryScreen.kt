package com.sdl.grantha.ui.screens

import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sdl.grantha.ui.components.DownloadProgressBar
import com.sdl.grantha.ui.components.GranthaCard
import com.sdl.grantha.ui.theme.*
import com.sdl.grantha.ui.viewmodels.LibraryViewModel

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

    // Initial catalog sync
    LaunchedEffect(Unit) {
        if (granthas.isEmpty()) {
            viewModel.syncCatalog()
        }
    }

    // Show download errors
    LaunchedEffect(downloadProgress?.error) {
        downloadProgress?.error?.let { error ->
            viewModel.setError(error)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            "SDL",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            "${uiState.downloadedCount}/${uiState.totalCount} downloaded • ${uiState.downloadedSizeMb} MB",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                actions = {
                    // Toggle downloaded only
                    IconButton(onClick = { viewModel.toggleDownloadedOnly() }) {
                        Icon(
                            if (uiState.showDownloadedOnly) Icons.Filled.FilterAlt
                            else Icons.Outlined.FilterAlt,
                            contentDescription = "Filter downloaded",
                            tint = if (uiState.showDownloadedOnly) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    // Sync catalog
                    IconButton(onClick = { viewModel.syncCatalog() }) {
                        if (uiState.isSyncing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(Icons.Filled.Sync, contentDescription = "Sync catalog")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        floatingActionButton = {
            val isDownloading = (bulkProgress != null && !bulkProgress!!.isComplete) ||
                    (downloadProgress != null && !downloadProgress!!.isComplete && downloadProgress!!.error == null)
            
            if (isDownloading) {
                ExtendedFloatingActionButton(
                    onClick = { viewModel.cancelDownloads() },
                    icon = { Icon(Icons.Filled.Stop, contentDescription = null) },
                    text = { Text("Stop Download") },
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer
                )
            } else if (uiState.isSelectionMode) {
                ExtendedFloatingActionButton(
                    onClick = { viewModel.downloadSelected() },
                    icon = { Icon(Icons.Filled.Download, contentDescription = null) },
                    text = { Text("Download ${uiState.selectedGranthas.size}") },
                    containerColor = MaterialTheme.colorScheme.primary
                )
            } else {
                FloatingActionButton(
                    onClick = { viewModel.downloadAll() },
                    containerColor = MaterialTheme.colorScheme.primary
                ) {
                    Icon(Icons.Filled.CloudDownload, contentDescription = "Download All")
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Search bar
            OutlinedTextField(
                value = uiState.searchQuery,
                onValueChange = { viewModel.setSearchQuery(it) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                placeholder = { Text("Search granthas by name or tag...") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = {
                    if (uiState.searchQuery.isNotBlank()) {
                        IconButton(onClick = { viewModel.setSearchQuery("") }) {
                            Icon(Icons.Filled.Clear, contentDescription = "Clear")
                        }
                    }
                },
                singleLine = true,
                shape = MaterialTheme.shapes.medium
            )

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
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "${uiState.selectedGranthas.size} selected",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Medium
                        )
                        Row {
                            TextButton(onClick = {
                                viewModel.selectAll(granthas.filter { !it.isDownloaded }.map { it.name })
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
                            Icons.Outlined.LibraryBooks,
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
                                } else if (grantha.isDownloaded) {
                                    onNavigateToReader(grantha.name, 1)
                                }
                            },
                            onLongClick = { viewModel.toggleSelection(grantha.name) },
                            onDownloadClick = { viewModel.downloadSingle(grantha.name) },
                            onDeleteClick = { viewModel.deleteGrantha(grantha.name) }
                        )
                    }

                    // Bottom spacer for FAB
                    item { Spacer(modifier = Modifier.height(80.dp)) }
                }
            }
        }
    }
}
