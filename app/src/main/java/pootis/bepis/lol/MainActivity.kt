@file:OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)

package pootis.bepis.lol

import android.Manifest
import android.content.ContentResolver
import android.content.Intent
import android.database.ContentObserver
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Build
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.work.*
import kotlinx.coroutines.launch
import pootis.bepis.lol.ui.theme.LolsyncTheme
import java.text.SimpleDateFormat
import java.util.*

private const val SYNC_WORK_NAME = "UnifiedPhotoSync"

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
                    WorkManager.getInstance(applicationContext).cancelUniqueWork("BackgroundPhotoSync")
                }
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
                    onOpenSettings = { startActivity(Intent(this, SettingsActivity::class.java)) }
                )
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        contentResolver.unregisterContentObserver(mediaObserver)
    }

    private fun updateLibraryCount() {
        val projection = arrayOf(MediaStore.MediaColumns._ID)
        var count = 0
        fun getCount(uri: android.net.Uri) {
            contentResolver.query(uri, projection, null, null, null)?.use { count += it.count }
        }
        try {
            getCount(MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
            getCount(MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
            totalLibraryCount = count
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun startSyncWorker(settings: WebDavSettings) {
        if (settings.url.isBlank()) return
        val data = workDataOf("baseUrl" to settings.url, "user" to settings.username, "password" to settings.password)
        val syncRequest = OneTimeWorkRequestBuilder<SyncWorker>()
            .setInputData(data)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(applicationContext).enqueueUniqueWork(SYNC_WORK_NAME, ExistingWorkPolicy.KEEP, syncRequest)
        AppLogger.log("Sync requested")
    }

    private fun scheduleBackgroundSync(settings: WebDavSettings) {
        val data = workDataOf("baseUrl" to settings.url, "user" to settings.username, "password" to settings.password)
        val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).setRequiresCharging(true).build()
        val syncRequest = PeriodicWorkRequestBuilder<SyncWorker>(1, java.util.concurrent.TimeUnit.HOURS)
            .setInputData(data)
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(applicationContext).enqueueUniquePeriodicWork("BackgroundPhotoSync", ExistingPeriodicWorkPolicy.KEEP, syncRequest)
    }

    private fun stopSyncWorker() {
        WorkManager.getInstance(applicationContext).cancelUniqueWork(SYNC_WORK_NAME)
        WorkManager.getInstance(applicationContext).cancelUniqueWork("BackgroundPhotoSync")
        AppLogger.log("Sync cancellation requested")
    }
}

sealed class Screen(val title: String, val icon: ImageVector) {
    data object Sync : Screen("Sync", Icons.Default.Build)
    data object Entries : Screen("Entries", Icons.AutoMirrored.Filled.List)
}

@Composable
fun MainAppScreen(
    settingsRepository: SettingsRepository,
    database: SyncDatabase,
    totalLibraryCount: Int,
    onStartSync: (WebDavSettings) -> Unit,
    onStopSync: () -> Unit,
    onOpenSettings: () -> Unit
) {
    var selectedScreen by remember { mutableStateOf<Screen>(Screen.Sync) }
    val scope = rememberCoroutineScope()

    val settings by settingsRepository.settingsFlow.collectAsStateWithLifecycle(initialValue = WebDavSettings("", "", ""))
    val logs by AppLogger.logs.collectAsStateWithLifecycle()
    val syncedCount by database.photoDao().getSyncedCountFlow().collectAsStateWithLifecycle(initialValue = 0)
    val syncedEntries by database.photoDao().getAllFlow().collectAsStateWithLifecycle(initialValue = emptyList())

    val workManager = WorkManager.getInstance(androidx.compose.ui.platform.LocalContext.current)
    val manualWorkInfos by workManager.getWorkInfosForUniqueWorkFlow(SYNC_WORK_NAME).collectAsStateWithLifecycle(initialValue = emptyList())
    val backgroundWorkInfos by workManager.getWorkInfosForUniqueWorkFlow("BackgroundPhotoSync").collectAsStateWithLifecycle(initialValue = emptyList())

    val activeWork = (manualWorkInfos + backgroundWorkInfos).firstOrNull { it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED }
    val isSyncing = activeWork != null
    val progress = activeWork?.progress?.getFloat("progress", -1f) ?: -1f
    val current = activeWork?.progress?.getInt("current", 0) ?: 0
    val total = activeWork?.progress?.getInt("total", 0) ?: 0
    val currentFileName = activeWork?.progress?.getString("name") ?: ""

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Photo Sync") },
                actions = {
                    IconButton(onClick = { AppLogger.clear() }) { Icon(Icons.Default.Delete, contentDescription = "Clear Logs") }
                    IconButton(onClick = onOpenSettings) { Icon(Icons.Default.Settings, contentDescription = "Settings") }
                }
            )
        },
        bottomBar = {
            NavigationBar {
                val screens = listOf(Screen.Sync, Screen.Entries)
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
        Column(modifier = Modifier.padding(innerPadding).fillMaxSize()) {
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
                    
                    if (isSyncing) {
                        SyncProgressSection(progress, current, total, currentFileName, onStopSync)
                    }

                    LogConsole(
                        modifier = Modifier.fillMaxWidth().height(200.dp).background(Color.Black),
                        logs = logs
                    )
                }
                Screen.Entries -> {
                    EntriesScreen(
                        modifier = Modifier.fillMaxSize(),
                        entries = syncedEntries,
                        onDelete = { entry ->
                            scope.launch { database.photoDao().delete(entry) }
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
            Text("Please configure WebDAV settings in the top right corner.", color = MaterialTheme.colorScheme.error)
        } else {
            Text("Ready to sync to:", style = MaterialTheme.typography.labelLarge)
            Text(settings.url, style = MaterialTheme.typography.bodyMedium)
            Spacer(modifier = Modifier.height(24.dp))
            if (!isSyncing) {
                Button(onClick = onStartSync, modifier = Modifier.fillMaxWidth()) { Text("Start Sync Now") }
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
    if (entries.isEmpty()) {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            Text("No synced entries found.")
        }
    } else {
        LazyColumn(modifier = modifier) {
            stickyHeader {
                Surface(modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.secondaryContainer) {
                    Row(modifier = Modifier.padding(8.dp)) {
                        Text("File Name", modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                        Text("Synced At", modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, textAlign = TextAlign.Center)
                        Spacer(modifier = Modifier.width(48.dp))
                    }
                }
            }
            items(entries) { entry ->
                Row(modifier = Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(entry.fileName, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(entry.timestamp)),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center
                    )
                    IconButton(onClick = { onDelete(entry) }) {
                        Icon(Icons.Default.Delete, contentDescription = "Delete entry", tint = MaterialTheme.colorScheme.error)
                    }
                }
                HorizontalDivider()
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
