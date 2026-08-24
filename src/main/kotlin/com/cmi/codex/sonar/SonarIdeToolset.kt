package com.cmi.codex.sonar

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.McpToolset
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.util.Computable
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.sonarlint.intellij.analysis.AnalysisResult
import org.sonarlint.intellij.analysis.AnalysisSubmitter
import org.sonarlint.intellij.analysis.RunningAnalysesTracker
import org.sonarlint.intellij.core.BackendService
import org.sonarlint.intellij.finding.LiveFinding
import org.sonarlint.intellij.finding.hotspot.LiveSecurityHotspot
import org.sonarlint.intellij.finding.issue.LiveIssue
import java.io.IOException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.time.Instant
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Read-only bridge from SonarQube for IDE's in-memory findings to IntelliJ's MCP server.
 *
 * The live tool reports the on-the-fly holder. The explicit pre-commit tool analyzes only the
 * caller-supplied saved files and waits for the public CheckInCallable results; it never hooks the
 * native IntelliJ commit workflow.
 */
class SonarIdeToolset : McpToolset {
    companion object {
        private const val MAX_PRECOMMIT_FILES = 200
        private const val MAX_RELATIVE_PATH_LENGTH = 2048
        private const val DEFAULT_PRECOMMIT_TIMEOUT_SECONDS = 60
        private const val MIN_PRECOMMIT_TIMEOUT_SECONDS = 5
        private const val MAX_PRECOMMIT_TIMEOUT_SECONDS = 120
        private const val POLL_INTERVAL_MILLIS = 100L
        private val activePrecommitProjects = ConcurrentHashMap.newKeySet<String>()
    }

    @McpTool
    @McpDescription(
        "Read the current SonarQube for IDE findings for the active IntelliJ project. " +
            "This is an in-memory, read-only snapshot and is not the Jenkins/SonarQube quality gate."
    )
    suspend fun sonar_ide_current_findings(
        @McpDescription("Absolute IntelliJ project path. Required when more than one project is open.")
        projectPath: String? = null,
        @McpDescription("Optional project-relative file path. Omit to return all currently tracked files.")
        file: String? = null,
        @McpDescription("Include findings marked resolved by SonarQube for IDE.")
        includeResolved: Boolean = false,
    ): String {
        val project = selectProject(projectPath)
            ?: return unavailable("NO_PROJECT", "No open IntelliJ project matches projectPath")
        if (project.isDisposed) {
            return unavailable("PROJECT_DISPOSED", "The active IntelliJ project is already disposed")
        }

        val requestedPath = normalizeRequestedPath(file)
        val snapshot = try {
            readAction {
                if (project.isDisposed) {
                    return@readAction null
                }
                val submitter = project.getService(AnalysisSubmitter::class.java)
                    ?: return@readAction null
                val holder = submitter.onTheFlyFindingsHolder
                val projectPath = project.basePath
                val issues = holder.getAllIssues()
                    .asSequence()
                    .filter { issue: LiveIssue -> includeResolved || !issue.isResolved() }
                    .filter { issue: LiveIssue -> requestedPath == null || matchesPath(project, issue.file(), requestedPath) }
                    .map { issue -> issueJson(project, issue) }
                    .toList()
                val hotspots = holder.getAllHotspots()
                    .asSequence()
                    .filter { hotspot: LiveSecurityHotspot -> includeResolved || !hotspot.isResolved() }
                    .filter { hotspot: LiveSecurityHotspot -> requestedPath == null || matchesPath(project, hotspot.file(), requestedPath) }
                    .map { hotspot -> hotspotJson(project, hotspot) }
                    .toList()
                CurrentFindingsSnapshot(projectPath, issues, hotspots)
            }
        } catch (_: ProcessCanceledException) {
            return unavailable(
                if (project.isDisposed) "PROJECT_DISPOSED" else "ANALYSIS_CANCELLED",
                "IntelliJ cancelled the Findings read while the project was changing",
            )
        } ?: return unavailable("SONAR_IDE_UNAVAILABLE", "SonarQube for IDE AnalysisSubmitter is unavailable")

        val result = JsonObject()
        result.addProperty("available", true)
        result.addProperty("sourceKind", "on_the_fly")
        result.addProperty("snapshotExact", false)
        result.addProperty("stalePossible", true)
        result.addProperty("projectPath", snapshot.projectPath)
        result.addProperty("fetchedAt", Instant.now().toString())
        result.addProperty("fileFilter", requestedPath)
        result.add("issues", jsonArray(snapshot.issues))
        result.add("hotspots", jsonArray(snapshot.hotspots))
        result.addProperty("issueCount", snapshot.issues.size)
        result.addProperty("hotspotCount", snapshot.hotspots.size)
        return result.toString()
    }

    @McpTool
    @McpDescription(
        "Run an explicit SonarQube for IDE pre-commit analysis for the supplied project-relative files. " +
            "This does not hook or modify the native IntelliJ commit workflow."
    )
    suspend fun sonar_ide_analyze_precommit(
        @McpDescription("Absolute path of an open IntelliJ project.")
        projectPath: String,
        @McpDescription(
            "Project-relative files to analyze. The list must contain 1 to 200 existing files whose exact paths " +
                "were confirmed from Git, IntelliJ, or the file system; do not infer paths from Java package names."
        )
        files: List<String>,
        @McpDescription("Bounded wait for all SonarQube for IDE analysis callbacks, from 5 to 120 seconds.")
        timeoutSeconds: Int = DEFAULT_PRECOMMIT_TIMEOUT_SECONDS,
    ): String {
        val project = selectProject(projectPath)
            ?: return unavailable(
                "NO_PROJECT",
                "No open IntelliJ project matches projectPath",
                sourceKind = "precommit",
                projectPath = projectPath,
            )
        if (project.isDisposed) {
            return unavailable(
                "PROJECT_DISPOSED",
                "The selected IntelliJ project is already disposed",
                sourceKind = "precommit",
                projectPath = project.basePath,
            )
        }
        if (timeoutSeconds !in MIN_PRECOMMIT_TIMEOUT_SECONDS..MAX_PRECOMMIT_TIMEOUT_SECONDS) {
            return unavailable(
                "INVALID_REQUEST",
                "timeoutSeconds must be between $MIN_PRECOMMIT_TIMEOUT_SECONDS and $MAX_PRECOMMIT_TIMEOUT_SECONDS",
                sourceKind = "precommit",
                projectPath = project.basePath,
            )
        }

        val validated = try {
            validatePrecommitFiles(project, files)
        } catch (_: ProcessCanceledException) {
            return unavailable(
                if (project.isDisposed) "PROJECT_DISPOSED" else "ANALYSIS_CANCELLED",
                "IntelliJ cancelled pre-commit file validation while the project was changing",
                sourceKind = "precommit",
                projectPath = runCatching { project.basePath }.getOrNull(),
            )
        } catch (error: IllegalArgumentException) {
            return unavailable(
                "INVALID_REQUEST",
                error.message ?: "The pre-commit file list is invalid",
                sourceKind = "precommit",
                projectPath = project.basePath,
            )
        }
        val projectKey = normalizedProjectKey(project)
        if (!activePrecommitProjects.add(projectKey)) {
            return unavailable(
                "ANALYSIS_ALREADY_RUNNING",
                "An explicit pre-commit analysis is already running for this project",
                sourceKind = "precommit",
                projectPath = project.basePath,
            )
        }

        var startedAnalysisIds: List<UUID> = emptyList()
        var analysisFinished = false
        try {
            val submitter = project.getService(AnalysisSubmitter::class.java)
                ?: return unavailable(
                    "SONAR_IDE_UNAVAILABLE",
                    "SonarQube for IDE AnalysisSubmitter is unavailable",
                    sourceKind = "precommit",
                    projectPath = project.basePath,
                )
            val tracker = project.getService(RunningAnalysesTracker::class.java)
                ?: return unavailable(
                    "SONAR_IDE_UNAVAILABLE",
                    "SonarQube for IDE analysis tracking is unavailable",
                    sourceKind = "precommit",
                    projectPath = project.basePath,
                )
            val request = try {
                withContext(Dispatchers.Default) {
                    submitter.analyzeFilesPreCommit(validated.virtualFiles)
                }
            } catch (_: RuntimeException) {
                return unavailable(
                    "ANALYSIS_FAILED",
                    "SonarQube for IDE could not start the explicit pre-commit analysis",
                    sourceKind = "precommit",
                    projectPath = project.basePath,
                )
            } ?: return unavailable(
                "ANALYSIS_NOT_STARTED",
                "SonarQube for IDE did not start an analysis for the requested files",
                sourceKind = "precommit",
                projectPath = project.basePath,
            )

            val callback = request.left
            val analysisIds = request.right.distinct()
            startedAnalysisIds = analysisIds
            if (analysisIds.isEmpty()) {
                return unavailable(
                    "ANALYSIS_NOT_STARTED",
                    "SonarQube for IDE returned no analysis identifiers",
                    sourceKind = "precommit",
                    projectPath = project.basePath,
                )
            }
            val completed = withTimeoutOrNull(timeoutSeconds * 1_000L) {
                while (!project.isDisposed && analysisIds.any { tracker.getById(it) != null }) {
                    delay(POLL_INTERVAL_MILLIS)
                }
                !project.isDisposed
            } ?: false
            if (!completed) {
                return unavailable(
                    if (project.isDisposed) "PROJECT_DISPOSED" else "ANALYSIS_TIMEOUT",
                    if (project.isDisposed) {
                        "The IntelliJ project was disposed while analysis was running"
                    } else {
                        "The explicit pre-commit analysis did not finish within $timeoutSeconds seconds"
                    },
                    sourceKind = "precommit",
                    projectPath = project.basePath,
                )
            }
            // The tracker has removed every requested ID, so cancellation is no longer needed.
            analysisFinished = true
            if (!callback.analysisSucceeded()) {
                return unavailable(
                    "ANALYSIS_FAILED",
                    "SonarQube for IDE reported an error during the explicit pre-commit analysis",
                    sourceKind = "precommit",
                    projectPath = project.basePath,
                )
            }

            val results: List<AnalysisResult> = callback.getResults()
            val completedIds = results.map { result: AnalysisResult -> result.analysisId }.toSet()
            if (!completedIds.containsAll(analysisIds.toSet())) {
                return unavailable(
                    "INCOMPLETE_ANALYSIS",
                    "Not every requested SonarQube for IDE analysis returned a final result",
                    sourceKind = "precommit",
                    projectPath = project.basePath,
                )
            }
            return precommitResultJson(project, validated.relativePaths, analysisIds, results)
        } finally {
            if (!analysisFinished && startedAnalysisIds.isNotEmpty()) {
                cancelOwnAnalyses(startedAnalysisIds)
            }
            activePrecommitProjects.remove(projectKey)
        }
    }

    private fun cancelOwnAnalyses(analysisIds: List<UUID>) {
        val backend = runCatching {
            ApplicationManager.getApplication().getService(BackendService::class.java)
        }.getOrNull() ?: return
        analysisIds.forEach { id ->
            runCatching { backend.cancelTask(id.toString()) }
        }
    }

    private fun selectProject(requestedPath: String?): Project? = try {
        readAction {
            val openProjects = ProjectManager.getInstance().openProjects
                .filterNot { it.isDisposed }
            if (requestedPath.isNullOrBlank()) {
                openProjects.singleOrNull()
            } else {
                val normalized = try {
                    Path.of(requestedPath.trim()).normalize().toString()
                } catch (_: RuntimeException) {
                    return@readAction null
                }
                openProjects.firstOrNull { project ->
                    val basePath = project.basePath ?: return@firstOrNull false
                    Path.of(basePath).normalize().toString().equals(normalized, ignoreCase = true)
                }
            }
        }
    } catch (_: ProcessCanceledException) {
        null
    }

    private fun unavailable(
        code: String,
        message: String,
        sourceKind: String = "on_the_fly",
        projectPath: String? = null,
    ): String {
        val result = JsonObject()
        result.addProperty("available", false)
        result.addProperty("sourceKind", sourceKind)
        result.addProperty("status", code)
        result.addProperty("message", message)
        result.addProperty("snapshotExact", false)
        result.addProperty("isCommitSnapshot", false)
        result.addProperty("stalePossible", sourceKind == "on_the_fly")
        projectPath?.let { result.addProperty("projectPath", it) }
        result.addProperty("fetchedAt", Instant.now().toString())
        return result.toString()
    }

    private fun precommitResultJson(
        project: Project,
        requestedFiles: List<String>,
        analysisIds: List<UUID>,
        results: List<AnalysisResult>,
    ): String = try {
        readAction {
            if (project.isDisposed) {
                return@readAction unavailable(
                    "PROJECT_DISPOSED",
                    "The IntelliJ project was disposed before findings could be serialized",
                    sourceKind = "precommit",
                    projectPath = project.basePath,
                )
            }
            val capturedAt = Instant.now()
            val issues = results
                .flatMap { it.findings.issuesPerFile.values }
                .flatten()
                .filter { it.isValid() && !it.isResolved() }
                .map { issueJson(project, it) }
            val hotspots = results
                .flatMap { it.findings.securityHotspotsPerFile.values }
                .flatten()
                .filter { it.isValid() && !it.isResolved() }
                .map { hotspotJson(project, it) }
            val analyzedFiles = results
                .flatMap { it.analyzedFiles }
                .mapNotNull { projectRelativePath(project, it) }
                .distinct()
                .sorted()
            val analyzedFileKeys = analyzedFiles.map { it.lowercase(Locale.ROOT) }.toSet()
            if (requestedFiles.any { it.lowercase(Locale.ROOT) !in analyzedFileKeys }) {
                return@readAction unavailable(
                    "INCOMPLETE_ANALYSIS",
                    "SonarQube for IDE did not return a final result for every requested file",
                    sourceKind = "precommit",
                    projectPath = project.basePath,
                )
            }

            val result = JsonObject()
            result.addProperty("available", true)
            result.addProperty("status", "OK")
            result.addProperty("sourceKind", "precommit")
            result.addProperty("snapshotExact", true)
            result.addProperty("isCommitSnapshot", true)
            result.addProperty("stalePossible", false)
            result.addProperty("projectPath", project.basePath)
            result.addProperty("capturedAt", capturedAt.toString())
            result.addProperty("fetchedAt", capturedAt.toString())
            results.maxOfOrNull { it.analysisDate }?.let { result.addProperty("analysisDate", it.toString()) }
            if (analysisIds.size == 1) {
                result.addProperty("analysisId", analysisIds.single().toString())
            }
            result.add("analysisIds", jsonStringArray(analysisIds.map(UUID::toString)))
            result.add("requestedFiles", jsonStringArray(requestedFiles))
            result.add("analyzedFiles", jsonStringArray(analyzedFiles))
            result.add("issues", jsonArray(issues))
            result.add("hotspots", jsonArray(hotspots))
            result.addProperty("issueCount", issues.size)
            result.addProperty("hotspotCount", hotspots.size)
            result.addProperty("completeness", "complete_for_requested_files")
            result.addProperty("detailsTruncated", false)
            result.toString()
        }
    } catch (_: ProcessCanceledException) {
        unavailable(
            if (project.isDisposed) "PROJECT_DISPOSED" else "ANALYSIS_CANCELLED",
            "IntelliJ cancelled Findings serialization while the project was changing",
            sourceKind = "precommit",
            projectPath = runCatching { project.basePath }.getOrNull(),
        )
    }

    /**
     * SonarQube for IDE findings expose IntelliJ VirtualFile/Document-backed state. Newer
     * IntelliJ builds enforce read access for those model objects even when the caller is on a
     * background coroutine, so every snapshot/serialization path enters a read action explicitly.
     */
    private fun <T> readAction(action: () -> T): T =
        ApplicationManager.getApplication().runReadAction(Computable { action() })

    private fun validatePrecommitFiles(project: Project, files: List<String>): ValidatedPrecommitFiles {
        require(files.isNotEmpty()) { "files must contain at least one project-relative path" }
        require(files.size <= MAX_PRECOMMIT_FILES) { "files must contain at most $MAX_PRECOMMIT_FILES paths" }
        val projectBasePath = project.basePath
            ?: throw IllegalArgumentException("The selected project has no local base path")
        val basePath = try {
            Path.of(projectBasePath).toAbsolutePath().normalize()
        } catch (_: InvalidPathException) {
            throw IllegalArgumentException("The selected project base path is invalid")
        } catch (_: SecurityException) {
            throw IllegalArgumentException("The selected project base path is unavailable")
        }
        val realBasePath = resolveRealPath(basePath, "The selected project base path is unavailable")
        val relativePaths = ArrayList<String>()
        val virtualFiles = LinkedHashSet<VirtualFile>()
        val seen = HashSet<String>()

        files.forEachIndexed { index, value ->
            val raw = value.trim().replace('\\', '/')
            require(raw.isNotEmpty()) { "files[$index] must not be blank" }
            require(raw.length <= MAX_RELATIVE_PATH_LENGTH) {
                "files[$index] exceeds $MAX_RELATIVE_PATH_LENGTH characters"
            }
            require(!raw.contains('\u0000') && !raw.contains('\r') && !raw.contains('\n')) {
                "files[$index] contains invalid control characters"
            }
            require(raw.split('/').none { it == ".." }) { "files[$index] must not contain '..' segments" }
            val relativePath = try {
                Path.of(raw).normalize()
            } catch (_: InvalidPathException) {
                throw IllegalArgumentException("files[$index] is not a valid path")
            }
            require(!relativePath.isAbsolute && relativePath.root == null && relativePath.toString() != ".") {
                "files[$index] must be project-relative"
            }
            val resolvedPath = basePath.resolve(relativePath).normalize()
            require(resolvedPath.startsWith(basePath)) { "files[$index] resolves outside the project" }
            val realPath = resolveRealPath(resolvedPath, "files[$index] does not exist or is inaccessible")
            require(realPath.startsWith(realBasePath) && Files.isRegularFile(realPath)) {
                "files[$index] must resolve to a regular file inside the project"
            }
            val virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(realPath)
                ?: throw IllegalArgumentException("files[$index] is unavailable in the IntelliJ virtual file system")
            require(virtualFile.isValid && !virtualFile.isDirectory) {
                "files[$index] is not a valid IntelliJ project file"
            }
            val normalizedRelativePath = basePath.relativize(resolvedPath).toString().replace('\\', '/')
            val deduplicationKey = normalizedRelativePath.lowercase(Locale.ROOT)
            if (seen.add(deduplicationKey)) {
                relativePaths.add(normalizedRelativePath)
                virtualFiles.add(virtualFile)
            }
        }
        require(virtualFiles.isNotEmpty()) { "files did not contain any unique project files" }
        return ValidatedPrecommitFiles(relativePaths, virtualFiles)
    }

    private fun normalizedProjectKey(project: Project): String = project.basePath
        ?.let { Path.of(it).toAbsolutePath().normalize().toString().lowercase(Locale.ROOT) }
        ?: project.locationHash

    private fun issueJson(project: Project, issue: LiveIssue): JsonObject = findingJson(project, issue).also {
        it.addProperty("findingKind", "ISSUE")
        it.addProperty("ruleType", issue.getType()?.name)
        it.addProperty("resolutionStatus", issue.getStatus()?.name)
    }

    private fun hotspotJson(project: Project, hotspot: LiveSecurityHotspot): JsonObject = findingJson(project, hotspot).also {
        it.addProperty("findingKind", "HOTSPOT")
        it.addProperty("vulnerabilityProbability", hotspot.getVulnerabilityProbability()?.name)
        it.addProperty("status", hotspot.getStatus()?.name)
    }

    private fun findingJson(project: Project, finding: LiveFinding): JsonObject {
        val result = JsonObject()
        result.addProperty("id", finding.getId()?.toString())
        result.addProperty("serverKey", finding.getServerKey())
        result.addProperty("ruleKey", finding.getRuleKey())
        result.addProperty("message", finding.message)
        result.addProperty("severity", finding.getUserSeverity()?.name)
        result.addProperty("isOnNewCode", finding.isOnNewCode())
        result.addProperty("valid", finding.isValid())
        result.addProperty("resolved", finding.isResolved())
        result.addProperty("file", projectRelativePath(project, finding.file()))
        finding.validTextRange?.let { range ->
            val location = JsonObject()
            location.addProperty("startOffset", range.startOffset)
            location.addProperty("endOffset", range.endOffset)
            val document = finding.file()?.let { FileDocumentManager.getInstance().getDocument(it) }
            if (document != null) {
                location.addProperty("startLine", document.getLineNumber(range.startOffset) + 1)
                location.addProperty("startColumn", range.startOffset - document.getLineStartOffset(document.getLineNumber(range.startOffset)) + 1)
                val endOffset = range.endOffset.coerceAtMost(document.textLength)
                location.addProperty("endLine", document.getLineNumber(endOffset) + 1)
                location.addProperty("endColumn", endOffset - document.getLineStartOffset(document.getLineNumber(endOffset)) + 1)
            }
            result.add("range", location)
        }
        val impacts = JsonArray()
        finding.getImpacts().forEach { impact ->
            val impactJson = JsonObject()
            impactJson.addProperty("softwareQuality", impact.getSoftwareQuality()?.name)
            impactJson.addProperty("impactSeverity", impact.getImpactSeverity()?.name)
            impacts.add(impactJson)
        }
        result.add("impacts", impacts)
        return result
    }

    private fun matchesPath(project: Project, virtualFile: VirtualFile?, requestedPath: String): Boolean =
        projectRelativePath(project, virtualFile)?.replace('\\', '/') == requestedPath

    private fun projectRelativePath(project: Project, virtualFile: VirtualFile?): String? {
        val filePath = virtualFile?.path ?: return null
        val basePath = project.basePath ?: return filePath.replace('\\', '/')
        return try {
            Path.of(basePath).normalize().relativize(Path.of(filePath).normalize()).toString().replace('\\', '/')
        } catch (_: IllegalArgumentException) {
            filePath.replace('\\', '/')
        }
    }

    private fun normalizeRequestedPath(file: String?): String? = file
        ?.trim()
        ?.replace('\\', '/')
        ?.removePrefix("./")
        ?.takeIf { it.isNotEmpty() }

    private fun jsonArray(values: List<JsonObject>): JsonArray = JsonArray().also { array ->
        values.forEach { value -> array.add(value) }
    }

    private fun jsonStringArray(values: List<String>): JsonArray = JsonArray().also { array ->
        values.forEach { value -> array.add(value) }
    }

    private data class ValidatedPrecommitFiles(
        val relativePaths: List<String>,
        val virtualFiles: LinkedHashSet<VirtualFile>,
    )

    private data class CurrentFindingsSnapshot(
        val projectPath: String?,
        val issues: List<JsonObject>,
        val hotspots: List<JsonObject>,
    )

}

internal fun resolveRealPath(path: Path, invalidMessage: String): Path = try {
    path.toRealPath()
} catch (_: IOException) {
    throw IllegalArgumentException(invalidMessage)
} catch (_: SecurityException) {
    throw IllegalArgumentException(invalidMessage)
}
