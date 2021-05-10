package kz.curs.testappjava;

import android.Manifest;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.MediaMetadataRetriever;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Queue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import kz.curs.testappjava.databinding.ActivityMainBinding;

import static kz.curs.testappjava.AudioReceiver.BUFF_SIZE;

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

    private File signalFile;
    private String audioFileName;
    private BufferedWriter bufferedWriter;
    private boolean isMaxSet = false;
    private int max_val = Short.MIN_VALUE;
    private long samplesCollected = 0;

    private long referenceSum = 0;
    private int referenceAvg = 0;
    private long referenceStdev = 0;
    private short referenceAmplitudes[] = new short[441000];
    private int referenceOffsetIndicator = 0;
    private Queue<Integer> amplitudeBatches = new ArrayDeque<>();
    private long windowSum = 0;
    private int silenceCounter = 0;
    private int loudnessCounter = 0;


    public final static int TIME_OTP_RESEND_WAIT = 120;
    public final static int MILLISECONDS_PER_SECOND = 1000;
    public final static int SECONDS_PER_MINUTE = 60;
    public static final int RECORD_TIME = 61;

    public static final long SAMPLES_IN_SECOND = 44100;

    private CountDownTimer timer = new CountDownTimer(RECORD_TIME * MILLISECONDS_PER_SECOND, MILLISECONDS_PER_SECOND) {
        public void onTick(long millisUntilFinished) {
            binding.tvTimer.setText(String.format("Seconds left = %s", millisUntilFinished / MILLISECONDS_PER_SECOND));
        }

        public void onFinish() {
            stop();
        }
    };

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        listener.addHandler(listenerHandler);

        binding.buttonRecord.setOnClickListener((v) -> {
            if (!isListening) {
                samplesCollected = 0;
                if (!checkPermissions())
                    requestPermissions();
                else {
                    startRecording();
                    startListening();
                    createSignalFile();
                }
                timer.start();
            } else {
                stop();
            }
        });
    }

    private void stop() {
        isMaxSet = true;
        stopListening();
        stopRecording();

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

    private void writeToFile(short[] amplitudes) {
        for (short amplitude : amplitudes) {
            try {
                bufferedWriter.write(amplitude + "\n");
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    ExecutorService listenerExecutor = Executors.newSingleThreadExecutor();
    private final Handler listenerHandler = new Handler(Looper.getMainLooper(), msg -> {
        short[] amplitudes = ((short[]) msg.obj);
        processAmplitudes(amplitudes);

        return true;
    });
    public static double REFERENCE = 0.00002;

    private void processAmplitudes(short[] amplitudes) {
//        Log.e(TAG, "Msg received. Size: " + amplitudes.length);
        samplesCollected += amplitudes.length;
        binding.tvSamples.setText(String.format("Samples collected: %d", samplesCollected));

        if (samplesCollected <= SAMPLES_IN_SECOND) {
            binding.tvStatus.setText("Игнорируем первую секунду");
            return;
        }

        writeToFile(amplitudes);

        //region First 10 seconds
        if (samplesCollected <= 10 * SAMPLES_IN_SECOND) {
            binding.tvStatus.setText("Записываем данные для сравнения");

            for (short amplitude : amplitudes) {
                referenceSum += Math.abs(amplitude);
                referenceAmplitudes[referenceOffsetIndicator] = amplitude;
                referenceOffsetIndicator++;
            }


            if (samplesCollected == 10 * SAMPLES_IN_SECOND) {
                //Store the values before extremums filter
                storeAmplitudeArray(referenceAmplitudes, "Before filter");
                Log.e(TAG, referenceSum / 441000 + "");
                //Calculating the average
                referenceAvg = (int) (referenceSum / (10 * SAMPLES_IN_SECOND));

                //Calculating the stdev
                int sum = 0;
                for (short amplitude : referenceAmplitudes) {
                    sum += Math.pow((Math.abs(amplitude) - referenceAvg), 2);
                }
                referenceStdev = (long) Math.sqrt(sum / (10 * SAMPLES_IN_SECOND));
                Log.e(TAG, "Reference avg = " + referenceAvg);
                Log.e(TAG, "Reference stdev = " + referenceStdev);

                //Removing the extremums
                int extremumsCounter = 0;
                for (int i = 0; i < referenceAmplitudes.length; i++) {
                    short amplitude = referenceAmplitudes[i];
                    if (amplitude < referenceAvg - 3 * referenceStdev) {
                        referenceAmplitudes[i] = (short) (referenceAvg - referenceStdev);
                        extremumsCounter++;
                    }
                    if (amplitude > referenceAvg + 3 * referenceStdev) {
                        referenceAmplitudes[i] = (short) (referenceAvg + referenceStdev);
                        extremumsCounter++;
                    }
                }

                storeAmplitudeArray(referenceAmplitudes, "After filter");
                Log.e(TAG, "Extremums found = " + extremumsCounter);
                binding.tvStatus.setText("Начинаем прокторинг. Записываем первые 10 секунд прокторинга");
            }

            return;
        }
        //endregion

        if (samplesCollected == 61 * SAMPLES_IN_SECOND) {
            try {
                bufferedWriter.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }


        filterAmplitudeExtremums(amplitudes);
        int amplitudeBatchSum = 0;
        for (short amplitude : amplitudes) {
            amplitudeBatchSum += Math.abs(amplitude);
        }

        windowSum += amplitudeBatchSum;
        amplitudeBatches.add(amplitudeBatchSum);

        if (amplitudeBatches.size() < SAMPLES_IN_SECOND * 10 / BUFF_SIZE) {
            return;
        }

        if (amplitudeBatches.size() > SAMPLES_IN_SECOND * 10 / BUFF_SIZE) {
            int sumToRemove = amplitudeBatches.poll();
            windowSum -= sumToRemove;
        }

        int windowAvg = (int) (windowSum / (10 * SAMPLES_IN_SECOND));

//        Log.e(TAG, String.format("Reference avg = %s, WindowAvg = %s", referenceAvg, windowAvg));

        if (referenceAvg * 0.85 > windowAvg) {
            silenceCounter++;
            binding.tvSilenceCounter.setText("Подозрительная тишиина");
            binding.tvSilenceCounter.setText(String.format("Счетчик подозриетльной тишины: %d", silenceCounter));
            return;
        }

        if (referenceAvg * 1.15 < windowAvg) {
            loudnessCounter++;
            binding.tvStatus.setText("Шум");
            binding.tvLoudnessCounter.setText(String.format("Счетчик шума: %d", loudnessCounter));
            return;
        }

        if (referenceAvg * 0.85 < windowAvg && windowAvg < referenceAvg * 1.15) {
            binding.tvStatus.setText("В пределах нормы");
        }
    }

    private double getAmplitude(short[] buffer) {
        int bufferSize = buffer.length;
        double average = 0.0;
        //Log.e(TAG, "Amplitude size" + buffer.length);
        int max = Short.MIN_VALUE;
        for (short s : buffer) {

            average += Math.abs(s);
            if (max < Math.abs(s)) {
                max = Math.abs(s);
            }

        }
//        Log.e(TAG, "Taken into account: " + bufferSize);
//
//        Log.e(TAG, "Max val: " + max);
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
        audioFileName = String.format("Audio. %s.mp3", recordNameFormat.format(new Date()));
        File file = new File(getExternalCacheDir(), audioFileName);
        try {
            Log.e(TAG, "Audio filed created = " + file.createNewFile());
        } catch (IOException e) {
            e.printStackTrace();
        }
        currentRecord = file;
        return file.getPath();
    }

    private String createSignalFile() {
        File file = new File(getExternalCacheDir(), String.format("Signal. %s.txt", recordNameFormat.format(new Date())));
        try {
            if (file.createNewFile()) {
                Log.e(TAG, "File created");
                signalFile = file;
                bufferedWriter = new BufferedWriter(new FileWriter(signalFile));
            } else {
                Log.e(TAG, "File creation failed");
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        Log.e(TAG, file.getAbsolutePath());
        Log.e(TAG, file.getPath());
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

    private void storeAmplitudeArray(short[] amplitudes, String fileName) {
        Log.e(TAG, "storeAmplitudeArray. Arr size = " + amplitudes.length);
        File file = new File(getExternalCacheDir(), String.format("%s. %s.txt", fileName, recordNameFormat.format(new Date())));
        try {
            if (file.createNewFile()) {
                Log.e(TAG, "storeAmplitudeArray. File created");

                BufferedWriter amplitudesWriter = new BufferedWriter(new FileWriter(file));

                for (short amplitude : amplitudes) {
                    try {
                        amplitudesWriter.write(amplitude + "\n");
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
                amplitudesWriter.close();
            } else {
                Log.e(TAG, "storeAmplitudeArray. File creation failed");
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void filterAmplitudeExtremums(short[] amplitudes) {
        //Calculating the sum of abs. values
        int amplitudesSum = 0;
        for (short amplitude : amplitudes) {
            amplitudesSum += Math.abs(amplitude);
        }
        //Calculating the average
        int amplitudesAvg = (int) (amplitudesSum / (10 * BUFF_SIZE));

        //Calculating the stdev
        int sum = 0;
        for (short amplitude : amplitudes) {
            sum += Math.pow((Math.abs(amplitude) - amplitudesAvg), 2);
        }
        long stdev = (long) Math.sqrt(sum / (10 * BUFF_SIZE));

        //Removing the extremums
        int extremumsCounter = 0;
        for (int i = 0; i < amplitudes.length; i++) {
            short amplitude = amplitudes[i];
            if (amplitude < amplitudesAvg - 3 * stdev) {
                amplitudes[i] = (short) (amplitudesAvg - stdev);
                extremumsCounter++;
            }
            if (amplitude > amplitudesAvg + 3 * stdev) {
                amplitudes[i] = (short) (amplitudesAvg + stdev);
                extremumsCounter++;
            }
        }
    }

    //endregion
}
