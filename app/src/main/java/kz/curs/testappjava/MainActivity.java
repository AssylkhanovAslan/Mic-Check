package kz.curs.testappjava;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaMetadataRetriever;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Timer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import kz.curs.testappjava.databinding.ActivityMainBinding;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = MainActivity.class.getSimpleName();

    private static final int RECORDING_TIME = 5000;
    private static final int SCANNING_INTERVAL = 500;
    private final int PERMISSIONS_REQUEST_CODE = 1488;

    private ActivityMainBinding binding;

    private final AudioReceiver listener = new AudioReceiver(new AudioFormatInfo(44100, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT));
    private final double referenceDb = 70;
    private boolean isListening = false;

    private MediaRecorder recorder = null;
    private File currentRecord;
    private final List<File> recordFiles = new ArrayList<>();
    private File referenceRecord;
    private boolean isRecording = false;

    private boolean isMaxSet = false;
    private int max_val = Short.MIN_VALUE;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        listener.addHandler(listenerHandler);

        binding.buttonRecord.setOnClickListener((v) -> {
            if (!isListening) {
                if (!checkPermissions())
                    requestPermissions();
                else
                    startListening();
            } else {
                isMaxSet = true;
                stopListening();
                stopRecording();
            }
        });
    }

    private boolean checkPermissions() {
        return ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                && ActivityCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
                && ActivityCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO, Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.READ_EXTERNAL_STORAGE}, PERMISSIONS_REQUEST_CODE);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        if (requestCode == PERMISSIONS_REQUEST_CODE) {
            for (int result : grantResults)
                if (result != PackageManager.PERMISSION_GRANTED)
                    return;

            startListening();
        }
    }

    //region Audio

    //region Listener

    private void startListening() {
        listenerExecutor.execute(listener::run);
        binding.imageRecord.setVisibility(View.VISIBLE);
        isListening = true;
    }

    ExecutorService listenerExecutor = Executors.newSingleThreadExecutor();
    private final Handler listenerHandler = new Handler(Looper.getMainLooper(), msg -> {
        Log.e(TAG, "Msg received");
        short[] amplitudes = ((short[]) msg.obj);
        for (short amplitude : amplitudes) {
            if (Math.abs(amplitude) > max_val) {
                if (isMaxSet) {
                    binding.textDecibels.setText("Вы шумите!");
                } else {
                    binding.textDecibels.setText(String.valueOf(max_val));
                    max_val = Math.abs(amplitude);
                    Log.e(TAG, String.valueOf(max_val));
                }
            }

        }

//        double db = getDecibels((int)getAmplitude((byte[])msg.obj));
//        binding.textDecibels.setText(String.valueOf(db));
//        if(db > referenceDb){
//            if(isRecording)
//                continueRecording();
//            else
//                startRecording();
//        }
        return true;
    });
    public static double REFERENCE = 0.00002;

    private double getAmplitude(short[] buffer) {
        int bufferSize = buffer.length;
        double average = 0.0;
        Log.e(TAG, "Amplitude size" + buffer.length);
        int max = Short.MIN_VALUE;
        for (short s : buffer) {

            average += Math.abs(s);
            if (max < Math.abs(s)) {
                max = Math.abs(s);
            }

        }
        Log.e(TAG, "Taken into account: " + bufferSize);

        Log.e(TAG, "Max val: " + max);
        //x=max;
        double x = average / bufferSize;
        double db = 0;
        if (x == 0) {
            return 0;
        }
        // calculating the pascal pressure based on the idea that the max amplitude (between 0 and 32767) is
        // relative to the pressure
        double pressure = x / 51805.5336; //the value 51805.5336 can be derived from asuming that x=32767=0.6325 Pa and x=1 = 0.00002 Pa (the reference value)
        db = (20 * Math.log10(pressure / REFERENCE));
        if (db > 0) {
            return db;
        }
        return 0;
    }

    private double getDecibels(int amplitude) {
        return amplitude;
    }

    private void stopListening() {
        listener.stop();
        binding.imageRecord.setVisibility(View.GONE);
        isListening = false;
    }

    //endregion

    //region Recorder

    private void startRecording() {
        if (recorder != null)
            stopRecording();
        recorder = new MediaRecorder();
        recorder.setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION);
        recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
        recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
        recorder.setOutputFile(createRecordFile());
        recorder.setOnErrorListener((mr, what, extra) -> {
            Log.e("MediaRecorder", String.format("ERROR TYPE: %s\n\tERROR:%s", what, extra));
        });
        try {
            recorder.prepare();
            //Thread.sleep(1000);
            recorder.start();
        } catch (IOException e) {
            e.printStackTrace();
        }
        binding.imageRecord.setSelected(true);
        isRecording = true;
        continueRecording();
    }

    private String createRecordFile() {
        File file = new File(getExternalCacheDir(), String.format("%s_%s.mp4", recordNameFormat.format(new Date()), recordFiles.size()));
        currentRecord = file;
        return file.getPath();
    }

    private final SimpleDateFormat recordNameFormat = new SimpleDateFormat("ddMMyyHHmm", Locale.getDefault());

    private void continueRecording() {
        recorderHandler.removeCallbacks(stopRunnable);
        recorderHandler.postDelayed(stopRunnable, RECORDING_TIME);
    }

    private final Handler recorderHandler = new Handler(Looper.getMainLooper());

    private final Runnable stopRunnable = this::stopRecording;

    private void stopRecording() {
        if (recorder == null)
            return;
        recorderHandler.removeCallbacks(stopRunnable);
        recorder.stop();
        recorder.release();
        recorder = null;
        binding.imageRecord.setSelected(false);
        float duration = getAudioDuration(currentRecord.getPath());
        if (duration < 10) {
            if (referenceRecord == null)
                saveRecord(duration);
            else
                currentRecord.delete();
        } else {
            saveRecord(duration);
        }
        currentRecord = null;
        isRecording = false;
    }

    private float getAudioDuration(String url) {
        try {
            Uri uri = Uri.parse(url);
            MediaMetadataRetriever mmr = new MediaMetadataRetriever();
            mmr.setDataSource(getApplicationContext(), uri);
            String durationStr = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            float millSecond = Float.parseFloat(durationStr);
            return millSecond / 1000;
        } catch (Exception e) {
            return 0;
        }
    }

    private void saveRecord(float duration) {
        File renamedFile = createRecordFile(duration);
        if (!currentRecord.renameTo(renamedFile))
            renamedFile = currentRecord;
        if (referenceRecord == null)
            referenceRecord = renamedFile;
        else
            recordFiles.add(renamedFile);
    }

    private File createRecordFile(float duration) {
        return new File(getExternalCacheDir(), String.format("%s_%s_%s.mp4", recordNameFormat.format(new Date()), recordFiles.size(), (long) duration));
    }

    //endregion

    //endregion
}
