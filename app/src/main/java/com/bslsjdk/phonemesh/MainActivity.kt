package com.bslsjdk.phonemesh

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.*
import android.content.pm.PackageManager
import android.net.wifi.p2p.*
import android.os.Build
import android.os.Bundle
import android.view.ViewGroup
import android.widget.*
import java.io.*
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Locale
import kotlin.concurrent.thread

class MainActivity : Activity() {
    companion object {
        private const val PORT = 8988
        private const val REQUEST_PERMISSIONS = 100
        private const val TEST_BYTES = 16 * 1024 * 1024
        private const val MAX_TEST_BYTES = 64 * 1024 * 1024
    }

    private lateinit var manager: WifiP2pManager
    private lateinit var channel: WifiP2pManager.Channel
    private lateinit var peerList: ListView
    private lateinit var peerAdapter: ArrayAdapter<String>
    private lateinit var statusView: TextView
    private lateinit var serverButton: Button
    private lateinit var sendButton: Button
    private val peers = mutableListOf<WifiP2pDevice>()
    private var isGroupFormed = false
    private var isGroupOwner = false
    private var receiverRegistered = false
    @Volatile private var serverRunning = false
    @Volatile private var currentOwnerAddress: String? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> requestPeers()
                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> requestConnectionInfo()
                WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                    val state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
                    appendStatus(if (state == WifiP2pManager.WIFI_P2P_STATE_ENABLED)
                        "Wi-Fi Direct enabled." else "Wi-Fi Direct disabled; enable Wi-Fi in Android settings.")
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        manager = getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager
        channel = manager.initialize(this, mainLooper, null)
        buildUi()
        registerP2pReceiver()
        appendStatus("PhoneMesh AI transport prototype v0.1")
        appendStatus("No internet or traditional hotspot required. Keep Wi-Fi enabled.")
        appendStatus("Connect both phones; group owner starts server, client sends benchmark.")
        ensurePermissions()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        root.addView(TextView(this).apply { text = "PhoneMesh AI"; textSize = 26f })
        root.addView(TextView(this).apply {
            text = "Offline Wi-Fi Direct + TCP transfer test"
            textSize = 14f
        })
        val scan = Button(this).apply {
            text = "1. Scan nearby phones"
            setOnClickListener { if (ensurePermissions()) discoverPeers() }
        }
        root.addView(scan)
        peerList = ListView(this).apply { choiceMode = ListView.CHOICE_MODE_SINGLE }
        peerAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_single_choice, mutableListOf())
        peerList.adapter = peerAdapter
        root.addView(peerList, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(145)
        ))
        root.addView(Button(this).apply {
            text = "2. Connect to selected phone"
            setOnClickListener { connectSelectedPeer() }
        })
        serverButton = Button(this).apply {
            text = "3A. Start benchmark server (group owner)"
            setOnClickListener { startBenchmarkServer() }
        }
        root.addView(serverButton)
        sendButton = Button(this).apply {
            text = "3B. Send 16 MiB benchmark (client)"
            setOnClickListener { sendBenchmark() }
        }
        root.addView(sendButton)
        root.addView(TextView(this).apply {
            text = "Development test only. This measures data transfer, not model inference."
            textSize = 12f
        })
        statusView = TextView(this).apply {
            textSize = 13f
            setPadding(0, dp(10), 0, dp(10))
        }
        root.addView(ScrollView(this).apply { addView(statusView) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        updateButtons()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun registerP2pReceiver() {
        val filter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        else {
            @Suppress("DEPRECATION")
            registerReceiver(receiver, filter)
        }
        receiverRegistered = true
    }

    private fun ensurePermissions(): Boolean {
        val required = if (Build.VERSION.SDK_INT >= 33)
            arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES)
        else if (Build.VERSION.SDK_INT >= 23)
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        else emptyArray()
        val missing = required.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) {
            requestPermissions(missing.toTypedArray(), REQUEST_PERMISSIONS)
            return false
        }
        return true
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_PERMISSIONS) {
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED })
                discoverPeers()
            else appendStatus("Nearby-device permission denied.")
        }
    }

    @SuppressLint("MissingPermission")
    private fun discoverPeers() {
        manager.discoverPeers(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() = appendStatus("Discovery started.")
            override fun onFailure(reason: Int) = appendStatus("Discovery failed: " + p2pError(reason))
        })
        requestPeers()
    }

    @SuppressLint("MissingPermission")
    private fun requestPeers() {
        manager.requestPeers(channel) { list: WifiP2pDeviceList ->
            peers.clear()
            peers.addAll(list.deviceList)
            peerAdapter.clear()
            peerAdapter.addAll(peers.map { device ->
                (device.deviceName.ifBlank { "Unnamed device" }) + "\n" +
                    device.deviceAddress + "\n" + deviceStatus(device.status)
            })
            peerAdapter.notifyDataSetChanged()
            appendStatus("Found " + peers.size + " peer(s). Select one and connect.")
        }
    }

    @SuppressLint("MissingPermission")
    private fun connectSelectedPeer() {
        val index = peerList.checkedItemPosition
        if (index == ListView.INVALID_POSITION || index !in peers.indices) {
            appendStatus("Select a discovered phone first.")
            return
        }
        val config = WifiP2pConfig().apply { deviceAddress = peers[index].deviceAddress }
        manager.connect(channel, config, object : WifiP2pManager.ActionListener {
            override fun onSuccess() = appendStatus("Connection requested; accept any Android system prompt.")
            override fun onFailure(reason: Int) = appendStatus("Connect failed: " + p2pError(reason))
        })
    }

    @SuppressLint("MissingPermission")
    private fun requestConnectionInfo() {
        manager.requestConnectionInfo(channel) { info: WifiP2pInfo ->
            isGroupFormed = info.groupFormed
            isGroupOwner = info.isGroupOwner
            currentOwnerAddress = info.groupOwnerAddress?.hostAddress
            if (info.groupFormed) {
                appendStatus("Connected. Role: " + (if (isGroupOwner) "GROUP OWNER / SERVER" else "CLIENT") +
                    "; owner=" + (currentOwnerAddress ?: "unknown"))
            } else appendStatus("No active Wi-Fi Direct group.")
            updateButtons()
        }
    }

    private fun updateButtons() {
        if (!::serverButton.isInitialized) return
        serverButton.isEnabled = isGroupFormed && isGroupOwner && !serverRunning
        sendButton.isEnabled = isGroupFormed && !isGroupOwner
    }

    private fun startBenchmarkServer() {
        if (!isGroupFormed || !isGroupOwner) {
            appendStatus("Start the server on the group-owner phone.")
            return
        }
        if (serverRunning) return
        serverRunning = true
        updateButtons()
        appendStatus("Starting benchmark server on TCP port " + PORT + "...")
        thread(name = "PhoneMesh-Benchmark-Server") {
            try {
                ServerSocket().use { server ->
                    server.reuseAddress = true
                    server.bind(InetSocketAddress(PORT))
                    appendStatus("Benchmark server is listening on TCP port " + PORT + ". Tap Send benchmark on the client phone.")
                    server.soTimeout = 120_000
                    server.accept().use { socket ->
                        socket.soTimeout = 120_000
                        val input = DataInputStream(BufferedInputStream(socket.getInputStream()))
                        val output = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))
                        val length = input.readInt()
                        require(length in 1..MAX_TEST_BYTES) { "Invalid payload length: " + length }
                        val start = System.nanoTime()
                        var received = 0L
                        val buffer = ByteArray(64 * 1024)
                        while (received < length) {
                            val wanted = minOf(buffer.size.toLong(), length.toLong() - received).toInt()
                            val count = input.read(buffer, 0, wanted)
                            if (count < 0) error("Peer closed before payload completed.")
                            received += count
                        }
                        val elapsed = (System.nanoTime() - start).coerceAtLeast(1L)
                        output.writeLong(received)
                        output.writeLong(elapsed)
                        output.flush()
                        val rate = received * 8.0 / elapsed.toDouble() * 1000.0
                        appendStatus("Server received " + (received / (1024 * 1024)) +
                            " MiB in " + formatMs(elapsed) + " ms; rate " + formatRate(rate) + " Mbit/s.")
                    }
                }
            } catch (e: Exception) {
                appendStatus("Server error: " + e.javaClass.simpleName + ": " + e.message)
            } finally {
                serverRunning = false
                runOnUiThread { updateButtons() }
            }
        }
    }

    private fun sendBenchmark() {
        if (!isGroupFormed || isGroupOwner) {
            appendStatus("Use Send benchmark on the client phone.")
            return
        }
        val owner = currentOwnerAddress
        if (owner.isNullOrBlank()) {
            appendStatus("Owner address not ready; wait for connection status.")
            return
        }
        sendButton.isEnabled = false
        appendStatus("Sending 16 MiB in 64 KiB chunks. Keep both phones awake.")
        thread(name = "PhoneMesh-Benchmark-Client") {
            try {
                Socket().use { socket ->
                    socket.tcpNoDelay = true
                    socket.connect(InetSocketAddress(owner, PORT), 15_000)
                    socket.soTimeout = 120_000
                    val output = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))
                    val input = DataInputStream(BufferedInputStream(socket.getInputStream()))
                    output.writeInt(TEST_BYTES)
                    val buffer = ByteArray(64 * 1024) { index -> (index and 0xff).toByte() }
                    var sent = 0
                    val start = System.nanoTime()
                    while (sent < TEST_BYTES) {
                        val count = minOf(buffer.size, TEST_BYTES - sent)
                        output.write(buffer, 0, count)
                        sent += count
                    }
                    output.flush()
                    val clientElapsed = (System.nanoTime() - start).coerceAtLeast(1L)
                    val serverReceived = input.readLong()
                    val serverElapsed = input.readLong()
                    val clientRate = sent * 8.0 / clientElapsed.toDouble() * 1000.0
                    val serverRate = serverReceived * 8.0 / serverElapsed.toDouble() * 1000.0
                    appendStatus("Client sent " + (sent / (1024 * 1024)) + " MiB; elapsed " +
                        formatMs(clientElapsed) + " ms, client rate " + formatRate(clientRate) +
                        " Mbit/s; server payload rate " + formatRate(serverRate) + " Mbit/s.")
                }
            } catch (e: Exception) {
                appendStatus("Client error: " + e.javaClass.simpleName + ": " + e.message +
                    ". Start the server first.")
            } finally {
                runOnUiThread { updateButtons() }
            }
        }
    }

    private fun appendStatus(message: String) {
        runOnUiThread {
            val lines = (statusView.text.toString() + "\n• " + message).lines().takeLast(28)
            statusView.text = lines.joinToString("\n")
        }
    }

    private fun formatMs(nanos: Long): String =
        String.format(Locale.US, "%.1f", nanos / 1_000_000.0)

    private fun formatRate(mbit: Double): String = String.format(Locale.US, "%.2f", mbit)

    private fun p2pError(reason: Int): String = when (reason) {
        WifiP2pManager.P2P_UNSUPPORTED -> "Wi-Fi Direct unsupported"
        WifiP2pManager.BUSY -> "system busy; retry shortly"
        WifiP2pManager.ERROR -> "framework error"
        else -> "code " + reason
    }

    private fun deviceStatus(status: Int): String = when (status) {
        WifiP2pDevice.AVAILABLE -> "available"
        WifiP2pDevice.INVITED -> "invited"
        WifiP2pDevice.CONNECTED -> "connected"
        WifiP2pDevice.FAILED -> "failed"
        WifiP2pDevice.UNAVAILABLE -> "unavailable"
        else -> "status " + status
    }

    override fun onResume() {
        super.onResume()
        requestConnectionInfo()
    }

    override fun onDestroy() {
        if (receiverRegistered) {
            try { unregisterReceiver(receiver) } catch (_: IllegalArgumentException) { }
        }
        super.onDestroy()
    }
}
