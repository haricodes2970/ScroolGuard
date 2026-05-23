package com.hari.scrollguard

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Path
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent

class ScrollGuardService : AccessibilityService() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var notificationHelper: ScrollGuardNotification

    private var foregroundPackage: String? = null
    private var inFeedSection = false
    private var gracePeriodEndMs = 0L
    private var graceTickRunnable: Runnable? = null
    private var lastCounterGestureMs = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        notificationHelper = ScrollGuardNotification(this)
        isEnabled = isBlockingEnabledInPrefs(this)
        refreshFeedContext()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!isEnabled) {
            onLeaveFeedSection()
            return
        }

        event?.packageName?.toString()?.let { foregroundPackage = it }

        when (event?.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> refreshFeedContext()

            AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                val pkg = event.packageName?.toString() ?: foregroundPackage ?: return
                if (pkg !in feedMonitoredPackages) return
                handleViewScrolled(event, pkg)
            }
        }
    }

    private fun handleViewScrolled(event: AccessibilityEvent, packageName: String) {
        val root = rootInActiveWindow ?: return
        try {
            if (!FeedDetector.isInScrollableFeed(packageName, root)) return

            if (!inFeedSection) {
                inFeedSection = true
                onEnterFeedSection()
            }

            if (!shouldBlockScrolling()) return

            val deltaY = extractScrollDeltaY(event)
            if (deltaY == 0) return

            val now = System.currentTimeMillis()
            if (now - lastCounterGestureMs < COUNTER_GESTURE_COOLDOWN_MS) return
            lastCounterGestureMs = now
            injectCounterScroll(deltaY)
        } finally {
            root.recycle()
        }
    }

    private fun refreshFeedContext() {
        mainHandler.post {
            val pkg = foregroundPackage ?: rootInActiveWindow?.packageName?.toString()
            if (pkg == null) return@post

            if (pkg !in feedMonitoredPackages) {
                if (inFeedSection) onLeaveFeedSection()
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
            } finally {
                root.recycle()
            }
        }
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
    }

    private fun onLeaveFeedSection() {
        inFeedSection = false
        gracePeriodEndMs = 0L
        stopGraceTicker()
        notificationHelper.dismiss()
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
                    mainHandler.postDelayed(this, 1000L)
                } else {
                    gracePeriodEndMs = 0L
                    notificationHelper.showBlockingActive()
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

    private fun extractScrollDeltaY(event: AccessibilityEvent): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return event.scrollDeltaY
        }
        // API 24–27: infer direction from scroll position when delta unavailable.
        return when {
            event.scrollY > 0 -> 1
            event.scrollY < 0 -> -1
            else -> 0
        }
    }

    /**
     * Undo a vertical scroll by swiping the opposite direction across the feed center.
     */
    private fun injectCounterScroll(scrollDeltaY: Int) {
        val dm = resources.displayMetrics
        val centerX = dm.widthPixels / 2f
        val centerY = dm.heightPixels / 2f
        val distance = (dm.heightPixels * 0.38f).coerceIn(280f, 900f)

        val path = Path()
        if (scrollDeltaY > 0) {
            // Content moved down → user swiped up for next item → swipe down to undo.
            path.moveTo(centerX, centerY - distance / 2f)
            path.lineTo(centerX, centerY + distance / 2f)
        } else {
            path.moveTo(centerX, centerY + distance / 2f)
            path.lineTo(centerX, centerY - distance / 2f)
        }

        val stroke = GestureDescription.StrokeDescription(path, 0, 160)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        dispatchGesture(gesture, null, null)
    }

    override fun onInterrupt() {
        onLeaveFeedSection()
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
                    instance?.onLeaveFeedSection()
                }
            }

        /** Apps where we detect Reels/Shorts sub-sections (not whole-app blocking). */
        val feedMonitoredPackages: Set<String> = setOf(
            "com.instagram.android",
            "com.google.android.youtube"
        )

        private const val PREFS_NAME = "scrollguard_prefs"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_GRACE_MINUTES = "grace_period_minutes"
        private const val COUNTER_GESTURE_COOLDOWN_MS = 350L

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
