package com.luckyalanzhou.barcodegenerator

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase

@Entity(tableName = "code_items")
data class CodeItemEntity(
    @PrimaryKey val id: Long,
    val text: String,
    val format: String,
    val createdAt: Long,
    val favorite: Boolean,
    val folder: String,
    val inHistory: Boolean
)

@Entity(tableName = "favorite_groups")
data class FavoriteGroupEntity(
    @PrimaryKey val id: Long,
    val folder: String,
    val name: String,
    val savedAt: Long
)

@Entity(tableName = "favorite_group_items", primaryKeys = ["groupId", "itemId"])
data class FavoriteGroupItemEntity(val groupId: Long, val itemId: Long)

@Entity(tableName = "favorite_folders")
data class FavoriteFolderEntity(@PrimaryKey val name: String)

@Dao
interface BarcodeDao {
    @Query("SELECT * FROM code_items ORDER BY createdAt DESC, id DESC") suspend fun loadItems(): List<CodeItemEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun saveItems(items: List<CodeItemEntity>)
    @Query("DELETE FROM code_items") suspend fun clearItems()

    @Query("SELECT * FROM favorite_groups ORDER BY savedAt DESC, id DESC") suspend fun loadGroups(): List<FavoriteGroupEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun saveGroups(groups: List<FavoriteGroupEntity>)
    @Query("DELETE FROM favorite_groups") suspend fun clearGroups()
    @Query("SELECT * FROM favorite_group_items") suspend fun loadGroupItems(): List<FavoriteGroupItemEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun saveGroupItems(items: List<FavoriteGroupItemEntity>)
    @Query("DELETE FROM favorite_group_items") suspend fun clearGroupItems()

    @Query("SELECT * FROM favorite_folders ORDER BY name") suspend fun loadFolders(): List<FavoriteFolderEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun saveFolders(folders: List<FavoriteFolderEntity>)
    @Query("DELETE FROM favorite_folders") suspend fun clearFolders()

}

@Database(
    entities = [CodeItemEntity::class, FavoriteGroupEntity::class, FavoriteGroupItemEntity::class, FavoriteFolderEntity::class],
    version = 1,
    exportSchema = false
)
abstract class BarcodeDatabase : RoomDatabase() {
    abstract fun barcodeDao(): BarcodeDao

    companion object {
        fun create(context: Context): BarcodeDatabase = Room.databaseBuilder(
            context.applicationContext,
            BarcodeDatabase::class.java,
            "barcode_generator.db"
        ).build()
    }
}
