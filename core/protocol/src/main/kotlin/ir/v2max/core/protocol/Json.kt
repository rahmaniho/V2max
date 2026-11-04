package ir.v2max.core.protocol

/**
 * A minimal JSON reader.
 *
 * VMess links carry a base64-encoded JSON object, and the app only ever needs
 * to read flat string/number fields out of it. Pulling in Gson for that would
 * add a dependency to the pure-JVM core for no benefit, so this implements the
 * subset the protocol requires: objects, arrays, strings (with the full escape
 * set including `\uXXXX`), numbers, booleans and null.
 *
 * It is intentionally strict: malformed input returns null rather than
 * partially parsing, so a corrupt link is rejected instead of producing a
 * half-populated server.
 */
sealed interface Json {
    data class Str(val value: String) : Json
    data class Num(val value: Double) : Json
    data class Bool(val value: Boolean) : Json
    data object Null : Json
    data class Arr(val items: List<Json>) : Json
    data class Obj(val members: Map<String, Json>) : Json {
        operator fun get(key: String): Json? = members[key]
    }

    /** Value as a string, coercing numbers/booleans the way the old code did. */
    fun asString(): String? = when (this) {
        is Str -> value
        is Num -> if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()
        is Bool -> value.toString()
        else -> null
    }

    fun asObject(): Obj? = this as? Obj
    fun asArray(): Arr? = this as? Arr

    companion object {
        fun parse(text: String): Json? = Parser(text).parseRoot()

        /** Renders a value as a quoted JSON string literal, escaping as required. */
        fun quote(value: String): String {
            val sb = StringBuilder(value.length + 2)
            sb.append('"')
            for (c in value) {
                when (c) {
                    '"' -> sb.append("\\\"")
                    '\\' -> sb.append("\\\\")
                    '\n' -> sb.append("\\n")
                    '\r' -> sb.append("\\r")
                    '\t' -> sb.append("\\t")
                    '\b' -> sb.append("\\b")
                    '\u000C' -> sb.append("\\f")
                    else -> if (c < ' ') {
                        sb.append("\\u").append(c.code.toString(16).padStart(4, '0'))
                    } else {
                        sb.append(c)
                    }
                }
            }
            sb.append('"')
            return sb.toString()
        }
    }
}

private class Parser(private val s: String) {
    private var i = 0

    fun parseRoot(): Json? {
        skipWs()
        val v = parseValue() ?: return null
        skipWs()
        return if (i == s.length) v else null // reject trailing garbage
    }

    private fun parseValue(): Json? {
        skipWs()
        if (i >= s.length) return null
        return when (val c = s[i]) {
            '"' -> parseString()?.let { Json.Str(it) }
            '{' -> parseObject()
            '[' -> parseArray()
            't' -> literal("true", Json.Bool(true))
            'f' -> literal("false", Json.Bool(false))
            'n' -> literal("null", Json.Null)
            else -> if (c == '-' || c in '0'..'9') parseNumber() else null
        }
    }

    private fun literal(word: String, value: Json): Json? {
        if (!s.startsWith(word, i)) return null
        i += word.length
        return value
    }

    private fun parseObject(): Json.Obj? {
        expect('{') ?: return null
        val map = LinkedHashMap<String, Json>()
        skipWs()
        if (peek() == '}') { i++; return Json.Obj(map) }
        while (true) {
            skipWs()
            val key = parseString() ?: return null
            skipWs()
            expect(':') ?: return null
            val value = parseValue() ?: return null
            map[key] = value
            skipWs()
            when (peek()) {
                ',' -> i++
                '}' -> { i++; return Json.Obj(map) }
                else -> return null
            }
        }
    }

    private fun parseArray(): Json.Arr? {
        expect('[') ?: return null
        val items = ArrayList<Json>()
        skipWs()
        if (peek() == ']') { i++; return Json.Arr(items) }
        while (true) {
            val v = parseValue() ?: return null
            items.add(v)
            skipWs()
            when (peek()) {
                ',' -> i++
                ']' -> { i++; return Json.Arr(items) }
                else -> return null
            }
        }
    }

    private fun parseString(): String? {
        expect('"') ?: return null
        val sb = StringBuilder()
        while (true) {
            if (i >= s.length) return null
            val c = s[i++]
            when (c) {
                '"' -> return sb.toString()
                '\\' -> {
                    if (i >= s.length) return null
                    when (val e = s[i++]) {
                        '"' -> sb.append('"')
                        '\\' -> sb.append('\\')
                        '/' -> sb.append('/')
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000C')
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'u' -> {
                            if (i + 4 > s.length) return null
                            val hex = s.substring(i, i + 4)
                            val cp = hex.toIntOrNull(16) ?: return null
                            i += 4
                            sb.append(cp.toChar())
                        }
                        else -> return null
                    }
                }
                else -> sb.append(c)
            }
        }
    }

    private fun parseNumber(): Json.Num? {
        val start = i
        if (peek() == '-') i++
        while (i < s.length && s[i] in '0'..'9') i++
        if (i < s.length && s[i] == '.') {
            i++
            while (i < s.length && s[i] in '0'..'9') i++
        }
        if (i < s.length && (s[i] == 'e' || s[i] == 'E')) {
            i++
            if (i < s.length && (s[i] == '+' || s[i] == '-')) i++
            while (i < s.length && s[i] in '0'..'9') i++
        }
        if (i == start) return null
        return s.substring(start, i).toDoubleOrNull()?.let { Json.Num(it) }
    }

    private fun peek(): Char? = if (i < s.length) s[i] else null

    private fun expect(c: Char): Unit? {
        if (i >= s.length || s[i] != c) return null
        i++
        return Unit
    }

    private fun skipWs() {
        while (i < s.length && s[i].let { it == ' ' || it == '\t' || it == '\n' || it == '\r' }) i++
    }
}
