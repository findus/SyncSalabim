package pootis.bepis.lol

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "synced_photos")
data class SyncedPhoto(
    @PrimaryKey val id: Long,
    val fileName: String,
    val timestamp: Long
)

@Dao
interface PhotoDao {
    @Query("SELECT * FROM synced_photos")
    suspend fun getAll(): List<SyncedPhoto>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(photo: SyncedPhoto)

    @Query("SELECT EXISTS(SELECT 1 FROM synced_photos WHERE id = :id)")
    suspend fun isSynced(id: Long): Boolean

    @Query("SELECT COUNT(*) FROM synced_photos")
    fun getSyncedCountFlow(): Flow<Int>
}

@Database(entities = [SyncedPhoto::class], version = 1)
abstract class SyncDatabase : RoomDatabase() {
    abstract fun photoDao(): PhotoDao

    companion object {
        @Volatile
        private var INSTANCE: SyncDatabase? = null

        fun getDatabase(context: Context): SyncDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    SyncDatabase::class.java,
                    "sync_database"
                ).build()
                INSTANCE = instance
                instance
            }
        }
    }
}
