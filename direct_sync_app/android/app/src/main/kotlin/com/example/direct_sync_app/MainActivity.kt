package com.example.direct_sync_app

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.*
import android.os.Bundle
import android.util.Log
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import kotlinx.coroutines.*
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : FlutterActivity() {
    private val CHANNEL = "com.example.direct_sync_app/camera"
    private val ACTION_USB_PERMISSION = "com.example.direct_sync_app.USB_PERMISSION"
    private val TAG = "CameraConnection"
    
    private var usbManager: UsbManager? = null
    private lateinit var ptpController: PtpController
    
    private val coroutineScope = CoroutineScope(Dispatchers.Main + Job())

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
            
            // Fix for deprecated getParcelableExtra
            fun getUsbDevice(intent: Intent): UsbDevice? {
                return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                }
            }
            
            when (intent?.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    val device = getUsbDevice(intent)
                    Log.i(TAG, "[$timestamp] USB device attached: ${device?.deviceName}, VendorID: 0x${device?.vendorId?.toString(16)}, ProductID: 0x${device?.productId?.toString(16)}")
                    // Canon device check
                    if (device?.vendorId == 0x04a9) {
                        Log.i(TAG, "[$timestamp] Canon camera detected")
                    }
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    val device = getUsbDevice(intent)
                    Log.i(TAG, "[$timestamp] USB device detached: ${device?.deviceName}")
                    // If our connected device was detached, release resources
                    if (ptpController.getConnectedDeviceInfo()?.get("deviceName") == device?.deviceName) {
                        ptpController.release()
                    }
                }
            }
        }
    }

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL).setMethodCallHandler { call, result ->
            when (call.method) {
                "initializeCamera" -> {
                    initializeCamera(result)
                }
                "getStorageIds" -> {
                    getStorageIds(result)
                }
                "getObjectHandles" -> {
                    val storageId = call.argument<Int>("storageId") ?: 0
                    getObjectHandles(storageId, result)
                }
                "getObjectInfo" -> {
                    val objectHandle = call.argument<Int>("objectHandle") ?: 0
                    getObjectInfo(objectHandle, result)
                }
                "getObject" -> {
                    val objectHandle = call.argument<Int>("objectHandle") ?: 0
                    getObject(objectHandle, result)
                }
                else -> {
                    result.notImplemented()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        ptpController = PtpController(this)
        
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
        Log.i(TAG, "[$timestamp] MainActivity created, PTP Controller initialized")
        
        val usbDeviceFilter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            // We'll handle USB permission differently to avoid crash
        }
        registerReceiver(usbReceiver, usbDeviceFilter)
    }

    private fun initializeCamera(result: MethodChannel.Result) {
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
        Log.d(TAG, "[$timestamp] Flutter requested camera initialization")
        
        // Check if already connected
        if (ptpController.isConnected()) {
            Log.i(TAG, "[$timestamp] Camera already connected")
            result.success(mapOf(
                "connected" to true,
                "message" to "Camera already connected"
            ))
            return
        }
        
        // Find compatible camera
        val (found, _) = ptpController.findCamera()
        if (found) {
            // Device found, attempt direct connection
            val device = usbManager?.deviceList?.values?.find { device ->
                device.vendorId == 0x04a9 // Canon vendor ID
            }
            
            if (device != null) {
                Log.d(TAG, "[$timestamp] Trying to connect to camera: ${device.deviceName}")
                
                // Check if permission is already granted
                if (usbManager?.hasPermission(device) == true) {
                    Log.i(TAG, "[$timestamp] Permission already granted, setting up device")
                    val success = ptpController.setupDevice(device)
                    if (success) {
                        Log.i(TAG, "[$timestamp] Device setup successful")
                        result.success(mapOf(
                            "connected" to true,
                            "message" to "Camera connected successfully"
                        ))
                    } else {
                        Log.e(TAG, "[$timestamp] Device setup failed")
                        result.error("SETUP_ERROR", "Failed to set up camera connection", null)
                    }
                } else {
                    // Create a one-time receiver for this specific permission request
                    val permissionReceiver = object : BroadcastReceiver() {
                        override fun onReceive(context: Context?, intent: Intent?) {
                            if (ACTION_USB_PERMISSION == intent?.action) {
                                val permDevice: UsbDevice? = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                                    intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                                } else {
                                    @Suppress("DEPRECATION")
                                    intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                                }
                                
                                if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                                    permDevice?.let { dev ->
                                        Log.i(TAG, "[$timestamp] USB permission granted for device: ${dev.deviceName}")
                                        val success = ptpController.setupDevice(dev)
                                        if (success) {
                                            Log.i(TAG, "[$timestamp] Device setup successful")
                                            result.success(mapOf(
                                                "connected" to true,
                                                "message" to "Camera connected successfully"
                                            ))
                                        } else {
                                            Log.e(TAG, "[$timestamp] Device setup failed")
                                            result.error("SETUP_ERROR", "Failed to set up camera connection", null)
                                        }
                                    }
                                } else {
                                    Log.w(TAG, "[$timestamp] USB permission denied for device: ${permDevice?.deviceName}")
                                    result.error("PERMISSION_DENIED", "Permission denied to access camera", null)
                                }
                                
                                // Unregister after use
                                context?.unregisterReceiver(this)
                            }
                        }
                    }
                    
                    // Register the receiver for this specific action
                    val permissionFilter = IntentFilter(ACTION_USB_PERMISSION)
                    registerReceiver(permissionReceiver, permissionFilter)
                    
                    // Request permission
                    Log.d(TAG, "[$timestamp] Requesting permission for camera: ${device.deviceName}")
                    val permissionIntent = PendingIntent.getBroadcast(
                        this,
                        0,
                        Intent(ACTION_USB_PERMISSION),
                        PendingIntent.FLAG_IMMUTABLE
                    )
                    usbManager?.requestPermission(device, permissionIntent)
                    
                    // Don't return a result yet - it will be returned by the receiver when permission is granted/denied
                }
            } else {
                Log.e(TAG, "[$timestamp] Error: Device found but couldn't be retrieved")
                result.error("DEVICE_ERROR", "Device found but couldn't be retrieved", null)
            }
        } else {
            Log.e(TAG, "[$timestamp] No compatible camera found")
            result.error("NO_DEVICE", "No compatible camera found", null)
        }
    }



    private fun getStorageIds(result: MethodChannel.Result) {
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
        Log.d(TAG, "[$timestamp] Flutter requested storage IDs")
        
        coroutineScope.launch {
            try {
                if (!ptpController.isConnected()) {
                    Log.e(TAG, "[$timestamp] Camera not connected")
                    result.error("NOT_CONNECTED", "Camera not connected", null)
                    return@launch
                }
                
                val storageIds = ptpController.getStorageIds()
                if (storageIds != null) {
                    Log.d(TAG, "[$timestamp] Returning ${storageIds.size} storage IDs to Flutter")
                    result.success(storageIds)
                } else {
                    Log.e(TAG, "[$timestamp] Failed to get storage IDs")
                    result.error("PTP_ERROR", "Failed to get storage IDs", null)
                }
            } catch (e: Exception) {
                Log.e(TAG, "[$timestamp] Error getting storage IDs: ${e.message}")
                result.error("PTP_ERROR", e.message, null)
            }
        }
    }

    private fun getObjectHandles(storageId: Int, result: MethodChannel.Result) {
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
        Log.d(TAG, "[$timestamp] Flutter requested object handles for storage $storageId")
        
        coroutineScope.launch {
            try {
                if (!ptpController.isConnected()) {
                    Log.e(TAG, "[$timestamp] Camera not connected")
                    result.error("NOT_CONNECTED", "Camera not connected", null)
                    return@launch
                }
                
                val objectHandles = ptpController.getObjectHandles(storageId)
                if (objectHandles != null) {
                    Log.d(TAG, "[$timestamp] Returning ${objectHandles.size} object handles to Flutter")
                    result.success(objectHandles)
                } else {
                    Log.e(TAG, "[$timestamp] Failed to get object handles")
                    result.error("PTP_ERROR", "Failed to get object handles", null)
                }
            } catch (e: Exception) {
                Log.e(TAG, "[$timestamp] Error getting object handles: ${e.message}")
                result.error("PTP_ERROR", e.message, null)
            }
        }
    }

    private fun getObjectInfo(objectHandle: Int, result: MethodChannel.Result) {
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
        Log.d(TAG, "[$timestamp] Flutter requested info for object $objectHandle")
        
        coroutineScope.launch {
            try {
                if (!ptpController.isConnected()) {
                    Log.e(TAG, "[$timestamp] Camera not connected")
                    result.error("NOT_CONNECTED", "Camera not connected", null)
                    return@launch
                }
                
                val info = ptpController.getObjectInfo(objectHandle)
                if (info != null) {
                    Log.d(TAG, "[$timestamp] Returning object info to Flutter")
                    result.success(info)
                } else {
                    Log.e(TAG, "[$timestamp] Failed to get object info")
                    result.error("PTP_ERROR", "Failed to get object info", null)
                }
            } catch (e: Exception) {
                Log.e(TAG, "[$timestamp] Error getting object info: ${e.message}")
                result.error("PTP_ERROR", e.message, null)
            }
        }
    }

    private fun getObject(objectHandle: Int, result: MethodChannel.Result) {
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
        Log.d(TAG, "[$timestamp] Flutter requested download for object $objectHandle")
        
        coroutineScope.launch {
            try {
                if (!ptpController.isConnected()) {
                    Log.e(TAG, "[$timestamp] Camera not connected")
                    result.error("NOT_CONNECTED", "Camera not connected", null)
                    return@launch
                }
                
                val data = ptpController.getObject(objectHandle)
                if (data != null) {
                    Log.d(TAG, "[$timestamp] Returning object data (${data.size} bytes) to Flutter")
                    result.success(data)
                } else {
                    Log.e(TAG, "[$timestamp] Failed to download object")
                    result.error("PTP_ERROR", "Failed to download object", null)
                }
            } catch (e: Exception) {
                Log.e(TAG, "[$timestamp] Error downloading object: ${e.message}")
                result.error("PTP_ERROR", e.message, null)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        coroutineScope.cancel()
        unregisterReceiver(usbReceiver)
        ptpController.release()
    }
}
