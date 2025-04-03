package com.brahamchari.demoplugin.services

import com.brahamchari.MCPServer
import com.brahamchari.android.AndroidMCPServerImpl
import com.brahamchari.demoplugin.client.AndroidMCPClient
import com.brahamchari.demoplugin.client.MCPClient
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import kotlinx.coroutines.*
import kotlin.coroutines.CoroutineContext

@Service(Service.Level.PROJECT)
class McpService(
    private val project: Project
) : CoroutineScope, Disposable {
    // --- Manage CoroutineScope internally ---
    // Create a Job that can be cancelled when the service is disposed
    private val serviceJob = SupervisorJob()
    // Define the context using the Job and appropriate dispatchers
    override val coroutineContext: CoroutineContext
        get() = serviceJob + Dispatchers.Default // Or IO, depending on primary work type

    // --- Configuration ---
    private val serverPort: Int = 5000 // Match server port
    private val serverHost: String = "127.0.0.1" // Connect specifically to loopback
    private val adbPath: String = "/path/to/your/adb" // TODO: Load from settings

    // --- Get Dependencies Lazily ---
    // Get the project-specific AnthropicService lazily
    private val anthropicService: AnthropicService by lazy {
        println("McpService [${project.name}]: Lazily getting AnthropicService instance.")
        // AnthropicService.getInstance(project) // Use companion object if available
        project.getService(AnthropicService::class.java) // Standard way
    }

    private val _mcpServer = lazy {
        println("McpService [${project.name}]: Lazily creating AndroidMCPServerImpl.")
        AndroidMCPServerImpl(adbPath, serverPort, "0.0.0.0") // Pass project-specific port
    }
    val mcpServer by _mcpServer

    private val _mcpClient = lazy {
        println("McpService [${project.name}]: Lazily creating AndroidMCPClient.")
        AndroidMCPClient(
            host = serverHost,
            port = serverPort, // Use the same derived port
            anthropicClient = anthropicService.anthropicClient,
            parentScope = this // Pass this service's scope
        )
    }
    val mcpClient by _mcpClient

    private var startupJob: Job? = null

    init {
        println("McpService: Initializing with WebSocket...")
    }

    fun initializeAndStart() {
        if (startupJob?.isActive == true) {
            println("McpService: Startup already in progress.")
            return
        }
        startupJob = launch { // Use the service's scope
            println("McpService: Attempting to start server...")
            try {
                val serverStarted = mcpServer.startServer() // Start the server

                if (serverStarted) {
                    println("McpService: Server start initiated. Connecting client...")
                    delay(500) // Brief delay for server to be fully ready (can be improved)
                    try {
                         mcpClient.connect() // Connect the client
                         // Check mcpClient.isConnected after a short delay or via callbacks if possible
                         delay(500) // Give client time to attempt connection
                         if (mcpClient.isConnected) {
                              println("McpService: Client connect initiated (assumed connected).")
                         } else {
                              System.err.println("McpService: Client failed to connect after server start.")
                              // Optionally stop server if client connection fails
                              // mcpServer.stopServer()
                         }
                    } catch (clientEx: Exception) {
                         System.err.println("McpService: Client connection attempt failed: ${clientEx.message}")
                         // Optionally stop server
                         // mcpServer.stopServer()
                    }
                } else {
                    System.err.println("McpService: Server failed to start.")
                }
            } catch (e: Exception) {
                 System.err.println("McpService: Error during startup coroutine: ${e.message}")
                 e.printStackTrace()
                 // Ensure cleanup happens even if startServer throws
                 withContext(NonCancellable) { stopServerAndClient() }
            } finally {
                 println("McpService: Startup job finished.")
            }
        }
    }

    suspend fun stopServerAndClient() {
        println("McpService [${project.name}]: Shutting down server and client...")
        startupJob?.cancelAndJoin()
        startupJob = null

        // Check if lazy instances were initialized before trying to stop/shutdown
        if (_mcpClient.isInitialized()) {
            try { mcpClient.disconnect() } catch (e: Exception) { /* Log */ }
        }
        if (_mcpServer.isInitialized()) {
            try { mcpServer.stopServer() } catch (e: Exception) { /* Log */ }
        }
        if (_mcpClient.isInitialized()) {
            try { mcpClient.shutdown() } catch (e: Exception) { /* Log */ }
        }
        println("McpService [${project.name}]: Shutdown sequence complete.")
    }

    override fun dispose() {
        println("McpService [${project.name}]: Disposing service...")
        // Use runBlocking or GlobalScope for cleanup if suspend funcs must be called from dispose
        // But prefer cancelling the scope and letting coroutines handle cleanup gracefully.
        if (serviceJob.isActive) {
            serviceJob.cancel(CancellationException("McpService for project ${project.name} is being disposed."))
            println("McpService [${project.name}]: Coroutine scope cancelled.")
        }
        // TODO: Explicit cleanup if needed beyond coroutine cancellation
        // runBlocking { stopServerAndClientInternal() } // Can cause delays if network ops hang
    }


    companion object {
        fun getInstance(project: Project): McpService = project.getService(McpService::class.java)
    }
}
