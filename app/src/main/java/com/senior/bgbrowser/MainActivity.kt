package com.senior.bgbrowser

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.webkit.ValueCallback
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var webViewContainer: FrameLayout
    private lateinit var tabStripLayout: LinearLayout
    private lateinit var urlInput: EditText
    private lateinit var progressBar: ProgressBar
    private lateinit var btnTabKeepAliveToggle: Button
    private lateinit var btnDesktopModeToggle: Button

    private var pendingFileCallback: ValueCallback<Array<Uri>>? = null

    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val callback = pendingFileCallback ?: return@registerForActivityResult
        val uris = android.webkit.WebChromeClient.FileChooserParams.parseResult(
            result.resultCode,
            result.data
        )
        callback.onReceiveValue(uris)
        pendingFileCallback = null
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        startKeepAliveService()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        TabManager.ensureInitialized(this)
        setupUiProgrammatically()
        bindTabManagerCallbacks()
        checkAndRequestPermissions()
        startKeepAliveService()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val activeWv = TabManager.getActiveTab(this@MainActivity).webView
                if (activeWv.canGoBack()) {
                    activeWv.goBack()
                } else {
                    moveTaskToBack(true)
                }
            }
        })
    }

    private fun setupUiProgrammatically() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        val tabBarContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#141414"))
            gravity = Gravity.CENTER_VERTICAL
            setPadding(8, 8, 8, 4)
        }

        val tabScroll = HorizontalScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            isHorizontalScrollBarEnabled = false
        }

        tabStripLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        tabScroll.addView(tabStripLayout)

        val btnNewTab = Button(this).apply {
            text = "+ Tab"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#2E7D32"))
            setOnClickListener {
                TabManager.createNewTab(this@MainActivity, TabManager.DEFAULT_HOME_URL, keepAlive = true, selectImmediately = true)
                TabManager.attachActiveTabToActivity(this@MainActivity, webViewContainer, BrowserService.overlayContainerRef)
            }
        }

        tabBarContainer.addView(tabScroll)
        tabBarContainer.addView(btnNewTab)

        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(10, 6, 10, 6)
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#1E1E1E"))
        }

        urlInput = EditText(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            hint = "Nhập URL hoặc tìm kiếm Google..."
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = EditorInfo.IME_ACTION_GO
            setSingleLine(true)
            setOnEditorActionListener { _, actionId, event ->
                if (actionId == EditorInfo.IME_ACTION_GO ||
                    (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
                ) {
                    loadFromAddressBar()
                    true
                } else false
            }
        }

        val btnGo = Button(this).apply {
            text = "Đi"
            setOnClickListener { loadFromAddressBar() }
        }

        topBar.addView(urlInput)
        topBar.addView(btnGo)

        val scrollTools = HorizontalScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#252525"))
            isHorizontalScrollBarEnabled = false
        }
        val toolBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(8, 4, 8, 4)
            gravity = Gravity.CENTER_VERTICAL
        }

        val btnBack = Button(this).apply {
            text = "◀"
            setOnClickListener {
                val wv = TabManager.getActiveTab(this@MainActivity).webView
                if (wv.canGoBack()) wv.goBack()
            }
        }
        val btnForward = Button(this).apply {
            text = "▶"
            setOnClickListener {
                val wv = TabManager.getActiveTab(this@MainActivity).webView
                if (wv.canGoForward()) wv.goForward()
            }
        }
        val btnReload = Button(this).apply {
            text = "↻"
            setOnClickListener {
                TabManager.getActiveTab(this@MainActivity).webView.reload()
            }
        }

        btnTabKeepAliveToggle = Button(this).apply {
            text = "🟢 Nền: BẬT"
            setOnClickListener {
                val active = TabManager.getActiveTab(this@MainActivity)
                TabManager.setTabKeepAlive(this@MainActivity, active, !active.keepAliveInBackground)
                updateToolbarForActiveTab(active)
                notifyServiceConfigChanged()
            }
        }

        btnDesktopModeToggle = Button(this).apply {
            text = "📱 Mobile"
            setOnClickListener {
                val active = TabManager.getActiveTab(this@MainActivity)
                TabManager.toggleDesktopMode(this@MainActivity, active)
                updateToolbarForActiveTab(active)
            }
        }

        val btnUserscripts = Button(this).apply {
            text = "🧩 Userscripts"
            setOnClickListener {
                showUserScriptsManagerDialog()
            }
        }

        val btnTestTimer = Button(this).apply {
            text = "⏱ Test Đếm Giây"
            setOnClickListener {
                TabManager.loadBuiltInTimerPageIntoActiveTab(this@MainActivity)
            }
        }

        val btnSettings = Button(this).apply {
            text = "⚙ Cài đặt"
            setOnClickListener {
                showBrowserSettingsDialog()
            }
        }

        val btnOem = Button(this).apply {
            text = "🔋 Chống ngủ OEM"
            setOnClickListener {
                OemBatteryHelper.showOemGuideDialog(this@MainActivity)
            }
        }

        toolBar.addView(btnBack)
        toolBar.addView(btnForward)
        toolBar.addView(btnReload)
        toolBar.addView(btnTabKeepAliveToggle)
        toolBar.addView(btnDesktopModeToggle)
        toolBar.addView(btnUserscripts)
        toolBar.addView(btnTestTimer)
        toolBar.addView(btnSettings)
        toolBar.addView(btnOem)
        scrollTools.addView(toolBar)

        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 8)
            max = 100
        }

        webViewContainer = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }

        root.addView(tabBarContainer)
        root.addView(topBar)
        root.addView(scrollTools)
        root.addView(progressBar)
        root.addView(webViewContainer)

        setContentView(root)
        renderTabStrip()
    }

    private fun renderTabStrip() {
        tabStripLayout.removeAllViews()
        val currentId = TabManager.activeTabId

        for (tab in TabManager.tabs) {
            val isSelected = (tab.id == currentId)
            val chip = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(16, 8, 12, 8)
                val params = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                params.setMargins(4, 0, 4, 0)
                layoutParams = params
                setBackgroundColor(
                    if (isSelected) Color.parseColor("#37474F") else Color.parseColor("#262626")
                )
                setOnClickListener {
                    TabManager.switchTab(
                        this@MainActivity,
                        tab.id,
                        webViewContainer,
                        BrowserService.overlayContainerRef
                    )
                }
            }

            val badge = if (tab.keepAliveInBackground) "🟢 " else "⚪ "
            val shortTitle = tab.title.take(16).ifBlank { "Tab" }

            val tvTitle = TextView(this).apply {
                text = "$badge$shortTitle"
                setTextColor(Color.WHITE)
                textSize = 13f
                if (isSelected) setTypeface(typeface, Typeface.BOLD)
            }

            val tvClose = TextView(this).apply {
                text = "  ✕"
                setTextColor(Color.parseColor("#FF8A80"))
                textSize = 14f
                setPadding(8, 0, 4, 0)
                setOnClickListener {
                    TabManager.closeTab(
                        this@MainActivity,
                        tab.id,
                        webViewContainer,
                        BrowserService.overlayContainerRef
                    )
                    notifyServiceConfigChanged()
                }
            }

            chip.addView(tvTitle)
            chip.addView(tvClose)
            tabStripLayout.addView(chip)
        }
    }

    private fun updateToolbarForActiveTab(tab: BrowserTab) {
        urlInput.setText(tab.url)
        btnTabKeepAliveToggle.text = if (tab.keepAliveInBackground) "🟢 Nền: BẬT" else "⚪ Nền: TẮT"
        btnDesktopModeToggle.text = if (tab.desktopMode) "🖥 Desktop" else "📱 Mobile"
    }

    private fun bindTabManagerCallbacks() {
        TabManager.onTabsChanged = {
            runOnUiThread {
                renderTabStrip()
            }
        }
        TabManager.onActiveTabStateUpdated = { activeTab ->
            runOnUiThread {
                updateToolbarForActiveTab(activeTab)
            }
        }
        TabManager.onProgressChanged = { progress ->
            runOnUiThread {
                progressBar.progress = progress
            }
        }
        TabManager.fileChooserCallback = { callback, params ->
            pendingFileCallback?.onReceiveValue(null)
            pendingFileCallback = callback
            try {
                filePickerLauncher.launch(params.createIntent())
                true
            } catch (_: Exception) {
                pendingFileCallback = null
                false
            }
        }
    }

    private fun loadFromAddressBar() {
        val raw = urlInput.text.toString().trim()
        if (raw.isEmpty()) return
        val active = TabManager.getActiveTab(this)
        if (raw == "bg://test-timer") {
            TabManager.loadBuiltInTimerPageIntoActiveTab(this)
            return
        }
        val finalUrl = when {
            raw.startsWith("http://") || raw.startsWith("https://") -> raw
            raw.contains(".") && !raw.contains(" ") -> "https://$raw"
            else -> "https://www.google.com/search?q=${Uri.encode(raw)}"
        }
        active.webView.loadUrl(finalUrl)
    }

    override fun onStart() {
        super.onStart()
        TabManager.attachActiveTabToActivity(
            this,
            webViewContainer,
            BrowserService.overlayContainerRef
        )
    }

    override fun onStop() {
        super.onStop()
        TabManager.saveTabsState(this)
        val intent = Intent(this, BrowserService::class.java).apply {
            action = BrowserService.ACTION_MOVE_TO_OVERLAY
        }
        ContextCompat.startForegroundService(this, intent)
    }

    private fun showUserScriptsManagerDialog() {
        val scripts = UserScriptManager.getAllScripts(this)
        val scroll = ScrollView(this)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 24, 40, 16)
        }
        scroll.addView(container)

        var dialogRef: AlertDialog? = null

        for (script in scripts) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 12, 0, 12)
            }

            val cb = CheckBox(this).apply {
                isChecked = script.enabled
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                text = "${script.name}\n(@run-at ${script.runAt} • ${script.matches.joinToString()})"
                setOnCheckedChangeListener { _, isChecked ->
                    UserScriptManager.toggleScript(this@MainActivity, script.id, isChecked)
                    TabManager.reloadUserScriptsOnAllTabs(this@MainActivity)
                }
            }

            val btnEdit = Button(this).apply {
                text = "Sửa"
                setOnClickListener {
                    dialogRef?.dismiss()
                    showEditUserScriptDialog(script)
                }
            }

            val btnDel = Button(this).apply {
                text = "Xóa"
                setOnClickListener {
                    UserScriptManager.deleteScript(this@MainActivity, script.id)
                    TabManager.reloadUserScriptsOnAllTabs(this@MainActivity)
                    dialogRef?.dismiss()
                    showUserScriptsManagerDialog()
                }
            }

            row.addView(cb)
            row.addView(btnEdit)
            row.addView(btnDel)
            container.addView(row)
        }

        dialogRef = AlertDialog.Builder(this)
            .setTitle("Trình quản lý Userscript (${scripts.size})")
            .setView(scroll)
            .setPositiveButton("+ Thêm Script mới") { _, _ ->
                showEditUserScriptDialog(null)
            }
            .setNegativeButton("Đóng", null)
            .create()

        dialogRef.show()
    }

    private fun showEditUserScriptDialog(existing: UserScript?) {
        val defaultTemplate = """
            // ==UserScript==
            // @name         Auto Keep Alive Ping
            // @match        *://*/*
            // @run-at       document-end
            // @grant        none
            // ==/UserScript==
            
            console.log('Userscript đã chạy trên: ' + location.href);
        """.trimIndent()

        val editor = EditText(this).apply {
            setText(existing?.code ?: defaultTemplate)
            minLines = 10
            gravity = Gravity.TOP or Gravity.START
            typeface = Typeface.MONOSPACE
            textSize = 12f
        }

        AlertDialog.Builder(this)
            .setTitle(if (existing == null) "Thêm Userscript mới" else "Sửa: ${existing.name}")
            .setView(editor)
            .setPositiveButton("Lưu & Áp dụng") { _, _ ->
                val raw = editor.text.toString()
                if (raw.isNotBlank()) {
                    UserScriptManager.addOrUpdateFromRawCode(
                        this,
                        existing?.id,
                        raw,
                        existing?.enabled ?: true
                    )
                    TabManager.reloadUserScriptsOnAllTabs(this)
                    TabManager.getActiveTab(this).webView.reload()
                    Toast.makeText(this, "Đã lưu Userscript & tải lại tab", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Hủy") { _, _ ->
                showUserScriptsManagerDialog()
            }
            .show()
    }

    private fun showBrowserSettingsDialog() {
        val scroll = ScrollView(this)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 32, 48, 16)
        }
        scroll.addView(layout)

        val cbWakeLock = CheckBox(this).apply {
            text = "Giữ thức CPU & Wi-Fi (PARTIAL_WAKE_LOCK + WifiLock)"
            isChecked = BrowserSettings.isWakeLockEnabled(this@MainActivity)
        }

        val cbSilentAudio = CheckBox(this).apply {
            text = "Phát âm thanh im lặng (AudioTrack + MediaSession)"
            isChecked = BrowserSettings.isSilentAudioEnabled(this@MainActivity)
        }

        val cbBlockPopup = CheckBox(this).apply {
            text = "Chặn cửa sổ bật lên (Block Popups)"
            isChecked = BrowserSettings.isBlockPopups(this@MainActivity)
        }

        val tvUaLabel = TextView(this).apply {
            text = "\nUser-Agent tùy chỉnh (Để trống = Mặc định):"
            setPadding(0, 12, 0, 4)
        }

        val etCustomUa = EditText(this).apply {
            hint = "Mozilla/5.0 (...)"
            setText(BrowserSettings.getCustomUserAgent(this@MainActivity))
            setSingleLine(false)
            maxLines = 3
        }

        val btnOverlayPerm = Button(this).apply {
            text = if (Settings.canDrawOverlays(this@MainActivity)) {
                "✅ Đã cấp quyền Overlay 1x1px"
            } else {
                "⚠ Cấp quyền Overlay 1x1px (Chống giảm nhịp Timer)"
            }
            setOnClickListener {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivity(intent)
            }
        }

        layout.addView(cbWakeLock)
        layout.addView(cbSilentAudio)
        layout.addView(cbBlockPopup)
        layout.addView(tvUaLabel)
        layout.addView(etCustomUa)
        layout.addView(btnOverlayPerm)

        AlertDialog.Builder(this)
            .setTitle("Cài đặt Trình duyệt & Chạy nền")
            .setView(scroll)
            .setPositiveButton("Lưu & Áp dụng") { _, _ ->
                BrowserSettings.setWakeLockEnabled(this, cbWakeLock.isChecked)
                BrowserSettings.setSilentAudioEnabled(this, cbSilentAudio.isChecked)
                BrowserSettings.setBlockPopups(this, cbBlockPopup.isChecked)
                BrowserSettings.setCustomUserAgent(this, etCustomUa.text.toString())

                TabManager.applySettingsToAllTabs(this)
                notifyServiceConfigChanged()
                Toast.makeText(this, "Đã lưu cài đặt", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Hủy", null)
            .show()
    }

    private fun notifyServiceConfigChanged() {
        val updateIntent = Intent(this, BrowserService::class.java).apply {
            action = BrowserService.ACTION_UPDATE_CONFIG
        }
        ContextCompat.startForegroundService(this, updateIntent)
    }

    private fun checkAndRequestPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        if (!OemBatteryHelper.isIgnoringBatteryOptimizations(this)) {
            OemBatteryHelper.requestIgnoreBatteryOptimizations(this)
        }
    }

    private fun startKeepAliveService() {
        val intent = Intent(this, BrowserService::class.java).apply {
            action = BrowserService.ACTION_START
        }
        ContextCompat.startForegroundService(this, intent)
    }
}
