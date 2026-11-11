package com.llsl.viper4android.viper

import android.media.audiofx.AudioEffect
import com.llsl.viper4android.utils.FileLogger
import java.lang.reflect.Constructor
import java.lang.reflect.Method
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

class ViperEffect(
    private val audioSessionId: Int,
    private val typeUuid: UUID = EFFECT_TYPE_UUID,
) {
    companion object {
        val EFFECT_TYPE_UUID: UUID = UUID.fromString("ec7178ec-e5e1-4432-a3f4-4657e6795210")
        val EFFECT_TYPE_UUID_AIDL: UUID = UUID.fromString("7261676f-6d75-7369-6364-28e2fd3ac39e")
        val EFFECT_UUID: UUID = UUID.fromString("90380da3-8536-4744-a6a3-5731970e640f")

        private val ctor: Constructor<AudioEffect>? by lazy {
            try {
                AudioEffect::class.java
                    .getConstructor(
                        UUID::class.java,
                        UUID::class.java,
                        Int::class.javaPrimitiveType,
                        Int::class.javaPrimitiveType,
                    )
            } catch (e: Exception) {
                FileLogger.e("Effect", "AudioEffect constructor not found", e)
                null
            }
        }

        private val setParamMethod: Method? by lazy {
            try {
                AudioEffect::class.java
                    .getMethod("setParameter", ByteArray::class.java, ByteArray::class.java)
            } catch (e: Exception) {
                FileLogger.e("Effect", "setParameter method not found", e)
                null
            }
        }

        private val getParamMethod: Method? by lazy {
            try {
                AudioEffect::class.java
                    .getMethod("getParameter", ByteArray::class.java, ByteArray::class.java)
            } catch (e: Exception) {
                FileLogger.e("Effect", "getParameter method not found", e)
                null
            }
        }
    }

    private var effect: AudioEffect? = null

    val isCreated: Boolean
        get() = effect != null

    fun create(): Boolean {
        if (effect != null) return true
        val c = ctor
        if (c == null) {
            FileLogger.e("Effect", "AudioEffect constructor is null, reflection failed")
            return false
        }
        return try {
            FileLogger.d(
                "Effect",
                "Creating AudioEffect: type=$typeUuid, uuid=$EFFECT_UUID, session=$audioSessionId",
            )
            effect = c.newInstance(typeUuid, EFFECT_UUID, 0, audioSessionId)
            FileLogger.d("Effect", "AudioEffect created successfully for session $audioSessionId")
            true
        } catch (e: Exception) {
            FileLogger.e("Effect", "Failed to create AudioEffect for session $audioSessionId", e)
            val cause = e.cause
            if (cause != null) {
                FileLogger.e("Effect", "Root cause: ${cause.javaClass.name}: ${cause.message}")
            }
            false
        }
    }

    fun release() {
        effect?.release()
        effect = null
    }

    val hasControl: Boolean
        get() =
            try {
                effect?.hasControl() ?: false
            } catch (_: Exception) {
                false
            }

    var enabled: Boolean
        get() = effect?.enabled ?: false
        set(value) {
            try {
                effect?.enabled = value
            } catch (e: Exception) {
                FileLogger.e("Effect", "Failed to set enabled=$value for session $audioSessionId", e)
            }
        }

    context(fx: AudioEffect, m: Method)
    private fun invokeParam(
        paramBytes: ByteArray,
        valueBytes: ByteArray,
        tag: String,
    ): Boolean =
        try {
            val status = m.invoke(fx, paramBytes, valueBytes) as Int
            if (status != AudioEffect.SUCCESS) {
                FileLogger.w("Effect", "$tag returned $status")
                false
            } else {
                true
            }
        } catch (e: Exception) {
            FileLogger.e("Effect", "$tag invoke failed", e)
            false
        }

    fun setParameter(
        param: Int,
        value: ParamValue,
    ): Boolean {
        val fx = effect ?: return false
        val m = setParamMethod ?: return false
        return with(fx) { with(m) { invokeParam(intToBytes(param), ViperParamPayload.encode(value), "setParameter($param, $value)") } }
    }

    fun getParameter(
        param: Int,
        size: Int,
    ): ByteArray {
        val fx = effect ?: return ByteArray(0)
        val m = getParamMethod ?: return ByteArray(0)
        val paramBytes = intToBytes(param)
        val valueBytes = ByteArray(size)
        try {
            val status = m.invoke(fx, paramBytes, valueBytes) as Int
            if (status < 0) {
                FileLogger.w("Effect", "getParameter($param, size=$size) returned status $status")
                return ByteArray(0)
            }
            return valueBytes
        } catch (e: Exception) {
            FileLogger.e("Effect", "getParameter($param, size=$size) invoke failed", e)
            return ByteArray(0)
        }
    }

    fun getIntParameter(param: Int): Int {
        val bytes = getParameter(param, Int.SIZE_BYTES)
        if (bytes.size != Int.SIZE_BYTES) return -1
        return bytes.toLittleEndianInt()
    }

    fun getLongParameter(param: Int): Long {
        val bytes = getParameter(param, Long.SIZE_BYTES)
        if (bytes.size != Long.SIZE_BYTES) return 0L
        return bytes.toLittleEndianLong()
    }

    fun getStringParameter(
        param: Int,
        size: Int,
        fallback: String,
    ): String {
        val bytes = getParameter(param, size)
        if (bytes.size != size) return ""
        return bytes.toNullTerminatedString(fallback)
    }

    fun getStatus(): DriverStatus =
        DriverStatus(
            enabled = enabled,
            sampleRate = getIntParameter(ViperParams.PARAM_GET_SAMPLING_RATE),
            processedFrames = getLongParameter(ViperParams.PARAM_GET_PROCESSED_FRAMES),
            kernelId = getIntParameter(ViperParams.PARAM_GET_CONVOLUTION_KERNEL_ID),
            versionCode = getIntParameter(ViperParams.PARAM_GET_DRIVER_VERSION_CODE),
            versionName = getStringParameter(ViperParams.PARAM_GET_DRIVER_VERSION_NAME, 256, "-"),
            arch = getStringParameter(ViperParams.PARAM_GET_ARCHITECTURE, 64, "Unknow"),
        )

    private fun intToBytes(value: Int): ByteArray =
        ByteBuffer
            .allocate(4)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putInt(value)
            .array()

    private fun ByteArray.toLittleEndianInt(): Int = ByteBuffer.wrap(this).order(ByteOrder.LITTLE_ENDIAN).int

    private fun ByteArray.toLittleEndianLong(): Long = ByteBuffer.wrap(this).order(ByteOrder.LITTLE_ENDIAN).long

    private fun ByteArray.toNullTerminatedString(fallback: String): String {
        if (isEmpty()) return fallback
        val nullIdx = indexOf(0.toByte())
        return if (nullIdx >= 0) String(this, 0, nullIdx) else String(this)
    }
}
