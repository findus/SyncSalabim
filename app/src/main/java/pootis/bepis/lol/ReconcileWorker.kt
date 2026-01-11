package pootis.bepis.lol

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import okhttp3.Credentials
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.logging.HttpLoggingInterceptor
import java.text.SimpleDateFormat
import java.util.*

private const val TAG = "ReconcileWorker"

class ReconcileWorker(appContext: Context, workerParams: WorkerParameters) :
    CoroutineWorker(appContext, workerParams) {

    private val db = SyncDatabase.getDatabase(appContext)
    private val client = OkHttpClient.Builder()
        .build()

    override suspend fun doWork(): Result {
        AppLogger.log("Starting full database reconciliation...")
        
        val baseUrlStr = inputData.getString("baseUrl")?.removeSuffix("/") ?: return Result.failure()
        val user = inputData.getString("user") ?: return Result.failure()
        val password = inputData.getString("password") ?: return Result.failure()
        val auth = Credentials.basic(user, password)
        val baseUrl = baseUrlStr.toHttpUrl()

        try {
            // 1. Delete all entries in local database
            db.photoDao().deleteAll()
            AppLogger.log("Local sync cache cleared.")

            // 2. Get all local media files
            val allLocalItems = getAllLocalMedia()
            val total = allLocalItems.size
            AppLogger.log("Found $total local media files to verify.")

            var processed = 0
            var reconciled = 0

            for (item in allLocalItems) {
                processed++
                
                // Progress update
                setProgress(workDataOf(
                    "progress" to (processed.toFloat() / total),
                    "current" to processed,
                    "total" to total,
                    "name" to "Verifying: ${item.name}"
                ))

                // 3. Extract path (Year/Month)
                val date = Date(if (item.dateTaken > 0) item.dateTaken else System.currentTimeMillis())
                val year = SimpleDateFormat("yyyy", Locale.US).format(date)
                val month = SimpleDateFormat("MM", Locale.US).format(date)

                val remoteUrl = baseUrl.newBuilder()
                    .addPathSegment(year)
                    .addPathSegment(month)
                    .addPathSegment(item.name)
                    .build()

                // 4. Check if file exists remotely (using HEAD)
                if (remoteFileExists(remoteUrl.toString(), auth)) {
                    // 5. Add to local database if it exists remotely
                    db.photoDao().insert(SyncedPhoto(item.id, item.name, System.currentTimeMillis()))
                    reconciled++
                }
            }

            AppLogger.log("Reconciliation finished. $reconciled items marked as synced.")
            return Result.success()
        } catch (e: Exception) {
            AppLogger.log("ERROR during reconciliation: ${e.message}")
            return Result.failure()
        }
    }

    private fun getAllLocalMedia(): List<LocalItem> {
        val items = mutableListOf<LocalItem>()
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.DATE_TAKEN
        )

        fun query(uri: Uri) {
            applicationContext.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val dateCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_TAKEN)
                while (cursor.moveToNext()) {
                    items.add(LocalItem(
                        id = cursor.getLong(idCol),
                        name = cursor.getString(nameCol),
                        dateTaken = cursor.getLong(dateCol)
                    ))
                }
            }
        }

        query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
        query(MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
        return items
    }

    private fun remoteFileExists(url: String, auth: String): Boolean {
        val request = Request.Builder()
            .url(url)
            .head() // HEAD request is efficient for existence checks
            .addHeader("Authorization", auth)
            .build()
        
        return try {
            client.newCall(request).execute().use { response ->
                response.isSuccessful
            }
        } catch (e: Exception) {
            false
        }
    }

    data class LocalItem(val id: Long, val name: String, val dateTaken: Long)
}
