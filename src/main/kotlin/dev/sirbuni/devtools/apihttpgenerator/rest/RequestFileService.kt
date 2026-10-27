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
import dev.sirbuni.devtools.apihttpgenerator.graphql.GraphQlWriter
import dev.sirbuni.devtools.apihttpgenerator.graphql.SpqrOperations

/**
 * Writes generated requests into `<module>/.local/http/<Class>.http`, creating the folder and the
 * env files when missing, then opens the file at the first new request. Spring MVC handlers become
 * HTTP requests, SPQR resolvers become `GRAPHQL` requests behind a login request.
 */
object RequestFileService {

    fun generate(project: Project, method: PsiMethod) {
        val cls = ReadAction.compute<PsiClass?, RuntimeException> { method.containingClass } ?: return
        generate(project, cls) { config ->
            SpringEndpoints.endpoint(method)?.let { listOf(RequestWriter().write(it)) }
                ?: SpqrOperations.operation(method)?.let { listOf(GraphQlWriter(config.graphqlPath).write(it)) }
                ?: emptyList()
        }
    }

    fun generate(project: Project, cls: PsiClass) {
        generate(project, cls) { config ->
            val rest = RequestWriter().let { w -> SpringEndpoints.endpoints(cls).map { w.write(it) } }
            val graphql = GraphQlWriter(config.graphqlPath).let { w -> SpqrOperations.operations(cls).map { w.write(it) } }
            rest + graphql
        }
    }

    private class Config(val host: String, val graphqlPath: String)

    private class Prepared(val root: VirtualFile, val fileName: String, val requests: List<GeneratedRequest>, val config: Config) {
        val hasGraphQl get() = requests.any { it.text.contains("\nGRAPHQL ") }
    }

    private fun generate(project: Project, cls: PsiClass, requests: (Config) -> List<GeneratedRequest>) {
        val prepared = ReadAction.compute<Prepared?, RuntimeException> {
            val root = ModuleUtilCore.findModuleForPsiElement(cls)
                ?.let { ModuleRootManager.getInstance(it).contentRoots.firstOrNull() }
                ?: return@compute null
            val config = readConfig(root)
            Prepared(root, cls.name ?: "requests", requests(config), config)
        } ?: return notify(project, "No module folder found for this class", NotificationType.WARNING)

        if (prepared.requests.isEmpty()) return notify(project, "No Spring request mappings or SPQR resolvers found here", NotificationType.WARNING)
        // GraphQL requests need a token first, so the login request leads the file.
        val all = if (prepared.hasGraphQl) listOf(GraphQlWriter.loginRequest()) + prepared.requests else prepared.requests

        var opened: Pair<VirtualFile, Int>? = null
        var merged: HttpFileText.Merged? = null
        WriteCommandAction.runWriteCommandAction(project, "Generate HTTP Requests", null, {
            val dir = VfsUtil.createDirectoryIfMissing(prepared.root, ".local/http") ?: return@runWriteCommandAction
            val shared = mutableMapOf("HOST" to prepared.config.host)
            if (prepared.hasGraphQl) {
                shared += mapOf("AUTH_URL" to prepared.config.host + "/oauth2/token", "GRANT_TYPE" to "password", "CLIENT_ID" to "", "USERNAME" to "")
                updateEnv(dir, PRIVATE_ENV_FILE, mapOf("CLIENT_SECRET" to "", "PASSWORD" to ""))
            }
            updateEnv(dir, ENV_FILE, shared)

            val file = dir.findChild("${prepared.fileName}.http") ?: dir.createChildData(this, "${prepared.fileName}.http")
            val document = FileDocumentManager.getInstance().getDocument(file) ?: return@runWriteCommandAction
            val result = HttpFileText.merge(document.text, all)
            if (result.added.isNotEmpty()) {
                document.setText(result.text)
                FileDocumentManager.getInstance().saveDocument(document)
            }
            // Open at the first generated endpoint, not at the login request.
            val focus = (result.added.firstOrNull { it.requestLine != "POST {{AUTH_URL}}" } ?: result.added.firstOrNull()
                ?: result.skipped.last()).requestLine
            val offset = result.text.indexOf(focus).let { line -> if (line < 0) 0 else result.text.lastIndexOf("###", line).coerceAtLeast(0) }
            merged = result
            opened = file to offset
        })

        opened?.let { (file, offset) -> OpenFileDescriptor(project, file, offset).navigate(true) }
        val result = merged ?: return
        val endpoints = result.added.count { it.requestLine != "POST {{AUTH_URL}}" }
        val message = when {
            result.added.isEmpty() -> "Already in ${prepared.fileName}.http: nothing new to add"
            result.skipped.isEmpty() -> "Added ${count(endpoints)} to .local/http/${prepared.fileName}.http"
            else -> "Added ${count(endpoints)}; ${result.skipped.size} already in the file"
        }
        notify(project, message, NotificationType.INFORMATION)
    }

    private const val ENV_FILE = "http-client.env.json"
    private const val PRIVATE_ENV_FILE = "http-client.private.env.json"

    private fun updateEnv(dir: VirtualFile, name: String, keys: Map<String, String>) {
        val file = dir.findChild(name)
        val updated = HttpFileText.withEnvKeys(file?.let { VfsUtil.loadText(it) }, keys) ?: return
        VfsUtil.saveText(file ?: dir.createChildData(this, name), updated)
    }

    private fun count(n: Int) = if (n == 1) "1 request" else "$n requests"

    private fun readConfig(root: VirtualFile): Config {
        val resources = root.findFileByRelativePath("src/main/resources")
        fun text(name: String) = resources?.findChild(name)?.let { VfsUtil.loadText(it) }
        val properties = text("application.properties")
        val yaml = text("application.yml") ?: text("application.yaml")
        return Config(HttpFileText.host(properties, yaml), HttpFileText.graphqlPath(properties, yaml))
    }

    private fun notify(project: Project, message: String, type: NotificationType) {
        NotificationGroupManager.getInstance().getNotificationGroup(CopyAsJsonAction.NOTIFICATION_GROUP)
            .createNotification(message, type).notify(project)
    }
}
