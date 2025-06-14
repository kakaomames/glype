import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.IBinder;
import android.os.ResultReceiver;
import android.util.Log;

import androidx.annotation.Nullable;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class TranscriptionService extends Service {

    private static final String TAG = "TranscriptionService";

    public static final String ACTION_TRANSCRIBE = "com.example.ACTION_TRANSCRIBE";
    public static final String EXTRA_AUDIO_FILE_PATH = "com.example.EXTRA_AUDIO_FILE_PATH";
    public static final String EXTRA_MODEL_PATH = "com.example.EXTRA_MODEL_PATH";
    public static final String EXTRA_RESULT_RECEIVER = "com.example.EXTRA_RESULT_RECEIVER";

    public static final int RESULT_CODE_SUCCESS = 1;
    public static final int RESULT_CODE_ERROR = 0;
    public static final String EXTRA_TRANSCRIPTION_TEXT = "com.example.EXTRA_TRANSCRIPTION_TEXT";
    public static final String EXTRA_ERROR_MESSAGE = "com.example.EXTRA_ERROR_MESSAGE";

    private WhisperApiHelper whisperApiHelper;
    private ExecutorService executorService;

    @Override
    public void onCreate() {
        super.onCreate();
        whisperApiHelper = new WhisperApiHelper(); // Assuming WhisperApiHelper has a default constructor
        executorService = Executors.newSingleThreadExecutor();
        Log.i(TAG, "Service created. WhisperApiHelper and ExecutorService initialized.");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || !ACTION_TRANSCRIBE.equals(intent.getAction())) {
            Log.w(TAG, "Received null intent or unknown action. Stopping service if no tasks.");
            // If there are no active tasks, and this was a spurious start, stop.
            // However, with START_REDELIVER_INTENT, this might be a redelivered intent.
            // For simplicity, let's assume valid intents are always provided.
            // stopSelf(startId); // Be cautious with this here with START_REDELIVER_INTENT
            return START_NOT_STICKY; // Or START_REDELIVER_INTENT depending on desired behavior for malformed intents
        }

        String audioFilePath = intent.getStringExtra(EXTRA_AUDIO_FILE_PATH);
        String modelPath = intent.getStringExtra(EXTRA_MODEL_PATH);
        ResultReceiver resultReceiver = intent.getParcelableExtra(EXTRA_RESULT_RECEIVER);

        if (audioFilePath == null || modelPath == null || resultReceiver == null) {
            Log.e(TAG, "Missing required extras: audioFilePath, modelPath, or ResultReceiver.");
            if (resultReceiver != null) {
                Bundle bundle = new Bundle();
                bundle.putString(EXTRA_ERROR_MESSAGE, "Missing required parameters in intent.");
                resultReceiver.send(RESULT_CODE_ERROR, bundle);
            }
            // stopSelf(startId); // Consider if service should stop on bad parameters
            return START_NOT_STICKY;
        }

        Log.i(TAG, "Received transcription request for: " + audioFilePath + " with model: " + modelPath);
        TranscriptionTask task = new TranscriptionTask(audioFilePath, modelPath, resultReceiver, whisperApiHelper, startId);
        executorService.submit(task);

        return START_REDELIVER_INTENT; // If process dies, redeliver intent
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.i(TAG, "Service destroying. Shutting down ExecutorService.");
        if (executorService != null) {
            executorService.shutdown();
            try {
                if (!executorService.awaitTermination(5, TimeUnit.SECONDS)) {
                    executorService.shutdownNow();
                    Log.w(TAG, "ExecutorService did not terminate gracefully. Forced shutdown.");
                }
            } catch (InterruptedException e) {
                Log.e(TAG, "Interrupted while waiting for executor service to terminate.", e);
                executorService.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        // If WhisperApiHelper holds any global resources that need explicit release
        // (beyond individual WhisperContext instances), clean them up here.
        // For now, assuming WhisperContext.close() is sufficient.
        Log.i(TAG, "Service destroyed.");
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        // This service is not designed for binding.
        return null;
    }

    private class TranscriptionTask implements Runnable {
        private final String audioFilePath;
        private final String modelPath;
        private final ResultReceiver resultReceiver;
        private final WhisperApiHelper localWhisperApiHelper;
        private final int serviceStartId; // To allow task to call stopSelf(startId)

        TranscriptionTask(String audioFilePath, String modelPath, ResultReceiver resultReceiver,
                          WhisperApiHelper whisperApiHelperInstance, int startId) {
            this.audioFilePath = audioFilePath;
            this.modelPath = modelPath;
            this.resultReceiver = resultReceiver;
            this.localWhisperApiHelper = whisperApiHelperInstance;
            this.serviceStartId = startId;
        }

        @Override
        public void run() {
            Log.d(TAG, "Transcription task started for: " + audioFilePath);
            Bundle resultBundle = new Bundle();
            try {
                // 1. Audio Processing (Simulated)
                Log.i(TAG, "Audio processing would happen here for: " + audioFilePath);
                // Simulate 1 second of 16kHz mono audio
                float[] audioSamples = new float[16000];
                // In a real scenario, this would involve reading and decoding the audio file.
                // For example:
                // AudioProcessor audioProcessor = new AudioProcessor();
                // audioSamples = audioProcessor.decodeAudioFile(audioFilePath);

                // 2. Transcription Logic
                // Using try-with-resources for WhisperContext
                try (WhisperApiHelper.WhisperContext context = localWhisperApiHelper.initModel(modelPath)) {
                    if (context == null) { // Should be handled by initModel throwing an exception
                        throw new WhisperApiHelper.ModelInitializationException("initModel returned null context unexpectedly.");
                    }
                    Log.d(TAG, "Model initialized successfully for: " + modelPath);
                    String transcription = localWhisperApiHelper.transcribe(context, audioSamples);
                    Log.i(TAG, "Transcription successful for: " + audioFilePath);

                    resultBundle.putString(EXTRA_TRANSCRIPTION_TEXT, transcription);
                    resultReceiver.send(RESULT_CODE_SUCCESS, resultBundle);
                }
                // WhisperContext.close() is automatically called here
            } catch (WhisperApiHelper.ModelInitializationException e) {
                Log.e(TAG, "Model initialization failed for " + modelPath, e);
                resultBundle.putString(EXTRA_ERROR_MESSAGE, "Model initialization failed: " + e.getMessage());
                resultReceiver.send(RESULT_CODE_ERROR, resultBundle);
            } catch (WhisperApiHelper.TranscriptionException e) {
                Log.e(TAG, "Transcription failed for " + audioFilePath, e);
                resultBundle.putString(EXTRA_ERROR_MESSAGE, "Transcription failed: " + e.getMessage());
                resultReceiver.send(RESULT_CODE_ERROR, resultBundle);
            } catch (UnsatisfiedLinkError e) {
                Log.e(TAG, "Native library link error during transcription task.", e);
                resultBundle.putString(EXTRA_ERROR_MESSAGE, "Native library error: " + e.getMessage());
                resultReceiver.send(RESULT_CODE_ERROR, resultBundle);
            }
            catch (Exception e) { // Catch-all for other unexpected errors
                Log.e(TAG, "An unexpected error occurred during transcription for " + audioFilePath, e);
                resultBundle.putString(EXTRA_ERROR_MESSAGE, "An unexpected error occurred: " + e.getMessage());
                resultReceiver.send(RESULT_CODE_ERROR, resultBundle);
            } finally {
                Log.d(TAG, "Transcription task finished for: " + audioFilePath);
                // Decide if service should stop. If using a single thread executor,
                // and tasks are meant to be processed sequentially, stopping after each one might be desired
                // if the queue is typically empty. Otherwise, let the service run until explicitly stopped
                // or until Android stops it.
                // For START_REDELIVER_INTENT, the service will restart if killed, and the intent will be redelivered.
                // If no more intents are coming, it will eventually stop.
                // stopSelf(serviceStartId); // Use this if each task should try to stop the service.
            }
        }
    }

    /**
     * Helper method to easily start the TranscriptionService.
     *
     * @param context        The context to use for starting the service.
     * @param audioFilePath  Path to the audio file to be transcribed.
     * @param modelPath      Path to the whisper model file.
     * @param resultReceiver A ResultReceiver to get the transcription result or error.
     */
    public static void startTranscription(Context context, String audioFilePath, String modelPath, ResultReceiver resultReceiver) {
        Intent intent = new Intent(context, TranscriptionService.class);
        intent.setAction(ACTION_TRANSCRIBE);
        intent.putExtra(EXTRA_AUDIO_FILE_PATH, audioFilePath);
        intent.putExtra(EXTRA_MODEL_PATH, modelPath);
        intent.putExtra(EXTRA_RESULT_RECEIVER, resultReceiver);
        context.startService(intent);
        Log.d(TAG, "Sent intent to start TranscriptionService for: " + audioFilePath);
    }
}
