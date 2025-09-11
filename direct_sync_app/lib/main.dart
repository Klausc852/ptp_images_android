import 'dart:async';
import 'package:flutter/material.dart';
import 'package:flutter_dotenv/flutter_dotenv.dart';
import 'photo_list_screen.dart';
import 'camera_connection_screen.dart';
import 's3_sync_screen.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:workmanager/workmanager.dart';
import 'photo_sync_service.dart';
import 'battery_optimization_helper.dart';

// This top-level function will be executed by the WorkManager
// when tasks are triggered
@pragma('vm:entry-point')
void callbackDispatcher() {
  Workmanager().executeTask((taskName, inputData) async {
    print('Task $taskName started with data: $inputData');

    switch (taskName) {
      case 'task-identifier':
      case 'photoChangeCheckTask':
      case 'Frequent Photo Check':
      case 'Frequent Photo Check (Recovery)':
        try {
          // Initialize photo sync service for background processing
          final photoSyncService = PhotoSyncService();
          await photoSyncService.initialize(inBackground: true);

          // Load environment
          try {
            await dotenv.load(fileName: "assets/.env.dev");
          } catch (e) {
            print('Error loading environment: $e');
            // Continue even if environment loading fails
          }

          // Check for new photos with a timeout to prevent hanging
          try {
            // Set a timeout for the operation
            bool completed = false;

            // Start a timer that will complete if the operation takes too long
            Future.delayed(const Duration(seconds: 25), () {
              if (!completed) {
                print('Background photo check timeout triggered');
                completed = true;
              }
            });

            // Attempt the actual operation
            await photoSyncService.checkForNewPhotos();

            completed = true;
            print('Background photo check completed: ${DateTime.now()}');

            // Register the next task to ensure continuity even when app is closed
            try {
              // Register a new one-off task to run in 15 minutes
              String uniqueId = 'auto-${DateTime.now().millisecondsSinceEpoch}';
              await Workmanager().registerOneOffTask(
                uniqueId,
                'photoChangeCheckTask',
                tag: 'Auto Scheduled Check',
                initialDelay: const Duration(minutes: 15),
                inputData: {'isAutoScheduled': true},
              );
              print('Registered next background check for 15 minutes later');
            } catch (e) {
              print('Failed to register next task: $e');
            }

            return true;
          } catch (e) {
            print('Error in background photo check: $e');

            // Even on error, try to schedule the next task
            try {
              String uniqueId =
                  'recovery-${DateTime.now().millisecondsSinceEpoch}';
              await Workmanager().registerOneOffTask(
                uniqueId,
                'photoChangeCheckTask',
                tag: 'Recovery Check',
                initialDelay: const Duration(minutes: 5),
                inputData: {'isRecovery': true},
              );
              print('Registered recovery check for 5 minutes later');
            } catch (e) {
              print('Failed to register recovery task: $e');
            }

            return true; // Return success to prevent retry attempts
          }
        } catch (e) {
          print('Error executing task $taskName: $e');
          return false;
        }
      default:
        print('Unknown task: $taskName');
        return false;
    }
  });
}

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();

  // Load the appropriate environment file
  // You can change this to .env.prod for production
  await dotenv.load(fileName: "assets/.env.dev");

  // Initialize Workmanager
  await Workmanager().initialize(
    callbackDispatcher,
    isInDebugMode: true, // Set to false in production
  );

  // Cancel all existing tasks to clean up any stuck in retry loops
  await Workmanager().cancelAll();
  print('Cancelled all existing background tasks');

  // Register the periodic task with more aggressive settings
  await Workmanager().registerPeriodicTask(
    'periodic-photo-check',
    'task-identifier',
    tag: 'Background Photo Check',
    initialDelay: const Duration(seconds: 10),
    inputData: {'isPeriodicCheck': true},
    frequency: const Duration(
      minutes: 15,
    ), // Run every 15 minutes instead of 100
    constraints: Constraints(
      networkType: NetworkType.connected, // Only run when network is available
      requiresBatteryNotLow: false, // Run even when battery is low
      requiresCharging: false, // Run whether charging or not
      requiresDeviceIdle: false, // Run whether device is idle or not
      requiresStorageNotLow: false, // Run even when storage is low
    ),
    existingWorkPolicy: ExistingWorkPolicy.replace,
    backoffPolicy: BackoffPolicy.linear, // Linear retry if it fails
  );
  print('Periodic task registered with 15-minute frequency');

  // Register a backup one-time task that will run after app closure
  await Workmanager().registerOneOffTask(
    'one-off-backup',
    'task-identifier',
    tag: 'Backup Check',
    initialDelay: const Duration(minutes: 5),
    inputData: {'isBackupCheck': true},
    constraints: Constraints(networkType: NetworkType.connected),
    existingWorkPolicy: ExistingWorkPolicy.replace,
  );
  print('Backup one-time task registered for 5 minutes after app closure');

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

class _HomeMenuScreenState extends State<HomeMenuScreen> {
  final String username =
      "User"; // You can replace this with actual user's name if available

  @override
  void initState() {
    super.initState();

    // Initialize the app
    _initializeApp();
  }

  // Initialize app
  Future<void> _initializeApp() async {
    // Initialize the photo sync service
    final photoSyncService = PhotoSyncService();
    await photoSyncService.initialize();

    // Request battery optimization disabling to ensure background tasks run
    // This needs to be run after a delay to ensure UI is ready
    Future.delayed(const Duration(seconds: 5), () {
      BatteryOptimizationHelper.showBatteryOptimizationDialog(context);
    });

    // Check auto-sync preference for UI state
    try {
      SharedPreferences prefs = await SharedPreferences.getInstance();
      bool autoSyncEnabled = prefs.getBool('auto_sync_enabled') ?? false;

      if (autoSyncEnabled) {
        print('Auto-sync is enabled in preferences');

        // Register a high-frequency task for immediate background checking
        await Workmanager().registerPeriodicTask(
          'high-frequency-check',
          'photoChangeCheckTask',
          tag: 'High Frequency Photo Check',
          frequency: const Duration(minutes: 15),
          inputData: {'isFrequentCheck': true, 'chainedTask': true},
          constraints: Constraints(
            networkType: NetworkType.connected,
            requiresBatteryNotLow: false,
            requiresCharging: false,
          ),
          existingWorkPolicy: ExistingWorkPolicy.keep,
        );

        // Also register a backup task with unique ID to avoid conflict
        String uniqueId = 'backup-${DateTime.now().millisecondsSinceEpoch}';
        await Workmanager().registerOneOffTask(
          uniqueId,
          'photoChangeCheckTask',
          tag: 'Backup Check',
          initialDelay: const Duration(minutes: 2),
        );

        print(
          'Registered high-frequency background photo check and backup task',
        );

        // Trigger photo check immediately within the app
        await photoSyncService.checkForNewPhotos();
      }
    } catch (e) {
      print('Error loading preferences or registering tasks: $e');
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('Direct Sync App'), elevation: 2),
      body: Container(
        decoration: BoxDecoration(
          gradient: LinearGradient(
            begin: Alignment.topCenter,
            end: Alignment.bottomCenter,
            colors: [Colors.blue.shade50, Colors.white],
          ),
        ),
        child: Center(
          child: Padding(
            padding: const EdgeInsets.all(24.0),
            child: Column(
              mainAxisAlignment: MainAxisAlignment.center,
              children: [
                // Greeting section
                Container(
                  padding: const EdgeInsets.all(24),
                  decoration: BoxDecoration(
                    color: Colors.white,
                    borderRadius: BorderRadius.circular(16),
                    boxShadow: [
                      BoxShadow(
                        color: Colors.black.withOpacity(0.1),
                        blurRadius: 10,
                        offset: const Offset(0, 4),
                      ),
                    ],
                  ),
                  child: Column(
                    children: [
                      // App icon or logo
                      Container(
                        width: 80,
                        height: 80,
                        decoration: BoxDecoration(
                          color: Colors.deepPurple.shade100,
                          shape: BoxShape.circle,
                        ),
                        child: Icon(
                          Icons.camera,
                          size: 40,
                          color: Colors.deepPurple.shade700,
                        ),
                      ),
                      const SizedBox(height: 24),
                      // Greeting text
                      Text(
                        'Welcome, $username!',
                        style: const TextStyle(
                          fontSize: 28,
                          fontWeight: FontWeight.bold,
                        ),
                      ),
                      const SizedBox(height: 8),
                      Text(
                        'What would you like to do today?',
                        style: TextStyle(
                          fontSize: 16,
                          color: Colors.grey.shade700,
                        ),
                      ),
                    ],
                  ),
                ),

                const SizedBox(height: 40),

                // Main action buttons
                Row(
                  mainAxisAlignment: MainAxisAlignment.center,
                  children: [
                    // Connect Camera Button
                    Expanded(
                      child: _buildActionButton(
                        context,
                        title: 'Connect Camera',
                        icon: Icons.camera_alt,
                        color: Colors.blue.shade700,
                        onTap: () {
                          Navigator.of(context).push(
                            MaterialPageRoute(
                              builder:
                                  (context) => const CameraConnectionScreen(),
                            ),
                          );
                        },
                      ),
                    ),
                    const SizedBox(width: 20),
                    // Sync to S3 Button
                    Expanded(
                      child: _buildActionButton(
                        context,
                        title: 'Sync to S3',
                        icon: Icons.cloud_upload,
                        color: Colors.green.shade700,
                        onTap: () {
                          // Navigate to S3 sync screen
                          Navigator.of(context).push(
                            MaterialPageRoute(
                              builder: (context) => const S3SyncScreen(),
                            ),
                          );
                        },
                      ),
                    ),
                  ],
                ),

                const SizedBox(height: 24),

                // View Photos Button
                SizedBox(
                  width: double.infinity,
                  child: ElevatedButton.icon(
                    style: ElevatedButton.styleFrom(
                      padding: const EdgeInsets.symmetric(vertical: 16),
                      shape: RoundedRectangleBorder(
                        borderRadius: BorderRadius.circular(8),
                      ),
                    ),
                    icon: const Icon(Icons.photo_library),
                    label: const Text(
                      'View Downloaded Photos',
                      style: TextStyle(fontSize: 16),
                    ),
                    onPressed: () {
                      Navigator.of(context).push(
                        MaterialPageRoute(
                          builder: (context) => const PhotoListScreen(),
                        ),
                      );
                    },
                  ),
                ),

                const SizedBox(height: 16),

                // Test WorkManager Task Button
                SizedBox(
                  width: double.infinity,
                  child: ElevatedButton.icon(
                    style: ElevatedButton.styleFrom(
                      padding: const EdgeInsets.symmetric(vertical: 16),
                      backgroundColor: Colors.amber.shade700,
                      foregroundColor: Colors.white,
                      shape: RoundedRectangleBorder(
                        borderRadius: BorderRadius.circular(8),
                      ),
                    ),
                    icon: const Icon(Icons.av_timer),
                    label: const Text(
                      'Test Background Task',
                      style: TextStyle(fontSize: 16),
                    ),
                    onPressed: () async {
                      // Register a one-time test task
                      await Workmanager().registerOneOffTask(
                        'one-time-test-${DateTime.now().millisecondsSinceEpoch}',
                        'task-identifier',
                        tag: 'Manual Test',
                        inputData: {
                          'triggered': 'manually',
                          'time': DateTime.now().toString(),
                        },
                        constraints: Constraints(
                          networkType: NetworkType.connected,
                        ),
                      );

                      ScaffoldMessenger.of(context).showSnackBar(
                        const SnackBar(
                          content: Text(
                            'Background task triggered! Check the console logs.',
                          ),
                          duration: Duration(seconds: 2),
                        ),
                      );
                    },
                  ),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }

  @override
  void dispose() {
    // Clean up any resources or listeners here
    super.dispose();
  }

  Widget _buildActionButton(
    BuildContext context, {
    required String title,
    required IconData icon,
    required Color color,
    required VoidCallback onTap,
  }) {
    return InkWell(
      onTap: onTap,
      borderRadius: BorderRadius.circular(12),
      child: Container(
        padding: const EdgeInsets.symmetric(vertical: 24, horizontal: 12),
        decoration: BoxDecoration(
          color: Colors.white,
          borderRadius: BorderRadius.circular(12),
          border: Border.all(color: color.withOpacity(0.3)),
          boxShadow: [
            BoxShadow(
              color: color.withOpacity(0.1),
              blurRadius: 8,
              offset: const Offset(0, 2),
            ),
          ],
        ),
        child: Column(
          children: [
            Container(
              padding: const EdgeInsets.all(12),
              decoration: BoxDecoration(
                color: color.withOpacity(0.1),
                shape: BoxShape.circle,
              ),
              child: Icon(icon, size: 32, color: color),
            ),
            const SizedBox(height: 12),
            Text(
              title,
              style: TextStyle(
                fontSize: 16,
                fontWeight: FontWeight.bold,
                color: color,
              ),
              textAlign: TextAlign.center,
            ),
          ],
        ),
      ),
    );
  }
}
