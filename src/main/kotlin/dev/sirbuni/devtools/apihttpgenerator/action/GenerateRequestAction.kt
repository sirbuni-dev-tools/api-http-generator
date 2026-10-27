package dev.sirbuni.devtools.apihttpgenerator.action

import com.intellij.codeInsight.intention.PsiElementBaseIntentionAction
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiCodeBlock
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiIdentifier
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiMethod
import com.intellij.psi.util.PsiTreeUtil
import dev.sirbuni.devtools.apihttpgenerator.graphql.SpqrOperations
import dev.sirbuni.devtools.apihttpgenerator.rest.RequestFileService
import dev.sirbuni.devtools.apihttpgenerator.rest.SpringEndpoints

/** Where request generation starts: one handler or resolver method, or a whole controller or resolver class. */
sealed interface GenerateTarget {
    data class Method(val method: PsiMethod) : GenerateTarget
    data class Controller(val cls: PsiClass) : GenerateTarget

    fun run(project: Project) = when (this) {
        is Method -> RequestFileService.generate(project, method)
        is Controller -> RequestFileService.generate(project, cls)
    }

    companion object {
        /** A handler method around the caret, else the controller around it (or the controller of the file). */
        fun at(element: PsiElement?, file: PsiJavaFile?): GenerateTarget? {
            if (element != null) {
                if (element is PsiMethod && isGenerated(element)) return Method(element)
                if (element is PsiClass && isGenerated(element)) return Controller(element)
                PsiTreeUtil.getParentOfType(element, PsiMethod::class.java, false)
                    ?.takeIf { isGenerated(it) }?.let { return Method(it) }
                PsiTreeUtil.getParentOfType(element, PsiClass::class.java, false)
                    ?.takeIf { isGenerated(it) }?.let { return Controller(it) }
            }
            return file?.classes?.firstOrNull { isGenerated(it) }?.let { Controller(it) }
        }

        fun isGenerated(method: PsiMethod) = SpringEndpoints.endpoint(method) != null || SpqrOperations.operation(method) != null

        fun isGenerated(cls: PsiClass) =
            SpringEndpoints.isController(cls) || SpqrOperations.isResolverClass(cls)
    }
}

/** Editor and Project view action, with a rebindable shortcut. */
class GenerateRequestAction : AnAction() {

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val target = target(e)
        e.presentation.isEnabledAndVisible = target != null
        e.presentation.text = if (target is GenerateTarget.Controller) "Generate HTTP Requests for Controller" else "Generate HTTP Request"
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        ReadAction.compute<GenerateTarget?, RuntimeException> { target(e) }?.run(project)
    }

    private fun target(e: AnActionEvent): GenerateTarget? {
        val file = e.getData(CommonDataKeys.PSI_FILE)
        val editor = e.getData(CommonDataKeys.EDITOR)
        val element = if (file != null && editor != null) file.findElementAt(editor.caretModel.offset)
        else e.getData(CommonDataKeys.PSI_ELEMENT)
        return GenerateTarget.at(element, file as? PsiJavaFile)
    }
}

/** Alt+Enter on a handler method's signature: generate its request. */
class GenerateRequestIntention : PsiElementBaseIntentionAction() {
    override fun getFamilyName() = "API HTTP Generator"
    override fun getText() = "Generate HTTP request for this endpoint"
    override fun startInWriteAction() = false

    override fun isAvailable(project: Project, editor: Editor?, element: PsiElement): Boolean = handlerAt(element) != null

    override fun invoke(project: Project, editor: Editor?, element: PsiElement) {
        handlerAt(element)?.let { RequestFileService.generate(project, it) }
    }

    /** Only on the signature or annotations, not inside the body, so the list stays quiet while editing code. */
    private fun handlerAt(element: PsiElement): PsiMethod? {
        val method = PsiTreeUtil.getParentOfType(element, PsiMethod::class.java, false) ?: return null
        if (PsiTreeUtil.isAncestor(method.body ?: return null, element, false) && element !is PsiCodeBlock) return null
        return method.takeIf { GenerateTarget.isGenerated(it) }
    }
}

/** Alt+Enter on a controller's class name: generate every request it maps. */
class GenerateControllerRequestsIntention : PsiElementBaseIntentionAction() {
    override fun getFamilyName() = "API HTTP Generator"
    override fun getText() = "Generate HTTP requests for this controller"
    override fun startInWriteAction() = false

    override fun isAvailable(project: Project, editor: Editor?, element: PsiElement): Boolean = controllerAt(element) != null

    override fun invoke(project: Project, editor: Editor?, element: PsiElement) {
        controllerAt(element)?.let { RequestFileService.generate(project, it) }
    }

    private fun controllerAt(element: PsiElement): PsiClass? {
        val cls = (element as? PsiIdentifier)?.parent as? PsiClass ?: return null
        return cls.takeIf { SpringEndpoints.endpoints(it).isNotEmpty() || SpqrOperations.operations(it).isNotEmpty() }
    }
}
