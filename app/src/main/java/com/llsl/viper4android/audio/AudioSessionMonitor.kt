package com.llsl.viper4android.audio

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.inputmethod.InputMethodManager
import com.llsl.viper4android.utils.FileLogger
import com.llsl.viper4android.utils.RootShell

class AudioSessionMonitor(
    private val context: Context,
    private val onPlaybackActive: (sessionId: Int, packageName: String) -> Unit,
    private val onSessionClose: (sessionId: Int) -> Unit,
) {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private val activePiids = mutableMapOf<Int, SessionInfo>()
    private var registered = false
    @Volatile
    private var isResolving = false
    @Volatile
    private var hasPendingChanges = false
    private var lastMediaPackage: String = ""

    private val wakeLock: PowerManager.WakeLock? by lazy {
        try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ViPER4Android:AudioSessionMonitor")
        } catch (_: Exception) {
            null
        }
    }

    private val isSystemPrivileged: Boolean by lazy {
        context.checkSelfPermission("android.permission.MODIFY_AUDIO_ROUTING") ==
            PackageManager.PERMISSION_GRANTED
    }

    private data class SessionInfo(
        val sessionId: Int,
        val packageName: String,
    )

    private val playbackCallback =
        object : AudioManager.AudioPlaybackCallback() {
            override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>) {
                handlePlaybackChange()
            }
        }

    fun start() {
        if (registered) return
        FileLogger.i(TAG, "Starting playback monitor")
        audioManager.registerAudioPlaybackCallback(playbackCallback, mainHandler)
        registered = true
        resolveFromDumpsys()
    }

    private val pendingCloses = mutableMapOf<Int, Runnable>()

    fun stop() {
        if (!registered) return
        audioManager.unregisterAudioPlaybackCallback(playbackCallback)
        registered = false
        for (runnable in pendingCloses.values) {
            mainHandler.removeCallbacks(runnable)
        }
        pendingCloses.clear()
        activePiids.clear()
        FileLogger.i(TAG, "Playback monitor stopped")
    }

    private fun cancelSessionClose(sessionId: Int) {
        pendingCloses.remove(sessionId)?.let {
            mainHandler.removeCallbacks(it)
            FileLogger.i(TAG, "Cancelled pending close for session $sessionId")
        }
    }

    private fun scheduleSessionClose(sessionId: Int) {
        cancelSessionClose(sessionId)
        val runnable = Runnable {
            pendingCloses.remove(sessionId)
            val stillActive = activePiids.values.any { it.sessionId == sessionId }
            if (!stillActive) {
                FileLogger.i(TAG, "Executing debounced session close: $sessionId")
                onSessionClose(sessionId)
            }
        }
        pendingCloses[sessionId] = runnable
        mainHandler.postDelayed(runnable, 3000L)
    }

    private fun isNonMediaApp(pkg: String): Boolean {
        if (pkg.isEmpty() || pkg.startsWith("android") || pkg == "com.android.systemui") return true
        if (pkg.contains("launcher") || pkg.endsWith(".home") || pkg.contains("inputmethod")) return true
        try {
            val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            val resolveInfos = context.packageManager.queryIntentActivities(homeIntent, 0)
            if (resolveInfos.any { it.activityInfo?.packageName == pkg }) return true
        } catch (_: Exception) {}
        try {
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            if (imm?.enabledInputMethodList?.any { it.packageName == pkg } == true) return true
        } catch (_: Exception) {}
        return false
    }

    fun getActivePackage(): String {
        val currentMediaPkg =
            activePiids.values.lastOrNull {
                it.packageName.isNotEmpty() && !isNonMediaApp(it.packageName)
            }?.packageName
        if (!currentMediaPkg.isNullOrEmpty()) {
            lastMediaPackage = currentMediaPkg
            return currentMediaPkg
        }
        return lastMediaPackage
    }

    private fun handlePlaybackChange() {
        synchronized(this) {
            if (isResolving) {
                hasPendingChanges = true
                return
            }
            isResolving = true
        }
        Thread {
            try {
                wakeLock?.acquire(10_000L)
                do {
                    hasPendingChanges = false
                    resolveFromDumpsys()
                } while (hasPendingChanges)
            } finally {
                try {
                    if (wakeLock?.isHeld == true) wakeLock?.release()
                } catch (_: Exception) {}
                synchronized(this) {
                    isResolving = false
                }
            }
        }.start()
    }

    private fun resolveFromDumpsys() {
        if (!isSystemPrivileged && !RootShell.isRootAvailable()) {
            FileLogger.w(TAG, "Root not available")
            return
        }

        val discovered =
            if (isSystemPrivileged) {
                queryActivePlayersSys()
            } else {
                queryActivePlayers()
            } ?: return

        mainHandler.post {
            val discoveredPiids = discovered.keys
            val currentPiids = activePiids.keys.toSet()

            val newPiids = discoveredPiids - currentPiids
            val gonePiids = currentPiids - discoveredPiids

            for (piid in newPiids) {
                val info = discovered[piid] ?: continue
                if (info.sessionId <= 0 || isNonMediaApp(info.packageName)) continue
                cancelSessionClose(info.sessionId)
                val alreadyTracked = activePiids.values.any { it.sessionId == info.sessionId }
                activePiids[piid] = info
                lastMediaPackage = info.packageName
                FileLogger.i(
                    TAG,
                    "Active playback piid=$piid session=${info.sessionId} (${info.packageName}) alreadyTracked=$alreadyTracked",
                )
                onPlaybackActive(info.sessionId, info.packageName)
            }

            for (piid in gonePiids) {
                val info = activePiids.remove(piid) ?: continue
                if (info.sessionId > 0 && !isNonMediaApp(info.packageName)) {
                    val stillActive = activePiids.values.any { it.sessionId == info.sessionId }
                    FileLogger.i(
                        TAG,
                        "Piid ended piid=$piid session=${info.sessionId} (${info.packageName}) stillActive=$stillActive",
                    )
                    if (!stillActive) {
                        scheduleSessionClose(info.sessionId)
                    }
                }
            }
        }
    }

    private fun queryActivePlayersSys(): Map<Int, SessionInfo>? =
        try {
            val playerSessions = mutableMapOf<Int, SessionInfo>()
            val configs = audioManager.activePlaybackConfigurations
            val configClass = AudioPlaybackConfiguration::class.java
            // Required when not using system internal sdk
            val isActiveMethod = configClass.getMethod("isActive")
            val getAudioSessionIdMethod = configClass.getMethod("getSessionId")
            val getClientUidMethod = configClass.getMethod("getClientUid")
            val getPlayerInterfaceIdMethod = configClass.getMethod("getPlayerInterfaceId")

            for (config in configs) {
                if (isActiveMethod.invoke(config) as? Boolean != true) continue

                val sid = getAudioSessionIdMethod.invoke(config) as? Int
                if (sid == null || sid <= 0) continue

                try {
                    val getAudioAttributesMethod = configClass.getMethod("getAudioAttributes")
                    val attrs = getAudioAttributesMethod.invoke(config) as? AudioAttributes
                    if (attrs != null && (attrs.usage == AudioAttributes.USAGE_ASSISTANCE_SONIFICATION ||
                                          attrs.usage == AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE ||
                                          attrs.usage == AudioAttributes.USAGE_NOTIFICATION)) {
                        continue
                    }
                } catch (_: Exception) {}

                // Some player use LL API do not have piid, make sure to fake one from session id
                val piid = (getPlayerInterfaceIdMethod.invoke(config) as Int).let { if (it != 0) it else -sid }

                val uid = getClientUidMethod.invoke(config) as? Int
                if (uid == null || uid < 0) continue
                val pkg = context.packageManager.getPackagesForUid(uid)?.firstOrNull() ?: uid.toString()

                playerSessions[piid] = SessionInfo(sid, pkg)
            }
            playerSessions
        } catch (e: Exception) {
            FileLogger.e(TAG, "Framework active playback query failed", e)
            null
        }

    private fun queryActivePlayers(): Map<Int, SessionInfo>? =
        try {
            val text =
                RootShell.execAndRead(
                    "dumpsys audio | grep -E 'AudioPlaybackConfiguration|state:started|new player'",
                    timeoutSec = DUMPSYS_TIMEOUT_SEC,
                )

            val apcLines = text.lineSequence().filter { it.contains("AudioPlaybackConfiguration") }.toList()
            if (apcLines.isNotEmpty()) {
                val result = mutableMapOf<Int, SessionInfo>()
                for (line in apcLines) {
                    val trimmed = line.trim()
                    if (!trimmed.contains("state:started")) continue
                    if (trimmed.contains("SoundPool") ||
                        trimmed.contains("USAGE_ASSISTANCE_SONIFICATION") ||
                        trimmed.contains("USAGE_ASSISTANCE_NAVIGATION_GUIDANCE") ||
                        trimmed.contains("CONTENT_TYPE_SONIFICATION")
                    ) continue

                    val piid = PIID_PATTERN.find(trimmed)?.groupValues?.get(1)?.toIntOrNull() ?: continue
                    val sid = SESSION_PATTERN.find(trimmed)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                    if (sid <= 0) continue

                    val uid = UID_PATTERN.find(trimmed)?.groupValues?.get(1)?.toIntOrNull()
                    var pkg = PACKAGE_PATTERN.find(trimmed)?.groupValues?.get(1) ?: ""
                    if (pkg.isEmpty() && uid != null) {
                        pkg = context.packageManager.getPackagesForUid(uid)?.firstOrNull() ?: ""
                    }
                    result[piid] = SessionInfo(sid, pkg)
                }
                result
            } else {
                val currentText = text.substringBefore("Audio event log:")
                val playerSessions = mutableMapOf<Int, SessionInfo>()
                for (line in currentText.lineSequence()) {
                    val trimmed = line.trim()
                    if (trimmed.contains("SoundPool") ||
                        trimmed.contains("USAGE_ASSISTANCE_SONIFICATION") ||
                        trimmed.contains("USAGE_ASSISTANCE_NAVIGATION_GUIDANCE") ||
                        trimmed.contains("CONTENT_TYPE_SONIFICATION")
                    ) continue
                    val piid =
                        PIID_PATTERN
                            .find(trimmed)
                            ?.groupValues
                            ?.get(1)
                            ?.toIntOrNull() ?: continue
                    val sid =
                        SESSION_PATTERN
                            .find(trimmed)
                            ?.groupValues
                            ?.get(1)
                            ?.toIntOrNull() ?: 0
                    val uid =
                        UID_PATTERN
                            .find(trimmed)
                            ?.groupValues
                            ?.get(1)
                            ?.toIntOrNull()
                    var pkg = PACKAGE_PATTERN.find(trimmed)?.groupValues?.get(1) ?: ""
                    if (pkg.isEmpty() && uid != null) {
                        pkg = context.packageManager.getPackagesForUid(uid)?.firstOrNull() ?: ""
                    }
                    if (sid > 0 && (piid !in playerSessions || pkg.isNotEmpty())) {
                        playerSessions[piid] = SessionInfo(sid, pkg)
                    }
                }

                val activePiidSet = mutableSetOf<Int>()
                for (line in currentText.lineSequence()) {
                    if (!line.contains("state:started")) continue
                    val piid =
                        PIID_PATTERN
                            .find(line)
                            ?.groupValues
                            ?.get(1)
                            ?.toIntOrNull() ?: continue
                    activePiidSet.add(piid)
                }

                val result = mutableMapOf<Int, SessionInfo>()
                for (piid in activePiidSet) {
                    val info = playerSessions[piid] ?: continue
                    result[piid] = info
                }
                result
            }
        } catch (e: Exception) {
            FileLogger.e(TAG, "dumpsys query failed", e)
            null
        }

    companion object {
        private const val TAG = "SessionMonitor"
        private const val DUMPSYS_TIMEOUT_SEC = 5L

        private val PIID_PATTERN = Regex("""piid:(\d+)""")
        private val SESSION_PATTERN = Regex("""session(?:Id)?:(\d+)""")
        private val PACKAGE_PATTERN = Regex("""package:(\S+)""")
        private val UID_PATTERN = Regex("""u(?:id)?/pid:(\d+)/\d+""")
    }
}
