package com.sdl.grantha.ui.screens

import androidx.compose.animation.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sdl.grantha.ui.components.DownloadProgressBar
import com.sdl.grantha.ui.components.SearchResultCard
import com.sdl.grantha.ui.viewmodels.SearchViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    onNavigateToReader: (String, Int) -> Unit = { _, _ -> },
    viewModel: SearchViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var showAdvanced by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "Search ग्रन्थs",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Search input
            OutlinedTextField(
                value = uiState.textQuery,
                onValueChange = { viewModel.setTextQuery(it) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                placeholder = { Text("Enter Sanskrit text to search...") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = {
                    Row {
                        if (uiState.textQuery.isNotBlank()) {
                            IconButton(onClick = { viewModel.setTextQuery("") }) {
                                Icon(Icons.Filled.Clear, contentDescription = "Clear")
                            }
                        }
                        // Search button
                        IconButton(
                            onClick = { viewModel.search() },
                            enabled = !uiState.isSearching
                        ) {
                            Icon(
                                Icons.Filled.PlayArrow,
                                contentDescription = "Search",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                },
                singleLine = true,
                shape = MaterialTheme.shapes.medium
            )

            // Advanced options toggle
            TextButton(
                onClick = { showAdvanced = !showAdvanced },
                modifier = Modifier.padding(horizontal = 16.dp)
            ) {
                Icon(
                    if (showAdvanced) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text("Advanced Options")
            }

            // Advanced options panel
            AnimatedVisibility(visible = showAdvanced) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp)
                    ) {
                        // Tag filter
                        OutlinedTextField(
                            value = uiState.tagsQuery,
                            onValueChange = { viewModel.setTagsQuery(it) },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Filter by tags") },
                            placeholder = { Text("e.g., वेदान्तः, रामानुजः") },
                            singleLine = true
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        // Tag chips from downloaded granthas
                        if (uiState.availableTags.isNotEmpty()) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                for (tag in uiState.availableTags.take(20)) {
                                    FilterChip(
                                        selected = tag in uiState.selectedTags,
                                        onClick = { viewModel.toggleTag(tag) },
                                        label = { Text(tag, style = MaterialTheme.typography.labelSmall) }
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        // Logic toggles
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("Text Logic", style = MaterialTheme.typography.labelSmall)
                                Row {
                                    FilterChip(
                                        selected = uiState.textLogic == "or",
                                        onClick = { viewModel.setTextLogic("or") },
                                        label = { Text("OR") }
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    FilterChip(
                                        selected = uiState.textLogic == "and",
                                        onClick = { viewModel.setTextLogic("and") },
                                        label = { Text("AND") }
                                    )
                                }
                            }

                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("Tag Logic", style = MaterialTheme.typography.labelSmall)
                                Row {
                                    FilterChip(
                                        selected = uiState.tagsLogic == "or",
                                        onClick = { viewModel.setTagsLogic("or") },
                                        label = { Text("OR") }
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    FilterChip(
                                        selected = uiState.tagsLogic == "and",
                                        onClick = { viewModel.setTagsLogic("and") },
                                        label = { Text("AND") }
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        // Fuzzy search slider
                        Text(
                            "Fuzzy Tolerance: ${uiState.fuzzyPct}%",
                            style = MaterialTheme.typography.labelMedium
                        )
                        Slider(
                            value = uiState.fuzzyPct.toFloat(),
                            onValueChange = { viewModel.setFuzzyPct(it.toInt()) },
                            valueRange = 0f..50f,
                            steps = 9
                        )

                        // Custom Sanskrit rules
                        OutlinedTextField(
                            value = uiState.customRulesText,
                            onValueChange = { viewModel.setCustomRulesText(it) },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Custom Rules (A=B format)") },
                            placeholder = { Text("ः=ो\nं=ँ") },
                            minLines = 2,
                            maxLines = 3
                        )
                    }
                }
            }

            // Search progress
            if (uiState.isSearching) {
                DownloadProgressBar(
                    progress = uiState.searchProgress,
                    label = "Searching: ${uiState.currentBook}"
                )
            }

            // Error
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

            // Results
            if (uiState.hasSearched && !uiState.isSearching) {
                // Results header
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "${uiState.results.size} results found",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Medium
                        )
                        TextButton(onClick = { viewModel.clearResults() }) {
                            Text("Clear")
                        }
                    }
                }
            }

            // Results list
            if (uiState.results.isNotEmpty()) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(
                        items = uiState.results,
                        key = { "${it.granthaName}_${it.page}_${it.contextText.hashCode()}" }
                    ) { result ->
                        SearchResultCard(
                            result = result,
                            onClick = { onNavigateToReader(result.granthaName, result.page) }
                        )
                    }
                }
            } else if (uiState.hasSearched && !uiState.isSearching) {
                // No results
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Filled.SearchOff,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            "No results found",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            "Try different search terms or download more granthas",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else if (!uiState.isSearching && !uiState.hasSearched) {
                // Initial state
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("🔍", style = MaterialTheme.typography.displayLarge)
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            "Search downloaded granthas",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            "All searching happens locally on your device",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}
