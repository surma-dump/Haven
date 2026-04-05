package sh.haven.feature.sftp

import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Cable
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.FileCopy
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import kotlinx.coroutines.launch
import sh.haven.feature.sftp.SftpViewModel.Companion.isMediaFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun SftpScreen(
    pendingSmbProfileId: String? = null,
    pendingRcloneProfileId: String? = null,
    viewModel: SftpViewModel = hiltViewModel(),
) {
    val connectedProfiles by viewModel.connectedProfiles.collectAsState()
    val activeProfileId by viewModel.activeProfileId.collectAsState()
    val currentPath by viewModel.currentPath.collectAsState()
    val entries by viewModel.entries.collectAsState()
    val sortMode by viewModel.sortMode.collectAsState()
    val showHidden by viewModel.showHidden.collectAsState()
    val loading by viewModel.loading.collectAsState()
    val transferProgress by viewModel.transferProgress.collectAsState()
    val error by viewModel.error.collectAsState()
    val message by viewModel.message.collectAsState()
    val lastDownload by viewModel.lastDownload.collectAsState()
    val uploadConflict by viewModel.uploadConflict.collectAsState()
    val fileClipboard by viewModel.clipboard.collectAsState()
    val isRclone by viewModel.isRcloneProfile.collectAsState()
    val hasMediaFiles by viewModel.hasMediaFiles.collectAsState()
    val mediaExtensions by viewModel.mediaExtensionsSet.collectAsState()

    LaunchedEffect(pendingSmbProfileId) {
        pendingSmbProfileId?.let { viewModel.setPendingSmbProfile(it) }
    }

    LaunchedEffect(pendingRcloneProfileId) {
        pendingRcloneProfileId?.let { viewModel.setPendingRcloneProfile(it) }
    }

    viewModel.syncConnectedProfiles()

    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(error) {
        error?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.dismissError()
        }
    }

    LaunchedEffect(lastDownload) {
        val dl = lastDownload ?: return@LaunchedEffect
        viewModel.dismissMessage() // clear the plain message so it doesn't double-show

        // Auto-install APK files directly
        if (dl.fileName.endsWith(".apk", ignoreCase = true)) {
            try {
                val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                    setDataAndType(dl.uri, "application/vnd.android.package-archive")
                    addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            } catch (e: Exception) {
                @Suppress("LocalContextGetResourceValueCall")
                snackbarHostState.showSnackbar("Install failed: ${e.message}")
            }
            viewModel.clearLastDownload()
            return@LaunchedEffect
        }

        @Suppress("LocalContextGetResourceValueCall")
        val downloadedMessage = context.getString(R.string.sftp_downloaded, dl.fileName)
        @Suppress("LocalContextGetResourceValueCall")
        val openLabel = context.getString(R.string.sftp_open)
        val result = snackbarHostState.showSnackbar(
            message = downloadedMessage,
            actionLabel = openLabel,
            duration = androidx.compose.material3.SnackbarDuration.Long,
        )
        if (result == androidx.compose.material3.SnackbarResult.ActionPerformed) {
            try {
                val mimeType = context.contentResolver.getType(dl.uri) ?: "*/*"
                val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                    setDataAndType(dl.uri, mimeType)
                    addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(intent)
            } catch (e: Exception) {
                @Suppress("LocalContextGetResourceValueCall")
                snackbarHostState.showSnackbar(context.getString(R.string.sftp_no_app_to_open))
            }
        }
        viewModel.clearLastDownload()
    }

    LaunchedEffect(message) {
        // Only show plain messages when there's no download result (download has its own snackbar)
        val msg = message ?: return@LaunchedEffect
        if (lastDownload == null) {
            snackbarHostState.showSnackbar(msg)
        }
        viewModel.dismissMessage()
    }

    // File picker for upload
    val uploadLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            // Query the actual display name from the content resolver
            val fileName = context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (cursor.moveToFirst() && nameIndex >= 0) cursor.getString(nameIndex) else null
            } ?: uri.lastPathSegment?.substringAfterLast('/') ?: "upload"
            viewModel.uploadFile(fileName, uri)
        }
    }

    // New folder dialog state
    var showNewFolderDialog by remember { mutableStateOf(false) }

    // Folder upload picker
    val folderUploadLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            viewModel.uploadFolder(uri)
        }
    }

    // Directory picker for download
    var pendingDownload by remember { mutableStateOf<SftpEntry?>(null) }
    val downloadLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("*/*")
    ) { uri ->
        if (uri != null) {
            pendingDownload?.let { entry ->
                viewModel.downloadFile(entry, uri)
            }
        }
        pendingDownload = null
    }

    val clipboardManager = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var showSortMenu by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        currentPath,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    if (currentPath != "/" && activeProfileId != null) {
                        IconButton(onClick = { viewModel.navigateUp() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.sftp_navigate_up))
                        }
                    }
                },
                actions = {
                    if (activeProfileId != null) {
                        IconButton(onClick = { viewModel.toggleShowHidden() }) {
                            Icon(
                                if (showHidden) Icons.Filled.Visibility else Icons.Filled.VisibilityOff,
                                contentDescription = if (showHidden) stringResource(R.string.sftp_hide_hidden_files) else stringResource(R.string.sftp_show_hidden_files),
                            )
                        }
                        Box {
                            IconButton(onClick = { showSortMenu = true }) {
                                Icon(Icons.AutoMirrored.Filled.Sort, stringResource(R.string.sftp_sort))
                            }
                            SortDropdown(
                                expanded = showSortMenu,
                                currentMode = sortMode,
                                onDismiss = { showSortMenu = false },
                                onSelect = { mode ->
                                    viewModel.setSortMode(mode)
                                    showSortMenu = false
                                },
                            )
                        }
                        IconButton(onClick = { viewModel.refresh() }) {
                            Icon(Icons.Filled.Refresh, stringResource(R.string.sftp_refresh))
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            if (activeProfileId != null) {
                var fabExpanded by remember { mutableStateOf(false) }
                Column(horizontalAlignment = Alignment.End) {
                    AnimatedVisibility(visible = fabExpanded) {
                        Column(
                            horizontalAlignment = Alignment.End,
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.padding(bottom = 8.dp),
                        ) {
                            SmallFloatingActionButton(onClick = {
                                fabExpanded = false
                                showNewFolderDialog = true
                            }) {
                                Icon(Icons.Filled.CreateNewFolder, stringResource(R.string.sftp_new_folder))
                            }
                            SmallFloatingActionButton(onClick = {
                                fabExpanded = false
                                folderUploadLauncher.launch(null)
                            }) {
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier.size(24.dp),
                                ) {
                                    Icon(
                                        Icons.Filled.Folder,
                                        stringResource(R.string.sftp_upload_folder),
                                        modifier = Modifier.size(24.dp),
                                    )
                                    Icon(
                                        Icons.Filled.ArrowUpward,
                                        contentDescription = null,
                                        modifier = Modifier
                                            .size(14.dp)
                                            .padding(top = 2.dp),
                                        tint = MaterialTheme.colorScheme.primaryContainer,
                                    )
                                }
                            }
                            SmallFloatingActionButton(onClick = {
                                fabExpanded = false
                                uploadLauncher.launch(arrayOf("*/*"))
                            }) {
                                Icon(Icons.Filled.Upload, stringResource(R.string.sftp_upload_file))
                            }
                            if (hasMediaFiles) {
                                SmallFloatingActionButton(onClick = {
                                    fabExpanded = false
                                    viewModel.playFolder()
                                }) {
                                    Icon(Icons.Filled.PlayArrow, stringResource(R.string.sftp_play_folder))
                                }
                            }
                        }
                    }
                    if (fileClipboard != null) {
                        FloatingActionButton(onClick = { viewModel.pasteFromClipboard() }) {
                            Icon(Icons.Filled.ContentPaste, stringResource(R.string.sftp_paste))
                        }
                    } else {
                        FloatingActionButton(onClick = { fabExpanded = !fabExpanded }) {
                            Icon(
                                when {
                                    fabExpanded -> Icons.Filled.CreateNewFolder
                                    hasMediaFiles -> Icons.Filled.PlayArrow
                                    else -> Icons.Filled.Upload
                                },
                                if (fabExpanded) stringResource(R.string.sftp_fab_close) else stringResource(R.string.sftp_fab_actions),
                            )
                        }
                    }
                }
            }
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            if (loading) {
                val progress = transferProgress
                if (progress != null && (progress.totalBytes > 0 || progress.fileName.isNotEmpty())) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        if (progress.totalBytes > 0) {
                            LinearProgressIndicator(
                                progress = { progress.fraction },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        } else {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 2.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                progress.fileName,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            if (progress.totalBytes > 0) {
                                Text(
                                    "${Formatter.formatFileSize(context, progress.transferredBytes)} / ${Formatter.formatFileSize(context, progress.totalBytes)}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }

            if (connectedProfiles.isEmpty()) {
                EmptyState()
            } else {
                // Server tabs
                if (connectedProfiles.size > 1) {
                    val activeIndex = connectedProfiles.indexOfFirst { it.id == activeProfileId }
                        .coerceAtLeast(0)
                    PrimaryScrollableTabRow(
                        selectedTabIndex = activeIndex,
                        modifier = Modifier.fillMaxWidth(),
                        edgePadding = 8.dp,
                    ) {
                        connectedProfiles.forEach { profile ->
                            Tab(
                                selected = profile.id == activeProfileId,
                                onClick = { viewModel.selectProfile(profile.id) },
                                text = {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    ) {
                                        if (profile.isRclone) {
                                            Icon(
                                                Icons.Filled.Cloud,
                                                contentDescription = null,
                                                modifier = Modifier.size(14.dp),
                                            )
                                        }
                                        Text(profile.label, maxLines = 1)
                                    }
                                },
                            )
                        }
                    }
                }

                // Clipboard banner
                fileClipboard?.let { cb ->
                    Surface(tonalElevation = 2.dp) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                if (cb.isCut) Icons.Filled.ContentCut else Icons.Filled.FileCopy,
                                null,
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                if (cb.isCut) {
                                    stringResource(
                                        if (cb.entries.size > 1) R.string.sftp_items_cut_plural else R.string.sftp_items_cut,
                                        cb.entries.size,
                                    )
                                } else {
                                    stringResource(
                                        if (cb.entries.size > 1) R.string.sftp_items_copied_plural else R.string.sftp_items_copied,
                                        cb.entries.size,
                                    )
                                },
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = { viewModel.clearClipboard() }) {
                                Text(stringResource(R.string.common_cancel))
                            }
                        }
                    }
                }

                // File list
                if (entries.isEmpty() && !loading) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            stringResource(R.string.sftp_empty_directory),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        // ".." parent directory entry
                        if (currentPath != "/" && currentPath.isNotEmpty()) {
                            item(key = "__parent__") {
                                ListItem(
                                    headlineContent = { Text("..") },
                                    supportingContent = { Text(stringResource(R.string.sftp_parent_directory)) },
                                    leadingContent = {
                                        Icon(
                                            Icons.AutoMirrored.Filled.ArrowBack,
                                            contentDescription = stringResource(R.string.sftp_navigate_up_icon),
                                            tint = MaterialTheme.colorScheme.primary,
                                        )
                                    },
                                    modifier = Modifier.clickable { viewModel.navigateUp() },
                                )
                            }
                        }
                        itemsIndexed(entries, key = { index, entry -> "${activeProfileId}:${index}:${entry.path}" }) { _, entry ->
                            FileListItem(
                                entry = entry,
                                onTap = {
                                    if (entry.isDirectory) {
                                        viewModel.navigateTo(entry.path)
                                    }
                                },
                                onDownload = {
                                    pendingDownload = entry
                                    downloadLauncher.launch(entry.name)
                                },
                                onDelete = { viewModel.deleteEntry(entry) },
                                onCopyPath = {
                                    clipboardManager.setText(AnnotatedString(entry.path))
                                    @Suppress("LocalContextGetResourceValueCall")
                                    val pathCopiedMsg = context.getString(R.string.sftp_path_copied)
                                    scope.launch {
                                        snackbarHostState.showSnackbar(pathCopiedMsg)
                                    }
                                },
                                onCopy = { viewModel.copyToClipboard(listOf(entry), isCut = false) },
                                onCut = { viewModel.copyToClipboard(listOf(entry), isCut = true) },
                                onPlay = if (isRclone && entry.isMediaFile(mediaExtensions)) {
                                    { viewModel.playMediaFile(entry) }
                                } else null,
                            )
                        }
                    }
                }
            }
        }
    }

    // New Folder dialog
    if (showNewFolderDialog) {
        var folderName by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showNewFolderDialog = false },
            title = { Text(stringResource(R.string.sftp_new_folder_title)) },
            text = {
                OutlinedTextField(
                    value = folderName,
                    onValueChange = { folderName = it },
                    label = { Text(stringResource(R.string.sftp_folder_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showNewFolderDialog = false
                        viewModel.createDirectory(folderName)
                    },
                    enabled = folderName.isNotBlank(),
                ) { Text(stringResource(R.string.sftp_create)) }
            },
            dismissButton = {
                TextButton(onClick = { showNewFolderDialog = false }) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }

    // Upload conflict dialog
    uploadConflict?.let { conflict ->
        AlertDialog(
            onDismissRequest = { viewModel.resolveConflict(SftpViewModel.ConflictChoice.SKIP) },
            title = { Text(stringResource(R.string.sftp_file_already_exists)) },
            text = { Text(stringResource(R.string.sftp_file_exists_message, conflict.fileName)) },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { viewModel.resolveConflict(SftpViewModel.ConflictChoice.REPLACE) }) {
                        Text(stringResource(R.string.sftp_replace))
                    }
                    TextButton(onClick = { viewModel.resolveConflict(SftpViewModel.ConflictChoice.REPLACE_ALL) }) {
                        Text(stringResource(R.string.sftp_replace_all))
                    }
                }
            },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { viewModel.resolveConflict(SftpViewModel.ConflictChoice.SKIP) }) {
                        Text(stringResource(R.string.sftp_skip))
                    }
                    TextButton(onClick = { viewModel.resolveConflict(SftpViewModel.ConflictChoice.SKIP_ALL) }) {
                        Text(stringResource(R.string.sftp_skip_all))
                    }
                }
            },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileListItem(
    entry: SftpEntry,
    onTap: () -> Unit,
    onDownload: () -> Unit,
    onDelete: () -> Unit,
    onCopyPath: () -> Unit,
    onCopy: () -> Unit = {},
    onCut: () -> Unit = {},
    onPlay: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    var showMenu by remember { mutableStateOf(false) }

    val dateFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }

    Box {
        ListItem(
            headlineContent = { Text(entry.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            supportingContent = {
                @Suppress("LocalContextGetResourceValueCall")
                val sizeText = if (entry.isDirectory) context.getString(R.string.sftp_directory) else Formatter.formatFileSize(context, entry.size)
                val dateText = dateFormat.format(Date(entry.modifiedTime * 1000))
                Text("$sizeText  $dateText")
            },
            leadingContent = {
                Icon(
                    if (entry.isDirectory) Icons.Filled.Folder else Icons.Filled.Description,
                    contentDescription = stringResource(if (entry.isDirectory) R.string.sftp_directory_icon else R.string.sftp_file_icon),
                    tint = if (entry.isDirectory) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            },
            modifier = Modifier.combinedClickable(
                onClick = onTap,
                onLongClick = { showMenu = true },
            ),
        )

        DropdownMenu(
            expanded = showMenu,
            onDismissRequest = { showMenu = false },
        ) {
            if (onPlay != null) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.sftp_play)) },
                    leadingIcon = { Icon(Icons.Filled.PlayArrow, null) },
                    onClick = { showMenu = false; onPlay() },
                )
            }
            if (!entry.isDirectory) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.sftp_download)) },
                    leadingIcon = { Icon(Icons.Filled.Download, null) },
                    onClick = { showMenu = false; onDownload() },
                )
            }
            DropdownMenuItem(
                text = { Text(stringResource(R.string.common_copy)) },
                leadingIcon = { Icon(Icons.Filled.FileCopy, null) },
                onClick = { showMenu = false; onCopy() },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.sftp_cut)) },
                leadingIcon = { Icon(Icons.Filled.ContentCut, null) },
                onClick = { showMenu = false; onCut() },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.sftp_copy_path)) },
                leadingIcon = { Icon(Icons.Filled.ContentCopy, null) },
                onClick = { showMenu = false; onCopyPath() },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.common_delete)) },
                leadingIcon = { Icon(Icons.Filled.Delete, null) },
                onClick = { showMenu = false; onDelete() },
            )
        }
    }
}

@Composable
private fun SortDropdown(
    expanded: Boolean,
    currentMode: SortMode,
    onDismiss: () -> Unit,
    onSelect: (SortMode) -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        SortMode.entries.forEach { mode ->
            val label = when (mode) {
                SortMode.NAME_ASC -> stringResource(R.string.sftp_sort_name_asc)
                SortMode.NAME_DESC -> stringResource(R.string.sftp_sort_name_desc)
                SortMode.SIZE_ASC -> stringResource(R.string.sftp_sort_size_asc)
                SortMode.SIZE_DESC -> stringResource(R.string.sftp_sort_size_desc)
                SortMode.DATE_ASC -> stringResource(R.string.sftp_sort_date_asc)
                SortMode.DATE_DESC -> stringResource(R.string.sftp_sort_date_desc)
            }
            DropdownMenuItem(
                text = { Text(label) },
                leadingIcon = {
                    RadioButton(
                        selected = mode == currentMode,
                        onClick = null,
                    )
                },
                onClick = { onSelect(mode) },
            )
        }
    }
}

@Composable
private fun EmptyState() {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            Icons.Filled.Folder,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            stringResource(R.string.sftp_file_browser_title),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 16.dp),
        )
        Text(
            stringResource(R.string.sftp_connect_to_browse),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}
