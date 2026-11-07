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
    private val onPacketReceived: (type: Byte, payload: ByteArray) -> Unit
) {
    companion object {
        private const val TAG = "BluetoothAudioService"
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
    }

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    val bluetoothAdapter: BluetoothAdapter? = bluetoothManager?.adapter

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _discoveredDevices = MutableStateFlow<List<BluetoothDeviceInfo>>(emptyList())
    val discoveredDevices: StateFlow<List<BluetoothDeviceInfo>> = _discoveredDevices.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val scope = CoroutineScope(Dispatchers.IO)
    private var acceptJob: Job? = null
    private var connectJob: Job? = null
    private var connectedWorker: ConnectedWorker? = null
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

    @SuppressLint("MissingPermission")
    fun startListening() {
        val adapter = bluetoothAdapter ?: return
        stopListening()
        disconnectCurrentConnection()

        val localName = try {
            adapter.name ?: "This Phone"
        } catch (e: Exception) {
            "This Phone"
        }

        _connectionState.value = ConnectionState.Listening(localName)

        acceptJob = scope.launch {
            try {
                serverSocket = adapter.listenUsingRfcommWithServiceRecord(SERVICE_NAME, SPP_UUID)
                Log.d(TAG, "Listening on RFCOMM for incoming connection...")
                val socket = serverSocket?.accept()
                if (socket != null) {
                    serverSocket?.close()
                    serverSocket = null
                    val remoteDev = socket.remoteDevice
                    val remoteName = try {
                        remoteDev.name ?: remoteDev.address
                    } catch (e: Exception) {
                        remoteDev.address
                    }
                    withContext(Dispatchers.Main) {
                        _connectionState.value = ConnectionState.Connected(remoteName, remoteDev.address)
                    }
                    connectedWorker = ConnectedWorker(socket)
                    connectedWorker?.start()
                }
            } catch (e: IOException) {
                Log.d(TAG, "Server socket closed or interrupted: ${e.message}")
                if (_connectionState.value is ConnectionState.Listening) {
                    withContext(Dispatchers.Main) {
                        _connectionState.value = ConnectionState.Disconnected
                    }
                }
            }
        }
    }

    fun stopListening() {
        acceptJob?.cancel()
        acceptJob = null
        try {
            serverSocket?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing server socket: ${e.message}")
        }
        serverSocket = null
    }

    @SuppressLint("MissingPermission")
    fun connectToDevice(deviceAddress: String, deviceName: String? = null) {
        val adapter = bluetoothAdapter ?: return
        stopListening()
        disconnectCurrentConnection()

        val name = deviceName ?: deviceAddress
        _connectionState.value = ConnectionState.Connecting(name, deviceAddress)

        connectJob = scope.launch {
            try {
                adapter.cancelDiscovery()
                val device = adapter.getRemoteDevice(deviceAddress)
                var socket: BluetoothSocket? = null

                // 1. Try standard SPP UUID
                try {
                    socket = device.createRfcommSocketToServiceRecord(SPP_UUID)
                    socket.connect()
                } catch (e: IOException) {
                    Log.w(TAG, "Standard RFCOMM failed, attempting reflection fallback: ${e.message}")
                    socket?.close()
                    // 2. Reflection fallback for non-standard device Bluetooth stacks
                    val fallbackMethod = device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
                    socket = fallbackMethod.invoke(device, 1) as BluetoothSocket
                    socket.connect()
                }

                if (socket != null && socket.isConnected) {
                    withContext(Dispatchers.Main) {
                        _connectionState.value = ConnectionState.Connected(name, deviceAddress)
                    }
                    connectedWorker = ConnectedWorker(socket)
                    connectedWorker?.start()
                } else {
                    throw IOException("Socket could not connect")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Connection failed: ${e.message}")
                withContext(Dispatchers.Main) {
                    _connectionState.value = ConnectionState.Disconnected
                }
            }
        }
    }

    fun disconnect() {
        stopListening()
        disconnectCurrentConnection()
        _connectionState.value = ConnectionState.Disconnected
    }

    private fun disconnectCurrentConnection() {
        connectJob?.cancel()
        connectJob = null
        connectedWorker?.cancel()
        connectedWorker = null
    }

    fun sendAudio(data: ByteArray) {
        if (connectedWorker == null || data.isEmpty()) return
        try {
            val compressed = com.example.audio.AdpcmCodec.encode(data)
            connectedWorker?.queuePacket(TYPE_AUDIO_ADPCM, compressed)
        } catch (e: Exception) {
            connectedWorker?.queuePacket(TYPE_AUDIO, data)
        }
    }

    fun clearAudioQueue() {
        connectedWorker?.clearAudioQueue()
    }

    fun sendPttStart() {
        connectedWorker?.queuePacket(TYPE_PTT_START, ByteArray(0))
    }

    fun sendPttEnd() {
        connectedWorker?.queuePacket(TYPE_PTT_END, ByteArray(0))
    }

    fun sendTextMessage(text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        connectedWorker?.queuePacket(TYPE_TEXT, bytes)
    }

    fun sendConfig(sampleRate: Int) {
        val bytes = ByteBuffer.allocate(4).putInt(sampleRate).array()
        connectedWorker?.queuePacket(TYPE_CONFIG, bytes)
    }

    private inner class ConnectedWorker(private val socket: BluetoothSocket) {
        @Volatile
        private var isRunning = true
        private val inStream = DataInputStream(BufferedInputStream(socket.inputStream, 16384))
        private val outStream = DataOutputStream(BufferedOutputStream(socket.outputStream, 16384))

        // Low-latency FIFO queue (capacity 15 packets = ~300ms ceiling to prevent bufferbloat)
        private val sendQueue = LinkedBlockingQueue<Pair<Byte, ByteArray>>(15)

        private var readJob: Job? = null
        private var writeJob: Job? = null
        private var keepAliveJob: Job? = null

        @Volatile
        private var lastSendTime = System.currentTimeMillis()

        fun start() {
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
                            onPacketReceived(type, payload)
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
            // Real-time zero-lag enforcement:
            // Never let audio buffer behind. If more than 3 audio packets (~60ms) are pending,
            // drop older packets so the transmitted stream is always strictly real-time!
            if (type == TYPE_AUDIO || type == TYPE_AUDIO_ADPCM) {
                while (sendQueue.size >= 4) {
                    sendQueue.poll()
                }
            }
            sendQueue.offer(Pair(type, payload))
        }

        fun clearAudioQueue() {
            val iterator = sendQueue.iterator()
            while (iterator.hasNext()) {
                val item = iterator.next()
                if (item.first == TYPE_AUDIO || item.first == TYPE_AUDIO_ADPCM) {
                    iterator.remove()
                }
            }
        }

        private fun onDisconnectOccurred() {
            if (!isRunning) return
            isRunning = false
            cancel()
            scope.launch(Dispatchers.Main) {
                if (_connectionState.value is ConnectionState.Connected) {
                    _connectionState.value = ConnectionState.Disconnected
                }
            }
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
