package dev.tsdroid.ui.screen

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.tsdroid.han.R
import dev.tslib.ConnectionState
import dev.tsdroid.ui.component.ChannelTree
import dev.tsdroid.viewmodel.ConnectionViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectionScreen(
    onConnected: () -> Unit,
    onNavigateToAbout: () -> Unit,
    viewModel: ConnectionViewModel = viewModel(),
) {
    val address by viewModel.address.collectAsStateWithLifecycle()
    val nickname by viewModel.nickname.collectAsStateWithLifecycle()
    val password by viewModel.password.collectAsStateWithLifecycle()
    val channel by viewModel.channel.collectAsStateWithLifecycle()
    val bookmarks by viewModel.bookmarks.collectAsStateWithLifecycle()
    val bookmarkIcons by viewModel.bookmarkIcons.collectAsStateWithLifecycle()
    val autoReconnect by viewModel.autoReconnect.collectAsStateWithLifecycle()
    val editingIndex by viewModel.editingIndex.collectAsStateWithLifecycle()
    val connectionState by viewModel.connectionState.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val browsedChannels by viewModel.browsedChannels.collectAsStateWithLifecycle()
    val isBrowsing by viewModel.isBrowsing.collectAsStateWithLifecycle()
    val showChannelPicker by viewModel.showChannelPicker.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var deleteConfirmIndex by remember { mutableStateOf<Int?>(null) }
    var showBottomSheet by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    var selectedTab by remember { mutableIntStateOf(0) }

    val isConnecting = connectionState == ConnectionState.CONNECTING
    val context = LocalContext.current
    val settingsStore = remember { dev.tsdroid.data.SettingsStore(context) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { }

    LaunchedEffect(Unit) {
        val permissions = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        permissionLauncher.launch(permissions.toTypedArray())
        viewModel.resumeExistingConnection(onConnected)
    }

    LaunchedEffect(autoReconnect) {
        if (autoReconnect) {
            viewModel.tryAutoReconnect(onConnected)
        }
    }

    LaunchedEffect(error) {
        error?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearError()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            text = if (selectedTab == 0) stringResource(R.string.app_name)
                                   else stringResource(R.string.tab_settings)
                        )
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent,
                        scrolledContainerColor = Color.Transparent,
                    ),
                )
            },
            bottomBar = {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.6f)) {
                    NavigationBarItem(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        icon = { Icon(Icons.Default.Home, contentDescription = null) },
                        label = { Text(stringResource(R.string.tab_home)) },
                    )
                    NavigationBarItem(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                        label = { Text(stringResource(R.string.tab_settings)) },
                    )
                }
            },
            snackbarHost = { SnackbarHost(snackbarHostState) },
            floatingActionButton = {
                if (selectedTab == 0) {
                    FloatingActionButton(onClick = { showBottomSheet = true }) {
                        Icon(Icons.Default.Add, contentDescription = stringResource(R.string.manual_connection))
                    }
                }
            },
        ) { padding ->
            Box(modifier = Modifier.fillMaxSize().padding(padding)) {
                // Tab 0: Home
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer { alpha = if (selectedTab == 0) 1f else 0f }
                        .then(
                            if (selectedTab == 0) Modifier else Modifier.graphicsLayer {
                                translationY = -size.height
                            }
                        ),
                ) {
                    if (bookmarks.isEmpty()) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                stringResource(R.string.no_connection),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 16.dp)
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            bookmarks.forEachIndexed { index, bookmark ->
                                    Card(
                                        modifier = Modifier.fillMaxWidth(),
                                        colors = CardDefaults.cardColors(
                                            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.5f),
                                        ),
                                        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(12.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            val icon = if (bookmark.iconId != 0L) bookmarkIcons[bookmark.iconId] else null
                                            if (icon != null) {
                                                Image(
                                                    bitmap = icon,
                                                    contentDescription = null,
                                                    modifier = Modifier.size(32.dp),
                                                    contentScale = ContentScale.Fit,
                                                )
                                            } else {
                                                Icon(
                                                    Icons.Default.Star,
                                                    contentDescription = null,
                                                    modifier = Modifier.size(32.dp),
                                                    tint = MaterialTheme.colorScheme.primary,
                                                )
                                            }
                                            Spacer(Modifier.width(12.dp))
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    bookmark.serverName ?: bookmark.name,
                                                    style = MaterialTheme.typography.bodyLarge,
                                                )
                                                Text(
                                                    bookmark.address,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                            }
                                            FilledTonalButton(
                                                onClick = { viewModel.connectBookmark(bookmark, onConnected) },
                                                enabled = !isConnecting,
                                            ) {
                                                Text(stringResource(R.string.connect))
                                            }
                                            Box {
                                                var menuExpanded by remember { mutableStateOf(false) }
                                                IconButton(onClick = { menuExpanded = true }) {
                                                    Icon(Icons.Default.MoreVert, contentDescription = null)
                                                }
                                                DropdownMenu(
                                                    expanded = menuExpanded,
                                                    onDismissRequest = { menuExpanded = false },
                                                ) {
                                                    DropdownMenuItem(
                                                        text = { Text(stringResource(R.string.edit)) },
                                                        onClick = {
                                                            menuExpanded = false
                                                            viewModel.editBookmark(bookmark, index)
                                                            showBottomSheet = true
                                                        },
                                                        leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                                                    )
                                                    DropdownMenuItem(
                                                        text = { Text(stringResource(R.string.remove)) },
                                                        onClick = {
                                                            menuExpanded = false
                                                            deleteConfirmIndex = index
                                                        },
                                                        leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) },
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                            Spacer(Modifier.height(16.dp))
                        }
                    }

                // Tab 1: Settings (always in composition, hidden when not selected)
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer { alpha = if (selectedTab == 1) 1f else 0f }
                        .then(
                            if (selectedTab == 1) Modifier else Modifier.graphicsLayer {
                                translationY = size.height
                            }
                        ),
                ) {
                    SettingsPage(
                        onNavigateToAbout = onNavigateToAbout,
                        autoReconnect = autoReconnect,
                        onAutoReconnectChange = { viewModel.setAutoReconnect(it) },
                    )
                }
            }

            deleteConfirmIndex?.let { idx ->
                val bookmarkName = bookmarks.getOrNull(idx)?.let { it.serverName ?: it.name } ?: ""
                AlertDialog(
                    onDismissRequest = { deleteConfirmIndex = null },
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    title = { Text(stringResource(R.string.remove)) },
                    text = { Text(stringResource(R.string.confirm_delete, bookmarkName)) },
                    confirmButton = {
                        TextButton(onClick = {
                            viewModel.removeBookmark(idx)
                            deleteConfirmIndex = null
                        }) {
                            Text(stringResource(R.string.remove))
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { deleteConfirmIndex = null }) {
                            Text(stringResource(R.string.cancel))
                        }
                    },
                )
            }

            if (showChannelPicker) {
                Dialog(onDismissRequest = { viewModel.showChannelPicker.value = false }) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        shape = MaterialTheme.shapes.large,
                        tonalElevation = 0.dp,
                    ) {
                        Column(modifier = Modifier.padding(24.dp)) {
                            Text(
                                stringResource(R.string.select_channel),
                                style = MaterialTheme.typography.headlineSmall,
                            )
                            Spacer(Modifier.height(16.dp))
                            ChannelTree(
                                channels = browsedChannels,
                                users = emptyList(),
                                onChannelClick = { viewModel.selectChannel(it) },
                                modifier = Modifier.fillMaxWidth().height(400.dp),
                            )
                            Spacer(Modifier.height(16.dp))
                            TextButton(
                                onClick = { viewModel.showChannelPicker.value = false },
                                modifier = Modifier.align(Alignment.End),
                            ) {
                                Text(stringResource(R.string.cancel))
                            }
                        }
                    }
                }
            }

            if (showBottomSheet) {
                ModalBottomSheet(
                    onDismissRequest = {
                        showBottomSheet = false
                        viewModel.cancelEdit()
                    },
                    sheetState = sheetState,
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                ) {
                    val isEditing = editingIndex >= 0
                    val glassTextFieldColors = OutlinedTextFieldDefaults.colors(
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                    )
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp)
                            .navigationBarsPadding(),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            stringResource(if (isEditing) R.string.edit_bookmark else R.string.manual_connection),
                            style = MaterialTheme.typography.titleLarge,
                        )

                        OutlinedTextField(
                            value = address,
                            onValueChange = { viewModel.address.value = it },
                            label = { Text(stringResource(R.string.server_address)) },
                            placeholder = { Text(stringResource(R.string.server_address_placeholder)) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            maxLines = 1,
                            enabled = !isConnecting,
                            colors = glassTextFieldColors,
                        )

                        OutlinedTextField(
                            value = nickname,
                            onValueChange = { viewModel.nickname.value = it },
                            label = { Text(stringResource(R.string.nickname)) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            maxLines = 1,
                            enabled = !isConnecting,
                            colors = glassTextFieldColors,
                        )

                        OutlinedTextField(
                            value = password,
                            onValueChange = { viewModel.password.value = it },
                            label = { Text(stringResource(R.string.password_optional)) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            maxLines = 1,
                            enabled = !isConnecting,
                            visualTransformation = PasswordVisualTransformation(),
                            colors = glassTextFieldColors,
                        )

                        OutlinedTextField(
                            value = channel,
                            onValueChange = { viewModel.channel.value = it },
                            label = { Text(stringResource(R.string.channel_optional)) },
                            placeholder = { Text(stringResource(R.string.default_channel)) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            maxLines = 1,
                            enabled = !isConnecting,
                            trailingIcon = {
                                if (isBrowsing) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.height(24.dp).width(24.dp),
                                        strokeWidth = 2.dp,
                                    )
                                } else {
                                    IconButton(onClick = { viewModel.browseChannels() }, enabled = !isConnecting) {
                                        Icon(Icons.Default.Search, contentDescription = stringResource(R.string.browse_channels))
                                    }
                                }
                            },
                            colors = glassTextFieldColors,
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Button(
                                onClick = {
                                    scope.launch {
                                        sheetState.hide()
                                        showBottomSheet = false
                                    }
                                    viewModel.connect(onConnected)
                                },
                                modifier = Modifier.weight(1f),
                                enabled = !isConnecting,
                            ) {
                                if (isConnecting) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.height(20.dp).width(20.dp),
                                        strokeWidth = 2.dp,
                                    )
                                    Spacer(Modifier.width(8.dp))
                                }
                                Text(stringResource(if (isConnecting) R.string.connecting else R.string.connect))
                            }

                            FilledTonalButton(onClick = {
                                viewModel.saveBookmark()
                                scope.launch {
                                    sheetState.hide()
                                    showBottomSheet = false
                                }
                            }) {
                                Icon(Icons.Default.Star, contentDescription = null)
                                Spacer(Modifier.width(4.dp))
                                Text(stringResource(if (isEditing) R.string.save else R.string.add_bookmark))
                            }
                        }

                        Spacer(Modifier.height(16.dp))
                    }
                }
            }
        }
    }
}
