package com.cgluWxh.freeclipcomp

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import com.cgluWxh.freeclipcomp.ui.theme.FreeclipCompanionTheme

const val TAG = "FreeClipScanner"
const val DEVICE_NAME = "Huawei FreeClip"

class MainActivity : ComponentActivity() {
    private var deviceMac: String? = null
    
    private lateinit var bluetoothManager: BluetoothManager
    private lateinit var bluetoothAdapter: BluetoothAdapter
    private lateinit var bluetoothLeScanner: BluetoothLeScanner
    private lateinit var sharedPref: SharedPreferences
    
    private var scanning = false
    private var prompting = false
    private val handler = Handler(Looper.getMainLooper())
    
    // 存储扫描到的设备信息
    private val deviceInfo = mutableStateOf("Scanning for devices...")
    
    // 蓝牙扫描回调
    private val leScanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            super.onScanResult(callbackType, result)
            val device = result.device
            val deviceName = device.name ?: "Unknown"
            
            if (
                (deviceMac == null && deviceName.contains(DEVICE_NAME, ignoreCase = true))
                || (device.address == deviceMac)
            ) {
                Log.d(TAG, "Found FreeClip device: ${device.address}")
                
                val scanRecord = result.scanRecord
                val bytes = scanRecord?.bytes ?: byteArrayOf()
                
                val hexString = bytes.joinToString(" ") { "%02x".format(it) }
                Log.d(TAG, "Received Data: $hexString")
                val caseBattery = bytes[20].toInt()
                val leftBattery = bytes[21].toInt()
                val rightBattery = bytes[22].toInt()

                if (caseBattery == 0 && leftBattery == 0 && rightBattery == 0) return

                val getBatteryStr = {it: Int ->
                    "${if (it < 0) it + 128 else it}% ${if (it < 0) "充电中" else ""}"
                }

                if (deviceMac == null && !prompting) {
                    prompting = true
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("绑定设备")
                        .setMessage("要绑定 ${device.address} 作为您的设备吗？您可以长按主界面任意位置来取消绑定。")
                        .setPositiveButton("好") { dialog, _ ->
                            val editor = sharedPref.edit()
                            editor.putString("deviceMac", device.address)
                            editor.apply()
                            deviceMac = device.address
                            dialog.dismiss()
                        }
                        .setNegativeButton("不了") { dialog, _ ->
                            dialog.dismiss()
                        }
                        .setCancelable(false) // 防止点击外部取消
                        .setOnDismissListener { prompting = false }
                        .create()
                        .show()
                }

                val info = """
                    设备名称: $deviceName
                    设备地址: ${device.address}
                    充电仓电量: ${getBatteryStr(caseBattery)}
                    左耳机电量: ${getBatteryStr(leftBattery)}
                    右耳机电量: ${getBatteryStr(rightBattery)}
                    调试数据: $hexString
                """.trimIndent()
                
                deviceInfo.value = info
                Log.d(TAG, info)
            }
        }
        
        override fun onScanFailed(errorCode: Int) {
            super.onScanFailed(errorCode)
            deviceInfo.value = "扫描蓝牙设备失败: $errorCode"
            Log.e(TAG, "BLE Scan failed with error $errorCode")
        }
    }
    
    // 权限请求
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allPermissionsGranted = permissions.all { it.value }
        if (allPermissionsGranted) {
            startScan()
        } else {
            deviceInfo.value = "请确保已授权所有权限"
            Log.e(TAG, "Required permissions not granted")
        }
    }
    
    @OptIn(ExperimentalFoundationApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 初始化蓝牙
        bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter
        bluetoothLeScanner = bluetoothAdapter.bluetoothLeScanner
        
        setContent {
            FreeclipCompanionTheme {
                Scaffold(
                    modifier = Modifier.fillMaxSize()
                   .combinedClickable(
                       onClick = {
                           if (scanning) return@combinedClickable
                           checkPermissionsAndStartScan()
                       },
                       onLongClick = {
                           if (deviceMac == null) return@combinedClickable
                           AlertDialog.Builder(this@MainActivity)
                            .setTitle("取消绑定设备")
                            .setMessage("要取消绑定 $deviceMac 吗？")
                            .setPositiveButton("好") { dialog, _ ->
                                val editor = sharedPref.edit()
                                editor.putString("deviceMac", null)
                                editor.apply()
                                deviceMac = null
                                dialog.dismiss()
                            }
                            .setNegativeButton("不了") { dialog, _ ->
                                dialog.dismiss()
                            }
                            .setCancelable(false) // 防止点击外部取消
                            .create()
                            .show()
                       }
                   )
                ) { innerPadding ->
                    DeviceInfoDisplay(
                        info = deviceInfo.value,
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }

        sharedPref = getSharedPreferences("freeclip_companion", MODE_PRIVATE)
        deviceMac = sharedPref.getString("deviceMac", null)

        checkPermissionsAndStartScan()
    }
    
    private fun checkPermissionsAndStartScan() {
        val requiredPermissions = mutableListOf(
            Manifest.permission.BLUETOOTH,
            Manifest.permission.BLUETOOTH_ADMIN
        )
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            requiredPermissions.add(Manifest.permission.BLUETOOTH_SCAN)
            requiredPermissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            requiredPermissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        
        val permissionsToRequest = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }.toTypedArray()
        
        if (permissionsToRequest.isEmpty()) {
            startScan()
        } else {
            requestPermissionLauncher.launch(permissionsToRequest)
        }
    }
    
    @SuppressLint("MissingPermission")
    private fun startScan() {
        if (!bluetoothAdapter.isEnabled) {
            deviceInfo.value = "请先打开蓝牙后点击任意位置重试"
            return
        }


        if (scanning) return
        
        scanning = true
        deviceInfo.value = "正在搜索 $DEVICE_NAME..."
        Log.d(TAG, "Starting BLE scan")
        
        // 扫描10秒后自动停止
        handler.postDelayed({
            stopScan()
        }, 30000)
        
        bluetoothLeScanner.startScan(leScanCallback)
    }
    
    @SuppressLint("MissingPermission")
    private fun stopScan() {
        if (deviceInfo.value == "正在搜索 $DEVICE_NAME...") {
            deviceInfo.value = "未找到 $DEVICE_NAME，点击任意位置重试"
            return
        }
        deviceInfo.value += "\n数据更新已停止，点击任意位置来更新"
        if (!scanning) return
        
        bluetoothLeScanner.stopScan(leScanCallback)
        scanning = false
        Log.d(TAG, "BLE scan stopped")
    }
    
    override fun onDestroy() {
        stopScan()
        super.onDestroy()
    }

    @Composable
    fun DeviceInfoDisplay(info: String, modifier: Modifier = Modifier) {
        Box(modifier = Modifier
            .fillMaxSize()
            .wrapContentSize(Alignment.Center)
        ) {
            Text(
                text = info,
                modifier = modifier
            )
        }
    }
}
