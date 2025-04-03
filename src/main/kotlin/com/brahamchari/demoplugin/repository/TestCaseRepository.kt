package com.brahamchari.demoplugin.repository

import com.brahamchari.MCPServer
import com.brahamchari.android.AndroidMCPServerImpl
import com.brahamchari.demoplugin.MyProjectService
import com.brahamchari.demoplugin.di.TestCaseInjector
import com.brahamchari.demoplugin.models.*
import com.brahamchari.demoplugin.utils.ADBUtils
import com.brahamchari.demoplugin.utils.AiPrompts
import com.brahamchari.demoplugin.utils.Utils
import com.google.genai.Client
import com.google.gson.Gson
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow

interface TestCaseRepository {
    var isTestRunning: Boolean

    val androidMCPServer: MCPServer

    fun runTestCase(testCase: TestCase, deviceId: String): Flow<TestCaseRun>

    fun getAllTestCase(): List<TestCase>

    fun getAdbDevices(): List<Pair<String, String>>

    suspend fun stopRunningTest()
}

class TestCaseRepositoryImpl(
        private val geminiClient: Client,
        private val myProjectService: MyProjectService
): TestCaseRepository {

    @Volatile
    override var isTestRunning: Boolean = false

    private var currentTestJob: Job? = null

    private val gson: Gson by lazy {
        TestCaseInjector.getTestCaseInjector().gson
    }

    override val androidMCPServer: MCPServer by lazy {
        AndroidMCPServerImpl(adbSystemPath ?: "adb")
    }

    val adbSystemPath: String? by lazy {
        ADBUtils.getAdbPath()
    }

    override fun runTestCase(testCase: TestCase, deviceId: String): Flow<TestCaseRun> = channelFlow {
        androidMCPServer.startServer()
        if (isTestRunning) throw Exception("Test is already running")
        isTestRunning = true
        println("Run Test case started")

        var testCaseMessage: String? = null
        var testCaseRun: TestCaseRun
        val actionList = mutableListOf<AiResponseData>()
        var previousResponse = getInitialAction()

        currentTestJob = myProjectService.launchOnScope(Dispatchers.IO) {
            try {
                while (previousResponse.aiResponse.feedback == AiFeedback.CONTINUE) {
                    println("Step running")
                    val screenshotName = "screenshot_${System.currentTimeMillis()}"
                    val screenshotPath = async { ADBUtils.captureAndGetScreenshotPath(deviceId, screenshotName) }
                    ADBUtils.runAdbCommand("shell uiautomator dump /sdcard/window_dump.xml", deviceId)
                    val screenContext = ADBUtils.runAdbCommand("shell cat /sdcard/window_dump.xml", deviceId)

                    val aiResponse = makeAiCall(
                            previousResponse.aiResponse,
                            testCase,
                            getCleanedScreenContext(screenContext),
                            actionList
                    )
                    val currentResponse = AiResponseData(
                            aiResponse = aiResponse,
                            timestamp = System.currentTimeMillis(),
                            screenshotPath = screenshotPath.await()
                    )
                    previousResponse = currentResponse
                    actionList.add(previousResponse)

                    testCaseRun = TestCaseRun(
                            aiResponses = actionList,
                            runningStatus = getTCRunningStatus(currentResponse.aiResponse.feedback)
                    )
                    send(testCaseRun) // Emit the updated data

                    println("\nCurrent response - $currentResponse")
                    if (currentResponse.aiResponse.feedback == AiFeedback.CONTINUE) {
                        println("AI feedback continue")
                        currentResponse.aiResponse.action.adbCommand?.let {
                            println("Run adb command - $it")
                            ADBUtils.runAdbCommand(it)
                        }
                        currentResponse.aiResponse.action.delayAfter?.let { delay(it) }
                    } else {
                        break
                    }

                    delay(1000)
                }
            } catch (e: RuntimeException) {
                println("Exception occurred - ${e.localizedMessage}")
                testCaseMessage = e.localizedMessage
            } finally {
                println("Run Test case finished")
                val testFinalStatus = getTCFinalStatus(previousResponse.aiResponse.feedback)
                testCaseRun = TestCaseRun(
                        aiResponses = actionList,
                        runningStatus = TestRunningStatus.STOPPED,
                        testFinalStatus = testFinalStatus,
                        message = testCaseMessage ?: if (testFinalStatus == TestFinalStatus.INTERRUPTED)
                            "Test case is interrupted" else null
                )
                send(testCaseRun)
                isTestRunning = false
            }
        }

        // Wait until the flow is closed or canceled, this helps to keep the flow active
        awaitClose {
            // Handle cleanup or cancellation here
            println("Flow is closed")
        }
    }

    private fun getCleanedScreenContext(screenXml: String): String {
        return gson.toJson(Utils.cleanHierarchyDump(screenXml))
    }


    override fun getAllTestCase(): List<TestCase> {
        TODO("Not yet implemented")
    }

    override fun getAdbDevices(): List<Pair<String, String>> = ADBUtils.getAdbDevices()

    override suspend fun stopRunningTest() {
        currentTestJob?.cancelAndJoin()
    }

    private fun getTCRunningStatus(feedback: AiFeedback): TestRunningStatus {
        return if (feedback == AiFeedback.CONTINUE) TestRunningStatus.RUNNING
        else TestRunningStatus.STOPPED
    }

    private fun getTCFinalStatus(feedback: AiFeedback): TestFinalStatus = when (feedback) {
        AiFeedback.PASS -> TestFinalStatus.PASS
        AiFeedback.FAIL -> TestFinalStatus.FAIL
        AiFeedback.INTERRUPTED -> TestFinalStatus.INTERRUPTED
        AiFeedback.CONTINUE -> TestFinalStatus.INTERRUPTED
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

    private fun makeAiCall(previousResponse: AiResponse, testCase: TestCase, screenContext: String, actionList: MutableList<AiResponseData>): AiResponse {
        val allPreviousSteps: List<String> = actionList.map { it.aiResponse.context }
        val prompt = AiPrompts.getPromptForNextAiAction(
                testCase = testCase.name,
                lastAction = gson.toJson(previousResponse),
                screenContext = screenContext,
                listOfPreviousSteps = gson.toJson(allPreviousSteps)
        )
        val response = geminiClient.models.generateContent("gemini-2.0-flash-001", prompt, null)
        val formattedResponse = removeJsonTags(response.text())
        println("Formatted AI Response - $formattedResponse")
        return try {
            gson.fromJson(formattedResponse, AiResponse::class.java)
        } catch (e: Exception) {
            println("Unable to generate action from AI - ${e.message}")
            AiResponse(
                    context = "Unable to generate action from AI. Error - ${e.message}",
                    action = AiAction(type = AiActionType.KillApp),
                    feedback = AiFeedback.INTERRUPTED
            )
        }
    }

    private fun removeJsonTags(input: String): String {
        // First, remove the leading "```json" part and the trailing "```"
        val withoutLeadingJsonTag = input.substringAfter("```json").trim()
        return withoutLeadingJsonTag.substringBefore("```").trim()
    }

}