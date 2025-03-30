package com.brahamchari.demoplugin.utils

import com.intellij.openapi.application.ApplicationManager
import java.io.File
import java.io.FileOutputStream
import kotlin.jvm.Throws

object ADBUtils {

    private val screenshotSaveFolderPath: String by lazy {
        val configDir = File(System.getProperty("user.home"), ".config/Google/AndroidStudio/plugins/testAi/")
        if (!configDir.exists()) configDir.mkdirs()
        println("Screenshot save folder path - ${configDir.absolutePath}")
        configDir.absolutePath
    }

    fun getAdbDevices(): List<Pair<String, String>> {
        return try {
            val output = runAdbCommand("devices -l")

            // Parse device list output
            output.lines()
                    .drop(1) // Skip header
                    .filter { it.contains("device ") } // Ensure it's an actual device
                    .mapNotNull { parseAdbDeviceLine(it) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    @Throws(RuntimeException::class)
    fun runAdbCommand(command: String, deviceId: String? = null): String {
        val adbPath = getAdbPath() // Function to get ADB path dynamically
        if(adbPath == null) {
            println("ADB Path not found")
            // TODO: Add notification here
        }
        println("Adb path - $adbPath")
        return try {
            // Split command into arguments
            val args = command.split(" ").toMutableList()

            if(args.first() == "adb") {
                args.removeFirst()
            }

            deviceId?.let {
                // Prepend device ID to the command
                args.add(0, "-s")
                args.add(1, deviceId)
            }

            println("adb path - $adbPath args - $args")

            val process = ProcessBuilder(adbPath, *args.toTypedArray())
                    .redirectErrorStream(true)
                    .start()

            process.inputStream.bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            println("Error running ADB command on device $deviceId: $e")
            e.printStackTrace()
            throw RuntimeException("Error running ADB command on device $deviceId: ${e.message}")
        }
    }

    fun captureAndGetScreenshotPath(deviceId: String, name: String): String? {
        val savePath = "$screenshotSaveFolderPath/$name.png"
        println("Screenshot path - $savePath")

        return try {
            // Run adb command and get raw bytes instead of converting to a string
            val process = ProcessBuilder("adb", "-s", deviceId, "exec-out", "screencap", "-p")
                    .redirectErrorStream(true)
                    .start()

            val outputFile = File(savePath)
            if (outputFile.exists()) outputFile.delete()

            // Write the raw bytes directly to file
            process.inputStream.use { input ->
                FileOutputStream(outputFile).use { output ->
                    input.copyTo(output)
                }
            }

            process.waitFor() // Ensure process completes

            savePath
        } catch (e: Exception) {
            println("Error capturing screenshot: ${e.message}")
            null
        }
    }

    private fun parseAdbDeviceLine(line: String): Pair<String, String>? {
        val parts = line.split("\\s+".toRegex()) // Split by whitespace
        if (parts.isNotEmpty()) {
            val deviceId = parts[0] // First part is device ID
            val model = parts.find { it.startsWith("model:") }?.removePrefix("model:") ?: "Unknown"
            return deviceId to model
        }
        return null
    }

    fun getAdbPath(): String? {
        val sdkPath = ApplicationManager.getApplication()
                .getService(com.android.tools.idea.sdk.IdeSdks::class.java)
                ?.androidSdkPath?.absolutePath

        return sdkPath?.let {
            val adbFile = File(it, "platform-tools${File.separator}adb.exe")
            if (adbFile.exists()) adbFile.absolutePath else null
        } ?: run {
            null
        }
    }
}