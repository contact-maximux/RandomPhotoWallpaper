package com.example.randomphotowallpaper

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
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

        resetNightModeAfterRestart()
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

            ACTION_ADD_FOLDER -> openFolderPicker(false)

            ACTION_TOGGLE_NIGHT -> toggleNightMode()

            else -> {
                if (getFolderUris().isEmpty()) {
                    openFolderPicker(false)
                } else {
                    changeWallpaper()
                }
            }
        }
    }

    // Detect a device reboot using Android's boot counter.
    private fun resetNightModeAfterRestart() {
        try {
            val bootCount = Settings.Global.getInt(
                contentResolver,
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
            // Keep the app usable if the device does not expose boot count.
        }
    }

    private fun toggleNightMode() {
        if (prefs.getBoolean(KEY_NIGHT_MODE, false)) {
            // Day mode never requires a PIN.
            prefs.edit()
                .putBoolean(KEY_NIGHT_MODE, false)
                .apply()

            changeWallpaper()
        } else {
            authenticateForNightMode()
        }
    }

    private fun authenticateForNightMode() {
        val savedPin = NightPinStore.read(this)

        if (savedPin == null) {
            showCreatePinDialog()
        } else {
            showEnterPinDialog(savedPin)
        }
    }

    private fun showCreatePinDialog() {
        val firstPin = EditText(this).apply {
            hint = "Create 4–8 digit PIN"
            inputType = InputType.TYPE_CLASS_NUMBER or
                InputType.TYPE_NUMBER_VARIATION_PASSWORD
        }

        val confirmPin = EditText(this).apply {
            hint = "Confirm PIN"
            inputType = InputType.TYPE_CLASS_NUMBER or
                InputType.TYPE_NUMBER_VARIATION_PASSWORD
        }

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 12, 48, 0)
            addView(firstPin)
            addView(confirmPin)
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle("Create Night mode PIN")
            .setMessage("Create a 4–8 digit PIN to protect Night mode.")
            .setView(layout)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val pin = firstPin.text.toString()
                val confirmation = confirmPin.text.toString()

                if (!pin.matches(Regex("\\d{4,8}"))) {
                    firstPin.error = "Enter 4–8 digits"
                    return@setOnClickListener
                }

                if (pin != confirmation) {
                    confirmPin.error = "PINs do not match"
                    return@setOnClickListener
                }

                try {
                    NightPinStore.save(this, pin)
                    dialog.dismiss()
                    continueEnablingNightMode()
                } catch (e: Exception) {
                    Toast.makeText(
                        this,
                        "Could not save PIN: ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }

        dialog.show()
    }

    private fun showEnterPinDialog(savedPin: String) {
        val pinInput = EditText(this).apply {
            hint = "Enter Night mode PIN"
            inputType = InputType.TYPE_CLASS_NUMBER or
                InputType.TYPE_NUMBER_VARIATION_PASSWORD
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle("Night mode")
            .setMessage("Enter your PIN to enable Night mode.")
            .setView(pinInput)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Unlock", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (pinInput.text.toString() == savedPin) {
                    dialog.dismiss()
                    continueEnablingNightMode()
                } else {
                    pinInput.error = "Incorrect PIN"
                }
            }
        }

        dialog.show()
    }

    private fun continueEnablingNightMode() {
        val nightFolder = prefs.getString(KEY_NIGHT_FOLDER, null)

        if (nightFolder.isNullOrBlank()) {
            openFolderPicker(true)
        } else {
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
            if (!isFinishing) {
                Toast.makeText(
                    this,
                    message,
                    Toast.LENGTH_LONG
                ).show()
                finish()
            }
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
                contentResolver.takePersistableUriPermission(tree, flags)
            } catch (_: SecurityException) {
            }

            if (pickingNightFolder) {
                prefs.edit()
                    .putString(KEY_NIGHT_FOLDER, tree.toString())
                    .putBoolean(KEY_NIGHT_MODE, true)
                    .apply()
            } else {
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
        folders.forEach { array.put(it) }

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
        private const val KEY_LAST_BOOT_COUNT = "last_seen_boot_count"

        private const val ACTION_DELETE_AND_NEXT =
            "com.example.randomphotowallpaper.DELETE_AND_NEXT"

        private const val ACTION_ADD_FOLDER =
            "com.example.randomphotowallpaper.ADD_FOLDER"

        private const val ACTION_TOGGLE_NIGHT =
            "com.example.randomphotowallpaper.TOGGLE_NIGHT"
    }
}

// PIN encryption using Android Keystore.
private object NightPinStore {

    private const val PREFS = "wallpaper_state"
    private const val KEY_ENCRYPTED_PIN = "night_mode_encrypted_pin"
    private const val KEY_ALIAS = "night_mode_pin_key"

    fun save(context: Activity, pin: String) {
        val key = getOrCreateKey()
        val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, key)

        val encrypted = cipher.doFinal(pin.toByteArray(Charsets.UTF_8))
        val iv = cipher.iv

        val combined = iv + encrypted

        val encoded = android.util.Base64.encodeToString(
            combined,
            android.util.Base64.NO_WRAP
        )

        context.getSharedPreferences(PREFS, Activity.MODE_PRIVATE)
            .edit()
            .putString(KEY_ENCRYPTED_PIN, encoded)
            .apply()
    }

    fun read(context: Activity): String? {
        val encoded = context.getSharedPreferences(
            PREFS,
            Activity.MODE_PRIVATE
        ).getString(KEY_ENCRYPTED_PIN, null) ?: return null

        val combined = android.util.Base64.decode(
            encoded,
            android.util.Base64.NO_WRAP
        )

        val iv = combined.copyOfRange(0, 12)
        val encrypted = combined.copyOfRange(12, combined.size)

        val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            javax.crypto.Cipher.DECRYPT_MODE,
            getOrCreateKey(),
            javax.crypto.spec.GCMParameterSpec(128, iv)
        )

        return String(
            cipher.doFinal(encrypted),
            Charsets.UTF_8
        )
    }

    private fun getOrCreateKey(): java.security.Key {
        val keyStore = java.security.KeyStore.getInstance("AndroidKeyStore")
        keyStore.load(null)

        val existing = keyStore.getKey(KEY_ALIAS, null)
        if (existing != null) return existing

        val generator = javax.crypto.KeyGenerator.getInstance(
            android.security.keystore.KeyProperties.KEY_ALGORITHM_AES,
            "AndroidKeyStore"
        )

        val spec = android.security.keystore.KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or
                android.security.keystore.KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(
                android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE
            )
            .setRandomizedEncryptionRequired(true)
            .build()

        generator.init(spec)
        return generator.generateKey()
    }
}