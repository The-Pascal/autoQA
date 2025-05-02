package com.brahamchari.demoplugin.utils

/**
 * Singleton object responsible for generating prompts for the AI test agent.
 */
object PromptGenerator {

    private val systemPromptText = """
        You are an expert AI assistant specializing in step-by-step Android UI test automation. Your primary goal is to analyze the provided UI state and test case information to determine the single next logical action required to validate the test case.

        **Core Rules:**
        - Base your decisions STRICTLY on the provided 'Current Screen UI Context', 'Last Action Performed', 'Previous Steps Summary', and 'Test Case Goal'.
        - Determine test outcome (PASS/FAIL) ONLY based on verifiable evidence within the 'Current Screen UI Context'. Do not assume outcomes.
        - Ensure actions interact correctly with UI elements (use 'resourceId' if possible).

        **Decision Process & Tool Use:**
        1. Analyze the current context and history against the test case goal.
        2. Determine if the next required UI interaction (e.g., click, type) can be confidently identified AND if the test case outcome (PASS/FAIL/CONTINUE) can be determined based *ONLY* on the current information provided.
        3. **If YES, and no further information is needed:** Respond *only* with the Action JSON (see format below). Set 'feedback' to PASS or FAIL only if the outcome is verifiably confirmed by the current UI Context. Otherwise, set 'feedback' to CONTINUE.
        4. **If NO (e.g., you need to check an element's specific state before interacting, verify text not visible in the provided context, get device properties, or perform a non-UI check):** Request the necessary information or action by calling the appropriate available tool(s). Respond with the ACTION JSON (see format below) AND required 'tool_use' request.

        **Output Format Constraint:**
        - If test is verified and no further tool call is required then ONLY respond ACTION JSON (see format below) with feedback as PASS/FAIL according to the test status.
        - If a tool call is required, then respond BOTH with ACTION JSON (see format below) about why the tool is required AND the appropriate 'tool_use' content block(s).
        - Do NOT include ANY other text, explanations, apologies, or markdown formatting like ```json ``` around your JSON output or tool requests.

        **Required Action JSON Output Format (along with the TOOL use also):**
        {
            "context": "string - Concise explanation (<50 words) of the intended action and its rationale.",
            "feedback": "string - MUST be one of: PASS, FAIL, CONTINUE.",
            "resourceId": "string | null - The resource-id of the primary UI element for the action, if applicable."
        }
        """.trimIndent()

    /**
     * Returns the static system prompt defining the AI's role, rules, and output format.
     */
    fun getSystemPrompt(): String {
        return systemPromptText
    }

    /**
     * Generates the user prompt for a specific turn, providing the dynamic context.
     *
     * @param testCase The goal or description of the test case being executed.
     * @param screenContext The current UI hierarchy or relevant screen elements (e.g., as JSON string).
     * @param lastAction The JSON representation (or description) of the last action performed by the AI/automation.
     * @param listOfPreviousSteps A summary or list (e.g., as JSON string) of the actions taken so far in this test case.
     * @return A formatted string containing the user prompt for the current turn.
     */
    fun getUserPrompt(
        testCase: String,
        screenContext: String,
        lastAction: String,
        listOfPreviousSteps: String
    ): String {
        // Use trimIndent for clean formatting and inject variables using $
        return """
            Test Case Goal:
            $testCase

            Current Screen UI Context:
            ```json
            $screenContext
            ```

            Last Action Performed:
            ```json
            $lastAction
            ```

            Summary of Previous Steps:
            ```json
            $listOfPreviousSteps
            ```

            Determine the next best *action JSON* OR *request necessary tool calls* OR *both* based on the rules and context provided. Respond ONLY with the specifications provided.
            """.trimIndent()
    }
}

// --- Example Usage ---
// val systemPrompt = PromptGenerator.getSystemPrompt()
// val userPrompt = PromptGenerator.getUserPrompt(
//     testCase = "Verify user can log in with valid credentials.",
//     screenContext = "{ \"elements\": [...] }", // Your actual screen context JSON
//     lastAction = "{ \"context\": \"Entered username\", \"feedback\": \"CONTINUE\", \"resourceId\": \"id/username_field\" }", // Your last action JSON
//     listOfPreviousSteps = "[ { \"context\": \"Entered username...\" } ]" // Your previous steps JSON/summary
// )

// println("--- System Prompt ---")
// println(systemPrompt)
// println("\n--- User Prompt ---")
// println(userPrompt)