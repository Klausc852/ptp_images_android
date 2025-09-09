import 'dart:io';
import 'dart:typed_data';
import 'dart:async';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:path_provider/path_provider.dart';
import 'photo_list_screen.dart';
import 'storage_browser_screen.dart';

// Define the method channel for native communication
const platform = MethodChannel('com.example.direct_sync_app/camera');

void main() {
  runApp(const MyApp());
}

class MyApp extends StatelessWidget {
  const MyApp({Key? key}) : super(key: key);

  // This widget is the root of your application.
  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'Direct Sync App',
      theme: ThemeData(
        colorScheme: ColorScheme.fromSeed(seedColor: Colors.deepPurple),
        useMaterial3: true,
      ),
      home: const HomeMenuScreen(),
    );
  }
}

class HomeMenuScreen extends StatefulWidget {
  const HomeMenuScreen({Key? key}) : super(key: key);

  @override
  State<HomeMenuScreen> createState() => _HomeMenuScreenState();
}

class _HomeMenuScreenState extends State<HomeMenuScreen>
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

  Future<void> _checkCameraConnection() async {
    setState(() {
      _isChecking = true;
    });

    try {
      // The result could now be a boolean or a Map based on our native code changes
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
      final result = await platform.invokeMethod('initializeCamera');
      bool nowConnected = false;
      String statusMessage = '';

      if (result is bool) {
        nowConnected = result;
        statusMessage = result ? 'Camera connected' : 'Camera not connected';
      } else if (result is Map) {
        nowConnected = result['connected'] as bool? ?? false;
        statusMessage =
            result['message'] as String? ??
            (nowConnected ? 'Camera connected' : 'Camera not connected');
      }

      // Only update state if connection status changed
      if (wasConnected != nowConnected) {
        setState(() {
          _isConnected = nowConnected;
          _connectionStatus = statusMessage;

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

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('Direct Sync App'),
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
                  ElevatedButton(
                    onPressed: _isChecking ? null : _checkCameraConnection,
                    child: Text(
                      _isChecking ? 'Checking...' : 'Refresh Connection',
                    ),
                  ),
                ],
              ),
            ),
            ElevatedButton.icon(
              icon: const Icon(Icons.photo_library),
              label: const Text('Downloaded Photos'),
              onPressed: () {
                Navigator.of(context).push(
                  MaterialPageRoute(
                    builder: (context) => const PhotoListScreen(),
                  ),
                );
              },
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
