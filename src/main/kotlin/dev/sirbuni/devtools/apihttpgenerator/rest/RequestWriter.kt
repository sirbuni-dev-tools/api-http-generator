package dev.sirbuni.devtools.apihttpgenerator.rest

import com.intellij.psi.PsiParameter
import dev.sirbuni.devtools.apihttpgenerator.json.FakeSource
import dev.sirbuni.devtools.apihttpgenerator.json.IntegerRange
import dev.sirbuni.devtools.apihttpgenerator.json.JsonRenderer
import dev.sirbuni.devtools.apihttpgenerator.json.RenderMode
import dev.sirbuni.devtools.apihttpgenerator.json.ValueNode
import dev.sirbuni.devtools.apihttpgenerator.psi.ClassWalker

/** One request ready for a `.http` file, plus the `@name = value` file variables its URL uses. */
data class GeneratedRequest(
    /** `POST {{HOST}}/employees/{{id}}`: also how an existing copy is recognised. */
    val requestLine: String,
    val text: String,
    val fileVariables: Map<String, String>,
)

/** Writes Spring endpoints as IntelliJ HTTP Client requests. */
class RequestWriter(source: FakeSource = FakeSource()) {
    private val walker = ClassWalker()
    private val bodyWalker = ClassWalker(skipGenerated = true)
    private val placeholders = JsonRenderer(RenderMode.PLACEHOLDERS, source)
    private val fixed = JsonRenderer(RenderMode.FIXED, source)

    fun write(endpoint: Endpoint): GeneratedRequest {
        val owner = endpoint.method.containingClass?.name ?: ""
        val fileVariables = linkedMapOf<String, String>()
        var path = endpoint.path
        for ((name, parameter) in endpoint.pathVariables) {
            path = path.replace("{$name}", "{{$name}}")
            fileVariables[name] = fixedValue(name, parameter, owner)
        }

        // An id picks the record to update or delete, so it is a file variable you set, never a random value.
        val params = endpoint.requestParams.map { (name, p) ->
            if (isId(name, p, owner)) {
                fileVariables.putIfAbsent(name, fixedValue(name, p, owner))
                name to "{{$name}}"
            } else name to placeholders.renderPlain(leaf(name, p, owner))
        } + if (endpoint.pageable) listOf("page" to "0", "size" to "20") else emptyList()
        // A JSON body leaves no room for a form, so parameters then go to the query string as well.
        val inQuery = endpoint.httpMethod == "GET" || endpoint.body != null

        val requestLine = "${endpoint.httpMethod} {{HOST}}$path"
        val out = StringBuilder()
        out.append("### ${endpoint.httpMethod} ${humanize(endpoint.method.name)}\n")
        // The name lists the request in the Services tool window; HttpFileText.merge keeps it unique per file.
        out.append("# @name ${endpoint.method.name}\n")
        out.append(requestLine)
        if (inQuery && params.isNotEmpty()) {
            params.forEachIndexed { i, (name, value) -> out.append('\n').append(if (i == 0) "    ?" else "    &").append("$name=$value") }
        }
        out.append('\n')
        for ((name, p) in endpoint.headers) out.append("$name: ${placeholders.renderPlain(leaf(name, p, owner))}\n")

        val body = endpoint.body
        when {
            body != null -> {
                out.append("Content-Type: application/json\n\n")
                out.append(placeholders.render(bodyWalker.walk(body.type))).append('\n')
            }
            !inQuery && params.isNotEmpty() -> {
                out.append("Content-Type: application/x-www-form-urlencoded\n\n")
                out.append(params.joinToString("&\n") { (name, value) -> "$name=$value" }).append('\n')
            }
        }
        return GeneratedRequest(requestLine, out.toString(), fileVariables)
    }

    private fun leaf(key: String, parameter: PsiParameter, owner: String) =
        walker.walkVariables(listOf(key to parameter), owner).fields.first().value

    private fun isId(name: String, parameter: PsiParameter, owner: String): Boolean {
        val generator = (leaf(name, parameter, owner) as? ValueNode)?.generator
        val n = name.lowercase()
        return (n == "id" || n.endsWith("id") || n == "uid" || n == "uuid") &&
            (generator is IntegerRange || generator is dev.sirbuni.devtools.apihttpgenerator.json.UuidValue)
    }

    /** Path variables and ids are edited by hand to point at a real record, so ids start at 1 rather than at random. */
    private fun fixedValue(name: String, parameter: PsiParameter, owner: String): String {
        val node = leaf(name, parameter, owner)
        if (node is ValueNode && node.generator is IntegerRange && name.lowercase().endsWith("id")) return "1"
        return urlSafe(fixed.renderPlain(node))
    }

    /** File variables land in URL paths and query strings, so `Outdoors & Tools` becomes `Outdoors`. */
    private fun urlSafe(value: String): String =
        value.split(Regex("[^A-Za-z0-9._~-]+")).firstOrNull { it.isNotEmpty() } ?: "1"

    companion object {
        /** `updateEmployee_salary` -> `update employee salary`, as titles in a hand-written file read. */
        fun humanize(name: String): String =
            name.split(Regex("(?<=[a-z0-9])(?=[A-Z])|_+")).filter { it.isNotEmpty() }.joinToString(" ") { it.lowercase() }
    }
}
