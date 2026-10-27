package dev.sirbuni.devtools.apihttpgenerator.rest

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiMethod
import dev.sirbuni.devtools.apihttpgenerator.action.CopyAsJsonAction

/**
 * Writes generated requests into `<module>/.local/http/<Controller>.http`, creating the folder and an
 * `http-client.env.json` with `HOST` when missing, then opens the file at the first new request.
 */
object RequestFileService {

    fun generate(project: Project, method: PsiMethod) {
        val controller = ReadAction.compute<PsiClass?, RuntimeException> { method.containingClass } ?: return
        generate(project, controller) { listOfNotNull(SpringEndpoints.endpoint(method)) }
    }

    fun generate(project: Project, controller: PsiClass) {
        generate(project, controller) { SpringEndpoints.endpoints(controller) }
    }

    private fun generate(project: Project, controller: PsiClass, endpoints: () -> List<Endpoint>) {
        val prepared = ReadAction.compute<Prepared?, RuntimeException> {
            val root = ModuleUtilCore.findModuleForPsiElement(controller)
                ?.let { ModuleRootManager.getInstance(it).contentRoots.firstOrNull() }
                ?: return@compute null
            val writer = RequestWriter()
            Prepared(root, controller.name ?: "requests", endpoints().map { writer.write(it) }, readHost(root))
        } ?: return notify(project, "No module folder found for this controller", NotificationType.WARNING)

        if (prepared.requests.isEmpty()) return notify(project, "No Spring request mappings found here", NotificationType.WARNING)

        var opened: Pair<VirtualFile, Int>? = null
        var merged: HttpFileText.Merged? = null
        WriteCommandAction.runWriteCommandAction(project, "Generate HTTP Requests", null, {
            val dir = VfsUtil.createDirectoryIfMissing(prepared.root, ".local/http") ?: return@runWriteCommandAction
            if (dir.findChild(ENV_FILE) == null) {
                VfsUtil.saveText(dir.createChildData(this, ENV_FILE), HttpFileText.envFile(prepared.host))
            }
            val file = dir.findChild("${prepared.fileName}.http") ?: dir.createChildData(this, "${prepared.fileName}.http")
            val document = FileDocumentManager.getInstance().getDocument(file) ?: return@runWriteCommandAction
            val result = HttpFileText.merge(document.text, prepared.requests)
            if (result.added.isNotEmpty()) {
                document.setText(result.text)
                FileDocumentManager.getInstance().saveDocument(document)
            }
            val focus = (result.added.firstOrNull() ?: result.skipped.first()).requestLine
            val offset = result.text.indexOf(focus).let { line -> if (line < 0) 0 else result.text.lastIndexOf("###", line).coerceAtLeast(0) }
            merged = result
            opened = file to offset
        })

        opened?.let { (file, offset) -> OpenFileDescriptor(project, file, offset).navigate(true) }
        val result = merged ?: return
        val message = when {
            result.added.isEmpty() -> "Already in ${prepared.fileName}.http: nothing new to add"
            result.skipped.isEmpty() -> "Added ${count(result.added.size)} to .local/http/${prepared.fileName}.http"
            else -> "Added ${count(result.added.size)}; ${result.skipped.size} already in the file"
        }
        notify(project, message, NotificationType.INFORMATION)
    }

    private class Prepared(val root: VirtualFile, val fileName: String, val requests: List<GeneratedRequest>, val host: String)

    private const val ENV_FILE = "http-client.env.json"

    private fun count(n: Int) = if (n == 1) "1 request" else "$n requests"

    private fun readHost(root: VirtualFile): String {
        val resources = root.findFileByRelativePath("src/main/resources")
        fun text(name: String) = resources?.findChild(name)?.let { VfsUtil.loadText(it) }
        return HttpFileText.host(text("application.properties"), text("application.yml") ?: text("application.yaml"))
    }

    private fun notify(project: Project, message: String, type: NotificationType) {
        NotificationGroupManager.getInstance().getNotificationGroup(CopyAsJsonAction.NOTIFICATION_GROUP)
            .createNotification(message, type).notify(project)
    }
}
