//OutputAnalyzer.java
package eu.berdosi.app.heartbeat;

import android.graphics.Bitmap;
import android.os.CountDownTimer;
import android.os.Handler;
import android.os.Message;
import android.util.Log;
import android.view.TextureView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;

import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

class OutputAnalyzer {
    private final MainActivity activity;

    private final ChartDrawer chartDrawer;

    private MeasureStore store;

    private final int measurementInterval = 45;
    private final int measurementLength = 15000; // ensure the number of data points is the power of two
    private final int clipLength = 3500;

    private int detectedValleys = 0;
    private int ticksPassed = 0;

    private final CopyOnWriteArrayList<Long> valleys = new CopyOnWriteArrayList<>();

    private CountDownTimer timer;

    private final Handler mainHandler;

    private final ApiService apiService;

    private OnMeasurementFinishedListener listener;

    private float lastPulseValue;

    private String userId;


    public interface OnMeasurementFinishedListener {
        void onMeasurementFinished(Measurement<Float> data);
    }

    public void setOnMeasurementFinishedListener(OnMeasurementFinishedListener listener) {
        this.listener = listener;
    }

    private void notifyMeasurementFinished(Measurement<Float> data) {
        if (listener != null) {
            listener.onMeasurementFinished(data);
        }
    }

    OutputAnalyzer(MainActivity activity, TextureView graphTextureView, Handler mainHandler, ApiService apiService, String userId) {
        this.activity = activity;
        this.chartDrawer = new ChartDrawer(graphTextureView);
        this.mainHandler = mainHandler;
        this.apiService = apiService;
        this.userId = userId;
    }


    private boolean detectValley() {
        final int valleyDetectionWindowSize = 13;
        CopyOnWriteArrayList<Measurement<Integer>> subList = store.getLastStdValues(valleyDetectionWindowSize);
        if (subList.size() < valleyDetectionWindowSize) {
            return false;
        } else {
            Integer referenceValue = subList.get((int) Math.ceil(valleyDetectionWindowSize / 2f)).measurement;

            for (Measurement<Integer> measurement : subList) {
                if (measurement.measurement < referenceValue) return false;
            }

            // filter out consecutive measurements due to too high measurement rate
            return (!subList.get((int) Math.ceil(valleyDetectionWindowSize / 2f)).measurement.equals(
                    subList.get((int) Math.ceil(valleyDetectionWindowSize / 2f) - 1).measurement));
        }
    }

    void measurePulse(TextureView textureView, CameraService cameraService, String userId) {

        // 20 times a second, get the amount of red on the picture.
        // detect local minimums, calculate pulse.
        store = new MeasureStore();

        this.userId = userId;

        detectedValleys = 0;

        timer = new CountDownTimer(measurementLength, measurementInterval) {

            @Override
            public void onTick(long millisUntilFinished) {
                // skip the first measurements, which are broken by exposure metering
                if (clipLength > (++ticksPassed * measurementInterval)) return;

                Thread thread = new Thread(() -> {
                    Bitmap currentBitmap = textureView.getBitmap();
                    int pixelCount = textureView.getWidth() * textureView.getHeight();
                    int measurement = 0;
                    int[] pixels = new int[pixelCount];

                    currentBitmap.getPixels(pixels, 0, textureView.getWidth(), 0, 0, textureView.getWidth(), textureView.getHeight());

                    // extract the red component
                    // https://developer.android.com/reference/android/graphics/Color.html#decoding
                    for (int pixelIndex = 0; pixelIndex < pixelCount; pixelIndex++) {
                        measurement += (pixels[pixelIndex] >> 16) & 0xff;
                    }
                    // max int is 2^31 (2147483647) , so width and height can be at most 2^11,
                    // as 2^8 * 2^11 * 2^11 = 2^30, just below the limit

                    store.add(measurement);

                    if (detectValley()) {
                        detectedValleys = detectedValleys + 1;
                        valleys.add(store.getLastTimestamp().getTime());
                        // in 13 seconds (13000 milliseconds), I expect 15 valleys. that would be a pulse of 15 / 130000 * 60 * 1000 = 69

                        String currentValue = String.format(
                                Locale.getDefault(),
                                activity.getResources().getQuantityString(R.plurals.measurement_output_template, detectedValleys),
                                (valleys.size() == 1)
                                        ? (60f * (detectedValleys) / (Math.max(1, (measurementLength - millisUntilFinished - clipLength) / 1000f)))
                                        : (60f * (detectedValleys - 1) / (Math.max(1, (valleys.get(valleys.size() - 1) - valleys.get(0)) / 1000f))),
                                detectedValleys,
                                1f * (measurementLength - millisUntilFinished - clipLength) / 1000f);

                        lastPulseValue = (float) (Math.round(10.0 * 60f * (detectedValleys - 1) / (Math.max(1, (valleys.get(valleys.size() - 1) - valleys.get(0)) / 1000f))) / 10.0);

                        sendMessage(MainActivity.MESSAGE_UPDATE_REALTIME, currentValue);
                    }

                    // draw the chart on a separate thread.
                    Thread chartDrawerThread = new Thread(() -> chartDrawer.draw(store.getStdValues()));
                    chartDrawerThread.start();
                });
                thread.start();
            }

            @Override
            public void onFinish() {
                CopyOnWriteArrayList<Measurement<Float>> stdValues = store.getStdValues();

                // clip the interval to the first till the last one - on this interval, there were detectedValleys - 1 periods

                // If the camera only provided a static image, there are no valleys in the signal.
                // A camera not available error is shown, which is the most likely cause.
                if (valleys.size() == 0) {
                    mainHandler.sendMessage(Message.obtain(
                            mainHandler,
                            MainActivity.MESSAGE_CAMERA_NOT_AVAILABLE,
                            "No valleys detected - there may be an issue when accessing the camera."));
                    return;
                }

                String currentValue = String.format(
                        Locale.getDefault(),
                        activity.getResources().getQuantityString(R.plurals.measurement_output_template, detectedValleys - 1),
                        60f * (detectedValleys - 1) / (Math.max(1, (valleys.get(valleys.size() - 1) - valleys.get(0)) / 1000f)),
                        detectedValleys - 1,
                        1f * (valleys.get(valleys.size() - 1) - valleys.get(0)) / 1000f);

                sendMessage(MainActivity.MESSAGE_UPDATE_REALTIME, currentValue);

                StringBuilder returnValueSb = new StringBuilder();
                returnValueSb.append(currentValue);
                returnValueSb.append(activity.getString(R.string.row_separator));

                // look for "drops" of 0.15 - 0.75 in the value
                // a drop may take 2-3 ticks.
                // int dropCount = 0;
                // for (int stdValueIdx = 4; stdValueIdx < stdValues.size(); stdValueIdx++) {
                //     if (((stdValues.get(stdValueIdx - 2).measurement - stdValues.get(stdValueIdx).measurement) > dropHeight) &&
                //             !((stdValues.get(stdValueIdx - 3).measurement - stdValues.get(stdValueIdx - 1).measurement) > dropHeight) &&
                //            !((stdValues.get(stdValueIdx - 4).measurement - stdValues.get(stdValueIdx - 2).measurement) > dropHeight)
                //    ) {
                //        dropCount++;
                //    }
                // }

                // returnValueSb.append(activity.getString(R.string.detected_pulse));
                // returnValueSb.append(activity.getString(R.string.separator));
                // returnValueSb.append((float) dropCount / ((float) (measurementLength - clipLength) / 1000f / 60f));
                // returnValueSb.append(activity.getString(R.string.row_separator));

                returnValueSb.append(activity.getString(R.string.raw_values));
                returnValueSb.append(activity.getString(R.string.row_separator));


                for (int stdValueIdx = 0; stdValueIdx < stdValues.size(); stdValueIdx++) {
                    // stdValues.forEach((value) -> { // would require API level 24 instead of 21.
                    Measurement<Float> value = stdValues.get(stdValueIdx);
                    String timeStampString =
                            new SimpleDateFormat(
                                    activity.getString(R.string.dateFormatGranular),
                                    Locale.getDefault()
                            ).format(value.timestamp);
                    returnValueSb.append(timeStampString);
                    returnValueSb.append(activity.getString(R.string.separator));
                    returnValueSb.append(value.measurement);
                    returnValueSb.append(activity.getString(R.string.row_separator));
                }

                returnValueSb.append(activity.getString(R.string.output_detected_peaks_header));
                returnValueSb.append(activity.getString(R.string.row_separator));

                // add detected valleys location
                for (long tick : valleys) {
                    returnValueSb.append(tick);
                    returnValueSb.append(activity.getString(R.string.row_separator));
                }

                Date timeS = store.getLastTimestamp();
                Measurement<Float> pulseMeasurement = new Measurement<>(timeS, lastPulseValue - 1f, userId);

                activity.setLastMeasuredData(pulseMeasurement);
                //notifyMeasurementFinished(pulseMeasurement);

                sendMeasurementToAPI(pulseMeasurement);

                sendMessage(MainActivity.MESSAGE_UPDATE_FINAL, returnValueSb.toString());

                cameraService.stop();
            }
        };

        activity.setViewState(MainActivity.VIEW_STATE.MEASUREMENT);
        timer.start();
    }


    private void sendMeasurementToAPI(Measurement<Float> pulseMeasurement) {
        if (apiService != null) {
            Call<Void> call = apiService.sendPulseData(pulseMeasurement);
            call.enqueue(new Callback<Void>() {
                @Override
                public void onResponse(Call<Void> call, Response<Void> response) {
                    Log.i("API", "Pulse data sent successfully.");
                }
                @Override
                public void onFailure(Call<Void> call, Throwable t) {
                    Log.e("API", "Error sending pulse data: " + t.getMessage());
                }
            });
        } else {
            Log.e("OutputAnalyzer", "ApiService nincs inicializálva.");
        }
    }

    void stop() {
        if (timer != null) {
            timer.cancel();
        }
    }

    void sendMessage(int what, Object message) {
        Message msg = new Message();
        msg.what = what;
        msg.obj = message;
        mainHandler.sendMessage(msg);
    }
}
