package dev.sirbuni.devtools.apihttpgenerator.graphql

import com.intellij.openapi.projectRoots.JavaSdk
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.psi.PsiClass
import com.intellij.testFramework.LightProjectDescriptor
import com.intellij.testFramework.LoggedErrorProcessor
import com.intellij.testFramework.fixtures.DefaultLightProjectDescriptor
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import dev.sirbuni.devtools.apihttpgenerator.json.FakeSource
import dev.sirbuni.devtools.apihttpgenerator.rest.RequestFileService

/** A typical SPQR code-first API: a response wrapper, a paged result, a search input and a mutation input. */
class GraphQlWriterTest : LightJavaCodeInsightFixtureTestCase() {

    override fun getProjectDescriptor(): LightProjectDescriptor = REAL_JDK

    private lateinit var resolver: PsiClass

    override fun setUp() {
        LoggedErrorProcessor.executeWith<Throwable>(object : LoggedErrorProcessor() {
            override fun processError(category: String, message: String, details: Array<out String>, t: Throwable?): Set<Action> =
                if (message.contains("[Plugin: com.intellij.modules.ultimate]")) Action.NONE
                else super.processError(category, message, details, t)
        }) { super.setUp() }
        val spqr = "package io.leangen.graphql.annotations;"
        listOf(
            "$spqr public @interface GraphQLQuery { String name() default \"\"; String description() default \"\"; }",
            "$spqr public @interface GraphQLMutation { String name() default \"\"; String description() default \"\"; }",
            "$spqr public @interface GraphQLArgument { String name() default \"\"; }",
            "$spqr public @interface GraphQLIgnore {}",
            "package io.leangen.graphql.spqr.spring.annotations; public @interface GraphQLApi {}",
            "package lombok; public @interface Getter {}",
            "package demo; @lombok.Getter public class ApiResponse<T> { private boolean success; private int code; private String message; private T data; }",
            "package demo; @lombok.Getter public class Paged<T> { private java.util.List<T> items; private long total; @io.leangen.graphql.annotations.GraphQLIgnore private String internal; }",
            "package demo; public record DepartmentView(java.util.UUID uid, String name, String code) {}",
            "package demo; public class PageRequestParam { private Integer page; private Integer pageSize; }",
            "package demo; public class SearchRequest { private PageRequestParam paging; }",
            "package demo; public record DepartmentInput(String name, String code) {}",
        ).forEach { myFixture.addClass(it) }
        resolver = myFixture.addClass(
            """
            package demo;
            import io.leangen.graphql.annotations.*;
            import java.util.UUID;
            @io.leangen.graphql.spqr.spring.annotations.GraphQLApi
            public class DepartmentResolver {
                @GraphQLQuery(name = "departments")
                public ApiResponse<Paged<DepartmentView>> list(@GraphQLArgument(name = "search") SearchRequest search) { return null; }
                @GraphQLMutation(name = "updateDepartment")
                public ApiResponse<DepartmentView> update(@GraphQLArgument(name = "uid") UUID uid, @GraphQLArgument(name = "input") DepartmentInput input) { return null; }
                @GraphQLQuery(name = "ping")
                public String ping() { return "pong"; }
                public void helper() {}
            }
            """.trimIndent()
        )
    }

    private fun write(methodName: String) =
        GraphQlWriter(source = FakeSource(seed = 5)).write(SpqrOperations.operation(resolver.findMethodsByName(methodName, false).single())!!)

    fun `test query with input variables and a three level selection`() {
        val text = write("list").text
        assertTrue(text, text.startsWith("### GRAPHQL departments\n# @name departments\nGRAPHQL {{HOST}}/graphql\nAuthorization: Bearer {{access_token}}\n\n"))
        assertTrue(text, text.contains("query Departments(\$search: SearchRequestInput!) {\n  departments(search: \$search) {"))
        assertTrue(text, text.contains("    data {\n      items {\n        uid\n        name\n        code\n      }\n      total\n    }"))
        assertFalse(text, text.contains("internal"))
        // The same lines the README shows.
        assertTrue(text, text.contains("{\n  \"search\": {\n    \"paging\": {\n      \"page\": 0,\n      \"pageSize\": 10\n    }\n  }\n}\n"))
        assertTrue(text, text.contains("  departments(search: \$search) {\n    success\n    code\n    message\n    data {"))
    }

    fun `test mutation ids are file variables and inputs are typed by SPQR naming`() {
        val request = write("update")
        assertTrue(request.text, request.text.contains("mutation UpdateDepartment(\$uid: UUID!, \$input: DepartmentInputInput!)"))
        assertTrue(request.text, request.text.contains("\"uid\": \"{{uid}}\""))
        assertEquals(setOf("uid"), request.fileVariables.keys)
        assertEquals("mutation UpdateDepartment(\$uid: UUID!, \$input: DepartmentInputInput!) {", request.requestLine)
    }

    fun `test Spring Data Page is selected as an object and own ids in inputs are null`() {
        myFixture.addClass(
            """
            package org.springframework.data.domain;
            public interface Slice<T> extends Iterable<T> { java.util.List<T> getContent(); int getNumber(); }
            """.trimIndent()
        )
        myFixture.addClass("package org.springframework.data.domain; public interface Page<T> extends Slice<T> { long getTotalElements(); }")
        myFixture.addClass("package demo; public class TypeDto { private java.util.UUID uid; private String name; private java.util.UUID ownerUid; }")
        resolver = myFixture.addClass(
            """
            package demo;
            import io.leangen.graphql.annotations.*;
            public class TypeResolver {
                @GraphQLQuery(name = "typesPage")
                public org.springframework.data.domain.Page<DepartmentView> page(@GraphQLArgument(name = "uid") java.util.UUID uid) { return null; }
                @GraphQLMutation(name = "saveType")
                public DepartmentView save(@GraphQLArgument(name = "dto") TypeDto dto) { return null; }
            }
            """.trimIndent()
        )
        val page = write("page")
        assertTrue(page.text, page.text.contains("content {\n      uid\n      name\n      code\n    }"))
        assertTrue(page.text, page.text.contains("totalElements") && page.text.contains("number"))
        assertTrue(page.text, page.text.contains("\"uid\": \"{{uid}}\""))
        val save = write("save").text
        assertTrue(save, save.contains("\"uid\": null") && save.contains("\"ownerUid\": \"{{\$uuid}}\""))
    }

    fun `test a scalar result has no selection and no variables`() {
        val text = write("ping").text
        assertTrue(text, text.contains("query Ping {\n  ping\n}\n"))
        assertFalse(text, text.contains("\n{\n"))
    }

    fun `test a resolver class gets a login request first and env keys`() {
        myFixture.addFileToProject("src/main/resources/application.properties", "server.port=8081\ngraphql.spqr.http.endpoint=/graphql\n")
        RequestFileService.generate(project, resolver)
        RequestFileService.generate(project, resolver)
        val dir = VfsUtil.findRelativeFile(myFixture.findFileInTempDir("."), ".local", "http")!!
        val http = VfsUtil.loadText(dir.findChild("DepartmentResolver.http")!!)
        assertTrue(http, http.startsWith("@uid = 00000000-0000-0000-0000-000000000000\n\n### POST login"))
        assertEquals(1, Regex("(?m)^# @name login$").findAll(http).count())
        assertEquals(3, Regex("(?m)^GRAPHQL ").findAll(http).count())
        val env = VfsUtil.loadText(dir.findChild("http-client.env.json")!!)
        assertTrue(env, env.contains("\"AUTH_URL\": \"http://localhost:8081/oauth2/token\""))
        val private = VfsUtil.loadText(dir.findChild("http-client.private.env.json")!!)
        assertTrue(private, private.contains("\"CLIENT_SECRET\": \"\"") && private.contains("\"PASSWORD\": \"\""))
    }

    companion object {
        private val REAL_JDK = object : DefaultLightProjectDescriptor() {
            override fun getSdk(): Sdk = JavaSdk.getInstance().createJdk("test-jdk", System.getProperty("java.home"), false)
        }
    }
}
