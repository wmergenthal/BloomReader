package org.sil.bloom.reader;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.pm.PackageManager;
import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

import android.util.Log;
import android.util.SparseArray;
import android.view.Menu;
import android.view.MenuItem;
import android.view.SurfaceView;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import com.google.android.gms.vision.CameraSource;
import com.google.android.gms.vision.Detector;
import com.google.android.gms.vision.barcode.Barcode;
import com.google.android.gms.vision.barcode.BarcodeDetector;

import java.io.IOException;

import org.sil.bloom.reader.wifi.AcceptNotificationHandler;

// WM -- This is an edited version of SyncActivity.java in HearThisAndroid.
//       I am removing everything having to do with SyncServer since BloomReader
//       already instantiates and uses it for Wi-Fi book transfer. I also remove
//       things having to do with communications, file transfer, etc, for the same
//       reason: BloomReader already handles those.

public class SyncActivity extends AppCompatActivity implements
        AcceptNotificationHandler.NotificationListener {
        //AcceptFileHandler.IFileReceivedNotification,  // WM, BloomReader already implements
        //RequestFileHandler.IFileSentNotification {    // WM, file I/O not needed

    Button scanBtn;
    Button continueButton;
    TextView ipView;
    SurfaceView preview;
    private static final int REQUEST_CAMERA_PERMISSION = 201;
    boolean scanning = false;
    TextView progressView;

    private BarcodeDetector barcodeDetector;
    private CameraSource cameraSource;
    private static String qrDecodedData = null;
    private static boolean qrDecodedDataIsReady = false;
    private static boolean shouldStopNow = false;

    // Getter for decoded QR scan data.
    public static String GetQrData() {
        return qrDecodedData;
    }

    // Flag getter indicating whether decoded data from QR scan is ready.
    public static boolean GetQrDataAvailable() {
        return qrDecodedDataIsReady;
    }

    // Flag setter by which another class can tell this activity to close.
    public static void ActivityStop() {
        Log.d("WM","SyncActivity::ActivityStop, setting flag to stop");
        shouldStopNow = true;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Log.d("WM","SyncActivity::onCreate, starting, shouldStopNow = " + shouldStopNow);
        Log.d("WM","                                  qrDecodedDataIsReady = " + qrDecodedDataIsReady);

        if (shouldStopNow == true) {
            Log.d("WM","SyncActivity::onCreate, shouldStopNow=true so close screen and abort");
            finish();
        }

        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_sync);
        getSupportActionBar().setTitle(R.string.sync_title);
        progressView = (TextView) findViewById(R.id.progress);
        continueButton = (Button) findViewById(R.id.continue_button);
        preview = (SurfaceView) findViewById(R.id.surface_view);
        preview.setVisibility(View.INVISIBLE);
        continueButton.setEnabled(false);
        final SyncActivity thisActivity = this;

        // TODO: WM, we never get into here. Can this method be removed?
        continueButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                Log.d("WM","SyncActivity::onCreate.continueButton.setOnClickListener.onClick, starting");
                thisActivity.finish();
            }
        });

        Log.d("WM","SyncActivity::onCreate, done");
    }

    @Override
    protected void onResume() {
        Log.d("WM","SyncActivity::onResume, calling super.onResume() ");
        super.onResume();
        Log.d("WM","SyncActivity::onResume, did super.onResume(), returning");
    }

    @Override
    protected void onPause() {
        Log.d("WM","SyncActivity::onPause, stopping camera");
        super.onPause();
        if (cameraSource != null) {
            cameraSource.release();
            cameraSource = null;
        }
        Log.d("WM","SyncActivity::onPause, done");
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        Log.d("WM","SyncActivity::onCreateOptionsMenu, starting");
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.menu_sync, menu);
        ipView = (TextView) findViewById(R.id.ip_address);
        scanBtn = (Button) findViewById(R.id.scan_button);
        scanBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // This approach is deprecated, but the new approach (using ML_Kit)
                // requires us to increase MinSdk from 18 to 19 (4.4) and barcode scanning is
                // not important enough for us to do that. This works fine on an app that targets
                // SDK 33, at least while running on Android 12.
                Log.d("WM","SyncActivity::onCreateOptionsMenu.onClick, starting");
                barcodeDetector = new BarcodeDetector.Builder(SyncActivity.this)
                        .setBarcodeFormats(Barcode.QR_CODE)
                        .build();

                if (cameraSource != null) {
                    //cameraSource.stop();
                    cameraSource.release();
                    cameraSource = null;
                }

                cameraSource = new CameraSource.Builder(SyncActivity.this, barcodeDetector)
                        .setRequestedPreviewSize(1920, 1080)
                        .setAutoFocusEnabled(true)
                        .build();
                Log.d("WM","SyncActivity::onCreateOptionsMenu.onClick, new CameraSource done");

                barcodeDetector.setProcessor(new Detector.Processor<Barcode>() {

                    @Override
                    public void release() {
                        // Toast.makeText(getApplicationContext(), "To prevent memory leaks barcode scanner has been stopped", Toast.LENGTH_SHORT).show();
                    }

                    @Override
                    public void receiveDetections(Detector.Detections<Barcode> detections) {
                        //Log.d("WM","SyncActivity::onCreateOptionsMenu.onClick, receiveDetections starting"); // generates much output
                        final SparseArray<Barcode> barcodes = detections.getDetectedItems();
                        if (scanning && barcodes.size() != 0) {
                            qrDecodedData = barcodes.valueAt(0).displayValue;
                            Log.d("WM","SyncActivity::onCreateOptionsMenu,");
                            Log.d("WM","   barcodes.size() = " + barcodes.size());
                            Log.d("WM","   barcode content = " + qrDecodedData);
                            if (qrDecodedData != null) {
                                scanning = false; // don't want to repeat this if it finds the image again
                                Log.d("WM","SyncActivity::onCreateOptionsMenu, non-null content");
                                runOnUiThread(new Runnable() {
                                                  @Override
                                                  public void run() {
                                                      // Enhance: do something (add a magic number or label?) so we can tell if they somehow scanned
                                                      // some other QR code. We've reduced the chances by telling the BarCodeDetector to
                                                      // only look for QR codes, but conceivably the user could find something else.
                                                      // It's only used for one thing: to give us the book info that was advertised over
                                                      // UDP but we never heard. If we get it here via QR code we will send Desktop the same
                                                      // book request we would have sent had we received the advert via UDP. So if it's no good,
                                                      // there'll probably be an exception, and it will be ignored, and nothing will happen
                                                      // except that whatever text the QR code represents shows on the screen, which might
                                                      // provide some users a clue that all is not well.
                                                      //
                                                      // Display the decoded QR content on the tablet screen.
                                                      Log.d("WM","SyncActivity::onCreateOptionsMenu, show QR data onscreen");
                                                      ipView.setText(qrDecodedData);
                                                      preview.setVisibility(View.INVISIBLE);
                                                      qrDecodedDataIsReady = true;

                                                      // We have what we need from the scan so turn off the camera.
                                                      Log.d("WM","SyncActivity::onCreateOptionsMenu, turn off camera");
                                                      cameraSource.stop();
                                                      cameraSource.release();
                                                      cameraSource = null;

                                                      // We need to close this screen and return to the Wi-Fi screen. We can do
                                                      // that *after* our QR data has been received and processed. Until then, spin
                                                      // in a slow polling loop waiting for permission to shut down.
                                                      while (shouldStopNow == false) {
                                                          Log.d("WM", "SyncActivity::onCreateOptionsMenu, waiting for permission to close");
                                                          try {
                                                              Thread.sleep(1000);
                                                          } catch (InterruptedException e) {
                                                              e.printStackTrace();
                                                          }
                                                      }
                                                      // Our work is done here. Reset QR data buffer (so duplicate book requests
                                                      // don't get made) and flags, and close the screen.
                                                      Log.d("WM","SyncActivity::onCreateOptionsMenu, permission granted, closing");
                                                      qrDecodedData = null;
                                                      qrDecodedDataIsReady = false;
                                                      //shouldStopNow = false;
                                                      finish();
                                                  }
                                              });
                            } else {
                                Log.d("WM","SyncActivity::onCreateOptionsMenu, null contents");
                            }
                        } else {
                            //Log.d("WM","SyncActivity::onCreateOptionsMenu, no data to decode");
                        }
                    }
                });

                Log.d("WM","SyncActivity::onCreateOptionsMenu, exited barcodeDetector.setProcessor");

                if (ActivityCompat.checkSelfPermission(SyncActivity.this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                    Log.d("WM","SyncActivity::onCreateOptionsMenu, PackageManager 01");
                    try {
                        scanning = true;
                        preview.setVisibility(View.VISIBLE);
                        cameraSource.start(preview.getHolder());
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                } else {
                    Log.d("WM","SyncActivity::onCreateOptionsMenu, PackageManager 02");
                    ActivityCompat.requestPermissions(SyncActivity.this, new
                            String[]{Manifest.permission.CAMERA}, REQUEST_CAMERA_PERMISSION);
                }
            }
        });
        // WM -- Reader already handles communication back to Desktop (TCP mechanism), so
        //       displaying our IP address to the user (next 3 lines) is optional.
        //String ourIpAddress = getOurIpAddress();
        //TextView ourIpView = (TextView) findViewById(R.id.our_ip_address);
        //ourIpView.setText(ourIpAddress);
        AcceptNotificationHandler.addNotificationListener(this);
        Log.d("WM","SyncActivity::onCreateOptionsMenu, done, returning");
        return true;
    }

    @SuppressLint("MissingPermission")
    @Override
    public void onRequestPermissionsResult(
            int requestCode,
            String permissions[],
            int[] grantResults) {
        Log.d("WM","SyncActivity::onRequestPermissionsResult, request " + requestCode);
        // TODO, WM -- determine whether the next line super.onRequestPermissionsResult() is needed.
        //       The first time I ran this the program crashed after the line above. A popup said
        //       to also call the superclass' version of this, so I added it (next line). When I
        //       ran it there was no crash, but now when I comment it out there ALSO is no crash.
        //       So I don't really know yet what's going on...
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        Log.d("WM","SyncActivity::onRequestPermissionsResult, did super call");
        switch (requestCode) {
            case REQUEST_CAMERA_PERMISSION:
                if (grantResults.length > 0) {
                    if (grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                        try {
                            scanning = true;
                            preview.setVisibility(View.VISIBLE);
                            Log.d("WM","SyncActivity::onRequestPermissionsResult, calling cameraSource.start()");
                            cameraSource.start(preview.getHolder());
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                    } else {
                        Log.d("WM","SyncActivity::onRequestPermissionsResult, grantResults[0] = " + grantResults[0]);
                    }
                } else {
                    Log.d("WM","SyncActivity::onRequestPermissionsResult, grantResults.length = " + grantResults.length);
                }
        }
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        int id = item.getItemId();

        //noinspection SimplifiableIfStatement
        //if (id == R.id.action_settings) {
        //    return true;
        //}

        Log.d("WM","SyncActivity::onOptionsItemSelected, got item " + id + ", call super() on it");
        return super.onOptionsItemSelected(item);
    }

    @Override
    public void onNotification(String message) {
        Log.d("WM","SyncActivity::onNotification, called with " + message);
        AcceptNotificationHandler.removeNotificationListener(this);
        setProgress(getString(R.string.sync_success));
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                Log.d("WM","SyncActivity::onNotification, calling continueButton.setEnabled(true)");
                continueButton.setEnabled(true);
            }
        });
    }

    // WM -- can this be removed? Reader shows progress, seems we need not also do it here.
    //       If we do remove this then also remove the call in onNotification().
    void setProgress(final String text) {
        Log.d("WM","SyncActivity::setProgress, remove?");
        runOnUiThread(new Runnable() {
            public void run() {
                progressView.setText(text);
            }
        });
    }
}
