package dev.sirbuni.devtools.apihttpgenerator.psi

import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiArrayType
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiClassType
import com.intellij.psi.PsiEnumConstant
import com.intellij.psi.PsiModifier
import com.intellij.psi.PsiModifierListOwner
import com.intellij.psi.PsiPrimitiveType
import com.intellij.psi.PsiSubstitutor
import com.intellij.psi.PsiType
import com.intellij.psi.PsiTypeParameter
import com.intellij.psi.PsiTypes
import com.intellij.psi.PsiVariable
import com.intellij.psi.PsiWildcardType
import com.intellij.psi.util.InheritanceUtil
import com.intellij.psi.util.PsiUtil
import com.intellij.psi.util.TypeConversionUtil
import dev.sirbuni.devtools.apihttpgenerator.json.ArrayNode
import dev.sirbuni.devtools.apihttpgenerator.json.Constraints
import dev.sirbuni.devtools.apihttpgenerator.json.CutNode
import dev.sirbuni.devtools.apihttpgenerator.json.EmptyArrayNode
import dev.sirbuni.devtools.apihttpgenerator.json.FieldContext
import dev.sirbuni.devtools.apihttpgenerator.json.FieldHints
import dev.sirbuni.devtools.apihttpgenerator.json.JsonField
import dev.sirbuni.devtools.apihttpgenerator.json.JsonNode
import dev.sirbuni.devtools.apihttpgenerator.json.MapNode
import dev.sirbuni.devtools.apihttpgenerator.json.NullNode
import dev.sirbuni.devtools.apihttpgenerator.json.ObjectNode
import dev.sirbuni.devtools.apihttpgenerator.json.Timing
import dev.sirbuni.devtools.apihttpgenerator.json.ValueKind
import dev.sirbuni.devtools.apihttpgenerator.json.ValueNode

/**
 * Reduces a Java type to the JSON Jackson would read or write for it.
 *
 * Follows fields (superclass first) or record components, skips static, transient,
 * `@Transient` and `@JsonIgnore` members, renames by `@JsonProperty`, and reads validation
 * constraints. Nested classes stop at [maxDepth]; a class already on the path becomes `{}`.
 */
class ClassWalker(
    private val maxDepth: Int = 3,
    /** Leave out `@GeneratedValue` fields, which a create request must not send (Hibernate then expects the row to exist). */
    private val skipGenerated: Boolean = false,
    /** Also follow public getters without a matching field (such as a computed `getCode()`), as SPQR's output types do. */
    private val includeGetters: Boolean = false,
) {

    /** [inObject] is true for a field of a payload class, false for a top-level parameter or argument. */
    private data class Member(val name: String, val owner: String, val annotated: PsiModifierListOwner?, val inObject: Boolean = false)

    fun walk(psiClass: PsiClass): JsonNode =
        walk(JavaPsiFacade.getElementFactory(psiClass.project).createType(psiClass))

    fun walk(type: PsiType): JsonNode = node(type, Member("", "", null), emptySet(), 0)

    /**
     * Fields, parameters or local variables as one object, keyed by [keys] (for example the
     * `@RequestParam` names). Values follow the same rules as class fields.
     */
    fun walkVariables(variables: List<Pair<String, PsiVariable>>, owner: String): ObjectNode =
        ObjectNode(variables.map { (key, v) -> JsonField(key, node(v.type, Member(v.name ?: key, owner, v), emptySet(), 0)) })

    /** `List<T>`, `Set<T>`, `Map<K, V>` and the like, which render as JSON arrays or objects although they are interfaces. */
    fun isCollectionOrMap(type: PsiType): Boolean {
        val cls = (type as? PsiClassType)?.resolve() ?: return false
        return InheritanceUtil.isInheritor(cls, "java.util.Collection") || InheritanceUtil.isInheritor(cls, "java.util.Map")
    }

    /** Whether a type renders as a JSON object of its own fields (a DTO or entity), not as a single value or list. */
    fun isObjectType(type: PsiType): Boolean {
        val cls = (type as? PsiClassType)?.resolve() ?: return false
        val name = cls.qualifiedName ?: return false
        if (cls is PsiTypeParameter || cls.isEnum || cls.isInterface || name in SIMPLE_KINDS) return false
        if (name.startsWith("java.") || name.startsWith("javax.") || name.startsWith("jakarta.")) return false
        return true
    }

    private fun node(type: PsiType, member: Member, path: Set<String>, depth: Int): JsonNode = when (type) {
        is PsiPrimitiveType -> primitiveKind(type)?.let { leaf(it, member) } ?: NullNode
        is PsiArrayType ->
            if (type.componentType == PsiTypes.byteType()) leaf(ValueKind.BYTES, member)
            else ArrayNode(node(type.componentType, member, path, depth))
        is PsiWildcardType -> node(type.extendsBound, member, path, depth)
        is PsiClassType -> classNode(type, member, path, depth)
        else -> NullNode
    }

    private fun classNode(type: PsiClassType, member: Member, path: Set<String>, depth: Int): JsonNode {
        val resolved = type.resolveGenerics()
        val cls = resolved.element ?: return NullNode
        if (cls is PsiTypeParameter) return NullNode
        val name = cls.qualifiedName ?: return NullNode

        SIMPLE_KINDS[name]?.let { return leaf(it, member) }
        if (cls.isEnum) {
            val constants = cls.fields.filterIsInstance<PsiEnumConstant>().map { jsonName(it) ?: it.name }
            return leaf(ValueKind.ENUM, member, constants)
        }
        if (name == "java.util.Optional") {
            return type.parameters.firstOrNull()?.let { node(it, member, path, depth) } ?: NullNode
        }
        if (InheritanceUtil.isInheritor(cls, "java.util.Map")) {
            val value = PsiUtil.substituteTypeParameter(type, "java.util.Map", 1, false)
            return MapNode(value?.let { node(it, member, path, depth) } ?: NullNode)
        }
        // Collections only: Spring Data's Page is Iterable but Jackson and SPQR write it as an object with content.
        if (InheritanceUtil.isInheritor(cls, "java.util.Collection")) {
            if (isCriteriaList(member.name)) return EmptyArrayNode
            val element = PsiUtil.substituteTypeParameter(type, "java.util.Collection", 0, false)
            return ArrayNode(element?.let { node(it, member, path, depth) } ?: NullNode)
        }
        if (name.startsWith("java.") || name.startsWith("javax.") || name.startsWith("jakarta.")) return NullNode
        if (name in path || depth >= maxDepth) return CutNode
        return objectNode(cls, resolved.substitutor, path + name, depth + 1)
    }

    private fun objectNode(cls: PsiClass, substitutor: PsiSubstitutor, path: Set<String>, depth: Int): JsonNode {
        val ignored = ignoredProperties(cls)
        val fields = mutableListOf<JsonField>()

        if (cls.isRecord) {
            for (component in cls.recordComponents) {
                if (isSkipped(component)) continue
                val key = jsonName(component) ?: component.name
                if (key in ignored) continue
                fields += JsonField(key, node(substitutor.substitute(component.type), Member(component.name, cls.name ?: "", component, inObject = true), path, depth))
            }
            return ObjectNode(fields)
        }

        for (declaring in hierarchy(cls)) {
            val classSubstitutor = if (declaring == cls) substitutor
            else TypeConversionUtil.getSuperClassSubstitutor(declaring, cls, substitutor)
            for (field in declaring.fields) {
                if (field is PsiEnumConstant || field.hasModifierProperty(PsiModifier.STATIC) ||
                    field.hasModifierProperty(PsiModifier.TRANSIENT) || isSkipped(field)
                ) continue
                if (includeGetters && !isExposed(field, cls)) continue
                val key = jsonName(field) ?: field.name
                if (key in ignored || fields.any { it.key == key }) continue
                val type = classSubstitutor.substitute(field.type) ?: field.type
                fields += JsonField(key, node(type, Member(field.name, cls.name ?: "", field, inObject = true), path, depth))
            }
        }
        if (includeGetters) {
            // Interfaces too: Spring Data's Page<T> declares getContent() on Slice, a parent interface.
            for (declaring in listOf(cls) + InheritanceUtil.getSuperClasses(cls).filter { it.qualifiedName != "java.lang.Object" }) {
                val classSubstitutor = if (declaring == cls) substitutor
                else TypeConversionUtil.getSuperClassSubstitutor(declaring, cls, substitutor)
                for (method in declaring.methods) {
                    val key = propertyName(method) ?: continue
                    if (fields.any { it.key == key } || isSkipped(method)) continue
                    val type = classSubstitutor.substitute(method.returnType) ?: continue
                    fields += JsonField(key, node(type, Member(key, cls.name ?: "", method, inObject = true), path, depth))
                }
            }
        }
        return ObjectNode(fields)
    }

    /**
     * Whether an output type shows this field: it is public, Lombok generates its getter (`@Getter`, `@Data`
     * or `@Value` on the field or class), or the class has the getter itself. SPQR exposes nothing else,
     * and asking for a field it does not expose fails the whole query.
     */
    private fun isExposed(field: com.intellij.psi.PsiField, cls: PsiClass): Boolean {
        if (field.hasModifierProperty(PsiModifier.PUBLIC)) return true
        val owner = field.containingClass
        if (LOMBOK_GETTERS.any { field.hasAnnotation(it) || owner?.hasAnnotation(it) == true }) return true
        val suffix = field.name.replaceFirstChar { it.uppercase() }
        return cls.findMethodsByName("get$suffix", true).isNotEmpty() || cls.findMethodsByName("is$suffix", true).isNotEmpty()
    }

    /** Search filters and sort lists: a made-up entry names a field the server does not allow, so they stay empty. */
    private fun isCriteriaList(name: String): Boolean {
        val n = name.lowercase()
        return n.contains("filter") || n.contains("search") || n.contains("criteria") || n in setOf("sort", "sorts", "sortfields", "orderby")
    }

    /** `getCode()` -> `code`, `isActive()` on a boolean -> `active`; null for anything that is not a public getter. */
    private fun propertyName(method: com.intellij.psi.PsiMethod): String? {
        if (!method.hasModifierProperty(PsiModifier.PUBLIC) || method.hasModifierProperty(PsiModifier.STATIC) ||
            method.parameterList.parametersCount > 0 || method.isConstructor
        ) return null
        val returnType = method.returnType ?: return null
        if (returnType == PsiTypes.voidType()) return null
        val name = method.name
        val raw = when {
            name.startsWith("get") && name.length > 3 && name != "getClass" -> name.substring(3)
            name.startsWith("is") && name.length > 2 && (returnType == PsiTypes.booleanType() || returnType.canonicalText == "java.lang.Boolean") -> name.substring(2)
            else -> return null
        }
        return raw.replaceFirstChar { it.lowercase() }
    }

    /** The class and its superclasses, top-most first, without `java.lang.Object`. */
    private fun hierarchy(cls: PsiClass): List<PsiClass> =
        generateSequence(cls) { it.superClass }
            .takeWhile { it.qualifiedName != "java.lang.Object" }
            .toList()
            .reversed()

    private fun leaf(kind: ValueKind, member: Member, enumConstants: List<String> = emptyList()): JsonNode {
        // A request's own id picks the record to update or is generated by the server: never a made-up value.
        if (skipGenerated && member.inObject && member.name.lowercase() in OWN_IDS) return NullNode
        val context = FieldContext(member.name, kind, member.owner, enumConstants, constraints(member.annotated))
        return ValueNode(FieldHints.resolve(context))
    }

    private fun isSkipped(owner: PsiModifierListOwner) =
        annotation(owner, "Transient") != null || annotation(owner, "JsonIgnore")?.let { boolean(it, "value") != false } == true ||
            annotation(owner, "GraphQLIgnore") != null ||
            (skipGenerated && annotation(owner, "GeneratedValue") != null)

    private fun jsonName(owner: PsiModifierListOwner): String? =
        annotation(owner, "JsonProperty")?.let { string(it, "value") }?.takeIf { it.isNotEmpty() }

    private fun ignoredProperties(cls: PsiClass): Set<String> =
        hierarchy(cls).flatMap { c -> annotation(c, "JsonIgnoreProperties")?.let { strings(it, "value") }.orEmpty() }.toSet()

    private fun constraints(owner: PsiModifierListOwner?): Constraints {
        if (owner == null) return Constraints()
        val size = annotation(owner, "Size")
        val decimalMin = annotation(owner, "DecimalMin")?.let { string(it, "value")?.toDoubleOrNull() }
        val decimalMax = annotation(owner, "DecimalMax")?.let { string(it, "value")?.toDoubleOrNull() }
        val min = annotation(owner, "Min")?.let { number(it, "value") }
            ?: decimalMin
            ?: annotation(owner, "Positive")?.let { 1.0 }
            ?: annotation(owner, "PositiveOrZero")?.let { 0.0 }
        val max = annotation(owner, "Max")?.let { number(it, "value") }
            ?: decimalMax
            ?: annotation(owner, "Negative")?.let { -1.0 }
            ?: annotation(owner, "NegativeOrZero")?.let { 0.0 }
        val timing = when {
            annotation(owner, "Past") != null || annotation(owner, "PastOrPresent") != null -> Timing.PAST
            annotation(owner, "Future") != null || annotation(owner, "FutureOrPresent") != null -> Timing.FUTURE
            else -> Timing.ANY
        }
        return Constraints(
            email = annotation(owner, "Email") != null,
            minSize = size?.let { number(it, "min")?.toInt() }?.takeIf { it > 0 },
            maxSize = size?.let { number(it, "max")?.toInt() }?.takeIf { it < Int.MAX_VALUE },
            min = min,
            max = max,
            pattern = annotation(owner, "Pattern")?.let { string(it, "regexp") },
            timing = timing,
        )
    }

    private fun annotation(owner: PsiModifierListOwner, simpleName: String): PsiAnnotation? =
        ANNOTATION_PACKAGES[simpleName].orEmpty().firstNotNullOfOrNull { owner.getAnnotation("$it.$simpleName") }

    private fun constant(annotation: PsiAnnotation, attribute: String): Any? {
        val value = annotation.findAttributeValue(attribute) ?: return null
        return JavaPsiFacade.getInstance(annotation.project).constantEvaluationHelper.computeConstantExpression(value)
    }

    private fun string(annotation: PsiAnnotation, attribute: String) = constant(annotation, attribute) as? String
    private fun boolean(annotation: PsiAnnotation, attribute: String) = constant(annotation, attribute) as? Boolean
    private fun number(annotation: PsiAnnotation, attribute: String) = (constant(annotation, attribute) as? Number)?.toDouble()

    private fun strings(annotation: PsiAnnotation, attribute: String): List<String> {
        val value = annotation.findAttributeValue(attribute) ?: return emptyList()
        val items = (value as? com.intellij.psi.PsiArrayInitializerMemberValue)?.initializers?.toList() ?: listOf(value)
        val helper = JavaPsiFacade.getInstance(annotation.project).constantEvaluationHelper
        return items.mapNotNull { helper.computeConstantExpression(it) as? String }
    }

    private fun primitiveKind(type: PsiPrimitiveType): ValueKind? = when (type) {
        PsiTypes.intType(), PsiTypes.shortType() -> ValueKind.INTEGER
        PsiTypes.longType() -> ValueKind.LONG
        PsiTypes.byteType() -> ValueKind.BYTE
        PsiTypes.doubleType(), PsiTypes.floatType() -> ValueKind.DECIMAL
        PsiTypes.booleanType() -> ValueKind.BOOLEAN
        PsiTypes.charType() -> ValueKind.CHAR
        else -> null
    }

    companion object {
        private val SIMPLE_KINDS = mapOf(
            "java.lang.String" to ValueKind.STRING,
            "java.lang.CharSequence" to ValueKind.STRING,
            "java.lang.Character" to ValueKind.CHAR,
            "java.lang.Integer" to ValueKind.INTEGER,
            "java.lang.Short" to ValueKind.INTEGER,
            "java.lang.Long" to ValueKind.LONG,
            "java.lang.Byte" to ValueKind.BYTE,
            "java.lang.Double" to ValueKind.DECIMAL,
            "java.lang.Float" to ValueKind.DECIMAL,
            "java.math.BigDecimal" to ValueKind.DECIMAL,
            "java.math.BigInteger" to ValueKind.BIG_INTEGER,
            "java.lang.Boolean" to ValueKind.BOOLEAN,
            "java.util.UUID" to ValueKind.UUID,
            "java.time.LocalDate" to ValueKind.LOCAL_DATE,
            "java.sql.Date" to ValueKind.LOCAL_DATE,
            "java.time.LocalDateTime" to ValueKind.LOCAL_DATE_TIME,
            "java.time.LocalTime" to ValueKind.LOCAL_TIME,
            "java.time.Instant" to ValueKind.INSTANT,
            "java.time.OffsetDateTime" to ValueKind.INSTANT,
            "java.time.ZonedDateTime" to ValueKind.INSTANT,
            "java.util.Date" to ValueKind.INSTANT,
            "java.sql.Timestamp" to ValueKind.INSTANT,
        )

        private val OWN_IDS = setOf("id", "uid", "uuid")
        private val LOMBOK_GETTERS = listOf("lombok.Getter", "lombok.Data", "lombok.Value")

        private val VALIDATION = listOf("jakarta.validation.constraints", "javax.validation.constraints")
        private val JACKSON = listOf("com.fasterxml.jackson.annotation")

        private val ANNOTATION_PACKAGES: Map<String, List<String>> =
            listOf("Size", "Min", "Max", "DecimalMin", "DecimalMax", "Positive", "PositiveOrZero", "Negative", "NegativeOrZero",
                "Past", "PastOrPresent", "Future", "FutureOrPresent", "Pattern").associateWith { VALIDATION } +
                mapOf(
                    "Email" to VALIDATION + "org.hibernate.validator.constraints",
                    "JsonProperty" to JACKSON,
                    "JsonIgnore" to JACKSON,
                    "JsonIgnoreProperties" to JACKSON,
                    "Transient" to listOf("jakarta.persistence", "javax.persistence", "org.springframework.data.annotation"),
                    "GeneratedValue" to listOf("jakarta.persistence", "javax.persistence"),
                    "GraphQLIgnore" to listOf("io.leangen.graphql.annotations"),
                )
    }
}
