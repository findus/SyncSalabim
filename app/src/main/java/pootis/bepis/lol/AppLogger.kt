package pootis.bepis.lol

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.text.SimpleDateFormat
import java.util.*

object AppLogger {
    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs.asStateFlow()

    fun log(message: String) {
        val timestamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val formattedMessage = "[$timestamp] $message"
        _logs.update { currentLogs ->
            // Keep only the last 100 logs to prevent memory issues
            val newLogs = currentLogs + formattedMessage
            if (newLogs.size > 100) newLogs.takeLast(100) else newLogs
        }
    }

    fun clear() {
        _logs.value = emptyList()
    }
}
