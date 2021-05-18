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
import android.os.Message;
import android.util.Log;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

import org.apache.commons.lang3.ArrayUtils;

import java.io.BufferedWriter;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Queue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import kz.curs.testappjava.databinding.ActivityMainBinding;

import static android.view.View.GONE;
import static android.view.View.VISIBLE;
import static kz.curs.testappjava.AudioReceiver.BUFF_SIZE;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = MainActivity.class.getSimpleName();

    private static final int RECORDING_TIME = 3000;
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
    ExecutorService listenerExecutor = Executors.newSingleThreadExecutor();
    private boolean examFinished = true;


    public final static int TIME_OTP_RESEND_WAIT = 120;
    public final static int MILLISECONDS_PER_SECOND = 1000;
    public final static int SECONDS_PER_MINUTE = 60;
    public static final int RECORD_TIME = 61;
    private int THRESHOLD_COEFFICIENT = 3;

    public static final long SAMPLES_IN_SECOND = 44100;
    private ArrayList<short[]> audioToStore = new ArrayList<>();
    private DataOutputStream output = null;
    private File currentExamFolder = null;

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
                    examFinished = false;
                    String coefficientText = binding.edtCoefficient.getText().toString();
                    try {
                        THRESHOLD_COEFFICIENT = Integer.parseInt(coefficientText);
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                    if (createExamFolder()) {
                        startListening();
                    }
                    Log.e(TAG, "Coefficient = " + THRESHOLD_COEFFICIENT);
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
        examFinished = true;
        checkIfAllRecordsStored();
    }

    private void checkIfAllRecordsStored() {
        if (examFinished) {
            int visibility = silenceCounter == loudnessCounter ? GONE : VISIBLE;
            binding.pbStoring.setVisibility(visibility);
        }
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
        listenerExecutor.execute(listener);
        binding.imageRecord.setVisibility(View.VISIBLE);
        isListening = true;
    }

    private void writeToFile(byte[] amplitudes) {
        for (short amplitude : amplitudes) {
            try {
                bufferedWriter.write(amplitude + "\n");
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    private final Handler listenerHandler = new Handler(Looper.getMainLooper(), msg -> {
        short[] amplitudes = ((short[]) msg.obj);
        processAmplitudes(amplitudes);

        return true;
    });

    private void processAmplitudes(short[] amplitudes) {
        samplesCollected += amplitudes.length;

        if (isRecording) {
            audioToStore.add(Arrays.copyOf(amplitudes, amplitudes.length));
        }

        if (samplesCollected < SAMPLES_IN_SECOND) {
            binding.tvStatus.setText("Игнорируем первую секунду");
            return;
        }

        if (samplesCollected == SAMPLES_IN_SECOND) {
            loudnessCounter++;
            binding.tvLoudnessCounter.setText(String.format(Locale.getDefault(), "Счетчик записей: %d", loudnessCounter));
            binding.imageRecord.setSelected(true);
            isRecording = true;
            recorderHandler.removeCallbacks(stopRunnable);
            recorderHandler.postDelayed(stopRunnable, 10 * SAMPLES_IN_SECOND);
            return;
        }

        //region First 10 seconds
        if (samplesCollected <= 10 * SAMPLES_IN_SECOND) {
            binding.tvStatus.setText("Записываем данные для сравнения");

            for (short amplitude : amplitudes) {
                referenceSum += Math.abs(amplitude);
                referenceAmplitudes[referenceOffsetIndicator] = amplitude;
                referenceOffsetIndicator++;
            }


            if (samplesCollected == 10 * SAMPLES_IN_SECOND) {
                stopRecording();

                referenceOffsetIndicator = 0;
                //Store the values before extremums filter
                //storeAmplitudeArray(referenceAmplitudes, "Before filter");
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

                binding.tvThreshold.setText("Пороговое значение: " + (referenceAvg + THRESHOLD_COEFFICIENT * referenceStdev));

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

                //storeAmplitudeArray(referenceAmplitudes, "After filter");
                //Log.e(TAG, "Extremums found = " + extremumsCounter);
                binding.tvStatus.setText("Тишина! Идет экзамен");
            }

            return;
        }
//        //endregion

        if (samplesCollected == 61 * SAMPLES_IN_SECOND) {
            try {
                bufferedWriter.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        //TODO: Recover it later and move to method
        filterAmplitudeExtremums(amplitudes);
        for (short amplitude : amplitudes) {
            if (Math.abs(amplitude) > referenceAvg + THRESHOLD_COEFFICIENT * referenceStdev) {
                if (!isRecording) {
                    loudnessCounter++;
                    binding.tvLoudnessCounter.setText(String.format(Locale.getDefault(), "Счетчик записей: %d", loudnessCounter));
                    audioToStore.add(Arrays.copyOf(amplitudes, amplitudes.length));
                    startRecording();
                } else {
                    continueRecording();
                }
            } else {
            }
        }

//        windowSum += amplitudeBatchSum;
//        amplitudeBatches.add(amplitudeBatchSum);
//
//        if (amplitudeBatches.size() < SAMPLES_IN_SECOND * 10 / BUFF_SIZE) {
//            return;
//        }
//
//        if (amplitudeBatches.size() > SAMPLES_IN_SECOND * 10 / BUFF_SIZE) {
//            int sumToRemove = amplitudeBatches.poll();
//            windowSum -= sumToRemove;
//        }
//
//        int windowAvg = (int) (windowSum / (10 * SAMPLES_IN_SECOND));
//
////        Log.e(TAG, String.format("Reference avg = %s, WindowAvg = %s", referenceAvg, windowAvg));
//
//        if (referenceAvg * 0.85 > windowAvg) {
//            silenceCounter++;
//            binding.tvSilenceCounter.setText("Подозрительная тишиина");
//            binding.tvSilenceCounter.setText(String.format("Счетчик подозриетльной тишины: %d", silenceCounter));
//            return;
//        }
//
//        if (referenceAvg * 1.15 < windowAvg) {
//            loudnessCounter++;
//            binding.tvStatus.setText("Шум");
//            binding.tvLoudnessCounter.setText(String.format("Счетчик шума: %d", loudnessCounter));
//            return;
//        }
//
//        if (referenceAvg * 0.85 < windowAvg && windowAvg < referenceAvg * 1.15) {
//            binding.tvStatus.setText("В пределах нормы");
//        }
    }

    private void stopListening() {
        listener.stop();
        binding.imageRecord.setVisibility(GONE);
        isListening = false;
    }

    //endregion

    //region Recorder

    private void startRecording() {
        binding.imageRecord.setSelected(true);
        isRecording = true;
        continueRecording();
    }

    private File createRecordWaveFile() {
        audioFileName = String.format("Audio. %s.wav", recordNameFormat.format(new Date()));
        File file = new File(currentExamFolder, audioFileName);
        try {
            Log.e(TAG, "Audio file created = " + file.createNewFile());
        } catch (IOException e) {
            e.printStackTrace();
        }
        currentRecord = file;
        return file;
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

    private boolean createExamFolder() {
        File file = new File(getExternalCacheDir(), String.format("Exam. %s", recordNameFormat.format(new Date())));
        try {
            if (file.mkdir()) {
                Log.e(TAG, "Exam folder created");
                currentExamFolder = file;
                return true;
            } else {
                Log.e(TAG, "Exam folder creation failed");
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return false;
    }

    private final SimpleDateFormat recordNameFormat = new SimpleDateFormat("ddMMyy_HHmmss", Locale.getDefault());

    private void continueRecording() {
        Log.e(TAG, "Continue recording");
        recorderHandler.removeCallbacks(stopRunnable);
        recorderHandler.postDelayed(stopRunnable, RECORDING_TIME);
    }

    private final Handler recorderHandler = new Handler(Looper.getMainLooper());

    private final Runnable stopRunnable = this::stopRecording;

    private void stopRecording() {
        storeRecordedData();
        recorderHandler.removeCallbacks(stopRunnable);
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
//        Log.e(TAG, "filterAmplitudeExtremums. Is inside the UI Thread = " + (Looper.myLooper() == Looper.getMainLooper()));
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

    private void storeRecordedData() {
        List<short[]> toStoreList = audioToStore;
        audioToStore = new ArrayList<>();

        short[] mergedData = new short[0];
        for (short[] data : toStoreList) {
            Log.e(TAG, "Data len = " + data.length);

            mergedData = ArrayUtils.addAll(mergedData, data);
        }

        if (mergedData.length != 0) {
            shortToWave(mergedData);
        }
        //TODO: Write everything to a file
    }

    private void shortToWave(short[] audioData) {
        ExecutorService executorService = Executors.newSingleThreadExecutor();
        Runnable fileStoringRunable = () -> {
            Message resultMsg = new Message();
            Log.e(TAG, "Is inside the UI Thread = " + (Looper.myLooper() == Looper.getMainLooper()));
            Log.e(TAG, "Len = " + audioData.length);
            //TODO: move to prev. method later
            File wavFile = createRecordWaveFile();
            if (wavFile == null || !wavFile.exists()) {
                Log.e(TAG, "Could not create a file");
                return;
            }
            DataOutputStream wavOutputStream = null;
            try {
                wavOutputStream = new DataOutputStream(new FileOutputStream(wavFile));
            } catch (Exception e) {
                e.printStackTrace();
            }

            try {
                if (wavOutputStream != null) {

                    int mySubChunk1Size = 16;
                    int myBitsPerSample = 16;
                    int myFormat = 1;
                    long myChannels = 1;
                    long mySampleRate = 44100;
                    long myByteRate = mySampleRate * myChannels * myBitsPerSample / 8;
                    int myBlockAlign = (int) (myChannels * myBitsPerSample / 8);


                    long myDataSize = audioData.length * 2;
                    long myChunk2Size = myDataSize * myChannels * myBitsPerSample / 8;
                    long myChunkSize = 36 + myChunk2Size;

                    // WAVE header
                    // see http://ccrma.stanford.edu/courses/422/projects/WaveFormat/

                    //region Wave Headers
                    byte[] header = new byte[44];

                    header[0] = 'R';  // RIFF/WAVE header
                    header[1] = 'I';
                    header[2] = 'F';
                    header[3] = 'F';
                    header[4] = (byte) (myChunkSize & 0xff);
                    header[5] = (byte) ((myChunkSize >> 8) & 0xff);
                    header[6] = (byte) ((myChunkSize >> 16) & 0xff);
                    header[7] = (byte) ((myChunkSize >> 24) & 0xff);
                    header[8] = 'W';
                    header[9] = 'A';
                    header[10] = 'V';
                    header[11] = 'E';
                    header[12] = 'f';  // 'fmt ' chunk
                    header[13] = 'm';
                    header[14] = 't';
                    header[15] = ' ';
                    header[16] = 16;  // 4 bytes: size of 'fmt ' chunk
                    header[17] = 0;
                    header[18] = 0;
                    header[19] = 0;
                    header[20] = 1;  // format = 1
                    header[21] = 0;
                    header[22] = (byte) 1;
                    header[23] = 0;
                    header[24] = (byte) (mySampleRate & 0xff);
                    header[25] = (byte) ((mySampleRate >> 8) & 0xff);
                    header[26] = (byte) ((mySampleRate >> 16) & 0xff);
                    header[27] = (byte) ((mySampleRate >> 24) & 0xff);
                    header[28] = (byte) (myByteRate & 0xff);
                    header[29] = (byte) ((myByteRate >> 8) & 0xff);
                    header[30] = (byte) ((myByteRate >> 16) & 0xff);
                    header[31] = (byte) ((myByteRate >> 24) & 0xff);
                    header[32] = (byte) (myBlockAlign);  // block align
                    header[33] = 0;
                    header[34] = 16;  // bits per sample
                    header[35] = 0;
                    header[36] = 'd';
                    header[37] = 'a';
                    header[38] = 't';
                    header[39] = 'a';
                    header[40] = (byte) (myDataSize & 0xff);
                    header[41] = (byte) ((myDataSize >> 8) & 0xff);
                    header[42] = (byte) ((myDataSize >> 16) & 0xff);
                    header[43] = (byte) ((myDataSize >> 24) & 0xff);

                    wavOutputStream.write(header, 0, 44);
//                endregion
                }

                for (short s : audioData) {
                    writeShort(wavOutputStream, s);
                }

                wavOutputStream.flush();
                if (wavOutputStream != null) {
                    wavOutputStream.close();
                    resultMsg.obj = true;
                    wavFileRecordedHandler.sendMessage(resultMsg);
                }
            } catch (Exception e) {
                e.printStackTrace();
                resultMsg.obj = false;
                wavFileRecordedHandler.sendMessage(resultMsg);
            }
        };
        executorService.execute(fileStoringRunable);
    }

    private void writeShort(final DataOutputStream output, final short value) throws IOException {
        output.write(value >> 0);
        output.write(value >> 8);
    }

    private final Handler wavFileRecordedHandler = new Handler(Looper.getMainLooper(), message -> {
        boolean isSuccessful = (boolean) message.obj;
        if (isSuccessful) {
            silenceCounter++;
            binding.tvSilenceCounter.setText(String.format(Locale.getDefault(), "%d записей успешно сохранено", silenceCounter));
            checkIfAllRecordsStored();
        }
        return true;
    });
}
