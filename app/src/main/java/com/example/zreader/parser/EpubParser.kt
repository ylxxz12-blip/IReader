package com.example.zreader.parser

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.File
import java.util.zip.ZipFile

/** EPUB 解析：仅抽取正文文本（按 spine 顺序），图片与样式忽略 */
object EpubParser {

    data class EpubBook(val title: String, val author: String, val text: String)

    fun parse(file: File, fallbackTitle: String): EpubBook {
        ZipFile(file).use { zip ->
            val opfPath = findOpfPath(zip)
            val opfEntry = zip.getEntry(opfPath) ?: throw IllegalArgumentException("EPUB 缺少 OPF: $opfPath")
            val opfDir = opfPath.substringBeforeLast('/', "")

            var title = ""
            var author = ""
            val manifest = HashMap<String, String>() // id -> href
            val spine = ArrayList<String>()          // idref 顺序

            val p = newParser(zip.getInputStream(opfEntry))
            var inTitle = false
            var inCreator = false
            while (p.next() != XmlPullParser.END_DOCUMENT) {
                when (p.eventType) {
                    XmlPullParser.START_TAG -> when (p.name.lowercase()) {
                        "title" -> inTitle = true
                        "creator" -> inCreator = true
                        "item" -> {
                            val id = p.getAttributeValue(null, "id")
                            val href = p.getAttributeValue(null, "href")
                            if (id != null && href != null) manifest[id] = unescape(href)
                        }
                        "itemref" -> {
                            val idref = p.getAttributeValue(null, "idref")
                            if (idref != null) spine.add(idref)
                        }
                    }
                    XmlPullParser.TEXT -> {
                        val t = p.text?.trim().orEmpty()
                        if (t.isNotEmpty()) {
                            if (inTitle && title.isEmpty()) title = t
                            if (inCreator && author.isEmpty()) author = t
                        }
                    }
                    XmlPullParser.END_TAG -> when (p.name.lowercase()) {
                        "title" -> inTitle = false
                        "creator" -> inCreator = false
                    }
                }
            }

            val paragraphs = ArrayList<String>()
            for (idref in spine) {
                val href = manifest[idref] ?: continue
                val path = if (opfDir.isEmpty()) href else "$opfDir/$href"
                val entry = zip.getEntry(path) ?: zip.getEntry(href) ?: continue
                val html = zip.getInputStream(entry)
                paragraphs.addAll(extractParagraphs(html))
            }
            if (paragraphs.isEmpty()) throw IllegalArgumentException("EPUB 未解析到正文")
            if (title.isEmpty()) title = fallbackTitle
            return EpubBook(title, author, paragraphs.joinToString("\n"))
        }
    }

    private fun findOpfPath(zip: ZipFile): String {
        val container = zip.getEntry("META-INF/container.xml")
            ?: throw IllegalArgumentException("EPUB 缺少 container.xml")
        val p = newParser(zip.getInputStream(container))
        while (p.next() != XmlPullParser.END_DOCUMENT) {
            if (p.eventType == XmlPullParser.START_TAG && p.name.lowercase() == "rootfile") {
                val path = p.getAttributeValue(null, "full-path")
                if (!path.isNullOrEmpty()) return path
            }
        }
        throw IllegalArgumentException("container.xml 中未找到 rootfile")
    }

    private val BLOCK_TAGS = setOf("p", "h1", "h2", "h3", "h4", "h5", "h6", "blockquote", "li", "pre", "div")

    /** 抽取 XHTML 中的块级文本；跳过 head/script/style */
    private fun extractParagraphs(input: java.io.InputStream): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        val p = newParser(input)
        var blockDepth = 0
        var skipDepth = 0
        val blockStack = ArrayDeque<Boolean>() // 每层 block 是否已产出过 <p> 等子块

        while (p.next() != XmlPullParser.END_DOCUMENT) {
            when (p.eventType) {
                XmlPullParser.START_TAG -> {
                    val name = p.name.lowercase()
                    if (name == "br" && blockDepth > 0 && skipDepth == 0) sb.append('\n')
                    if (name in setOf("head", "script", "style")) {
                        skipDepth++
                    } else if (name in BLOCK_TAGS) {
                        if (skipDepth == 0) {
                            blockStack.addLast(false)
                            blockDepth++
                        }
                    }
                }
                XmlPullParser.TEXT -> {
                    if (skipDepth == 0 && blockDepth > 0) {
                        val t = p.text ?: ""
                        if (t.isNotEmpty()) sb.append(t)
                    }
                }
                XmlPullParser.END_TAG -> {
                    val name = p.name.lowercase()
                    if (name in setOf("head", "script", "style")) {
                        if (skipDepth > 0) skipDepth--
                    } else if (name in BLOCK_TAGS && skipDepth == 0 && blockDepth > 0) {
                        blockDepth--
                        val hadChildBlock = blockStack.removeLastOrNull() ?: false
                        val text = sb.toString().replace('\n', ' ')
                            .replace(Regex("\\s+"), " ").trim()
                        if (text.isNotEmpty() && !hadChildBlock) {
                            out.add(text)
                        }
                        sb.clear()
                        if (blockStack.isNotEmpty()) blockStack[blockStack.size - 1] = true
                    }
                }
            }
        }
        return out
    }

    private fun newParser(input: java.io.InputStream): XmlPullParser {
        val factory = XmlPullParserFactory.newInstance()
        factory.isNamespaceAware = true
        val p = factory.newPullParser()
        p.setInput(input.reader(Charsets.UTF_8))
        return p
    }

    private fun unescape(s: String): String = java.net.URLDecoder.decode(s, "UTF-8")
}
