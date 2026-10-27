package dev.sirbuni.devtools.apihttpgenerator.rest

import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiArrayInitializerMemberValue
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiParameter
import com.intellij.psi.PsiReferenceExpression

/** One Spring MVC handler method, reduced to what an HTTP request needs. */
data class Endpoint(
    val httpMethod: String,
    /** Class and method mapping joined, for example `/employees/{id}`. */
    val path: String,
    val method: PsiMethod,
    val pathVariables: List<Pair<String, PsiParameter>>,
    /** `@RequestParam` and plain simple-typed parameters: query string on GET, form body otherwise. */
    val requestParams: List<Pair<String, PsiParameter>>,
    val headers: List<Pair<String, PsiParameter>>,
    val body: PsiParameter?,
    /** A `Pageable` parameter: `page` and `size` are added to the query. */
    val pageable: Boolean,
)

object SpringEndpoints {
    private const val BIND = "org.springframework.web.bind.annotation"

    private val SHORTCUT_MAPPINGS = mapOf(
        "$BIND.GetMapping" to "GET", "$BIND.PostMapping" to "POST", "$BIND.PutMapping" to "PUT",
        "$BIND.PatchMapping" to "PATCH", "$BIND.DeleteMapping" to "DELETE",
    )
    private const val REQUEST_MAPPING = "$BIND.RequestMapping"

    /** Cheap check by annotation name, safe for line markers: no resolving. */
    fun looksLikeHandler(method: PsiMethod): Boolean = method.annotations.any { a ->
        val name = a.nameReferenceElement?.referenceName ?: return@any false
        name in SIMPLE_MAPPING_NAMES
    }

    fun isController(cls: PsiClass): Boolean =
        cls.hasAnnotation("org.springframework.stereotype.Controller") ||
            cls.hasAnnotation("$BIND.RestController") || cls.hasAnnotation(REQUEST_MAPPING)

    fun endpoint(method: PsiMethod): Endpoint? {
        val (httpMethod, mapping) = mapping(method) ?: return null
        val prefix = method.containingClass?.getAnnotation(REQUEST_MAPPING)?.let { firstPath(it) }.orEmpty()
        val path = joinPath(prefix, firstPath(mapping))

        val pathVariables = mutableListOf<Pair<String, PsiParameter>>()
        val requestParams = mutableListOf<Pair<String, PsiParameter>>()
        val headers = mutableListOf<Pair<String, PsiParameter>>()
        var body: PsiParameter? = null
        var pageable = false
        for (p in method.parameterList.parameters) {
            when {
                p.type.canonicalText == "org.springframework.data.domain.Pageable" -> pageable = true
                p.hasAnnotation("$BIND.PathVariable") -> pathVariables += name(p, "$BIND.PathVariable") to p
                p.hasAnnotation("$BIND.RequestParam") -> requestParams += name(p, "$BIND.RequestParam") to p
                p.hasAnnotation("$BIND.RequestHeader") -> headers += name(p, "$BIND.RequestHeader") to p
                p.hasAnnotation("$BIND.RequestBody") -> body = p
                SpringParams.isFramework(p) -> Unit
                SpringParams.isSimple(p.type) -> requestParams += (p.name to p)
            }
        }
        return Endpoint(httpMethod, path, method, pathVariables, requestParams, headers, body, pageable)
    }

    fun endpoints(cls: PsiClass): List<Endpoint> = cls.methods.mapNotNull { endpoint(it) }

    private fun mapping(method: PsiMethod): Pair<String, PsiAnnotation>? {
        for ((fqn, verb) in SHORTCUT_MAPPINGS) method.getAnnotation(fqn)?.let { return verb to it }
        val requestMapping = method.getAnnotation(REQUEST_MAPPING) ?: return null
        val verb = requestMapping.findDeclaredAttributeValue("method")?.let { value ->
            val first = (value as? PsiArrayInitializerMemberValue)?.initializers?.firstOrNull() ?: value
            (first as? PsiReferenceExpression)?.referenceName
        } ?: "GET"
        return verb to requestMapping
    }

    /** `value` or `path`, the first entry when it is an array; empty when absent. */
    private fun firstPath(annotation: PsiAnnotation): String {
        for (attribute in listOf("value", "path")) {
            val value = annotation.findDeclaredAttributeValue(attribute) ?: continue
            val first = (value as? PsiArrayInitializerMemberValue)?.initializers?.firstOrNull() ?: value
            val text = JavaPsiFacade.getInstance(annotation.project).constantEvaluationHelper.computeConstantExpression(first) as? String
            if (text != null) return text
        }
        return ""
    }

    private fun joinPath(prefix: String, path: String): String {
        val joined = "/" + listOf(prefix, path).map { it.trim('/') }.filter { it.isNotEmpty() }.joinToString("/")
        // Path patterns such as {id:[0-9]+} keep only the variable name.
        return joined.replace(Regex("\\{([^}:]+):[^}]*}"), "{$1}")
    }

    private fun name(p: PsiParameter, annotationFqn: String): String {
        val annotation = p.getAnnotation(annotationFqn) ?: return p.name
        for (attribute in listOf("value", "name")) {
            val value = annotation.findDeclaredAttributeValue(attribute) ?: continue
            val text = JavaPsiFacade.getInstance(p.project).constantEvaluationHelper.computeConstantExpression(value) as? String
            if (!text.isNullOrEmpty()) return text
        }
        return p.name
    }

    private val SIMPLE_MAPPING_NAMES = setOf("GetMapping", "PostMapping", "PutMapping", "PatchMapping", "DeleteMapping", "RequestMapping")
}

/** Parameter checks shared by Copy as JSON and the request generators. */
object SpringParams {
    /** Request, response, session, principal, model and SPQR environment arguments never travel in a request. */
    fun isFramework(parameter: PsiParameter): Boolean {
        val type = parameter.type.canonicalText
        return FRAMEWORK_TYPE_PREFIXES.any { type.startsWith(it) } ||
            parameter.annotations.any { it.qualifiedName in SPQR_CONTEXT }
    }

    fun isSimple(type: com.intellij.psi.PsiType): Boolean =
        type is com.intellij.psi.PsiPrimitiveType || type.canonicalText in SIMPLE_TYPES ||
            ((type as? com.intellij.psi.PsiClassType)?.resolve()?.isEnum == true)

    private val SPQR_CONTEXT = setOf("io.leangen.graphql.annotations.GraphQLEnvironment", "io.leangen.graphql.annotations.GraphQLRootContext")

    private val FRAMEWORK_TYPE_PREFIXES = listOf(
        "jakarta.servlet.", "javax.servlet.", "java.security.Principal", "org.springframework.ui.",
        "org.springframework.validation.", "org.springframework.security.", "org.springframework.web.",
        "org.springframework.http.HttpHeaders", "io.leangen.graphql.execution.",
    )

    private val SIMPLE_TYPES = setOf(
        "java.lang.String", "java.lang.Integer", "java.lang.Long", "java.lang.Short", "java.lang.Byte",
        "java.lang.Double", "java.lang.Float", "java.lang.Boolean", "java.lang.Character",
        "java.math.BigDecimal", "java.math.BigInteger", "java.util.UUID",
        "java.time.LocalDate", "java.time.LocalDateTime", "java.time.LocalTime", "java.time.Instant",
    )
}
