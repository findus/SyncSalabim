package pootis.bepis.lol

import android.content.Context
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import okhttp3.Credentials
import okhttp3.HttpUrl.Companion.toHttpUrl

class ReconcileWorker(appContext: Context, workerParams: WorkerParameters) :
    BaseSyncWorker(appContext, workerParams) {

    override val tag = "ReconcileWorker"
    override val notificationId = 2

    override suspend fun doWork(): Result {
        log("Starting full database reconciliation...")

        createNotificationChannel()
        try {
            setForeground(createForegroundInfo(0, 0, "Initializing reconciliation...", "Rebuilding Database"))
        } catch (e: Exception) {
            log("Failed to set foreground info", e)
        }

        val baseUrlStr = inputData.getString("baseUrl")?.removeSuffix("/") ?: return Result.failure()
        val user = inputData.getString("user") ?: return Result.failure()
        val password = inputData.getString("password") ?: return Result.failure()
        val auth = Credentials.basic(user, password)
        val baseUrl = baseUrlStr.toHttpUrl()
        val selectedFolders = inputData.getStringArray("selectedFolders")?.toSet() ?: emptySet()

        try {
            // 1. Delete all entries in local database
            db.photoDao().deleteAll()
            log("Local sync cache cleared.")

            // 2. Get all local media files
            val allLocalItems = getAllLocalMedia(selectedFolders)
            val total = allLocalItems.size
            log("Found $total local media files to verify.")

            var reconciled = 0

            for ((index, item) in allLocalItems.withIndex()) {
                val current = index + 1
                val progressValue = index.toFloat() / total

                // Progress update for UI
                setProgress(workDataOf(
                    "progress" to progressValue,
                    "current" to current,
                    "total" to total,
                    "name" to "Verifying: ${item.name}"
                ))

                // Update Foreground Notification
                updateProgressNotification(current, total, item.name, "Rebuilding Database")

                val remoteUrl = getRemoteUrl(item, baseUrl)

                // 4. Check if file exists remotely (using HEAD)
                if (remoteFileExists(remoteUrl.toString(), auth)) {
                    // 5. Add to local database if it exists remotely
                    db.photoDao().insert(SyncedPhoto(item.id, item.name, System.currentTimeMillis()))
                    reconciled++
                }
            }

            setProgress(workDataOf("progress" to 1f, "current" to total, "total" to total, "name" to "Done"))
            
            log("Reconciliation finished. $reconciled items marked as synced.")
            showFinishedNotification("Rebuilding Finished", "Successfully reconciled $reconciled items.")
            
            return Result.success()
        } catch (e: Exception) {
            log("ERROR during reconciliation: ${e.message}")
            showFinishedNotification("Sync Failed", e.message ?: "An unexpected error occurred.")
            return Result.failure()
        }
    }
}
