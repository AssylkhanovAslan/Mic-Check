package kz.curs.testappjava;

import android.util.Log;

public class AmplitudeProcessor {

    private static String TAG = AmplitudeProcessor.class.getSimpleName();

    private long referenceSum = 0;
    long referenceAvg = 0;
    long referenceStdev = 0;
    private short referenceAmplitudes[] = new short[441000];
    private int referenceOffsetIndicator = 0;

    public void addAmplitudesToReference(short[] amplitudes) {
        for (short amplitude : amplitudes) {
            referenceSum += Math.abs(amplitude);
            referenceAmplitudes[referenceOffsetIndicator] = amplitude;
            referenceOffsetIndicator++;
        }

    }

    public void estimateReferences() {
        referenceOffsetIndicator = 0;

        //Calculating the average
        referenceAvg = referenceSum / (441000);
        Log.e(TAG, "Reference avg in long = " + referenceAvg);
        int referenceAvgInt = (int) (referenceSum / 441000);
        Log.e(TAG, "Reference avg in long = " + referenceAvgInt);

        //Calculating the stdev
        long sum = 0;
        for (short amplitude : referenceAmplitudes) {
            sum += Math.pow((Math.abs(amplitude) - referenceAvg), 2);
        }
        referenceStdev = (long) Math.sqrt(sum / 441000.0);
        Log.e(TAG, "Reference stdev in long = " + referenceStdev);
    }



}
