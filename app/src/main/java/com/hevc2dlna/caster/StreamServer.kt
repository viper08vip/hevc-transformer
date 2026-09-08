package com.hevc2dlna.caster

import android.util.Log
import fi.iki.elonen.NanoHTTPD
import java.io.IOException
import java.io.InputStream

class StreamServer(private val port: Int, private val onWrite: (() -> Unit)? = null) : NanoHTTPD(port) {

    private var pipe: ParcelFileDescriptor? = null
    private var outputStream: java.io.OutputStream? = null
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

    fun getOutputStream(): java.io.OutputStream? = outputStream

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        if (uri == "/stream") {
            val fis = inputStream ?: return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "No stream")
            return newChunkedResponse(Response.Status.OK, "video/mp4", fis)
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
