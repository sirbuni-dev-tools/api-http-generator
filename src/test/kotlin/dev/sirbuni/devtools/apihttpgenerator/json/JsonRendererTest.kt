package dev.sirbuni.devtools.apihttpgenerator.json

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JsonRendererTest {

    private val employee = ObjectNode(
        listOf(
            JsonField("firstName", ValueNode(FakerValue("name", "firstName"))),
            JsonField("salary", ValueNode(IntegerRange(1000, 5000))),
            JsonField("active", ValueNode(BooleanValue)),
            JsonField("skills", ArrayNode(ValueNode(OneOf(listOf("java"))))),
            JsonField("manager", CutNode),
            JsonField("extra", MapNode(NullNode)),
        )
    )

    @Test
    fun `placeholders are quoted for strings only and fixed values fill the gaps`() {
        val json = JsonRenderer(RenderMode.PLACEHOLDERS, FakeSource(seed = 7)).render(employee)
        val expected = """
            {
              "firstName": "{{${'$'}random.name.firstName}}",
              "salary": {{${'$'}random.integer(1000, 5001)}},
              "active": ACTIVE,
              "skills": [
                "java"
              ],
              "manager": {},
              "extra": {
                "key": null
              }
            }
        """.trimIndent()
        val active = Regex("\"active\": (true|false)").find(json)!!.groupValues[1]
        assertEquals(expected.replace("ACTIVE", active), json)
    }

    @Test
    fun `fixed mode writes plain values`() {
        val json = JsonRenderer(RenderMode.FIXED, FakeSource(seed = 7)).render(employee)
        assertTrue(json, Regex("\"firstName\": \"[^{\"]+\"").containsMatchIn(json))
        assertTrue(json, Regex("\"salary\": \\d{4}").containsMatchIn(json))
        assertTrue(json, !json.contains("{{"))
    }

    @Test
    fun `strings are escaped`() {
        assertEquals("\"a\\\"b\\\\c\\nd\"", JsonRenderer.quote("a\"b\\c\nd"))
    }
}
