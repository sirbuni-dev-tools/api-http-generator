package dev.sirbuni.devtools.apihttpgenerator.rest

import org.junit.Assert.assertEquals
import org.junit.Test

class HttpFileTextTest {

    private val get = GeneratedRequest("GET {{HOST}}/employees/{{id}}", "### GET find\nGET {{HOST}}/employees/{{id}}\n", mapOf("id" to "1"))
    private val post = GeneratedRequest("POST {{HOST}}/employees", "### POST add\nPOST {{HOST}}/employees\n", emptyMap())

    @Test
    fun `a new file gets the variables first, then the requests`() {
        val merged = HttpFileText.merge("", listOf(get, post))
        assertEquals("@id = 1\n\n### GET find\nGET {{HOST}}/employees/{{id}}\n\n### POST add\nPOST {{HOST}}/employees\n", merged.text)
        assertEquals(2, merged.added.size)
    }

    @Test
    fun `requests already in the file are kept as edited and not added again`() {
        val existing = "@id = 42\n\n### GET my edited version\nGET {{HOST}}/employees/{{id}}\nAccept: application/json\n"
        val merged = HttpFileText.merge(existing, listOf(get, post))
        assertEquals(listOf(post), merged.added)
        assertEquals(listOf(get), merged.skipped)
        assertEquals(existing + "\n### POST add\nPOST {{HOST}}/employees\n", merged.text)
    }

    @Test
    fun `request names stay unique in the file`() {
        fun named(path: String) = GeneratedRequest("GET {{HOST}}$path", "### GET all\n# @name getAll\nGET {{HOST}}$path\n", emptyMap())
        val existing = "### GET kept\n# @name getAll\nGET {{HOST}}/old\n"
        val merged = HttpFileText.merge(existing, listOf(named("/a"), named("/b")))
        assertEquals(listOf("getAll", "getAll2", "getAll3"), Regex("(?m)^# @name (\\S+)").findAll(merged.text).map { it.groupValues[1] }.toList())
    }

    @Test
    fun `host comes from properties, then yaml, with placeholder defaults`() {
        assertEquals("http://localhost:8080", HttpFileText.host(null, null))
        assertEquals("http://localhost:8281", HttpFileText.host("server.port=\${SERVER_PORT:8281}\n", null))
        assertEquals("http://localhost:9000/api", HttpFileText.host("server.port = 9000\nserver.servlet.context-path=/api/\n", null))
        val yaml = "spring:\n  application:\n    name: demo\nserver:\n  port: 8085\n  servlet:\n    context-path: /hr\n"
        assertEquals("http://localhost:8085/hr", HttpFileText.host(null, yaml))
    }

    @Test
    fun `titles read like the hand-written ones`() {
        assertEquals("update employee salary", RequestWriter.humanize("updateEmployee_salary"))
        assertEquals("find employee by id", RequestWriter.humanize("findEmployeeById"))
    }
}
