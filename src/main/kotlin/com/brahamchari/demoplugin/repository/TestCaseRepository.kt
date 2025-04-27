package com.brahamchari.demoplugin.repository

import com.android.ddmlib.AndroidDebugBridge
import com.android.ddmlib.IDevice
import com.brahamchari.demoplugin.client.MCPClient
import com.brahamchari.demoplugin.di.TestCaseInjector
import com.brahamchari.demoplugin.models.*
import com.brahamchari.demoplugin.services.McpService
import com.brahamchari.demoplugin.utils.ADBUtils
import com.brahamchari.demoplugin.utils.Utils
import com.google.gson.Gson
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

interface TestCaseRepository {
    var isTestRunning: Boolean

    fun runTestCase(textInput: String, deviceId: String): Flow<TestExecutionLog>

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

    override fun runTestCase(textInput: String, deviceId: String): Flow<TestExecutionLog> = channelFlow {
        if (isTestRunning) {
            // TODO: ideally execution shouldn't come here if test is already running
            trySend(
                TestExecutionLog(
                    userInput = textInput,
                    isLoading = false,
                    finalStatus = TestRunningStatus.FAILED,
                    errorMessage = "Test is already running"
                )
            )
            close(IllegalStateException("Test is already running"))
            return@channelFlow
        }

        // Check MCP Service (Assuming this logic is correct)
        var serviceInitStatus = true
        if (!mcpService.isServiceRunning) {
            log.info("MCP Service not running, attempting to initialize...")
            serviceInitStatus = mcpService.initializeAndStart()
        }
        if (!serviceInitStatus) {
            log.error("MCP Service unavailable")
            trySend(
                TestExecutionLog(
                    userInput = textInput,
                    isLoading = false,
                    finalStatus = TestRunningStatus.FAILED,
                    errorMessage = "Required service (MCP) unavailable"
                )
            )
            close(IllegalStateException("MCP Service unavailable"))
            return@channelFlow
        }

        isTestRunning = true
        log.info("\n\n\n\nRun Test case started for input: '$textInput'\n\n\n\n")

        // --- Initialize Log Data ---
        // Create a mutable log object to update throughout the flow
        val currentLogData = TestExecutionLog(
            userInput = textInput,
            isLoading = true,
            finalStatus = null // Explicitly null initially
        )
        trySend(currentLogData.copy()) // Emit initial loading state

        var previousResponseInternal = getInitialAction() // Internal state for AI loop
        val testCaseObjectForAI = TestCase(id = -1, name = textInput) // Create dummy TestCase if makeAiCall requires it

        // Launch the main execution logic within the repository's scope
        currentTestJob = repositoryScope.launch(Dispatchers.IO) {
            var loopError: Exception? = null
            try {
                // --- Optional: Emit Introduction Step ---
                // You might want a specific AI call here for the intro, or extract from first response
                 currentLogData.botIntroduction = "Processing test: '$textInput'..." // Example
                 trySend(currentLogData.copy(isLoading = true)) // Emit intro + loading
                 delay(500)

                // --- Main Step Loop ---
                while (previousResponseInternal.aiResponse.feedback == AiFeedback.CONTINUE && isActive) { // Check isActive
                    val currentStepIndex = currentLogData.steps.size
                    log.info("\n\nRunning step ${currentStepIndex + 1}")
                    currentLogData.isLoading = true // Mark as loading for this step
                    // trySend(currentLogData.copy()) // Optional: emit loading state before executing step actions

                    // Execute step actions (ADB, AI call)
                    val screenshotName = "screenshot_${System.currentTimeMillis()}_step${currentStepIndex + 1}"
                    val screenshotPath = ADBUtils.captureAndGetScreenshotPath(deviceId, screenshotName)
                    ADBUtils.runAdbCommand("shell uiautomator dump /sdcard/window_dump.xml", deviceId)
                    val screenContext = ADBUtils.runAdbCommand("shell cat /sdcard/window_dump.xml", deviceId)

                    // Prepare context for AI (using actual TestStep objects might be better)
                    val previousStepsContext = currentLogData.steps.map { it.action ?: "" }

                    val aiResponse = makeAiCall(
                        previousResponseInternal.aiResponse,
                        testCaseObjectForAI,
                        getCleanedScreenContext(screenContext),
                        previousStepsContext,
                        mcpService.mcpClient
                    )

                    // Update internal state for the *next* loop iteration
                    val currentResponseInternal = AiResponseData(
                        aiResponse = aiResponse, timestamp = System.currentTimeMillis(), screenshotPath = screenshotPath
                    )
                    previousResponseInternal = currentResponseInternal

                    // Create TestStep for logging
                    val newStep = mapToTestStep(currentStepIndex, currentResponseInternal)
                    currentLogData.steps.add(newStep)

                    // Determine state after this step
                    currentLogData.finalStatus = mapFeedbackToFinalStatus(aiResponse.feedback)
                    currentLogData.isLoading =
                        (currentLogData.finalStatus == null) // Still loading if status isn't final

                    // Emit the updated log data including the new step
                    trySend(currentLogData.copy())
                    log.info("Emitted step ${newStep.stepNumber}. FinalStatus: ${currentLogData.finalStatus}, Loading: ${currentLogData.isLoading}\n\n")
                    // delay(500) // Optional delay between steps
                } // End while loop

            } catch (e: CancellationException) {
                log.info("Test execution job cancelled for input: '$textInput'.")
                loopError = e
                // Don't rethrow cancellation, let finally handle it
            } catch (e: Exception) {
                log.error("Exception during test execution loop for input: '$textInput'.", e)
                loopError = e
            } finally {
                log.info("Finishing test execution job for input: '$textInput'.")
                isTestRunning = false

                // Update final state in logData if job is still active (wasn't cancelled externally)
                // And ensure loading is false
                if (isActive) { // Check if the flow/scope is still active
                    currentLogData.isLoading = false
                    if (loopError != null && loopError !is CancellationException) {
                        currentLogData.finalStatus = TestRunningStatus.FAILED
                        currentLogData.errorMessage = loopError.message ?: "Test failed due to exception"
                    } else if (loopError is CancellationException) {
                        // If cancelled internally or externally
                        currentLogData.finalStatus = TestRunningStatus.STOPPED // Indicate stopped state
                        currentLogData.errorMessage = "Test stopped"
                    } else if (currentLogData.finalStatus == null) {
                        currentLogData.finalStatus = TestRunningStatus.FAILED
                        currentLogData.errorMessage = "Test finished without clear Pass/Fail status."
                    }
                    // Emit the very final state
                    trySend(currentLogData.copy())
                    log.info("Sent final log state: ${currentLogData.finalStatus}")
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
    }.flowOn(Dispatchers.IO) // Ensure the flow setup and execution runs on IO dispatcher

    private fun getCleanedScreenContext(screenXml: String): String {
        return gson.toJson(Utils.cleanHierarchyDump(screenXml))
    }

    // --- stopRunningTest Implementation ---
    override suspend fun stopRunningTest() {
        val jobToCancel = currentTestJob
        if (jobToCancel?.isActive == true) {
            log.info("stopRunningTest called, cancelling job.")
            isTestRunning = false // Set flag immediately for external checks
            jobToCancel.cancelAndJoin() // Cancel the job and wait for it to finish
            log.info("Test job cancelled and joined.")
        } else {
            log.info("stopRunningTest called, but no active test job found.")
            isTestRunning = false // Ensure flag is reset anyway
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
    private fun mapToTestStep(stepIndex: Int, responseData: AiResponseData): TestStep {
        // TODO: Adjust mapping based on actual AiResponseData structure
        return TestStep(
            stepNumber = stepIndex + 1, // Steps are 1-based
            screenshotPath = responseData.screenshotPath,
            action = responseData.aiResponse.context, // Or a more specific action string?
            resourceId = responseData.aiResponse.action.resourceId, // Assuming AiAction is nullable or has nullable fields
            bounds = Rectangle(100, 200).toString()
        )
    }

    // Maps internal AI Feedback to final TestRunningStatus for the log
    private fun mapFeedbackToFinalStatus(feedback: AiFeedback?): TestRunningStatus? {
        return when (feedback) {
            AiFeedback.PASS -> TestRunningStatus.PASSED
            AiFeedback.FAIL -> TestRunningStatus.FAILED
            AiFeedback.INTERRUPTED -> TestRunningStatus.STOPPED // Or maybe STOPPED? Define behaviour.
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
        // Example placeholder response based on feedback logic
        delay(1500) // Simulate AI processing time
        return if (actionList.size < 3) { // Simulate 4 steps total
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