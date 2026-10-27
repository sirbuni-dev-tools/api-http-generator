package dev.sirbuni.devtools.apihttpgenerator.action

import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiClassType
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiIdentifier
import com.intellij.psi.PsiJavaCodeReferenceElement
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiModifierListOwner
import com.intellij.psi.PsiParameter
import com.intellij.psi.PsiVariable
import com.intellij.psi.util.PsiTreeUtil
import dev.sirbuni.devtools.apihttpgenerator.json.JsonNode
import dev.sirbuni.devtools.apihttpgenerator.psi.ClassWalker

/** What "Copy as JSON" copies, decided from the caret or the Project view selection. */
sealed interface CopyTarget {
    /** Shown in the notification, for example `Employee` or `parameters of updateSalary`. */
    val label: String

    /** Null when there is nothing to copy, such as a method without parameters. */
    fun toJson(walker: ClassWalker): JsonNode?

    data class Class(val cls: PsiClass) : CopyTarget {
        override val label get() = cls.name ?: "class"
        override fun toJson(walker: ClassWalker) = walker.walk(cls)
    }

    /**
     * A field, parameter or local variable: its class when it holds an object, else `{"name": value}`.
     * An interface-typed variable (an injected DAO or service) has no JSON, so nothing is copied.
     */
    data class Variable(val variable: PsiVariable) : CopyTarget {
        override val label get() = variable.name ?: "variable"
        override fun toJson(walker: ClassWalker): JsonNode? = when {
            (variable.type as? PsiClassType)?.resolve()?.isInterface == true && !walker.isCollectionOrMap(variable.type) -> null
            walker.isObjectType(variable.type) -> walker.walk(variable.type)
            else -> walker.walkVariables(listOf(keyOf(variable) to variable), ownerName(variable))
        }
    }

    /** A handler or resolver method: its `@RequestBody` class, else its parameters as one object. */
    data class Method(val method: PsiMethod) : CopyTarget {
        override val label get() = "parameters of ${method.name}"
        override fun toJson(walker: ClassWalker): JsonNode? {
            val params = method.parameterList.parameters.filterNot { isFrameworkParameter(it) }
            params.firstOrNull { annotationOf(it, "RequestBody") != null }?.let { return walker.walk(it.type) }
            if (params.isEmpty()) return null
            return walker.walkVariables(params.map { keyOf(it) to it }, method.containingClass?.name ?: "")
        }
    }

    companion object {
        private const val SPRING_BIND = "org.springframework.web.bind.annotation"
        private const val SPQR = "io.leangen.graphql.annotations"

        /**
         * From the most specific element at the caret outwards: a class name or type reference,
         * then a variable (named or declared there), then the enclosing method, then the enclosing class.
         */
        fun at(element: PsiElement): CopyTarget? {
            if (element is PsiClass) return Class(element)
            if (element is PsiVariable) return Variable(element)
            if (element is PsiMethod) return Method(element)

            val identifierOwner = (element as? PsiIdentifier)?.parent
            if (identifierOwner is PsiClass) return Class(identifierOwner)
            if (identifierOwner is PsiMethod) return Method(identifierOwner)

            PsiTreeUtil.getParentOfType(element, PsiJavaCodeReferenceElement::class.java, false)?.resolve()?.let { resolved ->
                when {
                    resolved is PsiClass && isUserClass(resolved) -> return Class(resolved)
                    resolved is PsiVariable -> return Variable(resolved)
                }
            }
            PsiTreeUtil.getParentOfType(element, PsiVariable::class.java, false)?.let { return Variable(it) }
            PsiTreeUtil.getParentOfType(element, PsiMethod::class.java, false)?.let { return Method(it) }
            return PsiTreeUtil.getParentOfType(element, PsiClass::class.java, false)?.let { Class(it) }
        }

        /** A class from the project's own sources; library classes such as `ResponseEntity` are compiled. */
        private fun isUserClass(cls: PsiClass): Boolean {
            val name = cls.qualifiedName ?: return false
            return cls !is com.intellij.psi.PsiCompiledElement && !cls.isInterface && !cls.isAnnotationType &&
                !name.startsWith("java.") && !name.startsWith("javax.") && !name.startsWith("jakarta.")
        }

        /** The JSON key: the name in `@RequestParam`, `@PathVariable`, `@RequestHeader` or `@GraphQLArgument`, else the Java name. */
        fun keyOf(variable: PsiVariable): String {
            for (annotation in listOf("RequestParam", "PathVariable", "RequestHeader")) {
                annotationOf(variable, annotation)?.let { a ->
                    (stringAttribute(a, "value") ?: stringAttribute(a, "name"))?.let { return it }
                }
            }
            variable.annotations.firstOrNull { it.qualifiedName == "$SPQR.GraphQLArgument" }
                ?.let { stringAttribute(it, "name") }?.let { return it }
            return variable.name ?: "value"
        }

        private fun ownerName(variable: PsiVariable): String =
            PsiTreeUtil.getParentOfType(variable, PsiClass::class.java)?.name ?: ""

        /** Request, response, session, principal and SPQR environment arguments never travel as JSON. */
        private fun isFrameworkParameter(parameter: PsiParameter): Boolean {
            val type = parameter.type.canonicalText
            return FRAMEWORK_TYPE_PREFIXES.any { type.startsWith(it) } ||
                parameter.annotations.any { it.qualifiedName == "$SPQR.GraphQLEnvironment" || it.qualifiedName == "$SPQR.GraphQLRootContext" }
        }

        private val FRAMEWORK_TYPE_PREFIXES = listOf(
            "jakarta.servlet.", "javax.servlet.", "java.security.Principal", "org.springframework.ui.",
            "org.springframework.validation.", "org.springframework.security.", "org.springframework.web.",
            "org.springframework.http.HttpHeaders", "io.leangen.graphql.execution.",
        )

        private fun annotationOf(owner: PsiModifierListOwner, simpleName: String) = owner.getAnnotation("$SPRING_BIND.$simpleName")

        private fun stringAttribute(annotation: com.intellij.psi.PsiAnnotation, name: String): String? {
            val value = annotation.findDeclaredAttributeValue(name) ?: return null
            return (JavaPsiFacade.getInstance(annotation.project).constantEvaluationHelper.computeConstantExpression(value) as? String)
                ?.takeIf { it.isNotEmpty() }
        }
    }
}
