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
import kotlinx.coroutines.CancellationException
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
    
    // Track lifecycle state for proper connection management
    private var isInStarted = false
    
    // Remember the last connected device for potential reconnection
    private var lastConnectedDeviceId: Int? = null
    private var lastConnectedDeviceName: String? = null
    
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
            val timestamp = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
            
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
                        
                        // Get detailed information about the disconnection
                        val stackTrace = Thread.currentThread().stackTrace.joinToString("\\n") { 
                            "  at ${it.className}.${it.methodName}(${it.fileName}:${it.lineNumber})"
                        }
                        
                        // Log detailed information about the disconnect event
                        Log.i(TAG, "[$timestamp] USB device detached: ${device?.deviceName}")
                        Log.d(TAG, "[$timestamp] USB detach stack trace: \\n$stackTrace")
                        Log.d(TAG, "[$timestamp] USB detachment details - Intent extras: ${intent.extras?.keySet()?.joinToString()}")
                        
                        // Check our active connection state at detachment time
                        Log.d(TAG, "[$timestamp] Active connection type at detach: $activeConnectionType")
                        Log.d(TAG, "[$timestamp] Connection state: isConnected=${ptpController.isConnected()}")
                        
                        // If our connected device was detached, release resources
                        if (ptpController.getConnectedDeviceInfo()?.get("deviceName") == device?.deviceName) {
                            Log.i(TAG, "[$timestamp] Our connected camera was detached - releasing resources")
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
        
        // Track whether we're in started state for proper lifecycle management
        isInStarted = false
        
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
                "verifyCameraConnection" -> {
                    verifyCameraConnection(result)
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
        
        // Cancel any ongoing verification first
        cancelPreviousVerification()
        
        // Check if already connected
        if (ptpController.isConnected()) {
            Log.i(TAG, "[$timestamp] Camera already connected")
            result.success(mapOf(
                "connected" to true,
                "message" to "Camera already connected"
            ))
            return
        }
        
        // Create a separate scope for initialization to prevent lifecycle issues
        val initScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
        
        // Setup timeout job with longer timeout (30 seconds) and better error reporting
        val timeoutJob = initScope.launch {
            try {
                delay(30000) // 30 seconds timeout
                if (isActive) {
                    Log.e(TAG, "[$timestamp] Camera initialization timed out after 30 seconds")
                    
                    // Make sure to release any resources before returning an error
                    ptpController.releaseAnyPendingConnections()
                    
                    withContext(Dispatchers.Main) {
                        result.error("TIMEOUT", "Camera initialization timed out. Please try again or check your camera connection.", null)
                    }
                }
            } catch (e: CancellationException) {
                // Normal cancellation, do nothing
                Log.d(TAG, "[$timestamp] Initialization timeout job cancelled normally")
            }
        }
        
        // Find compatible camera
        initScope.launch {
            try {
                Log.d(TAG, "[$timestamp] Looking for compatible camera...")
                val (found, deviceInfo) = ptpController.findCamera()
                
                if (found) {
                    Log.d(TAG, "[$timestamp] Compatible camera found: $deviceInfo")
                    
                    // Device found, attempt direct connection
                    val device = usbManager?.deviceList?.values?.find { device ->
                        device.vendorId == 0x04a9 // Canon vendor ID
                    }
                    
                    if (device != null) {
                        Log.d(TAG, "[$timestamp] Trying to connect to camera: ${device.deviceName} (${device.deviceId})")
                        
                        // Check if permission is already granted
                        if (usbManager?.hasPermission(device) == true) {
                            Log.i(TAG, "[$timestamp] Permission already granted, setting up device")
                            
                            try {
                                val startSetup = System.currentTimeMillis()
                                val success = ptpController.setupDevice(device)
                                val setupTime = System.currentTimeMillis() - startSetup
                                Log.d(TAG, "[$timestamp] Device setup took $setupTime ms")
                                
                                timeoutJob.cancel() // Cancel timeout
                                
                                if (success) {
                                    Log.i(TAG, "[$timestamp] Device setup successful")
                                    activeConnectionType = ConnectionType.USB
                                    
                                    // Remember this device for potential reconnection
                                    lastConnectedDeviceId = device.deviceId
                                    lastConnectedDeviceName = device.deviceName
                                    
                                    withContext(Dispatchers.Main) {
                                        result.success(mapOf(
                                            "connected" to true,
                                            "message" to "Camera connected successfully",
                                            "connectionType" to "USB"
                                        ))
                                    }
                                } else {
                                    Log.e(TAG, "[$timestamp] Device setup failed")
                                    activeConnectionType = ConnectionType.NONE
                                    ptpController.releaseAnyPendingConnections()
                                    
                                    withContext(Dispatchers.Main) {
                                        result.error("SETUP_ERROR", "Failed to set up camera connection", null)
                                    }
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "[$timestamp] Exception during device setup: ${e.message}")
                                e.printStackTrace()
                                activeConnectionType = ConnectionType.NONE
                                ptpController.releaseAnyPendingConnections()
                                
                                withContext(Dispatchers.Main) {
                                    result.error("SETUP_ERROR", "Exception during camera setup: ${e.message}", null)
                                }
                            }
                        } else {
                            // No need for a separate permission receiver - handle permission directly
                            Log.d(TAG, "[$timestamp] Setting up direct permission handling")
                            
                            try {
                                // Just request permission without a custom broadcast receiver
                                val permissionIntent = PendingIntent.getActivity(
                                    this@MainActivity,
                                    0,
                                    Intent(this@MainActivity, MainActivity::class.java),
                                    PendingIntent.FLAG_IMMUTABLE
                                )
                                
                                usbManager?.requestPermission(device, permissionIntent)
                                
                                // Since we're going through the activity, we'll handle permission in onResume
                                timeoutJob.cancel()
                                
                                withContext(Dispatchers.Main) {
                                    result.success(mapOf(
                                        "connected" to false,
                                        "message" to "Permission dialog shown to user",
                                        "status" to "PERMISSION_REQUESTED"
                                    ))
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "[$timestamp] Error requesting permission: ${e.message}")
                                timeoutJob.cancel()
                                ptpController.releaseAnyPendingConnections()
                                
                                withContext(Dispatchers.Main) {
                                    result.error("PERMISSION_ERROR", "Failed to request permission: ${e.message}", null)
                                }
                            }
                        }
                    } else {
                        timeoutJob.cancel() // Cancel timeout
                        Log.e(TAG, "[$timestamp] Error: Device found but couldn't be retrieved")
                        
                        withContext(Dispatchers.Main) {
                            result.error("DEVICE_ERROR", "Device found but couldn't be retrieved", null)
                        }
                    }
                } else {
                    timeoutJob.cancel() // Cancel timeout
                    Log.e(TAG, "[$timestamp] No compatible camera found")
                    
                    withContext(Dispatchers.Main) {
                        result.error("NO_DEVICE", "No compatible camera found", null)
                    }
                }
            } catch (e: Exception) {
                timeoutJob.cancel()
                Log.e(TAG, "[$timestamp] Exception during camera initialization: ${e.message}")
                e.printStackTrace()
                
                withContext(Dispatchers.Main) {
                    result.error("INIT_ERROR", "Camera initialization failed: ${e.message}", null)
                }
            } finally {
                initScope.cancel() // Clean up the scope when done
            }
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
        
        try {
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
                        
                        // Remember this device for potential reconnection
                        lastConnectedDeviceId = canonDevice.deviceId
                        lastConnectedDeviceName = canonDevice.deviceName
                        
                        setupNewPhotoListener()
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "[$timestamp] Error in onResume: ${e.message}")
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
                                    flutterEngine?.dartExecutor?.binaryMessenger?.let { messenger ->
                                        MethodChannel(messenger, CHANNEL)
                                            .invokeMethod("onCameraEvent", event)
                                    }
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
        
        // Cancel any ongoing verification first
        cancelPreviousVerification()
        
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
    
    /**
     * Verify that the camera connection is still active and functioning
     */
    // Track verification jobs to cancel them later if needed
    private var currentVerificationScope: CoroutineScope? = null
    private var currentTimeoutJob: Job? = null
    
    private fun verifyCameraConnection(result: MethodChannel.Result) {
        val timestamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
        Log.d(TAG, "[$timestamp] Verifying camera connection...")
        Log.d(TAG, "[$timestamp] Active connection type: ${activeConnectionType.name}")
        
        // Cancel any previous verification in progress
        cancelPreviousVerification()
        
        // Do a quick check first for obvious disconnection
        if (activeConnectionType == ConnectionType.NONE) {
            Log.e(TAG, "[$timestamp] No active camera connection to verify")
            result.success(mapOf(
                "connected" to false,
                "message" to "No active camera connection"
            ))
            return
        }
        
        // Do a basic check to catch obvious issues - use the enhanced version that checks physical device
        val basicCheck = when (activeConnectionType) {
            ConnectionType.USB -> ptpController.isConnected(checkPhysicalDevice = true)
            ConnectionType.WIFI -> true // No easy way to check WiFi without full verification
            else -> false
        }
        
        if (!basicCheck && activeConnectionType == ConnectionType.USB) {
            Log.e(TAG, "[$timestamp] Basic connection check failed - USB device not properly connected")
            activeConnectionType = ConnectionType.NONE
            result.success(mapOf(
                "connected" to false,
                "message" to "Camera disconnected"
            ))
            return
        }
        
        // Use a new coroutine scope that won't be affected by activity lifecycle
        val verificationScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
        currentVerificationScope = verificationScope
        
        // Setup timeout job with increased timeout
        val timeoutJob = verificationScope.launch {
            try {
                delay(15000) // 15 seconds timeout for verification (increased from 10 seconds)
                // Only trigger timeout if the job is still active
                if (isActive) {
                    Log.e(TAG, "[$timestamp] Camera connection verification timed out after 15 seconds")

                    // Clean up any resources in case of timeout
                    if (activeConnectionType == ConnectionType.USB) {
                        ptpController.releaseAnyPendingConnections()
                    } else if (activeConnectionType == ConnectionType.WIFI) {
                        ptpIpController.releaseAnyPendingConnections()
                    }
                    
                    // Clear tracking variables since this job is completing
                    currentTimeoutJob = null
                    currentVerificationScope = null
                    
                    result.error("TIMEOUT", "Camera connection verification timed out", null)
                }
            } catch (e: CancellationException) {
                // Ignore cancellation exceptions
                Log.d(TAG, "[$timestamp] Timeout job cancelled normally")
            }
        }
        currentTimeoutJob = timeoutJob
        
        verificationScope.launch {
            try {
                // Log the active connection type for debugging
                Log.d(TAG, "[$timestamp] Active connection type during verification: ${activeConnectionType.name}")
                
                // Try multiple verification attempts
                var verified = false
                var attempts = 0
                val maxAttempts = 2
                
                while (attempts < maxAttempts && !verified) {
                    attempts++
                    
                    if (attempts > 1) {
                        Log.d(TAG, "[$timestamp] Retrying verification (attempt $attempts of $maxAttempts)")
                        delay(1000) // Wait before retry
                    }
                    
                    verified = when (activeConnectionType) {
                        ConnectionType.USB -> ptpController.verifyConnection()
                        ConnectionType.WIFI -> ptpIpController.verifyConnection()
                        else -> false
                    }
                    
                    if (verified) break
                }
                
                // Only proceed if the coroutine is still active
                if (isActive) {
                    timeoutJob.cancel() // Cancel timeout
                    
                    if (verified) {
                        Log.i(TAG, "[$timestamp] Camera connection verified successfully after $attempts attempt(s)")
                        
                        // Clear tracking variables since this verification is complete
                        currentTimeoutJob = null
                        currentVerificationScope = null
                        
                        result.success(mapOf(
                            "connected" to true,
                            "message" to "Camera connection verified",
                            "connectionType" to activeConnectionType.name
                        ))
                    } else {
                        // Connection failed verification, update state
                        Log.e(TAG, "[$timestamp] Camera connection verification failed after $attempts attempt(s)")
                        
                        // Clean up resources for the failed connection
                        if (activeConnectionType == ConnectionType.USB) {
                            ptpController.releaseAnyPendingConnections()
                        } else if (activeConnectionType == ConnectionType.WIFI) {
                            ptpIpController.releaseAnyPendingConnections()
                        }
                        
                        // Clear tracking variables since this verification is complete
                        currentTimeoutJob = null
                        currentVerificationScope = null
                        
                        activeConnectionType = ConnectionType.NONE
                        result.success(mapOf(
                            "connected" to false,
                            "message" to "Camera connection lost"
                        ))
                    }
                }
            } catch (e: CancellationException) {
                // Handle coroutine cancellation gracefully
                Log.d(TAG, "[$timestamp] Verification job cancelled")
                timeoutJob.cancel()
            } catch (e: Exception) {
                // Only proceed if the coroutine is still active
                if (isActive) {
                    timeoutJob.cancel() // Cancel timeout
                    Log.e(TAG, "[$timestamp] Error verifying camera connection: ${e.message}")
                    e.printStackTrace()
                    
                    // Clear tracking variables on error
                    currentTimeoutJob = null
                    currentVerificationScope = null
                    
                    result.error("VERIFICATION_ERROR", "Failed to verify connection: ${e.message}", null)
                }
            } finally {
                // Clean up resources - this will run even if the coroutine is cancelled
                verificationScope.cancel()
            }
        }
    }

    /**
     * Cancel any verification process that might be in progress
     * This prevents multiple verification tasks from running simultaneously
     */
    private fun cancelPreviousVerification() {
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
        
        if (currentTimeoutJob != null || currentVerificationScope != null) {
            Log.d(TAG, "[$timestamp] Cancelling previous verification process")
            
            // Cancel timeout job first
            currentTimeoutJob?.cancel()
            currentTimeoutJob = null
            
            // Then cancel the whole scope
            currentVerificationScope?.cancel()
            currentVerificationScope = null
            
            Log.d(TAG, "[$timestamp] Previous verification cancelled")
        }
    }
    
    /**
     * Handle new intents, especially for USB connections
     * This is important for properly handling USB device connections when the app is already running
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
        Log.i(TAG, "[$timestamp] onNewIntent: ${intent.action}")
        
        // Update the intent stored in the activity
        setIntent(intent)
        
        // If we're in the started state, process this intent for potential USB connections
        if (isInStarted) {
            handlePotentialUsbIntent(intent)
        }
    }
    
    /**
     * Called when the activity is becoming visible to the user
     */
    override fun onStart() {
        super.onStart()
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
        Log.i(TAG, "[$timestamp] onStart")
        
        // Update lifecycle state
        isInStarted = true
        
        // Process the current intent for USB connection handling
        handlePotentialUsbIntent(intent)
    }
    
    /**
     * Called when the activity is no longer visible to the user
     */
    override fun onStop() {
        super.onStop()
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
        Log.i(TAG, "[$timestamp] onStop")
        
        // Update lifecycle state
        isInStarted = false
    }
    
    /**
     * Handle a potential USB intent by examining it for device information
     * This is called from both onStart and onNewIntent to handle USB connections
     */
    private fun handlePotentialUsbIntent(intent: Intent) {
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
        
        // Check if this is a USB device attached action
        if (UsbManager.ACTION_USB_DEVICE_ATTACHED == intent.action) {
            val device = getUsbDevice(intent)
            if (device != null) {
                Log.i(TAG, "[$timestamp] USB device attached via intent: ${device.deviceName}")
                
                // Check if this is potentially our last connected device
                val isLastDevice = lastConnectedDeviceId == device.deviceId || 
                                  lastConnectedDeviceName == device.deviceName
                
                // If this is the last device we were connected to, try to reconnect
                if (isLastDevice && activeConnectionType == ConnectionType.NONE) {
                    Log.i(TAG, "[$timestamp] This appears to be our previously connected camera - attempting to reconnect")
                    
                    // If we have permission, reconnect immediately
                    if (usbManager?.hasPermission(device) == true) {
                        // Use a coroutine to avoid blocking the main thread
                        coroutineScope.launch {
                            try {
                                val success = ptpController.setupDevice(device)
                                if (success) {
                                    Log.i(TAG, "[$timestamp] Auto-reconnected to camera successfully")
                                    activeConnectionType = ConnectionType.USB
                                } else {
                                    Log.e(TAG, "[$timestamp] Auto-reconnect failed")
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "[$timestamp] Error during auto-reconnect: ${e.message}")
                            }
                        }
                    } else {
                        // Request permission
                        requestUsbPermission(device)
                    }
                }
            }
        }
    }
    
    /**
     * Request permission for a USB device
     */
    private fun requestUsbPermission(device: UsbDevice) {
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
        Log.i(TAG, "[$timestamp] Requesting permission for USB device: ${device.deviceName}")
        
        try {
            // Create a broadcast receiver for the permission response
            val permissionReceiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
                    
                    if (ACTION_USB_PERMISSION == intent?.action) {
                        synchronized(this) {
                            val permissionDevice = getUsbDevice(intent)
                            
                            // Unregister this receiver since we only need it once
                            try {
                                context?.unregisterReceiver(this)
                            } catch (e: Exception) {
                                Log.e(TAG, "[$ts] Error unregistering receiver: ${e.message}")
                            }
                            
                            if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                                if (permissionDevice != null) {
                                    Log.i(TAG, "[$ts] Permission granted for device: ${permissionDevice.deviceName}")
                                    
                                    // Connect to the device in a coroutine
                                    coroutineScope.launch {
                                        try {
                                            val success = ptpController.setupDevice(permissionDevice)
                                            if (success) {
                                                Log.i(TAG, "[$ts] Connected to camera after permission granted")
                                                activeConnectionType = ConnectionType.USB
                                                
                                                // Remember this device for potential reconnection
                                                lastConnectedDeviceId = permissionDevice.deviceId
                                                lastConnectedDeviceName = permissionDevice.deviceName
                                            } else {
                                                Log.e(TAG, "[$ts] Failed to connect after permission granted")
                                            }
                                        } catch (e: Exception) {
                                            Log.e(TAG, "[$ts] Error connecting after permission: ${e.message}")
                                        }
                                    }
                                }
                                else {
                                    Log.i(TAG, "[$ts] Permission denied for device")
                                }
                            } else {
                                Log.i(TAG, "[$ts] Permission denied for device")
                            }
                        }
                    }
                }
            }
            
            // Register the permission receiver
            val permissionFilter = IntentFilter(ACTION_USB_PERMISSION)
            registerReceiver(permissionReceiver, permissionFilter)
            
            // Request permission
            val permissionIntent = PendingIntent.getBroadcast(this, 0, Intent(ACTION_USB_PERMISSION), 
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                    PendingIntent.FLAG_MUTABLE
                } else {
                    0
                }
            )
            usbManager?.requestPermission(device, permissionIntent)
        } catch (e: Exception) {
            Log.e(TAG, "[$timestamp] Error requesting USB permission: ${e.message}")
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        
        // Cancel any verification in progress
        cancelPreviousVerification()
        
        // Cancel main scope
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
