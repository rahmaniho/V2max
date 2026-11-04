package ir.v2max.core.config

/**
 * An ordered JSON writer.
 *
 * Xray reads config objects by key, so order is not semantically required — but
 * a stable, readable order makes a generated config diffable when debugging a
 * connection problem, which is exactly when it matters. Insertion order is
 * preserved throughout, and null values are dropped so callers can write
 * `put("x", maybeValue)` without an `if`.
 */
class JObj {
    /** Insertion-ordered members; internal so the renderer can iterate them. */
    internal val members = LinkedHashMap<String, Any?>()

    /** Adds [value] unless it is null. */
    fun put(key: String, value: Any?): JObj = apply { if (value != null) members[key] = value }

    /** Adds [value] only when [condition] holds. */
    fun putIf(key: String, condition: Boolean, value: Any?): JObj = apply {
        if (condition && value != null) members[key] = value
    }

    /** Adds [value] unless it is a blank string. */
    fun putNonBlank(key: String, value: String?): JObj = apply {
        if (!value.isNullOrBlank()) members[key] = value
    }

    /** Adds a nested object, omitting it entirely if the block adds nothing. */
    fun obj(key: String, block: JObj.() -> Unit): JObj = apply {
        val child = JObj().apply(block)
        if (child.isNotEmpty()) members[key] = child
    }

    /** Adds a nested array, omitting it entirely if the block adds nothing. */
    fun arr(key: String, block: JArr.() -> Unit): JObj = apply {
        val child = JArr().apply(block)
        if (child.isNotEmpty()) members[key] = child
    }

    fun has(key: String): Boolean = members.containsKey(key)

    fun isNotEmpty(): Boolean = members.isNotEmpty()

    fun isEmpty(): Boolean = members.isEmpty()

    fun render(): String = buildString { writeObj(this@JObj, this, 0) }

    override fun toString(): String = render()
}

/**
 * A pre-serialised JSON fragment.
 *
 * Some share links embed raw JSON (XHTTP `extra`, an explicit `finalmask`).
 * Those must be spliced into the output verbatim — re-encoding them as a string
 * would produce a value the core cannot parse. The fragment is validated by the
 * caller before being wrapped.
 */
data class RawJson(val json: String)

class JArr {
    private val items = mutableListOf<Any?>()

    fun add(value: Any?): JArr = apply { if (value != null) items.add(value) }

    fun addAll(values: Iterable<String>): JArr = apply { values.forEach { items.add(it) } }

    /** Appends a nested object, omitted if the block adds nothing. */
    fun obj(block: JObj.() -> Unit): JArr = apply {
        val child = JObj().apply(block)
        if (child.isNotEmpty()) items.add(child)
    }

    /** Appends a raw, already-serialised JSON fragment (for embedded JSON). */
    fun raw(json: String): JArr = apply { items.add(RawJson(json)) }

    fun isNotEmpty(): Boolean = items.isNotEmpty()

    internal fun items(): List<Any?> = items
}

// ---------------------------------------------------------------- rendering

private fun writeObj(obj: JObj, sb: StringBuilder, indent: Int) {
    val entries = obj.members
    if (entries.isEmpty()) {
        sb.append("{}")
        return
    }
    sb.append('{')
    var first = true
    for ((k, v) in entries) {
        if (!first) sb.append(',')
        first = false
        sb.append('"').append(escape(k)).append("\":")
        writeValue(v, sb, indent)
    }
    sb.append('}')
}

private fun writeValue(value: Any?, sb: StringBuilder, indent: Int) {
    when (value) {
        null -> sb.append("null")
        is JObj -> writeObj(value, sb, indent)
        is JArr -> {
            val items = value.items()
            if (items.isEmpty()) {
                sb.append("[]")
                return
            }
            sb.append('[')
            var first = true
            for (item in items) {
                if (!first) sb.append(',')
                first = false
                writeValue(item, sb, indent)
            }
            sb.append(']')
        }
        is RawJson -> sb.append(value.json)
        is Boolean -> sb.append(value.toString())
        is Number -> sb.append(value.toString())
        is String -> sb.append('"').append(escape(value)).append('"')
        else -> sb.append('"').append(escape(value.toString())).append('"')
    }
}

private fun escape(s: String): String {
    val sb = StringBuilder(s.length + 8)
    for (c in s) {
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
    return sb.toString()
}


