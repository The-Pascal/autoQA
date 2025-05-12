package com.brahamchari.demoplugin.services

import com.brahamchari.demoplugin.models.TestExecutionLog
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*

interface TestLogPersistenceService {
    /**
     * Saves the given TestExecutionLog, associating it with the specified project.
     * @param project The project context for this log.
     * @param logData The data to save.
     * @return A Result containing the saved File on success, or an Exception on failure.
     */
    suspend fun saveLog(project: Project, logData: TestExecutionLog): Result<File>

    /**
     * Loads all saved TestExecutionLogs for a specific project.
     * @param project The project context.
     * @return A Result containing a List of loaded logs on success (newest first),
     * or an Exception on failure.
     */
    suspend fun loadLogsForProject(project: Project): Result<List<TestExecutionLog>>

    // Optional: Add methods to load single log by ID, delete logs, etc. if needed
    // suspend fun loadLogById(project: Project, logId: String): Result<TestExecutionLog?>
    // suspend fun deleteLog(project: Project, logId: String): Result<Unit>
}

@Service(Service.Level.APP) // Register as an Application Level Service
class TestLogPersistenceServiceImpl : TestLogPersistenceService {

    private val log = Logger.getInstance(TestLogPersistenceServiceImpl::class.java)
    // Use a Gson instance (potentially injected or created here)
    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()

    // Define the base directory for ALL logs from this plugin
    // Place it within the IDE's system path (for caches, logs etc.) - less likely to be deleted by user
    private val pluginLogBaseDir: File by lazy {
        // Use a unique subdirectory name for your plugin
        File(PathManager.getSystemPath(), "autoqa-plugin-logs").apply {
            if (!exists()) {
                try {
                    mkdirs() // Create the base directory if it doesn't exist
                    log.info("Created plugin log base directory: $absolutePath")
                } catch (e: SecurityException) {
                    log.error("Failed to create plugin log base directory: $absolutePath", e)
                }
            }
        }
    }

    /** Gets the specific sub-directory for a given project's logs. */
    private fun getLogDirectoryForProject(project: Project): File {
        // Use project.locationHash for a stable, unique identifier resistant to project renaming/moving
        val projectDirName = project.locationHash
        return File(pluginLogBaseDir, projectDirName).apply {
            // Create project-specific directory if needed when accessed
            if (!exists()) {
                try {
                    mkdirs()
                } catch (e: SecurityException) {
                    log.error("Failed to create project log directory: $absolutePath", e)
                }
            }
        }
    }

    override suspend fun saveLog(project: Project, logData: TestExecutionLog): Result<File> = withContext(Dispatchers.IO) {
        try {
            val projectLogDir = getLogDirectoryForProject(project)
            if (!projectLogDir.exists() && !projectLogDir.mkdirs()) {
                throw IOException("Failed to create log directory: ${projectLogDir.absolutePath}")
            }

            // Generate filename (timestamp + sanitized input + partial ID)
            val timestampFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
            val timestampStr = timestampFormat.format(Date(logData.startTime))
            val safeInput = logData.userInput.replace(Regex("[^a-zA-Z0-9_-]"), "_").take(50)
            val filename = "${timestampStr}_${safeInput}_${logData.id.take(8)}.json"
            val saveFile = File(projectLogDir, filename)

            val jsonContent = gson.toJson(logData)

            // Write to File safely
            saveFile.writeText(jsonContent, Charsets.UTF_8)

            log.info("TestExecutionLog saved successfully to: ${saveFile.absolutePath}")
            Result.success(saveFile)
        } catch (e: Exception) {
            log.error("Failed to save log ${logData.id} for project ${project.locationHash}", e)
            Result.failure(e)
        }
    }

    override suspend fun loadLogsForProject(project: Project): Result<List<TestExecutionLog>> = withContext(Dispatchers.IO) {
        try {
            val projectLogDir = getLogDirectoryForProject(project)
            if (!projectLogDir.isDirectory) {
                return@withContext Result.success(emptyList())
            }

            // Find all .json files, defensively handle listFiles returning null
            val logFiles = projectLogDir.listFiles { _, name -> name.endsWith(".json", ignoreCase = true) } ?: emptyArray()

            val logs = logFiles.mapNotNull { file ->
                try {
                    val jsonContent = file.readText(Charsets.UTF_8)
                    // Ensure TestExecutionLog class has necessary fields/constructors for Gson
                    gson.fromJson(jsonContent, TestExecutionLog::class.java)
                } catch (e: Exception) {
                    log.error("Failed to load or parse log file: ${file.name}", e)
                    null // Skip files that fail to parse
                }
            }.sortedByDescending { it.startTime } // Sort by start time, newest first

            log.info("Loaded ${logs.size} logs for project ${project.locationHash}")
            Result.success(logs)
        } catch (e: Exception) {
            log.error("Failed to load logs for project ${project.locationHash}", e)
            Result.failure(e)
        }
    }
}