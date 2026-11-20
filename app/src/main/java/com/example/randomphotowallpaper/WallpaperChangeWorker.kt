
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
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.TypedValue
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
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
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

            // Create clean lock-screen and decorated home-screen images.
            val (lockBitmap, homeBitmap) = decodeAndCrop(
                context,
                chosen
            )

            try {
                // Save the decorated home-screen image for debugging.
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

                // FIRST: Clean photo only on the lock screen.
                wallpaperManager.setBitmap(
                    lockBitmap,
                    null,
                    true,
                    WallpaperManager.FLAG_LOCK
                )

                // SECOND: Same photo with quote and calendar on home screen.
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

        // Create two identical copies before adding any overlays.
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

        // Draw all overlays on the home-screen copy only.
        val canvas = Canvas(homeBitmap)

        // Existing 20% black overlay.
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

        drawQuoteOverlay(
            context,
            canvas,
            homeBitmap.width,
            homeBitmap.height
        )

        drawCalendarStrip(
            context,
            canvas,
            homeBitmap.width,
            homeBitmap.height
        )

        return Pair(lockBitmap, homeBitmap)
    }

    // Quote overlay matching MainActivity.
    private fun drawQuoteOverlay(
        context: Context,
        canvas: Canvas,
        width: Int,
        height: Int
    ) {
        val quotes = listOf(
            "The journey of a thousand miles begins with one step." to "Lao Tzu",
            "Believe you can and you're halfway there." to "Theodore Roosevelt",
            "It always seems impossible until it's done." to "Nelson Mandela",
            "What we think, we become." to "Buddha",
            "Learning never exhausts the mind." to "Leonardo da Vinci",
            "The secret of getting ahead is getting started." to "Mark Twain",
            "Great things are done by a series of small things brought together." to "Vincent van Gogh",
            "Be yourself; everyone else is already taken." to "Oscar Wilde",
            "Dream big and dare to fail." to "Norman Vaughan",
            "The only way to do great work is to love what you do." to "Steve Jobs",
            "Life is like riding a bicycle. To keep your balance, you must keep moving." to "Albert Einstein",
            "Only those who will risk going too far can possibly find out how far one can go." to "T. S. Eliot",
            "Act as if what you do makes a difference. It does." to "William James"
        )

        val selected = quotes.random()
        val density = context.resources.displayMetrics.density

        val left = width / 3f + 5f
        val top = height * 0.052f
        val maxWidth = width - left - (width * 0.08f)

        val quotePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(241, 241, 241) // #F1F1F1
            textSize = 14f * density
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
            color = Color.rgb(241, 241, 241) // #F1F1F1
            textSize = 11f * density
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            textAlign = Paint.Align.LEFT
            setShadowLayer(
                3f * density,
                1f * density,
                1f * density,
                Color.BLACK
            )
        }

        val words = selected.first.split(" ")
        val lines = mutableListOf<String>()
        var currentLine = ""

        for (word in words) {
            val candidate =
                if (currentLine.isEmpty()) word
                else "$currentLine $word"

            if (
                quotePaint.measureText(candidate) <= maxWidth ||
                currentLine.isEmpty()
            ) {
                currentLine = candidate
            } else {
                lines.add(currentLine)
                currentLine = word
            }

            if (lines.size == 2) break
        }

        if (lines.size < 2 && currentLine.isNotEmpty()) {
            lines.add(currentLine)
        }

        val lineHeight = quotePaint.textSize * 1.25f
        var y = top + quotePaint.textSize

        lines.take(2).forEach { line ->
            canvas.drawText(line, left, y, quotePaint)
            y += lineHeight
        }

        y += 1f * density
        canvas.drawText(selected.second, left, y, authorPaint)
    }

    // Calendar strip matching MainActivity.
    private fun drawCalendarStrip(
        context: Context,
        canvas: Canvas,
        width: Int,
        height: Int
    ) {
        val density = context.resources.displayMetrics.density
        val today = Calendar.getInstance()

        val margin = width * 0.035f
        val gap = width * 0.003f
        val tileWidth = (width - margin * 2f - gap * 9f) / 10f

        // Calendar at the extreme bottom.
        val tileBottom = height.toFloat()
        val tileHeight = height * 0.05f
        val tileTop = tileBottom - tileHeight

        // Month heading - disabled for now.
        // Uncomment this block whenever you want the month name back.
        /*
        val headingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(241, 241, 241)
            textSize = 52f * density
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            textAlign = Paint.Align.CENTER
            setShadowLayer(2f * density, 1f, 1f, Color.BLACK)
        }

        val monthText = SimpleDateFormat(
            "MMMM yyyy",
            Locale.getDefault()
        ).format(today.time)

        val headingBaseline =
            tileTop - 2f - headingPaint.fontMetrics.descent

        canvas.drawText(
            monthText,
            width / 2f,
            headingBaseline,
            headingPaint
        )
        */

        // Day names: #F1F1F1, normal weight.
        val dayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(241, 241, 241)
            textSize = 18f * density
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            textAlign = Paint.Align.CENTER
            setShadowLayer(2f * density, 1f, 1f, Color.BLACK)
        }

        // Dates: #F1F1F1, normal weight.
        val datePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(241, 241, 241)
            textSize = 26f * density
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            textAlign = Paint.Align.CENTER
            setShadowLayer(2f * density, 1f, 1f, Color.BLACK)
        }

        val weekdayBackground = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            alpha = 51
        }

        // Weekends: white rectangle with dark text.
        val weekendBackground = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            alpha = 255
        }

        val weekendTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(20, 20, 20)
            textSize = 18f * density
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            textAlign = Paint.Align.CENTER
        }

        val weekendDatePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(20, 20, 20)
            textSize = 26f * density
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            textAlign = Paint.Align.CENTER
        }

        // Today: cyan background, no border.
        val todayBackground = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(0, 255, 255)
            alpha = 255
        }

        for (index in 0 until 10) {
            val date = (today.clone() as Calendar).apply {
                add(Calendar.DAY_OF_YEAR, index)
            }

            val left = margin + index * (tileWidth + gap)
            val right = left + tileWidth

            val dayOfWeek = date.get(Calendar.DAY_OF_WEEK)

            val isWeekend =
                dayOfWeek == Calendar.SATURDAY ||
                    dayOfWeek == Calendar.SUNDAY

            val isToday = index == 0

            val rect = android.graphics.RectF(
                left,
                tileTop,
                right,
                tileBottom
            )

            val backgroundPaint = when {
                isToday -> todayBackground
                isWeekend -> weekendBackground
                else -> weekdayBackground
            }

            canvas.drawRoundRect(
                rect,
                5f * density,
                5f * density,
                backgroundPaint
            )

            val dayName = SimpleDateFormat(
                "EEE",
                Locale.getDefault()
            ).format(date.time)

            val dayNumber = date.get(
                Calendar.DAY_OF_MONTH
            ).toString()

            val centerX = (left + right) / 2f

            val currentDayPaint =
                if (isWeekend) weekendTextPaint else dayPaint

            val currentDatePaint =
                if (isWeekend) weekendDatePaint else datePaint

            canvas.drawText(
                dayName,
                centerX,
                tileTop + tileHeight * 0.36f,
                currentDayPaint
            )

            canvas.drawText(
                dayNumber,
                centerX,
                tileTop + tileHeight * 0.90f,
                currentDatePaint
            )
        }
    }

    // EXIF orientation correction.
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
