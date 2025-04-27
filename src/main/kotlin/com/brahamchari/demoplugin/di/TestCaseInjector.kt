package com.brahamchari.demoplugin.di

import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.brahamchari.demoplugin.MyProjectService
import com.brahamchari.demoplugin.client.AndroidMCPClient
import com.brahamchari.demoplugin.client.MCPClient
import com.brahamchari.demoplugin.models.SettingsState
import com.brahamchari.demoplugin.presenter.MainTestCasePresenter
import com.brahamchari.demoplugin.presenter.MainTestCasePresenterImpl
import com.brahamchari.demoplugin.repository.MainTestCaseView
import com.brahamchari.demoplugin.repository.TestCaseRepository
import com.brahamchari.demoplugin.repository.TestCaseRepositoryImpl
import com.brahamchari.demoplugin.services.McpService
import com.brahamchari.demoplugin.services.SettingService
import com.google.genai.Client
import com.google.gson.Gson
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project

interface TestCaseInjector {

    val gson: Gson

    val androidMCPClient: MCPClient

    fun getMCPService(project: Project): McpService

    fun getGeminiClient(geminiApiKey: String): Client

    fun getSettingsState(project: Project): SettingsState

    fun getTestCaseRepository(
        projectService: MyProjectService,
        geminiApiKey: String,
        project: Project,
        disposable: Disposable
    ): TestCaseRepository

    companion object {

        private val lock = Any()

        @Volatile
        private var testCaseInjector: TestCaseInjector? = null

        fun getTestCaseInjector(): TestCaseInjector = testCaseInjector ?: synchronized(lock) {
            if (testCaseInjector != null) testCaseInjector
            testCaseInjector = TestCaseInjectorImpl()
            testCaseInjector!!
        }
    }

    fun getTestCasePresenter(view: MainTestCaseView, project: Project, disposable: Disposable): MainTestCasePresenter
}

class TestCaseInjectorImpl : TestCaseInjector {

    private val lockRepo = Any()
    private val lockGemini = Any()
    private var testCaseRepository: TestCaseRepository? = null
    private var geminiClient: Client? = null

    private val anthropicApiKey = ""

    private val anthropicClient by lazy {
        AnthropicOkHttpClient.builder()
            .apiKey(anthropicApiKey)
            .build()
    }

    override val gson: Gson by lazy {
        Gson()
    }

    override val androidMCPClient: MCPClient by lazy {
        AndroidMCPClient(anthropicClient = anthropicClient)
    }

    override fun getMCPService(project: Project): McpService {
        return McpService.getInstance(project)
    }

    override fun getGeminiClient(geminiApiKey: String) =
        geminiClient ?: synchronized(lockGemini) {
            if (geminiClient != null) geminiClient
            println("Gemini api key - $geminiApiKey")
            geminiClient = Client.builder().apiKey(geminiApiKey).build()
            geminiClient!!
        }

    override fun getSettingsState(project: Project): SettingsState {
        return SettingService.getInstance(project).state
    }

    override fun getTestCaseRepository(
        projectService: MyProjectService,
        geminiApiKey: String,
        project: Project,
        disposable: Disposable
    ): TestCaseRepository =
        testCaseRepository ?: synchronized(lockRepo) {
            if (testCaseRepository != null) testCaseRepository
            testCaseRepository = TestCaseRepositoryImpl(project, disposable)
            testCaseRepository!!
        }

    override fun getTestCasePresenter(
        view: MainTestCaseView,
        project: Project,
        disposable: Disposable
    ): MainTestCasePresenter {
        val projectService = project.service<MyProjectService>()
        val testCaseRepository =
            getTestCaseRepository(projectService, getSettingsState(project).apiKey, project, disposable)
        return MainTestCasePresenterImpl(view, testCaseRepository, disposable)
    }
}
