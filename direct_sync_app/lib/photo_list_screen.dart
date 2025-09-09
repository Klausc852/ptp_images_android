import 'dart:io';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

// Use the same method channel defined in main.dart
const platform = MethodChannel('com.example.direct_sync_app/ptp');

class PhotoListScreen extends StatefulWidget {
  const PhotoListScreen({Key? key}) : super(key: key);

  @override
  State<PhotoListScreen> createState() => _PhotoListScreenState();
}

class _PhotoListScreenState extends State<PhotoListScreen> {
  List<Map<String, dynamic>> _photos = [];
  bool _isLoading = true;
  String _errorMessage = '';

  @override
  void initState() {
    super.initState();
    _loadPhotos();
  }

  Future<void> _loadPhotos() async {
    setState(() {
      _isLoading = true;
      _errorMessage = '';
    });

    try {
      final result = await platform.invokeMethod('getDownloadedPhotos');

      List<Map<String, dynamic>> photoList = [];
      for (var photo in result) {
        photoList.add(Map<String, dynamic>.from(photo));
      }

      setState(() {
        _photos = photoList;
        _isLoading = false;
      });
    } catch (e) {
      setState(() {
        _errorMessage = 'Error loading photos: $e';
        _isLoading = false;
      });
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
            onPressed: _loadPhotos,
            tooltip: 'Refresh list',
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

    return ListView.builder(
      itemCount: _photos.length,
      itemBuilder: (context, index) {
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
            title: Text(
              filename,
              maxLines: 1,
              overflow: TextOverflow.ellipsis,
              style: const TextStyle(fontWeight: FontWeight.bold),
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

    return Scaffold(
      appBar: AppBar(
        title: Text(filename),
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
                Text(
                  filename,
                  style: const TextStyle(
                    color: Colors.white,
                    fontWeight: FontWeight.bold,
                    fontSize: 16,
                  ),
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
