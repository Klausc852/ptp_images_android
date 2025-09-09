package com.example.direct_sync_app

import android.util.Log
import kotlinx.coroutines.*
import java.io.*
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.*

/**
 * Controller for PTP/IP protocol which allows connecting to cameras over network/WiFi
 * Based on information from:
 * - https://julianschroden.com/post/2023-06-15-capturing-images-using-ptp-ip-on-canon-eos-cameras/
 * - http://www.gphoto.org/doc/ptpip.php
 */
class PtpIpController {
    companion object {
        private const val TAG = "PtpIpController"
        private const val DEFAULT_PORT = 15740 // Standard Canon PTP/IP port
        
        // PTP/IP Operation Codes - same as regular PTP
        const val PTP_OPERATION_GET_STORAGE_IDS = 0x1004
        const val PTP_OPERATION_GET_STORAGE_INFO = 0x1005
        const val PTP_OPERATION_GET_OBJECT_HANDLES = 0x1007
        const val PTP_OPERATION_GET_OBJECT_INFO = 0x1008
        const val PTP_OPERATION_GET_OBJECT = 0x1009
        
        // PTP/IP specific packet types
        private const val PTPIP_INIT_COMMAND_REQUEST = 1
        private const val PTPIP_INIT_COMMAND_ACK = 2
        private const val PTPIP_INIT_EVENT_REQUEST = 3
        private const val PTPIP_INIT_EVENT_ACK = 4
        private const val PTPIP_OPERATION_REQUEST = 5
        private const val PTPIP_OPERATION_RESPONSE = 6
        private const val PTPIP_EVENT = 7
        private const val PTPIP_START_DATA_PACKET = 8
        private const val PTPIP_DATA_PACKET = 9
        private const val PTPIP_CANCEL_TRANSACTION = 10
        private const val PTPIP_END_DATA_PACKET = 11
        private const val PTPIP_PING = 12
        private const val PTPIP_PONG = 13
        
        // GUID for identification
        private val CLIENT_GUID = UUID.randomUUID().toString()
    }
    
    // Network connection
    private var commandSocket: Socket? = null
    private var eventSocket: Socket? = null
    private var commandInputStream: DataInputStream? = null
    private var commandOutputStream: DataOutputStream? = null
    private var eventInputStream: DataInputStream? = null
    private var eventOutputStream: DataOutputStream? = null
    
    // Session management
    private var connected = false
    private var sessionId: Int = 0
    private var transactionId: Int = 0
    
    // Coroutine scope
    private val coroutineScope = CoroutineScope(Dispatchers.IO + Job())
    
    /**
     * Connect to a camera via PTP/IP using its IP address
     * @param ipAddress The IP address of the camera
     * @param port The port to connect to, defaults to 15740 for Canon
     * @return True if connection was successful
     */
    suspend fun connect(ipAddress: String, port: Int = DEFAULT_PORT): Boolean = withContext(Dispatchers.IO) {
        try {
            logMessage("Connecting to camera at $ipAddress:$port")
            
            // Step 1: Create command socket connection
            commandSocket = Socket(ipAddress, port)
            commandInputStream = DataInputStream(commandSocket!!.getInputStream())
            commandOutputStream = DataOutputStream(commandSocket!!.getOutputStream())
            
            // Step 2: Initialize connection with camera
            val initResult = initCommandConnection()
            if (!initResult) {
                logError("Failed to initialize command connection")
                closeConnection()
                return@withContext false
            }
            
            // Step 3: Create event socket connection
            eventSocket = Socket(ipAddress, port)
            eventInputStream = DataInputStream(eventSocket!!.getInputStream())
            eventOutputStream = DataOutputStream(eventSocket!!.getOutputStream())
            
            // Step 4: Initialize event connection
            val eventResult = initEventConnection()
            if (!eventResult) {
                logError("Failed to initialize event connection")
                closeConnection()
                return@withContext false
            }
            
            // Step 5: Open a session
            sessionId = 1 // Default session ID
            if (!openSession(sessionId)) {
                logError("Failed to open session")
                closeConnection()
                return@withContext false
            }
            
            // Connection successful
            connected = true
            logMessage("Successfully connected to camera via PTP/IP")
            
            // Start listening for events in background
            startEventListener()
            
            return@withContext true
        } catch (e: Exception) {
            logError("Connection error: ${e.message}")
            e.printStackTrace()
            closeConnection()
            return@withContext false
        }
    }
    
    /**
     * Initialize the command connection
     */
    private fun initCommandConnection(): Boolean {
        try {
            // Prepare init command packet
            val hostName = "Android-DirectSyncApp"
            val hostNameBytes = hostName.toByteArray(Charsets.UTF_8)
            
            // Calculate packet size: 8 (header) + 16 (GUID) + 2 (hostname length) + hostname length
            val packetSize = 8 + 16 + 2 + hostNameBytes.size
            
            val initPacket = ByteBuffer.allocate(packetSize)
            initPacket.order(ByteOrder.LITTLE_ENDIAN)
            
            // Write header
            initPacket.putInt(packetSize)
            initPacket.putInt(PTPIP_INIT_COMMAND_REQUEST)
            
            // Write GUID (16 bytes)
            val guidBytes = CLIENT_GUID.replace("-", "").substring(0, 32)
            for (i in 0 until 16) {
                val byteVal = Integer.parseInt(guidBytes.substring(i * 2, i * 2 + 2), 16).toByte()
                initPacket.put(byteVal)
            }
            
            // Write hostname
            initPacket.putShort(hostNameBytes.size.toShort())
            initPacket.put(hostNameBytes)
            
            // Send packet
            commandOutputStream?.write(initPacket.array())
            logMessage("Sent init command packet")
            
            // Read response
            val responseSize = commandInputStream?.readInt() ?: 0
            if (responseSize < 8) {
                logError("Invalid response size: $responseSize")
                return false
            }
            
            val responseType = commandInputStream?.readInt() ?: 0
            if (responseType != PTPIP_INIT_COMMAND_ACK) {
                logError("Unexpected response type: $responseType")
                return false
            }
            
            // Read session ID - this will be needed for later operations
            sessionId = commandInputStream?.readInt() ?: 0
            logMessage("Received command connection ACK with session ID: $sessionId")
            
            return true
        } catch (e: Exception) {
            logError("Error initializing command connection: ${e.message}")
            e.printStackTrace()
            return false
        }
    }
    
    /**
     * Initialize the event connection
     */
    private fun initEventConnection(): Boolean {
        try {
            // Prepare event init packet
            val packetSize = 8 + 4 // 8 bytes header, 4 bytes session ID
            
            val eventInitPacket = ByteBuffer.allocate(packetSize)
            eventInitPacket.order(ByteOrder.LITTLE_ENDIAN)
            
            // Write header
            eventInitPacket.putInt(packetSize)
            eventInitPacket.putInt(PTPIP_INIT_EVENT_REQUEST)
            
            // Write session ID
            eventInitPacket.putInt(sessionId)
            
            // Send packet
            eventOutputStream?.write(eventInitPacket.array())
            logMessage("Sent event init packet")
            
            // Read response
            val responseSize = eventInputStream?.readInt() ?: 0
            if (responseSize < 8) {
                logError("Invalid event response size: $responseSize")
                return false
            }
            
            val responseType = eventInputStream?.readInt() ?: 0
            if (responseType != PTPIP_INIT_EVENT_ACK) {
                logError("Unexpected event response type: $responseType")
                return false
            }
            
            logMessage("Received event connection ACK")
            return true
        } catch (e: Exception) {
            logError("Error initializing event connection: ${e.message}")
            e.printStackTrace()
            return false
        }
    }
    
    /**
     * Open a PTP session
     */
    private fun openSession(sessionId: Int): Boolean {
        try {
            // Prepare operation request packet
            val packetSize = 30  // Fixed size for simple operation
            val packet = ByteBuffer.allocate(packetSize)
            packet.order(ByteOrder.LITTLE_ENDIAN)
            
            // Header: size and type
            packet.putInt(packetSize)
            packet.putInt(PTPIP_OPERATION_REQUEST)
            
            // Operation data
            packet.putInt(1)  // Data phase info (1 = no data phase)
            packet.putShort(0x1002.toShort())  // OpenSession operation code
            transactionId++
            packet.putInt(transactionId)  // Transaction ID
            packet.putInt(sessionId)  // Parameter 1: session ID
            
            // Fill remaining with zeros
            for (i in 0 until 10) {
                packet.put(0.toByte())
            }
            
            // Send packet
            commandOutputStream?.write(packet.array())
            logMessage("Sent OpenSession request")
            
            // Read response
            val responseSize = commandInputStream?.readInt() ?: 0
            if (responseSize < 8) {
                logError("Invalid open session response size: $responseSize")
                return false
            }
            
            val responseType = commandInputStream?.readInt() ?: 0
            if (responseType != PTPIP_OPERATION_RESPONSE) {
                logError("Unexpected open session response type: $responseType")
                return false
            }
            
            // Read response code
            val responseCode = commandInputStream?.readShort()?.toInt() ?: 0
            if (responseCode != 0x2001) {  // OK response
                logError("Open session failed with response code: $responseCode")
                return false
            }
            
            logMessage("Session opened successfully")
            return true
        } catch (e: Exception) {
            logError("Error opening session: ${e.message}")
            e.printStackTrace()
            return false
        }
    }
    
    /**
     * Get a list of storage IDs from the camera
     */
    suspend fun getStorageIds(): List<Int>? = withContext(Dispatchers.IO) {
        if (!connected) {
            logError("Not connected to camera")
            return@withContext null
        }
        
        try {
            // Prepare operation request packet
            val packetSize = 30  // Fixed size for simple operation
            val packet = ByteBuffer.allocate(packetSize)
            packet.order(ByteOrder.LITTLE_ENDIAN)
            
            // Header: size and type
            packet.putInt(packetSize)
            packet.putInt(PTPIP_OPERATION_REQUEST)
            
            // Operation data
            packet.putInt(1)  // Data phase info (1 = operation with data-in phase)
            packet.putShort(PTP_OPERATION_GET_STORAGE_IDS.toShort())  // GetStorageIDs operation code
            transactionId++
            packet.putInt(transactionId)  // Transaction ID
            
            // Fill remaining with zeros (no parameters needed)
            for (i in 0 until 14) {
                packet.put(0.toByte())
            }
            
            // Send packet
            commandOutputStream?.write(packet.array())
            logMessage("Sent GetStorageIDs request")
            
            // Read data phase start packet
            val dataStartSize = commandInputStream?.readInt() ?: 0
            val dataStartType = commandInputStream?.readInt() ?: 0
            
            if (dataStartType != PTPIP_START_DATA_PACKET) {
                logError("Unexpected data start type: $dataStartType")
                return@withContext null
            }
            
            val dataSize = commandInputStream?.readInt() ?: 0
            logMessage("Receiving data packet of size $dataSize bytes")
            
            // Read data
            val data = ByteArray(dataSize)
            commandInputStream?.readFully(data)
            
            // Process data - storage IDs array
            val buffer = ByteBuffer.wrap(data)
            buffer.order(ByteOrder.LITTLE_ENDIAN)
            val count = buffer.getInt()
            
            val ids = ArrayList<Int>()
            for (i in 0 until count) {
                ids.add(buffer.getInt())
            }
            
            // Read operation response packet
            val responseSize = commandInputStream?.readInt() ?: 0
            val responseType = commandInputStream?.readInt() ?: 0
            
            if (responseType != PTPIP_OPERATION_RESPONSE) {
                logError("Unexpected response type after data: $responseType")
                return@withContext null
            }
            
            // Read response code
            val responseCode = commandInputStream?.readShort()?.toInt() ?: 0
            if (responseCode != 0x2001) {  // OK response
                logError("GetStorageIDs failed with response code: $responseCode")
                return@withContext null
            }
            
            logMessage("Successfully retrieved ${ids.size} storage IDs")
            return@withContext ids
        } catch (e: Exception) {
            logError("Error getting storage IDs: ${e.message}")
            e.printStackTrace()
            return@withContext null
        }
    }
    
    /**
     * Get object handles from a specific storage
     */
    suspend fun getObjectHandles(storageId: Int): List<Int>? = withContext(Dispatchers.IO) {
        if (!connected) {
            logError("Not connected to camera")
            return@withContext null
        }
        
        try {
            // Prepare operation request packet
            val packetSize = 34  // Fixed size for this operation with one parameter
            val packet = ByteBuffer.allocate(packetSize)
            packet.order(ByteOrder.LITTLE_ENDIAN)
            
            // Header: size and type
            packet.putInt(packetSize)
            packet.putInt(PTPIP_OPERATION_REQUEST)
            
            // Operation data
            packet.putInt(1)  // Data phase info (1 = operation with data-in phase)
            packet.putShort(PTP_OPERATION_GET_OBJECT_HANDLES.toShort())
            transactionId++
            packet.putInt(transactionId)
            packet.putInt(storageId)  // Parameter 1: storage ID
            packet.putInt(0)          // Parameter 2: object format code (0 = all formats)
            packet.putInt(0)          // Parameter 3: association (0 = all objects)
            
            // Fill remaining with zeros
            for (i in 0 until 6) {
                packet.put(0.toByte())
            }
            
            // Send packet
            commandOutputStream?.write(packet.array())
            logMessage("Sent GetObjectHandles request for storage ID: $storageId")
            
            // Read data phase start packet
            val dataStartSize = commandInputStream?.readInt() ?: 0
            val dataStartType = commandInputStream?.readInt() ?: 0
            
            if (dataStartType != PTPIP_START_DATA_PACKET) {
                logError("Unexpected data start type: $dataStartType")
                return@withContext null
            }
            
            val dataSize = commandInputStream?.readInt() ?: 0
            logMessage("Receiving object handles data of size $dataSize bytes")
            
            // Read data
            val data = ByteArray(dataSize)
            commandInputStream?.readFully(data)
            
            // Process data - object handles array
            val buffer = ByteBuffer.wrap(data)
            buffer.order(ByteOrder.LITTLE_ENDIAN)
            val count = buffer.getInt()
            
            val handles = ArrayList<Int>()
            for (i in 0 until count) {
                handles.add(buffer.getInt())
            }
            
            // Read operation response packet
            val responseSize = commandInputStream?.readInt() ?: 0
            val responseType = commandInputStream?.readInt() ?: 0
            
            if (responseType != PTPIP_OPERATION_RESPONSE) {
                logError("Unexpected response type after data: $responseType")
                return@withContext null
            }
            
            // Read response code
            val responseCode = commandInputStream?.readShort()?.toInt() ?: 0
            if (responseCode != 0x2001) {  // OK response
                logError("GetObjectHandles failed with response code: $responseCode")
                return@withContext null
            }
            
            logMessage("Successfully retrieved ${handles.size} object handles")
            return@withContext handles
        } catch (e: Exception) {
            logError("Error getting object handles: ${e.message}")
            e.printStackTrace()
            return@withContext null
        }
    }
    
    /**
     * Get information about a specific object
     */
    suspend fun getObjectInfo(objectHandle: Int): Map<String, Any>? = withContext(Dispatchers.IO) {
        if (!connected) {
            logError("Not connected to camera")
            return@withContext null
        }
        
        try {
            // Prepare operation request packet
            val packetSize = 30  // Fixed size for this operation with one parameter
            val packet = ByteBuffer.allocate(packetSize)
            packet.order(ByteOrder.LITTLE_ENDIAN)
            
            // Header: size and type
            packet.putInt(packetSize)
            packet.putInt(PTPIP_OPERATION_REQUEST)
            
            // Operation data
            packet.putInt(1)  // Data phase info (1 = operation with data-in phase)
            packet.putShort(PTP_OPERATION_GET_OBJECT_INFO.toShort())
            transactionId++
            packet.putInt(transactionId)
            packet.putInt(objectHandle)  // Parameter 1: object handle
            
            // Fill remaining with zeros
            for (i in 0 until 10) {
                packet.put(0.toByte())
            }
            
            // Send packet
            commandOutputStream?.write(packet.array())
            logMessage("Sent GetObjectInfo request for object handle: $objectHandle")
            
            // Read data phase start packet
            val dataStartSize = commandInputStream?.readInt() ?: 0
            val dataStartType = commandInputStream?.readInt() ?: 0
            
            if (dataStartType != PTPIP_START_DATA_PACKET) {
                logError("Unexpected data start type: $dataStartType")
                return@withContext null
            }
            
            val dataSize = commandInputStream?.readInt() ?: 0
            logMessage("Receiving object info data of size $dataSize bytes")
            
            // Read data
            val data = ByteArray(dataSize)
            commandInputStream?.readFully(data)
            
            // Process data - object info structure
            val buffer = ByteBuffer.wrap(data)
            buffer.order(ByteOrder.LITTLE_ENDIAN)
            
            // Extract key information
            val storageId = buffer.getInt()
            val objectFormat = buffer.getShort().toInt() & 0xFFFF
            val protectionStatus = buffer.getShort().toInt() & 0xFFFF
            val objectCompressedSize = buffer.getInt()
            
            val info = mapOf(
                "objectHandle" to objectHandle,
                "storageId" to storageId,
                "format" to objectFormat,
                "protectionStatus" to protectionStatus,
                "size" to objectCompressedSize
            )
            
            // Read operation response packet
            val responseSize = commandInputStream?.readInt() ?: 0
            val responseType = commandInputStream?.readInt() ?: 0
            
            if (responseType != PTPIP_OPERATION_RESPONSE) {
                logError("Unexpected response type after data: $responseType")
                return@withContext null
            }
            
            // Read response code
            val responseCode = commandInputStream?.readShort()?.toInt() ?: 0
            if (responseCode != 0x2001) {  // OK response
                logError("GetObjectInfo failed with response code: $responseCode")
                return@withContext null
            }
            
            logMessage("Successfully retrieved object info")
            return@withContext info
        } catch (e: Exception) {
            logError("Error getting object info: ${e.message}")
            e.printStackTrace()
            return@withContext null
        }
    }
    
    /**
     * Download an object (file) from the camera
     */
    suspend fun getObject(objectHandle: Int): ByteArray? = withContext(Dispatchers.IO) {
        if (!connected) {
            logError("Not connected to camera")
            return@withContext null
        }
        
        try {
            // Prepare operation request packet
            val packetSize = 30  // Fixed size for this operation with one parameter
            val packet = ByteBuffer.allocate(packetSize)
            packet.order(ByteOrder.LITTLE_ENDIAN)
            
            // Header: size and type
            packet.putInt(packetSize)
            packet.putInt(PTPIP_OPERATION_REQUEST)
            
            // Operation data
            packet.putInt(1)  // Data phase info (1 = operation with data-in phase)
            packet.putShort(PTP_OPERATION_GET_OBJECT.toShort())
            transactionId++
            packet.putInt(transactionId)
            packet.putInt(objectHandle)  // Parameter 1: object handle
            
            // Fill remaining with zeros
            for (i in 0 until 10) {
                packet.put(0.toByte())
            }
            
            // Send packet
            commandOutputStream?.write(packet.array())
            logMessage("Sent GetObject request for object handle: $objectHandle")
            
            // Read data phase start packet
            val dataStartSize = commandInputStream?.readInt() ?: 0
            val dataStartType = commandInputStream?.readInt() ?: 0
            
            if (dataStartType != PTPIP_START_DATA_PACKET) {
                logError("Unexpected data start type: $dataStartType")
                return@withContext null
            }
            
            val totalDataSize = commandInputStream?.readInt() ?: 0
            logMessage("Receiving object data of size $totalDataSize bytes")
            
            // Read data - potentially in multiple packets
            val outputStream = ByteArrayOutputStream(totalDataSize)
            var bytesRemaining = totalDataSize
            
            while (bytesRemaining > 0) {
                // Read data packet header if not first packet
                var packetSize = if (bytesRemaining < totalDataSize) {
                    val size = commandInputStream?.readInt() ?: 0
                    val type = commandInputStream?.readInt() ?: 0
                    
                    if (type != PTPIP_DATA_PACKET) {
                        logError("Unexpected data packet type: $type")
                        return@withContext null
                    }
                    
                    commandInputStream?.readInt() ?: 0
                } else {
                    // For first packet, we already have the size
                    totalDataSize
                }
                
                // Read actual data (in chunks to handle large files)
                val bufferSize = minOf(packetSize, bytesRemaining, 8192) // 8KB buffer
                val buffer = ByteArray(bufferSize)
                var bytesRead = 0
                
                while (bytesRead < packetSize && bytesRemaining > 0) {
                    val readSize = minOf(buffer.size, bytesRemaining)
                    val read = commandInputStream?.read(buffer, 0, readSize) ?: -1
                    
                    if (read <= 0) break
                    
                    outputStream.write(buffer, 0, read)
                    bytesRead += read
                    bytesRemaining -= read
                    
                    // Log progress for large files
                    if (totalDataSize > 1024*1024) {  // > 1MB
                        val percent = ((totalDataSize - bytesRemaining) * 100) / totalDataSize
                        if (percent % 10 == 0) {
                            logMessage("Download progress: $percent%")
                        }
                    }
                }
            }
            
            // Read operation response packet
            val responseSize = commandInputStream?.readInt() ?: 0
            val responseType = commandInputStream?.readInt() ?: 0
            
            if (responseType != PTPIP_OPERATION_RESPONSE) {
                logError("Unexpected response type after data: $responseType")
                return@withContext null
            }
            
            // Read response code
            val responseCode = commandInputStream?.readShort()?.toInt() ?: 0
            if (responseCode != 0x2001) {  // OK response
                logError("GetObject failed with response code: $responseCode")
                return@withContext null
            }
            
            val data = outputStream.toByteArray()
            logMessage("Successfully downloaded object (${data.size} bytes)")
            return@withContext data
        } catch (e: Exception) {
            logError("Error downloading object: ${e.message}")
            e.printStackTrace()
            return@withContext null
        }
    }
    
    /**
     * Start background event listener
     */
    private fun startEventListener() {
        coroutineScope.launch {
            logMessage("Started event listener")
            
            try {
                while (connected) {
                    // Check if we can read an event
                    if (eventInputStream?.available() ?: 0 > 0) {
                        val eventSize = eventInputStream?.readInt() ?: 0
                        val eventType = eventInputStream?.readInt() ?: 0
                        
                        if (eventType == PTPIP_EVENT) {
                            // Process event data
                            val eventCode = eventInputStream?.readShort()?.toInt() ?: 0
                            val transactionId = eventInputStream?.readInt() ?: 0
                            
                            logMessage("Received event: code=$eventCode, transactionId=$transactionId")
                            
                            // Read any additional parameters
                            // For simplicity, we're not fully processing events here
                        }
                    }
                    
                    // Avoid busy waiting
                    delay(100)
                }
            } catch (e: Exception) {
                if (connected) {
                    logError("Event listener error: ${e.message}")
                    e.printStackTrace()
                }
            }
        }
    }
    
    /**
     * Check if connected to camera
     */
    fun isConnected(): Boolean {
        return connected
    }
    
    /**
     * Close all connections
     */
    fun closeConnection() {
        connected = false
        
        try {
            // Close command connection
            commandInputStream?.close()
            commandOutputStream?.close()
            commandSocket?.close()
            
            // Close event connection
            eventInputStream?.close()
            eventOutputStream?.close()
            eventSocket?.close()
            
            logMessage("All connections closed")
        } catch (e: Exception) {
            logError("Error closing connections: ${e.message}")
        } finally {
            commandInputStream = null
            commandOutputStream = null
            commandSocket = null
            eventInputStream = null
            eventOutputStream = null
            eventSocket = null
        }
    }
    
    /**
     * Cancel all coroutine jobs and close connections
     */
    fun release() {
        coroutineScope.cancel()
        closeConnection()
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
}
