package com.example.randomphotowallpaper

import android.app.Activity
import android.app.WallpaperManager
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.util.DisplayMetrics
import android.view.WindowManager
import android.widget.Toast
import androidx.exifinterface.media.ExifInterface
import java.io.InputStream
import java.util.Collections
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

class MainActivity : Activity() {
    private val prefs by lazy { getSharedPreferences("wallpaper_state", MODE_PRIVATE) }
    private val worker = Executors.newSingleThreadExecutor()
    private val pickFolderRequest = 4102

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val savedTree = prefs.getString(KEY_TREE, null)
        if (savedTree.isNullOrBlank()) {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            }
            startActivityForResult(intent, pickFolderRequest)
        } else {
            applyRandomWallpaper(Uri.parse(savedTree))
        }
    }

    @Deprecated("Uses the system folder picker for the one-time folder selection.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != pickFolderRequest) {
            finish()
            return
        }
        val tree = data?.data
        if (resultCode == RESULT_OK && tree != null) {
            val flags = data.flags and
                (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            try {
                contentResolver.takePersistableUriPermission(tree, flags)
            } catch (_: SecurityException) {
                // Read access is sufficient; the system may not grant write access.
            }
            prefs.edit().putString(KEY_TREE, tree.toString()).apply()
            applyRandomWallpaper(tree)
        } else {
            finish()
        }
    }

    private fun applyRandomWallpaper(tree: Uri) {
        worker.execute {
            try {
                val images = mutableListOf<Uri>()
                collectImages(tree, images)

                if (images.isEmpty()) {
                    runOnUiThread {
                        Toast.makeText(this, "No supported photos found in that folder.", Toast.LENGTH_LONG).show()
                        finish()
                    }
                    return@execute
                }

                val chosen = chooseWithoutRepeating(images)
                val target = targetResolution()
                val bitmap = decodeAndRandomCrop(chosen, target.first, target.second)

                val manager = WallpaperManager.getInstance(this)
                manager.setBitmap(
                    bitmap,
                    null,
                    true,
                    WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK
                )
                bitmap.recycle()

                runOnUiThread {
                    Toast.makeText(this, "Wallpaper updated.", Toast.LENGTH_SHORT).show()
                    finish()
                }
            } catch (t: Throwable) {
                runOnUiThread {
                    Toast.makeText(this, "Could not set wallpaper: ${t.message ?: "unknown error"}", Toast.LENGTH_LONG).show()
                    finish()
                }
            }
        }
    }

    private fun collectImages(treeUri: Uri, output: MutableList<Uri>) {
        val rootId = DocumentsContract.getTreeDocumentId(treeUri)
        collectFromDocument(treeUri, rootId, output, 0)
    }

    private fun collectFromDocument(treeUri: Uri, documentId: String, output: MutableList<Uri>, depth: Int) {
        if (depth > 12) return
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, documentId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_MIME_TYPE
        )
        contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val mimeColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
            while (cursor.moveToNext()) {
                val id = cursor.getString(idColumn)
                val mime = cursor.getString(mimeColumn) ?: ""
                if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                    collectFromDocument(treeUri, id, output, depth + 1)
                } else if (mime.startsWith("image/")) {
                    output.add(DocumentsContract.buildDocumentUriUsingTree(treeUri, id))
                }
            }
        }
    }

    private fun chooseWithoutRepeating(images: List<Uri>): Uri {
        val all = images.map { it.toString() }.toSet()
        var bag = prefs.getStringSet(KEY_REMAINING, emptySet())?.toMutableSet() ?: mutableSetOf()
        bag.retainAll(all)
        if (bag.isEmpty()) bag.addAll(all)

        val selected = bag.random()
        bag.remove(selected)
        prefs.edit()
            .putStringSet(KEY_REMAINING, bag)
            .putString(KEY_LAST, selected)
            .apply()
        return Uri.parse(selected)
    }

    private fun targetResolution(): Pair<Int, Int> {
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        (getSystemService(WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(metrics)
        return Pair(metrics.widthPixels.coerceAtLeast(1), metrics.heightPixels.coerceAtLeast(1))
    }

    private fun decodeAndRandomCrop(uri: Uri, targetWidth: Int, targetHeight: Int): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri).use { input ->
            BitmapFactory.decodeStream(input, null, bounds)
        }
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Unsupported or unreadable image." }

        val sample = calculateSampleSize(bounds.outWidth, bounds.outHeight, targetWidth, targetHeight)
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = contentResolver.openInputStream(uri).use { input ->
            BitmapFactory.decodeStream(input, null, options)
        } ?: error("Unable to decode photo.")

        val oriented = applyExifOrientation(uri, decoded)
        if (oriented !== decoded) decoded.recycle()

        val sourceW = oriented.width
        val sourceH = oriented.height
        val targetRatio = targetWidth.toFloat() / targetHeight.toFloat()
        val sourceRatio = sourceW.toFloat() / sourceH.toFloat()

        val cropW: Int
        val cropH: Int
        if (sourceRatio > targetRatio) {
            cropH = sourceH
            cropW = (sourceH * targetRatio).toInt().coerceIn(1, sourceW)
        } else {
            cropW = sourceW
            cropH = (sourceW / targetRatio).toInt().coerceIn(1, sourceH)
        }

        // Randomly choose the crop window anywhere it can fit inside the photo.
        val left = if (sourceW > cropW) Random.nextInt(sourceW - cropW + 1) else 0
        val top = if (sourceH > cropH) Random.nextInt(sourceH - cropH + 1) else 0
        val cropped = Bitmap.createBitmap(oriented, left, top, cropW, cropH)
        if (cropped !== oriented) oriented.recycle()

        val result = Bitmap.createScaledBitmap(cropped, targetWidth, targetHeight, true)
        if (result !== cropped) cropped.recycle()
        return result
    }

    private fun calculateSampleSize(width: Int, height: Int, targetW: Int, targetH: Int): Int {
        var sample = 1
        while (width / (sample * 2) >= targetW && height / (sample * 2) >= targetH) {
            sample *= 2
        }
        return sample
    }

    private fun applyExifOrientation(uri: Uri, bitmap: Bitmap): Bitmap {
        return try {
            val orientation = contentResolver.openInputStream(uri).use { input ->
                ExifInterface(input!!).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL
                )
            }
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
        } catch (_: Throwable) {
            bitmap
        }
    }

    override fun onDestroy() {
        worker.shutdown()
        super.onDestroy()
    }

    companion object {
        private const val KEY_TREE = "selected_tree_uri"
        private const val KEY_REMAINING = "remaining_photos"
        private const val KEY_LAST = "last_photo"
    }
}
