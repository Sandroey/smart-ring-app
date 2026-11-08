package com.rea.ring

object Protocol {

    fun crc16(data: ByteArray, len: Int): Int {
        var s = 0xFFFF
        for (i in 0 until len) {
            val b = data[i].toInt() and 0xFF
            var v = (((s shl 8) and 0xFF00) or ((s shr 8) and 0xFF)) xor b
            v = v and 0xFFFF
            val v3 = (v xor ((v and 0xFF) shr 4)) and 0xFFFF
            val v4 = (v3 xor ((v3 shl 12) and 0xFFFF)) and 0xFFFF
            s = (v4 xor (((v4 and 0xFF) shl 5) and 0xFFFF)) and 0xFFFF
        }
        return s and 0xFFFF
    }

    fun buildFrame(type: Int, payload: ByteArray): ByteArray {
        val total = payload.size + 6
        val out = ByteArray(total)
        out[0] = ((type shr 8) and 0xFF).toByte()
        out[1] = (type and 0xFF).toByte()
        out[2] = (total and 0xFF).toByte()
        out[3] = ((total shr 8) and 0xFF).toByte()
        System.arraycopy(payload, 0, out, 4, payload.size)
        val c = crc16(out, payload.size + 4)
        out[total - 2] = (c and 0xFF).toByte()
        out[total - 1] = ((c shr 8) and 0xFF).toByte()
        return out
    }

    fun hex(bytes: ByteArray): String =
        bytes.joinToString(" ") { String.format("%02x", it.toInt() and 0xFF) }

    fun parseHex(text: String): ByteArray {
        val clean = text.replace("[^0-9a-fA-F]".toRegex(), "")
        val n = clean.length / 2
        val out = ByteArray(n)
        for (i in 0 until n) {
            out[i] = clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
        return out
    }
}