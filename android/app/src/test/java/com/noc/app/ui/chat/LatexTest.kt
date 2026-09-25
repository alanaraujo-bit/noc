package com.noc.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LatexTest {
    @Test fun symbols_and_scripts() {
        assertEquals("CO₂ + RuBP → 2 PGA", Latex.toUnicode("""CO_2 + RuBP \rightarrow 2 \text{PGA}"""))
        assertEquals("E = mc²", Latex.toUnicode("E = mc^2"))
        assertEquals("x₁₀ ≤ α · β", Latex.toUnicode("""x_{10} \leq \alpha \cdot \beta"""))
    }

    @Test fun fractions_and_roots() {
        assertEquals("1⁄2", Latex.toUnicode("""\frac{1}{2}"""))
        assertEquals("(a + b)/(2c)", Latex.toUnicode("""\frac{a + b}{2c}"""))
        assertEquals("√(b² - 4ac)", Latex.toUnicode("""\sqrt{b^2 - 4ac}"""))
    }

    @Test fun preprocess_blocks_inline_and_currency() {
        val md = "A reação:\n" + """\[""" + "\nCO_2 + H_2O " + """\rightarrow""" + " C_6H_{12}O_6\n" + """\]""" +
            "\ne a energia " + """\(E = h\nu\)""" + ". Custa R$ 5 e $10."
        val out = Latex.preprocess(md)
        assertTrue(out, out.contains("```fórmula\nCO₂ + H₂O → C₆H₁₂O₆\n```"))
        assertTrue(out, out.contains("`E = hν`"))
        assertTrue("moeda não pode virar fórmula: $out", out.contains("Custa R$ 5 e $10."))
    }

    @Test fun code_blocks_untouched() {
        val md = "```bash\necho \$HOME " + """\(nada\)""" + "\n```"
        assertEquals(md, Latex.preprocess(md))
    }
}
