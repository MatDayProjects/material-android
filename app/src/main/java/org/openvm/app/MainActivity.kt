package org.openvm.app

import android.content.DialogInterface
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.tabs.TabLayout
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import org.openvm.app.backend.BackendReadiness
import org.openvm.app.backend.QemuRuntimeController
import org.openvm.app.backend.RuntimeBackendRegistry
import org.openvm.app.backend.RuntimeProcessSnapshot
import org.openvm.app.backend.RuntimeProcessState
import org.openvm.app.model.VmHistoryEntry
import org.openvm.app.model.VmHistoryStore
import org.openvm.app.model.VmProfile
import org.openvm.app.model.VmProfileStore
import org.openvm.app.model.VmStatus
import org.openvm.app.settings.LanguageMode
import org.openvm.app.settings.OpenVmSettings
import org.openvm.app.settings.SettingsStore
import org.openvm.app.runtime.RfbFramebuffer
import org.openvm.app.runtime.RuntimeAssetStore
import org.openvm.app.runtime.VncDisplayClient
import org.openvm.app.ui.Copy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {
    private lateinit var toolbar: com.google.android.material.appbar.MaterialToolbar
    private lateinit var tabs: TabLayout
    private lateinit var contentContainer: FrameLayout
    private lateinit var profileStore: VmProfileStore
    private lateinit var historyStore: VmHistoryStore
    private lateinit var settingsStore: SettingsStore
    private lateinit var backendRegistry: RuntimeBackendRegistry
    private lateinit var runtimeAssetStore: RuntimeAssetStore
    private lateinit var qemuRuntimeController: QemuRuntimeController

    private var settings = OpenVmSettings()
    private var activeTab = TAB_PROFILES
    private var profileSearch: TextInputEditText? = null
    private var profileSearchLayout: TextInputLayout? = null
    private var profileRegexEnabled = false
    private var profileRegexPattern = ""
    private var profileRegexFlags = ""
    private var historySearch: TextInputEditText? = null
    private var historyRegexEnabled = false
    private var historyRegexPattern = ""
    private var historyRegexFlags = ""
    private var pendingImageUri: String? = null
    private var activeImageLabel: TextView? = null
    private var pendingQemuExecutableUri: String? = null
    private var activeQemuExecutableLabel: TextView? = null
    private val displayClients = mutableMapOf<String, VncDisplayClient>()
    private val displayViews = mutableMapOf<String, ImageView>()
    private val pendingStarts = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    private val imagePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        runCatching {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        pendingImageUri = uri.toString()
        activeImageLabel?.text = getString(R.string.guest_image_selected, uri.lastPathSegment ?: uri.toString())
    }

    private val qemuExecutablePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        runCatching {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        pendingQemuExecutableUri = uri.toString()
        activeQemuExecutableLabel?.text = getString(
            R.string.qemu_executable_selected,
            uri.lastPathSegment ?: uri.toString(),
        )
    }

    private val configurationPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        runCatching {
            contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                ?: error("The selected file could not be read")
        }.onSuccess { payload ->
            runCatching { profileStore.importJson(payload) }
                .onSuccess { count ->
                    historyStore.record("imported")
                    showMessage("Imported $count VM profile(s)")
                    renderCurrentTab()
                }
                .onFailure { error -> showError("Import failed: ${error.message ?: "invalid configuration"}") }
        }.onFailure { error -> showError("Import failed: ${error.message ?: "file could not be read"}") }
    }

    private val exportLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) return@registerForActivityResult
        runCatching {
            contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(profileStore.exportJson()) }
                ?: error("The selected destination could not be opened")
        }.onSuccess {
            historyStore.record("exported")
            showMessage("Configuration exported")
        }.onFailure { error -> showError("Export failed: ${error.message ?: "destination could not be written"}") }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        profileStore = VmProfileStore(applicationContext)
        historyStore = VmHistoryStore(applicationContext)
        settingsStore = SettingsStore(applicationContext)
        backendRegistry = RuntimeBackendRegistry(applicationContext)
        runtimeAssetStore = RuntimeAssetStore(applicationContext)
        qemuRuntimeController = QemuRuntimeController(applicationContext)
        settings = settingsStore.read()
        AppCompatDelegate.setDefaultNightMode(if (settings.darkTheme) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO)
        buildShell()
        renderCurrentTab()
    }

    override fun onDestroy() {
        displayClients.values.forEach(VncDisplayClient::close)
        displayClients.clear()
        displayViews.clear()
        if (::qemuRuntimeController.isInitialized) qemuRuntimeController.close()
        super.onDestroy()
    }

    private fun buildShell() {
        window.statusBarColor = Color.TRANSPARENT
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ContextCompat.getColor(this@MainActivity, R.color.openvm_surface))
        }
        toolbar = com.google.android.material.appbar.MaterialToolbar(this).apply {
            title = settings.displayName
            minimumHeight = dp(64)
            setTitleTextColor(ContextCompat.getColor(this@MainActivity, R.color.openvm_on_surface))
        }
        root.addView(toolbar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(64)))

        tabs = TabLayout(this).apply {
            isTabIndicatorFullWidth = false
            addTab(newTab().setText(R.string.tab_virtual_machines), true)
            addTab(newTab().setText(R.string.tab_history))
            addTab(newTab().setText(R.string.tab_settings))
            addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
                override fun onTabSelected(tab: TabLayout.Tab) {
                    activeTab = tab.position
                    renderCurrentTab()
                }

                override fun onTabUnselected(tab: TabLayout.Tab) = Unit
                override fun onTabReselected(tab: TabLayout.Tab) = Unit
            })
        }
        root.addView(tabs, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        contentContainer = FrameLayout(this)
        root.addView(contentContainer, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayShowTitleEnabled(true)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(Menu.NONE, MENU_IMPORT, Menu.NONE, R.string.menu_import)
        menu.add(Menu.NONE, MENU_EXPORT, Menu.NONE, R.string.menu_export)
        menu.add(Menu.NONE, MENU_PALETTE, Menu.NONE, R.string.menu_palette)
        menu.add(Menu.NONE, MENU_ABOUT, Menu.NONE, R.string.menu_about)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        MENU_IMPORT -> { configurationPicker.launch(arrayOf("application/json", "text/json", "text/plain")); true }
        MENU_EXPORT -> { exportLauncher.launch("openvm-config.json"); true }
        MENU_PALETTE -> { showCommandPalette(); true }
        MENU_ABOUT -> { showAbout(); true }
        else -> super.onOptionsItemSelected(item)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_F && event.isCtrlPressed && event.isShiftPressed) {
            showCommandPalette()
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    private fun renderCurrentTab() {
        contentContainer.removeAllViews()
        contentContainer.addView(
            when (activeTab) {
                TAB_PROFILES -> buildProfilesPage()
                TAB_HISTORY -> buildHistoryPage()
                else -> buildSettingsPage()
            },
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
    }

    private fun buildProfilesPage(): View {
        val scroll = ScrollView(this).apply { isFillViewport = true }
        val column = column()
        scroll.addView(column)

        column.addView(
            sectionHeading(
                title = "Local-first VM control",
                subtitle = "Profiles stay on this device. OpenVM never downloads guest images, creates an account, or sends configuration to a service.",
            ),
        )
        column.addView(buildSummaryCard())

        val searchRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL }
        val searchLayout = TextInputLayout(this).apply {
            hint = getString(R.string.action_search)
            endIconMode = TextInputLayout.END_ICON_CLEAR_TEXT
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        profileSearchLayout = searchLayout
        profileSearch = TextInputEditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            addTextChangedListener(SimpleTextWatcher { renderProfiles() })
        }
        searchLayout.addView(profileSearch)
        searchRow.addView(searchLayout)
        searchRow.addView(
            MaterialButton(this).apply {
                text = getString(R.string.action_build_regex)
                contentDescription = getString(R.string.action_build_regex)
                setOnClickListener { showRegexBuilder(forProfiles = true) }
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    marginStart = dp(8)
                }
            },
        )
        column.addView(searchRow)

        column.addView(
            MaterialButton(this).apply {
                text = getString(R.string.action_create_profile)
                setOnClickListener { showProfileEditor(null) }
                layoutParams = fullWidthParams()
            },
        )

        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        profileListContainer = list
        column.addView(list, fullWidthParams())
        renderProfiles()

        column.addView(buildBackendsCard())
        return scroll
    }

    private lateinit var profileListContainer: LinearLayout

    private fun buildSummaryCard(): View {
        val profiles = profileStore.profiles.value
        val running = profiles.count { it.status == VmStatus.RUNNING }
        val card = MaterialCardView(this).apply {
            radius = dp(24).toFloat()
            cardElevation = dp(2).toFloat()
            setCardBackgroundColor(ContextCompat.getColor(this@MainActivity, R.color.openvm_surface_variant))
            layoutParams = fullWidthParams(bottom = 16)
        }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(16), dp(16), dp(16), dp(16)) }
        row.addView(stat("Profiles", profiles.size.toString()), weightParams())
        row.addView(stat("Running", running.toString()), weightParams())
        row.addView(stat("Storage", profiles.sumOf { it.storageGb }.let { "$it GB" }), weightParams())
        card.addView(row)
        return card
    }

    private fun stat(label: String, value: String): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(TextView(this@MainActivity).apply { text = value; textSize = 24f; setTextColor(ContextCompat.getColor(this@MainActivity, R.color.openvm_on_surface)) })
        addView(TextView(this@MainActivity).apply { text = label; textSize = 12f; setTextColor(ContextCompat.getColor(this@MainActivity, R.color.openvm_secondary)) })
    }

    private fun renderProfiles() {
        if (!::profileListContainer.isInitialized) return
        displayViews.clear()
        profileListContainer.removeAllViews()
        val query = profileSearch?.text?.toString()?.trim().orEmpty()
        val regex = if (profileRegexEnabled && profileRegexPattern.isNotBlank()) parseRegex(profileRegexPattern, profileRegexFlags) else null
        profileSearchLayout?.error = if (profileRegexEnabled && query.isNotBlank() && regex == null) getString(R.string.regex_invalid) else null
        val filtered = profileStore.profiles.value.filter { profile ->
            val haystack = listOf(profile.name, profile.androidVersion, profile.architecture, profile.imageLabel().orEmpty()).joinToString(" ")
            if (regex != null) regex.containsMatchIn(haystack) else haystack.contains(query, ignoreCase = true)
        }
        if (filtered.isEmpty()) {
            profileListContainer.addView(emptyCard(Copy.emptyProfiles.render(settings), getString(R.string.empty_profiles_body)))
        } else {
            filtered.forEach { profile -> profileListContainer.addView(profileCard(profile)) }
        }
    }

    private fun profileCard(profile: VmProfile): View {
        val card = MaterialCardView(this).apply {
            radius = dp(24).toFloat()
            cardElevation = dp(1).toFloat()
            layoutParams = fullWidthParams(bottom = 12)
        }
        val body = column().apply { setPadding(dp(16), dp(16), dp(16), dp(12)) }
        val heading = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL }
        heading.addView(TextView(this).apply { text = profile.name; textSize = 20f; setTextColor(ContextCompat.getColor(this@MainActivity, R.color.openvm_on_surface)) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        heading.addView(TextView(this).apply { text = statusLabel(profile.status); textSize = 12f; setTextColor(ContextCompat.getColor(this@MainActivity, R.color.openvm_secondary)) })
        body.addView(heading)
        body.addView(TextView(this).apply {
            text = "${backendLabel(profile.backendId)} · ${profile.androidVersion} · ${profile.architecture} · ${profile.vcpus} vCPU · ${profile.memoryMb} MB · ${profile.storageGb} GB"
            textSize = 14f
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.openvm_secondary))
            setPadding(0, dp(6), 0, 0)
        })
        body.addView(TextView(this).apply {
            text = profile.imageLabel()?.let { getString(R.string.guest_image_selected, it) } ?: getString(R.string.guest_image_none)
            textSize = 13f
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.openvm_secondary))
            setPadding(0, dp(2), 0, 0)
        })
        if (profile.status == VmStatus.RUNNING) {
            body.addView(ImageView(this).apply {
                minimumHeight = dp(220)
                setBackgroundColor(Color.BLACK)
                scaleType = ImageView.ScaleType.FIT_CENTER
                contentDescription = getString(R.string.guest_display, profile.name)
                displayViews[profile.id] = this
            }, fullWidthParams(top = 8, bottom = 4))
        }

        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.END; setPadding(0, dp(8), 0, 0) }
        buttons.addView(MaterialButton(this).apply {
            text = if (profile.status == VmStatus.RUNNING) getString(R.string.action_stop) else getString(R.string.action_start)
            setOnClickListener { handleRuntimeAction(profile) }
        })
        buttons.addView(MaterialButton(this).apply {
            text = getString(R.string.action_edit)
            setOnClickListener { showProfileEditor(profile) }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(4) }
        })
        body.addView(buttons)
        card.addView(body)
        card.setOnLongClickListener { showProfileActions(profile); true }
        return card
    }

    private fun handleRuntimeAction(profile: VmProfile) {
        if (profile.status == VmStatus.STARTING && profile.backendId == "qemu" && pendingStarts.remove(profile.id)) {
            profileStore.updateStatus(profile.id, VmStatus.STOPPED)
            renderCurrentTab()
            showMessage("QEMU start cancelled before the process launched")
            return
        }
        if (profile.status == VmStatus.STARTING) {
            showMessage("The selected runtime is still preparing")
            return
        }
        if (profile.status == VmStatus.RUNNING || profile.status == VmStatus.STOPPING) {
            if (profile.backendId != "qemu") {
                showMessage("Stop is waiting for the selected runtime adapter")
                return
            }
            profileStore.updateStatus(profile.id, VmStatus.STOPPING)
            renderCurrentTab()
            lifecycleScope.launch(Dispatchers.IO) {
                val stopped = qemuRuntimeController.stop(profile.id)
                withContext(Dispatchers.Main) { applyRuntimeSnapshot(stopped) }
            }
            return
        }
        if (profile.backendId != "qemu") {
            val message = if (profile.imageUri.isNullOrBlank()) Copy.imageRequired.render(settings) else Copy.runtimeNotReady.render(settings)
            showMessage(message)
            return
        }
        if (profile.imageUri.isNullOrBlank() || profile.qemuExecutableUri.isNullOrBlank()) {
            showMessage(backendRegistry.startReadiness(profile))
            return
        }
        profileStore.updateStatus(profile.id, VmStatus.STARTING)
        renderCurrentTab()
        showMessage("Validating guest image and starting QEMU")
        pendingStarts.add(profile.id)
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val image = runtimeAssetStore.materializeGuestImage(
                    profile.id,
                    Uri.parse(profile.imageUri),
                    profile.storageGb.toLong() * 1024L * 1024L * 1024L,
                )
                if (!pendingStarts.contains(profile.id)) return@launch
                val executable = runtimeAssetStore.materializeQemuExecutable(profile.id, Uri.parse(profile.qemuExecutableUri))
                if (!pendingStarts.contains(profile.id)) return@launch
                val displaySocket = runtimeAssetStore.prepareDisplaySocket(profile.id)
                val started = qemuRuntimeController.start(
                    profile,
                    executable.file,
                    image.file,
                    displaySocket,
                    listener = { snapshot -> runOnUiThread { applyRuntimeSnapshot(snapshot) } },
                    shouldStart = { pendingStarts.contains(profile.id) },
                )
                withContext(Dispatchers.Main) { applyRuntimeSnapshot(started) }
            } catch (error: Throwable) {
                if (!pendingStarts.contains(profile.id)) return@launch
                withContext(Dispatchers.Main) {
                    profileStore.updateStatus(profile.id, VmStatus.ERROR)
                    renderCurrentTab()
                    showError(error.message ?: "The guest runtime could not be prepared")
                }
            } finally {
                pendingStarts.remove(profile.id)
            }
        }
    }

    private fun applyRuntimeSnapshot(snapshot: RuntimeProcessSnapshot) {
        val status = when (snapshot.state) {
            RuntimeProcessState.RUNNING -> VmStatus.RUNNING
            RuntimeProcessState.STARTING -> VmStatus.STARTING
            RuntimeProcessState.STOPPING -> VmStatus.STOPPING
            RuntimeProcessState.STOPPED -> VmStatus.STOPPED
            RuntimeProcessState.ERROR -> VmStatus.ERROR
        }
        profileStore.updateStatus(snapshot.profileId, status)
        renderCurrentTab()
        if (snapshot.state == RuntimeProcessState.RUNNING && snapshot.displaySocketPath != null) {
            startDisplayClient(snapshot.profileId, snapshot.displaySocketPath)
        } else if (snapshot.state == RuntimeProcessState.STOPPED || snapshot.state == RuntimeProcessState.ERROR) {
            closeDisplayClient(snapshot.profileId)
        }
        when (snapshot.state) {
            RuntimeProcessState.ERROR -> showError(
                buildString {
                    append(snapshot.message)
                    if (snapshot.outputTail.isNotEmpty()) append("\n").append(snapshot.outputTail.takeLast(3).joinToString("\n"))
                },
            )
            RuntimeProcessState.STOPPED -> showMessage(snapshot.message)
            else -> Unit
        }
    }

    private fun startDisplayClient(profileId: String, socketPath: String) {
        if (displayClients.containsKey(profileId)) return
        val client = VncDisplayClient(socketPath)
        displayClients[profileId] = client
        client.start(
            onFrame = { frame ->
                runOnUiThread { displayViews[profileId]?.setImageBitmap(frame.toBitmap()) }
            },
            onError = { message ->
                runOnUiThread {
                    if (profileStore.profiles.value.any { it.id == profileId && it.status == VmStatus.RUNNING }) {
                        showError("Guest display unavailable: $message")
                    }
                }
            },
        )
    }

    private fun closeDisplayClient(profileId: String) {
        displayClients.remove(profileId)?.close()
    }

    private fun RfbFramebuffer.toBitmap(): Bitmap = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)

    private fun showProfileActions(profile: VmProfile) {
        MaterialAlertDialogBuilder(this)
            .setTitle(profile.name)
            .setItems(arrayOf(getString(R.string.menu_edit), getString(R.string.menu_delete))) { _, which ->
                if (which == 0) showProfileEditor(profile) else confirmDelete(profile)
            }
            .show()
    }

    private fun confirmDelete(profile: VmProfile) {
        MaterialAlertDialogBuilder(this)
            .setTitle(if (settings.showEmojis) "⚠️ Delete ${profile.name}?" else "Delete ${profile.name}?")
            .setMessage("This removes only the local profile record. The guest image file is not deleted.")
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.menu_delete) { _, _ ->
                profileStore.delete(profile.id)
                historyStore.record("deleted", profile.name)
                showMessage(Copy.deleted.render(settings))
                renderCurrentTab()
            }
            .show()
    }

    private fun buildBackendsCard(): View {
        val card = MaterialCardView(this).apply {
            radius = dp(24).toFloat()
            cardElevation = dp(1).toFloat()
            layoutParams = fullWidthParams(top = 8, bottom = 24)
        }
        val body = column().apply { setPadding(dp(16), dp(16), dp(16), dp(16)) }
        body.addView(TextView(this).apply { text = getString(R.string.backend_title); textSize = 18f })
        backendRegistry.descriptors().forEach { backend ->
            val state = when (backend.readiness) {
                BackendReadiness.READY -> "Ready"
                BackendReadiness.UNAVAILABLE -> "Unavailable"
                BackendReadiness.NOT_CONFIGURED -> getString(R.string.backend_not_ready)
            }
            body.addView(TextView(this).apply {
                text = "${backend.name} · $state\n${backend.explanation}"
                textSize = 13f
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.openvm_secondary))
                setPadding(0, dp(12), 0, 0)
            })
        }
        card.addView(body)
        return card
    }

    private fun showProfileEditor(existing: VmProfile?) {
        pendingImageUri = existing?.imageUri
        pendingQemuExecutableUri = existing?.qemuExecutableUri
        val form = ScrollView(this)
        val column = column().apply { setPadding(dp(4), 0, dp(4), 0) }
        form.addView(column)
        val name = input(getString(R.string.profile_name), existing?.name.orEmpty(), InputType.TYPE_CLASS_TEXT)
        val androidVersion = input(getString(R.string.android_version), existing?.androidVersion ?: "Android 14", InputType.TYPE_CLASS_TEXT)
        val memory = input(getString(R.string.memory_mb), (existing?.memoryMb ?: 2048).toString(), InputType.TYPE_CLASS_NUMBER)
        val storage = input(getString(R.string.storage_gb), (existing?.storageGb ?: 16).toString(), InputType.TYPE_CLASS_NUMBER)
        val vcpus = input(getString(R.string.vcpus), (existing?.vcpus ?: 2).toString(), InputType.TYPE_CLASS_NUMBER)
        val backendChoices = arrayOf(getString(R.string.backend_avf), getString(R.string.backend_qemu))
        val backendInput = MaterialAutoCompleteTextView(this).apply {
            setSimpleItems(backendChoices)
            setText(if (existing?.backendId == "qemu") backendChoices[1] else backendChoices[0], false)
            inputType = InputType.TYPE_NULL
            contentDescription = getString(R.string.profile_backend)
        }
        val backend = TextInputLayout(this).apply {
            hint = getString(R.string.profile_backend)
            addView(backendInput)
        }
        listOf(name, androidVersion, memory, storage, vcpus, backend).forEach { column.addView(it) }
        val imageLabel = TextView(this).apply {
            text = existing?.imageLabel()?.let { getString(R.string.guest_image_selected, it) } ?: getString(R.string.guest_image_none)
            textSize = 13f
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.openvm_secondary))
            setPadding(0, dp(4), 0, dp(4))
        }
        activeImageLabel = imageLabel
        column.addView(imageLabel)
        column.addView(MaterialButton(this).apply {
            text = getString(R.string.action_import_image)
            setOnClickListener { imagePicker.launch(arrayOf("application/octet-stream", "application/*", "*/*")) }
        })
        val qemuLabel = TextView(this).apply {
            text = pendingQemuExecutableUri?.let { getString(R.string.qemu_executable_selected, Uri.parse(it).lastPathSegment ?: it) }
                ?: getString(R.string.qemu_executable_none)
            textSize = 13f
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.openvm_secondary))
            setPadding(0, dp(8), 0, dp(4))
        }
        activeQemuExecutableLabel = qemuLabel
        column.addView(qemuLabel)
        column.addView(MaterialButton(this).apply {
            text = getString(R.string.action_import_qemu)
            setOnClickListener { qemuExecutablePicker.launch(arrayOf("application/octet-stream", "application/*", "*/*")) }
        })

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(existing?.let { "Edit ${it.name}" } ?: getString(R.string.action_create_profile))
            .setView(form)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_save, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                val draft = runCatching {
                    val profile = (existing ?: VmProfile(name = name.editText?.text?.toString().orEmpty())).copy(
                        name = name.editText?.text?.toString().orEmpty().trim(),
                        androidVersion = androidVersion.editText?.text?.toString().orEmpty().trim(),
                        memoryMb = memory.editText?.text?.toString()?.toIntOrNull() ?: -1,
                        storageGb = storage.editText?.text?.toString()?.toIntOrNull() ?: -1,
                        vcpus = vcpus.editText?.text?.toString()?.toIntOrNull() ?: -1,
                        imageUri = pendingImageUri,
                        backendId = if (backendInput.text?.toString() == backendChoices[1]) "qemu" else "avf",
                        qemuExecutableUri = pendingQemuExecutableUri,
                        updatedAt = System.currentTimeMillis(),
                    )
                    profile
                }.getOrElse { error("Invalid numeric value") }
                val errors = draft.validationErrors()
                if (errors.isNotEmpty()) {
                    showError(errors.joinToString("\n"))
                    return@setOnClickListener
                }
                profileStore.upsert(draft)
                historyStore.record(if (existing == null) "created" else "updated", draft.name)
                dialog.dismiss()
                showMessage(Copy.saved.render(settings))
                renderCurrentTab()
            }
        }
        dialog.setOnDismissListener {
            activeImageLabel = null
            activeQemuExecutableLabel = null
        }
        dialog.show()
    }

    private fun buildHistoryPage(): View {
        val scroll = ScrollView(this).apply { isFillViewport = true }
        val column = column().apply { setPadding(dp(20), dp(20), dp(20), dp(24)) }
        scroll.addView(column)
        column.addView(sectionHeading("Local version history", "Every profile change is recorded locally so the control plane stays auditable."))
        val searchRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL }
        val layout = TextInputLayout(this).apply {
            hint = getString(R.string.action_search)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        historySearch = TextInputEditText(this).apply { addTextChangedListener(SimpleTextWatcher { renderHistoryList(historyContainer) }) }
        layout.addView(historySearch)
        searchRow.addView(layout)
        searchRow.addView(MaterialButton(this).apply {
            text = getString(R.string.action_build_regex)
            setOnClickListener { showRegexBuilder(forProfiles = false) }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(8) }
        })
        column.addView(searchRow)
        historyContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        column.addView(historyContainer, fullWidthParams())
        renderHistoryList(historyContainer)
        return scroll
    }

    private lateinit var historyContainer: LinearLayout

    private fun renderHistoryList(container: LinearLayout) {
        if (!::historyContainer.isInitialized) return
        container.removeAllViews()
        val query = historySearch?.text?.toString()?.trim().orEmpty()
        val regex = if (historyRegexEnabled && historyRegexPattern.isNotBlank()) parseRegex(historyRegexPattern, historyRegexFlags) else null
        val entries = historyStore.entries().asReversed().filter { entry ->
            val text = "${entry.action} ${entry.profileName.orEmpty()}"
            if (regex != null) regex.containsMatchIn(text) else text.contains(query, ignoreCase = true)
        }
        if (entries.isEmpty()) {
            container.addView(emptyCard(getString(R.string.history_empty), "Changes to profiles, imports, and exports will appear here."))
        } else entries.forEach { entry -> container.addView(historyCard(entry)) }
    }

    private fun historyCard(entry: VmHistoryEntry): View = MaterialCardView(this).apply {
        radius = dp(18).toFloat()
        cardElevation = dp(1).toFloat()
        layoutParams = fullWidthParams(bottom = 10)
        addView(column().apply {
            setPadding(dp(14), dp(12), dp(14), dp(12))
            addView(TextView(this@MainActivity).apply { text = historyLabel(entry); textSize = 15f })
            addView(TextView(this@MainActivity).apply {
                text = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(entry.timestamp))
                textSize = 12f
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.openvm_secondary))
                setPadding(0, dp(3), 0, 0)
            })
        })
    }

    private fun historyLabel(entry: VmHistoryEntry): String = when (entry.action) {
        "created" -> getString(R.string.history_created) + (entry.profileName?.let { ": $it" } ?: "")
        "updated" -> getString(R.string.history_updated) + (entry.profileName?.let { ": $it" } ?: "")
        "deleted" -> getString(R.string.history_deleted) + (entry.profileName?.let { ": $it" } ?: "")
        "imported" -> getString(R.string.history_imported)
        "exported" -> getString(R.string.history_exported)
        else -> entry.action
    }

    private fun buildSettingsPage(): View {
        val scroll = ScrollView(this).apply { isFillViewport = true }
        val column = column().apply { setPadding(dp(20), dp(20), dp(20), dp(24)) }
        scroll.addView(column)
        column.addView(sectionHeading("Settings", "Preferences are local and can be changed at any time."))

        val searchLayout = TextInputLayout(this).apply { hint = getString(R.string.action_search) }
        val search = TextInputEditText(this)
        searchLayout.addView(search)
        column.addView(searchLayout)

        val languageCard = settingsCard(getString(R.string.settings_language), getString(R.string.settings_language_explanation))
        val languageButton = MaterialButton(this).apply {
            text = settings.languageMode.displayName()
            setOnClickListener { showLanguagePicker() }
        }
        languageCard.addView(languageButton)
        column.addView(languageCard)

        val messageCard = settingsCard("Messages", "The funny level styles messages without changing facts. Errors and warnings keep their exact meaning.")
        messageCard.addView(sliderWithLabel(getString(R.string.settings_funny_english), settings.englishFunnyLevel) { value ->
            settings = settings.copy(englishFunnyLevel = value)
            settingsStore.write(settings)
        })
        messageCard.addView(sliderWithLabel(getString(R.string.settings_funny_cantonese), settings.cantoneseFunnyLevel) { value ->
            settings = settings.copy(cantoneseFunnyLevel = value)
            settingsStore.write(settings)
        })
        messageCard.addView(MaterialSwitch(this).apply {
            text = getString(R.string.settings_emojis)
            isChecked = settings.showEmojis
            setOnCheckedChangeListener { _, checked ->
                settings = settings.copy(showEmojis = checked)
                settingsStore.write(settings)
            }
        })
        column.addView(messageCard)

        val identityCard = settingsCard(getString(R.string.settings_display_name), getString(R.string.settings_display_name_explanation))
        val displayNameLayout = TextInputLayout(this).apply { hint = getString(R.string.settings_display_name) }
        val displayName = TextInputEditText(this).apply { setText(settings.displayName); inputType = InputType.TYPE_CLASS_TEXT }
        displayNameLayout.addView(displayName)
        identityCard.addView(displayNameLayout)
        val identityButtons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.END }
        identityButtons.addView(MaterialButton(this).apply {
            text = getString(R.string.action_reset)
            setOnClickListener { displayName.setText(getString(R.string.app_name)) }
        })
        identityButtons.addView(MaterialButton(this).apply {
            text = getString(R.string.action_save)
            setOnClickListener {
                val value = displayName.text?.toString()?.trim().orEmpty().ifBlank { getString(R.string.app_name) }.take(64)
                settings = settings.copy(displayName = value)
                settingsStore.write(settings)
                toolbar.title = value
                showMessage("Display name saved")
            }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(4) }
        })
        identityCard.addView(identityButtons)
        column.addView(identityCard)

        val appearanceCard = settingsCard("Appearance", "Choose a light or dark surface. The setting is persisted locally.")
        appearanceCard.addView(MaterialSwitch(this).apply {
            text = "Dark theme"
            isChecked = settings.darkTheme
            setOnCheckedChangeListener { _, checked ->
                settings = settings.copy(darkTheme = checked)
                settingsStore.write(settings)
                AppCompatDelegate.setDefaultNightMode(if (checked) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO)
            }
        })
        column.addView(appearanceCard)

        search.addTextChangedListener(SimpleTextWatcher { query ->
            val normalized = query.trim().lowercase(Locale.ROOT)
            for (index in 2 until column.childCount) {
                val child = column.getChildAt(index)
                if (child === searchLayout) continue
                child.visibility = if (normalized.isBlank() || child.contentDescription?.toString()?.lowercase(Locale.ROOT)?.contains(normalized) != false) View.VISIBLE else View.GONE
            }
        })
        return scroll
    }

    private fun showLanguagePicker() {
        val values = LanguageMode.entries.toTypedArray()
        val labels = values.map { it.displayName() }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.settings_language))
            .setSingleChoiceItems(labels, settings.languageMode.ordinal) { dialog, which ->
                settings = settings.copy(languageMode = values[which])
                settingsStore.write(settings)
                dialog.dismiss()
                renderCurrentTab()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun LanguageMode.displayName(): String = when (this) {
        LanguageMode.ENGLISH -> getString(R.string.settings_english)
        LanguageMode.CANTONESE -> getString(R.string.settings_cantonese)
        LanguageMode.BILINGUAL -> getString(R.string.settings_bilingual)
    }

    private fun sliderWithLabel(label: String, value: Int, onChange: (Int) -> Unit): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        contentDescription = label
        addView(TextView(this@MainActivity).apply { text = "$label: $value"; tag = label })
        addView(Slider(this@MainActivity).apply {
            valueFrom = 1f
            valueTo = 5f
            stepSize = 1f
            this.value = value.toFloat()
            addOnChangeListener { slider, newValue, _ ->
                val rounded = newValue.roundToInt()
                (getChildAt(0) as TextView).text = "$label: $rounded"
                onChange(rounded)
            }
        })
    }

    private fun settingsCard(title: String, explanation: String): LinearLayout = column().apply {
        contentDescription = "$title $explanation"
        setPadding(dp(16), dp(14), dp(16), dp(12))
        background = GradientDrawable().apply {
            setColor(ContextCompat.getColor(this@MainActivity, R.color.openvm_surface_variant))
            cornerRadius = dp(24).toFloat()
        }
        addView(TextView(this@MainActivity).apply { text = title; textSize = 18f })
        addView(TextView(this@MainActivity).apply {
            text = explanation
            textSize = 13f
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.openvm_secondary))
            setPadding(0, dp(4), 0, dp(8))
        })
        layoutParams = fullWidthParams(bottom = 12)
    }

    private fun showRegexBuilder(forProfiles: Boolean) {
        val form = column()
        val patternLayout = TextInputLayout(this).apply { hint = getString(R.string.regex_pattern) }
        val pattern = TextInputEditText(this).apply {
            setText(if (forProfiles) profileRegexPattern else historyRegexPattern)
            inputType = InputType.TYPE_CLASS_TEXT
        }
        patternLayout.addView(pattern)
        form.addView(patternLayout)
        val flagsLayout = TextInputLayout(this).apply { hint = getString(R.string.regex_flags) }
        val flags = TextInputEditText(this).apply { setText(if (forProfiles) profileRegexFlags else historyRegexFlags) }
        flagsLayout.addView(flags)
        form.addView(flagsLayout)
        val sampleLayout = TextInputLayout(this).apply { hint = getString(R.string.regex_sample) }
        val sample = TextInputEditText(this).apply { setText("OpenVM arm64 Android 14") }
        sampleLayout.addView(sample)
        form.addView(sampleLayout)
        val preview = TextView(this).apply { textSize = 13f; setTextColor(ContextCompat.getColor(this@MainActivity, R.color.openvm_secondary)); setPadding(0, dp(8), 0, 0) }
        form.addView(preview)
        val inserts = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL }
        listOf("^", "$", "[a-z]", "( )", "|", ".*", "\\d+").forEach { token ->
            inserts.addView(MaterialButton(this).apply {
                text = token
                setOnClickListener {
                    val position = pattern.selectionStart.coerceAtLeast(0)
                    pattern.text?.insert(position, token)
                }
            })
        }
        form.addView(inserts)
        val updatePreview = {
            val parsed = parseRegex(pattern.text?.toString().orEmpty(), flags.text?.toString().orEmpty())
            preview.text = if (parsed == null) getString(R.string.regex_invalid) else {
                val matches = parsed.findAll(sample.text?.toString().orEmpty()).count()
                "${getString(R.string.regex_matches)}: $matches"
            }
        }
        pattern.addTextChangedListener(SimpleTextWatcher { updatePreview() })
        flags.addTextChangedListener(SimpleTextWatcher { updatePreview() })
        sample.addTextChangedListener(SimpleTextWatcher { updatePreview() })
        updatePreview()
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.action_build_regex))
            .setView(form)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton("Use pattern", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                val nextPattern = pattern.text?.toString().orEmpty()
                val nextFlags = flags.text?.toString().orEmpty()
                if (nextPattern.isBlank() || parseRegex(nextPattern, nextFlags) == null) {
                    preview.text = getString(R.string.regex_invalid)
                    return@setOnClickListener
                }
                if (forProfiles) {
                    profileRegexEnabled = true
                    profileRegexPattern = nextPattern
                    profileRegexFlags = nextFlags
                    profileSearch?.setText(nextPattern)
                    renderProfiles()
                } else {
                    historyRegexEnabled = true
                    historyRegexPattern = nextPattern
                    historyRegexFlags = nextFlags
                    historySearch?.setText(nextPattern)
                    renderHistoryList(historyContainer)
                }
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun parseRegex(pattern: String, flags: String): Regex? {
        val options = mutableSetOf<RegexOption>()
        flags.forEach { flag ->
            when (flag) {
                'i' -> options += RegexOption.IGNORE_CASE
                'm' -> options += RegexOption.MULTILINE
                's' -> options += RegexOption.DOT_MATCHES_ALL
                else -> return null
            }
        }
        return runCatching { Regex(pattern.take(MAX_PATTERN_LENGTH), options) }.getOrNull()
    }

    private fun showCommandPalette() {
        val root = column()
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.menu_palette))
            .setView(root)
            .setNegativeButton(R.string.action_close, null)
            .create()
        root.setPadding(dp(8), 0, dp(8), 0)
        val searchLayout = TextInputLayout(this).apply { hint = getString(R.string.action_search) }
        val search = TextInputEditText(this)
        searchLayout.addView(search)
        root.addView(searchLayout)
        val commands = listOf(
            "Create VM profile" to { showProfileEditor(null) },
            "Import configuration" to { configurationPicker.launch(arrayOf("application/json", "text/json", "text/plain")) },
            "Export configuration" to { exportLauncher.launch("openvm-config.json") },
            "Open history" to { tabs.getTabAt(TAB_HISTORY)?.select() },
            "Open settings" to { tabs.getTabAt(TAB_SETTINGS)?.select() },
        )
        val buttons = commands.map { (label, action) ->
            MaterialButton(this).apply {
                text = label
                setOnClickListener { dialog.dismiss(); action() }
                layoutParams = fullWidthParams()
            }
        }
        buttons.forEach(root::addView)
        search.addTextChangedListener(SimpleTextWatcher { query ->
            val normalized = query.trim().lowercase(Locale.ROOT)
            buttons.forEach { button -> button.visibility = if (normalized.isBlank() || button.text.toString().lowercase(Locale.ROOT).contains(normalized)) View.VISIBLE else View.GONE }
        })
        dialog.show()
    }

    private fun showAbout() {
        MaterialAlertDialogBuilder(this)
            .setTitle(settings.displayName)
            .setMessage(getString(R.string.about_body) + "\n\nVersion 0.1.0")
            .setPositiveButton(R.string.action_close, null)
            .show()
    }

    private fun showMessage(message: String) {
        val finalMessage = if (settings.showEmojis && !message.startsWith("⚠️")) "✅ $message" else message
        Snackbar.make(contentContainer, finalMessage, Snackbar.LENGTH_LONG).show()
    }

    private fun showError(message: String) {
        val finalMessage = if (settings.showEmojis) "⚠️ $message" else message
        Snackbar.make(contentContainer, finalMessage, Snackbar.LENGTH_LONG).show()
    }

    private fun emptyCard(title: String, bodyText: String): View = MaterialCardView(this).apply {
        radius = dp(24).toFloat()
        cardElevation = dp(1).toFloat()
        layoutParams = fullWidthParams(bottom = 16)
        addView(column().apply {
            setPadding(dp(20), dp(20), dp(20), dp(20))
            addView(TextView(this@MainActivity).apply { text = title; textSize = 18f })
            addView(TextView(this@MainActivity).apply {
                text = bodyText
                textSize = 14f
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.openvm_secondary))
                setPadding(0, dp(6), 0, 0)
            })
        })
    }

    private fun sectionHeading(title: String, subtitle: String): View = column().apply {
        setPadding(0, 0, 0, dp(16))
        addView(TextView(this@MainActivity).apply { text = title; textSize = 24f })
        addView(TextView(this@MainActivity).apply {
            text = subtitle
            textSize = 14f
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.openvm_secondary))
            setPadding(0, dp(6), 0, 0)
        })
    }

    private fun input(hintText: String, value: String, type: Int): TextInputLayout = TextInputLayout(this).apply {
        hint = hintText
        layoutParams = fullWidthParams(bottom = 8)
        addView(TextInputEditText(this@MainActivity).apply { setText(value); inputType = type })
    }

    private fun column(): LinearLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

    private fun statusLabel(status: VmStatus): String = when (status) {
        VmStatus.RUNNING -> getString(R.string.profile_running)
        VmStatus.STOPPED -> getString(R.string.profile_stopped)
        VmStatus.STARTING -> "Starting"
        VmStatus.STOPPING -> "Stopping"
        VmStatus.ERROR -> "Error"
    }

    private fun backendLabel(id: String): String = when (id) {
        "qemu" -> getString(R.string.backend_qemu)
        else -> getString(R.string.backend_avf)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    private fun fullWidthParams(top: Int = 0, bottom: Int = 0): LinearLayout.LayoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
        setMargins(0, dp(top), 0, dp(bottom))
    }

    private fun weightParams(): LinearLayout.LayoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)

    private class SimpleTextWatcher(private val onChanged: (String) -> Unit) : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = onChanged(s?.toString().orEmpty())
        override fun afterTextChanged(s: Editable?) = Unit
    }

    companion object {
        private const val TAB_PROFILES = 0
        private const val TAB_HISTORY = 1
        private const val TAB_SETTINGS = 2
        private const val MENU_IMPORT = 100
        private const val MENU_EXPORT = 101
        private const val MENU_PALETTE = 102
        private const val MENU_ABOUT = 103
        private const val MAX_PATTERN_LENGTH = 256
    }
}
