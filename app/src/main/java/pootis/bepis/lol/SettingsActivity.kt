@file:OptIn(ExperimentalMaterial3Api::class)

package pootis.bepis.lol

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import pootis.bepis.lol.ui.theme.LolsyncTheme

private const val TAG = "SettingsActivity"

class SettingsActivity : ComponentActivity() {
    private lateinit var settingsRepository: SettingsRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settingsRepository = SettingsRepository(this)
        enableEdgeToEdge()

        setContent {
            LolsyncTheme {
                val settings by settingsRepository.settingsFlow.collectAsStateWithLifecycle(
                    initialValue = WebDavSettings("", "", "")
                )
                
                Scaffold(
                    topBar = {
                        CenterAlignedTopAppBar(
                            title = { Text("Settings") },
                            navigationIcon = {
                                IconButton(onClick = { finish() }) {
                                    Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                                }
                            }
                        )
                    }
                ) { innerPadding ->
                    SettingsScreen(
                        modifier = Modifier.padding(innerPadding),
                        initialSettings = settings,
                        onSave = { newSettings ->
                            Log.d(TAG, "Saving settings: URL=${newSettings.url}, User=${newSettings.username}")
                            lifecycleScope.launch {
                                try {
                                    settingsRepository.saveSettings(newSettings)
                                    Log.d(TAG, "Settings saved successfully")
                                    finish()
                                } catch (e: Exception) {
                                    Log.e(TAG, "Failed to save settings", e)
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
fun SettingsScreen(
    modifier: Modifier = Modifier,
    initialSettings: WebDavSettings,
    onSave: (WebDavSettings) -> Unit
) {
    var url by remember(initialSettings.url) { mutableStateOf(initialSettings.url) }
    var user by remember(initialSettings.username) { mutableStateOf(initialSettings.username) }
    var pass by remember(initialSettings.password) { mutableStateOf(initialSettings.password) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        OutlinedTextField(
            value = url,
            onValueChange = { url = it },
            label = { Text("WebDAV URL") },
            placeholder = { Text("https://dav.example.com/photos") },
            modifier = Modifier.fillMaxWidth()
        )

        OutlinedTextField(
            value = user,
            onValueChange = { user = it },
            label = { Text("Username") },
            modifier = Modifier.fillMaxWidth()
        )

        OutlinedTextField(
            value = pass,
            onValueChange = { pass = it },
            label = { Text("Password") },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth()
        )

        Button(
            onClick = { onSave(WebDavSettings(url, user, pass)) },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Save Settings")
        }
    }
}
