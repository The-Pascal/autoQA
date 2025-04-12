package com.brahamchari.demoplugin.presenter

import com.brahamchari.demoplugin.MyProjectService
import com.brahamchari.demoplugin.models.TestCase
import com.brahamchari.demoplugin.models.TestRunningStatus
import com.brahamchari.demoplugin.repository.MainTestCaseView
import com.brahamchari.demoplugin.repository.TestCaseRepository
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import kotlin.coroutines.CoroutineContext

interface MainTestCasePresenter {

    fun loadPreviousTestCase(): List<TestCase>

    fun runTestCase(testCase: TestCase)
    fun stopRunningTest()
    fun setSelectedDevice(selectedDevice: String?)

    fun getAllAdbDevices()
}

class MainTestCasePresenterImpl(
        private val view: MainTestCaseView,
        private val testCaseRepository: TestCaseRepository,
        private val myProjectService: MyProjectService,
        private val project: Project,
    parentDisposable: Disposable
): MainTestCasePresenter, CoroutineScope, Disposable {

    private var selectedDeviceId: String? = null

    // first is deviceId & second is deviceModel
    private var allAdbDevices: List<Pair<String, String>>? = null


    private val presenterJob = SupervisorJob()
    override val coroutineContext: CoroutineContext
        get() = presenterJob + Dispatchers.EDT

    init {
        Disposer.register(parentDisposable, this)
        println("MainTestCasePresenter created for project -${project.name}")
    }

    override fun loadPreviousTestCase(): List<TestCase> {
        TODO("Not yet implemented")
    }

    override fun runTestCase(testCase: TestCase) {

        view.updateTestStatus(TestRunningStatus.RUNNING)

        if(selectedDeviceId == null) {
            println("No adb device selected")
            // TODO: add notification here
            return
        }

        myProjectService.launchOnScope(Dispatchers.IO) {
            testCaseRepository.runTestCase(testCase, selectedDeviceId!!).collectLatest { testCaseRun ->
                withContext(Dispatchers.EDT) {
                    view.appendTestStep(testCaseRun.aiResponses)

                    if(testCaseRun.runningStatus == TestRunningStatus.STOPPED) {
                        view.updateTestStatus(TestRunningStatus.STOPPED)
                    }
                }
            }
        }
    }

    override fun stopRunningTest() {
        myProjectService.launchOnScope(Dispatchers.IO) {
            testCaseRepository.stopRunningTest()
        }
    }

    override fun setSelectedDevice(selectedDevice: String?) {
        selectedDevice?.let {
            this.selectedDeviceId = allAdbDevices?.firstOrNull {
                it.second == selectedDevice
            }?.first
        }
    }

    override fun getAllAdbDevices() {
        myProjectService.launchOnScope(Dispatchers.IO) {
            val allDevices = testCaseRepository.getAdbDevices()
            allAdbDevices = allDevices
            withContext(Dispatchers.EDT) {
                view.updateAdbDevices(allDevices.map { it.second })
            }
        }
    }

    override fun dispose() {
        println("MyPluginPresenter disposing scope for project ${project.name}")
        // Cancel the presenter's scope when the UI is disposed
        presenterJob.cancel(CancellationException("Presenter scope cancelled because UI was disposed."))
    }

}