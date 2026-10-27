package dev.sirbuni.devtools.apihttpgenerator.rest

import com.intellij.openapi.projectRoots.JavaSdk
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.psi.PsiClass
import com.intellij.testFramework.LightProjectDescriptor
import com.intellij.testFramework.LoggedErrorProcessor
import com.intellij.testFramework.fixtures.DefaultLightProjectDescriptor
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import dev.sirbuni.devtools.apihttpgenerator.json.FakeSource

class RequestWriterTest : LightJavaCodeInsightFixtureTestCase() {

    override fun getProjectDescriptor(): LightProjectDescriptor = REAL_JDK

    private lateinit var controller: PsiClass

    override fun setUp() {
        LoggedErrorProcessor.executeWith<Throwable>(object : LoggedErrorProcessor() {
            override fun processError(category: String, message: String, details: Array<out String>, t: Throwable?): Set<Action> =
                if (message.contains("[Plugin: com.intellij.modules.ultimate]")) Action.NONE
                else super.processError(category, message, details, t)
        }) { super.setUp() }
        val bind = "package org.springframework.web.bind.annotation;"
        listOf(
            "$bind public @interface RestController {}",
            "$bind public @interface RequestMapping { String[] value() default {}; String[] path() default {}; RequestMethod[] method() default {}; }",
            "$bind public enum RequestMethod { GET, POST, PUT, PATCH, DELETE }",
            "$bind public @interface GetMapping { String[] value() default {}; }",
            "$bind public @interface PostMapping { String[] value() default {}; }",
            "$bind public @interface DeleteMapping { String[] value() default {}; }",
            "$bind public @interface PathVariable { String value() default \"\"; }",
            "$bind public @interface RequestParam { String value() default \"\"; }",
            "$bind public @interface RequestHeader { String value() default \"\"; }",
            "$bind public @interface RequestBody {}",
            "package demo; public class Employee { private String firstName; private int salary; }",
        ).forEach { myFixture.addClass(it) }
        controller = myFixture.addClass(
            """
            package demo;
            import org.springframework.web.bind.annotation.*;
            @RestController
            @RequestMapping("/api")
            public class EmployeeController {
                @PostMapping("/myemployees-entity-salary")
                public Boolean updateEmployee_salary(@RequestParam("empId") int empId, @RequestParam("salary") double salary) { return true; }

                @GetMapping("/myemployees-entity-find/{id}")
                public Employee findEmployeeById(@PathVariable("id") int empId, @RequestHeader("X-Tenant") String tenant) { return null; }

                @GetMapping("/search")
                public java.util.List<Employee> search(@RequestParam String firstName, int page) { return null; }

                @PostMapping("/myemployees-entity")
                public Employee add(@RequestBody Employee employee, jakarta.servlet.http.HttpServletRequest request) { return employee; }

                @RequestMapping(value = "/legacy", method = RequestMethod.DELETE)
                public void legacy(@RequestParam("empId") long empId) {}

                public void notAnEndpoint() {}
            }
            """.trimIndent()
        )
    }

    private fun write(methodName: String): GeneratedRequest =
        RequestWriter(FakeSource(seed = 3)).write(SpringEndpoints.endpoint(controller.findMethodsByName(methodName, false).single())!!)

    fun `test form body for a POST with request params`() {
        assertEquals(
            """
            ### POST update employee salary
            # @name updateEmployee_salary
            POST {{HOST}}/api/myemployees-entity-salary
            Content-Type: application/x-www-form-urlencoded

            empId={{empId}}&
            salary={{${'$'}random.float(1000, 5000000)}}

            """.trimIndent(),
            write("updateEmployee_salary").text,
        )
        assertEquals(mapOf("empId" to "1"), write("updateEmployee_salary").fileVariables)
    }

    fun `test path variables become file variables and headers are written`() {
        val request = write("findEmployeeById")
        assertEquals("GET {{HOST}}/api/myemployees-entity-find/{{id}}", request.requestLine)
        assertEquals(mapOf("id" to "1"), request.fileVariables)
        assertTrue(request.text, request.text.contains("\nX-Tenant: {{${'$'}random.lorem.word}}\n"))
    }

    fun `test GET puts params in the query, including unannotated simple ones`() {
        val text = write("search").text
        assertTrue(text, text.contains("GET {{HOST}}/api/search\n    ?firstName={{${'$'}random.name.firstName}}\n    &page={{${'$'}random.integer(1, 1001)}}\n"))
    }

    fun `test request body is JSON and servlet arguments are ignored`() {
        val text = write("add").text
        assertTrue(text, text.contains("Content-Type: application/json\n\n{\n  \"firstName\": \"{{${'$'}random.name.firstName}}\""))
        assertFalse(text, text.contains("request"))
    }

    fun `test file variables are URL safe`() {
        controller = myFixture.addClass(
            """
            package demo2;
            import org.springframework.web.bind.annotation.*;
            @RestController
            public class ByDept {
                @GetMapping("/by/{dept}/{fullName}")
                public void find(@PathVariable String dept, @PathVariable String fullName) {}
            }
            """.trimIndent()
        )
        repeat(5) {
            write("find").fileVariables.values.forEach { assertTrue(it, it.matches(Regex("[A-Za-z0-9._~-]+"))) }
        }
    }

    fun `test generated ids are left out of request bodies`() {
        myFixture.addClass("package jakarta.persistence; public @interface GeneratedValue {}")
        myFixture.addClass("package demo3; public class Account { @jakarta.persistence.GeneratedValue private int id; private String email; }")
        controller = myFixture.addClass(
            """
            package demo3;
            import org.springframework.web.bind.annotation.*;
            @RestController
            public class Accounts { @PostMapping("/accounts") public Account create(@RequestBody Account a) { return a; } }
            """.trimIndent()
        )
        val text = write("create").text
        assertTrue(text, text.contains("\"email\"") && !text.contains("\"id\""))
    }

    fun `test RequestMapping with a method attribute`() {
        assertEquals("DELETE {{HOST}}/api/legacy", write("legacy").requestLine)
    }

    fun `test the controller lists only its handler methods`() {
        assertEquals(5, SpringEndpoints.endpoints(controller).size)
    }

    fun `test generating writes the file and env file and does not duplicate`() {
        myFixture.addFileToProject("src/main/resources/application.properties", "server.port=\${SERVER_PORT:8281}\n")
        RequestFileService.generate(project, controller)
        RequestFileService.generate(project, controller)
        val root = myFixture.findFileInTempDir(".")
        val dir = VfsUtil.findRelativeFile(root, ".local", "http")!!
        val http = VfsUtil.loadText(dir.findChild("EmployeeController.http")!!)
        assertEquals(1, Regex("(?m)^POST \\{\\{HOST}}/api/myemployees-entity-salary$").findAll(http).count())
        assertTrue(http, http.startsWith("@empId = 1\n@id = 1\n\n###"))
        assertEquals("{\n  \"local\": {\n    \"HOST\": \"http://localhost:8281\"\n  }\n}\n", VfsUtil.loadText(dir.findChild("http-client.env.json")!!))
    }

    companion object {
        private val REAL_JDK = object : DefaultLightProjectDescriptor() {
            override fun getSdk(): Sdk = JavaSdk.getInstance().createJdk("test-jdk", System.getProperty("java.home"), false)
        }
    }
}
