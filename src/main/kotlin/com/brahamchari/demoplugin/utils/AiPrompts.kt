package com.brahamchari.demoplugin.utils

object AiPrompts {

    fun getPromptForNextAiAction(screenContext: String, lastAction: String, listOfPreviousSteps: String, testCase: String): String {
        return """
            You are an AI agent assisting in automated Android UI testing. 
            Given the CURRENT UI CONTEXT, the LAST ACTION performed, the LIST of ALL PREVIOUS STEPS performed for this testcase AND and the CURRENT testcase, generate the NEXT APPROPRIATE ACTION in the structured JSON format of **AiAction**.
            
            Ensure that:
            - You make decisions that align with achieving the goal of the test case and to verify if the test case PASS or FAILS.
            - Stop the test only when you have verified if the testcase has PASSED or FAILED, depending on UI context.
            - Do not consider a testcase to be PASSED or FAILED until you have VERIFIED using UI context of the screen.
            - Actions are taken step-by-step, correctly interacting with UI elements.
            - Ensure the action aligns with the test case intent.
            - If an action requires a delay, set delayAfter accordingly.
            - Provide resourceId or contentDescription if applicable.
            
            **Screen UI Context:**
            $screenContext
            
            **Last Action:**
            $lastAction
            
            **List of all Previous Actions for current testcase("$testCase")**
            $listOfPreviousSteps
            
            **Test Case to Validate:**
            $testCase
            
            Based on the above, determine the next best action and return the output strictly in the following JSON format:
            {
                "context": "Short explanation of what this action is trying to achieve",
                "feedback": "PASS | FAIL | CONTINUE",
                "resourceId": "<string | null>"
            }
            
            Ensure that your response strictly adheres to this format with valid values.
        """.trimIndent()
    }
}