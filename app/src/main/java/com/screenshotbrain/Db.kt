package com.screenshotbrain

import android.content.Context
import androidx.room.*
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
    val hidden: Boolean = false
)

@Dao
interface ItemDao {
    @Query(
        "SELECT * FROM items WHERE hidden = 0 AND (:q = '' OR text LIKE '%' || :q || '%' " +
            "OR type LIKE '%' || :q || '%' OR detail LIKE '%' || :q || '%') " +
            "ORDER BY addedAt DESC"
    )
    fun search(q: String): Flow<List<Item>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(item: Item): Long

    @Query("SELECT source FROM items")
    suspend fun sources(): List<String>

    /** Soft delete: keeps the row so the scanner doesn't re-import the same screenshot. */
    @Query("UPDATE items SET hidden = 1 WHERE id IN (:ids)")
    suspend fun hide(ids: List<Long>)

    /** Hard delete: used when the image file itself was deleted from the phone. */
    @Query("DELETE FROM items WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)
}

@Database(entities = [Item::class], version = 2, exportSchema = false)
abstract class AppDb : RoomDatabase() {
    abstract fun dao(): ItemDao

    companion object {
        @Volatile private var inst: AppDb? = null
        fun get(c: Context): AppDb = inst ?: synchronized(this) {
            inst ?: Room.databaseBuilder(c.applicationContext, AppDb::class.java, "sb.db")
                .fallbackToDestructiveMigration()
                .build().also { inst = it }
        }
    }
}
