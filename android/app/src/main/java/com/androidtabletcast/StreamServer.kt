package com.androidtabletcast

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom

interface StreamEvents {
    fun onAuthenticated(preset: Int)
    fun onClientClosed()
    fun onKeyframeRequested()
    fun onPreset(preset: Int)
    fun onInput(packet: Packet)
    fun onTrustRequested(pcId: ByteArray, name: String)
    fun onStatus(text: String)
}

class StreamServer(
    context: Context,
    private val trustStore: TrustStore,
    private val events: StreamEvents,
) {
    private val appContext = context.applicationContext
    private val lockout = AuthLockout()
    private val writeLock = Any()
    private val random = SecureRandom()
    private var serverSocket: ServerSocket? = null
    private var clientSocket: Socket? = null
    private var beaconSocket: DatagramSocket? = null
    private var acceptThread: Thread? = null
    private var beaconThread: Thread? = null
    private var clientThread: Thread? = null
    private var nsdManager: NsdManager? = null
    private var registration: NsdManager.RegistrationListener? = null

    @Volatile
    private var running = false

    @Volatile
    var pin: String = ""
        private set

    @Volatile
    var trustMode: Boolean = false

    @Volatile
    private var authed = false

    @Volatile
    var deviceWidth: Int = 0

    @Volatile
    var deviceHeight: Int = 0

    private var tabletId: ByteArray = ByteArray(ID_BYTES)
    private var nonce: ByteArray = ByteArray(NONCE_BYTES)
    @Volatile
    private var pendingPcId: ByteArray? = null

    fun start(pin: String, tabletId: ByteArray, width: Int, height: Int) {
        stop()
        this.pin = pin
        this.tabletId = tabletId
        deviceWidth = width
        deviceHeight = height
        running = true
        val socket = ServerSocket()
        socket.reuseAddress = true
        socket.bind(InetSocketAddress(TCP_PORT))
        serverSocket = socket
        registerNsd()
        acceptThread = Thread({ acceptLoop(socket) }, "atcast-accept").also { it.start() }
        beaconSocket = DatagramSocket()
        beaconThread = Thread({ beaconLoop() }, "atcast-beacon").also { it.start() }
        events.onStatus("待受中")
    }

    fun stop() {
        running = false
        authed = false
        pendingPcId = null
        try {
            clientSocket?.close()
        } catch (_: Exception) {
        }
        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }
        try {
            beaconSocket?.close()
        } catch (_: Exception) {
        }
        unregisterNsd()
        acceptThread?.join(300)
        beaconThread?.join(300)
        clientThread?.join(300)
        clientSocket = null
        serverSocket = null
        beaconSocket = null
    }

    fun send(packet: Packet) {
        if (!authed && packet !is Packet.Hello && packet !is Packet.AuthResult && packet !is Packet.TrustGrant && packet !is Packet.Pong) {
            return
        }
        val bytes = Protocol.encode(packet)
        val socket = clientSocket ?: return
        synchronized(writeLock) {
            try {
                socket.getOutputStream().write(bytes)
                socket.getOutputStream().flush()
            } catch (_: Exception) {
                // 切断は読み取り側が片づける。
            }
        }
    }

    fun grantIfPending(pcId: ByteArray, name: String): Boolean {
        val pending = pendingPcId ?: return false
        if (!pending.contentEquals(pcId)) {
            return false
        }
        val token = trustStore.grant(pcId, name)
        send(Packet.TrustGrant(tabletId, token))
        pendingPcId = null
        return true
    }

    private fun acceptLoop(socket: ServerSocket) {
        while (running) {
            val client = try {
                socket.accept()
            } catch (_: Exception) {
                break
            }
            if (clientSocket != null) {
                client.close()
                continue
            }
            clientSocket = client
            clientThread = Thread({ handle(client) }, "atcast-client").also { it.start() }
        }
    }

    private fun handle(socket: Socket) {
        val address = socket.inetAddress.hostAddress ?: "?"
        try {
            socket.tcpNoDelay = true
            socket.keepAlive = true
            nonce = ByteArray(NONCE_BYTES)
            random.nextBytes(nonce)
            authed = false
            writeHello(socket)
            val reader = PacketReader(socket.getInputStream())
            var attempts = 0
            while (running) {
                when (val packet = reader.read()) {
                    is Packet.AuthPin -> {
                        // 信頼で通ったあとに残った PIN を送られても、失敗として上書きしない。
                        if (!authed && !onPin(socket, address, packet.pin)) {
                            break
                        }
                    }
                    is Packet.AuthTrust -> {
                        if (!authed) {
                            onTrust(address, packet)
                        }
                    }
                    is Packet.TrustRequest -> {
                        if (authed) {
                            pendingPcId = packet.pcId
                            events.onTrustRequested(packet.pcId, packet.name)
                        }
                    }
                    is Packet.InputTouch, is Packet.InputKey, is Packet.InputScroll -> {
                        if (authed) {
                            events.onInput(packet)
                        }
                    }
                    is Packet.StreamConfig -> {
                        if (authed) {
                            events.onPreset(packet.preset)
                        }
                    }
                    Packet.RequestKeyframe -> {
                        if (authed) {
                            events.onKeyframeRequested()
                        }
                    }
                    is Packet.Ping -> send(Packet.Pong(packet.clientUnixMs, System.currentTimeMillis()))
                    else -> Unit
                }
                if (!authed) {
                    attempts += 1
                    if (attempts >= 4) {
                        break
                    }
                }
            }
        } catch (_: Exception) {
            // 切断と不正フレームは同じ後処理に落とす。
        } finally {
            authed = false
            pendingPcId = null
            try {
                socket.close()
            } catch (_: Exception) {
            }
            if (clientSocket === socket) {
                clientSocket = null
            }
            events.onClientClosed()
            events.onStatus("待受中")
        }
    }

    private fun writeHello(socket: Socket) {
        trustMode = Preferences(appContext).trustMode
        val hello = Packet.Hello(
            version = PROTOCOL_VERSION,
            tabletId = tabletId,
            trustMode = trustMode,
            nonce = nonce,
            videoWidth = 0,
            videoHeight = 0,
            deviceWidth = deviceWidth,
            deviceHeight = deviceHeight,
            name = Preferences(appContext).deviceName,
        )
        val bytes = Protocol.encode(hello)
        synchronized(writeLock) {
            socket.getOutputStream().write(bytes)
            socket.getOutputStream().flush()
        }
    }

    private fun onPin(socket: Socket, address: String, pinValue: String): Boolean {
        val now = System.currentTimeMillis()
        if (lockout.isLocked(address, now)) {
            send(Packet.AuthResult(AUTH_LOCKED))
            socket.close()
            return false
        }
        if (pinValue != pin) {
            lockout.recordFailure(address, now)
            send(Packet.AuthResult(AUTH_BAD_PIN))
            return true
        }
        lockout.clear(address)
        becomeAuthenticated()
        return true
    }

    private fun onTrust(address: String, packet: Packet.AuthTrust) {
        if (!trustMode) {
            send(Packet.AuthResult(AUTH_TRUST_MODE_OFF))
            return
        }
        if (!trustStore.verify(packet.pcId, nonce, packet.hmac)) {
            send(Packet.AuthResult(AUTH_UNTRUSTED))
            return
        }
        lockout.clear(address)
        becomeAuthenticated()
    }

    private fun becomeAuthenticated() {
        authed = true
        send(Packet.AuthResult(AUTH_OK))
        events.onStatus("接続中")
        events.onAuthenticated(PRESET_HIGH_QUALITY)
    }

    private fun beaconLoop() {
        while (running) {
            try {
                val payload = Beacon.encode(TCP_PORT, tabletId, Preferences(appContext).deviceName)
                val targets = broadcastAddresses()
                for (target in targets) {
                    val packet = DatagramPacket(payload, payload.size, target, UDP_BEACON_PORT)
                    beaconSocket?.send(packet)
                }
            } catch (_: Exception) {
            }
            try {
                Thread.sleep(1000)
            } catch (_: InterruptedException) {
                break
            }
        }
    }

    private fun broadcastAddresses(): List<InetAddress> {
        val found = ArrayList<InetAddress>()
        val interfaces = NetworkInterface.getNetworkInterfaces() ?: return listOf(InetAddress.getByName("255.255.255.255"))
        while (interfaces.hasMoreElements()) {
            val network = interfaces.nextElement()
            if (!network.isUp || network.isLoopback) {
                continue
            }
            for (address in network.interfaceAddresses) {
                val broadcast = address.broadcast
                if (broadcast != null) {
                    found.add(broadcast)
                }
            }
        }
        if (found.isEmpty()) {
            found.add(InetAddress.getByName("255.255.255.255"))
        }
        return found
    }

    private fun registerNsd() {
        val manager = appContext.getSystemService(NsdManager::class.java) ?: return
        val info = NsdServiceInfo()
        info.serviceName = utf8Trunc(Preferences(appContext).deviceName, 40)
        info.serviceType = "_atcast._tcp."
        info.port = TCP_PORT
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) = Unit
            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit
            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit
        }
        nsdManager = manager
        registration = listener
        try {
            manager.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (_: Exception) {
        }
    }

    private fun unregisterNsd() {
        val manager = nsdManager
        val listener = registration
        nsdManager = null
        registration = null
        if (manager != null && listener != null) {
            try {
                manager.unregisterService(listener)
            } catch (_: Exception) {
            }
        }
    }
}

fun localIpv4(): List<String> {
    val addresses = ArrayList<String>()
    val interfaces = NetworkInterface.getNetworkInterfaces() ?: return addresses
    while (interfaces.hasMoreElements()) {
        val network = interfaces.nextElement()
        if (!network.isUp || network.isLoopback) {
            continue
        }
        val inet = network.inetAddresses
        while (inet.hasMoreElements()) {
            val address = inet.nextElement()
            if (address is Inet4Address && !address.isLoopbackAddress) {
                addresses.add(address.hostAddress ?: continue)
            }
        }
    }
    return addresses
}

fun newPin(): String {
    val value = SecureRandom().nextInt(900000) + 100000
    return value.toString()
}
