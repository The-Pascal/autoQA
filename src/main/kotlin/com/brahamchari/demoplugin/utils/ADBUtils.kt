package com.brahamchari.demoplugin.utils

import com.android.ddmlib.AndroidDebugBridge
import com.android.ddmlib.IDevice
import com.android.tools.idea.adb.AdbService
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.jvm.Throws

object ADBUtils {

    @Throws(Exception::class)
    suspend fun fetchAdbDevices(project: Project): List<IDevice> = withContext(Dispatchers.IO) {
        println("Executing fetchAdbDevicesInternal on ${Thread.currentThread().name}")
        val devices: List<IDevice>
        val bridge: AndroidDebugBridge?
        try {
            val adbFuture = AdbService.getInstance().getDebugBridge(project)
            bridge = try {
                adbFuture.get(5, TimeUnit.SECONDS)
            } catch (e: TimeoutException) {
                println("Timeout getting ADB bridge")
                throw RuntimeException("Timeout connecting to ADB.")
            } catch (e: Exception) {
                e.printStackTrace()
                println("Error getting ADB bridge: ${e.message}")
                throw RuntimeException("Cannot get ADB bridge: ${e.message}")
            }

            if (bridge != null && bridge.isConnected) {
                devices = bridge.devices?.toList() ?: emptyList()
                println("Fetched ${devices.size} devices.")
            } else if (bridge == null) {
                println("ADB Bridge instance is null after future.")
                throw RuntimeException("ADB Bridge not available (SDK configured?).")
            } else { // bridge != null but not connected
                println("ADB Bridge is not connected.")
                throw RuntimeException("ADB Bridge is not connected.")
            }
        } catch (e: Exception) {
            // Catch and log, but rethrow standard Exception to signal failure
            val errorMsg = "Failed to fetch ADB devices: ${e.message}"
            e.printStackTrace()
            println(errorMsg)
            throw Exception(errorMsg, e)
        }
        devices
    }

    val screenshotSaveFolderPath: String by lazy {
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