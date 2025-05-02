package com.brahamchari.demoplugin.client

import com.anthropic.core.JsonValue

sealed interface ProcessResult {
    data class Success(val queryResult: List<QueryResult>) : ProcessResult

    data class Error(val exception: Throwable) : ProcessResult
}

data class QueryResult(
    /**
     * if there is some text output then store in this
     */
    val textResult: String? = null,
    /**
     * if there is some tool call, then store in this
     */
    val toolCallInfo: ToolCallInfo? = null
)

data class ToolCallInfo(
    val toolName: String,
    val inputArgumentsJson:  Map<String, JsonValue>,
    val toolCallResult: String
)
