package com.brahamchari.demoplugin.repository // Or your actual package

import com.android.ddmlib.IDevice
import com.brahamchari.demoplugin.models.AiResponseData
import com.brahamchari.demoplugin.models.TestExecutionLog
import com.brahamchari.demoplugin.models.TestRunningStatus
import javax.swing.Icon

interface MainTestCaseView {
    // --- Test Case Methods ---
    fun updateTestStatus(testRunningStatus: TestRunningStatus)
    fun getTestCaseInputText(): String

    // --- Log Area Methods ---
    /** Displays the user's input text in the log area. */
    fun displayUserInputLog(text: String)
    /** Adds a placeholder or initial view for a new bot response cycle. Returns the ID assigned. */
    fun addBotResponsePlaceholder(): String
    /** Updates the content of a specific bot response block based on the latest data. */
    fun updateBotResponseLog(logId: String, logData: TestExecutionLog)
    /** Clears the entire chat/log area. */
    fun clearLogArea()


    // --- ADB Device Methods ---
    fun showAdbDevices(devices: List<IDevice>)
    fun showAdbLoading(isLoading: Boolean)
    fun showAdbError(message: String?) // Kept for potential specific errors
    fun getSelectedDevice(): IDevice?

    fun setStatus(text: String, icon: Icon?) // For the bottom status label
}