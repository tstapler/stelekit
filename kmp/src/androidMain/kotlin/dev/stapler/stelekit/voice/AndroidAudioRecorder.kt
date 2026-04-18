// Copyright (c) 2026 Tyler Stapler
// SPDX-License-Identifier: Elastic-2.0
package dev.stapler.stelekit.voice

import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.media.AudioFocusRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.sqrt

class AndroidAudioRecorder(private val context: Context) : AudioRecorder {

    companion object {
        private const val SAMPLE_RATE = 16_000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val BIT_RATE = 128_000
        private const val MIME_TYPE = "audio/mp4a-latm"
        private const val CODEC_TIMEOUT_US = 10_000L
    }

    private val _amplitudeFlow = MutableStateFlow(0f)
    override val amplitudeFlow: Flow<Float> = _amplitudeFlow.asStateFlow()

    @Volatile private var stopRequested = false
    @Volatile private var pauseRequested = false

    override suspend fun startRecording(): PlatformAudioFile = withContext(Dispatchers.IO) {
        stopRequested = false
        pauseRequested = false

        val outputFile = File(context.cacheDir, "voice_${System.currentTimeMillis()}.m4a")
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

        val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setOnAudioFocusChangeListener { change ->
                when (change) {
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> pauseRequested = true
                    AudioManager.AUDIOFOCUS_GAIN -> pauseRequested = false
                    AudioManager.AUDIOFOCUS_LOSS -> stopRequested = true
                }
            }
            .build()
        audioManager.requestAudioFocus(focusRequest)

        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        val bufferSize = maxOf(minBuf * 4, 8192)

        var audioRecord: AudioRecord? = null
        var mediaCodec: MediaCodec? = null
        var mediaMuxer: MediaMuxer? = null

        try {
            audioRecord = try {
                AudioRecord(
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                    SAMPLE_RATE,
                    CHANNEL_CONFIG,
                    AUDIO_FORMAT,
                    bufferSize,
                )
            } catch (e: SecurityException) {
                audioManager.abandonAudioFocusRequest(focusRequest)
                return@withContext PlatformAudioFile("")
            }

            if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
                audioRecord.release()
                audioManager.abandonAudioFocusRequest(focusRequest)
                return@withContext PlatformAudioFile("")
            }

            mediaCodec = MediaCodec.createEncoderByType(MIME_TYPE)
            val format = MediaFormat.createAudioFormat(MIME_TYPE, SAMPLE_RATE, 1).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, bufferSize)
            }
            mediaCodec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            mediaCodec.start()

            mediaMuxer = MediaMuxer(
                outputFile.absolutePath,
                MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4,
            )

            audioRecord.startRecording()

            val pcmBuffer = ByteArray(bufferSize)
            val bufferInfo = MediaCodec.BufferInfo()
            var muxerStarted = false
            var muxerTrackIndex = -1
            var presentationTimeUs = 0L

            // isActive checks for coroutine cancellation; stopRequested handles user-initiated stop.
            while (!stopRequested && isActive) {
                if (pauseRequested) {
                    kotlinx.coroutines.delay(50)
                    continue
                }

                val bytesRead = audioRecord.read(pcmBuffer, 0, pcmBuffer.size)
                if (bytesRead <= 0) continue

                // RMS amplitude for animated feedback
                var sumSq = 0.0
                for (i in 0 until bytesRead - 1 step 2) {
                    val sample = ((pcmBuffer[i + 1].toInt() shl 8) or (pcmBuffer[i].toInt() and 0xFF)).toShort().toInt()
                    sumSq += sample.toDouble() * sample.toDouble()
                }
                val rms = sqrt(sumSq / (bytesRead / 2)).toFloat() / Short.MAX_VALUE
                _amplitudeFlow.value = rms.coerceIn(0f, 1f)

                // Feed PCM to encoder
                val inputIdx = mediaCodec.dequeueInputBuffer(CODEC_TIMEOUT_US)
                if (inputIdx >= 0) {
                    val inputBuf = mediaCodec.getInputBuffer(inputIdx)!!
                    inputBuf.clear()
                    inputBuf.put(pcmBuffer, 0, bytesRead)
                    presentationTimeUs += (bytesRead.toLong() * 1_000_000L) / (SAMPLE_RATE * 2L)
                    mediaCodec.queueInputBuffer(inputIdx, 0, bytesRead, presentationTimeUs, 0)
                }

                // Drain encoder output; onFormatChanged starts the muxer on first invocation.
                drainEncoder(mediaCodec, mediaMuxer, bufferInfo, muxerStarted, muxerTrackIndex, 0) { trackIdx ->
                    muxerTrackIndex = trackIdx
                    if (!muxerStarted) {
                        mediaMuxer.start()
                        muxerStarted = true
                    }
                }
            }

            // Signal EOS and drain remaining frames to produce a valid .m4a file.
            val inputIdx = mediaCodec.dequeueInputBuffer(CODEC_TIMEOUT_US)
            if (inputIdx >= 0) {
                mediaCodec.queueInputBuffer(
                    inputIdx, 0, 0, presentationTimeUs,
                    MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                )
            }
            drainEncoder(mediaCodec, mediaMuxer, bufferInfo, muxerStarted, muxerTrackIndex, CODEC_TIMEOUT_US)

            PlatformAudioFile(outputFile.absolutePath)
        } catch (e: SecurityException) {
            PlatformAudioFile("")
        } finally {
            runCatching { audioRecord?.stop() }
            runCatching { audioRecord?.release() }
            runCatching { mediaCodec?.stop() }
            runCatching { mediaCodec?.release() }
            runCatching { mediaMuxer?.stop() }
            runCatching { mediaMuxer?.release() }
            audioManager.abandonAudioFocusRequest(focusRequest)
            _amplitudeFlow.value = 0f
        }
    }

    override suspend fun stopRecording() {
        stopRequested = true
    }

    override suspend fun readBytes(file: PlatformAudioFile): ByteArray =
        if (file.isEmpty) ByteArray(0)
        else withContext(Dispatchers.IO) { File(file.path).readBytes() }

    override fun deleteRecording(file: PlatformAudioFile) {
        if (!file.isEmpty) File(file.path).delete()
    }

    /**
     * Drains encoded output from [codec] into [muxer]. Stops at INFO_TRY_AGAIN_LATER or EOS.
     * [timeoutUs] 0 = non-blocking (use during the record loop); CODEC_TIMEOUT_US = blocking
     * (use after signalling EOS to ensure all frames are flushed).
     * [onFormatChanged] is called once when the output format is known; it should add the muxer
     * track and start the muxer.
     */
    private fun drainEncoder(
        codec: MediaCodec,
        muxer: MediaMuxer,
        info: MediaCodec.BufferInfo,
        muxerStarted: Boolean,
        trackIndex: Int,
        timeoutUs: Long,
        onFormatChanged: ((Int) -> Unit)? = null,
    ) {
        while (true) {
            val idx = codec.dequeueOutputBuffer(info, timeoutUs)
            when {
                idx == MediaCodec.INFO_TRY_AGAIN_LATER -> break
                idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    if (onFormatChanged != null) {
                        val newTrack = muxer.addTrack(codec.outputFormat)
                        onFormatChanged(newTrack)
                    }
                }
                idx >= 0 -> {
                    val buf = codec.getOutputBuffer(idx)!!
                    val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    if (!isConfig && muxerStarted && trackIndex >= 0 && info.size > 0) {
                        muxer.writeSampleData(trackIndex, buf, info)
                    }
                    codec.releaseOutputBuffer(idx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
        }
    }
}
