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
import java.nio.ByteBuffer
import java.nio.ByteOrder

class MainActivity : FlutterActivity() {
    private val CHANNEL = "com.example.direct_sync_app/camera"
    private val ACTION_USB_PERMISSION = "com.example.direct_sync_app.USB_PERMISSION"
    
    private var usbManager: UsbManager? = null
    private var usbDevice: UsbDevice? = null
    private var usbDeviceConnection: UsbDeviceConnection? = null
    private var usbInterface: UsbInterface? = null
    private var endpointIn: UsbEndpoint? = null
    private var endpointOut: UsbEndpoint? = null
    
    private val coroutineScope = CoroutineScope(Dispatchers.Main + Job())

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (ACTION_USB_PERMISSION == intent?.action) {
                synchronized(this) {
                    val device: UsbDevice? = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        device?.apply {
                            setupDevice(this)
                        }
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
        
        val usbDeviceFilter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED) 
        }
        registerReceiver(usbReceiver, usbDeviceFilter)
    }

    private fun initializeCamera(result: MethodChannel.Result) {
        usbManager?.deviceList?.values?.find { device ->
            // Canon cameras typically use these vendor IDs
            device.vendorId == 0x04a9
        }?.let { device ->
            usbDevice = device
            val permissionIntent = PendingIntent.getBroadcast(
                this,
                0,
                Intent(ACTION_USB_PERMISSION),
                PendingIntent.FLAG_IMMUTABLE
            )
            usbManager?.requestPermission(device, permissionIntent)
            result.success(true)
        } ?: result.error("NO_DEVICE", "No Canon camera found", null)
    }

    private fun setupDevice(device: UsbDevice) {
        usbDeviceConnection = usbManager?.openDevice(device)
        usbInterface = device.getInterface(0)
        
        usbDeviceConnection?.claimInterface(usbInterface, true)
        
        // Find bulk endpoints
        for (i in 0 until usbInterface?.endpointCount!!) {
            val endpoint = usbInterface?.getEndpoint(i)
            if (endpoint?.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                if (endpoint.direction == UsbConstants.USB_DIR_IN) {
                    endpointIn = endpoint
                } else if (endpoint.direction == UsbConstants.USB_DIR_OUT) {
                    endpointOut = endpoint
                }
            }
        }
    }

    private fun getStorageIds(result: MethodChannel.Result) {
        coroutineScope.launch {
            try {
                val command = ByteBuffer.allocate(12)
                command.order(ByteOrder.LITTLE_ENDIAN)
                command.putInt(12) // Length
                command.putShort(1) // Type (command block)
                command.putShort(0x1004) // Operation code (GetStorageIDs)
                command.putInt(0) // Transaction ID
                
                sendPtpCommand(command.array())
                
                val response = receivePtpData()
                val storageIds = response?.let {
                    val buffer = ByteBuffer.wrap(it)
                    buffer.order(ByteOrder.LITTLE_ENDIAN)
                    val count = buffer.getInt(0)
                    val ids = ArrayList<Int>()
                    for (i in 0 until count) {
                        ids.add(buffer.getInt(4 + i * 4))
                    }
                    ids
                }
                
                result.success(storageIds)
            } catch (e: Exception) {
                result.error("PTP_ERROR", e.message, null)
            }
        }
    }

    private fun getObjectHandles(storageId: Int, result: MethodChannel.Result) {
        coroutineScope.launch {
            try {
                val command = ByteBuffer.allocate(16)
                command.order(ByteOrder.LITTLE_ENDIAN)
                command.putInt(16) // Length
                command.putShort(1) // Type
                command.putShort(0x1007) // Operation code (GetObjectHandles)
                command.putInt(0) // Transaction ID
                command.putInt(storageId) // Storage ID
                
                sendPtpCommand(command.array())
                
                val response = receivePtpData()
                val objectHandles = response?.let {
                    val buffer = ByteBuffer.wrap(it)
                    buffer.order(ByteOrder.LITTLE_ENDIAN)
                    val count = buffer.getInt(0)
                    val handles = ArrayList<Int>()
                    for (i in 0 until count) {
                        handles.add(buffer.getInt(4 + i * 4))
                    }
                    handles
                }
                
                result.success(objectHandles)
            } catch (e: Exception) {
                result.error("PTP_ERROR", e.message, null)
            }
        }
    }

    private fun getObjectInfo(objectHandle: Int, result: MethodChannel.Result) {
        coroutineScope.launch {
            try {
                val command = ByteBuffer.allocate(16)
                command.order(ByteOrder.LITTLE_ENDIAN)
                command.putInt(16) // Length
                command.putShort(1) // Type
                command.putShort(0x1008) // Operation code (GetObjectInfo)
                command.putInt(0) // Transaction ID
                command.putInt(objectHandle)
                
                sendPtpCommand(command.array())
                
                val response = receivePtpData()
                // Parse object info and return as Map
                val info = response?.let {
                    mapOf(
                        "objectHandle" to objectHandle,
                        "format" to ByteBuffer.wrap(it, 4, 2).order(ByteOrder.LITTLE_ENDIAN).short,
                        "size" to ByteBuffer.wrap(it, 8, 4).order(ByteOrder.LITTLE_ENDIAN).int
                    )
                }
                
                result.success(info)
            } catch (e: Exception) {
                result.error("PTP_ERROR", e.message, null)
            }
        }
    }

    private fun getObject(objectHandle: Int, result: MethodChannel.Result) {
        coroutineScope.launch {
            try {
                val command = ByteBuffer.allocate(16)
                command.order(ByteOrder.LITTLE_ENDIAN)
                command.putInt(16) // Length
                command.putShort(1) // Type
                command.putShort(0x1009) // Operation code (GetObject)
                command.putInt(0) // Transaction ID
                command.putInt(objectHandle)
                
                sendPtpCommand(command.array())
                
                val response = receivePtpData()
                result.success(response)
            } catch (e: Exception) {
                result.error("PTP_ERROR", e.message, null)
            }
        }
    }

    private suspend fun sendPtpCommand(command: ByteArray) = withContext(Dispatchers.IO) {
        usbDeviceConnection?.bulkTransfer(endpointOut, command, command.size, 5000)
    }

    private suspend fun receivePtpData(): ByteArray? = withContext(Dispatchers.IO) {
        val buffer = ByteArray(1024 * 1024) // 1MB buffer
        val length = usbDeviceConnection?.bulkTransfer(endpointIn, buffer, buffer.size, 5000)
        return@withContext if (length != null && length > 0) buffer.copyOf(length) else null
    }

    override fun onDestroy() {
        super.onDestroy()
        coroutineScope.cancel()
        unregisterReceiver(usbReceiver)
        usbDeviceConnection?.releaseInterface(usbInterface)
        usbDeviceConnection?.close()
    }
}
