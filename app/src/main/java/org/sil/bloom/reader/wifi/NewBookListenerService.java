package org.sil.bloom.reader.wifi;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiInfo;
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
import java.net.InetSocketAddress;
import java.net.Socket;           // WM, added
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketAddress;
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
    WifiManager wifi = null;
    Thread UDPListenerThread;   // renamed from UDPBroadcastThread
    Thread QRListenerThread;
    private boolean shouldStartSocketListen = true;
    private boolean shouldStartQRListen = true;
    private boolean qrScanInProgress = false;
    private boolean advertProcessedOk = false;        // TODO, WM, is this truly helpful? remove it?

    // port on which the desktop is listening for our book request.
    // Must match Bloom Desktop UDPListener._portToListen.
    // Must be different from ports in NewBookListenerService.startListenForUDPBroadcast
    // and SyncServer._serverPort.
    //static int desktopPortUDP = 5915;  -- use TCP instead to make book requests
    static int desktopPortTCP = 5916;
    static int numSecondsUdpTimeout = 30;  // WM, long but Samsung Galaxy Tab A seems to need it
    static int numSecondsQrTimeout = 20;
    static int numSecondsTcpTimeout = 5;   // WM, 3 seems a lot but Samsung needs even more
    static boolean debugPacketPrint = true;
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

        if (socket != null) {
            Log.d("WM", "listenUDP: starting, socket.isClosed() = " + socket.isClosed());
        } else {
            Log.d("WM", "listenUDP: starting, socket is null");
        }
        if (socket == null || socket.isClosed()) {
            try {
                // https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/net/DatagramSocket.html --
                // "In order to receive broadcast packets a DatagramSocket should be bound to the wildcard address."
                // Binding to wildcard address is done with 2nd arg = null.
                Log.d("WM", "listenUDP: creating UDP socket bound to wildcard address");
                socket = new DatagramSocket(port, null);
                Log.d("WM", "listenUDP: setting broadcast");
                socket.setBroadcast(true);
            } catch (SocketException e) {
                Log.d("WM","listenUDP: SocketException (" + e + ")");
            }
            Log.d("WM", "listenUDP: socket ready for use");
        } else {
            Log.d("WM", "listenUDP: skipped UDP socket creation, socket.isClosed() = " + socket.isClosed());
        }

        udpPktLen = 0;
        // MulticastLock seems to have become necessary for receiving a broadcast packet around Android 8.
        //WifiManager wifi;  -- elevate to class-level scope
        if (wifi == null) {
            Log.d("WM", "listenUDP: getting context");
            wifi = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            Log.d("WM", "listenUDP: creating multicastLock");
            multicastLock = wifi.createMulticastLock("lock");
        } else {
            Log.d("WM", "listenUDP: reusing wifi and multicastLock");
        }
        Log.d("WM", "listenUDP: acquiring multicastLock");
        multicastLock.acquire();
        Log.d("WM", "listenUDP: got multicastLock");

        try {
            packet = new DatagramPacket(recvBuf, recvBuf.length);

            // Before using the socket, configure it to have a timeout.
            // If timeout occurs, stop listening for UDP and start listening for QR code.
            //
            // Note: the sequence diagram 'bloom_book_xfer_02c_QR_advert_TCP_ok.pdf' originally
            // specified a timeout value of 4 secs. The Samsung Galaxy Tab A (Android 8.1.0)
            // needs 6 secs at a minimum (empirically observed); 10 or more seems good.
            socket.setSoTimeout(numSecondsUdpTimeout * 1000);  // specify in milliseconds

            // Blocking call; timeout raises an exception.
            Log.e("UDP", "Waiting for UDP broadcast");
            Log.d("WM", "listenUDP: waiting for UDP broadcast, timeout = " + numSecondsUdpTimeout + " secs");
            socket.receive(packet);

            udpPktLen = packet.getLength();

            if (debugPacketPrint) {     // debug only
                byte[] pktBytes = packet.getData();
                String pktString = new String(pktBytes);
                Log.d("WM", "listenUDP: got UDP packet (" + udpPktLen + " bytes) from " + packet.getAddress().getHostAddress());
                Log.d("WM", "   advertisement = " + pktString.substring(0, udpPktLen));
            }
        } catch (SocketTimeoutException e) {
            //e.printStackTrace();
            Log.d("WM","listenUDP: " + e);
            socket.close();
            Log.d("WM", "listenUDP: releasing-A multicastLock");
            multicastLock.release();

            // UDP got nothing; now give QR listener a turn. Keep UDP off until QR is done.
            //shouldStartQRListen = true;  // should already be true but just in case...
            shouldStartSocketListen = false;
            Log.d("WM", "listenUDP: interrupting QR-listener thread-A");
            qrScanInProgress = true;
            QRListenerThread.interrupt();
            Log.d("WM", "listenUDP: returning, SocketTimeoutException");
            return;
        } catch (IOException e) {
            e.printStackTrace();
            Log.d("WM","listenUDP: " + e);
            socket.close();
            Log.d("WM", "listenUDP: releasing-B multicastLock");
            multicastLock.release();

            // UDP had an issue; now give QR listener a turn. Keep UDP off until QR is done.
            //shouldStartQRListen = true;  // should already be true but just in case...
            shouldStartSocketListen = false;
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
        //boolean ignoreAdvert = true;  // WM, test only: scenarios where Reader must see no UDP adverts

        if (bookRequested) {
            Log.d("WM","listenUDP: ignore advert (getting book)");
            ignoreAdvert = true;
        } else if (addsToSkipBeforeRetry > 0) {
            // We ignore a few adds after requesting a book before we (hopefully) start receiving.
            addsToSkipBeforeRetry--;
            Log.d("WM","listenUDP: ignore advert (decr'd skips, now = " + addsToSkipBeforeRetry + ")");
            ignoreAdvert = true;
        }

        if (!ignoreAdvert && (udpPktLen > 0)) {
            // We have a valid advertisement via UDP. Pull out from the packet *header* the
            // Desktop IP address, then process the advert and make a book request.
            String pktString = new String(packet.getData()).trim();
            String pktSenderIP = new String(packet.getAddress().getHostAddress());
            Log.d("WM", "listenUDP: calling processBookAdvert()");  // WM, temporary
            advertProcessedOk = processBookAdvert(pktString, pktSenderIP);
        }

        Log.d("WM", "listenUDP: done, success=" + advertProcessedOk + ", releasing multicastLock");  // WM, temporary
        multicastLock.release();
        // TODO: closing the socket seems like a good thing, but sometimes it raises a debug stack
        //       trace on BloomEditor that looks rather alarming to a user. Skip the close for now
        //       but keep in mind that further testing may show the need for it after all.
        //socket.close();

        Log.d("WM", "listenUDP: returning-normal");
    }

    // Once the advertisement is obtained as a string, either from QR code or UDP,
    // this function is called to extract the needed pieces of info and then create
    // a book request and send it to BloomDesktop.
    // The advert string and Desktop IP address string are passed in as arguments.
    private Boolean processBookAdvert(String advertString, String targetIP) throws Exception {
        try {
            // Pull out from the advertisement payload: (a) book title, (b) book version, (c) SSID if any
            JSONObject msgJson = new JSONObject(advertString);
            String title = new String(msgJson.getString("title"));
            String newBookVersion = new String(msgJson.getString("version"));
            String ssidDesktop = new String(msgJson.getString("ssid"));
            Log.d("WM","processBookAdvert: ssidDesktop = " + ssidDesktop);
            //Log.d("WM","processBookAdvert: ssidDesktop = TBD");

            String sender = "unknown";
            String protocolVersion = "0.0";
            try {
                protocolVersion = msgJson.getString("protocolVersion");
                sender = msgJson.getString("sender");
            } catch(JSONException e) {
                e.printStackTrace();
                Log.d("WM","processBookAdvert: JSONException-1 (" + e + "), bail");
                return false;
            }

            // Verify that protocol version falls in the correct range.
            float version = Float.parseFloat(protocolVersion);
            if (version <  2.0f) {
                if (!reportedVersionProblem) {
                    GetFromWiFiActivity.sendProgressMessage(this, "You need a newer version of Bloom editor to exchange data with this BloomReader\n");
                    reportedVersionProblem = true;
                }
                Log.d("WM","processBookAdvert: version < 2 (" + version + "), bail");
                //multicastLock.release();
                return false;
            } else if (version >= 3.0f) {
                // Desktop currently uses 2.0 exactly; the plan is that non-breaking changes
                // will tweak the minor version number, breaking will change the major.
                if (!reportedVersionProblem) {
                    GetFromWiFiActivity.sendProgressMessage(this, "You need a newer version of BloomReader to exchange data with this sender\n");
                    reportedVersionProblem = true;
                }
                Log.d("WM","processBookAdvert: version >= 3 (" + version + "), bail");
                //multicastLock.release();
                return false;
            }

            // Check if we (Reader) and Desktop are on the same SSID.
            // From the advert we know the Desktop's SSID, if it has one. Now get ours.
            // NOTE: Reader's SSID will probably come back with quotes around it -- not sure why.
            // A quote is an illegal character for an SSID, plus it messes up the comparison with
            // the Desktop's SSID, so remove any quote characters found.
            WifiManager wifiMan = (WifiManager)getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            WifiInfo info = wifiMan.getConnectionInfo();
            String ssidReader = info.getSSID();
            ssidReader = ssidReader.replace("\"", "");
            Log.d("WM","processBookAdvert: ssidReader = " + ssidReader);

            // TODO:
            //   - If we are not on the same SSID as Desktop then there is a good chance that a
            //     book request and/or transfer won't work. Put up a warning to our User, advising
            //     them to join the same SSID that Desktop is on.
            //   - If we are on the same SSID as Desktop then the transfer should work. Proceed.
            //   - If either of us is on a wired network connection, that connection won't have an
            //     SSID. At least they aren't *different* so a transfer might work. Proceed.
            //if (ssidDesktop) {
            //    // Desktop is using Wi-Fi, so verify that we are both on the same SSID.
            //    Log.d("WM","processBookAdvert: compare SSID-desktop (" + ssidDesktop + ") and SSID-reader (" + ssidReader + ")");
            //    if (!ssidDesktop.equals(ssidReader)) {
            //        Log.d("WM","processBookAdvert: different SSIDs, prompt user");
            //        aaaaaaaaaaaaaaaaaa;
            //    } else {
            //        Log.d("WM","processBookAdvert: matching SSIDs, proceed");
            //    }
            //} else {
            //    // Desktop is using a wired connection. That has no SSID but will still work.
            //    Log.d("WM","processBookAdvert: Desktop has wired (no SSID), no check needed");
            //}

            File bookFile = IOUtilities.getBookFileIfExists(title);
            Log.d("WM","processBookAdvert: bookFile=" + bookFile);
            Log.d("WM","processBookAdvert: title=" + title + ", newBookVersion=" + newBookVersion);

            if (requestBookIfNewVersion(title, newBookVersion, bookFile, targetIP, sender)) {
                Log.d("WM","processBookAdvert: did requestBookIfNewVersion(), return");
                return true;
            } else {
                Log.d("WM","processBookAdvert: requestBookIfNewVersion() failed, bail");
                return false;
            }
        } catch (JSONException e) {
            // This can stay in production. Just ignore any broadcast packet that doesn't have
            // the data we expect.
            e.printStackTrace();
            Log.d("WM","processBookAdvert: JSONException-2 (" + e + ")");
        }
        Log.d("WM","processBookAdvert: done, returning ok");
        return true;
    }

    private void listenQR() throws Exception {
        // UDP-listener gets first crack at receiving an advert and requesting the
        // book offered. But if it hears nothing for the specified interval then give
        // QR-scanning a shot.

        // Wait here patiently until we are interrupted. If our enable flag is set, that is
        // UDP-listener telling us to proceed. If the flag is not set then it is a signal from
        // elsewhere, in which case we do NOT proceed.
        try {
            Log.d("WM", "listenQR: wait while UDP-listener listens for advert");
            Thread.sleep(60000);  // TODO: should we have *indefinite* sleep?
        } catch (InterruptedException e) {
            if (shouldStartQRListen) {
                Log.d("WM", "listenQR: interrupted! calling qrScanAndProcess()");
                qrScanAndProcess();
                // QR scan operation is done so re-enable UDP listening.
                qrScanInProgress = false;
            } else {
                Log.d("WM", "listenQR: interrupted! but disabled so do nothing");
            }
        }
        Log.d("WM", "listenQR: done, returning");
    }

    private void qrScanAndProcess() {
        Intent qrScan = new Intent(this, SyncActivity.class);
        //if (qrScan == null) {       -- Android Studio says this is always false
        //    Log.d("WM","qrScanAndProcess: qrScan == null, bail");
        //    return;
        //}

        // Samsung Galaxy-Tab-A tablet (Android SDK level 34) works with or without this
        // flag, but BQ Aquaris M10 FHD tablet (Android SDK level 33) needs it. Not sure why.
        qrScan.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

        //Log.d("WM","qrScanAndProcess: calling startActivity()");
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
                Log.d("WM","qrScanAndProcess: InterruptedException, i = " + i);
                // Not sure what else to do...
            }
            if (SyncActivity.GetQrDataAvailable()) {
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

        Log.d("WM", "qrScanAndProcess: done, success=" + advertProcessedOk + ", return");  // WM, temporary
    }

    private boolean requestBookIfNewVersion(String bkTitle, String bkVersion, File bkFile, String desktopIP, String sender) {
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
            return true;
        } else {
            if (bookExists) {
                Log.d("WM","requestBookIfNewVersion: requesting updated book");
                GetFromWiFiActivity.sendProgressMessage(this, String.format(getString(R.string.found_new_version), bkTitle, sender) + "\n");
            } else {
                Log.d("WM","requestBookIfNewVersion: requesting new book");
                GetFromWiFiActivity.sendProgressMessage(this, String.format(getString(R.string.found_file), bkTitle, sender) + "\n");
            }

            // Make the book request over the TCP connection.
            Log.d("WM","requestBookIfNewVersion: calling getBookTcp()");
            if (getBookTcp(desktopIP, bkTitle)) {
                // It can take a few seconds for the transfer to get going. We won't ask for this again unless
                // we don't start getting it in a reasonable time.
                Log.d("WM","requestBookIfNewVersion: our IP address is " + getOurIpAddress());
                addsToSkipBeforeRetry = 3;
                return true;
            } else {
                return false;
            }
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
            if (success) {
                Log.d("WM", "receivedFile: calling transferComplete(OKAY)");
            } else {
                Log.d("WM", "receivedFile: calling transferComplete(FAIL)");
            }
            _parent.transferComplete(success);
            if (success) {
                // We won't announce subsequent up-to-date advertisements for this book.
                _announcedBooks.add(_title);
                GetFromWiFiActivity.sendBookLoadedMessage(_parent, name);
            }
        }
    }

    // This is a TCP version of getBook(). That function implements a UDP unicast response to the
    // Desktop's advertisement. The TCP response implemented here has some advantages:
    //   - TCP is guaranteed delivery. Yes, invoking getBook() means that Desktop's UDP broadcast
    //     was received, but there is no guarantee that a UDP response from Reader will likewise be.
    //   - This function uses no deprecated functions. The UDP response used two -- one in getBook()
    //     and one in 'private static class SendMessage'.
    private boolean getBookTcp(String ip, String title) {
        AcceptFileHandler.requestFileReceivedNotification(new EndOfTransferListener(this, title));

        // This server will be sent the actual book data (and the final notification). Start it now
        // before sending the book request to ensure it's ready if the reply is quick.
        Log.d("WM","getBookTcp: calling SyncServer-start");
        startSyncServer();

        Socket socketTcp = null;
        OutputStream outStream = null;

        try {
            // Establish a connection to Desktop for making a book request.
            // If connection is not achieved within the timeout period, bail.
            socketTcp = new Socket();
            SocketAddress sockAddr = new InetSocketAddress(ip, desktopPortTCP);
            Log.d("WM","getBookTcp: connecting (TCP) to Desktop at " + ip + ":" + desktopPortTCP);
            socketTcp.connect(sockAddr, numSecondsTcpTimeout * 1000);
            Log.d("WM","getBookTcp: TCP to Desktop CONNECTED");

            // Create and send message to Desktop.
            outStream = new DataOutputStream(socketTcp.getOutputStream());
            JSONObject bookRequest = new JSONObject();
            try {
                // Names used here must match those in Bloom WiFiAdvertiser.Start(),
                // in the event handler for _wifiListener.NewMessageReceived.
                bookRequest.put("deviceAddress", getOurIpAddress());
                bookRequest.put("deviceName", getOurDeviceName());
            } catch (JSONException e) {
                e.printStackTrace();
                Log.d("WM","getBookTcp: JSONException (" + e + "), bail");
                return false;
            }
            byte[] outBuf = bookRequest.toString().getBytes("UTF-8");
            outStream.write(outBuf);
            Log.d("WM","getBookTcp: JSON message sent to desktop, " + outBuf.length + " bytes:");
            Log.d("WM","   " + bookRequest.toString());

            // Set the flag indicating start of transaction with Desktop.
            Log.d("WM","getBookTcp: setting \'bookRequested\' = true");
            bookRequested = true;
        } catch (SocketTimeoutException e) {
            e.printStackTrace();
            Log.d("WM","getBookTcp: SocketTimeoutException (" + e + "), bail");
            return false;
        }
        catch (IOException i) {
            Log.d("WM","getBookTcp: IOException-1 (" + i + "), bail");
            return false;
        }

        // Close the connection.
        Log.d("WM","getBookTcp: closing TCP connection...");
        try {
            outStream.close();
            socketTcp.close();
        }
        catch (IOException i) {
            Log.d("WM","getBookTcp: IOException-2 (" + i + "), bail");
            return false;
        }
        Log.d("WM","getBookTcp: done");
        return true;
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
        Log.d("WM","transferComplete: setting \'bookRequested\' = false");
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

        // It can be confusing to see UDPL restart right after a book request has been made.
        // The UDP socket and WiFi context are being touched while an HTTP connection is being
        // set up with Desktop for a book transfer.
        // To avoid this, check the book-operation-in-progress flag. If it is true, enter a slow
        // polling loop wwhere UDPL sleeps in 1-second durations. At the end of each nap it checks
        // whether the book transfer is done, and we don't restart UDPL until it is.
        while (bookRequested) {
            Log.d("WM", "startListenForUDPBroadcast: bookRequested is active, wait...");
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Log.d("WM","startListenForUDPBroadcast: InterruptedException-1");
                // nothing to do
            }
        }
        Log.d("WM", "startListenForUDPBroadcast: creating UDPListenerThread");
        UDPListenerThread = new Thread(new Runnable() {
            public void run() {
                try {
                    Integer port = 5913; // Must match port in Bloom class WiFiAdvertiser
                    while (shouldStartSocketListen) {
                        if (!qrScanInProgress) {  // TODO: consider adding 'bookRequested' as a factor
                            Log.d("WM", "startListenForUDPBroadcast: calling listenUDP(port " + port + ")");
                            listenUDP(port);
                            Log.d("WM", "startListenForUDPBroadcast: listenUDP() returned");
                        } else {
                            Log.d("WM", "startListenForUDPBroadcast: QR scan in progress, retry later");
                            try {
                                Thread.sleep(1000);
                            } catch (InterruptedException e) {
                                Log.d("WM","startListenForUDPBroadcast: InterruptedException-2");
                                // nothing to do
                            }
                        }
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        });
        Log.d("WM", "startListenForUDPBroadcast: starting UDPListenerThread (ID = " + UDPListenerThread.getId() + ")");
        UDPListenerThread.start();
    }

    private void startListenForQRCode() {
        Log.d("WM", "startListenForQRCode: creating QRListenerThread");
        QRListenerThread = new Thread(new Runnable() {
            public void run() {
                try {
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
        Log.d("WM", "startListenForQRCode: starting QRListenerThread (ID = " + QRListenerThread.getId() + ")");
        QRListenerThread.start();
    }

    private void stopListen() {
        // don't let UDP listener start again --
        Log.d("WM","stopListen: clearing shouldStartSocketListen");
        shouldStartSocketListen = false;

        // don't let QR listener start again--
        Log.d("WM","stopListen: clearing shouldStartQRListen");
        shouldStartQRListen = false;

        //Log.d("WM", "stopListen: releasing multicastLock");
        //multicastLock.release();  -- leads to crash

        // Want to stop both listener threads. The way to do that seems to be:
        //   - call interrupt() on the thread
        //   - set the thread to null
        // But, can't do this for QRL because interrupt() is how it gets awakened by UDPL.
        // So at this point just stop UDPL, which should also keep QRL from activating.
        if (UDPListenerThread != null) {
            Log.d("WM","stopListen: closing socket");
            socket.disconnect();
            socket.close();
            //socket = null;
            Log.d("WM","stopListen: calling interrupt() on UDPListenerThread (ID = " + UDPListenerThread.getId() + ")");
            UDPListenerThread.interrupt();
            UDPListenerThread = null;

            // Next time UDPL starts in a new thread, enable it to listen for UDP adverts.
            qrScanInProgress = false;
        }
        //if (QRListenerThread != null) {
        //    Log.d("WM","stopListen: stopping QRListenerThread");
        //    QRListenerThread.interrupt();
        //    QRListenerThread = null;
        //}
        Log.d("WM","stopListen: done, returning");
    }

    @Override
    public void onDestroy() {
        Log.d("WM","onDestroy: calling stopListen()");
        stopListen();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        shouldStartSocketListen = true;
        Log.d("WM","onStartCommand: calling startListenForUDPBroadcast()");
        startListenForUDPBroadcast();

        // Start QRL but don't enable it (via 'shouldStartQRListen') to operate yet. That
        // only happens when UDPL triggers QRL by calling its interrupt().
        Log.d("WM","onStartCommand: calling startListenForQRCode()");
        startListenForQRCode();
        return START_STICKY;
    }
}
