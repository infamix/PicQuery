// FILE: app/src/main/java/me/grey/picquery/data/AppDatabase.kt
package me.grey.picquery.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import me.grey.picquery.data.dao.AlbumDao
import me.grey.picquery.data.dao.EmbeddingDao
import me.grey.picquery.data.dao.ImageSimilarityDao
import me.grey.picquery.data.model.Album
import me.grey.picquery.data.model.Embedding
import me.grey.picquery.data.model.ImageSimilarity

@Database(entities = [Embedding::class, Album::class, ImageSimilarity::class], version = 4, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {

    abstract fun embeddingDao(): EmbeddingDao

    abstract fun albumDao(): AlbumDao

    abstract fun imageSimilarityDao(): ImageSimilarityDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "app-db")
                    .addMigrations(MIGRATION_2_3, MIGRATION_3_4)
                    .fallbackToDestructiveMigrationFrom(1)
                    .build()
                INSTANCE = instance
                instance
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // No schema changes
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS image_similarity (
                        photo_id INTEGER PRIMARY KEY NOT NULL,
                        similarity_score REAL NOT NULL
                    );
                """.trimIndent())
            }
        }
    }
}