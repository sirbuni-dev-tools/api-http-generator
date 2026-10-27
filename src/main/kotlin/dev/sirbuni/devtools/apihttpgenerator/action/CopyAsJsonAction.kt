package dev.sirbuni.devtools.apihttpgenerator.action

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.psi.PsiJavaFile
import dev.sirbuni.devtools.apihttpgenerator.json.JsonRenderer
import dev.sirbuni.devtools.apihttpgenerator.json.RenderMode
import dev.sirbuni.devtools.apihttpgenerator.psi.ClassWalker
import java.awt.datatransfer.StringSelection

/**
 * Copies what the caret is on as JSON with fake values: a class, a field or parameter,
 * or a method's parameters. See [CopyTarget] for how the target is chosen.
 */
abstract class CopyAsJsonAction(private val mode: RenderMode) : AnAction() {

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = target(e) != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val (label, json) = ReadAction.compute<Pair<String, String?>?, RuntimeException> {
            val target = target(e) ?: return@compute null
            target.label to target.toJson(ClassWalker())?.let { JsonRenderer(mode).render(it) }
        } ?: return

        val group = NotificationGroupManager.getInstance().getNotificationGroup(NOTIFICATION_GROUP)
        if (json == null) {
            group.createNotification("Nothing to copy: $label has no JSON fields", NotificationType.WARNING).notify(project)
            return
        }
        CopyPasteManager.getInstance().setContents(StringSelection(json))
        val kind = if (mode == RenderMode.FIXED) "fake values" else "\$random placeholders"
        group.createNotification("Copied $label as JSON with $kind", NotificationType.INFORMATION).notify(project)
    }

    companion object {
        const val NOTIFICATION_GROUP = "API HTTP Generator"

        fun target(e: AnActionEvent): CopyTarget? {
            val file = e.getData(CommonDataKeys.PSI_FILE)
            val editor = e.getData(CommonDataKeys.EDITOR)
            if (file != null && editor != null) {
                // At the end of an identifier the caret sits just after it, so look one character back too.
                val offset = editor.caretModel.offset
                val element = file.findElementAt(offset)?.takeUnless { it is com.intellij.psi.PsiWhiteSpace }
                    ?: file.findElementAt((offset - 1).coerceAtLeast(0))
                return element?.let { CopyTarget.at(it) }
            }
            e.getData(CommonDataKeys.PSI_ELEMENT)?.let { CopyTarget.at(it) }?.let { return it }
            return (file as? PsiJavaFile)?.classes?.firstOrNull()?.let { CopyTarget.Class(it) }
        }
    }
}

class CopyAsJsonFixedAction : CopyAsJsonAction(RenderMode.FIXED)

class CopyAsJsonPlaceholdersAction : CopyAsJsonAction(RenderMode.PLACEHOLDERS)
