import 'dart:io';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:shared_preferences/shared_preferences.dart';

// Use the same method channel defined in main.dart
const platform = MethodChannel('com.example.direct_sync_app/camera');

class PhotoListScreen extends StatefulWidget {
  const PhotoListScreen({Key? key}) : super(key: key);

  @override
  State<PhotoListScreen> createState() => _PhotoListScreenState();
}

class _PhotoListScreenState extends State<PhotoListScreen> {
  // Scroll controller for infinite scroll pagination
  final ScrollController _scrollController = ScrollController();
  List<Map<String, dynamic>> _photos = [];
  bool _isLoading = true;
  bool _isLoadingMore = false;
  String _errorMessage = '';
  Set<String> _syncedPhotoIds = {};

  // Pagination parameters
  int _currentPage = 0;
  final int _photosPerPage = 20; // Number of photos to load per page
  bool _hasMorePhotos = true; // Flag to track if more photos are available

  @override
  void initState() {
    super.initState();

    // Setup scroll controller for infinite scrolling
    _scrollController.addListener(_scrollListener);

    // Load initial photos
    _loadPhotos();
  }

  @override
  void dispose() {
    _scrollController.removeListener(_scrollListener);
    _scrollController.dispose();
    super.dispose();
  }

  // Listener for scroll events to handle infinite scrolling
  void _scrollListener() {
    if (!_isLoading && !_isLoadingMore && _hasMorePhotos) {
      if (_scrollController.position.pixels >=
          _scrollController.position.maxScrollExtent * 0.8) {
        // User scrolled to 80% of the list, load more photos
        _loadPhotos(reset: false);
      }
    }
  }

  // Method is now used in onTap of the refresh button

  Future<void> _loadPhotos({bool reset = true}) async {
    if (reset) {
      setState(() {
        _isLoading = true;
        _errorMessage = '';
        _currentPage = 0;
        _hasMorePhotos = true;
        _photos = [];
      });
    } else {
      setState(() {
        _isLoadingMore = true;
      });
    }

    try {
      // First, get synced photo IDs from PhotoSyncService
      await _loadSyncedPhotoIds();

      final Map<String, dynamic> args = {
        'page': _currentPage,
        'pageSize': _photosPerPage,
      };

      dynamic result;
      try {
        // Try to use the paginated method
        result = await platform.invokeMethod(
          'getDownloadedPhotosPaginated',
          args,
        );
      } catch (e) {
        // Fallback to non-paginated method if the new method isn't implemented yet
        print(
          'Warning: getDownloadedPhotosPaginated not implemented, falling back to getDownloadedPhotos',
        );
        final List<dynamic> photos = await platform.invokeMethod(
          'getDownloadedPhotos',
        );

        // Create a compatible result structure
        final int start = _currentPage * _photosPerPage;
        final int end = start + _photosPerPage;

        // Apply pagination manually
        final List<dynamic> pagedPhotos =
            photos.length > start
                ? photos.sublist(
                  start,
                  photos.length > end ? end : photos.length,
                )
                : [];

        result = {'photos': pagedPhotos, 'hasMore': end < photos.length};
      }

      // Check if we've reached the end
      final bool hasMore = result['hasMore'] as bool? ?? false;
      final List<dynamic> photosData = result['photos'] as List<dynamic>? ?? [];

      List<Map<String, dynamic>> photoList = [];
      for (var photo in photosData) {
        final photoMap = Map<String, dynamic>.from(photo);

        // Check if this photo is synced and add the synced status
        final String photoId =
            photoMap['id']?.toString() ??
            photoMap['filename']?.toString() ??
            '';
        photoMap['synced'] = _syncedPhotoIds.contains(photoId);

        photoList.add(photoMap);
      }

      // Increment page for next load and update hasMore flag
      _currentPage++;
      _hasMorePhotos = hasMore;

      setState(() {
        if (reset) {
          _photos = photoList;
        } else {
          _photos.addAll(photoList);
        }
        _isLoading = false;
        _isLoadingMore = false;
      });

      print(
        'Loaded ${photoList.length} photos (total: ${_photos.length}, hasMore: $_hasMorePhotos)',
      );
    } catch (e) {
      setState(() {
        _errorMessage = 'Error loading photos: $e';
        _isLoading = false;
        _isLoadingMore = false;
        _hasMorePhotos = false; // Assume no more photos on error
      });
      print('Error loading photos: $e');
    }
  }

  // Load synced photo IDs from PhotoSyncService
  Future<void> _loadSyncedPhotoIds() async {
    try {
      // Use the PhotoSyncService to get synced photo IDs
      // First load the IDs from SharedPreferences
      final prefs = await SharedPreferences.getInstance();
      final syncedIds = prefs.getStringList('syncedPhotoIds') ?? [];
      _syncedPhotoIds = syncedIds.toSet();
      print(
        'Loaded ${_syncedPhotoIds.length} synced photo IDs in PhotoListScreen',
      );
    } catch (e) {
      print('Error loading synced photo IDs in PhotoListScreen: $e');
    }
  }

  Future<void> _clearDownloadedPhotos() async {
    try {
      await platform.invokeMethod('clearDownloadedPhotos');
      _loadPhotos(); // Reload the empty list
    } catch (e) {
      setState(() {
        _errorMessage = 'Error clearing photos: $e';
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('Downloaded Photos'),
        actions: [
          IconButton(
            icon: const Icon(Icons.refresh),
            onPressed: () async {
              // Load photos again and also refresh synced status
              await _loadPhotos();

              // Show confirmation
              if (mounted) {
                ScaffoldMessenger.of(context).showSnackBar(
                  const SnackBar(
                    content: Text('Photos and sync status refreshed'),
                    duration: Duration(seconds: 1),
                  ),
                );
              }
            },
            tooltip: 'Refresh list and sync status',
          ),
          IconButton(
            icon: const Icon(Icons.delete_outline),
            onPressed:
                _photos.isNotEmpty
                    ? () => _showClearConfirmationDialog()
                    : null,
            tooltip: 'Clear list',
          ),
        ],
      ),
      body: _buildBody(),
    );
  }

  Widget _buildBody() {
    if (_isLoading) {
      return const Center(child: CircularProgressIndicator());
    }

    if (_errorMessage.isNotEmpty) {
      return Center(
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            Text(
              _errorMessage,
              style: const TextStyle(color: Colors.red),
              textAlign: TextAlign.center,
            ),
            const SizedBox(height: 20),
            ElevatedButton(
              onPressed: _loadPhotos,
              child: const Text('Try Again'),
            ),
          ],
        ),
      );
    }

    if (_photos.isEmpty) {
      return const Center(
        child: Text(
          'No photos downloaded yet.\nTake a photo with your camera to see it here.',
          textAlign: TextAlign.center,
          style: TextStyle(fontSize: 16),
        ),
      );
    }

    return RefreshIndicator(
      onRefresh: () => _loadPhotos(reset: true),
      child: ListView.builder(
        controller: _scrollController,
        itemCount:
            _photos.length +
            (_hasMorePhotos ? 1 : 0), // Add one for the loading indicator
        itemBuilder: (context, index) {
          // Show loading indicator at the end
          if (index == _photos.length && _hasMorePhotos) {
            return const Center(
              child: Padding(
                padding: EdgeInsets.all(16.0),
                child: CircularProgressIndicator(),
              ),
            );
          }
          // Display photos in reverse order (newest first)
          final photo = _photos[_photos.length - 1 - index];
          final path = photo['path'] as String;
          final filename = photo['filename'] as String;
          final timestamp = photo['timestamp'] as int;
          final width = photo['width'] as int? ?? 0;
          final height = photo['height'] as int? ?? 0;
          final size = photo['size'] as int? ?? 0;

          // Format the timestamp as a readable date/time
          final date = DateTime.fromMillisecondsSinceEpoch(timestamp);
          final formattedDate =
              '${date.year}-${date.month.toString().padLeft(2, '0')}-${date.day.toString().padLeft(2, '0')} '
              '${date.hour.toString().padLeft(2, '0')}:${date.minute.toString().padLeft(2, '0')}:${date.second.toString().padLeft(2, '0')}';

          // Format the file size as KB or MB
          String formattedSize;
          if (size < 1024 * 1024) {
            formattedSize = '${(size / 1024).toStringAsFixed(1)} KB';
          } else {
            formattedSize = '${(size / (1024 * 1024)).toStringAsFixed(1)} MB';
          }

          return Card(
            margin: const EdgeInsets.symmetric(horizontal: 12, vertical: 6),
            child: ListTile(
              contentPadding: const EdgeInsets.all(8),
              leading: Container(
                width: 60,
                height: 60,
                decoration: BoxDecoration(
                  color: Colors.grey[300],
                  borderRadius: BorderRadius.circular(4),
                ),
                child:
                    File(path).existsSync()
                        ? Image.file(
                          File(path),
                          fit: BoxFit.cover,
                          errorBuilder: (context, error, stackTrace) {
                            return const Icon(Icons.broken_image, size: 30);
                          },
                        )
                        : const Icon(Icons.photo, size: 30),
              ),
              title: Row(
                children: [
                  Expanded(
                    child: Text(
                      filename,
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: TextStyle(
                        fontWeight: FontWeight.bold,
                        color:
                            photo['synced'] == true
                                ? Colors.green.shade700
                                : Colors.black,
                      ),
                    ),
                  ),
                  if (photo['synced'] == true)
                    Icon(
                      Icons.cloud_done,
                      color: Colors.green.shade700,
                      size: 16,
                    ),
                ],
              ),
              subtitle: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text('Date: $formattedDate'),
                  Text('Size: $formattedSize, Resolution: ${width}x$height'),
                ],
              ),
              isThreeLine: true,
              onTap: () => _showPhotoDetails(photo, context),
            ),
          );
        },
      ),
    );
  }

  void _showPhotoDetails(Map<String, dynamic> photo, BuildContext context) {
    final path = photo['path'] as String;

    if (!File(path).existsSync()) {
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('Image file no longer exists')),
      );
      return;
    }

    Navigator.of(context).push(
      MaterialPageRoute(builder: (context) => PhotoDetailScreen(photo: photo)),
    );
  }

  Future<void> _showClearConfirmationDialog() async {
    return showDialog<void>(
      context: context,
      barrierDismissible: false,
      builder: (BuildContext context) {
        return AlertDialog(
          title: const Text('Clear Photo List?'),
          content: const Text(
            'This will clear the list of downloaded photos. '
            'The actual image files will remain on your device.',
          ),
          actions: <Widget>[
            TextButton(
              child: const Text('Cancel'),
              onPressed: () {
                Navigator.of(context).pop();
              },
            ),
            TextButton(
              child: const Text('Clear'),
              onPressed: () {
                Navigator.of(context).pop();
                _clearDownloadedPhotos();
              },
            ),
          ],
        );
      },
    );
  }
}

class PhotoDetailScreen extends StatelessWidget {
  final Map<String, dynamic> photo;

  const PhotoDetailScreen({Key? key, required this.photo}) : super(key: key);

  @override
  Widget build(BuildContext context) {
    final path = photo['path'] as String;
    final filename = photo['filename'] as String;
    final timestamp = photo['timestamp'] as int;
    final width = photo['width'] as int? ?? 0;
    final height = photo['height'] as int? ?? 0;
    final size = photo['size'] as int? ?? 0;

    // Format the timestamp as a readable date/time
    final date = DateTime.fromMillisecondsSinceEpoch(timestamp);
    final formattedDate =
        '${date.year}-${date.month.toString().padLeft(2, '0')}-${date.day.toString().padLeft(2, '0')} '
        '${date.hour.toString().padLeft(2, '0')}:${date.minute.toString().padLeft(2, '0')}:${date.second.toString().padLeft(2, '0')}';

    // Format the file size as KB or MB
    String formattedSize;
    if (size < 1024 * 1024) {
      formattedSize = '${(size / 1024).toStringAsFixed(1)} KB';
    } else {
      formattedSize = '${(size / (1024 * 1024)).toStringAsFixed(1)} MB';
    }

    final bool isSynced = photo['synced'] == true;

    return Scaffold(
      appBar: AppBar(
        title: Row(
          children: [
            Expanded(child: Text(filename)),
            if (isSynced) Icon(Icons.cloud_done, color: Colors.green, size: 20),
          ],
        ),
        actions: [
          IconButton(
            icon: const Icon(Icons.share),
            onPressed: () {
              // TODO: Implement sharing functionality
              ScaffoldMessenger.of(context).showSnackBar(
                const SnackBar(content: Text('Sharing not implemented yet')),
              );
            },
          ),
          PopupMenuButton<String>(
            onSelected: (value) {
              if (value == 'view_in_gallery') {
                // TODO: Implement opening in gallery app
                ScaffoldMessenger.of(context).showSnackBar(
                  const SnackBar(
                    content: Text('Open in gallery not implemented yet'),
                  ),
                );
              } else if (value == 'delete') {
                // TODO: Implement delete functionality
                ScaffoldMessenger.of(context).showSnackBar(
                  const SnackBar(content: Text('Delete not implemented yet')),
                );
              }
            },
            itemBuilder: (BuildContext context) {
              return {'View in Gallery', 'Delete'}.map((String choice) {
                return PopupMenuItem<String>(
                  value:
                      choice == 'View in Gallery'
                          ? 'view_in_gallery'
                          : 'delete',
                  child: Text(choice),
                );
              }).toList();
            },
          ),
        ],
      ),
      body: Column(
        children: [
          Expanded(
            child: InteractiveViewer(
              panEnabled: true,
              boundaryMargin: const EdgeInsets.all(20),
              minScale: 0.5,
              maxScale: 4,
              child: Image.file(
                File(path),
                errorBuilder: (context, error, stackTrace) {
                  return Center(
                    child: Column(
                      mainAxisAlignment: MainAxisAlignment.center,
                      children: [
                        const Icon(Icons.broken_image, size: 64),
                        const SizedBox(height: 16),
                        Text('Error loading image: $error'),
                      ],
                    ),
                  );
                },
              ),
            ),
          ),
          Container(
            color: Colors.black87,
            padding: const EdgeInsets.all(16),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Row(
                  children: [
                    Expanded(
                      child: Text(
                        filename,
                        style: TextStyle(
                          color: isSynced ? Colors.green : Colors.white,
                          fontWeight: FontWeight.bold,
                          fontSize: 16,
                        ),
                      ),
                    ),
                    if (isSynced)
                      Icon(Icons.cloud_done, color: Colors.green, size: 16),
                  ],
                ),
                const SizedBox(height: 4),
                Text(
                  'Taken: $formattedDate',
                  style: const TextStyle(color: Colors.white70),
                ),
                const SizedBox(height: 2),
                Text(
                  'Resolution: ${width}x$height}, Size: $formattedSize',
                  style: const TextStyle(color: Colors.white70),
                ),
                const SizedBox(height: 2),
                Text(
                  'Path: $path',
                  style: const TextStyle(color: Colors.white70),
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }
}
