package kz.curs.testappjava

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.MediaPlayer
import android.media.MediaPlayer.OnCompletionListener
import android.net.Uri
import android.os.*
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import kz.curs.testappjava.databinding.ActivityMainBinding
import org.apache.commons.lang3.ArrayUtils
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.lang.Exception
import java.lang.Math.pow
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt

class KotlinActivity : AppCompatActivity() {
    
    private val TAG = KotlinActivity::class.java.simpleName
    private val RECORDING_TIME = 3000
    private val SCANNING_INTERVAL = 500
    private val PERMISSIONS_REQUEST_CODE = 1488

    private val listener = AudioReceiver(
        AudioFormatInfo(
            44100,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
    )
    private var isListening = false

    lateinit var binding: ActivityMainBinding

    private var currentRecord: File? = null
    private val recordFiles: List<File> = ArrayList()
    private val referenceRecord: File? = null
    private var isRecording = false

    private var samplesCollected: Long = 0

    private var referenceSum: Long = 0
    private var referenceAvg = 0
    private var referenceStdev: Long = 0
    private var referenceAmplitudes = ShortArray(441000)
    private var referenceOffsetIndicator = 0
    private val amplitudeBatches: Queue<Int> = ArrayDeque()
    private val windowSum: Long = 0
    private var silenceCounter = 0
    private var loudnessCounter = 0
    var listenerExecutor = Executors.newSingleThreadExecutor()
    private var examFinished = true
    private var mediaPlayer: MediaPlayer? = null
    private var isMaxSet = false
    private var audioFileName: String? = null


    val TIME_OTP_RESEND_WAIT = 120
    val MILLISECONDS_PER_SECOND = 1000
    val SECONDS_PER_MINUTE = 60
    val RECORD_TIME = 61
    private var THRESHOLD_COEFFICIENT = 3

    val SAMPLES_IN_SECOND: Long = 44100
    private var audioToStore = ArrayList<ShortArray>()
    private val output: DataOutputStream? = null
    private var currentExamFolder: File? = null

    private val timer: CountDownTimer = object : CountDownTimer(
        (MainActivity.RECORD_TIME * MainActivity.MILLISECONDS_PER_SECOND).toLong(),
        MainActivity.MILLISECONDS_PER_SECOND.toLong()
    ) {
        override fun onTick(millisUntilFinished: Long) {
            binding.tvTimer.setText(
                String.format(
                    "Seconds left = %s",
                    millisUntilFinished / MainActivity.MILLISECONDS_PER_SECOND
                )
            )
        }

        override fun onFinish() {
            stop()
        }
    }

    private val listenerHandler = Handler(
        Looper.getMainLooper()
    ) { msg: Message ->
        val amplitudes = msg.obj as ShortArray
        processAmplitudes(amplitudes)
        true
    }

    private fun processAmplitudes(amplitudes: ShortArray) {
        samplesCollected += amplitudes.size.toLong()
        if (isRecording) {
            audioToStore.add(Arrays.copyOf(amplitudes, amplitudes.size))
        }
        if (samplesCollected < MainActivity.SAMPLES_IN_SECOND) {
            binding.tvStatus.text = "Игнорируем первую секунду"
            return
        }
        if (samplesCollected == MainActivity.SAMPLES_IN_SECOND) {
            loudnessCounter++
            binding.tvLoudnessCounter.text =
                String.format(Locale.getDefault(), "Счетчик записей: %d", loudnessCounter)
            binding.imageRecord.isSelected = true
            isRecording = true
            recorderHandler.removeCallbacks(stopRunnable)
            recorderHandler.postDelayed(stopRunnable, 10 * MainActivity.SAMPLES_IN_SECOND)
            return
        }

        //region First 10 seconds
        if (samplesCollected <= 10 * MainActivity.SAMPLES_IN_SECOND) {
            binding.tvStatus.text = "Записываем данные для сравнения"

            for (amplitude in amplitudes) {
                referenceSum += Math.abs(amplitude.toInt()).toLong()
                referenceAmplitudes[referenceOffsetIndicator] = amplitude
                referenceOffsetIndicator++
            }

            if (samplesCollected == 10 * MainActivity.SAMPLES_IN_SECOND) {
                stopRecording()

                referenceOffsetIndicator = 0
                //Store the values before extremums filter
                //storeAmplitudeArray(referenceAmplitudes, "Before filter");
                //Calculating the average
                referenceAvg = (referenceSum / 441000).toInt()

                //Calculating the stdev
                var sum = 0
                for (amplitude in referenceAmplitudes) {
                    sum += (abs(amplitude.toInt()) - referenceAvg).toDouble().pow(2).toInt()
//                    sum += Math.pow((Math.abs(amplitude.toInt()) - referenceAvg).toDouble(), 2.0)
//                        .toInt()
                }
                referenceStdev =
                    sqrt((sum / (10 * SAMPLES_IN_SECOND).toDouble())).toLong()
                Log.e(TAG, "Reference avg = $referenceAvg")
                Log.e(TAG, "Reference stdev = $referenceStdev")
                binding.tvThreshold.text =
                    "Пороговое значение: " + (referenceAvg + THRESHOLD_COEFFICIENT * referenceStdev) + "\nAvg = $referenceAvg, Stdev = $referenceStdev"

                //Removing the extremums
                var extremumsCounter = 0
                for (i in referenceAmplitudes.indices) {
                    val amplitude = referenceAmplitudes[i]
                    if (amplitude < referenceAvg - 3 * referenceStdev) {
                        referenceAmplitudes[i] = (referenceAvg - referenceStdev).toShort()
                        extremumsCounter++
                    }
                    if (amplitude > referenceAvg + 3 * referenceStdev) {
                        referenceAmplitudes[i] = (referenceAvg + referenceStdev).toShort()
                        extremumsCounter++
                    }
                }

                //storeAmplitudeArray(referenceAmplitudes, "After filter");
                //Log.e(TAG, "Extremums found = " + extremumsCounter);
                binding.tvStatus.text = "Тишина! Идет экзамен"
            }
            return
        }
        //        //endregion
        if (samplesCollected == 61 * MainActivity.SAMPLES_IN_SECOND) {
            try {
//                bufferedWriter.close()
            } catch (e: IOException) {
                e.printStackTrace()
            }
        }

        //TODO: Recover it later and move to method
        filterAmplitudeExtremums(amplitudes)
        for (amplitude in amplitudes) {
            if (Math.abs(amplitude.toInt()) > referenceAvg + THRESHOLD_COEFFICIENT * referenceStdev) {
                if (!isRecording) {
                    loudnessCounter++
                    binding.tvLoudnessCounter.text =
                        String.format(Locale.getDefault(), "Счетчик записей: %d", loudnessCounter)
                    audioToStore.add(Arrays.copyOf(amplitudes, amplitudes.size))
                    startRecording()
                } else {
                    continueRecording()
                }
            } else {
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        listener.addHandler(listenerHandler)
        binding.buttonRecord.setOnClickListener { v ->
            if (!isListening) {
                samplesCollected = 0
                if (!checkPermissions()) requestPermissions() else {
                    examFinished = false
                    val coefficientText = binding.edtCoefficient.text.toString()
                    try {
                        THRESHOLD_COEFFICIENT = coefficientText.toInt()
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                    if (createExamFolder()) {
                        resetValues()
                        startListening()
                    }
                    Log.e(TAG, "Coefficient = $THRESHOLD_COEFFICIENT")
                }
                timer.start()
            } else {
                stop()
            }
        }
    }

    private fun stop() {
        isMaxSet = true
        stopListening()
        stopRecording()
        examFinished = true
        checkIfAllRecordsStored()
        timer.cancel()
    }

    private fun checkIfAllRecordsStored() {
        if (examFinished) {
            val visibility = if (silenceCounter == loudnessCounter) View.GONE else View.VISIBLE
            binding.pbStoring.visibility = visibility
        }
    }

    private fun checkPermissions(): Boolean {
        return ActivityCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED && ActivityCompat.checkSelfPermission(
            this,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
        ) == PackageManager.PERMISSION_GRANTED && ActivityCompat.checkSelfPermission(
            this,
            Manifest.permission.READ_EXTERNAL_STORAGE
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            requestPermissions(
                arrayOf(
                    Manifest.permission.RECORD_AUDIO,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE,
                    Manifest.permission.READ_EXTERNAL_STORAGE
                ), PERMISSIONS_REQUEST_CODE
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String?>,
        grantResults: IntArray
    ) {
        if (requestCode == PERMISSIONS_REQUEST_CODE) {
            for (result in grantResults) if (result != PackageManager.PERMISSION_GRANTED) return
            startListening()
        }
    }

    //region Audio

    //region Listener

    //region Audio
    //region Listener
    private fun startListening() {
        listenerExecutor.execute(listener)
        binding.imageRecord.visibility = View.VISIBLE
        isListening = true
    }

    private fun stopListening() {
        listener.stop()
        binding.imageRecord.visibility = View.GONE
        isListening = false
    }

    //endregion

    //region Recorder

    //endregion
    //region Recorder
    private fun startRecording() {
        binding.imageRecord.isSelected = true
        isRecording = true
        continueRecording()
    }

    private fun createRecordWaveFile(): File? {
        audioFileName =
            String.format("Audio. #%s %s.wav", loudnessCounter, recordNameFormat.format(Date()))
        val file = File(currentExamFolder, audioFileName)
        try {
            Log.e(TAG, "Audio file created = " + file.createNewFile())
        } catch (e: IOException) {
            e.printStackTrace()
        }
        currentRecord = file
        return file
    }

    private fun createExamFolder(): Boolean {
        val file =
            File(externalCacheDir, String.format("Exam. %s", recordNameFormat.format(Date())))
        try {
            if (file.mkdir()) {
                Log.e(TAG, "Exam folder created")
                currentExamFolder = file
                return true
            } else {
                Log.e(TAG, "Exam folder creation failed")
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return false
    }

    private val recordNameFormat = SimpleDateFormat("ddMMyy_HHmmss", Locale.getDefault())

    private fun continueRecording() {
        Log.e(TAG, "Continue recording")
        recorderHandler.removeCallbacks(stopRunnable)
        recorderHandler.postDelayed(stopRunnable, RECORDING_TIME.toLong())
    }

    private val recorderHandler = Handler(Looper.getMainLooper())

    private val stopRunnable = Runnable { stopRecording() }

    private fun stopRecording() {
        storeRecordedData()
        recorderHandler.removeCallbacks(stopRunnable)
        binding.imageRecord.isSelected = false
        currentRecord = null
        isRecording = false
    }

    private fun createRecordFile(duration: Float): File? {
        return File(
            externalCacheDir, String.format(
                "%s_%s_%s.mp4", recordNameFormat.format(Date()), recordFiles.size,
                duration.toLong()
            )
        )
    }

    private fun filterAmplitudeExtremums(amplitudes: ShortArray) {
//        Log.e(TAG, "filterAmplitudeExtremums. Is inside the UI Thread = " + (Looper.myLooper() == Looper.getMainLooper()));
        //Calculating the sum of abs. values
        var amplitudesSum = 0
        for (amplitude in amplitudes) {
            amplitudesSum += Math.abs(amplitude.toInt())
        }
        //Calculating the average
        val amplitudesAvg = (amplitudesSum / (10 * AudioReceiver.BUFF_SIZE))

        //Calculating the stdev
        var sum = 0
        for (amplitude in amplitudes) {
            sum += Math.pow((Math.abs(amplitude.toInt()) - amplitudesAvg).toDouble(), 2.0).toInt()
        }
        val stdev =
            Math.sqrt((sum / (10 * AudioReceiver.BUFF_SIZE)).toDouble()).toLong()

        //Removing the extremums
        var extremumsCounter = 0
        for (i in amplitudes.indices) {
            val amplitude = amplitudes[i]
            if (amplitude < amplitudesAvg - 3 * stdev) {
                amplitudes[i] = (amplitudesAvg - stdev).toShort()
                extremumsCounter++
            }
            if (amplitude > amplitudesAvg + 3 * stdev) {
                amplitudes[i] = (amplitudesAvg + stdev).toShort()
                extremumsCounter++
            }
        }
    }

    //endregion

    //endregion
    private fun storeRecordedData() {
        val toStoreList: List<ShortArray> = audioToStore
        audioToStore = ArrayList()
        var mergedData = ShortArray(0)
        for (data in toStoreList) {
            mergedData = ArrayUtils.addAll(mergedData, *data)
        }
        if (mergedData.size != 0) {
            shortToWave(mergedData)
        }
        //TODO: Write everything to a file
    }

    private fun shortToWave(audioData: ShortArray) {
        val executorService = Executors.newSingleThreadExecutor()
        val fileStoringRunable = Runnable {
            val resultMsg = Message()
           
            //TODO: move to prev. method later
            val wavFile = createRecordWaveFile()
            if (wavFile == null || !wavFile.exists()) {
                return@Runnable
            }
            var wavOutputStream: DataOutputStream? = null
            try {
                wavOutputStream = DataOutputStream(FileOutputStream(wavFile))
            } catch (e: Exception) {
                e.printStackTrace()
            }
            try {
                if (wavOutputStream != null) {
                    val mySubChunk1Size = 16
                    val myBitsPerSample = 16
                    val myFormat = 1
                    val myChannels: Long = 1
                    val mySampleRate: Long = 44100
                    val myByteRate = mySampleRate * myChannels * myBitsPerSample / 8
                    val myBlockAlign = (myChannels * myBitsPerSample / 8).toInt()
                    val myDataSize = (audioData.size * 2).toLong()
                    val myChunk2Size = myDataSize * myChannels * myBitsPerSample / 8
                    val myChunkSize = 36 + myChunk2Size

                    // WAVE header
                    // see http://ccrma.stanford.edu/courses/422/projects/WaveFormat/

                    //region Wave Headers
                    val header = ByteArray(44)
                    header[0] = 'R'.code.toByte() // RIFF/WAVE header
                    header[1] = 'I'.code.toByte()
                    header[2] = 'F'.code.toByte()
                    header[3] = 'F'.code.toByte()
                    header[4] = (myChunkSize and 0xff).toByte()
                    header[5] = (myChunkSize shr 8 and 0xff).toByte()
                    header[6] = (myChunkSize shr 16 and 0xff).toByte()
                    header[7] = (myChunkSize shr 24 and 0xff).toByte()
                    header[8] = 'W'.code.toByte()
                    header[9] = 'A'.code.toByte()
                    header[10] = 'V'.code.toByte()
                    header[11] = 'E'.code.toByte()
                    header[12] = 'f'.code.toByte() // 'fmt ' chunk
                    header[13] = 'm'.code.toByte()
                    header[14] = 't'.code.toByte()
                    header[15] = ' '.code.toByte()
                    header[16] = 16 // 4 bytes: size of 'fmt ' chunk
                    header[17] = 0
                    header[18] = 0
                    header[19] = 0
                    header[20] = 1 // format = 1
                    header[21] = 0
                    header[22] = 1.toByte()
                    header[23] = 0
                    header[24] = (mySampleRate and 0xff).toByte()
                    header[25] = (mySampleRate shr 8 and 0xff).toByte()
                    header[26] = (mySampleRate shr 16 and 0xff).toByte()
                    header[27] = (mySampleRate shr 24 and 0xff).toByte()
                    header[28] = (myByteRate and 0xff).toByte()
                    header[29] = (myByteRate shr 8 and 0xff).toByte()
                    header[30] = (myByteRate shr 16 and 0xff).toByte()
                    header[31] = (myByteRate shr 24 and 0xff).toByte()
                    header[32] = myBlockAlign.toByte() // block align
                    header[33] = 0
                    header[34] = 16 // bits per sample
                    header[35] = 0
                    header[36] = 'd'.code.toByte()
                    header[37] = 'a'.code.toByte()
                    header[38] = 't'.code.toByte()
                    header[39] = 'a'.code.toByte()
                    header[40] = (myDataSize and 0xff).toByte()
                    header[41] = (myDataSize shr 8 and 0xff).toByte()
                    header[42] = (myDataSize shr 16 and 0xff).toByte()
                    header[43] = (myDataSize shr 24 and 0xff).toByte()
                    wavOutputStream.write(header, 0, 44)
                    //                endregion
                }
                for (s in audioData) {
                    writeShort(wavOutputStream, s)
                }
                wavOutputStream!!.flush()
                if (wavOutputStream != null) {
                    wavOutputStream.close()
                    resultMsg.obj = true
                    wavFileRecordedHandler.sendMessage(resultMsg)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                resultMsg.obj = false
                wavFileRecordedHandler.sendMessage(resultMsg)
            }
        }
        executorService.execute(fileStoringRunable)
    }

    @Throws(IOException::class)
    private fun writeShort(output: DataOutputStream?, value: Short) {
        output?.write(value.toInt() shr 0)
        output?.write(value.toInt() shr 8)
    }

    private val wavFileRecordedHandler = Handler(
        Looper.getMainLooper()
    ) { message: Message ->
        val isSuccessful = message.obj as Boolean
        if (isSuccessful) {
            silenceCounter++
            binding.tvSilenceCounter.text = String.format(
                Locale.getDefault(),
                "%d записей успешно сохранено",
                silenceCounter
            )
            checkIfAllRecordsStored()
            updatePlaybackButtons()
        }
        true
    }

    private fun resetValues() {
        loudnessCounter = 0
        silenceCounter = 0
        referenceAvg = 0
        referenceStdev = 0
        referenceAmplitudes = ShortArray(441000)
        referenceOffsetIndicator = 0
        binding.tvSilenceCounter.text = ""
        binding.tvLoudnessCounter.text = ""
        binding.tvThreshold.text = ""
        binding.tvStatus.text = ""
        binding.lltButtons.removeAllViews()
    }

    private fun updatePlaybackButtons() {
        binding.lltButtons.removeAllViews()
        for (playbackFile in currentExamFolder!!.listFiles()) {
            val playbackButton = Button(this)
            playbackButton.text = playbackFile.name
            playbackButton.setOnClickListener { view: View? ->
                playTheFile(
                    playbackFile
                )
            }
            val layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            layoutParams.bottomMargin = 20
            playbackButton.layoutParams = layoutParams
            binding.lltButtons.addView(playbackButton)
        }
    }

    private fun playTheFile(fileToPlay: File) {
        if (mediaPlayer != null) {
            if (mediaPlayer?.isPlaying() == true) {
                mediaPlayer?.stop()
            }
            mediaPlayer?.release()
        }
        mediaPlayer = MediaPlayer()
        try {
            mediaPlayer?.setDataSource(this, Uri.fromFile(fileToPlay))
            mediaPlayer?.prepare()
            mediaPlayer?.start()
            mediaPlayer?.setOnCompletionListener(OnCompletionListener { mp: MediaPlayer ->
                mp.release()
                mediaPlayer = null
            })
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }
}