package dev.mikhailtail.handyagent.core.json

import kotlin.math.abs
import kotlin.math.floor

private val CH_BS: Char = 8.toChar()
private val CH_FF: Char = 12.toChar()
private val CH_QUOTE: Char = 34.toChar()
private val CH_BACKSLASH: Char = 92.toChar()

/** 极简 JSON 树 + 解析器 + 序列化器。零 Android/第三方依赖，可在宿主 JVM 单测。 */
sealed class Json {
    object Null : Json()
    data class Bool(val value: Boolean) : Json()
    data class Num(val value: Double) : Json()
    data class Str(val value: String) : Json()
    data class Arr(val items: List<Json>) : Json()
    data class Obj(val fields: Map<String, Json>) : Json()

    val isNull: Boolean get() = this is Null

    operator fun get(key: String): Json? = (this as? Obj)?.fields?.get(key)

    fun str(key: String): String? = (this[key] as? Str)?.value
    fun int(key: String): Int? = (this[key] as? Num)?.value?.toInt()
    fun long(key: String): Long? = (this[key] as? Num)?.value?.toLong()
    fun double(key: String): Double? = (this[key] as? Num)?.value
    fun bool(key: String): Boolean? = (this[key] as? Bool)?.value
    fun array(key: String): List<Json> = (this[key] as? Arr)?.items ?: emptyList()
    fun obj(key: String): Json? = this[key] as? Obj

    /** 取子对象的字段表；缺失或类型不符时返回空表（对称于 [array]，调用方无需 `as?` 强转）。 */
    fun fieldsOf(key: String): Map<String, Json> = (this[key] as? Obj)?.fields ?: emptyMap()

    fun asStringOrNull(): String? = (this as? Str)?.value
    fun asIntOrNull(): Int? = (this as? Num)?.value?.toInt()
    fun asBooleanOrNull(): Boolean? = (this as? Bool)?.value

    fun merge(other: Json): Json {
        if (this !is Obj || other !is Obj) return other
        val m = LinkedHashMap(fields)
        m.putAll(other.fields)
        return Obj(m)
    }

    fun encode(): String = buildString { JsonWriter.write(this, this@Json) }

    override fun toString(): String = encode()

    companion object {
        val TRUE = Bool(true)
        val FALSE = Bool(false)

        fun of(v: String?): Json = if (v == null) Null else Str(v)
        fun of(v: Boolean): Json = if (v) TRUE else FALSE
        fun of(v: Int): Json = Num(v.toDouble())
        fun of(v: Long): Json = Num(v.toDouble())
        fun of(v: Double): Json = Num(v)
        fun arr(items: List<Json>): Json = Arr(items)
        fun arr(vararg items: Json): Json = Arr(items.toList())
        fun obj(vararg pairs: Pair<String, Json>): Json = Obj(linkedMapOf(*pairs))
        fun obj(map: Map<String, Json>): Json = Obj(LinkedHashMap(map))
        fun arrOfStrings(items: List<String>): Json = Arr(items.map { Str(it) })

        fun parse(text: String): Json = JsonParser(text).parseDocument()
        fun parseOrNull(text: String): Json? = try { parse(text) } catch (e: JsonException) { null }
    }
}

class JsonException(message: String, val offset: Int = -1) : Exception(
    if (offset >= 0) "$message (offset=$offset)" else message
)

private class JsonParser(private val src: String) {
    private var i = 0
    private var depth = 0

    fun parseDocument(): Json {
        skipWs()
        val v = parseValue()
        skipWs()
        if (i != src.length) fail("Trailing content")
        return v
    }

    private fun fail(msg: String): Nothing = throw JsonException(msg, i)

    private fun skipWs() {
        while (i < src.length) {
            val c = src[i]
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') i++ else break
        }
    }

    private fun parseValue(): Json {
        if (depth > 200) fail("Nesting too deep")
        if (i >= src.length) fail("Unexpected end of input")
        val c = src[i]
        return when {
            c == '{' -> parseObject()
            c == '[' -> parseArray()
            c == CH_QUOTE -> Json.Str(parseString())
            c == 't' -> { expect("true"); Json.TRUE }
            c == 'f' -> { expect("false"); Json.FALSE }
            c == 'n' -> { expect("null"); Json.Null }
            c == '-' || c.isDigit() -> parseNumber()
            else -> fail("Unexpected char '$c'")
        }
    }

    private fun expect(lit: String) {
        if (!src.startsWith(lit, i)) fail("Expected $lit")
        i += lit.length
    }

    private fun parseObject(): Json {
        depth++
        i++
        val map = LinkedHashMap<String, Json>()
        skipWs()
        if (i < src.length && src[i] == '}') { i++; depth--; return Json.Obj(map) }
        while (true) {
            skipWs()
            if (i >= src.length || src[i] != CH_QUOTE) fail("Expected object key")
            val k = parseString()
            skipWs()
            if (i >= src.length || src[i] != ':') fail("Expected ':'")
            i++
            skipWs()
            map[k] = parseValue()
            skipWs()
            if (i >= src.length) fail("Unterminated object")
            val d = src[i]
            when {
                d == ',' -> i++
                d == '}' -> { i++; depth--; return Json.Obj(map) }
                else -> fail("Expected ',' or '}'")
            }
        }
    }

    private fun parseArray(): Json {
        depth++
        i++
        val list = ArrayList<Json>()
        skipWs()
        if (i < src.length && src[i] == ']') { i++; depth--; return Json.Arr(list) }
        while (true) {
            skipWs()
            list.add(parseValue())
            skipWs()
            if (i >= src.length) fail("Unterminated array")
            val d = src[i]
            when {
                d == ',' -> i++
                d == ']' -> { i++; depth--; return Json.Arr(list) }
                else -> fail("Expected ',' or ']'")
            }
        }
    }

    private fun parseString(): String {
        i++
        val sb = StringBuilder()
        while (true) {
            if (i >= src.length) fail("Unterminated string")
            val c = src[i]
            when {
                c == CH_QUOTE -> { i++; return sb.toString() }
                c == CH_BACKSLASH -> {
                    i++
                    if (i >= src.length) fail("Unterminated escape")
                    when (val e = src[i]) {
                        'b' -> { sb.append(CH_BS); i++ }
                        'f' -> { sb.append(CH_FF); i++ }
                        'n' -> { sb.append(10.toChar()); i++ }
                        'r' -> { sb.append(13.toChar()); i++ }
                        't' -> { sb.append(9.toChar()); i++ }
                        '"' -> { sb.append(CH_QUOTE); i++ }
                        '/' -> { sb.append('/'); i++ }
                        'u' -> {
                            if (i + 4 >= src.length) fail("Bad unicode escape")
                            val hex = src.substring(i + 1, i + 5)
                            val code = hex.toIntOrNull(16) ?: fail("Bad unicode escape '$hex'")
                            sb.append(code.toChar())
                            i += 5
                        }
                        else -> if (e == CH_BACKSLASH) { sb.append(CH_BACKSLASH); i++ } else fail("Bad escape")
                    }
                }
                c.code < 0x20 -> fail("Raw control char in string")
                else -> { sb.append(c); i++ }
            }
        }
    }

    private fun parseNumber(): Json {
        val start = i
        if (i < src.length && src[i] == '-') i++
        while (i < src.length && src[i].isDigit()) i++
        if (i < src.length && src[i] == '.') {
            i++
            while (i < src.length && src[i].isDigit()) i++
        }
        if (i < src.length && (src[i] == 'e' || src[i] == 'E')) {
            i++
            if (i < src.length && (src[i] == '+' || src[i] == '-')) i++
            while (i < src.length && src[i].isDigit()) i++
        }
        val raw = src.substring(start, i)
        val d = raw.toDoubleOrNull() ?: fail("Bad number '$raw'")
        return Json.Num(d)
    }
}

private object JsonWriter {
    fun write(sb: StringBuilder, v: Json) {
        when (v) {
            is Json.Null -> sb.append("null")
            is Json.Bool -> sb.append(if (v.value) "true" else "false")
            is Json.Num -> sb.append(fmtNum(v.value))
            is Json.Str -> writeString(sb, v.value)
            is Json.Arr -> {
                sb.append('[')
                v.items.forEachIndexed { idx, item ->
                    if (idx > 0) sb.append(',')
                    write(sb, item)
                }
                sb.append(']')
            }
            is Json.Obj -> {
                sb.append('{')
                var first = true
                for ((k, value) in v.fields) {
                    if (!first) sb.append(',')
                    first = false
                    writeString(sb, k)
                    sb.append(':')
                    write(sb, value)
                }
                sb.append('}')
            }
        }
    }

    private fun fmtNum(d: Double): String {
        if (d.isNaN() || d.isInfinite()) return "null"
        if (d == floor(d) && abs(d) < 1e15) return d.toLong().toString()
        return d.toString()
    }

    private fun writeString(sb: StringBuilder, s: String) {
        sb.append(CH_QUOTE)
        for (c in s) {
            when {
                c == CH_QUOTE -> sb.append(CH_BACKSLASH).append(CH_QUOTE)
                c == CH_BACKSLASH -> sb.append(CH_BACKSLASH).append(CH_BACKSLASH)
                c == 10.toChar() -> sb.append(CH_BACKSLASH).append('n')
                c == 13.toChar() -> sb.append(CH_BACKSLASH).append('r')
                c == 9.toChar() -> sb.append(CH_BACKSLASH).append('t')
                c == CH_BS -> sb.append(CH_BACKSLASH).append('b')
                c == CH_FF -> sb.append(CH_BACKSLASH).append('f')
                c.code < 0x20 -> sb.append(CH_BACKSLASH).append("u%04x".format(c.code))
                else -> sb.append(c)
            }
        }
        sb.append(CH_QUOTE)
    }
}
