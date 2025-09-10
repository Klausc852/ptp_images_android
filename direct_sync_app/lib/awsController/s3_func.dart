import 'dart:io';
import 'dart:async';
import 'package:amplify_flutter/amplify_flutter.dart';
import 'package:amplify_storage_s3/amplify_storage_s3.dart';
import 'package:amplify_auth_cognito/amplify_auth_cognito.dart';
import 'package:path/path.dart' as path;
import 'amplify_outputs.dart';
import 'dart:typed_data';
import 'package:flutter_dotenv/flutter_dotenv.dart';

/// Configure Amplify with the amplify_outputs.dart configuration
Future<bool> configureAmplify() async {
  try {
    // Check if Amplify is already configured
    if (Amplify.isConfigured) {
      safePrint('Amplify is already configured');
      return true;
    }

    final storage = AmplifyStorageS3();
    final auth = AmplifyAuthCognito();
    await Amplify.addPlugins([auth, storage]);

    // call Amplify.configure to use the initialized categories in your app
    await Amplify.configure(amplifyConfig);
    safePrint('Amplify configured successfully');
    return true;
  } on Exception catch (e) {
    safePrint('An error occurred configuring Amplify: $e');
    return false;
  }
}

final s3ListOptions = StorageListOptions(
  bucket: StorageBucket.fromBucketInfo(
    BucketInfo(
      bucketName: dotenv.env['AWS_BASE_S3_BUCKET']!,
      region: dotenv.env['AWS_BASE_S3_REGION']!,
    ),
  ),
);
final s3UploadOptions = StorageUploadFileOptions(
  bucket: StorageBucket.fromBucketInfo(
    BucketInfo(
      bucketName: dotenv.env['AWS_BASE_S3_BUCKET']!,
      region: dotenv.env['AWS_BASE_S3_REGION']!,
    ),
  ),
);

/// Upload a single file to S3 with progress r

Future<String?> uploadRawImageToS3(
  String albumName,
  String photoId,
  String imagePath,
) async {
  safePrint("uploadRawImageToS3 called, $photoId");
  try {
    final result =
        await Amplify.Storage.uploadFile(
          path: StoragePath.fromString(
            'rawImage/o0XpoFXFBLjRtJ6bcr1v/uploads/$albumName/$photoId',
          ),
          localFile: AWSFile.fromPath(imagePath),
          options: s3UploadOptions,
        ).result;
    safePrint('Successfully uploaded file to S3: ${result.uploadedItem.path}');
    return result.uploadedItem.path;
  } on StorageException catch (e) {
    safePrint('Error uploading file to S3: ${e.message}');
    return null;
  }
}

/// Checks if Amplify is configured
bool isAmplifyConfigured() {
  return Amplify.isConfigured;
}
