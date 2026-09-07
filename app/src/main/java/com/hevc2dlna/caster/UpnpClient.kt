package com.hevc2dlna.caster

import android.util.Log
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.MulticastSocket
import java.net.SocketTimeoutException
import java.net.URL
import javax.xml.parsers.DocumentBuilderFactory

data class RendererDevice(
    val friendlyName: String,
    val location: String,
    val uuid: String,
    val controlUrl: String?
)

class UpnpClient {

    private val multicastGroup = "239.255.255.250"
    private val multicastPort = 1900
    private val searchTarget = "urn:schemas-upnp-org:device:MediaRenderer:1"

    fun discoverRenderers(timeoutMs: Long = 3000): List<RendererDevice> {
        val results = mutableListOf<RendererDevice>()
        var socket: MulticastSocket? = null
        try {
            socket = MulticastSocket(multicastPort)
            socket.soTimeout = timeoutMs.toInt()
            socket.timeToLive = 2

            val group = InetAddress.getByName(multicastGroup)
            val local = InetAddress.getLocalHost()

            val request = buildSsdpRequest()
            val reqBytes = request.toByteArray(charset("UTF-8"))
            val packet = java.net.DatagramPacket(reqBytes, reqBytes.size, group, multicastPort)
            socket.send(packet)

            val buf = ByteArray(4096)
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                try {
                    val recv = java.net.DatagramPacket(buf, buf.size)
                    socket.receive(recv)
                    val resp = String(recv.data, 0, recv.length, charset("UTF-8"))
                    val location = extractHeader(resp, "location") ?: extractHeader(resp, "Location")
                        ?: continue
                    val usn = extractHeader(resp, "usn") ?: ""

                    val parsed = fetchDescription(location)
                    if (parsed != null && parsed.controlUrl != null) {
                        results.add(parsed.copy(location = location, uuid = usn))
                    }
                } catch (e: SocketTimeoutException) {
                    break
                }
            }
        } catch (e: Exception) {
            Log.e("UpnpClient", "Discovery failed", e)
        } finally {
            socket?.close()
        }
        return results.distinctBy { it.location }
    }

    private fun buildSsdpRequest(): String {
        return "M-SEARCH * HTTP/1.1\r\n" +
                "HOST: $multicastGroup:$multicastPort\r\n" +
                "MAN: \"ssdp:discover\"\r\n" +
                "MX: 2\r\n" +
                "ST: $searchTarget\r\n" +
                "\r\n"
    }

    private fun extractHeader(raw: String, key: String): String? {
        val lines = raw.split("\r\n", "\n")
        val prefix = key.lowercase()
        for (line in lines) {
            val idx = line.indexOf(":", ignoreCase = true)
            if (idx > 0 && line.substring(0, idx).trim().lowercase() == prefix) {
                return line.substring(idx + 1).trim()
            }
        }
        return null
    }

    fun fetchFromIp(ip: String): RendererDevice? {
        val ports = listOf(80, 8080, 49152, 52000)
        for (port in ports) {
            val url = "http://$ip:$port/description.xml"
            val parsed = fetchDescription(url)
            if (parsed != null) return parsed.copy(location = url)
        }
        return null
    }

    fun fetchDescription(location: String): RendererDevice? {
        return try {
            val url = URL(location)
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 3000
            conn.readTimeout = 3000
            conn.requestMethod = "GET"
            conn.connect()

            if (conn.responseCode != 200) {
                conn.disconnect()
                return null
            }

            val text = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()
            parseDescriptionXml(location, text)
        } catch (e: Exception) {
            Log.e("UpnpClient", "Failed to fetch $location", e)
            null
        }
    }

    fun setAvTransportUri(renderer: RendererDevice, streamUrl: String) {
        val controlUrl = renderer.controlUrl ?: return
        val body = """<?xml version="1.0"?>
            <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
              <s:Body>
                <u:SetAVTransportURI xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
                  <InstanceID>0</InstanceID>
                  <CurrentURI>$streamUrl</CurrentURI>
                  <CurrentURIMetaData></CurrentURIMetaData>
                </u:SetAVTransportURI>
              </s:Body>
            </s:Envelope>""".trimIndent()

        sendSoap(renderer.location, controlUrl, "SetAVTransportURI", body)
    }

    fun play(renderer: RendererDevice) {
        val controlUrl = renderer.controlUrl ?: return
        val body = """<?xml version="1.0"?>
            <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
              <s:Body>
                <u:Play xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
                  <InstanceID>0</InstanceID>
                  <Speed>1</Speed>
                </u:Play>
              </s:Body>
            </s:Envelope>""".trimIndent()

        sendSoap(renderer.location, controlUrl, "Play", body)
    }

    private fun sendSoap(deviceLocation: String, actionUrl: String, action: String, body: String) {
        try {
            val base = URL(deviceLocation)
            val url = URL(base.protocol, base.host, base.port, actionUrl)
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 3000
            conn.readTimeout = 3000
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"")
            conn.setRequestProperty("SOAPAction", "\"urn:schemas-upnp-org:service:AVTransport:1#$action\"")
            conn.doOutput = true
            conn.outputStream.use { it.write(body.toByteArray(charset("UTF-8"))) }
            val code = conn.responseCode
            Log.i("UpnpClient", "$action response=$code")
            conn.disconnect()
        } catch (e: Exception) {
            Log.e("UpnpClient", "SOAP $action failed", e)
        }
    }

    private fun parseDescriptionXml(location: String, xml: String): RendererDevice? {
        return try {
            val db = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            val doc = db.parse(ByteArrayInputStream(xml.toByteArray()))
            val deviceNode = doc.getElementsByTagName("device").item(0) as? Element ?: return null
            val friendlyName = getText(deviceNode, "friendlyName") ?: "Unknown"
            val uuid = getText(doc.documentElement, "UDN") ?: ""
            val controlUrl = findControlUrl(doc)
            RendererDevice(friendlyName, location, uuid, controlUrl)
        } catch (e: Exception) {
            Log.e("UpnpClient", "XML parse failed", e)
            null
        }
    }

    private fun findControlUrl(doc: Element): String? {
        val services = doc.getElementsByTagName("service")
        for (i in 0 until services.length) {
            val svc = services.item(i) as Element
            if (getText(svc, "serviceType")?.contains("AVTransport") == true) {
                val control = getText(svc, "controlURL")
                val base = doc.getElementsByTagName("URLBase").item(0)?.let {
                    (it as Node).textContent
                } ?: (doc.documentElement.getAttribute("xmlns:dlna") ?: "")
                return resolveUrl(base, control ?: return null)
            }
        }
        return null
    }

    private fun resolveUrl(base: String?, path: String): String {
        if (path.startsWith("http://") || path.startsWith("https://")) return path
        if (base != null && (base.startsWith("http://") || base.startsWith("https://"))) {
            val b = URL(base)
            return URL(b.protocol, b.host, b.port, path).toString()
        }
        return path
    }

    private fun getText(parent: Node, tag: String): String? {
        val nodes = parent.childNodes
        for (i in 0 until nodes.length) {
            val n = nodes.item(i)
            if (n.nodeType == Node.ELEMENT_NODE && n.nodeName == tag) {
                return n.textContent?.trim()
            }
        }
        return null
    }
}
