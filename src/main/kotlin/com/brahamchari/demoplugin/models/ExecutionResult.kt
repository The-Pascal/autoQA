package com.brahamchari.demoplugin.models

import com.anthropic.core.JsonValue

data class TestExecutionLog(
    val id: String,
    val userInput: String,
    val startTime: Long,
    var endTime: Long? = null,
    var status: TestStatus = TestStatus.RUNNING,
    var isSaved: Boolean = false,
    var executionResult: ExecutionResult? = null,
    var error: ExecutionError? = null
)

data class ExecutionResult(
    val introduction: String,
    val llmUsed: String,
    var targetDeviceId: String? = null,
    val testSteps: MutableList<TestStep>? = null,
)

data class ExecutionError(
    var message: String
)

data class TestStep(
    val id: String,
    var title: String,
    val timestamp: Long,
    var screenshotPath: String? = null,
    var outcome: StepOutcome,
    var actions: List<Action>? = null,
    var errorMessage: String? = null
)

data class Action(
    val actionContext: ActionContext? = null,
    val toolInfo: ToolInfo? = null
)

data class ActionContext(
    val context: String,
    val feedback: ActionFeedback,
    val resourceId: String? = null
)

data class ToolInfo(
    val toolName: String,
    val toolArguments: Map<String, JsonValue>
)

enum class StepOutcome { SUCCESS, FAILURE, CONTINUE, ERROR }

enum class ActionFeedback { PASS, FAIL, CONTINUE }