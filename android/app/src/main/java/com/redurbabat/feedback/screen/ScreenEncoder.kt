package com.redurbabat.feedback.screen

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import com.redurbabat.feedback.security.CryptoUtils
import java.nio.ByteBuffer

/**
 * H.264 encoder for one screen stream.
 *
 * Takes frames from a [Surface] that the MediaProjection mirrors into, and hands encoded access
 * units to [ScreenCaptureCoordinator]. There is no audio track and no way to add one: the codec
 * is a video encoder and the protocol has no field for sound (section 8.5).
 *
 * Nothing here can be unit tested in this repository - MediaCodec is device code. The parts that
 * could be separated out were: the codec string comes from [AvcCodecString], the resolution from
 * [ScreenEncoderPlan], the chunking from [ScreenFrameChunker].
 */
class ScreenEncoder(
    private val settings: ScreenEncoderSettings,
    /**
     * Called when the encoder cannot go on.
     *
     * No message: the protocol has exactly one answer for this (`encoder_error`), the app keeps
     * no logs by design, and a string nobody reads is worse than no string.
     */
    private val onFailure: () -> Unit,
) {
    private var codec: MediaCodec? = null
    private var thread: HandlerThread? = null
    private var inputSurface: Surface? = null
    private var announced = false

    /** SPS/PPS, kept so every keyframe can carry them (protocol section 8.5.5). */
    private var configBytes: ByteArray = ByteArray(0)

    val surface: Surface? get() = inputSurface

    fun start() {
        val format = MediaFormat.createVideoFormat(MIME_TYPE, settings.width, settings.height).apply {
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface,
            )
            setInteger(MediaFormat.KEY_BIT_RATE, settings.bitrateKbps * 1_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, settings.fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, KEYFRAME_INTERVAL_SECONDS)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Repeats SPS/PPS before every keyframe. Without it a viewer that lost a frame
                // has nothing to resynchronise on (protocol section 8.5.5).
                setInteger(MediaFormat.KEY_PREPEND_HEADER_TO_SYNC_FRAMES, 1)
            }
        }

        val worker = HandlerThread("feedback-screen-encoder").also { it.start() }
        thread = worker

        val encoder = MediaCodec.createEncoderByType(MIME_TYPE)
        codec = encoder
        encoder.setCallback(Callback(), Handler(worker.looper))
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        inputSurface = encoder.createInputSurface()
        encoder.start()
    }

    fun requestKeyframe() {
        val encoder = codec ?: return
        try {
            encoder.setParameters(
                Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) },
            )
        } catch (_: IllegalStateException) {
            onFailure()
        }
    }

    fun release() {
        val encoder = codec
        codec = null
        try {
            encoder?.stop()
        } catch (_: IllegalStateException) {
            // Already stopped; releasing is still the right next step.
        }
        encoder?.release()
        configBytes = ByteArray(0)
        inputSurface?.release()
        inputSurface = null
        thread?.quitSafely()
        thread = null
    }

    private inner class Callback : MediaCodec.Callback() {

        override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
            // Surface input: the encoder pulls frames itself, there is nothing to feed.
        }

        override fun onOutputBufferAvailable(
            codec: MediaCodec,
            index: Int,
            info: MediaCodec.BufferInfo,
        ) {
            val buffer = codec.getOutputBuffer(index)
            if (buffer == null) {
                codec.releaseOutputBuffer(index, false)
                return
            }
            val bytes = copyOut(buffer, info)
            val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
            codec.releaseOutputBuffer(index, false)

            if (isConfig) {
                announce(bytes)
                return
            }
            if (bytes.isEmpty() || !announced) {
                return
            }
            val keyFrame = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
            ScreenCaptureCoordinator.frame(
                EncodedScreenFrame(
                    // Adds nothing when the encoder already prepended the header, which it does
                    // from API 29 on. Below that, and on encoders that ignore the hint, this is
                    // the only reason a keyframe is usable at all.
                    data = if (keyFrame) ScreenFrameHeader.withHeader(bytes, configBytes) else bytes,
                    keyFrame = keyFrame,
                    timestampUs = info.presentationTimeUs,
                ),
            )
        }

        override fun onError(codec: MediaCodec, error: MediaCodec.CodecException) {
            onFailure()
        }

        override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
            // The codec config arrives as a buffer with BUFFER_FLAG_CODEC_CONFIG as well, and
            // that is the one path that also carries the raw SPS bytes we need.
        }
    }

    private fun copyOut(buffer: ByteBuffer, info: MediaCodec.BufferInfo): ByteArray {
        if (info.size <= 0) {
            return ByteArray(0)
        }
        buffer.position(info.offset)
        buffer.limit(info.offset + info.size)
        val bytes = ByteArray(info.size)
        buffer.get(bytes)
        return bytes
    }

    private fun announce(configBytes: ByteArray) {
        if (announced) {
            return
        }
        val codecString = AvcCodecString.fromAnnexB(configBytes)
        if (codecString == null) {
            // Guessing here would produce a picture that fails to decode in the browser for
            // reasons nobody can see from either side.
            onFailure()
            return
        }
        announced = true
        this.configBytes = configBytes
        ScreenCaptureCoordinator.started(
            ScreenStreamConfig(
                width = settings.width,
                height = settings.height,
                codec = codecString,
                fps = settings.fps,
                configBase64 = CryptoUtils.Base64.encode(configBytes),
            ),
        )
    }

    private companion object {
        val MIME_TYPE: String = MediaFormat.MIMETYPE_VIDEO_AVC
        const val KEYFRAME_INTERVAL_SECONDS = 2
    }
}
