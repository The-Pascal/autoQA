package com.brahamchari.android

import com.brahamchari.MCPServer
import io.ktor.utils.io.streams.*
import io.modelcontextprotocol.kotlin.sdk.*
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.StdioServerTransport
import kotlinx.coroutines.*
import kotlinx.io.asSink
import kotlinx.io.buffered
import kotlinx.serialization.json.*

class AndroidMCPServerImpl(private val adbPath: String) : MCPServer {

    private lateinit var server: Server

    private val coroutineScope by lazy { CoroutineScope(Dispatchers.IO + SupervisorJob()) }
    private val androidInteractionManager by lazy { AndroidInteractionManagerImpl(adbPath) }

    @Volatile
    override var isServerRunning: Boolean = false

    override fun startServer() {
        coroutineScope.launch {
            server = createServer()
            addAllTools()

            // Create a transport using standard IO for server communication
            val transport = StdioServerTransport(
                    System.`in`.asInput(),
                    System.out.asSink().buffered()
            )

            server.connect(transport)

            // Wait for server closure without blocking
            val done = CompletableDeferred<Unit>()
            server.onCloseCallback = {
                done.complete(Unit)
            }
            isServerRunning = true
            done.await()
        }
    }

    override suspend fun stopServer() {
        if (::server.isInitialized) {
            server.close()
            coroutineScope.coroutineContext.cancelChildren() // Cancel all running coroutines
            isServerRunning = false
        } else {
            throw IllegalStateException("Server not initialized")
        }
    }

    private fun createServer(): Server {
        return Server(
                Implementation(name = "android-mcp", version = "1.0.0"),
                ServerOptions(capabilities = ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = false)))
        )
    }

    private fun addAllTools() {

        server.addTool(
                name = "get_screen_context",
                description = """
                    Returns the current UI hierarchy dump from the screen.
                """.trimIndent()
        ) {
            delay(1000)
            val screenContext = androidInteractionManager.getScreenContext()
            CallToolResult(content = listOf(TextContent(screenContext)))
        }

        server.addTool(
                name = "tap_on_screen",
                description = """
                    Uses adb command to tap the coordinates on the screen.
                    Returns screen context after the tap action is completed.
                """.trimIndent(),
                inputSchema = Tool.Input(
                        properties = buildJsonObject {
                            put("xCoordinate", JsonPrimitive("number"))
                            put("yCoordinate", JsonPrimitive("number"))
                        },
                        required = listOf("xCoordinate", "yCoordinate")
                ),
        ) { request: CallToolRequest ->
            val xCoordinate = request.arguments["xCoordinate"]?.jsonPrimitive?.longOrNull
            val yCoordinate = request.arguments["yCoordinate"]?.jsonPrimitive?.longOrNull

            if(xCoordinate == null || yCoordinate == null) {
                return@addTool CallToolResult(
                        content = listOf(TextContent("xCoordinate & yCoordinate should not be null"))
                )
            }

            androidInteractionManager.tapOnScreen(xCoordinate, yCoordinate)
            delay(500)
            val screenContext = androidInteractionManager.getScreenContext()

            CallToolResult(content = listOf(TextContent(screenContext)))
        }

        server.addTool(
                name = "swipe_on_screen",
                description = """
                    Performs a swipe gesture from start to end coordinates.
                    Returns screen context after completion.
                """.trimIndent(),
                inputSchema = Tool.Input(
                        properties = buildJsonObject {
                            put("startX", JsonPrimitive("number"))
                            put("startY", JsonPrimitive("number"))
                            put("endX", JsonPrimitive("number"))
                            put("endY", JsonPrimitive("number"))
                            put("duration", JsonPrimitive("number"))
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

        server.addTool(
                name = "input_text",
                description = """
                    Inputs text into the currently focused field.
                    Returns cleaned screen context after processing.
                """.trimIndent(),
                inputSchema = Tool.Input(
                        properties = buildJsonObject {
                            put("input", JsonPrimitive("string"))
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

        server.addTool(
                name = "launch_app",
                description = """
                    Launches the specified application.
                    Returns cleaned screen context after processing.
                """.trimIndent(),
                inputSchema = Tool.Input(
                        properties = buildJsonObject {
                            put("packageName", JsonPrimitive("string"))
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

        server.addTool(
                name = "close_app",
                description = """
                    Closes the specified application.
                    Returns cleaned screen context after processing.
                """.trimIndent(),
                inputSchema = Tool.Input(
                        properties = buildJsonObject {
                            put("packageName", JsonPrimitive("string"))
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

        server.addTool(
                name = "press_back_button",
                description = """
                    Presses the system back button.
                    Returns cleaned screen context after processing.
                """.trimIndent(),
                inputSchema = Tool.Input(
                        properties = buildJsonObject {},
                        required = emptyList()
                ),
        ) { _: CallToolRequest ->
            androidInteractionManager.pressBackButton()
            delay(500)
            val screenContext = androidInteractionManager.getScreenContext()

            CallToolResult(content = listOf(TextContent(screenContext)))
        }

        server.addTool(
                name = "list_connected_devices",
                description = """
                    Retrieves a list of connected devices with their model names.
                """.trimIndent(),
                inputSchema = Tool.Input(
                        properties = buildJsonObject {},
                        required = emptyList()
                ),
        ) { _: CallToolRequest ->
            val devices = androidInteractionManager.listConnectedDevices()

            CallToolResult(content = listOf(TextContent(devices.joinToString("\n"))))
        }

        server.addTool(
                name = "wait",
                description = """
                    Wait for given duration and then return screen context again.
                    Returns cleaned screen context after the delay.
                """.trimIndent(),
                inputSchema = Tool.Input(
                        properties = buildJsonObject {
                            put("timeInMillis", JsonPrimitive("number"))
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

        server.addTool(
                name = "execute_command",
                description = """
                    Runs a custom ADB command if other tools are insufficient.
                    Returns cleaned screen context after execution.
                """.trimIndent(),
                inputSchema = Tool.Input(
                        properties = buildJsonObject {
                            put("command", JsonPrimitive("string"))
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
