package com.senior.bgbrowser

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.WebView
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class UserScript(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val matches: List<String>,
    val runAt: String,
    val code: String,
    var enabled: Boolean = true
)

object UserScriptManager {
    private const val PREFS_NAME = "bg_browser_userscripts"
    private const val KEY_SCRIPTS_JSON = "scripts_json"

    private val scripts = mutableListOf<UserScript>()
    private var isLoaded = false

    fun ensureLoaded(context: Context) {
        if (isLoaded) return
        isLoaded = true
        val raw = context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_SCRIPTS_JSON, null)

        scripts.clear()
        if (raw.isNullOrBlank()) {
            val defaultSpoofScript = """
                // ==UserScript==
                // @name         Always Visible Spoofer (Chống Web phát hiện ẩn tab)
                // @match        *://*/*
                // @run-at       document-start
                // @grant        none
                // ==/UserScript==
                (function() {
                    try {
                        Object.defineProperty(document, 'hidden', { get: () => false, configurable: true });
                        Object.defineProperty(document, 'visibilityState', { get: () => 'visible', configurable: true });
                        window.addEventListener('visibilitychange', function(e) {
                            e.stopImmediatePropagation();
                        }, true);
                    } catch (err) {}
                })();
            """.trimIndent()
            addOrUpdateFromRawCode(context, null, defaultSpoofScript, true)
        } else {
            try {
                val arr = JSONArray(raw)
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    val matchArr = obj.optJSONArray("matches") ?: JSONArray()
                    val matchList = mutableListOf<String>()
                    for (j in 0 until matchArr.length()) {
                        matchList.add(matchArr.getString(j))
                    }
                    scripts.add(
                        UserScript(
                            id = obj.getString("id"),
                            name = obj.getString("name"),
                            matches = matchList,
                            runAt = obj.optString("runAt", "document-end"),
                            code = obj.getString("code"),
                            enabled = obj.optBoolean("enabled", true)
                        )
                    )
                }
            } catch (_: Exception) {}
        }
    }

    private fun save(context: Context) {
        val arr = JSONArray()
        for (s in scripts) {
            val obj = JSONObject().apply {
                put("id", s.id)
                put("name", s.name)
                put("runAt", s.runAt)
                put("code", s.code)
                put("enabled", s.enabled)
                val mArr = JSONArray()
                s.matches.forEach { mArr.put(it) }
                put("matches", mArr)
            }
            arr.put(obj)
        }
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_SCRIPTS_JSON, arr.toString())
            .apply()
    }

    fun getAllScripts(context: Context): List<UserScript> {
        ensureLoaded(context)
        return scripts.toList()
    }

    fun addOrUpdateFromRawCode(
        context: Context,
        existingId: String?,
        rawCode: String,
        enabled: Boolean = true
    ): UserScript {
        ensureLoaded(context)
        val parsed = parseHeader(existingId ?: UUID.randomUUID().toString(), rawCode, enabled)
        val idx = scripts.indexOfFirst { it.id == parsed.id }
        if (idx >= 0) {
            scripts[idx] = parsed
        } else {
            scripts.add(parsed)
        }
        save(context)
        return parsed
    }

    fun toggleScript(context: Context, id: String, enabled: Boolean) {
        ensureLoaded(context)
        scripts.find { it.id == id }?.let {
            it.enabled = enabled
            save(context)
        }
    }

    fun deleteScript(context: Context, id: String) {
        ensureLoaded(context)
        scripts.removeAll { it.id == id }
        save(context)
    }

    private fun parseHeader(id: String, rawCode: String, enabled: Boolean): UserScript {
        var name = "Userscript không tên"
        val matches = mutableListOf<String>()
        var runAt = "document-end"

        val lines = rawCode.lines()
        var inHeader = false
        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.contains("==UserScript==")) {
                inHeader = true
                continue
            }
            if (trimmed.contains("==/UserScript==")) {
                break
            }
            if (inHeader && trimmed.startsWith("//")) {
                val content = trimmed.removePrefix("//").trim()
                when {
                    content.startsWith("@name") -> {
                        name = content.removePrefix("@name").trim().ifEmpty { name }
                    }
                    content.startsWith("@match") -> {
                        val m = content.removePrefix("@match").trim()
                        if (m.isNotEmpty()) matches.add(m)
                    }
                    content.startsWith("@include") -> {
                        val m = content.removePrefix("@include").trim()
                        if (m.isNotEmpty()) matches.add(m)
                    }
                    content.startsWith("@run-at") -> {
                        val r = content.removePrefix("@run-at").trim()
                        if (r in listOf("document-start", "document-end", "document-idle")) {
                            runAt = r
                        }
                    }
                }
            }
        }
        if (matches.isEmpty()) {
            matches.add("*://*/*")
        }
        return UserScript(
            id = id,
            name = name,
            matches = matches,
            runAt = runAt,
            code = rawCode,
            enabled = enabled
        )
    }

    @SuppressLint("RequiresFeature")
    fun installDocumentStartScripts(
        context: Context,
        webView: WebView,
        oldHandlers: MutableList<ScriptHandler>
    ) {
        ensureLoaded(context)
        oldHandlers.forEach {
            try {
                it.remove()
            } catch (_: Exception) {}
        }
        oldHandlers.clear()

        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            return
        }

        val startScripts = scripts.filter { it.enabled && it.runAt == "document-start" }
        for (script in startScripts) {
            val wrappedJs = wrapScriptWithUrlMatcher(script)
            try {
                val handler = WebViewCompat.addDocumentStartJavaScript(
                    webView,
                    wrappedJs,
                    setOf("*")
                )
                oldHandlers.add(handler)
            } catch (_: Exception) {}
        }
    }

    fun injectScriptsForLifecycle(
        context: Context,
        webView: WebView,
        url: String,
        isPageStarted: Boolean
    ) {
        ensureLoaded(context)
        val hasNativeDocStart = WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)

        for (script in scripts) {
            if (!script.enabled) continue
            if (!matchesUrl(url, script.matches)) continue

            val shouldInjectNow = if (isPageStarted) {
                !hasNativeDocStart && script.runAt == "document-start"
            } else {
                script.runAt == "document-end" || script.runAt == "document-idle"
            }

            if (shouldInjectNow) {
                val js = """
                    (function() {
                        try {
                            ${script.code}
                        } catch (e) {
                            console.error('[Userscript Error]', e);
                        }
                    })();
                """.trimIndent()
                webView.evaluateJavascript(js, null)
            }
        }
    }

    private fun wrapScriptWithUrlMatcher(script: UserScript): String {
        val regexArrayJs = script.matches.joinToString(",") { pattern ->
            val regexStr = patternToRegexString(pattern)
                .replace("\\", "\\\\")
                .replace("'", "\\'")
            "new RegExp('$regexStr')"
        }
        return """
            (function() {
                try {
                    var currentUrl = window.location.href;
                    var patterns = [$regexArrayJs];
                    var matched = false;
                    for (var i = 0; i < patterns.length; i++) {
                        if (patterns[i].test(currentUrl)) {
                            matched = true;
                            break;
                        }
                    }
                    if (!matched) return;
                    ${script.code}
                } catch (e) {
                    console.error('[Userscript DocStart Error]', e);
                }
            })();
        """.trimIndent()
    }

    fun matchesUrl(url: String, patterns: List<String>): Boolean {
        for (p in patterns) {
            if (p == "<all_urls>" || p == "*://*/*" || p == "*") return true
            try {
                val regex = Regex(patternToRegexString(p), RegexOption.IGNORE_CASE)
                if (regex.matches(url)) return true
            } catch (_: Exception) {}
        }
        return false
    }

    private fun patternToRegexString(pattern: String): String {
        val sb = StringBuilder("^")
        for (ch in pattern) {
            when (ch) {
                '*' -> sb.append(".*")
                '?' -> sb.append(".")
                '.', '+', '^', '$', '(', ')', '[', ']', '{', '}', '|', '\\' -> {
                    sb.append("\\").append(ch)
                }
                else -> sb.append(ch)
            }
        }
        sb.append("$")
        return sb.toString()
    }
}
