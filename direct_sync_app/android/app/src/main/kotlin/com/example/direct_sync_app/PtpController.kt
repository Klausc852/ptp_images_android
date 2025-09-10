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
    
    // Connection monitoring
    private var connectionMonitorJob: Job? = null
    private var keepAliveJob: Job? = null
    private var lastKnownDeviceId: Int? = null
    private var lastKnownDeviceName: String? = null
    
    init {
        usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        logMessage("PTP Controller initialized")
    }
    
    /**
     * Start monitoring USB connection for stability
     * This helps detect phantom disconnect events
     */
    fun startConnectionMonitoring() {
        if (connectionMonitorJob != null) return
        
        // Save the current device details for reconnection attempts
        lastKnownDeviceId = usbDevice?.deviceId
        lastKnownDeviceName = usbDevice?.deviceName
        
        // Record information about who started the monitoring
        val callerStack = Thread.currentThread().stackTrace
            .drop(3) // Skip the immediate calling methods
            .take(5) // Take just a few frames for context
            .joinToString("\\n") { 
                "  at ${it.className}.${it.methodName}(${it.fileName}:${it.lineNumber})" 
            }
        logMessage("Starting USB connection monitoring\\nCalled from:\\n$callerStack")
        
        connectionMonitorJob = coroutineScope.launch(SupervisorJob()) {
            try {
                var previouslyAttached = true // Assume attached at start
                var previouslyValid = true // Assume valid at start
                var monitorCycles = 0
                var lastConnectionAttemptTime = 0L
                
                while (isActive) {
                    delay(2000) // Check every 2 seconds (reduced from 3s for faster detection)
                    monitorCycles++
                    
                    // Only run checks if we're supposed to be connected
                    if (usbDevice != null) {
                        // Log device and connection details periodically
                        if (monitorCycles % 10 == 0) { // Every 30 seconds
                            val deviceCount = usbManager?.deviceList?.size ?: 0
                            logMessage("Connection monitor status: " +
                                "Devices connected: $deviceCount, " +
                                "Session open: $sessionOpen, " +
                                "Watching device: ${usbDevice?.deviceName}")
                        }
                        
                        // Check if the device is still physically attached
                        val isPhysicallyAttached = usbManager?.deviceList?.values?.any { 
                            it.deviceId == usbDevice?.deviceId 
                        } ?: false
                        
                        // Check if our connection is still valid
                        val isConnectionValid = usbDeviceConnection != null && 
                                              usbInterface != null && 
                                              endpointIn != null && 
                                              endpointOut != null
                        
                        // Track state changes for better diagnostics
                        val attachmentChanged = isPhysicallyAttached != previouslyAttached
                        val validityChanged = isConnectionValid != previouslyValid
                        
                        // Report state changes
                        if (attachmentChanged) {
                            if (isPhysicallyAttached) {
                                logMessage("USB device re-appeared in device list")
                            } else {
                                // This is the critical case - device disappeared from system
                                logError("⚠️ USB DEVICE DISAPPEARED FROM SYSTEM ⚠️")
                                logError("Device disappeared from USB device list. " +
                                       "This is likely due to camera power-saving mode or physical disconnection")
                                
                                // Check UsbDevice status
                                val deviceStatus = try {
                                    "Device status: ${usbDevice?.deviceId ?: "null"}, " +
                                    "Connection open: ${usbDeviceConnection != null}"
                                } catch (e: Exception) {
                                    "Error accessing device: ${e.message}"
                                }
                                logError(deviceStatus)
                            }
                        }
                        
                        if (validityChanged) {
                            if (isConnectionValid) {
                                logMessage("USB connection objects restored")
                            } else {
                                // Connection objects became invalid
                                logError("⚠️ USB CONNECTION OBJECTS BECAME INVALID ⚠️")
                                logError("USB endpoints or interface became null - " +
                                       "this is likely an Android-side resource cleanup")
                            }
                        }
                                                
                        // If the device is physically attached but our connection is invalid,
                        // this could be a phantom disconnect
                        if (isPhysicallyAttached && !isConnectionValid) {
                            logMessage("Potential phantom disconnect detected - device is present but connection is invalid")
                            
                            // Could attempt reconnection here
                        }
                        // If the connection seems valid but the device is not in the list,
                        // this could be a device that went to sleep or a USB enumeration issue
                        else if (!isPhysicallyAttached && isConnectionValid) {
                            logMessage("Unusual state: device not in USB device list but connection appears valid")
                            
                            // Recheck after a short delay (USB enumeration can be slow)
                            delay(1000)
                            val recheckAttached = usbManager?.deviceList?.values?.any { 
                                it.deviceId == usbDevice?.deviceId 
                            } ?: false
                            
                            if (!recheckAttached) {
                                // Double checked - device is truly gone from system
                                logError("Device confirmed missing from USB list but connection objects still exist")
                                
                                // List all current USB devices for diagnostics
                                val currentDevices = usbManager?.deviceList?.values?.joinToString { 
                                    "${it.deviceName} (ID: ${it.deviceId})" 
                                } ?: "none"
                                logMessage("Current USB devices: $currentDevices")
                            }
                        }
                        
                        // Update previous state
                        previouslyAttached = isPhysicallyAttached
                        previouslyValid = isConnectionValid
                    }
                }
            } catch (e: CancellationException) {
                // Normal cancellation
                logMessage("Connection monitoring cancelled")
            } catch (e: Exception) {
                logError("Error in connection monitoring: ${e.message}")
            }
        }
    }
    
    /**
     * Stop the connection monitoring
     */
    fun stopConnectionMonitoring() {
        connectionMonitorJob?.cancel()
        connectionMonitorJob = null
        logMessage("USB connection monitoring stopped")
    }
    
    /**
     * Start a keep-alive job to prevent camera auto power-off
     * Many cameras have auto power-off features that disconnect USB
     * This sends periodic lightweight commands to keep the camera active
     */
    private fun startKeepAliveJob() {
        if (keepAliveJob != null) return
        
        // Use a separate supervisor job so errors don't affect other operations
        keepAliveJob = coroutineScope.launch(SupervisorJob()) {
            try {
                logMessage("Starting keep-alive mechanism to prevent camera auto power-off")
                
                var consecutiveErrors = 0
                val maxConsecutiveErrors = 3
                
                while (isActive) {
                    try {
                        // Shorter interval to ensure more frequent keep-alive
                        delay(1500) // Send a command every 1.5 seconds (reduced from 3s)
                        
                        // Check if we're connected before trying to send commands
                        val isConnected = isConnected(checkPhysicalDevice = true)
                        
                        if (isConnected) {
                            try {
                                // Get device info is a lightweight command that shouldn't impact performance
                                val command = ByteBuffer.allocate(12)
                                command.order(ByteOrder.LITTLE_ENDIAN)
                                command.putInt(12) // Length
                                command.putShort(1) // Type (command block)
                                command.putShort(PTP_OPERATION_GET_DEVICE_INFO.toShort())
                                command.putInt(++transactionId) // Transaction ID
                                
                                val sent = sendPtpCommand(command.array())
                                if (sent > 0) {
                                    // Drain the response but don't process it
                                    val response = receivePtpData()
                                    if (response != null) {
                                        // Only log occasionally to avoid filling the logs
                                        if (System.currentTimeMillis() % 30000 < 1500) {
                                            logMessage("Camera keep-alive ping successful")
                                        }
                                        // Reset consecutive errors counter on success
                                        consecutiveErrors = 0
                                    } else {
                                        logError("Keep-alive ping received null response")
                                        consecutiveErrors++
                                    }
                                } else {
                                    logError("Keep-alive ping failed to send")
                                    consecutiveErrors++
                                }
                            } catch (e: Exception) {
                                // Log errors but don't crash the keep-alive job
                                logError("Error sending keep-alive command: ${e.message}")
                                consecutiveErrors++
                            }
                        } else {
                            // Not connected - log periodically to avoid spamming
                            if (System.currentTimeMillis() % 30000 < 1500) {
                                logMessage("Keep-alive paused - device not connected")
                            }
                            
                            // Try to auto-recover if session is lost but device is still physically connected
                            if (usbDevice != null && isDeviceStillPhysicallyConnected()) {
                                logMessage("Device physically connected but session appears lost. Attempting to re-establish...")
                                
                                // Try to re-open the session
                                try {
                                    reopenSession()
                                } catch (e: Exception) {
                                    logError("Failed to re-establish session: ${e.message}")
                                }
                            }
                            
                            delay(3000) // Wait a bit longer when not connected
                        }
                        
                        // If we've had too many consecutive errors, try to re-establish the session
                        if (consecutiveErrors >= maxConsecutiveErrors) {
                            logMessage("Keep-alive detected $consecutiveErrors consecutive errors. Attempting to re-establish session...")
                            
                            try {
                                reopenSession()
                                consecutiveErrors = 0
                            } catch (e: Exception) {
                                logError("Failed to re-establish session after consecutive errors: ${e.message}")
                            }
                        }
                    } catch (e: CancellationException) {
                        throw e // Allow normal cancellation
                    } catch (e: Exception) {
                        logError("Error in keep-alive job: ${e.message}")
                        delay(3000) // Wait before retrying after an error
                    }
                }
            } catch (e: CancellationException) {
                // Normal cancellation
                logMessage("Keep-alive job cancelled")
            } catch (e: Exception) {
                logError("Error in keep-alive job: ${e.message}")
            }
        }
    }
    
    /**
     * Stop the keep-alive job
     */
    private fun stopKeepAliveJob() {
        keepAliveJob?.cancel()
        keepAliveJob = null
        logMessage("Camera keep-alive mechanism stopped")
    }
    
    /**
     * Check if the device is still physically connected via USB
     * This is separate from isConnected() because we want to check only physical presence
     */
    private fun isDeviceStillPhysicallyConnected(): Boolean {
        val device = usbDevice ?: return false
        
        // Check if the device is still in the USB manager's device list
        val isPhysicallyConnected = usbManager?.deviceList?.values?.any { 
            it.deviceId == device.deviceId 
        } ?: false
        
        if (!isPhysicallyConnected) {
            logMessage("Device is no longer physically connected")
        }
        
        return isPhysicallyConnected
    }
    
    /**
     * Attempt to re-open a PTP session when it appears to be lost
     * This happens when the device is still physically connected but the session is lost
     */
    private suspend fun reopenSession() {
        logMessage("Attempting to re-establish PTP session...")
        
        if (!isDeviceStillPhysicallyConnected()) {
            logError("Cannot re-establish session - device not physically connected")
            return
        }
        
        // First clean up any existing session
        try {
            if (sessionOpen) {
                closeSession()
            }
        } catch (e: Exception) {
            logError("Error closing previous session: ${e.message}")
            // Continue anyway to try to establish a new session
        }
        
        // Make sure USB interface is still claimed
        val device = usbDevice ?: return
        val usbIface = usbInterface
        
        if (usbIface != null && usbDeviceConnection != null) {
            // Try to release and reclaim the interface
            try {
                usbDeviceConnection?.releaseInterface(usbIface)
                val claimed = usbDeviceConnection?.claimInterface(usbIface, true) ?: false
                if (!claimed) {
                    logError("Failed to reclaim interface during session recovery")
                    return
                }
                logMessage("Successfully reclaimed interface")
            } catch (e: Exception) {
                logError("Error reclaiming interface: ${e.message}")
                return
            }
        } else if (usbDeviceConnection != null) {
            // Try to claim the interface again
            try {
                val newInterface = device.getInterface(0)
                val claimed = usbDeviceConnection?.claimInterface(newInterface, true) ?: false
                if (!claimed) {
                    logError("Failed to claim new interface during session recovery")
                    return
                }
                usbInterface = newInterface
                logMessage("Successfully claimed new interface")
                
                // Find endpoints again
                for (i in 0 until newInterface.endpointCount) {
                    val endpoint = newInterface.getEndpoint(i)
                    if (endpoint.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                        if (endpoint.direction == UsbConstants.USB_DIR_IN) {
                            endpointIn = endpoint
                        } else if (endpoint.direction == UsbConstants.USB_DIR_OUT) {
                            endpointOut = endpoint
                        }
                    } else if (endpoint.type == UsbConstants.USB_ENDPOINT_XFER_INT && 
                               endpoint.direction == UsbConstants.USB_DIR_IN) {
                        endpointEvent = endpoint
                    }
                }
            } catch (e: Exception) {
                logError("Error claiming new interface: ${e.message}")
                return
            }
        } else {
            // Try to reopen the device connection
            try {
                usbDeviceConnection = usbManager?.openDevice(device)
                if (usbDeviceConnection == null) {
                    logError("Failed to reopen device connection")
                    return
                }
                
                val newInterface = device.getInterface(0)
                val claimed = usbDeviceConnection?.claimInterface(newInterface, true) ?: false
                if (!claimed) {
                    logError("Failed to claim interface after reopening connection")
                    return
                }
                
                usbInterface = newInterface
                
                // Find endpoints
                for (i in 0 until newInterface.endpointCount) {
                    val endpoint = newInterface.getEndpoint(i)
                    if (endpoint.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                        if (endpoint.direction == UsbConstants.USB_DIR_IN) {
                            endpointIn = endpoint
                        } else if (endpoint.direction == UsbConstants.USB_DIR_OUT) {
                            endpointOut = endpoint
                        }
                    } else if (endpoint.type == UsbConstants.USB_ENDPOINT_XFER_INT && 
                               endpoint.direction == UsbConstants.USB_DIR_IN) {
                        endpointEvent = endpoint
                    }
                }
                
                logMessage("Successfully reopened device connection and claimed interface")
            } catch (e: Exception) {
                logError("Error reopening device connection: ${e.message}")
                return
            }
        }
        
        // Finally, try to open a new PTP session
        try {
            // Reset the session ID and transaction ID
            transactionId = 0
            sessionId = Random().nextInt(0x10000) // Use a new random session ID
            
            val sessionSuccess = openSession()
            if (sessionSuccess) {
                logMessage("Successfully re-established PTP session")
                
                // Restart the event listener if needed
                if (newObjectListener != null && eventListenerJob == null) {
                    startEventListener()
                }
            } else {
                logError("Failed to open new PTP session")
            }
        } catch (e: Exception) {
            logError("Error opening new PTP session: ${e.message}")
        }
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
        
        // Store device info for potential reconnections
        lastKnownDeviceId = device.deviceId
        lastKnownDeviceName = device.deviceName
        
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
        
        // Open a PTP session with a dedicated scope that won't be affected by cancellations
        val sessionSuccess = try {
            // Use a separate dispatcher and job to avoid interference from other scopes
            val sessionScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
            val result = runBlocking {
                try {
                    // Run session opening in the dedicated scope
                    sessionScope.async { openSession() }.await()
                } finally {
                    // Always clean up the scope when done
                    sessionScope.cancel()
                }
            }
            result
        } catch (e: Exception) {
            logError("Exception during session opening: ${e.message}")
            e.printStackTrace()
            false
        }
        
        if (!sessionSuccess) {
            logError("Failed to open PTP session")
            return false
        }
        
        logMessage("Camera setup completed successfully")
        
        // Start connection monitoring for better stability
        startConnectionMonitoring()
        
        // Start the keep-alive job to prevent camera auto power-off
        startKeepAliveJob()
        
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
                
                // Ensure we have at least 4 bytes to read the count
                if (buffer.capacity() < 4) {
                    logError("Response too short: ${buffer.capacity()} bytes")
                    return@let null
                }
                
                val count = buffer.getInt(0)
                
                // Sanity check on count to avoid buffer overflow
                if (count < 0 || count > 100 || buffer.capacity() < 4 + count * 4) {
                    logError("Invalid storage ID count: $count (buffer size: ${buffer.capacity()})")
                    return@let ArrayList<Int>() // Return empty list instead of null
                }
                
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
                
                // Ensure we have at least 4 bytes to read the count
                if (buffer.capacity() < 4) {
                    logError("Response too short: ${buffer.capacity()} bytes")
                    return@let null
                }
                
                val count = buffer.getInt(0)
                
                // Sanity check on count to avoid buffer overflow
                if (count < 0 || count > 10000 || buffer.capacity() < 4 + count * 4) {
                    logError("Invalid object handle count: $count (buffer size: ${buffer.capacity()})")
                    return@let ArrayList<Int>() // Return empty list instead of null
                }
                
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
        try {
            if (usbDeviceConnection == null || endpointOut == null) {
                logError("Cannot send command - USB connection not properly initialized")
                return@withContext -1
            }
            
            // Command format (first 12 bytes):
            // - Bytes 0-3: Length (4 bytes, little endian)
            // - Bytes 4-5: Type (2 bytes, little endian)
            // - Bytes 6-7: Operation code (2 bytes, little endian)
            // - Bytes 8-11: Transaction ID (4 bytes, little endian)
            val cmdType = if (command.size >= 6) {
                val type = ByteBuffer.wrap(command, 4, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xFFFF
                type
            } else 0
            
            val opCode = if (command.size >= 8) {
                val op = ByteBuffer.wrap(command, 6, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xFFFF
                op
            } else 0
            
            // Log start time for performance tracking
            val startTime = System.currentTimeMillis()
            
            // Increased timeout to 10 seconds for more reliable operation
            val result = usbDeviceConnection?.bulkTransfer(endpointOut, command, command.size, 10000) ?: -1
            
            val elapsedTime = System.currentTimeMillis() - startTime
            
            if (result < 0) {
                logError("Failed to send PTP command (type: 0x${cmdType.toString(16)}, op: 0x${opCode.toString(16)}) after ${elapsedTime}ms")
                return@withContext -1
            } else {
                logMessage("Sent ${command.size} bytes to camera (type: 0x${cmdType.toString(16)}, op: 0x${opCode.toString(16)}) in ${elapsedTime}ms")
                return@withContext result
            }
        } catch (e: Exception) {
            logError("Exception while sending PTP command: ${e.message}")
            e.printStackTrace()
            return@withContext -1
        }
    }
    
    /**
     * Receive PTP data from the device
     */
    private suspend fun receivePtpData(): ByteArray? = withContext(Dispatchers.IO) {
        try {
            if (usbDeviceConnection == null || endpointIn == null) {
                logError("Cannot receive data - USB connection not properly initialized")
                return@withContext null
            }
            
            val buffer = ByteArray(1024 * 1024) // 1MB buffer
            
            // Log start time for performance tracking
            val startTime = System.currentTimeMillis()
            
            // Try multiple attempts to read if the first one fails
            var length: Int? = null
            var attempts = 0
            val maxAttempts = 5 // Increased from 3 to 5 for more reliability
            
            while (attempts < maxAttempts) {
                attempts++
                
                // Wait a bit before retrying, increasing the wait time with each attempt
                if (attempts > 1) {
                    logMessage("Retrying data receive (attempt $attempts of $maxAttempts)...")
                    delay(attempts * 500L) // 500ms, 1000ms, 1500ms, 2000ms, 2500ms
                    
                    // Check if device is still physically connected before retrying
                    val isDeviceAttached = usbManager?.deviceList?.values?.any { 
                        it.deviceId == usbDevice?.deviceId 
                    } ?: false
                    
                    if (!isDeviceAttached) {
                        logError("Device appears to be detached, aborting data receive")
                        
                        // Check if any USB device was unplugged - check for kernel activity
                        // This helps determine if it was physically unplugged
                        checkAndLogUSBActivityOnSystem()
                        
                        return@withContext null
                    }
                }
                
                // Increased timeout to 15 seconds for more reliable operation
                try {
                    length = usbDeviceConnection?.bulkTransfer(endpointIn, buffer, buffer.size, 15000)
                } catch (e: Exception) {
                    logError("Error during bulkTransfer on attempt $attempts: ${e.message}")
                    
                    // Look for IO errors that indicate physical disconnection
                    if (e.message?.contains("I/O error", ignoreCase = true) == true ||
                        e.message?.contains("connection", ignoreCase = true) == true) {
                        logError("Detected likely physical disconnection in exception: ${e.message}")
                    }
                    
                    // Continue with next attempt if we have any left
                    if (attempts >= maxAttempts) throw e
                    continue
                }
                
                if (length != null && length > 0) {
                    break // Success, exit the retry loop
                } else if (length == 0) {
                    logError("Received zero bytes on attempt $attempts (empty response)")
                } else if (length == null) {
                    logError("Received null length on attempt $attempts (possible connection issue)")
                } else if (length < 0) {
                    logError("Received negative length ($length) on attempt $attempts (transfer failed)")
                    
                    // Check USB device state
                    val isDeviceAttached = usbManager?.deviceList?.values?.any { 
                        it.deviceId == usbDevice?.deviceId 
                    } ?: false
                    
                    if (!isDeviceAttached) {
                        logError("Device no longer appears in USB device list")
                        
                        // Check system USB activity to determine if hardware disconnect
                        checkAndLogUSBActivityOnSystem()
                        
                        break // Stop attempting if device is gone
                    }
                }
            }
            
            val elapsedTime = System.currentTimeMillis() - startTime
            
            if (length != null && length > 0) {
                logMessage("Received $length bytes from camera in ${elapsedTime}ms (after $attempts attempts)")
                
                // If it's a small response, log the first few bytes for debugging
                if (length < 64) {
                    val hexString = buffer.take(length).joinToString("") { 
                        "%02x".format(it) 
                    }
                    logMessage("Response data: $hexString")
                }
                
                return@withContext buffer.copyOf(length)
            } else {
                logError("Failed to receive data from camera after ${elapsedTime}ms and $attempts attempts (length: $length)")
                
                // Check if device is still connected and report more details
                val isDeviceAttached = usbManager?.deviceList?.values?.any { 
                    it.deviceId == usbDevice?.deviceId 
                } ?: false
                
                val interfaceClaimed = try {
                    usbDeviceConnection?.claimInterface(usbInterface, false) ?: false
                } catch (e: Exception) {
                    logError("Exception when checking interface claim: ${e.message}")
                    false
                }
                
                logError("Device still attached: $isDeviceAttached")
                logError("Interface claimed: $interfaceClaimed")
                
                return@withContext null
            }
        } catch (e: Exception) {
            logError("Exception while receiving PTP data: ${e.message}")
            e.printStackTrace()
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
        var attemptCount = 0
        val maxAttempts = 3
        var lastError: Exception? = null
        
        while (attemptCount < maxAttempts) {
            attemptCount++
            
            try {
                logMessage("Opening PTP session with ID $sessionId... (Attempt $attemptCount of $maxAttempts)")
                
                // Check if the device is still connected
                if (usbDeviceConnection == null || endpointIn == null || endpointOut == null) {
                    logError("Cannot open session - USB connection not properly initialized")
                    
                    // If this is the first attempt, try to re-initialize the connection
                    if (attemptCount == 1 && usbDevice != null) {
                        logMessage("Attempting to re-initialize USB connection...")
                        try {
                            // Close any existing connection
                            usbDeviceConnection?.close()
                            
                            // Reopen the connection
                            usbDeviceConnection = usbManager?.openDevice(usbDevice)
                            if (usbDeviceConnection == null) {
                                logError("Failed to reopen USB connection")
                                continue
                            }
                            
                            // Claim the interface
                            usbInterface = usbDevice?.getInterface(0)
                            val claimed = usbDeviceConnection?.claimInterface(usbInterface, true) ?: false
                            if (!claimed) {
                                logError("Failed to claim interface")
                                continue
                            }
                            
                            // Find endpoints
                            for (i in 0 until (usbInterface?.endpointCount ?: 0)) {
                                val endpoint = usbInterface?.getEndpoint(i)
                                if (endpoint?.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                                    if (endpoint.direction == UsbConstants.USB_DIR_IN) {
                                        endpointIn = endpoint
                                    } else if (endpoint.direction == UsbConstants.USB_DIR_OUT) {
                                        endpointOut = endpoint
                                    }
                                } else if (endpoint?.type == UsbConstants.USB_ENDPOINT_XFER_INT && 
                                           endpoint.direction == UsbConstants.USB_DIR_IN) {
                                    endpointEvent = endpoint
                                }
                            }
                            
                            logMessage("USB connection re-initialized")
                            
                            // If we still don't have the required endpoints, fail
                            if (usbDeviceConnection == null || endpointIn == null || endpointOut == null) {
                                logError("Re-initialization didn't restore required endpoints")
                                continue
                            }
                        } catch (e: Exception) {
                            logError("Error during USB re-initialization: ${e.message}")
                            continue
                        }
                    } else {
                        return@withContext false
                    }
                }
                
                // Clear any pending data in the IN endpoint
                val clearBuffer = ByteArray(1024)
                try {
                    usbDeviceConnection?.bulkTransfer(endpointIn, clearBuffer, clearBuffer.size, 50)
                } catch (e: Exception) {
                    // Ignore errors during clearing
                }
                
                val command = ByteBuffer.allocate(16)
                command.order(ByteOrder.LITTLE_ENDIAN)
                command.putInt(16) // Length
                command.putShort(1) // Type (command block)
                command.putShort(PTP_OPERATION_OPEN_SESSION.toShort()) // Operation code
                command.putInt(++transactionId) // Transaction ID
                command.putInt(sessionId) // Session ID parameter
                
                // Add a timeout check for the command
                val startTime = System.currentTimeMillis()
                val sent = sendPtpCommand(command.array())
                val commandTime = System.currentTimeMillis() - startTime
                logMessage("Send command took $commandTime ms")
                
                if (sent < 0) {
                    logError("Failed to send OpenSession command")
                    delay(200) // Short delay before retry
                    continue
                }
                
                // Get response (should be a "OK" response code 0x2001)
                val response = ByteArray(12) // Response is usually 12 bytes
                
                // Add a timeout check for the response
                val responseStartTime = System.currentTimeMillis()
                val responseLength = try {
                    usbDeviceConnection?.bulkTransfer(endpointIn, response, response.size, 5000) ?: -1
                } catch (e: Exception) {
                    logError("Exception during response reading: ${e.message}")
                    e.printStackTrace()
                    lastError = e
                    -1
                }
                val responseTime = System.currentTimeMillis() - responseStartTime
                logMessage("Receive response took $responseTime ms, length: $responseLength bytes")
                
                if (responseLength >= 12) {
                    val responseCode = ByteBuffer.wrap(response, 6, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xFFFF
                    if (responseCode == 0x2001) { // OK response
                        logMessage("Session opened successfully")
                        sessionOpen = true
                        return@withContext true
                    } else {
                        logError("Failed to open session: response code 0x${responseCode.toString(16)}")
                        
                        // Try a different session ID on the next attempt
                        if (attemptCount < maxAttempts) {
                            sessionId = Random().nextInt(0x10000) // Use a new random session ID
                            delay(500) // Wait before retry
                        }
                    }
                } else {
                    logError("Invalid response when opening session (length: $responseLength)")
                    if (responseLength > 0) {
                        // Log the response bytes for debugging
                        val hexString = response.take(responseLength).joinToString("") { 
                            "%02x".format(it) 
                        }
                        logError("Response data: $hexString")
                    }
                    
                    // Try a different session ID on the next attempt
                    if (attemptCount < maxAttempts) {
                        sessionId = Random().nextInt(0x10000) // Use a new random session ID
                        delay(500) // Wait before retry
                    }
                }
            } catch (e: Exception) {
                // Check for cancellation specifically
                if (e is CancellationException) {
                    logError("Session opening was cancelled")
                    throw e  // Re-throw cancellation exceptions to properly propagate them
                } else {
                    logError("Error opening PTP session (attempt $attemptCount): ${e.message}")
                    e.printStackTrace()
                    lastError = e
                    
                    if (attemptCount < maxAttempts) {
                        // Wait before retry
                        delay(500)
                    }
                }
            }
        }
        
        logError("Failed to open session after $maxAttempts attempts")
        if (lastError != null) {
            logError("Last error: ${lastError.message}")
        }
        return@withContext false
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
     * Check system USB activity to determine if there was a physical disconnection
     */
    private fun checkAndLogUSBActivityOnSystem() {
        try {
            // List all current USB devices for diagnostics
            val currentDevices = usbManager?.deviceList?.values?.joinToString { 
                "${it.deviceName} (ID: ${it.deviceId}, VendorID: 0x${it.vendorId.toString(16)})" 
            } ?: "none"
            logMessage("Current USB devices at disconnect time: $currentDevices")
            
            // Compare with our last known device
            val ourDevice = "Last device: ${lastKnownDeviceName ?: "unknown"} (ID: ${lastKnownDeviceId ?: "unknown"})"
            logMessage(ourDevice)
            
            // Check if camera has gone to sleep mode by trying to enumerate device list again
            // Sometimes cameras will disappear and reappear in the list when in power saving mode
            val recheckDevices = usbManager?.deviceList?.values?.joinToString { 
                "${it.deviceName} (ID: ${it.deviceId})" 
            } ?: "none"
            
            if (recheckDevices != currentDevices) {
                logMessage("⚠️ USB device list changed during check - camera power mode change likely")
                logMessage("Updated device list: $recheckDevices")
            }
            
            // Camera vendors like Canon often have device sleep modes that can cause temporary disconnections
            if (lastKnownDeviceId != null) {
                val deviceReappeared = usbManager?.deviceList?.values?.any { 
                    it.deviceId == lastKnownDeviceId 
                } ?: false
                
                if (deviceReappeared) {
                    logMessage("Camera device ID reappeared in device list - likely power mode transition")
                }
            }
        } catch (e: Exception) {
            logError("Error checking system USB activity: ${e.message}")
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
        // Capture release caller information
        val callerStack = Thread.currentThread().stackTrace
            .drop(3) // Skip the immediate calling methods
            .take(5) // Take just a few frames for context
            .joinToString("\\n") { 
                "  at ${it.className}.${it.methodName}(${it.fileName}:${it.lineNumber})" 
            }
        logMessage("Releasing PTP controller resources\\nCalled from:\\n$callerStack")
        
        // Log current connection state before releasing
        val deviceInfo = getConnectedDeviceInfo()
        val connectionState = if (deviceInfo != null) "Connected to ${deviceInfo["deviceName"]}" else "Not connected"
        val usbState = "UsbDevice: ${usbDevice?.deviceName ?: "null"}, Connection: ${usbDeviceConnection != null}"
        logMessage("Connection state at release: $connectionState, $usbState")
        
        // Check if the device is still physically attached
        val isDeviceAttached = usbDevice?.let { device ->
            usbManager?.deviceList?.values?.any { 
                it.deviceId == device.deviceId 
            }
        } ?: false
        logMessage("Device still physically attached at release time: $isDeviceAttached")
        
        // Stop connection monitoring and keep-alive
        stopConnectionMonitoring()
        stopKeepAliveJob()
        
        // Cancel all coroutines
        coroutineScope.launch {
            // Try to properly close the session first
            if (sessionOpen) {
                try {
                    logMessage("Closing active PTP session")
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
            logMessage("Cancelling coroutine scope")
            coroutineScope.cancel()
        }
        
        // Release USB resources
        try {
            if (usbInterface != null && usbDeviceConnection != null) {
                logMessage("Releasing USB interface")
                usbDeviceConnection?.releaseInterface(usbInterface)
            }
            
            if (usbDeviceConnection != null) {
                logMessage("Closing USB connection")
                usbDeviceConnection?.close()
            }
        } catch (e: Exception) {
            logError("Error releasing USB resources: ${e.message}")
            e.printStackTrace()
        }
        
        // Log before nulling out
        logMessage("Clearing USB resource references")
        usbDevice = null
        usbDeviceConnection = null
        usbInterface = null
        endpointIn = null
        endpointOut = null
        endpointEvent = null
    }
    
    /**
     * Release any pending connections without trying to close the session
     * This is used for emergency cleanup when initialization fails
     */
    fun releaseAnyPendingConnections() {
        logMessage("Releasing any pending connections")
        
        try {
            // Stop any ongoing jobs
            stopConnectionMonitoring()
            stopKeepAliveJob()
            
            // Release USB resources immediately
            usbDeviceConnection?.releaseInterface(usbInterface)
            usbDeviceConnection?.close()
            
            // Reset state variables
            usbDevice = null
            usbDeviceConnection = null
            usbInterface = null
            endpointIn = null
            endpointOut = null
            endpointEvent = null
            sessionOpen = false
            
            logMessage("Pending connections released")
        } catch (e: Exception) {
            logError("Error releasing pending connections: ${e.message}")
            e.printStackTrace()
        }
        
        // Reset session state
        sessionOpen = false
        transactionId = 0
    }
    
    /**
     * Check if a device is connected
     * @param checkPhysicalDevice If true, also verify the device is still in the USB device list
     */
    fun isConnected(checkPhysicalDevice: Boolean = false): Boolean {
        val basicCheck = usbDevice != null && usbDeviceConnection != null && 
                         usbInterface != null && endpointIn != null && endpointOut != null
        
        if (!basicCheck) {
            return false
        }
        
        if (checkPhysicalDevice) {
            // Also verify the device is still physically connected
            val isDeviceAttached = usbManager?.deviceList?.values?.any { 
                it.deviceId == usbDevice?.deviceId 
            } ?: false
            
            if (!isDeviceAttached) {
                logError("Device appears to be physically disconnected")
                return false
            }
        }
        
        return true
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
    
    /**
     * Verify that the camera connection is still active and functioning
     * This actually tests communication with the camera, not just the local state
     * @return Boolean true if camera responds correctly
     */
    suspend fun verifyConnection(): Boolean = withContext(Dispatchers.IO) {
        try {
            logMessage("Starting camera connection verification...")
            
            // First, perform a quick check of local state
            if (!isConnected()) {
                logError("Cannot verify connection - not connected according to local state")
                return@withContext false
            }
            
            if (endpointIn == null || endpointOut == null || usbInterface == null) {
                logError("Cannot verify connection - USB interface not properly initialized")
                return@withContext false
            }
            
            // Check if device is still attached physically
            val isDeviceAttached = usbManager?.deviceList?.values?.any { 
                it.deviceId == usbDevice?.deviceId 
            } ?: false
            
            if (!isDeviceAttached) {
                logError("Cannot verify connection - device no longer attached to system")
                // Double check with UsbManager directly
                val deviceList = usbManager?.deviceList
                logMessage("Current USB device list size: ${deviceList?.size ?: 0}")
                deviceList?.forEach { (name, device) ->
                    logMessage("USB device: $name, ID: ${device.deviceId}, VendorID: 0x${device.vendorId.toString(16)}")
                }
                return@withContext false
            }
            
            // Check if our session is still valid
            if (sessionId <= 0 || !sessionOpen) {
                logError("Cannot verify connection - no active session")
                return@withContext false
            }
            
            logMessage("Verifying camera connection with session check...")
            
            // Try with get storage IDs first (usually most reliable)
            try {
                // Create a dedicated verification scope that won't be affected by cancellations
                val verifyScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
                
                val result = verifyScope.async {
                    try {
                        // Multiple verification attempts
                        var verified = false
                        var verificationAttempts = 0
                        val maxVerificationAttempts = 2 // Try twice if needed
                        
                        while (verificationAttempts < maxVerificationAttempts && !verified) {
                            verificationAttempts++
                            
                            if (verificationAttempts > 1) {
                                logMessage("Retry verification attempt $verificationAttempts...")
                                delay(1000) // Wait a second before retrying
                            }
                            
                            // Method 1: Try to get storage IDs - this is a common operation that should work reliably
                            val command = ByteBuffer.allocate(12)
                            command.order(ByteOrder.LITTLE_ENDIAN)
                            command.putInt(12) // Length
                            command.putShort(1) // Type (command block)
                            command.putShort(PTP_OPERATION_GET_STORAGE_IDS.toShort()) // Operation code
                            command.putInt(++transactionId) // Transaction ID
                            
                            val sent = sendPtpCommand(command.array())
                            if (sent > 0) {
                                // Wait a bit before reading response
                                delay(200) // Increased wait time
                                
                                // We don't need to process the response data, just check if we got a valid response
                                val responseData = receivePtpData()
                                if (responseData != null && responseData.isNotEmpty()) {
                                    logMessage("Connection verified successfully with GET_STORAGE_IDS")
                                    verified = true
                                    break
                                }
                            }
                            
                            // Check if the device is still physically attached before trying the fallback method
                            val stillAttached = usbManager?.deviceList?.values?.any { 
                                it.deviceId == usbDevice?.deviceId 
                            } ?: false
                            
                            if (!stillAttached) {
                                logError("Device disappeared during verification")
                                return@async false
                            }
                            
                            // Method 2: Try with device info as fallback
                            logMessage("Trying fallback verification with GET_DEVICE_INFO...")
                            
                            val fallbackCommand = ByteBuffer.allocate(12)
                            fallbackCommand.order(ByteOrder.LITTLE_ENDIAN)
                            fallbackCommand.putInt(12) // Length
                            fallbackCommand.putShort(1) // Type (command block)
                            fallbackCommand.putShort(PTP_OPERATION_GET_DEVICE_INFO.toShort()) // Operation code
                            fallbackCommand.putInt(++transactionId) // Transaction ID
                            
                            val fallbackSent = sendPtpCommand(fallbackCommand.array())
                            if (fallbackSent > 0) {
                                // Wait a bit before reading response
                                delay(200) // Increased wait time
                                
                                val fallbackResponse = receivePtpData()
                                if (fallbackResponse != null && fallbackResponse.isNotEmpty()) {
                                    logMessage("Connection verified successfully with GET_DEVICE_INFO")
                                    verified = true
                                    break
                                }
                            }
                        }
                        
                        if (!verified) {
                            logError("Connection verification failed after $verificationAttempts attempts - both methods failed")
                            return@async false
                        }
                        
                        return@async verified
                    } catch (e: Exception) {
                        logError("Error in verification task: ${e.message}")
                        e.printStackTrace()
                        return@async false
                    }
                }.await()
                
                // Clean up the scope
                verifyScope.cancel()
                
                return@withContext result
            } catch (e: Exception) {
                logError("Error during verification process: ${e.message}")
                e.printStackTrace()
                return@withContext false
            }
        } catch (e: Exception) {
            logError("Error verifying camera connection: ${e.message}")
            e.printStackTrace()
            
            // Connection failed, reset state
            return@withContext false
        }
    }
}
