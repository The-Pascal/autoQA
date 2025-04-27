package com.brahamchari.demoplugin.models

import java.util.*

// Represents who sent a message or part of the log
enum class Sender { USER, BOT }

// Represents one step in the test execution log
// TODO: Replace with your actual data structure
data class TestStep(
    val stepNumber: Int,
    val screenshotPath: String?,
    val action: String?,
    val resourceId: String?,
    val bounds: String?
)

// Represents the entire state of one test execution log cycle
// TODO: Replace with your actual data structure
data class TestExecutionLog(
    val id: String = UUID.randomUUID().toString(), // Unique ID for this execution
    var userInput: String? = null, // The initial user input
    var botIntroduction: String? = null,
    val steps: MutableList<TestStep> = mutableListOf(), // Use persistent list if needed
    var isLoading: Boolean = true, // Start in loading state
    var finalStatus: TestRunningStatus? = null, // PASSED, FAILED
    var errorMessage: String? = null
)