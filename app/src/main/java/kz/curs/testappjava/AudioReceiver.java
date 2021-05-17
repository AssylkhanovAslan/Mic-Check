package kz.curs.testappjava;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.Process;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

public class AudioReceiver implements Runnable {

    private static final String TAG = AudioReceiver.class.getSimpleName();
    public static final int BUFF_SIZE = 3675;

    private final AudioFormatInfo formatInfo;

    public AudioReceiver(AudioFormatInfo format) {
        formatInfo = format;
    }

    private final int BUFF_COUNT = 32;
    public final static int MSG_DATA = 1337;

    private List<Handler> handlers = new ArrayList<>();
    private boolean mIsRunning = true;
    private AudioRecord recorder = null;

    @Override
    public void run() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);
        mIsRunning = true;

        int buffSize = AudioRecord.getMinBufferSize(formatInfo.getSampleRateInHz(), formatInfo.getChannelConfig(), formatInfo.getAudioFormat());
        Log.e(TAG, "Buff size" + buffSize);

        if (buffSize == AudioRecord.ERROR) {
            System.err.println("getMinBufferSize returned ERROR");
            return;
        }

        if (buffSize == AudioRecord.ERROR_BAD_VALUE) {
            System.err.println("getMinBufferSize returned ERROR_BAD_VALUE");
            return;
        }

        if (formatInfo.getAudioFormat() != AudioFormat.ENCODING_PCM_16BIT) {
            System.err.println("unknown format");
            return;
        }

        short[][] buffers = new short[BUFF_COUNT][BUFF_SIZE];

        recorder = new AudioRecord(MediaRecorder.AudioSource.MIC, formatInfo.getSampleRateInHz(), formatInfo.getChannelConfig(), formatInfo.getAudioFormat(), buffSize * 10);

        if (recorder.getState() != AudioRecord.STATE_INITIALIZED) {
            System.err.println("getState() != STATE_INITIALIZED");
            return;
        }

        try {
            recorder.startRecording();
        } catch (IllegalStateException e) {
            e.printStackTrace();
            return;
        }

        int count = 0;

        while (mIsRunning) {
            int samplesRead = recorder.read(buffers[count], 0, buffers[count].length);
            if (samplesRead == AudioRecord.ERROR_INVALID_OPERATION) {
                System.err.println("read() returned ERROR_INVALID_OPERATION");
                return;
            }
            if (samplesRead == AudioRecord.ERROR_BAD_VALUE) {
                System.err.println("read() returned ERROR_BAD_VALUE");
                return;
            }

            sendMsg(buffers[count]);
            count = (count + 1) % BUFF_COUNT;
        }

        try {
            try {
                recorder.stop();
            } catch (IllegalStateException e) {
                e.printStackTrace();
                return;
            }
        } finally {
            recorder.release();
            recorder = null;
        }
    }

    public void addHandler(Handler handler) {
        handlers.add(handler);
    }

    public void stop() {
        if (mIsRunning) {
            mIsRunning = false;
            recorder.stop();
        }
    }

    private void sendMsg(short[] data) {
        for (Handler handler : handlers) {
            handler.sendMessage(handler.obtainMessage(MSG_DATA, data));
        }
    }
}
