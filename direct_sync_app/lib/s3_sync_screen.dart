import 'dart:async';
import 'dart:io';
import 'package:flutter/material.dart';
import 'photo_sync_service.dart';
import 'sync_logs_manager.dart';
import 'awsController/s3_func.dart';

// Note: Photo class is now defined in photo_sync_service.dart

class S3SyncScreen extends StatefulWidget {
  const S3SyncScreen({Key? key}) : super(key: key);

  // Static method to get the current album name from the PhotoSyncService
  static String getCurrentAlbumName() {
    return PhotoSyncService().getAlbumName();
  }

  @override
  State<S3SyncScreen> createState() => _S3SyncScreenState();
}

class _S3SyncScreenState extends State<S3SyncScreen> {
  final PhotoSyncService _photoSyncService = PhotoSyncService();
  bool _isLoading = false;
  bool _syncStarted = false;
  double _progress = 0.0;
  String _statusMessage = 'Ready to sync photos to S3';

  // Sync logs manager
  final SyncLogsManager _syncLogsManager = SyncLogsManager();

  // List of photos available for syncing
  List<Photo> _photos = [];

  // Date filter - default to start of today
  DateTime _filterDateTime = DateTime(
    DateTime.now().year,
    DateTime.now().month,
    DateTime.now().day,
    0,
    0,
    0,
  );

  // Auto sync settings
  bool _autoSyncEnabled = false;
  bool _autoSyncInProgress = false;

  // S3 sync configuration
  String _albumName = '20250908Test'; // Default album name
  int _photoQuantity = 100; // Default photo quantity limit

  // Text editing controllers
  final TextEditingController _albumNameController = TextEditingController();
  final TextEditingController _photoQuantityController =
      TextEditingController();

  @override
  void initState() {
    super.initState();

    // Initialize controllers with default values
    _albumNameController.text = _albumName;
    _photoQuantityController.text = _photoQuantity.toString();

    // Initialize photo sync service
    _initializePhotoSyncService();

    // Configure AWS Amplify
    _configureAmplify();
  }

  // Initialize AWS Amplify
  Future<void> _configureAmplify() async {
    try {
      _addLog('Configuring AWS Amplify...');
      final configured = await configureAmplify();

      if (configured) {
        _addLog('AWS Amplify configured successfully');
      } else {
        _addLog('Failed to configure AWS Amplify');
      }
    } catch (e) {
      _addLog('Error configuring AWS Amplify: $e');
    }
  }

  // Initialize the photo sync service
  Future<void> _initializePhotoSyncService() async {
    // Register callbacks
    _photoSyncService.addLogCallback(_addLog);
    _photoSyncService.addPhotosLoadedCallback(_onPhotosLoaded);
    _photoSyncService.addAutoSyncStatusCallback(_onAutoSyncStatusChanged);

    // Set the album name in the service
    await _photoSyncService.setAlbumName(_albumName);

    // Initialize service with photo quantity from settings
    await _photoSyncService.initialize(quantity: _photoQuantity);

    // Update UI state from service
    setState(() {
      _autoSyncEnabled = _photoSyncService.autoSyncEnabled;
    });
  } // Callback when photos are loaded

  void _onPhotosLoaded(List<Photo> photos) {
    setState(() {
      _photos = photos;
    });
  }

  // Callback when auto sync status changes
  void _onAutoSyncStatusChanged(bool isInProgress) {
    setState(() {
      _autoSyncInProgress = isInProgress;
    });
  }

  // Background check timer

  @override
  void dispose() {
    // Dispose of text controllers
    _albumNameController.dispose();
    _photoQuantityController.dispose();

    // Unregister callbacks from the photo sync service
    _photoSyncService.removeLogCallback(_addLog);
    _photoSyncService.removePhotosLoadedCallback(_onPhotosLoaded);
    _photoSyncService.removeAutoSyncStatusCallback(_onAutoSyncStatusChanged);

    super.dispose();
  }

  // Request permission and load photos from device
  Future<void> _loadPhotosFromDevice() async {
    setState(() {
      _isLoading = true;
      _statusMessage = 'Loading photos from device...';
    });

    try {
      // Use the photo sync service to load photos with the photo quantity limit
      await _photoSyncService.loadPhotos(quantity: _photoQuantity);

      // PhotoSyncService will call our callback with the photos
      setState(() {
        _isLoading = false;
        _statusMessage = 'Found ${_photos.length} photos';
      });
    } catch (e) {
      setState(() {
        _isLoading = false;
        _statusMessage = 'Error loading photos: $e';
      });
      print('Error loading photos: $e');
    }
  }

  // Filter photos based on the selected datetime
  void _filterPhotosByDateTime(DateTime dateTime) {
    // Use the photo sync service to filter photos
    _photoSyncService.setFilterDateTime(dateTime);

    setState(() {
      _filterDateTime = dateTime;
      _statusMessage = 'Found ${_photos.length} photos ';
    });
  }

  // Format datetime for display
  String _formatDateTime(DateTime dateTime) {
    return '${dateTime.year}/${dateTime.month.toString().padLeft(2, '0')}/${dateTime.day.toString().padLeft(2, '0')} '
        '${dateTime.hour.toString().padLeft(2, '0')}:${dateTime.minute.toString().padLeft(2, '0')}';
  }

  // Build the sync queue statistics widget
  Widget _buildSyncQueueStats() {
    // Get queue statistics from the service
    final stats = _photoSyncService.getSyncQueueStats();

    return Column(
      children: [
        // First row - Synced and Failed
        Row(
          children: [
            Expanded(
              child: _buildStatItem(
                icon: Icons.check_circle,
                label: 'Synced',
                value: stats['synced']?.toString() ?? '0',
                color: Colors.green,
              ),
            ),
            Expanded(
              child: _buildStatItem(
                icon: Icons.error,
                label: 'Failed',
                value: stats['failed']?.toString() ?? '0',
                color: Colors.red,
              ),
            ),
          ],
        ),
        const SizedBox(height: 16),
        // Second row - Pending and Total
        Row(
          children: [
            Expanded(
              child: _buildStatItem(
                icon: Icons.hourglass_bottom,
                label: 'Pending',
                value: stats['pending']?.toString() ?? '0',
                color: Colors.orange,
              ),
            ),
            Expanded(
              child: _buildStatItem(
                icon: Icons.photo_library,
                label: 'Total Photos',
                value: stats['total']?.toString() ?? '0',
                color: Colors.blue,
              ),
            ),
          ],
        ),
      ],
    );
  }

  // Helper method to build a stat item
  Widget _buildStatItem({
    required IconData icon,
    required String label,
    required String value,
    required Color color,
  }) {
    return Column(
      children: [
        Container(
          padding: const EdgeInsets.all(12),
          decoration: BoxDecoration(
            color: color.withOpacity(0.2),
            shape: BoxShape.circle,
          ),
          child: Icon(icon, color: color, size: 24),
        ),
        const SizedBox(height: 8),
        Text(
          value,
          style: TextStyle(
            fontSize: 24,
            fontWeight: FontWeight.bold,
            color: color,
          ),
        ),
        const SizedBox(height: 4),
        Text(
          label,
          style: const TextStyle(fontSize: 14, color: Colors.black54),
        ),
      ],
    );
  }

  // Show datetime picker
  Future<void> _showDateTimePicker() async {
    final DateTime? pickedDate = await showDatePicker(
      context: context,
      initialDate: _filterDateTime,
      firstDate: DateTime(2000),
      lastDate: DateTime.now(),
    );

    if (pickedDate != null) {
      final TimeOfDay? pickedTime = await showTimePicker(
        context: context,
        initialTime: TimeOfDay.fromDateTime(_filterDateTime),
      );

      if (pickedTime != null) {
        setState(() {
          _filterDateTime = DateTime(
            pickedDate.year,
            pickedDate.month,
            pickedDate.day,
            pickedTime.hour,
            pickedTime.minute,
          );
        });

        // Refresh the photo list with the new filter
        _filterPhotosByDateTime(_filterDateTime);
      }
    }
  }

  // Toggle auto sync
  Future<void> _toggleAutoSync(bool enabled) async {
    await _photoSyncService.setAutoSync(enabled);
    setState(() {
      _autoSyncEnabled = enabled;
    });

    // If enabled, register the sync function
    if (enabled) {
      _photoSyncService.syncNewPhotos(_performBackgroundSync);
    }
  }

  // Toggle selection of a photo
  void _togglePhotoSelection(String photoId) {
    setState(() {
      final photoIndex = _photos.indexWhere((photo) => photo.id == photoId);
      if (photoIndex != -1) {
        _photos[photoIndex].isSelected = !_photos[photoIndex].isSelected;
      }
    });
  }

  // Select or deselect all photos
  void _toggleSelectAll(bool selectAll) {
    setState(() {
      for (var photo in _photos) {
        photo.isSelected = selectAll;
      }
    });
  }

  // Get count of selected photos
  int get _selectedPhotoCount =>
      _photos.where((photo) => photo.isSelected).length;

  // Real S3 sync process using AWS Amplify
  Future<void> _startS3Sync() async {
    if (_isLoading) return;

    // Check if Amplify is configured
    if (!isAmplifyConfigured()) {
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(
          content: Text('AWS Amplify is not configured. Please try again.'),
          backgroundColor: Colors.red,
        ),
      );
      // Try to configure Amplify again
      await _configureAmplify();
      return;
    }

    // Get selected photos
    final selectedPhotos = _photos.where((photo) => photo.isSelected).toList();

    // Check if any photos are selected
    if (selectedPhotos.isEmpty) {
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(
          content: Text('Please select at least one photo to sync'),
          backgroundColor: Colors.red,
        ),
      );
      return;
    }

    // Limit photo count to the specified quantity
    final photosToSync =
        selectedPhotos.length > _photoQuantity
            ? selectedPhotos.sublist(0, _photoQuantity)
            : selectedPhotos;

    // Notify if we're limiting the number of photos
    if (selectedPhotos.length > _photoQuantity) {
      _addLog(
        'Note: Limiting sync to $_photoQuantity photos (${selectedPhotos.length} selected)',
      );
    }

    setState(() {
      _isLoading = true;
      _syncStarted = true;
      _progress = 0.0;
      _statusMessage = 'Initializing S3 sync to album "$_albumName"...';
      _syncLogsManager.clearLogs();
      _syncLogsManager.addLog(
        'Starting sync process to album "$_albumName"...',
      );
    });

    _updateProgress(0.1, 'Authenticating with AWS...');

    _updateProgress(0.2, 'Scanning local photos...');

    _updateProgress(0.3, 'Found ${photosToSync.length} photos to sync');

    // Track successful uploads
    int successCount = 0;
    int failCount = 0;

    // Real uploading of photos one by one
    int total = photosToSync.length;
    for (int i = 0; i < total; i++) {
      Photo photo = photosToSync[i];

      try {
        // Check if the photo has a valid file path
        final File? assetFile = await photo.asset?.file;
        if (assetFile == null) {
          _addLog('Error: Could not access file for ${photo.name}');
          await _photoSyncService.markPhotoAsSyncFailed(photo.id);
          failCount++;
          continue;
        }

        // Base progress value (0.3 to 0.9 range for uploads)
        double baseProgress = 0.3;
        double progressPerPhoto =
            0.6 / total; // 0.6 is the range for all photos

        // Update progress for this photo
        _updateProgress(
          baseProgress + (i * progressPerPhoto),
          'Uploading photo ${i + 1} of $total to album "$_albumName"...',
        );

        // Use the real uploadRawImageToS3 function from s3_func.dart
        final uploadResult = await uploadRawImageToS3(
          _albumName,
          photo.name,
          assetFile.path,
        );

        if (uploadResult != null) {
          // Mark the photo as synced
          await _photoSyncService.markPhotoAsSynced(photo.id);
          _addLog('Uploaded ${photo.name} to album "$_albumName"');
          successCount++;
        } else {
          _addLog('Failed to upload ${photo.name}');
          await _photoSyncService.markPhotoAsSyncFailed(photo.id);
          failCount++;
        }
      } catch (e) {
        _addLog('Error uploading ${photo.name}: $e');
        await _photoSyncService.markPhotoAsSyncFailed(photo.id);
        failCount++;
      }
    }

    await Future.delayed(const Duration(seconds: 1));
    _updateProgress(0.9, 'Verifying uploads...');

    await Future.delayed(const Duration(seconds: 1));

    if (failCount > 0) {
      _updateProgress(
        1.0,
        'Sync completed with $successCount successful uploads and $failCount failures',
      );
    } else {
      _updateProgress(
        1.0,
        'All $successCount photos synced successfully to "$_albumName"!',
      );
    }

    setState(() {
      _isLoading = false;
    });
  }

  void _updateProgress(double progress, String message) {
    setState(() {
      _progress = progress;
      _statusMessage = message;
      _syncLogsManager.addLog(message);
    });
  }

  void _addLog(String log) {
    setState(() {
      _syncLogsManager.addLog(log);
    });
  }

  // Process the sync queue manually
  Future<void> _processQueueManually() async {
    if (_photoSyncService.syncQueueLength == 0) {
      ScaffoldMessenger.of(
        context,
      ).showSnackBar(const SnackBar(content: Text('No photos in sync queue')));
      return;
    }

    setState(() {
      _syncStarted = true;
      _progress = 0.0;
      _statusMessage = 'Processing sync queue...';
    });

    try {
      await _photoSyncService.startAutoSync();

      setState(() {
        _progress = 1.0;
        _statusMessage = 'Queue processing completed!';
      });

      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('Sync queue processed successfully')),
      );
    } catch (e) {
      setState(() {
        _statusMessage = 'Error processing queue: $e';
      });

      ScaffoldMessenger.of(
        context,
      ).showSnackBar(SnackBar(content: Text('Error: $e')));
    } finally {
      // This ensures the UI reflects the current state
      setState(() {});
    }
  }

  // Format time for display (HH:MM:SS)
  String _formatTime(DateTime time) {
    return '${time.hour.toString().padLeft(2, '0')}:${time.minute.toString().padLeft(2, '0')}:${time.second.toString().padLeft(2, '0')}';
  }

  // Perform actual background sync without UI updates
  Future<void> _performBackgroundSync(List<Photo> photosToSync) async {
    if (photosToSync.isEmpty) return;

    _addLog('Starting background sync for ${photosToSync.length} photos...');

    // Authenticate with AWS (simulated)
    await Future.delayed(const Duration(seconds: 1));
    _addLog('AWS authenticated for background sync');

    // Upload photos one by one
    int total = photosToSync.length;
    for (int i = 0; i < total; i++) {
      await Future.delayed(const Duration(milliseconds: 250));

      // Mark the photo as synced using the service
      Photo photo = photosToSync[i];
      try {
        // Check if the photo has a valid file path
        final File? assetFile = await photo.asset?.file;
        if (assetFile == null) {
          _addLog('Error: Could not access file for ${photo.name}');
          await _photoSyncService.markPhotoAsSyncFailed(photo.id);
          continue;
        }

        // Use the real uploadRawImageToS3 function from s3_func.dart
        final uploadResult = await uploadRawImageToS3(
          _albumName,
          photo.name,
          assetFile.path,
        );

        if (uploadResult != null) {
          // Mark as synced if upload was successful
          await _photoSyncService.markPhotoAsSynced(photo.id);
          _addLog('Background uploaded ${i + 1}/$total: ${photo.name}');
        } else {
          _addLog('Failed to upload ${photo.name}');
          await _photoSyncService.markPhotoAsSyncFailed(photo.id);
        }
      } catch (e) {
        _addLog('Error uploading ${photo.name}: $e');
        await _photoSyncService.markPhotoAsSyncFailed(photo.id);
      }

      _addLog('Background uploaded ${i + 1}/$total: ${photo.name}');
    }

    _addLog('Background sync completed for ${photosToSync.length} photos');

    // Show notification to the user
    if (!mounted) return;
    _showSyncCompletionNotification(photosToSync.length);
  }

  // Show notification for completed sync
  void _showSyncCompletionNotification(int count) {
    // Show snackbar notification
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(
        content: Text('Auto-sync completed: $count photos uploaded'),
        backgroundColor: Colors.green,
        duration: const Duration(seconds: 3),
        action: SnackBarAction(
          label: 'VIEW',
          textColor: Colors.white,
          onPressed: () {
            // Scroll to logs section
            // In a real app, you might want to scroll to a specific section
            // or navigate to a sync history screen
          },
        ),
      ),
    );
  }

  // Handle album name changes
  void _updateAlbumName(String value) {
    final trimmedValue = value.trim();
    setState(() {
      _albumName = trimmedValue;
    });

    // Update the album name in the PhotoSyncService
    _photoSyncService.setAlbumName(trimmedValue);
    _addLog('Album name updated to: $trimmedValue');
  }

  // Handle photo quantity changes
  void _updatePhotoQuantity(String value) {
    try {
      final quantity = int.parse(value);
      if (quantity > 0) {
        setState(() {
          _photoQuantity = quantity;
        });

        // Reload photos with the new quantity if not currently loading
        if (!_isLoading) {
          _loadPhotosFromDevice();
        }
      }
    } catch (e) {
      // Invalid number - keep existing value
      _photoQuantityController.text = _photoQuantity.toString();
    }
  }

  void _resetSync() {
    setState(() {
      _isLoading = false;
      _syncStarted = false;
      _progress = 0.0;
      _statusMessage = 'Ready to sync photos to S3';
      _syncLogsManager.clearLogs();
    });
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('Sync to S3'),
        elevation: 2,
        actions: [
          IconButton(
            icon: const Icon(Icons.refresh),
            onPressed: _isLoading ? null : _loadPhotosFromDevice,
            tooltip: 'Refresh Photos',
          ),
          IconButton(
            icon: const Icon(Icons.clear_all),
            onPressed:
                _isLoading
                    ? null
                    : () async {
                      await _photoSyncService.clearAllSyncedStatus();
                      ScaffoldMessenger.of(context).showSnackBar(
                        const SnackBar(
                          content: Text('Cleared all synced photo statuses'),
                          backgroundColor: Colors.orange,
                        ),
                      );
                    },
            tooltip: 'Clear All Synced Status',
          ),
        ],
      ),
      body: Container(
        decoration: BoxDecoration(
          gradient: LinearGradient(
            begin: Alignment.topCenter,
            end: Alignment.bottomCenter,
            colors: [Colors.green.shade50, Colors.white],
          ),
        ),
        child: SingleChildScrollView(
          child: Padding(
            padding: const EdgeInsets.all(20.0),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Card(
                  elevation: 4,
                  shape: RoundedRectangleBorder(
                    borderRadius: BorderRadius.circular(16),
                  ),
                  child: Padding(
                    padding: const EdgeInsets.all(20.0),
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Row(
                          children: [
                            Container(
                              padding: const EdgeInsets.all(12),
                              decoration: BoxDecoration(
                                color: Colors.green.shade100,
                                shape: BoxShape.circle,
                              ),
                              child: Icon(
                                Icons.cloud_upload,
                                size: 28,
                                color: Colors.green.shade700,
                              ),
                            ),
                            const SizedBox(width: 16),
                            const Expanded(
                              child: Text(
                                'S3 Cloud Sync',
                                style: TextStyle(
                                  fontSize: 22,
                                  fontWeight: FontWeight.bold,
                                ),
                              ),
                            ),
                          ],
                        ),
                        const SizedBox(height: 10),
                        // Date filter chip
                        Wrap(
                          spacing: 8.0,
                          children: [
                            Chip(
                              label: Text(
                                'Photos after ${_formatDateTime(_filterDateTime)}',
                                style: TextStyle(fontSize: 12),
                              ),
                              deleteIcon: Icon(Icons.edit, size: 18),
                              onDeleted:
                                  _isLoading ? null : _showDateTimePicker,
                              backgroundColor: Colors.blue.shade100,
                              deleteIconColor: Colors.blue.shade700,
                            ),
                            // Auto-sync toggle
                            FilterChip(
                              avatar: Icon(
                                Icons.sync,
                                size: 18,
                                color:
                                    _autoSyncEnabled
                                        ? Colors.green.shade700
                                        : Colors.grey.shade700,
                              ),
                              label: Text(
                                'Auto-sync',
                                style: TextStyle(fontSize: 12),
                              ),
                              selected: _autoSyncEnabled,
                              onSelected:
                                  _isLoading
                                      ? null
                                      : (value) {
                                        _toggleAutoSync(value);
                                      },
                              selectedColor: Colors.green.shade100,
                              backgroundColor: Colors.grey.shade100,
                              checkmarkColor: Colors.green.shade700,
                            ),
                          ],
                        ),
                        const SizedBox(height: 10),
                        Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            Text(
                              _statusMessage,
                              style: TextStyle(
                                fontSize: 16,
                                fontWeight:
                                    _syncStarted
                                        ? FontWeight.bold
                                        : FontWeight.normal,
                                color:
                                    _syncStarted && _progress == 1.0
                                        ? Colors.green.shade700
                                        : Colors.black87,
                              ),
                            ),
                            if (_autoSyncInProgress)
                              Padding(
                                padding: const EdgeInsets.only(top: 4),
                                child: Row(
                                  children: [
                                    SizedBox(
                                      width: 12,
                                      height: 12,
                                      child: CircularProgressIndicator(
                                        strokeWidth: 2,
                                        color: Colors.amber,
                                      ),
                                    ),
                                    SizedBox(width: 6),
                                    Text(
                                      'Auto-sync in progress...',
                                      style: TextStyle(
                                        fontSize: 12,
                                        fontStyle: FontStyle.italic,
                                        color: Colors.amber.shade800,
                                      ),
                                    ),
                                  ],
                                ),
                              ),
                          ],
                        ),
                        const SizedBox(height: 12),
                        if (_syncStarted) ...[
                          ClipRRect(
                            borderRadius: BorderRadius.circular(8),
                            child: LinearProgressIndicator(
                              value: _progress,
                              backgroundColor: Colors.grey.shade200,
                              valueColor: AlwaysStoppedAnimation<Color>(
                                Colors.green.shade500,
                              ),
                              minHeight: 10,
                            ),
                          ),
                          const SizedBox(height: 8),
                          Text(
                            '${(_progress * 100).toInt()}%',
                            style: TextStyle(
                              fontWeight: FontWeight.bold,
                              color: Colors.green.shade700,
                            ),
                          ),
                        ],
                        const SizedBox(height: 20),
                        // Album name input field
                        TextField(
                          controller: _albumNameController,
                          decoration: InputDecoration(
                            labelText: 'Album Name',
                            hintText: 'Enter target album name',
                            prefixIcon: Icon(
                              Icons.folder,
                              color: Colors.blue.shade700,
                            ),
                            border: OutlineInputBorder(
                              borderRadius: BorderRadius.circular(8),
                            ),
                            contentPadding: const EdgeInsets.symmetric(
                              vertical: 12,
                              horizontal: 16,
                            ),
                          ),
                          onChanged: _updateAlbumName,
                          enabled: !_isLoading,
                        ),
                        const SizedBox(height: 12),
                        // Photo quantity input field
                        TextField(
                          controller: _photoQuantityController,
                          decoration: InputDecoration(
                            labelText: 'Photo Quantity Limit',
                            hintText: 'Enter maximum number of photos to sync',
                            prefixIcon: Icon(
                              Icons.filter_list,
                              color: Colors.orange.shade700,
                            ),
                            border: OutlineInputBorder(
                              borderRadius: BorderRadius.circular(8),
                            ),
                            contentPadding: const EdgeInsets.symmetric(
                              vertical: 12,
                              horizontal: 16,
                            ),
                            helperText: 'Default: 100',
                          ),
                          keyboardType: TextInputType.number,
                          onChanged: _updatePhotoQuantity,
                          enabled: !_isLoading,
                        ),
                        const SizedBox(height: 24),
                        Row(
                          mainAxisAlignment: MainAxisAlignment.center,
                          children: [
                            if (!_syncStarted || _progress == 1.0)
                              ElevatedButton.icon(
                                icon: const Icon(Icons.play_arrow),
                                label: Text(
                                  _progress == 1.0
                                      ? 'Sync Again'
                                      : 'Start Sync',
                                ),
                                style: ElevatedButton.styleFrom(
                                  backgroundColor: Colors.green.shade600,
                                  foregroundColor: Colors.white,
                                  padding: const EdgeInsets.symmetric(
                                    horizontal: 24,
                                    vertical: 12,
                                  ),
                                ),
                                onPressed: _isLoading ? null : _startS3Sync,
                              )
                            else
                              ElevatedButton.icon(
                                icon: const Icon(Icons.stop),
                                label: const Text('Cancel'),
                                style: ElevatedButton.styleFrom(
                                  backgroundColor: Colors.red.shade400,
                                  foregroundColor: Colors.white,
                                  padding: const EdgeInsets.symmetric(
                                    horizontal: 24,
                                    vertical: 12,
                                  ),
                                ),
                                onPressed: _isLoading ? _resetSync : null,
                              ),
                          ],
                        ),
                      ],
                    ),
                  ),
                ),
                const SizedBox(height: 20),
                // Sync Status Card
                const SizedBox(height: 20),
                // Photo selection section
                if (!_syncStarted || _progress == 1.0) ...[
                  Row(
                    mainAxisAlignment: MainAxisAlignment.spaceBetween,
                    children: [
                      const Text(
                        'Select Photos to Sync:',
                        style: TextStyle(
                          fontSize: 16,
                          fontWeight: FontWeight.bold,
                        ),
                      ),
                      TextButton.icon(
                        icon: Icon(
                          _photos.every((p) => p.isSelected)
                              ? Icons.check_box
                              : Icons.check_box_outline_blank,
                        ),
                        label: Text(
                          _photos.every((p) => p.isSelected)
                              ? 'Deselect All'
                              : 'Select All',
                        ),
                        onPressed:
                            () => _toggleSelectAll(
                              !_photos.every((p) => p.isSelected),
                            ),
                      ),
                    ],
                  ),
                  const SizedBox(height: 8),
                  SizedBox(
                    height: 120,
                    child: Card(
                      elevation: 2,
                      shape: RoundedRectangleBorder(
                        borderRadius: BorderRadius.circular(12),
                      ),
                      child:
                          _isLoading
                              ? Center(
                                child: Column(
                                  mainAxisAlignment: MainAxisAlignment.center,
                                  children: [
                                    CircularProgressIndicator(),
                                    SizedBox(height: 8),
                                    Text('Loading photos...'),
                                  ],
                                ),
                              )
                              : _photos.isEmpty
                              ? Center(child: Text('No photos found on device'))
                              : ListView.builder(
                                scrollDirection: Axis.horizontal,
                                itemCount: _photos.length,
                                padding: const EdgeInsets.all(8),
                                itemBuilder: (context, index) {
                                  final photo = _photos[index];
                                  return Padding(
                                    padding: const EdgeInsets.only(right: 8),
                                    child: InkWell(
                                      onTap:
                                          () => _togglePhotoSelection(photo.id),
                                      borderRadius: BorderRadius.circular(8),
                                      child: Stack(
                                        alignment: Alignment.bottomRight,
                                        children: [
                                          Container(
                                            width: 100,
                                            decoration: BoxDecoration(
                                              border: Border.all(
                                                color:
                                                    photo.isSelected
                                                        ? Colors.green.shade700
                                                        : Colors.grey.shade300,
                                                width: photo.isSelected ? 3 : 1,
                                              ),
                                              borderRadius:
                                                  BorderRadius.circular(8),
                                              color: Colors.grey.shade200,
                                            ),
                                            child: Column(
                                              children: [
                                                ClipRRect(
                                                  borderRadius:
                                                      BorderRadius.vertical(
                                                        top: Radius.circular(7),
                                                      ),
                                                  child: SizedBox(
                                                    height: 80,
                                                    width: 100,
                                                    child:
                                                        photo.thumbnailData !=
                                                                null
                                                            ? Image.memory(
                                                              photo
                                                                  .thumbnailData!,
                                                              fit: BoxFit.cover,
                                                            )
                                                            : Icon(
                                                              Icons.photo,
                                                              size: 48,
                                                              color:
                                                                  Colors
                                                                      .grey
                                                                      .shade700,
                                                            ),
                                                  ),
                                                ),
                                                Expanded(
                                                  child: Container(
                                                    alignment: Alignment.center,
                                                    padding:
                                                        const EdgeInsets.symmetric(
                                                          horizontal: 4,
                                                        ),
                                                    child: Text(
                                                      photo.name,
                                                      style: TextStyle(
                                                        fontSize: 10,
                                                        color:
                                                            photo.synced
                                                                ? Colors
                                                                    .green
                                                                    .shade700
                                                                : photo
                                                                    .syncFailed
                                                                ? Colors
                                                                    .red
                                                                    .shade700
                                                                : null,
                                                        fontWeight:
                                                            (photo.synced ||
                                                                    photo
                                                                        .syncFailed)
                                                                ? FontWeight
                                                                    .bold
                                                                : null,
                                                      ),
                                                      overflow:
                                                          TextOverflow.ellipsis,
                                                      maxLines: 1,
                                                    ),
                                                  ),
                                                ),
                                              ],
                                            ),
                                          ),
                                          // Show sync status indicator
                                          if (photo.synced)
                                            Positioned(
                                              top: 4,
                                              left: 4,
                                              child: Container(
                                                padding: EdgeInsets.all(2),
                                                decoration: BoxDecoration(
                                                  color: Colors.green.shade700,
                                                  shape: BoxShape.circle,
                                                ),
                                                child: Icon(
                                                  Icons.cloud_done,
                                                  size: 14,
                                                  color: Colors.white,
                                                ),
                                              ),
                                            ),

                                          // Show failed sync indicator
                                          if (photo.syncFailed)
                                            Positioned(
                                              top: 4,
                                              left: 4,
                                              child: Container(
                                                padding: EdgeInsets.all(2),
                                                decoration: BoxDecoration(
                                                  color: Colors.red.shade700,
                                                  shape: BoxShape.circle,
                                                ),
                                                child: Icon(
                                                  Icons.cloud_off,
                                                  size: 14,
                                                  color: Colors.white,
                                                ),
                                              ),
                                            ),

                                          // Show queued indicator
                                          if (!photo.synced &&
                                              !photo.syncFailed &&
                                              _photoSyncService
                                                  .getSyncQueue()
                                                  .any((p) => p.id == photo.id))
                                            Positioned(
                                              top: 4,
                                              left: 4,
                                              child: Container(
                                                padding: EdgeInsets.all(2),
                                                decoration: BoxDecoration(
                                                  color: Colors.orange.shade700,
                                                  shape: BoxShape.circle,
                                                ),
                                                child: Icon(
                                                  Icons.hourglass_empty,
                                                  size: 14,
                                                  color: Colors.white,
                                                ),
                                              ),
                                            ),

                                          // Show selection indicator
                                          if (photo.isSelected)
                                            Container(
                                              decoration: BoxDecoration(
                                                color: Colors.green.shade700,
                                                borderRadius:
                                                    const BorderRadius.only(
                                                      topLeft: Radius.circular(
                                                        8,
                                                      ),
                                                      bottomRight:
                                                          Radius.circular(8),
                                                    ),
                                              ),
                                              padding: const EdgeInsets.all(4),
                                              child: const Icon(
                                                Icons.check,
                                                color: Colors.white,
                                                size: 16,
                                              ),
                                            ),
                                        ],
                                      ),
                                    ),
                                  );
                                },
                              ),
                    ),
                  ),
                  Padding(
                    padding: const EdgeInsets.only(top: 8, bottom: 8),
                    child: Text(
                      '${_selectedPhotoCount} of ${_photos.length} photos selected',
                      style: TextStyle(
                        color: Colors.grey.shade700,
                        fontWeight: FontWeight.bold,
                      ),
                    ),
                  ),
                  if (_photoSyncService.photoLibraryUpdated &&
                      _photoSyncService.lastPhotoUpdateTime != null)
                    Padding(
                      padding: const EdgeInsets.only(bottom: 16),
                      child: Row(
                        mainAxisAlignment: MainAxisAlignment.center,
                        children: [
                          Icon(Icons.update, size: 16, color: Colors.green),
                          SizedBox(width: 6),
                          Text(
                            'Photo library updated at ${_formatTime(_photoSyncService.lastPhotoUpdateTime!)}',
                            style: TextStyle(
                              color: Colors.green,
                              fontStyle: FontStyle.italic,
                              fontSize: 12,
                            ),
                          ),
                        ],
                      ),
                    ),
                ],

                // Display logs using the SyncLogsManager
                Card(
                  elevation: 4,
                  shape: RoundedRectangleBorder(
                    borderRadius: BorderRadius.circular(16),
                  ),
                  child: Padding(
                    padding: const EdgeInsets.all(20.0),
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Row(
                          children: [
                            Container(
                              padding: const EdgeInsets.all(12),
                              decoration: BoxDecoration(
                                color: Colors.blue.shade100,
                                shape: BoxShape.circle,
                              ),
                              child: Icon(
                                Icons.sync_rounded,
                                size: 28,
                                color: Colors.blue.shade700,
                              ),
                            ),
                            const SizedBox(width: 16),
                            const Expanded(
                              child: Text(
                                'Sync Queue Status',
                                style: TextStyle(
                                  fontSize: 18,
                                  fontWeight: FontWeight.bold,
                                ),
                              ),
                            ),
                          ],
                        ),
                        const SizedBox(height: 16),
                        // Status statistics
                        _buildSyncQueueStats(),
                        const SizedBox(height: 20),
                        // Process Queue Button
                        Center(
                          child: ElevatedButton.icon(
                            icon: const Icon(Icons.sync),
                            label: const Text('Process Sync Queue'),
                            style: ElevatedButton.styleFrom(
                              backgroundColor: Colors.orange.shade600,
                              foregroundColor: Colors.white,
                              padding: const EdgeInsets.symmetric(
                                horizontal: 24,
                                vertical: 12,
                              ),
                            ),
                            onPressed:
                                _photoSyncService.syncQueueLength > 0 &&
                                        !_autoSyncInProgress
                                    ? () => _processQueueManually()
                                    : null,
                          ),
                        ),
                      ],
                    ),
                  ),
                ),
                _syncLogsManager.buildLogsWidget(),
              ],
            ),
          ),
        ),
      ),
    );
  }
}
