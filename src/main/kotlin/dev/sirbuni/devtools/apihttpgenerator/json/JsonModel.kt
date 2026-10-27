package dev.sirbuni.devtools.apihttpgenerator.json

/**
 * A class reduced to the shape of its JSON, with a value generator on every leaf.
 * Built once from PSI, then rendered as fixed fake values or as HTTP Client placeholders.
 */
sealed interface JsonNode

data class ObjectNode(val fields: List<JsonField>) : JsonNode

data class JsonField(val key: String, val value: JsonNode)

data class ArrayNode(val element: JsonNode) : JsonNode

/** A `Map<K, V>` field; rendered with a single sample key. */
data class MapNode(val value: JsonNode) : JsonNode

data class ValueNode(val generator: ValueGenerator) : JsonNode

/** A class already on the walk path, or past the depth limit: rendered as `{}`. */
data object CutNode : JsonNode

data object NullNode : JsonNode
