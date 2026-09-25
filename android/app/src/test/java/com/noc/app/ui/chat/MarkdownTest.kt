package com.noc.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownTest {
    private fun parse(md: String) = MarkdownParsing.chunks(md).flatMap { MarkdownParsing.parseChunk(it) }

    private fun text(b: MdBlock.Paragraph) = b.inline.joinToString("") {
        when (it) { is Inline.Text -> it.text; is Inline.Code -> it.text; is Inline.Link -> it.text; Inline.Break -> "\n" }
    }

    @Test fun loose_list_continuation_has_no_indent() {
        val md = "1. **Primeiro**\n\n   Texto do primeiro.\n\n2. **Segundo**\n\n   Texto do segundo."
        val blocks = parse(md)
        val lists = blocks.filterIsInstance<MdBlock.ListBlock>()
        assertTrue(lists.isNotEmpty())
        val paras = lists.flatMap { l -> l.items.flatMap { it.blocks } }.filterIsInstance<MdBlock.Paragraph>()
        paras.forEach { assertFalse("indentação sobrando: '${text(it)}'", text(it).startsWith(" ")) }
        assertTrue(paras.any { text(it) == "Texto do primeiro." })
    }

    @Test fun code_fence_survives_blank_lines_and_keeps_language() {
        val md = "Veja:\n\n```python\ndef f():\n\n    return 1\n```\n\nFim."
        val code = parse(md).filterIsInstance<MdBlock.Code>().single()
        assertEquals("python", code.lang)
        assertEquals("def f():\n\n    return 1", code.code)
        assertTrue(code.closed)
    }

    @Test fun unclosed_fence_while_streaming() {
        val code = parse("```kotlin\nval x = 1\n").filterIsInstance<MdBlock.Code>().single()
        assertFalse(code.closed)
        assertEquals("val x = 1", code.code)
    }

    @Test fun inline_styles_and_links() {
        val p = parse("Um **forte**, um *itálico*, `código` e [link](https://x.dev).").single() as MdBlock.Paragraph
        assertTrue(p.inline.any { it is Inline.Text && it.bold && it.text == "forte" })
        assertTrue(p.inline.any { it is Inline.Text && it.italic && it.text == "itálico" })
        assertTrue(p.inline.any { it is Inline.Code && it.text == "código" })
        assertTrue(p.inline.any { it is Inline.Link && it.url == "https://x.dev" })
    }

    @Test fun table_parses() {
        val t = parse("| a | b |\n|---|---|\n| 1 | 2 |\n| 3 | 4 |").single() as MdBlock.Table
        assertEquals(2, t.header.size)
        assertEquals(2, t.rows.size)
    }

    @Test fun headings_and_rule() {
        val b = parse("# Título\n\n---\n\n## Sub")
        assertEquals(1, (b[0] as MdBlock.Heading).level)
        assertTrue(b[1] is MdBlock.Rule)
        assertEquals(2, (b[2] as MdBlock.Heading).level)
    }

    @Test fun huge_text_parses_fast() {
        val sb = StringBuilder()
        repeat(400) { i -> sb.append("## Seção $i\n\nParágrafo com **negrito** e `code` número $i.\n\n- item a\n- item b\n\n```js\nconsole.log($i)\n```\n\n") }
        val t0 = System.nanoTime()
        val blocks = parse(sb.toString())
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertTrue(blocks.size >= 1600)
        assertTrue("parse lento: $ms ms", ms < 3000)
    }

    @Test fun plain_text_strips_markup() {
        val plain = MarkdownParsing.plainText("## Título\n\nUm **forte** e `code`.\n\n- a\n- b")
        assertEquals("Título\n\nUm forte e code.\n\n• a\n• b", plain)
    }
}
