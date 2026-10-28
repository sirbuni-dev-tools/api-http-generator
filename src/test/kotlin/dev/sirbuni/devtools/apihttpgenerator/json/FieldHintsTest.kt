package dev.sirbuni.devtools.apihttpgenerator.json

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FieldHintsTest {

    private fun resolve(name: String, kind: ValueKind = ValueKind.STRING, owner: String = "", constraints: Constraints = Constraints()) =
        FieldHints.resolve(FieldContext(name, kind, owner, constraints = constraints))

    @Test
    fun `name hints ignore case and underscores`() {
        assertEquals(FakerValue("name", "firstName"), resolve("firstName"))
        assertEquals(FakerValue("name", "firstName"), resolve("first_name"))
        assertEquals(FakerValue("name", "lastName"), resolve("SURNAME"))
        assertEquals(FakerValue("internet", "emailAddress"), resolve("email"))
        assertEquals(FakerValue("phoneNumber", "cellPhone"), resolve("phone"))
    }

    @Test
    fun `name follows the class that declares it`() {
        assertEquals(FakerValue("company", "name"), resolve("name", owner = "Institution"))
        assertEquals(FakerValue("commerce", "department"), resolve("name", owner = "Department"))
        assertEquals(FakerValue("name", "title"), resolve("name", owner = "RoleDto"))
        assertEquals(FakerValue("name", "fullName"), resolve("name", owner = "Employee"))
        assertEquals(FakerValue("lorem", "word"), resolve("name", owner = "Category"))
    }

    @Test
    fun `the type decides between hints that share a name`() {
        assertEquals(UuidValue, resolve("id"))
        assertEquals(IntegerRange(1, 1000), resolve("id", ValueKind.LONG))
        assertEquals(IntegerRange(1000, 5_000_000), resolve("salary", ValueKind.INTEGER))
        assertEquals(DecimalRange(1000.0, 5_000_000.0), resolve("salary", ValueKind.DECIMAL))
    }

    @Test
    fun `the last camel-case words match when the whole name does not`() {
        assertEquals(FakerValue("internet", "emailAddress"), resolve("customerEmail"))
        assertEquals(IntegerRange(1, 1000), resolve("createdById", ValueKind.LONG))
        assertEquals(FakerValue("name", "firstName"), resolve("employeeFirstName"))
    }

    @Test
    fun `unknown names fall back to the type`() {
        assertEquals(FakerValue("lorem", "word"), resolve("zebra"))
        assertEquals(IntegerRange(1, 1000), resolve("zebra", ValueKind.INTEGER))
        assertEquals(BooleanValue, resolve("active", ValueKind.BOOLEAN))
        assertEquals(DateValue(), resolve("startDate", ValueKind.LOCAL_DATE))
        assertEquals(IsoTimestamp, resolve("createdAt", ValueKind.INSTANT))
        assertEquals(UuidValue, resolve("anything", ValueKind.UUID))
    }

    @Test
    fun `paging starts at the first page and sorting is left unset`() {
        assertEquals(IntegerRange(0, 0), resolve("first", ValueKind.INTEGER))
        assertEquals(IntegerRange(0, 0), resolve("page", ValueKind.INTEGER))
        assertEquals(IntegerRange(10, 10), resolve("pageSize", ValueKind.INTEGER))
        assertEquals(NullValue, resolve("sortBy"))
        assertEquals(OneOf(listOf("ASC", "DESC")), resolve("sortDirection"))
    }

    @Test
    fun `a password and its confirmation get the same value`() {
        assertEquals(FieldHints.PASSWORD, resolve("password", constraints = Constraints(minSize = 8, maxSize = 20)))
        assertEquals(FieldHints.PASSWORD, resolve("confirmPassword"))
        assertEquals(FieldHints.PASSWORD, resolve("newPassword"))
    }

    @Test
    fun `enums pick from their constants`() {
        val generator = FieldHints.resolve(FieldContext("status", ValueKind.ENUM, enumConstants = listOf("OPEN", "CLOSED")))
        assertEquals(OneOf(listOf("OPEN", "CLOSED")), generator)
    }

    @Test
    fun `constraints come before name hints`() {
        assertEquals(PatternValue("[A-Z]{3}[0-9]{4}"), resolve("firstName", constraints = Constraints(pattern = "[A-Z]{3}[0-9]{4}")))
        assertEquals(FakerValue("internet", "emailAddress"), resolve("contact", constraints = Constraints(email = true)))
        assertEquals(IntegerRange(18, 60), resolve("age", ValueKind.INTEGER, constraints = Constraints(min = 18.0, max = 60.0)))
        assertEquals(DateValue(Timing.PAST), resolve("birthDate", ValueKind.LOCAL_DATE, constraints = Constraints(timing = Timing.PAST)))
    }

    @Test
    fun `size keeps a fitting hint and shortens the rest`() {
        assertEquals(FakerValue("name", "firstName"), resolve("firstName", constraints = Constraints(minSize = 2, maxSize = 50)))
        assertEquals(Letters(4), resolve("firstName", constraints = Constraints(minSize = 3, maxSize = 5)))
    }

    @Test
    fun `placeholders for numbers exclude the upper bound the way the HTTP Client does`() {
        assertEquals("\$random.integer(18, 61)", IntegerRange(18, 60).placeholder)
        assertNull(IntegerRange(0, Long.MAX_VALUE).placeholder)
        assertEquals("\$random.float(0, 100)", DecimalRange(0.0, 100.0).placeholder)
    }

    @Test
    fun `every Faker method a hint uses exists in Datafaker`() {
        val source = FakeSource(seed = 1)
        val fakerValues = FieldHints.allHintGenerators().filterIsInstance<FakerValue>()
        assertTrue(fakerValues.size > 30)
        val missing = fakerValues.filter { runCatching { it.concrete(source).isBlank() }.getOrDefault(true) }
        assertEquals(emptyList<FakerValue>(), missing)
    }

    @Test
    fun `every Faker method a hint uses was verified against the HTTP Client`() {
        val unverified = FieldHints.allHintGenerators().filterIsInstance<FakerValue>()
            .map { "${it.provider}.${it.method}" }
            .filterNot { it in Rules.verifiedPlaceholders }
        assertEquals(emptyList<String>(), unverified)
    }
}
