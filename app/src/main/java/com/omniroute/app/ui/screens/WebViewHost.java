package com.omniroute.app.ui.screens;

import android.content.Context;
import android.view.View;
import android.webkit.WebView;

/**
 * Java bridge to the framework {@link WebView}.
 *
 * <p>The Kotlin compiler in the pinned toolchain (2.0.21, AGP 8.7.3) cannot resolve
 * {@code android.webkit.WebView} — CI-verified: {@code android.webkit.WebSettings}
 * (same package, same jar) resolves while {@code WebView} does not, under both K1 and
 * K2 and across Kotlin 2.0.21/2.1.20. javac resolves it fine, so every WebView
 * touchpoint lives here and Kotlin only sees a plain {@link View}.
 */
public final class WebViewHost {
    private WebViewHost() {}

    /** Creates a WebView with JS + DOM storage enabled and the initial URL loaded. */
    public static View create(Context context, String url) {
        WebView webView = new WebView(context);
        webView.getSettings().setJavaScriptEnabled(true);
        webView.getSettings().setDomStorageEnabled(true);
        if (url != null && !url.isEmpty()) {
            webView.loadUrl(url);
        }
        return webView;
    }

    /** Loads [url] into [view] when it is a WebView; no-op otherwise. */
    public static void loadUrl(View view, String url) {
        if (view instanceof WebView) {
            ((WebView) view).loadUrl(url);
        }
    }

    /** Reloads [view] when it is a WebView; no-op otherwise. */
    public static void reload(View view) {
        if (view instanceof WebView) {
            ((WebView) view).reload();
        }
    }
}
