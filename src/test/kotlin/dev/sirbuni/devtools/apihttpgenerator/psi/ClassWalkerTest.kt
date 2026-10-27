package dev.sirbuni.devtools.apihttpgenerator.psi

import com.intellij.openapi.projectRoots.JavaSdk
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.psi.PsiClass
import com.intellij.testFramework.LightProjectDescriptor
import com.intellij.testFramework.LoggedErrorProcessor
import com.intellij.testFramework.fixtures.DefaultLightProjectDescriptor
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import dev.sirbuni.devtools.apihttpgenerator.json.ArrayNode
import dev.sirbuni.devtools.apihttpgenerator.json.CutNode
import dev.sirbuni.devtools.apihttpgenerator.json.DateValue
import dev.sirbuni.devtools.apihttpgenerator.json.FakerValue
import dev.sirbuni.devtools.apihttpgenerator.json.IntegerRange
import dev.sirbuni.devtools.apihttpgenerator.json.IsoTimestamp
import dev.sirbuni.devtools.apihttpgenerator.json.JsonNode
import dev.sirbuni.devtools.apihttpgenerator.json.Letters
import dev.sirbuni.devtools.apihttpgenerator.json.MapNode
import dev.sirbuni.devtools.apihttpgenerator.json.ObjectNode
import dev.sirbuni.devtools.apihttpgenerator.json.OneOf
import dev.sirbuni.devtools.apihttpgenerator.json.PatternValue
import dev.sirbuni.devtools.apihttpgenerator.json.Timing
import dev.sirbuni.devtools.apihttpgenerator.json.UuidValue
import dev.sirbuni.devtools.apihttpgenerator.json.ValueNode

class ClassWalkerTest : LightJavaCodeInsightFixtureTestCase() {

    // The bundled mock JDK is not shipped with the Gradle test setup, so java.util and java.time come from the JDK running the tests.
    override fun getProjectDescriptor(): LightProjectDescriptor = REAL_JDK

    override fun setUp() {
        // The unified IntelliJ IDEA 2026.2 test IDE fails to start one of the Ultimate module's own startup
        // activities (class Z.Z.Z.Z.Z). Unrelated to this plugin, so that error alone is let through.
        LoggedErrorProcessor.executeWith<Throwable>(object : LoggedErrorProcessor() {
            override fun processError(category: String, message: String, details: Array<out String>, t: Throwable?): Set<Action> =
                if (message.contains("[Plugin: com.intellij.modules.ultimate]")) Action.NONE
                else super.processError(category, message, details, t)
        }) { super.setUp() }
        // Stubs for the library annotations and types the walker recognises by name.
        listOf(
            "package jakarta.validation.constraints; public @interface Email {}",
            "package jakarta.validation.constraints; public @interface Past {}",
            "package jakarta.validation.constraints; public @interface Size { int min() default 0; int max() default 2147483647; }",
            "package jakarta.validation.constraints; public @interface Min { long value(); }",
            "package jakarta.validation.constraints; public @interface Max { long value(); }",
            "package jakarta.validation.constraints; public @interface Pattern { String regexp(); }",
            "package jakarta.persistence; public @interface Transient {}",
            "package com.fasterxml.jackson.annotation; public @interface JsonProperty { String value() default \"\"; }",
            "package com.fasterxml.jackson.annotation; public @interface JsonIgnore { boolean value() default true; }",
            "package com.fasterxml.jackson.annotation; public @interface JsonIgnoreProperties { String[] value() default {}; }",
        ).forEach { myFixture.addClass(it) }
    }

    companion object {
        private val REAL_JDK = object : DefaultLightProjectDescriptor() {
            override fun getSdk(): Sdk = JavaSdk.getInstance().createJdk("test-jdk", System.getProperty("java.home"), false)
        }
    }

    private fun walk(source: String, name: String): ObjectNode {
        myFixture.addClass(source)
        val cls: PsiClass = myFixture.findClass(name)
        return ClassWalker().walk(cls) as ObjectNode
    }

    private fun ObjectNode.keys() = fields.map { it.key }
    private operator fun ObjectNode.get(key: String): JsonNode = fields.first { it.key == key }.value
    private fun JsonNode.generator() = (this as ValueNode).generator

    fun `test fields, inheritance, skips, renames and constraints`() {
        myFixture.addClass(
            """
            package demo;
            public abstract class BaseEntity {
                private Long id;
                private java.time.Instant createdAt;
            }
            """.trimIndent()
        )
        myFixture.addClass("package demo; public enum Status { ACTIVE, ON_LEAVE }")
        myFixture.addClass(
            """
            package demo;
            import java.util.List;
            public class Department {
                private String name;
                private List<Employee> employees;
            }
            """.trimIndent()
        )
        val employee = walk(
            """
            package demo;
            import java.util.*;
            import jakarta.validation.constraints.*;
            import com.fasterxml.jackson.annotation.*;
            @JsonIgnoreProperties({"internalNote"})
            public class Employee extends BaseEntity {
                public static final String TABLE = "employees";
                private transient int cache;
                @jakarta.persistence.Transient private String computed;
                @JsonIgnore private String password;
                private String internalNote;
                private String firstName;
                @Email private String contact;
                @Min(18) @Max(60) private int age;
                private Status status;
                @JsonProperty("dept") private Department department;
                private List<String> tags;
                private Map<String, Integer> scores;
                @Past private java.time.LocalDate birthDate;
                @Pattern(regexp = "EMP[0-9]{4}") private String code;
                private UUID uid;
            }
            """.trimIndent(),
            "demo.Employee",
        )

        assertEquals(
            listOf("id", "createdAt", "firstName", "contact", "age", "status", "dept", "tags", "scores", "birthDate", "code", "uid"),
            employee.keys(),
        )
        assertEquals(IntegerRange(1, 1000), employee["id"].generator())
        assertEquals(IsoTimestamp, employee["createdAt"].generator())
        assertEquals(FakerValue("name", "firstName"), employee["firstName"].generator())
        assertEquals(FakerValue("internet", "emailAddress"), employee["contact"].generator())
        assertEquals(IntegerRange(18, 60), employee["age"].generator())
        assertEquals(OneOf(listOf("ACTIVE", "ON_LEAVE")), employee["status"].generator())
        assertEquals(DateValue(Timing.PAST), employee["birthDate"].generator())
        assertEquals(PatternValue("EMP[0-9]{4}"), employee["code"].generator())

        val department = employee["dept"] as ObjectNode
        assertEquals(FakerValue("commerce", "department"), department["name"].generator())
        // Employee -> Department -> employees -> Employee is a cycle: the inner Employee is cut.
        assertEquals(CutNode, (department["employees"] as ArrayNode).element)

        assertEquals(FakerValue("lorem", "word"), ((employee["tags"] as ArrayNode).element).generator())
        assertTrue(employee["scores"] is MapNode)
    }

    fun `test records and size`() {
        val record = walk(
            """
            package demo;
            import jakarta.validation.constraints.Size;
            public record CreateEmployee(String firstName, @Size(min = 3, max = 5) String code, java.util.UUID uid) {}
            """.trimIndent(),
            "demo.CreateEmployee",
        )
        assertEquals(listOf("firstName", "code", "uid"), record.keys())
        assertEquals(Letters(4), record["code"].generator())
        assertEquals(UuidValue, record["uid"].generator())
    }

    fun `test generic type arguments are followed`() {
        myFixture.addClass("package demo; public class Item { private String productName; }")
        myFixture.addClass("package demo; public class Page<T> { private java.util.List<T> content; private long totalElements; }")
        val holder = walk("package demo; public class Holder { private Page<Item> page; }", "demo.Holder")
        val page = holder["page"] as ObjectNode
        val item = (page["content"] as ArrayNode).element as ObjectNode
        assertEquals(FakerValue("commerce", "productName"), item["productName"].generator())
    }

    fun `test nesting stops at the depth limit`() {
        myFixture.addClass("package demo; public class D { private String word; }")
        myFixture.addClass("package demo; public class C { private D d; }")
        myFixture.addClass("package demo; public class B { private C c; }")
        val a = walk("package demo; public class A { private B b; }", "demo.A")
        val c = ((a["b"] as ObjectNode)["c"]) as ObjectNode
        assertEquals(CutNode, c["d"])
    }
}
