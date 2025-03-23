package com.brahamchari.demoplugin

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import kotlinx.coroutines.*

@Service(Service.Level.PROJECT)
class MyProjectService(
        private val project: Project,
        private val cs: CoroutineScope
) {
    fun launchOnScope(dispatcher: CoroutineDispatcher, block: suspend CoroutineScope.() -> Unit): Job = cs.launch(dispatcher) {
        block()
    }

    fun scheduleSomething() {
        cs.launch {
            delay(2000)
            println("Finally scheduled!!")
        }
    }
}
