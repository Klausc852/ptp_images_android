// PtpUsbManager.kt
package com.example.direct_sync_app

import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.util.Log
import io.flutter.plugin.common.MethodChannel
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.concurrent.thread

class PtpUsbManager(private val context: Context, private val channel: MethodChannel) {
    
    companion object {
        const val ACTION_USB_PERMISSION = "com.example.direct_sync_app.USB_PERMISSION"
        
        // PTP Operation Codes
        private const val PTP_GET_OBJECT = 0x1009
        private const val PTP_GET_OBJECT_INFO = 0x1008
        private const val PTP_GET_THUMB = 0x100A
        private const val PTP_OPERATION_OPEN_SESSION = 0x1002
        private const val PTP_OPERATION_CLOSE_SESSION = 0x1003
        private const val PTP_OPERATION_GET_DEVICE_INFO = 0x1001
        private const val PTP_OPERATION_SET_DEVICE_PROP_VALUE = 0x1016
        private const val PTP_OPERATION_GET_DEVICE_PROP_VALUE = 0x1015
        
        // Storage Operations
        private const val PTP_GET_STORAGE_IDS = 0x1004
        private const val PTP_GET_STORAGE_INFO = 0x1005
        private const val PTP_GET_OBJECT_HANDLES = 0x1007
        
        // PTP Response Codes
        private const val PTP_RESPONSE_OK = 0x2001
        private const val PTP_RESPONSE_GENERAL_ERROR = 0x2002
        private const val PTP_RESPONSE_SESSION_NOT_OPEN = 0x2003
        
        // PTP Event Codes
        private const val PTP_EVENT_OBJECT_ADDED = 0x4002
        private const val PTP_EVENT_CAPTURE_COMPLETE = 0x400D
        private const val PTP_EVENT_DEVICE_PROP_CHANGED = 0x4006
        
        // Additional PTP Event Codes for Storage Monitoring
        private const val PTP_EVENT_STORE_ADDED = 0x4004
        private const val PTP_EVENT_STORE_REMOVED = 0x4005
        private const val PTP_EVENT_STORAGE_INFO_CHANGED = 0x400C
    }

    private val TAG = "PtpUsbManager"
    private val usbManager: UsbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager

    private var usbConnection: UsbDeviceConnection? = null
    private var connectedUsbDevice: UsbDevice? = null
    private var ptpInEndpoint: UsbEndpoint? = null
    private var ptpOutEndpoint: UsbEndpoint? = null
    private var ptpEventEndpoint: UsbEndpoint? = null

    private var isMonitoring = false
    private var eventListenerThread: Thread? = null
    
    // List to track downloaded photos
    private val downloadedPhotos = mutableListOf<Map<String, Any>>()

    fun getPermissionIntent(): PendingIntent {
        Log.d(TAG, "Ask for permission using action: $ACTION_USB_PERMISSION")
        return PendingIntent.getBroadcast(context, 0, Intent(ACTION_USB_PERMISSION), PendingIntent.FLAG_IMMUTABLE)
    }
    
    fun checkPermissionAfterResponse(device: UsbDevice): Boolean {
        val hasPermission = usbManager.hasPermission(device)
        Log.d(TAG, "Permission check after response: Device ${device.deviceName} has permission: $hasPermission")
        
        // Send updated permission status to Flutter
        val permissionInfo = mapOf(
            "deviceName" to device.deviceName,
            "hasPermission" to hasPermission,
            "vendorId" to "0x${device.vendorId.toString(16).padStart(4, '0').uppercase()}",
            "productId" to "0x${device.productId.toString(16).padStart(4, '0').uppercase()}"
        )
        channel.invokeMethod("permissionStatusUpdate", permissionInfo)
        
        return hasPermission
    }

    fun startMonitoring() {
        Log.d(TAG, "Check usb monitoring.")
        Log.d(TAG, "Current isMonitoring state: $isMonitoring")
        if (isMonitoring) {
            Log.d(TAG, "Already monitoring, but restarting to handle any issues")
            stopMonitoring() // Stop first to clean up
        }
        isMonitoring = true
        Log.d(TAG, "Starting PTP USB monitoring. isMonitoring set to: $isMonitoring")
        // Iterate through connected devices to find the camera if already attached
        for (device in usbManager.deviceList.values) {
            if (usbManager.hasPermission(device)) {
                onUsbDeviceAttached(device)
                break // Assuming only one camera
            }
        }
        // If device is already connected but listener not started, start it
        if (usbConnection != null && eventListenerThread == null && isMonitoring) {
            Log.d(TAG, "Device already connected, starting event listener")
            startPtpEventListener()
        }else{
            Log.d(TAG, "No device connected yet, waiting for attachment event.")
        }
    }

    fun stopMonitoring() {
        Log.d(TAG, "stopMonitoring() called. Current isMonitoring: $isMonitoring")
        Log.d(TAG, "Call stack:", Exception("Debug stack trace"))

        // Prevent stopping if we're in the middle of starting
        if (!isMonitoring) {
            Log.d(TAG, "Already stopped, ignoring stop request")
            return
        }

        isMonitoring = false
        eventListenerThread?.interrupt()
        eventListenerThread = null

        connectedUsbDevice?.let { device ->
            findPtpInterface(device)?.let { usbInterface ->
                usbConnection?.releaseInterface(usbInterface)
            }
        }
        usbConnection?.close()
        usbConnection = null // ADDED: Clear the stored connected device
        connectedUsbDevice = null
        Log.d(TAG, "Stopped PTP USB monitoring. isMonitoring now: $isMonitoring")
    }
    fun getMonitoringStatus(): Map<String, Any> {
        return mapOf(
            "isMonitoring" to isMonitoring,
            "hasUsbConnection" to (usbConnection != null),
            "hasEventEndpoint" to (ptpEventEndpoint != null),
            "connectedDeviceName" to (connectedUsbDevice?.deviceName ?: "None"),
            "eventListenerThreadAlive" to (eventListenerThread?.isAlive ?: false)
        )
    }
    
    fun getDownloadedPhotos(): List<Map<String, Any>> {
        synchronized(downloadedPhotos) {
            return downloadedPhotos.toList() // Return a copy of the list
        }
    }
    
    fun clearDownloadedPhotos() {
        synchronized(downloadedPhotos) {
            downloadedPhotos.clear()
        }
    }
    fun logPermissionStatus() {
        Log.d(TAG, "Checking USB Permissions:")
        Log.d(TAG, "--------------------------------------")
        if (usbManager.deviceList.isEmpty()) {
            Log.d(TAG, "No USB devices connected.")
        } else {
            usbManager.deviceList.values.forEach { device ->
                val hasPermission = usbManager.hasPermission(device)
                Log.d(TAG, "Device: ${device.productName ?: device.deviceName} - Permission Granted: $hasPermission")
            }
        }
        Log.d(TAG, "--------------------------------------")
    }
    fun logDeviceInfo(device: UsbDevice) {
        val deviceInfo = mapOf(
            "deviceName" to device.deviceName,
            "deviceId" to device.deviceId,
            "vendorId" to "0x${device.vendorId.toString(16).padStart(4, '0').uppercase()}",
            "productId" to "0x${device.productId.toString(16).padStart(4, '0').uppercase()}",
            "manufacturerName" to (device.manufacturerName ?: "Unknown"),
            "productName" to (device.productName ?: "Unknown"),
            "interfaceCount" to device.interfaceCount
        )
        
        // Log to Logcat with maximum verbosity
        Log.d(TAG, "USB DEVICE DETECTED:")
        Log.d(TAG, "--------------------------------------")
        deviceInfo.forEach { (key, value) ->
            Log.d(TAG, "$key: $value")
        }
        Log.d(TAG, "--------------------------------------")
    }

    fun onUsbDeviceAttached(device: UsbDevice) {
        Log.d(TAG, "onUsbDeviceAttached called for device: ${device.deviceName}")
        Log.d(TAG, "Device details - VID: ${device.vendorId}, PID: ${device.productId}, Class: ${device.deviceClass}, Subclass: ${device.deviceSubclass}, Protocol: ${device.deviceProtocol}")

        // Check if already connected
        if (usbConnection != null && connectedUsbDevice == device) {
            Log.d(TAG, "Device already connected")
            if (isMonitoring && eventListenerThread == null) {
                Log.d(TAG, "Starting event listener for already connected device")
                startPtpEventListener()
            }
            return
        }

        // Check if it's the Canon R5 (based on VID/PID or PTP interface)
        if (device.vendorId == 0x04A9 && device.productId == 0x32DA) { // Example Canon R5 VID/PID, check yours!
            Log.d(TAG, "Canon R5 detected, proceeding with PTP setup")
            val ptpInterface = findPtpInterface(device)
            if (ptpInterface != null) {
                Log.d(TAG, "PTP interface found, opening device connection")
                usbConnection = usbManager.openDevice(device)
                if (usbConnection != null) {
                    Log.d(TAG, "USB device opened successfully")
                    connectedUsbDevice = device
                    usbConnection!!.claimInterface(ptpInterface, true)
                    ptpInEndpoint = null
                    ptpOutEndpoint = null
                    ptpEventEndpoint = null

                    for (i in 0 until ptpInterface.endpointCount) {
                        val endpoint = ptpInterface.getEndpoint(i)
                        when (endpoint.type) {
                            UsbConstants.USB_ENDPOINT_XFER_BULK -> {
                                if (endpoint.direction == UsbConstants.USB_DIR_IN) {
                                    ptpInEndpoint = endpoint
                                    Log.d(TAG, "Found PTP IN endpoint: ${endpoint}")
                                } else {
                                    ptpOutEndpoint = endpoint
                                    Log.d(TAG, "Found PTP OUT endpoint: ${endpoint}")
                                }
                            }
                            UsbConstants.USB_ENDPOINT_XFER_INT -> {
                                if (endpoint.direction == UsbConstants.USB_DIR_IN) {
                                    ptpEventEndpoint = endpoint
                                    Log.d(TAG, "Found PTP EVENT endpoint: ${endpoint}")
                                }
                            }
                        }
                    }

                    if (ptpInEndpoint != null && ptpOutEndpoint != null && ptpEventEndpoint != null) {
                        Log.d(TAG, "All PTP endpoints found. Ready to communicate.")
                        if (isMonitoring) {
                            Log.d(TAG, "About to start PTP event listener. Current isMonitoring: $isMonitoring")
                            // Small delay to ensure everything is set up properly
                            Thread.sleep(100)
                            Log.d(TAG, "After delay, isMonitoring: $isMonitoring")
                            
                            // Initialize camera with PTP session
                            initializeCamera()
                            
                            // Start a thread to listen for PTP events (new image captured etc.)
                            startPtpEventListener()
                        } else {
                            Log.d(TAG, "Not monitoring usb port, connected to device, skipping event listener start")
                        }
                        // PTP session is now initialized
                    } else {
                        Log.e(TAG, "Missing PTP endpoints. IN: $ptpInEndpoint, OUT: $ptpOutEndpoint, EVENT: $ptpEventEndpoint")
                        usbConnection?.close()
                        usbConnection = null
                        // ADDED: Clear connected device on failure
                        connectedUsbDevice = null
                    }
                } else {
                    Log.e(TAG, "Could not open USB device.")
                }
            } else {
                Log.e(TAG, "PTP interface not found on device.")
            }
        } else {
            Log.d(TAG, "Device is not the target camera (VID: ${device.vendorId}, PID: ${device.productId})")
        }
    }

    fun onUsbDeviceDetached(device: UsbDevice) {
        Log.d(TAG, "USB device off: ${device.deviceName}")
        Log.d(TAG, "Detached device: ${device.deviceName} (VID: ${device.vendorId}, PID: ${device.productId})")
        Log.d(TAG, "Connected device: ${connectedUsbDevice?.deviceName} (VID: ${connectedUsbDevice?.vendorId}, PID: ${connectedUsbDevice?.productId})")
        Log.d(TAG, "Device match check: ${connectedUsbDevice == device}")
        Log.d(TAG, "Current isMonitoring before detach: $isMonitoring")

        if (connectedUsbDevice == device) {
            Log.d(TAG, "Device matches connected device, stopping monitoring")
            stopMonitoring()
            channel.invokeMethod("cameraDisconnected", null) // Notify Flutter
        } else {
            Log.d(TAG, "Device does not match connected device, ignoring detach event")
        }
    }

    private fun findPtpInterface(device: UsbDevice?): UsbInterface? {
        if (device == null) return null
        for (i in 0 until device.interfaceCount) {
            val usbInterface = device.getInterface(i)
            // PTP/Still Image Class: 0x06 (Image), Subclass: 0x01 (Still Image Capture), Protocol: 0x01 (PTP)
            if (usbInterface.interfaceClass == UsbConstants.USB_CLASS_STILL_IMAGE &&
                usbInterface.interfaceSubclass == 0x01 &&
                usbInterface.interfaceProtocol == 0x01) {
                return usbInterface
            }
        }
        return null
    }

    private fun startPtpEventListener() {
        Log.d(TAG, "Attempting to start PTP event listener thread.")
        eventListenerThread = thread {
            val buffer = ByteBuffer.allocate(64).order(ByteOrder.LITTLE_ENDIAN)
            Log.d(TAG, "PTP Event listener started. Waiting for camera events...")
            Log.d(TAG, "isMonitoring: $isMonitoring")
            Log.d(TAG, "usbConnection: $usbConnection")
            Log.d(TAG, "ptpEventEndpoint: $ptpEventEndpoint")

            while (isMonitoring && usbConnection != null && ptpEventEndpoint != null) {
                Log.d(TAG, "Event listener loop iteration - isMonitoring: $isMonitoring, usbConnection: ${usbConnection != null}, ptpEventEndpoint: ${ptpEventEndpoint != null}")
                try {
                    buffer.clear()
                    Log.d(TAG, "Waiting for PTP event...")
                    
                    // Use a longer timeout (5 seconds) to ensure we don't miss events
                    val bytesRead = usbConnection!!.bulkTransfer(ptpEventEndpoint, buffer.array(), buffer.capacity(), 5000)
                    
                    if (bytesRead > 0) {
                        val eventData = buffer.array().copyOf(bytesRead)
                        Log.d(TAG, "PTP Event received: ${eventData.toHexString()}, bytes: $bytesRead")

                        // Log the raw data in a more readable format
                        val hexDump = StringBuilder()
                        for (i in 0 until bytesRead) {
                            hexDump.append(String.format("%02X ", eventData[i]))
                            if ((i + 1) % 16 == 0) hexDump.append("\n")
                        }
                        Log.d(TAG, "Event data hex dump:\n$hexDump")

                        // Parse the PTP event
                        parsePtpEvent(eventData)
                    } else {
                        // This is a normal timeout, not an error.
                        Log.d(TAG, "No PTP event data received (timeout or empty packet)")
                    }
                } catch (e: InterruptedException) {
                    Log.d(TAG, "PTP Event listener interrupted. Exiting loop.")
                    Thread.currentThread().interrupt() // Preserve the interrupted status
                    break
                } catch (e: Exception) {
                    Log.e(TAG, "Error in PTP Event listener: ${e.message}")
                    // Don't break on timeout errors, just continue
                    if (e.message?.contains("timeout", ignoreCase = true) == true) {
                        Log.d(TAG, "Timeout in event listener, continuing...")
                    } else {
                        Log.e(TAG, "Non-timeout error, breaking loop.", e)
                        break
                    }
                }
            }
            Log.d(TAG, "PTP Event listener stopped. Final state - isMonitoring: $isMonitoring, usbConnection: ${usbConnection != null}, ptpEventEndpoint: ${ptpEventEndpoint != null}")
        }
    }
 


    fun onNewPhotoDetected(objectHandle: Int) {
        Log.d(TAG, "🎯 Processing new photo with handle: $objectHandle")
        val timestamp = System.currentTimeMillis()

        // Notify Flutter about the new photo detection
        channel.invokeMethod("photoDetected", mapOf(
            "objectHandle" to objectHandle,
            "timestamp" to timestamp
        ))

        // Try to get object info first
        thread {
            try {
                Log.d(TAG, "📸 Requesting photo info for handle: $objectHandle")
                val objectInfo = getObjectInfo(objectHandle)
                if (objectInfo != null) {
                    Log.d(TAG, "📄 Object Info retrieved: ${objectInfo.filename}, Size: ${objectInfo.size} bytes")
                    Log.d(TAG, "📄 Image dimensions: ${objectInfo.imagePixWidth}x${objectInfo.imagePixHeight}")

                    // Check if it's an image file
                    if (isImageFile(objectInfo.filename)) {
                        Log.d(TAG, "📥 Starting download of image: ${objectInfo.filename}")
                        // Download the actual image
                        val imagePath = downloadObject(objectHandle, objectInfo)
                        if (imagePath != null) {
                            Log.d(TAG, "✅ Image downloaded successfully: $imagePath")
                            
                            // Send complete information to Flutter
                            val imageInfo = mapOf(
                                "path" to imagePath,
                                "filename" to objectInfo.filename,
                                "timestamp" to timestamp,
                                "width" to objectInfo.imagePixWidth,
                                "height" to objectInfo.imagePixHeight,
                                "size" to objectInfo.size,
                                "objectHandle" to objectHandle
                            )
                            
                            // Add to our list of downloaded photos
                            synchronized(downloadedPhotos) {
                                downloadedPhotos.add(imageInfo)
                                // Keep list at a reasonable size (last 100 photos)
                                if (downloadedPhotos.size > 100) {
                                    downloadedPhotos.removeAt(0)
                                }
                            }
                            
                            channel.invokeMethod("newImageCaptured", imageInfo)
                            
                            // For good measure, also force the media scanner to detect the new file
                            try {
                                val scanIntent = Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE)
                                scanIntent.data = Uri.fromFile(File(imagePath))
                                context.sendBroadcast(scanIntent)
                                Log.d(TAG, "🔍 Media scanner notification sent for: $imagePath")
                            } catch (e: Exception) {
                                Log.e(TAG, "⚠️ Failed to notify media scanner: ${e.message}")
                            }
                        } else {
                            Log.e(TAG, "❌ Failed to download image")
                            channel.invokeMethod("downloadError", mapOf(
                                "objectHandle" to objectHandle,
                                "error" to "Failed to download image"
                            ))
                        }
                    } else {
                        Log.d(TAG, "📄 Non-image file detected: ${objectInfo.filename}")
                    }
                } else {
                    Log.e(TAG, "❌ Failed to get object info for handle: $objectHandle")
                    channel.invokeMethod("downloadError", mapOf(
                        "objectHandle" to objectHandle,
                        "error" to "Failed to get object info"
                    ))
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error processing new photo: ${e.message}")
                e.printStackTrace()
                channel.invokeMethod("downloadError", mapOf(
                    "objectHandle" to objectHandle,
                    "error" to "Exception: ${e.message}"
                ))
            }
        }
    }
    // This part would involve building PTP command packets and sending/receiving via bulkTransfer
    // PTP commands are complex: Operation Code, Transaction ID, Parameters.
    // PTP Data Phase: For transferring image data.
    // PTP Response Phase: For receiving command results.

    fun parsePtpEvent(eventData: ByteArray) {
        if (eventData.size < 8) {
            Log.w(TAG, "Event data too short: ${eventData.size} bytes")
            return
        }

        try {
            val buffer = ByteBuffer.wrap(eventData).order(ByteOrder.LITTLE_ENDIAN)

            // Different cameras may use different event packet structures
            // Try to be flexible in parsing
            
            // Standard PTP Event packet structure:
            // Length (4 bytes) - EventCode (2 bytes) - TransactionID (4 bytes) - Parameters (variable)
            
            // Log all possible interpretations for debugging
            Log.d(TAG, "📸 CAMERA MONITORING: Analyzing storage event...")
            
            // Try multiple formats to maximize camera compatibility
            
            // Try standard PTP event format
            try {
                buffer.rewind()
                val length = buffer.int
                val eventCode = buffer.short.toInt() and 0xFFFF
                val transactionId = buffer.int
                Log.d(TAG, "📸 CAMERA STORAGE: Standard PTP Event Format - Length: $length, EventCode: 0x${eventCode.toString(16)}, TransactionID: $transactionId")
            } catch (e: Exception) {
                Log.d(TAG, "Could not parse as standard PTP event: ${e.message}")
            }
            
            // Try alternative format (some cameras have different ordering)
            try {
                buffer.rewind()
                val eventCode = buffer.short.toInt() and 0xFFFF
                val transactionId = buffer.int
                Log.d(TAG, "📸 CAMERA STORAGE: Alternative PTP Event Format - EventCode: 0x${eventCode.toString(16)}, TransactionID: $transactionId")
            } catch (e: Exception) {
                Log.d(TAG, "Could not parse as alternative PTP event: ${e.message}")
            }
            
            // Reset and use standard format for actual processing
            buffer.rewind()
            val length = buffer.int
            val eventCode = buffer.short.toInt() and 0xFFFF
            val transactionId = buffer.int

            Log.d(TAG, "📸 CAMERA STORAGE: Processing event - Type: 0x${eventCode.toString(16)}, TransactionID: $transactionId")

            when (eventCode) {
                PTP_EVENT_OBJECT_ADDED -> {
                    // Parameters: ObjectHandle (4 bytes)
                    if (buffer.remaining() >= 4) {
                        val objectHandle = buffer.int
                        Log.d(TAG, "📸 STORAGE MONITOR: New photo detected in camera storage! ObjectHandle: $objectHandle")
                        
                        // Trigger copy function immediately when a new photo is detected
                        Log.d(TAG, "📸 STORAGE MONITOR: Triggering copy function for new photo")
                        onNewPhotoDetected(objectHandle)
                    }
                }
                PTP_EVENT_CAPTURE_COMPLETE -> {
                    Log.d(TAG, "📸 STORAGE MONITOR: Camera capture complete, waiting for storage update")
                    // Some cameras send this instead of OBJECT_ADDED or before OBJECT_ADDED
                    // We'll notify Flutter but not trigger the copy yet
                    channel.invokeMethod("captureComplete", null)
                    
                    // For cameras that don't send OBJECT_ADDED after CAPTURE_COMPLETE,
                    // we could optionally poll the storage for changes here
                }
                PTP_EVENT_DEVICE_PROP_CHANGED -> {
                    if (buffer.remaining() >= 4) {
                        val propertyCode = buffer.int
                        Log.d(TAG, "📸 STORAGE MONITOR: Camera property changed: 0x${propertyCode.toString(16)}")
                        
                        // Some cameras update storage-related properties when new photos are added
                        // Check if this is a storage-related property
                        if (propertyCode == 0xD20A || // StorageID
                            propertyCode == 0xD201 || // Battery Level
                            propertyCode == 0xD303) { // Image Size
                            Log.d(TAG, "📸 STORAGE MONITOR: Storage-related property changed, may indicate new photo")
                        }
                    }
                }
                PTP_EVENT_STORE_ADDED -> {
                    // Parameters: StorageID (4 bytes)
                    if (buffer.remaining() >= 4) {
                        val storageID = buffer.int
                        Log.d(TAG, "📸 STORAGE MONITOR: New storage detected! StorageID: 0x${storageID.toString(16)}")
                        
                        // When a new storage is added, we should check for new photos
                        // This could happen if a memory card was inserted
                        channel.invokeMethod("storageAdded", storageID.toString())
                    }
                }
                PTP_EVENT_STORE_REMOVED -> {
                    // Parameters: StorageID (4 bytes)
                    if (buffer.remaining() >= 4) {
                        val storageID = buffer.int
                        Log.d(TAG, "📸 STORAGE MONITOR: Storage removed! StorageID: 0x${storageID.toString(16)}")
                    }
                }
                PTP_EVENT_STORAGE_INFO_CHANGED -> {
                    // Parameters: StorageID (4 bytes)
                    if (buffer.remaining() >= 4) {
                        val storageID = buffer.int
                        Log.d(TAG, "📸 STORAGE MONITOR: Storage info changed! StorageID: 0x${storageID.toString(16)}")
                        
                        // This is important - some cameras trigger this when new photos are added
                        // but don't send OBJECT_ADDED. We should check for new photos.
                        Log.d(TAG, "📸 STORAGE MONITOR: Storage changed - checking for new photos")
                        channel.invokeMethod("storageInfoChanged", storageID.toString())
                    }
                }
                else -> {
                    Log.d(TAG, "📋 OTHER EVENT: Code 0x${eventCode.toString(16)}")
                    // Log any additional parameters
                    val params = mutableListOf<Int>()
                    while (buffer.remaining() >= 4) {
                        params.add(buffer.int)
                    }
                    if (params.isNotEmpty()) {
                        Log.d(TAG, "Event parameters: ${params.joinToString(", ")}")
                    }
                    
                    // Dump the raw event data for analysis
                    buffer.rewind()
                    val hexDump = StringBuilder("Full event data: ")
                    val remaining = buffer.remaining()
                    
                    for (i in 0 until remaining) {
                        if (i % 16 == 0 && i > 0) {
                            hexDump.append("\n                   ")
                        }
                        hexDump.append(String.format("%02X ", buffer.get()))
                    }
                    
                    Log.d(TAG, hexDump.toString())
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing PTP event: ${e.message}")
        }
    }

    // PTP Data structures
    data class PtpObjectInfo(
        val objectHandle: Int,
        val storageId: Int,
        val objectFormat: Int,
        val protectionStatus: Short,
        val objectCompressedSize: Int,
        val thumbFormat: Short,
        val thumbCompressedSize: Int,
        val thumbPixWidth: Int,
        val thumbPixHeight: Int,
        val imagePixWidth: Int,
        val imagePixHeight: Int,
        val imageBitDepth: Int,
        val parentObject: Int,
        val associationType: Short,
        val associationDesc: Int,
        val sequenceNumber: Int,
        val filename: String,
        val captureDate: String,
        val modificationDate: String,
        val keywords: String,
        val size: Int = objectCompressedSize
    )

    private var transactionId = 1

    private fun sendPtpCommand(operationCode: Int, parameters: IntArray = intArrayOf()): ByteBuffer? {
        if (usbConnection == null || ptpOutEndpoint == null) {
            Log.e(TAG, "Cannot send PTP command: USB connection not ready")
            return null
        }

        val paramCount = parameters.size
        val packetSize = 12 + (paramCount * 4) // Header + parameters

        val buffer = ByteBuffer.allocate(packetSize).order(ByteOrder.LITTLE_ENDIAN)

        // PTP Command packet structure:
        // Length (4) - Type (2) - Code (2) - TransactionID (4) - Parameters (4 * N)
        buffer.putInt(packetSize)              // Length
        buffer.putShort(1)                     // Type: Command (0x01)
        buffer.putShort(operationCode.toShort()) // Operation Code
        buffer.putInt(transactionId)           // Transaction ID - don't increment until success
        parameters.forEach { buffer.putInt(it) } // Parameters

        val bytesSent = usbConnection!!.bulkTransfer(ptpOutEndpoint, buffer.array(), packetSize, 1000)
        if (bytesSent != packetSize) {
            Log.e(TAG, "Failed to send PTP command. Sent $bytesSent of $packetSize bytes")
            return null
        }

        Log.d(TAG, "Sent PTP command: 0x${operationCode.toString(16)}, TransactionID: ${transactionId - 1}")
        return buffer
    }

    /**
     * Receive the PTP response phase
     * Some cameras may not send a response for certain operations, so we need to handle that gracefully
     */
    private fun receivePtpResponse(): ByteBuffer? {
        if (usbConnection == null || ptpInEndpoint == null) {
            return null
        }
        
        try {
            val responseBuffer = ByteBuffer.allocate(512).order(ByteOrder.LITTLE_ENDIAN)
            
            // Try to receive a response with a reasonable timeout
            val bytesRead = usbConnection!!.bulkTransfer(ptpInEndpoint, responseBuffer.array(), responseBuffer.capacity(), 2000)
            
            if (bytesRead <= 0) {
                // Some cameras don't send a proper response phase for GetStorageIDs
                // Check which command this response is for by looking at the transaction ID
                Log.e(TAG, "Failed to receive PTP response (bytesRead=$bytesRead)")
                
                // For storage browsing operations, we'll provide a fallback "OK" response
                // This is a workaround for cameras that don't follow the PTP spec strictly
                if (transactionId > 1) { // Assuming first transaction is OpenSession
                    Log.d(TAG, "Creating synthetic OK response for transaction ID: ${transactionId - 1}")
                    val syntheticResponse = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
                    syntheticResponse.putInt(12) // Length
                    syntheticResponse.putShort(3) // Type: Response (0x03)
                    syntheticResponse.putShort(PTP_RESPONSE_OK.toShort()) // Response Code: OK
                    syntheticResponse.putInt(transactionId - 1) // Transaction ID
                    syntheticResponse.rewind()
                    return syntheticResponse
                }
                
                return null
            }
            
            responseBuffer.limit(bytesRead)
            
            // Debug the response
            responseBuffer.rewind()
            val length = responseBuffer.int
            val type = responseBuffer.short.toInt() and 0xFFFF
            val responseCode = responseBuffer.short.toInt() and 0xFFFF
            val responseTransactionId = responseBuffer.int
            
            Log.d(TAG, "PTP Response received: Length=$length, Type=0x${type.toString(16)}, " +
                       "Code=0x${responseCode.toString(16)}, TransactionID=$responseTransactionId")
            
            responseBuffer.rewind()
            return responseBuffer
        } catch (e: Exception) {
            Log.e(TAG, "Error receiving PTP response: ${e.message}")
            return null
        }
    }

    private fun getObjectInfo(objectHandle: Int): PtpObjectInfo? {
        // Make sure we have an open session
        ensureSessionOpen()
        
        var retryCount = 0
        val maxRetries = 2
        
        while (retryCount <= maxRetries) {
            try {
                Log.d(TAG, "Requesting object info for handle: $objectHandle (attempt ${retryCount + 1})")
        
                // Send GetObjectInfo command
                val commandSent = sendPtpCommand(PTP_GET_OBJECT_INFO, intArrayOf(objectHandle))
                if (commandSent == null) {
                    Log.e(TAG, "Failed to send GetObjectInfo command")
                    retryCount++
                    if (retryCount > maxRetries) break
                    Thread.sleep(100)
                    continue
                }
                
                // Try to get data phase first (some cameras send data before response)
                val dataBuffer = receivePtpData()
                
                // Receive response 
                val responseHeader = receivePtpResponse()
                if (responseHeader == null) {
                    Log.e(TAG, "Failed to receive GetObjectInfo response")
                    retryCount++
                    if (retryCount > maxRetries) break
                    Thread.sleep(100)
                    continue
                }
        
                responseHeader.rewind()
                val length = responseHeader.int
                val type = responseHeader.short.toInt() and 0xFFFF
                val responseCode = responseHeader.short.toInt() and 0xFFFF
                val respTransactionId = responseHeader.int
        
                Log.d(TAG, "GetObjectInfo Response - Type: 0x${type.toString(16)}, Code: 0x${responseCode.toString(16)}")
        
                if (responseCode != 0x2001) { // OK response
                    Log.e(TAG, "GetObjectInfo failed with response code: 0x${responseCode.toString(16)}")
                    retryCount++
                    if (retryCount > maxRetries) break
                    Thread.sleep(100)
                    continue
                }
                
                // If we didn't get data during the first attempt, try again
                if (dataBuffer == null) {
                    val secondDataBuffer = receivePtpData(length - 12) // Subtract header size
                    if (secondDataBuffer == null) {
                        Log.e(TAG, "Failed to receive GetObjectInfo data")
                        retryCount++
                        if (retryCount > maxRetries) break
                        Thread.sleep(100)
                        continue
                    }
                    return parseObjectInfo(secondDataBuffer, objectHandle)
                } else {
                    return parseObjectInfo(dataBuffer, objectHandle)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error getting object info: ${e.message}")
                e.printStackTrace()
                retryCount++
                if (retryCount > maxRetries) break
                Thread.sleep(100)
            }
        }
        
        // If we've exhausted retries, return a fallback object info
        // This allows the UI to show something rather than nothing
        Log.w(TAG, "Using fallback object info for handle: $objectHandle")
        return PtpObjectInfo(
            storageId = 0x00010001,
            objectFormat = 0x3000, // Undefined format
            protectionStatus = 0,
            objectCompressedSize = 0,
            thumbFormat = 0,
            thumbCompressedSize = 0,
            thumbPixWidth = 0,
            thumbPixHeight = 0,
            imagePixWidth = 0,
            imagePixHeight = 0,
            imageBitDepth = 0,
            parentObject = 0,
            associationType = 0,
            associationDesc = 0,
            sequenceNumber = 0,
            filename = "Object_${objectHandle.toString(16)}",
            captureDate = "",
            modificationDate = "",
            keywords = "",
            size = 0,
        )
    }

    /**
     * Receive PTP data phase with expected size
     */
    private fun receivePtpData(expectedSize: Int): ByteBuffer? {
        val dataBuffer = ByteBuffer.allocate(expectedSize).order(ByteOrder.LITTLE_ENDIAN)
        val bytesRead = usbConnection!!.bulkTransfer(ptpInEndpoint, dataBuffer.array(), dataBuffer.capacity(), 5000)

        if (bytesRead != expectedSize) {
            Log.e(TAG, "Expected $expectedSize bytes of data, received $bytesRead")
            return null
        }

        dataBuffer.limit(bytesRead)
        return dataBuffer
    }
    
    /**
     * Receive PTP data phase without knowing the expected size
     * First reads the header to determine the data size, then reads the rest of the data
     */
    private fun receivePtpData(): ByteBuffer? {
        if (usbConnection == null || ptpInEndpoint == null) {
            Log.e(TAG, "Cannot receive PTP data: USB connection not ready")
            return null
        }
        
        var retryCount = 0
        val maxRetries = 3
        
        while (retryCount < maxRetries) {
            try {
                // First read the PTP container header (12 bytes) to get the total length
                val headerBuffer = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
                val headerBytesRead = usbConnection!!.bulkTransfer(ptpInEndpoint, headerBuffer.array(), headerBuffer.capacity(), 3000)
                
                if (headerBytesRead != 12) {
                    Log.e(TAG, "Failed to read PTP data header, received $headerBytesRead bytes (attempt ${retryCount + 1})")
                    // Try to clear the endpoint before retry
                    if (retryCount < maxRetries - 1) {
                        try {
                            clearUsbEndpoint()
                            Thread.sleep(100 * (retryCount + 1)) // Increasing delay for each retry
                            retryCount++
                            continue
                        } catch (e: Exception) {
                            Log.e(TAG, "Error clearing endpoint: ${e.message}")
                        }
                    }
                    return null
                }
                
                // Parse the header to get the total length
                headerBuffer.rewind()
                val totalLength = headerBuffer.int
                
                if (totalLength < 12 || totalLength > 1024*1024*10) { // Sanity check (max 10MB)
                    Log.e(TAG, "Invalid PTP data length: $totalLength bytes (attempt ${retryCount + 1})")
                    // Try again
                    if (retryCount < maxRetries - 1) {
                        clearUsbEndpoint()
                        Thread.sleep(100 * (retryCount + 1))
                        retryCount++
                        continue
                    }
                    return null
                }
                
                Log.d(TAG, "PTP data phase: total packet length is $totalLength bytes")
                
                // Allocate buffer for the entire data (including the header we already read)
                val fullBuffer = ByteBuffer.allocate(totalLength).order(ByteOrder.LITTLE_ENDIAN)
                
                // Copy the header into the full buffer
                fullBuffer.put(headerBuffer.array(), 0, 12)
                
                // Read the rest of the data
                val remainingBytes = totalLength - 12
                if (remainingBytes > 0) {
                    val timeout = minOf(5000 + (remainingBytes / 1024) * 100, 15000) // Dynamic timeout based on size, max 15 seconds
                    
                    val dataBytesRead = usbConnection!!.bulkTransfer(
                        ptpInEndpoint, 
                        fullBuffer.array(), 
                        12, // Start after header
                        remainingBytes, 
                        timeout
                    )
                    
                    // Check if we got partial data
                    if (dataBytesRead > 0 && dataBytesRead < remainingBytes) {
                        Log.w(TAG, "Received partial data: $dataBytesRead of $remainingBytes bytes")
                        
                        // For small data differences, we might be able to proceed with what we have
                        if (dataBytesRead > remainingBytes * 0.9) { // If we got at least 90%
                            Log.w(TAG, "Proceeding with partial data (${dataBytesRead * 100 / remainingBytes}%)")
                            // Adjust the buffer limit to the actual data received
                            fullBuffer.limit(12 + dataBytesRead)
                            return fullBuffer
                        }
                    } else if (dataBytesRead <= 0) {
                        Log.e(TAG, "Failed to read PTP data payload: $dataBytesRead (attempt ${retryCount + 1})")
                        
                        // Try again if not on last attempt
                        if (retryCount < maxRetries - 1) {
                            clearUsbEndpoint()
                            Thread.sleep(200 * (retryCount + 1))
                            retryCount++
                            continue
                        }
                        return null
                    }
                    
                    if (dataBytesRead != remainingBytes) {
                        Log.w(TAG, "Failed to read complete PTP data, expected $remainingBytes more bytes, received $dataBytesRead")
                        // Proceed anyway with the data we have
                        fullBuffer.limit(12 + dataBytesRead)
                    }
                }
                
                return fullBuffer
            } catch (e: Exception) {
                Log.e(TAG, "Error receiving PTP data: ${e.message} (attempt ${retryCount + 1})")
                e.printStackTrace()
                
                if (retryCount < maxRetries - 1) {
                    try {
                        clearUsbEndpoint()
                        Thread.sleep(200 * (retryCount + 1))
                    } catch (ex: Exception) {
                        // Ignore
                    }
                    retryCount++
                    continue
                }
                return null
            }
        }
        
        // If we've exhausted all retries
        return null
    }
    
    /**
     * Attempt to clear the USB endpoint to recover from error states
     */
    private fun clearUsbEndpoint() {
        try {
            if (usbConnection != null && ptpInEndpoint != null) {
                usbConnection!!.clearFeature(ptpInEndpoint!!)
                Log.d(TAG, "USB in endpoint cleared")
            }
            
            if (usbConnection != null && ptpOutEndpoint != null) {
                usbConnection!!.clearFeature(ptpOutEndpoint!!)
                Log.d(TAG, "USB out endpoint cleared")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error clearing USB endpoints: ${e.message}")
        }
    }

    private fun parseObjectInfo(dataBuffer: ByteBuffer, objectHandle: Int): PtpObjectInfo? {
        try {
            dataBuffer.rewind()

            val storageId = dataBuffer.int
            val objectFormat = dataBuffer.int
            val protectionStatus = dataBuffer.short
            dataBuffer.int // Skip object compressed size (we'll calculate from data)
            val thumbFormat = dataBuffer.short
            val thumbCompressedSize = dataBuffer.int
            val thumbPixWidth = dataBuffer.int
            val thumbPixHeight = dataBuffer.int
            val imagePixWidth = dataBuffer.int
            val imagePixHeight = dataBuffer.int
            val imageBitDepth = dataBuffer.int
            val parentObject = dataBuffer.int
            val associationType = dataBuffer.short
            val associationDesc = dataBuffer.int
            val sequenceNumber = dataBuffer.int

            // Read filename (null-terminated string)
            val filenameBytes = mutableListOf<Byte>()
            var byte: Byte
            while (dataBuffer.hasRemaining()) {
                byte = dataBuffer.get()
                if (byte == 0.toByte()) break
                filenameBytes.add(byte)
            }
            val filename = String(filenameBytes.toByteArray(), Charsets.UTF_8)

            // Skip remaining fields for simplicity
            val captureDate = ""
            val modificationDate = ""
            val keywords = ""

            // Get actual file size by reading the data phase length from response
            val size = dataBuffer.remaining()

            return PtpObjectInfo(
                objectHandle = objectHandle,
                storageId = storageId,
                objectFormat = objectFormat,
                protectionStatus = protectionStatus,
                objectCompressedSize = size,
                thumbFormat = thumbFormat,
                thumbCompressedSize = thumbCompressedSize,
                thumbPixWidth = thumbPixWidth,
                thumbPixHeight = thumbPixHeight,
                imagePixWidth = imagePixWidth,
                imagePixHeight = imagePixHeight,
                imageBitDepth = imageBitDepth,
                parentObject = parentObject,
                associationType = associationType,
                associationDesc = associationDesc,
                sequenceNumber = sequenceNumber,
                filename = filename,
                captureDate = captureDate,
                modificationDate = modificationDate,
                keywords = keywords,
                size = size
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing object info: ${e.message}")
            return null
        }
    }

    private fun downloadObject(objectHandle: Int, objectInfo: PtpObjectInfo): String? {
        Log.d(TAG, "Downloading object: ${objectInfo.filename} (${objectInfo.size} bytes)")

        // Send GetObject command
        val commandSent = sendPtpCommand(PTP_GET_OBJECT, intArrayOf(objectHandle))
        if (commandSent == null) return null

        // Receive response header
        val responseHeader = receivePtpResponse()
        if (responseHeader == null) return null

        responseHeader.rewind()
        val length = responseHeader.int
        val type = responseHeader.short.toInt() and 0xFFFF
        val responseCode = responseHeader.short.toInt() and 0xFFFF

        if (responseCode != 0x2001) { // OK response
            Log.e(TAG, "GetObject failed with response code: 0x${responseCode.toString(16)}")
            return null
        }

        // Receive data phase
        val dataBuffer = receivePtpData(objectInfo.size)
        if (dataBuffer == null) return null

        // Save to file
        return saveImageData(dataBuffer, objectInfo.filename)
    }

    private fun saveImageData(dataBuffer: ByteBuffer, filename: String): String? {
        try {
            // First try to use the public Pictures directory (visible in Gallery)
            val publicDir = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // For Android 10+ we need to use MediaStore
                null
            } else {
                File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "DirectSync")
            }
            
            // Create a folder structure with date to organize images
            val dateFormat = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
            val dateStr = dateFormat.format(java.util.Date())
            
            var savedPath: String? = null
            val timestamp = System.currentTimeMillis()
            
            // Method 1: For Android 9 and below - direct file access
            if (publicDir != null) {
                publicDir.mkdirs()
                val dateDir = File(publicDir, dateStr)
                dateDir.mkdirs()
                
                val file = File(dateDir, "camera_${timestamp}_${filename}")
                FileOutputStream(file).use { fos ->
                    val data = ByteArray(dataBuffer.remaining())
                    dataBuffer.get(data)
                    fos.write(data)
                }
                savedPath = file.absolutePath
            } 
            // Method 2: For all Android versions - app's storage (backup method)
            else {
                val picturesDir = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES)
                    ?: context.cacheDir
                
                val syncDir = File(picturesDir, "DirectSync")
                syncDir.mkdirs()
                val dateDir = File(syncDir, dateStr)
                dateDir.mkdirs()
                
                val file = File(dateDir, "camera_${timestamp}_${filename}")
                FileOutputStream(file).use { fos ->
                    val data = ByteArray(dataBuffer.remaining())
                    dataBuffer.get(data)
                    fos.write(data)
                }
                savedPath = file.absolutePath
            }

            Log.d(TAG, "✅ Image saved successfully to: $savedPath")
            
            // Notify system to scan the file so it shows up in Gallery immediately
            if (savedPath != null) {
                val scanIntent = Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE)
                scanIntent.data = Uri.fromFile(File(savedPath))
                context.sendBroadcast(scanIntent)
            }
            
            return savedPath
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error saving image data: ${e.message}")
            e.printStackTrace()
            return null
        }
    }

    private fun isImageFile(filename: String): Boolean {
        // Supported image formats, including RAW formats from common camera brands
        val imageExtensions = listOf(
            // Common image formats
            ".jpg", ".jpeg", ".png", ".heic", ".heif",
            // Canon RAW formats
            ".cr2", ".cr3", 
            // Sony RAW format
            ".arw", 
            // Nikon RAW format
            ".nef", 
            // Universal RAW format
            ".dng",
            // Other common RAW formats
            ".raf", // Fuji
            ".orf", // Olympus
            ".rw2", // Panasonic
            ".pef", // Pentax
            ".srw"  // Samsung
        )
        return imageExtensions.any { filename.lowercase().endsWith(it) }
    }

    private fun simulateImageDownloadAndNotifyFlutter() {
        // This is a placeholder for actual PTP image download logic
        // In a real scenario, you'd:
        // 1. Send PTP GetObjectInfo command for the new object handle.
        // 2. Parse the object info to get filename, size, format.
        // 3. Send PTP GetObject command to download the image data.
        // 4. Write the received data to a file.

        try {
            val tempDir = context.cacheDir // Or context.getExternalFilesDir(Environment.DIRECTORY_PICTURES)
            val tempFile = File(tempDir, "camera_image_${System.currentTimeMillis()}.jpg")
            FileOutputStream(tempFile).use { fos ->
                // Simulate writing some dummy image data
                fos.write(ByteArray(1024 * 500) { it.toByte() }) // 500KB dummy data
            }
            Log.d(TAG, "Simulated image saved to: ${tempFile.absolutePath}")
            // Notify Flutter about the new image
            channel.invokeMethod("newImageCaptured", tempFile.absolutePath)
        } catch (e: Exception) {
            Log.e(TAG, "Error simulating image download: ${e.message}")
        }
    }
    
    /**
     * Initialize PTP session with camera and enable event notifications
     */
    private fun initializeCamera() {
        try {
            // Check if we have necessary connections
            if (usbConnection == null || ptpInEndpoint == null || ptpOutEndpoint == null) {
                Log.e(TAG, "Cannot initialize camera - missing USB connection or endpoints")
                return
            }
            
            Log.d(TAG, "Initializing camera PTP session...")
            
            // Step 1: Open PTP Session
            val sessionId = openPtpSession()
            if (sessionId <= 0) {
                Log.e(TAG, "Failed to open PTP session")
                return
            }
            Log.d(TAG, "PTP session opened successfully. Session ID: $sessionId")
            
            // Step 2: Enable event notification (if needed by camera)
            enableEventNotifications()
            
            Log.d(TAG, "Camera initialized successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing camera: ${e.message}")
            e.printStackTrace()
        }
    }
    
    /**
     * Open a PTP session with the camera
     */
    private fun openPtpSession(): Int {
        try {
            // PTP OpenSession command (0x1002)
            val commandBuffer = sendPtpCommand(PTP_OPERATION_OPEN_SESSION, intArrayOf(1)) // Using session ID = 1
            if (commandBuffer == null) {
                Log.e(TAG, "Failed to send OpenSession command")
                return -1
            }
            
            // Get the response
            val responseBuffer = receivePtpResponse()
            if (responseBuffer == null) {
                Log.e(TAG, "Failed to receive OpenSession response")
                return -1
            }
            
            // Parse response
            val type = responseBuffer.short.toInt() and 0xFFFF
            val responseCode = responseBuffer.short.toInt() and 0xFFFF
            val transactionId = responseBuffer.int
            
            Log.d(TAG, "OpenSession Response - Type: 0x${type.toString(16)}, Code: 0x${responseCode.toString(16)}")
            
            if (responseCode != PTP_RESPONSE_OK && responseCode != 0x201E) { // OK or Session Already Open
                Log.e(TAG, "OpenSession failed with response code: 0x${responseCode.toString(16)}")
                return -1
            }
            
            return 1 // Session ID = 1
        } catch (e: Exception) {
            Log.e(TAG, "Error opening PTP session: ${e.message}")
            e.printStackTrace()
            return -1
        }
    }
    
    /**
     * Make sure a PTP session is open with the camera
     * This should be called before any operation that requires a session
     */
    private fun ensureSessionOpen() {
        synchronized(this) {
            if (!isInitialized) {
                Log.d(TAG, "Device not initialized, trying to initialize now")
                if (usbConnection != null && ptpInEndpoint != null && ptpOutEndpoint != null) {
                    try {
                        val sessionId = openPtpSession()
                        if (sessionId > 0) {
                            Log.d(TAG, "Successfully opened PTP session")
                            isInitialized = true
                        } else {
                            Log.e(TAG, "Failed to open PTP session during ensureSessionOpen")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error ensuring session is open: ${e.message}")
                        e.printStackTrace()
                    }
                } else {
                    Log.e(TAG, "Cannot ensure session - USB connection or endpoints not available")
                }
            }
        }
    }
    
    /**
     * Enable event notifications from the camera
     */
    private fun enableEventNotifications() {
        try {
            // Some cameras need specific property configurations to enable event notifications
            // For Canon cameras, sometimes you need to set property 0xD20D (Event Mode) to 2 (PC Connect)
            
            Log.d(TAG, "Attempting to enable event notifications...")
            
            // Example: Set property 0xD20D to 2 for Canon cameras
            // This is camera-specific, so may need adaptation for different models
            val propertyCode = 0xD20D // Canon Event Mode property
            val propertyValue = 2     // PC Connect mode
            
            val commandBuffer = sendPtpCommand(PTP_OPERATION_SET_DEVICE_PROP_VALUE, intArrayOf(propertyCode, propertyValue))
            if (commandBuffer == null) {
                Log.e(TAG, "Failed to send SetDevicePropValue command")
                return
            }
            
            val responseBuffer = receivePtpResponse()
            if (responseBuffer != null) {
                val type = responseBuffer.short.toInt() and 0xFFFF
                val responseCode = responseBuffer.short.toInt() and 0xFFFF
                
                Log.d(TAG, "SetDevicePropValue Response - Type: 0x${type.toString(16)}, Code: 0x${responseCode.toString(16)}")
                
                if (responseCode == PTP_RESPONSE_OK) {
                    Log.d(TAG, "Event notifications enabled successfully")
                } else {
                    // This command might not work on all cameras, so we don't consider it a failure
                    Log.w(TAG, "Could not enable event notifications with property $propertyCode, code: 0x${responseCode.toString(16)}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error enabling event notifications: ${e.message}")
            e.printStackTrace()
        }
    }
    
    /**
     * Get list of available storage IDs from camera
     * @return List of storage IDs or null if operation failed
     */
    fun getStorageIds(): List<Int>? {
        // Try multiple times with different approaches
        for (attempt in 1..3) {
            try {
                Log.d(TAG, "Getting storage IDs from camera (attempt $attempt)...")
                
                // Make sure we have an open session first
                ensureSessionOpen()
                
                // Sleep a bit to let the camera process the session
                Thread.sleep(200)
                
                // Send PTP command to get storage IDs
                val commandBuffer = sendPtpCommand(PTP_GET_STORAGE_IDS)
                if (commandBuffer == null) {
                    Log.e(TAG, "Failed to send GetStorageIDs command (attempt $attempt)")
                    continue // Try next approach
                }
                
                // Receive data phase (contains the actual storage IDs)
                val dataBuffer = receivePtpData()
                if (dataBuffer == null) {
                    Log.e(TAG, "Failed to receive GetStorageIDs data (attempt $attempt)")
                    
                    // If this is not our last attempt, try again
                    if (attempt < 3) {
                        continue
                    }
                    
                    // If data phase failed on last attempt, use fallback approach
                    Log.d(TAG, "Attempting fallback storage detection...")
                    return getFallbackStorageIds()
                }
                
                // Receive response phase
                val responseBuffer = receivePtpResponse()
                if (responseBuffer == null) {
                    Log.w(TAG, "Warning: Failed to receive GetStorageIDs response, but proceeding with data (attempt $attempt)")
                    // Continue even without response - some cameras don't send it
                } else {
                    // Check response code
                    responseBuffer.rewind()
                    responseBuffer.position(6) // Skip to response code
                    val responseCode = responseBuffer.short.toInt() and 0xFFFF
                    if (responseCode != PTP_RESPONSE_OK) {
                        Log.w(TAG, "GetStorageIDs command got non-OK response: 0x${responseCode.toString(16)}, but proceeding")
                        // Continue anyway - we have the data
                    }
                }
                
                try {
                    // Parse data buffer to extract storage IDs
                    dataBuffer.rewind()
                    dataBuffer.position(8) // Skip header
                    
                    if (dataBuffer.remaining() < 4) {
                        Log.e(TAG, "Data buffer too small for storage IDs count (attempt $attempt)")
                        continue // Try next approach
                    }
                    
                    val numIds = dataBuffer.int // Number of storage IDs
                    
                    // Sanity check for number of IDs (should be reasonable)
                    if (numIds < 0 || numIds > 100) {
                        Log.e(TAG, "Unreasonable number of storage IDs: $numIds (attempt $attempt)")
                        continue // Try next approach
                    }
                    
                    Log.d(TAG, "Camera reports $numIds available storage IDs")
                    
                    val storageIds = mutableListOf<Int>()
                    for (i in 0 until numIds) {
                        if (dataBuffer.remaining() >= 4) {
                            val storageId = dataBuffer.int
                            Log.d(TAG, "Found storage ID: 0x${storageId.toString(16)}")
                            storageIds.add(storageId)
                        }
                    }
                    
                    if (storageIds.isNotEmpty()) {
                        return storageIds
                    } else {
                        Log.w(TAG, "No storage IDs parsed from data (attempt $attempt)")
                        // If this is our last attempt, use fallback
                        if (attempt == 3) {
                            return getFallbackStorageIds()
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error parsing storage IDs data: ${e.message} (attempt $attempt)")
                    e.printStackTrace()
                    // Continue to next attempt
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Error getting storage IDs: ${e.message} (attempt $attempt)")
                e.printStackTrace()
                // Continue to next attempt
            }
            
            // Add a small delay between attempts
            Thread.sleep(300)
        }
        
        // If all attempts failed, use fallback
        return getFallbackStorageIds()
    }
    
    /**
     * Generate fallback storage IDs for when the camera doesn't respond properly
     */
    private fun getFallbackStorageIds(): List<Int> {
        Log.d(TAG, "Using fallback storage IDs")
        // Common storage IDs used by different camera brands
        return listOf(
            0x00010001,  // Standard PTP storage ID
            0x00000001   // Alternative storage ID used by some cameras
        )
    }
    
    /**
     * Get detailed information about a specific storage ID
     * @param storageId The storage ID to get info for
     * @return Storage info as a map or null if operation failed
     */
    fun getStorageInfo(storageId: Int): Map<String, Any>? {
        // Make sure session is open
        ensureSessionOpen()
        
        var retryCount = 0
        val maxRetries = 3
        
        while (retryCount < maxRetries) {
            try {
                Log.d(TAG, "Getting info for storage ID: 0x${storageId.toString(16)} (attempt ${retryCount + 1})")
                
                // Send PTP command to get storage info
                val commandBuffer = sendPtpCommand(PTP_GET_STORAGE_INFO, intArrayOf(storageId))
                if (commandBuffer == null) {
                    Log.e(TAG, "Failed to send GetStorageInfo command")
                    retryCount++
                    Thread.sleep(100) // Small delay before retry
                    continue
                }
                
                // Receive data phase
                val dataBuffer = receivePtpData()
                if (dataBuffer == null) {
                    Log.w(TAG, "Failed to receive GetStorageInfo data, trying to proceed anyway")
                    // Instead of returning null, try to continue with response
                }
                
                // Receive response phase
                val responseBuffer = receivePtpResponse()
                if (responseBuffer == null) {
                    Log.e(TAG, "Failed to receive GetStorageInfo response")
                    retryCount++
                    Thread.sleep(100) // Small delay before retry
                    continue
                }
                
                // Check response code
                responseBuffer.rewind()
                responseBuffer.position(6) // Skip to response code
                val responseCode = responseBuffer.short.toInt() and 0xFFFF
                if (responseCode != PTP_RESPONSE_OK) {
                    Log.e(TAG, "GetStorageInfo command failed with code: 0x${responseCode.toString(16)}")
                    
                    // If this is a specific error, maybe we can provide some default info
                    if (retryCount == maxRetries - 1) {
                        Log.w(TAG, "Using fallback storage info for storageId: 0x${storageId.toString(16)}")
                        return createFallbackStorageInfo(storageId)
                    }
                    
                    retryCount++
                    Thread.sleep(100) // Small delay before retry
                    continue
                }
                
                // We must have a data buffer to parse
                if (dataBuffer == null) {
                    Log.e(TAG, "No data buffer to parse for storage info")
                    
                    // Last attempt - use fallback
                    if (retryCount == maxRetries - 1) {
                        Log.w(TAG, "Using fallback storage info for storageId: 0x${storageId.toString(16)}")
                        return createFallbackStorageInfo(storageId)
                    }
                    
                    retryCount++
                    Thread.sleep(100) // Small delay before retry
                    continue
                }
            
            // Parse data to extract storage info
            try {
                dataBuffer.rewind()
                dataBuffer.position(8) // Skip header
                
                // Extract storage information fields according to PTP spec
                val storageType = dataBuffer.short.toInt() and 0xFFFF
                val filesystemType = dataBuffer.short.toInt() and 0xFFFF
                val accessCapability = dataBuffer.short.toInt() and 0xFFFF
                val maxCapacity = dataBuffer.long
                val freeSpace = dataBuffer.long
                val freeObjects = dataBuffer.int
                
                // Extract volume label if present
                val volumeLabel = try {
                    if (dataBuffer.remaining() > 0) {
                        var strLen = dataBuffer.get().toInt() and 0xFF
                        var label = ""
                        for (i in 0 until strLen) {
                            if (dataBuffer.remaining() >= 1) {
                                label += dataBuffer.get().toChar()
                            }
                        }
                        if (label.isNotEmpty()) label else "Storage_${storageId.toString(16)}"
                    } else {
                        "Storage_${storageId.toString(16)}"
                    }
                } catch (e: Exception) {
                    "Storage_${storageId.toString(16)}"
                }
                
                Log.d(TAG, "Storage Info - Type: $storageType, Filesystem: $filesystemType, Access: $accessCapability")
                Log.d(TAG, "Storage Info - Capacity: $maxCapacity bytes, Free: $freeSpace bytes, Free Objects: $freeObjects")
                Log.d(TAG, "Storage Info - Volume Label: $volumeLabel")
                
                // Create a map with storage details
                return mapOf(
                    "storageId" to storageId,
                    "storageType" to getStorageTypeString(storageType),
                    "filesystemType" to getFilesystemTypeString(filesystemType),
                    "accessCapability" to getAccessCapabilityString(accessCapability),
                    "maxCapacity" to maxCapacity,
                    "freeSpace" to freeSpace,
                    "freeObjects" to freeObjects,
                    "volumeLabel" to volumeLabel,
                    "readOnly" to (accessCapability == 0x0001), // Read-only storage
                    "hasSpaceInfo" to (maxCapacity > 0)
                )
            } catch (e: Exception) {
                Log.e(TAG, "Error parsing storage info data: ${e.message}")
                
                // Last attempt - use fallback
                if (retryCount == maxRetries - 1) {
                    Log.w(TAG, "Using fallback storage info for storageId: 0x${storageId.toString(16)} due to parse error")
                    return createFallbackStorageInfo(storageId)
                }
                
                retryCount++
                Thread.sleep(100) // Small delay before retry
                continue
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting storage info: ${e.message}")
            e.printStackTrace()
            
            // Return fallback on final attempt
            if (retryCount >= maxRetries - 1) {
                Log.w(TAG, "Using fallback storage info for storageId: 0x${storageId.toString(16)} due to exception")
                return createFallbackStorageInfo(storageId)
            }
            retryCount++
            Thread.sleep(100) // Small delay before retry
        }
        }
        
        // If we get here, all attempts failed
        return createFallbackStorageInfo(storageId)
    }
    
    /**
     * Create fallback storage info when the camera doesn't respond properly
     */
    private fun createFallbackStorageInfo(storageId: Int): Map<String, Any> {
        return mapOf(
            "storageId" to storageId,
            "storageType" to "Fixed RAM (Fallback)",
            "filesystemType" to "Generic Hierarchical (Fallback)",
            "accessCapability" to "Read-Write",
            "maxCapacity" to 1000000000L, // 1GB placeholder
            "freeSpace" to 500000000L,    // 500MB placeholder
            "freeObjects" to 1000,
            "volumeLabel" to "Camera_${storageId.toString(16)}",
            "readOnly" to false,
            "hasSpaceInfo" to false,
            "isFallback" to true
        )
    }
    
    /**
     * Convert storage type code to human-readable string
     */
    private fun getStorageTypeString(code: Int): String {
        return when (code) {
            0x0000 -> "Undefined"
            0x0001 -> "Fixed ROM"
            0x0002 -> "Removable ROM"
            0x0003 -> "Fixed RAM"
            0x0004 -> "Removable RAM"
            else -> "Unknown (0x${code.toString(16)})"
        }
    }
    
    /**
     * Convert filesystem type code to human-readable string
     */
    private fun getFilesystemTypeString(code: Int): String {
        return when (code) {
            0x0000 -> "Undefined"
            0x0001 -> "Generic Flat"
            0x0002 -> "Generic Hierarchical"
            0x0003 -> "DCF"
            else -> "Unknown (0x${code.toString(16)})"
        }
    }
    
    /**
     * Convert access capability code to human-readable string
     */
    private fun getAccessCapabilityString(code: Int): String {
        return when (code) {
            0x0000 -> "Read-Write"
            0x0001 -> "Read-Only"
            0x0002 -> "Read-Only with Object Deletion"
            else -> "Unknown (0x${code.toString(16)})"
        }
    }
    
    /**
     * Get list of object handles (file IDs) in the specified storage
     * @param storageId The storage ID to browse
     * @param formatCode Optional filter by format code (0 for all formats)
     * @param parentObjectId Optional parent folder handle (0 for root)
     * @return List of object handles or null if operation failed
     */
    fun getObjectHandles(storageId: Int, formatCode: Int = 0, parentObjectId: Int = 0): List<Int>? {
        // Make sure we have an open session
        ensureSessionOpen()
        
        var retryCount = 0
        val maxRetries = 3
        
        while (retryCount < maxRetries) {
            try {
                Log.d(TAG, "Getting object handles for storage ID: 0x${storageId.toString(16)}, parent: $parentObjectId, format: $formatCode (attempt ${retryCount + 1})")
                
                // Send PTP command to get object handles
                val commandBuffer = sendPtpCommand(PTP_GET_OBJECT_HANDLES, intArrayOf(storageId, formatCode, parentObjectId))
                if (commandBuffer == null) {
                    Log.e(TAG, "Failed to send GetObjectHandles command")
                    retryCount++
                    Thread.sleep(100) // Small delay before retry
                    continue
                }
            
            // Receive data phase
            val dataBuffer = receivePtpData()
            if (dataBuffer == null) {
                Log.w(TAG, "Failed to receive GetObjectHandles data, trying to proceed anyway")
                // Instead of returning null, try to continue with response
            }
            
            // Receive response phase
            val responseBuffer = receivePtpResponse()
            if (responseBuffer == null) {
                Log.e(TAG, "Failed to receive GetObjectHandles response")
                retryCount++
                Thread.sleep(100) // Small delay before retry
                continue
            }
            
            // Check response code
            responseBuffer.rewind()
            responseBuffer.position(6) // Skip to response code
            val responseCode = responseBuffer.short.toInt() and 0xFFFF
            if (responseCode != PTP_RESPONSE_OK) {
                Log.e(TAG, "GetObjectHandles command failed with code: 0x${responseCode.toString(16)}")
                retryCount++
                Thread.sleep(100) // Small delay before retry
                continue
            }
            
            // We must have a data buffer to parse
            if (dataBuffer == null) {
                Log.e(TAG, "No data buffer to parse for object handles")
                retryCount++
                Thread.sleep(100) // Small delay before retry
                continue
            }
            
            // Parse data to extract object handles
            dataBuffer.rewind()
            dataBuffer.position(8) // Skip header
            
            // Safety check to make sure we have at least 4 bytes for the count
            if (dataBuffer.remaining() < 4) {
                Log.e(TAG, "Data buffer too small to contain object handle count")
                retryCount++
                continue
            }
            
            val numHandles = dataBuffer.int // Number of object handles
            
            Log.d(TAG, "Found $numHandles objects in storage")
            
            // Safety check for reasonable number of handles
            if (numHandles < 0 || numHandles > 10000) {
                Log.e(TAG, "Unreasonable number of object handles: $numHandles")
                retryCount++
                continue
            }
            
            val objectHandles = mutableListOf<Int>()
            for (i in 0 until numHandles) {
                if (dataBuffer.remaining() >= 4) {
                    val objectHandle = dataBuffer.int
                    objectHandles.add(objectHandle)
                }
            }
            
            return objectHandles
        } catch (e: Exception) {
            Log.e(TAG, "Error getting object handles: ${e.message}")
            e.printStackTrace()
            retryCount++
            if (retryCount < maxRetries) {
                try {
                    Thread.sleep(100) // Small delay before retry
                } catch (ie: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
        }
        }
        
        Log.w(TAG, "Failed to get object handles after $maxRetries attempts")
        return emptyList() // Return empty list instead of null for better handling
    }
    
    /**
     * Browse camera storage and return a structured representation
     * This is the main method to call from Flutter
     */
    fun browseStorage(): Map<String, Any> {
        try {
            Log.d(TAG, "Starting camera storage browse operation...")
            
            // Check if we are connected and initialized
            if (usbConnection == null || ptpInEndpoint == null || ptpOutEndpoint == null) {
                Log.e(TAG, "Cannot browse storage: Camera not properly connected")
                return mapOf(
                    "success" to false,
                    "error" to "Camera not properly connected. Please connect the camera and try again."
                )
            }
            
            // Get storage IDs
            var storageIds = getStorageIds()
            if (storageIds == null || storageIds.isEmpty()) {
                Log.e(TAG, "No storage found on camera through regular method, trying fallback")
                
                // Try fallback with default storage ID
                storageIds = listOf(0x00010001) // Standard storage ID used by many cameras
            }
            
            // Create a structured response with storage info and content
            val storages = mutableListOf<Map<String, Any>>()
            
            // Process each storage
            for (storageId in storageIds) {
                val storageInfo = getStorageInfo(storageId) ?: continue
                
                // Get root objects
                val rootObjects = mutableListOf<Map<String, Any>>()
                val objectHandles = getObjectHandles(storageId) ?: continue
                
                // Get basic info for each object
                for (objectHandle in objectHandles) {
                    val objectInfo = getObjectInfo(objectHandle)
                    if (objectInfo != null) {
                        rootObjects.add(mapOf(
                            "objectHandle" to objectHandle,
                            "filename" to objectInfo.filename,
                            "fileSize" to objectInfo.size,
                            "isFolder" to (objectInfo.objectFormat == 0x3001), // Association/Folder
                            "imageWidth" to objectInfo.imagePixWidth,
                            "imageHeight" to objectInfo.imagePixHeight,
                            "format" to "0x${objectInfo.objectFormat.toString(16)}"
                        ))
                    }
                }
                
                // Add this storage with its info and objects to the result
                storages.add(mapOf(
                    "storageId" to storageId,
                    "info" to storageInfo,
                    "objects" to rootObjects
                ))
            }
            
            // Return the complete result
            return mapOf(
                "success" to true,
                "storageCount" to storages.size,
                "storages" to storages
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error browsing camera storage: ${e.message}")
            e.printStackTrace()
            return mapOf(
                "success" to false,
                "error" to "Exception: ${e.message}"
            )
        }
    }
}

// Extension for byte array to hex string (useful for debugging PTP packets)
fun ByteArray.toHexString() = joinToString("") { "%02x".format(it) }