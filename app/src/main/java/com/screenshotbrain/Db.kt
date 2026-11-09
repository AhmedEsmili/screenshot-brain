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
    val addedAt: Long
)

@Dao
interface ItemDao {
    @Query(
        "SELECT * FROM items WHERE (:q = '' OR text LIKE '%' || :q || '%' " +
            "OR type LIKE '%' || :q || '%' OR detail LIKE '%' || :q || '%') " +
            "ORDER BY addedAt DESC"
    )
    fun search(q: String): Flow<List<Item>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(item: Item): Long

    @Query("SELECT source FROM items")
    suspend fun sources(): List<String>
}

@Database(entities = [Item::class], version = 1, exportSchema = false)
abstract class AppDb : RoomDatabase() {
    abstract fun dao(): ItemDao

    companion object {
        @Volatile private var inst: AppDb? = null
        fun get(c: Context): AppDb = inst ?: synchronized(this) {
            inst ?: Room.databaseBuilder(c.applicationContext, AppDb::class.java, "sb.db")
                .build().also { inst = it }
        }
    }
}
