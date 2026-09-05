package com.luckyalanzhou.barcodegenerator

import android.content.ContentResolver
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

private const val INTERCHANGE_FORMAT = "BarcodeGeneratorInterchange"
private const val INTERCHANGE_VERSION = 1

data class InterchangeFavorite(
    val id: String?,
    val name: String,
    val rootFolder: String,
    val subFolder: String,
    val type: String,
    val time: Long,
    val texts: List<String>
) {
    val folder: String get() = listOf(rootFolder, subFolder).filter { it.isNotBlank() }.joinToString("/")
}

data class InterchangeBackup(val favorites: List<InterchangeFavorite>, val folders: List<String>)

data class TransferEntities(
    val items: List<CodeItemEntity>,
    val groups: List<FavoriteGroupEntity>,
    val links: List<FavoriteGroupItemEntity>,
    val folders: List<FavoriteFolderEntity>
)

object FavoritesTransferManager {
    fun export(resolver: ContentResolver, uri: Uri, groups: List<FavoriteGroupEntity>, links: List<FavoriteGroupItemEntity>, items: List<CodeItemEntity>, folders: List<FavoriteFolderEntity>) {
        val itemById = items.associateBy { it.id }
        val linksByGroup = links.groupBy { it.groupId }
        val payload = JSONObject().apply {
            put("folders", JSONArray().apply { folders.map { it.name }.distinct().filter { it.isNotBlank() }.forEach(::put) })
            put("favorites", JSONArray().apply {
                groups.forEach { group ->
                    val groupItems = linksByGroup[group.id].orEmpty().mapNotNull { itemById[it.itemId] }
                    val types = groupItems.map { toTransferType(it.format) }.distinct()
                    require(types.size <= 1) { "收藏“${group.name}”包含多种条码格式，暂不支持跨平台导出" }
                    val parts = splitFolder(group.folder)
                    put(JSONObject().apply {
                        put("id", group.id.toString()); put("name", group.name); put("rootFolder", parts.first); put("subFolder", parts.second)
                        put("type", types.firstOrNull() ?: "code128"); put("time", group.savedAt); put("texts", JSONArray(groupItems.map { it.text }))
                    })
                }
            })
        }.toString()
        val root = JSONObject().apply {
            put("format", INTERCHANGE_FORMAT); put("version", INTERCHANGE_VERSION); put("exportedAt", System.currentTimeMillis()); put("payload", payload); put("sha256", sha256(payload))
        }
        resolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(root.toString()) } ?: error("无法创建收藏备份文件")
    }

    fun restore(resolver: ContentResolver, uri: Uri): InterchangeBackup {
        val root = resolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }?.let(::JSONObject) ?: error("无法读取收藏备份文件")
        require(root.optString("format") == INTERCHANGE_FORMAT && root.optInt("version") == INTERCHANGE_VERSION) { "不支持的跨平台收藏备份文件" }
        val hasPayload = root.has("payload")
        val payload = if (hasPayload) root.getString("payload") else root.toString()
        if (hasPayload) require(root.optString("sha256").equals(sha256(payload), true)) { "收藏备份文件校验失败，可能已损坏" }
        val data = if (hasPayload) JSONObject(payload) else root
        val folders = data.optJSONArray("folders").toFolderPaths()
        val favorites = data.optJSONArray("favorites").toObjects { value ->
            val legacyPath = value.optString("folder", "").trim()
            val rootFolder = value.optString("rootFolder", legacyPath.substringBefore('/')).trim()
            val subFolder = value.optString("subFolder", legacyPath.substringAfter('/', "")).trim()
            val name = value.optString("name").trim()
            val texts = value.optJSONArray("texts").toStrings().map { it.trim() }.filter { it.isNotEmpty() }
            require(name.isNotBlank()) { "跨平台收藏缺少文件名" }
            require(rootFolder.isBlank() || !rootFolder.contains('/')) { "一级文件夹格式无效" }
            require(subFolder.isBlank() || !subFolder.contains('/')) { "二级文件夹格式无效" }
            require(texts.isNotEmpty()) { "收藏“$name”没有条码内容" }
            InterchangeFavorite(value.optString("id").takeIf { it.isNotBlank() }, name, rootFolder, subFolder, toTransferType(value.optString("type", "code128")), value.optLong("time", System.currentTimeMillis()), texts)
        }
        require(favorites.map { Triple(it.folder, it.name, it.texts) }.distinct().size == favorites.size) { "跨平台备份中包含重复收藏" }
        return InterchangeBackup(favorites, folders)
    }

    fun appendEntities(backup: InterchangeBackup, existingItems: List<CodeItemEntity>, existingGroups: List<FavoriteGroupEntity>, existingLinks: List<FavoriteGroupItemEntity>): TransferEntities {
        var nextItemId = (existingItems.maxOfOrNull { it.id } ?: 0L) + 1L
        var nextGroupId = (existingGroups.maxOfOrNull { it.id } ?: 0L) + 1L
        val existingGroupItems = existingGroups.associate { group -> group.id to existingLinks.filter { it.groupId == group.id }.mapNotNull { link -> existingItems.firstOrNull { it.id == link.itemId }?.text } }
        val existingKeys = existingGroups.map { group -> Triple(group.folder, group.name, existingGroupItems[group.id].orEmpty()) }.toMutableSet()
        val items = mutableListOf<CodeItemEntity>(); val groups = mutableListOf<FavoriteGroupEntity>(); val links = mutableListOf<FavoriteGroupItemEntity>()
        backup.favorites.forEach { favorite ->
            val key = Triple(favorite.folder, favorite.name, favorite.texts)
            if (!existingKeys.add(key)) return@forEach
            val group = FavoriteGroupEntity(nextGroupId++, favorite.folder, favorite.name, favorite.time)
            val groupItems = favorite.texts.map { text -> CodeItemEntity(nextItemId++, text, toAndroidFormat(favorite.type), favorite.time, true, favorite.folder, false) }
            groups += group; items += groupItems; links += groupItems.map { FavoriteGroupItemEntity(group.id, it.id) }
        }
        val folderNames = (backup.folders + backup.favorites.map { it.folder }).filter { it.isNotBlank() }.distinct()
        return TransferEntities(items, groups, links, folderNames.map(::FavoriteFolderEntity))
    }

    private fun splitFolder(folder: String): Pair<String, String> {
        val parts = folder.split('/').filter { it.isNotBlank() }
        require(parts.size <= 2) { "文件夹“$folder”超过两级，无法跨平台导出" }
        return (parts.getOrNull(0) ?: "") to (parts.getOrNull(1) ?: "")
    }
    private fun toTransferType(format: String): String = when (format.trim().lowercase()) {
        "qr", "qr code" -> "qr"; "code128", "code 128-b" -> "code128"; "code39", "code 39" -> "code39"; "ean13", "ean-13" -> "ean13"; "ean8", "ean-8" -> "ean8"; "upca", "upc-a" -> "upca"; "itf14", "itf-14", "itf" -> "itf14"; "codabar" -> "codabar"; else -> error("不支持的条码格式：$format")
    }
    private fun toAndroidFormat(type: String): String = when (toTransferType(type)) {
        "qr" -> "QR Code"; "code128" -> "Code 128-B"; "code39" -> "Code 39"; "ean13" -> "EAN-13"; "ean8" -> "EAN-8"; "upca" -> "UPC-A"; "itf14" -> "ITF-14"; else -> "Codabar"
    }
    private fun JSONArray?.toStrings(): List<String> = if (this == null) emptyList() else (0 until length()).map { getString(it) }
    private fun <T> JSONArray?.toObjects(mapper: (JSONObject) -> T): List<T> = if (this == null) emptyList() else (0 until length()).map { mapper(getJSONObject(it)) }
    private fun JSONArray?.toFolderPaths(): List<String> = if (this == null) emptyList() else (0 until length()).flatMap { index ->
        when (val value = opt(index)) {
            is String -> listOf(value)
            is JSONObject -> {
                val root = value.optString("name").trim()
                val children = value.optJSONArray("children").toStrings().map { it.trim() }.filter { it.isNotBlank() }
                listOf(root).filter { it.isNotBlank() } + children.map { child -> if (root.isBlank()) child else "$root/$child" }
            }
            else -> emptyList()
        }
    }.filter { it.isNotBlank() }.distinct()
    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
