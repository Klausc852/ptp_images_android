import 'dart:async';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'storage_browser_screen.dart';

// Define the method channel for native communication
const platform = MethodChannel('com.example.direct_sync_app/camera');

class CameraConnectionScreen extends StatefulWidget {
  const CameraConnectionScreen({Key? key}) : super(key: key);

  @override
  State<CameraConnectionScreen> createState() => _CameraConnectionScreenState();
}

class _CameraConnectionScreenState extends State<CameraConnectionScreen>
    with WidgetsBindingObserver {
  bool _isConnected = false;
  bool _isChecking = false;
  String _connectionStatus = 'Camera not connected';
  Timer? _connectionCheckTimer;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _checkCameraConnection();
    // Start periodic connection checks
    _startConnectionMonitoring();
    // Setup camera event listener
    _setupEventListener();
  }

  @override
  void dispose() {
    _stopConnectionMonitoring();
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    // When app comes back to foreground, check connection status
    if (state == AppLifecycleState.resumed) {
      _checkCameraConnection();
    }
  }

  void _startConnectionMonitoring() {
    // Check connection status every 5 seconds
    _connectionCheckTimer = Timer.periodic(const Duration(seconds: 5), (timer) {
      // Only perform check if we're not already checking and if the widget is still mounted
      if (!_isChecking && mounted) {
        _checkConnectionSilently();
      }
    });
  }

  void _stopConnectionMonitoring() {
    _connectionCheckTimer?.cancel();
    _connectionCheckTimer = null;
  }

  void _setupEventListener() {
    platform.setMethodCallHandler((call) async {
      switch (call.method) {
        case 'onCameraEvent':
          final Map<dynamic, dynamic> event = call.arguments;
          if (event['event'] == 'new_photo') {
            _handleNewPhotoEvent(event);
          }
          break;
        default:
          print('Unknown method call: ${call.method}');
      }
      return null;
    });
  }

  void _handleNewPhotoEvent(Map<dynamic, dynamic> event) {
    final int objectHandle = event['objectHandle'];
    final Map<dynamic, dynamic>? info = event['info'];

    print('New photo detected! Handle: 0x${objectHandle.toRadixString(16)}');
    if (info != null) {
      print('Photo info: $info');
    }

    // Show notification to user
    if (mounted) {
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          content: Text('New photo taken on camera!'),
          action: SnackBarAction(
            label: 'View',
            onPressed: () {
              // Navigate to photo viewer or refresh storage browser
              Navigator.of(context).push(
                MaterialPageRoute(
                  builder: (context) => const StorageBrowserScreen(),
                ),
              );
            },
          ),
          duration: const Duration(seconds: 5),
        ),
      );
    }
  }

  /// Verify that the camera connection is active and functioning
  /// This tests actual communication with the camera, not just app state
  Future<bool> _verifyCameraConnection({bool showIndicators = true}) async {
    if (showIndicators) {
      setState(() {
        _isChecking = true;
        _connectionStatus = 'Verifying camera connection...';
      });
    }

    try {
      final result = await platform.invokeMethod('verifyCameraConnection');

      final bool connected = result['connected'] ?? false;
      final String message =
          result['message'] ??
          (connected ? 'Camera connection verified' : 'Camera connection lost');

      if (showIndicators) {
        setState(() {
          _isConnected = connected;
          _connectionStatus = message;
          _isChecking = false;
        });
      }

      return connected;
    } catch (e) {
      if (showIndicators) {
        setState(() {
          _isConnected = false;
          _connectionStatus = 'Connection verification failed: ${e.toString()}';
          _isChecking = false;
        });
      }

      print('Error verifying connection: $e');
      return false;
    }
  }

  Future<void> _connectToWifiCamera(
    String ipAddress, {
    int port = 15740,
  }) async {
    setState(() {
      _isChecking = true;
      _connectionStatus = 'Connecting to camera via WiFi...';
    });

    try {
      final result = await platform.invokeMethod('connectToWifiCamera', {
        'ipAddress': ipAddress,
        'port': port,
      });

      setState(() {
        _isConnected = result['connected'] ?? false;
        _connectionStatus = result['message'] ?? 'Unknown connection status';
        _isChecking = false;
      });

      if (_isConnected) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text('Camera connected via WiFi')),
        );

        // Verify the connection is fully working
        await Future.delayed(const Duration(milliseconds: 500));
        if (mounted) {
          await _verifyCameraConnection();
        }
      }
    } catch (e) {
      setState(() {
        _isConnected = false;
        _connectionStatus = 'Failed to connect: ${e.toString()}';
        _isChecking = false;
      });

      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text('Connection error: ${e.toString()}')),
      );
    }
  }

  Future<void> _checkCameraConnection() async {
    setState(() {
      _isChecking = true;
    });

    try {
      // Try USB connection first
      final result = await platform.invokeMethod('initializeCamera');

      // Handle different response types
      if (result is bool) {
        // Handle legacy boolean response
        setState(() {
          _isConnected = result;
          _connectionStatus =
              result ? 'Camera connected' : 'Camera not connected';
          _isChecking = false;
        });

        // If connected, verify the connection works
        if (result) {
          // Wait a moment before verification
          await Future.delayed(const Duration(milliseconds: 500));
          if (mounted) {
            await _verifyCameraConnection();
          }
        }
      } else if (result is Map) {
        // Handle the new map response format
        final bool connected = result['connected'] as bool? ?? false;
        final String? message = result['message'] as String?;
        final String? status = result['status'] as String?;

        setState(() {
          _isConnected = connected;
          _connectionStatus =
              message ??
              (connected ? 'Camera connected' : 'Camera not connected');

          // Only stop checking if we're fully connected or have a definitive error
          // If we're just waiting for permission, keep the checking state
          if (connected || status != 'PERMISSION_REQUESTED') {
            _isChecking = false;
          }
        });

        // If we're waiting for permission, poll for connection status after a delay
        if (status == 'PERMISSION_REQUESTED') {
          await Future.delayed(const Duration(seconds: 2));
          if (mounted) {
            _pollConnectionStatus();
          }
        } else if (connected) {
          // If connected, verify the connection works
          await Future.delayed(const Duration(milliseconds: 500));
          if (mounted) {
            await _verifyCameraConnection();
          }
        }
      } else {
        // Handle unexpected response type
        setState(() {
          _isConnected = false;
          _connectionStatus = 'Error: Unexpected response format';
          _isChecking = false;
        });
      }
    } on PlatformException catch (e) {
      setState(() {
        _isConnected = false;
        _connectionStatus = 'Error: ${e.message}';
        _isChecking = false;
      });
    } catch (e) {
      setState(() {
        _isConnected = false;
        _connectionStatus = 'Unknown error occurred';
        _isChecking = false;
      });
    }
  }

  // Poll for connection status after permission request
  Future<void> _pollConnectionStatus() async {
    try {
      final result = await platform.invokeMethod('initializeCamera');

      if (result is Map) {
        final bool connected = result['connected'] as bool? ?? false;
        final String? message = result['message'] as String?;

        setState(() {
          _isConnected = connected;
          _connectionStatus =
              message ??
              (connected ? 'Camera connected' : 'Camera not connected');
          _isChecking = false;
        });
      } else if (result is bool) {
        setState(() {
          _isConnected = result;
          _connectionStatus =
              result ? 'Camera connected' : 'Camera not connected';
          _isChecking = false;
        });
      }
    } catch (e) {
      // If polling fails, just stop checking
      setState(() {
        _isChecking = false;
      });
    }
  }

  // Check connection status without showing loading indicators
  Future<void> _checkConnectionSilently() async {
    // Don't set _isChecking to true to avoid UI changes
    bool wasConnected = _isConnected;

    try {
      // Use our verification method instead of just checking app state
      final bool nowConnected = await _verifyCameraConnection(
        showIndicators: false,
      );

      // Only update state if connection status changed
      if (wasConnected != nowConnected) {
        setState(() {
          _isConnected = nowConnected;
          _connectionStatus =
              nowConnected ? 'Camera connected' : 'Camera not connected';

          // If connection was lost, show notification
          if (wasConnected && !nowConnected) {
            _showConnectionLostNotification();
          }
        });
      }
    } catch (e) {
      // Only update if we were previously connected and now have an error
      if (wasConnected) {
        setState(() {
          _isConnected = false;
          _connectionStatus = 'Connection lost: ${e.toString()}';
        });
        _showConnectionLostNotification();
      }
    }
  }

  // Show notification when connection is lost
  void _showConnectionLostNotification() {
    if (mounted) {
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(
          content: Text('Camera connection lost!'),
          backgroundColor: Colors.red,
          duration: Duration(seconds: 3),
        ),
      );
    }
  }

  void _showWifiConnectionDialog() {
    final ipAddressController = TextEditingController();
    final portController = TextEditingController(text: '15740');

    showDialog(
      context: context,
      builder: (context) {
        return AlertDialog(
          title: const Text('Connect to WiFi Camera'),
          content: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              TextField(
                controller: ipAddressController,
                decoration: const InputDecoration(
                  labelText: 'Camera IP Address',
                  hintText: 'e.g., 192.168.1.100',
                ),
                keyboardType: TextInputType.text,
              ),
              TextField(
                controller: portController,
                decoration: const InputDecoration(
                  labelText: 'Port (default: 15740)',
                ),
                keyboardType: TextInputType.number,
              ),
            ],
          ),
          actions: [
            TextButton(
              onPressed: () {
                Navigator.of(context).pop();
              },
              child: const Text('Cancel'),
            ),
            TextButton(
              onPressed: () {
                final ipAddress = ipAddressController.text.trim();
                final port = int.tryParse(portController.text.trim()) ?? 15740;

                if (ipAddress.isNotEmpty) {
                  Navigator.of(context).pop();
                  _connectToWifiCamera(ipAddress, port: port);
                } else {
                  ScaffoldMessenger.of(context).showSnackBar(
                    const SnackBar(
                      content: Text('Please enter a valid IP address'),
                    ),
                  );
                }
              },
              child: const Text('Connect'),
            ),
          ],
        );
      },
    );
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('Camera Connection'),
        actions: [
          // Connection status indicator
          Container(
            padding: const EdgeInsets.symmetric(horizontal: 12),
            child: Row(
              children: [
                AnimatedSwitcher(
                  duration: const Duration(milliseconds: 300),
                  child: Icon(
                    _isConnected ? Icons.camera_alt : Icons.camera_alt_outlined,
                    key: ValueKey<bool>(_isConnected),
                    color: _isConnected ? Colors.green : Colors.red,
                  ),
                ),
                const SizedBox(width: 4),
                AnimatedOpacity(
                  opacity: _isChecking ? 1.0 : 0.0,
                  duration: const Duration(milliseconds: 200),
                  child: const SizedBox(
                    width: 12,
                    height: 12,
                    child: CircularProgressIndicator(strokeWidth: 2),
                  ),
                ),
              ],
            ),
          ),
        ],
      ),
      body: Center(
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            // Connection status
            Container(
              padding: const EdgeInsets.all(16),
              margin: const EdgeInsets.only(bottom: 32),
              decoration: BoxDecoration(
                color:
                    _isChecking
                        ? Colors.blue.withOpacity(0.1)
                        : (_isConnected
                            ? Colors.green.withOpacity(0.1)
                            : Colors.red.withOpacity(0.1)),
                borderRadius: BorderRadius.circular(8),
                border: Border.all(
                  color:
                      _isChecking
                          ? Colors.blue
                          : (_isConnected ? Colors.green : Colors.red),
                  width: 1,
                ),
              ),
              child: Column(
                children: [
                  Row(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      _isChecking
                          ? const SizedBox(
                            width: 20,
                            height: 20,
                            child: CircularProgressIndicator(strokeWidth: 3),
                          )
                          : Icon(
                            _isConnected ? Icons.check_circle : Icons.error,
                            color: _isConnected ? Colors.green : Colors.red,
                          ),
                      const SizedBox(width: 8),
                      Flexible(
                        child: Text(
                          _connectionStatus,
                          style: TextStyle(
                            fontSize: 16,
                            color:
                                _isChecking
                                    ? Colors.blue
                                    : (_isConnected
                                        ? Colors.green
                                        : Colors.red),
                            fontWeight: FontWeight.bold,
                          ),
                          textAlign: TextAlign.center,
                        ),
                      ),
                    ],
                  ),
                  const SizedBox(height: 8),
                  Row(
                    mainAxisAlignment: MainAxisAlignment.center,
                    children: [
                      ElevatedButton(
                        onPressed: _isChecking ? null : _checkCameraConnection,
                        child: Text(
                          _isChecking ? 'Checking...' : 'USB Connection',
                        ),
                      ),
                      const SizedBox(width: 8),
                      ElevatedButton(
                        onPressed:
                            _isChecking ? null : _showWifiConnectionDialog,
                        child: const Text('WiFi Connection'),
                      ),
                    ],
                  ),
                  const SizedBox(height: 8),
                  if (_isConnected)
                    ElevatedButton.icon(
                      icon: const Icon(Icons.sync),
                      label: const Text('Verify Connection'),
                      onPressed:
                          _isChecking ? null : () => _verifyCameraConnection(),
                      style: ElevatedButton.styleFrom(
                        backgroundColor: Colors.amber.shade100,
                        foregroundColor: Colors.amber.shade900,
                      ),
                    ),
                ],
              ),
            ),

            const SizedBox(height: 24),
            ElevatedButton.icon(
              icon: const Icon(Icons.sd_storage),
              label: const Text('Camera Storage Browser'),
              onPressed:
                  _isConnected
                      ? () {
                        Navigator.of(context).push(
                          MaterialPageRoute(
                            builder: (context) => const StorageBrowserScreen(),
                          ),
                        );
                      }
                      : null, // Disable button when camera is not connected
            ),
          ],
        ),
      ),
    );
  }
}
