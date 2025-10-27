package com.example.voskhinditranscriber;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.android.material.card.MaterialCardView;
import com.google.android.material.floatingactionbutton.FloatingActionButton;

import java.io.File;

public class MainActivity extends AppCompatActivity {

    private static final int PERMISSIONS_REQUEST_RECORD_AUDIO = 1;
    
    private FloatingActionButton recordButton;
    private Button uploadButton;
    private Button clearButton;
    private TextView transcriptionTextView;
    private TextView statusTextView;
    private ProgressBar progressBar;
    private MaterialCardView transcriptionCard;
    
    private VoskTranscriptionService transcriptionService;
    private boolean isRecording = false;
    private StringBuilder completeTranscription = new StringBuilder();
    
    private ActivityResultLauncher<String> audioPickerLauncher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        recordButton = findViewById(R.id.recordButton);
        uploadButton = findViewById(R.id.uploadButton);
        clearButton = findViewById(R.id.clearButton);
        transcriptionTextView = findViewById(R.id.transcriptionTextView);
        statusTextView = findViewById(R.id.statusTextView);
        progressBar = findViewById(R.id.progressBar);
        transcriptionCard = findViewById(R.id.transcriptionCard);

        // Request audio permission
        int permissionCheck = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO);
        if (permissionCheck != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.RECORD_AUDIO}, PERMISSIONS_REQUEST_RECORD_AUDIO);
        }

        // Setup audio picker
        audioPickerLauncher = registerForActivityResult(
            new ActivityResultContracts.GetContent(),
            uri -> {
                if (uri != null) {
                    transcribeAudioFile(uri);
                }
            }
        );

        recordButton.setOnClickListener(v -> toggleRecording());
        uploadButton.setOnClickListener(v -> selectAudioFile());
        clearButton.setOnClickListener(v -> clearTranscription());

        // Initialize transcription service
        initializeTranscriptionService();
    }

    private void initializeTranscriptionService() {
        statusTextView.setText("Initializing Vosk model...");
        progressBar.setVisibility(View.VISIBLE);
        
        new Thread(() -> {
            try {
                transcriptionService = new VoskTranscriptionService(this);
                transcriptionService.setTranscriptionListener(new VoskTranscriptionService.TranscriptionListener() {
                    @Override
                    public void onPartialResult(String text) {
                        runOnUiThread(() -> {
                            statusTextView.setText("🎤 Listening...");
                            // Show partial result in a lighter color or italic style
                            transcriptionTextView.setText(completeTranscription.toString() + "\n" + text);
                        });
                    }

                    @Override
                    public void onFinalResult(String text) {
                        runOnUiThread(() -> {
                            if (!text.isEmpty()) {
                                if (completeTranscription.length() > 0) {
                                    completeTranscription.append(" ");
                                }
                                completeTranscription.append(text);
                                transcriptionTextView.setText(completeTranscription.toString());
                                transcriptionCard.setVisibility(View.VISIBLE);
                            }
                        });
                    }

                    @Override
                    public void onError(String error) {
                        runOnUiThread(() -> {
                            statusTextView.setText("❌ Error occurred");
                            progressBar.setVisibility(View.GONE);
                            Toast.makeText(MainActivity.this, error, Toast.LENGTH_LONG).show();
                            isRecording = false;
                            updateRecordButton(false);
                        });
                    }

                    @Override
                    public void onModelReady() {
                        runOnUiThread(() -> {
                            statusTextView.setText("✅ Ready to record or upload audio");
                            progressBar.setVisibility(View.GONE);
                            recordButton.setEnabled(true);
                            uploadButton.setEnabled(true);
                            Toast.makeText(MainActivity.this, "Model loaded successfully!", Toast.LENGTH_SHORT).show();
                        });
                    }
                });

                runOnUiThread(() -> {
                    statusTextView.setText("✅ Ready to record or upload audio");
                    progressBar.setVisibility(View.GONE);
                    recordButton.setEnabled(true);
                    uploadButton.setEnabled(true);
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    String errorMsg = "Failed to initialize Vosk\n\n" +
                                    "Please ensure:\n" +
                                    "1. Download Hindi model from:\n   https://alphacephei.com/vosk/models\n" +
                                    "2. Extract and rename to 'model-hi'\n" +
                                    "3. Place in app/src/main/assets/model-hi/\n\n" +
                                    "Error: " + e.getMessage();
                    statusTextView.setText("❌ Failed to initialize");
                    progressBar.setVisibility(View.GONE);
                    Toast.makeText(MainActivity.this, errorMsg, Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }

    private void selectAudioFile() {
        audioPickerLauncher.launch("audio/*");
    }

    private void transcribeAudioFile(Uri audioUri) {
        if (transcriptionService == null || !transcriptionService.isModelReady()) {
            Toast.makeText(this, "Please wait for model to load", Toast.LENGTH_SHORT).show();
            return;
        }

        statusTextView.setText("📂 Processing audio file...");
        progressBar.setVisibility(View.VISIBLE);
        uploadButton.setEnabled(false);
        recordButton.setEnabled(false);

        new Thread(() -> {
            try {
                String result = transcriptionService.transcribeAudioFile(audioUri);
                runOnUiThread(() -> {
                    if (!result.isEmpty()) {
                        completeTranscription.append(result);
                        transcriptionTextView.setText(completeTranscription.toString());
                        transcriptionCard.setVisibility(View.VISIBLE);
                        statusTextView.setText("✅ Transcription complete");
                        Toast.makeText(MainActivity.this, "Transcription complete!", Toast.LENGTH_SHORT).show();
                    } else {
                        statusTextView.setText("⚠️ No speech detected");
                        Toast.makeText(MainActivity.this, "No speech detected in audio", Toast.LENGTH_SHORT).show();
                    }
                    progressBar.setVisibility(View.GONE);
                    uploadButton.setEnabled(true);
                    recordButton.setEnabled(true);
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    statusTextView.setText("❌ Failed to transcribe");
                    progressBar.setVisibility(View.GONE);
                    uploadButton.setEnabled(true);
                    recordButton.setEnabled(true);
                    Toast.makeText(MainActivity.this, "Error: " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }

    private void clearTranscription() {
        completeTranscription.setLength(0);
        transcriptionTextView.setText("");
        transcriptionCard.setVisibility(View.GONE);
        statusTextView.setText("✅ Ready to record or upload audio");
        Toast.makeText(this, "Transcription cleared", Toast.LENGTH_SHORT).show();
    }

    private void updateRecordButton(boolean recording) {
        if (recording) {
            recordButton.setImageResource(android.R.drawable.ic_media_pause);
            recordButton.setBackgroundTintList(getResources().getColorStateList(android.R.color.holo_red_light, null));
        } else {
            recordButton.setImageResource(android.R.drawable.ic_btn_speak_now);
            recordButton.setBackgroundTintList(getResources().getColorStateList(android.R.color.holo_blue_dark, null));
        }
    }

    private void toggleRecording() {
        if (transcriptionService == null) {
            Toast.makeText(this, "Transcription service not initialized", Toast.LENGTH_SHORT).show();
            return;
        }

        if (isRecording) {
            stopRecording();
        } else {
            startRecording();
        }
    }

    private void startRecording() {
        int permissionCheck = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO);
        if (permissionCheck != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.RECORD_AUDIO}, PERMISSIONS_REQUEST_RECORD_AUDIO);
            return;
        }

        transcriptionService.startRecording();
        isRecording = true;
        updateRecordButton(true);
        uploadButton.setEnabled(false);
        statusTextView.setText("🎤 Recording...");
        transcriptionCard.setVisibility(View.VISIBLE);
    }

    private void stopRecording() {
        transcriptionService.stopRecording();
        isRecording = false;
        updateRecordButton(false);
        uploadButton.setEnabled(true);
        statusTextView.setText("✅ Recording stopped");
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        
        if (requestCode == PERMISSIONS_REQUEST_RECORD_AUDIO) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Audio permission granted", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "Audio permission is required for transcription", Toast.LENGTH_LONG).show();
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (transcriptionService != null) {
            transcriptionService.shutdown();
        }
    }
}
