package com.brahamchari.demoplugin.presenter

import com.android.ddmlib.IDevice
import com.brahamchari.demoplugin.models.*
import com.brahamchari.demoplugin.repository.MainTestCaseView
import com.brahamchari.demoplugin.repository.TestCaseRepository
import com.brahamchari.demoplugin.utils.PluginNotifier
import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.util.Disposer
import com.intellij.ui.AnimatedIcon
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import javax.swing.Icon
import kotlin.coroutines.CoroutineContext

interface MainTestCasePresenter {

    fun loadPreviousTestCase(): List<TestCase>

    fun runTestCase(inputText: String)
    fun stopRunningTest()

    fun onRefreshDevicesClicked()
    fun onDeviceSelected(device: IDevice?)
}

class MainTestCasePresenterImpl(
    private val view: MainTestCaseView,
    private val testCaseRepository: TestCaseRepository,
    parentDisposable: Disposable
) : MainTestCasePresenter, CoroutineScope, Disposable {

    private val log = Logger.getInstance(MainTestCasePresenterImpl::class.java) // Use Logger

    // State managed by presenter
    private var selectedDeviceSerial: String? = null
    private var currentDevices: List<IDevice> = emptyList()
    private var isAdbHealthy = true // Track if the ADB flow is operating normally

    private val presenterJob = SupervisorJob()
    override val coroutineContext: CoroutineContext
        get() = presenterJob + Dispatchers.EDT

    // Keep track of the currently active test execution Job
    private var currentTestJob: Job? = null

    init {
        observeAdbDevices()

        Disposer.register(parentDisposable, this)
    }

    override fun loadPreviousTestCase(): List<TestCase> {
        TODO("Not yet implemented")
    }

    override fun runTestCase(inputText: String) {
        // Cancel any previous test run first
        stopRunningTestInternal("Starting new test case")

        val targetDeviceId = selectedDeviceSerial
        if (targetDeviceId == null) {
            log.warn("Run test ignored: No device selected.")
            view.setStatus("Please select a device first.", AllIcons.General.Warning)
            return
        }
        if (inputText.isBlank()) {
            log.warn("Run test ignored: Input text is blank.")
            view.setStatus("Please enter test case text.", AllIcons.General.Warning)
            return
        }

        log.info("Starting test run on device: $targetDeviceId")

        // Update UI: Clear log, show input, add placeholder, set status
        view.clearLogArea()
        view.displayUserInputLog(inputText)
        val logId = view.addBotResponsePlaceholder()

        view.updateTestStatus(TestStatus.RUNNING) // Set Stop button state
        view.setStatus("Processing test case...", AnimatedIcon.Default()) // Show loading

        var testExecutionLog: TestExecutionLog? = null

        // Launch the test execution flow collection
        currentTestJob = launch { // Use presenterScope (this.launch)
            try {
                // Assume repository method takes text & deviceId, returns Flow<TestExecutionLog>
                testCaseRepository.runTestCase(logId, inputText, targetDeviceId)
                    .onStart { log.debug("Test execution flow started for $logId") }
                    .onCompletion { cause ->
                        if (!currentCoroutineContext().isActive) return@onCompletion // Check scope/view validity
                        handleTestCompletion(testExecutionLog, inputText, cause)
                    }
                    .collect { logUpdate ->
                        if (!isActive) return@collect // Check if job was cancelled
                        log.warn("\n\nUpdating view for $logId ${logUpdate.status}\n\n")

                        view.updateBotResponseLog(logId, logUpdate)
                        testExecutionLog = logUpdate.copy()

                        updateTestStatusDisplay(logUpdate, testExecutionLog)
                    }
            } catch (e: CancellationException) {
                log.info("Test execution flow cancelled for $logId: ${e.message}")
                handleTestCompletion(testExecutionLog, inputText, e) // Treat cancellation as failure/stop
            } catch (e: Exception) {
                log.error("Failed to start or collect test execution flow for $logId: ${e.message}", e)
                handleTestCompletion(testExecutionLog, inputText, e) // Treat other errors as failure
            } finally {
                // Ensure state is reset if job ends unexpectedly outside onCompletion
                if(isActive){ // Only reset if not naturally completed/cancelled
                    view.updateTestStatus(TestStatus.STOPPED)
                }
            }
        }
    }

    private fun updateTestStatusDisplay(
        logUpdate: TestExecutionLog,
        testExecutionLog: TestExecutionLog?
    ) {
        val testStepNumber = (logUpdate.executionResult?.testSteps?.size ?: 0) + 1
        val statusText: String
        val statusIcon: Icon?
        when (logUpdate.status) {
            TestStatus.RUNNING -> {
                statusText = "Running Step $testStepNumber..."
                statusIcon = AnimatedIcon.Default()
            }

            TestStatus.PASSED -> {
                statusText = testExecutionLog?.error?.message ?: "Test Passed"
                statusIcon = AllIcons.General.InspectionsOK
            }

            TestStatus.FAILED -> {
                statusText = testExecutionLog?.error?.message ?: "Test Failed"
                statusIcon = AllIcons.General.Error
            }

            TestStatus.STOPPED -> {
                statusText = testExecutionLog?.error?.message ?: "Test Stopped"
                statusIcon = AllIcons.Process.Stop
            }
        }
        view.setStatus(statusText, statusIcon)
    }

    // Handles UI updates when the test flow completes or is cancelled/errored
    private fun handleTestCompletion(testExecutionLog: TestExecutionLog?, inputText: String, cause: Throwable?) {
        view.updateTestStatus(TestStatus.STOPPED) // Reset button to "Send"

        if(testExecutionLog == null) {
            view.setStatus("Something went wrong. Please try again!", AllIcons.General.Error)
            return
        }

        val logId = testExecutionLog.id
        when (cause) {
            null -> {
                // Normal completion - final status should be in the last emitted log data
                log.info("Test execution flow completed successfully for $logId.")
            }
            is CancellationException -> {
                log.info("Test execution explicitly cancelled or stopped for $logId.")
                testExecutionLog.status = TestStatus.STOPPED
                testExecutionLog.error = ExecutionError("Test stopped by user")
                view.updateBotResponseLog(logId, testExecutionLog)
                view.setStatus("Test stopped.", AllIcons.Process.Stop)
            }
            else -> {
                // Failure
                log.error("Test execution flow failed for $logId.", cause)
                testExecutionLog.status = TestStatus.FAILED
                testExecutionLog.error = ExecutionError(cause.message ?: "Unknown Error")
                view.updateBotResponseLog(logId, testExecutionLog)
                view.setStatus("Test Failed: ${cause.message}", AllIcons.General.Error)
            }
        }
        // Clear the job reference once completed/cancelled/failed
        if (currentTestJob?.isCompleted == true || currentTestJob?.isCancelled == true) {
            currentTestJob = null
        }
    }


    override fun stopRunningTest() {
        stopRunningTestInternal("Stop requested by user")
    }

    // Internal stop function to allow specifying reason and avoid re-entry
    private fun stopRunningTestInternal(reason: String) {
        if (currentTestJob?.isActive == true) {
            log.info("Attempting to stop running test ($reason)...")
            view.setStatus("Stopping test...", AllIcons.Process.Stop)
            currentTestJob?.cancel(CancellationException(reason))
            currentTestJob = null

             launch {
                 try {
                     testCaseRepository.stopRunningTest()
                     log.info("Repository stop notified.")
                 } catch (e: Exception) { log.error("Error notifying repository stop", e) }
             }
        } else {
            log.debug("Stop requested but no active test job found.")
            // Ensure UI is in stopped state if somehow out of sync
            view.updateTestStatus(TestStatus.STOPPED)
        }
    }

    override fun dispose() {
        // Cancel the presenter's scope when the UI is disposed
        presenterJob.cancel(CancellationException("Presenter scope cancelled because UI was disposed."))
    }

    private fun observeAdbDevices() {
        println("Observe Adb devices START")
        testCaseRepository.getAdbDevicesFlow()
            .onStart {
                println("ADB device flow collection started.")
                view.showAdbLoading(true)
                view.setStatus("Connecting to ADB...", AllIcons.Actions.Refresh)
                isAdbHealthy = true // Assume healthy until error
            }
            .catch { e -> // Catch terminal errors in the flow (e.g., if flow source fails)
                println(">>> Terminal error in ADB devices flow: ${e.message}")
                isAdbHealthy = false
                currentDevices = emptyList()
                view.showAdbLoading(false)
                view.showAdbDevices(emptyList())
                view.setStatus("ADB Error: ${e.message}", AllIcons.General.Error)
            }
            .onEach { devices ->
                println("Presenter received ${devices.size} devices from flow.")
                isAdbHealthy = true
                currentDevices = devices
                view.showAdbLoading(false)
                view.showAdbDevices(devices)

                // --- Selection Logic ---
                val selectionBefore = selectedDeviceSerial
                val previouslySelectedDevice = devices.find { it.serialNumber == selectionBefore }

                if (previouslySelectedDevice == null && devices.isNotEmpty()) {
                    selectedDeviceSerial = devices.first().serialNumber
                    println("Auto-selecting first device: $selectedDeviceSerial")
                } else if (devices.isEmpty()) {
                    selectedDeviceSerial = null
                }
                // If selection changed due to auto-select, notify view (optional but good practice)
                // if (selectedDeviceSerial != selectionBefore && view?.getSelectedDevice()?.serialNumber != selectedDeviceSerial) {
                //     view?.selectDevice(selectedDeviceSerial) // Need a selectDevice method in View if needed
                // }
                // --- End Selection Logic ---

                updateStatusBasedOnSelection() // Update status bar text/icon
            }
            // Use launchIn to start collecting in the presenter's scope
            .launchIn(this)
    }

    override fun onRefreshDevicesClicked() {
        println("Refresh devices clicked by user.")
        if (isAdbHealthy) {
            view.showAdbLoading(true)
            view.setStatus("Checking ADB devices...", AllIcons.Actions.Refresh)
            // Optional: If you implemented triggerAdbDeviceRefresh in the repo:
            // presenterScope.launch {
            //     try { repository.triggerAdbDeviceRefresh() } catch (e: Exception) { ... }
            // }
            // For now, just show temporary status, flow `onEach` will reset it.
            launch {
                delay(1000) // Give visual feedback for 1 sec
                if (isAdbHealthy) { // Check if view still exists and no error occurred
                    view.showAdbLoading(false)
                    updateStatusBasedOnSelection() // Restore status based on current state
                }
            }
        } else {
            view.setStatus("ADB connection error, cannot refresh.", AllIcons.General.Error)
        }
    }

    override fun onDeviceSelected(device: IDevice?) {
        // Called by the View when the user changes the ComboBox selection
        val newSerial = device?.serialNumber
        // Update internal state only if selection actually changed
        if (selectedDeviceSerial != newSerial) {
            selectedDeviceSerial = newSerial
            println("User selected device: ${selectedDeviceSerial ?: "null"}")
            updateStatusBasedOnSelection()
        }
    }

    private fun updateStatusBasedOnSelection() {
        if (!isAdbHealthy) {
            view.setStatus("ADB Error state", AllIcons.General.Error)
            return
        }

        // Determine the display name for the selected device
        val selectedDeviceName = selectedDeviceSerial?.let { serial ->
            currentDevices.find { it.serialNumber == serial }?.let { device ->
                if (device.isEmulator) device.avdName ?: serial else device.name ?: serial
            } ?: serial // Fallback to serial if device not found (shouldn't happen often)
        }

        // Construct status text
        val statusText = when {
            currentDevices.isEmpty() -> "No devices connected."
            selectedDeviceName != null -> "Selected: $selectedDeviceName"
            else -> "${currentDevices.size} device(s) found. Select one." // Nothing selected yet
        }
        // Choose icon based on state
        val icon = if (currentDevices.isNotEmpty()) AllIcons.General.InspectionsOK else AllIcons.General.Warning

        view.setStatus(statusText, icon)
    }

}