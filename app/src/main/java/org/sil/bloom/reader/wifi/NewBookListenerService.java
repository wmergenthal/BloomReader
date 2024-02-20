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

        // MulticastLock seems to have become necessary for receiving a broadcast packet around Android 8.
        WifiManager wifi;
        wifi = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        multicastLock = wifi.createMulticastLock("lock");
        multicastLock.acquire();
        Log.d("WM", "listen: acquired multicastLock");

        // Even if we're not using QR data clearing this flag won't affect anything, so just do it.
        BloomReaderApplication.setQrInputReceived(false);

        try {
            DatagramPacket packet = new DatagramPacket(recvBuf, recvBuf.length);
            //Log.e("UDP", "Waiting for UDP broadcast");
            Log.d("UDP", "Waiting for UDP broadcast");
            socket.receive(packet);

            // WM, debug: show a packet received and print its payload.
            int pktLen = packet.getLength();
            byte[] pktBytes = packet.getData();
            Log.d("WM", "listen: got UDP packet (" + pktLen + " bytes) from " + packet.getAddress().getHostAddress());
            String pktString = new String(pktBytes);
            Log.d("WM", "   advertisement = " + pktString.substring(0, pktLen));
            // WM, end of debug packet print

            if (gettingBook) {
                Log.d("WM","listen: ignore advert (getting book), release multicastLock, returning");
                multicastLock.release();  // perhaps not strictly necessary but saves some battery
                return; // ignore new advertisements while downloading. Will receive again later.
            }
            if (addsToSkipBeforeRetry > 0) {
                // We ignore a few adds after requesting a book before we (hopefully) start receiving.
                addsToSkipBeforeRetry--;
                Log.d("WM","listen: ignore advert (decr'd skips, now = " + addsToSkipBeforeRetry + "), release multicastLock, returning");
                multicastLock.release();  // perhaps not strictly necessary but saves some battery
                return;
            }

            // Pull out from the advertisement *payload*: (a) book title, (b) book version
            // NOTE: (b) in the JSON advert is a hash of I don't know what all. Requiring a user to
            // enter 44 characters of gibberish would be time consuming and (worse) error-prone, but
            // I don't know where in the tablet the current hash can be displayed. If there is such
            // a spot the user can copy/paste from there into the Reader popup textbox; otherwise,
            // type carefully! The textbox is displayed for WiFi-listen when the advertisement
            // alternative is in effect -- i.e., 'BloomReaderApplication.qrCodeUsedInsteadOfAdvert'
            // is set true. See GetFromWiFiActivity::onCreate().
            String message = new String(packet.getData()).trim();
            JSONObject msgJson = new JSONObject(message);
            String title = new String(msgJson.getString("title"));
            String newBookVersion = new String(msgJson.getString("version"));

            // Pull out from the advertisement packet *header*: Desktop IP address
            String senderIP = new String(packet.getAddress().getHostAddress());

            //Log.d("WM","listen: got data from UDP advert");
            //Log.d("WM", "listen: outset, verify QR-data-valid is false: " + BloomReaderApplication.getQrInputIsValid());

            if (BloomReaderApplication.qrCodeUsedInsteadOfAdvert) {
                // EXPERIMENT: QR code simulation
                // Wait until we have user input for Desktop's IP address, book's title, and book's
                // version. Title and version are not part of the reply to Desktop but Reader does
                // need them to decide whether to request this book.
                while (BloomReaderApplication.getQrInputReceived() == false) {
                    Thread.sleep(500);
                }

                // User input was parsed out elsewhere into separate strings. If they are valid,
                // use them now to update what we got (if anything) from the UDP advertisement.
                if (BloomReaderApplication.getQrInputIsValid()) {
                    senderIP = BloomReaderApplication.getDesktopIpAddrInQrCode();
                    title = BloomReaderApplication.getBookTitleInQrCode();
                    newBookVersion = BloomReaderApplication.getBookVersionInQrCode();

                    Log.d("WM", "listen: overwrite IP address from manual entry: " + senderIP);
                    Log.d("WM", "listen: overwrite book title from manual entry: " + title);
                    Log.d("WM", "listen: overwrite book version from manual entry: " + newBookVersion);
                } else {
                    Log.d("WM", "listen: QR data invalid, ignore it, release multicastLock and return");
                    multicastLock.release();  // perhaps not strictly necessary but saves some battery
                    return;
                }
            }

            String sender = "unknown";
            String protocolVersion = "0.0";
            try {
                protocolVersion = msgJson.getString("protocolVersion");
                sender = msgJson.getString("sender");
            } catch(JSONException e) {
                Log.d("WM","listen: JSONException-1, " + e);
                e.printStackTrace();
            }
            float version = Float.parseFloat(protocolVersion);
            if (version <  2.0f) {
                if (!reportedVersionProblem) {
                    GetFromWiFiActivity.sendProgressMessage(this, "You need a newer version of Bloom editor to exchange data with this BloomReader\n");
                    reportedVersionProblem = true;
                }
                Log.d("WM","listen:  version < 2 (" + version + "), release multicastLock, returning");
                multicastLock.release();  // perhaps not strictly necessary but saves some battery
                return;
            } else if (version >= 3.0f) {
                // Desktop currently uses 2.0 exactly; the plan is that non-breaking changes
                // will tweak the minor version number, breaking will change the major.
                if (!reportedVersionProblem) {
                    GetFromWiFiActivity.sendProgressMessage(this, "You need a newer version of BloomReader to exchange data with this sender\n");
                    reportedVersionProblem = true;
                }
                Log.d("WM","listen: version >= 3 (" + version + "), release multicastLock, returning");
                multicastLock.release();  // perhaps not strictly necessary but saves some battery
                return;
            }
            //Log.d("WM","listen: getting bookFile for title \"" + title + "\"");
            File bookFile = IOUtilities.getBookFileIfExists(title);
            Log.d("WM","listen: bookFile=" + bookFile);
            Log.d("WM","listen: title=" + title + ", newBookVersion=" + newBookVersion);

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
                Log.d("WM","listen: already have book");
            } else {
                if (bookExists) {
                    Log.d("WM","listen: requesting updated book");
                    GetFromWiFiActivity.sendProgressMessage(this, String.format(getString(R.string.found_new_version), title, sender) + "\n");
                }
                else {
                    Log.d("WM","listen: requesting new book");
                    GetFromWiFiActivity.sendProgressMessage(this, String.format(getString(R.string.found_file), title, sender) + "\n");
                }
                // It can take a few seconds for the transfer to get going. We won't ask for this again unless
                // we don't start getting it in a reasonable time.
                Log.d("WM","listen: our IP address is " + getOurIpAddress());
                addsToSkipBeforeRetry = 3;
                //Log.d("WM","listen: calling getBook() for \"" + title + "\" from " + senderIP);
                //getBook(senderIP, title);
                Log.d("WM","listen: calling getBook_tcp() for \"" + title + "\" from " + senderIP);
                getBook_tcp(senderIP, title);
            }
        } catch (JSONException e) {
            // This can stay in production. Just ignore any broadcast packet that doesn't have
            // the data we expect.
            Log.d("WM","listen: JSONException-2, " + e);
            e.printStackTrace();
        }
        finally {
            socket.close();
            multicastLock.release();
            Log.d("WM","listen: released multicastLock, closed UDP socket");
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
            Log.d("WM","receivedFile: calling transferComplete()");
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
        Log.d("WM","getBook: requesting Desktop at " + sourceIP + " for " + title);
        Log.d("WM","  our IP = " + sendMessageTask.ourIpAddress + ", our device = " + sendMessageTask.ourDeviceName);
        sendMessageTask.execute();
    }

    private void getBook_tcp(String ip, String title) {
        AcceptFileHandler.requestFileReceivedNotification(new EndOfTransferListener(this, title));

        // This server will be sent the actual book data (and the final notification). Start it now
        // before sending the book request to ensure it's ready if the reply is quick.
        Log.d("WM","getBook_tcp: calling startSyncServer()");
        startSyncServer();

        Socket socket = null;
        OutputStream outStream = null;

        try {
            // Establish a connection to Desktop.
            Log.d("WM","getBook_tcp: creating TCP socket to Desktop at " + ip + ":" + desktopPort);
            socket = new Socket(ip, desktopPort);
            Log.d("WM","getBook_tcp: got TCP socket; CONNECTED");

            // Create and send message to Desktop.
            outStream = new DataOutputStream(socket.getOutputStream());
            JSONObject bookRequest = new JSONObject();
            try {
                // names used here must match those in Bloom WiFiAdvertiser.Start(),
                // in the event handler for _wifiListener.NewMessageReceived.
                bookRequest.put("deviceAddress", getOurIpAddress());
                bookRequest.put("deviceName", getOurDeviceName());
            } catch (JSONException e) {
                Log.d("WM","getBook_tcp: JSONException-1, " + e);
                e.printStackTrace();
            }
            byte[] outBuf = bookRequest.toString().getBytes("UTF-8");
            outStream.write(outBuf);
            Log.d("WM","getBook_tcp: JSON message sent to desktop, " + outBuf.length + " bytes:");
            Log.d("WM","   " + bookRequest.toString());
        }
        catch (IOException i) {
            Log.d("WM","getBook_tcp: IOException-1, " + i + ", returning");
            return;
        }

        // Close the connection.
        Log.d("WM","getBook_tcp: closing TCP connection...");
        try {
            outStream.close();
            socket.close();
        }
        catch (IOException i) {
            Log.d("WM","getBook_tcp: IOException-2, " + i);
        }
        Log.d("WM","getBook_tcp: done, returning");
    }

    private void startSyncServer() {
        if (httpServiceRunning)
            return;
        Intent serviceIntent = new Intent(this, SyncService.class);
        Log.d("WM","startSyncServer: calling startService()");
        startService(serviceIntent);
        httpServiceRunning = true;
    }

    private void stopSyncServer() {
        if (!httpServiceRunning)
            return;
        Intent serviceIntent = new Intent(this, SyncService.class);
        Log.d("WM","stopSyncServer: calling stopService()");
        stopService(serviceIntent);
        httpServiceRunning = false;
    }

    // Called via EndOfTransferListener when desktop sends transfer complete notification.
    private void transferComplete(boolean success) {
        // We can stop listening for file transfers and notifications from the desktop.
        Log.d("WM","transferComplete: calling stopSyncServer()");
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
        if (oldShaBytes == null) {
            //Log.d("WM","IsBookUpToDate: oldShaBytes = null, bookFile probably is too, bail");  // WM, temporary
            return false;
        }
        String oldSha = "";
        try {
            oldSha = new String(oldShaBytes, "UTF-8");
            Log.d("WM","IsBookUpToDate: oldSha = " + oldSha);  // WM, temporary
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
        Log.d("WM","IsBookUpToDate: returning [oldSha.equals(newBookVersion)] = " + oldSha.equals(newBookVersion));  // WM, temporary
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
                        Log.d("WM","startListenForUDPBroadcast: calling listen(" + port + ")");
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
        Log.d("WM","onStartCommand: calling startListenForUDPBroadcast()");
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
