package com.brahamchari

interface MCPServer {

    var isServerRunning: Boolean

    suspend fun startServer(): Boolean

    suspend fun stopServer()
}