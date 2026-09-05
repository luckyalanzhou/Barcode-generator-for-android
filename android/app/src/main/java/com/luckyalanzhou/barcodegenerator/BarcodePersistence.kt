package com.luckyalanzhou.barcodegenerator

import android.Manifest
import android.app.AlertDialog
import android.content.*
import android.content.pm.PackageManager
import android.graphics.*
import android.net.Uri
import android.os.Bundle
import android.text.*
import android.view.*
import android.widget.*
import androidx.appcompat.app.AppCompatDelegate
import androidx.lifecycle.lifecycleScope
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.withLock
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.zxing.*
import com.google.zxing.common.HybridBinarizer
import org.json.*
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.*

internal suspend fun MainActivity.saveFavoriteFoldersOnIo(folders: List<FavoriteFolderEntity>) {
    database.withTransaction {
        dao.clearFolders()
        dao.saveFolders(folders)
    }
}

internal suspend fun MainActivity.saveAllFavoritesOnIo(
    itemSnapshot: List<CodeItemEntity>,
    groupSnapshot: List<FavoriteGroupEntity>,
    groupItemSnapshot: List<FavoriteGroupItemEntity>,
    folderSnapshot: List<FavoriteFolderEntity>
) {
    database.withTransaction {
        dao.clearGroupItems(); dao.clearGroups(); dao.clearItems(); dao.clearFolders()
        dao.saveItems(itemSnapshot); dao.saveGroups(groupSnapshot)
        dao.saveGroupItems(groupItemSnapshot); dao.saveFolders(folderSnapshot)
    }
}

internal suspend fun MainActivity.loadFavoriteFoldersOnIo() {
    favoriteFolders.clear()
    favoriteFolders.addAll((dao.loadFolders().map { it.name } + favoriteGroups.map { it.folder }).filter { it.isNotBlank() && it != "默认" }.distinct().sorted())
}

internal suspend fun MainActivity.loadItemsOnIo() {
    items.clear()
    items.addAll(dao.loadItems().map { CodeItem(it.id, it.text, it.format, it.createdAt, it.favorite, it.folder.takeUnless { folder -> folder == "默认" } ?: "", it.inHistory) })
}

internal suspend fun MainActivity.loadFavoriteGroupsOnIo() {
    favoriteGroups.clear()
    val itemIds = dao.loadGroupItems().groupBy { it.groupId }
    favoriteGroups.addAll(dao.loadGroups().map { group ->
        FavoriteGroup(group.id, group.folder.takeUnless { it == "默认" } ?: "", group.name, group.savedAt, itemIds[group.id].orEmpty().map { it.itemId }.toMutableList())
    })
}

private fun MainActivity.itemSnapshot() = items.take(MainActivity.MAX_HISTORY_ITEMS).map { CodeItemEntity(it.id, it.text, it.format, it.createdAt, it.favorite, it.folder, it.inHistory) }
private fun MainActivity.groupSnapshot() = favoriteGroups.take(MainActivity.MAX_FAVORITE_GROUPS).map { FavoriteGroupEntity(it.id, it.folder, it.name, it.savedAt) }
private fun MainActivity.groupItemSnapshot() = favoriteGroups.take(MainActivity.MAX_FAVORITE_GROUPS).flatMap { group -> group.itemIds.map { FavoriteGroupItemEntity(group.id, it) } }
private fun MainActivity.folderSnapshot() = favoriteFolders.filter { it.isNotBlank() }.distinct().map(::FavoriteFolderEntity)

internal fun MainActivity.saveItems() {
    val snapshot = itemSnapshot()
    lifecycleScope.launch(Dispatchers.IO) { databaseMutex.withLock { database.withTransaction { dao.clearItems(); dao.saveItems(snapshot) } } }
}
internal fun MainActivity.saveFavoriteGroups() {
    val groups = groupSnapshot(); val links = groupItemSnapshot()
    lifecycleScope.launch(Dispatchers.IO) { databaseMutex.withLock { database.withTransaction { dao.clearGroupItems(); dao.clearGroups(); dao.saveGroups(groups); dao.saveGroupItems(links) } } }
}
internal fun MainActivity.saveFavoriteFolders() {
    val folders = folderSnapshot()
    lifecycleScope.launch(Dispatchers.IO) { databaseMutex.withLock { saveFavoriteFoldersOnIo(folders) } }
}
internal fun MainActivity.saveAllFavorites() {
    val items = itemSnapshot(); val groups = groupSnapshot(); val links = groupItemSnapshot(); val folders = folderSnapshot()
    lifecycleScope.launch(Dispatchers.IO) { databaseMutex.withLock { saveAllFavoritesOnIo(items, groups, links, folders) } }
}
internal fun MainActivity.loadItems() = lifecycleScope.launch(Dispatchers.IO) { databaseMutex.withLock { loadItemsOnIo() } }
internal fun MainActivity.loadFavoriteGroups() = lifecycleScope.launch(Dispatchers.IO) { databaseMutex.withLock { loadFavoriteGroupsOnIo() } }
internal fun MainActivity.loadFavoriteFolders() = lifecycleScope.launch(Dispatchers.IO) { databaseMutex.withLock { loadFavoriteFoldersOnIo() } }

internal suspend fun MainActivity.migrateLegacySettingsIfNeeded() {
        if (settingsStore.get(SettingsStore.SETTINGS_MIGRATED, false)) return
        val migratedStyle = StyleSettings(
            barColor = legacyPrefs.getInt("style_bar_color", Color.BLACK), bgColor = legacyPrefs.getInt("style_bg_color", Color.WHITE),
            showText = legacyPrefs.getBoolean("style_show_text", true), textPosition = legacyPrefs.getString("style_text_position", "bottom") ?: "bottom",
            textSize = legacyPrefs.getFloat("style_text_size", 14f), barHeight = legacyPrefs.getInt("style_bar_height", 60), barWidth = legacyPrefs.getFloat("style_bar_width", 200f),
            margin = legacyPrefs.getInt("style_margin", 6), showFormat = legacyPrefs.getBoolean("style_show_format", true), colorScheme = legacyPrefs.getString("style_color_scheme", "system") ?: "system"
        )
        settingsStore.saveStyle(migratedStyle).join()
        legacyPrefs.getString("last_update_error", "")?.takeIf { it.isNotBlank() }?.let(settingsStore::setUpdateError)
        settingsStore.markMigrated().join()
        legacyPrefs.edit().remove("style_bar_color").remove("style_bg_color").remove("style_show_text").remove("style_text_position").remove("style_text_size").remove("style_bar_height").remove("style_bar_width").remove("style_margin").remove("style_show_format").remove("style_transparent_background").remove("style_color_scheme").remove("last_update_error").apply()
    }


internal suspend fun MainActivity.migrateLegacyDataIfNeeded() = databaseMutex.withLock {
        if (legacyPrefs.getBoolean("room_data_migrated", false)) return
        val legacyItems = runCatching { JSONArray(legacyPrefs.getString("items", "[]")) }.getOrDefault(JSONArray())
        val legacyGroups = runCatching { JSONArray(legacyPrefs.getString("favorite_groups", "[]")) }.getOrDefault(JSONArray())
        val legacyFolders = legacyPrefs.getStringSet("favorite_folders", emptySet()).orEmpty()
        if (dao.loadItems().isEmpty()) {
            val migratedItems = (0 until legacyItems.length()).mapNotNull { index -> runCatching { legacyItems.getJSONObject(index) }.getOrNull()?.let { item ->
                CodeItemEntity(item.getLong("id"), item.getString("text"), item.getString("format"), item.optLong("createdAt", item.getLong("id")), item.optBoolean("favorite"), item.optString("folder", "默认"), item.optBoolean("inHistory", true))
            } }
            dao.saveItems(migratedItems)
        }
        if (dao.loadGroups().isEmpty()) {
            val groups = mutableListOf<FavoriteGroupEntity>()
            val links = mutableListOf<FavoriteGroupItemEntity>()
            for (index in 0 until legacyGroups.length()) runCatching { legacyGroups.getJSONObject(index) }.getOrNull()?.let { group ->
                val groupId = group.getLong("id")
                groups.add(FavoriteGroupEntity(groupId, group.optString("folder", "默认"), group.optString("name", "未命名收藏"), group.optLong("savedAt", groupId)))
                val itemIds = group.optJSONArray("itemIds") ?: JSONArray()
                for (itemIndex in 0 until itemIds.length()) links.add(FavoriteGroupItemEntity(groupId, itemIds.getLong(itemIndex)))
            }
            dao.saveGroups(groups)
            dao.saveGroupItems(links)
        }
        if (dao.loadFolders().isEmpty()) dao.saveFolders(legacyFolders.filter { it.isNotBlank() && it != "默认" }.map(::FavoriteFolderEntity))
        legacyPrefs.edit().putBoolean("room_data_migrated", true).remove("items").remove("favorite_groups").remove("favorite_folders").remove("next_item_id").remove("next_group_id").apply()
    }
