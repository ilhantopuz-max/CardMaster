package com.cardmaster

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "cards")
data class BizCard(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String = "",
    val company: String = "",
    val jobTitle: String = "",
    val mobile: String = "",
    val phone: String = "",
    val email: String = "",
    val website: String = "",
    val address: String = "",
    val city: String = "",
    val note: String = "",
    val logoPath: String = "",
    val photoPath: String = "",
    val createdAt: Long = System.currentTimeMillis()
)

@Dao
interface CardDao {
    @Query("SELECT * FROM cards ORDER BY createdAt DESC")
    fun all(): Flow<List<BizCard>>

    @Query("SELECT * FROM cards")
    suspend fun snapshot(): List<BizCard>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(c: BizCard): Long

    @Delete
    suspend fun delete(c: BizCard)
}

@Database(entities = [BizCard::class], version = 1, exportSchema = false)
abstract class AppDb : RoomDatabase() {
    abstract fun dao(): CardDao

    companion object {
        @Volatile private var inst: AppDb? = null
        fun get(c: Context): AppDb = inst ?: synchronized(this) {
            inst ?: Room.databaseBuilder(c.applicationContext, AppDb::class.java, "cards.db")
                .build().also { inst = it }
        }
    }
}
