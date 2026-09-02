package com.strong.clearlink.resolve

import android.content.Context

/** 从 assets 加载 AdbPlayer 同款 Hook JS */
object CaptureHooks {
    @Volatile
    private var captureHook: String? = null

    @Volatile
    private var kuaishouCaptureHook: String? = null

    @Volatile
    private var readCapture: String? = null

    @Volatile
    private var noteNudge: String? = null

    fun ensureLoaded(context: Context) {
        if (captureHook != null) return
        synchronized(this) {
            if (captureHook != null) return
            val assets = context.applicationContext.assets
            captureHook = assets.open("hooks/capture_hook.js").bufferedReader().use { it.readText() }
            kuaishouCaptureHook =
                assets.open("hooks/kuaishou_capture_hook.js").bufferedReader().use { it.readText() }
            readCapture = assets.open("hooks/read_capture.js").bufferedReader().use { it.readText() }
            noteNudge = assets.open("hooks/note_nudge.js").bufferedReader().use { it.readText() }
        }
    }

    fun captureHook(context: Context): String {
        ensureLoaded(context)
        return captureHook!!
    }

    fun kuaishouCaptureHook(context: Context): String {
        ensureLoaded(context)
        return kuaishouCaptureHook!!
    }

    fun readCapture(context: Context): String {
        ensureLoaded(context)
        return readCapture!!
    }

    fun noteNudge(context: Context): String {
        ensureLoaded(context)
        return noteNudge!!
    }
}
