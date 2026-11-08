package com.rea.ring

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.util.Log
import android.view.accessibility.AccessibilityEvent

class SwipeService : AccessibilityService() {

    companion object {
        private const val TAG = "ReaSwipe"
        @Volatile var instance: SwipeService? = null
        @Volatile var lastAction: String = ""
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d(TAG, "service connected")
    }

    override fun onDestroy() {
        Log.d(TAG, "service destroyed")
        instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    fun swipeUp() = swipe(0, 0.72f, 0.28f, 130)

    fun tapShutter(): Boolean {
        val root = try { rootInActiveWindow } catch (_: Exception) { null }
        if (root != null) {
            val found = findShutter(root, 0)
            if (found != null) {
                val ok = found.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
                Log.d(TAG, "shutter node click=" + ok)
                if (ok) return true
            }
        }
        val d = resources.displayMetrics
        val path = Path().apply {
            moveTo(d.widthPixels / 2f, d.heightPixels * 0.88f)
            lineTo(d.widthPixels / 2f + 1f, d.heightPixels * 0.88f)
        }
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 80))
            .build()
        val ok = try { dispatchGesture(g, null, null) } catch (_: Exception) { false }
        Log.d(TAG, "shutter tap fallback=" + ok)
        return ok
    }

    private fun findShutter(node: android.view.accessibility.AccessibilityNodeInfo?, depth: Int): android.view.accessibility.AccessibilityNodeInfo? {
        if (node == null || depth > 25) return null
        val desc = (node.contentDescription?.toString() ?: "") + " " + (node.text?.toString() ?: "")
        val keys = arrayOf("拍照", "快门", "shutter", "capture", "Take photo", "Photo")
        if (node.isClickable && keys.any { desc.contains(it, ignoreCase = true) }) return node
        for (i in 0 until node.childCount) {
            val r = findShutter(node.getChild(i), depth + 1)
            if (r != null) return r
        }
        return null
    }

    fun swipeDown() = swipe(0, 0.28f, 0.72f, 130)

    private fun swipe(dir: Int, from: Float, to: Float, ms: Long) {
        val d = resources.displayMetrics
        val x = d.widthPixels / 2f
        val path = Path().apply {
            moveTo(x, d.heightPixels * from)
            lineTo(x + 2f, d.heightPixels * to)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, ms))
            .build()
        val ok = try {
            dispatchGesture(gesture, null, null)
        } catch (e: Exception) {
            Log.d(TAG, "dispatch error: " + e.message)
            false
        }
        lastAction = if (ok) "swipe " + System.currentTimeMillis() else "fail " + System.currentTimeMillis()
        Log.d(TAG, "swipe dispatched=" + ok + " h=" + d.heightPixels)
    }
}