package com.hevc2dlna.caster

import android.os.ParcelFileDescriptor
import android.util.Log
import fi.iki.elonen.NanoHTTPD
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

class StreamServer(private val port: Int, private val onWrite: (() -> Unit)? = null) : NanoHTTPD(port) {

    private var pipe: ParcelFileDescriptor? = null
    private var outputStream: OutputStream? = null
    private var inputStream: InputStream? = null

    init {
        try {
            val pfd = ParcelFileDescriptor.createPipe()
            pipe = pfd
            outputStream = ParcelFileDescriptor.AutoCloseOutputStream(pfd[1])
            inputStream = ParcelFileDescriptor.AutoCloseInputStream(pfd[0])
        } catch (e: IOException) {
            Log.e("StreamServer", "Failed to create pipe", e)
        }
    }

    fun getOutputStream(): OutputStream? = outputStream

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        if (uri == "/stream") {
            val fis = inputStream ?: return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "No stream")
            return object : ChunkedResponse(Response.Status.OK, "video/mp4", null) {
                override fun sendDataChunk(session: IHTTPSession, byteBuffer: java.nio.ByteBuffer) {
                    try {
                        val buf = ByteArray(65536)
                        val read = fis.read(buf)
                        if (read > 0) {
                            byteBuffer.put(buf, 0, read)
                            onWrite?.invoke()
                        } else {
                            byteBuffer.put(byteArrayOf())
                        }
                    } catch (e: IOException) {
                        byteBuffer.put(byteArrayOf())
                    }
                }
            }
        }
        return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not Found")
    }

    override fun stop() {
        super.stop()
        try {
            outputStream?.close()
            inputStream?.close()
            pipe?.close()
        } catch (e: IOException) {
            Log.e("StreamServer", "Error closing", e)
        }
    }
}
