package com.example.zreader.reader

import android.graphics.Color

/** 阅读主题（文字与背景设置中的背景配色） */
data class ReaderSkin(
    val name: String,
    val bg: Int,
    val text: Int,
    val secondary: Int,
    val mask: Int,          // 菜单遮罩
    val darkIcons: Boolean, // 状态栏图标是否用深色
)

object ReaderSkins {
    val ALL = listOf(
        ReaderSkin("纸白", Color.WHITE, 0xFF20201F.toInt(), 0xFF9A9589.toInt(), 0x33000000, darkIcons = true),
        ReaderSkin("羊皮纸", 0xFFF5EDD8.toInt(), 0xFF433A2E.toInt(), 0xFFA89C86.toInt(), 0x33806614, darkIcons = true),
        ReaderSkin("护眼", 0xFFCBE5CB.toInt(), 0xFF2C3A2C.toInt(), 0xFF7D937D.toInt(), 0x33004400, darkIcons = true),
        ReaderSkin("夜间", 0xFF232326.toInt(), 0xFFA8A8A8.toInt(), 0xFF6E6E72.toInt(), 0x66000000, darkIcons = false),
        ReaderSkin("纯黑", Color.BLACK, 0xFF8F8F8F.toInt(), 0xFF5A5A5A.toInt(), 0x88000000.toInt(), darkIcons = false),
    )

    fun get(index: Int): ReaderSkin = ALL[index.coerceIn(0, ALL.size - 1)]

    /** 高亮颜色（ARGB，绘制时直接作为底色，含透明度） */
    val HIGHLIGHTS = intArrayOf(
        0x66F5C518, // 黄
        0x6664D98B, // 绿
        0x6657A8F5, // 蓝
        0x66EF8FA8, // 粉
    )

    fun highlightOpaque(index: Int): Int = intArrayOf(
        0xFFF5C518.toInt(), 0xFF64D98B.toInt(), 0xFF57A8F5.toInt(), 0xFFEF8FA8.toInt(),
    )[index.coerceIn(0, 3)]

    /** 书架封面配色（起止渐变色） */
    val COVER_COLORS = arrayOf(
        0xFF7E8CE0.toInt() to 0xFF5A68C8.toInt(),
        0xFF63B6A8.toInt() to 0xFF3F9086.toInt(),
        0xFFDD8E6B.toInt() to 0xFFC46A4A.toInt(),
        0xFF9E86C8.toInt() to 0xFF7A62AC.toInt(),
        0xFF6FA8DC.toInt() to 0xFF4F88BC.toInt(),
        0xFFC87FA0.toInt() to 0xFFA85F80.toInt(),
        0xFF8FAE5D.toInt() to 0xFF6E8C3E.toInt(),
        0xFFB0855F.toInt() to 0xFF8E6A42.toInt(),
    )
}
