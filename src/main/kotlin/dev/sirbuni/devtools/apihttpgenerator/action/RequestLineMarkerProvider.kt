package dev.sirbuni.devtools.apihttpgenerator.action

import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProvider
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.PopupStep
import com.intellij.openapi.ui.popup.util.BaseListPopupStep
import com.intellij.openapi.util.IconLoader
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiIdentifier
import com.intellij.psi.PsiMethod
import com.intellij.ui.awt.RelativePoint
import dev.sirbuni.devtools.apihttpgenerator.json.JsonRenderer
import dev.sirbuni.devtools.apihttpgenerator.json.RenderMode
import dev.sirbuni.devtools.apihttpgenerator.psi.ClassWalker
import dev.sirbuni.devtools.apihttpgenerator.rest.RequestFileService
import dev.sirbuni.devtools.apihttpgenerator.rest.SpringEndpoints
import java.awt.datatransfer.StringSelection

/** A gutter icon on Spring handler methods; a click offers a small menu rather than writing at once. */
class RequestLineMarkerProvider : LineMarkerProvider {

    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? {
        val method = (element as? PsiIdentifier)?.parent as? PsiMethod ?: return null
        if (method.nameIdentifier != element || !SpringEndpoints.looksLikeHandler(method)) return null
        return LineMarkerInfo(
            element, element.textRange, ICON,
            { "Generate HTTP request" },
            { mouseEvent, identifier -> showMenu(identifier.parent as PsiMethod, RelativePoint(mouseEvent)) },
            GutterIconRenderer.Alignment.LEFT,
            { "Generate HTTP request" },
        )
    }

    private fun showMenu(method: PsiMethod, point: RelativePoint) {
        val project = method.project
        val choices = listOf(GENERATE_ONE, GENERATE_ALL, COPY_JSON)
        val step = object : BaseListPopupStep<String>("API HTTP Generator", choices) {
            override fun onChosen(selectedValue: String, finalChoice: Boolean): PopupStep<*>? {
                doFinalStep {
                    when (selectedValue) {
                        GENERATE_ONE -> RequestFileService.generate(project, method)
                        GENERATE_ALL -> method.containingClass?.let { RequestFileService.generate(project, it) }
                        COPY_JSON -> copyJson(method)
                    }
                }
                return PopupStep.FINAL_CHOICE
            }
        }
        JBPopupFactory.getInstance().createListPopup(step).show(point)
    }

    private fun copyJson(method: PsiMethod) {
        val json = com.intellij.openapi.application.ReadAction.compute<String?, RuntimeException> {
            CopyTarget.Method(method).toJson(ClassWalker())?.let { JsonRenderer(RenderMode.PLACEHOLDERS).render(it) }
        } ?: return
        CopyPasteManager.getInstance().setContents(StringSelection(json))
    }

    companion object {
        private val ICON = IconLoader.getIcon("/icons/httpRequest.svg", RequestLineMarkerProvider::class.java)
        private const val GENERATE_ONE = "Generate request for this endpoint"
        private const val GENERATE_ALL = "Generate requests for the whole controller"
        private const val COPY_JSON = "Copy request body as JSON (\$random placeholders)"
    }
}
