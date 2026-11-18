package com.example.randomphotowallpaper

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.WallpaperManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Color
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import android.util.DisplayMetrics
import android.view.WindowManager
import android.widget.Toast
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import org.json.JSONArray
import java.util.concurrent.Executors
import kotlin.random.Random

class MainActivity : Activity() {
    private val prefs by lazy { getSharedPreferences("wallpaper_state", MODE_PRIVATE) }
    private val worker = Executors.newSingleThreadExecutor()
    private val pickFolderRequest = 4102
    private var pendingAddFolder = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        createNotificationChannel()
        requestNotificationPermissionIfNeeded()
        WallpaperScheduler.schedule(this)

        when (intent?.action) {
            ACTION_DELETE_AND_NEXT -> deleteCurrentAndChange()
            ACTION_ADD_FOLDER -> openFolderPicker(true)
            else -> {
                if (getFolderUris().isEmpty()) openFolderPicker(false)
                else applyRandomWallpaper()
            }
        }
    }

    private fun openFolderPicker(addAnother: Boolean) {
        pendingAddFolder = addAnother
        val picker = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        startActivityForResult(picker, pickFolderRequest)
    }

    @Deprecated("Uses Android's system folder picker")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != pickFolderRequest) { finish(); return }
        val tree = data?.data
        if (resultCode == RESULT_OK && tree != null) {
            val flags = data.flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            try { contentResolver.takePersistableUriPermission(tree, flags) } catch (_: SecurityException) { }
            val folders = getFolderUris().toMutableSet()
            folders.add(tree.toString())
            saveFolderUris(folders)
            applyRandomWallpaper()
        } else {
            finish()
        }
    }

    private fun deleteCurrentAndChange() {
        worker.execute {
            var deleteMessage = ""
            val current = prefs.getString(KEY_CURRENT_IMAGE, null)
            if (!current.isNullOrBlank()) {
                try {
                    val deleted = DocumentsContract.deleteDocument(contentResolver, Uri.parse(current))
                    deleteMessage = if (deleted) "Previous photo deleted." else "Could not delete previous photo."
                    removeFromHistory(current)
                } catch (t: Throwable) {
                    deleteMessage = "Could not delete photo (folder write access may be unavailable)."
                }
            }
            applyWallpaperOnWorker(deleteMessage)
        }
    }

    private fun applyRandomWallpaper() {
        worker.execute { applyWallpaperOnWorker("") }
    }

    private fun applyWallpaperOnWorker(prefixMessage: String) {
        try {
            val images = mutableListOf<Uri>()
            getFolderUris().forEach { tree ->
                try { collectImages(Uri.parse(tree), images) } catch (_: Exception) { }
            }
            val uniqueImages = images.distinctBy { it.toString() }
            if (uniqueImages.isEmpty()) {
                runOnUiThread {
                    Toast.makeText(this, "No supported photos found in the selected folders.", Toast.LENGTH_LONG).show()
                    finish()
                }
                return
            }

            val historyFile = File(filesDir, HISTORY_FILE)
            val currentIds = uniqueImages.map { it.toString() }.toSet()
            val used = readUsedHistory(historyFile).intersect(currentIds).toMutableSet()
            if ((currentIds - used).isEmpty()) used.clear()
            val chosen = Uri.parse((currentIds - used).random())
            val target = targetResolution()
            val bitmap = decodeAndRandomCrop(chosen, target.first, target.second)
            try {
                WallpaperManager.getInstance(this).setBitmap(
                    bitmap, null, true,
                    WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK
                )
            } finally { bitmap.recycle() }

            // Commit the no-repeat history only after Android successfully sets the wallpaper.
            used.add(chosen.toString())
            writeUsedHistoryAtomically(historyFile, used)
            prefs.edit().putString(KEY_CURRENT_IMAGE, chosen.toString()).apply()
            val displayName = queryDisplayName(chosen) ?: "Photo"
            runOnUiThread {
                showWallpaperNotification(displayName)
                val msg = listOf(prefixMessage, "Wallpaper updated.").filter { it.isNotBlank() }.joinToString(" ")
                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                finish()
            }
        } catch (t: Throwable) {
            runOnUiThread {
                Toast.makeText(this, "Could not set wallpaper: ${t.message ?: "unknown error"}", Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }

    // Deliberately scan only files directly inside each selected folder. No subfolders.
    private fun collectImages(treeUri: Uri, output: MutableList<Uri>) {
        val rootId = DocumentsContract.getTreeDocumentId(treeUri)
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, rootId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME
        )
        contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val mimeColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
            val nameColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            while (cursor.moveToNext()) {
                val id = cursor.getString(idColumn)
                val mime = cursor.getString(mimeColumn) ?: ""
                val name = cursor.getString(nameColumn) ?: ""
                if (!name.startsWith(".") && mime.startsWith("image/")) {
                    output.add(DocumentsContract.buildDocumentUriUsingTree(treeUri, id))
                }
            }
        }
    }

    private fun getFolderUris(): Set<String> {
        val raw = prefs.getString(KEY_FOLDERS, null)
        if (!raw.isNullOrBlank()) return try {
            val a = JSONArray(raw)
            (0 until a.length()).map { a.getString(it) }.toSet()
        } catch (_: Exception) { emptySet() }
        // Migrate the previous single-folder setting.
        val legacy = prefs.getString(KEY_TREE, null)
        return if (legacy.isNullOrBlank()) emptySet() else setOf(legacy)
    }

    private fun saveFolderUris(folders: Set<String>) {
        val a = JSONArray(); folders.forEach { a.put(it) }
        prefs.edit().putString(KEY_FOLDERS, a.toString()).remove(KEY_TREE).apply()
    }

    private fun removeFromHistory(id: String) {
        val file = File(filesDir, HISTORY_FILE)
        val remaining = readUsedHistory(file) - id
        writeUsedHistoryAtomically(file, remaining)
    }

    private fun readUsedHistory(file: File): Set<String> {
        if (!file.exists()) return emptySet()
        return try {
            val array = JSONArray(file.readText(Charsets.UTF_8))
            buildSet { for (i in 0 until array.length()) array.optString(i).takeIf { it.isNotBlank() }?.let { add(it) } }
        } catch (_: Exception) { emptySet() }
    }

    private fun writeUsedHistoryAtomically(file: File, used: Set<String>) {
        val array = JSONArray(); used.forEach { array.put(it) }
        val temp = File(filesDir, "$HISTORY_FILE.tmp")
        FileOutputStream(temp).use { stream ->
            stream.write(array.toString().toByteArray(Charsets.UTF_8)); stream.flush(); stream.fd.sync()
        }
        try {
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: Exception) {
            if (!temp.renameTo(file)) { temp.delete(); error("Could not save wallpaper history.") }
        }
    }

    private fun queryDisplayName(uri: Uri): String? = try {
        contentResolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    } catch (_: Exception) { null }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Wallpaper changes", NotificationManager.IMPORTANCE_DEFAULT)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 501)
        }
    }

    private fun showWallpaperNotification(photoName: String) {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val deleteIntent = Intent(this, MainActivity::class.java).setAction(ACTION_DELETE_AND_NEXT)
        val addIntent = Intent(this, MainActivity::class.java).setAction(ACTION_ADD_FOLDER)
        val deletePending = PendingIntent.getActivity(this, 101, deleteIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val addPending = PendingIntent.getActivity(this, 102, addIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL_ID) else Notification.Builder(this)
        val notification = builder
            .setSmallIcon(android.R.drawable.ic_menu_gallery)
            .setContentTitle("Wallpaper changed")
            .setContentText(photoName)
            .setAutoCancel(true)
            .addAction(android.R.drawable.ic_menu_delete, "Delete & change next", deletePending)
            .addAction(android.R.drawable.ic_menu_add, "Add more folder", addPending)
            .build()
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
    }

    private fun targetResolution(): Pair<Int, Int> {
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        (getSystemService(WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(metrics)
        return Pair(metrics.widthPixels.coerceAtLeast(1), metrics.heightPixels.coerceAtLeast(1))
    }

    private fun decodeAndRandomCrop(uri: Uri, targetWidth: Int, targetHeight: Int): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, bounds) }
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Unsupported or unreadable image." }
        val sample = calculateSampleSize(bounds.outWidth, bounds.outHeight, targetWidth, targetHeight)
        val options = BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 }
        val decoded = contentResolver.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, options) } ?: error("Unable to decode photo.")
        val oriented = applyExifOrientation(uri, decoded)
        if (oriented !== decoded) decoded.recycle()
        val targetRatio = targetWidth.toFloat() / targetHeight.toFloat()
        val sourceRatio = oriented.width.toFloat() / oriented.height.toFloat()
        val cropW: Int; val cropH: Int
        if (sourceRatio > targetRatio) { cropH = oriented.height; cropW = (oriented.height * targetRatio).toInt().coerceIn(1, oriented.width) }
        else { cropW = oriented.width; cropH = (oriented.width / targetRatio).toInt().coerceIn(1, oriented.height) }
        val left = if (oriented.width > cropW) Random.nextInt(oriented.width - cropW + 1) else 0
        val top = if (oriented.height > cropH) Random.nextInt(oriented.height - cropH + 1) else 0
        val cropped = Bitmap.createBitmap(oriented, left, top, cropW, cropH)
        if (cropped !== oriented) oriented.recycle()
        val scaled = Bitmap.createScaledBitmap(cropped, targetWidth, targetHeight, true)
        if (scaled !== cropped) cropped.recycle()
        val darkened = scaled.copy(Bitmap.Config.ARGB_8888, true) ?: error("Unable to prepare wallpaper overlay.")
        scaled.recycle()
        Canvas(darkened).drawRect(0f, 0f, darkened.width.toFloat(), darkened.height.toFloat(), Paint().apply { color = Color.BLACK; alpha = 51 })
        return darkened
    }

    private fun calculateSampleSize(width: Int, height: Int, targetW: Int, targetH: Int): Int {
        var sample = 1
        while (width / (sample * 2) >= targetW && height / (sample * 2) >= targetH) sample *= 2
        return sample
    }

    private fun applyExifOrientation(uri: Uri, bitmap: Bitmap): Bitmap {
        return try {
            val orientation = contentResolver.openInputStream(uri).use { ExifInterface(it!!).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
            val matrix = Matrix()
            when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
                ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
                ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.preScale(-1f, 1f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.preScale(1f, -1f)
                else -> return bitmap
            }
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        } catch (_: Throwable) { bitmap }
    }

    override fun onDestroy() { worker.shutdown(); super.onDestroy() }

    companion object {
        private const val KEY_TREE = "selected_tree_uri"
        private const val KEY_FOLDERS = "selected_folder_uris"
        private const val KEY_CURRENT_IMAGE = "current_wallpaper_image_uri"
        private const val HISTORY_FILE = "used_images.json"
        private const val CHANNEL_ID = "wallpaper_changes"
        private const val NOTIFICATION_ID = 701
        private const val ACTION_DELETE_AND_NEXT = "com.example.randomphotowallpaper.DELETE_AND_NEXT"
        private const val ACTION_ADD_FOLDER = "com.example.randomphotowallpaper.ADD_FOLDER"
    }
}
