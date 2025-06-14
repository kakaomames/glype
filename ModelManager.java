import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.util.Log;

import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

public class ModelManager {

    private static final String TAG = "ModelManager";

    private final Context context;
    private final DownloadManager downloadManager;
    private final Map<Long, DownloadRequestListener> activeDownloads = new HashMap<>();

    public interface DownloadListener {
        void onProgress(int progressPercentage);
        void onComplete(File modelFile);
        void onError(String reason);
    }

    private static class DownloadRequestListener {
        final DownloadListener listener;
        final String targetFileName;

        DownloadRequestListener(DownloadListener listener, String targetFileName) {
            this.listener = listener;
            this.targetFileName = targetFileName;
        }
    }

    public ModelManager(Context context) {
        this.context = context.getApplicationContext(); // Use application context
        this.downloadManager = (DownloadManager) this.context.getSystemService(Context.DOWNLOAD_SERVICE);
        if (this.downloadManager == null) {
            Log.e(TAG, "DownloadManager service not available.");
            // Consider throwing an exception or having a flag indicating unavailability
        }
    }

    public void downloadModel(String modelUrl, String targetFileName, DownloadListener listener) {
        if (downloadManager == null) {
            listener.onError("DownloadManager not available.");
            return;
        }

        File targetFile = new File(context.getFilesDir(), targetFileName);
        if (targetFile.exists()) {
            // Optionally, one might want to compare file sizes or hashes if redownload is needed
            Log.i(TAG, "Model " + targetFileName + " already exists. Notifying listener.");
            listener.onComplete(targetFile);
            return;
        }

        DownloadManager.Request request;
        try {
            request = new DownloadManager.Request(Uri.parse(modelUrl));
        } catch (IllegalArgumentException e) {
            Log.e(TAG, "Invalid model URL: " + modelUrl, e);
            listener.onError("Invalid model URL: " + modelUrl);
            return;
        }

        request.setTitle("Downloading Model: " + targetFileName);
        request.setDescription(modelUrl);
        request.setDestinationInExternalFilesDir(context, null, targetFileName); // Saves to /Android/data/your.package/files/targetFileName
        // For app-internal storage like context.getFilesDir():
        // request.setDestinationUri(Uri.fromFile(new File(context.getFilesDir(), targetFileName)));
        // Note: setDestinationInExternalFilesDir is generally preferred for user/system manageability
        // but the requirement was context.getFilesDir(). Let's adjust to use internal storage.

        File destinationFile = new File(context.getFilesDir(), targetFileName);
        request.setDestinationUri(Uri.fromFile(destinationFile));


        try {
            long downloadId = downloadManager.enqueue(request);
            activeDownloads.put(downloadId, new DownloadRequestListener(listener, targetFileName));

            // Register receiver if this is the first download
            if (activeDownloads.size() == 1) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    context.registerReceiver(downloadReceiver, new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE), Context.RECEIVER_NOT_EXPORTED);
                } else {
                    context.registerReceiver(downloadReceiver, new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE));
                }
                Log.d(TAG, "DownloadReceiver registered.");
            }
            listener.onProgress(0); // Initial progress
            Log.i(TAG, "Download enqueued for " + targetFileName + " with ID: " + downloadId);
        } catch (Exception e) {
            Log.e(TAG, "Failed to enqueue download request for " + modelUrl, e);
            listener.onError("Failed to enqueue download: " + e.getMessage());
        }
    }

    public File getModelPath(String fileName) {
        File modelFile = new File(context.getFilesDir(), fileName);
        if (modelFile.exists()) {
            return modelFile;
        }
        return null;
    }

    public boolean isModelDownloaded(String fileName) {
        return getModelPath(fileName) != null;
    }

    private final BroadcastReceiver downloadReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent intent) {
            long id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
            if (!activeDownloads.containsKey(id)) {
                return; // Not a download we are tracking
            }

            DownloadRequestListener requestListener = activeDownloads.get(id);
            Objects.requireNonNull(requestListener, "DownloadRequestListener should not be null for a tracked download ID.");

            DownloadManager.Query query = new DownloadManager.Query();
            query.setFilterById(id);
            try (Cursor cursor = downloadManager.query(query)) {
                if (cursor != null && cursor.moveToFirst()) {
                    int statusIndex = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS);
                    int reasonIndex = cursor.getColumnIndex(DownloadManager.COLUMN_REASON); // Corrected from COLUMN_ERROR_CODE to COLUMN_REASON for better error messages

                    int status = cursor.getInt(statusIndex);

                    if (status == DownloadManager.STATUS_SUCCESSFUL) {
                        File modelFile = new File(context.getFilesDir(), requestListener.targetFileName);
                        Log.i(TAG, "Download " + id + " (" + requestListener.targetFileName + ") completed successfully.");
                        requestListener.listener.onComplete(modelFile);
                    } else {
                        int reason = cursor.getInt(reasonIndex);
                        String reasonText = getDownloadErrorReason(reason, cursor, reasonIndex);
                        Log.e(TAG, "Download " + id + " (" + requestListener.targetFileName + ") failed. Status: " + status + ", Reason: " + reasonText);
                        requestListener.listener.onError("Download failed: " + reasonText);
                    }
                } else {
                    Log.e(TAG, "Download " + id + " (" + requestListener.targetFileName + ") cursor empty or failed to move to first. Cannot determine status.");
                    requestListener.listener.onError("Failed to get download status for " + requestListener.targetFileName);
                }
            } catch (Exception e) {
                Log.e(TAG, "Error processing download completion for " + id + " (" + requestListener.targetFileName + ")", e);
                requestListener.listener.onError("Error processing download completion: " + e.getMessage());
            } finally {
                activeDownloads.remove(id);
                if (activeDownloads.isEmpty()) {
                    try {
                        context.unregisterReceiver(this);
                        Log.d(TAG, "DownloadReceiver unregistered as no active downloads.");
                    } catch (IllegalArgumentException e) {
                        // Receiver might have already been unregistered or not registered at all
                        Log.w(TAG, "Failed to unregister DownloadReceiver: " + e.getMessage());
                    }
                }
            }
        }
    };

    private String getDownloadErrorReason(int reasonCode, Cursor cursor, int reasonColumnIndex) {
        // Try to get a more descriptive message if available
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) { // COLUMN_ERROR_CODE_DOCUMENTATION available from API 28
             // The column DownloadManager.COLUMN_REASON actually holds the reason code directly.
             // For more detailed textual reasons, one might need to map these codes to strings.
        }
        // For simplicity, returning the code as a string.
        // A more robust implementation would map these codes to user-friendly messages.
        return "Reason code " + reasonCode;
    }

    // Call this method when the ModelManager is no longer needed, e.g., in onDestroy of an Activity/Service
    public void cleanup() {
        if (!activeDownloads.isEmpty()) {
            Log.w(TAG, "Cleaning up ModelManager with active downloads still present. Unregistering receiver.");
            // Optionally, you might want to cancel ongoing downloads here
            // For example:
            // for (long downloadId : activeDownloads.keySet()) {
            //     downloadManager.remove(downloadId);
            // }
            // activeDownloads.clear();
        }
        try {
            context.unregisterReceiver(downloadReceiver);
            Log.d(TAG, "DownloadReceiver unregistered during cleanup.");
        } catch (IllegalArgumentException e) {
            // Receiver might have already been unregistered or not registered at all
            // This is fine, just means it wasn't active.
            Log.d(TAG, "DownloadReceiver was not registered or already unregistered during cleanup.");
        }
    }
}
