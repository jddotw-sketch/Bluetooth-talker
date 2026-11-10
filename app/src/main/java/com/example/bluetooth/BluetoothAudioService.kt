package com.example.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import com.example.model.BluetoothDeviceInfo
import com.example.model.ConnectionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue

class BluetoothAudioService(
    private val context: Context,
    private val onPacketReceived: (deviceAddress: String, type: Byte, payload: ByteArray) -> Unit
) {
    companion object {
        private const val TAG = "BluetoothAudioService"
        const val MAX_CONNECTIONS = 2
        private const val SERVICE_NAME = "BlueVoiceAudio"
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

        // Protocol Magic & Types
        const val MAGIC_BYTE: Byte = 0x5A
        const val TYPE_AUDIO: Byte = 0x01
        const val TYPE_PTT_START: Byte = 0x02
        const val TYPE_PTT_END: Byte = 0x03
        const val TYPE_PING: Byte = 0x04
        const val TYPE_TEXT: Byte = 0x05
        const val TYPE_AUDIO_ADPCM: Byte = 0x06 // IMA-ADPCM 4:1 compressed ultra-low-latency audio
        const val TYPE_CONFIG: Byte = 0x07 // Transmits sample rate & audio config
        const val TYPE_LATENCY_CONFIG: Byte = 0x08 // 1 byte: 0 = smooth, 1 = low latency
    }

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    val bluetoothAdapter: BluetoothAdapter? = bluetoothManager?.adapter

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _connectedDevices = MutableStateFlow<List<BluetoothDeviceInfo>>(emptyList())
    val connectedDevices: StateFlow<List<BluetoothDeviceInfo>> = _connectedDevices.asStateFlow()

    private val _discoveredDevices = MutableStateFlow<List<BluetoothDeviceInfo>>(emptyList())
    val discoveredDevices: StateFlow<List<BluetoothDeviceInfo>> = _discoveredDevices.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val scope = CoroutineScope(Dispatchers.IO)
    private var acceptJob: Job? = null
    private var connectJob: Job? = null
    // One RFCOMM worker per source; a receiver may accept phone + Windows simultaneously.
    private val connections = linkedMapOf<String, ConnectedWorker>()
    private val connectionNames = linkedMapOf<String, String>()
    private var serverSocket: BluetoothServerSocket? = null

    private var isReceiverRegistered = false

    private val discoveryReceiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val device: BluetoothDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    }
                    val rssi = intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE)

                    device?.let { dev ->
                        val name = try {
                            dev.name ?: dev.address
                        } catch (e: SecurityException) {
                            dev.address
                        }
                        val info = BluetoothDeviceInfo(
                            name = name,
                            address = dev.address,
                            isPaired = dev.bondState == BluetoothDevice.BOND_BONDED,
                            rssi = if (rssi != Short.MIN_VALUE) rssi else null
                        )

                        val currentList = _discoveredDevices.value.toMutableList()
                        val index = currentList.indexOfFirst { it.address == info.address }
                        if (index >= 0) {
                            currentList[index] = info
                        } else {
                            currentList.add(info)
                        }
                        _discoveredDevices.value = currentList
                    }
                }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    _isScanning.value = false
                }
            }
        }
    }

    init {
        registerDiscoveryReceiver()
    }

    private fun registerDiscoveryReceiver() {
        if (!isReceiverRegistered) {
            val filter = IntentFilter().apply {
                addAction(BluetoothDevice.ACTION_FOUND)
                addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
            }
            context.registerReceiver(discoveryReceiver, filter)
            isReceiverRegistered = true
        }
    }

    @SuppressLint("MissingPermission")
    fun getPairedDevices(): List<BluetoothDeviceInfo> {
        val adapter = bluetoothAdapter ?: return emptyList()
        return try {
            adapter.bondedDevices?.map { dev ->
                BluetoothDeviceInfo(
                    name = dev.name ?: dev.address,
                    address = dev.address,
                    isPaired = true
                )
            } ?: emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get bonded devices: ${e.message}")
            emptyList()
        }
    }

    @SuppressLint("MissingPermission")
    fun startDiscovery() {
        val adapter = bluetoothAdapter ?: return
        try {
            if (adapter.isDiscovering) {
                adapter.cancelDiscovery()
            }
            _discoveredDevices.value = emptyList()
            _isScanning.value = adapter.startDiscovery()
        } catch (e: Exception) {
            Log.e(TAG, "Error starting discovery: ${e.message}")
            _isScanning.value = false
        }
    }

    @SuppressLint("MissingPermission")
    fun stopDiscovery() {
        try {
            bluetoothAdapter?.cancelDiscovery()
            _isScanning.value = false
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping discovery: ${e.message}")
        }
    }


    /** Keep the server socket open when the first peer connects so a second peer can join. */
    @SuppressLint("MissingPermission")
    fun startListening() {
        val adapter = bluetoothAdapter ?: return
        if (acceptJob?.isActive == true) return
        val localName = try { adapter.name ?: "This Phone" } catch (_: Exception) { "This Phone" }
        if (_connectedDevices.value.isEmpty()) _connectionState.value = ConnectionState.Listening(localName)
        acceptJob = scope.launch {
            try {
                val server = adapter.listenUsingRfcommWithServiceRecord(SERVICE_NAME, SPP_UUID)
                serverSocket = server
                Log.i(TAG, "Bluetooth SPP server listening (up to $MAX_CONNECTIONS peers)")
                while (isActive) {
                    val socket = server.accept() ?: continue
                    val remote = socket.remoteDevice
                    val name = try { remote.name ?: remote.address } catch (_: Exception) { remote.address }
                    if (!registerConnection(socket, name)) {
                        Log.w(TAG, "Ignoring extra/duplicate Bluetooth connection from ${remote.address}")
                        try { socket.close() } catch (_: Exception) {}
                    }
                }
            } catch (e: Exception) {
                Log.d(TAG, "Bluetooth listen ended: ${e.message}")
            } finally {
                try { serverSocket?.close() } catch (_: Exception) {}
                serverSocket = null
                if (isActive) updateConnectionState(false)
            }
        }
    }

    fun stopListening() {
        acceptJob?.cancel()
        acceptJob = null
        try { serverSocket?.close() } catch (e: Exception) {
            Log.w(TAG, "Error stopping Bluetooth listener", e)
        }
        serverSocket = null
        updateConnectionState(false)
    }

    @SuppressLint("MissingPermission")
    fun connectToDevice(deviceAddress: String, deviceName: String? = null) {
        val adapter = bluetoothAdapter ?: return
        synchronized(connections) {
            if (connections.containsKey(deviceAddress) || connections.size >= MAX_CONNECTIONS) return
        }
        connectJob?.cancel()
        val name = deviceName ?: deviceAddress
        if (_connectedDevices.value.isEmpty()) _connectionState.value = ConnectionState.Connecting(name, deviceAddress)
        connectJob = scope.launch {
            var socket: BluetoothSocket? = null
            var registered = false
            try {
                adapter.cancelDiscovery()
                val device = adapter.getRemoteDevice(deviceAddress)
                try {
                    socket = device.createRfcommSocketToServiceRecord(SPP_UUID)
                    socket.connect()
                } catch (e: IOException) {
                    Log.w(TAG, "Standard RFCOMM connect failed; trying channel 1: ${e.message}")
                    try { socket?.close() } catch (_: Exception) {}
                    val fallback = device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
                    socket = fallback.invoke(device, 1) as BluetoothSocket
                    socket.connect()
                }
                val connected = socket ?: throw IOException("No Bluetooth socket")
                registered = registerConnection(connected, name)
                if (!registered) Log.w(TAG, "Connection rejected: already connected or at capacity")
            } catch (e: Exception) {
                Log.e(TAG, "Bluetooth connection failed: ${e.message}")
            } finally {
                if (!registered) {
                    try { socket?.close() } catch (_: Exception) {}
                }
                updateConnectionState(acceptJob?.isActive == true)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun registerConnection(socket: BluetoothSocket, name: String): Boolean {
        val address = socket.remoteDevice.address
        val worker: ConnectedWorker
        synchronized(connections) {
            if (connections.containsKey(address) || connections.size >= MAX_CONNECTIONS) return false
            worker = ConnectedWorker(socket, address)
            connections[address] = worker
            connectionNames[address] = name
            updateConnectionState(acceptJob?.isActive == true)
        }
        worker.start()
        Log.i(TAG, "Connected Bluetooth sources: ${_connectedDevices.value.size}/$MAX_CONNECTIONS")
        return true
    }

    private fun updateConnectionState(listening: Boolean) {
        synchronized(connections) {
            _connectedDevices.value = connections.keys.map { address ->
                BluetoothDeviceInfo(name = connectionNames[address] ?: address, address = address, isPaired = true)
            }
            val first = _connectedDevices.value.firstOrNull()
            _connectionState.value = when {
                first != null -> ConnectionState.Connected(first.name, first.address)
                listening -> ConnectionState.Listening("This Phone")
                else -> ConnectionState.Disconnected
            }
        }
    }

    private fun onWorkerDisconnected(address: String, worker: ConnectedWorker) {
        synchronized(connections) {
            if (connections[address] !== worker) return
            connections.remove(address)
            connectionNames.remove(address)
            updateConnectionState(acceptJob?.isActive == true)
        }
        Log.i(TAG, "Disconnected Bluetooth source $address")
    }

    fun disconnectDevice(deviceAddress: String) {
        val worker = synchronized(connections) {
            connectionNames.remove(deviceAddress)
            connections.remove(deviceAddress)
        }
        worker?.cancel()
        updateConnectionState(acceptJob?.isActive == true)
    }

    fun disconnect() {
        stopListening()
        connectJob?.cancel()
        connectJob = null
        val peers = synchronized(connections) {
            val active = connections.values.toList()
            connections.clear()
            connectionNames.clear()
            active
        }
        peers.forEach { it.cancel() }
        encoderState.reset()
        updateConnectionState(false)
    }

    private fun activeWorkers(): List<ConnectedWorker> =
        synchronized(connections) { connections.values.toList() }

    private val encoderState = com.example.audio.AdpcmCodec.CodecState()
    @Volatile
    var lowLatencyEnabled: Boolean = false
        set(value) {
            field = value
            activeWorkers().forEach { it.setLowLatency(value) }
        }

    @Synchronized
    fun sendAudio(data: ByteArray) {
        val peers = activeWorkers()
        if (peers.isEmpty() || data.isEmpty()) return
        try {
            val compressed = com.example.audio.AdpcmCodec.encode(data, encoderState)
            peers.forEach { it.queuePacket(TYPE_AUDIO_ADPCM, compressed) }
        } catch (e: Exception) {
            Log.w(TAG, "ADPCM encode failed, using PCM", e)
            peers.forEach { it.queuePacket(TYPE_AUDIO, data) }
        }
    }

    @Synchronized
    fun clearAudioQueue() {
        encoderState.reset()
        activeWorkers().forEach { it.clearAudioQueue() }
    }

    fun sendPttStart() = activeWorkers().forEach { it.queuePacket(TYPE_PTT_START, ByteArray(0)) }

    fun sendPttEnd() = activeWorkers().forEach { it.queuePacket(TYPE_PTT_END, ByteArray(0)) }

    fun sendTextMessage(text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        if (bytes.size > 32768) return
        activeWorkers().forEach { it.queuePacket(TYPE_TEXT, bytes) }
    }

    fun sendLatencyConfig(enabled: Boolean = lowLatencyEnabled) {
        activeWorkers().forEach { it.queuePacket(TYPE_LATENCY_CONFIG, byteArrayOf(if (enabled) 1 else 0)) }
    }

    fun sendConfig(sampleRate: Int) {
        val bytes = ByteBuffer.allocate(4).putInt(sampleRate).array()
        activeWorkers().forEach { it.queuePacket(TYPE_CONFIG, bytes) }
    }

    private inner class ConnectedWorker(private val socket: BluetoothSocket, private val address: String) {
        @Volatile
        private var isRunning = true
        private val inStream = DataInputStream(BufferedInputStream(socket.inputStream, 16384))
        private val outStream = DataOutputStream(BufferedOutputStream(socket.outputStream, 16384))

        // Buffer capacity 30 packets (~600ms headroom to smoothly absorb RFCOMM jitter without dropping frames)
        private val sendQueue = OutgoingPacketQueue(30, 15) { it == TYPE_AUDIO || it == TYPE_AUDIO_ADPCM }

        fun setLowLatency(enabled: Boolean) {
            sendQueue.maxAudioPackets = if (enabled) 6 else 15
        }

        private var readJob: Job? = null
        private var writeJob: Job? = null
        private var keepAliveJob: Job? = null

        @Volatile
        private var lastSendTime = System.currentTimeMillis()

        fun start() {
            setLowLatency(lowLatencyEnabled)
            // Dedicated Single-Threaded Write Loop
            writeJob = scope.launch {
                try {
                    while (isActive && isRunning) {
                        val (type, payload) = sendQueue.take()
                        outStream.writeByte(MAGIC_BYTE.toInt())
                        outStream.writeByte(type.toInt())
                        outStream.writeShort(payload.size)
                        if (payload.isNotEmpty()) {
                            outStream.write(payload)
                        }
                        outStream.flush()
                        lastSendTime = System.currentTimeMillis()
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "Writer loop ended: ${e.message}")
                    onDisconnectOccurred()
                }
            }

            // Keep-Alive Heartbeat Job (prevents Bluetooth controller sleep and random cutouts)
            keepAliveJob = scope.launch {
                while (isActive && isRunning) {
                    delay(3000)
                    if (System.currentTimeMillis() - lastSendTime > 2500) {
                        queuePacket(TYPE_PING, ByteArray(0))
                    }
                }
            }

            // Dedicated Single-Threaded Read Loop
            readJob = scope.launch {
                try {
                    while (isActive && isRunning) {
                        // Scan for Magic Byte with EOF detection
                        var b = inStream.read()
                        if (b == -1) {
                            Log.d(TAG, "EOF received from remote peer")
                            break
                        }
                        while (b != MAGIC_BYTE.toInt()) {
                            b = inStream.read()
                            if (b == -1) break
                        }
                        if (b == -1) break

                        // Read Type
                        val type = inStream.readByte()

                        // Read Payload length (short)
                        val length = inStream.readShort().toInt() and 0xFFFF
                        if (length > 32768) {
                            Log.w(TAG, "Excessive payload length $length, resyncing...")
                            continue
                        }

                        val payload = ByteArray(length)
                        if (length > 0) {
                            inStream.readFully(payload, 0, length)
                        }

                        // Safely dispatch to listener
                        try {
                            onPacketReceived(address, type, payload)
                        } catch (t: Throwable) {
                            Log.e(TAG, "Error in onPacketReceived callback: ${t.message}")
                        }
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "Reader loop ended: ${e.message}")
                } finally {
                    onDisconnectOccurred()
                }
            }
        }

        fun queuePacket(type: Byte, payload: ByteArray) {
            if (!isRunning) return
            // Soft ceiling to prevent bufferbloat while allowing natural Bluetooth bursts:
            // Only prune if queue backs up beyond 15 packets (~300ms)
            if (!sendQueue.offer(type, payload)) {
                Log.w(TAG, "Bluetooth queue full of control packets; dropped packet type=$type")
            }
        }

        fun clearAudioQueue() {
            sendQueue.clearAudio()
        }

        private fun onDisconnectOccurred() {
            if (!isRunning) return
            isRunning = false
            cancel()
            onWorkerDisconnected(address, this)
        }

        fun cancel() {
            isRunning = false
            readJob?.cancel()
            writeJob?.cancel()
            keepAliveJob?.cancel()
            sendQueue.clear()
            try {
                socket.close()
            } catch (e: Exception) {
                Log.e(TAG, "Error closing socket: ${e.message}")
            }
        }
    }

    fun cleanup() {
        stopDiscovery()
        disconnect()
        if (isReceiverRegistered) {
            try {
                context.unregisterReceiver(discoveryReceiver)
            } catch (e: Exception) {
                Log.e(TAG, "Error unregistering receiver: ${e.message}")
            }
            isReceiverRegistered = false
        }
    }
}
