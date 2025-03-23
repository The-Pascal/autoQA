package com.brahamchari.android

import com.brahamchari.DeviceInteractionManager
import com.brahamchari.models.DeviceInfo

class AndroidInteractionManagerImpl(private val adbPath: String) : DeviceInteractionManager {

    @Volatile
    private var deviceId: String? = null

    override fun setDeviceId(deviceId: String) {
        this.deviceId = deviceId
    }

    override fun getScreenContext(): String {
        return runAdbCommand("$adbPath shell uiautomator dump && $adbPath shell cat /sdcard/window_dump.xml")
                ?: "Failed to retrieve screen context"
    }

    override fun tapOnScreen(x: Long, y: Long) {
        runAdbCommand("$adbPath shell input tap $x $y")
                ?: throw RuntimeException("Failed to tap on screen at ($x, $y)")
    }

    override fun swipeOnScreen(startX: Long, startY: Long, endX: Long, endY: Long, duration: Long) {
        runAdbCommand("$adbPath shell input swipe $startX $startY $endX $endY $duration")
                ?: throw RuntimeException("Failed to swipe on screen from ($startX, $startY) to ($endX, $endY) in $duration ms")
    }

    override fun inputText(input: String) {
        val escapedInput = input.replace(" ", "%s") // Handle spaces in input
        runAdbCommand("$adbPath shell input text \"$escapedInput\"")
                ?: throw RuntimeException("Failed to input text: $input")
    }

    override fun launchApp(packageName: String) {
        runAdbCommand("$adbPath shell monkey -p $packageName -c android.intent.category.LAUNCHER 1")
                ?: throw RuntimeException("Failed to launch app: $packageName")
    }

    override fun closeApp(packageName: String) {
        runAdbCommand("$adbPath shell am force-stop $packageName")
                ?: throw RuntimeException("Failed to close app: $packageName")
    }

    override fun pressBackButton() {
        runAdbCommand("$adbPath shell input keyevent KEYCODE_BACK")
                ?: throw RuntimeException("Failed to press back button")
    }

    override fun listConnectedDevices(): List<DeviceInfo> {
        val output = runAdbCommand("$adbPath devices -l") ?: return emptyList()
        return output.lines()
                .drop(1) // Skip the header line
                .filter { it.isNotBlank() }
                .mapNotNull { parseDeviceInfo(it) }
    }

    override fun wait(timeInMillis: Long) {
        TODO("No need to implement for Android")
    }

    override fun executeCommand(command: String) {
        if (command.isBlank()) throw IllegalArgumentException("Command should not be empty")
        runAdbCommand("$adbPath shell $command")
                ?: throw RuntimeException("Failed to execute command: $command")
    }

    private fun runAdbCommand(command: String): String? {
        return try {
            val args = command.split(" ").toMutableList()

            if(args.first() == "adb") { args.removeFirst() }

            deviceId?.let {
                // Prepend device ID to the command
                args.add(0, "-s")
                args.add(1, it)
            }

            val process = ProcessBuilder(adbPath, *args.toTypedArray())
                    .redirectErrorStream(true)
                    .start()

            process.inputStream.bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun parseDeviceInfo(deviceLine: String): DeviceInfo? {
        val parts = deviceLine.split("\\s+".toRegex())
        if (parts.size < 2) return null

        val deviceId = parts[0]
        val model = parts.find { it.startsWith("model:") }?.split(":")?.get(1) ?: "Unknown"

        return DeviceInfo(deviceId, model)
    }
}
