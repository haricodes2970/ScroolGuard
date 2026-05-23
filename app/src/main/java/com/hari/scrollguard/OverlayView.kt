package com.hari.scrollguard

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.hypot

class OverlayView(context: Context) : View(context) {

    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L
    private var scrollBlocked = false

    private val scrollThresholdPx: Float
        get() = 20f * resources.displayMetrics.density

    private val tapMovementThresholdPx: Float
        get() = 12f * resources.displayMetrics.density

    init {
        isClickable = true
        setBackgroundColor(Color.TRANSPARENT)
    }

    override fun onDraw(canvas: Canvas) {
        // Fully transparent overlay
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                downTime = event.eventTime
                scrollBlocked = false
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = abs(event.x - downX)
                val dy = abs(event.y - downY)
                if (dy > dx && dy > scrollThresholdPx) {
                    scrollBlocked = true
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                if (scrollBlocked) {
                    // Drop vertical swipe — do not pass through or re-inject.
                    return true
                }
                val dx = abs(event.x - downX)
                val dy = abs(event.y - downY)
                val distance = hypot(dx.toDouble(), dy.toDouble()).toFloat()
                val duration = event.eventTime - downTime
                if (distance < tapMovementThresholdPx && duration < 250) {
                    ScrollGuardService.instance?.injectTap(event.x, event.y)
                }
                return true
            }

            MotionEvent.ACTION_CANCEL -> return true
        }
        return true
    }
}
