import 'dart:async';
import 'dart:typed_data';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:path_provider/path_provider.dart';
import 'dart:io';

const platform = MethodChannel('com.example.direct_sync_app/camera');

class StorageBrowserScreen extends StatefulWidget {
  const StorageBrowserScreen({Key? key}) : super(key: key);

  @override
  State<StorageBrowserScreen> createState() => _StorageBrowserScreenState();
}

class _StorageBrowserScreenState extends State<StorageBrowserScreen> {
  bool _isLoading = true;
  bool _isConnected = false;
  String _errorMessage = '';
  List<int> _storageIds = [];
  Map<int, List<int>> _objectHandles = {};
  Map<int, Map<String, dynamic>> _objectInfos = {};
  Map<int, String> _localPaths = {};
  bool _showStorageList = true;

  @override
  void initState() {
    super.initState();
    _initializeCamera();
  }

  Future<void> _initializeCamera() async {
    try {
      final bool result = await platform.invokeMethod('initializeCamera');
      if (result) {
        _browseStorage();
      } else {
        setState(() {
          _errorMessage = 'Failed to initialize camera';
          _isLoading = false;
        });
      }
    } on PlatformException catch (e) {
      setState(() {
        _errorMessage = 'Error: ${e.message}';
        _isLoading = false;
      });
    }
  }

  Future<void> _browseStorage() async {
    try {
      // Get storage IDs
      final List<dynamic>? storageIds = await platform.invokeMethod(
        'getStorageIds',
      );

      if (storageIds == null) {
        setState(() {
          _errorMessage = 'No storage found';
          _isLoading = false;
        });
        return;
      }

      _storageIds = storageIds.cast<int>();

      // Get object handles for each storage
      for (final storageId in _storageIds) {
        final List<dynamic>? handles = await platform.invokeMethod(
          'getObjectHandles',
          {'storageId': storageId},
        );

        if (handles != null) {
          _objectHandles[storageId] = handles.cast<int>();

          // Get object info for each handle
          for (final handle in _objectHandles[storageId]!) {
            final Map<dynamic, dynamic>? info = await platform.invokeMethod(
              'getObjectInfo',
              {'objectHandle': handle},
            );

            if (info != null) {
              _objectInfos[handle] = Map<String, dynamic>.from(info);
            }
          }
        }
      }

      setState(() {
        _isLoading = false;
      });
    } on PlatformException catch (e) {
      setState(() {
        _errorMessage = 'Error: ${e.message}';
        _isLoading = false;
      });
    }

    try {
      final result = await platform.invokeMethod('browseStorage');
      setState(() {
        _storageData = Map<String, dynamic>.from(result);
        _isLoading = false;
      });
    } on PlatformException catch (e) {
      setState(() {
        _errorMessage = 'Failed to browse storage: ${e.message}';
        _isLoading = false;
      });
    } catch (e) {
      setState(() {
        _errorMessage = 'Unexpected error: $e';
        _isLoading = false;
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('Camera Storage Browser'),
        actions: [
          IconButton(
            icon: const Icon(Icons.refresh),
            onPressed: _browseStorage,
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
            Text(_errorMessage, style: const TextStyle(color: Colors.red)),
            const SizedBox(height: 20),
            ElevatedButton(
              onPressed: _browseStorage,
              child: const Text('Retry'),
            ),
          ],
        ),
      );
    }

    if (!_storageData.containsKey('success') ||
        _storageData['success'] != true) {
      return Center(
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            const Text(
              'Failed to browse camera storage',
              style: TextStyle(color: Colors.red),
            ),
            if (_storageData.containsKey('error'))
              Text(_storageData['error'].toString()),
            const SizedBox(height: 20),
            ElevatedButton(
              onPressed: _browseStorage,
              child: const Text('Retry'),
            ),
          ],
        ),
      );
    }

    final storageCount = _storageData['storageCount'] ?? 0;
    final storages =
        _storageData.containsKey('storages')
            ? List<Map<String, dynamic>>.from(_storageData['storages'])
            : <Map<String, dynamic>>[];

    if (storageCount == 0 || storages.isEmpty) {
      return const Center(child: Text('No storage found on camera'));
    }

    return ListView.builder(
      itemCount: storages.length,
      itemBuilder: (context, index) {
        final storage = storages[index];
        final storageInfo = Map<String, dynamic>.from(storage['info']);
        final storageObjects = List<Map<String, dynamic>>.from(
          storage['objects'],
        );

        return _buildStorageCard(storageInfo, storageObjects);
      },
    );
  }

  Widget _buildStorageCard(
    Map<String, dynamic> info,
    List<Map<String, dynamic>> objects,
  ) {
    final volumeLabel = info['volumeLabel'] ?? 'Unknown';
    final storageId = info['storageId'] ?? 'Unknown';
    final maxCapacity = info['maxCapacity'] ?? 0;
    final freeSpace = info['freeSpace'] ?? 0;

    // Format sizes in human readable format
    final totalSizeStr = _formatFileSize(maxCapacity);
    final freeSizeStr = _formatFileSize(freeSpace);

    return Card(
      margin: const EdgeInsets.all(10),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          ListTile(
            title: Text(
              volumeLabel != 'Unknown'
                  ? 'Storage: $volumeLabel'
                  : 'Storage: $storageId',
              style: const TextStyle(fontWeight: FontWeight.bold),
            ),
            subtitle: Text('$freeSizeStr free of $totalSizeStr'),
          ),
          const Divider(),
          Padding(
            padding: const EdgeInsets.symmetric(
              horizontal: 16.0,
              vertical: 8.0,
            ),
            child: Text(
              '${objects.length} items',
              style: TextStyle(fontWeight: FontWeight.bold),
            ),
          ),
          _buildStorageContents(objects),
        ],
      ),
    );
  }

  Widget _buildStorageContents(List<Map<String, dynamic>> objects) {
    return ListView.builder(
      shrinkWrap: true,
      physics: const NeverScrollableScrollPhysics(),
      itemCount: objects.length,
      itemBuilder: (context, index) {
        final object = objects[index];
        final filename = object['filename'] ?? 'Unknown';
        final isFolder = object['isFolder'] ?? false;
        final size = object['fileSize'] ?? 0;
        final objectHandle = object['objectHandle'];

        return ListTile(
          leading: Icon(
            isFolder ? Icons.folder : _getFileIcon(filename),
            color: isFolder ? Colors.amber : null,
          ),
          title: Text(filename),
          subtitle: Text(isFolder ? 'Folder' : _formatFileSize(size)),
          trailing:
              isFolder
                  ? const Icon(Icons.chevron_right)
                  : IconButton(
                    icon: const Icon(Icons.download),
                    onPressed: () => _downloadObject(objectHandle),
                  ),
        );
      },
    );
  }

  IconData _getFileIcon(String filename) {
    final extension = filename.split('.').last.toLowerCase();

    switch (extension) {
      case 'jpg':
      case 'jpeg':
      case 'png':
      case 'gif':
      case 'cr2':
      case 'cr3':
      case 'nef':
      case 'arw':
        return Icons.photo;
      case 'mov':
      case 'mp4':
      case 'avi':
        return Icons.movie;
      default:
        return Icons.insert_drive_file;
    }
  }

  String _formatFileSize(dynamic bytes) {
    if (bytes == null) return '0 B';

    // Convert to double to handle large values properly
    double size = bytes is int ? bytes.toDouble() : 0.0;

    const suffixes = ['B', 'KB', 'MB', 'GB', 'TB'];
    var i = 0;

    while (size >= 1024 && i < suffixes.length - 1) {
      size /= 1024;
      i++;
    }

    return '${size.toStringAsFixed(1)} ${suffixes[i]}';
  }

  void _downloadObject(int objectHandle) {
    // To be implemented - would call native method to download the file
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(content: Text('Downloading object $objectHandle...')),
    );
  }
}
