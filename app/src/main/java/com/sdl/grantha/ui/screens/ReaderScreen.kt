package com.sdl.grantha.ui.screens

import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.TextSnippet
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sdl.grantha.ui.viewmodels.ReaderViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(
    granthaName: String,
    startPage: Int = 1,
    highlightQuery: String? = null,
    onBack: () -> Unit = {},
    viewModel: ReaderViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var showPageImage by remember { mutableStateOf(false) }
    var goToPageText by remember { mutableStateOf("") }
    var showGoToDialog by remember { mutableStateOf(false) }

    // Load grantha on first composition
    LaunchedEffect(granthaName, startPage, highlightQuery) {
        viewModel.loadGrantha(granthaName, startPage, highlightQuery)
    }

    // Default to Archive view for non-downloaded books
    LaunchedEffect(uiState.grantha) {
        if (uiState.grantha != null && !uiState.grantha!!.isDownloaded) {
            showPageImage = true
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            uiState.granthaName,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (uiState.currentSubBook != null) {
                            Text(
                                "📖 ${uiState.currentSubBook}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.tertiary
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    // Toggle page image view (only if downloaded OCR text is available)
                    if (uiState.grantha?.isDownloaded == true) {
                        IconButton(onClick = { showPageImage = !showPageImage }) {
                            Icon(
                                if (showPageImage) Icons.AutoMirrored.Filled.TextSnippet else Icons.Filled.Image,
                                contentDescription = if (showPageImage) "Show text" else "Show page image"
                            )
                        }
                    }
                    // Go to page
                    IconButton(onClick = { showGoToDialog = true }) {
                        Icon(Icons.Filled.Pin, contentDescription = "Go to page")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        bottomBar = {
            // Page navigation bar (only in text mode)
            if (!uiState.isLoading && !showPageImage) {
                BottomAppBar(
                    containerColor = MaterialTheme.colorScheme.surface,
                    tonalElevation = 4.dp
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val maxPage = if (uiState.grantha?.isDownloaded == true) uiState.totalPages else 2000

                        IconButton(
                            onClick = { viewModel.previousPage() },
                            enabled = uiState.currentPage > 1
                        ) {
                            Icon(Icons.Filled.ChevronLeft, contentDescription = "Previous page")
                        }

                        Text(
                            if (uiState.grantha?.isDownloaded == true) 
                                "Page ${uiState.currentPage} / ${uiState.totalPages}"
                            else
                                "Page ${uiState.currentPage}",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Medium
                        )

                        IconButton(
                            onClick = { viewModel.nextPage() },
                            enabled = uiState.currentPage < maxPage
                        ) {
                            Icon(Icons.Filled.ChevronRight, contentDescription = "Next page")
                        }
                    }
                }
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when {
                uiState.isLoading -> {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                }
                uiState.error != null -> {
                    Column(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            Icons.Filled.Error,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.error
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            uiState.error ?: "Unknown error",
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                }
                showPageImage -> {
                    // Archive.org page image via WebView
                    val readerUrl = viewModel.getArchiveReaderUrl(uiState.currentPage)
                    if (readerUrl.isNotBlank()) {
                        AndroidView(
                            modifier = Modifier.fillMaxSize(),
                            factory = { context ->
                                WebView(context).apply {
                                    webViewClient = WebViewClient()
                                    settings.javaScriptEnabled = true
                                    settings.loadWithOverviewMode = true
                                    settings.useWideViewPort = true
                                    settings.builtInZoomControls = true
                                    settings.displayZoomControls = false
                                    loadUrl(readerUrl)
                                }
                            },
                            update = { webView ->
                                webView.loadUrl(readerUrl)
                            }
                        )
                    } else {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("Page image not available (no archive.org identifier)")
                        }
                    }
                }
                else -> {
                    // Text content
                    val currentPageContent = uiState.pages.find { it.pageNumber == uiState.currentPage }

                    if (currentPageContent != null) {
                        val text = currentPageContent.text
                        val highlightQuery = uiState.highlightQuery
                        
                        val annotatedString = remember(text, highlightQuery) {
                            androidx.compose.ui.text.buildAnnotatedString {
                                append(text)
                                if (!highlightQuery.isNullOrBlank()) {
                                    val queries = highlightQuery.split(",").map { it.trim() }.filter { it.isNotBlank() }
                                    val noisePattern = "[\\s\\-]*"
                                    
                                    queries.forEach { q ->
                                        // Smart regex that ignores spaces and hyphens between characters
                                        val termClean = q.replace(Regex("[\\s\\-]"), "")
                                        if (termClean.isEmpty()) return@forEach
                                        
                                        val regexString = buildString {
                                            for (i in termClean.indices) {
                                                append(Regex.escape(termClean[i].toString()))
                                                if (i < termClean.length - 1) append(noisePattern)
                                            }
                                        }
                                        
                                        try {
                                            val regex = Regex(regexString, RegexOption.IGNORE_CASE)
                                            regex.findAll(text).forEach { match ->
                                                addStyle(
                                                    style = androidx.compose.ui.text.SpanStyle(
                                                        background = androidx.compose.ui.graphics.Color.Yellow.copy(alpha = 0.4f),
                                                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                                                    ),
                                                    start = match.range.first,
                                                    end = match.range.last + 1
                                                )
                                            }
                                        } catch (_: Exception) {}
                                    }
                                }
                            }
                        }

                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState())
                                .padding(16.dp)
                        ) {
                            Text(
                                text = annotatedString,
                                style = MaterialTheme.typography.bodyLarge
                            )
                        }
                    } else {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("Page ${uiState.currentPage} not found")
                        }
                    }
                }
            }
        }
    }

    // Go to page dialog
    if (showGoToDialog) {
        AlertDialog(
            onDismissRequest = { showGoToDialog = false },
            title = { Text("Go to Page") },
            text = {
                OutlinedTextField(
                    value = goToPageText,
                    onValueChange = { goToPageText = it.filter { ch -> ch.isDigit() } },
                    label = { 
                        Text(
                            if (uiState.grantha?.isDownloaded == true) 
                                "Page number (1-${uiState.totalPages})" 
                            else 
                                "Page number"
                        ) 
                    },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    goToPageText.toIntOrNull()?.let { viewModel.goToPage(it) }
                    showGoToDialog = false
                    goToPageText = ""
                }) {
                    Text("Go")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showGoToDialog = false
                    goToPageText = ""
                }) {
                    Text("Cancel")
                }
            }
        )
    }
}
