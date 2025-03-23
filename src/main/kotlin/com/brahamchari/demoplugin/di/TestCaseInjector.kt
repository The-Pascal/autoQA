package com.brahamchari.demoplugin.di

import com.brahamchari.demoplugin.MyProjectService
import com.brahamchari.demoplugin.models.SettingsState
import com.brahamchari.demoplugin.presenter.MainTestCasePresenter
import com.brahamchari.demoplugin.presenter.MainTestCasePresenterImpl
import com.brahamchari.demoplugin.repository.MainTestCaseView
import com.brahamchari.demoplugin.repository.TestCaseRepository
import com.brahamchari.demoplugin.repository.TestCaseRepositoryImpl
import com.brahamchari.demoplugin.services.SettingService
import com.google.genai.Client
import com.google.gson.Gson
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project

interface TestCaseInjector {

    val gson: Gson

    fun getGeminiClient(geminiApiKey: String): Client

    fun getSettingsState(project: Project): SettingsState

    fun getTestCaseRepository(projectService: MyProjectService, geminiApiKey: String): TestCaseRepository

    fun getTestCasePresenter(
            view: MainTestCaseView,
            project: Project
    ): MainTestCasePresenter

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
}

class TestCaseInjectorImpl : TestCaseInjector {

    private val lockRepo = Any()
    private val lockGemini = Any()
    private var testCaseRepository: TestCaseRepository? = null
    private var geminiClient: Client? = null

    override val gson: Gson by lazy {
        Gson()
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

    override fun getTestCaseRepository(projectService: MyProjectService, geminiApiKey: String): TestCaseRepository =
            testCaseRepository ?: synchronized(lockRepo) {
                if (testCaseRepository != null) testCaseRepository
                testCaseRepository = TestCaseRepositoryImpl(getGeminiClient(geminiApiKey), projectService)
                testCaseRepository!!
            }

    override fun getTestCasePresenter(view: MainTestCaseView, project: Project): MainTestCasePresenter {
        val projectService = project.service<MyProjectService>()
        val testCaseRepository = getTestCaseRepository(projectService, getSettingsState(project).apiKey)
        return MainTestCasePresenterImpl(view, testCaseRepository, projectService)
    }
}
