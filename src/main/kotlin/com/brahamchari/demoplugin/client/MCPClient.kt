package com.brahamchari.demoplugin.client

import io.modelcontextprotocol.kotlin.sdk.Implementation
import io.modelcontextprotocol.kotlin.sdk.client.Client
import java.net.Socket

class MCPClient(
        private val host: String = "localhost",
        private val port: Int = 5000
) {
    private val mcpClient = Client(clientInfo = Implementation("mcp-client", "1.0.0"))
    private lateinit var socket: Socket
}