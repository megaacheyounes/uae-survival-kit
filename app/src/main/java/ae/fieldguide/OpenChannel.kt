package ae.fieldguide

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import androidx.compose.runtime.*
import java.util.UUID

@SuppressLint("MissingPermission")
@Suppress("DEPRECATION")
internal class OpenChannel(context: Context) {
    private val manager = context.getSystemService(BluetoothManager::class.java)
    private val adapter get() = manager?.adapter
    private val main = Handler(Looper.getMainLooper())
    private val wire = OpenChannelWire()
    val nickname get() = wire.nickname
    data class Line(val from: String, val body: String, val mine: Boolean)
    val lines = mutableStateListOf<Line>()
    var links by mutableIntStateOf(0); private set
    var status by mutableStateOf(""); private set
    var running by mutableStateOf(false); private set
    private var server: BluetoothGattServer? = null
    private val service = UUID.fromString("f47b5e2d-4a9e-4c5a-9b3f-8e1d2c3a4b5c")
    private val characteristic = UUID.fromString("a1b2c3d4-e5f6-4a5b-8c9d-0e1f2a3b4c5d")
    private val subscription = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    private val outgoing = BluetoothGattCharacteristic(characteristic,
        BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
        BluetoothGattCharacteristic.PERMISSION_WRITE)
    private class Client(val gatt: BluetoothGatt) {
        var tx: BluetoothGattCharacteristic? = null
        var limit = 20
        var ready = false
        val queue = ArrayDeque<ByteArray>()
        var writing = false
    }
    private val clients = mutableMapOf<String, Client>()
    private val subscribers = mutableMapOf<String, BluetoothDevice>()
    private val serverLimits = mutableMapOf<String, Int>()
    private val notices = ArrayDeque<Pair<BluetoothDevice,ByteArray>>()
    private var notifying: BluetoothDevice? = null
    private val app = context.applicationContext

    private fun update() {
        links = (subscribers.keys + clients.filterValues { it.ready }.keys).distinct().size
    }
    private fun log(from: String, text: String, mine: Boolean = false) {
        lines.add(Line(from, text, mine))
        if (lines.size > 100) lines.removeAt(0)
    }
    fun send(text: String): Boolean {
        if (text.isBlank() || text.toByteArray().size > OpenChannelWire.MAX_TEXT_BYTES) return false
        if (!broadcast(wire.message(text))) { status = "No nearby link is ready to send."; return false }
        status = ""
        log("You", text, true)
        return true
    }
    private fun incoming(address: String, bytes: ByteArray) {
        if (!running || bytes.size > 520) return
        val item = wire.receive(bytes) ?: return
        item.text?.let { log(item.sender, it) }
        item.relay?.let { broadcast(it, address) }
    }
    private fun broadcast(bytes: ByteArray, except: String? = null): Boolean {
        var queued = false
        for ((address,client) in clients.toMap()) {
            if (address != except && client.ready && bytes.size <= client.limit && client.queue.size < 16) {
                client.queue.addLast(bytes); writeNext(client); queued = true
            }
        }
        for ((address,device) in subscribers) {
            if (address != except && bytes.size <= (serverLimits[address] ?: 20) && notices.size < 64) {
                notices.addLast(device to bytes); queued = true
            }
        }
        notifyNext()
        return queued
    }
    private fun writeNext(client: Client) {
        val tx = client.tx ?: return
        if (client.writing || client.queue.isEmpty()) return
        tx.value = client.queue.removeFirst()
        tx.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        client.writing = client.gatt.writeCharacteristic(tx)
        if (!client.writing) drop(client.gatt)
    }
    private fun notifyNext() {
        while (notifying == null && notices.isNotEmpty()) {
            val (device,bytes) = notices.removeFirst()
            if (device.address !in subscribers) continue
            outgoing.value = bytes
            if (server?.notifyCharacteristicChanged(device, outgoing, false) == true) notifying = device
        }
    }
    private fun drop(gatt: BluetoothGatt) {
        if (clients[gatt.device.address]?.gatt === gatt) clients.remove(gatt.device.address)
        runCatching { gatt.disconnect(); gatt.close() }; update()
    }
    private fun handle(action: () -> Unit) { main.post { if (running) runCatching(action).onFailure { status = "Bluetooth connection interrupted." } } }
    private val clientEvents = object: BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, result: Int, state: Int) = handle {
            if (result != 0 || state == BluetoothProfile.STATE_DISCONNECTED) drop(g)
            else if (state == BluetoothProfile.STATE_CONNECTED && !g.requestMtu(517)) drop(g)
        }
        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, result: Int) = handle {
            val c = clients[g.device.address] ?: return@handle
            if (result != 0 || mtu < 190) drop(g) else { c.limit = mtu - 3; if (!g.discoverServices()) drop(g) }
        }
        override fun onServicesDiscovered(g: BluetoothGatt, result: Int) = handle {
            val c = clients[g.device.address] ?: return@handle
            val tx = g.getService(service)?.getCharacteristic(characteristic)
            val descriptor = tx?.getDescriptor(subscription)
            if (result != 0 || descriptor == null || !g.setCharacteristicNotification(tx,true)) drop(g)
            else { c.tx = tx; descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE; if (!g.writeDescriptor(descriptor)) drop(g) }
        }
        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, result: Int) = handle {
            val c = clients[g.device.address] ?: return@handle
            if (result != 0) drop(g) else { c.ready = true; update(); broadcast(wire.announce()) }
        }
        override fun onCharacteristicWrite(g: BluetoothGatt, tx: BluetoothGattCharacteristic, result: Int) = handle {
            val c = clients[g.device.address] ?: return@handle
            if (result != 0) drop(g) else { c.writing = false; writeNext(c) }
        }
        override fun onCharacteristicChanged(g: BluetoothGatt, tx: BluetoothGattCharacteristic) {
            val data = tx.value.copyOf(); handle { incoming(g.device.address,data) }
        }
    }
    private val serverEvents = object: BluetoothGattServerCallback() {
        override fun onServiceAdded(result: Int, added: BluetoothGattService) = handle {
            if (result == 0) advertise() else status = "This phone can scan, but cannot advertise the channel."
        }
        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) = handle { serverLimits[device.address] = mtu - 3 }
        override fun onConnectionStateChange(device: BluetoothDevice, result: Int, state: Int) = handle {
            if (state == BluetoothProfile.STATE_DISCONNECTED) {
                subscribers.remove(device.address); serverLimits.remove(device.address)
                if (notifying == device) notifying = null
                notifyNext(); update()
            }
        }
        override fun onDescriptorWriteRequest(device: BluetoothDevice, request: Int, d: BluetoothGattDescriptor, prepared: Boolean, respond: Boolean, offset: Int, value: ByteArray) = handle {
            val valid = d.uuid == subscription && !prepared && offset == 0
            if (respond) server?.sendResponse(device,request,if(valid) 0 else BluetoothGatt.GATT_FAILURE,0,null)
            if (valid) {
                if (value.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)) subscribers[device.address] = device
                else subscribers.remove(device.address)
                update(); broadcast(wire.announce())
            }
        }
        override fun onCharacteristicWriteRequest(device: BluetoothDevice, request: Int, tx: BluetoothGattCharacteristic, prepared: Boolean, respond: Boolean, offset: Int, value: ByteArray) = handle {
            val valid = tx.uuid == characteristic && !prepared && offset == 0 && value.size <= 520
            if (respond) server?.sendResponse(device,request,if(valid) 0 else BluetoothGatt.GATT_FAILURE,0,null)
            if (valid) incoming(device.address,value)
        }
        override fun onNotificationSent(device: BluetoothDevice, result: Int) = handle { if (notifying == device) notifying = null; notifyNext() }
    }
    private val advertising = object: AdvertiseCallback() {
        override fun onStartFailure(errorCode: Int) = handle { status = "Listening for phones. Bluetooth advertising is unavailable." }
    }
    private fun advertise() {
        val settings = AdvertiseSettings.Builder().setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED).setConnectable(true).build()
        val uuid = ParcelUuid(service)
        adapter?.bluetoothLeAdvertiser?.startAdvertising(settings, AdvertiseData.Builder().addServiceUuid(uuid).build(),
            AdvertiseData.Builder().addServiceData(uuid,wire.id).build(),advertising)
    }
    private val scan = object: ScanCallback() {
        override fun onScanResult(type: Int, result: ScanResult) = handle {
            val d = result.device
            if (d.address !in clients && d.address !in subscribers && clients.size < 12) {
                val g = d.connectGatt(app,false,clientEvents,BluetoothDevice.TRANSPORT_LE) ?: return@handle
                clients[d.address] = Client(g)
                main.postDelayed({ if (clients[d.address]?.let { it.gatt === g && !it.ready } == true) drop(g) }, 18_000)
            }
        }
        override fun onScanFailed(errorCode: Int) = handle { status = "Bluetooth scan stopped. Leave and reopen the channel to retry." }
    }
    private val heartbeat = object: Runnable {
        override fun run() { if (running) { if (links > 0) broadcast(wire.announce()); main.postDelayed(this,10_000) } }
    }
    fun start() {
        if (running) return
        runCatching {
            require(adapter?.isEnabled == true)
            val scanner = requireNotNull(adapter?.bluetoothLeScanner)
            running = true
            server = manager.openGattServer(app,serverEvents)
            outgoing.addDescriptor(BluetoothGattDescriptor(subscription,BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE))
            server?.addService(BluetoothGattService(service,BluetoothGattService.SERVICE_TYPE_PRIMARY).apply { addCharacteristic(outgoing) })
            scanner.startScan(listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(service)).build()),
                ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),scan)
            main.postDelayed(heartbeat,10_000); status = ""
        }.onFailure { close(); status = "Turn on Bluetooth and allow nearby devices, then try again." }
    }
    fun close() {
        running = false; main.removeCallbacksAndMessages(null)
        runCatching { adapter?.bluetoothLeScanner?.stopScan(scan) }
        runCatching { adapter?.bluetoothLeAdvertiser?.stopAdvertising(advertising) }
        clients.values.toList().forEach { drop(it.gatt) }
        runCatching { server?.close() }; server = null
        subscribers.clear(); serverLimits.clear(); notices.clear(); notifying = null; update()
    }
}
