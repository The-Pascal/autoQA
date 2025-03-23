package com.brahamchari.demoplugin.repository

import com.brahamchari.demoplugin.models.AiResponse
import com.brahamchari.demoplugin.models.AiResponseData
import com.brahamchari.demoplugin.models.TestRunningStatus

interface MainTestCaseView {

    fun appendTestStep(actionList: List<AiResponseData>)

    fun updateTestStatus(testRunningStatus: TestRunningStatus)

    fun updateAdbDevices(adbDevices: List<String>)
}