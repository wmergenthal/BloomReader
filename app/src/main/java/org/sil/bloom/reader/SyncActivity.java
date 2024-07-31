package org.sil.bloom.reader;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.AsyncTask;
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
import java.net.DatagramPacket;  // WM, can remove?
import java.net.DatagramSocket;  // WM, can remove?
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.UnknownHostException;  // WM, can remove?
import java.util.Date;
import java.util.Enumeration;

//import org.sil.bloom.reader.R;  // WM, added
import org.sil.bloom.reader.wifi.AcceptNotificationHandler;
import org.sil.bloom.reader.wifi.AcceptFileHandler;
import org.sil.bloom.reader.wifi.RequestFileHandler;

// WM -- I will comment out anything in this file having to do with SyncServer,
//       since BloomReader already instantiates and uses it for Wi-Fi book transfer.

public class SyncActivity extends AppCompatActivity implements
        AcceptNotificationHandler.NotificationListener,
        //AcceptFileHandler.IFileReceivedNotification,  // WM, BloomReader already implements
        RequestFileHandler.IFileSentNotification {

    Button scanBtn;
    Button continueButton;
    TextView ipView;
    SurfaceView preview;
    //int desktopPort = 11007; // port on which the desktop is listening for our IP address
    private static final int REQUEST_CAMERA_PERMISSION = 201;
    boolean scanning = false;
    TextView progressView;

    private BarcodeDetector barcodeDetector;
    private CameraSource cameraSource;
    private static String qrDecodedData;
    private static boolean qrDecodedDataIsReady = false;

    // Getter for decoded QR scan data.
    public static String GetQrData() {
        return qrDecodedData;
    }

    // Getter for flag indicating whether decoded data from QR scan is ready.
    public static boolean GetQrDataAvailable() {
        return qrDecodedDataIsReady;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Log.d("WM","SyncActivity::onCreate, starting");
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_sync);
        getSupportActionBar().setTitle(R.string.sync_title);
        //startSyncServer();  // WM, see comment near TOF regarding SyncServer
        progressView = (TextView) findViewById(R.id.progress);
        continueButton = (Button) findViewById(R.id.continue_button);
        preview = (SurfaceView) findViewById(R.id.surface_view);
        preview.setVisibility(View.INVISIBLE);
        continueButton.setEnabled(false);
        final SyncActivity thisActivity = this;
        continueButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                Log.d("WM","SyncActivity::onCreate.continueButton.setOnClickListener.onClick, starting");
                thisActivity.finish();
            }
        });
        Log.d("WM","SyncActivity::onCreate, done");
    }

    // WM, see comment near TOF regarding SyncServer
    //private void startSyncServer() {
    //    Intent serviceIntent = new Intent(this, SyncService.class);
    //    startService(serviceIntent);
    //}

    @Override
    protected void onResume() {
        Log.d("WM","SyncActivity::onResume, calling super.onResume() ");
        super.onResume();
        Log.d("WM","SyncActivity::onResume, did super.onResume(), returning");
        // WM -- the next line is incompatible with requestFileReceivedNotification()
        //       which already exists in AcceptFileHandler.java and is used by Reader.
        //       But actually we don't need either of the next two lines since, unlike
        //       in HTA, this class doesn't need to do file transfers.
        //AcceptFileHandler.requestFileReceivedNotification(this);
        //RequestFileHandler.requestFileSentNotification((this));
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

    // WM, do we need this menu?
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
                Log.d("WM","SyncActivity::onCreateOptionsMenu.onClick, build done");

                if (cameraSource != null)
                {
                    //cameraSource.stop();
                    cameraSource.release();
                    cameraSource = null;
                    Log.d("WM","SyncActivity::onCreateOptionsMenu.onClick, deleted cameraSource");
                }

                cameraSource = new CameraSource.Builder(SyncActivity.this, barcodeDetector)
                        .setRequestedPreviewSize(1920, 1080)
                        .setAutoFocusEnabled(true)
                        .build();
                Log.d("WM","SyncActivity::onCreateOptionsMenu.onClick, new CameraSource done");

                barcodeDetector.setProcessor(new Detector.Processor<Barcode>() {
                    //Log.d("WM","SyncActivity::onCreateOptionsMenu.onClick.barcodeDetector.setProcessor, starting");

                    @Override
                    public void release() {
                        // Toast.makeText(getApplicationContext(), "To prevent memory leaks barcode scanner has been stopped", Toast.LENGTH_SHORT).show();
                    }

                    @Override
                    public void receiveDetections(Detector.Detections<Barcode> detections) {
                        // WM, this debug output line spews MUCH content, suppress it
                        //Log.d("WM","SyncActivity::onCreateOptionsMenu.setProcessor.receiveDetections, starting");
                        final SparseArray<Barcode> barcodes = detections.getDetectedItems();
                        if (scanning && barcodes.size() != 0) {
                            //String contents = barcodes.valueAt(0).displayValue;
                            qrDecodedData = barcodes.valueAt(0).displayValue;
                            Log.d("WM","SyncActivity::onCreateOptionsMenu.setProcessor,");
                            Log.d("WM","   barcodes.size() = " + barcodes.size());
                            Log.d("WM","   barcode content = " + qrDecodedData);
                            if (qrDecodedData != null) {
                                scanning = false; // don't want to repeat this if it finds the image again
                                Log.d("WM","SyncActivity::onCreateOptionsMenu.setProcessor, non-null content");
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
                                                      Log.d("WM","SyncActivity::onCreateOptionsMenu.setProcessor, show QR data");
                                                      ipView.setText(qrDecodedData);
                                                      preview.setVisibility(View.INVISIBLE);
                                                      qrDecodedDataIsReady = true;

                                                      // We have what we need from the scan so turn off the camera.
                                                      Log.d("WM","SyncActivity::onCreateOptionsMenu.setProcessor, turn off camera");
                                                      cameraSource.stop();
                                                      cameraSource.release();
                                                      cameraSource = null;
                                                  }
                                              });

                            } else {
                                Log.d("WM","SyncActivity::onCreateOptionsMenu.setProcessor.receiveDetections, null contents");
                            }
                        }
                    }
                });

                if (ActivityCompat.checkSelfPermission(SyncActivity.this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                    try {
                        scanning = true;
                        preview.setVisibility(View.VISIBLE);
                        cameraSource.start(preview.getHolder());
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                } else {
                    ActivityCompat.requestPermissions(SyncActivity.this, new
                            String[]{Manifest.permission.CAMERA}, REQUEST_CAMERA_PERMISSION);
                }
            }
        });
        // WM -- Reader already handles communication back to Desktop (TCP mechanism), so displaying
        //       our IP address to the user (next 4 lines) is optional.
        Log.d("WM","SyncActivity::onCreateOptionsMenu, display Android's IP address");
        String ourIpAddress = getOurIpAddress();
        TextView ourIpView = (TextView) findViewById(R.id.our_ip_address);
        ourIpView.setText(ourIpAddress);
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
        // WM -- very first time I ran this the program crashed after the line above. A popup said
        // that calling the superclass' version of this should also be done, so I added it (next
        // line). When I ran it there was no crash, but now when I comment it out there ALSO is no
        // crash. So I don't really know yet what's going on...
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

    // Get the IP address of this device (on the WiFi network) to transmit to the desktop.
    //
    // WM -- Reader already handles this communication back to Desktop so remove this function
    //       when I understand how to safely excise things from the menu.
    private String getOurIpAddress() {
        Log.d("WM","SyncActivity::getOurIpAddress, starting");
        String ip = "";
        try {
            Enumeration<NetworkInterface> enumNetworkInterfaces = NetworkInterface.getNetworkInterfaces();
            while (enumNetworkInterfaces.hasMoreElements()) {
                NetworkInterface networkInterface = enumNetworkInterfaces.nextElement();
                Enumeration<InetAddress> enumInetAddress = networkInterface.getInetAddresses();
                while (enumInetAddress.hasMoreElements()) {
                    InetAddress inetAddress = enumInetAddress.nextElement();

                    if (inetAddress.isSiteLocalAddress()) {
                        Log.d("WM","SyncActivity::getOurIpAddress, returning ok: " + inetAddress.getHostAddress());
                        return inetAddress.getHostAddress();
                    }
                }
            }
        } catch (SocketException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
            ip += "Something Wrong! " + e.toString() + "\n";
        }

        Log.d("WM","SyncActivity::getOurIpAddress, returning BAD: " + ip);
        return ip;
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

    // WM -- can this be removed? Reader shows progress, seems we need not also do it here...
    void setProgress(final String text) {
        Log.d("WM","SyncActivity::setProgress, remove?");
        runOnUiThread(new Runnable() {
            public void run() {
                progressView.setText(text);
            }
        });
    }

    Date lastProgress = new Date();
    boolean stopUpdatingProgress = false;

    // WM -- NewBookListenerService has a same-name version of this function, which is used for
    //       Wi-Fi book transfer.
    //
    //@Override
    //public void receivingFile(final String name) {
    //    // To prevent excess flicker and wasting compute time on progress reports,
    //    // only change once per second.
    //    if (new Date().getTime() - lastProgress.getTime() < 1000)
    //        return;
    //    lastProgress = new Date();
    //    setProgress("receiving " + name);
    //}

    // WM -- NewBookListenerService has a same-name version of this function, which is used for
    //       Wi-Fi book transfer.
    //
    //@Override
    //public void receivedFile(String name, boolean success) { }

    // WM -- I don't think we should need this function. Reader already handles file
    //       transfer with Desktop. If I do remove this must also remove its caller in
    //       the file from HTA RequestFileHandler.java -- and maybe that entire file?
    @Override
    public void sendingFile(final String name) {
        if (new Date().getTime() - lastProgress.getTime() < 1000)
            return;
        lastProgress = new Date();
        setProgress("sending " + name);
    }

    // WM -- we don't need this because BloomReader already handles communication
    //       with BloomDesktop via a TCP mechanism.
    //
    // This class is responsible to send one message packet to the IP address we
    // obtained from the desktop, containing the Android's own IP address.
    //private class SendMessage extends AsyncTask<Void, Void, Void> {
    //
    //    public String ourIpAddress;
    //    @Override
    //    protected Void doInBackground(Void... params) {
    //        try {
    //            String ipAddress = ipView.getText().toString();
    //            InetAddress receiverAddress = InetAddress.getByName(ipAddress);
    //            DatagramSocket socket = new DatagramSocket();
    //            byte[] buffer = ourIpAddress.getBytes("UTF-8");
    //            DatagramPacket packet = new DatagramPacket(buffer, buffer.length, receiverAddress, desktopPort);
    //            socket.send(packet);
    //        } catch (UnknownHostException e) {
    //            e.printStackTrace();
    //        } catch (IOException e) {
    //            e.printStackTrace();
    //        }
    //        return null;
    //    }
    //}
}
