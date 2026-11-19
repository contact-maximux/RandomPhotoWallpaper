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
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.DocumentsContract
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

        val canvas = Canvas(darkened)

        // Existing 20% black wallpaper overlay.
        canvas.drawRect(
            0f,
            0f,
            darkened.width.toFloat(),
            darkened.height.toFloat(),
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK
                alpha = 51
            }
        )

        // Quote below camera area in upper-right half.
        drawQuoteOverlay(
            context,
            canvas,
            darkened.width,
            darkened.height
        )

        // Calendar at bottom.
        drawCalendarStrip(
            context,
            canvas,
            darkened.width,
            darkened.height
        )

        return darkened
    }

    // Random quote in the upper-right half of the wallpaper.
    private fun drawQuoteOverlay(
        context: Context,
        canvas: Canvas,
        width: Int,
        height: Int
    ) {
        val quotes = listOf(
            Pair("The journey of a thousand miles begins with a single step.", "Lao Tzu"),
            Pair("Believe you can and you're halfway there.", "Theodore Roosevelt"),
            Pair("It always seems impossible until it's done.", "Nelson Mandela"),
            Pair("What you think, you become.", "Buddha"),
            Pair("Simplicity is the ultimate sophistication.", "Leonardo da Vinci"),
            Pair("The secret of getting ahead is getting started.", "Mark Twain"),
            Pair("Great things are done by a series of small things brought together.", "Vincent van Gogh"),
            Pair("Be yourself; everyone else is already taken.", "Oscar Wilde"),
            Pair("Dream big and dare to fail.", "Norman Vaughan"),
            Pair("Do what you can, with what you have, where you are.", "Theodore Roosevelt"),
            Pair("Stay hungry. Stay foolish.", "Steve Jobs"),
            Pair("In the middle of difficulty lies opportunity.", "Albert Einstein"),
            Pair("Every moment is a fresh beginning.", "T. S. Eliot"),
            Pair("Act as if what you do makes a difference. It does.", "William James")
        )

        val selected = quotes.random()
        val density = context.resources.displayMetrics.scaledDensity

        val textSize = 14f * density
        val authorSize = 11f * density

        val left = width * 0.55f
        val maxWidth = width * 0.40f
        val top = height * 0.15f

        val quotePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            this.textSize = textSize
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
            color = Color.WHITE
            textSize = authorSize
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
            val testLine = if (currentLine.isEmpty()) {
                word
            } else {
                "$currentLine $word"
            }

            if (
                quotePaint.measureText(testLine) <= maxWidth ||
                currentLine.isEmpty()
            ) {
                currentLine = testLine
            } else {
                lines.add(currentLine)
                currentLine = word
            }
        }

        if (currentLine.isNotEmpty()) {
            lines.add(currentLine)
        }

        val firstTwoLines = lines.take(2)

        val visibleLines: List<String> =
            if (lines.size > 2 && firstTwoLines.isNotEmpty()) {

                val shortenedLastLine = generateSequence(firstTwoLines.last()) { line ->
                    if (line.isEmpty()) null else line.dropLast(1).trimEnd()
                }.firstOrNull {
                    quotePaint.measureText("$it…") <= maxWidth
                } ?: ""

                listOf(
                    firstTwoLines.first(),
                    shortenedLastLine + "…"
                )

            } else {
                firstTwoLines
            }

        val lineHeight = textSize * 1.35f

        visibleLines.forEachIndexed { index, line ->
            canvas.drawText(
                line,
                left,
                top + index * lineHeight,
                quotePaint
            )
        }

        canvas.drawText(
            "— ${selected.second}",
            left,
            top + visibleLines.size * lineHeight + 4f * density,
            authorPaint
        )

        quotePaint.clearShadowLayer()
        authorPaint.clearShadowLayer()
    }

    // Updated minimal transparent 10-day calendar strip.
    private fun drawCalendarStrip(
        context: Context,
        canvas: Canvas,
        width: Int,
        height: Int
    ) {
        val today = Calendar.getInstance()

        // Calendar uses 20% of screen height.
        val stripHeight = height * 0.20f
        val margin = width * 0.035f
        val gap = width * 0.003f

        val tertiaryAttr = context.resources.getIdentifier(
            "colorTertiary",
            "attr",
            context.packageName
        )

        val typedValue = TypedValue()

        val tertiaryColor =
            if (
                tertiaryAttr != 0 &&
                context.theme.resolveAttribute(
                    tertiaryAttr,
                    typedValue,
                    true
                )
            ) {
                typedValue.data
            } else {
                Color.rgb(127, 86, 217)
            }

        // Month and year heading.
        val headerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply
