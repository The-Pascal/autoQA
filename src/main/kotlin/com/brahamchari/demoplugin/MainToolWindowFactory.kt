package com.brahamchari.demoplugin

import com.brahamchari.demoplugin.di.TestCaseInjector
import com.brahamchari.demoplugin.tabs.TestToolWindowContent
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory

internal class MainToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        println("Create tool window started")

        val testCaseInjector = TestCaseInjector.getTestCaseInjector()
//        val mainTWContentPanel = MainToolWindowContent(toolWindow, testCaseInjector).getContentPanel()
        val testTWContentPanel = TestToolWindowContent(project, project, testCaseInjector)

        val contentTabTest = ContentFactory.getInstance().createContent(testTWContentPanel, "Test", false)
//        val contentTab1 = ContentFactory.getInstance().createContent(mainTWContentPanel, "Current", false)
//        val contentTab2 = ContentFactory.getInstance().createContent(mainTWContentPanel, "Previous", false)
        toolWindow.contentManager.addContent(contentTabTest)
//        toolWindow.contentManager.addContent(contentTab1)
//        toolWindow.contentManager.addContent(contentTab2)
        println("Create tool window finished")
    }
}