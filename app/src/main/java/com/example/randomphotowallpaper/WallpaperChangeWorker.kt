
package com.example.randomphotowallpaper

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.WallpaperManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.DocumentsContract
import androidx.exifinterface.media.ExifInterface
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import org.json.JSONArray
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Calendar
import java.util.concurrent.TimeUnit
import kotlin.random.Random

class WallpaperChangeWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val context = applicationContext

        if (
            isQuietHours() ||
            isLandscape(context) ||
            isPowerSaveMode(context) ||
            isDoNotDisturb(context)
        ) {
            return Result.success()
        }

        val prefs = context.getSharedPreferences(
            "wallpaper_state",
            Context.MODE_PRIVATE
        )

        val folders = readFolders(prefs)
        if (folders.isEmpty()) return Result.success()

        return try {
            val photos = mutableListOf<Uri>()

            folders.forEach { tree ->
                try {
                    collectDirectImages(context, Uri.parse(tree), photos)
                } catch (_: Exception) {
                }
            }

            val all = photos.distinctBy { it.toString() }
            if (all.isEmpty()) return Result.success()

            val historyFile = File(context.filesDir, "used_images.json")
            val currentIds = all.map { it.toString() }.toSet()

            val used = readHistory(historyFile)
                .intersect(currentIds)
                .toMutableSet()

            if ((currentIds - used).isEmpty()) {
                used.clear()
            }

            val chosen = Uri.parse((currentIds - used).random())
            val bitmap = decodeAndCrop(context, chosen)

            try {
                WallpaperManager.getInstance(context).setBitmap(
                    bitmap,
                    null,
                    true,
                    WallpaperManager.FLAG_SYSTEM or
                        WallpaperManager.FLAG_LOCK
                )
            } finally {
                bitmap.recycle()
            }

            // Record the image only after successfully setting wallpaper.
            used.add(chosen.toString())
            writeHistory(context, historyFile, used)

            prefs.edit()
                .putString("current_wallpaper_image_uri", chosen.toString())
                .apply()

            showNotification(
                context,
                displayName(context, chosen) ?: "Photo"
            )

            Result.success()

        } catch (_: Exception) {
            Result.retry()
        }
    }

    private fun isQuietHours(): Boolean {
        val hour = Calendar.getInstance()
            .get(Calendar.HOUR_OF_DAY)

        return hour >= 21 || hour < 9
    }

    private fun isLandscape(context: Context): Boolean {
        return context.resources.configuration.orientation ==
            Configuration.ORIENTATION_LANDSCAPE
    }

    private fun isPowerSaveMode(context: Context): Boolean {
        val power = context.getSystemService(
            Context.POWER_SERVICE
        ) as PowerManager

        return power.isPowerSaveMode
    }

    private fun isDoNotDisturb(context: Context): Boolean {
        val manager = context.getSystemService(
            Context.NOTIFICATION_SERVICE
        ) as NotificationManager

        if (
            Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
            !manager.isNotificationPolicyAccessGranted
        ) {
            return false
        }

        return try {
            manager.currentInterruptionFilter !=
                NotificationManager.INTERRUPTION_FILTER_ALL
        } catch (_: SecurityException) {
            false
        }
    }

    private fun readFolders(
        prefs: android.content.SharedPreferences
    ): Set<String> {
        val raw = prefs.getString("selected_folder_uris", null)

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

        return prefs.getString("selected_tree_uri", null)
            ?.let { setOf(it) }
            ?: emptySet()
    }

    // Direct children only. Subdirectories are not scanned.
    private fun collectDirectImages(
        context: Context,
        tree: Uri,
        output: MutableList<Uri>
    ) {
        val rootId = DocumentsContract.getTreeDocumentId(tree)

        val children =
            DocumentsContract.buildChildDocumentsUriUsingTree(
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
            "used_images.json.tmp"
        )

        FileOutputStream(temp).use { stream ->
            stream.write(
                array.toString().toByteArray(Charsets.UTF_8)
            )
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
                error("Could not save wallpaper history")
            }
        }
    }

    private fun displayName(
        context: Context,
        uri: Uri
    ): String? {
        return try {
            context.contentResolver.query(
                uri,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME
                ),
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

        val deleteIntent = Intent(
            context,
            MainActivity::class.java
        ).setAction(
            "com.example.randomphotowallpaper.DELETE_AND_NEXT"
        )

        val addIntent = Intent(
            context,
            MainActivity::class.java
        ).setAction(
            "com.example.randomphotowallpaper.ADD_FOLDER"
        )

        val deletePending = PendingIntent.getActivity(
            context,
            101,
            deleteIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or
                PendingIntent.FLAG_IMMUTABLE
        )

        val addPending = PendingIntent.getActivity(
            context,
            102,
            addIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or
                PendingIntent.FLAG_IMMUTABLE
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
                    "Delete & change next",
                    deletePending
                )
                .addAction(
                    android.R.drawable.ic_menu_add,
                    "Add more folder",
                    addPending
                )
                .build()
        )
    }

    private fun decodeAndCrop(
        context: Context,
        uri: Uri
    ): Bitmap {
        val metrics = context.resources.displayMetrics

        val targetWidth = metrics.widthPixels.coerceAtLeast(1)
        val targetHeight = metrics.heightPixels.coerceAtLeast(1)

        val bounds = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }

        context.contentResolver.openInputStream(uri).use {
            BitmapFactory.decodeStream(it, null, bounds)
        }

        require(
            bounds.outWidth > 0 && bounds.outHeight > 0
        ) {
            "Unreadable image"
        }

        var sample = 1

        while (
            bounds.outWidth / (sample * 2) >= targetWidth &&
            bounds.outHeight / (sample * 2) >= targetHeight
        ) {
            sample *= 2
        }

        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }

        val decoded = context.contentResolver.openInputStream(uri)
            .use {
                BitmapFactory.decodeStream(it, null, opts)
            } ?: error("Image decode failed")

        val oriented = applyExif(context, uri, decoded)

        if (oriented !== decoded) {
            decoded.recycle()
        }

        val ratio = targetWidth.toFloat() / targetHeight
        val sourceRatio = oriented.width.toFloat() / oriented.height

        val cropW: Int
        val cropH: Int

        if (sourceRatio > ratio) {
            cropH = oriented.height
            cropW = (oriented.height * ratio)
                .toInt()
                .coerceIn(1, oriented.width)
        } else {
            cropW = oriented.width
            cropH = (oriented.width / ratio)
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

        if (cropped !== oriented) {
            oriented.recycle()
        }

        val scaled = Bitmap.createScaledBitmap(
            cropped,
            targetWidth,
            targetHeight,
            true
        )

        if (scaled !== cropped) {
            cropped.recycle()
        }

        val darkened = scaled.copy(
            Bitmap.Config.ARGB_8888,
            true
        ) ?: error("Overlay failed")

        scaled.recycle()

        Canvas(darkened).drawRect(
            0f,
            0f,
            darkened.width.toFloat(),
            darkened.height.toFloat(),
            Paint().apply {
                color = Color.BLACK
                alpha = 51
            }
        )

        return darkened
    }

    // FIXED: block body allows return bitmap inside the when statement.
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

    companion object {
        private const val CHANNEL_ID = "wallpaper_changes"
        private const val NOTIFICATION_ID = 701
    }
}

object WallpaperScheduler {

    fun schedule(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiresBatteryNotLow(true)
            .build()

        val request =
            PeriodicWorkRequestBuilder<WallpaperChangeWorker>(
                4,
                TimeUnit.HOURS
            )
                .setConstraints(constraints)
                .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            "random_wallpaper_every_four_hours",
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }
}
