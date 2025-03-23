package com.brahamchari.demoplugin

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.service

class DemoAction: AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val projectService = e.project?.service<MyProjectService>()
        projectService?.scheduleSomething()
    }
}