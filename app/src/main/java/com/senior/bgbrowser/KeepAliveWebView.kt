package com.senior.bgbrowser

import android.content.Context
import android.view.View
import android.webkit.WebView

class KeepAliveWebView(context: Context) : WebView(context) {

    var keepAliveInBackground: Boolean = true

    override fun onWindowVisibilityChanged(visibility: Int) {
        if (keepAliveInBackground && visibility != View.VISIBLE) {
            super.onWindowVisibilityChanged(View.VISIBLE)
        } else {
            super.onWindowVisibilityChanged(visibility)
        }
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        if (keepAliveInBackground && visibility != View.VISIBLE) {
            super.onVisibilityChanged(changedView, View.VISIBLE)
        } else {
            super.onVisibilityChanged(changedView, visibility)
        }
    }

    override fun onPause() {
        if (!keepAliveInBackground) {
            super.onPause()
        }
    }

    fun forcePause() {
        super.onPause()
    }
}
