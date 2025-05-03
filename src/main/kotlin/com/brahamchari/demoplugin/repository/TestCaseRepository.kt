package com.brahamchari.demoplugin.repository

import com.android.ddmlib.AndroidDebugBridge
import com.android.ddmlib.IDevice
import com.brahamchari.demoplugin.client.MCPClient
import com.brahamchari.demoplugin.client.ProcessResult
import com.brahamchari.demoplugin.client.QueryResult
import com.brahamchari.demoplugin.di.TestCaseInjector
import com.brahamchari.demoplugin.models.*
import com.brahamchari.demoplugin.services.McpService
import com.brahamchari.demoplugin.utils.ADBUtils
import com.brahamchari.demoplugin.utils.PromptGenerator
import com.brahamchari.demoplugin.utils.Utils
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.ChannelResult
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.onFailure
import kotlinx.coroutines.flow.*
import java.awt.Rectangle
import java.util.UUID

interface TestCaseRepository {
    var isTestRunning: Boolean

    fun runTestCase(testId: String, textInput: String, deviceId: String): Flow<TestExecutionLog>

    suspend fun stopRunningTest()

    /**
     * Provides a Flow that emits the current list of connected ADB devices
     * whenever the list changes. The flow handles the underlying ADB listener.
     * Emits an empty list if ADB is unavailable or an error occurs during fetch.
     */
    fun getAdbDevicesFlow(): Flow<List<IDevice>>
}

class TestCaseRepositoryImpl(
    private val project: Project,
    parentDisposable: Disposable
) : TestCaseRepository {

    private val log = Logger.getInstance(TestCaseRepositoryImpl::class.java)

    private var currentTestJob: Job? = null

    private val gson: Gson by lazy { TestCaseInjector.getTestCaseInjector().gson }
    private val repositoryScope by lazy { CoroutineScope(SupervisorJob() + Dispatchers.Default) }
    private val mcpService: McpService by lazy { TestCaseInjector.getTestCaseInjector().getMCPService(project) }

    init {
        Disposer.register(parentDisposable) {
            log.info("Disposing TestCaseRepositoryImpl, cancelling scope.")
            repositoryScope.cancel("Repository instance disposed.")
        }
    }

    @Volatile
    override var isTestRunning: Boolean = false

    override fun runTestCase(testId: String, textInput: String, deviceId: String): Flow<TestExecutionLog> = channelFlow {
        val testExecutionLog = TestExecutionLog(
            id = testId,
            userInput = textInput,
            startTime = System.currentTimeMillis(),
            status = TestStatus.RUNNING,
            isSaved = false
        )

        if (isTestRunning) {
            trySend(
                testExecutionLog.copy(
                    status = TestStatus.FAILED,
                    error = ExecutionError(message = "Test is already running")
                )
            )
            close(IllegalStateException("Test is already running"))
            return@channelFlow
        }

        var serviceInitStatus = true
        if (!mcpService.isServiceRunning) {
            log.info("MCP Service not running, attempting to initialize...")
            serviceInitStatus = mcpService.initializeAndStart()
        }
        if (!serviceInitStatus) {
            log.error("MCP Service unavailable")
            trySend(
                testExecutionLog.copy(
                    status = TestStatus.FAILED,
                    error = ExecutionError(message = "Request service (MCP) unavailable")
                )
            )
            close(IllegalStateException("MCP Service unavailable"))
            return@channelFlow
        }

        isTestRunning = true
        log.info("\n\n\n\nRun Test case started for input: '$textInput'\n\n\n\n")

        currentTestJob = repositoryScope.launch(Dispatchers.IO) {
            var loopError: Exception? = null
            try {
                testExecutionLog.executionResult = ExecutionResult(
                    introduction = getTestIntro(textInput, deviceId),
                    llmUsed = "Claude",
                    targetDeviceId = deviceId,
                    testSteps = mutableListOf()
                )
                trySend(testExecutionLog.copy())

                do {
                    val currentStepNumber = (testExecutionLog.executionResult?.testSteps?.size ?: 0) + 1
                    log.info("\n\nRunning step $currentStepNumber")

                    val mcpResponse = makeMCPCall(testExecutionLog, deviceId)
                    testExecutionLog.executionResult?.testSteps?.add(mcpResponse)
                    testExecutionLog.status = when(mcpResponse.outcome) {
                        StepOutcome.SUCCESS -> TestStatus.PASSED
                        StepOutcome.FAILURE -> TestStatus.FAILED
                        StepOutcome.CONTINUE -> TestStatus.RUNNING
                        StepOutcome.ERROR -> {
                            val errorMessage = mcpResponse.errorMessage ?: "Error while processing step: ${mcpResponse.title}"
                            loopError = Exception(errorMessage)
                            testExecutionLog.error = ExecutionError(errorMessage)
                            TestStatus.FAILED
                        }
                    }

                    trySend(testExecutionLog.copy())
                    log.info("Emitted step $currentStepNumber ${mcpResponse.title}. FinalStatus: ${testExecutionLog.status}\n\n")
                    delay(150) // Optional delay between steps
                } while (testExecutionLog.status == TestStatus.RUNNING && isActive)

            } catch (e: CancellationException) {
                log.info("Test execution job cancelled for input: '$textInput'.")
                loopError = e
            } catch (e: Exception) {
                log.error("Exception during test execution loop for input: '$textInput'.", e)
                loopError = e
            } finally {
                log.info("Finishing test execution job for input: '$textInput'.")
                isTestRunning = false

                if (isActive) {
                    if (loopError != null && loopError !is CancellationException) {
                        testExecutionLog.status = TestStatus.FAILED
                        testExecutionLog.error = ExecutionError(loopError.message ?: "Test failed due to exception")
                    } else if (loopError is CancellationException) {
                        testExecutionLog.status = TestStatus.STOPPED
                        testExecutionLog.error = ExecutionError("Test stopped by user")
                    }
                    trySend(testExecutionLog.copy())
                    log.info("Sent final log state: ${testExecutionLog.status}")
                } else {
                    log.warn("Flow/Scope became inactive before final state could be sent.")
                }
                // Close the flow from the producer side upon completion or error
                close(loopError) // Use default close handling
            }
        }

        // Cleanup: Cancel the job if the flow collector stops collecting
        awaitClose {
            log.warn("Test execution flow closing (awaitClose).")
            isTestRunning = false // Ensure flag is reset
            if (currentTestJob?.isActive == true) {
                log.info("Cancelling active test job due to flow closure.")
                currentTestJob?.cancel(CancellationException("Flow collection stopped."))
            }
        }
    }.flowOn(Dispatchers.IO)

    private fun mapToActions(queryResult: List<QueryResult>): Pair<StepOutcome, List<Action>> {
        var actionFeedback = StepOutcome.CONTINUE
        val actions = queryResult.map { block ->
            val actionContext = block.textResult
                ?.takeIf { it.isNotBlank() }
                ?.let { jsonText ->
                    try {
                        val actionContext = gson.fromJson(jsonText, ActionContext::class.java)
                        actionFeedback = when(actionContext.feedback) {
                            ActionFeedback.PASS -> StepOutcome.SUCCESS
                            ActionFeedback.FAIL -> StepOutcome.FAILURE
                            ActionFeedback.CONTINUE -> StepOutcome.CONTINUE
                        }
                        actionContext
                    } catch (e: Exception) {
                        log.warn("Failed to parse ActionContext JSON: $jsonText", e)
                        null
                    }
                }
            val toolInfo = block.toolCallInfo?.let { tcInfo ->
                ToolInfo(tcInfo.toolName, tcInfo.inputArgumentsJson)
            }
            Action(actionContext, toolInfo)
        }

        return Pair(actionFeedback, actions)
    }

    private suspend fun makeMCPCall(testExecutionLog: TestExecutionLog, deviceId: String, retries: Int = 2): TestStep {
        val testSteps = testExecutionLog.executionResult?.testSteps
        val currentStepIndex = testSteps?.size ?: 0
        val stepNumber = currentStepIndex + 1

        val currentTestStep = TestStep(
            id = UUID.randomUUID().toString(),
            title = "Step $stepNumber",
            timestamp = System.currentTimeMillis(),
            screenshotPath = null,
            outcome = StepOutcome.CONTINUE
        )

        if (retries <= 0) {
            log.warn("MCP Call failed after all retries for test: ${testExecutionLog.userInput} Step: $stepNumber")
            currentTestStep.outcome = StepOutcome.ERROR
            currentTestStep.title = "Step $stepNumber: Failed (Retries Exhausted)"
            currentTestStep.errorMessage = "AI/MCP call failed after multiple retries."
            return currentTestStep
        }

        try {
            currentTestStep.screenshotPath = try {
                withContext(Dispatchers.IO) { // Ensure ADB calls are off main thread
                    val screenshotName = "screenshot_${System.currentTimeMillis()}_step${stepNumber}"
                    ADBUtils.captureAndGetScreenshotPath(deviceId, screenshotName)
                }
            } catch (e: Exception) {
                log.error("Failed to capture screenshot for step $stepNumber", e)
                null
            }

            val screenCtxJson: String = getScreenCtxJson(deviceId)

            log.info("Making MCP call for test '${testExecutionLog.userInput}', Step $stepNumber, Try ${3 - retries}")

            val previousStepsContext = gson.toJson(testSteps?.map { it.actions } ?: "No previous steps")
            val lastMCPStep = gson.toJson(testSteps?.lastOrNull() ?: "No last step")
            val mcpClient = mcpService.mcpClient

            if(mcpClient.isConnected) {
                val systemPrompt = PromptGenerator.getActionSystemPrompt()
                val userPrompt = PromptGenerator.getActionUserPrompt(testExecutionLog.userInput, screenCtxJson, lastMCPStep, previousStepsContext)

                when(val mcpResult = mcpClient.processQuery(userPrompt, systemPrompt)) {
                    is ProcessResult.Error -> throw Exception("MCP processing error: ${mcpResult.exception.message}")
                    is ProcessResult.Success -> {
                        return try {
                            val (stepOutcome, actions) = mapToActions(mcpResult.queryResult)
                            currentTestStep.apply {
                                this.actions = actions
                                this.outcome = stepOutcome
                            }
                        } catch (e: JsonSyntaxException) {
                            log.error("Failed to parse ActionContext JSON from MCP for step $stepNumber: ${mcpResult.queryResult}", e)
                            currentTestStep.outcome = StepOutcome.ERROR
                            currentTestStep.errorMessage = "Failed to parse AI action JSON: ${e.message}"
                            currentTestStep.title = "Step $stepNumber: Error (Parse Failed)"
                            currentTestStep
                        }
                    }
                }
            } else {
                throw IllegalStateException("MCP Server not connected.")
            }
        } catch (e: Exception) {
            log.warn("Exception during makeMCPCall (try ${3 - retries}) for step $stepNumber: ${e.message}", e)

            if (e is CancellationException) {
                log.info("MCP call for step $stepNumber cancelled.")
                currentTestStep.outcome = StepOutcome.ERROR // Or a specific CANCELLED state?
                currentTestStep.errorMessage = "Operation cancelled during step $stepNumber."
                currentTestStep.title = "Step $stepNumber: Cancelled"
                return currentTestStep // Don't retry if cancelled
            }

            log.warn("Retrying MCP call for step $stepNumber (${retries - 1} retries left)...")
            val delayMillis = 500L
            delay(delayMillis)
            return makeMCPCall(testExecutionLog, deviceId, retries - 1)
        }
    }

    private suspend fun getTestIntro(textInput: String, deviceId: String): String {
        return try {
            val screenCtxJson = getScreenCtxJson(deviceId)
            if (mcpService.mcpClient.isConnected) {
                val userPrompt = PromptGenerator.getIntroductionUserPrompt(textInput, screenCtxJson)
                val systemPrompt = PromptGenerator.getIntroductionSystemPrompt()
                return when(val queryResult = mcpService.mcpClient.processQuery(userPrompt, systemPrompt)) {
                    is ProcessResult.Error -> throw queryResult.exception
                    is ProcessResult.Success -> {
                        queryResult.queryResult[0].textResult ?: throw Exception("Unable to generate introduction")
                    }
                }
            } else {
                throw Exception("MCP Service is unavailable")
            }
        } catch (e: Exception) {
            log.error("getTestIntro(): Error - ${e.message}", e)
            "Unable to generate introduction. Processing test: $textInput"
        }
    }

    private suspend fun getScreenCtxJson(deviceId: String): String {
        val screenCtxJson: String = try {
            withContext(Dispatchers.IO) {
                ADBUtils.runAdbCommand("shell uiautomator dump /sdcard/window_dump.xml", deviceId)
                val screenCtxXml = ADBUtils.runAdbCommand("shell cat /sdcard/window_dump.xml", deviceId)
                getCleanedScreenContext(screenCtxXml)
            }
        } catch (e: Exception) {
            log.error("Failed to get/clean screen context", e)
            "{ \"error\": \"Failed to get screen context\" }" // Provide error JSON
        }
        return screenCtxJson
    }

    private fun getCleanedScreenContext(screenXml: String): String {
        return gson.toJson(Utils.cleanHierarchyDump(screenXml))
    }

    override suspend fun stopRunningTest() {
        val jobToCancel = currentTestJob
        if (jobToCancel?.isActive == true) {
            log.info("stopRunningTest called, cancelling job.")
            isTestRunning = false
            jobToCancel.cancelAndJoin()
            log.info("Test job cancelled and joined.")
        } else {
            log.info("stopRunningTest called, but no active test job found.")
            isTestRunning = false
        }
    }

    // --- Reactive ADB Device Flow Implementation ---
    private val adbDevicesSharedFlow: SharedFlow<List<IDevice>> = callbackFlow {
        println(">>> Starting ADB device monitoring callbackFlow")

        // Define the ADB listener within the flow builder
        val adbListener = object : AndroidDebugBridge.IDeviceChangeListener {
            // Helper function to refresh and send device list into the flow
            fun refreshAndSend(reason: String) {
                println("ADBListener requesting refresh (Reason: $reason)")
                // Launch background task within the flow's scope
                repositoryScope.launch(Dispatchers.IO) {
                    try {
                        val devices = ADBUtils.fetchAdbDevices(project)
                        println("Fetched ${devices.size} devices, sending to flow.")
                        // Sort devices: Emulators last, then by name/serial
                        val sortedDevices =
                            devices.sortedWith(compareBy({ !it.isEmulator }, { it.name ?: it.serialNumber }))
                        // trySend is non-blocking, suitable here
                        val result = trySend(sortedDevices)
                        result.onFailure { throwable ->
                            if (throwable != null) {
                                throwable.printStackTrace()
                                println("Failed to send devices to flow: ${throwable.message}")
                            } else {
                                println("Failed to send devices to flow (channel closed or full)")
                            }
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                        println("Error fetching/sending ADB devices in flow: ${e.message}")
                        // Send empty list on error to signal failure to collector
                        val result: ChannelResult<Unit> = trySend(emptyList()) // 1. Call trySend and store result
                        result.onFailure { throwable -> // 2. Call onFailure on the result variable
                            if (throwable != null) {
                                println("Failed to send empty list after error: ${throwable.message}")
                            } else {
                                println("Failed to send empty list after error (channel closed or full)")
                            }
                        }
                    }
                }
            }

            override fun deviceConnected(device: IDevice) {
                refreshAndSend("Device Connected: ${device.serialNumber}")
            }

            override fun deviceDisconnected(device: IDevice) {
                refreshAndSend("Device Disconnected: ${device.serialNumber}")
            }

            override fun deviceChanged(device: IDevice, changeMask: Int) {
                if (changeMask and (IDevice.CHANGE_STATE or IDevice.CHANGE_BUILD_INFO) != 0) {
                    refreshAndSend("Device Changed: ${device.serialNumber}")
                } else {
                    println("Device Changed event ignored (Mask: $changeMask): ${device.serialNumber}")
                }
            }
        }

        // Add the listener (should happen off EDT)
        println("Adding ADB Change Listener")
        AndroidDebugBridge.addDeviceChangeListener(adbListener)

        // Perform initial fetch right after adding listener
        println("Performing initial ADB device fetch for flow")
        adbListener.refreshAndSend("Initial Fetch") // Call the helper

        // awaitClose is crucial for cleanup when the flow collector cancels
        awaitClose {
            println("<<< Stopping ADB device monitoring callbackFlow, removing listener.")
            AndroidDebugBridge.removeDeviceChangeListener(adbListener)
        }
    }
        .flowOn(Dispatchers.IO)
        .distinctUntilChanged()
        .shareIn(
            scope = repositoryScope,
            started = SharingStarted.WhileSubscribed(5000),
            replay = 1
        )

    override fun getAdbDevicesFlow(): Flow<List<IDevice>> {
        return adbDevicesSharedFlow
    }

    private fun getInitialAction(): AiResponseData {
        return AiResponseData(
            aiResponse = AiResponse(
                context = "Starting test case",
                action = AiAction(
                    type = AiActionType.DELAY,
                    delayAfter = 500
                ),
                feedback = AiFeedback.CONTINUE
            ),
            timestamp = System.currentTimeMillis()
        )
    }

    private fun removeJsonTags(input: String): String {
        // First, remove the leading "```json" part and the trailing "```"
        val withoutLeadingJsonTag = input.substringAfter("```json").trim()
        return withoutLeadingJsonTag.substringBefore("```").trim()
    }

    // --- Helper methods (Keep relevant ones) ---

    // Maps internal AiResponseData to TestStep for logging
    private fun mapToTestStep(stepIndex: Int, responseData: AiResponseData): TestStepOld {
        // TODO: Adjust mapping based on actual AiResponseData structure
        return TestStepOld(
            stepNumber = stepIndex + 1, // Steps are 1-based
            screenshotPath = responseData.screenshotPath,
            action = responseData.aiResponse.context, // Or a more specific action string?
            resourceId = responseData.aiResponse.action.resourceId, // Assuming AiAction is nullable or has nullable fields
            bounds = Rectangle(100, 200).toString()
        )
    }

    // Maps internal AI Feedback to final TestRunningStatus for the log
    private fun mapFeedbackToFinalStatus(feedback: AiFeedback?): TestStatus? {
        return when (feedback) {
            AiFeedback.PASS -> TestStatus.PASSED
            AiFeedback.FAIL -> TestStatus.FAILED
            AiFeedback.INTERRUPTED -> TestStatus.STOPPED // Or maybe STOPPED? Define behaviour.
            AiFeedback.CONTINUE -> null // Not a final status
            null -> null // Handle null feedback if possible
        }
    }

    private suspend fun makeAiCall(
        previousResponse: AiResponse,
        testCase: TestCase,
        screenContext: String,
        actionList: List<String>,
        mcpClient: MCPClient?
    ): AiResponse {
        // Ensure MCPClient is passed correctly and used
        if (mcpClient == null || !mcpClient.isConnected) throw Exception("MCP Client is not available or not connected")
        log.info("Making AI call for test: ${testCase.name}, step: ${actionList.size + 1}")
        // TODO: Implement actual AI call using MCPClient or other service
        mcpClient.processQuery("")
        // Example placeholder response based on feedback logic
        delay(500) // Simulate AI processing time
        return if (actionList.size < 1) { // Simulate 4 steps total
            AiResponse(
                context = "Simulated action for step ${actionList.size + 1}",
                action = AiAction(
                    type = AiActionType.DELAY, // Example action
                    delayAfter = 500,
                    resourceId = "@id/some_button_${actionList.size + 1}",
                    bounds = Rectangle(100, 200)
                ),
                feedback = AiFeedback.CONTINUE
            )
        } else {
            AiResponse(
                context = "Simulated final step, assuming Pass",
                action = AiAction(type = AiActionType.KillApp), // No further action
                feedback = AiFeedback.PASS
            )
        }
    }


}