package com.screenshotbrain

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "items", indices = [Index(value = ["source"], unique = true)])
data class Item(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val source: String,
    val uri: String?,
    val text: String,
    val type: String,
    val detail: String,
    val addedAt: Long,
    val hidden: Boolean = false,
    @ColumnInfo(defaultValue = "") val folder: String = ""
)

@Entity(tableName = "folders")
data class Folder(@PrimaryKey val name: String, val createdAt: Long)

@Dao
interface ItemDao {
    @Query(
        "SELECT * FROM items WHERE hidden = 0 AND (:q = '' OR text LIKE '%' || :q || '%' " +
            "OR type LIKE '%' || :q || '%' OR detail LIKE '%' || :q || '%' " +
            "OR folder LIKE '%' || :q || '%') ORDER BY addedAt DESC"
    )
    fun search(q: String): Flow<List<Item>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(item: Item): Long

    @Query("SELECT source FROM items")
    suspend fun sources(): List<String>

    @Query("SELECT * FROM items")
    suspend fun allItems(): List<Item>

    @Query("UPDATE items SET type = :type, detail = :detail, folder = CASE WHEN folder = type THEN :type ELSE folder END WHERE id = :id")
    suspend fun reclass(id: Long, type: String, detail: String)

    @Query("UPDATE items SET hidden = 1 WHERE id IN (:ids)")
    suspend fun hide(ids: List<Long>)

    @Query("DELETE FROM items WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    @Query("UPDATE items SET folder = :folder WHERE id IN (:ids)")
    suspend fun move(ids: List<Long>, folder: String)

    @Query("SELECT * FROM folders ORDER BY createdAt")
    fun customFolders(): Flow<List<Folder>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addFolder(f: Folder)

    @Query("DELETE FROM folders WHERE name = :name")
    suspend fun removeFolder(name: String)

    @Query("UPDATE items SET folder = type WHERE folder = :name")
    suspend fun releaseFolder(name: String)
}

@Database(entities = [Item::class, Folder::class], version = 3, exportSchema = false)
abstract class AppDb : RoomDatabase() {
    abstract fun dao(): ItemDao

    companion object {
        private val M2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE items ADD COLUMN folder TEXT NOT NULL DEFAULT ''")
                db.execSQL("UPDATE items SET folder = type")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `folders` (`name` TEXT NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL, PRIMARY KEY(`name`))"
                )
            }
        }

        @Volatile private var inst: AppDb? = null
        fun get(c: Context): AppDb = inst ?: synchronized(this) {
            inst ?: Room.databaseBuilder(c.applicationContext, AppDb::class.java, "sb.db")
                .addMigrations(M2_3)
                .fallbackToDestructiveMigration()
                .build().also { inst = it }
        }
    }
}
