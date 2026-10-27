package dev.sirbuni.devtools.apihttpgenerator.rest

/** Pure text work on `.http` and environment files, kept apart from the IDE so it can be unit-tested. */
object HttpFileText {

    data class Merged(val text: String, val added: List<GeneratedRequest>, val skipped: List<GeneratedRequest>)

    /**
     * Appends the requests a file does not have yet. A request counts as present when a line equals its
     * request line, so edits to its headers or body are kept. Missing file variables go above the first request.
     */
    fun merge(existing: String, requests: List<GeneratedRequest>): Merged {
        val lines = existing.lines().map { it.trim() }.toSet()
        val (skipped, added) = requests.distinctBy { it.requestLine }.partition { it.requestLine in lines }

        val declared = Regex("(?m)^@([\\w.-]+)\\s*=").findAll(existing).map { it.groupValues[1] }.toSet()
        val newVariables = added.flatMap { it.fileVariables.entries }
            .distinctBy { it.key }
            .filter { it.key !in declared }
            .joinToString("") { "@${it.key} = ${it.value}\n" }

        val usedNames = NAME_LINE.findAll(existing).map { it.groupValues[1] }.toMutableSet()
        val named = added.map { request -> uniqueName(request, usedNames) }

        var text = existing
        if (newVariables.isNotEmpty()) {
            val firstRequest = Regex("(?m)^###").find(text)?.range?.first ?: text.length
            val head = text.substring(0, firstRequest)
            val separator = if (head.isEmpty() || head.endsWith("\n")) "" else "\n"
            text = head + separator + newVariables + "\n" + text.substring(firstRequest)
        }
        for (request in named) {
            text = text.trimEnd('\n')
            if (text.isNotEmpty()) text += "\n\n"
            text += request.text
        }
        if (text.isNotEmpty() && !text.endsWith("\n")) text += "\n"
        return Merged(text, named, skipped)
    }

    private val NAME_LINE = Regex("(?m)^(?:#|//)\\s*@name\\s*=?\\s*(\\S+)")

    /** Overloaded handlers share a method name; the second becomes `name2`, and so on. */
    private fun uniqueName(request: GeneratedRequest, used: MutableSet<String>): GeneratedRequest {
        val name = NAME_LINE.find(request.text)?.groupValues?.get(1) ?: return request
        var candidate = name
        var n = 2
        while (candidate in used) candidate = name + n++
        used += candidate
        return if (candidate == name) request
        else request.copy(text = request.text.replaceFirst("# @name $name\n", "# @name $candidate\n"))
    }

    /**
     * `http://localhost:<server.port><server.servlet.context-path>` from `application.properties` or `.yml`,
     * reading the default of a `${VAR:default}` placeholder. Port 8080 and no context path when unset.
     */
    fun host(properties: String?, yaml: String?): String {
        val port = property(properties, "server.port") ?: yamlValue(yaml, listOf("server", "port")) ?: "8080"
        val contextPath = (property(properties, "server.servlet.context-path")
            ?: yamlValue(yaml, listOf("server", "servlet", "context-path")))?.trim('/')?.takeIf { it.isNotEmpty() }
        return "http://localhost:$port" + (contextPath?.let { "/$it" } ?: "")
    }

    fun envFile(host: String): String = "{\n  \"local\": {\n    \"HOST\": \"$host\"\n  }\n}\n"

    private fun property(text: String?, key: String): String? =
        text?.lines()?.map { it.trim() }?.lastOrNull { it.startsWith("$key=") || it.startsWith("$key:") || it.startsWith("$key ") }
            ?.substringAfter(key)?.trimStart(' ', '=', ':')?.let(::resolveDefault)

    /** Enough YAML for nested `server:` keys; no anchors or lists. */
    private fun yamlValue(text: String?, keys: List<String>): String? {
        if (text == null) return null
        var depth = 0
        var indent = -1
        for (raw in text.lines()) {
            if (raw.isBlank() || raw.trimStart().startsWith("#")) continue
            if (raw.trim() == "---") { depth = 0; indent = -1; continue }
            val lineIndent = raw.length - raw.trimStart().length
            if (depth > 0 && lineIndent <= indent) { depth = 0; indent = -1 }
            val line = raw.trim()
            val key = keys[depth]
            if (line.startsWith("$key:") && lineIndent > indent) {
                val value = line.substringAfter(":").trim()
                if (depth == keys.lastIndex) return value.takeIf { it.isNotEmpty() }?.let(::resolveDefault)
                depth++
                indent = lineIndent
            }
        }
        return null
    }

    private fun resolveDefault(value: String): String {
        val v = value.trim().trim('"', '\'')
        val m = Regex("^\\$\\{[^:}]+:([^}]*)}$").find(v)
        return m?.groupValues?.get(1) ?: v
    }
}
