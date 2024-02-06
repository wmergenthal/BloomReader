package org.sil.bloom.reader.wifi;

import android.app.AlertDialog;  // WM, added
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;  // WM, added
import android.content.Intent;
import android.content.IntentFilter;
import android.net.NetworkInfo;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import android.util.Log;  // WM, added
import android.view.LayoutInflater;  // WM, added
import android.view.View;
import android.widget.Button;
import android.widget.EditText;  // WM, added
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;  // WM, added

import org.sil.bloom.reader.BaseActivity;
import org.sil.bloom.reader.BloomReaderApplication;  // WM, added
import org.sil.bloom.reader.MainActivity;
import org.sil.bloom.reader.R;

import java.lang.reflect.Method;
import java.util.ArrayList;

// An activity that is made to look like a dialog (see the theme associated with it in
// the main manifest and defined in styles.xml) and which implements the command to receive
// Bloom books from Wifi (i.e., from a desktop running Bloom...eventually possibly from
// another copy of BloomReader). This is launched from a menu option in the main activity.
public class GetFromWiFiActivity extends BaseActivity {

    ArrayList<String> newBookPaths = new ArrayList<String>();
    ProgressReceiver mProgressReceiver;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_get_from_wi_fi);

        mProgressReceiver = new ProgressReceiver();
        LocalBroadcastManager.getInstance(this).registerReceiver(mProgressReceiver,
                new IntentFilter(NewBookListenerService.BROADCAST_BOOK_LISTENER_PROGRESS));

        final Button okButton = (Button)findViewById(R.id.wifiOk);
        okButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                onBackPressed();
            }
        });

        if (BloomReaderApplication.simulateQrCodeUsedInsteadOfAdvert) {
            // WM, EXPERIMENT
            // As commented in onNavigationItemSelected(), we add a text box to the Wi-Fi book share
            // screen for the user to enter data normally provided by Desktop's UDP advertisement:
            //    Desktop IP address, book title, book version
            // This is a proof of concept to verify that these data items *can* be provided by an
            // alternative such as QR code.

            // Create dialog box layout and the dialog itself.
            LayoutInflater inflater = (LayoutInflater) getSystemService(Context.LAYOUT_INFLATER_SERVICE);
            View dialogView = inflater.inflate(R.layout.activity_enter_desktop_ip_address, null);
            AlertDialog.Builder builder = new AlertDialog.Builder(this);

            // Set the dialog box title and message.
            builder.setTitle("QR code alternative - text entry");
            builder.setMessage("Enter as follows: Desktop IP address, semicolon, book title, semicolon, book version (from Android Studio log):");

            // Create EditText view for user to enter text, then make that the dialog box's view.
            EditText userInput = new EditText(this);
            builder.setView(userInput);

            // Get user input and evaluate. Use it if valid, otherwise ignore it.
            builder.setPositiveButton("OK", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    // Get what the user entered.
                    String inputText = userInput.getText().toString();

                    // User input should contain 3 elements delimited with semicolons, like this:
                    // Desktop's IP address, then ';', then book title, then ';', then book version
                    //   - Parse these things out
                    //   - Verify the number of elements is as expected; if not then put up an error
                    //     message instructing the user to try again
                    //   - Sanity check the elements; ignore them if something is wrong
                    String delims = ";";
                    String[] tokens = inputText.split(delims);
                    int numElements = tokens.length;
                    if (numElements != 3) {
                        Toast.makeText(getApplicationContext(), "You provided " + numElements +
                                " elements, 3 are required. Try again.", Toast.LENGTH_LONG).show();
                        Log.d("WM", "onClick: got " + numElements + " but 3 are required, bail");
                        return;
                    }

                    Log.d("WM", "onClick: ipAddr  = " + tokens[0]);
                    Log.d("WM", "         title   = " + tokens[1]);
                    Log.d("WM", "         version = " + tokens[2]);

                    // IP address: check for valid IPv4 format; if incorrect, ignore and show an error
                    // Book title: no check needed, downstream logic handles if null (VERIFY!!)
                    // Book version: no check needed, downstream logic handles if null (VERIFY!!)
                    boolean isValidIPv4Addr = validateIPv4(tokens[0]);
                    if (isValidIPv4Addr) {
                        Toast.makeText(getApplicationContext(), "You entered: " + inputText +
                                "\nLooks good, we\'ll take it", Toast.LENGTH_LONG).show();
                        // Make user input available to the book-receive subsystem.
                        BloomReaderApplication.setDesktopIpAddrInQrCode(tokens[0]);
                        BloomReaderApplication.setBookTitleInQrCode(tokens[1]);
                        BloomReaderApplication.setBookVersionInQrCode(tokens[2]);
                    } else {
                        Toast.makeText(getApplicationContext(), "You entered: " + inputText +
                                "\nIP address is invalid. Try again.", Toast.LENGTH_LONG).show();
                    }
                }
            });

            // Dialog composition complete. Render it.
            builder.show();

            // WM, END EXPERIMENT
        }
    }

    // Check whether the passed in string has a valid IPv4 format. If it does then return true,
    // otherwise return false. Make it public static so other code can use it.
    // Based on code from:
    // https://stackoverflow.com/questions/4581877/validating-ipv4-string-in-java?rq=4
    public static boolean validateIPv4(String ip) {
        try {
            if (ip == null || ip.isEmpty()) {
                Log.d("WM","validateIPv4: " + ip + ", error, is null or empty");
                return false;
            }

            String[] parts = ip.split("\\.");
            if (parts.length != 4) {
                Log.d("WM","validateIPv4: " + ip + ", error, not 4 sections");
                return false;
            }

            for (String s : parts) {
                int i = Integer.parseInt(s);
                if ((i < 0) || (i > 255)) {
                    Log.d("WM","validateIPv4: " + ip + ", error, not 0 - 255");
                    return false;
                }
            }

            if (ip.endsWith(".")) {
                Log.d("WM","validateIPv4: " + ip + ", error, ends with a dot");
                return false;
            }

            Log.d("WM","validateIPv4: " + ip + ", format ok");
            return true;

        } catch (NumberFormatException nfe) {
            Log.e("WM","GetFromWiFiActivity::validateIPv4, exception: " + nfe);
            return false;
        }
    }

    @Override
    protected void onDestroy() {
        // Unregister since the activity is about to be closed.
        LocalBroadcastManager.getInstance(this).unregisterReceiver(mProgressReceiver);
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        // inform the main activity of the books we added.
        Intent result = new Intent();
        result.putExtra(MainActivity.NEW_BOOKS, newBookPaths.toArray(new String[0]));
        setResult(RESULT_OK, result);
        finish();
    }

    // This is used by various companion classes that want to display stuff in our progress window.
    public static void sendProgressMessage(Context context, String message) {
        Intent progressIntent = new Intent(NewBookListenerService.BROADCAST_BOOK_LISTENER_PROGRESS)
                .putExtra(NewBookListenerService.BROADCAST_BOOK_LISTENER_PROGRESS_CONTENT, message);
        LocalBroadcastManager.getInstance(context).sendBroadcast(progressIntent);
    }

    public static void sendBookLoadedMessage(Context context, String bookpath) {
        Intent bookLoadedIntent = new Intent(NewBookListenerService.BROADCAST_BOOK_LISTENER_PROGRESS)
                .putExtra(NewBookListenerService.BROADCAST_BOOK_LOADED, bookpath);
        LocalBroadcastManager.getInstance(context).sendBroadcast(bookLoadedIntent);
    }

    // This class supports receiving the messages sent by calls to sendProgressMessage()
    private class ProgressReceiver extends BroadcastReceiver {

        @Override
        public void onReceive(Context context, Intent intent) {
            Bundle extras = intent.getExtras();
            if (extras.containsKey(NewBookListenerService.BROADCAST_BOOK_LOADED)) {
                newBookPaths.add(extras.getString(NewBookListenerService.BROADCAST_BOOK_LOADED));
                return;
            }
            TextView progressView = (TextView) findViewById(R.id.wifi_progress);
            progressView.append(intent.getStringExtra(NewBookListenerService.BROADCAST_BOOK_LISTENER_PROGRESS_CONTENT));
            // Scroll to the bottom so the new message is visible
            // see https://stackoverflow.com/questions/3506696/auto-scrolling-textview-in-android-to-bring-text-into-view
            // Hints there suggest we might not need a scroll view wrapped around our text view...we can make the
            // text view itself scrollable. That might be more efficient for a long report.
            // This is good enough for now.
            ScrollView progressScroller = (ScrollView) findViewById(R.id.wifi_progress_scroller);
            progressScroller.fullScroll(View.FOCUS_DOWN);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        String wifiName = getWifiName(this);
        if (wifiName == null)
        {
            GetFromWiFiActivity.sendProgressMessage(this, getString(R.string.no_wifi_connected) + "\n\n");
        }
        else {
            // For some reason the name of the ILC network comes with quotes already around it.
            // Since we want one lot of quotes but not two, decided to add them if missing.
            if (!wifiName.startsWith("\""))
                wifiName = "\"" + wifiName;
            if (!wifiName.endsWith("\""))
                wifiName = wifiName + "\"";
            GetFromWiFiActivity.sendProgressMessage(this, String.format(getString(R.string.looking_for_adds), wifiName) + "\n\n");

            startBookListener();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        // Enhance: do we want to do something to allow an in-progress transfer to complete?
        stopBookListener();
    }

    @Override
    protected void onNewOrUpdatedBook(String fullPath) {
        // This never gets called!
    }

    // Get the human-readable name of the WiFi network that the Android is connected to
    // (or null if not connected over WiFi).
    public String getWifiName(Context context) {
        WifiManager manager = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (manager.isWifiEnabled()) {
            WifiInfo wifiInfo = manager.getConnectionInfo();
            if (wifiInfo != null) {
                NetworkInfo.DetailedState state = WifiInfo.getDetailedStateOf(wifiInfo.getSupplicantState());
                if (state == NetworkInfo.DetailedState.CONNECTED || state == NetworkInfo.DetailedState.OBTAINING_IPADDR) {
                    return wifiInfo.getSSID();
                }
            }
        }

        if (deviceHotspotActive(manager))
            return getString(R.string.device_hotspot);

        return null;
    }

    // Android doesn't have a public API for finding this out,
    // but that's nothing a Reflection hack can't solve...
    private boolean deviceHotspotActive(WifiManager manager) {
        try {
            final Method method = manager.getClass().getDeclaredMethod("isWifiApEnabled");
            method.setAccessible(true);
            return (Boolean) method.invoke(manager);
        }
        catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    private void startBookListener() {
        Intent serviceIntent = new Intent(this, NewBookListenerService.class);
        startService(serviceIntent);
    }

    private void stopBookListener() {
        Intent serviceIntent = new Intent(this, NewBookListenerService.class);
        stopService(serviceIntent);
    }
}
