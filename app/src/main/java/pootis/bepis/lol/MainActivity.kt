@file:OptIn(ExperimentalMaterial3Api::class)

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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.work.*
import pootis.bepis.lol.ui.theme.LolsyncTheme

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

        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        requestPermissionLauncher.launch(permissions)

        // Register observers for both images and videos
        contentResolver.registerContentObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, mediaObserver)
        contentResolver.registerContentObserver(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, mediaObserver)

        updateLibraryCount()

        setContent {
            LolsyncTheme {
                val settings by settingsRepository.settingsFlow.collectAsStateWithLifecycle(
                    initialValue = WebDavSettings("", "", "")
                )

                val workInfos by WorkManager.getInstance(applicationContext)
                    .getWorkInfosForUniqueWorkFlow("PhotoSync")
                    .collectAsStateWithLifecycle(initialValue = emptyList())

                val logs by AppLogger.logs.collectAsStateWithLifecycle()
                val syncedCount by database.photoDao().getSyncedCountFlow().collectAsStateWithLifecycle(initialValue = 0)

                val activeWorkInfo = workInfos.firstOrNull { 
                    it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED 
                }
                
                val isSyncing = activeWorkInfo != null
                val progress = activeWorkInfo?.progress?.getFloat("progress", -1f) ?: -1f
                val current = activeWorkInfo?.progress?.getInt("current", 0) ?: 0
                val total = activeWorkInfo?.progress?.getInt("total", 0) ?: 0
                val currentFileName = activeWorkInfo?.progress?.getString("name") ?: ""

                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    topBar = {
                        CenterAlignedTopAppBar(
                            title = { Text("Photo Sync") },
                            actions = {
                                IconButton(onClick = { AppLogger.clear() }) {
                                    Icon(Icons.Default.Delete, contentDescription = "Clear Logs")
                                }
                                IconButton(onClick = {
                                    startActivity(Intent(this@MainActivity, SettingsActivity::class.java))
                                }) {
                                    Icon(Icons.Default.Settings, contentDescription = "Settings")
                                }
                            }
                        )
                    }
                ) { innerPadding ->
                    Column(modifier = Modifier.padding(innerPadding).fillMaxSize()) {
                        MainScreenContent(
                            modifier = Modifier.weight(1f),
                            settings = settings,
                            isSyncing = isSyncing,
                            progress = progress,
                            currentCount = current,
                            totalCount = total,
                            currentFileName = currentFileName,
                            libraryTotal = totalLibraryCount,
                            librarySynced = syncedCount,
                            onStartSync = {
                                startSyncWorker(settings)
                            },
                            onStopSync = {
                                stopSyncWorker()
                            }
                        )
                        LogConsole(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(250.dp)
                                .background(Color.Black),
                            logs = logs
                        )
                    }
                }
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
            contentResolver.query(uri, projection, null, null, null)?.use {
                count += it.count
            }
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

        val data = workDataOf(
            "baseUrl" to settings.url,
            "user" to settings.username,
            "password" to settings.password
        )

        val syncRequest = OneTimeWorkRequestBuilder<SyncWorker>()
            .setInputData(data)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .build()

        WorkManager.getInstance(applicationContext).enqueueUniqueWork(
            "PhotoSync",
            ExistingWorkPolicy.REPLACE,
            syncRequest
        )
        AppLogger.log("Manual sync triggered")
    }

    private fun stopSyncWorker() {
        WorkManager.getInstance(applicationContext).cancelUniqueWork("PhotoSync")
        AppLogger.log("Sync cancellation requested")
    }
}

@Composable
fun MainScreenContent(
    modifier: Modifier = Modifier,
    settings: WebDavSettings,
    isSyncing: Boolean,
    progress: Float,
    currentCount: Int,
    totalCount: Int,
    currentFileName: String,
    libraryTotal: Int,
    librarySynced: Int,
    onStartSync: () -> Unit,
    onStopSync: () -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalArrangement = Arrangement.Top,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Library Stats", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Total items:")
                    Text("$libraryTotal")
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Synced items:")
                    Text("$librarySynced")
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Remaining:")
                    Text("${(libraryTotal - librarySynced).coerceAtLeast(0)}")
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        if (settings.url.isBlank()) {
            Text(
                "Please configure WebDAV settings in the top right corner before syncing.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.error
            )
        } else {
            Text("Ready to sync to:", style = MaterialTheme.typography.labelLarge)
            Text(settings.url, style = MaterialTheme.typography.bodyMedium)
            
            Spacer(modifier = Modifier.height(32.dp))

            if (isSyncing) {
                SyncProgressContent(progress, currentCount, totalCount, currentFileName, onStopSync)
            } else {
                Button(
                    onClick = onStartSync,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Start Sync Now")
                }
            }
        }
    }
}

@Composable
fun LogConsole(modifier: Modifier = Modifier, logs: List<String>) {
    val listState = rememberLazyListState()
    
    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) {
            listState.animateScrollToItem(logs.size - 1)
        }
    }

    LazyColumn(
        state = listState,
        modifier = modifier.padding(4.dp),
        contentPadding = PaddingValues(bottom = 8.dp)
    ) {
        item {
            Text(
                "--- DEBUG CONSOLE ---",
                color = Color.Green,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace
            )
        }
        items(logs) { log ->
            Text(
                text = log,
                color = if (log.contains("ERROR")) Color.Red else Color.LightGray,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                lineHeight = 14.sp
            )
        }
    }
}

@Composable
fun SyncProgressContent(
    progress: Float,
    current: Int,
    total: Int,
    fileName: String,
    onStopSync: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (progress >= 0) {
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth().height(12.dp)
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Syncing $current of $total", style = MaterialTheme.typography.labelMedium)
                Text("${(progress * 100).toInt()}%", style = MaterialTheme.typography.labelMedium)
            }
        } else {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Text("Preparing sync...", style = MaterialTheme.typography.labelMedium)
        }

        if (fileName.isNotEmpty()) {
            Text(
                text = fileName,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Spacer(modifier = Modifier.height(16.dp))
        
        OutlinedButton(
            onClick = onStopSync,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
        ) {
            Text("Stop Sync")
        }
    }
}
