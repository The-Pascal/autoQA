package com.brahamchari.demoplugin

import com.brahamchari.demoplugin.di.TestCaseInjector
import com.brahamchari.demoplugin.presenter.MainTestCasePresenter
import com.brahamchari.demoplugin.tabs.SavedTestsPanel
import com.brahamchari.demoplugin.tabs.TestToolWindowContent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.content.ContentManagerEvent
import com.intellij.ui.content.ContentManagerListener

const val MY_TOOL_WINDOW_ID = "AutoQA"

internal class MainToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        println("Create tool window started")

        val testCaseInjector = TestCaseInjector.getTestCaseInjector()

        // Tab 1
        val testTWContentPanel = TestToolWindowContent(project, toolWindow.disposable, testCaseInjector)
        val contentTabTest = ContentFactory.getInstance().createContent(testTWContentPanel, "Main", false)
        toolWindow.contentManager.addContent(contentTabTest)

        // Tab 2
        val savedTestsPanel = SavedTestsPanel(
            project,
            toolWindow.disposable,
            testTWContentPanel.presenter,
            testTWContentPanel
        )
        val contentTabSavedTests = ContentFactory.getInstance().createContent(savedTestsPanel, "Saved", false)
        toolWindow.contentManager.addContent(contentTabSavedTests)

        // Add listener to refresh SavedTestsPanel when its tab is selected
        toolWindow.contentManager.addContentManagerListener(object : ContentManagerListener {
            override fun selectionChanged(event: ContentManagerEvent) {
                println("ContentManager selection changed. Newly selected content: ${event.content.displayName}, component: ${event.content.component?.javaClass?.simpleName}, operation: ${event.operation}")
                // We want to refresh when the "Saved Tests" tab *becomes* selected.
                if (event.content.component == savedTestsPanel) {
                    // The 'add' operation in ContentManagerEvent often means it was just made the selected content.
                    // This listener will fire every time the selection changes to this tab.
                    println("'Saved Tests' tab selected via ContentManagerListener. Requesting data refresh.")
                    savedTestsPanel.onTabSelected()
                }
            }
        })

        // Handle the case where the "Saved Tests" tab might be the default selected tab
        // when the tool window is first opened. The listener might not fire for this initial state,
        // or it might fire too early.
        //invokeLater ensures the UI is more likely to be initialized.
        ApplicationManager.getApplication().invokeLater {
            if (toolWindow.contentManager.selectedContent?.component == savedTestsPanel && toolWindow.isVisible) {
                println("'Saved Tests' tab is initially selected and tool window is visible. Triggering initial data load if not already loading.")
                savedTestsPanel.onTabSelected()
            }
        }

        println("Create tool window finished")
    }
}
