package com.example.direct_sync_app


import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import android.content.Context
import android.hardware.usb.UsbManager
import android.content.Intent
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import android.hardware.usb.UsbDevice
import android.os.Parcelable 

class MainActivity: FlutterActivity() {
    private val STORAGE_PERMISSION_REQUEST = 1001
    private val MANAGE_STORAGE_PERMISSION_REQUEST = 1002
    private val CHANNEL = "com.example.direct_sync_app/ptp"
    private lateinit var channel: MethodChannel
    private lateinit var usbManager: UsbManager
    private var ptpUsbManager: PtpUsbManager? = null 

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        channel = MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL)
        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager

        // Initialize PtpUsbManager and pass the MethodChannel to it
        ptpUsbManager = PtpUsbManager(applicationContext, channel)

        channel.setMethodCallHandler { call, result ->
            when (call.method) {
                "startCameraMonitoring" -> {
                    // Check and request storage permissions before starting
                    if (checkStoragePermissions()) {
                        ptpUsbManager?.startMonitoring()
                        result.success(null)
                    } else {
                        requestStoragePermissions()
                        result.error("PERMISSION_DENIED", "Storage permissions required", null)
                    }
                }
                "requestStoragePermissions" -> {
                    requestStoragePermissions()
                    result.success(null)
                }
                "checkPermissions" -> {
                    val permissions = checkAllPermissions()
                    result.success(permissions)
                }
                "getMonitoringStatus" -> {
                    val status = ptpUsbManager?.getMonitoringStatus() ?: mapOf("error" to "PtpUsbManager not initialized")
                    result.success(status)
                }
                "getDownloadedPhotos" -> {
                    val photos = ptpUsbManager?.getDownloadedPhotos() ?: listOf<Map<String, Any>>()
                    result.success(photos)
                }
                "clearDownloadedPhotos" -> {
                    ptpUsbManager?.clearDownloadedPhotos()
                    result.success(null)
                }
                "browseStorage" -> {
                    // Execute in a background thread to not block UI
                    Thread {
                        val storageData = ptpUsbManager?.browseStorage() ?: mapOf(
                            "success" to false,
                            "error" to "PtpUsbManager not initialized"
                        )
                        activity.runOnUiThread {
                            result.success(storageData)
                        }
                    }.start()
                }
                else -> result.notImplemented()
            }
        }

        // Register USB device attached/detached broadcast receiver
        val usbDeviceFilter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED) 
        }
        registerReceiver(usbReceiver, usbDeviceFilter)
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(usbReceiver)
        ptpUsbManager?.stopMonitoring() // Clean up PTP resources
    }

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val action: String? = intent.action
            val device: UsbDevice? = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
            
            when (action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    android.util.Log.d("MainActivity", "📱 USB DEVICE ATTACHED: ${device?.deviceName}")
                    ptpUsbManager?.logPermissionStatus()
                    
                    device?.let { 
                        // Request permission to access the device
                        if (!usbManager.hasPermission(it)) {
                            android.util.Log.d("MainActivity", "📱 Requesting permission for device ${it.deviceName}")
                            val permissionIntent = ptpUsbManager?.getPermissionIntent()
                            if (permissionIntent != null) {
                                usbManager.requestPermission(it, permissionIntent)
                            } else {
                                android.util.Log.e("MainActivity", "❌ Failed to create permission intent")
                            }
                        } else {
                            android.util.Log.d("MainActivity", "✅ Already have permission for device ${it.deviceName}")
                            ptpUsbManager?.onUsbDeviceAttached(it) 
                            // Check permission status after we know we have it
                            ptpUsbManager?.checkPermissionAfterResponse(it)
                        }
                    }
                }
                
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    android.util.Log.d("MainActivity", "📱 USB DEVICE DETACHED: ${device?.deviceName}")
                    device?.let {
                        ptpUsbManager?.onUsbDeviceDetached(it)
                    }
                }
                
                PtpUsbManager.ACTION_USB_PERMISSION -> {
                    val permissionGranted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    android.util.Log.d("MainActivity", "📱 USB PERMISSION RESPONSE: granted=$permissionGranted for device=${device?.deviceName}")
                    
                    if (device != null) {
                        // Always check permission status after a response regardless of granted/denied
                        ptpUsbManager?.checkPermissionAfterResponse(device)
                        
                        if (permissionGranted) {
                            // Send permission granted status to Flutter
                            channel.invokeMethod("permissionChanged", mapOf(
                                "device" to device.deviceName,
                                "granted" to true
                            ))
                            
                            // Now we can proceed with device connection
                            ptpUsbManager?.startMonitoring()
                            ptpUsbManager?.onUsbDeviceAttached(device)
                            ptpUsbManager?.logDeviceInfo(device)

                        } else {
                            // Send permission denied status to Flutter
                            channel.invokeMethod("permissionChanged", mapOf(
                                "device" to device.deviceName,
                                "granted" to false
                            ))
                            
                            android.util.Log.e("MainActivity", "❌ Permission denied for device ${device.deviceName}")
                        }
                    }
                }
            }
        }
    }

    // Permission handling methods
    private fun checkStoragePermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android 13+ (API 33+)
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Android 11+ (API 30+)
            // Check if we have MANAGE_EXTERNAL_STORAGE or if we're using scoped storage
            android.os.Environment.isExternalStorageManager() ||
            (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED &&
             ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED)
        } else {
            // Android 10 and below
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestStoragePermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android 13+ - Request granular media permissions
            ActivityCompat.requestPermissions(
                this,
                arrayOf(
                    Manifest.permission.READ_MEDIA_IMAGES,
                    Manifest.permission.READ_MEDIA_VIDEO
                ),
                STORAGE_PERMISSION_REQUEST
            )
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Android 11+ - Try to request MANAGE_EXTERNAL_STORAGE first
            if (!android.os.Environment.isExternalStorageManager()) {
                try {
                    val intent = android.content.Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                    intent.data = android.net.Uri.parse("package:$packageName")
                    startActivityForResult(intent, MANAGE_STORAGE_PERMISSION_REQUEST)
                } catch (e: Exception) {
                    // Fallback to regular storage permissions
                    ActivityCompat.requestPermissions(
                        this,
                        arrayOf(
                            Manifest.permission.READ_EXTERNAL_STORAGE,
                            Manifest.permission.WRITE_EXTERNAL_STORAGE
                        ),
                        STORAGE_PERMISSION_REQUEST
                    )
                }
            }
        } else {
            // Android 10 and below
            ActivityCompat.requestPermissions(
                this,
                arrayOf(
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                ),
                STORAGE_PERMISSION_REQUEST
            )
        }
    }

    private fun checkAllPermissions(): Map<String, Boolean> {
        val permissions = mutableMapOf<String, Boolean>()
        android.util.Log.d("MainActivity", "🔍 Checking all permissions...")
        // USB Host feature
        permissions["usbHost"] = packageManager.hasSystemFeature(PackageManager.FEATURE_USB_HOST)

        // Storage permissions
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions["readMediaImages"] = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED
            permissions["readMediaVideo"] = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED
        } else {
            permissions["readExternalStorage"] = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
            permissions["writeExternalStorage"] = ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }

        // MANAGE_EXTERNAL_STORAGE (Android 11+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            permissions["manageExternalStorage"] = android.os.Environment.isExternalStorageManager()
        }

        return permissions
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        when (requestCode) {
            STORAGE_PERMISSION_REQUEST -> {
                val allGranted = grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }
                if (allGranted) {
                    android.util.Log.d("MainActivity", "✅ Storage permissions granted")
                    // Now we can start camera monitoring
                    ptpUsbManager?.startMonitoring()
                } else {
                    android.util.Log.w("MainActivity", "❌ Storage permissions denied")
                }

                // Notify Flutter about permission result
                channel.invokeMethod("storagePermissionsResult", mapOf(
                    "granted" to allGranted
                ))
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        when (requestCode) {
            MANAGE_STORAGE_PERMISSION_REQUEST -> {
                val granted = android.os.Environment.isExternalStorageManager()
                android.util.Log.d("MainActivity", "MANAGE_EXTERNAL_STORAGE result: $granted")

                // Notify Flutter about the result
                channel.invokeMethod("manageStorageResult", mapOf(
                    "granted" to granted
                ))

                if (granted) {
                    // Now we can start camera monitoring
                    ptpUsbManager?.startMonitoring()
                }
            }
        }
    }
}