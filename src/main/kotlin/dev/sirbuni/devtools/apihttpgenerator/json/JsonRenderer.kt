package dev.sirbuni.devtools.apihttpgenerator.json

enum class RenderMode {
    /** Fixed fake values, valid JSON that pastes anywhere. */
    FIXED,

    /** `{{$random...}}` placeholders where the HTTP Client has a generator, fixed values elsewhere. Only for `.http` files. */
    PLACEHOLDERS,
}

/** Writes a [JsonNode] as pretty-printed JSON with two-space indentation. */
class JsonRenderer(private val mode: RenderMode, private val source: FakeSource = FakeSource()) {

    fun render(node: JsonNode): String = StringBuilder().also { write(node, it, 0) }.toString()

    /** A single value for a URL, form body or header: the placeholder or the raw value, never quoted. */
    fun renderPlain(node: JsonNode): String {
        if (node !is ValueNode) return render(node).lines().joinToString("") { it.trim() }
        val placeholder = node.generator.placeholder
        return if (mode == RenderMode.PLACEHOLDERS && placeholder != null) "{{$placeholder}}" else node.generator.concrete(source)
    }

    private fun write(node: JsonNode, out: StringBuilder, depth: Int) {
        when (node) {
            is ObjectNode -> writeObject(node.fields.map { it.key to it.value }, out, depth)
            is MapNode -> writeObject(listOf("key" to node.value), out, depth)
            is ArrayNode -> {
                out.append("[\n").append(indent(depth + 1))
                write(node.element, out, depth + 1)
                out.append('\n').append(indent(depth)).append(']')
            }
            is ValueNode -> out.append(value(node.generator))
            CutNode -> out.append("{}")
            NullNode -> out.append("null")
            EmptyArrayNode -> out.append("[]")
        }
    }

    private fun writeObject(fields: List<Pair<String, JsonNode>>, out: StringBuilder, depth: Int) {
        if (fields.isEmpty()) {
            out.append("{}")
            return
        }
        out.append("{\n")
        fields.forEachIndexed { i, (key, value) ->
            out.append(indent(depth + 1)).append(quote(key)).append(": ")
            write(value, out, depth + 1)
            if (i < fields.lastIndex) out.append(',')
            out.append('\n')
        }
        out.append(indent(depth)).append('}')
    }

    private fun value(generator: ValueGenerator): String {
        val placeholder = generator.placeholder
        if (mode == RenderMode.PLACEHOLDERS && placeholder != null) {
            val expression = "{{$placeholder}}"
            return if (generator.isString) "\"$expression\"" else expression
        }
        val raw = generator.concrete(source)
        return if (generator.isString) quote(raw) else raw
    }

    private fun indent(depth: Int) = "  ".repeat(depth)

    companion object {
        fun quote(s: String): String {
            val sb = StringBuilder("\"")
            for (c in s) {
                when (c) {
                    '"' -> sb.append("\\\"")
                    '\\' -> sb.append("\\\\")
                    '\n' -> sb.append("\\n")
                    '\r' -> sb.append("\\r")
                    '\t' -> sb.append("\\t")
                    else -> if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
                }
            }
            return sb.append('"').toString()
        }
    }
}
