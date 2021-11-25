package kz.curs.testappjava

import android.util.Log
import kotlin.math.abs

class AmplitudeProcessorKt {

    private val TAG = AmplitudeProcessor::class.java.simpleName

    private var referenceSum: Long = 0
    var referenceAvg: Long = 0
    var referenceStdev: Long = 0
    private val referenceAmplitudes = ShortArray(441000)
    private var referenceOffsetIndicator = 0

    fun addAmplitudesToReference(amplitudes: ShortArray) {
        for (amplitude in amplitudes) {
            referenceSum += abs(amplitude.toInt())
            referenceAmplitudes[referenceOffsetIndicator] = amplitude
            referenceOffsetIndicator++
        }
    }

    fun estimateReferences() {
        referenceOffsetIndicator = 0

        //Calculating the average
        referenceAvg = referenceSum / 441000
        Log.e(TAG, "Reference avg in long = $referenceAvg")
        val referenceAvgInt = (referenceSum / 441000).toInt()
        Log.e(TAG, "Reference avg in long = $referenceAvgInt")

        //Calculating the stdev
        var sum: Long = 0
        for (amplitude in referenceAmplitudes) {
            sum += Math.pow((Math.abs(amplitude.toInt()) - referenceAvg).toDouble(), 2.0).toLong()
        }
        referenceStdev = Math.sqrt(sum / 441000.0).toLong()
        Log.e(TAG, "Reference stdev in long = $referenceStdev")
    }
}