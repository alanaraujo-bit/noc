package com.noc.app.ui.chat

/**
 * LaTeX leve → Unicode. Modelos escrevem fórmulas como \( … \), $ … $, \[ … \] e $$ … $$;
 * sem isso o usuário veria "\rightarrow" e "CO_2" crus. Não é um motor TeX: cobre o uso comum
 * (setas, operadores, letras gregas, índices, frações, raízes, \text{}).
 */
object Latex {
    private val symbols = mapOf(
        "rightarrow" to "→", "to" to "→", "leftarrow" to "←", "gets" to "←", "Rightarrow" to "⇒", "Leftarrow" to "⇐",
        "leftrightarrow" to "↔", "Leftrightarrow" to "⇔", "rightleftharpoons" to "⇌", "longrightarrow" to "⟶", "implies" to "⟹",
        "iff" to "⟺", "mapsto" to "↦", "uparrow" to "↑", "downarrow" to "↓",
        "times" to "×", "cdot" to "·", "div" to "÷", "pm" to "±", "mp" to "∓", "ast" to "∗", "star" to "⋆", "circ" to "∘",
        "leq" to "≤", "le" to "≤", "geq" to "≥", "ge" to "≥", "neq" to "≠", "ne" to "≠", "approx" to "≈", "sim" to "∼",
        "simeq" to "≃", "equiv" to "≡", "propto" to "∝", "ll" to "≪", "gg" to "≫",
        "infty" to "∞", "sum" to "∑", "prod" to "∏", "int" to "∫", "oint" to "∮", "partial" to "∂", "nabla" to "∇",
        "in" to "∈", "notin" to "∉", "subset" to "⊂", "subseteq" to "⊆", "supset" to "⊃", "cup" to "∪", "cap" to "∩",
        "emptyset" to "∅", "varnothing" to "∅", "forall" to "∀", "exists" to "∃", "neg" to "¬", "land" to "∧", "lor" to "∨",
        "angle" to "∠", "perp" to "⊥", "parallel" to "∥", "degree" to "°", "prime" to "′", "ldots" to "…", "cdots" to "⋯",
        "dots" to "…", "therefore" to "∴", "because" to "∵", "hbar" to "ℏ", "ell" to "ℓ", "Re" to "ℜ", "Im" to "ℑ",
        "alpha" to "α", "beta" to "β", "gamma" to "γ", "delta" to "δ", "epsilon" to "ε", "varepsilon" to "ε", "zeta" to "ζ",
        "eta" to "η", "theta" to "θ", "vartheta" to "ϑ", "iota" to "ι", "kappa" to "κ", "lambda" to "λ", "mu" to "μ",
        "nu" to "ν", "xi" to "ξ", "pi" to "π", "varpi" to "ϖ", "rho" to "ρ", "sigma" to "σ", "tau" to "τ", "upsilon" to "υ",
        "phi" to "φ", "varphi" to "φ", "chi" to "χ", "psi" to "ψ", "omega" to "ω",
        "Gamma" to "Γ", "Delta" to "Δ", "Theta" to "Θ", "Lambda" to "Λ", "Xi" to "Ξ", "Pi" to "Π", "Sigma" to "Σ",
        "Upsilon" to "Υ", "Phi" to "Φ", "Psi" to "Ψ", "Omega" to "Ω",
        "quad" to "  ", "qquad" to "    ", "," to " ", ";" to " ", ":" to " ", "!" to "", " " to " ",
        "left" to "", "right" to "", "big" to "", "Big" to "", "bigl" to "", "bigr" to "", "displaystyle" to "",
        "%" to "%", "$" to "$", "{" to "{", "}" to "}", "_" to "_", "&" to "&", "#" to "#",
        "sin" to "sin", "cos" to "cos", "tan" to "tan", "log" to "log", "ln" to "ln", "exp" to "exp", "lim" to "lim",
        "max" to "max", "min" to "min",
    )

    private val sup = mapOf(
        '0' to '⁰', '1' to '¹', '2' to '²', '3' to '³', '4' to '⁴', '5' to '⁵', '6' to '⁶', '7' to '⁷', '8' to '⁸', '9' to '⁹',
        '+' to '⁺', '-' to '⁻', '=' to '⁼', '(' to '⁽', ')' to '⁾', 'n' to 'ⁿ', 'i' to 'ⁱ', 'x' to 'ˣ', 'y' to 'ʸ',
        'a' to 'ᵃ', 'b' to 'ᵇ', 'c' to 'ᶜ', 'd' to 'ᵈ', 'e' to 'ᵉ', 'k' to 'ᵏ', 'm' to 'ᵐ', 't' to 'ᵗ', 'T' to 'ᵀ',
        '∘' to '°', '°' to '°',
    )
    private val sub = mapOf(
        '0' to '₀', '1' to '₁', '2' to '₂', '3' to '₃', '4' to '₄', '5' to '₅', '6' to '₆', '7' to '₇', '8' to '₈', '9' to '₉',
        '+' to '₊', '-' to '₋', '=' to '₌', '(' to '₍', ')' to '₎', 'a' to 'ₐ', 'e' to 'ₑ', 'i' to 'ᵢ', 'j' to 'ⱼ', 'k' to 'ₖ',
        'm' to 'ₘ', 'n' to 'ₙ', 'o' to 'ₒ', 'p' to 'ₚ', 'r' to 'ᵣ', 's' to 'ₛ', 't' to 'ₜ', 'x' to 'ₓ',
    )

    /** Converte uma expressão LaTeX para texto Unicode legível. */
    fun toUnicode(src: String): String {
        val p = Parser(src)
        return p.parseAll().replace(Regex("[ \\t]{2,}"), " ").trim()
    }

    private class Parser(val s: String) {
        var i = 0

        fun parseAll(): String {
            val sb = StringBuilder()
            while (i < s.length) sb.append(atom())
            return sb.toString()
        }

        /** Um grupo {…} ou um único átomo. */
        fun group(): String {
            skipSpaces()
            if (i < s.length && s[i] == '{') {
                i++
                val sb = StringBuilder()
                var depth = 1
                while (i < s.length) {
                    if (s[i] == '}') { depth--; if (depth == 0) { i++; break } }
                    if (s[i] == '{') { depth++; i++; sb.append('{'); continue }
                    sb.append(atom())
                }
                return sb.toString()
            }
            return if (i < s.length) atom() else ""
        }

        fun skipSpaces() { while (i < s.length && s[i] == ' ') i++ }

        fun atom(): String {
            val c = s[i]
            return when (c) {
                '\\' -> command()
                '^' -> { i++; script(group(), sup, "^") }
                '_' -> { i++; script(group(), sub, "_") }
                '{' -> group()
                '}' -> { i++; "" }
                '&' -> { i++; " " }
                '~' -> { i++; " " }
                else -> { i++; c.toString() }
            }
        }

        fun command(): String {
            i++ // '\'
            if (i >= s.length) return ""
            if (!s[i].isLetter()) {
                val sym = s[i].toString(); i++
                return if (sym == "\\") "\n" else symbols[sym] ?: sym
            }
            val start = i
            while (i < s.length && s[i].isLetter()) i++
            val name = s.substring(start, i)
            return when (name) {
                "frac", "dfrac", "tfrac" -> {
                    val a = group(); val b = group()
                    val simple = { t: String -> t.length <= 3 && t.none { it == ' ' || it == '+' || it == '-' } }
                    if (simple(a) && simple(b)) "$a⁄$b" else "($a)/($b)"
                }
                "sqrt" -> {
                    var n = ""
                    skipSpaces()
                    if (i < s.length && s[i] == '[') { val e = s.indexOf(']', i); if (e > 0) { n = s.substring(i + 1, e); i = e + 1 } }
                    val x = group()
                    val root = when (n) { "3" -> "∛"; "4" -> "∜"; else -> "√" }
                    if (x.length <= 2) "$root$x" else "$root($x)"
                }
                "text", "mathrm", "mathbf", "textbf", "mathit", "textit", "operatorname", "mathsf", "mathtt", "boldsymbol", "mathcal", "mathbb" -> group()
                "vec" -> group() + "⃗"
                "hat" -> group() + "̂"
                "bar", "overline" -> group() + "̄"
                "dot" -> group() + "̇"
                "begin", "end" -> { group(); "" }
                else -> symbols[name] ?: name
            }
        }

        fun script(t: String, table: Map<Char, Char>, mark: String): String =
            if (t.isNotEmpty() && t.all { it in table }) t.map { table[it]!! }.joinToString("")
            else if (t.length == 1) "$mark$t" else "$mark($t)"
    }

    // ------------------------------------------------------------------ pré-processamento do Markdown

    private val displayBracket = Regex("""\\\[(.+?)\\\]""", RegexOption.DOT_MATCHES_ALL)
    private val displayDollar = Regex("""\$\$(.+?)\$\$""", RegexOption.DOT_MATCHES_ALL)
    private val inlineParen = Regex("""\\\((.+?)\\\)""")
    // $…$ ao estilo pandoc: abre sem espaço depois, fecha sem espaço antes e sem dígito depois (evita "R$ 5 e $10").
    private val inlineDollar = Regex("""(?<![\\$\w])\$(?=\S)([^$\n]+?)(?<=\S)\$(?!\d)""")

    /**
     * Troca fórmulas por texto Unicode antes do parse do Markdown: blocos viram ```fórmula``` e
     * fórmulas inline viram `código`. Blocos de código existentes não são tocados.
     */
    fun preprocess(md: String): String {
        if (!md.contains('\\') && !md.contains('$')) return md
        val out = StringBuilder()
        // separa trechos de código (``` … ```) para não mexer neles
        val parts = md.split("```")
        parts.forEachIndexed { idx, part ->
            if (idx > 0) out.append("```")
            if (idx % 2 == 1) { out.append(part); return@forEachIndexed }
            var t = part
            t = displayBracket.replace(t) { m -> "\n```fórmula\n" + toUnicode(m.groupValues[1]) + "\n```\n" }
            t = displayDollar.replace(t) { m -> "\n```fórmula\n" + toUnicode(m.groupValues[1]) + "\n```\n" }
            t = t.split('`').mapIndexed { j, seg ->
                if (j % 2 == 1) seg
                else {
                    var x = inlineParen.replace(seg) { m -> "`" + toUnicode(m.groupValues[1]) + "`" }
                    x = inlineDollar.replace(x) { m -> "`" + toUnicode(m.groupValues[1]) + "`" }
                    x
                }
            }.joinToString("`")
            out.append(t)
        }
        return out.toString()
    }
}
