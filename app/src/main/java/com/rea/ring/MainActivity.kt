package com.rea.ring

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

val Bg = Color(0xFF0B0E14)
val CardBg = Color(0xFF151A23)
val Accent = Color(0xFF4ADE80)
val Muted = Color(0xFF8A93A6)

data class MetricInfo(val key: String, val label: String, val unit: String, val color: Color)

val METRICS = listOf(
    MetricInfo("sleep", "睡眠", "h", Color(0xFF8B5CF6)),
    MetricInfo("heart", "心率", "bpm", Accent),
    MetricInfo("bp", "血压", "mmHg", Color(0xFFF87171)),
    MetricInfo("spo2", "血氧", "%", Color(0xFF38BDF8)),
    MetricInfo("temp", "体温", "°C", Color(0xFFFF9F7A)),
    MetricInfo("hrv", "HRV", "ms", Color(0xFFFBBF24)),
    MetricInfo("stress", "个人压力", "", Color(0xFFF472B6)),
    MetricInfo("steps", "运动步数", "步", Color(0xFF34D399)),
    MetricInfo("battery", "电量", "%", Color(0xFFA3E635))
)

class MainActivity : ComponentActivity() {

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(background = Bg, surface = CardBg)) {
                AppRoot(onAskPermissions = { permLauncher.launch(requiredPermissions()) })
            }
        }
    }
}

fun requiredPermissions(): Array<String> {
    val list = mutableListOf<String>()
    if (Build.VERSION.SDK_INT >= 31) {
        list += Manifest.permission.BLUETOOTH_SCAN
        list += Manifest.permission.BLUETOOTH_CONNECT
    } else {
        list += Manifest.permission.ACCESS_FINE_LOCATION
    }
    if (Build.VERSION.SDK_INT >= 33) list += Manifest.permission.POST_NOTIFICATIONS
    return list.toTypedArray()
}

fun hasPermissions(ctx: Context): Boolean {
    val scan = if (Build.VERSION.SDK_INT >= 31) Manifest.permission.BLUETOOTH_SCAN
    else Manifest.permission.ACCESS_FINE_LOCATION
    val connect = if (Build.VERSION.SDK_INT >= 31) Manifest.permission.BLUETOOTH_CONNECT
    else Manifest.permission.ACCESS_FINE_LOCATION
    fun ok(p: String) = ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED
    return ok(scan) && ok(connect)
}

@Composable
fun AppRoot(onAskPermissions: () -> Unit) {
    val ctx = LocalContext.current
    val state by Ring.state.collectAsStateWithLifecycle()
    var granted by remember { mutableStateOf(hasPermissions(ctx)) }
    var refreshTick by remember { mutableStateOf(0) }
    val owner = LocalLifecycleOwner.current

    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) {
                granted = hasPermissions(ctx)
                refreshTick++
            }
        }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }

    LaunchedEffect(granted) {
        Ring.updateGestureMode(ctx)
        if (granted && !Ring.state.value.connected && !Ring.state.value.scanning) Ring.startScan(ctx)
    }
    LaunchedEffect(state.connected) {
        if (state.connected) ContextCompat.startForegroundService(ctx, android.content.Intent(ctx, RingService::class.java))
        else ctx.stopService(android.content.Intent(ctx, RingService::class.java))
    }

    Surface(color = Bg, modifier = Modifier.fillMaxSize()) {
        if (!granted) {
            Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
                Text("需要蓝牙权限", color = Color.White)
                Spacer(Modifier.height(12.dp))
                Button(onClick = onAskPermissions, modifier = Modifier.fillMaxWidth()) { Text("授权") }
            }
        } else {
            HomeScreen(state, refreshTick) { mi ->
                ctx.startActivity(
                    android.content.Intent(ctx, DetailActivity::class.java).putExtra("key", mi.key)
                )
            }
        }
    }
}

@Composable
fun HomeScreen(state: RingState, refreshTick: Int = 0, onOpen: (MetricInfo) -> Unit) {
    val ctx = LocalContext.current
    var stats by remember { mutableStateOf<Map<String, Stats>>(emptyMap()) }
    var dia by remember { mutableStateOf<Int?>(null) }
    var todaySteps by remember { mutableStateOf(0) }
    var sleepTotal by remember { mutableStateOf(0) }
    var sleepDeep by remember { mutableStateOf(0) }
    var sleepRem by remember { mutableStateOf(0) }
    var didRecompute by remember { mutableStateOf(false) }

    LaunchedEffect(state.connected, state.heart, state.steps, state.battery, state.bpSys, state.hrv, refreshTick, state.lastRx) {
        if (!didRecompute) {
            Store.get(ctx).cleanupBadTimestamps()
            Store.get(ctx).recomputeStress()
            didRecompute = true
        }
        val since = startOfToday()
        val m = HashMap<String, Stats>()
        for (mi in METRICS) {
            val key = if (mi.key == "bp") "bp_sys" else mi.key
            Store.get(ctx).statsSince(key, since)?.let { m[mi.key] = it }
        }
        stats = m
        dia = Store.get(ctx).latest("bp_dia")?.first
        todaySteps = Store.get(ctx).maxSince("steps", since)
        val segs = Store.get(ctx).lastSleepSession()
        sleepTotal = segs.sumOf { it.second }
        sleepDeep = segs.filter { it.third == 3 }.sumOf { it.second }
        sleepRem = segs.filter { it.third == 2 }.sumOf { it.second }
    }

    Column(
        modifier = Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 16.dp),
    ) {
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                Text("Ring", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Color.White)
                Text(
                    when {
                        state.status.isNotEmpty() -> state.status
                        state.connected -> "已连接"
                        state.scanning -> "搜索中…"
                        else -> "未连接"
                    },
                    color = Muted, fontSize = 12.sp, maxLines = 1
                )
            }
            if (state.connected) {
                TextButton(onClick = { Ring.syncLatest() }) { Text("同步", color = Accent) }
                TextButton(onClick = { Ring.measureAll() }) { Text("立刻测量", color = Accent) }
            }
        }
        Spacer(Modifier.height(8.dp))

        LazyVerticalStaggeredGrid(
            columns = StaggeredGridCells.Fixed(2),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalItemSpacing = 10.dp,
            modifier = Modifier.weight(1f)
        ) {
            items(METRICS) { mi ->
                MetricCard(mi, state, stats[mi.key], dia, todaySteps, sleepTotal, sleepDeep, sleepRem) {
                    onOpen(mi)
                }
            }
            item {
                Column(
                    Modifier
                        .background(CardBg, RoundedCornerShape(16.dp))
                        .clickable {
                            val next = when (GesturePrefs.mode(ctx)) {
                                GesturePrefs.OFF -> GesturePrefs.SWIPE
                                GesturePrefs.SWIPE -> GesturePrefs.PHOTO
                                else -> GesturePrefs.OFF
                            }
                            GesturePrefs.setMode(ctx, next)
                            Ring.updateGestureMode(ctx)
                        }
                        .padding(14.dp)
                ) {
                    Text("体感控制", color = Color.White, fontSize = 14.sp)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        GesturePrefs.label(state.gestureMode) + "  ›",
                        color = if (state.gestureMode == GesturePrefs.OFF) Muted else Accent,
                        fontSize = 11.sp
                    )
                    if (state.gestureMode != GesturePrefs.OFF && !isAccessibilityOn(ctx)) {
                        Spacer(Modifier.height(3.dp))
                        Text("需开启无障碍服务", color = Color(0xFFF87171), fontSize = 10.sp)
                    }
                    if (state.tapCount > 0) {
                        Spacer(Modifier.height(3.dp))
                        Text("已触发 " + state.tapCount + " 次 · " + fmtTime(state.tapAt), color = Muted, fontSize = 10.sp)
                    }
                }
            }
            item {
                Column(
                    Modifier
                        .background(CardBg, RoundedCornerShape(16.dp))
                        .clickable {
                            when {
                                state.connected -> Ring.disconnect()
                                else -> Ring.startScan(ctx)
                            }
                        }
                        .padding(14.dp)
                ) {
                    Text("连接", color = Color.White, fontSize = 14.sp)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        when {
                            state.connected -> "已连接 · 点击断开"
                            state.scanning -> "搜索中…"
                            else -> "未连接 · 点击连接"
                        },
                        color = if (state.connected) Accent else Muted,
                        fontSize = 11.sp
                    )
                }
            }
        }
    }
}

@Composable
fun MetricCard(
    mi: MetricInfo,
    state: RingState,
    st: Stats?,
    dia: Int?,
    todaySteps: Int,
    sleepTotal: Int,
    sleepDeep: Int,
    sleepRem: Int,
    onClick: () -> Unit
) {
    val live = when (mi.key) {
        "heart" -> state.heart
        "spo2" -> state.spo2
        "battery" -> state.battery
        "hrv" -> state.hrv
        else -> null
    }
    val value = when (mi.key) {
        "bp" -> {
            val s = state.bpSys ?: st?.last
            val d = state.bpDia ?: dia
            if (s != null && d != null) s.toString() + "/" + d else "--"
        }
        "steps" -> if (todaySteps >= 0 && st != null) todaySteps.toString() else "--"
        "sleep" -> if (sleepTotal > 0) fmtDur(sleepTotal) else "--"
        "temp" -> {
            val t = state.temp ?: st?.last?.let { it / 100.0 }
            if (t != null) String.format("%.1f", t) else "--"
        }
        else -> st?.last?.toString() ?: live?.toString() ?: "--"
    }
    Column(
        modifier = Modifier
            .background(CardBg, RoundedCornerShape(16.dp))
            .clickable { onClick() }
            .padding(14.dp)
    ) {
        Text(mi.label, color = Muted, fontSize = 12.sp)
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, color = Color.White, fontSize = if (mi.key == "bp") 22.sp else 26.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(4.dp))
            if (mi.key != "sleep") Text(mi.unit, color = Muted, fontSize = 11.sp, modifier = Modifier.padding(bottom = 4.dp))
        }
        Spacer(Modifier.height(6.dp))
        val sub = when {
            mi.key == "sleep" && sleepTotal > 0 -> "深睡 " + fmtDur(sleepDeep) + " · REM " + fmtDur(sleepRem)
            mi.key == "sleep" -> "昨晚暂无"
            mi.key == "temp" -> "设备不支持体温传感器"
            st == null -> "今日暂无"
            mi.key == "steps" -> if ((st?.count ?: 0) > 0) "今日累计" else "今日暂无"
            mi.key == "battery" -> "更新 " + fmtTime(st.lastTs)
            mi.key == "temp" -> "今日 " + st.count + " 次 · " +
                String.format("%.1f", st.min / 100.0) + "~" + String.format("%.1f", st.max / 100.0)
            else -> "今日 " + st.count + " 次 · " + st.min + "~" + st.max
        }
        Text(sub, color = Muted, fontSize = 10.sp)
        if (st != null && (mi.key == "hrv" || mi.key == "stress") &&
            System.currentTimeMillis() - st.lastTs > 2L * 3600 * 1000) {
            Text("数据较旧：戒指未同步历史", color = Color(0xFFF87171), fontSize = 9.sp)
        }
        if (st != null && mi.key != "steps" && mi.key != "battery" && mi.key != "sleep") {
            Text("更新 " + fmtTime(st.lastTs), color = Muted, fontSize = 10.sp)
        }
    }
}

@Composable
fun ActionCard(title: String, sub: String, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .background(CardBg, RoundedCornerShape(16.dp))
            .clickable { onClick() }
            .padding(14.dp)
    ) {
        Text(title, color = Color.White, fontSize = 14.sp)
        Spacer(Modifier.height(8.dp))
        Text(sub, color = Muted, fontSize = 11.sp)
    }
}

@Composable
fun SleepScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val st by Ring.state.collectAsStateWithLifecycle()
    var segs by remember { mutableStateOf<List<Triple<Long, Int, Int>>>(emptyList()) }
    var tick by remember { mutableStateOf(0) }
    var sel by remember { mutableStateOf<Int?>(null) }
    var dayOffset by remember { mutableStateOf(0) }
    LaunchedEffect(st.lastRx, tick, dayOffset) {
        val anchor = startOfToday() - dayOffset * 86400000L + 12 * 3600 * 1000L
        segs = Store.get(ctx).sleepNight(anchor)
    }
    val deep = segs.filter { it.third == 3 }.sumOf { it.second }
    val light = segs.filter { it.third == 1 }.sumOf { it.second }
    val rem = segs.filter { it.third == 2 }.sumOf { it.second }
    val total = segs.sumOf { it.second }
    val deepColor = Color(0xFF4338CA)

    val lightColor = Color(0xFF818CF8)
    val remColor = Color(0xFF22D3EE)

    Column(Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 18.dp)) {
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("‹ 返回", color = Accent, fontSize = 14.sp, modifier = Modifier.clickable { onBack() })
            Spacer(Modifier.weight(1f))
            Text("立即同步", color = Accent, fontSize = 14.sp, modifier = Modifier.clickable {
                Ring.measure("sleep")
                tick++
            })
        }
        Spacer(Modifier.height(10.dp))
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            for (k in 0 until 14) {
                val dayTs = startOfToday() - k * 86400000L
                val label = when (k) {
                    0 -> "今天"
                    1 -> "昨天"
                    2 -> "前天"
                    else -> {
                        val c = java.util.Calendar.getInstance().apply { timeInMillis = dayTs }
                        String.format("%d/%d", c.get(java.util.Calendar.MONTH) + 1, c.get(java.util.Calendar.DAY_OF_MONTH))
                    }
                }
                val on = dayOffset == k
                Box(
                    Modifier
                        .background(if (on) Accent.copy(alpha = 0.18f) else CardBg, RoundedCornerShape(14.dp))
                        .clickable { dayOffset = k }
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text(label, color = if (on) Accent else Muted, fontSize = 12.sp)
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(if (total > 0) fmtDur(total) else "--", color = Color.White, fontSize = 40.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            if (segs.isNotEmpty()) {
                Column(horizontalAlignment = Alignment.End) {
                    Text(fmtTime(segs.first().first) + " 入睡", color = Muted, fontSize = 11.sp)
                    Text(fmtTime(segs.last().first + segs.last().second * 1000L) + " 起床", color = Muted, fontSize = 11.sp)
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SleepStat("深睡", fmtDur(deep), deepColor, Modifier.weight(1f))
            SleepStat("浅睡", fmtDur(light), lightColor, Modifier.weight(1f))
            SleepStat("快速眼动", fmtDur(rem), remColor, Modifier.weight(1f))
        }
        Spacer(Modifier.height(16.dp))
        Column(Modifier.fillMaxWidth().background(CardBg, RoundedCornerShape(20.dp)).padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("睡眠分期", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.weight(1f))
                val s = sel
                if (s != null && s in segs.indices) {
                    val name = when (segs[s].third) { 3 -> "深睡"; 2 -> "快速眼动"; else -> "浅睡" }
                    Text(fmtTime(segs[s].first) + "  " + name, color = Accent, fontSize = 11.sp)
                } else {
                    Text("拖动查看", color = Muted.copy(alpha = 0.6f), fontSize = 10.sp)
                }
            }
            Spacer(Modifier.height(12.dp))
            if (segs.isEmpty()) {
                Text("暂无睡眠数据，佩戴过夜后自动同步", color = Muted, fontSize = 12.sp)
            } else {
                Canvas(
                    modifier = Modifier.fillMaxWidth().height(96.dp).pointerInput(segs.size) {
                        detectDragGestures(
                            onDragStart = { off ->
                                val t0 = segs.first().first
                                val t1 = segs.last().first + segs.last().second * 1000L
                                val t = t0 + ((off.x / size.width) * (t1 - t0)).toLong()
                                sel = segs.indexOfLast { it.first <= t }.coerceAtLeast(0)
                            },
                            onDragEnd = { },
                            onDrag = { change, _ ->
                                val t0 = segs.first().first
                                val t1 = segs.last().first + segs.last().second * 1000L
                                val t = t0 + ((change.position.x / size.width) * (t1 - t0)).toLong()
                                sel = segs.indexOfLast { it.first <= t }.coerceAtLeast(0)
                            }
                        )
                    }
                ) {
                    val t0 = segs.first().first
                    val t1 = segs.last().first + segs.last().second * 1000L
                    val spanMs = (t1 - t0).coerceAtLeast(1)
                    val laneH = size.height / 3f
                    for (i in 0..3) {
                        val x = size.width * i / 3f
                        drawLine(Muted.copy(alpha = 0.07f), androidx.compose.ui.geometry.Offset(x, 0f), androidx.compose.ui.geometry.Offset(x, size.height), strokeWidth = 1f)
                    }
                    for (r in 0..2) {
                        val y = laneH * r
                        drawLine(Muted.copy(alpha = 0.06f), androidx.compose.ui.geometry.Offset(0f, y), androidx.compose.ui.geometry.Offset(size.width, y), strokeWidth = 1f)
                    }
                    for ((ts, sec, stage) in segs) {
                        val x0 = ((ts - t0).toFloat() / spanMs) * size.width
                        val x1 = (((ts + sec * 1000L) - t0).toFloat() / spanMs) * size.width
                        val row = when (stage) { 2 -> 0; 1 -> 1; else -> 2 }
                        val c = when (stage) {
                            3 -> deepColor
                            2 -> remColor
                            else -> lightColor
                        }
                        drawRoundRect(
                            color = c,
                            topLeft = androidx.compose.ui.geometry.Offset(x0, row * laneH + 6f),
                            size = androidx.compose.ui.geometry.Size((x1 - x0).coerceAtLeast(1.2f), laneH - 12f),
                            cornerRadius = androidx.compose.ui.geometry.CornerRadius(3f, 3f)
                        )
                    }
                    val s = sel
                    if (s != null && s in segs.indices) {
                        val ts = segs[s].first
                        val x = ((ts - t0).toFloat() / spanMs) * size.width
                        drawLine(Color.White.copy(alpha = 0.55f), androidx.compose.ui.geometry.Offset(x, 0f), androidx.compose.ui.geometry.Offset(x, size.height), strokeWidth = 1.5f)
                    }
                }
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    listOf(0f, 0.33f, 0.66f, 1f).forEach { f ->
                        val t = (segs.first().first + ((segs.last().first + segs.last().second * 1000L) - segs.first().first) * f).toLong()
                        Text(fmtTime(t), color = Muted, fontSize = 9.sp)
                    }
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    LegendDot("深睡", deepColor)
                    LegendDot("浅睡", lightColor)
                    LegendDot("快速眼动", remColor)
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        Text("明细", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(2.dp))
        Column(Modifier.verticalScroll(rememberScrollState())) {
            segs.reversed().take(60).forEach { (ts, sec, stage) ->
                val name = when (stage) { 3 -> "深睡"; 2 -> "快速眼动"; else -> "浅睡" }
                val c = when (stage) { 3 -> deepColor; 2 -> remColor; else -> lightColor }
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.width(4.dp).height(14.dp).background(c, RoundedCornerShape(2.dp)))
                        Spacer(Modifier.width(8.dp))
                        Text(fmtTime(ts), color = Muted, fontSize = 12.sp)
                    }
                    Text(name + "   " + fmtDur(sec), color = Color.White, fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
fun SleepStat(label: String, value: String, color: Color, modifier: Modifier = Modifier) {
    Column(modifier.background(CardBg, RoundedCornerShape(16.dp)).padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(6.dp).height(6.dp).background(color, RoundedCornerShape(3.dp)))
            Spacer(Modifier.width(5.dp))
            Text(label, color = Muted, fontSize = 11.sp)
        }
        Spacer(Modifier.height(6.dp))
        Text(value, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun LegendDot(label: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(8.dp).height(8.dp).background(color, RoundedCornerShape(2.dp)))
        Spacer(Modifier.width(4.dp))
        Text(label, color = Muted, fontSize = 10.sp)
    }
}

@Composable
fun DetailScreen(mi: MetricInfo, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val st by Ring.state.collectAsStateWithLifecycle()
    var range by remember { mutableStateOf(0) }
    var series by remember { mutableStateOf<List<Pair<Long, Int>>>(emptyList()) }
    var series2 by remember { mutableStateOf<List<Pair<Long, Int>>>(emptyList()) }
    var tick by remember { mutableStateOf(0) }

    LaunchedEffect(mi.key, range, st.lastRx, tick) {
        // temp 存的是百分之一度，展示时统一按 0.1 度处理
        val since = when (range) {
            0 -> startOfToday()
            1 -> System.currentTimeMillis() - 7L * 24 * 3600 * 1000
            else -> 0L
        }
        val key = if (mi.key == "bp") "bp_sys" else mi.key
        val raw = Store.get(ctx).seriesSince(key, since, 300)
        series = if (mi.key == "temp") raw.map { it.first to it.second / 10 } else raw
        if (mi.key == "bp") series2 = Store.get(ctx).seriesSince("bp_dia", since, 300)
    }

    Column(Modifier.fillMaxSize().statusBarsPadding().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("‹ 返回", color = Accent, fontSize = 14.sp, modifier = Modifier.clickable { onBack() })
            Spacer(Modifier.weight(1f))
            Text("立即测量", color = Accent, fontSize = 14.sp, modifier = Modifier.clickable {
                Ring.measure(mi.key)
                tick++
            })
        }
        Spacer(Modifier.height(14.dp))
        Text(mi.label, color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("今天", "7 天", "全部").forEachIndexed { i, label ->
                val selected = range == i
                Text(
                    label,
                    color = if (selected) Color.Black else Muted,
                    fontSize = 12.sp,
                    modifier = Modifier
                        .background(if (selected) Accent else CardBg, RoundedCornerShape(20.dp))
                        .clickable { range = i }
                        .padding(horizontal = 14.dp, vertical = 6.dp)
                )
            }
        }
        Spacer(Modifier.height(14.dp))
        Text(if (series.isEmpty()) "暂无数据" else series.size.toString() + " 个样本", color = Muted, fontSize = 12.sp)
        Spacer(Modifier.height(10.dp))
        Column(Modifier.fillMaxWidth().background(CardBg, RoundedCornerShape(20.dp)).padding(14.dp)) {
            if (series.size < 2) {
                Text("数据不足，等待采集", color = Muted, fontSize = 12.sp)
            } else {
                val mn = series.minOf { it.second }
                val mx = series.maxOf { it.second }
                val avg = series.map { it.second }.average()
                fun f(v: Number): String = if (mi.key == "temp") String.format("%.1f", v.toDouble() / 10.0) else v.toInt().toString()
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Stat("最低", f(mn), mi.unit)
                    Stat("平均", f(avg), mi.unit)
                    Stat("最高", f(mx), mi.unit)
                }
                Spacer(Modifier.height(10.dp))
                Chart(series, mi.color, series2, Color(0xFF60A5FA), mi.unit, mi.key == "bp")
                Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(fmtTime(series.first().first), color = Muted, fontSize = 10.sp)
                    Text(fmtTime(series.last().first), color = Muted, fontSize = 10.sp)
                }
                if (mi.key == "bp") {
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        LegendDot("收缩压", mi.color)
                        LegendDot("舒张压", Color(0xFF60A5FA))
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Column(Modifier.verticalScroll(rememberScrollState())) {
            val diaMap = series2.associate { it.first to it.second }
            series.reversed().take(40).forEach { (ts, v) ->
                Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(fmtTime(ts), color = Muted, fontSize = 12.sp)
                    val shown = if (mi.key == "bp") {
                        val d = diaMap[ts]
                        if (d != null) v.toString() + "/" + d + " " + mi.unit else v.toString() + " " + mi.unit
                    } else v.toString() + " " + mi.unit
                    Text(shown, color = Color.White, fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
fun Stat(label: String, value: String, unit: String) {
    Column {
        Text(label, color = Muted, fontSize = 10.sp)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.width(2.dp))
            Text(unit, color = Muted, fontSize = 9.sp, modifier = Modifier.padding(bottom = 2.dp))
        }
    }
}

@Composable
fun Chart(
    series: List<Pair<Long, Int>>,
    color: Color,
    series2: List<Pair<Long, Int>> = emptyList(),
    color2: Color = color,
    unit: String = "",
    dual: Boolean = false
) {
    val all = series + series2
    if (all.isEmpty()) return
    val rawLo = all.minOf { it.second }
    val rawHi = all.maxOf { it.second }
    val pad = ((rawHi - rawLo) * 0.15f).toInt().coerceAtLeast(1)
    val lo = rawLo - pad
    val hi = rawHi + pad
    val span = (hi - lo).coerceAtLeast(1)
    val labelStyle = androidx.compose.ui.text.TextStyle(color = Muted, fontSize = 9.sp)
    var sel by remember { mutableStateOf<Int?>(null) }

    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().height(28.dp), verticalAlignment = Alignment.CenterVertically) {
            val idx = sel
            if (idx != null && idx in series.indices) {
                Text(fmtTime(series[idx].first), color = Muted, fontSize = 10.sp)
                Spacer(Modifier.width(8.dp))
                Text(series[idx].second.toString() + " " + unit, color = color, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                if (dual && idx in series2.indices) {
                    Spacer(Modifier.width(8.dp))
                    Text(series2[idx].second.toString() + " " + unit, color = color2, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            } else {
                Text("长按或拖动查看数值", color = Muted.copy(alpha = 0.7f), fontSize = 10.sp)
            }
        }
        Row(Modifier.fillMaxWidth().height(180.dp)) {
            Column(
                Modifier.width(34.dp).fillMaxHeight(),
                verticalArrangement = Arrangement.SpaceBetween,
                horizontalAlignment = Alignment.End
            ) {
                Text(rawHi.toString(), style = labelStyle)
                Text(((rawHi + rawLo) / 2).toString(), style = labelStyle)
                Text(rawLo.toString(), style = labelStyle)
            }
            Spacer(Modifier.width(6.dp))
            Canvas(
                modifier = Modifier.weight(1f).fillMaxHeight().pointerInput(series.size) {
                    detectDragGestures(
                        onDragStart = { off ->
                            if (series.size >= 2) {
                                sel = ((off.x / size.width) * (series.size - 1)).toInt().coerceIn(0, series.size - 1)
                            }
                        },
                        onDragEnd = { },
                        onDrag = { change, _ ->
                            if (series.size >= 2) {
                                sel = ((change.position.x / size.width) * (series.size - 1)).toInt().coerceIn(0, series.size - 1)
                            }
                        }
                    )
                }
            ) {
                for (i in 1..3) {
                    val gy = size.height * i / 4f
                    drawLine(Muted.copy(alpha = 0.10f), androidx.compose.ui.geometry.Offset(0f, gy), androidx.compose.ui.geometry.Offset(size.width, gy), strokeWidth = 1f)
                }
                fun drawSeries(s: List<Pair<Long, Int>>, c: Color, fill: Boolean) {
                    if (s.size < 2) return
                    val dx = size.width / (s.size - 1)
                    val pts = s.mapIndexed { i, p ->
                        androidx.compose.ui.geometry.Offset(i * dx, size.height - ((p.second - lo).toFloat() / span) * size.height)
                    }
                    val path = Path()
                    path.moveTo(pts[0].x, pts[0].y)
                    for (i in 1 until pts.size) {
                        val prev = pts[i - 1]
                        val cur = pts[i]
                        val mx = (prev.x + cur.x) / 2f
                        path.cubicTo(mx, prev.y, mx, cur.y, cur.x, cur.y)
                    }
                    if (fill) {
                        val fillPath = Path()
                        fillPath.addPath(path)
                        fillPath.lineTo(size.width, size.height)
                        fillPath.lineTo(0f, size.height)
                        fillPath.close()
                        drawPath(fillPath, androidx.compose.ui.graphics.Brush.verticalGradient(
                            listOf(c.copy(alpha = 0.30f), c.copy(alpha = 0.02f))
                        ))
                    }
                    drawPath(path, c, style = Stroke(width = 2.5f))
                    if (s.size <= 40) pts.forEach { drawCircle(c, radius = 2f, center = it) }
                }
                drawSeries(series, color, series2.isEmpty())
                drawSeries(series2, color2, false)

                val idx = sel
                if (idx != null && idx < series.size) {
                    val dx = if (series.size > 1) size.width / (series.size - 1) else 0f
                    val x = idx * dx
                    drawLine(color.copy(alpha = 0.5f), androidx.compose.ui.geometry.Offset(x, 0f), androidx.compose.ui.geometry.Offset(x, size.height), strokeWidth = 1.5f)
                    val y = size.height - ((series[idx].second - lo).toFloat() / span) * size.height
                    drawCircle(color, radius = 5f, center = androidx.compose.ui.geometry.Offset(x, y))
                }
            }
        }
    }
}

fun isAccessibilityOn(ctx: Context): Boolean {
    val v = try {
        android.provider.Settings.Secure.getString(ctx.contentResolver, "enabled_accessibility_services")
    } catch (_: Exception) { null }
    return v != null && v.contains("com.rea.ring")
}

fun fmtDur(sec: Int): String {
    val h = sec / 3600
    val m = (sec % 3600) / 60
    return if (h > 0) h.toString() + "h " + m + "m" else m.toString() + "m"
}

fun startOfToday(): Long = java.util.Calendar.getInstance().apply {
    set(java.util.Calendar.HOUR_OF_DAY, 0)
    set(java.util.Calendar.MINUTE, 0)
    set(java.util.Calendar.SECOND, 0)
    set(java.util.Calendar.MILLISECOND, 0)
}.timeInMillis

fun fmtTime(ts: Long): String {
    val now = java.util.Calendar.getInstance()
    val c = java.util.Calendar.getInstance().apply { timeInMillis = ts }
    val sameDay = now.get(java.util.Calendar.YEAR) == c.get(java.util.Calendar.YEAR) &&
        now.get(java.util.Calendar.DAY_OF_YEAR) == c.get(java.util.Calendar.DAY_OF_YEAR)
    val fmt = if (sameDay) "HH:mm" else "MM-dd HH:mm"
    return SimpleDateFormat(fmt, Locale.getDefault()).format(Date(ts))
}