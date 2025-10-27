package com.example.voskhinditranscriber;

import android.content.Context;
import android.content.res.AssetManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.net.Uri;
import android.util.Log;

import org.json.JSONObject;
import org.vosk.Model;
import org.vosk.Recognizer;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public class VoskTranscriptionService {

    private static final String TAG = "VoskTranscription";
    private static final int SAMPLE_RATE = 16000;
    
    private Context context;
    private Model model;
    private Recognizer recognizer;
    private AudioRecord audioRecord;
    private Thread recognitionThread;
    private boolean isRecording = false;
    private boolean isModelReady = false;
    
    private TranscriptionListener listener;

    public interface TranscriptionListener {
        void onPartialResult(String text);
        void onFinalResult(String text);
        void onError(String error);
        void onModelReady();
    }

    public VoskTranscriptionService(Context context) throws IOException {
        this.context = context;
        initModel();
    }

    private void initModel() {
        new Thread(() -> {
            try {
                // Check if model exists in assets
                AssetManager assetManager = context.getAssets();
                String[] assets = assetManager.list("");
                boolean modelFound = false;
                
                if (assets != null) {
                    for (String asset : assets) {
                        Log.d(TAG, "Found asset: " + asset);
                        if (asset.equals("model-hi")) {
                            modelFound = true;
                            break;
                        }
                    }
                }
                
                if (!modelFound) {
                    String errorMsg = "Model folder 'model-hi' not found in assets.\n\n" +
                                    "Please download Vosk Hindi model from:\n" +
                                    "https://alphacephei.com/vosk/models\n\n" +
                                    "Extract and place it as:\n" +
                                    "app/src/main/assets/model-hi/";
                    Log.e(TAG, errorMsg);
                    if (listener != null) {
                        listener.onError(errorMsg);
                    }
                    return;
                }
                
                // Copy model from assets to internal storage
                File modelDir = new File(context.getFilesDir(), "model-hi");
                
                if (!modelDir.exists()) {
                    Log.d(TAG, "Copying model from assets to " + modelDir.getAbsolutePath());
                    copyAssetFolder(assetManager, "model-hi", modelDir.getAbsolutePath());
                } else {
                    Log.d(TAG, "Model already exists at " + modelDir.getAbsolutePath());
                }
                
                // Load the model
                Log.d(TAG, "Loading model from " + modelDir.getAbsolutePath());
                this.model = new Model(modelDir.getAbsolutePath());
                this.recognizer = new Recognizer(model, SAMPLE_RATE);
                this.isModelReady = true;
                
                Log.d(TAG, "Model initialized successfully");
                if (listener != null) {
                    listener.onModelReady();
                }
                
            } catch (Exception e) {
                String errorMsg = "Failed to load model: " + e.getMessage() + 
                                "\n\nPlease ensure:\n" +
                                "1. Downloaded Vosk Hindi model from:\n   https://alphacephei.com/vosk/models\n" +
                                "2. Extracted and renamed to 'model-hi'\n" +
                                "3. Placed in app/src/main/assets/model-hi/\n" +
                                "4. Contains folders: am/, conf/, graph/";
                Log.e(TAG, errorMsg, e);
                this.isModelReady = false;
                if (listener != null) {
                    listener.onError(errorMsg);
                }
            }
        }).start();
    }
    
    private void copyAssetFolder(AssetManager assetManager, String srcPath, String dstPath) throws IOException {
        String[] assets = assetManager.list(srcPath);
        
        if (assets == null || assets.length == 0) {
            // It's a file, copy it
            copyAssetFile(assetManager, srcPath, dstPath);
        } else {
            // It's a folder, create it and recurse
            File dir = new File(dstPath);
            if (!dir.exists() && !dir.mkdirs()) {
                throw new IOException("Failed to create directory: " + dstPath);
            }
            
            for (String asset : assets) {
                String srcSubPath = srcPath + "/" + asset;
                String dstSubPath = dstPath + "/" + asset;
                copyAssetFolder(assetManager, srcSubPath, dstSubPath);
            }
        }
    }
    
    private void copyAssetFile(AssetManager assetManager, String srcPath, String dstPath) throws IOException {
        InputStream in = null;
        OutputStream out = null;
        
        try {
            in = assetManager.open(srcPath);
            File outFile = new File(dstPath);
            out = new FileOutputStream(outFile);
            
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            
            Log.d(TAG, "Copied: " + srcPath + " -> " + dstPath);
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (IOException e) {
                    Log.e(TAG, "Error closing input stream", e);
                }
            }
            if (out != null) {
                try {
                    out.flush();
                    out.close();
                } catch (IOException e) {
                    Log.e(TAG, "Error closing output stream", e);
                }
            }
        }
    }

    public void setTranscriptionListener(TranscriptionListener listener) {
        this.listener = listener;
    }

    public boolean isModelReady() {
        return isModelReady;
    }

    public String transcribeAudioFile(Uri audioUri) throws IOException {
        if (!isModelReady || model == null) {
            throw new IOException("Model not ready. Please wait for initialization.");
        }

        Log.d(TAG, "Starting transcription of audio file: " + audioUri);
        
        // Create a new recognizer for file transcription
        Recognizer fileRecognizer = new Recognizer(model, SAMPLE_RATE);
        StringBuilder completeText = new StringBuilder();
        
        try (InputStream inputStream = context.getContentResolver().openInputStream(audioUri)) {
            if (inputStream == null) {
                throw new IOException("Cannot open audio file");
            }
            
            // Skip WAV header if present (44 bytes)
            byte[] header = new byte[44];
            inputStream.read(header);
            
            // Check if it's a WAV file
            boolean isWav = header[0] == 'R' && header[1] == 'I' && 
                           header[2] == 'F' && header[3] == 'F';
            
            if (!isWav) {
                // Reset stream if not WAV - just process as raw audio
                inputStream.close();
                return transcribeRawAudio(audioUri, fileRecognizer, completeText);
            }
            
            // Process audio data in chunks
            byte[] buffer = new byte[4096];
            int bytesRead;
            
            while ((bytesRead = inputStream.read(buffer)) != -1) {
                // Convert bytes to shorts (16-bit PCM)
                short[] audioData = new short[bytesRead / 2];
                for (int i = 0; i < audioData.length; i++) {
                    audioData[i] = (short) ((buffer[i * 2 + 1] << 8) | (buffer[i * 2] & 0xFF));
                }
                
                if (fileRecognizer.acceptWaveForm(audioData, audioData.length)) {
                    String result = fileRecognizer.getResult();
                    String text = extractTextFromJson(result);
                    if (!text.isEmpty()) {
                        if (completeText.length() > 0) {
                            completeText.append(" ");
                        }
                        completeText.append(text);
                        Log.d(TAG, "Intermediate result: " + text);
                    }
                }
            }
            
            // Get final result
            String finalResult = fileRecognizer.getFinalResult();
            String finalText = extractTextFromJson(finalResult);
            if (!finalText.isEmpty()) {
                if (completeText.length() > 0) {
                    completeText.append(" ");
                }
                completeText.append(finalText);
            }
            
            Log.d(TAG, "Complete transcription: " + completeText.toString());
            
        } catch (Exception e) {
            Log.e(TAG, "Error transcribing audio file", e);
            throw new IOException("Failed to transcribe audio: " + e.getMessage());
        } finally {
            fileRecognizer.close();
        }
        
        return completeText.toString().trim();
    }

    private String transcribeRawAudio(Uri audioUri, Recognizer fileRecognizer, StringBuilder completeText) throws IOException {
        try (InputStream inputStream = context.getContentResolver().openInputStream(audioUri)) {
            byte[] buffer = new byte[4096];
            int bytesRead;
            
            while ((bytesRead = inputStream.read(buffer)) != -1) {
                short[] audioData = new short[bytesRead / 2];
                for (int i = 0; i < audioData.length; i++) {
                    audioData[i] = (short) ((buffer[i * 2 + 1] << 8) | (buffer[i * 2] & 0xFF));
                }
                
                if (fileRecognizer.acceptWaveForm(audioData, audioData.length)) {
                    String result = fileRecognizer.getResult();
                    String text = extractTextFromJson(result);
                    if (!text.isEmpty()) {
                        if (completeText.length() > 0) {
                            completeText.append(" ");
                        }
                        completeText.append(text);
                    }
                }
            }
            
            String finalResult = fileRecognizer.getFinalResult();
            String finalText = extractTextFromJson(finalResult);
            if (!finalText.isEmpty()) {
                if (completeText.length() > 0) {
                    completeText.append(" ");
                }
                completeText.append(finalText);
            }
        }
        
        return completeText.toString().trim();
    }

    private String extractTextFromJson(String jsonResult) {
        try {
            JSONObject obj = new JSONObject(jsonResult);
            return obj.optString("text", "");
        } catch (Exception e) {
            Log.e(TAG, "Error extracting text from JSON", e);
            return "";
        }
    }

    public void startRecording() {
        if (!isModelReady || recognizer == null) {
            String errorMsg = "Recognizer not initialized. Please wait for model to load.\n\n" +
                            "If this persists, the model may not be installed correctly.\n" +
                            "Download from: https://alphacephei.com/vosk/models";
            if (listener != null) {
                listener.onError(errorMsg);
            }
            Log.e(TAG, errorMsg);
            return;
        }

        if (isRecording) {
            return;
        }

        int bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, 
                AudioFormat.CHANNEL_IN_MONO, 
                AudioFormat.ENCODING_PCM_16BIT);

        audioRecord = new AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize);

        if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
            if (listener != null) {
                listener.onError("Failed to initialize audio recorder. Check microphone permissions.");
            }
            return;
        }

        audioRecord.startRecording();
        isRecording = true;

        recognitionThread = new Thread(() -> {
            short[] buffer = new short[bufferSize];
            
            while (isRecording) {
                int numRead = audioRecord.read(buffer, 0, buffer.length);
                
                if (numRead > 0) {
                    if (recognizer.acceptWaveForm(buffer, numRead)) {
                        String result = recognizer.getResult();
                        processFinalResult(result);
                    } else {
                        String partialResult = recognizer.getPartialResult();
                        processPartialResult(partialResult);
                    }
                }
            }
        });

        recognitionThread.start();
    }

    public void stopRecording() {
        if (!isRecording) {
            return;
        }

        isRecording = false;

        if (recognitionThread != null) {
            try {
                recognitionThread.join(1000);
            } catch (InterruptedException e) {
                Log.e(TAG, "Error stopping recognition thread", e);
            }
        }

        if (audioRecord != null) {
            audioRecord.stop();
            audioRecord.release();
            audioRecord = null;
        }

        if (recognizer != null) {
            String finalResult = recognizer.getFinalResult();
            processFinalResult(finalResult);
        }
    }

    private void processPartialResult(String jsonResult) {
        try {
            JSONObject obj = new JSONObject(jsonResult);
            String text = obj.optString("partial", "");
            
            if (!text.isEmpty() && listener != null) {
                listener.onPartialResult(text);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error processing partial result", e);
        }
    }

    private void processFinalResult(String jsonResult) {
        try {
            JSONObject obj = new JSONObject(jsonResult);
            String text = obj.optString("text", "");
            
            if (!text.isEmpty() && listener != null) {
                listener.onFinalResult(text);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error processing final result", e);
        }
    }

    public void shutdown() {
        stopRecording();
        
        if (recognizer != null) {
            recognizer.close();
            recognizer = null;
        }
        
        if (model != null) {
            model.close();
            model = null;
        }
        
        isModelReady = false;
    }
}
