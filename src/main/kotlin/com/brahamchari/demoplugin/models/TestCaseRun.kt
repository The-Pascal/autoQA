package com.brahamchari.demoplugin.models

data class TestCaseRun(
        val aiResponses: MutableList<AiResponseData> = mutableListOf(),
        var runningStatus: TestRunningStatus,
        var testFinalStatus: TestFinalStatus? = null,
        var message: String? = null
)

data class AiResponseData(
        var aiResponse: AiResponse,
        var timestamp: Long,
        var screenshotPath: String? = null
)
