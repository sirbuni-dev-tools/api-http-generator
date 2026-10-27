package dev.sirbuni.devtools.apihttpgenerator.graphql

import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiArrayType
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiClassType
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiParameter
import com.intellij.psi.PsiPrimitiveType
import com.intellij.psi.PsiType
import com.intellij.psi.PsiTypes
import com.intellij.psi.util.InheritanceUtil
import com.intellij.psi.util.PsiUtil
import dev.sirbuni.devtools.apihttpgenerator.rest.SpringParams

/** One SPQR resolver method: a query or a mutation with its GraphQL name and arguments. */
data class Operation(
    val kind: String,
    val name: String,
    val method: PsiMethod,
    /** GraphQL argument name to the Java parameter. */
    val arguments: List<Pair<String, PsiParameter>>,
)

/** Reads SPQR's code-first annotations the way SPQR names the schema. */
object SpqrOperations {
    private const val ANNOTATIONS = "io.leangen.graphql.annotations"

    fun looksLikeResolver(method: PsiMethod): Boolean = method.annotations.any {
        val name = it.nameReferenceElement?.referenceName
        name == "GraphQLQuery" || name == "GraphQLMutation"
    }

    fun isResolverClass(cls: PsiClass): Boolean =
        cls.hasAnnotation("io.leangen.graphql.spqr.spring.annotations.GraphQLApi") || cls.methods.any { operation(it) != null }

    fun operation(method: PsiMethod): Operation? {
        val (kind, annotation) = when {
            method.hasAnnotation("$ANNOTATIONS.GraphQLQuery") -> "query" to method.getAnnotation("$ANNOTATIONS.GraphQLQuery")
            method.hasAnnotation("$ANNOTATIONS.GraphQLMutation") -> "mutation" to method.getAnnotation("$ANNOTATIONS.GraphQLMutation")
            else -> return null
        }
        val name = annotation?.let { string(it, "name") } ?: method.name
        val arguments = method.parameterList.parameters
            .filterNot { SpringParams.isFramework(it) || it.hasAnnotation("$ANNOTATIONS.GraphQLIgnore") }
            .map { p -> (p.getAnnotation("$ANNOTATIONS.GraphQLArgument")?.let { string(it, "name") } ?: p.name) to p }
        return Operation(kind, name, method, arguments)
    }

    fun operations(cls: PsiClass): List<Operation> = cls.methods.mapNotNull { operation(it) }

    /**
     * The GraphQL type SPQR gives a Java argument type: scalars by their usual names, enums by
     * their simple name, other classes as `<SimpleName>Input`, collections and arrays as lists.
     */
    fun inputTypeName(type: PsiType): String {
        if (type is PsiPrimitiveType) return PRIMITIVES[type] ?: "String"
        if (type is PsiArrayType) return "[${inputTypeName(type.componentType)}]"
        val classType = type as? PsiClassType ?: return "String"
        val cls = classType.resolve() ?: return "String"
        SCALARS[cls.qualifiedName]?.let { return it }
        if (InheritanceUtil.isInheritor(cls, "java.lang.Iterable")) {
            val element = PsiUtil.substituteTypeParameter(type, "java.lang.Iterable", 0, false)
            return "[${element?.let { inputTypeName(it) } ?: "String"}]"
        }
        if (cls.qualifiedName == "java.util.Optional") return classType.parameters.firstOrNull()?.let { inputTypeName(it) } ?: "String"
        return if (cls.isEnum) cls.name ?: "String" else "${cls.name}Input"
    }

    private fun string(annotation: com.intellij.psi.PsiAnnotation, attribute: String): String? {
        val value = annotation.findDeclaredAttributeValue(attribute) ?: return null
        return (JavaPsiFacade.getInstance(annotation.project).constantEvaluationHelper.computeConstantExpression(value) as? String)
            ?.takeIf { it.isNotEmpty() }
    }

    private val PRIMITIVES = mapOf(
        PsiTypes.intType() to "Int", PsiTypes.longType() to "Long", PsiTypes.shortType() to "Short",
        PsiTypes.byteType() to "Byte", PsiTypes.doubleType() to "Float", PsiTypes.floatType() to "Float",
        PsiTypes.booleanType() to "Boolean", PsiTypes.charType() to "Char",
    )

    private val SCALARS = mapOf(
        "java.lang.String" to "String", "java.lang.Integer" to "Int", "java.lang.Long" to "Long",
        "java.lang.Short" to "Short", "java.lang.Byte" to "Byte", "java.lang.Double" to "Float",
        "java.lang.Float" to "Float", "java.lang.Boolean" to "Boolean", "java.lang.Character" to "Char",
        "java.math.BigDecimal" to "BigDecimal", "java.math.BigInteger" to "BigInteger", "java.util.UUID" to "UUID",
        "java.time.LocalDate" to "LocalDate", "java.time.LocalDateTime" to "LocalDateTime", "java.time.LocalTime" to "LocalTime",
        "java.time.Instant" to "Instant", "java.time.OffsetDateTime" to "OffsetDateTime", "java.time.ZonedDateTime" to "ZonedDateTime",
        "java.util.Date" to "Date", "java.lang.Object" to "Object",
    )
}
