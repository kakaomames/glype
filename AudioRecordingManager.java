import android.media.MediaRecorder;
import java.io.File;
import java.io.IOException;

public class AudioRecordingManager {

    private MediaRecorder mediaRecorder;
    private boolean isRecording = false;

    public boolean startRecording(File outputFile) {
        if (isRecording) {
            // Already recording
            return false;
        }

        mediaRecorder = new MediaRecorder();
        mediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
        mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
        mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
        mediaRecorder.setAudioChannels(1);
        mediaRecorder.setAudioSamplingRate(16000);
        mediaRecorder.setAudioEncodingBitRate(96000);
        mediaRecorder.setOutputFile(outputFile.getAbsolutePath());

        try {
            mediaRecorder.prepare();
            mediaRecorder.start();
            isRecording = true;
            return true;
        } catch (IOException | IllegalStateException e) {
            e.printStackTrace();
            // Clean up if preparation or start fails
            if (mediaRecorder != null) {
                mediaRecorder.reset();
                mediaRecorder.release();
                mediaRecorder = null;
            }
            isRecording = false;
            return false;
        }
    }

    public void stopRecording() {
        if (!isRecording || mediaRecorder == null) {
            // Not recording or recorder not initialized
            return;
        }

        try {
            mediaRecorder.stop();
            mediaRecorder.reset();
            mediaRecorder.release();
        } catch (IllegalStateException e) {
            // This can happen if stop() is called in an invalid state
            e.printStackTrace();
            // Ensure recorder is reset and released even if stop() fails
            if (mediaRecorder != null) {
                mediaRecorder.reset();
                mediaRecorder.release();
            }
        } finally {
            mediaRecorder = null;
            isRecording = false;
        }
    }

    public boolean isRecording() {
        return isRecording;
    }
}
