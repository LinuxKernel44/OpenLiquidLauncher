package com.linuxkernel44.openliquidlauncher.widget

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.view.MotionEvent
import android.view.ViewConfiguration
import kotlin.math.hypot

/** Creates [DragAwareHostView]s so widgets can be long-pressed and dragged like any other home item. */
class LauncherWidgetHost(context: Context, hostId: Int) : AppWidgetHost(context, hostId) {
    override fun onCreateView(context: Context, appWidgetId: Int, appWidget: AppWidgetProviderInfo?): AppWidgetHostView =
        DragAwareHostView(context)
}

class DragAwareHostView(context: Context) : AppWidgetHostView(context) {
    /** Callbacks receive screen coordinates. */
    var onLongPress: ((x: Float, y: Float) -> Unit)? = null
    var onDragMove: ((x: Float, y: Float) -> Unit)? = null
    var onDragEnd: (() -> Unit)? = null

    private var downX = 0f
    private var downY = 0f
    private var longPressed = false
    private val slop = ViewConfiguration.get(context).scaledTouchSlop

    private val longPressRunnable = Runnable {
        longPressed = true
        parent?.requestDisallowInterceptTouchEvent(true)
        onLongPress?.invoke(downX, downY)
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                longPressed = false
                downX = ev.rawX; downY = ev.rawY
                postDelayed(longPressRunnable, ViewConfiguration.getLongPressTimeout().toLong())
            }
            MotionEvent.ACTION_MOVE -> if (!longPressed && hypot(ev.rawX - downX, ev.rawY - downY) > slop) removeCallbacks(longPressRunnable)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (!longPressed) removeCallbacks(longPressRunnable)
        }
        if (longPressed) {
            handleDrag(ev)
            return true
        }
        return false
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (longPressed) {
            handleDrag(ev)
            return true
        }
        return super.onTouchEvent(ev)
    }

    private fun handleDrag(ev: MotionEvent) {
        when (ev.actionMasked) {
            MotionEvent.ACTION_MOVE -> onDragMove?.invoke(ev.rawX, ev.rawY)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                longPressed = false
                onDragEnd?.invoke()
            }
        }
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(longPressRunnable)
        super.onDetachedFromWindow()
    }
}
