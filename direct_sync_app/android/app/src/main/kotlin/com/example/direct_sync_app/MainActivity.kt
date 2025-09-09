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
    
    // Connection types
    private enum class ConnectionType {
        NONE,
        USB,
        WIFI
    }
    
    private var usbManager: UsbManager? = null
    private lateinit var ptpController: PtpController
    private lateinit var ptpIpController: PtpIpController
    
    // Track the active connection type
    private var activeConnectionType = ConnectionType.NONE
    
    private val coroutineScope = CoroutineScope(Dispatchers.Main + Job())

    // Helper function to safely get UsbDevice from intent
    private fun getUsbDevice(intent: Intent): UsbDevice? {
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
        }
    }
    
    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
            
            if (intent == null) return
            
            try {
                when (intent.action) {
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
                    // We don't handle ACTION_USB_PERMISSION in the main receiver anymore
                }
            } catch (e: Exception) {
                Log.e(TAG, "[$timestamp] Error in USB broadcast receiver: ${e.message}")
                e.printStackTrace()
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
                "connectToWifiCamera" -> {
                    val ipAddress = call.argument<String>("ipAddress") ?: ""
                    val port = call.argument<Int>("port") ?: 15740
                    connectToWifiCamera(ipAddress, port, result)
                }
                "disconnectCamera" -> {
                    disconnectCamera(result)
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
        ptpIpController = PtpIpController()
        
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
        Log.i(TAG, "[$timestamp] MainActivity created, PTP Controllers initialized")
        
        // Setup the new object listener for automatic photo detection
        setupNewPhotoListener()
        
        val usbDeviceFilter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            // Don't listen for permission responses in the main receiver to avoid conflicts
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
        
        // Setup timeout job
        val timeoutJob = coroutineScope.launch {
            delay(20000) // 20 seconds timeout
            if (isActive) {
                Log.e(TAG, "[$timestamp] Camera initialization timed out after 20 seconds")
                result.error("TIMEOUT", "Camera initialization timed out", null)
            }
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
                    timeoutJob.cancel() // Cancel timeout
                    if (success) {
                        Log.i(TAG, "[$timestamp] Device setup successful")
                        activeConnectionType = ConnectionType.USB
                        result.success(mapOf(
                            "connected" to true,
                            "message" to "Camera connected successfully",
                            "connectionType" to "USB"
                        ))
                    } else {
                        Log.e(TAG, "[$timestamp] Device setup failed")
                        activeConnectionType = ConnectionType.NONE
                        result.error("SETUP_ERROR", "Failed to set up camera connection", null)
                    }
                } else {
                    // No need for a separate permission receiver - handle permission directly
                    // This simpler approach should be more stable
                    Log.d(TAG, "[$timestamp] Setting up direct permission handling")
                    
                    try {
                        // Just request permission without a custom broadcast receiver
                        val permissionIntent = PendingIntent.getActivity(
                            this,
                            0,
                            Intent(this, MainActivity::class.java),  // Return to this activity
                            PendingIntent.FLAG_IMMUTABLE
                        )
                        
                        usbManager?.requestPermission(device, permissionIntent)
                        
                        // Since we're going through the activity, we'll handle permission in onResume
                        // Return success now to prevent hanging the Flutter connection
                        timeoutJob.cancel()
                        result.success(mapOf(
                            "connected" to false,
                            "message" to "Permission dialog shown to user",
                            "status" to "PERMISSION_REQUESTED"
                        ))
                    } catch (e: Exception) {
                        Log.e(TAG, "[$timestamp] Error setting up permission: ${e.message}")
                        timeoutJob.cancel()
                        result.error("PERMISSION_ERROR", "Failed to request permission: ${e.message}", null)
                    }
                    
                    // Request permission
                    Log.d(TAG, "[$timestamp] Requesting permission for camera: ${device.deviceName}")
                    try {
                        val permissionIntent = PendingIntent.getBroadcast(
                            this,
                            0,
                            Intent(ACTION_USB_PERMISSION),
                            PendingIntent.FLAG_IMMUTABLE
                        )
                        usbManager?.requestPermission(device, permissionIntent)
                        
                        // Don't send any result yet - just log that we're waiting
                        Log.i(TAG, "[$timestamp] Waiting for user permission response")
                    } catch (e: Exception) {
                        Log.e(TAG, "[$timestamp] Error requesting permission: ${e.message}")
                        timeoutJob.cancel()
                        result.error("PERMISSION_REQUEST_ERROR", "Failed to request permission: ${e.message}", null)
                    }
                    // The final result will be returned by the permission receiver
                }
            } else {
                timeoutJob.cancel() // Cancel timeout
                Log.e(TAG, "[$timestamp] Error: Device found but couldn't be retrieved")
                result.error("DEVICE_ERROR", "Device found but couldn't be retrieved", null)
            }
        } else {
            timeoutJob.cancel() // Cancel timeout
            Log.e(TAG, "[$timestamp] No compatible camera found")
            result.error("NO_DEVICE", "No compatible camera found", null)
        }
    }



    private fun getStorageIds(result: MethodChannel.Result) {
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
        Log.d(TAG, "[$timestamp] Flutter requested storage IDs")
        
        coroutineScope.launch {
            try {
                when (activeConnectionType) {
                    ConnectionType.NONE -> {
                        Log.e(TAG, "[$timestamp] Camera not connected")
                        result.error("NOT_CONNECTED", "Camera not connected", null)
                        return@launch
                    }
                    ConnectionType.USB -> {
                        if (!ptpController.isConnected()) {
                            Log.e(TAG, "[$timestamp] USB camera connection lost")
                            activeConnectionType = ConnectionType.NONE
                            result.error("NOT_CONNECTED", "Camera connection lost", null)
                            return@launch
                        }
                        
                        val storageIds = ptpController.getStorageIds()
                        if (storageIds != null) {
                            Log.d(TAG, "[$timestamp] Returning ${storageIds.size} storage IDs to Flutter from USB")
                            result.success(storageIds)
                        } else {
                            Log.e(TAG, "[$timestamp] Failed to get storage IDs from USB")
                            result.error("PTP_ERROR", "Failed to get storage IDs", null)
                        }
                    }
                    ConnectionType.WIFI -> {
                        val storageIds = ptpIpController.getStorageIds()
                        if (storageIds != null) {
                            Log.d(TAG, "[$timestamp] Returning ${storageIds.size} storage IDs to Flutter from WiFi")
                            result.success(storageIds)
                        } else {
                            Log.e(TAG, "[$timestamp] Failed to get storage IDs from WiFi")
                            result.error("PTP_ERROR", "Failed to get storage IDs", null)
                        }
                    }
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
                when (activeConnectionType) {
                    ConnectionType.NONE -> {
                        Log.e(TAG, "[$timestamp] Camera not connected")
                        result.error("NOT_CONNECTED", "Camera not connected", null)
                        return@launch
                    }
                    ConnectionType.USB -> {
                        if (!ptpController.isConnected()) {
                            Log.e(TAG, "[$timestamp] USB camera connection lost")
                            activeConnectionType = ConnectionType.NONE
                            result.error("NOT_CONNECTED", "Camera connection lost", null)
                            return@launch
                        }
                        
                        val objectHandles = ptpController.getObjectHandles(storageId)
                        if (objectHandles != null) {
                            Log.d(TAG, "[$timestamp] Returning ${objectHandles.size} object handles to Flutter from USB")
                            result.success(objectHandles)
                        } else {
                            Log.e(TAG, "[$timestamp] Failed to get object handles from USB")
                            result.error("PTP_ERROR", "Failed to get object handles", null)
                        }
                    }
                    ConnectionType.WIFI -> {
                        val objectHandles = ptpIpController.getObjectHandles(storageId)
                        if (objectHandles != null) {
                            Log.d(TAG, "[$timestamp] Returning ${objectHandles.size} object handles to Flutter from WiFi")
                            result.success(objectHandles)
                        } else {
                            Log.e(TAG, "[$timestamp] Failed to get object handles from WiFi")
                            result.error("PTP_ERROR", "Failed to get object handles", null)
                        }
                    }
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
                when (activeConnectionType) {
                    ConnectionType.NONE -> {
                        Log.e(TAG, "[$timestamp] Camera not connected")
                        result.error("NOT_CONNECTED", "Camera not connected", null)
                        return@launch
                    }
                    ConnectionType.USB -> {
                        if (!ptpController.isConnected()) {
                            Log.e(TAG, "[$timestamp] USB camera connection lost")
                            activeConnectionType = ConnectionType.NONE
                            result.error("NOT_CONNECTED", "Camera connection lost", null)
                            return@launch
                        }
                        
                        val info = ptpController.getObjectInfo(objectHandle)
                        if (info != null) {
                            Log.d(TAG, "[$timestamp] Returning object info to Flutter from USB")
                            result.success(info)
                        } else {
                            Log.e(TAG, "[$timestamp] Failed to get object info from USB")
                            result.error("PTP_ERROR", "Failed to get object info", null)
                        }
                    }
                    ConnectionType.WIFI -> {
                        val info = ptpIpController.getObjectInfo(objectHandle)
                        if (info != null) {
                            Log.d(TAG, "[$timestamp] Returning object info to Flutter from WiFi")
                            result.success(info)
                        } else {
                            Log.e(TAG, "[$timestamp] Failed to get object info from WiFi")
                            result.error("PTP_ERROR", "Failed to get object info", null)
                        }
                    }
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
                when (activeConnectionType) {
                    ConnectionType.NONE -> {
                        Log.e(TAG, "[$timestamp] Camera not connected")
                        result.error("NOT_CONNECTED", "Camera not connected", null)
                        return@launch
                    }
                    ConnectionType.USB -> {
                        if (!ptpController.isConnected()) {
                            Log.e(TAG, "[$timestamp] USB camera connection lost")
                            activeConnectionType = ConnectionType.NONE
                            result.error("NOT_CONNECTED", "Camera connection lost", null)
                            return@launch
                        }
                        
                        val data = ptpController.getObject(objectHandle)
                        if (data != null) {
                            Log.d(TAG, "[$timestamp] Returning object data (${data.size} bytes) to Flutter from USB")
                            result.success(data)
                        } else {
                            Log.e(TAG, "[$timestamp] Failed to download object from USB")
                            result.error("PTP_ERROR", "Failed to download object", null)
                        }
                    }
                    ConnectionType.WIFI -> {
                        val data = ptpIpController.getObject(objectHandle)
                        if (data != null) {
                            Log.d(TAG, "[$timestamp] Returning object data (${data.size} bytes) to Flutter from WiFi")
                            result.success(data)
                        } else {
                            Log.e(TAG, "[$timestamp] Failed to download object from WiFi")
                            result.error("PTP_ERROR", "Failed to download object", null)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "[$timestamp] Error downloading object: ${e.message}")
                result.error("PTP_ERROR", e.message, null)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
        Log.d(TAG, "[$timestamp] Activity resumed, checking for camera connection")
        
        // Check if we have a USB camera that needs setup, but only if we're not already connected via WiFi
        if (activeConnectionType != ConnectionType.WIFI && !ptpController.isConnected()) {
            val canonDevice = usbManager?.deviceList?.values?.find { device ->
                device.vendorId == 0x04a9 // Canon vendor ID
            }
            
                if (canonDevice != null && usbManager?.hasPermission(canonDevice) == true) {
                    Log.i(TAG, "[$timestamp] Found Canon camera with permission in onResume, setting up")
                    val success = ptpController.setupDevice(canonDevice)
                    if (success) {
                        activeConnectionType = ConnectionType.USB
                        Log.i(TAG, "[$timestamp] USB connection established in onResume")
                        setupNewPhotoListener()
                    }
                }
            }
        }
    }
    
    /**
     * Setup listener for new photos taken on the camera
     */
    private fun setupNewPhotoListener() {
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
        
        when (activeConnectionType) {
            ConnectionType.USB -> {
                Log.i(TAG, "[$timestamp] Setting up new photo listener for USB camera")
                ptpController.setNewObjectListener { objectHandle ->
                    // This will be called when a new photo is detected on the camera
                    Log.i(TAG, "[$timestamp] New photo detected on camera! Object handle: 0x${objectHandle.toString(16)}")
                    
                    // You can auto-download the new photo here if desired
                    coroutineScope.launch {
                        try {
                            val info = ptpController.getObjectInfo(objectHandle)
                            if (info != null) {
                                Log.i(TAG, "[$timestamp] New photo info: $info")
                                
                                // Optional: Notify Flutter about the new photo
                                runOnUiThread {
                                    val event = mapOf(
                                        "event" to "new_photo",
                                        "objectHandle" to objectHandle,
                                        "info" to info
                                    )
                                    MethodChannel(flutterEngine?.dartExecutor?.binaryMessenger, CHANNEL)
                                        .invokeMethod("onCameraEvent", event)
                                }
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "[$timestamp] Error processing new photo: ${e.message}")
                        }
                    }
                }
            }
            ConnectionType.WIFI -> {
                Log.i(TAG, "[$timestamp] Setting up new photo listener for WiFi camera")
                // Similar implementation for PTP/IP would be added here
            }
            else -> {
                Log.i(TAG, "[$timestamp] No camera connected, not setting up photo listener")
            }
        }
    }    private fun connectToWifiCamera(ipAddress: String, port: Int, result: MethodChannel.Result) {
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
        Log.d(TAG, "[$timestamp] Flutter requested WiFi camera connection to $ipAddress:$port")
        
        if (activeConnectionType != ConnectionType.NONE && activeConnectionType != ConnectionType.WIFI) {
            // If already connected via USB, disconnect first
            if (activeConnectionType == ConnectionType.USB) {
                ptpController.release()
            }
        }
        
        // Setup timeout job
        val timeoutJob = coroutineScope.launch {
            delay(20000) // 20 seconds timeout
            if (isActive) {
                Log.e(TAG, "[$timestamp] WiFi camera connection timed out after 20 seconds")
                result.error("TIMEOUT", "WiFi camera connection timed out", null)
            }
        }
        
        coroutineScope.launch {
            try {
                val connected = ptpIpController.connect(ipAddress, port)
                timeoutJob.cancel() // Cancel timeout job
                
                if (connected) {
                    Log.i(TAG, "[$timestamp] Successfully connected to camera via WiFi")
                    activeConnectionType = ConnectionType.WIFI
                    result.success(mapOf(
                        "connected" to true,
                        "message" to "Camera connected successfully via WiFi",
                        "connectionType" to "WIFI"
                    ))
                } else {
                    Log.e(TAG, "[$timestamp] Failed to connect to camera via WiFi")
                    activeConnectionType = ConnectionType.NONE
                    result.error("CONNECTION_ERROR", "Failed to connect to camera via WiFi", null)
                }
            } catch (e: Exception) {
                timeoutJob.cancel() // Cancel timeout job
                Log.e(TAG, "[$timestamp] Error connecting to WiFi camera: ${e.message}")
                activeConnectionType = ConnectionType.NONE
                result.error("CONNECTION_ERROR", "Error connecting to WiFi camera: ${e.message}", null)
            }
        }
    }
    
    private fun disconnectCamera(result: MethodChannel.Result) {
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
        Log.d(TAG, "[$timestamp] Flutter requested camera disconnection")
        
        try {
            when (activeConnectionType) {
                ConnectionType.USB -> {
                    ptpController.release()
                    Log.i(TAG, "[$timestamp] USB camera disconnected")
                }
                ConnectionType.WIFI -> {
                    ptpIpController.closeConnection()
                    Log.i(TAG, "[$timestamp] WiFi camera disconnected")
                }
                else -> {
                    Log.i(TAG, "[$timestamp] No camera connected to disconnect")
                }
            }
            
            activeConnectionType = ConnectionType.NONE
            result.success(mapOf(
                "success" to true,
                "message" to "Camera disconnected successfully"
            ))
        } catch (e: Exception) {
            Log.e(TAG, "[$timestamp] Error disconnecting camera: ${e.message}")
            result.error("DISCONNECT_ERROR", "Error disconnecting camera: ${e.message}", null)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        coroutineScope.cancel()
        unregisterReceiver(usbReceiver)
        
        // Release resources based on connection type
        when (activeConnectionType) {
            ConnectionType.USB -> ptpController.release()
            ConnectionType.WIFI -> ptpIpController.closeConnection()
            else -> {} // No connection to close
        }
    }
}
