package com.hevc2dlna.caster

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var btnSelectVideo: Button
    private lateinit var tvCodecInfo: TextView
    private lateinit var btnSelectRenderer: Button
    private lateinit var tvRendererInfo: TextView
    private lateinit var btnCast: Button
    private lateinit var tvStatus: TextView

    private var selectedUri: Uri? = null
    private var selectedRenderer: RendererDevice? = null
    private var transcodeJob: kotlinx.coroutines.Job? = null
    private var streamServer: StreamServer? = null

    private val codecInspector = CodecInspector(this)
    private val upnpClient = UpnpClient()

    private val pickVideo = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            selectedUri = it
            lifecycleScope.launch {
                val info = withContext(Dispatchers.IO) { codecInspector.inspect(it) }
                tvCodecInfo.text = info.toString()
                updateCastButton()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        btnSelectVideo = findViewById(R.id.btnSelectVideo)
        tvCodecInfo = findViewById(R.id.tvCodecInfo)
        btnSelectRenderer = findViewById(R.id.btnSelectRenderer)
        tvRendererInfo = findViewById(R.id.tvRendererInfo)
        btnCast = findViewById(R.id.btnCast)
        tvStatus = findViewById(R.id.tvStatus)

        btnSelectVideo.setOnClickListener {
            pickVideo.launch(arrayOf("video/*"))
        }

        btnSelectRenderer.setOnClickListener {
            showRendererDialog()
        }

        btnCast.setOnClickListener {
            selectedUri?.let { uri ->
                selectedRenderer?.let { renderer ->
                    startCast(uri, renderer)
                } ?: run {
                    Toast.makeText(this, "Select a renderer first", Toast.LENGTH_SHORT).show()
                }
            } ?: run {
                Toast.makeText(this, "Select a video first", Toast.LENGTH_SHORT).show()
            }
        }

        requestPermissions()
    }

    private fun requestPermissions() {
        val perms = mutableListOf<String>()
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.READ_MEDIA_VIDEO)
        } else {
            perms.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        perms.add(Manifest.permission.INTERNET)
        perms.add(Manifest.permission.ACCESS_WIFI_STATE)
        perms.add(Manifest.permission.CHANGE_WIFI_MULTICAST_STATE)
        perms.add(Manifest.permission.ACCESS_NETWORK_STATE)

        val missing = perms.filter {
            ContextCompat.checkSelfPermission(this, it) != android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), 100)
        }
    }

    private fun showRendererDialog() {
        val items = mutableListOf<String>()
        val renderers = upnpClient.discoverRenderers()
        if (renderers.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("Renderers")
                .setMessage("No renderers found via SSDP. Enter IP manually?")
                .setPositiveButton("Manual IP") { _, _ -> showManualIpDialog() }
                .setNegativeButton("Cancel", null)
                .show()
            return
        }
        renderers.forEach { items.add("${it.friendlyName}\n${it.location}") }
        selectedRenderer = null
        AlertDialog.Builder(this)
            .setTitle("Select Renderer")
            .setItems(items.toTypedArray()) { _, idx ->
                selectedRenderer = renderers[idx]
                tvRendererInfo.text = selectedRenderer.toString()
                updateCastButton()
            }
            .setNeutralButton("Manual IP") { _, _ -> showManualIpDialog() }
            .show()
    }

    private fun showManualIpDialog() {
        val input = android.widget.EditText(this)
        input.inputType = android.text.InputType.TYPE_CLASS_PHONE
        AlertDialog.Builder(this)
            .setTitle("Enter renderer IP")
            .setView(input)
            .setPositiveButton("OK") { _, _ ->
                val ip = input.text.toString().trim()
                if (ip.isNotEmpty()) {
                    val renderer = upnpClient.fetchFromIp(ip)
                    if (renderer != null) {
                        selectedRenderer = renderer
                        tvRendererInfo.text = renderer.toString()
                        updateCastButton()
                    } else {
                        Toast.makeText(this, "Could not fetch renderer description", Toast.LENGTH_LONG).show()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun updateCastButton() {
        btnCast.isEnabled = selectedUri != null && selectedRenderer != null
    }

    private fun startCast(videoUri: Uri, renderer: RendererDevice) {
        transcodeJob?.cancel()
        val port = 8080
        streamServer = StreamServer(port) {
            runOnUiThread {
                tvStatus.text = getString(R.string.status_streaming)
            }
        }
        streamServer?.start()

        val outputStream = streamServer?.getOutputStream()
        if (outputStream == null) {
            tvStatus.text = "${getString(R.string.status_error)}: stream not initialized"
            return
        }

        val streamUrl = "http://127.0.0.1:$port/stream"
        tvStatus.text = getString(R.string.status_transcoding)

        transcodeJob = lifecycleScope.launch(Dispatchers.IO) {
            try {
                val pipeline = TranscodePipeline(this@MainActivity)
                pipeline.transcode(videoUri, outputStream)
                upnpClient.setAvTransportUri(renderer, streamUrl)
                upnpClient.play(renderer)
                withContext(Dispatchers.Main) {
                    tvStatus.text = getString(R.string.status_playing)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    tvStatus.text = "${getString(R.string.status_error)}: ${e.message}"
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        transcodeJob?.cancel()
        streamServer?.stop()
    }
}
