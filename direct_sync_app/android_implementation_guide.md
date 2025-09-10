# Android Implementation Guide for Paginated Photo Loading

To implement the paginated photo loading on the Android side, you'll need to modify your `MainActivity.kt` file to add a new method channel handler for `getDownloadedPhotosPaginated`. Here's how to implement it:

## Step 1: Add a new method channel handler

In your `MainActivity.kt`, find the `configureFlutterEngine` method or where you've registered your method channel handlers. Add a new handler for `getDownloadedPhotosPaginated`:

```kotlin
"getDownloadedPhotosPaginated" -> {
    val page = call.argument<Int>("page") ?: 0
    val pageSize = call.argument<Int>("pageSize") ?: 20
    
    Thread {
        val result = getDownloadedPhotosPaginated(page, pageSize)
        runOnUiThread {
            methodResult.success(result)
        }
    }.start()
}
```

## Step 2: Implement the getDownloadedPhotosPaginated method

Add this method to your MainActivity class:

```kotlin
private fun getDownloadedPhotosPaginated(page: Int, pageSize: Int): Map<String, Any> {
    val photosDir = File(filesDir, "photos")
    val result = mutableMapOf<String, Any>()
    val photosList = mutableListOf<Map<String, Any>>()

    if (photosDir.exists() && photosDir.isDirectory) {
        val allFiles = photosDir.listFiles() ?: emptyArray()
        
        // Sort by last modified date, newest first
        val sortedFiles = allFiles.sortedByDescending { it.lastModified() }
        
        // Calculate start and end indices for pagination
        val start = page * pageSize
        val end = minOf((page + 1) * pageSize, sortedFiles.size)
        
        // Check if we have more pages
        val hasMore = sortedFiles.size > end
        
        // Get the photos for the current page
        if (start < sortedFiles.size) {
            for (i in start until end) {
                val file = sortedFiles[i]
                try {
                    // Create photo info map similar to your current implementation
                    val photoMap = mutableMapOf<String, Any>()
                    photoMap["path"] = file.absolutePath
                    photoMap["filename"] = file.name
                    photoMap["timestamp"] = file.lastModified()
                    photoMap["id"] = file.name // Use filename as ID or generate a better ID
                    
                    // Get image dimensions if needed
                    try {
                        val options = BitmapFactory.Options()
                        options.inJustDecodeBounds = true
                        BitmapFactory.decodeFile(file.absolutePath, options)
                        photoMap["width"] = options.outWidth
                        photoMap["height"] = options.outHeight
                        
                        // Get file size
                        photoMap["size"] = file.length()
                    } catch (e: Exception) {
                        Log.e("DirectSyncApp", "Error getting image info: ${e.message}")
                    }
                    
                    photosList.add(photoMap)
                } catch (e: Exception) {
                    Log.e("DirectSyncApp", "Error processing photo: ${e.message}")
                }
            }
        }
        
        result["photos"] = photosList
        result["hasMore"] = hasMore
    } else {
        result["photos"] = photosList
        result["hasMore"] = false
    }
    
    return result
}
```

## Step 3: Update Imports

Make sure you have all necessary imports at the top of your file:

```kotlin
import android.graphics.BitmapFactory
import android.util.Log
import java.io.File
```

## Step 4: Test

Test the pagination functionality by running your app and scrolling through a large list of photos to ensure it loads properly in batches.

## Notes:

1. This implementation assumes your photos are stored in the app's files directory in a "photos" folder. Adjust the path as needed.
2. The implementation sorts photos by last modified date with newest first, which matches your current ListView display order.
3. The response includes both the list of photos and a `hasMore` flag to indicate whether more photos are available.
4. You may need to adjust the logic to match your existing photo loading implementation.
