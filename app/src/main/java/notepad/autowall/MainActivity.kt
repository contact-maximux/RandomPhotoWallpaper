
package notepad.autowall
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.content.Context
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
        }
    }

    private fun toggleNightMode() {
        if (prefs.getBoolean(KEY_NIGHT_MODE, false)) {
            prefs.edit()
                .putBoolean(KEY_NIGHT_MODE, false)
                .apply()

            changeWallpaper()
        } else {
            authenticateForNightMode()
        }
    }

    private fun authenticateForNightMode() {
        val savedPin = try {
            NightPinStore.read(this)
        } catch (_: Exception) {
            Toast.makeText(
                this,
                "Could not read saved PIN.",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        if (savedPin == null) {
            showCreatePinDialog()
        } else {
            showEnterPinDialog(savedPin)
        }
    }

    private fun createPinInput(hintText: String): EditText {
        return EditText(this).apply {
            hint = hintText
            inputType = InputType.TYPE_CLASS_NUMBER or
                InputType.TYPE_NUMBER_VARIATION_PASSWORD
            filters = arrayOf(InputFilter.LengthFilter(4))
            isFocusableInTouchMode = true
        }
    }

    private fun focusAndShowKeyboard(input: EditText, dialog: AlertDialog) {
        dialog.window?.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE
        )

        input.requestFocus()

        input.post {
            input.selectAll()

            val imm = getSystemService(
                Context.INPUT_METHOD_SERVICE
            ) as InputMethodManager

            imm.showSoftInput(
                input,
                InputMethodManager.SHOW_IMPLICIT
            )
        }
    }

    private fun showCreatePinDialog() {

        val firstPin = createPinInput("Create 4-digit PIN")
        val confirmPin = createPinInput("Confirm 4-digit PIN")

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 12, 48, 0)
            addView(firstPin)
            addView(confirmPin)
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle("Create Night mode PIN")
            .setMessage("Create a 4-digit PIN to protect Night mode.")
            .setView(layout)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener {

                    val pin = firstPin.text.toString()
                    val confirmation = confirmPin.text.toString()

                    if (!pin.matches(Regex("\\d{4}"))) {
                        firstPin.error = "Enter exactly 4 digits"
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

            focusAndShowKeyboard(firstPin, dialog)
        }

        dialog.show()
    }

    private fun showEnterPinDialog(savedPin: String) {

        val pinInput = createPinInput("Enter 4-digit PIN")

        val dialog = AlertDialog.Builder(this)
            .setTitle("Night mode")
            .setMessage("Enter your PIN to enable Night mode.")
            .setView(pinInput)
            .setNegativeButton("Cancel", null)
            .create()

        var submitted = false

        pinInput.addTextChangedListener(object : TextWatcher {

            override fun beforeTextChanged(
                s: CharSequence?,
                start: Int,
                count: Int,
                after: Int
            ) {}

            override fun onTextChanged(
                s: CharSequence?,
                start: Int,
                before: Int,
                count: Int
            ) {
                if (s?.length == 4 && !submitted) {
                    submitted = true

                    if (s.toString() == savedPin) {
                        dialog.dismiss()
                        continueEnablingNightMode()
                    } else {
                        // Hide the keyboard first.
                        val imm = getSystemService(
                            Context.INPUT_METHOD_SERVICE
                        ) as InputMethodManager
                    
                        imm.hideSoftInputFromWindow(
                            pinInput.windowToken,
                            0
                        )
                    
                        // Close the PIN dialog.
                        dialog.dismiss()
                    
                        // Close the Activity automatically.
                        finish()
                    
                        // Show error message.
                        Toast.makeText(
                            this@MainActivity,
                            "Wrong PIN. Night mode not enabled.",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }

            override fun afterTextChanged(s: android.text.Editable?) {}
        })

        dialog.setOnShowListener {
            focusAndShowKeyboard(pinInput, dialog)
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

        const val ACTION_DELETE_AND_NEXT = "notepad.autowall.DELETE_AND_NEXT"
        const val ACTION_ADD_FOLDER = "notepad.autowall.ADD_FOLDER"
        const val ACTION_TOGGLE_NIGHT = "notepad.autowall.TOGGLE_NIGHT"
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

        require(combined.size > 12) {
            "Invalid saved PIN data."
        }

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
