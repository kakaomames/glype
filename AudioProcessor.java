import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.util.Log;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;
import java.util.Arrays;

public class AudioProcessor {

    private static final String TAG = "AudioProcessor";
    private static final int TARGET_SAMPLE_RATE = 16000;
    private static final int TARGET_CHANNELS = 1; // Mono
    private static final int TARGET_BITS_PER_SAMPLE = 16;
    private static final int TIMEOUT_US = 10000; // 10ms

    public boolean processAudioToWav(File inputFile, File outputFile) {
        if (inputFile == null || !inputFile.exists() || outputFile == null) {
            Log.e(TAG, "Invalid input or output file.");
            return false;
        }

        MediaExtractor extractor = null;
        MediaCodec decoder = null;
        FileOutputStream fos = null;
        DataOutputStream dos = null;

        try {
            extractor = new MediaExtractor();
            extractor.setDataSource(inputFile.getAbsolutePath());

            int trackIndex = selectAudioTrack(extractor);
            if (trackIndex < 0) {
                Log.e(TAG, "No audio track found in " + inputFile.getName());
                return false;
            }
            extractor.selectTrack(trackIndex);
            MediaFormat inputFormat = extractor.getTrackFormat(trackIndex);
            String mime = inputFormat.getString(MediaFormat.KEY_MIME);
            if (mime == null) {
                Log.e(TAG, "MIME type is null for audio track.");
                return false;
            }

            Log.d(TAG, "Input format: " + inputFormat);
            int sourceSampleRate = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE);
            int sourceChannels = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT);

            decoder = MediaCodec.createDecoderByType(mime);
            // We request PCM 16 bit output from the decoder directly.
            // The MediaCodec might be able to do some conversions for us.
            // inputFormat.setString(MediaFormat.KEY_MIME, "audio/raw"); // This is for ENCODING, not DECODING
            // For decoding, we provide the encoded format and expect PCM.
            decoder.configure(inputFormat, null, null, 0);
            decoder.start();

            ByteArrayOutputStream pcmOutputStream = new ByteArrayOutputStream();
            ByteBuffer[] inputBuffers = decoder.getInputBuffers();
            ByteBuffer[] outputBuffers = decoder.getOutputBuffers();
            MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
            boolean sawInputEOS = false;
            boolean sawOutputEOS = false;

            while (!sawOutputEOS) {
                if (!sawInputEOS) {
                    int inputBufIndex = decoder.dequeueInputBuffer(TIMEOUT_US);
                    if (inputBufIndex >= 0) {
                        ByteBuffer inputBuf = inputBuffers[inputBufIndex];
                        int sampleSize = extractor.readSampleData(inputBuf, 0);
                        if (sampleSize < 0) {
                            decoder.queueInputBuffer(inputBufIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            sawInputEOS = true;
                            Log.d(TAG, "Input EOS reached.");
                        } else {
                            decoder.queueInputBuffer(inputBufIndex, 0, sampleSize, extractor.getSampleTime(), 0);
                            extractor.advance();
                        }
                    }
                }

                int outputBufIndex = decoder.dequeueOutputBuffer(bufferInfo, TIMEOUT_US);
                if (outputBufIndex >= 0) {
                    ByteBuffer outputBuf = outputBuffers[outputBufIndex];
                    byte[] pcmChunk = new byte[bufferInfo.size];
                    outputBuf.get(pcmChunk);
                    outputBuf.clear();
                    pcmOutputStream.write(pcmChunk);
                    decoder.releaseOutputBuffer(outputBufIndex, false);

                    if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        sawOutputEOS = true;
                        Log.d(TAG, "Output EOS reached.");
                    }
                } else if (outputBufIndex == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                    outputBuffers = decoder.getOutputBuffers();
                    Log.d(TAG, "Output buffers changed.");
                } else if (outputBufIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat newFormat = decoder.getOutputFormat();
                    Log.d(TAG, "Decoder output format changed: " + newFormat);
                    // The newFormat might give us the actual sample rate and channel count
                    // if MediaCodec performed a conversion.
                    sourceSampleRate = newFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE) ? newFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE) : sourceSampleRate;
                    sourceChannels = newFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT) ? newFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT) : sourceChannels;
                }
            }

            byte[] decodedPcmData = pcmOutputStream.toByteArray();
            Log.d(TAG, "Decoded PCM data length: " + decodedPcmData.length + ", Original SR: " + sourceSampleRate + ", Original CH: " + sourceChannels);

            // Convert to short array for processing
            ShortBuffer shortBuffer = ByteBuffer.wrap(decodedPcmData).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer();
            short[] pcmShorts = new short[shortBuffer.remaining()];
            shortBuffer.get(pcmShorts);

            // Downmix and Resample
            short[] processedPcm = processPcm(pcmShorts, sourceSampleRate, sourceChannels, TARGET_SAMPLE_RATE, TARGET_CHANNELS);
            Log.d(TAG, "Processed PCM data length (shorts): " + processedPcm.length);


            // Write to WAV file
            fos = new FileOutputStream(outputFile);
            dos = new DataOutputStream(fos);
            writeWavHeader(dos, processedPcm.length, TARGET_SAMPLE_RATE, TARGET_CHANNELS, TARGET_BITS_PER_SAMPLE);

            // Convert short array back to byte array for writing
            ByteBuffer byteBuf = ByteBuffer.allocate(processedPcm.length * 2); // 2 bytes per short
            byteBuf.order(ByteOrder.LITTLE_ENDIAN);
            for (short s : processedPcm) {
                byteBuf.putShort(s);
            }
            dos.write(byteBuf.array());
            Log.i(TAG, "Successfully processed and wrote WAV to " + outputFile.getAbsolutePath());
            return true;

        } catch (IOException | IllegalStateException | IllegalArgumentException e) {
            Log.e(TAG, "Error processing audio to WAV: " + e.getMessage(), e);
            return false;
        } finally {
            if (extractor != null) {
                extractor.release();
            }
            if (decoder != null) {
                try {
                    decoder.stop();
                } catch (IllegalStateException e) {
                    Log.e(TAG, "Error stopping decoder", e);
                }
                decoder.release();
            }
            try {
                if (dos != null) dos.close(); // fos will be closed by this
                else if (fos != null) fos.close();
            } catch (IOException e) {
                Log.e(TAG, "Error closing output streams: " + e.getMessage(), e);
            }
            if (!outputFile.exists() || outputFile.length() == 0) {
                 outputFile.delete(); // Clean up empty/failed output file
            } else if (outputFile.length() < 44 && outputFile.exists()){ // if only header or partial header written
                Log.w(TAG, "Output file is likely corrupt (too small), deleting: " + outputFile.getAbsolutePath());
                outputFile.delete();
            }
        }
    }

    private int selectAudioTrack(MediaExtractor extractor) {
        int numTracks = extractor.getTrackCount();
        for (int i = 0; i < numTracks; i++) {
            MediaFormat format = extractor.getTrackFormat(i);
            String mime = format.getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith("audio/")) {
                return i;
            }
        }
        return -1;
    }

    private short[] processPcm(short[] inputPcm, int inputRate, int inputChannels, int targetRate, int targetChannels) {
        // 1. Downmix to Mono if necessary
        short[] monoPcm;
        if (inputChannels == 1) {
            monoPcm = inputPcm;
        } else if (inputChannels == 2) {
            monoPcm = new short[inputPcm.length / 2];
            for (int i = 0; i < monoPcm.length; i++) {
                monoPcm[i] = (short) ((inputPcm[2 * i] + inputPcm[2 * i + 1]) / 2);
            }
            Log.d(TAG, "Downmixed stereo to mono. Original samples: " + inputPcm.length + ", Mono samples: " + monoPcm.length);
        } else {
            // For more than 2 channels, just take the first channel for simplicity
            Log.w(TAG, "Input has " + inputChannels + " channels. Taking first channel only.");
            monoPcm = new short[inputPcm.length / inputChannels];
            for (int i = 0; i < monoPcm.length; i++) {
                monoPcm[i] = inputPcm[i * inputChannels];
            }
        }

        // 2. Resample if necessary
        if (inputRate == targetRate) {
            Log.d(TAG, "Sample rate matches target. No resampling needed.");
            return monoPcm;
        }
        if (inputRate == 0) { // Should not happen if MediaFormat is correct
            Log.e(TAG, "Input sample rate is 0. Cannot resample.");
            return monoPcm; // Return as is, likely will fail later
        }

        Log.d(TAG, "Resampling from " + inputRate + " Hz to " + targetRate + " Hz.");
        // Simple linear interpolation
        int outputLength = (int) ((long) monoPcm.length * targetRate / inputRate);
        if (outputLength == 0 && monoPcm.length > 0) {
             Log.w(TAG, "Resampling resulted in 0 output samples for non-zero input. Input SR: " + inputRate + " Target SR: " + targetRate + " Input length: " + monoPcm.length );
             // Avoid division by zero or tiny output array if targetRate is much smaller or inputRate is huge.
             // This might happen with malformed files or extreme sample rates.
             // Return original monoPcm to avoid crashes, though it's not correctly resampled.
             return monoPcm;
        }
        if (outputLength == 0 && monoPcm.length == 0) {
            return new short[0]; // Empty input yields empty output
        }

        short[] outputPcm = new short[outputLength];
        double ratio = (double) (monoPcm.length -1) / (outputLength -1) ; // Ensure we don't go out of bounds for last sample

        if (monoPcm.length == 0) return outputPcm; // Handle empty input after downmixing.
        if (outputLength == 0 && monoPcm.length > 0) return monoPcm; // Should be caught above, but defensive.
        if (monoPcm.length == 1 && outputLength > 0) { // Handle single input sample
            Arrays.fill(outputPcm, monoPcm[0]);
            return outputPcm;
        }
         if (outputLength == 1 && monoPcm.length > 0) { // Handle single output sample
            outputPcm[0] = monoPcm[0];
            return outputPcm;
        }


        for (int i = 0; i < outputLength; i++) {
            double srcIndexDouble = i * ratio;
            int srcIndex1 = (int) srcIndexDouble;
            int srcIndex2 = Math.min(srcIndex1 + 1, monoPcm.length - 1); // Ensure srcIndex2 is within bounds
            double frac = srcIndexDouble - srcIndex1;

            short sample1 = monoPcm[srcIndex1];
            short sample2 = monoPcm[srcIndex2];

            outputPcm[i] = (short) (sample1 + (sample2 - sample1) * frac);
        }
        Log.d(TAG, "Resampled. Output samples: " + outputPcm.length);
        return outputPcm;
    }


    private void writeWavHeader(DataOutputStream out, long numSamples, int sampleRate, int numChannels, int bitsPerSample) throws IOException {
        long totalAudioLen = numSamples * numChannels * bitsPerSample / 8;
        long totalDataLen = totalAudioLen + 36; // 36 is header size without "RIFF" and chunk size
        long byteRate = (long) sampleRate * numChannels * bitsPerSample / 8;
        int blockAlign = numChannels * bitsPerSample / 8;

        byte[] header = new byte[44];

        header[0] = 'R';  // RIFF/WAVE header
        header[1] = 'I';
        header[2] = 'F';
        header[3] = 'F';
        header[4] = (byte) (totalDataLen & 0xff);
        header[5] = (byte) ((totalDataLen >> 8) & 0xff);
        header[6] = (byte) ((totalDataLen >> 16) & 0xff);
        header[7] = (byte) ((totalDataLen >> 24) & 0xff);
        header[8] = 'W';
        header[9] = 'A';
        header[10] = 'V';
        header[11] = 'E';
        header[12] = 'f';  // 'fmt ' chunk
        header[13] = 'm';
        header[14] = 't';
        header[15] = ' ';
        header[16] = 16;  // 4 bytes: size of 'fmt ' chunk (16 for PCM)
        header[17] = 0;
        header[18] = 0;
        header[19] = 0;
        header[20] = 1;  // format = 1 (PCM)
        header[21] = 0;
        header[22] = (byte) numChannels;
        header[23] = 0;
        header[24] = (byte) (sampleRate & 0xff);
        header[25] = (byte) ((sampleRate >> 8) & 0xff);
        header[26] = (byte) ((sampleRate >> 16) & 0xff);
        header[27] = (byte) ((sampleRate >> 24) & 0xff);
        header[28] = (byte) (byteRate & 0xff);
        header[29] = (byte) ((byteRate >> 8) & 0xff);
        header[30] = (byte) ((byteRate >> 16) & 0xff);
        header[31] = (byte) ((byteRate >> 24) & 0xff);
        header[32] = (byte) blockAlign;
        header[33] = 0;
        header[34] = (byte) bitsPerSample;
        header[35] = 0;
        header[36] = 'd';
        header[37] = 'a';
        header[38] = 't';
        header[39] = 'a';
        header[40] = (byte) (totalAudioLen & 0xff);
        header[41] = (byte) ((totalAudioLen >> 8) & 0xff);
        header[42] = (byte) ((totalAudioLen >> 16) & 0xff);
        header[43] = (byte) ((totalAudioLen >> 24) & 0xff);

        out.write(header, 0, 44);
        Log.d(TAG, "WAV header written. Total Audio Length: " + totalAudioLen + ", Num Samples: " + numSamples);
    }

    public float[] getFloatArrayFromWav(File wavFile) {
        if (wavFile == null || !wavFile.exists()) {
            Log.e(TAG, "WAV file not found: " + (wavFile != null ? wavFile.getAbsolutePath() : "null"));
            return null;
        }
        Log.d(TAG, "Reading WAV file: " + wavFile.getAbsolutePath());

        try (FileInputStream fis = new FileInputStream(wavFile);
             BufferedInputStream bis = new BufferedInputStream(fis)) {

            // Skip WAV header (44 bytes for standard PCM)
            // A more robust parser would read and verify format here.
            // For this task, we assume the input WAV is correctly formatted (16kHz, mono, 16-bit PCM)
            // by processAudioToWav or external means.
            byte[] headerSkip = new byte[44];
            int read = bis.read(headerSkip);
            if (read < 44) {
                Log.e(TAG, "Failed to read WAV header (expected 44 bytes, got " + read + ") from " + wavFile.getName());
                return null;
            }

            // Verify some header fields if possible (optional, but good for robustness)
            // Example: Check sample rate at byte 24, channels at byte 22
            // int headerSampleRate = ((headerSkip[25] & 0xFF) << 8) | (headerSkip[24] & 0xFF); // Little endian for short
            // if (headerSampleRate != TARGET_SAMPLE_RATE) Log.w(TAG, "Warning: WAV SR mismatch.");


            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buffer = new byte[1024 * 4]; // 4KB buffer
            int bytesRead;
            while ((bytesRead = bis.read(buffer)) != -1) {
                baos.write(buffer, 0, bytesRead);
            }
            byte[] pcmBytes = baos.toByteArray();

            if (pcmBytes.length == 0) {
                Log.w(TAG, "WAV file contains no PCM data after header: " + wavFile.getName());
                return new float[0];
            }
            if (pcmBytes.length % 2 != 0) {
                Log.w(TAG, "PCM data length is not even, potentially corrupt 16-bit WAV: " + wavFile.getName());
                // Trim the last byte if odd, or handle error differently
                pcmBytes = Arrays.copyOf(pcmBytes, pcmBytes.length - (pcmBytes.length % 2));
            }


            ShortBuffer shortBuffer = ByteBuffer.wrap(pcmBytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer();
            short[] shortSamples = new short[shortBuffer.remaining()];
            shortBuffer.get(shortSamples);

            float[] floatSamples = new float[shortSamples.length];
            for (int i = 0; i < shortSamples.length; i++) {
                floatSamples[i] = shortSamples[i] / 32768.0f; // Normalize to [-1.0, 1.0]
            }

            Log.i(TAG, "Successfully read " + floatSamples.length + " float samples from " + wavFile.getName());
            return floatSamples;

        } catch (IOException e) {
            Log.e(TAG, "Error reading WAV file " + wavFile.getName() + ": " + e.getMessage(), e);
            return null;
        }
    }
}
