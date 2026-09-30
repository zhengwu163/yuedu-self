package io.legado.app.help.readaloud.analysis

import io.legado.app.help.readaloud.role.ReadAloudRoleRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class TextUnitParserTest {

    @Test
    fun `parser keeps UTF-16 ranges and emoji text lossless`() {
        val snapshot = ChapterTextSnapshot(
            paragraphs = listOf(
                ChapterParagraphSnapshot(
                    paragraphIndex = 7,
                    chapterStart = 120,
                    text = "“😀你好！”"
                )
            )
        )

        val units = TextUnitParser.parse(snapshot)

        assertEquals(1, units.size)
        val unit = units.single()
        assertEquals(snapshot.textFor(unit.ranges), unit.text)
        assertEquals("“😀你好！”", unit.text)
        assertEquals(7, unit.ranges.single().paragraphIndex)
        assertEquals(0, unit.ranges.single().start)
        assertEquals(snapshot.paragraphs.single().text.length, unit.ranges.single().end)
        assertEquals(7, snapshot.paragraphs.single().text.length)
    }

    @Test
    fun `parser preserves cross paragraph unit boundaries and newline join`() {
        val snapshot = ChapterTextSnapshot(
            paragraphs = listOf(
                ChapterParagraphSnapshot(11, 300, "“第一段😀"),
                ChapterParagraphSnapshot(12, 309, "第二段。”")
            )
        )

        val unit = TextUnitParser.parse(snapshot).single()

        assertTrue(unit.ranges.size == 2)
        assertEquals("“第一段😀\n第二段。”", unit.text)
        assertEquals(unit.text, snapshot.textFor(unit.ranges))
        assertEquals(listOf(11, 12), unit.ranges.map { it.paragraphIndex })
        assertEquals(0, unit.ranges[0].start)
        assertEquals(snapshot.paragraphs[0].text.length, unit.ranges[0].end)
        assertEquals(0, unit.ranges[1].start)
        assertEquals(snapshot.paragraphs[1].text.length, unit.ranges[1].end)
    }

    @Test
    fun `snapshot rejects duplicate or reversed paragraph coordinates`() {
        assertRejects {
            ChapterTextSnapshot(
                paragraphs = listOf(
                    ChapterParagraphSnapshot(1, 20, "甲"),
                    ChapterParagraphSnapshot(1, 21, "乙")
                )
            )
        }
        assertRejects {
            ChapterTextSnapshot(
                paragraphs = listOf(
                    ChapterParagraphSnapshot(2, 20, "甲"),
                    ChapterParagraphSnapshot(3, 19, "乙")
                )
            )
        }
    }

    @Test
    fun `snapshot rejects chapter end before paragraph text ends`() {
        assertRejects {
            ChapterParagraphSnapshot(
                paragraphIndex = 4,
                chapterStart = 20,
                text = "四个字",
                chapterEnd = 22
            )
        }
    }

    @Test
    fun `snapshot text preserves blank paragraphs and changes when source changes`() {
        val snapshot = ChapterTextSnapshot(
            paragraphs = listOf(
                ChapterParagraphSnapshot(20, 0, "甲"),
                ChapterParagraphSnapshot(21, 1, ""),
                ChapterParagraphSnapshot(22, 2, "乙")
            )
        )

        assertEquals("甲\n\n乙", snapshot.text)
        assertEquals(
            "甲\n\n乙",
            snapshot.textFor(
                snapshot.paragraphs.map {
                    io.legado.app.help.readaloud.role.ReadAloudRoleRange(
                        it.paragraphIndex, 0, it.text.length
                    )
                }
            )
        )
        assertTrue(snapshot.textHash != snapshot.copy(
            paragraphs = snapshot.paragraphs.dropLast(1) +
                ChapterParagraphSnapshot(22, 2, "丙")
        ).textHash)
    }

    @Test
    fun `snapshot copies caller paragraph list`() {
        val source = mutableListOf(ChapterParagraphSnapshot(30, 0, "甲"))
        val snapshot = ChapterTextSnapshot(source)

        source += ChapterParagraphSnapshot(31, 1, "乙")

        assertEquals(1, snapshot.paragraphs.size)
        assertEquals("甲", snapshot.text)
    }

    @Test
    fun `units round trip whitespace separators and empty paragraphs`() {
        for (paragraphs in listOf(
            listOf("", "  \t", "“你好！”  “再见！”", "", "末尾 "),
            listOf("  ", "", "\t"),
            listOf("甲", "乙"),
            listOf("")
        )) {
            val snapshot = snapshot(paragraphs)
            val units = TextUnitParser.parse(snapshot)
            assertEquals(snapshot.text, units.joinToString("") { it.text })
            units.forEach { assertEquals(it.text, snapshot.textFor(it.ranges)) }
            assertEquals(paragraphs.joinToString("\n"), snapshot.text)
        }
    }

    @Test
    fun `long paragraphs split within 1200 UTF16 without breaking emoji or CRLF`() {
        for (text in listOf(
            "甲".repeat(1199) + "😀" + "乙".repeat(1500),
            "甲".repeat(1199) + "\r\n" + "乙".repeat(1500),
            "“" + "😀".repeat(1500) + "。”"
        )) {
            val snapshot = snapshot(listOf(text))
            val units = TextUnitParser.parse(snapshot)
            assertTrue(units.size >= 3)
            assertEquals(text, units.joinToString("") { it.text })
            units.forEach {
                assertTrue(it.text.length in 1..1200)
                assertFalse(it.text.last().isHighSurrogate())
                assertFalse(it.text.first().isLowSurrogate())
                assertEquals(it.text, snapshot.textFor(it.ranges))
            }
            units.zipWithNext().forEach { (left, right) ->
                assertFalse(left.text.endsWith("\r") && right.text.startsWith("\n"))
            }
        }
    }

    @Test
    fun `long cross paragraph quote keeps complete source ranges`() {
        val snapshot = snapshot(listOf("“" + "甲".repeat(1000), "", "乙".repeat(1500) + "。”"))
        val units = TextUnitParser.parse(snapshot)
        assertTrue(units.size >= 3)
        assertEquals(snapshot.text, units.joinToString("") { it.text })
        units.forEach {
            assertTrue(it.text.length <= 1200)
            assertEquals(it.text, snapshot.textFor(it.ranges))
        }
    }

    @Test
    fun `paragraph ending in carriage return keeps CRLF together`() {
        for (paragraphs in listOf(
            listOf("甲\r", "乙"),
            listOf("甲".repeat(1199) + "\r", "乙"),
            listOf("“你好。”\r", "", "“再见。”"),
            listOf("\r", "乙"),
            listOf("甲\r", "")
        )) {
            assertLossless(snapshot(paragraphs))
        }
    }

    @Test
    fun `every UTF16 interval round trips through empty paragraph boundaries`() {
        val snapshot = ChapterTextSnapshot(listOf(
            ChapterParagraphSnapshot(7, 10, ""),
            ChapterParagraphSnapshot(9, 20, "甲😀"),
            ChapterParagraphSnapshot(15, 30, ""),
            ChapterParagraphSnapshot(20, 40, "乙\r"),
            ChapterParagraphSnapshot(21, 50, "")
        ))
        val boundaries = (0..snapshot.text.length).filter {
            it == snapshot.text.length || !snapshot.text[it].isLowSurrogate()
        }
        for (start in boundaries) {
            for (end in boundaries.filter { it > start }) {
                assertEquals(
                    snapshot.text.substring(start, end),
                    snapshot.textFor(snapshot.rangesFor(start, end))
                )
            }
        }
    }

    @Test
    fun `seeded mixed text preserves all source characters`() {
        val random = Random(20260924)
        val tokens = listOf("甲", "😀", " ", "\t", "\r", "\n", "“", "”", "「", "」", "：", "。")
        repeat(200) {
            val paragraphs = List(random.nextInt(0, 6)) {
                List(random.nextInt(0, 24)) { tokens[random.nextInt(tokens.size)] }.joinToString("")
            }
            assertLossless(snapshot(paragraphs))
        }
    }

    @Test
    fun `malformed UTF16 and overlapping coordinates are rejected`() {
        listOf("\uD83D", "\uDE00", "甲\uD83D乙").forEach { text ->
            assertRejects { snapshot(listOf(text)) }
        }
        assertRejects {
            ChapterTextSnapshot(listOf(
                ChapterParagraphSnapshot(0, 0, "甲乙丙"),
                ChapterParagraphSnapshot(1, 1, "丁")
            ))
        }
        assertRejects { ChapterParagraphSnapshot(0, Int.MAX_VALUE, "甲") }
    }

    @Test
    fun `range cannot clip emoji duplicate skip or reverse paragraph order`() {
        val snapshot = snapshot(listOf("甲😀乙", "丙", "丁"))
        for (ranges in listOf(
            listOf(ReadAloudRoleRange(0, 0, 2)),
            listOf(ReadAloudRoleRange(0, 2, 4)),
            listOf(ReadAloudRoleRange(0, 0, 4), ReadAloudRoleRange(0, 0, 4)),
            listOf(ReadAloudRoleRange(0, 0, 4), ReadAloudRoleRange(0, 0, 1)),
            listOf(ReadAloudRoleRange(0, 0, 4), ReadAloudRoleRange(2, 0, 1)),
            listOf(ReadAloudRoleRange(0, 0, 1), ReadAloudRoleRange(1, 0, 1)),
            listOf(ReadAloudRoleRange(1, 0, 1), ReadAloudRoleRange(0, 0, 4)),
            listOf(ReadAloudRoleRange(99, 0, 1))
        )) {
            assertRejects { snapshot.textFor(ranges) }
        }
    }

    @Test
    fun `snapshot exposed paragraph list is immutable`() {
        val snapshot = snapshot(listOf("甲", "乙"))
        try {
            (snapshot.paragraphs as MutableList).clear()
        } catch (_: UnsupportedOperationException) {
            // 不可变列表拒绝修改。
        }
        assertEquals(2, snapshot.paragraphs.size)
        assertEquals("甲\n乙", snapshot.text)
    }

    @Test
    fun `same content units are distinct and deterministic without java hash collision`() {
        val first = TextUnitParser.parse(snapshot(listOf("Aa", "Aa")))
        assertEquals(first.size, first.map { it.unitId }.distinct().size)
        assertEquals(first.map { it.unitId }, TextUnitParser.parse(snapshot(listOf("Aa", "Aa"))).map { it.unitId })
        assertFalse(
            TextUnitParser.parse(snapshot(listOf("Aa"))).single().unitId ==
                TextUnitParser.parse(snapshot(listOf("BB"))).single().unitId
        )
    }

    @Test
    fun `diagnostic strings omit source text and role labels`() {
        val text = "仅限本地的正文片段"
        val snapshot = snapshot(listOf(text))
        val unit = TextUnitParser.parse(snapshot).single().copy(
            characterName = text,
            kind = text,
            roleType = text,
            reason = text
        )
        listOf(
            snapshot.toString(), snapshot.paragraphs.toString(),
            snapshot.paragraphs.single().toString(), unit.toString(), listOf(unit).toString()
        ).forEach { assertFalse(it.contains(text)) }
    }

    private fun assertLossless(snapshot: ChapterTextSnapshot) {
        val units = TextUnitParser.parse(snapshot)
        assertEquals(snapshot.text, units.joinToString("") { it.text })
        units.forEach {
            assertTrue(it.text.length in 1..1200)
            assertFalse(it.text.first().isLowSurrogate())
            assertFalse(it.text.last().isHighSurrogate())
            assertEquals(it.text, snapshot.textFor(it.ranges))
        }
        units.zipWithNext().forEach { (left, right) ->
            assertFalse(left.text.endsWith("\r") && right.text.startsWith("\n"))
        }
    }

    private fun snapshot(texts: List<String>): ChapterTextSnapshot {
        var start = 0
        return ChapterTextSnapshot(texts.mapIndexed { index, text ->
            ChapterParagraphSnapshot(index, start, text).also { start += text.length + 1 }
        })
    }

    private fun assertRejects(action: () -> Unit) {
        try {
            action()
        } catch (_: IllegalArgumentException) {
            return
        }
        throw AssertionError("expected IllegalArgumentException")
    }
}
