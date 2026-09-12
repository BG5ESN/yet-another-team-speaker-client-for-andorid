package dev.tsdroid.ui.component

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.tsdroid.han.R
import dev.tsdroid.model.ShareTarget
import dev.tsdroid.model.TsFileEntry
import dev.tslib.Channel
import dev.tslib.User
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileManagerDialog(
    currentPath: String,
    files: List<TsFileEntry>,
    isLoading: Boolean,
    users: List<User>,
    permissionHints: Long = 0L,
    onNavigateToFolder: (String) -> Unit,
    onNavigateUp: () -> Unit,
    onRefresh: () -> Unit,
    onDownload: (String) -> Unit,
    onDelete: (String) -> Unit,
    onRename: (String, String) -> Unit,
    onCreateDirectory: (String) -> Unit,
    onUploadFile: (String, ByteArray) -> Unit,
    onShareFile: (ShareTarget, String, Long) -> Unit,
    onDismiss: () -> Unit,
    onPreviewImage: (String) -> Unit = {},
) {
    // Permission flags — write operations require explicit permission bits.
    // When permissionHints == 0 (server didn't send hints), hide write buttons.
    // Download/browse are always allowed (CommandError toast handles denials).
    val canUpload = (permissionHints and Channel.PERM_FILE_UPLOAD) != 0L
    val canDelete = (permissionHints and Channel.PERM_FILE_DELETE) != 0L
    val canRename = (permissionHints and Channel.PERM_FILE_RENAME) != 0L
    val canCreateDir = (permissionHints and Channel.PERM_FILE_DIRECTORY_CREATE) != 0L
    val hasHints = permissionHints != 0L
    val canDownload = !hasHints || (permissionHints and Channel.PERM_FILE_DOWNLOAD) != 0L
    var showNewFolderDialog by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<String?>(null) }
    var deleteTarget by remember { mutableStateOf<String?>(null) }
    var shareTarget by remember { mutableStateOf<Pair<String, Long>?>(null) }

    val context = LocalContext.current
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        try {
            val cursor = context.contentResolver.query(uri, null, null, null, null)
            val nameIndex = cursor?.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME) ?: -1
            cursor?.moveToFirst()
            val fileName = if (nameIndex >= 0) cursor?.getString(nameIndex) ?: "file" else "file"
            cursor?.close()
            val data = context.contentResolver.openInputStream(uri)?.readBytes()
                ?: return@rememberLauncherForActivityResult
            if (data.size > 10_485_760) return@rememberLauncherForActivityResult
            onUploadFile(fileName, data)
        } catch (_: Exception) {}
    }

    // Sort: folders first, then files, alphabetically
    val sortedFiles = remember(files) {
        files.sortedWith(compareBy<TsFileEntry> { it.isFile }.thenBy { it.name.lowercase() })
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        tonalElevation = 3.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .navigationBarsPadding(),
        ) {
                // Top bar
                TopAppBar(
                    title = {
                        Text(
                            currentPath,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.titleSmall,
                        )
                    },
                    navigationIcon = {
                        if (currentPath != "/") {
                            IconButton(onClick = onNavigateUp) {
                                Icon(
                                    Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = null,
                                )
                            }
                        }
                    },
                    actions = {
                        if (canCreateDir) {
                            IconButton(onClick = { showNewFolderDialog = true }) {
                                Icon(Icons.Default.CreateNewFolder, contentDescription = stringResource(R.string.new_folder))
                            }
                        }
                        IconButton(onClick = onRefresh) {
                            Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.refresh))
                        }
                        if (canUpload) {
                            IconButton(onClick = { filePickerLauncher.launch("*/*") }) {
                                Icon(Icons.Default.Upload, contentDescription = stringResource(R.string.upload))
                            }
                        }
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.close))
                        }
                    },
                )

                // Content
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                ) {
                    when {
                        isLoading -> {
                            CircularProgressIndicator(
                                modifier = Modifier.align(Alignment.Center),
                            )
                        }
                        sortedFiles.isEmpty() -> {
                            Text(
                                text = stringResource(R.string.no_files),
                                modifier = Modifier.align(Alignment.Center),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        else -> {
                            LazyColumn(modifier = Modifier.fillMaxSize()) {
                                items(sortedFiles, key = { it.name }) { entry ->
                                    FileEntryRow(
                                        entry = entry,
                                        canDownload = canDownload,
                                        canRename = canRename,
                                        canDelete = canDelete,
                                        onFolderClick = { onNavigateToFolder(entry.name) },
                                        onDownload = { onDownload(entry.name) },
                                        onPreviewImage = { onPreviewImage(entry.name) },
                                        onShare = { shareTarget = entry.name to entry.size },
                                        onRename = { renameTarget = entry.name },
                                        onDelete = { deleteTarget = entry.name },
                                    )
                                }
                            }
                        }
                    }
                }

            }
        }
    // (end of Surface) — dialogs below are still inside the function

    // New folder dialog
    if (showNewFolderDialog) {
        InputDialog(
            title = stringResource(R.string.new_folder),
            label = stringResource(R.string.enter_folder_name),
            confirmText = stringResource(R.string.create),
            onConfirm = { name ->
                if (name.isNotBlank()) onCreateDirectory(name)
                showNewFolderDialog = false
            },
            onDismiss = { showNewFolderDialog = false },
        )
    }

    // Rename dialog
    renameTarget?.let { oldName ->
        InputDialog(
            title = stringResource(R.string.rename),
            label = stringResource(R.string.enter_new_name),
            initialValue = oldName,
            confirmText = stringResource(R.string.rename),
            onConfirm = { newName ->
                if (newName.isNotBlank() && newName != oldName) onRename(oldName, newName)
                renameTarget = null
            },
            onDismiss = { renameTarget = null },
        )
    }

    // Delete confirmation dialog
    deleteTarget?.let { name ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(stringResource(R.string.delete_file)) },
            text = { Text(stringResource(R.string.confirm_delete, name)) },
            confirmButton = {
                TextButton(onClick = {
                    onDelete(name)
                    deleteTarget = null
                }) {
                    Text(stringResource(R.string.delete_file))
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    // Share target selection dialog
    shareTarget?.let { (fileName, fileSize) ->
        ShareTargetDialog(
            fileName = fileName,
            users = users,
            onSelect = { target ->
                onShareFile(target, fileName, fileSize)
                shareTarget = null
            },
            onDismiss = { shareTarget = null },
        )
    }
}

@Composable
private fun ShareTargetDialog(
    fileName: String,
    users: List<User>,
    onSelect: (ShareTarget) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.share_file_title, fileName)) },
        text = {
            LazyColumn {
                // Channel option
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(ShareTarget.Channel) }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Default.Folder,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp),
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            stringResource(R.string.share_in_channel),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }
                // Users for PM
                items(users.filter { it.clientType.toInt() == 0 }, key = { it.id }) { user ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                onSelect(ShareTarget.PrivateMessage(user.id, user.nickname))
                            }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Default.Person,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(24.dp),
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            user.nickname,
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

@Composable
private fun FileEntryRow(
    entry: TsFileEntry,
    canDownload: Boolean = true,
    canRename: Boolean = true,
    canDelete: Boolean = true,
    onFolderClick: () -> Unit,
    onDownload: () -> Unit,
    onPreviewImage: () -> Unit,
    onShare: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    var showMenu by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                if (!entry.isFile) onFolderClick()
                else if (isImageFile(entry.name)) onPreviewImage()
                else if (canDownload) onDownload()
            }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Icon
        val icon = when {
            !entry.isFile -> Icons.Default.Folder
            isImageFile(entry.name) -> Icons.Default.Image
            else -> Icons.Default.Description
        }
        val tint = if (!entry.isFile) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant
        Icon(
            icon,
            contentDescription = if (!entry.isFile) stringResource(R.string.folder) else null,
            tint = tint,
            modifier = Modifier.size(24.dp),
        )

        Spacer(Modifier.width(12.dp))

        // Name + details
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.name,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (entry.isFile) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = formatFileSize(entry.size),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = formatTimestamp(entry.datetime),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        // Menu button
        Box {
            IconButton(onClick = { showMenu = true }) {
                Icon(
                    Icons.Default.MoreVert,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
            }
            DropdownMenu(
                expanded = showMenu,
                onDismissRequest = { showMenu = false },
            ) {
                if (entry.isFile && canDownload) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.download)) },
                        onClick = { showMenu = false; onDownload() },
                        leadingIcon = { Icon(Icons.Default.Download, null) },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.share_in_channel)) },
                        onClick = { showMenu = false; onShare() },
                        leadingIcon = { Icon(Icons.Default.Share, null) },
                    )
                }
                if (canRename) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.rename)) },
                        onClick = { showMenu = false; onRename() },
                        leadingIcon = { Icon(Icons.Default.DriveFileRenameOutline, null) },
                    )
                }
                if (canDelete) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.delete_file)) },
                        onClick = { showMenu = false; onDelete() },
                        leadingIcon = {
                            Icon(
                                Icons.Default.Delete, null,
                                tint = MaterialTheme.colorScheme.error,
                            )
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun InputDialog(
    title: String,
    label: String,
    initialValue: String = "",
    confirmText: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(initialValue) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text(label) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(text) },
                enabled = text.isNotBlank(),
            ) {
                Text(confirmText)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

private fun isImageFile(name: String): Boolean {
    val ext = name.substringAfterLast('.', "").lowercase()
    return ext in setOf("png", "jpg", "jpeg", "gif", "webp", "bmp")
}

private fun formatFileSize(bytes: Long): String {
    return when {
        bytes < 1024 -> "$bytes o"
        bytes < 1024 * 1024 -> "%.1f Ko".format(bytes / 1024.0)
        bytes < 1024 * 1024 * 1024 -> "%.1f Mo".format(bytes / (1024.0 * 1024.0))
        else -> "%.1f Go".format(bytes / (1024.0 * 1024.0 * 1024.0))
    }
}

private fun formatTimestamp(unixSeconds: Long): String {
    return try {
        val sdf = SimpleDateFormat("dd/MM/yy HH:mm", Locale.getDefault())
        sdf.format(Date(unixSeconds * 1000))
    } catch (_: Exception) {
        ""
    }
}
