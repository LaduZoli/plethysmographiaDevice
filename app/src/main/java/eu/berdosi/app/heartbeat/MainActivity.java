// MainActivity.java
package eu.berdosi.app.heartbeat;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.SurfaceTexture;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.appcompat.widget.Toolbar;
import androidx.core.app.ActivityCompat;

import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.Log;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.TextureView;
import android.view.Surface;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.material.snackbar.Snackbar;

import java.util.Date;
import java.text.SimpleDateFormat;
import java.util.Locale;

import okhttp3.OkHttpClient;
import okhttp3.logging.HttpLoggingInterceptor;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;

public class MainActivity extends Activity implements ActivityCompat.OnRequestPermissionsResultCallback, OutputAnalyzer.OnMeasurementFinishedListener {
    private OutputAnalyzer analyzer;

    private static final String BASE_URL = "https://us-central1-plethysmographia.cloudfunctions.net/app/api/";
    private ApiService apiService;

    private String userId;
    private final int REQUEST_CODE_CAMERA = 0;
    public static final int MESSAGE_UPDATE_REALTIME = 1;
    public static final int MESSAGE_UPDATE_FINAL = 2;
    public static final int MESSAGE_CAMERA_NOT_AVAILABLE = 3;
    private static final int MENU_INDEX_NEW_MEASUREMENT = 0;
    private static final int MENU_INDEX_EXPORT_RESULT = 1;
    private static final int MENU_INDEX_EXPORT_DETAILS = 2;

    public enum VIEW_STATE {
        MEASUREMENT,
        SHOW_RESULTS
    }
    @Override
    public void onMeasurementFinished(Measurement<Float> data) {
        sendToAPI(data, userId);
    }

    private Measurement<Float> lastMeasuredData;

    public void setLastMeasuredData(Measurement<Float> data) {
        lastMeasuredData = data;
    }
    public void onClickAPI(View view) {
        if (lastMeasuredData != null) {
            sendToAPI(lastMeasuredData, userId);
        } else {
            Toast.makeText(this, "Nincs elérhető mért adat", Toast.LENGTH_SHORT).show();
        }
    }

    private void sendToAPI(Measurement<Float> measurement, String userId) {
        Call<Void> call = apiService.sendPulseData(measurement);

        call.enqueue(new Callback<Void>() {
            @Override
            public void onResponse(@NonNull Call<Void> call, @NonNull Response<Void> response) {
                Toast.makeText(MainActivity.this, "Adatok elküldve az API-ra", Toast.LENGTH_SHORT).show();
            }
            @Override
            public void onFailure(@NonNull Call<Void> call, @NonNull Throwable t) {
                // Hiba esetén
                Log.e("API", "Hiba az adatküldés során: " + t.getMessage());
                Toast.makeText(MainActivity.this, "Hiba az adatküldés során", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private boolean justShared = false;

    @SuppressLint("HandlerLeak")
    private final Handler mainHandler = new Handler(Looper.getMainLooper()) {
        @Override
        public void handleMessage(@NonNull Message msg) {
            super.handleMessage(msg);

            if (msg.what ==  MESSAGE_UPDATE_REALTIME) {
                ((TextView) findViewById(R.id.textView)).setText(msg.obj.toString());
            }

            if (msg.what == MESSAGE_UPDATE_FINAL) {
                ((EditText) findViewById(R.id.editText)).setText(msg.obj.toString());

                // make sure menu items are enabled when it opens.
                Menu appMenu = ((Toolbar) findViewById(R.id.toolbar)).getMenu();

                setViewState(VIEW_STATE.SHOW_RESULTS);
            }

            if (msg.what == MESSAGE_CAMERA_NOT_AVAILABLE) {
                Log.println(Log.WARN, "camera", msg.obj.toString());

                ((TextView) findViewById(R.id.textView)).setText(
                        R.string.camera_not_found
                );
                analyzer.stop();
            }
        }
    };
    private final CameraService cameraService = new CameraService(this, mainHandler);

    @Override
    protected void onResume() {
        super.onResume();
        analyzer = new OutputAnalyzer(this, findViewById(R.id.graphTextureView), mainHandler, apiService, userId);
        TextureView cameraTextureView = findViewById(R.id.textureView2);
        SurfaceTexture previewSurfaceTexture = cameraTextureView.getSurfaceTexture();
        if ((previewSurfaceTexture != null) && !justShared) {
            Surface previewSurface = new Surface(previewSurfaceTexture);
            if (!this.getPackageManager().hasSystemFeature(PackageManager.FEATURE_CAMERA_FLASH)) {
                Snackbar.make(
                        findViewById(R.id.constraintLayout),
                        getString(R.string.noFlashWarning),
                        Snackbar.LENGTH_LONG
                ).show();
            }
            ((Toolbar) findViewById(R.id.toolbar)).getMenu().getItem(MENU_INDEX_NEW_MEASUREMENT).setVisible(false);
            cameraService.start(previewSurface);
            analyzer.measurePulse(cameraTextureView, cameraService, userId);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        cameraService.stop();
        if (analyzer != null) analyzer.stop();
        analyzer = new OutputAnalyzer(this, findViewById(R.id.graphTextureView), mainHandler, apiService, userId);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        UserSession userSession = UserSession.getInstance();
        userId = userSession.getUserId();

        HttpLoggingInterceptor loggingInterceptor = new HttpLoggingInterceptor();
        loggingInterceptor.setLevel(HttpLoggingInterceptor.Level.BODY);

        OkHttpClient httpClient = new OkHttpClient.Builder()
                .addInterceptor(loggingInterceptor)
                .build();

        Retrofit retrofit = new Retrofit.Builder()
                .baseUrl(BASE_URL)
                .addConverterFactory(GsonConverterFactory.create())
                .client(httpClient)
                .build();


        apiService = retrofit.create(ApiService.class);

        ActivityCompat.requestPermissions(this,
                new String[]{Manifest.permission.CAMERA},
                REQUEST_CODE_CAMERA);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        if (requestCode == REQUEST_CODE_CAMERA) {
            if (!(grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED)) {
                Snackbar.make(
                        findViewById(R.id.constraintLayout),
                        getString(R.string.cameraPermissionRequired),
                        Snackbar.LENGTH_LONG
                ).show();
            }
        }
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        Log.i("MENU", "menu is being prepared");

        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.menu, menu);

        return super.onPrepareOptionsMenu(menu);
    }

    public void setViewState(VIEW_STATE state) {
        Menu appMenu = ((Toolbar) findViewById(R.id.toolbar)).getMenu();
        switch (state) {
            case MEASUREMENT:
                appMenu.getItem(MENU_INDEX_NEW_MEASUREMENT).setVisible(false);
                appMenu.getItem(MENU_INDEX_EXPORT_RESULT).setVisible(false);
                appMenu.getItem(MENU_INDEX_EXPORT_DETAILS).setVisible(false);
                findViewById(R.id.floatingActionButton).setVisibility(View.INVISIBLE);
                findViewById(R.id.measure_button).setVisibility(View.INVISIBLE);
                break;
            case SHOW_RESULTS:
                findViewById(R.id.floatingActionButton).setVisibility(View.VISIBLE);
                findViewById(R.id.measure_button).setVisibility(View.VISIBLE);
                appMenu.getItem(MENU_INDEX_EXPORT_RESULT).setVisible(true);
                appMenu.getItem(MENU_INDEX_EXPORT_DETAILS).setVisible(true);
                appMenu.getItem(MENU_INDEX_NEW_MEASUREMENT).setVisible(true);
                break;
        }
    }

    public void onClickNewMeasurement(MenuItem item) {
        onClickNewMeasurement();
    }

    public void onClickNewMeasurement(View view) {
        onClickNewMeasurement();
    }

    public void onClickNewMeasurement() {
        analyzer = new OutputAnalyzer(this, findViewById(R.id.graphTextureView), mainHandler, apiService, userId);

        // clear prior results
        char[] empty = new char[0];
        ((EditText) findViewById(R.id.editText)).setText(empty, 0, 0);
        ((TextView) findViewById(R.id.textView)).setText(empty, 0, 0);

        // hide the new measurement item while another one is in progress in order to wait
        // for the previous one to finish
        // Exporting results cannot be done, either, as it would read from the already cleared UI.
        setViewState(VIEW_STATE.MEASUREMENT);

        TextureView cameraTextureView = findViewById(R.id.textureView2);
        SurfaceTexture previewSurfaceTexture = cameraTextureView.getSurfaceTexture();

        if (previewSurfaceTexture != null) {
            // this first appears when we close the application and switch back
            // - TextureView isn't quite ready at the first onResume.
            Surface previewSurface = new Surface(previewSurfaceTexture);
            cameraService.start(previewSurface);
            analyzer.measurePulse(cameraTextureView, cameraService, userId);
        }
    }


    public void onClickExportResult(MenuItem item) {
        final Intent intent = getTextIntent((String) ((TextView) findViewById(R.id.textView)).getText());
        justShared = true;
        startActivity(Intent.createChooser(intent, getString(R.string.send_output_to)));
    }

    public void onClickExportDetails(MenuItem item) {
        final Intent intent = getTextIntent(((EditText) findViewById(R.id.editText)).getText().toString());
        justShared = true;
        startActivity(Intent.createChooser(intent, getString(R.string.send_output_to)));
    }

    private Intent getTextIntent(String intentText) {
        final Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("text/plain");
        intent.putExtra(
                Intent.EXTRA_SUBJECT,
                String.format(
                        getString(R.string.output_header_template),
                        new SimpleDateFormat(
                                getString(R.string.dateFormat),
                                Locale.getDefault()
                        ).format(new Date())
                ));
        intent.putExtra(Intent.EXTRA_TEXT, intentText);
        return intent;
    }
}
