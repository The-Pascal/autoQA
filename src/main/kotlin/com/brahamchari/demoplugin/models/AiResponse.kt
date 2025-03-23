package com.brahamchari.demoplugin.models

import java.awt.Rectangle

data class AiResponse(
        val context: String,
        val action: AiAction,
        val feedback: AiFeedback
)

data class AiAction(
        val type: AiActionType,
        val resourceId: String? = null,
        val contentDescription: String? = null,
        val bounds: Rectangle? = null,
        val inputText: String? = null,
        val scrollDirection: ScrollDirection? = null,
        val isKeyboardAction: Boolean = false,
        val delayAfter: Long? = null, // Delay after action for animations
        val adbCommand: String? = null // ADB command for direct execution
)

enum class AiFeedback {
    PASS,
    FAIL,
    INTERRUPTED,
    CONTINUE
}

enum class ScrollDirection {
    UP, DOWN, LEFT, RIGHT
}

enum class AiActionType {
    TAP,
    AddText,
    Scroll,
    DeviceBackPress,
    KillApp,
    DELAY
}
