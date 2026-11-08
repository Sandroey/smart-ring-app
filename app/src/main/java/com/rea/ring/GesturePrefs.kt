package com.rea.ring

import android.content.Context
import android.content.SharedPreferences

object GesturePrefs {
    const val OFF = 0
    const val SWIPE = 1
    const val PHOTO = 2

    private fun sp(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences("ring_gesture", Context.MODE_PRIVATE)

    fun mode(ctx: Context): Int = sp(ctx).getInt("mode", OFF)

    fun setMode(ctx: Context, mode: Int) {
        sp(ctx).edit().putInt("mode", mode).apply()
        if (mode != OFF) sp(ctx).edit().putInt("last", mode).apply()
    }

    fun last(ctx: Context): Int = sp(ctx).getInt("last", SWIPE)

    fun toggle(ctx: Context): Int {
        val cur = mode(ctx)
        val next = if (cur == OFF) last(ctx) else OFF
        setMode(ctx, next)
        Ring.updateGestureMode(ctx)
        return next
    }

    fun label(mode: Int): String = when (mode) {
        SWIPE -> "抖音翻页"
        PHOTO -> "遥控拍照"
        else -> "已关闭"
    }
}
