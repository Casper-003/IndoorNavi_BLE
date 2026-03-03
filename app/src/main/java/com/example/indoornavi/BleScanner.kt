package com.example.indoornavi

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class BeaconDevice(
    val macAddress: String,
    val name: String?,
    var rawRssi: Int,
    var smoothedRssi: Int,
    var lastSeen: Long,
    val filter: MovingAverageFilter = MovingAverageFilter(windowSize = 5)
)

@SuppressLint("MissingPermission")
class BleScanner(private val context: Context) {
    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter = bluetoothManager.adapter
    private val scanner = bluetoothAdapter?.bluetoothLeScanner

    private val scannedDevicesMap = mutableMapOf<String, BeaconDevice>()
    private val _scannedDevicesFlow = MutableStateFlow<List<BeaconDevice>>(emptyList())
    val scannedDevicesFlow: StateFlow<List<BeaconDevice>> = _scannedDevicesFlow.asStateFlow()

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            super.onScanResult(callbackType, result)

            val device = result.device
            val rssi = result.rssi
            val mac = device.address

            val existingDevice = scannedDevicesMap[mac]
            if (existingDevice != null) {
                existingDevice.rawRssi = rssi
                existingDevice.smoothedRssi = existingDevice.filter.addAndGetAverage(rssi)
                existingDevice.lastSeen = System.currentTimeMillis()
            } else {
                val newFilter = MovingAverageFilter()
                val initialSmoothedRssi = newFilter.addAndGetAverage(rssi)
                val beacon = BeaconDevice(mac, device.name, rssi, initialSmoothedRssi, System.currentTimeMillis(), newFilter)
                scannedDevicesMap[mac] = beacon
            }

            // 仅仅在这里加一个 .toList() 生成新列表推给 Compose，解决原本偶尔不刷新的问题，不改任何底层逻辑
            _scannedDevicesFlow.value = scannedDevicesMap.values.sortedWith(
                compareByDescending<BeaconDevice> { !it.name.isNullOrEmpty() }.thenByDescending { it.smoothedRssi }
            ).toList()
        }
    }

    fun startScan() {
        scannedDevicesMap.clear()
        scanner?.startScan(scanCallback)
    }

    fun stopScan() {
        scanner?.stopScan(scanCallback)
    }

    fun clearDevices() {
        scannedDevicesMap.clear()
        _scannedDevicesFlow.value = emptyList()
    }
}