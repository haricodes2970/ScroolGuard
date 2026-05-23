package com.hari.scrollguard

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Path
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent

class ScrollGuardService : AccessibilityService() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var notificationHelper: ScrollGuardNotification

    private var windowManager: WindowManager? = null
    private var overlayView: OverlayView? = null

    private var foregroundPackage: String? = null
    private var inFeedSection = false
    private var gracePeriodEndMs = 0L
    private var graceTickRunnable: Runnable? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        notificationHelper = ScrollGuardNotification(this)
        isEnabled = isBlockingEnabledInPrefs(this)
        refreshFeedContext()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!isEnabled) {
            onLeaveFeedSection()
            removeOverlay()
            return
        }

        event?.packageName?.toString()?.let { foregroundPackage = it }

        when (event?.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> refreshFeedContext()
        }
    }

    private fun refreshFeedContext() {
        mainHandler.post {
            val pkg = foregroundPackage ?: rootInActiveWindow?.packageName?.toString()
            if (pkg == null) {
                removeOverlay()
                return@post
            }

            if (pkg !in feedMonitoredPackages) {
                if (inFeedSection) onLeaveFeedSection()
                removeOverlay()
                return@post
            }

            val root = rootInActiveWindow ?: return@post
            try {
                val inFeed = FeedDetector.isInScrollableFeed(pkg, root)
                when {
                    inFeed && !inFeedSection -> {
                        inFeedSection = true
                        onEnterFeedSection()
                    }
                    !inFeed && inFeedSection -> onLeaveFeedSection()
                }
                updateOverlay()
            } finally {
                root.recycle()
            }
        }
    }

    private fun updateOverlay() {
        if (!isEnabled || !inFeedSection || !shouldBlockScrolling()) {
            removeOverlay()
            return
        }
        addOverlay()
    }

    private fun onEnterFeedSection() {
        val graceMinutes = getGracePeriodMinutes(this)
        if (graceMinutes > 0) {
            gracePeriodEndMs = System.currentTimeMillis() + graceMinutes * 60_000L
            startGraceTicker()
        } else {
            gracePeriodEndMs = 0L
            notificationHelper.showBlockingActive()
        }
        updateOverlay()
    }

    private fun onLeaveFeedSection() {
        inFeedSection = false
        gracePeriodEndMs = 0L
        stopGraceTicker()
        notificationHelper.dismiss()
        removeOverlay()
    }

    private fun shouldBlockScrolling(): Boolean {
        if (!isEnabled) return false
        if (gracePeriodEndMs == 0L) return true
        return System.currentTimeMillis() >= gracePeriodEndMs
    }

    private fun startGraceTicker() {
        stopGraceTicker()
        val tick = object : Runnable {
            override fun run() {
                if (!inFeedSection || !isEnabled) {
                    stopGraceTicker()
                    return
                }
                val remaining = gracePeriodEndMs - System.currentTimeMillis()
                if (remaining > 0) {
                    notificationHelper.showGracePeriod(remaining)
                    removeOverlay()
                    mainHandler.postDelayed(this, 1000L)
                } else {
                    gracePeriodEndMs = 0L
                    notificationHelper.showBlockingActive()
                    updateOverlay()
                }
            }
        }
        graceTickRunnable = tick
        mainHandler.post(tick)
    }

    private fun stopGraceTicker() {
        graceTickRunnable?.let { mainHandler.removeCallbacks(it) }
        graceTickRunnable = null
    }

    fun addOverlay() {
        if (overlayView != null) return
        val wm = windowManager ?: return

        val view = OverlayView(this)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        wm.addView(view, params)
        overlayView = view
    }

    fun removeOverlay() {
        val view = overlayView ?: return
        try {
            windowManager?.removeView(view)
        } catch (_: IllegalArgumentException) {
            // Already removed
        }
        overlayView = null
    }

    fun injectTap(x: Float, y: Float) {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 50)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        dispatchGesture(gesture, null, null)
    }

    override fun onInterrupt() {
        mainHandler.post { removeOverlay() }
    }

    override fun onDestroy() {
        onLeaveFeedSection()
        if (instance === this) instance = null
        super.onDestroy()
    }

    companion object {
        @Volatile
        var instance: ScrollGuardService? = null

        @Volatile
        var isEnabled: Boolean = false
            set(value) {
                field = value
                if (!value) {
                    instance?.mainHandler?.post {
                        instance?.onLeaveFeedSection()
                    }
                } else {
                    instance?.refreshFeedContext()
                }
            }

        val feedMonitoredPackages: Set<String> = setOf(
            "com.instagram.android",
            "com.google.android.youtube"
        )

        private const val PREFS_NAME = "scrollguard_prefs"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_GRACE_MINUTES = "grace_period_minutes"

        fun persistEnabled(context: Context, enabled: Boolean) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_ENABLED, enabled)
                .apply()
        }

        fun isBlockingEnabledInPrefs(context: Context): Boolean {
            return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_ENABLED, false)
        }

        fun persistGracePeriodMinutes(context: Context, minutes: Int) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putInt(KEY_GRACE_MINUTES, minutes.coerceIn(0, 30))
                .apply()
        }

        fun getGracePeriodMinutes(context: Context): Int {
            return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getInt(KEY_GRACE_MINUTES, 0)
        }
    }
}
