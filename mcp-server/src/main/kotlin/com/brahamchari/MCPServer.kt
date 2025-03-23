package com.brahamchari

interface MCPServer {

    var isServerRunning: Boolean

    fun startServer()

    suspend fun stopServer()
}