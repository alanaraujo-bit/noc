package com.noc.app.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.noc.app.ui.components.pressable
import com.noc.app.ui.theme.GeistMono
import com.noc.app.ui.theme.MonoSmall
import com.noc.app.ui.theme.MonoStyle
import com.noc.app.ui.theme.Noc
import com.noc.app.ui.theme.NocColors
import dev.snipme.highlights.Highlights
import dev.snipme.highlights.model.BoldHighlight
import dev.snipme.highlights.model.ColorHighlight
import dev.snipme.highlights.model.SyntaxLanguage
import dev.snipme.highlights.model.SyntaxTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.ast.getTextInNode
import org.intellij.markdown.flavours.gfm.GFMElementTypes
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.flavours.gfm.GFMTokenTypes
import org.intellij.markdown.parser.MarkdownParser

// ------------------------------------------------------------------ modelo de blocos

@Immutable
sealed interface MdBlock {
    data class Paragraph(val inline: List<Inline>) : MdBlock
    data class Heading(val level: Int, val inline: List<Inline>) : MdBlock
    data class Code(val lang: String, val code: String, val closed: Boolean) : MdBlock
    data class Quote(val blocks: List<MdBlock>) : MdBlock
    data class ListBlock(val ordered: Boolean, val start: Int, val items: List<ListItem>) : MdBlock
    data class Table(val header: List<List<Inline>>, val rows: List<List<List<Inline>>>) : MdBlock
    data object Rule : MdBlock
}

data class ListItem(val task: Boolean?, val blocks: List<MdBlock>)

/** Trecho inline independente de tema (as cores entram só na renderização). */
sealed interface Inline {
    data class Text(val text: String, val bold: Boolean = false, val italic: Boolean = false, val strike: Boolean = false) : Inline
    data class Code(val text: String) : Inline
    data class Link(val text: String, val url: String) : Inline
    data object Break : Inline
}

// ------------------------------------------------------------------ parsing incremental

object MarkdownParsing {
    private val parser = MarkdownParser(GFMFlavourDescriptor())
    private val cache = object : LinkedHashMap<String, List<MdBlock>>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<MdBlock>>?) = size > 600
    }

    /**
     * Divide o texto em trechos de nível superior (separados por linha em branco fora de blocos de código).
     * Cada trecho é analisado e guardado em cache: durante o streaming, só o último trecho muda.
     */
    fun chunks(text: String): List<String> {
        val out = ArrayList<String>()
        val cur = StringBuilder()
        var fence: String? = null
        val lines = text.split('\n')
        for ((i, line) in lines.withIndex()) {
            val trimmed = line.trimStart()
            if (fence == null && (trimmed.startsWith("```") || trimmed.startsWith("~~~"))) {
                fence = trimmed.take(3)
            } else if (fence != null && trimmed.startsWith(fence)) {
                fence = null
            }
            val blank = line.isBlank()
            if (blank && fence == null && cur.isNotEmpty()) {
                // continuação indentada (itens de lista "soltos") não pode ser separada
                val next = lines.drop(i + 1).firstOrNull { it.isNotBlank() }
                val continues = next != null && (next.startsWith("  ") || next.startsWith("\t"))
                if (!continues) {
                    out.add(cur.toString())
                    cur.clear()
                    continue
                }
            }
            if (cur.isNotEmpty() || !blank) {
                if (cur.isNotEmpty()) cur.append('\n')
                cur.append(line)
            }
        }
        if (cur.isNotBlank()) out.add(cur.toString())
        return out
    }

    /** Texto limpo, sem marcação Markdown (para a folha de seleção de texto). */
    fun plainText(md: String): String = chunks(Latex.preprocess(md)).flatMap { parseChunk(it) }.joinToString("\n\n") { plain(it) }

    private fun plain(b: MdBlock): String = when (b) {
        is MdBlock.Paragraph -> plainInline(b.inline)
        is MdBlock.Heading -> plainInline(b.inline)
        is MdBlock.Code -> b.code
        is MdBlock.Quote -> b.blocks.joinToString("\n") { plain(it) }
        is MdBlock.ListBlock -> b.items.mapIndexed { i, item ->
            val marker = when (item.task) { true -> "☑ "; false -> "☐ "; null -> if (b.ordered) "${b.start + i}. " else "• " }
            marker + item.blocks.joinToString("\n") { plain(it) }
        }.joinToString("\n")
        is MdBlock.Table -> (listOf(b.header) + b.rows).joinToString("\n") { row -> row.joinToString("\t") { plainInline(it) } }
        MdBlock.Rule -> "—"
    }

    private fun plainInline(l: List<Inline>) = l.joinToString("") {
        when (it) { is Inline.Text -> it.text; is Inline.Code -> it.text; is Inline.Link -> it.text; Inline.Break -> "\n" }
    }

    fun parseChunk(chunk: String): List<MdBlock> {
        synchronized(cache) { cache[chunk] }?.let { return it }
        val blocks = try {
            val root = parser.buildMarkdownTreeFromString(chunk)
            root.children.mapNotNull { block(it, chunk) }
        } catch (e: Throwable) {
            listOf(MdBlock.Paragraph(listOf(Inline.Text(chunk))))
        }
        synchronized(cache) { cache[chunk] = blocks }
        return blocks
    }

    private fun block(n: ASTNode, src: String): MdBlock? = when (n.type) {
        MarkdownElementTypes.PARAGRAPH -> inlines(n, src).takeIf { l -> l.any { it !is Inline.Break && !(it is Inline.Text && it.text.isBlank()) } }?.let { MdBlock.Paragraph(it) }
        MarkdownElementTypes.ATX_1, MarkdownElementTypes.SETEXT_1 -> MdBlock.Heading(1, headingInlines(n, src))
        MarkdownElementTypes.ATX_2, MarkdownElementTypes.SETEXT_2 -> MdBlock.Heading(2, headingInlines(n, src))
        MarkdownElementTypes.ATX_3 -> MdBlock.Heading(3, headingInlines(n, src))
        MarkdownElementTypes.ATX_4, MarkdownElementTypes.ATX_5, MarkdownElementTypes.ATX_6 -> MdBlock.Heading(4, headingInlines(n, src))
        MarkdownElementTypes.CODE_FENCE -> codeFence(n, src)
        MarkdownElementTypes.CODE_BLOCK -> MdBlock.Code("", n.getTextInNode(src).toString().lines().joinToString("\n") { it.removePrefix("    ") }.trimEnd(), true)
        MarkdownElementTypes.BLOCK_QUOTE -> MdBlock.Quote(n.children.mapNotNull { block(it, src) })
        MarkdownElementTypes.ORDERED_LIST -> list(n, src, true)
        MarkdownElementTypes.UNORDERED_LIST -> list(n, src, false)
        GFMElementTypes.TABLE -> table(n, src)
        MarkdownTokenTypes.HORIZONTAL_RULE -> MdBlock.Rule
        MarkdownTokenTypes.EOL, MarkdownTokenTypes.WHITE_SPACE, MarkdownTokenTypes.BLOCK_QUOTE -> null
        MarkdownElementTypes.HTML_BLOCK -> MdBlock.Paragraph(listOf(Inline.Text(n.getTextInNode(src).toString())))
        else -> {
            val t = n.getTextInNode(src).toString()
            if (t.isBlank()) null else MdBlock.Paragraph(listOf(Inline.Text(t)))
        }
    }

    private fun headingInlines(n: ASTNode, src: String): List<Inline> {
        val content = n.children.firstOrNull { it.type == MarkdownTokenTypes.ATX_CONTENT || it.type == MarkdownTokenTypes.SETEXT_CONTENT }
        return if (content != null) inlines(content, src).trimLeadingSpace() else listOf(Inline.Text(n.getTextInNode(src).toString().trimStart('#', ' ')))
    }

    private fun List<Inline>.trimLeadingSpace(): List<Inline> {
        val first = firstOrNull() as? Inline.Text ?: return this
        return listOf(first.copy(text = first.text.trimStart())) + drop(1)
    }

    private fun codeFence(n: ASTNode, src: String): MdBlock.Code {
        val lang = n.children.firstOrNull { it.type == MarkdownTokenTypes.FENCE_LANG }?.getTextInNode(src)?.toString()?.trim() ?: ""
        val lines = n.children.filter { it.type == MarkdownTokenTypes.CODE_FENCE_CONTENT || it.type == MarkdownTokenTypes.EOL }
        val sb = StringBuilder()
        var started = false
        for (c in lines) {
            if (c.type == MarkdownTokenTypes.EOL) {
                if (started) sb.append('\n')
                started = true
            } else {
                sb.append(c.getTextInNode(src))
                started = true
            }
        }
        val closed = n.children.count { it.type == MarkdownTokenTypes.CODE_FENCE_START || it.type == MarkdownTokenTypes.CODE_FENCE_END } >= 2
        return MdBlock.Code(lang, sb.toString().trim('\n'), closed)
    }

    private fun list(n: ASTNode, src: String, ordered: Boolean): MdBlock.ListBlock {
        val items = n.children.filter { it.type == MarkdownElementTypes.LIST_ITEM }
        val start = if (ordered) items.firstOrNull()?.children?.firstOrNull { it.type == MarkdownTokenTypes.LIST_NUMBER }
            ?.getTextInNode(src)?.toString()?.trim()?.trimEnd('.', ')')?.toIntOrNull() ?: 1 else 1
        return MdBlock.ListBlock(ordered, start, items.map { item ->
            var task: Boolean? = null
            val blocks = item.children.mapNotNull { c ->
                if (c.type == GFMTokenTypes.CHECK_BOX) {
                    task = c.getTextInNode(src).contains('x', ignoreCase = true); null
                } else if (c.type == MarkdownTokenTypes.LIST_BULLET || c.type == MarkdownTokenTypes.LIST_NUMBER) null
                else block(c, src)
            }
            ListItem(task, blocks)
        })
    }

    private fun table(n: ASTNode, src: String): MdBlock.Table {
        fun cells(row: ASTNode) = row.children.filter { it.type == GFMTokenTypes.CELL }.map { inlines(it, src).trimLeadingSpace() }
        val header = n.children.firstOrNull { it.type == GFMElementTypes.HEADER }?.let { cells(it) } ?: emptyList()
        val rows = n.children.filter { it.type == GFMElementTypes.ROW }.map { cells(it) }
        return MdBlock.Table(header, rows)
    }

    private data class Fmt(val bold: Boolean = false, val italic: Boolean = false, val strike: Boolean = false)

    private fun inlines(n: ASTNode, src: String): List<Inline> {
        val out = ArrayList<Inline>()
        fun text(s: String, f: Fmt) {
            if (s.isEmpty()) return
            val last = out.lastOrNull()
            if (last is Inline.Text && last.bold == f.bold && last.italic == f.italic && last.strike == f.strike) {
                out[out.size - 1] = last.copy(text = last.text + s)
            } else out.add(Inline.Text(s, f.bold, f.italic, f.strike))
        }
        fun walk(node: ASTNode, f: Fmt) {
            when (node.type) {
                MarkdownElementTypes.STRONG -> node.children.forEach { if (it.type != MarkdownTokenTypes.EMPH) walk(it, f.copy(bold = true)) }
                MarkdownElementTypes.EMPH -> node.children.forEach { if (it.type != MarkdownTokenTypes.EMPH) walk(it, f.copy(italic = true)) }
                GFMElementTypes.STRIKETHROUGH -> node.children.forEach { if (it.type != GFMTokenTypes.TILDE) walk(it, f.copy(strike = true)) }
                MarkdownElementTypes.CODE_SPAN -> {
                    val raw = node.getTextInNode(src).toString()
                    out.add(Inline.Code(raw.trim('`').let { if (it.startsWith(' ') && it.endsWith(' ') && it.length > 1) it.substring(1, it.length - 1) else it }))
                }
                MarkdownElementTypes.INLINE_LINK -> {
                    val label = node.children.firstOrNull { it.type == MarkdownElementTypes.LINK_TEXT }
                        ?.getTextInNode(src)?.toString()?.removePrefix("[")?.removeSuffix("]") ?: ""
                    val url = node.children.firstOrNull { it.type == MarkdownElementTypes.LINK_DESTINATION }?.getTextInNode(src)?.toString() ?: ""
                    out.add(Inline.Link(label.ifEmpty { url }, url))
                }
                MarkdownElementTypes.AUTOLINK -> {
                    val url = node.getTextInNode(src).toString().trim('<', '>')
                    out.add(Inline.Link(url, url))
                }
                GFMTokenTypes.GFM_AUTOLINK -> {
                    val url = node.getTextInNode(src).toString()
                    out.add(Inline.Link(url, if (url.startsWith("www.")) "https://$url" else url))
                }
                MarkdownElementTypes.IMAGE -> text("[imagem]", f)
                MarkdownTokenTypes.EOL -> out.add(Inline.Break)
                MarkdownTokenTypes.HARD_LINE_BREAK -> out.add(Inline.Break)
                MarkdownTokenTypes.ESCAPED_BACKTICKS -> text(node.getTextInNode(src).toString().replace("\\`", "`"), f)
                MarkdownTokenTypes.WHITE_SPACE -> {
                    // indentação no começo da linha (continuação de itens de lista) não é conteúdo
                    val atLineStart = out.isEmpty() || out.last() == Inline.Break
                    if (!atLineStart) text(node.getTextInNode(src).toString(), f)
                }
                else -> {
                    if (node.children.isEmpty()) {
                        val t = node.getTextInNode(src).toString()
                        text(if (t.length == 2 && t[0] == '\\') t.substring(1) else t, f)
                    } else node.children.forEach { walk(it, f) }
                }
            }
        }
        n.children.forEach { walk(it, Fmt()) }
        // remove quebras nas pontas
        while (out.firstOrNull() == Inline.Break) out.removeAt(0)
        while (out.lastOrNull() == Inline.Break) out.removeAt(out.size - 1)
        return out
    }
}

// ------------------------------------------------------------------ renderização

@Composable
fun MarkdownText(text: String, modifier: Modifier = Modifier, streaming: Boolean = false, textColor: Color = Noc.colors.text) {
    val chunks = remember(text) { MarkdownParsing.chunks(Latex.preprocess(text)) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        chunks.forEachIndexed { i, chunk ->
            val blocks = remember(chunk) { MarkdownParsing.parseChunk(chunk) }
            val last = i == chunks.lastIndex
            blocks.forEachIndexed { j, b ->
                Block(b, textColor, streaming = streaming && last && j == blocks.lastIndex)
            }
        }
    }
}

@Composable
private fun Block(b: MdBlock, color: Color, streaming: Boolean) {
    val c = Noc.colors
    val body = MaterialTheme.typography.bodyLarge.copy(color = color)
    when (b) {
        is MdBlock.Paragraph -> RichText(b.inline, body)
        is MdBlock.Heading -> {
            val style = when (b.level) {
                1 -> MaterialTheme.typography.headlineMedium
                2 -> MaterialTheme.typography.headlineSmall
                3 -> MaterialTheme.typography.titleLarge
                else -> MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
            }
            RichText(b.inline, style.copy(color = color), Modifier.padding(top = if (b.level <= 2) 6.dp else 2.dp))
        }
        is MdBlock.Code -> if (b.lang == "fórmula") FormulaBlock(b.code) else CodeBlock(b.lang, b.code, streaming = streaming || !b.closed)
        is MdBlock.Quote -> Row(Modifier.fillMaxWidth()) {
            Column(
                Modifier
                    .drawLeftBar(c.line)
                    .padding(start = 14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) { b.blocks.forEach { Block(it, c.text2, false) } }
        }
        is MdBlock.ListBlock -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            b.items.forEachIndexed { i, item ->
                Row {
                    val marker = when {
                        item.task == true -> "☑"
                        item.task == false -> "☐"
                        b.ordered -> "${b.start + i}."
                        else -> "•"
                    }
                    Text(
                        marker, style = body.copy(color = if (b.ordered) c.text2 else c.text3, fontFeatureSettings = "tnum"),
                        modifier = Modifier.widthIn(min = if (b.ordered) 26.dp else 18.dp).padding(end = 6.dp),
                    )
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        item.blocks.forEach { Block(it, color, false) }
                    }
                }
            }
        }
        is MdBlock.Table -> TableBlock(b)
        MdBlock.Rule -> Box(Modifier.fillMaxWidth().padding(vertical = 6.dp).height(1.dp).background(c.line))
    }
}

private fun Modifier.drawLeftBar(color: Color) = this.drawBehind {
    drawRect(color, size = androidx.compose.ui.geometry.Size(3.dp.toPx(), size.height))
}

@Composable
fun RichText(inline: List<Inline>, style: TextStyle, modifier: Modifier = Modifier) {
    val c = Noc.colors
    val annotated = remember(inline, c) { toAnnotated(inline, c) }
    Text(annotated, style = style, modifier = modifier)
}

fun toAnnotated(inline: List<Inline>, c: NocColors): AnnotatedString = buildAnnotatedString {
    val linkStyles = TextLinkStyles(SpanStyle(color = c.accent, textDecoration = TextDecoration.Underline))
    inline.forEach { i ->
        when (i) {
            is Inline.Text -> withStyle(
                SpanStyle(
                    fontWeight = if (i.bold) FontWeight.SemiBold else null,
                    fontStyle = if (i.italic) FontStyle.Italic else null,
                    textDecoration = if (i.strike) TextDecoration.LineThrough else null,
                ),
            ) { append(i.text) }
            is Inline.Code -> withStyle(SpanStyle(fontFamily = GeistMono, fontSize = 0.88.em(), background = c.surface2, color = c.text)) {
                append(' '); append(i.text); append(' ')
            }
            is Inline.Link -> withLink(LinkAnnotation.Url(i.url, linkStyles)) { append(i.text) }
            Inline.Break -> append('\n')
        }
    }
}

private fun Double.em() = androidx.compose.ui.unit.TextUnit(this.toFloat(), androidx.compose.ui.unit.TextUnitType.Em)

// ------------------------------------------------------------------ código

private val langMap = mapOf(
    "kotlin" to SyntaxLanguage.KOTLIN, "kt" to SyntaxLanguage.KOTLIN, "kts" to SyntaxLanguage.KOTLIN,
    "java" to SyntaxLanguage.JAVA, "c" to SyntaxLanguage.C, "h" to SyntaxLanguage.C,
    "cpp" to SyntaxLanguage.CPP, "c++" to SyntaxLanguage.CPP, "cc" to SyntaxLanguage.CPP, "hpp" to SyntaxLanguage.CPP,
    "cs" to SyntaxLanguage.CSHARP, "csharp" to SyntaxLanguage.CSHARP, "c#" to SyntaxLanguage.CSHARP,
    "dart" to SyntaxLanguage.DART, "rust" to SyntaxLanguage.RUST, "rs" to SyntaxLanguage.RUST,
    "js" to SyntaxLanguage.JAVASCRIPT, "javascript" to SyntaxLanguage.JAVASCRIPT, "jsx" to SyntaxLanguage.JAVASCRIPT,
    "json" to SyntaxLanguage.JAVASCRIPT, "ts" to SyntaxLanguage.TYPESCRIPT, "typescript" to SyntaxLanguage.TYPESCRIPT,
    "tsx" to SyntaxLanguage.TYPESCRIPT, "py" to SyntaxLanguage.PYTHON, "python" to SyntaxLanguage.PYTHON,
    "rb" to SyntaxLanguage.RUBY, "ruby" to SyntaxLanguage.RUBY, "sh" to SyntaxLanguage.SHELL, "bash" to SyntaxLanguage.SHELL,
    "shell" to SyntaxLanguage.SHELL, "zsh" to SyntaxLanguage.SHELL, "powershell" to SyntaxLanguage.SHELL, "ps1" to SyntaxLanguage.SHELL,
    "swift" to SyntaxLanguage.SWIFT, "go" to SyntaxLanguage.GO, "golang" to SyntaxLanguage.GO, "php" to SyntaxLanguage.PHP,
    "perl" to SyntaxLanguage.PERL, "coffee" to SyntaxLanguage.COFFEESCRIPT,
)

private fun syntaxTheme(dark: Boolean): SyntaxTheme = if (dark) {
    SyntaxTheme("noc-dark", code = 0xE6E3DD, keyword = 0xF0A070, string = 0xA8CC8C, literal = 0xD7A6F2,
        comment = 0x7E7A72, metadata = 0x8FB8E0, multilineComment = 0x7E7A72, punctuation = 0xB5B1AA, mark = 0xF0A070)
} else {
    SyntaxTheme("noc-light", code = 0x24221F, keyword = 0xB0470F, string = 0x3B7A2A, literal = 0x7A3EA6,
        comment = 0x938E85, metadata = 0x2F66A3, multilineComment = 0x938E85, punctuation = 0x5C5852, mark = 0xB0470F)
}

private fun highlight(code: String, lang: String, dark: Boolean): AnnotatedString {
    val language = langMap[lang.lowercase()] ?: SyntaxLanguage.DEFAULT
    val hl = Highlights.Builder().code(code).language(language).theme(syntaxTheme(dark)).build().getHighlights()
    return buildAnnotatedString {
        append(code)
        hl.forEach { h ->
            val s = h.location.start.coerceIn(0, code.length)
            val e = h.location.end.coerceIn(s, code.length)
            when (h) {
                is ColorHighlight -> addStyle(SpanStyle(color = Color(0xFF000000 or h.rgb.toLong())), s, e)
                is BoldHighlight -> addStyle(SpanStyle(fontWeight = FontWeight.Medium), s, e)
            }
        }
    }
}

@Composable
fun CodeBlock(lang: String, code: String, streaming: Boolean) {
    val c = Noc.colors
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var copied by remember { mutableStateOf(false) }
    var highlighted by remember { mutableStateOf<AnnotatedString?>(null) }
    LaunchedEffect(code, c.isDark) {
        if (streaming) delay(150) // agrupa atualizações durante o streaming
        val r = withContext(Dispatchers.Default) { runCatching { highlight(code, lang, c.isDark) }.getOrNull() }
        if (r != null) highlighted = r
    }
    val shown = highlighted?.let { h ->
        when {
            h.text == code -> h
            code.startsWith(h.text) -> buildAnnotatedString { append(h); append(code.substring(h.text.length)) }
            else -> null
        }
    } ?: AnnotatedString(code)

    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.codeBg)) {
        Row(
            Modifier.fillMaxWidth().padding(start = 14.dp, end = 4.dp, top = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(lang.ifBlank { "código" }, style = MonoSmall, color = c.text3, modifier = Modifier.weight(1f))
            Row(
                Modifier.clip(RoundedCornerShape(8.dp)).pressable {
                    clipboard.setText(AnnotatedString(code))
                    copied = true
                    scope.launch { delay(1600); copied = false }
                }.padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(if (copied) Icons.Rounded.Check else Icons.Rounded.ContentCopy, null, tint = if (copied) c.ok else c.text3, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(6.dp))
                Text(if (copied) "Copiado" else "Copiar", style = MonoSmall, color = if (copied) c.ok else c.text3)
            }
        }
        Box(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            Text(shown, style = MonoStyle.copy(color = c.text), softWrap = false, modifier = Modifier.padding(start = 14.dp, end = 14.dp, bottom = 14.dp, top = 2.dp))
        }
    }
}

// ------------------------------------------------------------------ tabela

@Composable
private fun TableBlock(t: MdBlock.Table) {
    val c = Noc.colors
    val style = MaterialTheme.typography.bodyMedium.copy(color = c.text)
    val cols = maxOf(t.header.size, t.rows.maxOfOrNull { it.size } ?: 0)
    if (cols == 0) return
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(c.surface)
            .horizontalScroll(rememberScrollState()),
    ) {
        val allRows = listOf(t.header) + t.rows
        Layout(
            content = {
                allRows.forEachIndexed { r, row ->
                    for (col in 0 until cols) {
                        val cell = row.getOrNull(col) ?: emptyList()
                        Box(
                            Modifier
                                .background(if (r == 0) c.surface2 else if (r % 2 == 0) c.bg.copy(alpha = 0.5f) else Color.Transparent)
                                .padding(horizontal = 12.dp, vertical = 9.dp),
                        ) {
                            RichText(cell, if (r == 0) style.copy(fontWeight = FontWeight.SemiBold) else style)
                        }
                    }
                }
            },
        ) { measurables, constraints ->
            val maxCell = 260.dp.roundToPx()
            val rows = allRows.size
            // intrínsecos primeiro (cada célula é medida uma única vez, já no tamanho final)
            val widths = IntArray(cols) { col ->
                (0 until rows).maxOf { r -> measurables[r * cols + col].maxIntrinsicWidth(Constraints.Infinity).coerceAtMost(maxCell) }
            }
            val heights = IntArray(rows) { r ->
                (0 until cols).maxOf { col -> measurables[r * cols + col].minIntrinsicHeight(widths[col]) }
            }
            val finals = measurables.mapIndexed { i, m ->
                val r = i / cols
                val col = i % cols
                m.measure(Constraints.fixed(widths[col], heights[r]))
            }
            val totalW = widths.sum().coerceAtLeast(constraints.minWidth)
            val totalH = heights.sum()
            layout(totalW, totalH) {
                var y = 0
                for (r in 0 until rows) {
                    var x = 0
                    for (col in 0 until cols) {
                        finals[r * cols + col].place(x, y)
                        x += widths[col]
                    }
                    y += heights[r]
                }
            }
        }
    }
}


/** Fórmula em destaque (vinda de \[ … \] ou $$ … $$), já convertida para Unicode. */
@Composable
private fun FormulaBlock(text: String) {
    val c = Noc.colors
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(c.surface)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 14.dp),
    ) {
        Text(text, style = MaterialTheme.typography.titleMedium.copy(fontFamily = com.noc.app.ui.theme.Serif, fontSize = androidx.compose.ui.unit.TextUnit(20f, androidx.compose.ui.unit.TextUnitType.Sp)), color = c.text, softWrap = false)
    }
}
