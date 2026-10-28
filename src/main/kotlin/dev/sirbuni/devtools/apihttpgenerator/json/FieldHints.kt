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

    /** Upper and lower case, digits and a symbol: passes the usual password policies. Shared by a password and its confirmation. */
    val PASSWORD = OneOf(listOf(Rules.password))

    // The table lives in rules.json, shared with the httpgen CLI. Names are matched lower-case with
    // underscores removed, so first_name, firstName and FIRSTNAME all match; the first hint for the field's kind wins.
    private val hints = Rules.hints.map { h ->
        Hint(h.names.toSet(), h.kinds) { ctx -> Rules.generator(h.spec, ctx.kind, ctx.owner) }
    }

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
            ctx.kind == ValueKind.STRING && (c.minSize != null || c.maxSize != null) && !ctx.name.lowercase().contains("password") -> sizedText(ctx, c)
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
        ValueKind.ENUM -> if (ctx.enumConstants.isEmpty()) OneOf(listOf("VALUE")) else OneOf(ctx.enumConstants)
        else -> Rules.generator(Rules.types.getValue(ctx.kind), ctx.kind)
    }
}
