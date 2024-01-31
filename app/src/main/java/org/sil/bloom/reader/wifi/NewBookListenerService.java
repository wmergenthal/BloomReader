package org.sil.bloom.reader.wifi;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.wifi.WifiManager;
import android.os.AsyncTask;
import android.os.IBinder;
import android.util.Log;  // WM, added
import androidx.annotation.Nullable;

import org.json.JSONException;
import org.json.JSONObject;
import org.sil.bloom.reader.BaseActivity;
import org.sil.bloom.reader.BloomReaderApplication;
import org.sil.bloom.reader.IOUtilities;
import org.sil.bloom.reader.MainActivity;
import org.sil.bloom.reader.R;
//import org.sil.bloom.reader.models.BookCollection;

import java.io.File;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.io.DataOutputStream;  // WM, added
import java.io.OutputStream;      // WM, added
import java.io.InputStream;       // WM, added
import java.net.Socket;           // WM, added
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Set;

import static org.sil.bloom.reader.BloomReaderApplication.getOurDeviceName;


/**
 * Created by Thomson on 7/22/2017.
 * This class listens for a computer on the local network running Bloom to advertise a book as
 * available for download. When one is published, it gets it.
 * Based on ideas from https://gist.github.com/finnjohnsen/3654994
 */

public class NewBookListenerService extends Service {
    DatagramSocket socket;
    Thread UDPBroadcastThread;
    private Boolean shouldRestartSocketListen=true;

    // port on which the desktop is listening for our book request.
    // Must match Bloom Desktop UDPListener._portToListen.
    // Must be different from ports in NewBookListenerService.startListenForUDPBroadcast
    // and SyncServer._serverPort.
    static int desktopPort = 5915;
    boolean gettingBook = false;
    boolean httpServiceRunning = false;
    int addsToSkipBeforeRetry;
    boolean reportedVersionProblem = false;
    private Set<String> _announcedBooks = new HashSet<String>();
    WifiManager.MulticastLock multicastLock;

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void listen(Integer port) throws Exception {
        byte[] recvBuf = new byte[15000];
        if (socket == null || socket.isClosed()) {
            socket = new DatagramSocket(port);
            socket.setBroadcast(true);
        }

        // This seems to have become necessary for receiving a packet around Android 8.
        WifiManager wifi;
        wifi = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        multicastLock = wifi.createMulticastLock("lock");
        multicastLock.acquire();

        try {
            DatagramPacket packet = new DatagramPacket(recvBuf, recvBuf.length);
            //Log.e("UDP", "Waiting for UDP broadcast");
            Log.d("UDP", "Waiting for UDP broadcast");  // WM, temporary
            socket.receive(packet);

            // WM, debug only: show a packet received and print its payload.
            int pktLen = packet.getLength();
            byte[] pktBytes = packet.getData();
            Log.d("WM", "listen: got UDP packet (" + pktLen + " bytes) from " + packet.getAddress().getHostAddress());
            String pktString = new String(pktBytes);
            Log.d("WM", "   advertisement = " + pktString.substring(0, pktLen));
            // WM, end of debug packet print

            if (gettingBook) {
                Log.d("WM","listen: ignore advert, getting book");  // WM, temporary
                return; // ignore new advertisements while downloading. Will receive again later.
            }
            if (addsToSkipBeforeRetry > 0) {
                // We ignore a few adds after requesting a book before we (hopefully) start receiving.
                addsToSkipBeforeRetry--;
                Log.d("WM","listen: ignore advert, decr'd addsToSkipBeforeRetry, now = " + addsToSkipBeforeRetry);  // WM, temporary
                return;
            }

            // Pull out needed data elements from the advertisement.
            String message = new String(packet.getData()).trim();
            JSONObject msgJson = new JSONObject(message);
            String senderIP = new String(packet.getAddress().getHostAddress());
            String title = new String(msgJson.getString("title"));
            Log.d("WM","listen: got all data from UDP advert");

            if (BloomReaderApplication.simulateQrCodeUsedInsteadOfAdvert) {
                // EXPERIMENT: QR code simulation
                // Wait until we have user input for both IP address and book title.
                while (BloomReaderApplication.gotUserInput == false) {
                    Thread.sleep(500);
                }
                senderIP = new String(BloomReaderApplication.getDesktopIpAddrInQrCode());
                Log.d("WM","listen: got manual entry input");

                // Split up user input into separate strings and use them to overwrite what
                // we got from the UDP advertisement.

                //Log.d("WM","listen: overwrite with IP addr from manual entry: " + senderIP);
                //Log.d("WM","listen: overwrite with book title from manual entry: " + title);
            }

            // This field in the JSON advert is a hash of I don't know what all. One component must
            // be the date since the hash changes daily. Requiring a user to enter 44 characters
            // of gibberish would be time consuming and (worse) error-prone, so we must reluctantly
            // require the regular UDP advertisement to also be received in this experiment.
            String newBookVersion = msgJson.getString("version");

            String sender = "unknown";
            String protocolVersion = "0.0";
            try {
                protocolVersion = msgJson.getString("protocolVersion");
                sender = msgJson.getString("sender");
            } catch(JSONException e) {
                Log.d("WM","listen: JSONException-1, " + e);  // WM, temporary
                e.printStackTrace();
            }
            float version = Float.parseFloat(protocolVersion);
            if (version <  2.0f) {
                if (!reportedVersionProblem) {
                    GetFromWiFiActivity.sendProgressMessage(this, "You need a newer version of Bloom editor to exchange data with this BloomReader\n");
                    reportedVersionProblem = true;
                }
                Log.d("WM","listen: problem-1 with version (" + version + "), returning");  // WM, temporary
                return;
            } else if (version >= 3.0f) {
                // Desktop currently uses 2.0 exactly; the plan is that non-breaking changes
                // will tweak the minor version number, breaking will change the major.
                if (!reportedVersionProblem) {
                    GetFromWiFiActivity.sendProgressMessage(this, "You need a newer version of BloomReader to exchange data with this sender\n");
                    reportedVersionProblem = true;
                }
                Log.d("WM","listen: problem-2 with version (" + version + "), returning");  // WM, temporary
                return;
            }
            File bookFile = IOUtilities.getBookFileIfExists(title);
            boolean bookExists = bookFile != null;
            // If the book doesn't exist it can't be up to date.
            if (bookExists && IsBookUpToDate(bookFile, title, newBookVersion)) {
                // Enhance: possibly we might want to announce this again if the book has been off the air
                // for a while? So a user doesn't see "nothing happening" if he thinks he just started
                // publishing it, but somehow BR has seen it recently? Thought about just keeping
                // the most recent name, so we'd report a different one even if it had been advertised
                // recently. But there could be two advertisers on the network, which could lead to
                // alternating advertisements. Another idea: find a way to only keep track of, say,
                // books advertised in the last few seconds. Since books are normally advertised
                // every second, a book we haven't seen for even 5 seconds is probably interesting
                // enough to announce again. One way would be, every 5 seconds we copy the current
                // set to an 'old' set and clear current. Then when we see a book, we skip announcing if it is in
                // either set. But only add it to the new one. Then, after 5-10 seconds of not seeing
                // an add, a book would drop out of both. Another approach would be a dictionary
                // mapping title to last-advertised-time, and if > 5s ago announce again.
                if (!_announcedBooks.contains(title)) {
                    GetFromWiFiActivity.sendProgressMessage(this, String.format(getString(R.string.already_have_version), title) + "\n\n");
                    _announcedBooks.add(title); // don't keep saying this.
                }
                Log.d("WM","listen: already have book");  // WM, temporary
            }
            else {
                if (bookExists) {
                    GetFromWiFiActivity.sendProgressMessage(this, String.format(getString(R.string.found_new_version), title, sender) + "\n");
                }
                else {
                    GetFromWiFiActivity.sendProgressMessage(this, String.format(getString(R.string.found_file), title, sender) + "\n");
                }
                // It can take a few seconds for the transfer to get going. We won't ask for this again unless
                // we don't start getting it in a reasonable time.
                addsToSkipBeforeRetry = 3;
                //getBook(senderIP, title);

                // WM, EXPERIMENT: enable scanning a QR code for the book title and BloomDesktop's
                // IP address. Simulate by having the user enter these items into text boxes.
                //if (!BloomReaderApplication.simulateQrCodeUsedInsteadOfAdvert) {
                //    String possibleIpViaQrCode = BloomReaderApplication.getDesktopIpAddrFromQrCode();
                //    if (possibleIpViaQrCode != null) {
                //        Log.d("WM", "listen: QR, calling getBook_tcp() for \"" + title + "\" from " + possibleIpViaQrCode);  // WM, temporar
                //        getBook_tcp(possibleIpViaQrCode, title);
                //    }
                //} else {
                //    Log.d("WM","listen: calling getBook_tcp() for \"" + title + "\" from " + senderIP);  // WM, temporary
                //    getBook_tcp(senderIP, title);
                //}
                Log.d("WM","listen: calling getBook_tcp() for \"" + title + "\" from " + senderIP);  // WM, temporary
                getBook_tcp(senderIP, title);
                Log.d("WM","listen: getBook_tcp() returned");  // WM, temporary
            }
        } catch (JSONException e) {
            // This can stay in production. Just ignore any broadcast packet that doesn't have
            // the data we expect.
            Log.d("WM","listen: JSONException-2, " + e);  // WM, temporary
            e.printStackTrace();
        }
        finally {
            socket.close();
            multicastLock.release();
            Log.d("WM","listen: closed UDP socket");  // WM, temporary
        }
    }

    // Private class to handle receiving notification from AcceptFileHandler.
    // I can't figure out how to make an anonymous class which can keep a reference to itself
    // for use in removing itself later. The notification is sent when the transfer of a book
    // is complete.
    class EndOfTransferListener implements AcceptFileHandler.IFileReceivedNotification {

        NewBookListenerService _parent;
        String _title;
        public EndOfTransferListener(NewBookListenerService parent, String title) {
            _parent = parent;
            _title = title;
        }

        @Override
        public void receivingFile(String name) {
            // Once the receive actually starts, don't start more receives until we deal with this.
            // If our request for the book didn't produce a response, we'll ask again when we get
            // the next notification.
            gettingBook = true;
        }

        @Override
        public void receivedFile(String name, boolean success) {
            Log.d("WM","receivedFile: calling transferComplete()");  // WM, temporary
            _parent.transferComplete(success);
            if (success) {
                // We won't announce subsequent up-to-date advertisements for this book.
                _announcedBooks.add(_title);
                GetFromWiFiActivity.sendBookLoadedMessage(_parent, name);
            }
        }
    }

    private void getBook(String sourceIP, String title) {
        AcceptFileHandler.requestFileReceivedNotification(new EndOfTransferListener(this, title));
        // This server will be sent the actual book data (and the final notification)
        Log.d("WM","getBook: calling startSyncServer()");  // WM, temporary
        startSyncServer();
        // Send one package to the desktop to request the book. Its contents tell the desktop
        // what IP address to use.
        SendMessage sendMessageTask = new SendMessage();
        sendMessageTask.desktopIpAddress = sourceIP;
        sendMessageTask.ourIpAddress = getOurIpAddress();
        sendMessageTask.ourDeviceName = getOurDeviceName();
        Log.d("WM","getBook: requesting Desktop at " + sourceIP + " for " + title);  // WM, temporary
        Log.d("WM","  our IP = " + sendMessageTask.ourIpAddress + ", our device = " + sendMessageTask.ourDeviceName);  // WM, temporary
        sendMessageTask.execute();
    }

    private void getBook_tcp(String ip, String title) {
        AcceptFileHandler.requestFileReceivedNotification(new EndOfTransferListener(this, title));

        // This server will be sent the actual book data (and the final notification). Start it now
        // before sending the book request to ensure it's ready if the reply is quick.
        Log.d("WM","getBook_tcp: calling startSyncServer()");  // WM, temporary
        startSyncServer();

        Socket socket = null;
        OutputStream outStream = null;
        InputStream inStream = null;

        try {
            // Establish a connection to Desktop.
            Log.d("WM","getBook_tcp: creating TCP socket to Desktop at " + ip + ":" + desktopPort);  // WM, temporary
            socket = new Socket(ip, desktopPort);
            Log.d("WM","getBook_tcp: got TCP socket; CONNECTED");  // WM, temporary

            // Create and send message to Desktop.
            outStream = new DataOutputStream(socket.getOutputStream());
            JSONObject bookRequest = new JSONObject();
            try {
                // names used here must match those in Bloom WiFiAdvertiser.Start(),
                // in the event handler for _wifiListener.NewMessageReceived.
                bookRequest.put("deviceAddress", getOurIpAddress());
                bookRequest.put("deviceName", getOurDeviceName());
            } catch (JSONException e) {
                Log.d("WM","getBook_tcp: JSONException-1, " + e);  // WM, temporary
                e.printStackTrace();
            }
            byte[] outBuf = bookRequest.toString().getBytes("UTF-8");
            outStream.write(outBuf);
            Log.d("WM","getBook_tcp: JSON message sent to desktop, " + outBuf.length + " bytes:");  // WM, temporary
            Log.d("WM","   " + bookRequest.toString());  // WM, temporary
        }
        catch (IOException i) {
            Log.d("WM","getBook_tcp: IOException-1, " + i);  // WM, temporary
            return;
        }

        // Close the connection.
        Log.d("WM","getBook_tcp: closing TCP connection...");  // WM, temporary
        try {
            inStream.close();
            Log.d("WM","getBook_tcp: inStream closed");  // WM, temporary
            outStream.close();
            Log.d("WM","getBook_tcp: outStream closed");  // WM, temporary
            socket.close();
            Log.d("WM","getBook_tcp: socket closed");  // WM, temporary
        }
        catch (IOException i) {
            Log.d("WM","getBook_tcp: IOException-2, " + i);  // WM, temporary
        }
        Log.d("WM","getBook_tcp: done, returning");  // WM, temporary
    }

    private void startSyncServer() {
        if (httpServiceRunning)
            return;
        Intent serviceIntent = new Intent(this, SyncService.class);
        Log.d("WM","startSyncServer: calling startService()");  // WM, temporary
        startService(serviceIntent);
        httpServiceRunning = true;
    }

    private void stopSyncServer() {
        if (!httpServiceRunning)
            return;
        Intent serviceIntent = new Intent(this, SyncService.class);
        Log.d("WM","stopSyncServer: calling stopService()");  // WM, temporary
        stopService(serviceIntent);
        httpServiceRunning = false;
    }

    // Called via EndOfTransferListener when desktop sends transfer complete notification.
    private void transferComplete(boolean success) {
        // We can stop listening for file transfers and notifications from the desktop.
        Log.d("WM","transferComplete: calling stopSyncServer()");  // WM, temporary
        stopSyncServer();
        gettingBook = false;

        final int resultId = success ? R.string.done : R.string.transferFailed;
        GetFromWiFiActivity.sendProgressMessage(this, getString(resultId) + "\n\n");

        BaseActivity.playSoundFile(R.raw.bookarrival);
        // We already played a sound for this file, don't need to play another when we resume
        // the main activity and notice the new file.
        MainActivity.skipNextNewFileSound();
    }

    // Get the IP address of this device (on the WiFi network) to transmit to the desktop.
    private String getOurIpAddress() {
        String ip = "";
        try {
            Enumeration<NetworkInterface> enumNetworkInterfaces = NetworkInterface.getNetworkInterfaces();
            while (enumNetworkInterfaces.hasMoreElements()) {
                NetworkInterface networkInterface = enumNetworkInterfaces.nextElement();
                Enumeration<InetAddress> enumInetAddress = networkInterface.getInetAddresses();
                while (enumInetAddress.hasMoreElements()) {
                    InetAddress inetAddress = enumInetAddress.nextElement();

                    if (inetAddress.isSiteLocalAddress()) {
                        return inetAddress.getHostAddress();
                    }
                }
            }
        } catch (SocketException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
            ip += "Something Wrong! " + e.toString() + "\n";
        }

        return ip;
    }

    // Determine whether the book is up to date, based on comparing the version file embedded in it
    // with the one we got from the advertisement.
    // A small file called version.txt is embedded in each .bloompub/.bloomd file to store the file version information
    // sent with each advertisement. This allows BloomReader to avoid repeatedly downloading
    // the same version of the same book. BloomReader does not interpret the version information,
    // just compares what is in the  version.txt in the .bloompub/.bloomd file it has (if any) with what it
    // got in the new advertisement.
    boolean IsBookUpToDate(File bookFile, String title, String newBookVersion) {
        // "version.txt" must match the name given in Bloom Desktop BookCompressor.CompressDirectory()
        byte[] oldShaBytes = IOUtilities.ExtractZipEntry(bookFile, "version.txt");
        if (oldShaBytes == null)
            return false;
        String oldSha = "";
        try {
            oldSha = new String(oldShaBytes, "UTF-8");
            // Some versions of Bloom accidentally put out a version.txt starting with a BOM
            if (oldSha.startsWith("\uFEFF")) {
                oldSha = oldSha.substring(1);
            }
            // I don't think the version code in the Bloom publisher advertisement ever had a BOM,
            // but let's make it robust anyway.
            if (newBookVersion.startsWith("\uFEFF")) {
                newBookVersion = newBookVersion.substring(1);
            }
        } catch (UnsupportedEncodingException e) {
            e.printStackTrace();
        }
        return oldSha.equals(newBookVersion); // not ==, they are different objects.
    }

    public static final String BROADCAST_BOOK_LISTENER_PROGRESS = "org.sil.bloomreader.booklistener.progress";
    public static final String BROADCAST_BOOK_LISTENER_PROGRESS_CONTENT = "org.sil.bloomreader.booklistener.progress.content";
    public static final String BROADCAST_BOOK_LOADED = "org.sil.bloomreader.booklistener.book.loaded";

    void startListenForUDPBroadcast() {
        UDPBroadcastThread = new Thread(new Runnable() {
            public void run() {
                try {
                    Integer port = 5913; // Must match port in Bloom class WiFiAdvertiser
                    while (shouldRestartSocketListen) { //
                        Log.d("WM","startListenForUDPBroadcast: calling listen(" + port + ")");  // WM, temporary
                        listen(port);
                    }
                    //if (!shouldListenForUDPBroadcast) throw new ThreadDeath();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        });
        UDPBroadcastThread.start();
    }

    void stopListen() {
        shouldRestartSocketListen = false;
        if (socket != null)
            socket.close();
    }

    @Override
    public void onDestroy() {
        stopListen();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        shouldRestartSocketListen = true;
        Log.d("WM","onStartCommand: calling startListenForUDPBroadcast()");  // WM, temporary
        startListenForUDPBroadcast();
        return START_STICKY;
    }

    // This class is responsible to send one message packet to the IP address we
    // obtained from the desktop, containing the Android's own IP address.
    private static class SendMessage extends AsyncTask<Void, Void, Void> {

        public String ourIpAddress;
        public String desktopIpAddress;
        public String ourDeviceName;
        @Override
        protected Void doInBackground(Void... params) {
            try {
                InetAddress receiverAddress = InetAddress.getByName(desktopIpAddress);
                DatagramSocket socket = new DatagramSocket();
                JSONObject data = new JSONObject();
                try {
                    // names used here must match those in Bloom WiFiAdvertiser.Start(),
                    // in the event handler for _wifiListener.NewMessageReceived.
                    data.put("deviceAddress", ourIpAddress);
                    data.put("deviceName", ourDeviceName);
                } catch (JSONException e) {
                    // How could these fail?? But compiler demands we catch this.
                    e.printStackTrace();
                }
                byte[] buffer = data.toString().getBytes("UTF-8");
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length, receiverAddress, desktopPort);
                socket.send(packet);
            } catch (IOException e) {
                e.printStackTrace();
            }
            return null;
        }
    }
}
