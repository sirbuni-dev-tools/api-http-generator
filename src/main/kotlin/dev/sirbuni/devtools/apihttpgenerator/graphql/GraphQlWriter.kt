package dev.sirbuni.devtools.apihttpgenerator.graphql

import dev.sirbuni.devtools.apihttpgenerator.json.ArrayNode
import dev.sirbuni.devtools.apihttpgenerator.json.FakeSource
import dev.sirbuni.devtools.apihttpgenerator.json.IntegerRange
import dev.sirbuni.devtools.apihttpgenerator.json.JsonNode
import dev.sirbuni.devtools.apihttpgenerator.json.JsonRenderer
import dev.sirbuni.devtools.apihttpgenerator.json.ObjectNode
import dev.sirbuni.devtools.apihttpgenerator.json.RenderMode
import dev.sirbuni.devtools.apihttpgenerator.json.UuidValue
import dev.sirbuni.devtools.apihttpgenerator.json.ValueNode
import dev.sirbuni.devtools.apihttpgenerator.psi.ClassWalker
import dev.sirbuni.devtools.apihttpgenerator.rest.GeneratedRequest
import dev.sirbuni.devtools.apihttpgenerator.rest.RequestWriter

/** Writes SPQR resolvers as `GRAPHQL` requests with operation variables and a selection set. */
class GraphQlWriter(private val endpointPath: String = "/graphql", source: FakeSource = FakeSource()) {
    private val inputWalker = ClassWalker(skipGenerated = true)
    private val outputWalker = ClassWalker(maxDepth = 3, includeGetters = true)
    private val placeholders = JsonRenderer(RenderMode.PLACEHOLDERS, source)

    fun write(operation: Operation): GeneratedRequest {
        val owner = operation.method.containingClass?.name ?: ""
        val operationName = operation.name.replaceFirstChar { it.uppercase() }
        val declarations = operation.arguments.joinToString(", ") { (name, p) -> "\$$name: ${SpqrOperations.inputTypeName(p.type)}!" }
        val header = "${operation.kind} $operationName" + (if (declarations.isEmpty()) "" else "($declarations)") + " {"

        val call = operation.name + if (operation.arguments.isEmpty()) "" else
            "(" + operation.arguments.joinToString(", ") { (name, _) -> "$name: \$$name" } + ")"
        val returnType = operation.method.returnType
        val selection = returnType?.let { selection(outputWalker.walk(it), 2) }.orEmpty()

        val fileVariables = linkedMapOf<String, String>()
        val variables = operation.arguments.map { (name, p) ->
            val node = inputWalker.walkVariables(listOf(name to p), owner).fields.first().value
            val generator = (node as? ValueNode)?.generator
            val isId = name.lowercase().let { it == "id" || it.endsWith("id") || it == "uid" || it == "uuid" }
            if (isId && (generator is IntegerRange || generator is UuidValue)) {
                // Like REST path variables: you choose the record, it is never random.
                fileVariables.putIfAbsent(name, if (generator is UuidValue) "00000000-0000-0000-0000-000000000000" else "1")
                name to if (generator.isString) "\"{{$name}}\"" else "{{$name}}"
            } else name to placeholders.render(node)
        }

        val out = StringBuilder()
        out.append("### GRAPHQL ${RequestWriter.humanize(operation.name)}\n")
        out.append("# @name ${operation.name}\n")
        out.append("GRAPHQL {{HOST}}$endpointPath\n")
        out.append("Authorization: Bearer {{access_token}}\n\n")
        out.append(header).append('\n')
        out.append("  ").append(call).append(if (selection.isEmpty()) "\n" else " $selection\n")
        out.append("}\n")
        if (variables.isNotEmpty()) {
            out.append("\n{\n")
            out.append(variables.joinToString(",\n") { (name, value) -> "  \"$name\": " + value.replace("\n", "\n  ") })
            out.append("\n}\n")
        }
        return GeneratedRequest(header, out.toString(), fileVariables)
    }

    /** `{ a b c { d } }` over every field that has something to select; nested objects need fields of their own. */
    private fun selection(node: JsonNode, indent: Int): String {
        val obj = when (node) {
            is ObjectNode -> node
            is ArrayNode -> return selection(node.element, indent)
            else -> return ""
        }
        val pad = " ".repeat(indent + 2)
        val lines = obj.fields.mapNotNull { field ->
            when (val value = field.value) {
                is ValueNode -> field.key
                is ObjectNode, is ArrayNode -> selection(value, indent + 2).takeIf { it.isNotEmpty() }?.let { "${field.key} $it" }
                else -> null
            }
        }
        if (lines.isEmpty()) return ""
        return "{\n" + lines.joinToString("\n") { pad + it } + "\n" + " ".repeat(indent) + "}"
    }

    companion object {
        /** Logs in once per file and keeps the token for every request below it. Values live in the env files. */
        fun loginRequest(): GeneratedRequest = GeneratedRequest(
            "POST {{AUTH_URL}}",
            """
            ### POST login (run first; the token lasts until it expires)
            # @name login
            POST {{AUTH_URL}}
            Authorization: Basic {{CLIENT_ID}} {{CLIENT_SECRET}}
            Content-Type: application/x-www-form-urlencoded

            grant_type={{GRANT_TYPE}}&
            username={{USERNAME}}&
            password={{PASSWORD}}

            > {% client.global.set("access_token", response.body.access_token); %}

            """.trimIndent(),
            emptyMap(),
        )
    }
}
