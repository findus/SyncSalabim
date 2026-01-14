@file:OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)

package pootis.bepis.lol

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.work.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import pootis.bepis.lol.ui.theme.LolsyncTheme
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.roundToInt

private const val FOREGROUND_SYNC_TASK = "UnifiedPhotoSync"
private const val REBUILD_DB_SYNC_TASK = "ReconcileDatabase"
private const val BACKGROUND_SYNC_TASK = "BackgroundPhotoSync"
private const val TAG = "MainActivity"

class MainActivity : ComponentActivity() {

    private lateinit var settingsRepository: SettingsRepository
    private lateinit var database: SyncDatabase

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        updateLibraryCount()
    }

    private var totalLibraryCount by mutableIntStateOf(0)

    private val mediaObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            super.onChange(selfChange)
            updateLibraryCount()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settingsRepository = SettingsRepository(this)
        database = SyncDatabase.getDatabase(this)
        enableEdgeToEdge()

        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.READ_MEDIA_IMAGES)
            permissions.add(Manifest.permission.READ_MEDIA_VIDEO)
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        requestPermissionLauncher.launch(permissions.toTypedArray())

        contentResolver.registerContentObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, mediaObserver)
        contentResolver.registerContentObserver(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, mediaObserver)

        updateLibraryCount()

        lifecycleScope.launch {
            settingsRepository.settingsFlow.collect { settings ->
                if (settings.url.isNotBlank() && settings.backgroundSync) {
                    scheduleBackgroundSync(settings)
                } else {
                    WorkManager.getInstance(applicationContext).cancelUniqueWork(BACKGROUND_SYNC_TASK)
                }
                // Trigger library count update whenever settings (including folder selection) change
                updateLibraryCount(settings.selectedFolders)
            }
        }

        setContent {
            LolsyncTheme {
                MainAppScreen(
                    settingsRepository = settingsRepository,
                    database = database,
                    totalLibraryCount = totalLibraryCount,
                    onStartSync = { startSyncWorker(it) },
                    onStopSync = { stopSyncWorker() },
                    onStartReconcile = { startReconcileWorker(it) },
                    availableFolders = getAvailableFolders()
                )
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        contentResolver.unregisterContentObserver(mediaObserver)
    }

    private fun updateLibraryCount(selectedFolders: Set<String>? = null) {
        lifecycleScope.launch {
            val folders = selectedFolders ?: settingsRepository.settingsFlow.first().selectedFolders
            val projection = arrayOf(MediaStore.MediaColumns._ID)
            var count = 0
            
            fun getCount(uri: android.net.Uri) {
                val selection = if (folders.isNotEmpty()) {
                    val placeholders = folders.joinToString(",") { "?" }
                    "${MediaStore.MediaColumns.BUCKET_DISPLAY_NAME} IN ($placeholders)"
                } else {
                    null
                }
                val selectionArgs = if (folders.isNotEmpty()) {
                    folders.toTypedArray()
                } else {
                    null
                }
                contentResolver.query(uri, projection, selection, selectionArgs, null)?.use { count += it.count }
            }
            
            try {
                getCount(MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
                getCount(MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
                totalLibraryCount = count
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun getAvailableFolders(): List<String> {
        val folders = mutableSetOf<String>()
        val projection = arrayOf(MediaStore.MediaColumns.BUCKET_DISPLAY_NAME)
        fun query(uri: android.net.Uri) {
            contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                val bucketColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.BUCKET_DISPLAY_NAME)
                while (cursor.moveToNext()) {
                    cursor.getString(bucketColumn)?.let { folders.add(it) }
                }
            }
        }
        try {
            query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
            query(MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return folders.toList().sorted()
    }

    private fun isAnySyncRunning(): Boolean {
        val wm = WorkManager.getInstance(applicationContext)
        val statuses = listOf(
            wm.getWorkInfosForUniqueWork(FOREGROUND_SYNC_TASK).get(),
            wm.getWorkInfosForUniqueWork(REBUILD_DB_SYNC_TASK).get(),
            wm.getWorkInfosForUniqueWork(BACKGROUND_SYNC_TASK).get()
        )
        return statuses.flatten().any { it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED }
    }

    private fun startSyncWorker(settings: WebDavSettings) {
        if (settings.url.isBlank()) return
        if (isAnySyncRunning()) {
            Toast.makeText(this, "A task is already running", Toast.LENGTH_SHORT).show()
            return
        }
        val data = workDataOf(
            "baseUrl" to settings.url,
            "user" to settings.username,
            "password" to settings.password,
            "selectedFolders" to settings.selectedFolders.toTypedArray()
        )
        val syncRequest = OneTimeWorkRequestBuilder<SyncWorker>()
            .setInputData(data)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(applicationContext).enqueueUniqueWork(FOREGROUND_SYNC_TASK, ExistingWorkPolicy.KEEP, syncRequest)
        AppLogger.log("Sync requested")
    }

    private fun startReconcileWorker(settings: WebDavSettings) {
        if (settings.url.isBlank()) return
        if (isAnySyncRunning()) {
            Toast.makeText(this, "A task is already running", Toast.LENGTH_SHORT).show()
            return
        }
        val data = workDataOf(
            "baseUrl" to settings.url,
            "user" to settings.username,
            "password" to settings.password,
            "selectedFolders" to settings.selectedFolders.toTypedArray()
        )
        val reconcileRequest = OneTimeWorkRequestBuilder<ReconcileWorker>()
            .setInputData(data)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(applicationContext).enqueueUniqueWork(REBUILD_DB_SYNC_TASK, ExistingWorkPolicy.KEEP, reconcileRequest)
        AppLogger.log("Reconciliation requested")
    }

    private fun scheduleBackgroundSync(settings: WebDavSettings) {
        val data = workDataOf(
            "baseUrl" to settings.url,
            "user" to settings.username,
            "password" to settings.password,
            "selectedFolders" to settings.selectedFolders.toTypedArray()
        )
        val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).setRequiresCharging(true).build()
        val syncRequest = PeriodicWorkRequestBuilder<SyncWorker>(settings.backgroundSyncInterval.toLong(), java.util.concurrent.TimeUnit.MINUTES)
            .setInputData(data)
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(applicationContext).enqueueUniquePeriodicWork(BACKGROUND_SYNC_TASK, ExistingPeriodicWorkPolicy.KEEP, syncRequest)
    }

    private fun stopSyncWorker() {
        WorkManager.getInstance(applicationContext).cancelUniqueWork(FOREGROUND_SYNC_TASK)
        WorkManager.getInstance(applicationContext).cancelUniqueWork(BACKGROUND_SYNC_TASK)
        WorkManager.getInstance(applicationContext).cancelUniqueWork(REBUILD_DB_SYNC_TASK)
        AppLogger.log("Cancellation requested")
    }
}

sealed class Screen(val title: String, val icon: ImageVector) {
    data object Sync : Screen("Sync", Icons.Default.Build)
    data object Entries : Screen("Entries", Icons.AutoMirrored.Filled.List)
    data object Settings : Screen("Settings", Icons.Default.Settings)
}

@Composable
fun MainAppScreen(
    settingsRepository: SettingsRepository,
    database: SyncDatabase,
    totalLibraryCount: Int,
    onStartSync: (WebDavSettings) -> Unit,
    onStopSync: () -> Unit,
    onStartReconcile: (WebDavSettings) -> Unit,
    availableFolders: List<String>
) {
    var selectedScreen by remember { mutableStateOf<Screen>(Screen.Sync) }
    val scope = rememberCoroutineScope()

    val settings by settingsRepository.settingsFlow.collectAsStateWithLifecycle(initialValue = WebDavSettings("", "", ""))
    val logs by AppLogger.logs.collectAsStateWithLifecycle()
    val syncedCount by database.photoDao().getSyncedCountFlow().collectAsStateWithLifecycle(initialValue = 0)
    val syncedEntries by database.photoDao().getAllFlow().collectAsStateWithLifecycle(initialValue = emptyList())

    val workManager = WorkManager.getInstance(androidx.compose.ui.platform.LocalContext.current)
    val manualWorkInfos by workManager.getWorkInfosForUniqueWorkFlow(FOREGROUND_SYNC_TASK).collectAsStateWithLifecycle(initialValue = emptyList())
    val backgroundWorkInfos by workManager.getWorkInfosForUniqueWorkFlow(BACKGROUND_SYNC_TASK).collectAsStateWithLifecycle(initialValue = emptyList())
    val reconcileWorkInfos by workManager.getWorkInfosForUniqueWorkFlow(REBUILD_DB_SYNC_TASK).collectAsStateWithLifecycle(initialValue = emptyList())

    val activeWork = (manualWorkInfos + backgroundWorkInfos + reconcileWorkInfos).firstOrNull { it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED }
    val isSyncing = activeWork != null
    val progress = activeWork?.progress?.getFloat("progress", -1f) ?: -1f
    val current = activeWork?.progress?.getInt("current", 0) ?: 0
    val total = activeWork?.progress?.getInt("total", 0) ?: 0
    val currentFileName = activeWork?.progress?.getString("name") ?: ""

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Sync-Salabim \uD83E\uDE84") },
                actions = {
                    if (selectedScreen == Screen.Sync) {
                        IconButton(onClick = { AppLogger.clear() }) { Icon(Icons.Default.Delete, contentDescription = "Clear Logs") }
                    }
                    if (selectedScreen == Screen.Entries) {
                        IconButton(onClick = { onStartReconcile(settings) }, enabled = !isSyncing) {
                            Icon(Icons.Default.Refresh, contentDescription = "Reconcile Database")
                        }
                        IconButton(onClick = { 
                            scope.launch { 
                                database.photoDao().deleteAll() 
                                AppLogger.log("Sync history cleared (all items)")
                            } 
                        }, enabled = !isSyncing) {
                            Icon(Icons.Default.DeleteForever, contentDescription = "Delete All Entries", tint = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            )
        },
        bottomBar = {
            NavigationBar {
                val screens = listOf(Screen.Sync, Screen.Entries, Screen.Settings)
                screens.forEach { screen ->
                    NavigationBarItem(
                        icon = { Icon(screen.icon, contentDescription = screen.title) },
                        label = { Text(screen.title) },
                        selected = selectedScreen == screen,
                        onClick = { selectedScreen = screen }
                    )
                }
            }
        }
    ) { innerPadding ->
        Column(modifier = Modifier.padding(innerPadding).fillMaxSize().animateContentSize()) {
            // Global Progress Bar - fades in on top
            AnimatedVisibility(
                visible = isSyncing,
                enter = slideInVertically(initialOffsetY = { -it }) + fadeIn(),
                exit = slideOutVertically(targetOffsetY = { -it }) + fadeOut()
            ) {
                SyncProgressSection(progress, current, total, currentFileName, onStopSync)
            }

            when (selectedScreen) {
                Screen.Sync -> {
                    MainSyncContent(
                        modifier = Modifier.weight(1f),
                        settings = settings,
                        isSyncing = isSyncing,
                        libraryTotal = totalLibraryCount,
                        librarySynced = syncedCount,
                        onStartSync = { onStartSync(settings) }
                    )
                    
                    LogConsole(
                        modifier = Modifier.fillMaxWidth().height(200.dp).background(Color.Black),
                        logs = logs
                    )
                }
                Screen.Entries -> {
                    EntriesScreen(
                        modifier = Modifier.weight(1f),
                        entries = syncedEntries,
                        onDelete = { entry ->
                            scope.launch { database.photoDao().delete(entry) }
                        }
                    )
                }
                Screen.Settings -> {
                    SettingsTabScreen(
                        modifier = Modifier.fillMaxSize(),
                        initialSettings = settings,
                        availableFolders = availableFolders,
                        isSyncing = isSyncing,
                        onSave = { newSettings ->
                            Log.d(TAG, "Saving settings: URL=${newSettings.url}, User=${newSettings.username}")
                            scope.launch {
                                try {
                                    val urlChanged = newSettings.url != settings.url
                                    settingsRepository.saveSettings(newSettings)
                                    AppLogger.log("Settings saved")
                                    if (urlChanged && newSettings.url.isNotBlank()) {
                                        onStartReconcile(newSettings)
                                    }
                                } catch (e: Exception) {
                                    Log.e(TAG, "Failed to save settings", e)
                                    AppLogger.log("ERROR: Failed to save settings")
                                }
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun MainSyncContent(
    modifier: Modifier = Modifier,
    settings: WebDavSettings,
    isSyncing: Boolean,
    libraryTotal: Int,
    librarySynced: Int,
    onStartSync: () -> Unit
) {
    val allSynced = libraryTotal > 0 && librarySynced >= libraryTotal

    Column(
        modifier = modifier.fillMaxWidth().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Library Stats", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(8.dp))
                StatRow("Total items:", libraryTotal.toString())
                StatRow("Synced items:", librarySynced.toString())
                StatRow("Remaining:", (libraryTotal - librarySynced).coerceAtLeast(0).toString())
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        if (settings.url.isBlank()) {
            Text("Please configure WebDAV settings in the Settings tab.", color = MaterialTheme.colorScheme.error)
        } else {
            if (allSynced) {
                Text("✨ All items are synced", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleMedium)
            } else {
                Text("Ready to sync to:", style = MaterialTheme.typography.labelLarge)
                Text(settings.url, style = MaterialTheme.typography.bodyMedium)
            }
            
            Spacer(modifier = Modifier.height(24.dp))
            
            if (!isSyncing) {
                Button(
                    onClick = onStartSync, 
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !allSynced
                ) { 
                    Text("Start Sync Now") 
                }
            }
        }
    }
}

@Composable
fun StatRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label)
        Text(value)
    }
}

@Composable
fun SyncProgressSection(progress: Float, current: Int, total: Int, fileName: String, onStopSync: () -> Unit) {
    Column(modifier = Modifier.padding(16.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        if (progress >= 0) {
            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().height(8.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Syncing $current/$total", style = MaterialTheme.typography.labelSmall)
                Text("${(progress * 100).toInt()}%", style = MaterialTheme.typography.labelSmall)
            }
        } else {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Text("Preparing...", style = MaterialTheme.typography.labelSmall)
        }
        Text(fileName, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedButton(onClick = onStopSync, modifier = Modifier.fillMaxWidth()) { Text("Stop Sync") }
    }
}

@Composable
fun EntriesScreen(modifier: Modifier = Modifier, entries: List<SyncedPhoto>, onDelete: (SyncedPhoto) -> Unit) {
    val context = LocalContext.current
    var searchQuery by remember { mutableStateOf("") }
    
    val filteredEntries = remember(entries, searchQuery) {
        if (searchQuery.isBlank()) {
            entries
        } else {
            entries.filter { it.fileName.contains(searchQuery, ignoreCase = true) }
        }
    }

    Column(modifier = modifier) {
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            placeholder = { Text("Search synced files...") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = { searchQuery = "" }) {
                        Icon(Icons.Default.Close, contentDescription = "Clear search")
                    }
                }
            },
            singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(
                unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                focusedContainerColor = MaterialTheme.colorScheme.surface
            )
        )

        if (filteredEntries.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(if (searchQuery.isEmpty()) "No synced entries found." else "No matches found.")
            }
        } else {
            var itemsToDelete by remember { mutableStateOf(setOf<Long>()) }
            val scope = rememberCoroutineScope()
            
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                stickyHeader {
                    Surface(modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.secondaryContainer) {
                        Row(modifier = Modifier.padding(8.dp)) {
                            Text("File Name", modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                            Text("Synced At", modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, textAlign = TextAlign.Center)
                            Spacer(modifier = Modifier.width(48.dp))
                        }
                    }
                }
                items(items = filteredEntries, key = { it.id }) { entry ->
                    AnimatedVisibility(
                        visible = !itemsToDelete.contains(entry.id),
                        exit = shrinkVertically(animationSpec = tween(300)) + fadeOut()
                    ) {
                        Column {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(8.dp)
                                    .clickable {
                                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                        val clip = ClipData.newPlainText("filename", entry.fileName)
                                        clipboard.setPrimaryClip(clip)
                                        Toast.makeText(context, "Copied: ${entry.fileName}", Toast.LENGTH_SHORT).show()
                                    },
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = entry.fileName,
                                    modifier = Modifier.weight(1f),
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(entry.timestamp)),
                                    modifier = Modifier.weight(1f),
                                    style = MaterialTheme.typography.bodySmall,
                                    textAlign = TextAlign.Center
                                )
                                IconButton(onClick = {
                                    scope.launch {
                                        itemsToDelete = itemsToDelete + entry.id
                                        delay(300) // Match the exit animation duration
                                        onDelete(entry)
                                    }
                                }) {
                                    Icon(Icons.Default.Delete, contentDescription = "Delete entry", tint = MaterialTheme.colorScheme.error)
                                }
                            }
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SettingsTabScreen(
    modifier: Modifier = Modifier,
    initialSettings: WebDavSettings,
    availableFolders: List<String>,
    isSyncing: Boolean,
    onSave: (WebDavSettings) -> Unit
) {
    var url by remember(initialSettings.url) { mutableStateOf(initialSettings.url) }
    var user by remember(initialSettings.username) { mutableStateOf(initialSettings.username) }
    var pass by remember(initialSettings.password) { mutableStateOf(initialSettings.password) }
    var backgroundSync by remember(initialSettings.backgroundSync) { mutableStateOf(initialSettings.backgroundSync) }
    var backgroundInterval by remember(initialSettings.backgroundSyncInterval) { mutableFloatStateOf(initialSettings.backgroundSyncInterval.toFloat()) }
    var selectedFolders by remember(initialSettings.selectedFolders) { mutableStateOf(initialSettings.selectedFolders) }
    
    val keyboardController = LocalSoftwareKeyboardController.current

    LazyColumn(
        modifier = modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                label = { Text("WebDAV URL") },
                placeholder = { Text("https://dav.example.com/photos") },
                modifier = Modifier.fillMaxWidth(),
                enabled = !isSyncing
            )
        }

        item {
            OutlinedTextField(
                value = user,
                onValueChange = { user = it },
                label = { Text("Username") },
                modifier = Modifier.fillMaxWidth(),
                enabled = !isSyncing
            )
        }

        item {
            OutlinedTextField(
                value = pass,
                onValueChange = { pass = it },
                label = { Text("Password") },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
                enabled = !isSyncing
            )
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Background Sync", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Sync photos automatically when charging and connected to Wi-Fi",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = backgroundSync,
                    onCheckedChange = { backgroundSync = it },
                    enabled = !isSyncing
                )
            }
        }

        if (backgroundSync) {
            item {
                Column {
                    Text("Background Sync Interval: ${backgroundInterval.roundToInt()} minutes", style = MaterialTheme.typography.bodyMedium)
                    Slider(
                        value = backgroundInterval,
                        onValueChange = { backgroundInterval = it },
                        valueRange = 15f..1440f, // 15 mins to 24 hours
                        steps = (1440 - 15) / 15 - 1, // 15 minute increments
                        enabled = !isSyncing
                    )
                }
            }
        }

        item {
            Text("Sync Folders", style = MaterialTheme.typography.titleMedium)
            Text("If none selected, all folders will be synced.", style = MaterialTheme.typography.bodySmall)
        }

        items(availableFolders) { folder ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = !isSyncing) {
                        selectedFolders = if (selectedFolders.contains(folder)) {
                            selectedFolders - folder
                        } else {
                            selectedFolders + folder
                        }
                    },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(
                    checked = selectedFolders.contains(folder),
                    onCheckedChange = null, // Handled by row clickable
                    enabled = !isSyncing
                )
                Text(
                    text = folder, 
                    modifier = Modifier.padding(start = 8.dp),
                    color = if (!isSyncing) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                )
            }
        }

        item {
            Button(
                onClick = { 
                    keyboardController?.hide()
                    onSave(WebDavSettings(url, user, pass, backgroundSync, backgroundInterval.roundToInt(), selectedFolders)) 
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !isSyncing
            ) {
                Text("Save Settings")
            }
        }
    }
}

@Composable
fun LogConsole(modifier: Modifier = Modifier, logs: List<String>) {
    val listState = rememberLazyListState()
    LaunchedEffect(logs.size) { if (logs.isNotEmpty()) listState.animateScrollToItem(logs.size - 1) }
    LazyColumn(state = listState, modifier = modifier.padding(4.dp)) {
        items(logs) { log ->
            Text(text = log, color = if (log.contains("ERROR")) Color.Red else Color.LightGray, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
        }
    }
}
