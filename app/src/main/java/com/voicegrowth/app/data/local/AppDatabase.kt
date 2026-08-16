package com.voicegrowth.app.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.voicegrowth.app.data.local.dao.RecordingDao
import com.voicegrowth.app.data.local.entity.RecordingEntity
import com.voicegrowth.app.data.model.ProcessingStatus
import com.voicegrowth.app.data.model.RecordingSource

class Converters {
    @TypeConverter fun fromStatus(status: ProcessingStatus): String = status.name
    @TypeConverter fun toStatus(value: String): ProcessingStatus =
        runCatching { ProcessingStatus.valueOf(value) }.getOrDefault(ProcessingStatus.PENDING)

    @TypeConverter fun fromSource(source: RecordingSource): String = source.name
    @TypeConverter fun toSource(value: String): RecordingSource =
        runCatching { RecordingSource.valueOf(value) }.getOrDefault(RecordingSource.CALL_RECORDING)
}

@Database(entities = [RecordingEntity::class], version = 2, exportSchema = false)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun recordingDao(): RecordingDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE recordings ADD COLUMN driveAudioFileId TEXT")
                db.execSQL("DELETE FROM recordings WHERE id NOT IN (SELECT MIN(id) FROM recordings GROUP BY uriString)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_recordings_uriString ON recordings(uriString)")
            }
        }

        fun getInstance(context: Context): AppDatabase = INSTANCE ?: synchronized(this) {
            INSTANCE ?: Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "voicegrowth_database"
            )
                .addMigrations(MIGRATION_1_2)
                .build()
                .also { INSTANCE = it }
        }
    }
}
