package com.hevc2dlna.caster

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat

data class CodecInfo(
    val videoNeedsTranscode: Boolean,
    val videoMime: String,
    val audioNeedsTranscode: Boolean,
    val audioMime: String?
)

class CodecInspector(private val context: Context) {

    fun inspect(uri: android.net.Uri): CodecInfo {
        var videoNeedsTranscode = true
        var audioNeedsTranscode = true
        var videoMime: String? = null
        var audioMime: String? = null

        context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
            val extractor = MediaExtractor()
            extractor.setDataSource(pfd.fileDescriptor)

            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("video/")) {
                    videoMime = mime
                    videoNeedsTranscode = mime != "video/avc" && mime != "video/mp4v-es"
                } else if (mime.startsWith("audio/")) {
                    audioMime = mime
                    audioNeedsTranscode = !isCompatibleAudio(mime)
                }
            }
            extractor.release()
        }

        return CodecInfo(
            videoNeedsTranscode = videoNeedsTranscode,
            videoMime = videoMime ?: "unknown",
            audioNeedsTranscode = audioNeedsTranscode,
            audioMime = audioMime
        )
    }

    private fun isCompatibleAudio(mime: String): Boolean {
        return when {
            mime.equals("audio/mp4a-latm", ignoreCase = true) -> true
            mime.equals("audio/aac", ignoreCase = true) -> true
            mime.equals("audio/mpeg", ignoreCase = true) -> true
            else -> false
        }
    }

    companion object {
        fun hasHardwareHevcDecoder(): Boolean {
            val codecs = android.media.MediaCodecList(android.media.MediaCodecList.ALL_CODECS)
            for (info in codecs.codecInfos) {
                if (!info.isEncoder) {
                    val types = info.supportedTypes
                    if (types.any { it.equals(MediaFormat.MIMETYPE_VIDEO_HEVC, ignoreCase = true) }) {
                        return true
                    }
                }
            }
            return false
        }
    }
}
