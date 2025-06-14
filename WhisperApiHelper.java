import android.util.Log;

public class WhisperApiHelper {

    private static final String TAG = "WhisperApiHelper";

    static {
        try {
            System.loadLibrary("whisper-jni");
            Log.i(TAG, "Native library whisper-jni loaded successfully.");
        } catch (UnsatisfiedLinkError e) {
            Log.e(TAG, "Failed to load native library whisper-jni.", e);
            // Depending on the application's needs, this could throw a RuntimeException
            // or set a flag indicating that native functionality is unavailable.
        }
    }

    // Native Method Declarations
    private native long whisper_init_from_file_jni(String modelPath);
    private native void whisper_free_jni(long contextPtr);
    private native int whisper_full_jni(long contextPtr, int numThreads, float[] audioSamples, int numSamples);
    private native int whisper_full_n_segments_jni(long contextPtr);
    private native String whisper_full_get_segment_text_jni(long contextPtr, int segmentIndex);
    private native String whisper_get_system_info_jni(); // Assuming this directly returns String

    /**
     * Represents the whisper.cpp model context.
     * Implements AutoCloseable for try-with-resources management.
     */
    public static class WhisperContext implements AutoCloseable {
        private long contextPtr;
        private boolean released = false;

        private WhisperContext(long contextPtr) {
            if (contextPtr == 0) {
                throw new IllegalArgumentException("WhisperContext cannot be initialized with a null pointer.");
            }
            this.contextPtr = contextPtr;
            Log.d(TAG, "WhisperContext created with pointer: " + contextPtr);
        }

        public long getContextPtr() {
            if (released) {
                throw new IllegalStateException("WhisperContext has already been released.");
            }
            return contextPtr;
        }

        @Override
        public void close() {
            if (!released && contextPtr != 0) {
                Log.d(TAG, "Closing WhisperContext with pointer: " + contextPtr);
                new WhisperApiHelper().whisper_free_jni(contextPtr); // Access outer class method
                contextPtr = 0; // Mark as invalid
                released = true;
                Log.i(TAG, "WhisperContext released.");
            }
        }

        @Override
        protected void finalize() throws Throwable {
            try {
                if (!released && contextPtr != 0) {
                    Log.w(TAG, "WhisperContext with pointer " + contextPtr + " was not explicitly closed. Releasing via finalize().");
                    close();
                }
            } finally {
                super.finalize();
            }
        }
    }

    /**
     * Initializes a whisper.cpp model from the given file path.
     *
     * @param modelPath Path to the whisper model file.
     * @return A WhisperContext instance managing the native context pointer.
     * @throws ModelInitializationException if initialization fails.
     */
    public WhisperContext initModel(String modelPath) {
        if (modelPath == null || modelPath.isEmpty()) {
            throw new IllegalArgumentException("Model path cannot be null or empty.");
        }
        Log.i(TAG, "Initializing model from path: " + modelPath);
        long contextPtr = whisper_init_from_file_jni(modelPath);
        if (contextPtr == 0) {
            Log.e(TAG, "Failed to initialize whisper model from path: " + modelPath + ". JNI returned 0.");
            throw new ModelInitializationException("Failed to initialize whisper model. JNI returned 0.");
        }
        Log.i(TAG, "Model initialized successfully. Context pointer: " + contextPtr);
        return new WhisperContext(contextPtr);
    }

    /**
     * Transcribes audio samples using the provided whisper context.
     *
     * @param context      The WhisperContext holding the model pointer.
     * @param audioSamples Array of float audio samples (e.g., PCM 32-bit float).
     * @return The full transcribed text.
     * @throws IllegalArgumentException if context or audioSamples are null.
     * @throws TranscriptionException   if transcription fails.
     */
    public String transcribe(WhisperContext context, float[] audioSamples) {
        if (context == null) {
            throw new IllegalArgumentException("WhisperContext cannot be null.");
        }
        if (audioSamples == null) {
            throw new IllegalArgumentException("Audio samples array cannot be null.");
        }
        if (context.released) {
            throw new IllegalStateException("WhisperContext has been released and cannot be used for transcription.");
        }

        int numThreads = Math.max(1, Runtime.getRuntime().availableProcessors() / 2);
        // Consider making numThreads configurable or using whisper.cpp defaults if appropriate
        Log.i(TAG, "Starting transcription with " + numThreads + " threads for " + audioSamples.length + " samples.");

        int result = whisper_full_jni(context.getContextPtr(), numThreads, audioSamples, audioSamples.length);
        if (result != 0) {
            Log.e(TAG, "Transcription failed. whisper_full_jni returned error code: " + result);
            throw new TranscriptionException("Transcription failed. Error code: " + result);
        }

        Log.d(TAG, "Transcription JNI call successful. Fetching segments.");
        int numSegments = whisper_full_n_segments_jni(context.getContextPtr());
        if (numSegments < 0) {
            // This case should ideally not happen if whisper_full_jni succeeded.
            // It might indicate an issue with the JNI layer or underlying whisper.cpp state.
            Log.e(TAG, "Failed to get number of segments. whisper_full_n_segments_jni returned: " + numSegments);
            throw new TranscriptionException("Failed to get number of segments after transcription. Error code: " + numSegments);
        }

        Log.d(TAG, "Number of segments: " + numSegments);
        StringBuilder fullText = new StringBuilder();
        for (int i = 0; i < numSegments; i++) {
            String segmentText = whisper_full_get_segment_text_jni(context.getContextPtr(), i);
            if (segmentText == null) {
                // This could happen if JNI returns null for a segment
                Log.w(TAG, "Received null text for segment " + i + ". Skipping.");
                continue;
            }
            fullText.append(segmentText);
        }

        Log.i(TAG, "Transcription completed successfully.");
        return fullText.toString();
    }

    /**
     * Retrieves system information from the whisper.cpp library.
     *
     * @return A string containing system information.
     */
    public String getSystemInfo() {
        Log.i(TAG, "Requesting system info from native library.");
        try {
            String systemInfo = whisper_get_system_info_jni();
            if (systemInfo == null) {
                Log.w(TAG, "whisper_get_system_info_jni returned null.");
                return "System info not available (JNI returned null).";
            }
            Log.d(TAG, "System info retrieved: " + systemInfo);
            return systemInfo;
        } catch (UnsatisfiedLinkError e) {
            Log.e(TAG, "Failed to call whisper_get_system_info_jni due to UnsatisfiedLinkError. Library not loaded properly?", e);
            throw e; // Re-throw to indicate a critical issue
        } catch (Exception e) {
            Log.e(TAG, "Exception while calling whisper_get_system_info_jni.", e);
            return "Failed to get system info: " + e.getMessage();
        }
    }

    // Custom Exception Classes
    public static class ModelInitializationException extends RuntimeException {
        public ModelInitializationException(String message) {
            super(message);
        }
        public ModelInitializationException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public static class TranscriptionException extends RuntimeException {
        public TranscriptionException(String message) {
            super(message);
        }
        public TranscriptionException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
