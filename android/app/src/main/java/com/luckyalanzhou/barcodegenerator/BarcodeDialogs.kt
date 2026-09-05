package com.luckyalanzhou.barcodegenerator

import android.Manifest
import android.app.AlertDialog
import android.content.*
import android.content.pm.PackageManager
import android.graphics.*
import android.net.Uri
import android.os.Bundle
import androidx.lifecycle.lifecycleScope
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.withLock
import android.text.*
import android.view.*
import android.widget.*
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.FileProvider
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
import kotlin.math.roundToInt

internal fun MainActivity.checkForUpdates(silent: Boolean = false) {
    lifecycleScope.launch(Dispatchers.IO) {
        try {
            val connection = (URL("https://api.github.com/repos/luckyalanzhou/Barcode-generator-for-android/releases?per_page=100").openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"; connectTimeout = 8000; readTimeout = 8000
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "BarcodeGenerator/${BuildConfig.VERSION_NAME}")
            }
            if (connection.responseCode !in 200..299) throw IllegalStateException("GitHub HTTP ${connection.responseCode}")
            val releases = connection.inputStream.bufferedReader().use { JSONArray(it.readText()) }.also { connection.disconnect() }
            val release = (0 until releases.length())
                .mapNotNull { releases.optJSONObject(it) }
                .filter { it.optString("tag_name").startsWith("android-v") }
                .maxWithOrNull(Comparator { left, right ->
                    compareVersions(parseAppVersion(left.optString("tag_name")) ?: "0.0.0", parseAppVersion(right.optString("tag_name")) ?: "0.0.0")
                })
            val tag = release?.optString("tag_name")?.takeIf { it.isNotBlank() }
            val apkAsset = release?.optJSONArray("assets")?.let { assets -> (0 until assets.length()).mapNotNull { assets.optJSONObject(it) }.firstOrNull { it.optString("name").endsWith(".apk", true) } }
            val apkUrl = apkAsset?.optString("browser_download_url")?.takeIf { it.isNotBlank() }
            val expectedSize = apkAsset?.optLong("size", 0L)?.takeIf { it > 0L }
            val expectedSha256 = apkAsset?.optString("digest")?.removePrefix("sha256:")?.trim()?.lowercase(Locale.US)?.takeIf { it.matches(Regex("[0-9a-f]{64}")) }
            withContext(Dispatchers.Main) {
                val releaseTag = tag ?: run { if (!silent) toast("暂时无法获取更新信息"); return@withContext }
                val downloadUrl = apkUrl ?: run { if (!silent) toast("暂时无法获取更新信息"); return@withContext }
                val latest = parseAppVersion(releaseTag) ?: run { if (!silent) toast("版本信息格式不正确"); return@withContext }
                val updateAvailable = compareVersions(latest, BuildConfig.VERSION_NAME) > 0
                availableUpdateUrl = if (updateAvailable) downloadUrl else null
                availableUpdateExpectedSize = if (updateAvailable) expectedSize else null
                availableUpdateSha256 = if (updateAvailable) expectedSha256 else null
                if (page == "settings") render()
                if (updateAvailable && !updateDialogShowing) {
                    updateDialogShowing = true
                    val updateDialog = AlertDialog.Builder(this@checkForUpdates).setTitle("发现新版本").setMessage("检测到版本 $latest，是否立即更新？")
                        .setNegativeButton("稍后") { _, _ -> updateDialogShowing = false }
                        .setPositiveButton("立即更新") { _, _ -> updateDialogShowing = false; downloadAndInstall(downloadUrl, expectedSize, expectedSha256) }
                        .setOnCancelListener { updateDialogShowing = false }.create()
                    showIos26Dialog(updateDialog)
                } else if (!updateAvailable && !silent) toast("当前已是最新版本")
            }
        } catch (_: Exception) { if (!silent) withContext(Dispatchers.Main) { toast("检查更新失败，请稍后重试") } }
    }
}

internal fun MainActivity.downloadAndInstall(apkUrl: String, expectedSize: Long? = availableUpdateExpectedSize, expectedSha256: String? = availableUpdateSha256) {
    if (updateDownloadRunning) return
    updateDownloadRunning = true
    val progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
    val status = TextView(this).apply { text = "准备下载…"; textSize = 14f; setTextColor(secondaryText()); setPadding(0, dp(10), 0, 0) }
    val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(8), dp(24), dp(8)); addView(progress, LinearLayout.LayoutParams(-1, dp(8))); addView(status) }
    val dialog = AlertDialog.Builder(this).setTitle("下载更新").setView(box).setNegativeButton("取消", null).create()
    lateinit var job: kotlinx.coroutines.Job
    dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener { job.cancel(); dialog.dismiss() } }
    showIos26Dialog(dialog)
    job = lifecycleScope.launch(Dispatchers.IO) {
        val temp = File(cacheDir, "barcode-generator-update.apk.part")
        val official = File(cacheDir, "barcode-generator-update.apk")
        var connection: HttpURLConnection? = null
        try {
            val limit = UpdateSecurity.MAX_APK_DOWNLOAD_BYTES
            require(expectedSha256 != null) { "该版本缺少 SHA-256 校验信息，无法安全更新" }
            require(expectedSize == null || expectedSize <= limit) { "更新包超过 500 MB 限制" }
            connection = URL(apkUrl).openConnection() as HttpURLConnection
            connection!!.apply { connectTimeout = 15000; readTimeout = 30000; instanceFollowRedirects = true; setRequestProperty("User-Agent", "BarcodeGenerator/${BuildConfig.VERSION_NAME}") }
            if (connection!!.responseCode !in 200..299) throw IllegalStateException("HTTP ${connection!!.responseCode}")
            val total = connection!!.contentLengthLong.takeIf { it > 0 } ?: expectedSize
            require(total == null || total <= limit) { "更新包超过 500 MB 限制" }
            temp.delete()
            connection!!.inputStream.use { input -> temp.outputStream().use { output ->
                val buffer = ByteArray(16 * 1024); var done = 0L; var count: Int
                while (input.read(buffer).also { count = it } != -1) {
                    ensureActive(); require(done + count <= limit) { "更新包超过 500 MB 限制" }
                    output.write(buffer, 0, count); done += count
                    withContext(Dispatchers.Main) { if (total != null) { progress.isIndeterminate = false; progress.progress = (done * 100 / total).toInt().coerceIn(0, 100); status.text = "已下载 ${progress.progress}%" } else { progress.isIndeterminate = true; status.text = "正在下载… ${done / 1024} KB" } }
                }
            } }
            require(temp.isFile && temp.length() > 0L) { "APK 为空" }
            require(expectedSize == null || temp.length() == expectedSize) { "文件大小校验失败：${temp.length()} / $expectedSize" }
            val digest = MessageDigest.getInstance("SHA-256")
            val actual = temp.inputStream().use { input -> val buffer = ByteArray(16 * 1024); var count: Int; while (input.read(buffer).also { count = it } != -1) digest.update(buffer, 0, count); digest.digest().joinToString("") { "%02x".format(it) } }
            require(actual.equals(expectedSha256, true)) { "SHA-256 校验失败" }
            validateDownloadedApk(temp)
            official.delete(); require(temp.renameTo(official)) { "无法保存更新文件" }
            cacheDir.listFiles()?.filter { it.name.startsWith("barcode-generator-update") && it != official }?.forEach { it.delete() }
            withContext(Dispatchers.Main) { dialog.dismiss(); installApk(official) }
        } catch (error: Exception) {
            temp.delete()
            withContext(Dispatchers.Main) { dialog.dismiss(); if (error !is kotlinx.coroutines.CancellationException) { val reason = error.message ?: "未知错误"; settingsStore.setUpdateError(reason); AlertDialog.Builder(this@downloadAndInstall).setTitle("更新下载失败").setMessage(reason).setNegativeButton("关闭", null).setPositiveButton("重新下载") { _, _ -> downloadAndInstall(apkUrl, expectedSize, expectedSha256) }.create().also { showIos26Dialog(it) } } }
        } finally { connection?.disconnect(); withContext(Dispatchers.Main) { updateDownloadRunning = false } }
    }
    dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener { job.cancel(); dialog.dismiss() } }
}


private fun MainActivity.validateDownloadedApk(file: File) {
    val info = packageManager.getPackageArchiveInfo(file.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES or PackageManager.GET_SIGNATURES) ?: throw IllegalStateException("无法读取 APK 信息")
    if (info.packageName != packageName) throw IllegalStateException("APK 包名与当前应用不一致")
    val versionCode = if (android.os.Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
    if (versionCode <= BuildConfig.VERSION_CODE) throw IllegalStateException("APK 版本不是当前版本的更高版本")
    val downloaded = if (android.os.Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures
    val installedInfo = packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES or PackageManager.GET_SIGNATURES)
    val installed = if (android.os.Build.VERSION.SDK_INT >= 28) installedInfo.signingInfo?.apkContentsSigners else installedInfo.signatures
    if (downloaded.isNullOrEmpty() || installed.isNullOrEmpty() || downloaded.map { it.toCharsString() }.toSet() != installed.map { it.toCharsString() }.toSet()) throw IllegalStateException("APK 签名与当前应用不一致")
}

internal fun MainActivity.installApk(file: File) {
    try {
        if (!file.isFile || file.length() == 0L) { toast("更新文件不存在，请重新下载"); return }
        if (android.os.Build.VERSION.SDK_INT >= 26 && !packageManager.canRequestPackageInstalls()) {
            viewModel.pendingInstallPath = file.absolutePath
            AlertDialog.Builder(this).setTitle("需要允许安装未知应用").setMessage("为了安装应用更新，请在系统设置中允许“条码生成器”安装未知应用。开启后返回本应用，将自动继续安装。")
                .setNegativeButton("取消") { _, _ -> viewModel.pendingInstallPath = null }
                .setPositiveButton("去设置") { _, _ -> startActivity(Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName"))) }
                .setOnCancelListener { viewModel.pendingInstallPath = null }.create().also { showIos26Dialog(it) }; return
        }
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply { setDataAndType(uri, "application/vnd.android.package-archive"); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION); clipData = android.content.ClipData.newRawUri("APK", uri) }
        if (intent.resolveActivity(packageManager) == null) { toast("未找到可用的安装程序"); return }
        startActivity(intent)
    } catch (error: Exception) { val reason = error.message ?: "未知安装错误"; settingsStore.setUpdateError(reason); toast("安装失败：$reason") }
}

    /** Release 标签为 android-v<应用版本>.<Actions 构建号>；构建号绝不参与应用版本比较。 */

internal fun MainActivity.parseAppVersion(releaseTag: String): String? {
    val value = releaseTag.trim().removePrefix("android-v").removePrefix("v")
    val parts = value.split(".")
    if (parts.size < 3 || parts.size > 4 || parts.take(3).any { it.isEmpty() || it.length > 9 || it.toLongOrNull() == null }) return null
    if (parts.size == 4 && (parts[3].isEmpty() || parts[3].length > 12 || parts[3].toLongOrNull() == null)) return null
    return parts.take(3).joinToString(".")
}


internal fun MainActivity.compareVersions(a: String, b: String): Int = UpdateSecurity.compareVersions(a, b)


internal fun MainActivity.saveResultAsFavorite() {
    val activity = this
    if (resultItems.isEmpty()) return
    val editingGroup = selectedFavoriteGroup
    val folders = favoriteFolders.toMutableList()
    var selectedFolder = editingGroup?.folder?.takeIf { it in folders } ?: folders.firstOrNull() ?: ""
    lateinit var folderButton: Button
    folderButton = styleButton(Button(this).apply {
        text = selectedFolder.ifBlank { "选择文件夹" }
        setOnClickListener {
            val topFolders = folders.filter { !it.contains("/") }.distinct()
            if (topFolders.isEmpty()) { toast("请先新建一级文件夹"); return@setOnClickListener }
            val selectedParent = selectedFolder.substringBefore('/').takeIf { it in topFolders }
            showMaterialDropdown(folderButton, topFolders, popupWidth = folderButton.width, selectedIndex = topFolders.indexOf(selectedParent), forceBelowAnchor = true) { which ->
                val parent = topFolders[which]
                val children = folders.filter { it.startsWith("$parent/") && !it.removePrefix("$parent/").contains("/") }
                val options = children.map { it.removePrefix("$parent/") }.toMutableList().apply { add("使用一级文件夹：$parent"); add("新建二级文件夹") }
                val selectedChild = selectedFolder.removePrefix("$parent/").takeIf { selectedFolder.startsWith("$parent/") }
                showMaterialDropdown(folderButton, options, popupWidth = folderButton.width, selectedIndex = children.indexOf(selectedChild), forceBelowAnchor = true) { childIndex ->
                    when {
                        childIndex < children.size -> { selectedFolder = children[childIndex]; folderButton.text = selectedFolder }
                        childIndex == children.size -> { selectedFolder = parent; folderButton.text = selectedFolder }
                        else -> showSubfolderEditor(parent) { child ->
                            val path = "$parent/$child"
                            if (path !in folders) folders.add(path)
                            if (path !in favoriteFolders) favoriteFolders.add(path)
                            selectedFolder = path; folderButton.text = selectedFolder; saveFavoriteFolders()
                        }
                    }
                }
            }
        }
    })
    val addFolder = styleButton(Button(this).apply {
        text = "新建文件夹"
        setOnClickListener { showFolderEditor { folder ->
            if (folder !in folders) folders.add(folder)
            if (folder !in favoriteFolders) favoriteFolders.add(folder)
            selectedFolder = folder; folderButton.text = folder; saveFavoriteFolders()
        } }
    }).apply { setBackgroundDrawable(glassButtonBackground()) }
    val nameInput = EditText(this).apply {
        hint = "输入收藏文件名"
        setText(editingGroup?.name.orEmpty())
        setSingleLine(true)
        setBackgroundResource(R.drawable.bg_input)
        setPadding(dp(12), 0, dp(12), 0)
    }
    val box = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(24), dp(4), dp(24), 0)
        addView(TextView(activity).apply { text = "选择文件夹"; textSize = 14f; setTextColor(secondaryText()); setPadding(0, 0, 0, dp(6)) })
        addView(folderButton, LinearLayout.LayoutParams(-1, dp(48)))
        addView(addFolder, LinearLayout.LayoutParams(-2, dp(42)).apply { setMargins(0, dp(8), 0, dp(16)) })
        addView(TextView(activity).apply { text = "收藏文件名"; textSize = 14f; setTextColor(secondaryText()); setPadding(0, 0, 0, dp(6)) })
        addView(nameInput, LinearLayout.LayoutParams(-1, dp(48)))
    }
    fun persistFavorite(target: FavoriteGroup?, folder: String, name: String) {
        val savedAt = System.currentTimeMillis()
        resultItems.forEach { it.favorite = true; it.folder = folder }
        if (folder !in favoriteFolders) favoriteFolders.add(folder)
        if (target == null) favoriteGroups.add(0, FavoriteGroup(nextGroupId(), folder, name, savedAt, resultItems.map { it.id }.toMutableList()))
        else {
            val index = favoriteGroups.indexOfFirst { it.id == target.id }
            if (index >= 0) favoriteGroups[index] = FavoriteGroup(target.id, folder, name, savedAt, resultItems.map { it.id }.toMutableList())
        }
        items.filter { it.favorite && favoriteGroups.none { group -> group.itemIds.contains(it.id) } }.forEach { it.favorite = false }
        saveAllFavorites(); selectedFavoriteGroup = null; page = "favorites"; render(); toast("已保存到 $folder")
    }
    val saveDialog = AlertDialog.Builder(this).setTitle(if (editingGroup == null) "保存到收藏" else "编辑收藏").setView(box).setNegativeButton("取消", null).setPositiveButton("保存") { _, _ ->
        val name = nameInput.text.toString().trim()
        if (name.isEmpty()) { toast("请输入收藏文件名"); return@setPositiveButton }
        if (selectedFolder.isBlank()) { toast("请选择文件夹"); return@setPositiveButton }
        val conflict = favoriteGroups.firstOrNull { it.id != editingGroup?.id && it.folder == selectedFolder && it.name == name }
        if (conflict == null) persistFavorite(editingGroup, selectedFolder, name)
        else {
            val overwriteDialog = AlertDialog.Builder(activity).setTitle("覆盖收藏").setMessage("“$selectedFolder/$name”已存在，是否覆盖？").setNegativeButton("取消", null).setPositiveButton("覆盖") { _, _ ->
            if (editingGroup != null && editingGroup.id != conflict.id) favoriteGroups.removeAll { it.id == editingGroup.id }
            persistFavorite(conflict, selectedFolder, name)
            }.create()
            showIos26Dialog(overwriteDialog)
        }
    }.create()
    showIos26Dialog(saveDialog)
}


internal fun MainActivity.scanWithCamera() {
        val activity = this
        openCamera(43)
    }


internal fun MainActivity.captureText() {
        val activity = this
        openCamera(45)
    }


internal fun MainActivity.openCamera(requestCode: Int) {
        val activity = this
        pendingCameraRequest = requestCode
        if (android.os.Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), MainActivity.REQUEST_CAMERA_PERMISSION)
            return
        }
        launchCamera(requestCode)
    }


internal fun MainActivity.launchCamera(requestCode: Int) {
        val activity = this
        val photoFile = File.createTempFile("barcode_camera_", ".jpg", cacheDir)
        val photoUri = FileProvider.getUriForFile(this, "$packageName.fileprovider", photoFile)
        pendingCameraUri = photoUri
        pendingCameraFile = photoFile
        val intent = Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE).apply {
            putExtra(android.provider.MediaStore.EXTRA_OUTPUT, photoUri)
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivityForResult(intent, requestCode)
        } catch (_: Exception) {
            pendingCameraUri = null
            pendingCameraFile = null
            photoFile.delete()
            toast("当前设备没有可用的相机")
        }
    }


internal fun MainActivity.createFavoritesExport() {
    AlertDialog.Builder(this)
        .setTitle("导出收藏")
        .setItems(arrayOf("分享到其他应用", "保存到文件")) { _, which ->
            if (which == 0) shareFavoritesExport() else createFavoritesDocumentExport()
        }
        .create()
        .also { showIos26Dialog(it) }
}

/** 生成临时 JSON 并交给系统分享面板，可发送至聊天、邮件、网盘或文件管理器。 */
private fun MainActivity.shareFavoritesExport() {
    val name = "BarcodeGenerator-favorites-${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())}.json"
    lifecycleScope.launch(Dispatchers.IO) {
        val exportFile = File(cacheDir, name)
        val exportUri = FileProvider.getUriForFile(this@shareFavoritesExport, "$packageName.fileprovider", exportFile)
        val result = runCatching { FavoritesTransferManager.export(contentResolver, exportUri, dao.loadGroups(), dao.loadGroupItems(), dao.loadItems(), dao.loadFolders()) }
        withContext(Dispatchers.Main) {
            result.onSuccess {
                val share = Intent(Intent.ACTION_SEND).apply {
                    type = "application/json"
                    putExtra(Intent.EXTRA_STREAM, exportUri)
                    putExtra(Intent.EXTRA_TITLE, name)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    clipData = ClipData.newRawUri("收藏备份", exportUri)
                }
                startActivity(Intent.createChooser(share, "导出收藏到"))
            }.onFailure { toast("收藏导出失败：${it.message ?: "无法生成备份"}") }
        }
    }
}

/** 保留原有的 SAF 文件保存入口，供需要指定位置时使用。 */
private fun MainActivity.createFavoritesDocumentExport() {
    val name = "BarcodeGenerator-favorites-${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())}.json"
    startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply { type = "application/json"; putExtra(Intent.EXTRA_TITLE, name); addCategory(Intent.CATEGORY_OPENABLE) }, MainActivity.REQUEST_FAVORITES_EXPORT)
}

internal fun MainActivity.restoreFavoritesImport() {
    startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply { type = "application/json"; addCategory(Intent.CATEGORY_OPENABLE); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }, MainActivity.REQUEST_FAVORITES_IMPORT)
}

internal fun MainActivity.exportFavorites(uri: Uri) {
    lifecycleScope.launch(Dispatchers.IO) {
        val result = runCatching { FavoritesTransferManager.export(contentResolver, uri, dao.loadGroups(), dao.loadGroupItems(), dao.loadItems(), dao.loadFolders()) }
        withContext(Dispatchers.Main) { result.onSuccess { toast("收藏备份已导出") }.onFailure { toast("收藏导出失败：${it.message ?: "无法写入文件"}") } }
    }
}

internal fun MainActivity.confirmImportFavorites(uri: Uri) {
    val activity = this
    lifecycleScope.launch(Dispatchers.IO) {
        val parsed = runCatching { FavoritesTransferManager.restore(contentResolver, uri) }
        withContext(Dispatchers.Main) {
            parsed.onFailure { toast("无法导入收藏：${it.message ?: "文件格式无效"}") }.onSuccess { backup ->
                AlertDialog.Builder(activity).setTitle("导入跨平台收藏？").setMessage("将合并 ${backup.favorites.size} 个收藏，并保留一级文件夹、二级文件夹和收藏文件名。不会删除当前数据。")
                    .setNegativeButton("取消", null).setPositiveButton("导入") { _, _ -> importFavorites(backup) }.create().also { showIos26Dialog(it) }
            }
        }
    }
}

private fun MainActivity.importFavorites(backup: InterchangeBackup) {
    lifecycleScope.launch(Dispatchers.IO) {
        val result = runCatching {
            databaseMutex.withLock {
                val counts = database.withTransaction {
                    val transfer = FavoritesTransferManager.appendEntities(backup, dao.loadItems(), dao.loadGroups(), dao.loadGroupItems())
                    dao.saveItems(transfer.items); dao.saveGroups(transfer.groups); dao.saveGroupItems(transfer.links); dao.saveFolders(transfer.folders)
                    transfer.items.size to transfer.groups.size
                }
                loadItemsOnIo(); loadFavoriteGroupsOnIo(); loadFavoriteFoldersOnIo()
                collapsedFavoriteFolders.addAll(backup.folders.filter { it.isNotBlank() })
                collapsedFavoriteFolders.addAll(backup.favorites.map { it.folder }.filter { it.isNotBlank() })
                counts
            }
        }
        withContext(Dispatchers.Main) { result.onSuccess { (itemCount, groupCount) -> page = "favorites"; render(); toast("已导入 $groupCount 个收藏，$itemCount 条码") }.onFailure { toast("收藏导入失败：${it.message ?: "无法写入数据"}") } }
    }
}

internal fun MainActivity.pickBarcodeImage() { startActivityForResult(Intent(Intent.ACTION_GET_CONTENT).apply { type = "image/*"; addCategory(Intent.CATEGORY_OPENABLE) }, 44) }


internal fun MainActivity.pickTextImage() { startActivityForResult(Intent(Intent.ACTION_GET_CONTENT).apply { type = "image/*"; addCategory(Intent.CATEGORY_OPENABLE) }, 46) }


internal fun MainActivity.recognizeText(bitmap: Bitmap) {
        val activity = this
        val enhanced = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        val matrix = ColorMatrix().apply { setSaturation(0f); val scale = 1.35f; val offset = -44.8f; set(floatArrayOf(scale, 0f, 0f, 0f, offset, 0f, scale, 0f, 0f, offset, 0f, 0f, scale, 0f, offset, 0f, 0f, 0f, 1f, 0f)) }
        Canvas(enhanced).drawBitmap(bitmap, 0f, 0f, Paint(Paint.ANTI_ALIAS_FLAG).apply { colorFilter = ColorMatrixColorFilter(matrix) })
        val image = InputImage.fromBitmap(enhanced, 0)
        val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
        recognizer.process(image)
            .addOnSuccessListener { result ->
                val text = result.text.trim()
                if (text.isEmpty()) toast("未识别到文字，请拍摄清晰、正面的屏幕区域")
                else {
                    importRecognizedText(text)
                    toast("文字识别成功，已按行添加到输入框")
                }
            }
            .addOnFailureListener { toast("文字识别失败，请重试") }
            .addOnCompleteListener { recognizer.close(); enhanced.recycle() }
    }


internal fun MainActivity.importRecognizedText(text: String) {
        val activity = this
        val values = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (values.isEmpty()) return
        while (inputRows.size > 1) removeInputRow(inputRows.last())
        inputRows.firstOrNull()?.setText(values.first()) ?: addInputRow(values.first())
        values.drop(1).forEach { addInputRow(it) }
    }


internal fun MainActivity.decodeBitmap(bitmap: Bitmap): String? = try {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(RGBLuminanceSource(bitmap.width, bitmap.height, pixels)))).text
    } catch (_: Exception) { null }


internal fun MainActivity.showFolderEditor(initial: String = "", onSaved: (String) -> Unit) {
        val input = inputField("文件夹名称", initial)
        val box = LinearLayout(this).apply { setPadding(dp(24), dp(8), dp(24), 0); addView(input, LinearLayout.LayoutParams(-1, dp(50))) }
        val dialog = AlertDialog.Builder(this).setTitle(if (initial.isBlank()) "新建文件夹" else "重命名文件夹").setView(box).setNegativeButton("取消", null).setPositiveButton("保存", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = input.text.toString().trim()
                if (name.isBlank()) { toast("请输入文件夹名称"); return@setOnClickListener }
                if (favoriteFolders.any { it == name && it != initial }) { toast("已存在同名文件夹"); return@setOnClickListener }
                onSaved(name); dialog.dismiss()
            }
        }
        showIos26Dialog(dialog)
    }


internal fun MainActivity.showGroupEditor(group: FavoriteGroup) {
        val activity = this
        val nameInput = inputField("收藏文件名", group.name)
        val folderInput = inputField("文件夹", group.folder)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(8), dp(24), 0); addView(nameInput); addView(folderInput, LinearLayout.LayoutParams(-1, dp(50)).apply { setMargins(0, dp(10), 0, 0) }) }
        val dialog = AlertDialog.Builder(this).setTitle("编辑收藏").setView(box).setNegativeButton("取消", null).setNeutralButton("删除", null).setPositiveButton("保存", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = nameInput.text.toString().trim(); val folder = folderInput.text.toString().trim().ifEmpty { "默认" }
                if (name.isBlank()) { toast("请输入收藏文件名"); return@setOnClickListener }
                group.name = name; group.folder = folder; if (folder !in favoriteFolders) favoriteFolders.add(folder)
                selectedFavoriteGroup = group; saveAllFavorites(); render(); dialog.dismiss()
            }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                AlertDialog.Builder(this).setTitle("删除收藏").setMessage("确定删除“${group.name}”吗？").setNegativeButton("取消", null).setPositiveButton("删除") { _, _ ->
                    favoriteGroups.removeAll { it.id == group.id }
                    // 删除收藏文件时始终保留其所属文件夹。
                    if (group.folder.isNotBlank() && group.folder !in favoriteFolders) favoriteFolders.add(group.folder)
                    selectedFavoriteGroup = null; saveAllFavorites(); page = "favorites"; render()
                }.create().also { showIos26Dialog(it) }
                dialog.dismiss()
            }
        }
        showIos26Dialog(dialog)
    }


internal fun MainActivity.showItemEditor(item: CodeItem) {
        val activity = this
        val value = inputField("条码内容", item.text)
        val formatsSpinner = Spinner(this).apply {
            adapter = formatSpinnerAdapter()
            setSelection(formats.indexOfFirst { it.first == item.format }.coerceAtLeast(0))
            setOnTouchListener { view, event ->
                if (event.actionMasked == MotionEvent.ACTION_UP) {
                    showMaterialDropdown(view, formats.map { it.first }, popupWidth = view.width, selectedIndex = selectedItemPosition) { index ->
                        setSelection(index)
                    }
                }
                true
            }
        }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(8), dp(24), 0); addView(value); addView(formatsSpinner, LinearLayout.LayoutParams(-1, dp(50)).apply { setMargins(0, dp(10), 0, 0) }) }
        val dialog = AlertDialog.Builder(this).setTitle("编辑条目").setView(box).setNegativeButton("取消", null).setNeutralButton("删除", null).setPositiveButton("保存", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val text = value.text.toString().trim(); if (text.isBlank()) { toast("请输入条码内容"); return@setOnClickListener }
                item.text = text; item.format = formats[formatsSpinner.selectedItemPosition].first; saveItems(); render(); dialog.dismiss()
            }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                AlertDialog.Builder(this).setTitle("删除条目").setMessage("确定删除此条码吗？").setNegativeButton("取消", null).setPositiveButton("删除") { _, _ ->
                    items.removeAll { it.id == item.id }; favoriteGroups.forEach { it.itemIds.removeAll { id -> id == item.id } }; saveAllFavorites(); render()
                }.create().also { showIos26Dialog(it) }; dialog.dismiss()
            }
        }
        showIos26Dialog(dialog)
    }

