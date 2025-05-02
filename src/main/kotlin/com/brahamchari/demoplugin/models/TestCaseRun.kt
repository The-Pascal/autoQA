package com.brahamchari.demoplugin.models

data class TestCaseRun(
        val aiResponses: MutableList<AiResponseData> = mutableListOf(),
        var runningStatus: TestStatus,
        var testFinalStatus: TestFinalStatus? = null,
        var message: String? = null
)

data class AiResponseData(
        var aiResponse: AiResponse,
        var timestamp: Long,
        var screenshotPath: String? = null
)
