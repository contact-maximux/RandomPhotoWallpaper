
package com.example.randomphotowallpaper

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.WallpaperManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.provider.DocumentsContract
import android.provider.MediaStore
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

            val (lockBitmap, homeBitmap) = decodeAndCrop(
                context,
                chosen
            )

            try {
                try {
                    saveDebugWallpaper(context, homeBitmap)

                    android.util.Log.i(
                        "WallpaperDebug",
                        "SUCCESS: Saved Pictures/RandomWallpaperDebug/wall.jpg"
                    )
                } catch (e: Exception) {
                    android.util.Log.e(
                        "WallpaperDebug",
                        "FAILED TO SAVE WALLPAPER",
                        e
                    )
                }

                val wallpaperManager =
                    WallpaperManager.getInstance(context)

                wallpaperManager.setBitmap(
                    lockBitmap,
                    null,
                    true,
                    WallpaperManager.FLAG_LOCK
                )

                wallpaperManager.setBitmap(
                    homeBitmap,
                    null,
                    true,
                    WallpaperManager.FLAG_SYSTEM
                )

            } finally {
                lockBitmap.recycle()
                homeBitmap.recycle()
            }

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

        } catch (e: Exception) {
            android.util.Log.e(
                "WallpaperWorker",
                "Wallpaper update failed",
                e
            )
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
            android.content.res.Configuration.ORIENTATION_LANDSCAPE
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
                    "Delete Wallpaper",
                    deletePending
                )
                .addAction(
                    android.R.drawable.ic_menu_add,
                    "Add Folder",
                    addPending
                )
                .build()
        )
    }

    private fun saveDebugWallpaper(
        context: Context,
        bitmap: Bitmap
    ) {
        val fileName = "wall.jpg"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI

            val relativePath =
                "${Environment.DIRECTORY_PICTURES}/RandomWallpaperDebug/"

            resolver.delete(
                collection,
                "${MediaStore.Images.Media.DISPLAY_NAME}=? AND " +
                    "${MediaStore.Images.Media.RELATIVE_PATH}=?",
                arrayOf(fileName, relativePath)
            )

            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, relativePath)
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }

            val imageUri = resolver.insert(collection, values)
                ?: error("Could not create debug image")

            try {
                resolver.openOutputStream(imageUri)?.use { stream ->
                    check(
                        bitmap.compress(
                            Bitmap.CompressFormat.JPEG,
                            95,
                            stream
                        )
                    ) {
                        "Could not compress debug image"
                    }
                } ?: error("Could not open debug image")

                val completedValues = ContentValues().apply {
                    put(MediaStore.Images.Media.IS_PENDING, 0)
                }

                resolver.update(imageUri, completedValues, null, null)

                android.util.Log.i(
                    "WallpaperDebug",
                    "Image saved successfully: $imageUri"
                )
            } catch (e: Exception) {
                resolver.delete(imageUri, null, null)
                throw e
            }

        } else {
            val directory = File(
                context.getExternalFilesDir(Environment.DIRECTORY_PICTURES),
                "RandomWallpaperDebug"
            )

            if (!directory.exists()) {
                check(directory.mkdirs()) {
                    "Could not create debug directory"
                }
            }

            val file = File(directory, fileName)

            FileOutputStream(file).use { stream ->
                check(
                    bitmap.compress(
                        Bitmap.CompressFormat.JPEG,
                        95,
                        stream
                    )
                ) {
                    "Could not write debug image"
                }
            }
        }
    }

    private fun decodeAndCrop(
        context: Context,
        uri: Uri
    ): Pair<Bitmap, Bitmap> {
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

        val lockBitmap = scaled.copy(
            Bitmap.Config.ARGB_8888,
            true
        ) ?: error("Could not prepare lock-screen image")

        val homeBitmap = try {
            scaled.copy(
                Bitmap.Config.ARGB_8888,
                true
            ) ?: error("Could not prepare home-screen image")
        } catch (e: Exception) {
            lockBitmap.recycle()
            scaled.recycle()
            throw e
        }

        scaled.recycle()

        val canvas = Canvas(homeBitmap)

        canvas.drawRect(
            0f,
            0f,
            homeBitmap.width.toFloat(),
            homeBitmap.height.toFloat(),
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK
                alpha = 51
            }
        )

        // Calendar occupies the right 55% of the screen.
        drawCalendarStrip(
            context,
            canvas,
            homeBitmap.width,
            homeBitmap.height
        )

        // Quote is rotated clockwise along the extreme right edge.
        drawQuoteOverlay(
            context,
            canvas,
            homeBitmap.width,
            homeBitmap.height
        )

        return Pair(lockBitmap, homeBitmap)
    }

    // QUOTE: rotated 90 degrees clockwise along the right edge.
    // Author appears before the quote in the vertical reading direction.
    private fun drawQuoteOverlay(
        context: Context,
        canvas: Canvas,
        width: Int,
        height: Int
    ) {
        val selected = QuoteRepository.getRandomQuote(context)
        val density = context.resources.displayMetrics.density

        val edgeMargin = width * 0.025f
        val maxTextLength = height * 0.72f

        val quotePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(241, 241, 241)
            textSize = 17f * density
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            textAlign = Paint.Align.LEFT
            setShadowLayer(
                3f * density,
                1f * density,
                1f * density,
                Color.BLACK
            )
        }

        val authorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(241, 241, 241)
            textSize = 12f * density
            typeface = Typeface.create(
                "sans-serif-medium",
                Typeface.NORMAL
            )
            textAlign = Paint.Align.LEFT
            setShadowLayer(
                3f * density,
                1f * density,
                1f * density,
                Color.BLACK
            )
        }

        // Wrap quote into a maximum of two vertical columns.
        val words = selected.text.trim().split(Regex("\\s+"))
        val lines = mutableListOf<String>()
        var currentLine = ""

        for (word in words) {
            val candidate =
                if (currentLine.isEmpty()) word
                else "$currentLine $word"

            if (
                quotePaint.measureText(candidate) <= maxTextLength ||
                currentLine.isEmpty()
            ) {
                currentLine = candidate
            } else {
                lines.add(currentLine)
                currentLine = word

                if (lines.size == 2) break
            }
        }

        if (lines.size < 2 && currentLine.isNotEmpty()) {
            lines.add(currentLine)
        }

        val visibleLines = lines.take(2)
        val lineHeight = quotePaint.textSize * 1.25f

        // Rotate around the top-right corner.
        canvas.save()
        canvas.rotate(
            90f,
            width.toFloat(),
            0f
        )

        // After rotation, local Y controls distance from the right edge.
        val textX = height * 0.13f
        val textY = edgeMargin

        // Author comes first, above the quote.
        if (selected.author.isNotBlank()) {
            canvas.drawText(
                selected.author,
                height * 0.075f,
                textY,
                authorPaint
            )
        }

        // Each wrapped line is drawn as a parallel vertical line.
        visibleLines.forEachIndexed { index, line ->
            canvas.drawText(
                line,
                textX,
                textY + index * lineHeight,
                quotePaint
            )
        }

        canvas.restore()
    }

    // CALENDAR:
    // Three rows and seven rectangular tiles.
    // Calendar begins at 45% of screen width.
    // All tiles share the same background.
    // Saturday and Sunday text is bright red.
    private fun drawCalendarStrip(
        context: Context,
        canvas: Canvas,
        width: Int,
        height: Int
    ) {
        val density = context.resources.displayMetrics.density

        // Leave the left 45% of the wallpaper clear.
        val startX = width * 0.45f
        val rightMargin = width * 0.02f
        val gap = width * 0.002f

        val columns = 7

        val availableWidth =
            width - startX - rightMargin - gap * (columns - 1)

        val tileWidth = availableWidth / columns

        val topMargin = height * 0.035f
        val tileHeight = height * 0.038f
        val rowGap = height * 0.004f

        val dayLabels = listOf(
            "Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"
        )

        val today = Calendar.getInstance()

        // Find Monday of the current week.
        val currentMonday = (today.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)

            val day = get(Calendar.DAY_OF_WEEK)
            val daysFromMonday =
                (day - Calendar.MONDAY + 7) % 7

            add(Calendar.DAY_OF_YEAR, -daysFromMonday)
        }

        // One consistent background for every tile.
        val tileBackground = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            alpha = 100
        }

        val normalText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(245, 245, 245)
            textSize = 12f * density
            typeface = Typeface.create(
                "sans-serif-medium",
                Typeface.NORMAL
            )
            textAlign = Paint.Align.CENTER
            setShadowLayer(
                2f * density,
                1f * density,
                1f * density,
                Color.BLACK
            )
        }

        val dateText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(245, 245, 245)
            textSize = 16f * density
            typeface = Typeface.create(
                "sans-serif",
                Typeface.NORMAL
            )
            textAlign = Paint.Align.CENTER
            setShadowLayer(
                2f * density,
                1f * density,
                1f * density,
                Color.BLACK
            )
        }

        // Bright red for Saturday and Sunday.
        val weekendText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(255, 45, 45)
            textSize = 16f * density
            typeface = Typeface.create(
                "sans-serif-medium",
                Typeface.NORMAL
            )
            textAlign = Paint.Align.CENTER
            setShadowLayer(
                2f * density,
                1f * density,
                1f * density,
                Color.BLACK
            )
        }

        val todayText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 16f * density
            typeface = Typeface.create(
                "sans-serif-bold",
                Typeface.NORMAL
            )
            textAlign = Paint.Align.CENTER
            setShadowLayer(
                2f * density,
                1f * density,
                1f * density,
                Color.BLACK
            )
        }

        fun drawBlock(
            column: Int,
            row: Int,
            label: String,
            textPaint: Paint
        ) {
            val left = startX + column * (tileWidth + gap)
            val right = left + tileWidth
            val top = topMargin + row * (tileHeight + rowGap)
            val bottom = top + tileHeight

            canvas.drawRect(
                RectF(left, top, right, bottom),
                tileBackground
            )

            val baseline =
                top + (tileHeight - textPaint.textSize) / 2f -
                    (textPaint.ascent() + textPaint.descent()) / 2f

            canvas.drawText(
                label,
                (left + right) / 2f,
                baseline,
                textPaint
            )
        }

        // Row 1: weekday labels.
        for (column in 0 until columns) {
            val textPaint =
                if (column >= 5) weekendText else normalText

            drawBlock(
                column = column,
                row = 0,
                label = dayLabels[column],
                textPaint = textPaint
            )
        }

        // Row 2: current week.
        // Row 3: next week.
        for (row in 0..1) {
            for (column in 0 until columns) {
                val date = (currentMonday.clone() as Calendar).apply {
                    add(Calendar.DAY_OF_YEAR, row * 7 + column)
                }

                val isToday =
                    date.get(Calendar.YEAR) == today.get(Calendar.YEAR) &&
                    date.get(Calendar.DAY_OF_YEAR) ==
                        today.get(Calendar.DAY_OF_YEAR)

                val isWeekend = column >= 5

                val textPaint = when {
                    isToday -> todayText
                    isWeekend -> weekendText
                    else -> dateText
                }

                drawBlock(
                    column = column,
                    row = row + 1,
                    label = date.get(Calendar.DAY_OF_MONTH).toString(),
                    textPaint = textPaint
                )
            }
        }
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
