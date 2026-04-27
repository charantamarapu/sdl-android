package com.sdl.grantha.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.net.Uri
import android.content.Intent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.sdl.grantha.BuildConfig
import com.sdl.grantha.ui.viewmodels.LibraryViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: LibraryViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var isManager by remember { 
        mutableStateOf(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Environment.isExternalStorageManager()
            } else true
        )
    }

    // Update permission status immediately when returning to the screen
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        isManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else true
    }

    Scaffold(
        // TopAppBar removed as per user request
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = padding.calculateBottomPadding())
                .statusBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Server info
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "Server URL",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Filled.Cloud,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            BuildConfig.SERVER_URL,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }

            // Storage Management
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                )
            ) {
                var showDeleteDialog by remember { mutableStateOf(false) }
                
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "Storage & Maintenance",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    // Permissions
                    Button(
                        onClick = {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                                    data = Uri.parse("package:${context.packageName}")
                                }
                                context.startActivity(intent)
                            } else {
                                // For older versions, open app settings
                                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                    data = Uri.parse("package:${context.packageName}")
                                }
                                context.startActivity(intent)
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isManager) Color(0xFF2E7D32) else MaterialTheme.colorScheme.error,
                            contentColor = Color.White
                        )
                    ) {
                        Icon(
                            if (isManager) Icons.Filled.VerifiedUser else Icons.Filled.Security, 
                            contentDescription = null
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(if (isManager) "Storage Permission: Granted" else "Grant Storage Permission")
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    HorizontalDivider()
                    Spacer(modifier = Modifier.height(12.dp))

                    // Health Check
                    Button(
                        onClick = { viewModel.runHealthCheck() },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !uiState.isSyncing
                    ) {
                        if (uiState.isHealthChecking) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                        } else {
                            Icon(Icons.Filled.HealthAndSafety, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Library Health Check")
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(8.dp))

                    // Backup
                    var showBackupDialog by remember { mutableStateOf(false) }
                    Button(
                        onClick = { showBackupDialog = true },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !uiState.isSyncing && isManager
                    ) {
                        if (uiState.isBackingUp) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                        } else {
                            Icon(Icons.Filled.Backup, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Backup to Downloads")
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(8.dp))

                    // Restore
                    var showRestoreDialog by remember { mutableStateOf(false) }
                    Button(
                        onClick = { showRestoreDialog = true },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !uiState.isSyncing && isManager
                    ) {
                        if (uiState.isRestoring) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                        } else {
                            Icon(Icons.Filled.SettingsBackupRestore, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Restore from Downloads")
                        }
                    }

                    if (!isManager) {
                        Text(
                            "⚠️ Storage Permission is required for Backup and Restore features.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }

                    if (showBackupDialog) {
                        AlertDialog(
                            onDismissRequest = { showBackupDialog = false },
                            title = { Text("Backup Library?") },
                            text = { Text("This will copy your downloaded books and database to the 'SDL_Backup' folder in your Downloads. Existing backups will be overwritten.") },
                            confirmButton = {
                                TextButton(onClick = {
                                    viewModel.backupLibrary()
                                    showBackupDialog = false
                                }) { Text("Backup") }
                            },
                            dismissButton = {
                                TextButton(onClick = { showBackupDialog = false }) { Text("Cancel") }
                            }
                        )
                    }

                    if (showRestoreDialog) {
                        AlertDialog(
                            onDismissRequest = { showRestoreDialog = false },
                            title = { Text("Restore Library?") },
                            text = { Text("This will restore books from the 'SDL_Backup' folder in your Downloads. Local files will be overwritten by backup copies.") },
                            confirmButton = {
                                TextButton(onClick = {
                                    viewModel.restoreLibrary()
                                    showRestoreDialog = false
                                }) { Text("Restore") }
                            },
                            dismissButton = {
                                TextButton(onClick = { showRestoreDialog = false }) { Text("Cancel") }
                            }
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                    HorizontalDivider()
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    // Delete All
                    Button(
                        onClick = { showDeleteDialog = true },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Filled.DeleteSweep, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Delete All Downloaded Books")
                    }
                    
                    Text(
                        "Metadata and search index will remain. You can re-download books later.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }

                if (showDeleteDialog) {
                    AlertDialog(
                        onDismissRequest = { showDeleteDialog = false },
                        title = { Text("Delete All Downloads?") },
                        text = { Text("Are you sure you want to remove all downloaded granthas from your device? You will need to download them again for offline access.") },
                        confirmButton = {
                            TextButton(
                                onClick = {
                                    viewModel.deleteAllDownloads()
                                    showDeleteDialog = false
                                },
                                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                            ) {
                                Text("Delete All")
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { showDeleteDialog = false }) {
                                Text("Cancel")
                            }
                        }
                    )
                }
            }

            // Sync status message
            uiState.syncMessage?.let { message ->
                Snackbar(
                    modifier = Modifier.padding(top = 8.dp),
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
                    modifier = Modifier.padding(top = 8.dp),
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    action = {
                        TextButton(onClick = { viewModel.clearError() }) {
                            Text("Dismiss", color = MaterialTheme.colorScheme.error)
                        }
                    }
                ) {
                    Text(error)
                }
            }

            // About
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "About",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        "SDL",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        "Sanskrit Digital Library",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "Version ${BuildConfig.VERSION_NAME}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        "An offline Sanskrit text search application. Download granthas from the server and search them locally on your device.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    HorizontalDivider()
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        "Features:",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    val features = listOf(
                        "📚 Browse & download Sanskrit granthas",
                        "🔍 Search text offline with exact & fuzzy matching",
                        "📖 Read granthas with page images from Archive.org",
                        "🔒 Encrypted storage for downloaded texts",
                        "🏷️ Tag-based filtering with AND/OR logic",
                        "📝 Custom Sanskrit normalization rules"
                    )
                    for (feature in features) {
                        Text(
                            feature,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(vertical = 2.dp)
                        )
                    }
                }
            }
        }
    }
}
