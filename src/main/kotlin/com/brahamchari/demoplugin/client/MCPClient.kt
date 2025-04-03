package com.brahamchari.demoplugin.client

import com.anthropic.client.AnthropicClient
import com.anthropic.core.JsonValue
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.MessageParam
import com.anthropic.models.messages.Model
import com.anthropic.models.messages.ToolUnion
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import io.ktor.client.*
import io.ktor.client.engine.cio.* // Or another engine like OkHttp
import io.ktor.client.plugins.*
import io.ktor.client.plugins.websocket.*
import io.modelcontextprotocol.kotlin.sdk.* // Import core SDK classes (Client, JSONRPCMessage etc)
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.WebSocketClientTransport
import io.modelcontextprotocol.kotlin.sdk.shared.Transport
import kotlinx.coroutines.*
import kotlinx.serialization.json.JsonObject
import kotlin.coroutines.CoroutineContext
import kotlin.jvm.optionals.getOrNull
import kotlin.time.Duration.Companion.seconds

// Interface defining the client operations (optional but good practice)
interface MCPClient {
    val isConnected: Boolean
    suspend fun connect()
    suspend fun disconnect()

    suspend fun processQuery(query: String): String

    fun shutdown() // To release resources
}

class AndroidMCPClient(
    private val anthropicClient: AnthropicClient,
    private val host: String = "localhost", // Default to localhost
    private val port: Int = 5000,           // Default to the server's default port,
    // Inject scope or create one. Creating one here for simplicity.
    parentScope: CoroutineScope? = null
) : MCPClient, CoroutineScope {

    // Derive URL from host and port
    private val serverUrl = "ws://$host:$port/mcp" // Assumes server endpoint is /mcp

    // Dedicated scope for this client's operations + Ktor client
    private val clientScope = CoroutineScope(
        parentScope?.coroutineContext ?: (SupervisorJob() + Dispatchers.IO + CoroutineName("AndroidMCPClientScope"))
    )
    override val coroutineContext: CoroutineContext
        get() = clientScope.coroutineContext

    // Ktor HTTP Client for WebSockets
    private val httpClient = HttpClient(CIO) { // Or OkHttp if preferred/available
        install(WebSockets) {
            // Configure WebSocket options if needed (timeouts, etc.)
            pingInterval = 10.seconds.inWholeMilliseconds
            maxFrameSize = Long.MAX_VALUE
        }
        // Install and configure Timeouts
        install(HttpTimeout) {
            // Timeout for establishing the initial TCP connection
            connectTimeoutMillis = 10.seconds.inWholeMilliseconds // 10 seconds

            // Timeout for processing the entire HTTP request (including handshake)
            // Set reasonably high for WS setup. Can be null for indefinite wait after handshake.
            requestTimeoutMillis = 30.seconds.inWholeMilliseconds // 30 seconds total for setup

            // Timeout for waiting for data on the socket (after connection)
            // Important during handshake and if network stalls during frame transfer.
            socketTimeoutMillis = 20.seconds.inWholeMilliseconds // 20 seconds wait for data
        }

    }

    // MCP SDK Client logic instance
    private var mcpClientLogic: Client? = null
    // MCP Transport instance
    private var transport: WebSocketClientTransport? = null

    private var tools: List<Tool>? = null
    private var anthropicTools: List<ToolUnion>? = null

    private val messageParamsBuilder: MessageCreateParams.Builder = MessageCreateParams.builder()
        .model(Model.CLAUDE_3_5_SONNET_20241022)
        .maxTokens(1024)

    @Volatile
    override var isConnected: Boolean = false
        private set

    private var connectionJob: Job? = null

    override suspend fun connect() {
        // Prevent concurrent connection attempts or connecting if already connected
        if (isConnected || connectionJob?.isActive == true) {
            println("MCP Client: Already connected or connection attempt in progress.")
            return
        }

        println("MCP Client: Attempting to connect to $serverUrl...")
        connectionJob = clientScope.launch { // Launch connection logic in the client's scope
            try {
                // 1. Create the transport
                // This transport's initializeSession() will be called later by mcpClientLogic.connect()
                val newTransport = WebSocketClientTransport(
                    client = httpClient,
                    urlString = serverUrl
                    // requestBuilder can be added here if custom headers etc. are needed,
                    // but the transport handles the MCP subprotocol header internally.
                )
                transport = newTransport // Store the reference

                // 2. Create the core MCP Client logic instance
                // Pass necessary client options if the SDK requires them
                val newClientLogic = object : Client(clientInfo = Implementation(name = "mcp-client-android", version = "1.0.0")) {
                    override fun onClose() {
                        super.onClose()
                        println("MCP Client: Connection closed (onClose callback).")
                        handleDisconnection() // Handle cleanup when closed by server or error
                    }

                    override fun onError(error: Throwable) {
                        super.onError(error)
                        System.err.println("MCP Client: Error received (onError callback): ${error.message}")
                        // do I need to handle disconnection here?
                    }

                    override suspend fun connect(transport: Transport) {
                        super.connect(transport)
                        println("Connecting to the server")
                    }
                }
                mcpClientLogic = newClientLogic // Store the reference

                // 4. Initiate the connection via the MCP Client logic
                // This call likely triggers transport.start() -> transport.initializeSession()
                // which performs the actual WebSocket connection attempt.
                newClientLogic.connect(newTransport)

                val toolsResult = newClientLogic.listTools()
                tools = toolsResult?.tools
                anthropicTools = toolsResult?.tools?.map { tool ->
                    ToolUnion.ofTool(
                        com.anthropic.models.messages.Tool.builder()
                            .name(tool.name)
                            .description(tool.description ?: "")
                            .inputSchema(
                                com.anthropic.models.messages.Tool.InputSchema.builder()
                                    .type(JsonValue.from(tool.inputSchema.type))
                                    .properties(tool.inputSchema.properties.toJsonValue())
                                    .putAdditionalProperty("required", JsonValue.from(tool.inputSchema.required))
                                    .build()
                            ).build()
                    )
                }

                println("Tools - $tools\n\n\n\n")
                println("Anthropic tools - $anthropicTools\n\n\n\n")

                // --- IMPORTANT ---
                // The `connect` call above likely returns *before* the WebSocket handshake
                // is fully complete and the MCP initialize flow finishes.
                // Setting `isConnected = true` here is optimistic.
                // A truly robust implementation would wait for an `onInitialized` callback
                // from the `mcpClientLogic` if the SDK provides one.
                // For now, we assume connection if no immediate exception occurs.
                isConnected = true
                println("MCP Client: Connection initiated via SDK. Assuming connected (check callbacks for confirmation).")

            } catch (e: Exception) {
                // Catch exceptions during transport/client creation or the connect() call itself
                System.err.println("MCP Client: Connection initiation failed: ${e.message}")
                e.printStackTrace()
                handleDisconnection() // Ensure cleanup on failure
                // Optionally re-throw or return failure indication
                throw e // Re-throw to signal failure to caller
            } finally {
                // connectionJob = null // Reset job reference? Maybe not needed if using launch
            }
        }

        // Optionally wait for the connection job to finish initiation if desired,
        // but remember it might not mean fully connected yet.
        // connectionJob?.join()
    }

    // Process a user query and return a string response
    override suspend fun processQuery(query: String): String {
        if (!isConnected && mcpClientLogic == null) {
            throw Exception("MCP Client not initialized. Call connect() first")
        }

        // Create an initial message with a user's query
        val messages = mutableListOf(
            MessageParam.builder()
                .role(MessageParam.Role.USER)
                .content(query)
                .build()
        )

        // Send the query to the Anthropic model and get the response
        val response = anthropicClient.messages().create(
            messageParamsBuilder
                .messages(messages)
                .tools(anthropicTools!!)
                .build()
        )

        val finalText = mutableListOf<String>()
        response.content().forEach { content ->
            when {
                // Append text outputs from the response
                content.isText() -> finalText.add(content.text().getOrNull()?.text() ?: "")

                // If the response indicates a tool use, process it further
                content.isToolUse() -> {
                    val toolName = content.toolUse().get().name()
                    val toolArgs =
                        content.toolUse().get()._input().convert(object : TypeReference<Map<String, JsonValue>>() {})

                    // Call the tool with provided arguments
                    val result = mcpClientLogic?.callTool(
                        name = toolName,
                        arguments = toolArgs ?: emptyMap()
                    )
                    finalText.add("[Calling tool $toolName with args $toolArgs]")

                    // Add the tool result message to the conversation
                    messages.add(
                        MessageParam.builder()
                            .role(MessageParam.Role.USER)
                            .content(
                                """
                                        "type": "tool_result",
                                        "tool_name": $toolName,
                                        "result": ${result?.content?.joinToString("\n") { (it as TextContent).text ?: "" }}
                                    """.trimIndent()
                            )
                            .build()
                    )

                    // Retrieve an updated response after tool execution
                    val aiResponse = anthropicClient.messages().create(
                        messageParamsBuilder
                            .messages(messages)
                            .build()
                    )

                    // Append the updated response to final text
                    finalText.add(aiResponse.content().first().text().getOrNull()?.text() ?: "")
                }
            }
        }

        return finalText.joinToString("\n", prefix = "", postfix = "")
    }

    override suspend fun disconnect() {
        if (!isConnected && mcpClientLogic == null) {
            println("MCP Client: Already disconnected.")
            return
        }
        println("MCP Client: Disconnecting...")

        val clientToDisconnect = mcpClientLogic
        val jobToCancel = connectionJob

        // Clear state immediately
        handleDisconnection()

        // Cancel any ongoing connection attempt
        jobToCancel?.cancelAndJoin()

        // Ask the MCP client logic to perform disconnection (which should close transport)
        try {
            // Assuming the Client class has a disconnect method
            clientToDisconnect?.close() // This should trigger transport.close() -> session.close()
            println("MCP Client: SDK disconnect called.")
        } catch (e: Exception) {
            System.err.println("MCP Client: Error during SDK disconnect: ${e.message}")
            // Continue cleanup
        }
        println("MCP Client: Disconnect finished.")
    }

    /**
     * Cleans up client state and references. Call internally on disconnect/error.
     */
    private fun handleDisconnection() {
        isConnected = false
        transport = null
        mcpClientLogic = null
        connectionJob = null // Clear reference to the connection job
        println("MCP Client: Internal state cleaned up.")
    }

    /**
     * Releases the Ktor HttpClient resources. Call when the plugin is shutting down.
     */
    override fun shutdown() {
        println("MCP Client: Shutting down Ktor HttpClient...")
        // Ensure disconnect is called first
        // GlobalScope.launch { disconnect() } // Run disconnect in a scope if needed, but shutdown might be runBlocking
        httpClient.close() // Close the underlying Ktor client
        clientScope.cancel() // Cancel the client's coroutine scope
        println("MCP Client: Ktor HttpClient closed and scope cancelled.")
    }
}

private fun JsonObject.toJsonValue(): JsonValue {
    val mapper = ObjectMapper()
    val node = mapper.readTree(this.toString())
    return JsonValue.fromJsonNode(node)
}