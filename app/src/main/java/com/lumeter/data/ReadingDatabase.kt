package com.lumeter.data

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

/** A saved metering reading. */
@Serializable
@Entity(tableName = "readings")
data class Reading(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val timestamp: Long,
    val ev100: Double,
    val iso: Int,
    val aperture: Double,
    val shutter: Double,
    val meteringMode: String,
    val note: String = "Untitled reading",
    // v0.8 workflow columns; pre-0.8 rows carry NULL in all of them.
    val rollId: Long? = null,
    val filmStock: String? = null,
    val source: String? = null,
    val zone: Int? = null,
)

/** A loaded film roll tracked through its exposure life. */
@Serializable
@Entity(tableName = "rolls")
data class FilmRoll(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val stockName: String,
    val iso: Int,
    val frameCount: Int,
    val framesShot: Int = 0,
    val loadedAt: Long,
    val notes: String = "",
    val archivedAt: Long? = null,
) {
    val framesLeft: Int get() = (frameCount - framesShot).coerceAtLeast(0)
    val isArchived: Boolean get() = archivedAt != null
}

/** Envelope for the JSON export: rolls plus readings. */
@Serializable
data class ExportPayload(
    val rolls: List<FilmRoll>,
    val readings: List<Reading>,
)

@Database(
    entities = [Reading::class, FilmRoll::class],
    version = 2,
    exportSchema = false,
)
abstract class LumeterDatabase : RoomDatabase() {
    abstract fun readingDao(): ReadingDao
    abstract fun filmRollDao(): FilmRollDao

    companion object {
        /** v2: rolls table + the workflow columns on readings (all nullable, no backfill). */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `rolls` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`stockName` TEXT NOT NULL, `iso` INTEGER NOT NULL, " +
                        "`frameCount` INTEGER NOT NULL, `framesShot` INTEGER NOT NULL, " +
                        "`loadedAt` INTEGER NOT NULL, `notes` TEXT NOT NULL, " +
                        "`archivedAt` INTEGER)",
                )
                db.execSQL("ALTER TABLE `readings` ADD COLUMN `rollId` INTEGER")
                db.execSQL("ALTER TABLE `readings` ADD COLUMN `filmStock` TEXT")
                db.execSQL("ALTER TABLE `readings` ADD COLUMN `source` TEXT")
                db.execSQL("ALTER TABLE `readings` ADD COLUMN `zone` INTEGER")
            }
        }

        @Volatile
        private var INSTANCE: LumeterDatabase? = null

        fun getDatabase(context: Context): LumeterDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    LumeterDatabase::class.java,
                    "lumeter_database",
                )
                    .addMigrations(MIGRATION_1_2)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}

@Dao
interface ReadingDao {
    @Query("SELECT * FROM readings ORDER BY timestamp DESC")
    fun getAllReadings(): Flow<List<Reading>>

    @Query("SELECT * FROM readings WHERE id = :id")
    suspend fun getReadingById(id: Long): Reading?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertReading(reading: Reading)

    @Delete
    suspend fun deleteReading(reading: Reading)

    @Query("DELETE FROM readings WHERE id = :id")
    suspend fun deleteReadingById(id: Long)

    @Query("DELETE FROM readings")
    suspend fun deleteAllReadings()

    @Query("SELECT COUNT(*) FROM readings")
    suspend fun getReadingCount(): Int
}

@Dao
interface FilmRollDao {
    /** Active rolls first (newest load first), archived below them. */
    @Query(
        "SELECT * FROM rolls ORDER BY CASE WHEN archivedAt IS NULL THEN 0 ELSE 1 END, " +
            "loadedAt DESC",
    )
    fun getAllRolls(): Flow<List<FilmRoll>>

    @Insert
    suspend fun insertRoll(roll: FilmRoll): Long

    @Query("UPDATE rolls SET framesShot = framesShot + 1 WHERE id = :id")
    suspend fun incrementFrames(id: Long)

    @Query("UPDATE rolls SET archivedAt = :at WHERE id = :id")
    suspend fun setArchived(id: Long, at: Long?)

    @Query("DELETE FROM rolls WHERE id = :id")
    suspend fun deleteRollById(id: Long)

    @Query("SELECT * FROM rolls WHERE id = :id")
    suspend fun getRoll(id: Long): FilmRoll?
}

class ReadingRepository(private val db: LumeterDatabase) {
    private val readingDao = db.readingDao()
    private val rollDao = db.filmRollDao()

    val allReadings: Flow<List<Reading>> = readingDao.getAllReadings()
    val allRolls: Flow<List<FilmRoll>> = rollDao.getAllRolls()

    /** One logged frame = one reading plus the roll's shot counter, atomically. */
    suspend fun logReading(reading: Reading, rollId: Long?) {
        db.withTransaction {
            readingDao.insertReading(reading)
            rollId?.let { rollDao.incrementFrames(it) }
        }
    }

    suspend fun createRoll(roll: FilmRoll): Long = rollDao.insertRoll(roll)

    suspend fun setRollArchived(id: Long, archived: Boolean) {
        rollDao.setArchived(id, if (archived) System.currentTimeMillis() else null)
    }

    suspend fun deleteRollById(id: Long) {
        rollDao.deleteRollById(id)
    }

    suspend fun deleteReadingById(id: Long) {
        readingDao.deleteReadingById(id)
    }

    suspend fun deleteAllReadings() {
        readingDao.deleteAllReadings()
    }

    suspend fun getReadingCount(): Int {
        return readingDao.getReadingCount()
    }
}
