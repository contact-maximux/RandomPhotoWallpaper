
package com.example.randomphotowallpaper
import android.provider.Settings
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.WallpaperManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.view.WindowManager
import android.util.DisplayMetrics
import androidx.exifinterface.media.ExifInterface
import org.json.JSONArray
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.random.Random

object WallpaperEngine {

    private const val PREFS = "wallpaper_state"
    private const val KEY_LAST_BOOT_COUNT = "last_seen_boot_count"
    private const val KEY_FOLDERS = "selected_folder_uris"
    private const val KEY_LEGACY_FOLDER = "selected_tree_uri"
    private const val KEY_CURRENT_IMAGE = "current_wallpaper_image_uri"

    private const val KEY_NIGHT_FOLDER = "night_mode_folder_uri"
    private const val KEY_NIGHT_MODE = "night_mode_enabled"

    private const val NORMAL_HISTORY_FILE = "used_images.json"
    private const val NIGHT_HISTORY_FILE = "used_images_night.json"

    private const val CHANNEL_ID = "wallpaper_changes"
    private const val NOTIFICATION_ID = 701

    private const val ACTION_DELETE =
        "com.example.randomphotowallpaper.DELETE_AND_NEXT"

    private const val ACTION_ADD_FOLDER =
        "com.example.randomphotowallpaper.ADD_FOLDER"

    private const val ACTION_TOGGLE_NIGHT =
        "com.example.randomphotowallpaper.TOGGLE_NIGHT"

    private const val OVERLAY_ALPHA = 51 // 20% black

    // Main entry point for manual and scheduled wallpaper changes.
    @Synchronized
    fun changeWallpaper(context: Context) {

        val app = context.applicationContext
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

// Reset Night mode if the phone has restarted.
try {
    val bootCount = Settings.Global.getInt(
        app.contentResolver,
        Settings.Global.BOOT_COUNT
    )

    val previousBoot = prefs.getInt(KEY_LAST_BOOT_COUNT, -1)

    if (previousBoot != -1 && previousBoot != bootCount) {
        prefs.edit()
            .putBoolean(KEY_NIGHT_MODE, false)
            .apply()
    }

    prefs.edit()
        .putInt(KEY_LAST_BOOT_COUNT, bootCount)
        .apply()

} catch (_: Exception) {
}

val nightMode = prefs.getBoolean(KEY_NIGHT_MODE, false)

        // Select folders according to the active mode.
        val folders = if (nightMode) {
            listOfNotNull(
                prefs.getString(KEY_NIGHT_FOLDER, null)
            )
        } else {
            readFolders(prefs).toList()
        }

        require(folders.isNotEmpty()) {
            if (nightMode) {
                "No Night mode folder selected."
            } else {
                "No normal folders selected."
            }
        }

        val images = mutableListOf<Uri>()

        folders.forEach { folder ->
            try {
                collectDirectImages(app, Uri.parse(folder), images)
            } catch (_: Exception) {
            }
        }

        val uniqueImages = images.distinctBy { it.toString() }

        require(uniqueImages.isNotEmpty()) {
            "No supported photos found in the active folder."
        }

        // Each mode has its own independent image history.
        val historyName = if (nightMode) {
            NIGHT_HISTORY_FILE
        } else {
            NORMAL_HISTORY_FILE
        }

        val historyFile = File(app.filesDir, historyName)
        val currentIds = uniqueImages.map { it.toString() }.toSet()

        val used = readHistory(historyFile)
            .intersect(currentIds)
            .toMutableSet()

        // Start a new cycle when every available image has been used.
        if ((currentIds - used).isEmpty()) {
            used.clear()
        }

        val chosen = Uri.parse((currentIds - used).random())

        val resolution = targetResolution(app)

        val bitmap = decodeAndCrop(
            app,
            chosen,
            resolution.first,
            resolution.second
        )

        try {
            WallpaperManager.getInstance(app).setBitmap(
                bitmap,
                null,
                true,
                WallpaperManager.FLAG_SYSTEM or
                    WallpaperManager.FLAG_LOCK
            )
        } finally {
            bitmap.recycle()
        }

        // Save history only after wallpaper was successfully changed.
        used.add(chosen.toString())
        writeHistory(app, historyFile, used)

        prefs.edit()
            .putString(KEY_CURRENT_IMAGE, chosen.toString())
            .apply()

        showNotification(
            app,
            displayName(app, chosen) ?: "Photo"
        )
    }

    // Delete the currently displayed original photo and change wallpaper.
    @Synchronized
    fun deleteCurrentAndChange(context: Context): String {

        val app = context.applicationContext
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        val current = prefs.getString(KEY_CURRENT_IMAGE, null)

        var message = "No previous photo was recorded."

        if (!current.isNullOrBlank()) {
            try {
                val uri = Uri.parse(current)

                val deleted = DocumentsContract.deleteDocument(
                    app.contentResolver,
                    uri
                )

                if (deleted) {
                    // Remove it from both histories.
                    removeFromHistory(app, current)

                    message = "Previous photo deleted."

                    prefs.edit()
                        .remove(KEY_CURRENT_IMAGE)
                        .apply()
                } else {
                    message = "Could not delete previous photo."
                }

            } catch (_: Exception) {
                message = "Could not delete photo. Check folder write access."
            }
        }

        return try {
            changeWallpaper(app)
            "$message Wallpaper updated."
        } catch (e: Exception) {
            "$message Wallpaper change failed: ${e.message}"
        }
    }

    private fun readFolders(
        prefs: android.content.SharedPreferences
    ): Set<String> {

        val raw = prefs.getString(KEY_FOLDERS, null)

        if (!raw.isNullOrBlank()) {
            return try {
                val array = JSONArray(raw)

                (0 until array.length())
                    .map { array.getString(it) }
                    .toSet()

            } catch (_: Exception) {
                emptySet()
            }
        }

        val legacy = prefs.getString(KEY_LEGACY_FOLDER, null)

        return if (legacy.isNullOrBlank()) {
            emptySet()
        } else {
            setOf(legacy)
        }
    }

    // Only direct children are scanned. No subdirectories.
    private fun collectDirectImages(
        context: Context,
        tree: Uri,
        output: MutableList<Uri>
    ) {

        val rootId = DocumentsContract.getTreeDocumentId(tree)

        val children = DocumentsContract.buildChildDocumentsUriUsingTree(
            tree,
            rootId
        )

        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME
        )

        context.contentResolver.query(
            children,
            projection,
            null,
            null,
            null
        )?.use { cursor ->

            val idCol = cursor.getColumnIndexOrThrow(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID
            )

            val mimeCol = cursor.getColumnIndexOrThrow(
                DocumentsContract.Document.COLUMN_MIME_TYPE
            )

            val nameCol = cursor.getColumnIndexOrThrow(
                DocumentsContract.Document.COLUMN_DISPLAY_NAME
            )

            while (cursor.moveToNext()) {

                val id = cursor.getString(idCol)
                val mime = cursor.getString(mimeCol) ?: ""
                val name = cursor.getString(nameCol) ?: ""

                if (!name.startsWith(".") && mime.startsWith("image/")) {

                    output.add(
                        DocumentsContract.buildDocumentUriUsingTree(
                            tree,
                            id
                        )
                    )
                }
            }
        }
    }

    private fun readHistory(file: File): Set<String> {

        if (!file.exists()) return emptySet()

        return try {
            val array = JSONArray(file.readText(Charsets.UTF_8))

            buildSet {
                for (i in 0 until array.length()) {
                    array.optString(i)
                        .takeIf { it.isNotBlank() }
                        ?.let { add(it) }
                }
            }

        } catch (_: Exception) {
            emptySet()
        }
    }

    private fun writeHistory(
        context: Context,
        file: File,
        used: Set<String>
    ) {

        val array = JSONArray()
        used.forEach { array.put(it) }

        val temp = File(
            context.filesDir,
            "${file.name}.tmp"
        )

        FileOutputStream(temp).use { stream ->
            stream.write(array.toString().toByteArray(Charsets.UTF_8))
            stream.flush()
            stream.fd.sync()
        }

        try {
            Files.move(
                temp.toPath(),
                file.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE
            )
        } catch (_: Exception) {
            if (!temp.renameTo(file)) {
                temp.delete()
                error("Could not save wallpaper history.")
            }
        }
    }

    private fun removeFromHistory(
        context: Context,
        id: String
    ) {

        listOf(
            NORMAL_HISTORY_FILE,
            NIGHT_HISTORY_FILE
        ).forEach { name ->

            val file = File(context.filesDir, name)
            val remaining = readHistory(file) - id

            writeHistory(context, file, remaining)
        }
    }

    private fun targetResolution(context: Context): Pair<Int, Int> {

        val metrics = DisplayMetrics()

        @Suppress("DEPRECATION")
        (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager)
            .defaultDisplay.getRealMetrics(metrics)

        return Pair(
            metrics.widthPixels.coerceAtLeast(1),
            metrics.heightPixels.coerceAtLeast(1)
        )
    }

    private fun decodeAndCrop(
        context: Context,
        uri: Uri,
        targetWidth: Int,
        targetHeight: Int
    ): Bitmap {

        val bounds = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }

        context.contentResolver.openInputStream(uri).use {
            BitmapFactory.decodeStream(it, null, bounds)
        }

        require(bounds.outWidth > 0 && bounds.outHeight > 0) {
            "Unreadable image."
        }

        var sample = 1

        while (
            bounds.outWidth / (sample * 2) >= targetWidth &&
            bounds.outHeight / (sample * 2) >= targetHeight
        ) {
            sample *= 2
        }

        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }

        val decoded = context.contentResolver.openInputStream(uri)
            .use {
                BitmapFactory.decodeStream(it, null, options)
            } ?: error("Image decode failed.")

        val oriented = applyExif(context, uri, decoded)

        if (oriented !== decoded) decoded.recycle()

        val targetRatio = targetWidth.toFloat() / targetHeight
        val sourceRatio = oriented.width.toFloat() / oriented.height

        val cropW: Int
        val cropH: Int

        if (sourceRatio > targetRatio) {
            cropH = oriented.height
            cropW = (oriented.height * targetRatio)
                .toInt()
                .coerceIn(1, oriented.width)
        } else {
            cropW = oriented.width
            cropH = (oriented.width / targetRatio)
                .toInt()
                .coerceIn(1, oriented.height)
        }

        val left = if (oriented.width > cropW) {
            Random.nextInt(oriented.width - cropW + 1)
        } else {
            0
        }

        val top = if (oriented.height > cropH) {
            Random.nextInt(oriented.height - cropH + 1)
        } else {
            0
        }

        val cropped = Bitmap.createBitmap(
            oriented,
            left,
            top,
            cropW,
            cropH
        )

        if (cropped !== oriented) oriented.recycle()

        val scaled = Bitmap.createScaledBitmap(
            cropped,
            targetWidth,
            targetHeight,
            true
        )

        if (scaled !== cropped) cropped.recycle()

        val darkened = scaled.copy(
            Bitmap.Config.ARGB_8888,
            true
        ) ?: error("Overlay failed.")

        scaled.recycle()

        // 20% black overlay.
        Canvas(darkened).drawRect(
            0f,
            0f,
            darkened.width.toFloat(),
            darkened.height.toFloat(),
            Paint().apply {
                color = Color.BLACK
                alpha = OVERLAY_ALPHA
            }
        )

        return darkened
    }

    private fun applyExif(
        context: Context,
        uri: Uri,
        bitmap: Bitmap
    ): Bitmap {

        return try {
            val orientation = context.contentResolver
                .openInputStream(uri)
                .use {
                    ExifInterface(it!!).getAttributeInt(
                        ExifInterface.TAG_ORIENTATION,
                        ExifInterface.ORIENTATION_NORMAL
                    )
                }

            val matrix = Matrix()

            when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 ->
                    matrix.postRotate(90f)

                ExifInterface.ORIENTATION_ROTATE_180 ->
                    matrix.postRotate(180f)

                ExifInterface.ORIENTATION_ROTATE_270 ->
                    matrix.postRotate(270f)

                ExifInterface.ORIENTATION_FLIP_HORIZONTAL ->
                    matrix.preScale(-1f, 1f)

                ExifInterface.ORIENTATION_FLIP_VERTICAL ->
                    matrix.preScale(1f, -1f)

                else -> return bitmap
            }

            Bitmap.createBitmap(
                bitmap,
                0,
                0,
                bitmap.width,
                bitmap.height,
                matrix,
                true
            )

        } catch (_: Throwable) {
            bitmap
        }
    }

    private fun displayName(
        context: Context,
        uri: Uri
    ): String? {

        return try {
            context.contentResolver.query(
                uri,
                arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null,
                null,
                null
            )?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }

        } catch (_: Exception) {
            null
        }
    }

    private fun showNotification(
        context: Context,
        photoName: String
    ) {

        if (
            Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(
                android.Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        val manager = context.getSystemService(
            Context.NOTIFICATION_SERVICE
        ) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Wallpaper changes",
                    NotificationManager.IMPORTANCE_DEFAULT
                )
            )
        }

        val prefs = context.getSharedPreferences(
            PREFS,
            Context.MODE_PRIVATE
        )

        val nightMode = prefs.getBoolean(KEY_NIGHT_MODE, false)

        val deleteIntent = Intent(
            context,
            MainActivity::class.java
        ).setAction(ACTION_DELETE)

        val addIntent = Intent(
            context,
            MainActivity::class.java
        ).setAction(ACTION_ADD_FOLDER)

        val nightIntent = Intent(
            context,
            MainActivity::class.java
        ).setAction(ACTION_TOGGLE_NIGHT)

        val pendingFlags =
            PendingIntent.FLAG_UPDATE_CURRENT or
                PendingIntent.FLAG_IMMUTABLE

        val deletePending = PendingIntent.getActivity(
            context,
            101,
            deleteIntent,
            pendingFlags
        )

        val addPending = PendingIntent.getActivity(
            context,
            102,
            addIntent,
            pendingFlags
        )

        val nightPending = PendingIntent.getActivity(
            context,
            103,
            nightIntent,
            pendingFlags
        )

        val builder = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(context, CHANNEL_ID)
        } else {
            Notification.Builder(context)
        }

        manager.notify(
            NOTIFICATION_ID,
            builder
                .setSmallIcon(android.R.drawable.ic_menu_gallery)
                .setContentTitle("Wallpaper changed")
                .setContentText(photoName)
                .setAutoCancel(true)
                .addAction(
                    android.R.drawable.ic_menu_delete,
                    "Delete",
                    deletePending
                )
                .addAction(
                    android.R.drawable.ic_menu_add,
                    "Add folder",
                    addPending
                )
                .addAction(
                    android.R.drawable.ic_menu_day,
if (nightMode) "Day" else "Night",
                    nightPending
                )
                .build()
        )
    }
}
