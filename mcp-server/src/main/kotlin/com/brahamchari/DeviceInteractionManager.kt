package com.brahamchari

import com.brahamchari.models.DeviceInfo

interface DeviceInteractionManager {
    fun setDeviceId(deviceId: String)

    fun getScreenContext(): String

    fun tapOnScreen(x: Long, y: Long)

    fun swipeOnScreen(startX: Long, startY: Long, endX: Long, endY: Long, duration: Long)

    fun inputText(input: String)

    fun launchApp(packageName: String)

    fun closeApp(packageName: String)

    fun pressBackButton()

    fun listConnectedDevices(): List<DeviceInfo>

    fun executeCommand(command: String)
}