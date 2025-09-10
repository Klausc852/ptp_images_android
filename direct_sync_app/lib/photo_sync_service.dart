import 'dart:async';
import 'dart:io';
import 'dart:typed_data';
import 'package:direct_sync_app/awsController/s3_func.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:photo_manager/photo_manager.dart';
import 'package:shared_preferences/shared_preferences.dart';

// Photo model to track selection state
class Photo {
  final String id;
  final String name;
  final String path;
  final AssetEntity? asset; // Reference to the actual photo asset
  Uint8List? thumbnailData; // Thumbnail data
  bool isSelected;
  bool synced; // Track if the photo has been synced
  bool syncFailed; // Track if sync failed for this photo

  Photo({
    required this.id,
    required this.name,
    required this.path,
    this.asset,
    this.thumbnailData,
    this.isSelected = false,
    this.synced = false, // Default to not synced
    this.syncFailed = false, // Default to not failed
  });
}

final int syncDurationSeconds = 10; // Default sync duration in seconds
// Callback typedefs
typedef PhotoSyncCallback = void Function(String message);
typedef PhotosLoadedCallback = void Function(List<Photo> photos);
typedef AutoSyncStatusCallback = void Function(bool isInProgress);

class PhotoSyncService {
  // Singleton instance
  static final PhotoSyncService _instance = PhotoSyncService._internal();

  // Factory constructor
  factory PhotoSyncService() => _instance;

  // Internal constructor
  PhotoSyncService._internal();

  // Album name for S3 uploads
  String _albumName =
      'default-album'; // Default value, will be updated by S3SyncScreen

  // Setter and getter for album name
  Future<void> setAlbumName(String albumName) async {
    _albumName = albumName;

    // Store the album name in SharedPreferences for background tasks
    final prefs = await SharedPreferences.getInstance();
    await prefs.setString('album_name', albumName);
  }

  String getAlbumName() {
    return _albumName;
  } // Service state

  bool _isInitialized = false;
  bool _isLoading = false;
  bool _autoSyncEnabled = false;
  bool _autoSyncInProgress = false;
  DateTime? _lastPhotoUpdateTime;
  bool _photoLibraryUpdated = false;
  bool _listeningToPhotoChanges = false;

  // For photo library change notification
  ValueNotifier<bool>? _notifier;

  // Background check timer
  Stream<void>? _periodicCheckStream;
  StreamSubscription<void>? _periodicCheckSubscription;

  // Data storage
  List<Photo> _allPhotos = [];
  List<Photo> _filteredPhotos = [];
  List<Photo> _newPhotosQueue = []; // Queue of newly found photos pending sync
  DateTime _filterDateTime = DateTime.now();

  // Callback handlers
  final List<PhotoSyncCallback> _logCallbacks = [];
  final List<PhotosLoadedCallback> _photosLoadedCallbacks = [];
  final List<AutoSyncStatusCallback> _autoSyncStatusCallbacks = [];

  // Register callbacks
  void addLogCallback(PhotoSyncCallback callback) {
    _logCallbacks.add(callback);
  }

  void removeLogCallback(PhotoSyncCallback callback) {
    _logCallbacks.remove(callback);
  }

  void addPhotosLoadedCallback(PhotosLoadedCallback callback) {
    _photosLoadedCallbacks.add(callback);
  }

  void removePhotosLoadedCallback(PhotosLoadedCallback callback) {
    _photosLoadedCallbacks.remove(callback);
  }

  void addAutoSyncStatusCallback(AutoSyncStatusCallback callback) {
    _autoSyncStatusCallbacks.add(callback);
  }

  void removeAutoSyncStatusCallback(AutoSyncStatusCallback callback) {
    _autoSyncStatusCallbacks.remove(callback);
  }

  // Notify callbacks
  void _notifyLogCallbacks(String message) {
    for (var callback in _logCallbacks) {
      callback(message);
    }
  }

  void _notifyPhotosLoadedCallbacks() {
    for (var callback in _photosLoadedCallbacks) {
      callback(_filteredPhotos);
    }
  }

  void _notifyAutoSyncStatus(bool isInProgress) {
    for (var callback in _autoSyncStatusCallbacks) {
      callback(isInProgress);
    }
  }

  // Getters
  List<Photo> get photos => _filteredPhotos;
  List<Photo> get newPhotosQueue => _newPhotosQueue;
  bool get isLoading => _isLoading;
  bool get autoSyncEnabled => _autoSyncEnabled;
  bool get autoSyncInProgress => _autoSyncInProgress;
  DateTime get filterDateTime => _filterDateTime;
  bool get photoLibraryUpdated => _photoLibraryUpdated;
  DateTime? get lastPhotoUpdateTime => _lastPhotoUpdateTime;

  // Store for synced photo IDs
  Set<String> _syncedPhotoIds = {};

  // Mark a photo as synced
  Future<void> markPhotoAsSynced(String photoId) async {
    // Add the photo ID to the synced set
    _syncedPhotoIds.add(photoId);

    // Update the synced status in the current photo lists
    for (var photo in _allPhotos) {
      if (photo.id == photoId) {
        photo.synced = true;
        photo.syncFailed = false; // Reset failed status when sync succeeds
      }
    }

    for (var photo in _filteredPhotos) {
      if (photo.id == photoId) {
        photo.synced = true;
        photo.syncFailed = false; // Reset failed status when sync succeeds
      }
    }

    // Remove from new photos queue if present
    _newPhotosQueue.removeWhere((photo) => photo.id == photoId);

    // Save the updated synced photo IDs
    await _saveSyncedPhotoIds();

    // Notify listeners that the photo list has changed
    _notifyPhotosLoadedCallbacks();
  }

  // Mark a photo as failed sync
  Future<void> markPhotoAsSyncFailed(String photoId) async {
    // Update the sync failed status in the current photo lists
    for (var photo in _allPhotos) {
      if (photo.id == photoId) {
        photo.syncFailed = true;
        photo.synced = false; // Not synced if it failed
      }
    }

    for (var photo in _filteredPhotos) {
      if (photo.id == photoId) {
        photo.syncFailed = true;
        photo.synced = false; // Not synced if it failed
      }
    }

    // Leave in new photos queue if failed - we'll try again later

    // Notify listeners that the photo list has changed
    _notifyPhotosLoadedCallbacks();
  }

  // Add a photo to the sync queue
  void addPhotoToSyncQueue(Photo photo) {
    if (!_newPhotosQueue.any((p) => p.id == photo.id)) {
      _newPhotosQueue.add(photo);
      _notifyLogCallbacks('Added ${photo.name} to sync queue');
      _notifyPhotosLoadedCallbacks();
    }
  }

  // Add multiple photos to the sync queue
  void addPhotosToSyncQueue(List<Photo> photos) {
    int addedCount = 0;

    for (var photo in photos) {
      if (!_newPhotosQueue.any((p) => p.id == photo.id)) {
        _newPhotosQueue.add(photo);
        addedCount++;
      }
    }

    if (addedCount > 0) {
      _notifyLogCallbacks('Added $addedCount photos to sync queue');
      _notifyPhotosLoadedCallbacks();
    }
  }

  // Clear the sync queue
  void clearSyncQueue() {
    if (_newPhotosQueue.isNotEmpty) {
      _newPhotosQueue.clear();
      _notifyLogCallbacks('Cleared sync queue');
      _notifyPhotosLoadedCallbacks();
    }
  }

  // Get the current number of photos in the sync queue
  int get syncQueueLength => _newPhotosQueue.length;

  // Get a copy of the queue for UI display
  List<Photo> getSyncQueue() {
    return List.from(_newPhotosQueue);
  }

  // Get the sync queue status information
  Map<String, int> getSyncQueueStats() {
    int pending = _newPhotosQueue.length;
    int synced = _filteredPhotos.where((p) => p.synced).length;
    int failed = _filteredPhotos.where((p) => p.syncFailed).length;

    return {
      'pending': pending,
      'synced': synced,
      'failed': failed,
      'total': _filteredPhotos.length,
    };
  }

  // Clear all synced status (for testing)
  Future<void> clearAllSyncedStatus() async {
    _syncedPhotoIds.clear();

    // Update all photos in memory
    for (var photo in _allPhotos) {
      photo.synced = false;
    }

    for (var photo in _filteredPhotos) {
      photo.synced = false;
    }

    // Save the empty set
    await _saveSyncedPhotoIds();

    // Notify listeners
    _notifyPhotosLoadedCallbacks();
  }

  // Initialize the service
  Future<void> initialize({
    int quantity = 100,
    bool inBackground = false,
  }) async {
    // We can reinitialize if in background mode
    if (_isInitialized && !inBackground) return;

    // Request permission (might not work in background, but we'll try)
    await _requestPermission();

    // Load synced photo IDs from storage
    await _loadSyncedPhotoIds();

    // Check if there are stored sync preferences
    final prefs = await SharedPreferences.getInstance();
    _autoSyncEnabled = prefs.getBool('auto_sync_enabled') ?? false;

    // Retrieve stored album name if available
    String? storedAlbumName = prefs.getString('album_name');
    if (storedAlbumName != null && storedAlbumName.isNotEmpty) {
      _albumName = storedAlbumName;
    }

    // Only set up listeners if not in background mode
    if (!inBackground) {
      // Setup photo change listener
      await _setupPhotoChangeListener();

      // Setup periodic check
      _setupPeriodicPhotoCheck();
    }

    _isInitialized = true;

    // Load initial photos with specified quantity
    await loadPhotos(quantity: quantity);
  }

  // Load synced photo IDs from preferences
  Future<void> _loadSyncedPhotoIds() async {
    try {
      final prefs = await SharedPreferences.getInstance();
      final syncedIds = prefs.getStringList('syncedPhotoIds') ?? [];
      _syncedPhotoIds = syncedIds.toSet();
      print('Loaded ${_syncedPhotoIds.length} synced photo IDs');
    } catch (e) {
      print('Error loading synced photo IDs: $e');
    }
  }

  // Save synced photo IDs to preferences
  Future<void> _saveSyncedPhotoIds() async {
    try {
      final prefs = await SharedPreferences.getInstance();
      await prefs.setStringList('syncedPhotoIds', _syncedPhotoIds.toList());
      print('Saved ${_syncedPhotoIds.length} synced photo IDs');
    } catch (e) {
      print('Error saving synced photo IDs: $e');
    }
  }

  // Request permission
  Future<bool> _requestPermission() async {
    final permission = await PhotoManager.requestPermissionExtend();
    return permission.isAuth;
  }

  // Set the filter date time
  void setFilterDateTime(DateTime dateTime) {
    _filterDateTime = dateTime;
    _filterPhotosByDateTime();
    _notifyPhotosLoadedCallbacks();
  }

  // Toggle auto sync
  Future<void> setAutoSync(bool enabled) async {
    _autoSyncEnabled = enabled;

    // Store the auto-sync preference in SharedPreferences for background tasks
    final prefs = await SharedPreferences.getInstance();
    await prefs.setBool('auto_sync_enabled', enabled);

    // Notify through logs
    _notifyLogCallbacks(
      enabled
          ? 'Auto-sync enabled - new photos will sync automatically'
          : 'Auto-sync disabled',
    );

    // We still keep the in-app periodic check for UI responsiveness
    if (enabled && _periodicCheckSubscription == null) {
      _setupPeriodicPhotoCheck();
    } else if (!enabled && _periodicCheckSubscription != null) {
      _periodicCheckSubscription?.cancel();
      _periodicCheckSubscription = null;
    }
  }

  // Set up photo library change listener
  Future<void> _setupPhotoChangeListener() async {
    if (_listeningToPhotoChanges) return;

    // The notifier will be triggered whenever the photo library changes
    _notifier = ValueNotifier<bool>(false);

    _notifier?.addListener(() {
      print('Photo library change detected! Reloading photos...');
      // Reload photos when library changes
      loadPhotos();
    });

    // Register the change notifier with PhotoManager
    PhotoManager.addChangeCallback(_photoChangeCallback);

    // Start listening to changes
    await PhotoManager.startChangeNotify();
    _listeningToPhotoChanges = true;

    print('Photo library change listener set up');
  }

  // Callback for photo library changes
  void _photoChangeCallback(MethodCall call) {
    print('Photo library callback triggered: ${call.method}');

    // Check if this is a change notification event
    if (call.method == 'onChange' || call.method == 'notify') {
      _photoLibraryUpdated = true;
      _lastPhotoUpdateTime = DateTime.now();

      if (_notifier != null) {
        // Toggle the value to trigger the listener
        _notifier!.value = !_notifier!.value;
      }

      // If auto-sync is enabled, start syncing new photos
      if (_autoSyncEnabled && !_autoSyncInProgress) {
        startAutoSync();
      }
    }
  }

  // Set up periodic check for new photos
  void _setupPeriodicPhotoCheck() {
    _periodicCheckStream = Stream.periodic(
      Duration(seconds: syncDurationSeconds),
    );
    _periodicCheckSubscription = _periodicCheckStream?.listen((_) {
      // if (_autoSyncEnabled && !_autoSyncInProgress && !_isLoading) {
      print('Periodic check: looking for new photos');
      checkForNewPhotos(); // Use default quantity
      // }
    });
  }

  // Check for new photos without triggering UI updates
  Future<void> checkForNewPhotos({int? quantity}) async {
    _notifyLogCallbacks('Checking for new photos');
    if (_autoSyncInProgress || _isLoading) {
      print('Skipping check - sync in progress or loading');
      return;
    }

    try {
      // Get current count before loading
      final prevCount = _filteredPhotos.length;
      print('Checking for new photos - current count: $prevCount');

      // Store the IDs of existing photos to identify new ones
      final existingPhotoIds = _filteredPhotos.map((p) => p.id).toSet();

      // Load latest photos silently using the specified quantity or default
      await loadPhotosQuietly(quantity: quantity ?? 100);

      // Compare after loading
      final newCount = _filteredPhotos.length;
      print('After check - updated count: $newCount');

      // Find newly added photos
      final newPhotos =
          _filteredPhotos
              .where((p) => !existingPhotoIds.contains(p.id))
              .toList();
      final newPhotoCount = newPhotos.length;

      if (newPhotoCount > 0) {
        _notifyLogCallbacks('Periodic check: found $newPhotoCount new photos');

        // Debug info about new photos
        for (var photo in newPhotos) {
          print('New photo found: ${photo.name}');

          // Add to new photos queue if not already synced
          if (!photo.synced && !_newPhotosQueue.any((p) => p.id == photo.id)) {
            _newPhotosQueue.add(photo);
            print('Added ${photo.name} to sync queue');
          }
        }

        // Notify listeners about queue update
        _notifyPhotosLoadedCallbacks();

        if (_autoSyncEnabled) {
          startAutoSync();
        }
      } else {
        print('No new photos found in check');
      }
    } catch (e) {
      print('Error in periodic photo check: $e');
    }
  }

  // Load photos from device
  Future<void> loadPhotos({int quantity = 100}) async {
    if (_isLoading) return;

    _isLoading = true;
    print('Loading photos with quantity limit: $quantity');

    try {
      // Request permission
      final permission = await PhotoManager.requestPermissionExtend();
      if (!permission.isAuth) {
        _isLoading = false;
        print('Photo permission denied');
        return;
      }

      // Get all albums
      final List<AssetPathEntity> albums = await PhotoManager.getAssetPathList(
        onlyAll: true,
        type: RequestType.image,
      );

      if (albums.isEmpty) {
        _isLoading = false;
        print('No albums found');
        return;
      }

      // Get the recent album
      final recentAlbum = albums[0];

      // Get asset list (photos)
      final List<AssetEntity> assets = await recentAlbum.getAssetListRange(
        start: 0,
        end: quantity, // Use the specified quantity parameter
      );

      print('Loaded ${assets.length} assets from device');

      // Convert to our Photo model
      final List<Photo> loadedPhotos = [];
      for (var asset in assets) {
        final String fileName = asset.title ?? 'Unknown';
        // Check if this photo was previously synced
        final bool wasSynced = _syncedPhotoIds.contains(asset.id);

        loadedPhotos.add(
          Photo(
            id: asset.id,
            name: fileName,
            path: asset.relativePath ?? '',
            asset: asset,
            synced: wasSynced,
          ),
        );
      }

      // Load thumbnails for the photos
      for (var photo in loadedPhotos) {
        try {
          final Uint8List? thumbnail = await photo.asset?.thumbnailData;
          if (thumbnail != null) {
            photo.thumbnailData = thumbnail;
          }
        } catch (e) {
          print('Error loading thumbnail for ${photo.name}: $e');
        }
      }

      // Store all photos
      _allPhotos = loadedPhotos;
      print('Updated _allPhotos with ${_allPhotos.length} photos');

      // Apply date filter - this will also notify listeners
      _filterPhotosByDateTime();

      // Reset photo library update flag after refreshing
      if (_photoLibraryUpdated && _lastPhotoUpdateTime != null) {
        // Keep the flag true but just note that we've refreshed
        _notifyLogCallbacks(
          'Photos refreshed after library change at ${_formatTime(_lastPhotoUpdateTime!)}',
        );
      }
    } catch (e) {
      print('Error loading photos: $e');
    } finally {
      _isLoading = false;
    }
  }

  // Load photos quietly (without notifications)
  Future<void> loadPhotosQuietly({int quantity = 100}) async {
    print('Loading photos quietly with quantity limit: $quantity');
    try {
      // Request permission
      final permission = await PhotoManager.requestPermissionExtend();
      if (!permission.isAuth) return;

      // Get all albums
      final List<AssetPathEntity> albums = await PhotoManager.getAssetPathList(
        onlyAll: true,
        type: RequestType.image,
      );

      if (albums.isEmpty) return;

      // Get the recent album
      final recentAlbum = albums[0];

      // Get asset list (photos)
      final List<AssetEntity> assets = await recentAlbum.getAssetListRange(
        start: 0,
        end: quantity, // Use the specified quantity parameter
      );

      print('Quietly loaded ${assets.length} assets from device');

      // Convert to our Photo model
      final List<Photo> loadedPhotos = [];
      for (var asset in assets) {
        final String fileName = asset.title ?? 'Unknown';
        // Check if this photo was previously synced
        final bool wasSynced = _syncedPhotoIds.contains(asset.id);

        loadedPhotos.add(
          Photo(
            id: asset.id,
            name: fileName,
            path: asset.relativePath ?? '',
            asset: asset,
            synced: wasSynced,
          ),
        );
      }

      // Load thumbnails for the photos
      for (var photo in loadedPhotos) {
        try {
          final Uint8List? thumbnail = await photo.asset?.thumbnailData;
          if (thumbnail != null) {
            photo.thumbnailData = thumbnail;
          }
        } catch (e) {
          print('Error loading thumbnail for ${photo.name}: $e');
        }
      }

      // Store all photos
      _allPhotos = loadedPhotos;
      print('Quietly updated _allPhotos with ${_allPhotos.length} photos');

      // Apply date filter - this will also notify listeners
      _filterPhotosByDateTime();
    } catch (e) {
      print('Error silently loading photos: $e');
    }
  }

  // Filter photos based on the selected datetime
  void _filterPhotosByDateTime() {
    _filteredPhotos =
        _allPhotos.where((photo) {
          if (photo.asset == null) return false;

          // Get the photo's creation date/time
          final DateTime? photoDateTime = photo.asset!.createDateTime;
          if (photoDateTime == null) return false;
          // Compare with filter date/time
          return photoDateTime.isAfter(_filterDateTime) ||
              photoDateTime.isAtSameMomentAs(_filterDateTime);
        }).toList();

    // Debug info
    print(
      'Filtered photos updated: ${_filteredPhotos.length} photos after filtering (from ${_allPhotos.length} total)',
    );

    // Make sure to notify listeners about the updated list
    _notifyPhotosLoadedCallbacks();
  }

  // Auto-sync new photos to S3
  Future<void> startAutoSync() async {
    // Use a more robust locking mechanism
    if (_isLoading || _autoSyncInProgress) {
      print('Skipping auto-sync - already in progress or loading');
      return;
    }

    _autoSyncInProgress = true;
    _notifyAutoSyncStatus(true);
    _notifyLogCallbacks('Auto-sync triggered');
    print('Auto-sync started');

    try {
      // Create a separate list to handle photos that have already been synced
      List<Photo> photosToSync = [];

      // First check our queue of newly discovered photos
      if (_newPhotosQueue.isNotEmpty) {
        _notifyLogCallbacks(
          'Auto-syncing ${_newPhotosQueue.length} photos from queue in background',
        );
        // Process the queue
        await _performBackgroundSync(_newPhotosQueue);
      } else {
        // If queue is empty, look for new photos
        await checkForNewPhotos(
          quantity: 50,
        ); // Use smaller batch size for auto-sync

        if (_newPhotosQueue.isNotEmpty) {
          _notifyLogCallbacks(
            'Auto-syncing ${_newPhotosQueue.length} newly discovered photos',
          );
          await _performBackgroundSync(_newPhotosQueue);
        } else {
          _notifyLogCallbacks('No new photos to sync');
          print('No new photos to sync in auto-sync');
        }
      }
    } catch (e) {
      _notifyLogCallbacks('Error during auto-sync: $e');
      print('Error during auto-sync: $e');
    } finally {
      _autoSyncInProgress = false;
      _notifyAutoSyncStatus(false);
      print('Auto-sync completed');
    }
  }

  // Sync new photos using provided sync function
  Future<void> syncNewPhotos(
    Future<void> Function(List<Photo>) syncFunction,
  ) async {
    if (_lastPhotoUpdateTime == null) return;

    // Find new photos since the last sync time
    List<Photo> newPhotos =
        _filteredPhotos
            .where(
              (photo) =>
                  photo.asset != null &&
                  _lastPhotoUpdateTime != null &&
                  photo.asset!.createDateTime.isAfter(
                    _lastPhotoUpdateTime!.subtract(const Duration(minutes: 1)),
                  ),
            )
            .toList();

    if (newPhotos.isEmpty) {
      _notifyLogCallbacks('No new photos found to sync');
      return;
    }

    // Mark the new photos as selected
    for (var photo in newPhotos) {
      photo.isSelected = true;
    }

    // Call the provided sync function
    await syncFunction(newPhotos);
  }

  // Perform actual background sync
  Future<void> _performBackgroundSync(List<Photo> photosToSync) async {
    if (photosToSync.isEmpty) return;

    _notifyLogCallbacks(
      'Starting background sync for ${photosToSync.length} photos...',
    );

    // Authenticate with AWS (simulated)
    await Future.delayed(const Duration(seconds: 1));
    _notifyLogCallbacks('AWS authenticated for background sync');

    // Upload photos one by one
    int total = photosToSync.length;
    int successful = 0;
    int failed = 0;

    // Create a copy of the list to avoid modification during iteration
    List<Photo> photosToProcess = List.from(photosToSync);

    for (int i = 0; i < photosToProcess.length; i++) {
      Photo photo = photosToProcess[i];
      try {
        await Future.delayed(const Duration(milliseconds: 250));
        print(
          'Processing photo: ${photo.id}, name: ${photo.name}, path: ${photo.path}, '
          'selected: ${photo.isSelected}, synced: ${photo.synced}, syncFailed: ${photo.syncFailed}',
        );
        // Perform actual S3 upload
        final File? assetFile = await photo.asset?.file;

        String? uploadResult = await uploadRawImageToS3(
          _albumName,
          photo.name,
          assetFile!.path,
        );

        // Check if upload was successful
        bool syncSuccess = uploadResult != null;

        if (syncSuccess) {
          // Mark the photo as synced and persist the status
          await markPhotoAsSynced(photo.id);
          successful++;
          _notifyLogCallbacks(
            'Background uploaded ${i + 1}/$total: ${photo.name}',
          );
        } else {
          // Mark as failed
          await markPhotoAsSyncFailed(photo.id);
          failed++;
          _notifyLogCallbacks(
            'Background upload failed ${i + 1}/$total: ${photo.name}',
          );
        }
      } catch (e) {
        // Mark as failed on exception
        await markPhotoAsSyncFailed(photo.id);
        failed++;
        _notifyLogCallbacks('Error uploading ${photo.name}: $e');
      }
    }

    // Notify that photos list has been updated with sync status
    _notifyPhotosLoadedCallbacks();

    _notifyLogCallbacks(
      'Background sync completed: $successful succeeded, $failed failed',
    );
  }

  // Format time for display (HH:MM:SS)
  String _formatTime(DateTime time) {
    return '${time.hour.toString().padLeft(2, '0')}:${time.minute.toString().padLeft(2, '0')}:${time.second.toString().padLeft(2, '0')}';
  }

  // Clean up resources
  void dispose() {
    if (_listeningToPhotoChanges) {
      // Stop listening to changes
      PhotoManager.stopChangeNotify();

      // Remove the callback
      PhotoManager.removeChangeCallback(_photoChangeCallback);

      // Clean up the notifier
      _notifier?.dispose();
      _notifier = null;

      _listeningToPhotoChanges = false;
    }

    // Clean up periodic check
    _periodicCheckSubscription?.cancel();

    // Clear callbacks
    _logCallbacks.clear();
    _photosLoadedCallbacks.clear();
    _autoSyncStatusCallbacks.clear();
  }
}
