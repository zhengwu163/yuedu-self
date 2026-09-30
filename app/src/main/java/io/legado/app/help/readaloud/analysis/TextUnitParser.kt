package io.legado.app.help.readaloud.analysis

import io.legado.app.help.readaloud.role.ReadAloudRolePreprocessor
import io.legado.app.help.readaloud.role.ReadAloudRoleRange
import io.legado.app.help.readaloud.role.ReadAloudRoleUnit
import io.legado.app.help.readaloud.role.FrozenReadAloudPreprocessRules
import java.security.MessageDigest
import java.util.Collections

/**
 * 排版前章节的不可变快照。
 *
 * 段落内 start/end 和 [text] 都使用 Kotlin String 的 UTF-16 code-unit 口径；
 * 服务器只接收 [ParsedTextUnit.text]，正文回写仍依赖 [ParsedTextUnit.ranges]。
 */
data class ChapterParagraphSnapshot(
    val paragraphIndex: Int,
    val chapterStart: Int,
    val text: String,
    val chapterEnd: Int = chapterStart + text.length
) {
    init {
        require(paragraphIndex >= 0)
        require(chapterStart >= 0)
        require(chapterEnd.toLong() >= chapterStart.toLong() + text.length)
        validateUtf16(text)
    }

    // 避免打印快照/集合时将章节原文带入诊断日志。
    override fun toString(): String =
        "ChapterParagraphSnapshot(paragraphIndex=$paragraphIndex, textLength=${text.length})"
}

class ChapterTextSnapshot(paragraphs: List<ChapterParagraphSnapshot>) {
    private val storedParagraphs: List<ChapterParagraphSnapshot> =
        Collections.unmodifiableList(paragraphs.toList())

    val paragraphs: List<ChapterParagraphSnapshot>
        get() = storedParagraphs

    init {
        require(storedParagraphs.zipWithNext().all { (first, second) ->
            first.paragraphIndex < second.paragraphIndex &&
                first.chapterEnd <= second.chapterStart
        })
    }

    // 固定 LF 是快照协议的一部分；外部章节坐标不用于猜测或填充正文间隙。
    val text: String = storedParagraphs.joinToString("\n") { it.text }
    private val paragraphOrder = storedParagraphs.mapIndexed { index, paragraph ->
        paragraph.paragraphIndex to index
    }.toMap()
    private val offsets = IntArray(storedParagraphs.size).also { offsets ->
        var offset = 0
        storedParagraphs.forEachIndexed { index, paragraph ->
            offsets[index] = offset
            offset += paragraph.text.length
            if (index < storedParagraphs.lastIndex) offset++
        }
    }

    /**
     * 章节正文 hash 只基于本次快照，不使用 URL、标题或服务器返回内容。
     */
    val textHash: String = stableSha256(text)

    fun copy(paragraphs: List<ChapterParagraphSnapshot> = storedParagraphs): ChapterTextSnapshot {
        return ChapterTextSnapshot(paragraphs)
    }

    fun textFor(ranges: List<ReadAloudRoleRange>): String {
        var previous: ReadAloudRoleRange? = null
        return ranges.joinToString("\n") { range ->
            val index = paragraphOrder[range.paragraphIndex]
                ?: throw IllegalArgumentException("range paragraph is outside snapshot")
            val paragraph = storedParagraphs[index]
            val old = previous
            if (old != null) {
                // 多 range 只表示一段连续正文，不能跳段、重复或在段内凭空插入 LF。
                val previousIndex = paragraphOrder.getValue(old.paragraphIndex)
                require(index == previousIndex + 1)
                require(old.end == storedParagraphs[previousIndex].text.length && range.start == 0)
            }
            previous = range
            require(range.start in 0..paragraph.text.length)
            require(range.end in range.start..paragraph.text.length)
            require(isUtf16Boundary(paragraph.text, range.start))
            require(isUtf16Boundary(paragraph.text, range.end))
            paragraph.text.substring(range.start, range.end)
        }
    }

    internal fun offsetOf(paragraphIndex: Int, position: Int): Int =
        offsets[paragraphOrder.getValue(paragraphIndex)] + position

    /** 边界上的零长度 range 显式承载 LF；因此空段及单独分隔符也能准确回拼。 */
    internal fun rangesFor(start: Int, end: Int): List<ReadAloudRoleRange> {
        require(start in 0 until end && end <= text.length)
        var index = offsets.binarySearch(start).let { if (it >= 0) it else -it - 2 }
        val ranges = ArrayList<ReadAloudRoleRange>()
        while (index < storedParagraphs.size && offsets[index] <= end) {
            val paragraph = storedParagraphs[index]
            val localStart = (start - offsets[index]).coerceAtLeast(0)
            val localEnd = minOf(end - offsets[index], paragraph.text.length)
            if (localStart <= localEnd) {
                ranges += ReadAloudRoleRange(paragraph.paragraphIndex, localStart, localEnd)
            }
            index++
        }
        return Collections.unmodifiableList(ranges)
    }
}

data class ParsedTextUnit(
    val unitId: String,
    val kind: String,
    val roleType: String,
    val characterName: String,
    val ranges: List<ReadAloudRoleRange>,
    val text: String,
    val needsAi: Boolean,
    val confidence: Double,
    val reason: String
) {
    override fun toString(): String =
        "ParsedTextUnit(textLength=${text.length}, rangeCount=${ranges.size})"
}

object TextUnitParser {

    const val ANALYSIS_VERSION = "text-unit-v1:" + ReadAloudRolePreprocessor.VERSION
    private const val MAX_UNIT_LENGTH = 1200

    /**
     * 解析只在本地完成；不修改快照、不上传正文、不依赖服务器 DTO。
     */
    fun parse(
        snapshot: ChapterTextSnapshot,
        frozenRules: FrozenReadAloudPreprocessRules? = null
    ): List<ParsedTextUnit> {
        if (snapshot.paragraphs.isEmpty()) return emptyList()

        val localResult = ReadAloudRolePreprocessor.process(
            paragraphs = snapshot.paragraphs.map { it.text },
            frozenRules = frozenRules
        )
        val result = ArrayList<ParsedTextUnit>()

        fun append(start: Int, end: Int, role: ReadAloudRoleUnit?) {
            var cursor = start
            while (cursor < end) {
                // 固定上限按 UTF-16 计数，但不能拆开 emoji 或 CRLF。
                val next = safeSplitBoundary(snapshot.text, cursor + minOf(MAX_UNIT_LENGTH, end - cursor))
                val ranges = snapshot.rangesFor(cursor, next)
                val text = snapshot.text.substring(cursor, next)
                val kind = role?.kind ?: "narrator"
                result += ParsedTextUnit(
                    unitId = stableUnitId(text, kind, ranges),
                    kind = kind,
                    roleType = role?.roleType ?: "narrator",
                    characterName = role?.characterName ?: "旁白",
                    ranges = ranges,
                    text = text,
                    needsAi = role?.needsAi ?: false,
                    confidence = role?.confidence ?: 1.0,
                    reason = role?.reason ?: "source_gap"
                )
                cursor = next
            }
        }

        var cursor = 0
        var sourceCursor = 0
        localResult.units.forEach { unit ->
            val ranges = unit.ranges.map { range ->
                val sourceParagraph = snapshot.paragraphs.getOrNull(range.paragraphIndex)
                    ?: throw IllegalArgumentException("parser returned an unknown paragraph")
                range.copy(paragraphIndex = sourceParagraph.paragraphIndex)
            }
            require(snapshot.textFor(ranges) == unit.text)
            val sourceStart = snapshot.offsetOf(ranges.first().paragraphIndex, ranges.first().start)
            val sourceEnd = snapshot.offsetOf(ranges.last().paragraphIndex, ranges.last().end)
            require(sourceStart >= sourceCursor)
            sourceCursor = sourceEnd
            // 角色范围也可能结束在“段尾 CR + 段间 LF”之间；统一左移边界，
            // 将完整 CRLF 留给后续单元，避免在最后一个 CR 上无法向前推进。
            val start = safeSplitBoundary(snapshot.text, sourceStart)
            val end = safeSplitBoundary(snapshot.text, sourceEnd)
            append(cursor, start, null)
            append(start, end, unit)
            cursor = end
        }
        append(cursor, snapshot.text.length, null)
        return Collections.unmodifiableList(result)
    }

    private fun stableUnitId(
        text: String,
        kind: String,
        ranges: List<ReadAloudRoleRange>
    ): String {
        val coordinate = ranges.joinToString(";") {
            "${it.paragraphIndex}:${it.start}-${it.end}"
        }
        // ID 长度与跨段数量无关，服务端无需识别其含义。
        return "u_" + stableSha256("$kind|$coordinate|$text")
    }
}

private fun safeSplitBoundary(text: String, index: Int): Int {
    val insideCrLf = index > 0 && index < text.length &&
        text[index - 1] == '\r' && text[index] == '\n'
    return if (!isUtf16Boundary(text, index) || insideCrLf) index - 1 else index
}

private fun validateUtf16(text: String) {
    var index = 0
    while (index < text.length) {
        val current = text[index]
        when {
            current.isHighSurrogate() -> {
                require(index + 1 < text.length && text[index + 1].isLowSurrogate())
                index += 2
            }
            current.isLowSurrogate() -> throw IllegalArgumentException("unpaired UTF-16 surrogate")
            else -> index++
        }
    }
}

private fun isUtf16Boundary(text: String, index: Int): Boolean {
    return index == 0 || index == text.length ||
        !(text[index - 1].isHighSurrogate() && text[index].isLowSurrogate())
}

private fun stableSha256(value: String): String {
    return MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
}
