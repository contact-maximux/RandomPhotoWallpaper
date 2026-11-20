
package com.example.randomphotowallpaper

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import org.json.JSONArray
import java.util.concurrent.Executors

class MainActivity : Activity() {

    private val prefs by lazy {
        getSharedPreferences("wallpaper_state", MODE_PRIVATE)
    }

    private val worker = Executors.newSingleThreadExecutor()

    private val pickFolderRequest = 4102

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
	WallpaperScheduler.schedule(this)

        when (intent?.action) {

            ACTION_DELETE_AND_NEXT -> {
                worker.execute {
                    val message = try {
                        WallpaperEngine.deleteCurrentAndChange(this)
                    } catch (e: Exception) {
                        "Wallpaper operation failed: ${e.message}"
                    }

                    runOnUiThread {
                        Toast.makeText(
                            this,
                            message,
                            Toast.LENGTH_LONG
                        ).show()
                        finish()
                    }
                }
            }

            ACTION_ADD_FOLDER -> {
                openFolderPicker()
            }

            else -> {
                if (getFolderUris().isEmpty()) {
                    openFolderPicker()
                } else {
                    changeWallpaper()
                }
            }
        }
    }

    private fun changeWallpaper() {
        worker.execute {
            val message = try {
                WallpaperEngine.changeWallpaper(this)
                "Wallpaper updated."
            } catch (e: Exception) {
                "Could not change wallpaper: ${e.message}"
            }

            runOnUiThread {
                Toast.makeText(
                    this,
                    message,
                    Toast.LENGTH_LONG
                ).show()
                finish()
            }
        }
    }

    private fun openFolderPicker() {
        val picker = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }

        startActivityForResult(picker, pickFolderRequest)
    }

    @Deprecated("Uses Android system folder picker")
    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?
    ) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode != pickFolderRequest) {
            finish()
            return
        }

        val tree = data?.data

        if (resultCode == RESULT_OK && tree != null) {

            val flags = data.flags and (
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )

            try {
                contentResolver.takePersistableUriPermission(
                    tree,
                    flags
                )
            } catch (_: SecurityException) {
            }

            val folders = getFolderUris().toMutableSet()
            folders.add(tree.toString())

            saveFolderUris(folders)

            changeWallpaper()

        } else {
            finish()
        }
    }

    private fun getFolderUris(): Set<String> {
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

        val legacy = prefs.getString(KEY_TREE, null)

        return if (legacy.isNullOrBlank()) {
            emptySet()
        } else {
            setOf(legacy)
        }
    }

    private fun saveFolderUris(folders: Set<String>) {
        val array = JSONArray()

        folders.forEach {
            array.put(it)
        }

        prefs.edit()
            .putString(KEY_FOLDERS, array.toString())
            .remove(KEY_TREE)
            .apply()
    }

    override fun onDestroy() {
        worker.shutdown()
        super.onDestroy()
    }

    companion object {
        private const val KEY_TREE = "selected_tree_uri"
        private const val KEY_FOLDERS = "selected_folder_uris"

        private const val ACTION_DELETE_AND_NEXT =
            "com.example.randomphotowallpaper.DELETE_AND_NEXT"

        private const val ACTION_ADD_FOLDER =
            "com.example.randomphotowallpaper.ADD_FOLDER"
    }
}
