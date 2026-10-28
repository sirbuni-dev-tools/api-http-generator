package dev.sirbuni.devtools.apihttpgenerator.json

import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * The fake-value tables from `rules.json`, which the httpgen CLI shares. The decisions that use them
 * (constraints first, then names, then types) live in [FieldHints].
 */
object Rules {

    /** A field-name hint: the names it matches, the value kinds it applies to and how it generates. */
    class Hint(val names: List<String>, val kinds: Set<ValueKind>, val spec: JsonObject)

    private val root: JsonObject = javaClass.getResourceAsStream("/rules.json").use { stream ->
        requireNotNull(stream) { "rules.json is missing from the plugin" }
        JsonParser.parseReader(stream.reader()).asJsonObject
    }

    val password: String = root["password"].asString

    private val kindGroups: Map<String, Set<ValueKind>> = root.getAsJsonObject("kindGroups").entrySet()
        .associate { (group, kinds) -> group to kinds.asJsonArray.map { ValueKind.valueOf(it.asString) }.toSet() }

    val hints: List<Hint> = root.getAsJsonArray("hints").map { element ->
        val o = element.asJsonObject
        Hint(o.getAsJsonArray("names").map { it.asString }, kindGroups.getValue(o["kinds"].asString), o)
    }

    /** For a plain `name` field: the first entry whose words appear in the class name; the last entry has none and always matches. */
    val nameByOwner: List<Pair<List<String>, JsonObject>> = root.getAsJsonArray("nameByOwner").map { element ->
        val o = element.asJsonObject
        o.getAsJsonArray("contains").map { it.asString } to o
    }

    val types: Map<ValueKind, JsonObject> = root.getAsJsonObject("types").entrySet()
        .associate { (kind, spec) -> ValueKind.valueOf(kind) to spec.asJsonObject }

    val ownIds: Set<String> = root.getAsJsonArray("ownIds").map { it.asString }.toSet()

    val criteriaContains: List<String> = root.getAsJsonObject("criteriaLists").getAsJsonArray("contains").map { it.asString }
    val criteriaExact: Set<String> = root.getAsJsonObject("criteriaLists").getAsJsonArray("exact").map { it.asString }.toSet()

    val verifiedPlaceholders: Set<String> = root.getAsJsonArray("verifiedPlaceholders").map { it.asString }.toSet()

    /** Turns one generator spec into a [ValueGenerator] for a field of the given kind. */
    fun generator(spec: JsonObject, kind: ValueKind, owner: String = ""): ValueGenerator {
        fun flag(key: String) = spec.has(key) && spec[key].asBoolean
        return when {
            spec.has("faker") -> spec["faker"].asString.split('.', limit = 2).let { (p, m) -> FakerValue(p, m, kind == ValueKind.STRING) }
            flag("byOwner") -> nameFor(owner)
            flag("password") -> OneOf(listOf(password))
            spec.has("range") -> spec.getAsJsonArray("range").let { r ->
                if (kind == ValueKind.DECIMAL) DecimalRange(r[0].asDouble, r[1].asDouble) else IntegerRange(r[0].asLong, r[1].asLong)
            }
            spec.has("oneOf") -> OneOf(spec.getAsJsonArray("oneOf").map { it.asString })
            spec.has("letters") -> Letters(spec["letters"].asInt, flag("digits"))
            flag("uuid") -> UuidValue
            flag("null") -> NullValue
            flag("boolean") -> BooleanValue
            flag("date") -> DateValue()
            flag("dateTime") -> DateTimeValue()
            flag("time") -> TimeValue
            flag("isoTimestamp") -> IsoTimestamp
            flag("base64") -> Base64Value
            else -> error("rules.json: unknown generator $spec")
        }
    }

    private fun nameFor(owner: String): ValueGenerator {
        val o = owner.lowercase()
        val spec = nameByOwner.first { (words, _) -> words.isEmpty() || words.any { o.contains(it) } }.second
        return generator(spec, ValueKind.STRING)
    }
}
