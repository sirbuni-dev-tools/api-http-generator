package dev.sirbuni.devtools.apihttpgenerator.action

import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.projectRoots.JavaSdk
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.testFramework.LightProjectDescriptor
import com.intellij.testFramework.LoggedErrorProcessor
import com.intellij.testFramework.fixtures.DefaultLightProjectDescriptor
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import java.awt.datatransfer.DataFlavor
import java.io.File

class CopyAsJsonActionTest : LightJavaCodeInsightFixtureTestCase() {

    override fun getProjectDescriptor(): LightProjectDescriptor = REAL_JDK

    override fun setUp() {
        LoggedErrorProcessor.executeWith<Throwable>(object : LoggedErrorProcessor() {
            override fun processError(category: String, message: String, details: Array<out String>, t: Throwable?): Set<Action> =
                if (message.contains("[Plugin: com.intellij.modules.ultimate]")) Action.NONE
                else super.processError(category, message, details, t)
        }) { super.setUp() }
        myFixture.addClass("package demo; public enum Status { ACTIVE, ON_LEAVE }")
        myFixture.addClass(
            """
            package demo;
            public class Employee {
                private Long id;
                private String firstName;
                private String lastName;
                private String email;
                private String phone;
                private String dept;
                private int salary;
                private java.math.BigDecimal allowance;
                private boolean active;
                private Status status;
                private java.time.LocalDate startDate;
                private java.time.Instant createdAt;
                private java.util.UUID uid;
                private java.util.List<String> skills;
            }
            """.trimIndent()
        )
    }

    private fun copiedJson(actionId: String): String {
        myFixture.configureByText(
            "EmployeeController.java",
            """
            package demo;
            public class EmployeeController {
                public Employee add(Empl<caret>oyee employee) { return employee; }
            }
            """.trimIndent()
        )
        myFixture.performEditorAction(actionId)
        return CopyPasteManager.getInstance().getContents<String>(DataFlavor.stringFlavor)!!
    }

    fun `test caret on a parameter type copies that class with fake values`() {
        val json = copiedJson("ApiHttpGenerator.CopyAsJsonFixed")
        assertTrue(json, json.startsWith("{\n  \"id\": "))
        assertFalse(json, json.contains("{{"))
        assertTrue(json, Regex("\"status\": \"(ACTIVE|ON_LEAVE)\"").containsMatchIn(json))
        save("employee-fixed.json", json)
    }

    fun `test placeholder mode uses HTTP Client generators`() {
        val json = copiedJson("ApiHttpGenerator.CopyAsJsonPlaceholders")
        assertTrue(json, json.contains("\"firstName\": \"{{\$random.name.firstName}}\""))
        assertTrue(json, json.contains("\"salary\": {{\$random.integer(1000, 5000001)}}"))
        assertTrue(json, json.contains("\"uid\": \"{{\$uuid}}\""))
        save("employee-placeholders.json", json)
    }

    private fun copyAt(controllerWithCaret: String, actionId: String = "ApiHttpGenerator.CopyAsJsonPlaceholders"): String {
        listOf(
            "package org.springframework.web.bind.annotation; public @interface RequestParam { String value() default \"\"; String name() default \"\"; }",
            "package org.springframework.web.bind.annotation; public @interface RequestBody {}",
            "package demo; public interface EmployeeDAO { boolean update(int id, double salary); }",
        ).forEach { myFixture.addClass(it) }
        myFixture.configureByText("EmployeeController.java", controllerWithCaret)
        myFixture.performEditorAction(actionId)
        return CopyPasteManager.getInstance().getContents<String>(DataFlavor.stringFlavor)!!
    }

    private fun controller(body: String) = """
        package demo;
        import org.springframework.web.bind.annotation.*;
        public class EmployeeController {
            private EmployeeDAO employeeDAO;
            $body
        }
    """.trimIndent()

    private val updateSalary =
        "public Boolean update<m>Salary(@RequestParam(\"empId\") int em<p>pId, @RequestParam(\"salary\") double salary) { return employeeDAO.update(empId, salary); }"

    fun `test caret on a primitive parameter copies only that parameter`() {
        val json = copyAt(controller(updateSalary.replace("<m>", "").replace("<p>", "<caret>")))
        assertEquals("{\n  \"empId\": {{\$random.integer(1, 1001)}}\n}", json)
    }

    fun `test caret on the method name copies all its parameters`() {
        val json = copyAt(controller(updateSalary.replace("<m>", "<caret>").replace("<p>", "")))
        assertEquals("{\n  \"empId\": {{\$random.integer(1, 1001)}},\n  \"salary\": {{\$random.float(1000, 5000000)}}\n}", json)
    }

    fun `test caret inside the method body copies the method parameters, not the controller`() {
        val json = copyAt(controller(updateSalary.replace("<m>", "").replace("<p>", "").replace("return", "re<caret>turn")))
        assertTrue(json, json.contains("\"salary\"") && !json.contains("employeeDAO"))
    }

    fun `test caret on a method with a request body copies the body class`() {
        val json = copyAt(controller("public Employee a<caret>dd(@RequestBody Employee employee) { return employee; }"))
        assertTrue(json, json.contains("\"firstName\": \"{{\$random.name.firstName}}\""))
    }

    fun `test caret on a string field copies only that field`() {
        myFixture.configureByText("Person.java", "package demo; public class Person { private String first<caret>Name; private int age; }")
        myFixture.performEditorAction("ApiHttpGenerator.CopyAsJsonPlaceholders")
        val json = CopyPasteManager.getInstance().getContents<String>(DataFlavor.stringFlavor)!!
        assertEquals("{\n  \"firstName\": \"{{\$random.name.firstName}}\"\n}", json)
    }

    fun `test caret on an injected interface field copies nothing`() {
        CopyPasteManager.getInstance().setContents(java.awt.datatransfer.StringSelection("before"))
        val json = copyAt(controller(updateSalary.replace("<m>", "").replace("<p>", "")).replace("private EmployeeDAO employee", "private EmployeeDAO empl<caret>oyee"))
        assertEquals("before", json)
    }

    fun `test caret on the class name copies the whole class`() {
        myFixture.configureByText("Person.java", "package demo; public class Per<caret>son { private String firstName; private int age; }")
        myFixture.performEditorAction("ApiHttpGenerator.CopyAsJsonPlaceholders")
        val json = CopyPasteManager.getInstance().getContents<String>(DataFlavor.stringFlavor)!!
        assertTrue(json, json.contains("\"firstName\"") && json.contains("\"age\""))
    }

    /** Kept under build/ so the placeholders can be sent through ijhttp by hand. */
    private fun save(name: String, json: String) {
        File("build/samples").apply { mkdirs() }.resolve(name).writeText(json)
    }

    companion object {
        private val REAL_JDK = object : DefaultLightProjectDescriptor() {
            override fun getSdk(): Sdk = JavaSdk.getInstance().createJdk("test-jdk", System.getProperty("java.home"), false)
        }
    }
}
