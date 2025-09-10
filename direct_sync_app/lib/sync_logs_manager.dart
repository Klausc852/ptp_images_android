import 'package:flutter/material.dart';

/// Class representing a single log entry with timestamp
class LogEntry {
  final String message;
  final DateTime timestamp;

  LogEntry(this.message, {DateTime? timestamp})
    : this.timestamp = timestamp ?? DateTime.now();
}

/// A class to manage sync logs for S3 or other sync operations
class SyncLogsManager {
  /// The list of log entries with timestamps
  List<LogEntry> logs = [];

  /// Adds a new log message
  void addLog(String message) {
    logs.add(LogEntry(message));
  }

  /// Adds multiple log messages at once (with current timestamp)
  void addLogs(List<String> messages) {
    messages.forEach((message) {
      logs.add(LogEntry(message));
    });
  }

  /// Clears all logs
  void clearLogs() {
    logs.clear();
  }

  /// Returns the current logs
  List<LogEntry> getLogs() {
    return logs;
  }

  /// Checks if there are any logs
  bool get isEmpty => logs.isEmpty;

  /// Checks if there are logs
  bool get isNotEmpty => logs.isNotEmpty;

  /// Creates a widget to display logs in a card with fixed height
  Widget buildLogsWidget() {
    if (logs.isEmpty) {
      return const SizedBox.shrink(); // Return empty widget if no logs
    }

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        const Text(
          'Sync Log:',
          style: TextStyle(fontSize: 16, fontWeight: FontWeight.bold),
        ),
        const SizedBox(height: 8),
        Container(
          height: 200, // Fixed height for logs container
          child: Card(
            elevation: 2,
            shape: RoundedRectangleBorder(
              borderRadius: BorderRadius.circular(12),
            ),
            child: ListView.builder(
              padding: const EdgeInsets.all(12),
              itemCount: logs.length,
              // Reverse the index to display newest logs at the top
              itemBuilder: (context, index) {
                final LogEntry logEntry = logs[logs.length - 1 - index];
                final String formattedTime =
                    '${logEntry.timestamp.hour.toString().padLeft(2, '0')}:'
                    '${logEntry.timestamp.minute.toString().padLeft(2, '0')}';

                return Padding(
                  padding: const EdgeInsets.symmetric(vertical: 4),
                  child: Row(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      // Display timestamp in HH:MM format from the log entry
                      Text(
                        '$formattedTime • ',
                        style: TextStyle(
                          fontWeight: FontWeight.bold,
                          fontSize: 14,
                          color: Colors.grey.shade600,
                        ),
                      ),
                      Expanded(
                        child: Text(
                          logEntry.message,
                          style: TextStyle(
                            color:
                                logEntry.message.contains('Uploaded')
                                    ? Colors.green.shade700
                                    : Colors.black87,
                          ),
                        ),
                      ),
                    ],
                  ),
                );
              },
            ),
          ),
        ),
        const SizedBox(height: 20), // Add bottom spacing
      ],
    );
  }
}
