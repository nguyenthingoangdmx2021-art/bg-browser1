package com.senior.bgbrowser

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.Context
import android.content.MutableContextWrapper
import android.graphics.Bitmap
import android.net.Uri
import android.os.Environment
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import androidx.webkit.ScriptHandler
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

class BrowserTab(
    val id: String = UUID.randomUUID().toString(),
    val contextWrapper: MutableContextWrapper,
    var webView: KeepAliveWebView,
    var title: String = "Tab mới",
    var url: String = "https://www.google.com",
    var keepAliveInBackground: Boolean = true,
    var desktopMode: Boolean = false,
    var defaultUserAgent: String = "",
    val docStartHandlers: MutableList<ScriptHandler> = mutableListOf()
)

object TabManager {
    private const val PREFS_NAME = "bg_browser_tabs_state"
    private const val KEY_TABS_JSON = "saved_tabs_json"
    private const val KEY_ACTIVE_TAB_ID = "active_tab_id"
    const val DEFAULT_HOME_URL = "https://www.google.com"

    val tabs = mutableListOf<BrowserTab>()
    var activeTabId: String? = null
        private set

    private var isInitialized = false
    private var currentActivityContext: Context? = null

    var onTabsChanged: (() -> Unit)? = null
    var onActiveTabStateUpdated: ((BrowserTab) -> Unit)? = null
    var onProgressChanged: ((Int) -> Unit)? = null
    var fileChooserCallback: ((ValueCallback<Array<Uri>>, WebChromeClient.FileChooserParams) -> Boolean)? = null

    fun ensureInitialized(context: Context) {
        if (isInitialized && tabs.isNotEmpty()) return
        isInitialized = true
        val appCtx = context.applicationContext
        UserScriptManager.ensureLoaded(appCtx)

        val prefs = appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val rawJson = prefs.getString(KEY_TABS_JSON, null)
        val savedActiveId = prefs.getString(KEY_ACTIVE_TAB_ID, null)

        tabs.clear()
        if (!rawJson.isNullOrBlank()) {
            try {
                val arr = JSONArray(rawJson)
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    val id = obj.optString("id", UUID.randomUUID().toString())
                    val url = obj.optString("url", DEFAULT_HOME_URL)
                    val title = obj.optString("title", "Tab")
                    val keepBg = obj.optBoolean("keepAlive", true)
                    val desktop = obj.optBoolean("desktopMode", false)
                    createTabInternal(
                        appContext = appCtx,
                        id = id,
                        initialUrl = url,
                        initialTitle = title,
                        keepAlive = keepBg,
                        desktopMode = desktop,
                        selectImmediately = false
                    )
                }
            } catch (_: Exception) {}
        }

        if (tabs.isEmpty()) {
            createTabInternal(
                appContext = appCtx,
                id = UUID.randomUUID().toString(),
                initialUrl = DEFAULT_HOME_URL,
                initialTitle = "Google",
                keepAlive = true,
                desktopMode = false,
                selectImmediately = true
            )
        } else {
            activeTabId = if (tabs.any { it.id == savedActiveId }) {
                savedActiveId
            } else {
                tabs.first().id
            }
        }
    }

    fun getActiveTab(context: Context): BrowserTab {
        ensureInitialized(context)
        return tabs.find { it.id == activeTabId } ?: tabs.first().also { activeTabId = it.id }
    }

    fun createNewTab(
        context: Context,
        url: String = DEFAULT_HOME_URL,
        keepAlive: Boolean = true,
        selectImmediately: Boolean = true
    ): BrowserTab {
        ensureInitialized(context)
        val tab = createTabInternal(
            appContext = context.applicationContext,
            id = UUID.randomUUID().toString(),
            initialUrl = url,
            initialTitle = "Tab mới",
            keepAlive = keepAlive,
            desktopMode = false,
            selectImmediately = selectImmediately
        )
        saveTabsState(context)
        onTabsChanged?.invoke()
        return tab
    }

    private fun createTabInternal(
        appContext: Context,
        id: String,
        initialUrl: String,
        initialTitle: String,
        keepAlive: Boolean,
        desktopMode: Boolean,
        selectImmediately: Boolean
    ): BrowserTab {
        val wrapper = MutableContextWrapper(currentActivityContext ?: appContext)
        val wv = KeepAliveWebView(wrapper)
        val defaultUa = wv.settings.userAgentString ?: ""

        val tab = BrowserTab(
            id = id,
            contextWrapper = wrapper,
            webView = wv,
            title = initialTitle,
            url = initialUrl,
            keepAliveInBackground = keepAlive,
            desktopMode = desktopMode,
            defaultUserAgent = defaultUa
        )

        configureWebViewForTab(appContext, tab)
        tabs.add(tab)

        if (selectImmediately || activeTabId == null) {
            activeTabId = tab.id
        }

        if (initialUrl == "bg://test-timer") {
            loadBuiltInTimerPageIntoTab(tab)
        } else {
            tab.webView.loadUrl(initialUrl)
        }
        return tab
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebViewForTab(appContext: Context, tab: BrowserTab) {
        val wv = tab.webView
        wv.keepAliveInBackground = tab.keepAliveInBackground
        wv.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )

        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            cacheMode = WebSettings.LOAD_DEFAULT
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
        }

        applyTabSettings(appContext, tab)

        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(wv, true)

        UserScriptManager.installDocumentStartScripts(appContext, wv, tab.docStartHandlers)

        wv.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                val targetUrl = request?.url?.toString() ?: return false
                return !(targetUrl.startsWith("http://") || targetUrl.startsWith("https://"))
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                if (!url.isNullOrBlank()) {
                    tab.url = url
                    if (tab.id == activeTabId) {
                        onActiveTabStateUpdated?.invoke(tab)
                    }
                    UserScriptManager.injectScriptsForLifecycle(appContext, wv, url, isPageStarted = true)
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                CookieManager.getInstance().flush()
                if (!url.isNullOrBlank()) {
                    tab.url = url
                    val pageTitle = view?.title
                    if (!pageTitle.isNullOrBlank()) {
                        tab.title = pageTitle
                    }
                    saveTabsState(appContext)
                    if (tab.id == activeTabId) {
                        onActiveTabStateUpdated?.invoke(tab)
                    }
                    onTabsChanged?.invoke()
                    UserScriptManager.injectScriptsForLifecycle(appContext, wv, url, isPageStarted = false)
                }
            }

            override fun onRenderProcessGone(
                view: WebView?,
                detail: RenderProcessGoneDetail?
            ): Boolean {
                try {
                    (view?.parent as? ViewGroup)?.removeView(view)
                    view?.destroy()
                } catch (_: Exception) {}

                val newWv = KeepAliveWebView(tab.contextWrapper)
                tab.webView = newWv
                configureWebViewForTab(appContext, tab)
                if (tab.url == "bg://test-timer") {
                    loadBuiltInTimerPageIntoTab(tab)
                } else {
                    newWv.loadUrl(tab.url)
                }
                onTabsChanged?.invoke()
                return true
            }
        }

        wv.webChromeClient = object : WebChromeClient() {
            override fun onReceivedTitle(view: WebView?, title: String?) {
                super.onReceivedTitle(view, title)
                if (!title.isNullOrBlank()) {
                    tab.title = title
                    saveTabsState(appContext)
                    if (tab.id == activeTabId) {
                        onActiveTabStateUpdated?.invoke(tab)
                    }
                    onTabsChanged?.invoke()
                }
            }

            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                super.onProgressChanged(view, newProgress)
                if (tab.id == activeTabId) {
                    onProgressChanged?.invoke(newProgress)
                }
            }

            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                if (filePathCallback == null || fileChooserParams == null) return false
                val handler = fileChooserCallback
                return if (handler != null) {
                    handler.invoke(filePathCallback, fileChooserParams)
                } else {
                    filePathCallback.onReceiveValue(null)
                    false
                }
            }
        }

        wv.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            try {
                val request = DownloadManager.Request(Uri.parse(url)).apply {
                    setMimeType(mimeType)
                    val cookies = CookieManager.getInstance().getCookie(url)
                    addRequestHeader("cookie", cookies)
                    addRequestHeader("User-Agent", userAgent)
                    setDescription("Đang tải file...")
                    val fileName = URLUtil.guessFileName(url, contentDisposition, mimeType)
                    setTitle(fileName)
                    setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
                }
                val dm = appContext.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                dm.enqueue(request)
                Toast.makeText(appContext, "Đang tải: $url", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(appContext, "Lỗi tải file: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun applyTabSettings(context: Context, tab: BrowserTab) {
        val customUa = BrowserSettings.getCustomUserAgent(context)
        val blockPopups = BrowserSettings.isBlockPopups(context)

        tab.webView.settings.apply {
            javaScriptCanOpenWindowsAutomatically = !blockPopups
            setSupportMultipleWindows(!blockPopups)

            userAgentString = when {
                customUa.isNotEmpty() -> customUa
                tab.desktopMode -> BrowserSettings.DESKTOP_USER_AGENT
                else -> tab.defaultUserAgent
            }

            useWideViewPort = true
            loadWithOverviewMode = true
        }
    }

    fun applySettingsToAllTabs(context: Context) {
        ensureInitialized(context)
        tabs.forEach { applyTabSettings(context, it) }
    }

    fun reloadUserScriptsOnAllTabs(context: Context) {
        ensureInitialized(context)
        tabs.forEach { tab ->
            UserScriptManager.installDocumentStartScripts(
                context.applicationContext,
                tab.webView,
                tab.docStartHandlers
            )
        }
    }

    fun switchTab(
        activityContext: Context,
        tabId: String,
        activityContainer: FrameLayout,
        serviceOverlayContainer: FrameLayout?
    ) {
        ensureInitialized(activityContext)
        if (tabs.none { it.id == tabId }) return
        activeTabId = tabId
        saveTabsState(activityContext)
        attachActiveTabToActivity(activityContext, activityContainer, serviceOverlayContainer)
        onTabsChanged?.invoke()
    }

    fun closeTab(
        context: Context,
        tabId: String,
        activityContainer: FrameLayout?,
        serviceOverlayContainer: FrameLayout?
    ) {
        ensureInitialized(context)
        val index = tabs.indexOfFirst { it.id == tabId }
        if (index < 0) return

        val removed = tabs.removeAt(index)
        try {
            (removed.webView.parent as? ViewGroup)?.removeView(removed.webView)
            removed.webView.stopLoading()
            removed.webView.destroy()
        } catch (_: Exception) {}

        if (tabs.isEmpty()) {
            createNewTab(context, DEFAULT_HOME_URL, keepAlive = true, selectImmediately = true)
        } else if (activeTabId == tabId) {
            val nextIdx = index.coerceAtMost(tabs.size - 1)
            activeTabId = tabs[nextIdx].id
        }

        saveTabsState(context)
        if (activityContainer != null && currentActivityContext != null) {
            attachActiveTabToActivity(currentActivityContext!!, activityContainer, serviceOverlayContainer)
        }
        onTabsChanged?.invoke()
    }

    fun setTabKeepAlive(context: Context, tab: BrowserTab, enabled: Boolean) {
        tab.keepAliveInBackground = enabled
        tab.webView.keepAliveInBackground = enabled
        if (enabled) {
            tab.webView.onResume()
            tab.webView.resumeTimers()
        }
        saveTabsState(context)
        onTabsChanged?.invoke()
    }

    fun toggleDesktopMode(context: Context, tab: BrowserTab) {
        tab.desktopMode = !tab.desktopMode
        applyTabSettings(context, tab)
        tab.webView.reload()
        saveTabsState(context)
        onActiveTabStateUpdated?.invoke(tab)
    }

    fun attachActiveTabToActivity(
        activityContext: Context,
        activityContainer: FrameLayout,
        serviceOverlayContainer: FrameLayout?
    ) {
        ensureInitialized(activityContext)
        currentActivityContext = activityContext
        val active = getActiveTab(activityContext)

        activityContainer.removeAllViews()

        tabs.forEach { tab ->
            if (tab.id == active.id) {
                tab.contextWrapper.baseContext = activityContext
                (tab.webView.parent as? ViewGroup)?.removeView(tab.webView)
                tab.webView.layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                activityContainer.addView(tab.webView)
                tab.webView.onResume()
                tab.webView.resumeTimers()
            } else {
                tab.contextWrapper.baseContext = activityContext.applicationContext
                (tab.webView.parent as? ViewGroup)?.removeView(tab.webView)
                if (tab.keepAliveInBackground && serviceOverlayContainer != null) {
                    tab.webView.layoutParams = FrameLayout.LayoutParams(1, 1)
                    serviceOverlayContainer.addView(tab.webView)
                    tab.webView.onResume()
                } else if (!tab.keepAliveInBackground) {
                    tab.webView.forcePause()
                }
            }
        }

        onActiveTabStateUpdated?.invoke(active)
    }

    fun moveKeepAliveTabsToServiceOverlay(
        serviceContext: Context,
        serviceOverlayContainer: FrameLayout?
    ) {
        ensureInitialized(serviceContext)
        currentActivityContext = null
        val appCtx = serviceContext.applicationContext

        serviceOverlayContainer?.removeAllViews()

        tabs.forEach { tab ->
            tab.contextWrapper.baseContext = appCtx
            (tab.webView.parent as? ViewGroup)?.removeView(tab.webView)

            if (tab.keepAliveInBackground) {
                if (serviceOverlayContainer != null) {
                    tab.webView.layoutParams = FrameLayout.LayoutParams(1, 1)
                    serviceOverlayContainer.addView(tab.webView)
                }
                tab.webView.onResume()
                tab.webView.resumeTimers()
            } else {
                tab.webView.forcePause()
            }
        }
    }

    fun pulseKeepAliveTabs(context: Context) {
        if (!isInitialized) return
        var anyKeepAlive = false
        tabs.forEach { tab ->
            if (tab.keepAliveInBackground) {
                anyKeepAlive = true
                tab.webView.onResume()
            }
        }
        if (anyKeepAlive) {
            tabs.firstOrNull()?.webView?.resumeTimers()
        }
    }

    fun countKeepAliveTabs(): Int = tabs.count { it.keepAliveInBackground }

    fun loadBuiltInTimerPageIntoActiveTab(context: Context) {
        val tab = getActiveTab(context)
        loadBuiltInTimerPageIntoTab(tab)
        saveTabsState(context)
    }

    private fun loadBuiltInTimerPageIntoTab(tab: BrowserTab) {
        tab.url = "bg://test-timer"
        tab.title = "⏱ Test Chạy Nền"
        val html = """
            <!DOCTYPE html>
            <html>
            <head>
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <title>⏱ Test Chạy Nền (${tab.id.take(4)})</title>
                <style>
                    body { font-family: sans-serif; background: #101418; color: #00e676; padding: 18px; text-align: center; }
                    .counter { font-size: 46px; font-weight: bold; margin: 16px 0; }
                    .box { background: #1c2228; padding: 14px; border-radius: 8px; margin-bottom: 12px; color: #fff; text-align: left; font-size: 14px; line-height: 1.6; }
                </style>
            </head>
            <body>
                <h3>Kiểm thử Đa Tab Chạy Nền</h3>
                <div class="counter" id="sec">0s</div>
                <div class="box">
                    <div><b>Mã Tab:</b> #${tab.id.take(6)}</div>
                    <div><b>Thời gian thực (Wall Clock):</b> <span id="wall">0</span>s</div>
                    <div><b>Số nhịp setInterval (1s):</b> <span id="ticks">0</span></div>
                    <div><b>Độ lệch (Drift):</b> <span id="drift">0</span>s</div>
                    <div><b>document.visibilityState:</b> <span id="vis">unknown</span></div>
                    <div><b>Nhịp dựng hình rAF:</b> <span id="raf">0</span></div>
                </div>
                <script>
                    const startWall = Date.now();
                    let ticks = 0;
                    let rafCount = 0;
                    setInterval(() => {
                        ticks++;
                        const wallSec = Math.floor((Date.now() - startWall) / 1000);
                        document.getElementById('sec').innerText = ticks + 's';
                        document.getElementById('wall').innerText = wallSec;
                        document.getElementById('ticks').innerText = ticks;
                        document.getElementById('drift').innerText = (wallSec - ticks);
                        document.getElementById('vis').innerText = document.visibilityState;
                        document.title = '⏱ ' + ticks + 's (#' + '${tab.id.take(4)}' + ')';
                    }, 1000);
                    function loop() {
                        rafCount++;
                        if (rafCount % 60 === 0) {
                            document.getElementById('raf').innerText = rafCount;
                        }
                        requestAnimationFrame(loop);
                    }
                    requestAnimationFrame(loop);
                </script>
            </body>
            </html>
        """.trimIndent()

        tab.webView.loadDataWithBaseURL(
            "https://bgtest.local/${tab.id}",
            html,
            "text/html",
            "UTF-8",
            null
        )
        onActiveTabStateUpdated?.invoke(tab)
        onTabsChanged?.invoke()
    }

    fun saveTabsState(context: Context) {
        if (!isInitialized) return
        val arr = JSONArray()
        for (t in tabs) {
            val obj = JSONObject().apply {
                put("id", t.id)
                put("url", t.url)
                put("title", t.title)
                put("keepAlive", t.keepAliveInBackground)
                put("desktopMode", t.desktopMode)
            }
            arr.put(obj)
        }
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_TABS_JSON, arr.toString())
            .putString(KEY_ACTIVE_TAB_ID, activeTabId)
            .apply()
    }
}
