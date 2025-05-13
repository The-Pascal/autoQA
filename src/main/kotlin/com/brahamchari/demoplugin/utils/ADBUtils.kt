package com.brahamchari.demoplugin.utils

import com.android.ddmlib.AndroidDebugBridge
import com.android.ddmlib.IDevice
import com.android.tools.idea.adb.AdbService
import com.android.tools.idea.sdk.IdeSdks
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SystemInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.android.sdk.AndroidSdkUtils
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

    private val configPath = PathManager.getConfigPath()
    val screenshotSaveFolderPath: String by lazy {
        // Use a subdirectory specific to your plugin within the config area
        val pluginConfigDir = File(configPath, "AutoQA/screenshots")
        pluginConfigDir.mkdirs()
        val path = pluginConfigDir.absolutePath
        println("Screenshot save path - $path")
        path
    }

    // TODO: revisit this later
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

        val adbPath = getAdbPath() ?: "adb"

        return try {
            // Run adb command and get raw bytes instead of converting to a string
            val process = ProcessBuilder(adbPath, "-s", deviceId, "exec-out", "screencap", "-p")
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

            val exitCode = process.waitFor() // Wait for the adb command to complete

            if (exitCode == 0) {
                // Check if the file was actually created and has content
                if (outputFile.exists() && outputFile.length() > 0) {
                    println("Screenshot captured successfully: $savePath")
                    savePath
                } else {
                    println("ADB command reported success (exit code 0), but screenshot file is missing or empty at: $savePath for device $deviceId.")
                    outputFile.delete() // Clean up potentially empty file
                    null
                }
            } else {
                println("Error capturing screenshot: ADB command failed with exit code $exitCode for device $deviceId.")
                // Since error stream was redirected, it would have been consumed by copyTo or available in process.inputStream before closing.
                // For more detailed error, one might need to read the stream into a string before copyTo if it's small.
                outputFile.delete() // Clean up potentially partial/corrupt file
                null
            }
        } catch (e: Exception) {
            println("Error capturing screenshot: ${e.message}")
            null
        }
    }

    fun getAdbPath(): String? {
        // 1. Check for system property override first (common in Android tooling)
        val adbPathFromProperty = System.getProperty("android.adb.path")
        if (!adbPathFromProperty.isNullOrBlank()) {
            val adbFileFromProp = File(adbPathFromProperty)
            if (adbFileFromProp.exists() && adbFileFromProp.isFile && adbFileFromProp.canExecute()) {
                // Logger.getInstance("YourClass").info("Using ADB from system property: $adbPathFromProperty")
                return adbFileFromProp.absolutePath
            } else {
                // Logger.getInstance("YourClass").warn("System property 'android.adb.path' is set to '$adbPathFromProperty' but it's not a valid ADB executable.")
            }
        }

        // 2. If no valid override, use the SDK configured in the IDE
        val ideSdks = IdeSdks.getInstance() // Ensure this doesn't return null in your context
        val sdkPathFile: File = ideSdks.androidSdkPath ?: run {
            // Logger.getInstance("YourClass").warn("Android SDK path not configured in IDE.")
            return null
        }

        val adbExecutableName = if (SystemInfo.isWindows) {
            "adb.exe"
        } else {
            "adb" // For macOS and Linux
        }

        val platformToolsDir = File(sdkPathFile, "platform-tools")
        val adbFile = File(platformToolsDir, adbExecutableName)

        val path =  if (adbFile.exists() && adbFile.isFile && adbFile.canExecute()) {
            adbFile.absolutePath
        } else {
            // Logger.getInstance("YourClass").warn("ADB not found in SDK path: ${adbFile.absolutePath}")
            null
        }
        println("ADB path - $path")
        return path
    }
}