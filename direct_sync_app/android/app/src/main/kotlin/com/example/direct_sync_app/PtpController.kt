package com.example.direct_sync_app

import android.content.Context
import android.hardware.usb.*
import android.util.Log
import kotlinx.coroutines.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.*

/**
 * PtpController handles Picture Transfer Protocol (PTP) communication with cameras.
 * This class encapsulates all PTP-specific functionality to communicate with digital cameras.
 */
class PtpController(private val context: Context) {
    companion object {
        private const val TAG = "PtpController"
        
        // PTP Operation Codes
        const val PTP_OPERATION_GET_DEVICE_INFO = 0x1001
        const val PTP_OPERATION_OPEN_SESSION = 0x1002
        const val PTP_OPERATION_CLOSE_SESSION = 0x1003
        const val PTP_OPERATION_GET_STORAGE_IDS = 0x1004
        const val PTP_OPERATION_GET_STORAGE_INFO = 0x1005
        const val PTP_OPERATION_GET_OBJECT_HANDLES = 0x1007
        const val PTP_OPERATION_GET_OBJECT_INFO = 0x1008
        const val PTP_OPERATION_GET_OBJECT = 0x1009
        
        // PTP Event Codes
        const val PTP_EVENT_OBJECT_ADDED = 0x4002
        const val PTP_EVENT_DEVICE_PROP_CHANGED = 0x4006
        const val PTP_EVENT_OBJECT_INFO_CHANGED = 0x4007
    }
    
    // USB connection components
    private var usbManager: UsbManager? = null
    private var usbDevice: UsbDevice? = null
    private var usbDeviceConnection: UsbDeviceConnection? = null
    private var usbInterface: UsbInterface? = null
    private var endpointIn: UsbEndpoint? = null
    private var endpointOut: UsbEndpoint? = null
    private var endpointEvent: UsbEndpoint? = null
    
    // Session management
    private var sessionId: Int = 1
    private var transactionId: Int = 0
    private var sessionOpen: Boolean = false
    
    // New object listener
    private var newObjectListener: ((Int) -> Unit)? = null
    private var eventListenerJob: Job? = null
    
    // Coroutine scope for async operations
    private val coroutineScope = CoroutineScope(Dispatchers.IO + Job())
    
    init {
        usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        logMessage("PTP Controller initialized")
    }
    
    /**
     * Find and initialize a compatible camera device
     * @return Pair<Boolean, String> - success status and message
     */
    fun findCamera(): Pair<Boolean, String> {
        logMessage("Searching for compatible camera...")
        val devices = usbManager?.deviceList
        
        // Log all connected USB devices
        devices?.forEach { (deviceName, device) ->
            logMessage("Found USB device: $deviceName, " +
                     "VendorID: 0x${device.vendorId.toString(16)}, " +
                     "ProductID: 0x${device.productId.toString(16)}, " +
                     "Interfaces: ${device.interfaceCount}")
        }
        
        // Find a compatible camera (Canon in this case)
        usbManager?.deviceList?.values?.find { device ->
            // Canon cameras typically use this vendor ID
            device.vendorId == 0x04a9
        }?.let { device ->
            logMessage("Found Canon camera: ${device.deviceName}")
            logDeviceInfo(device)
            usbDevice = device
            return Pair(true, "Found camera: ${device.deviceName}")
        }
        
        return Pair(false, "No compatible camera found")
    }
    
    /**
     * Initialize the camera connection with the selected device
     * @param device The USB device to connect to
     * @return Boolean indicating if setup was successful
     */
    fun setupDevice(device: UsbDevice): Boolean {
        logMessage("Setting up camera device: ${device.deviceName}")
        
        usbDeviceConnection = usbManager?.openDevice(device)
        if (usbDeviceConnection == null) {
            logError("Failed to open USB connection to device")
            return false
        }
        
        // Log device details
        logMessage("Device has ${device.interfaceCount} interfaces")
        
        usbInterface = device.getInterface(0)
        logMessage("Using interface 0 with ${usbInterface?.endpointCount ?: 0} endpoints")
        
        val claimed = usbDeviceConnection?.claimInterface(usbInterface, true) ?: false
        if (!claimed) {
            logError("Failed to claim interface")
            return false
        }
        logMessage("Successfully claimed interface")
        
        // Find bulk endpoints
        for (i in 0 until usbInterface?.endpointCount!!) {
            val endpoint = usbInterface?.getEndpoint(i)
            if (endpoint?.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                if (endpoint.direction == UsbConstants.USB_DIR_IN) {
                    endpointIn = endpoint
                    logMessage("Found IN endpoint: ${endpoint.endpointNumber}")
                } else if (endpoint.direction == UsbConstants.USB_DIR_OUT) {
                    endpointOut = endpoint
                    logMessage("Found OUT endpoint: ${endpoint.endpointNumber}")
                }
            } else if (endpoint?.type == UsbConstants.USB_ENDPOINT_XFER_INT && endpoint.direction == UsbConstants.USB_DIR_IN) {
                // Interrupt endpoint is typically used for events
                endpointEvent = endpoint
                logMessage("Found EVENT endpoint: ${endpoint.endpointNumber}")
            }
        }
        
        if (endpointIn == null || endpointOut == null) {
            logError("Failed to find required endpoints")
            return false
        }
        
        logMessage("Basic camera setup completed, opening PTP session...")
        
        // Open a PTP session
        val sessionSuccess = coroutineScope.async { openSession() }.runBlocking()
        
        if (!sessionSuccess) {
            logError("Failed to open PTP session")
            return false
        }
        
        logMessage("Camera setup completed successfully")
        return true
    }
    
    /**
     * Get a list of storage IDs from the camera
     * @return List<Int>? A list of storage IDs or null if operation failed
     */
    suspend fun getStorageIds(): List<Int>? = withContext(Dispatchers.IO) {
        try {
            logMessage("Retrieving storage IDs...")
            val command = ByteBuffer.allocate(12)
            command.order(ByteOrder.LITTLE_ENDIAN)
            command.putInt(12) // Length
            command.putShort(1) // Type (command block)
            command.putShort(PTP_OPERATION_GET_STORAGE_IDS.toShort()) // Operation code
            command.putInt(0) // Transaction ID
            
            sendPtpCommand(command.array())
            
            val response = receivePtpData()
            val storageIds = response?.let {
                val buffer = ByteBuffer.wrap(it)
                buffer.order(ByteOrder.LITTLE_ENDIAN)
                val count = buffer.getInt(0)
                logMessage("Found $count storage units")
                
                val ids = ArrayList<Int>()
                for (i in 0 until count) {
                    val id = buffer.getInt(4 + i * 4)
                    ids.add(id)
                    logMessage("Storage ID: 0x${id.toString(16)}")
                }
                ids
            }
            
            if (storageIds == null) {
                logError("Failed to retrieve storage IDs")
            }
            
            return@withContext storageIds
        } catch (e: Exception) {
            logError("Error getting storage IDs: ${e.message}")
            return@withContext null
        }
    }
    
    /**
     * Get a list of object handles for a specific storage
     * @param storageId The storage ID to query
     * @return List<Int>? A list of object handles or null if operation failed
     */
    suspend fun getObjectHandles(storageId: Int): List<Int>? = withContext(Dispatchers.IO) {
        try {
            logMessage("Retrieving object handles for storage 0x${storageId.toString(16)}...")
            val command = ByteBuffer.allocate(16)
            command.order(ByteOrder.LITTLE_ENDIAN)
            command.putInt(16) // Length
            command.putShort(1) // Type
            command.putShort(PTP_OPERATION_GET_OBJECT_HANDLES.toShort()) // Operation code
            command.putInt(0) // Transaction ID
            command.putInt(storageId) // Storage ID
            
            sendPtpCommand(command.array())
            
            val response = receivePtpData()
            val objectHandles = response?.let {
                val buffer = ByteBuffer.wrap(it)
                buffer.order(ByteOrder.LITTLE_ENDIAN)
                val count = buffer.getInt(0)
                logMessage("Found $count objects in storage 0x${storageId.toString(16)}")
                
                val handles = ArrayList<Int>()
                for (i in 0 until count) {
                    val handle = buffer.getInt(4 + i * 4)
                    handles.add(handle)
                }
                handles
            }
            
            if (objectHandles == null) {
                logError("Failed to retrieve object handles")
            }
            
            return@withContext objectHandles
        } catch (e: Exception) {
            logError("Error getting object handles: ${e.message}")
            return@withContext null
        }
    }
    
    /**
     * Get information about a specific object
     * @param objectHandle The object handle to query
     * @return Map<String, Any>? Object information or null if operation failed
     */
    suspend fun getObjectInfo(objectHandle: Int): Map<String, Any>? = withContext(Dispatchers.IO) {
        try {
            logMessage("Retrieving info for object 0x${objectHandle.toString(16)}...")
            val command = ByteBuffer.allocate(16)
            command.order(ByteOrder.LITTLE_ENDIAN)
            command.putInt(16) // Length
            command.putShort(1) // Type
            command.putShort(PTP_OPERATION_GET_OBJECT_INFO.toShort()) // Operation code
            command.putInt(0) // Transaction ID
            command.putInt(objectHandle)
            
            sendPtpCommand(command.array())
            
            val response = receivePtpData()
            // Parse object info and return as Map
            val info = response?.let {
                val format = ByteBuffer.wrap(it, 4, 2).order(ByteOrder.LITTLE_ENDIAN).short
                val size = ByteBuffer.wrap(it, 8, 4).order(ByteOrder.LITTLE_ENDIAN).int
                
                logMessage("Object 0x${objectHandle.toString(16)} - Format: 0x${format.toString(16)}, Size: $size bytes")
                
                mapOf(
                    "objectHandle" to objectHandle,
                    "format" to format,
                    "size" to size
                )
            }
            
            if (info == null) {
                logError("Failed to retrieve object info")
            }
            
            return@withContext info
        } catch (e: Exception) {
            logError("Error getting object info: ${e.message}")
            return@withContext null
        }
    }
    
    /**
     * Get the data for a specific object
     * @param objectHandle The object handle to retrieve
     * @return ByteArray? The object data or null if operation failed
     */
    suspend fun getObject(objectHandle: Int): ByteArray? = withContext(Dispatchers.IO) {
        try {
            logMessage("Downloading object 0x${objectHandle.toString(16)}...")
            val command = ByteBuffer.allocate(16)
            command.order(ByteOrder.LITTLE_ENDIAN)
            command.putInt(16) // Length
            command.putShort(1) // Type
            command.putShort(PTP_OPERATION_GET_OBJECT.toShort()) // Operation code
            command.putInt(0) // Transaction ID
            command.putInt(objectHandle)
            
            sendPtpCommand(command.array())
            
            val response = receivePtpData()
            if (response == null) {
                logError("Failed to download object")
            } else {
                logMessage("Successfully downloaded object 0x${objectHandle.toString(16)}, size: ${response.size} bytes")
            }
            
            return@withContext response
        } catch (e: Exception) {
            logError("Error downloading object: ${e.message}")
            return@withContext null
        }
    }
    
    /**
     * Send a PTP command to the device
     */
    private suspend fun sendPtpCommand(command: ByteArray): Int = withContext(Dispatchers.IO) {
        val result = usbDeviceConnection?.bulkTransfer(endpointOut, command, command.size, 5000) ?: -1
        if (result < 0) {
            logError("Failed to send PTP command")
        } else {
            logMessage("Sent ${command.size} bytes to camera")
        }
        return@withContext result
    }
    
    /**
     * Receive PTP data from the device
     */
    private suspend fun receivePtpData(): ByteArray? = withContext(Dispatchers.IO) {
        val buffer = ByteArray(1024 * 1024) // 1MB buffer
        val length = usbDeviceConnection?.bulkTransfer(endpointIn, buffer, buffer.size, 5000)
        
        if (length != null && length > 0) {
            logMessage("Received $length bytes from camera")
            return@withContext buffer.copyOf(length)
        } else {
            logError("Failed to receive data from camera")
            return@withContext null
        }
    }
    
    /**
     * Log device information
     */
    private fun logDeviceInfo(device: UsbDevice) {
        logMessage("=== CAMERA DETAILS ===")
        logMessage("Device name: ${device.deviceName}")
        logMessage("Device ID: ${device.deviceId}")
        logMessage("Vendor ID: 0x${device.vendorId.toString(16)}")
        logMessage("Product ID: 0x${device.productId.toString(16)}")
        logMessage("Class: ${device.deviceClass}")
        logMessage("Subclass: ${device.deviceSubclass}")
        logMessage("Protocol: ${device.deviceProtocol}")
        logMessage("Interface count: ${device.interfaceCount}")
        
        // Log details about each interface
        // for (i in 0 until device.interfaceCount) {
        //     val intf = device.getInterface(i)
        //     logMessage("Interface #$i:")
        //     logMessage("  ID: ${intf.id}")
        //     logMessage("  Interface class: ${intf.interfaceClass}")
        //     logMessage("  Interface subclass: ${intf.interfaceSubclass}")
        //     logMessage("  Protocol: ${intf.interfaceProtocol}")
        //     logMessage("  Endpoint count: ${intf.endpointCount}")
            
        //     // Log details about each endpoint
        //     for (j in 0 until intf.endpointCount) {
        //         val endpoint = intf.getEndpoint(j)
        //         val type = when (endpoint.type) {
        //             UsbConstants.USB_ENDPOINT_XFER_CONTROL -> "CONTROL"
        //             UsbConstants.USB_ENDPOINT_XFER_ISOC -> "ISOCHRONOUS"
        //             UsbConstants.USB_ENDPOINT_XFER_BULK -> "BULK"
        //             UsbConstants.USB_ENDPOINT_XFER_INT -> "INTERRUPT"
        //             else -> "UNKNOWN"
        //         }
        //         val direction = if (endpoint.direction == UsbConstants.USB_DIR_IN) "IN" else "OUT"
        //         logMessage("    Endpoint #$j:")
        //         logMessage("      Address: ${endpoint.address}")
        //         logMessage("      Number: ${endpoint.endpointNumber}")
        //         logMessage("      Type: $type")
        //         logMessage("      Direction: $direction")
        //         logMessage("      Max packet size: ${endpoint.maxPacketSize}")
        //         logMessage("      Interval: ${endpoint.interval}")
        //     }
        // }
        logMessage("===================")
    }
    
    /**
     * Log a message with timestamp
     */
    private fun logMessage(message: String) {
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
        Log.d(TAG, "[$timestamp] $message")
    }
    
    /**
     * Log an error with timestamp
     */
    private fun logError(message: String) {
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
        Log.e(TAG, "[$timestamp] $message")
    }
    
    /**
     * Open a PTP session with the camera
     * @return Boolean indicating if session was opened successfully
     */
    suspend fun openSession(): Boolean = withContext(Dispatchers.IO) {
        try {
            logMessage("Opening PTP session with ID $sessionId...")
            val command = ByteBuffer.allocate(16)
            command.order(ByteOrder.LITTLE_ENDIAN)
            command.putInt(16) // Length
            command.putShort(1) // Type (command block)
            command.putShort(PTP_OPERATION_OPEN_SESSION.toShort()) // Operation code
            command.putInt(++transactionId) // Transaction ID
            command.putInt(sessionId) // Session ID parameter
            
            val sent = sendPtpCommand(command.array())
            if (sent < 0) {
                logError("Failed to send OpenSession command")
                return@withContext false
            }
            
            // Get response (should be a "OK" response code 0x2001)
            val response = ByteArray(12) // Response is usually 12 bytes
            val responseLength = usbDeviceConnection?.bulkTransfer(endpointIn, response, response.size, 5000) ?: -1
            
            if (responseLength >= 12) {
                val responseCode = ByteBuffer.wrap(response, 6, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xFFFF
                if (responseCode == 0x2001) { // OK response
                    logMessage("Session opened successfully")
                    sessionOpen = true
                    return@withContext true
                } else {
                    logError("Failed to open session: response code 0x${responseCode.toString(16)}")
                }
            } else {
                logError("Invalid response when opening session")
            }
            
            return@withContext false
        } catch (e: Exception) {
            logError("Error opening PTP session: ${e.message}")
            e.printStackTrace()
            return@withContext false
        }
    }
    
    /**
     * Close the PTP session
     * @return Boolean indicating if session was closed successfully
     */
    suspend fun closeSession(): Boolean = withContext(Dispatchers.IO) {
        if (!sessionOpen) {
            logMessage("No active session to close")
            return@withContext true
        }
        
        try {
            logMessage("Closing PTP session...")
            val command = ByteBuffer.allocate(12)
            command.order(ByteOrder.LITTLE_ENDIAN)
            command.putInt(12) // Length
            command.putShort(1) // Type (command block)
            command.putShort(PTP_OPERATION_CLOSE_SESSION.toShort()) // Operation code
            command.putInt(++transactionId) // Transaction ID
            
            val sent = sendPtpCommand(command.array())
            if (sent < 0) {
                logError("Failed to send CloseSession command")
                return@withContext false
            }
            
            // Get response
            val response = ByteArray(12)
            val responseLength = usbDeviceConnection?.bulkTransfer(endpointIn, response, response.size, 5000) ?: -1
            
            sessionOpen = false
            stopEventListener()
            
            if (responseLength >= 12) {
                val responseCode = ByteBuffer.wrap(response, 6, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xFFFF
                if (responseCode == 0x2001) { // OK response
                    logMessage("Session closed successfully")
                    return@withContext true
                } else {
                    logError("Failed to close session: response code 0x${responseCode.toString(16)}")
                }
            } else {
                logError("Invalid response when closing session")
            }
            
            return@withContext false
        } catch (e: Exception) {
            logError("Error closing PTP session: ${e.message}")
            e.printStackTrace()
            sessionOpen = false
            return@withContext false
        }
    }
    
    /**
     * Set a listener to be notified when a new object is added to the camera
     * @param listener A function that will be called with the object handle when a new object is detected
     */
    fun setNewObjectListener(listener: (Int) -> Unit) {
        newObjectListener = listener
        if (isConnected() && endpointEvent != null && eventListenerJob == null) {
            startEventListener()
        }
    }
    
    /**
     * Start listening for PTP events from the camera
     */
    private fun startEventListener() {
        if (endpointEvent == null) {
            logError("No event endpoint available, cannot listen for events")
            return
        }
        
        logMessage("Starting event listener...")
        eventListenerJob = coroutineScope.launch {
            val eventBuffer = ByteArray(64) // Events are usually small
            
            while (isActive && isConnected()) {
                try {
                    val length = usbDeviceConnection?.bulkTransfer(
                        endpointEvent, eventBuffer, eventBuffer.size, 500)
                    
                    if (length != null && length > 12) { // Valid event should be at least 12 bytes
                        val eventCode = ByteBuffer.wrap(eventBuffer, 6, 2)
                            .order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xFFFF
                        
                        when (eventCode) {
                            PTP_EVENT_OBJECT_ADDED -> {
                                val objectHandle = ByteBuffer.wrap(eventBuffer, 8, 4)
                                    .order(ByteOrder.LITTLE_ENDIAN).int
                                logMessage("New object added! Handle: 0x${objectHandle.toString(16)}")
                                newObjectListener?.invoke(objectHandle)
                            }
                            PTP_EVENT_DEVICE_PROP_CHANGED -> {
                                logMessage("Device property changed event")
                            }
                            PTP_EVENT_OBJECT_INFO_CHANGED -> {
                                logMessage("Object info changed event")
                            }
                            else -> {
                                if (eventCode != 0) {
                                    logMessage("Received event code: 0x${eventCode.toString(16)}")
                                }
                            }
                        }
                    }
                    
                    // Small delay to prevent high CPU usage
                    delay(100)
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    logError("Error in event listener: ${e.message}")
                    delay(1000) // Longer delay after error
                }
            }
        }
    }
    
    /**
     * Stop listening for PTP events
     */
    private fun stopEventListener() {
        eventListenerJob?.cancel()
        eventListenerJob = null
        logMessage("Event listener stopped")
    }
    
    /**
     * Clean up resources
     */
    fun release() {
        logMessage("Releasing PTP controller resources")
        
        // Cancel all coroutines
        coroutineScope.launch {
            // Try to properly close the session first
            if (sessionOpen) {
                try {
                    closeSession()
                } catch (e: Exception) {
                    logError("Error during session close: ${e.message}")
                }
            }
            
            // Stop the event listener
            stopEventListener()
        }
        
        // Cancel the entire scope after a short delay to allow cleanup
        coroutineScope.launch {
            delay(500)
            coroutineScope.cancel()
        }
        
        // Release USB resources
        usbDeviceConnection?.releaseInterface(usbInterface)
        usbDeviceConnection?.close()
        usbDevice = null
        usbDeviceConnection = null
        usbInterface = null
        endpointIn = null
        endpointOut = null
        endpointEvent = null
        
        // Reset session state
        sessionOpen = false
        transactionId = 0
    }
    
    /**
     * Check if a device is connected
     */
    fun isConnected(): Boolean {
        return usbDevice != null && usbDeviceConnection != null && 
               usbInterface != null && endpointIn != null && endpointOut != null
    }
    
    /**
     * Get current device information
     */
    fun getConnectedDeviceInfo(): Map<String, Any>? {
        return usbDevice?.let { device ->
            mapOf(
                "deviceName" to device.deviceName,
                "vendorId" to "0x${device.vendorId.toString(16)}",
                "productId" to "0x${device.productId.toString(16)}",
                "connected" to isConnected()
            )
        }
    }
}
