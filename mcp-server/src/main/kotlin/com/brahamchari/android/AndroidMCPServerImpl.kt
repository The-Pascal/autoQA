package com.brahamchari.android

import com.brahamchari.MCPServer
import io.ktor.server.application.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.plugins.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import io.modelcontextprotocol.kotlin.sdk.*
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.WebSocketMcpServerTransport
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import kotlin.math.roundToLong
import kotlin.time.Duration.Companion.seconds

class AndroidMCPServerImpl(
    private val adbPath: String,
    private val port: Int = 5000,
    private val host: String = "0.0.0.0" // Listen on all local interfaces
) : MCPServer {

    private lateinit var mcpServerLogic: Server

    private var embeddedKtorServer: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null
    private val serverLifecycleScope by lazy { CoroutineScope(Dispatchers.IO + SupervisorJob() + CoroutineName("MCPServerLifecycle")) }
    private val androidInteractionManager by lazy { AndroidInteractionManagerImpl(adbPath) }

    @Volatile
    override var isServerRunning: Boolean = false

    override suspend fun startServer(): Boolean {
        if (isServerRunning || embeddedKtorServer != null) {
            println("MCP Ktor Server: Already running.")
            return isServerRunning // Indicate already running is a success state for starting
        }

        println("MCP Ktor Server: Initializing...")

        // 1. Create the core MCP Server logic instance first
        mcpServerLogic = createMCPServerLogic() // Use helper function
        addAllTools() // Add tools to the MCP Server logic instance

        println("MCP Ktor Server: Starting embedded Ktor server on $host:$port...")


        try {
            // 1. Create the server configuration
            embeddedKtorServer = serverLifecycleScope.embeddedServer(
                factory = CIO, // Or Netty etc.
                port = port,
                host = host,
                parentCoroutineContext = serverLifecycleScope.coroutineContext
            ) {
                // Configure Ktor server modules inside the lambda
                install(WebSockets) {
                    pingPeriod = 15.seconds
                    timeout = 30.seconds
                    maxFrameSize = Long.MAX_VALUE
                    masking = false
                }

                // Define routing
                routing {
                    webSocket("/mcp", protocol = "mcp") {
                        // ... your WebSocket handling logic ...
                        println("MCP Ktor Server: WebSocket client connected: ${call.request.origin.remoteHost}")

                        val transport = try {
                            WebSocketMcpServerTransport(this)
                        } catch (e: IllegalStateException) {
                            System.err.println("MCP Ktor Server: Client connection failed validation (e.g., subprotocol): ${e.message}")
                            close(
                                io.ktor.websocket.CloseReason(
                                    io.ktor.websocket.CloseReason.Codes.PROTOCOL_ERROR,
                                    e.message ?: "Validation failed"
                                )
                            )
                            return@webSocket
                        } catch (e: Exception) {
                            System.err.println("MCP Ktor Server: Failed to create WebSocketMcpServerTransport: ${e.message}")
                            close(
                                io.ktor.websocket.CloseReason(
                                    io.ktor.websocket.CloseReason.Codes.INTERNAL_ERROR,
                                    "Server setup error"
                                )
                            )
                            return@webSocket
                        }

                        try {
                            this@AndroidMCPServerImpl.mcpServerLogic.connect(transport)
                            println("MCP Ktor Server: MCP logic connected to transport for ${call.request.origin.remoteHost}")
                            coroutineContext.job.join() // Wait for WebSocket session to end
                        } catch (e: Exception) {
                            System.err.println("MCP Ktor Server: Error during MCP session for ${call.request.origin.remoteHost}: ${e.message}")
                        } finally {
                            println("MCP Ktor Server: WebSocket client disconnected: ${call.request.origin.remoteHost}")
                        }
                    }
                }
            } // End of embeddedServer configuration lambda

            // 2. Start the server and assign the result (which is ApplicationEngine)
            embeddedKtorServer?.start(wait = false)

            // **Set running state immediately after successful start initiation**
            isServerRunning = true
            println("MCP Ktor Server: Ktor server start initiated successfully.")
            return true // Return true indicating successful start command

        } catch (e: Exception) {
            System.err.println("MCP Ktor Server: Failed to create or start Ktor server: ${e.message}")
            e.printStackTrace()
            // Ensure cleanup on failure
            try {
                embeddedKtorServer?.stop(100, 1000)
            } catch (stopEx: Exception) { /* Ignore stop error during startup failure */
            }
            embeddedKtorServer = null
            isServerRunning = false // Ensure state is false
            return false // Return false indicating failure
        }
    }

    override suspend fun stopServer() {
        if (!isServerRunning || embeddedKtorServer == null) {
            println("MCP Ktor Server: Server not running.")
            return
        }
        println("MCP Ktor Server: Stopping Ktor server...")
        isServerRunning = false // Set state early

        try {
            // Call stop on the EmbeddedServer instance
            embeddedKtorServer?.stop(1000, 5000) // Adjust grace periods
            println("MCP Ktor Server: Ktor server stopped.")
        } catch (e: Exception) {
            System.err.println("MCP Ktor Server: Error stopping Ktor server: ${e.message}")
        } finally {
            embeddedKtorServer = null // Clear the reference
            println("MCP Ktor Server: Stop sequence finished.")
        }
        // ... (optional closing of mcpServerLogic) ...
    }

    private fun createMCPServerLogic(): Server {
        return Server(
            Implementation(name = "android-mcp", version = "1.0.0"),
            ServerOptions(capabilities = ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = false)))
        )
    }

    private fun addAllTools() {

        mcpServerLogic.addTool(
            name = "get_screen_context",
            description = """
                    Returns the current UI hierarchy dump from the screen.
                """.trimIndent()
        ) {
            delay(1000)
            val screenContext = androidInteractionManager.getScreenContext()
            CallToolResult(content = listOf(TextContent(screenContext)))
        }

        mcpServerLogic.addTool(
            name = "tap_on_screen",
            description = """
                    Uses adb command to tap the coordinates on the screen.
                    Returns screen context after the tap action is completed.
                """.trimIndent(),
            inputSchema = Tool.Input(
                properties = buildJsonObject {
                    putJsonObject("xCoordinate") { put("type", "number") }
                    putJsonObject("yCoordinate") { put("type", "number") }
                },
                required = listOf("xCoordinate", "yCoordinate")
            ),
        ) { request: CallToolRequest ->
            val xJsonPrimitive = request.arguments["xCoordinate"]?.jsonPrimitive
            val yJsonPrimitive = request.arguments["yCoordinate"]?.jsonPrimitive

            // Attempt to parse as Double first to handle both integers and decimals
            val xDouble = xJsonPrimitive?.doubleOrNull
            val yDouble = yJsonPrimitive?.doubleOrNull

            if (xDouble == null || yDouble == null) {
                // This will now only trigger if the values are not numbers at all (e.g., strings)
                // or if the keys are missing and not caught by a schema validator earlier.
                return@addTool CallToolResult( // or return@defineTapOnScreenTool if used directly in lambda
                    content = listOf(TextContent("xCoordinate & yCoordinate must be valid numbers."))
                )
            }

            // Convert to Long by rounding to the nearest whole number
            val xCoordinate = xDouble.roundToLong()
            val yCoordinate = yDouble.roundToLong()

            // The original null check for xCoordinate and yCoordinate (as Longs) is no longer strictly necessary
            // here because if xDouble or yDouble were null, we would have returned above.
            // The conversion to Long from a valid Double will always succeed.

            println("Parsed coordinates - X: $xCoordinate, Y: $yCoordinate") // For debugging

            androidInteractionManager.tapOnScreen(xCoordinate, yCoordinate)
            delay(500) // Consider making this delay configurable or part of a shared constant
            val screenContext = androidInteractionManager.getScreenContext()

            CallToolResult(content = listOf(TextContent(screenContext)))
        }

        mcpServerLogic.addTool(
            name = "swipe_on_screen",
            description = """
                    Performs a swipe gesture from start to end coordinates.
                    Returns screen context after completion.
                """.trimIndent(),
            inputSchema = Tool.Input(
                properties = buildJsonObject {
                    putJsonObject("startX") { put("type", "number") }
                    putJsonObject("startY") { put("type", "number") }
                    putJsonObject("endX") { put("type", "number") }
                    putJsonObject("endY") { put("type", "number") }
                    putJsonObject("duration") { put("type", "number") }
                },
                required = listOf("startX", "startY", "endX", "endY", "duration")
            ),
        ) { request: CallToolRequest ->
            val startX = request.arguments["startX"]?.jsonPrimitive?.longOrNull
            val startY = request.arguments["startY"]?.jsonPrimitive?.longOrNull
            val endX = request.arguments["endX"]?.jsonPrimitive?.longOrNull
            val endY = request.arguments["endY"]?.jsonPrimitive?.longOrNull
            val duration = request.arguments["duration"]?.jsonPrimitive?.longOrNull

            if (startX == null || startY == null || endX == null || endY == null || duration == null) {
                return@addTool CallToolResult(
                    content = listOf(TextContent("All swipe coordinates and duration must be provided"))
                )
            }

            androidInteractionManager.swipeOnScreen(startX, startY, endX, endY, duration)
            delay(500)
            val screenContext = androidInteractionManager.getScreenContext()

            CallToolResult(content = listOf(TextContent(screenContext)))
        }

        mcpServerLogic.addTool(
            name = "input_text",
            description = """
                    Inputs text into the currently focused field.
                    Returns cleaned screen context after processing.
                """.trimIndent(),
            inputSchema = Tool.Input(
                properties = buildJsonObject {
                    putJsonObject("input") {
                        put("type", "string")
                        put("description", "Text to input in the focused field.")
                    }
                },
                required = listOf("input")
            ),
        ) { request: CallToolRequest ->
            val inputText = request.arguments["input"]?.jsonPrimitive?.contentOrNull

            if (inputText.isNullOrBlank()) {
                return@addTool CallToolResult(
                    content = listOf(TextContent("Input text should not be empty"))
                )
            }

            androidInteractionManager.inputText(inputText)
            delay(500)
            val screenContext = androidInteractionManager.getScreenContext()

            CallToolResult(content = listOf(TextContent(screenContext)))
        }

        mcpServerLogic.addTool(
            name = "launch_app",
            description = """
                    Launches the specified application.
                    Returns cleaned screen context after processing.
                """.trimIndent(),
            inputSchema = Tool.Input(
                properties = buildJsonObject {
                    putJsonObject("packageName") { put("type", "string") }
                },
                required = listOf("packageName")
            ),
        ) { request: CallToolRequest ->
            val packageName = request.arguments["packageName"]?.jsonPrimitive?.contentOrNull

            if (packageName.isNullOrBlank()) {
                return@addTool CallToolResult(
                    content = listOf(TextContent("Package name should not be empty"))
                )
            }

            androidInteractionManager.launchApp(packageName)
            delay(2000)
            val screenContext = androidInteractionManager.getScreenContext()

            CallToolResult(content = listOf(TextContent(screenContext)))
        }

        mcpServerLogic.addTool(
            name = "close_app",
            description = """
                    Closes the specified application.
                    Returns cleaned screen context after processing.
                """.trimIndent(),
            inputSchema = Tool.Input(
                properties = buildJsonObject {
                    putJsonObject("packageName") { put("type", "string") }
                },
                required = listOf("packageName")
            ),
        ) { request: CallToolRequest ->
            val packageName = request.arguments["packageName"]?.jsonPrimitive?.contentOrNull

            if (packageName.isNullOrBlank()) {
                return@addTool CallToolResult(
                    content = listOf(TextContent("Package name should not be empty"))
                )
            }

            androidInteractionManager.closeApp(packageName)
            delay(500)
            val screenContext = androidInteractionManager.getScreenContext()

            CallToolResult(content = listOf(TextContent(screenContext)))
        }

        mcpServerLogic.addTool(
            name = "press_back_button",
            description = """
                    Presses the system back button.
                    Returns cleaned screen context after processing.
                """.trimIndent(),
        ) { _: CallToolRequest ->
            androidInteractionManager.pressBackButton()
            delay(500)
            val screenContext = androidInteractionManager.getScreenContext()

            CallToolResult(content = listOf(TextContent(screenContext)))
        }

        mcpServerLogic.addTool(
            name = "list_connected_devices",
            description = """
                    Retrieves a list of connected devices with their model names.
                """.trimIndent()
        ) { _: CallToolRequest ->
            val devices = androidInteractionManager.listConnectedDevices()

            println("list connected device - $devices")

            CallToolResult(content = listOf(TextContent(devices.joinToString("\n"))))
        }

        mcpServerLogic.addTool(
            name = "wait",
            description = """
                    Wait for given duration and then return screen context again.
                    Returns cleaned screen context after the delay.
                """.trimIndent(),
            inputSchema = Tool.Input(
                properties = buildJsonObject {
                    putJsonObject("timeInMillis") { put("type", "number") }
                },
                required = listOf("timeInMillis")
            ),
        ) { request: CallToolRequest ->
            val timeInMillis = request.arguments["timeInMillis"]?.jsonPrimitive?.longOrNull

            if (timeInMillis == null || timeInMillis <= 0) {
                return@addTool CallToolResult(
                    content = listOf(TextContent("timeInMillis should be a positive number"))
                )
            }

            delay(timeInMillis)
            val screenContext = androidInteractionManager.getScreenContext()

            CallToolResult(content = listOf(TextContent(screenContext)))
        }

        mcpServerLogic.addTool(
            name = "execute_command",
            description = """
                    Runs a custom ADB command if other tools are insufficient.
                    Returns cleaned screen context after execution.
                """.trimIndent(),
            inputSchema = Tool.Input(
                properties = buildJsonObject {
                    putJsonObject("command") { put("type", "string") }
                },
                required = listOf("command")
            ),
        ) { request: CallToolRequest ->
            val command = request.arguments["command"]?.jsonPrimitive?.contentOrNull

            if (command.isNullOrBlank()) {
                return@addTool CallToolResult(
                    content = listOf(TextContent("Command should not be empty"))
                )
            }

            androidInteractionManager.executeCommand(command)
            delay(500)
            val screenContext = androidInteractionManager.getScreenContext()

            CallToolResult(content = listOf(TextContent(screenContext)))
        }
    }
}
