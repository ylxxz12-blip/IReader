package com.example.zreader.parser

/** 文本编码探测：BOM → 严格 UTF-8 校验 → 无 BOM UTF-16 启发 → GB18030 */
object CharsetDetector {

    fun detect(bytes: ByteArray, length: Int = bytes.size): String {
        if (length >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) return "UTF-8"
        if (length >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) return "UTF-16LE"
        if (length >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) return "UTF-16BE"

        // 严格 UTF-8 校验（采样前后各 64KB）
        if (isStrictUtf8(bytes, length)) return "UTF-8"

        // 无 BOM 的 UTF-16：ASCII 文本会出现大量 0 字节
        if (looksUtf16(bytes, length)) return "UTF-16LE"

        return "GB18030"
    }

    private fun isStrictUtf8(bytes: ByteArray, length: Int): Boolean {
        val from = maxOf(0, length / 2 - 65536)
        val to = minOf(length, length / 2 + 65536)
        var i = from
        while (i < to) {
            val b = bytes[i].toInt() and 0xFF
            val n = when {
                b < 0x80 -> 1
                b in 0xC2..0xDF -> 2
                b in 0xE0..0xEF -> 3
                b in 0xF0..0xF4 -> 4
                else -> return false
            }
            if (i + n > to) break // 采样截断处不判定
            for (k in 1 until n) {
                val c = bytes[i + k].toInt() and 0xFF
                if (c !in 0x80..0xBF) return false
            }
            // 过长的代理区/超平面校验
            if (n == 3 && b == 0xE0 && (bytes[i + 1].toInt() and 0xFF) < 0xA0) return false
            if (n == 3 && b == 0xED && (bytes[i + 1].toInt() and 0xFF) > 0x9F) return false
            if (n == 4 && b == 0xF0 && (bytes[i + 1].toInt() and 0xFF) < 0x90) return false
            if (n == 4 && b == 0xF4 && (bytes[i + 1].toInt() and 0xFF) > 0x8F) return false
            i += n
        }
        return true
    }

    private fun looksUtf16(bytes: ByteArray, length: Int): Boolean {
        val n = minOf(length, 4096)
        if (n < 4) return false
        var evenZero = 0
        var oddZero = 0
        var ascii = 0
        for (i in 0 until n - 1 step 2) {
            if (bytes[i].toInt() == 0) evenZero++
            if (bytes[i + 1].toInt() == 0) oddZero++
            if (bytes[i].toInt() in 0x20..0x7E) ascii++
        }
        val half = n / 2
        return (evenZero > half * 3 / 4 || oddZero > half * 3 / 4) && ascii > half / 4
    }
}
