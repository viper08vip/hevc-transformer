package com.hevc2dlna.caster

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.OutputStream

class TranscodePipeline(private val context: Context) {

    fun transcode(inputUri: android.net.Uri, outputStream: OutputStream) {
        if (!CodecInspector.hasHardwareHevcDecoder()) {
            throw IllegalStateException("No hardware HEVC decoder available on this device")
        }

        val extractor = MediaExtractor()
        context.contentResolver.openFileDescriptor(inputUri, "r")?.use { pfd ->
            extractor.setDataSource(pfd.fileDescriptor)
        } ?: throw IllegalArgumentException("Cannot open input file")

        var videoTrack = -1
        var audioTrack = -1
        var videoMime: String? = null
        var audioMime: String? = null

        for (i in 0 until extractor.trackCount) {
            val fmt = extractor.getTrackFormat(i)
            val mime = fmt.getString(MediaFormat.KEY_MIME)
            if (mime != null && mime.startsWith("video/")) {
                videoTrack = i
                videoMime = mime
            } else if (mime != null && mime.startsWith("audio/")) {
                audioTrack = i
                audioMime = mime
            }
        }

        if (videoTrack == -1) throw IllegalArgumentException("No video track found")

        extractor.selectTrack(videoTrack)

        val videoNeedsTranscode = videoMime != "video/avc" && videoMime != "video/mp4v-es"
        val audioNeedsTranscode = audioMime != null && !isCompatibleAudio(audioMime)

        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (videoNeedsTranscode) {
                    transcodeVideo(extractor, videoTrack, outputStream)
                } else {
                    streamCopyVideo(extractor, videoTrack, outputStream)
                }

                if (audioTrack != -1 && audioNeedsTranscode) {
                    transcodeAudio(extractor, audioTrack, outputStream)
                }

                extractor.release()
                outputStream.flush()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun transcodeVideo(
        extractor: MediaExtractor,
        track: Int,
        outputStream: OutputStream
    ) {
        val inputFormat = extractor.getTrackFormat(track)
        val decoder = MediaCodec.createDecoderByType(inputFormat.getString(MediaFormat.KEY_MIME)!!)
        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)

        val width = inputFormat.getInteger(MediaFormat.KEY_WIDTH)
        val height = inputFormat.getInteger(MediaFormat.KEY_HEIGHT)
        val frameRate = inputFormat.getInteger(MediaFormat.KEY_FRAME_RATE)

        val bitrate = 4 * 1024 * 1024
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height)
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
        format.setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
        format.setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val inputSurface = encoder.createInputSurface()
        encoder.start()

        decoder.configure(inputFormat, inputSurface, null, 0)
        decoder.start()

        val bufferInfo = MediaCodec.BufferInfo()

        while (true) {
            val inIndex = decoder.dequeueInputBuffer(10000)
            if (inIndex >= 0) {
                val buffer = decoder.getInputBuffer(inIndex)!!
                val sampleSize = extractor.readSampleData(buffer, 0)
                if (sampleSize >= 0) {
                    decoder.queueInputBuffer(inIndex, 0, sampleSize, extractor.sampleTime, extractor.sampleFlags)
                    extractor.advance()
                } else {
                    decoder.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                }
            }

            val outIndex = decoder.dequeueOutputBuffer(bufferInfo, 10000)
            if (outIndex >= 0) {
                decoder.releaseOutputBuffer(outIndex, false)
            }

            val encOutIndex = encoder.dequeueOutputBuffer(bufferInfo, 10000)
            if (encOutIndex >= 0) {
                val encodedBuffer = encoder.getOutputBuffer(encOutIndex)!!
                if (bufferInfo.size > 0) {
                    encodedBuffer.position(bufferInfo.offset)
                    encodedBuffer.limit(bufferInfo.offset + bufferInfo.size)
                    val chunk = ByteArray(bufferInfo.size)
                    encodedBuffer.get(chunk)
                    outputStream.write(chunk)
                    outputStream.flush()
                }

                encoder.releaseOutputBuffer(encOutIndex, false)

                if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    break
                }
            }
        }

        decoder.stop()
        decoder.release()
        encoder.stop()
        encoder.release()
    }

    private fun streamCopyVideo(
        extractor: MediaExtractor,
        track: Int,
        outputStream: OutputStream
    ) {
        val buffer = ByteBuffer.allocateDirect(1 * 1024 * 1024)

        while (true) {
            val size = extractor.readSampleData(buffer, 0)
            if (size < 0) break

            buffer.rewind()
            val chunk = ByteArray(size)
            buffer.get(chunk)
            outputStream.write(chunk)
            outputStream.flush()

            extractor.advance()
        }
    }

    private fun transcodeAudio(
        extractor: MediaExtractor,
        track: Int,
        outputStream: OutputStream
    ) {
        extractor.selectTrack(track)
        val format = extractor.getTrackFormat(track)
        val mime = format.getString(MediaFormat.KEY_MIME) ?: return
        val decoder = MediaCodec.createDecoderByType(mime)
        decoder.configure(format, null, null, 0)
        decoder.start()

        val bufferInfo = MediaCodec.BufferInfo()
        while (true) {
            val inIndex = decoder.dequeueInputBuffer(10000)
            if (inIndex >= 0) {
                val buf = decoder.getInputBuffer(inIndex)!!
                val size = extractor.readSampleData(buf, 0)
                if (size >= 0) {
                    decoder.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, extractor.sampleFlags)
                    extractor.advance()
                } else {
                    decoder.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                }
            }
            val outIndex = decoder.dequeueOutputBuffer(bufferInfo, 10000)
            if (outIndex >= 0) {
                val outBuf = decoder.getOutputBuffer(outIndex)!!
                if (bufferInfo.size > 0) {
                    outBuf.position(bufferInfo.offset)
                    outBuf.limit(bufferInfo.offset + bufferInfo.size)
                    val chunk = ByteArray(bufferInfo.size)
                    outBuf.get(chunk)
                    outputStream.write(chunk)
                    outputStream.flush()
                }
                decoder.releaseOutputBuffer(outIndex, false)
                if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break
            }
        }
        decoder.stop()
        decoder.release()
    }

    private fun isCompatibleAudio(mime: String): Boolean {
        return mime.equals("audio/mp4a-latm", ignoreCase = true)
                || mime.equals("audio/aac", ignoreCase = true)
                || mime.equals("audio/mpeg", ignoreCase = true)
    }
}
