import 'dart:io';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

class BatteryOptimizationHelper {
  static const platform = MethodChannel(
    'com.example.direct_sync_app/battery_optimization',
  );

  // Check if the app is ignoring battery optimizations
  static Future<bool> isBatteryOptimizationDisabled() async {
    if (!Platform.isAndroid) return true; // On iOS, we don't need this

    try {
      final bool result = await platform.invokeMethod(
        'isBatteryOptimizationDisabled',
      );
      return result;
    } on PlatformException catch (e) {
      print('Failed to check battery optimization status: ${e.message}');
      return false;
    }
  }

  // Request the user to disable battery optimization
  static Future<bool> requestDisableBatteryOptimization() async {
    if (!Platform.isAndroid) return true; // On iOS, we don't need this

    try {
      final bool result = await platform.invokeMethod(
        'requestDisableBatteryOptimization',
      );
      return result;
    } on PlatformException catch (e) {
      print('Failed to request battery optimization: ${e.message}');
      return false;
    }
  }

  // Show a dialog to request battery optimization permission
  static Future<void> showBatteryOptimizationDialog(
    BuildContext context,
  ) async {
    final bool isDisabled = await isBatteryOptimizationDisabled();

    if (!isDisabled) {
      showDialog(
        context: context,
        barrierDismissible: false,
        builder:
            (context) => AlertDialog(
              title: const Text('Improve Background Syncing'),
              content: const Text(
                'For automatic photo syncing to work properly in the background, please disable battery optimization for this app.'
                '\n\nThis allows the app to check for new photos periodically even when the app is not in use.',
              ),
              actions: [
                TextButton(
                  child: const Text('Later'),
                  onPressed: () {
                    Navigator.of(context).pop();
                  },
                ),
                ElevatedButton(
                  child: const Text('Disable Battery Optimization'),
                  onPressed: () async {
                    Navigator.of(context).pop();
                    await requestDisableBatteryOptimization();
                  },
                ),
              ],
            ),
      );
    }
  }
}
