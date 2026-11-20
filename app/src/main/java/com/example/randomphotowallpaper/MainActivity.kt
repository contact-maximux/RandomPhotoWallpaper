
package com.example.randomphotowallpaper

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
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

    private var pickingNightFolder = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WallpaperScheduler.schedule(this)
        requestNotificationPermissionIfNeeded()

        when (intent?.action) {

            ACTION_DELETE_AND_NEXT -> {
                worker.execute {
                    val message = try {
                        WallpaperEngine.deleteCurrentAndChange(this)
                    } catch (e: Exception) {
                        "Wallpaper operation failed: ${e.message}"
                    }

                    showMessageAndFinish(message)
                }
            }

            ACTION_ADD_FOLDER -> {
                openFolderPicker(false)
            }

            ACTION_TOGGLE_NIGHT -> {
                toggleNightMode()
            }

            else -> {
                if (getFolderUris().isEmpty()) {
                    openFolderPicker(false)
                } else {
                    changeWallpaper()
                }
            }
        }
    }

    private fun toggleNightMode() {
        val nightFolder = prefs.getString(KEY_NIGHT_FOLDER, null)

        if (prefs.getBoolean(KEY_NIGHT_MODE, false)) {
            // Turn Night mode OFF.
            prefs.edit()
                .putBoolean(KEY_NIGHT_MODE, false)
                .apply()

            changeWallpaper()

        } else if (nightFolder.isNullOrBlank()) {
            // No Night folder yet. Ask user to select one.
            openFolderPicker(true)

        } else {
            // Turn Night mode ON.
            prefs.edit()
                .putBoolean(KEY_NIGHT_MODE, true)
                .apply()

            changeWallpaper()
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

            showMessageAndFinish(message)
        }
    }

    private fun showMessageAndFinish(message: String) {
        runOnUiThread {
            Toast.makeText(
                this,
                message,
                Toast.LENGTH_LONG
            ).show()

            finish()
        }
    }

    private fun openFolderPicker(nightMode: Boolean) {
        pickingNightFolder = nightMode

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

            if (pickingNightFolder) {
                // Save separately from normal folders.
                prefs.edit()
                    .putString(KEY_NIGHT_FOLDER, tree.toString())
                    .putBoolean(KEY_NIGHT_MODE, true)
                    .apply()

            } else {
                // Add to the existing normal folder collection.
                val folders = getFolderUris().toMutableSet()
                folders.add(tree.toString())
                saveFolderUris(folders)
            }

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

    private fun requestNotificationPermissionIfNeeded() {
        if (
            Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(
                android.Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(
                arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),
                501
            )
        }
    }

    override fun onDestroy() {
        worker.shutdown()
        super.onDestroy()
    }

    companion object {
        private const val KEY_TREE = "selected_tree_uri"
        private const val KEY_FOLDERS = "selected_folder_uris"

        private const val KEY_NIGHT_FOLDER = "night_mode_folder_uri"
        private const val KEY_NIGHT_MODE = "night_mode_enabled"

        private const val ACTION_DELETE_AND_NEXT =
            "com.example.randomphotowallpaper.DELETE_AND_NEXT"

        private const val ACTION_ADD_FOLDER =
            "com.example.randomphotowallpaper.ADD_FOLDER"

        private const val ACTION_TOGGLE_NIGHT =
            "com.example.randomphotowallpaper.TOGGLE_NIGHT"
    }
}
