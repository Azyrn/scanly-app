package com.skeler.scanely.core.text

/**
 * Best-effort LaTeX -> Unicode so an equation reads as math without a math engine:
 * `\frac{a}{b}` -> a/b, `x^{2}` -> x², `\alpha` -> α. Unknown commands keep their name.
 */
object MathText {

    fun prettify(latex: String): String {
        var text = latex.trim()
            .replace("\\left", "")
            .replace("\\right", "")
            .replace("\\\\", "\n")
            .replace("^\\circ", "°")

        text = WRAPPER.replace(text) { it.groupValues[2] }

        // Innermost first; the loop unwraps nesting one layer per pass.
        var previous: String
        do {
            previous = text
            text = FRAC.replace(text) { "${group(it.groupValues[1])}/${group(it.groupValues[2])}" }
            text = SQRT.replace(text) { "√${group(it.groupValues[1])}" }
        } while (text != previous)

        SYMBOLS.forEach { (command, symbol) -> text = text.replace(command, symbol) }
        text = SPACING.replace(text, " ")

        text = SCRIPT.replace(text) { match ->
            val content = match.groupValues[2].ifEmpty { match.groupValues[3] }
            script(match.groupValues[1], content)
        }

        // `\sin` -> sin, `\log` -> log: an unknown command reads as its own name.
        text = COMMAND.replace(text) { it.groupValues[1] }

        return text
            .replace("{", "")
            .replace("}", "")
            .replace(Regex(" {2,}"), " ")
            .trim()
    }

    /** A fraction side only needs parentheses when it is more than one token. */
    private fun group(content: String): String {
        val inner = content.trim()
        return if (inner.length <= 1 || inner.all { it.isLetterOrDigit() }) inner else "($inner)"
    }

    private fun script(marker: String, content: String): String {
        val map = if (marker == "^") SUPERSCRIPTS else SUBSCRIPTS
        val converted = content.map { map[it] }
        return when {
            content.isNotEmpty() && converted.all { it != null } -> converted.joinToString("")
            content.length <= 1 -> "$marker$content"
            else -> "$marker($content)"
        }
    }

    private val WRAPPER = Regex("\\\\(text|textbf|textit|mathrm|mathbf|mathit|operatorname)\\{([^{}]*)}")
    private val FRAC = Regex("\\\\[dt]?frac\\{([^{}]*)}\\{([^{}]*)}")
    private val SQRT = Regex("\\\\sqrt\\{([^{}]*)}")
    private val SCRIPT = Regex("([\\^_])(?:\\{([^{}]*)}|([A-Za-z0-9+\\-]))")
    private val SPACING = Regex("\\\\[,;:! ]|~")
    private val COMMAND = Regex("\\\\([A-Za-z]+)")

    /** Longest command first, so `\leq` never half-matches as `\le`. */
    private val SYMBOLS = listOf(
        "\\times" to "×", "\\div" to "÷", "\\pm" to "±", "\\mp" to "∓",
        "\\cdots" to "⋯", "\\cdot" to "·", "\\ldots" to "…", "\\dots" to "…",
        "\\leq" to "≤", "\\le" to "≤", "\\geq" to "≥", "\\ge" to "≥",
        "\\neq" to "≠", "\\ne" to "≠", "\\approx" to "≈", "\\equiv" to "≡",
        "\\sim" to "∼", "\\propto" to "∝", "\\infty" to "∞",
        "\\degree" to "°", "\\circ" to "∘",
        "\\leftrightarrow" to "↔", "\\rightarrow" to "→", "\\leftarrow" to "←",
        "\\Rightarrow" to "⇒", "\\Leftarrow" to "⇐", "\\Leftrightarrow" to "⇔", "\\to" to "→",
        "\\sum" to "Σ", "\\prod" to "∏", "\\int" to "∫",
        "\\partial" to "∂", "\\nabla" to "∇",
        "\\notin" to "∉", "\\in" to "∈", "\\subseteq" to "⊆", "\\subset" to "⊂",
        "\\cup" to "∪", "\\cap" to "∩", "\\forall" to "∀", "\\exists" to "∃",
        "\\neg" to "¬", "\\wedge" to "∧", "\\land" to "∧", "\\vee" to "∨", "\\lor" to "∨",
        "\\alpha" to "α", "\\beta" to "β", "\\gamma" to "γ", "\\delta" to "δ",
        "\\epsilon" to "ε", "\\varepsilon" to "ε", "\\zeta" to "ζ", "\\eta" to "η",
        "\\theta" to "θ", "\\iota" to "ι", "\\kappa" to "κ", "\\lambda" to "λ",
        "\\mu" to "μ", "\\nu" to "ν", "\\xi" to "ξ", "\\pi" to "π", "\\rho" to "ρ",
        "\\sigma" to "σ", "\\tau" to "τ", "\\upsilon" to "υ", "\\phi" to "φ",
        "\\varphi" to "φ", "\\chi" to "χ", "\\psi" to "ψ", "\\omega" to "ω",
        "\\Gamma" to "Γ", "\\Delta" to "Δ", "\\Theta" to "Θ", "\\Lambda" to "Λ",
        "\\Xi" to "Ξ", "\\Pi" to "Π", "\\Sigma" to "Σ", "\\Phi" to "Φ",
        "\\Psi" to "Ψ", "\\Omega" to "Ω",
        "\\hbar" to "ħ", "\\ell" to "ℓ", "\\%" to "%"
    ).sortedByDescending { it.first.length }

    private val SUPERSCRIPTS = mapOf(
        '0' to "⁰", '1' to "¹", '2' to "²", '3' to "³", '4' to "⁴",
        '5' to "⁵", '6' to "⁶", '7' to "⁷", '8' to "⁸", '9' to "⁹",
        '+' to "⁺", '-' to "⁻", '=' to "⁼", '(' to "⁽", ')' to "⁾",
        'n' to "ⁿ", 'i' to "ⁱ", 'T' to "ᵀ"
    )

    private val SUBSCRIPTS = mapOf(
        '0' to "₀", '1' to "₁", '2' to "₂", '3' to "₃", '4' to "₄",
        '5' to "₅", '6' to "₆", '7' to "₇", '8' to "₈", '9' to "₉",
        '+' to "₊", '-' to "₋", '=' to "₌", '(' to "₍", ')' to "₎",
        'a' to "ₐ", 'e' to "ₑ", 'i' to "ᵢ", 'o' to "ₒ", 'x' to "ₓ",
        'n' to "ₙ", 'm' to "ₘ", 't' to "ₜ", 'k' to "ₖ", 's' to "ₛ"
    )
}
