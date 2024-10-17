package org.sil.bloom.reader.wifi;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.wifi.WifiManager;
//import android.os.AsyncTask;
import android.os.IBinder;
import android.util.Log;  // WM, added
import androidx.annotation.Nullable;

import org.json.JSONException;
import org.json.JSONObject;
import org.sil.bloom.reader.BaseActivity;
//import org.sil.bloom.reader.BloomReaderApplication;
import org.sil.bloom.reader.IOUtilities;
import org.sil.bloom.reader.MainActivity;
import org.sil.bloom.reader.R;
import org.sil.bloom.reader.SyncActivity;
//import org.sil.bloom.reader.models.BookCollection;

import java.io.File;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.io.DataOutputStream;  // WM, added
import java.io.OutputStream;      // WM, added
import java.net.Socket;           // WM, added
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.SocketTimeoutException;  // WM, added
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
    Thread UDPListenerThread;   // renamed from UDPBroadcastThread
    Thread QRListenerThread;
    private boolean shouldRestartSocketListen = true;
    private boolean shouldStartQRListen = true;
    private boolean qrScanInProgress = false;
    private boolean advertProcessedOk = false;        // TODO, WM, is this truly helpful? remove it?

    // port on which the desktop is listening for our book request.
    // Must match Bloom Desktop UDPListener._portToListen.
    // Must be different from ports in NewBookListenerService.startListenForUDPBroadcast
    // and SyncServer._serverPort.
    //static int desktopPortUDP = 5915;
    static int desktopPortTCP = 5916;
    static int numSecondsUdpTimeout = 15;  // WM, long but Samsung Galaxy Tab A seems to need it
    static int numSecondsQrTimeout = 20;
    static boolean debugPacketPrint = true;
    //static boolean suppressUdpReceive = true;  // WM, testing only
    int udpPktLen;
    boolean bookRequested = false;
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

    private void listenUDP(Integer port) throws Exception {
        DatagramPacket packet;
        byte[] recvBuf = new byte[15000];
        if (socket == null || socket.isClosed()) {
            socket = new DatagramSocket(port);
            socket.setBroadcast(true);
        }

        udpPktLen = 0;
        // MulticastLock seems to have become necessary for receiving a broadcast packet around Android 8.
        WifiManager wifi;
        wifi = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        multicastLock = wifi.createMulticastLock("lock");
        multicastLock.acquire();

        try {
            packet = new DatagramPacket(recvBuf, recvBuf.length);

            // Before using the socket, configure it to have a timeout.
            // If timeout occurs, stop listening for UDP and start listening for QR code.
            //
            // Note: the sequence diagram 'bloom_book_xfer_02c_QR_advert_TCP_ok.pdf' originally
            // specified a timeout value of 4 secs. The Samsung Galaxy Tab A (Android 8.1.0)
            // needs 6 secs at a minimum (empirically observed); 10 or more seems good.
            socket.setSoTimeout(numSecondsUdpTimeout * 1000);  // specify in milliseconds

            //if (suppressUdpReceive == true) {
            //    // QR testing only: suppress reception of UDP adverts. 'udpPktLen' will = 0.
            //    Log.d("WM", "listenUDP: UDP receive suppressed for QR testing, regular timeout");
            //    Thread.sleep(numSecondsUdpTimeout * 1000);
            //} else {
                // Normal operation.
                Log.e("UDP", "Waiting for UDP broadcast");
                Log.d("WM", "listenUDP: waiting for UDP broadcast, timeout = " + numSecondsUdpTimeout + " secs");
                socket.receive(packet);     // blocking call; timeout raises an exception
            //}

            udpPktLen = packet.getLength();

            if (debugPacketPrint == true) {     // debug only
                byte[] pktBytes = packet.getData();
                String pktString = new String(pktBytes);
                Log.d("WM", "listenUDP: got UDP packet (" + udpPktLen + " bytes) from " + packet.getAddress().getHostAddress());
                Log.d("WM", "   advertisement = " + pktString.substring(0, udpPktLen));
            }
        } catch (SocketTimeoutException e) {
            //e.printStackTrace();
            Log.d("WM","listenUDP: " + e);
            socket.close();
            multicastLock.release();

            // UDP got nothing. Now give QR listener a turn -- interrupt it.
            // Keep UDP off until QR is done.
            shouldRestartSocketListen = false;
            Log.d("WM", "listenUDP: interrupting QR-listener thread-A");
            qrScanInProgress = true;
            QRListenerThread.interrupt();
            Log.d("WM", "listenUDP: returning, SocketTimeoutException");
            return;
        } catch (IOException e) {
            e.printStackTrace();
            Log.d("WM","listenUDP: IOException (" + e + ")");
            socket.close();
            multicastLock.release();

            // UDP had an issue. Now give QR listener a turn -- interrupt it.
            // Keep UDP off until QR is done.
            shouldRestartSocketListen = false;
            Log.d("WM", "listenUDP: interrupting the QR-listener thread-B");
            qrScanInProgress = true;
            QRListenerThread.interrupt();
            Log.d("WM", "listenUDP: returning, IOException");
            return;
        }

        // We received an apparent UDP advert. There are a few reasons to ignore it:
        //   - We are already receiving a book and can't handle new adverts. This one will
        //     come around again and we can act on it when we're ready.
        //   - We have requested a book but haven't started receiving it yet.
        boolean ignoreAdvert = false;
        if (bookRequested) {
            Log.d("WM","listenUDP: ignore advert (getting book)");
            ignoreAdvert = true;
        } else if (addsToSkipBeforeRetry > 0) {
            // We ignore a few adds after requesting a book before we (hopefully) start receiving.
            addsToSkipBeforeRetry--;
            Log.d("WM","listenUDP: ignore advert (decr'd skips, now = " + addsToSkipBeforeRetry + ")");
            ignoreAdvert = true;
        }

        if ((ignoreAdvert == false) && (udpPktLen > 0)) {
            // We have a valid advertisement via UDP. Pull out from the packet *header* the
            // Desktop IP address, then process the advert and make a book request.
            String pktString = new String(packet.getData()).trim();
            String pktSenderIP = new String(packet.getAddress().getHostAddress());
            Log.d("WM", "listenUDP: calling processBookAdvert()");  // WM, temporary
            advertProcessedOk = processBookAdvert(pktString, pktSenderIP);

            //if (advertProcessedOk) {
            //    // We requested the book. We expect to receive it shortly, but even if it
            //    // doesn't arrive don't let QR-listener start. That could lead to confusion.
            //    Log.d("WM", "listenUDP: book requested ok, set shouldStartQRListen=false");
            //    // TODO: figure out arbitration flags!
            //    //shouldStartQRListen = false;
            //} else {
            //    Log.d("WM", "listenUDP: book request FAILED, keep shouldStartQRListen=true");
            //    // TODO: figure out arbitration flags!
            //    //shouldStartQRListen = true;
            //}
        }

        Log.d("WM", "listenUDP: done, success=" + advertProcessedOk + ", clean up and return");  // WM, temporary
        multicastLock.release();
        // TODO: closing the socket seems like a good thing, but sometimes it raises a debug stack
        //       trace on BloomEditor that looks rather alarming to a user. Skip the close for now.
        //socket.close();

        Log.d("WM", "listenUDP: returning-normal");
    }

    // Once the advertisement is obtained as a string, either from QR code or UDP,
    // this function is called to extract the needed pieces of info and then create
    // a book request and send it to BloomDesktop.
    // The advert string and Desktop IP address string are passed in as arguments.
    private Boolean processBookAdvert(String advertString, String targetIP) throws Exception {

        // This function running means that advert data arrived, by UDP or QR channel (doesn't
        // matter which). We don't want either listener to run while or after this advert is
        // processed so clear their start flags.
        // TODO: figure out arbitration flags!
        //Log.d("WM","processBookAdvert: clearing shouldRestartSocketListen and shouldStartQRListen");
        //shouldRestartSocketListen = false;
        //shouldStartQRListen = false;

        try {
            // Pull out from the advertisement payload: (a) book title, (b) book version
            JSONObject msgJson = new JSONObject(advertString);
            String title = new String(msgJson.getString("title"));
            String newBookVersion = new String(msgJson.getString("version"));

            String sender = "unknown";
            String protocolVersion = "0.0";
            try {
                protocolVersion = msgJson.getString("protocolVersion");
                sender = msgJson.getString("sender");
            } catch(JSONException e) {
                Log.d("WM","processBookAdvert: JSONException-1 (" + e + "), returning");
                e.printStackTrace();
                return false;
            }
            float version = Float.parseFloat(protocolVersion);
            if (version <  2.0f) {
                if (!reportedVersionProblem) {
                    GetFromWiFiActivity.sendProgressMessage(this, "You need a newer version of Bloom editor to exchange data with this BloomReader\n");
                    reportedVersionProblem = true;
                }
                Log.d("WM","processBookAdvert: version < 2 (" + version + "), returning");
                //multicastLock.release();
                return false;
            } else if (version >= 3.0f) {
                // Desktop currently uses 2.0 exactly; the plan is that non-breaking changes
                // will tweak the minor version number, breaking will change the major.
                if (!reportedVersionProblem) {
                    GetFromWiFiActivity.sendProgressMessage(this, "You need a newer version of BloomReader to exchange data with this sender\n");
                    reportedVersionProblem = true;
                }
                Log.d("WM","processBookAdvert: version >= 3 (" + version + "), returning");
                //multicastLock.release();
                return false;
            }

            File bookFile = IOUtilities.getBookFileIfExists(title);
            Log.d("WM","processBookAdvert: bookFile=" + bookFile);
            Log.d("WM","processBookAdvert: title=" + title + ", newBookVersion=" + newBookVersion);

            requestBookIfNewVersion(title, newBookVersion, bookFile, targetIP, sender);

        } catch (JSONException e) {
            // This can stay in production. Just ignore any broadcast packet that doesn't have
            // the data we expect.
            Log.d("WM","processBookAdvert: JSONException-2 (" + e + ")");
            e.printStackTrace();
        }
        Log.d("WM","processBookAdvert: done, returning ok");
        return true;
    }

    private void listenQR() throws Exception {
        // UDP-listener gets first crack at receiving an advert and requesting the
        // book offered. But if it hears nothing for the specified interval then give
        // QR-scanning a shot.

        // Wait here patiently until UDP-listener interrupts. That is our cue to proceed.
        try {
            Log.d("WM", "listenQR: sleep while UDP-listener listens for advert");
            Thread.currentThread().sleep(60000);  // TODO: can we have *indefinite* sleep?
        } catch (InterruptedException e) {
            Log.d("WM", "listenQR: interrupted! calling qrScanAndProcess()");
            qrScanAndProcess();
            // QR scan operation is done so re-enable UDP listening.
            qrScanInProgress = false;
        }
        Log.d("WM", "listenQR: done, returning");
    }

    private void qrScanAndProcess() {
        Intent qrScan = new Intent(this, SyncActivity.class);
        if (qrScan == null) {
            Log.d("WM","qrScanAndProcess: qrScan == null, bail");
            return;
        }
        // Samsung Galaxy-Tab-A tablet (Android SDK level 34) works with or without this
        // flag, but BQ Aquaris M10 FHD tablet (Android SDK level 33) needs it. Not sure why.
        qrScan.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        Log.d("WM","qrScanAndProcess: calling startActivity()");
        startActivity(qrScan);
        Log.d("WM","qrScanAndProcess: startActivity() returned, begin awaiting QR data");

        // Wait (up to the specified max seconds) for a decoded QR scan to be available.
        // It is an advert identical to what would have also been UDP-broadcast. When it
        // is available, grab it.
        String qrString = null;
        for (int i = 1; i <= numSecondsQrTimeout; i++) {
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Log.d("WM","qrScanAndProcess: InterruptedException when i = " + i);
                // Not sure what else to do...
            }
            if (SyncActivity.GetQrDataAvailable() == true) {
                qrString = SyncActivity.GetQrData();
                Log.d("WM","qrScanAndProcess: " + i + " secs, got QR data!");
                break;
            }
            Log.d("WM","qrScanAndProcess: " + i + " secs, no QR data yet");
        }

        // We have waited long enough.
        //   - If we got nothing, close QR scan screen and get back to Wi-Fi screen.
        //   - But if we got an advertisement via QR:
        //       - extract from it the Desktop's IP address
        //       - process it just like a UDP advert would be
        if (qrString == null) {
            //Log.d("WM", "qrScanAndProcess: didn't get QR data, call ActivityStop() and exit");
            //SyncActivity.ActivityStop();
            Log.d("WM", "qrScanAndProcess: didn't get QR data, calling stopService()");
            // TODO: does this really do anything? Remove it?
            stopService(qrScan);
            Log.d("WM", "qrScanAndProcess: returned from stopService(), returning");
            return;
        }

        try {
            JSONObject msgJsonQr = new JSONObject(qrString);
            String qrSenderIP = new String(msgJsonQr.getString("senderIP"));
            Log.d("WM", "qrScanAndProcess: calling processBookAdvert()");  // WM, temporary
            advertProcessedOk = processBookAdvert(qrString, qrSenderIP);
        } catch (JSONException e) {
            Log.d("WM","qrScanAndProcess: JSONException (" + e + "), bail");
            return;
        } catch (Exception e) {
            Log.d("WM","qrScanAndProcess: Exception (" + e + "), bail");
            return;
        }

        // We got the QR data so close its screen and return to the Wi-Fi screen.
        Log.d("WM", "qrScanAndProcess: advert processed, call ActivityStop()");  // WM, temporary
        SyncActivity.ActivityStop();
        //Log.d("WM", "qrScanAndProcess: advert processed, call stopService()");  // WM, temporary
        //stopService(qrScan);

        Log.d("WM", "qrScanAndProcess: done, success=" + advertProcessedOk + ", return");  // WM, temporary
    }

    private void requestBookIfNewVersion(String bkTitle, String bkVersion, File bkFile, String desktopIP, String sender) {
        boolean bookExists = bkFile != null;
        // If the book doesn't exist it can't be up to date.
        if (bookExists && IsBookUpToDate(bkFile, bkTitle, bkVersion)) {
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
            if (!_announcedBooks.contains(bkTitle)) {
                GetFromWiFiActivity.sendProgressMessage(this, String.format(getString(R.string.already_have_version), bkTitle) + "\n\n");
                _announcedBooks.add(bkTitle); // don't keep saying this.
            }
            Log.d("WM","requestBookIfNewVersion: already have book");
        } else {
            if (bookExists) {
                Log.d("WM","requestBookIfNewVersion: requesting updated book");
                GetFromWiFiActivity.sendProgressMessage(this, String.format(getString(R.string.found_new_version), bkTitle, sender) + "\n");
            } else {
                Log.d("WM","requestBookIfNewVersion: requesting new book");
                GetFromWiFiActivity.sendProgressMessage(this, String.format(getString(R.string.found_file), bkTitle, sender) + "\n");
            }
            // It can take a few seconds for the transfer to get going. We won't ask for this again unless
            // we don't start getting it in a reasonable time.
            Log.d("WM","requestBookIfNewVersion: our IP address is " + getOurIpAddress());
            addsToSkipBeforeRetry = 3;

            // Make the book request, finally.
            // Select either UDP or TCP by commenting out the unused call (and its debug msg).
            //Log.d("WM","requestBookIfNewVersion: calling getBook() [uses UDP]");
            //getBook(desktopIP, bkTitle);
            Log.d("WM","requestBookIfNewVersion: calling getBookTcp()");
            getBookTcp(desktopIP, bkTitle);
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
            Log.d("WM","receivingFile: \"" + name + "\"");
        }

        @Override
        public void receivedFile(String name, boolean success) {
            if (success == true) {
                Log.d("WM", "receivedFile: calling transferComplete(), OKAY");
            } else {
                Log.d("WM", "receivedFile: calling transferComplete(), FAIL");
            }
            _parent.transferComplete(success);
            if (success) {
                // We won't announce subsequent up-to-date advertisements for this book.
                _announcedBooks.add(_title);
                GetFromWiFiActivity.sendBookLoadedMessage(_parent, name);
            }
        }
    }

    //private void getBook(String sourceIP, String title) {
    //    AcceptFileHandler.requestFileReceivedNotification(new EndOfTransferListener(this, title));
    //    // This server will be sent the actual book data (and the final notification)
    //    Log.d("WM","getBook: calling startSyncServer()");  // WM, temporary
    //    startSyncServer();
    //    // Send one package to the desktop to request the book. Its contents tell the desktop
    //    // what IP address to use.
    //    SendMessage sendMessageTask = new SendMessage();
    //    sendMessageTask.desktopIpAddress = sourceIP;
    //    sendMessageTask.ourIpAddress = getOurIpAddress();
    //    sendMessageTask.ourDeviceName = getOurDeviceName();
    //    sendMessageTask.execute();  // deprecated method
    //
    //    // Set the flag indicating start of transaction with Desktop.
    //    Log.d("WM","getBook: setting \'gettingBook\' flag");
    //    bookRequested = true;
    //}

    // This is a TCP version of getBook(). That function implements a UDP unicast response to the
    // Desktop's advertisement. The TCP response implemented here has some advantages:
    //   - TCP is guaranteed delivery. Yes, invoking getBook() means that Desktop's UDP broadcast
    //     was received, but there is no guarantee that a UDP response from Reader will likewise be.
    //   - This function uses no deprecated functions. The UDP response uses two -- one in getBook()
    //     and one in 'private static class SendMessage'.
    private void getBookTcp(String ip, String title) {
        AcceptFileHandler.requestFileReceivedNotification(new EndOfTransferListener(this, title));

        // This server will be sent the actual book data (and the final notification). Start it now
        // before sending the book request to ensure it's ready if the reply is quick.
        Log.d("WM","getBookTcp: calling SyncServer-start");
        startSyncServer();

        Socket socketTcp = null;  // ungainly name but avoids possible confusion with UDP "socket"
        OutputStream outStream = null;

        try {
            // Establish a connection to Desktop.
            Log.d("WM","getBookTcp: creating TCP socket to Desktop at " + ip + ":" + desktopPortTCP);
            socketTcp = new Socket(ip, desktopPortTCP);
            Log.d("WM","getBookTcp: got TCP socket; CONNECTED");

            // Create and send message to Desktop.
            outStream = new DataOutputStream(socketTcp.getOutputStream());
            JSONObject bookRequest = new JSONObject();
            try {
                // names used here must match those in Bloom WiFiAdvertiser.Start(),
                // in the event handler for _wifiListener.NewMessageReceived.
                bookRequest.put("deviceAddress", getOurIpAddress());
                bookRequest.put("deviceName", getOurDeviceName());
            } catch (JSONException e) {
                Log.d("WM","getBookTcp: JSONException (" + e + ")");
                e.printStackTrace();
            }
            byte[] outBuf = bookRequest.toString().getBytes("UTF-8");
            outStream.write(outBuf);
            Log.d("WM","getBookTcp: JSON message sent to desktop, " + outBuf.length + " bytes:");
            Log.d("WM","   " + bookRequest.toString());

            // Set the flag indicating start of transaction with Desktop.
            Log.d("WM","getBookTcp: setting \'gettingBook\' flag");
            bookRequested = true;
        }
        catch (IOException i) {
            Log.d("WM","getBookTcp: IOException-1 (" + i + "), returning");
            return;
        }

        // Close the connection.
        Log.d("WM","getBookTcp: closing TCP connection...");
        try {
            outStream.close();
            socketTcp.close();
        }
        catch (IOException i) {
            Log.d("WM","getBookTcp: IOException-2 (" + i + ")");
        }
        Log.d("WM","getBookTcp: done");
    }

    private void startSyncServer() {
        if (httpServiceRunning) {
            Log.d("WM","SyncServer-start: already running, bail");
            return;
        }
        Intent serviceIntent = new Intent(this, SyncService.class);
        Log.d("WM","SyncServer-start: calling startService()");
        startService(serviceIntent);
        httpServiceRunning = true;
    }

    private void stopSyncServer() {
        if (!httpServiceRunning) {
            Log.d("WM","SyncServer-stop: already stopped, bail");
            return;
        }
        Intent serviceIntent = new Intent(this, SyncService.class);
        Log.d("WM","SyncServer-stop: calling stopService()");
        stopService(serviceIntent);
        httpServiceRunning = false;
    }

    // Called via EndOfTransferListener when desktop sends transfer complete notification.
    private void transferComplete(boolean success) {
        // We can stop listening for file transfers and notifications from the desktop.
        Log.d("WM","transferComplete: calling SyncServer-stop");
        stopSyncServer();
        Log.d("WM","transferComplete: clearing \'gettingBook\' flag");
        bookRequested = false;

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
    private boolean IsBookUpToDate(File bookFile, String title, String newBookVersion) {
        // "version.txt" must match the name given in Bloom Desktop BookCompressor.CompressDirectory()
        byte[] oldShaBytes = IOUtilities.ExtractZipEntry(bookFile, "version.txt");
        if (oldShaBytes == null) {
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

    private void startListenForUDPBroadcast() {
        Log.d("WM", "startListenForUDPBroadcast: creating UDPListenerThread");
        UDPListenerThread = new Thread(new Runnable() {
            public void run() {
                try {
                    Integer port = 5913; // Must match port in Bloom class WiFiAdvertiser
                    while (shouldRestartSocketListen) {
                        if (qrScanInProgress == false) {
                            Log.d("WM", "startListenForUDPBroadcast: calling listenUDP(port " + port + ")");
                            listenUDP(port);
                            Log.d("WM", "startListenForUDPBroadcast: listenUDP() returned");
                        } else {
                            Log.d("WM", "startListenForUDPBroadcast: QR scan in progress, retry later");
                            Thread.sleep(1000);
                        }
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        });
        Log.d("WM", "startListenForUDPBroadcast: starting UDPListenerThread");
        UDPListenerThread.start();
    }

    private void startListenForQRCode() {
        Log.d("WM", "startListenForQRCode: creating QRListenerThread");
        QRListenerThread = new Thread(new Runnable() {
            public void run() {
                try {
                    //while (shouldStartQRListen) {
                    if (shouldStartQRListen) {
                        Log.d("WM", "startListenForQRCode: calling listenQR()");
                        listenQR();
                        Log.d("WM", "startListenForQRCode: listenQR() returned,");
                        Log.d("WM", "   calling SyncActivity.ActivityStop()");
                        SyncActivity.ActivityStop();
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        });
        Log.d("WM", "startListenForQRCode: starting QRListenerThread");
        QRListenerThread.start();
    }

    private void stopListen() {
        // stop UDP listener --
        Log.d("WM","stopListen: clearing shouldRestartSocketListen");
        shouldRestartSocketListen = false;

        // stop QR listener --
        Log.d("WM","stopListen: clearing shouldStartQRListen");
        shouldStartQRListen = false;
    }

    @Override
    public void onDestroy() {
        Log.d("WM","onDestroy: calling stopListen()");
        stopListen();

        Log.d("WM","onDestroy: calling stopSelf()");
        stopSelf();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        shouldRestartSocketListen = true;
        Log.d("WM","onStartCommand: calling startListenForUDPBroadcast()");
        startListenForUDPBroadcast();

        //shouldStartQRListen = true;
        Log.d("WM","onStartCommand: calling startListenForQRCode()");
        startListenForQRCode();
        return START_STICKY;
    }

    // This class is responsible to send one message packet to the IP address we
    // obtained from the desktop, containing the Android's own IP address.
    //private static class SendMessage extends AsyncTask<Void, Void, Void> {
    //
    //    public String ourIpAddress;
    //    public String desktopIpAddress;
    //    public String ourDeviceName;
    //    @Override
    //    protected Void doInBackground(Void... params) {    // deprecated
    //        try {
    //            InetAddress receiverAddress = InetAddress.getByName(desktopIpAddress);
    //            DatagramSocket socket = new DatagramSocket();
    //            JSONObject data = new JSONObject();
    //            try {
    //                // names used here must match those in Bloom WiFiAdvertiser.Start(),
    //                // in the event handler for _wifiListener.NewMessageReceived.
    //                data.put("deviceAddress", ourIpAddress);
    //                data.put("deviceName", ourDeviceName);
    //            } catch (JSONException e) {
    //                // How could these fail?? But compiler demands we catch this.
    //                e.printStackTrace();
    //            }
    //            byte[] buffer = data.toString().getBytes("UTF-8");
    //            Log.d("WM","doInBackground: creating UDP packet for Desktop at " + desktopIpAddress + ":" + desktopPortUDP);
    //            DatagramPacket packet = new DatagramPacket(buffer, buffer.length, receiverAddress, desktopPortUDP);
    //            socket.send(packet);
    //            Log.d("WM","doInBackground: JSON message sent to desktop, " + buffer.length + " bytes:");
    //            Log.d("WM","   " + data.toString());
    //        } catch (IOException e) {
    //            e.printStackTrace();
    //        }
    //        return null;
    //    }
    //}
}
