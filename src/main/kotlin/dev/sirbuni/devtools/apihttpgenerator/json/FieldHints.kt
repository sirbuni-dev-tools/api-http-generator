package dev.sirbuni.devtools.apihttpgenerator.json

/** The Java type of a leaf, reduced to what decides its fake value. */
enum class ValueKind {
    STRING, CHAR, INTEGER, LONG, BYTE, DECIMAL, BIG_INTEGER, BOOLEAN,
    UUID, LOCAL_DATE, LOCAL_DATE_TIME, LOCAL_TIME, INSTANT, ENUM, BYTES, OTHER;

    val isWholeNumber get() = this == INTEGER || this == LONG || this == BYTE || this == BIG_INTEGER
    val isNumber get() = isWholeNumber || this == DECIMAL
}

/** Jakarta (or javax) validation constraints found on a field. Null means the constraint is absent. */
data class Constraints(
    val email: Boolean = false,
    val minSize: Int? = null,
    val maxSize: Int? = null,
    val min: Double? = null,
    val max: Double? = null,
    val pattern: String? = null,
    val timing: Timing = Timing.ANY,
)

data class FieldContext(
    val name: String,
    val kind: ValueKind,
    /** Simple name of the class declaring the field, for hints such as `name` on a company. */
    val owner: String = "",
    val enumConstants: List<String> = emptyList(),
    val constraints: Constraints = Constraints(),
)

/**
 * Picks a generator for a field: validation constraints first, then the field name, then the type.
 * Every Faker method used here was checked to resolve in the HTTP Client's `$random`.
 */
object FieldHints {

    private class Hint(val names: Set<String>, val kinds: Set<ValueKind>, val generator: (FieldContext) -> ValueGenerator)

    private val text = setOf(ValueKind.STRING)
    private val whole = setOf(ValueKind.INTEGER, ValueKind.LONG, ValueKind.BIG_INTEGER)
    private val numbers = setOf(ValueKind.INTEGER, ValueKind.LONG, ValueKind.BIG_INTEGER, ValueKind.DECIMAL)
    private val organisations = listOf("company", "institution", "organization", "organisation", "employer", "vendor", "supplier", "bank")
    private val people = listOf("employee", "user", "person", "customer", "member", "staff", "student", "contact", "trainee",
        "applicant", "author", "owner", "manager", "client", "patient", "teacher", "admin", "account")

    /** A plain `name` field means different things on different classes. */
    private fun nameFor(owner: String): ValueGenerator {
        val o = owner.lowercase()
        return when {
            organisations.any { o.contains(it) } -> FakerValue("company", "name")
            o.contains("department") || o.contains("dept") -> FakerValue("commerce", "department")
            o.contains("product") || o.contains("item") -> FakerValue("commerce", "productName")
            o.contains("university") || o.contains("school") || o.contains("college") -> FakerValue("university", "name")
            o.contains("city") || o.contains("town") -> FakerValue("address", "city")
            o.contains("country") -> FakerValue("address", "country")
            o.contains("course") || o.contains("training") -> FakerValue("educator", "course")
            o.contains("role") || o.contains("designation") || o.contains("position") -> FakerValue("name", "title")
            people.any { o.contains(it) } -> FakerValue("name", "fullName")
            else -> FakerValue("lorem", "word")
        }
    }

    private fun hint(kinds: Set<ValueKind>, vararg names: String, generator: (FieldContext) -> ValueGenerator) =
        Hint(names.toSet(), kinds, generator)

    private fun faker(provider: String, method: String) = { _: FieldContext -> FakerValue(provider, method) }

    // Names are matched lower-case with underscores removed, so first_name, firstName and FIRSTNAME all match.
    private val hints = listOf(
        hint(text, "email", "emailaddress", "mail", "workemail", "personalemail", generator = faker("internet", "emailAddress")),
        hint(text, "firstname", "givenname", "fname", "forename", "middlename", generator = faker("name", "firstName")),
        hint(text, "lastname", "surname", "familyname", "lname", generator = faker("name", "lastName")),
        hint(text, "fullname", "displayname", "contactname", "contactperson", generator = faker("name", "fullName")),
        hint(text, "name") { ctx -> nameFor(ctx.owner) },
        hint(text, "username", "login", "loginname", "userid", generator = faker("name", "username")),
        hint(text, "password", "pwd", "pass", "newpassword", "oldpassword", "confirmpassword", generator = faker("internet", "password")),
        hint(text, "phone", "phonenumber", "mobile", "mobilenumber", "msisdn", "telephone", "tel", "cellphone", "cell", generator = faker("phoneNumber", "cellPhone")),
        hint(text, "url", "website", "homepage", "link", "weburl", generator = faker("internet", "url")),
        hint(text, "domain", "domainname", generator = faker("internet", "domainName")),
        hint(text, "ip", "ipaddress", "clientip", generator = faker("internet", "ipV4Address")),
        // internet.avatar is not used: Datafaker has no such method and Java Faker's avatar URLs no longer resolve.
        hint(text, "image", "imageurl", "logo", "logourl", "avatar", "photo", "profilepicture", "picture", generator = faker("internet", "image")),
        hint(text, "slug", generator = faker("internet", "slug")),
        hint(text, "city", "town", "district", generator = faker("address", "city")),
        hint(text, "street", "streetaddress", "addressline", "addressline1", "physicaladdress", generator = faker("address", "streetAddress")),
        hint(text, "address", "fulladdress", "postaladdress", "location", generator = faker("address", "fullAddress")),
        hint(text, "zip", "zipcode", "postcode", "postalcode", generator = faker("address", "zipCode")),
        hint(text, "country", "nationality", generator = faker("address", "country")),
        hint(text, "countrycode", generator = faker("address", "countryCode")),
        hint(text, "region", "state", "province", generator = faker("address", "state")),
        hint(setOf(ValueKind.STRING, ValueKind.DECIMAL), "latitude", "lat") { ctx -> FakerValue("address", "latitude", ctx.kind == ValueKind.STRING) },
        hint(setOf(ValueKind.STRING, ValueKind.DECIMAL), "longitude", "lng", "lon", "long") { ctx -> FakerValue("address", "longitude", ctx.kind == ValueKind.STRING) },
        hint(text, "company", "companyname", "organization", "organisation", "organizationname", "institution", "institutionname", "employer", "vendor", "supplier", generator = faker("company", "name")),
        hint(text, "department", "dept", "departmentname", generator = faker("commerce", "department")),
        hint(text, "industry", "sector", generator = faker("company", "industry")),
        hint(text, "profession", "occupation", generator = faker("company", "profession")),
        hint(text, "title", "jobtitle", "designation", "position", "role", generator = faker("name", "title")),
        hint(text, "product", "productname", "item", "itemname", generator = faker("commerce", "productName")),
        hint(text, "description", "notes", "note", "comment", "comments", "remarks", "remark", "summary", "details", "reason", generator = faker("lorem", "sentence")),
        hint(text, "message", "body", "content", "text", generator = faker("lorem", "paragraph")),
        hint(text, "word", "tag", "keyword", "label", generator = faker("lorem", "word")),
        hint(text, "color", "colour", generator = faker("color", "name")),
        hint(text, "university", "school", "college", generator = faker("university", "name")),
        hint(text, "course", "programme", "program", generator = faker("educator", "course")),
        hint(text, "iban", "accountnumber", "bankaccount", generator = faker("finance", "iban")),
        hint(text, "bic", "swift", "swiftcode", generator = faker("finance", "bic")),
        hint(text, "isbn", generator = faker("code", "isbn13")),
        hint(text, "nin", "nida", "nationalid", "idnumber", generator = faker("idNumber", "valid")),
        hint(text, "currency", "currencycode") { OneOf(listOf("TZS", "USD", "EUR", "KES")) },
        hint(text, "gender", "sex") { OneOf(listOf("MALE", "FEMALE")) },
        hint(text, "code", "shortcode", "refcode", "reference", "referencenumber", "controlnumber") { Letters(8, withDigits = true) },
        hint(text, "token", "hash", "secret", "apikey", "checksum") { Letters(32, withDigits = true) },
        hint(text, "uid", "uuid", "guid", "id", "externalid", "trackingid") { UuidValue },
        hint(text, "price", "amount", "cost") { FakerValue("commerce", "price") },
        hint(numbers, "salary", "amount", "price", "cost", "fee", "fees", "balance", "total", "budget", "income", "grossamount", "netamount", "basicsalary") { ctx ->
            if (ctx.kind == ValueKind.DECIMAL) DecimalRange(1000.0, 5_000_000.0) else IntegerRange(1000, 5_000_000)
        },
        hint(whole, "age") { IntegerRange(18, 65) },
        hint(whole, "year", "financialyear", "fiscalyear") { IntegerRange(2020, 2030) },
        hint(whole, "month") { IntegerRange(1, 12) },
        hint(whole, "quantity", "qty", "count", "number", "total", "stock", "capacity", "size") { IntegerRange(1, 100) },
        hint(numbers, "percent", "percentage", "rate", "ratio", "score") { ctx ->
            if (ctx.kind == ValueKind.DECIMAL) DecimalRange(0.0, 100.0) else IntegerRange(0, 100)
        },
        hint(whole, "id", "version", "page") { IntegerRange(1, 1000) },
    )

    private val hintsByName: Map<String, List<Hint>> =
        hints.flatMap { h -> h.names.map { it to h } }.groupBy({ it.first }, { it.second })

    /** Every generator a name hint can return, so tests can check each Faker method exists on both sides. */
    internal fun allHintGenerators(): List<ValueGenerator> = hints.flatMap { h ->
        h.kinds.map { kind -> h.generator(FieldContext(h.names.first(), kind, owner = "Company")) } +
            h.kinds.map { kind -> h.generator(FieldContext(h.names.first(), kind)) }
    }.distinct()

    fun resolve(ctx: FieldContext): ValueGenerator {
        fromConstraints(ctx)?.let { return it }
        val key = ctx.name.lowercase().replace("_", "")
        val byName = hintsByName[key]?.firstOrNull { ctx.kind in it.kinds }
            ?: suffixHint(key, ctx)
        if (byName != null) return byName.generator(ctx)
        return fromType(ctx)
    }

    /** `createdById` or `customerEmail`: try the last camel-case words when the whole name matched nothing. */
    private fun suffixHint(key: String, ctx: FieldContext): Hint? {
        val words = ctx.name.split(Regex("(?<=[a-z0-9])(?=[A-Z])|_")).filter { it.isNotEmpty() }.map { it.lowercase() }
        for (start in 1 until words.size) {
            val tail = words.drop(start).joinToString("")
            if (tail == key) continue
            hintsByName[tail]?.firstOrNull { ctx.kind in it.kinds }?.let { return it }
        }
        return null
    }

    private fun fromConstraints(ctx: FieldContext): ValueGenerator? {
        val c = ctx.constraints
        return when {
            ctx.kind == ValueKind.STRING && c.pattern != null -> PatternValue(c.pattern)
            ctx.kind == ValueKind.STRING && c.email -> FakerValue("internet", "emailAddress")
            ctx.kind == ValueKind.STRING && (c.minSize != null || c.maxSize != null) -> sizedText(ctx, c)
            ctx.kind.isWholeNumber && (c.min != null || c.max != null) -> {
                val min = c.min?.toLong() ?: 0L
                IntegerRange(min, c.max?.toLong() ?: maxOf(min + 1000, 1000))
            }
            ctx.kind == ValueKind.DECIMAL && (c.min != null || c.max != null) -> {
                val min = c.min ?: 0.0
                DecimalRange(min, c.max ?: maxOf(min + 1000.0, 1000.0))
            }
            ctx.kind == ValueKind.LOCAL_DATE && c.timing != Timing.ANY -> DateValue(c.timing)
            ctx.kind == ValueKind.LOCAL_DATE_TIME && c.timing != Timing.ANY -> DateTimeValue(c.timing)
            else -> null
        }
    }

    /** `@Size` on a string: keep the name hint when its values fit, otherwise letters of a fitting length. */
    private fun sizedText(ctx: FieldContext, c: Constraints): ValueGenerator {
        val min = c.minSize ?: 0
        val max = c.maxSize ?: maxOf(min, 20)
        if (max >= 30 && min <= 3) {
            val hinted = resolve(ctx.copy(constraints = Constraints()))
            if (hinted is FakerValue) return hinted
        }
        return Letters(((min + max) / 2).coerceIn(maxOf(min, 1), maxOf(max, 1)))
    }

    private fun fromType(ctx: FieldContext): ValueGenerator = when (ctx.kind) {
        ValueKind.STRING -> FakerValue("lorem", "word")
        ValueKind.CHAR -> Letters(1)
        ValueKind.INTEGER, ValueKind.LONG, ValueKind.BIG_INTEGER -> IntegerRange(1, 1000)
        ValueKind.BYTE -> IntegerRange(0, 100)
        ValueKind.DECIMAL -> DecimalRange(1.0, 1000.0)
        ValueKind.BOOLEAN -> BooleanValue
        ValueKind.UUID -> UuidValue
        ValueKind.LOCAL_DATE -> DateValue()
        ValueKind.LOCAL_DATE_TIME -> DateTimeValue()
        ValueKind.LOCAL_TIME -> TimeValue
        ValueKind.INSTANT -> IsoTimestamp
        ValueKind.ENUM -> if (ctx.enumConstants.isEmpty()) OneOf(listOf("VALUE")) else OneOf(ctx.enumConstants)
        ValueKind.BYTES -> Base64Value
        ValueKind.OTHER -> OneOf(listOf("null"), isString = false)
    }
}
