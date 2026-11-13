package com.llsl.viper4android.utils

import java.io.File
import java.util.concurrent.TimeUnit

object RootShell {
    private const val TAG = "RootShell"
    private const val SU_TIMEOUT_SEC = 10L

    @Volatile
    private var cachedSuPath: String? = null

    @Volatile
    private var suDetected = false

    fun exec(
        command: String,
        timeoutSec: Long = SU_TIMEOUT_SEC,
    ): Process {
        val su = getSuPath()
        val process = Runtime.getRuntime().exec(arrayOf(su, "-c", command))
        if (!process.waitFor(timeoutSec, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            throw RuntimeException("su command timed out after ${timeoutSec}s: $command")
        }
        return process
    }

    fun execAndRead(
        command: String,
        timeoutSec: Long = SU_TIMEOUT_SEC,
    ): String {
        val su = getSuPath()
        val process = Runtime.getRuntime().exec(arrayOf(su, "-c", command))
        val outputFuture = java.util.concurrent.CompletableFuture.supplyAsync {
            process.inputStream.bufferedReader().use { it.readText() }
        }
        val errorDrain = Thread {
            try {
                process.errorStream.bufferedReader().use { it.readText() }
            } catch (_: Exception) {}
        }
        errorDrain.start()

        val completed = process.waitFor(timeoutSec, TimeUnit.SECONDS)
        if (!completed) {
            process.destroyForcibly()
            outputFuture.cancel(true)
            throw RuntimeException("su command timed out after ${timeoutSec}s: $command")
        }
        return try {
            outputFuture.get(1, TimeUnit.SECONDS)
        } catch (_: Exception) {
            ""
        }
    }

    fun getSuPath(): String {
        cachedSuPath?.let { return it }
        synchronized(this) {
            cachedSuPath?.let { return it }
            val path = detectSu()
            cachedSuPath = path
            suDetected = true
            return path
        }
    }

    fun isRootAvailable(): Boolean =
        try {
            getSuPath()
            true
        } catch (_: Exception) {
            false
        }

    private fun detectSu(): String {
        if (testSu("su")) {
            FileLogger.i(TAG, "su found via PATH")
            return "su"
        }

        val candidates =
            arrayOf(
                "/system/bin/su",
                "/system/xbin/su",
                "/system/bin/kp",
                "/sbin/su",
                "/debug_ramdisk/su",
                "/su/bin/su",
                "/data/adb/ksud",
                "/data/adb/ksu/bin/ksud",
                "/data/adb/apd",
                "/sbin/magisk",
                "/debug_ramdisk/magisk",
            )
        for (path in candidates) {
            if (File(path).exists() && testSu(path)) {
                FileLogger.i(TAG, "su found at $path")
                return path
            }
        }

        FileLogger.e(TAG, "No working su binary found")
        throw RuntimeException("Root not available: no working su binary found")
    }

    private fun testSu(path: String): Boolean {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf(path, "-c", "id"))
            val outputFuture = java.util.concurrent.CompletableFuture.supplyAsync {
                process.inputStream.bufferedReader().use { it.readText() }
            }
            val completed = process.waitFor(5, TimeUnit.SECONDS)
            if (!completed) {
                process.destroyForcibly()
                return false
            }
            if (process.exitValue() != 0) return false
            val output = outputFuture.get(1, TimeUnit.SECONDS)
            output.contains("uid=0")
        } catch (_: Exception) {
            false
        }
    }
}
