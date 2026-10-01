package com.lumeter.data

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

/** A saved metering reading. */
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
)

@Database(entities = [Reading::class], version = 1, exportSchema = false)
abstract class LumeterDatabase : RoomDatabase() {
    abstract fun readingDao(): ReadingDao

    companion object {
        @Volatile
        private var INSTANCE: LumeterDatabase? = null

        fun getDatabase(context: Context): LumeterDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    LumeterDatabase::class.java,
                    "lumeter_database"
                ).build()
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

class ReadingRepository(private val readingDao: ReadingDao) {
    val allReadings: Flow<List<Reading>> = readingDao.getAllReadings()
    
    suspend fun insertReading(reading: Reading) {
        readingDao.insertReading(reading)
    }
    
    suspend fun deleteReading(reading: Reading) {
        readingDao.deleteReading(reading)
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
