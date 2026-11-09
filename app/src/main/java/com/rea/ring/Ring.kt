package com.rea.ring

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.util.UUID

data class ScanItem(val name: String, val address: String, val rssi: Int)

data class RingState(
    val scanning: Boolean = false,
    val devices: List<ScanItem> = emptyList(),
    val connected: Boolean = false,
    val status: String = "未连接",
    val heart: Int? = null,
    val spo2: Int? = null,
    val steps: Int? = null,
    val distance: Int? = null,
    val kcal: Int? = null,
    val battery: Int? = null,
    val bpSys: Int? = null,
    val bpDia: Int? = null,
    val hrv: Int? = null,
    val firmware: String? = null,
    val lastRx: Long = 0L,
    val gestureMode: Int = 0,
    val tapCount: Int = 0,
    val temp: Double? = null,
    val tapAt: Long = 0L,
    val waveOn: Boolean = false,
    val waveform: List<Int> = emptyList(),
    val hrSeries: List<Int> = emptyList(),
    val spo2Series: List<Int> = emptyList(),
    val log: List<String> = emptyList()
)

@SuppressLint("MissingPermission")
object Ring {

    private const val TAG = "ReaRing"
    private const val SVC = "be940000-7333-be46-b7ae-689e71722bd5"
    private const val C_WRITE = "be940001-7333-be46-b7ae-689e71722bd5"
    private const val C_NOTIFY = "be940003-7333-be46-b7ae-689e71722bd5"
    private val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    private val _state = MutableStateFlow(RingState())
    val state: StateFlow<RingState> = _state

    private var gatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null
    private val rxBuffer = ArrayList<Byte>()
    private val found = LinkedHashMap<String, ScanItem>()
    private val ui = Handler(Looper.getMainLooper())
    private var autoConnecting = false
    private var lastContext: Context? = null
    private var lastAddress: String? = null
    private var reconnectCount = 0
    private var cycleRunning = false
    private var appCtx: Context? = null
    private val lastSaved = HashMap<String, Long>()
    private var lastAdapter: BluetoothAdapter? = null

    private val ops = ArrayDeque<() -> Unit>()
    private var opBusy = false

    private fun push(line: String) {
        _state.update { it.copy(log = (listOf(line) + it.log).take(60)) }
    }

    private fun enqueue(op: () -> Unit) {
        ops.addLast(op)
        drain()
    }

    private fun drain() {
        if (opBusy) return
        val next = ops.removeFirstOrNull() ?: return
        opBusy = true
        ui.postDelayed({
            if (opBusy) {
                Log.w(TAG, "op timeout, force continue")
                opBusy = false
                drain()
            }
        }, 2500)
        try {
            next()
        } catch (e: Exception) {
            Log.w(TAG, "op failed " + e.message)
            opBusy = false
            drain()
        }
    }

    private fun opDone() {
        opBusy = false
        drain()
    }

    private fun adapter(ctx: Context): BluetoothAdapter? {
        val mgr = ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        return mgr?.adapter
    }

    private val publishRunnable = Runnable {
        val list = found.values.sortedByDescending { it.rssi }.take(15)
        _state.update { it.copy(devices = list) }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val name = result.scanRecord?.deviceName ?: result.device.name ?: return
            if (name.isBlank()) return
            found[result.device.address] = ScanItem(name, result.device.address, result.rssi)
            if (!autoConnecting && name.uppercase().contains("TK5")) {
                autoConnecting = true
                push("发现 " + name + "，自动连接…")
                lastAdapter?.bluetoothLeScanner?.stopScan(this)
                _state.update { it.copy(scanning = false) }
                lastContext?.let { connect(it, result.device.address) }
            }
        }
    }

    fun startScan(ctx: Context) {
        val ad = adapter(ctx) ?: run { push("蓝牙不可用"); return }
        if (!ad.isEnabled) { push("请先打开蓝牙"); return }
        lastContext = ctx
        appCtx = ctx.applicationContext
        lastAdapter = ad
        found.clear()
        autoConnecting = false
        _state.update { it.copy(scanning = true, devices = emptyList()) }
        val scanner = ad.bluetoothLeScanner ?: return
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        scanner.startScan(null, settings, scanCallback)
        ui.postDelayed(object : Runnable {
            override fun run() {
                if (_state.value.scanning) {
                    publishRunnable.run()
                    ui.postDelayed(this, 500)
                }
            }
        }, 500)
        ui.postDelayed({
            if (autoConnecting) return@postDelayed
            try { ad.bluetoothLeScanner?.stopScan(scanCallback) } catch (_: Exception) {}
            _state.update { it.copy(scanning = false) }
            publishRunnable.run()
        }, 15000)
        push("开始扫描…")
    }

    fun stopScan(ctx: Context) {
        val ad = adapter(ctx) ?: return
        try { ad.bluetoothLeScanner?.stopScan(scanCallback) } catch (_: Exception) {}
        _state.update { it.copy(scanning = false) }
    }

    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            Log.d(TAG, "onConnectionStateChange status=$status newState=$newState")
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                gatt = g
                reconnectCount = 0
                opBusy = false
                ops.clear()
                _state.update { it.copy(connected = true, status = "已连接，协商 MTU…") }
                push("已连接，协商 MTU…")
                g.requestMtu(185)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                push("连接断开")
                _state.update { it.copy(connected = false, status = "已断开") }
                try { g.close() } catch (_: Exception) {}
                gatt = null
                writeChar = null
                rxBuffer.clear()
                opBusy = false
                ops.clear()
                cycleRunning = false
                val addr = lastAddress
                val c = lastContext
                if (addr != null && c != null && reconnectCount < 5) {
                    reconnectCount++
                    Log.d(TAG, "reconnect in 6s, attempt=" + reconnectCount)
                    ui.postDelayed({ if (!_state.value.connected) connect(c, addr) }, 6000)
                }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            Log.d(TAG, "onMtuChanged mtu=$mtu")
            push("MTU = $mtu")
            g.discoverServices()
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            Log.d(TAG, "onServicesDiscovered status=$status")
            val svc = g.getService(UUID.fromString(SVC))
            if (svc == null) {
                push("未找到私有服务")
                return
            }
            val c1 = svc.getCharacteristic(UUID.fromString(C_WRITE))
            val c3 = svc.getCharacteristic(UUID.fromString(C_NOTIFY))
            writeChar = c1
            opBusy = false
            ops.clear()
            enqueue { enable(g, c1) }
            enqueue { enable(g, c3) }
            enqueue { writeRaw(g, c1, Protocol.buildFrame(0x021B, ByteArray(0))) }
            enqueue { writeRaw(g, c1, Protocol.buildFrame(0x032F, byteArrayOf(1, 0))) }
            ui.postDelayed({ startScheduler() }, 1500)
            ui.postDelayed({ syncHistory() }, 3000)
            ui.postDelayed({ heartbeatLoop() }, 8000)
            ui.postDelayed({ syncTime() }, 2000)
            ui.postDelayed({ armGesture() }, 3000)
            armLoop()
            push("服务就绪")
            _state.update { it.copy(status = "就绪") }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            Log.d(TAG, "onDescriptorWrite status=$status")
            opDone()
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int) {
            Log.d(TAG, "onCharacteristicWrite status=$status")
            opDone()
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray) {
            handleBytes(value)
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            val v = ch.value ?: return
            if (Build.VERSION.SDK_INT < 33) handleBytes(v)
        }
    }

    private fun enable(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
        g.setCharacteristicNotification(ch, true)
        val d = ch.getDescriptor(CCCD)
        if (d == null) { opDone(); return }
        val value = if (ch.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0)
            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        else
            BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
        val ok = if (Build.VERSION.SDK_INT >= 33) {
            g.writeDescriptor(d, value) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            d.value = value
            @Suppress("DEPRECATION")
            g.writeDescriptor(d)
        }
        Log.d(TAG, "enable notify " + ch.uuid + " ok=" + ok)
    }

    private fun writeRaw(g: BluetoothGatt, ch: BluetoothGattCharacteristic, data: ByteArray) {
        Log.d(TAG, "TX " + Protocol.hex(data))
        logFrame("TX", Protocol.hex(data))
        if (Build.VERSION.SDK_INT >= 33) {
            g.writeCharacteristic(ch, data, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
        } else {
            @Suppress("DEPRECATION")
            ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            @Suppress("DEPRECATION")
            ch.value = data
            @Suppress("DEPRECATION")
            g.writeCharacteristic(ch)
        }
    }

    fun connect(ctx: Context, address: String) {
        val ad = adapter(ctx) ?: return
        val dev = ad.getRemoteDevice(address)
        Log.d(TAG, "connect -> $address")
        lastAddress = address
        appCtx = ctx.applicationContext
        push("连接 $address …")
        gatt = dev.connectGatt(ctx, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    private fun heartbeatLoop() {
        if (!_state.value.connected) return
        send(0x021B, ByteArray(0))
        ui.postDelayed({ heartbeatLoop() }, 15000)
    }

    private fun stopAll() {
        send(0x032F, byteArrayOf(0, 0))
        ui.postDelayed({ send(0x032F, byteArrayOf(0, 2)) }, 250)
        ui.postDelayed({ send(0x032F, byteArrayOf(0, 1)) }, 500)
        ui.postDelayed({ send(0x032F, byteArrayOf(0, 10)) }, 750)
        ui.postDelayed({ send(0x032F, byteArrayOf(0, 12)) }, 1000)
    }

    @Volatile private var measuring = false
    private var cycleStart = 0L

    fun measureAll() {
        if (!_state.value.connected) return
        val nowTs = System.currentTimeMillis()
        if (measuring && nowTs - cycleStart < 6 * 60 * 1000L) { push("测量进行中…"); return }
        measuring = true
        cycleStart = nowTs
        syncTime()
        stopAll()
        ui.postDelayed({
            push("全量测量：心率")
            send(0x032F, byteArrayOf(1, 0))
        }, 1500)
        ui.postDelayed({
            send(0x032F, byteArrayOf(0, 0))
            send(0x0506, ByteArray(0))
            send(0x032F, byteArrayOf(1, 2))
            push("全量测量：血氧")
        }, 25000)
        ui.postDelayed({
            send(0x032F, byteArrayOf(0, 2))
            send(0x032F, byteArrayOf(1, 10))
            push("全量测量：HRV")
        }, 90000)
        ui.postDelayed({
            send(0x032F, byteArrayOf(0, 10))
            send(0x032F, byteArrayOf(1, 12))
            push("全量测量：压力")
        }, 125000)
        ui.postDelayed({
            send(0x032F, byteArrayOf(0, 12))
            send(0x032F, byteArrayOf(1, 1))
            push("全量测量：血压")
        }, 160000)
        ui.postDelayed({
            send(0x032F, byteArrayOf(0, 1))
            send(0x0508, ByteArray(0))
            send(0x0309, byteArrayOf(1, 0, 2))
            push("全量测量：同步数据")
        }, 215000)
        ui.postDelayed({
            send(0x0200, byteArrayOf(0x47, 0x43))
            send(0x0509, ByteArray(0))
            syncSleep()
            val ctx = appCtx
            val got = if (ctx != null) Store.get(ctx).statsSince("heart", cycleStart)?.count ?: 0 else 0
            val tNow = _state.value.temp
            measuring = false
            push(
                when {
                    got > 0 -> "全量测量：完成"
                    tNow != null && tNow < 32.0 -> "未检测到佩戴（温度 " + String.format("%.1f", tNow) + "°C）"
                    else -> "未检测到佩戴，请戴好后重测"
                }
            )
        }, 220000)
        ui.postDelayed({ measuring = false }, 240000)
    }

    @Volatile private var lastSleepDataAt = 0L
    private var sleepTry = 0

    fun syncSleep() {
        if (!_state.value.connected) return
        sleepTry = 0
        requestSleep()
    }

    private fun requestSleep() {
        if (!_state.value.connected) return
        sleepTry++
        send(0x0504, ByteArray(0))
        push("同步睡眠（第 " + sleepTry + " 次）")
        ui.postDelayed({
            if (sleepTry < 3 && System.currentTimeMillis() - lastSleepDataAt > 25_000L) requestSleep()
        }, 25_000L)
    }

    fun syncTime() {
        val c = java.util.Calendar.getInstance()
        val y = c.get(java.util.Calendar.YEAR)
        val dow = c.get(java.util.Calendar.DAY_OF_WEEK)
        val wd = if (dow == 1) 6 else dow - 2
        val p = byteArrayOf(
            (y and 0xFF).toByte(), ((y shr 8) and 0xFF).toByte(),
            (c.get(java.util.Calendar.MONTH) + 1).toByte(),
            c.get(java.util.Calendar.DAY_OF_MONTH).toByte(),
            c.get(java.util.Calendar.HOUR_OF_DAY).toByte(),
            c.get(java.util.Calendar.MINUTE).toByte(),
            c.get(java.util.Calendar.SECOND).toByte(),
            wd.toByte()
        )
        send(0x0100, p)
        // 时区 + 纪元秒（部分固件用它设置 RTC）
        val now = System.currentTimeMillis() / 1000
        val offH = java.util.TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 3600000
        val tz = ByteArray(9)
        for (i in 0 until 8) tz[i] = ((now shr (8 * i)) and 0xFF).toByte()
        tz[8] = offH.toByte()
        send(0x0144, tz)
        push("已校准戒指时间")
    }

    fun syncLatest() {
        if (!_state.value.connected) return
        push("同步最新")
        send(0x0509, ByteArray(0))
        ui.postDelayed({ send(0x0200, byteArrayOf(0x47, 0x43)) }, 1500)
        ui.postDelayed({ send(0x0309, byteArrayOf(1, 0, 2)) }, 3000)
    }

    fun startScheduler() {
        if (cycleRunning) return
        cycleRunning = true
        measureAll()
        ui.postDelayed(object : Runnable {
            override fun run() {
                measureAll()
                ui.postDelayed(this, 30 * 60 * 1000L)
            }
        }, 30 * 60 * 1000L)
    }

    private fun lightCycle() {
        if (!_state.value.connected) return
        send(0x032F, byteArrayOf(1, 0))
        ui.postDelayed({ send(0x032F, byteArrayOf(0, 0)) }, 20000)
        ui.postDelayed({ send(0x032F, byteArrayOf(1, 2)) }, 22000)
        ui.postDelayed({ send(0x032F, byteArrayOf(0, 2)) }, 60000)
        ui.postDelayed({ send(0x0309, byteArrayOf(1, 0, 2)) }, 62000)
        ui.postDelayed({ send(0x0200, byteArrayOf(0x47, 0x43)) }, 64000)
    }

    fun takePhoto() { send(0x030E, byteArrayOf(1)) }

    fun syncSteps() {
        send(0x0309, byteArrayOf(1, 0, 2))
        send(0x0502, ByteArray(0))
    }

    fun queryBattery() {
        send(0x0200, byteArrayOf(0x47, 0x43))
    }

    fun measure(key: String) {
        when (key) {
            "heart" -> send(0x032F, byteArrayOf(1, 0))
            "spo2" -> send(0x032F, byteArrayOf(1, 2))
            "hrv" -> send(0x032F, byteArrayOf(1, 10))
            "stress" -> send(0x032F, byteArrayOf(1, 12))
            "bp" -> send(0x032F, byteArrayOf(1, 1))
            "steps" -> send(0x0309, byteArrayOf(1, 0, 2))
            "battery" -> send(0x0200, byteArrayOf(0x47, 0x43))
            "sleep" -> send(0x0504, ByteArray(0))
        }
    }

    fun syncHistory() {
        if (!_state.value.connected) return
        push("同步历史：全部")
        send(0x0509, ByteArray(0))
        ui.postDelayed({ send(0x0504, ByteArray(0)) }, 1200)
        ui.postDelayed({ push("同步历史：运动"); send(0x0502, ByteArray(0)) }, 2500)
        ui.postDelayed({ push("同步历史：心率"); send(0x0506, ByteArray(0)) }, 5000)
        ui.postDelayed({ push("同步历史：血压"); send(0x0508, ByteArray(0)) }, 7500)
        ui.postDelayed({ send(0x0200, byteArrayOf(0x47, 0x43)) }, 9000)
        ui.postDelayed({ send(0x0504, ByteArray(0)) }, 12000)
        ui.postDelayed({ send(0x0504, ByteArray(0)) }, 20000)
    }

    fun toggleWave(on: Boolean) {
        _state.update { it.copy(waveOn = on) }
        send(0x030B, byteArrayOf(if (on) 1 else 0, 0))
    }

    fun startHeart() { send(0x032F, byteArrayOf(1, 0)) }
    fun stopHeart() { send(0x032F, byteArrayOf(0, 0)) }
    fun startSpo2() { send(0x032F, byteArrayOf(1, 2)) }
    fun stopSpo2() { send(0x032F, byteArrayOf(0, 2)) }

    @Volatile private var lastTapAt = 0L
    fun updateGestureMode(ctx: Context) {
        _state.update { it.copy(gestureMode = GesturePrefs.mode(ctx)) }
        armGesture()
    }

    fun armGesture() {
        val ctx = appCtx ?: return
        if (!_state.value.connected) return
        if (GesturePrefs.mode(ctx) == GesturePrefs.OFF) return
        send(0x030E, byteArrayOf(1))
        Log.d(TAG, "gesture armed (0x030E)")
    }

    fun takePhotoByGesture(ctx: Context) {
        val svc = SwipeService.instance
        if (svc == null) { Log.d(TAG, "photo: accessibility not connected"); return }
        Log.d(TAG, "photo: shutter -> " + svc.tapShutter())
    }

    private val histBuf = ArrayList<Byte>()
    private val histFlush = Runnable { parseHistoryBuffer() }

    private fun parseHistoryBuffer() {
        val ctx = appCtx ?: return
        if (histBuf.isEmpty()) return
        val buf = ByteArray(histBuf.size)
        for (i in histBuf.indices) buf[i] = histBuf[i]
        histBuf.clear()
        if (buf.size < 20) return
        val tz = java.util.TimeZone.getDefault().getOffset(System.currentTimeMillis())
        var start = -1
        var bestScore = 0
        for (off in 0 until 20) {
            var i2 = off
            var prev = 0L
            var score = 0
            while (i2 + 20 <= buf.size) {
                val ts2 = (le32(buf, i2) + 946684800L) * 1000L - tz
                if (saneTs(ts2)) {
                    if (ts2 >= prev) score++
                    prev = ts2
                }
                i2 += 20
            }
            if (score > bestScore) { bestScore = score; start = off }
        }
        Log.d(TAG, "history align offset=" + start + " score=" + bestScore + " len=" + buf.size)
        if (start < 0 || bestScore < 2) return
        var i = start
        var n = 0
        val db = Store.get(ctx)
        // 零时间戳兜底：设备 RTC 丢失时，用"最近一条"的值打上当前时间
        var fbHr = 0; var fbSbp = 0; var fbDbp = 0; var fbO2 = 0
        var fbHrv = 0; var fbResp = 0; var fbTemp = 0; var fbSteps = 0
        while (i + 20 <= buf.size) {
            val ts = (le32(buf, i) + 946684800L) * 1000L - tz
            if (!saneTs(ts)) {
                val h2 = buf[i + 6].toInt() and 0xFF
                val s2 = buf[i + 7].toInt() and 0xFF
                val d2 = buf[i + 8].toInt() and 0xFF
                val o2v = buf[i + 9].toInt() and 0xFF
                val r2 = buf[i + 10].toInt() and 0xFF
                val v2 = buf[i + 11].toInt() and 0xFF
                val t2 = buf[i + 13].toInt() and 0xFF
                val st2 = (buf[i + 4].toInt() and 0xFF) or ((buf[i + 5].toInt() and 0xFF) shl 8)
                if (h2 in 25..220) fbHr = h2
                if (s2 in 60..200 && d2 in 30..140) { fbSbp = s2; fbDbp = d2 }
                if (o2v in 70..100) fbO2 = o2v
                if (r2 in 5..40) fbResp = r2
                if (v2 in 1..250) fbHrv = v2
                if (t2 in 30..42) fbTemp = t2
                if (st2 > 0) fbSteps = st2
                i += 20; continue
            }
            val steps = (buf[i + 4].toInt() and 0xFF) or ((buf[i + 5].toInt() and 0xFF) shl 8)
            val hr = buf[i + 6].toInt() and 0xFF
            val sbp = buf[i + 7].toInt() and 0xFF
            val dbp = buf[i + 8].toInt() and 0xFF
            val o2 = buf[i + 9].toInt() and 0xFF
            val resp = buf[i + 10].toInt() and 0xFF
            val hrvs = buf[i + 11].toInt() and 0xFF
            val tInt = buf[i + 13].toInt() and 0xFF
            val tFrac = buf[i + 14].toInt() and 0xFF
            if (steps > 0) db.insert("steps", steps, ts)
            if (hr in 25..220) db.insert("heart", hr, ts)
            if (sbp in 60..200 && dbp in 30..140) {
                db.insert("bp_sys", sbp, ts)
                db.insert("bp_dia", dbp, ts)
            }
            if (o2 in 70..100) db.insert("spo2", o2, ts)
            if (hrvs in 1..200) db.insert("hrv", hrvs, ts)
            if (resp in 5..40) db.insert("resp", resp, ts)
            if (tInt in 30..42) {
                val centi = tInt * 100 + tFrac
                db.insert("temp", centi, ts)
                _state.update { it.copy(temp = centi / 100.0) }
            }
            n++
            i += 20
        }
        val nowTs = System.currentTimeMillis()
        if (fbHr > 0) db.insert("heart", fbHr, nowTs)
        if (fbSbp > 0) { db.insert("bp_sys", fbSbp, nowTs); db.insert("bp_dia", fbDbp, nowTs) }
        if (fbO2 > 0) db.insert("spo2", fbO2, nowTs)
        if (fbResp > 0) db.insert("resp", fbResp, nowTs)
        if (fbHrv > 0) db.insert("hrv", fbHrv, nowTs)
        if (fbTemp > 0) db.insert("temp", fbTemp * 100, nowTs)
        if (fbSteps > 0) db.insert("steps", fbSteps, nowTs)
        if (n > 0 || fbHrv > 0) push("历史 " + n + " 条" + if (fbHrv > 0) " · HRV " + fbHrv else "")
    }

    private fun armLoop() {
        ui.postDelayed(object : Runnable {
            override fun run() {
                armGesture()
                ui.postDelayed(this, 60_000L)
            }
        }, 60_000L)
    }

    private val sleepBuf = ArrayList<Byte>()

    private fun parseSleepBuffer() {
        val ctx = appCtx
        val buf = ByteArray(sleepBuf.size)
        for (i in sleepBuf.indices) buf[i] = sleepBuf[i]
        sleepBuf.clear()
        if (ctx == null || buf.size < 8) return
        val tz = java.util.TimeZone.getDefault().getOffset(System.currentTimeMillis())
        val start = if ((buf[0].toInt() and 0xFF) == 0xAF) 5 else 0
        val segs = ArrayList<Triple<Long, Int, Int>>()
        var i = start
        while (i + 8 <= buf.size) {
            val code = buf[i + 3].toInt() and 0xFF
            if (buf[i + 2].toInt() == 0 && code in 0xF1..0xF3) {
                val sec = (buf[i].toInt() and 0xFF) or ((buf[i + 1].toInt() and 0xFF) shl 8)
                val stage = when (code) { 0xF1 -> 1; 0xF2 -> 2; else -> 3 }
                val endTs = (le32(buf, i + 4) + 946684800L) * 1000L - tz
                val startTs = endTs - sec * 1000L
                if (sec > 0 && saneTs(startTs)) segs.add(Triple(startTs, sec, stage))
                i += 8
            } else i++
        }
        if (segs.isNotEmpty()) {
            lastSleepDataAt = System.currentTimeMillis()
            Store.get(ctx).insertSleepSegs(segs)
            push("睡眠 " + segs.size + " 段")
        }
    }

    fun send(type: Int, payload: ByteArray) {
        val g = gatt ?: return
        val ch = writeChar ?: return
        enqueue { writeRaw(g, ch, Protocol.buildFrame(type, payload)) }
    }

    fun disconnect() {
        try { gatt?.disconnect() } catch (_: Exception) {}
        try { gatt?.close() } catch (_: Exception) {}
        gatt = null
        writeChar = null
        _state.update { it.copy(connected = false, status = "已断开") }
    }

    private fun logFrame(dir: String, hex: String) {
        val ctx = appCtx ?: return
        try {
            val f = java.io.File(ctx.getExternalFilesDir(null), "frames.txt")
            f.appendText(System.currentTimeMillis().toString() + " " + dir + " " + hex + "\n")
        } catch (_: Exception) {}
    }

    private fun save(metric: String, value: Int, ts: Long = 0L) {
        val ctx = appCtx ?: return
        val now = System.currentTimeMillis()
        val stamp = if (ts > 0) ts else now
        val last = lastSaved[metric] ?: 0L
        if (now - last < 30000L) { Log.d(TAG, "save $metric skip(throttle)"); return }
        lastSaved[metric] = now
        try {
            Store.get(ctx).insert(metric, value, stamp)
            Log.d(TAG, "save $metric=$value ts=$stamp ok")
        } catch (e: Exception) { Log.d(TAG, "save $metric fail: " + e.message) }
    }

    private fun handleBytes(bytes: ByteArray) {
        for (b in bytes) rxBuffer.add(b)
        while (rxBuffer.size >= 4) {
            val total = (rxBuffer[2].toInt() and 0xFF) or ((rxBuffer[3].toInt() and 0xFF) shl 8)
            if (total < 6 || total > 512) { rxBuffer.clear(); return }
            if (rxBuffer.size < total) return
            val frame = ByteArray(total)
            for (i in 0 until total) frame[i] = rxBuffer[i]
            repeat(total) { rxBuffer.removeAt(0) }
            dispatch(frame)
        }
    }

    private fun saneTs(ts: Long): Boolean {
        val now = System.currentTimeMillis()
        return ts in (now - 60L * 24 * 3600 * 1000)..(now + 24L * 3600 * 1000)
    }

    private fun le32(b: ByteArray, i: Int): Long =
        ((b[i].toInt() and 0xFF).toLong()) or
        ((b[i + 1].toInt() and 0xFF).toLong() shl 8) or
        ((b[i + 2].toInt() and 0xFF).toLong() shl 16) or
        ((b[i + 3].toInt() and 0xFF).toLong() shl 24)

    private fun dispatch(frame: ByteArray) {
        if (frame.size < 6) return
        val type = ((frame[0].toInt() and 0xFF) shl 8) or (frame[1].toInt() and 0xFF)
        val payload = frame.copyOfRange(4, frame.size - 2)
        _state.update { it.copy(lastRx = System.currentTimeMillis()) }
        Log.d(TAG, "RX " + String.format("%04X", type) + " " + Protocol.hex(payload))
        logFrame("RX", Protocol.hex(frame))
        val ctx = appCtx
        when (type) {
            0x0200 -> if (payload.size >= 6) {
                val batt = payload[5].toInt() and 0xFF
                val fw = (payload[3].toInt() and 0xFF).toString() + "." + (payload[2].toInt() and 0xFF)
                if (batt in 1..100) { save("battery", batt, 0); _state.update { it.copy(battery = batt) } }
                _state.update { it.copy(firmware = fw) }
            }
            0x0504 -> if (payload.size >= 8) {
                val cnt = (payload[2].toInt() and 0xFF) or ((payload[3].toInt() and 0xFF) shl 8) or
                    ((payload[4].toInt() and 0xFF) shl 16) or ((payload[5].toInt() and 0xFF) shl 24)
                Log.d(TAG, "sleep ack records=" + cnt)
                if (cnt == 0) push("睡眠：设备暂无新数据")
                else lastSleepDataAt = System.currentTimeMillis()
            }
            0x0513 -> {
                for (b in payload) sleepBuf.add(b)
                val need = if (sleepBuf.size >= 5 && (sleepBuf[0].toInt() and 0xFF) == 0xAF) {
                    (sleepBuf[2].toInt() and 0xFF) or ((sleepBuf[3].toInt() and 0xFF) shl 8) or ((sleepBuf[4].toInt() and 0xFF) shl 16)
                } else 0
                if (need > 0 && sleepBuf.size >= need) {
                    parseSleepBuffer()
                } else if (sleepBuf.size > 4000) {
                    sleepBuf.clear()
                }
            }
            0x0580 -> {
                if (sleepBuf.isNotEmpty()) parseSleepBuffer()
                if (histBuf.isNotEmpty()) { ui.removeCallbacks(histFlush); parseHistoryBuffer() }
            }
            0x0518 -> {
                for (b in payload) histBuf.add(b)
                ui.removeCallbacks(histFlush)
                ui.postDelayed(histFlush, 1200)
            }
            0x0515 -> if (ctx != null) {
                var i = 0; var n = 0
                while (i + 6 <= payload.size) {
                    val ts = (le32(payload, i) + 946684800L) * 1000L - java.util.TimeZone.getDefault().getOffset(System.currentTimeMillis())
                    val hr = payload[i + 5].toInt() and 0xFF
                    if (hr in 25..220 && saneTs(ts)) { Store.get(ctx).insert("heart", hr, ts); n++ }
                    i += 6
                }
                push("心率历史 " + n + " 条")
            }
            0x0403 -> {
                val now = System.currentTimeMillis()
                val gap = now - lastTapAt
                lastTapAt = now
                _state.update { it.copy(tapCount = it.tapCount + 1, tapAt = now) }
                push("戒指事件 " + Protocol.hex(payload) + " 间隔" + gap + "ms")
                if (gap < 2500L) {
                    Log.d(TAG, "0403 debounce gap=" + gap)
                } else {
                    val mode = if (ctx != null) GesturePrefs.mode(ctx) else GesturePrefs.OFF
                    Log.d(TAG, "0403 trigger mode=" + mode)
                    send(0x0403, byteArrayOf(0))
                    when (mode) {
                        GesturePrefs.SWIPE -> {
                            ui.postDelayed({ armGesture() }, 1500)
                            val svc = SwipeService.instance
                            if (svc == null) Log.d(TAG, "0403 but accessibility not connected")
                            else try { svc.swipeUp() } catch (e: Exception) { Log.d(TAG, "0403 swipe err " + e.message) }
                        }
                        GesturePrefs.PHOTO -> {
                            if (ctx != null) takePhotoByGesture(ctx)
                            ui.postDelayed({ armGesture() }, 1500)
                        }
                        else -> Log.d(TAG, "0403 ignored (off)")
                    }
                }
            }
            0x0600 -> if (payload.size >= 6) {
                val st = (payload[0].toInt() and 0xFF) or ((payload[1].toInt() and 0xFF) shl 8)
                val di = (payload[2].toInt() and 0xFF) or ((payload[3].toInt() and 0xFF) shl 8)
                val kc = (payload[4].toInt() and 0xFF) or ((payload[5].toInt() and 0xFF) shl 8)
                save("steps", st)
                _state.update { it.copy(steps = st, distance = di, kcal = kc) }
            }
            0x0601 -> if (payload.isNotEmpty()) {
                val v = payload[0].toInt() and 0xFF
                val valid = v in 25..220
                if (valid) save("heart", v)
                _state.update { it.copy(heart = if (valid) v else null, hrSeries = if (valid) (it.hrSeries + v).takeLast(200) else it.hrSeries) }
            }
            0x0602 -> if (payload.isNotEmpty()) {
                val v = payload[0].toInt() and 0xFF
                val valid = v in 70..100
                if (valid) save("spo2", v)
                _state.update { it.copy(spo2 = if (valid) v else null, spo2Series = if (valid) (it.spo2Series + v).takeLast(200) else it.spo2Series) }
            }
            0x0603 -> if (payload.size >= 4) {
                val sbp = payload[0].toInt() and 0xFF
                val dbp = payload[1].toInt() and 0xFF
                val hrvs = payload[3].toInt() and 0xFF
                if (sbp in 60..200 && dbp in 30..140) {
                    save("bp_sys", sbp, 0); save("bp_dia", dbp, 0)
                    _state.update { it.copy(bpSys = sbp, bpDia = dbp) }
                }
                if (hrvs in 1..200) { save("hrv", hrvs, 0); _state.update { it.copy(hrv = hrvs) } }
                if (payload.size >= 7) {
                    val tInt = payload[5].toInt() and 0xFF
                    val tFrac = payload[6].toInt() and 0xFF
                    if (tInt in 30..42) {
                        val centi = tInt * 100 + tFrac
                        save("temp", centi, 0)
                        _state.update { it.copy(temp = centi / 100.0) }
                    }
                }
            }
            0x040E -> if (payload.size >= 2 && (payload[1].toInt() and 0xFF) == 0x02) {
                push("测量失败：可能未佩戴")
            }
            0x0615 -> if (payload.isNotEmpty()) {
                val v = payload[0].toInt() and 0xFF
                save("battery", v, 0)
                _state.update { it.copy(battery = v) }
            }
        }
        push(String.format("%04X", type) + "  " + Protocol.hex(payload).take(40))
    }
}