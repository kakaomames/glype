import android.Manifest;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ResultReceiver;
import android.provider.OpenableColumns;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

// Assuming other classes (AudioRecordingManager, ModelManager, TranscriptionService) are in the same package
// or appropriate imports are added.

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";
    private static final int REQUEST_RECORD_AUDIO_PERMISSION = 200;
    private static final int SELECT_AUDIO_REQUEST_CODE = 101;

    // UI Elements
    private Button btnRecord;
    private Button btnStopRecording;
    private Button btnSelectFile;
    private Button btnDownloadModel;
    private TextView tvStatus;
    private ProgressBar progressBar;
    private ScrollView svTranscription;
    private TextView tvTranscriptionOutput;
    private Button btnCopyText;

    // Managers and Helpers
    private AudioRecordingManager audioRecordingManager;
    private ModelManager modelManager;
    private File currentRecordingFile;
    // TODO: Make modelName and modelUrl configurable or selectable by the user
    private String modelName = "ggml-tiny.en.bin"; // Default model name from whisper.cpp examples
    private String modelUrl = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny.en.bin";

    private TranscriptionResultReceiver transcriptionResultReceiver;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main); // Make sure this matches your XML file name

        // Initialize UI Elements
        btnRecord = findViewById(R.id.btnRecord);
        btnStopRecording = findViewById(R.id.btnStopRecording);
        btnSelectFile = findViewById(R.id.btnSelectFile);
        btnDownloadModel = findViewById(R.id.btnDownloadModel);
        tvStatus = findViewById(R.id.tvStatus);
        progressBar = findViewById(R.id.progressBar);
        svTranscription = findViewById(R.id.svTranscription); // Corrected ID if necessary
        tvTranscriptionOutput = findViewById(R.id.tvTranscriptionOutput);
        btnCopyText = findViewById(R.id.btnCopyText);

        // Initialize Managers and Helpers
        audioRecordingManager = new AudioRecordingManager();
        modelManager = new ModelManager(this);
        transcriptionResultReceiver = new TranscriptionResultReceiver(new Handler(Looper.getMainLooper()), this);

        // Request Permissions
        requestAudioPermission();

        // Set Click Listeners
        btnRecord.setOnClickListener(v -> startAudioRecording());
        btnStopRecording.setOnClickListener(v -> stopAudioRecording());
        btnSelectFile.setOnClickListener(v -> selectAudioFile());
        btnDownloadModel.setOnClickListener(v -> downloadModel());
        btnCopyText.setOnClickListener(v -> copyTranscriptionToClipboard());

        updateStatus("Ready. Download model if needed.");
    }

    private void requestAudioPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.RECORD_AUDIO},
                    REQUEST_RECORD_AUDIO_PERMISSION);
        } else {
            Log.d(TAG, "RECORD_AUDIO permission already granted.");
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_RECORD_AUDIO_PERMISSION) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Record Audio permission granted.", Toast.LENGTH_SHORT).show();
                Log.d(TAG, "RECORD_AUDIO permission granted by user.");
            } else {
                Toast.makeText(this, "Record Audio permission denied.", Toast.LENGTH_SHORT).show();
                updateStatus("Record Audio permission denied. Cannot record.");
                Log.w(TAG, "RECORD_AUDIO permission denied by user.");
            }
        }
    }

    private void startAudioRecording() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            requestAudioPermission();
            return;
        }

        currentRecordingFile = new File(getExternalCacheDir(), "recording.m4a");
        Log.d(TAG, "Attempting to start recording to: " + currentRecordingFile.getAbsolutePath());
        if (audioRecordingManager.startRecording(currentRecordingFile)) {
            updateStatus("Recording...");
            btnRecord.setVisibility(View.GONE);
            btnStopRecording.setVisibility(View.VISIBLE);
            tvTranscriptionOutput.setText(""); // Clear previous transcription
            btnCopyText.setVisibility(View.GONE);
        } else {
            updateStatus("Failed to start recording.");
            Log.e(TAG, "audioRecordingManager.startRecording failed.");
        }
    }

    private void stopAudioRecording() {
        audioRecordingManager.stopRecording();
        Log.d(TAG, "Recording stopped.");
        updateStatus("Recording stopped. Preparing for transcription...");
        btnRecord.setVisibility(View.VISIBLE);
        btnStopRecording.setVisibility(View.GONE);

        if (currentRecordingFile == null || !currentRecordingFile.exists()) {
            updateStatus("No recording found to transcribe.");
            Log.e(TAG, "currentRecordingFile is null or does not exist.");
            return;
        }

        File modelFile = modelManager.getModelPath(modelName);
        if (modelFile == null) {
            updateStatus("Model '" + modelName + "' not downloaded yet! Please download the model first.");
            Log.w(TAG, "Model file not found: " + modelName);
            return;
        }

        showProgress("Transcribing recorded audio...");
        TranscriptionService.startTranscription(this,
                currentRecordingFile.getAbsolutePath(),
                modelFile.getAbsolutePath(),
                transcriptionResultReceiver);
    }

    private void selectAudioFile() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("audio/*");
        // intent.addCategory(Intent.CATEGORY_OPENABLE); // Useful for ensuring the URI is streamable
        try {
            startActivityForResult(Intent.createChooser(intent, "Select Audio File"), SELECT_AUDIO_REQUEST_CODE);
        } catch (android.content.ActivityNotFoundException ex) {
            Toast.makeText(this, "Please install a File Manager.", Toast.LENGTH_SHORT).show();
            Log.e(TAG, "ActivityNotFoundException for ACTION_GET_CONTENT", ex);
        }
    }

    private void downloadModel() {
        if (modelManager.isModelDownloaded(modelName)) {
            updateStatus("Model '" + modelName + "' is already downloaded.");
            Log.i(TAG, "Model " + modelName + " already downloaded.");
            // Optionally, trigger something else if model is already downloaded, e.g., enable transcription buttons
            return;
        }

        showProgress("Downloading model: " + modelName);
        tvTranscriptionOutput.setText(""); // Clear previous transcription
        btnCopyText.setVisibility(View.GONE);

        modelManager.downloadModel(modelUrl, modelName, new ModelManager.DownloadListener() {
            @Override
            public void onProgress(int progressPercentage) {
                // DownloadManager doesn't easily provide continuous progress without more complex setup.
                // This might only be called once or not at all with the current ModelManager.
                updateStatus("Downloading model: " + modelName + " (" + progressPercentage + "%)");
                Log.d(TAG, "Model download progress for " + modelName + ": " + progressPercentage + "%");
            }

            @Override
            public void onComplete(File modelFile) {
                hideProgress();
                updateStatus("Model '" + modelFile.getName() + "' downloaded successfully.");
                Log.i(TAG, "Model " + modelFile.getName() + " downloaded successfully.");
            }

            @Override
            public void onError(String reason) {
                hideProgress();
                updateStatus("Model download error: " + reason);
                Log.e(TAG, "Model download error for " + modelName + ": " + reason);
            }
        });
    }

    private void copyTranscriptionToClipboard() {
        if (tvTranscriptionOutput.getText().toString().isEmpty()) {
            Toast.makeText(this, "Nothing to copy.", Toast.LENGTH_SHORT).show();
            return;
        }
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        ClipData clip = ClipData.newPlainText("transcription", tvTranscriptionOutput.getText().toString());
        if (clipboard != null) {
            clipboard.setPrimaryClip(clip);
            Toast.makeText(this, "Transcription copied to clipboard.", Toast.LENGTH_SHORT).show();
            Log.d(TAG, "Transcription copied to clipboard.");
        } else {
            Toast.makeText(this, "Failed to access clipboard service.", Toast.LENGTH_SHORT).show();
            Log.e(TAG, "ClipboardManager was null.");
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == SELECT_AUDIO_REQUEST_CODE && resultCode == RESULT_OK && data != null) {
            Uri audioFileUri = data.getData();
            if (audioFileUri != null) {
                Log.d(TAG, "Audio file selected: " + audioFileUri.toString());
                // Copy the file to app's cache directory to ensure consistent access and avoid permission issues
                String filePath = copyUriToCache(audioFileUri, "selected_audio_file");

                if (filePath != null) {
                    updateStatus("Transcribing selected file: " + new File(filePath).getName());
                    File modelFile = modelManager.getModelPath(modelName);
                    if (modelFile == null) {
                        updateStatus("Model '" + modelName + "' not downloaded yet! Please download the model first.");
                        hideProgress(); // Ensure progress is hidden if shown before this check
                        Log.w(TAG, "Model file not found for transcription: " + modelName);
                        return;
                    }
                    showProgress("Transcribing selected audio...");
                    tvTranscriptionOutput.setText(""); // Clear previous transcription
                    btnCopyText.setVisibility(View.GONE);
                    TranscriptionService.startTranscription(this, filePath, modelFile.getAbsolutePath(), transcriptionResultReceiver);
                } else {
                    updateStatus("Failed to get path or copy selected audio file.");
                    Log.e(TAG, "Failed to process selected audio file URI: " + audioFileUri);
                }
            } else {
                Log.w(TAG, "Selected audio file URI is null.");
            }
        }
    }

    // Helper method to copy URI content to a local file in app's cache
    // This is more reliable than trying to get a direct file path from content URIs
    private String copyUriToCache(Uri uri, String desiredNameBase) {
        String fileName = null;
        try (InputStream inputStream = getContentResolver().openInputStream(uri)) {
            if (inputStream == null) {
                Log.e(TAG, "Failed to open input stream for URI: " + uri);
                return null;
            }

            // Try to get original file name and extension
            String originalFileName = null;
            String extension = ".tmp";
            try (android.database.Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    int nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    if (nameIndex != -1) {
                        originalFileName = cursor.getString(nameIndex);
                        if (originalFileName != null) {
                            int dotIndex = originalFileName.lastIndexOf('.');
                            if (dotIndex > 0) {
                                extension = originalFileName.substring(dotIndex);
                            }
                        }
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "Could not determine original file name from URI: " + uri, e);
            }

            fileName = desiredNameBase + System.currentTimeMillis() + extension;
            File cacheFile = new File(getExternalCacheDir(), fileName);

            try (OutputStream outputStream = new FileOutputStream(cacheFile)) {
                byte[] buffer = new byte[4 * 1024]; // 4K buffer
                int read;
                while ((read = inputStream.read(buffer)) != -1) {
                    outputStream.write(buffer, 0, read);
                }
                outputStream.flush();
                Log.i(TAG, "Copied URI content to cache file: " + cacheFile.getAbsolutePath());
                return cacheFile.getAbsolutePath();
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to copy URI content to cache: " + uri, e);
            if (fileName != null) { // Attempt to clean up partially written file
                new File(getExternalCacheDir(), fileName).delete();
            }
            return null;
        }
    }


    /**
     * Placeholder for robust path retrieval.
     * For content URIs, directly converting to path is unreliable.
     * Copying to cache (as implemented in copyUriToCache) is a better approach.
     */
    private String getPathFromUri(Uri uri) {
        Log.w(TAG, "getPathFromUri is a placeholder. For reliable access, file should be copied to app's cache.");
        // This method is generally NOT reliable for content URIs, especially from file pickers.
        // It might work for some URIs, but it's better to copy the content to a local cache file.
        // Example:
        // if ("file".equalsIgnoreCase(uri.getScheme())) {
        //    return uri.getPath();
        // }
        // For content URIs, you'd typically copy the file to your app's cache directory.
        // For this placeholder, we'll just log and return null.
        // Consider using the copyUriToCache method instead.
        return copyUriToCache(uri, "temp_from_uri_");
    }

    // UI Helper Methods
    private void showProgress(String message) {
        runOnUiThread(() -> {
            progressBar.setVisibility(View.VISIBLE);
            tvStatus.setText(message);
            Log.d(TAG, "showProgress: " + message);
        });
    }

    private void hideProgress() {
        runOnUiThread(() -> {
            progressBar.setVisibility(View.GONE);
            Log.d(TAG, "hideProgress called.");
        });
    }

    private void updateStatus(String message) {
        runOnUiThread(() -> {
            tvStatus.setText(message);
            Log.d(TAG, "updateStatus: " + message);
        });
    }

    private void displayTranscription(String text) {
        runOnUiThread(() -> {
            tvTranscriptionOutput.setText(text);
            if (text != null && !text.isEmpty()) {
                btnCopyText.setVisibility(View.VISIBLE);
                svTranscription.post(() -> svTranscription.fullScroll(View.FOCUS_DOWN)); // Scroll to bottom
            } else {
                btnCopyText.setVisibility(View.GONE);
            }
            Log.d(TAG, "displayTranscription: text length " + (text != null ? text.length() : 0));
        });
    }

    // ResultReceiver Inner Class
    private static class TranscriptionResultReceiver extends ResultReceiver {
        private final MainActivity activity; // Reference to MainActivity

        public TranscriptionResultReceiver(Handler handler, MainActivity activity) {
            super(handler);
            this.activity = activity; // Be careful with context leaks if not static or handled well
        }

        @Override
        protected void onReceiveResult(int resultCode, Bundle resultData) {
            if (activity == null) {
                Log.e(TAG, "TranscriptionResultReceiver: MainActivity reference is null. Cannot update UI.");
                return;
            }
            activity.hideProgress();
            if (resultCode == TranscriptionService.RESULT_CODE_SUCCESS) {
                String text = resultData.getString(TranscriptionService.EXTRA_TRANSCRIPTION_TEXT);
                activity.updateStatus("Transcription successful!");
                activity.displayTranscription(text);
                Log.i(TAG, "TranscriptionResultReceiver: Success - " + (text != null ? text.substring(0, Math.min(text.length(), 50)) + "..." : "null"));
            } else {
                String error = resultData.getString(TranscriptionService.EXTRA_ERROR_MESSAGE, "Unknown error");
                activity.updateStatus("Transcription Error: " + error);
                activity.displayTranscription(""); // Clear transcription view on error
                Log.e(TAG, "TranscriptionResultReceiver: Error - " + error);
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // Clean up if necessary, e.g., unregister receivers if any were registered here
        // ModelManager's DownloadReceiver is managed by ModelManager itself via its lifecycle methods if cleanup() is called.
        // If modelManager.cleanup() is needed, it should be called e.g. here or when it's certain downloads are no longer needed.
        // For simplicity, assuming ModelManager handles its own receiver lifecycle sufficiently for this scope.
        Log.d(TAG, "MainActivity onDestroy.");
    }
}
