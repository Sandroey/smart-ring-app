package com.rea.ring

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class Stats(val count: Int, val min: Int, val max: Int, val last: Int, val lastTs: Long)

class Store private constructor(ctx: Context) : SQLiteOpenHelper(ctx, "ring.db", null, 3) {

    companion object {
        @Volatile private var inst: Store? = null
        fun get(ctx: Context): Store = inst ?: synchronized(this) {
            inst ?: Store(ctx.applicationContext).also { inst = it }
        }
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE samples (id INTEGER PRIMARY KEY AUTOINCREMENT, metric TEXT NOT NULL, value INTEGER NOT NULL, ts INTEGER NOT NULL)")
        db.execSQL("CREATE UNIQUE INDEX idx_unique_metric_ts ON samples(metric, ts)")
        db.execSQL("CREATE INDEX idx_metric_ts ON samples(metric, ts)")
        db.execSQL("CREATE TABLE sleep_seg (ts INTEGER PRIMARY KEY, seconds INTEGER NOT NULL, stage INTEGER NOT NULL)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS samples")
        db.execSQL("DROP TABLE IF EXISTS sleep_seg")
        onCreate(db)
    }

    fun insertSleepSegs(segs: List<Triple<Long, Int, Int>>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            for ((ts, sec, stage) in segs) {
                val cv = android.content.ContentValues()
                cv.put("ts", ts)
                cv.put("seconds", sec)
                cv.put("stage", stage)
                db.insertWithOnConflict("sleep_seg", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun cleanupBadTimestamps() {
        try {
            val now = System.currentTimeMillis()
            val hi = (now + 24L * 3600 * 1000).toString()
            val lo = (now - 60L * 24 * 3600 * 1000).toString()
            writableDatabase.delete("samples", "ts > ? OR ts < ?", arrayOf(hi, lo))
            writableDatabase.delete("sleep_seg", "ts > ? OR ts < ?", arrayOf(hi, lo))
        } catch (_: Exception) {}
    }

    fun sleepSessions(gapMs: Long = 3L * 3600 * 1000): List<List<Triple<Long, Int, Int>>> {
        val all = sleepSegs(0)
        val out = ArrayList<List<Triple<Long, Int, Int>>>()
        var cur = ArrayList<Triple<Long, Int, Int>>()
        var prevEnd = 0L
        for (s in all) {
            if (cur.isNotEmpty() && s.first - prevEnd > gapMs) {
                out.add(cur)
                cur = ArrayList()
            }
            cur.add(s)
            prevEnd = s.first + s.second * 1000L
        }
        if (cur.isNotEmpty()) out.add(cur)
        return out
    }

    fun lastSleepSession(): List<Triple<Long, Int, Int>> {
        val sessions = sleepSessions()
        return sessions.lastOrNull() ?: emptyList()
    }

    fun sleepSegs(sinceTs: Long): List<Triple<Long, Int, Int>> {
        val out = ArrayList<Triple<Long, Int, Int>>()
        readableDatabase.rawQuery(
            "SELECT ts, seconds, stage FROM sleep_seg WHERE ts>=? ORDER BY ts ASC",
            arrayOf(sinceTs.toString())
        ).use { c ->
            while (c.moveToNext()) out.add(Triple(c.getLong(0), c.getInt(1), c.getInt(2)))
        }
        return out
    }

    fun insert(metric: String, value: Int, ts: Long = System.currentTimeMillis()) {
        val cv = ContentValues().apply {
            put("metric", metric)
            put("value", value)
            put("ts", ts)
        }
        writableDatabase.insertWithOnConflict("samples", null, cv, SQLiteDatabase.CONFLICT_IGNORE)
        if (metric == "hrv") {
            val cv2 = android.content.ContentValues()
            cv2.put("metric", "stress")
            cv2.put("value", (90.0 - value * 0.75).toInt().coerceIn(5, 95))
            cv2.put("ts", ts)
            writableDatabase.insertWithOnConflict("samples", null, cv2, SQLiteDatabase.CONFLICT_IGNORE)
        }
    }

    fun insertBp(sys: Int, dia: Int, ts: Long) {
        if (sys !in 60..200 || dia !in 30..140) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            insert("bp_sys", sys, ts)
            insert("bp_dia", dia, ts)
            db.setTransactionSuccessful()
        } catch (_: Exception) {
        } finally {
            db.endTransaction()
        }
    }

    fun diaNear(ts: Long, windowMs: Long = 10 * 60 * 1000L): Int? {
        readableDatabase.rawQuery(
            "SELECT value FROM samples WHERE metric='bp_dia' AND ts BETWEEN ? AND ? ORDER BY ABS(ts-?) LIMIT 1",
            arrayOf((ts - windowMs).toString(), (ts + windowMs).toString(), ts.toString())
        ).use { c -> return if (c.moveToFirst()) c.getInt(0) else null }
    }

    fun sleepNight(anchor: Long): List<Triple<Long, Int, Int>> {
        val hi = anchor
        val lo = anchor - 18L * 3600 * 1000
        val out = ArrayList<Triple<Long, Int, Int>>()
        readableDatabase.rawQuery(
            "SELECT ts, seconds, stage FROM sleep_seg WHERE ts>=? AND ts<=? ORDER BY ts ASC",
            arrayOf(lo.toString(), hi.toString())
        ).use { c ->
            while (c.moveToNext()) out.add(Triple(c.getLong(0), c.getInt(1), c.getInt(2)))
        }
        return out
    }

    fun latest(metric: String): Pair<Int, Long>? {
        readableDatabase.query("samples", arrayOf("value", "ts"), "metric=?", arrayOf(metric), null, null, "ts DESC", "1").use { c ->
            return if (c.moveToFirst()) Pair(c.getInt(0), c.getLong(1)) else null
        }
    }

    fun series(metric: String, limit: Int = 200): List<Pair<Long, Int>> {
        val out = ArrayList<Pair<Long, Int>>()
        readableDatabase.query("samples", arrayOf("ts", "value"), "metric=?", arrayOf(metric), null, null, "ts DESC", limit.toString()).use { c ->
            while (c.moveToNext()) out.add(Pair(c.getLong(0), c.getInt(1)))
        }
        out.reverse()
        return out
    }

    fun statsSince(metric: String, sinceTs: Long): Stats? {
        var count = 0
        var mn = 0
        var mx = 0
        readableDatabase.rawQuery(
            "SELECT COUNT(*), MIN(value), MAX(value) FROM samples WHERE metric=? AND ts>=?",
            arrayOf(metric, sinceTs.toString())
        ).use { c ->
            if (c.moveToFirst() && !c.isNull(0) && c.getInt(0) > 0) {
                count = c.getInt(0)
                mn = c.getInt(1)
                mx = c.getInt(2)
            } else return null
        }
        val last = latest(metric) ?: return null
        return Stats(count, mn, mx, last.first, last.second)
    }

    fun seriesSince(metric: String, sinceTs: Long, limit: Int = 300): List<Pair<Long, Int>> {
        val out = ArrayList<Pair<Long, Int>>()
        readableDatabase.rawQuery(
            "SELECT ts, value FROM samples WHERE metric=? AND ts>=? ORDER BY ts ASC LIMIT ?",
            arrayOf(metric, sinceTs.toString(), limit.toString())
        ).use { c ->
            while (c.moveToNext()) out.add(Pair(c.getLong(0), c.getInt(1)))
        }
        return out
    }

    fun recomputeStress() {
        try {
            writableDatabase.execSQL("DELETE FROM samples WHERE metric='stress'")
            writableDatabase.execSQL(
                "INSERT INTO samples(metric, value, ts) SELECT 'stress', MIN(95, MAX(5, CAST(90 - value * 0.75 AS INT))), ts FROM samples WHERE metric='hrv'"
            )
        } catch (_: Exception) {}
    }

    fun maxSince(metric: String, sinceTs: Long): Int {
        readableDatabase.rawQuery("SELECT MAX(value) FROM samples WHERE metric=? AND ts>=?", arrayOf(metric, sinceTs.toString())).use { c ->
            return if (c.moveToFirst()) c.getInt(0) else 0
        }
    }

    fun sumSince(metric: String, sinceTs: Long): Int {
        readableDatabase.rawQuery("SELECT SUM(value) FROM samples WHERE metric=? AND ts>=?", arrayOf(metric, sinceTs.toString())).use { c ->
            return if (c.moveToFirst()) c.getInt(0) else 0
        }
    }

    fun count(metric: String): Int {
        readableDatabase.rawQuery("SELECT COUNT(*) FROM samples WHERE metric=?", arrayOf(metric)).use { c ->
            return if (c.moveToFirst()) c.getInt(0) else 0
        }
    }
}