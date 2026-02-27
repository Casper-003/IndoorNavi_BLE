package com.example.indoornavi

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

// 1. 数据结构更新：分离出原始 RSSI 和 滤波后的 RSSI
data class BeaconDevice(
    val macAddress: String,
    val name: String?,
    var rawRssi: Int,          // 物理层原始信号（有波动）
    var smoothedRssi: Int,     // 经过论文移动平均滤波后的平滑信号
    val lastSeen: Long,
    val filter: MovingAverageFilter = MovingAverageFilter(windowSize = 5) // 挂载滤波器
)

@SuppressLint("MissingPermission")
class BleScanner(context: Context) {

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
            val name = device.name

            val existingDevice = scannedDevicesMap[mac]

            if (existingDevice != null) {
                // 如果是列表里已有的设备，更新原始值，并重新计算滤波平均值
                existingDevice.rawRssi = rssi
                existingDevice.smoothedRssi = existingDevice.filter.addAndGetAverage(rssi)
            } else {
                // 如果是新发现的设备，给它配一个新的滤波器
                val newFilter = MovingAverageFilter()
                val initialSmoothedRssi = newFilter.addAndGetAverage(rssi)

                val beacon = BeaconDevice(
                    macAddress = mac,
                    name = name,
                    rawRssi = rssi,
                    smoothedRssi = initialSmoothedRssi, // 使用平滑后的初始值
                    lastSeen = System.currentTimeMillis(),
                    filter = newFilter
                )
                scannedDevicesMap[mac] = beacon
            }

            // 2. 排序逻辑更新：使用滤波后的 smoothedRssi 进行排序，UI 不再乱跳
            _scannedDevicesFlow.value = scannedDevicesMap.values.sortedWith(
                compareByDescending<BeaconDevice> { !it.name.isNullOrEmpty() }
                    .thenByDescending { it.smoothedRssi }
            )
        }
    }

    fun startScan() {
        if (scanner == null) return
        scannedDevicesMap.clear()
        scanner.startScan(scanCallback)
    }

    // 刚才可能被你不小心删掉的停止方法，补回来！
    fun stopScan() {
        scanner?.stopScan(scanCallback)
    }

    // 用于下拉刷新的清空方法
    fun clearDevices() {
        scannedDevicesMap.clear()
        _scannedDevicesFlow.value = emptyList()
    }
} // 这是 BleScanner 类的最后一个右大括号
