package com.sdl.grantha.ui.screens

import androidx.compose.animation.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sdl.grantha.ui.components.DownloadProgressBar
import com.sdl.grantha.ui.components.GranthaCard
import com.sdl.grantha.ui.components.SearchResultCard
import com.sdl.grantha.ui.viewmodels.SearchViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    onNavigateToReader: (String, Int, String?) -> Unit = { _, _, _ -> },
    viewModel: SearchViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    
    var isAnyFieldFocused by remember { mutableStateOf(false) }
    var showTextSuggestions by remember { mutableStateOf(false) }
    
    // Ensure no focus on start
    LaunchedEffect(Unit) {
        focusManager.clearFocus()
    }
    Scaffold(
        // TopAppBar removed as per user request
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = padding.calculateBottomPadding())
        ) {
            item {
                Column(modifier = Modifier.statusBarsPadding()) {
            // Premium Search Header (Web-like)
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surface,
                shadowElevation = 2.dp
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp)
                ) {
                    // Search Mode Tabs
                    TabRow(
                        selectedTabIndex = if (uiState.isAdvancedMode) 1 else 0,
                        containerColor = MaterialTheme.colorScheme.surface,
                        contentColor = MaterialTheme.colorScheme.primary,
                        divider = {},
                        indicator = { tabPositions ->
                            TabRowDefaults.SecondaryIndicator(
                                Modifier.tabIndicatorOffset(tabPositions[if (uiState.isAdvancedMode) 1 else 0]),
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    ) {
                        Tab(
                            selected = !uiState.isAdvancedMode,
                            onClick = { viewModel.setAdvancedMode(false) },
                            text = { Text("Basic Search", fontWeight = if (!uiState.isAdvancedMode) FontWeight.Bold else FontWeight.Normal) }
                        )
                        Tab(
                            selected = uiState.isAdvancedMode,
                            onClick = { viewModel.setAdvancedMode(true) },
                            text = { Text("Advanced Search", fontWeight = if (uiState.isAdvancedMode) FontWeight.Bold else FontWeight.Normal) }
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Styled Search Input
                    
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                    ) {
                        OutlinedTextField(
                            value = uiState.textQuery,
                            onValueChange = { 
                                viewModel.setTextQuery(it)
                                if (!uiState.isAdvancedMode) {
                                    viewModel.updateSuggestions(it, "text")
                                    showTextSuggestions = it.length >= 2
                                } else {
                                    showTextSuggestions = false
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .onFocusChanged { focusState ->
                                    isAnyFieldFocused = focusState.isFocused
                                    if (focusState.isFocused && !uiState.isOptionsExpanded) {
                                        viewModel.toggleOptionsExpanded()
                                    }
                                    if (!focusState.isFocused) showTextSuggestions = false
                                },
                            placeholder = { 
                                Text(if (uiState.isAdvancedMode) "Search inside books..." else "Search books...") 
                            },
                            // ... leadingIcon, trailingIcon, etc ...
                            leadingIcon = { 
                                Icon(
                                    Icons.Filled.Search, 
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary
                                ) 
                            },
                            trailingIcon = {
                                if (uiState.isSearching) {
                                    IconButton(onClick = { viewModel.stopSearch() }) {
                                        Icon(
                                            Icons.Filled.Stop, 
                                            contentDescription = "Stop search",
                                            tint = MaterialTheme.colorScheme.error
                                        )
                                    }
                                } else if (uiState.textQuery.isNotBlank()) {
                                    IconButton(onClick = { 
                                        viewModel.setTextQuery("")
                                        showTextSuggestions = false
                                    }) {
                                        Icon(Icons.Filled.Close, contentDescription = "Clear")
                                    }
                                }
                            },
                            singleLine = true,
                            shape = MaterialTheme.shapes.large,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = MaterialTheme.colorScheme.primary,
                                unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                                focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f),
                                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f)
                            )
                        )
                    }

                    // Suggestions List (Inline instead of Popup to avoid keyboard overlap)
                    if (showTextSuggestions && uiState.suggestions.isNotEmpty() && !uiState.isAdvancedMode) {
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 4.dp),
                            color = MaterialTheme.colorScheme.surface,
                            tonalElevation = 1.dp,
                            shape = MaterialTheme.shapes.medium,
                            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                        ) {
                            Column {
                                uiState.suggestions.forEach { suggestion ->
                                    ListItem(
                                        headlineContent = { Text(suggestion.value) },
                                        leadingContent = {
                                            Icon(
                                                if (suggestion.type == SearchViewModel.SuggestionType.BOOK) Icons.Filled.Book else Icons.AutoMirrored.Filled.Label,
                                                contentDescription = null,
                                                modifier = Modifier.size(18.dp),
                                                tint = MaterialTheme.colorScheme.primary
                                            )
                                        },
                                        modifier = Modifier.clickable {
                                            focusManager.clearFocus()
                                            viewModel.selectSuggestion(suggestion, "text")
                                            showTextSuggestions = false
                                        }
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Search Button (Full width, premium look)
                    AnimatedVisibility(
                        visible = isAnyFieldFocused && !uiState.isSearching && (uiState.textQuery.isNotBlank() || uiState.tagsQuery.isNotBlank())
                    ) {
                        Button(
                            onClick = { 
                                focusManager.clearFocus()
                                viewModel.search(uiState.isAdvancedMode) 
                            },
                            enabled = (uiState.textQuery.isNotBlank() || uiState.tagsQuery.isNotBlank()),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp)
                                .height(50.dp),
                            shape = MaterialTheme.shapes.medium,
                            elevation = ButtonDefaults.buttonElevation(defaultElevation = 2.dp)
                        ) {
                            Icon(Icons.Filled.Search, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Search", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            // Header for Advanced Options with Expand/Collapse toggle
            if (uiState.isAdvancedMode) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { viewModel.toggleOptionsExpanded() }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        "Advanced Options",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Icon(
                        if (uiState.isOptionsExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = if (uiState.isOptionsExpanded) "Collapse" else "Expand",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }

            // Advanced options panel
            AnimatedVisibility(
                visible = uiState.isAdvancedMode && uiState.isOptionsExpanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
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
                        // Tag filter (Include)
                        var showTagSuggestions by remember { mutableStateOf(false) }
                        Column(modifier = Modifier.fillMaxWidth()) {
                            OutlinedTextField(
                                value = uiState.tagsQuery,
                                onValueChange = { 
                                    viewModel.setTagsQuery(it) 
                                    viewModel.updateSuggestions(it, "tags")
                                    showTagSuggestions = it.length >= 2
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .onFocusChanged { isAnyFieldFocused = it.isFocused },
                                label = { Text("Include Tags") },
                                placeholder = { Text("e.g., वेदान्तः, रामानुजः") },
                                singleLine = true
                            )
                            
                            if (showTagSuggestions && uiState.suggestions.isNotEmpty()) {
                                Surface(
                                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                                    color = MaterialTheme.colorScheme.surface,
                                    tonalElevation = 1.dp,
                                    shape = MaterialTheme.shapes.small,
                                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                                ) {
                                    Column {
                                        uiState.suggestions.filter { it.type == SearchViewModel.SuggestionType.TAG }.forEach { suggestion ->
                                            ListItem(
                                                headlineContent = { Text(suggestion.value, style = MaterialTheme.typography.bodySmall) },
                                                modifier = Modifier.clickable {
                                                    viewModel.selectSuggestion(suggestion, "tags")
                                                    showTagSuggestions = false
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        // Tag filter (Exclude)
                        var showNegativeSuggestions by remember { mutableStateOf(false) }
                        Column(modifier = Modifier.fillMaxWidth()) {
                            OutlinedTextField(
                                value = uiState.negativeTagsQuery,
                                onValueChange = { 
                                    viewModel.setNegativeTagsQuery(it) 
                                    viewModel.updateSuggestions(it, "negative")
                                    showNegativeSuggestions = it.length >= 2
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .onFocusChanged { isAnyFieldFocused = it.isFocused },
                                label = { Text("Exclude Tags") },
                                placeholder = { Text("e.g., न्यायः") },
                                singleLine = true,
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = MaterialTheme.colorScheme.error,
                                    focusedLabelColor = MaterialTheme.colorScheme.error
                                )
                            )

                            if (showNegativeSuggestions && uiState.suggestions.isNotEmpty()) {
                                Surface(
                                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                                    color = MaterialTheme.colorScheme.surface,
                                    tonalElevation = 1.dp,
                                    shape = MaterialTheme.shapes.small,
                                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                                ) {
                                    Column {
                                        uiState.suggestions.filter { it.type == SearchViewModel.SuggestionType.TAG }.forEach { suggestion ->
                                            ListItem(
                                                headlineContent = { Text(suggestion.value, style = MaterialTheme.typography.bodySmall) },
                                                modifier = Modifier.clickable {
                                                    viewModel.selectSuggestion(suggestion, "negative")
                                                    showNegativeSuggestions = false
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        Spacer(modifier = Modifier.height(8.dp))

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
            if (uiState.isSearching && uiState.isAdvancedMode) {
                Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    DownloadProgressBar(
                        progress = uiState.searchProgress,
                        label = "Searching: ${uiState.currentBook}"
                    )
                }
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

            // Results header
            if (uiState.hasSearched && !uiState.isSearching) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                ) {
                    val count = if (uiState.isAdvancedMode) uiState.results.size else uiState.basicResults.size
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "$count ${if (uiState.isAdvancedMode) "matches" else "books"} found",
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
                }
            }

            // Results List
            if (uiState.isAdvancedMode) {
                if (uiState.selectedBookResults != null) {
                    // Show all snippets for the selected book
                    item {
                        ListItem(
                            headlineContent = { Text(uiState.selectedBookResults!!, fontWeight = FontWeight.Bold) },
                            leadingContent = { 
                                IconButton(onClick = { viewModel.deselectBook() }) {
                                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to books")
                                }
                            },
                            trailingContent = { 
                                Text(
                                    "${uiState.bookSnippets.size} results",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary
                                ) 
                            },
                            colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.1f))
                        )
                        HorizontalDivider(thickness = 0.5.dp)
                    }
                    
                    if (uiState.isDeepSearching) {
                        item {
                            Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator()
                            }
                        }
                    } else if (uiState.bookSnippets.isNotEmpty()) {
                        items(
                            items = uiState.bookSnippets,
                            key = { "snippet_${it.granthaName}_${it.page}_${it.contextText.hashCode()}" }
                        ) { result ->
                            Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                                SearchResultCard(
                                    result = result,
                                    onClick = { onNavigateToReader(result.granthaName, result.page, uiState.textQuery) }
                                )
                            }
                        }
                    } else if (!uiState.isDeepSearching) {
                        item {
                            NoResultsView("No matches found inside this book")
                        }
                    }
                } else if (uiState.results.isNotEmpty()) {
                    // Show the list of books found (each result here is the first match in a book)
                    items(
                        items = uiState.results,
                        key = { "book_${it.granthaName}" }
                    ) { result ->
                        Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                            SearchResultCard(
                                result = result,
                                onClick = { viewModel.selectBookForDeepSearch(result.granthaName) }
                            )
                        }
                    }
                } else if (uiState.hasSearched && !uiState.isSearching) {
                    item {
                        NoResultsView("No matches found inside books")
                    }
                }
            } else {
                if (uiState.basicResults.isNotEmpty()) {
                    items(
                        items = uiState.basicResults,
                        key = { it.name }
                    ) { grantha ->
                        Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                            GranthaCard(
                                grantha = grantha,
                                onClick = { onNavigateToReader(grantha.name, 1, uiState.textQuery) },
                                onDownloadClick = { viewModel.downloadGrantha(grantha.name) },
                                onDeleteClick = { viewModel.deleteGrantha(grantha.name) }
                            )
                        }
                    }
                } else if (uiState.hasSearched && !uiState.isSearching) {
                    item {
                        NoResultsView("No books found matching your query")
                    }
                }
            }
        }
    }
}

@Composable
private fun NoResultsView(message: String) {
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
            Text(message, style = MaterialTheme.typography.titleMedium)
            Text(
                "Try different search terms",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
