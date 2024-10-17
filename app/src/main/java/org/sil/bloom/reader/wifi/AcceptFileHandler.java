package org.sil.bloom.reader.wifi;

import android.content.Context;
import android.net.Uri;
import android.util.Log;

import org.sil.bloom.reader.R;
import org.sil.bloom.reader.models.BookCollection;

import cz.msebera.android.httpclient.HttpEntity;
import cz.msebera.android.httpclient.HttpEntityEnclosingRequest;
import cz.msebera.android.httpclient.HttpException;
import cz.msebera.android.httpclient.HttpRequest;
import cz.msebera.android.httpclient.HttpResponse;
import cz.msebera.android.httpclient.entity.StringEntity;
import cz.msebera.android.httpclient.protocol.HttpContext;
import cz.msebera.android.httpclient.protocol.HttpRequestHandler;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Handles requests with urls like http://[ipaddress]:5914/putfile?path=bookTitle.bloompub
 * to write a file containing the data transmitted to a file in the local books directory.
 * This is configured as a request handler in SyncServer.
 * Slightly adapted from a similar file in HearThis Android
 */
public class AcceptFileHandler implements HttpRequestHandler {
    Context _parent;
    private int bytesReadTotal = 0;
    public AcceptFileHandler(Context parent)
    {
        _parent = parent;
    }
    @Override
    public void handle(HttpRequest request, HttpResponse response, HttpContext httpContext) throws HttpException, IOException {
        GetFromWiFiActivity.sendProgressMessage(_parent, _parent.getString(R.string.downloading) + "\n");
        File baseDir = BookCollection.getLocalBooksDirectory();
        Uri uri = Uri.parse(request.getRequestLine().getUri());
        String filePath = uri.getQueryParameter("path");

        if (listener != null) {
            Log.d("WM", "AcceptFileHandler: receiving on path = " + filePath);
            listener.receivingFile(filePath);
        } else {
            Log.d("WM", "AcceptFileHandler: null listener-A!");
        }
        String path = baseDir  + "/" + filePath;
        HttpEntity entity = null;
        String result = "failure";
        if (request instanceof HttpEntityEnclosingRequest)
            entity = ((HttpEntityEnclosingRequest)request).getEntity();
        if (entity != null) {
            try {
                final InputStream input = entity.getContent();
                final byte[] buffer = new byte[4096];
                File file = new File(path);
                File dir = file.getParentFile();
                if (!dir.exists())
                    dir.mkdirs();
                boolean aborted = false;
                FileOutputStream fs = new FileOutputStream(file);
                try {
                    int bytesRead = 0;

                    Log.d("WM", "AcceptFileHandler: begin read");
                    while (bytesRead >= 0) {
                        try {
                            // This could block up to the SyncServer-socket-configured timeout (currently 1 sec).
                            bytesRead = input.read(buffer, 0, Math.min(input.available(), buffer.length));
                            //Log.d("WM", "AcceptFileHandler, did read, bytesRead = " + bytesRead);
                        } catch (IOException e) {
                            Log.d("WM", "AcceptFileHandler: IOException, bytesRead = " + bytesRead);
                            aborted = true;
                            break;
                        }

                        if (bytesRead > 0) {
                            fs.write(buffer, 0, bytesRead);
                            bytesReadTotal += bytesRead;  // not critical but might be nice to know
                        }
                    }
                    Log.d("WM", "AcceptFileHandler: done read, bytesReadTotal = " + bytesReadTotal);
                } catch (Exception e) {
                    // something unexpected went wrong while writing the output
                    Log.d("WM", "AcceptFileHandler: exception-A");
                    e.printStackTrace();
                    aborted = true;
                }
                fs.close();
                if (aborted) {
                    Log.d("WM", "AcceptFileHandler: aborted (probably incomplete), deleting");
                    file.delete(); // incomplete, useless, may cause exceptions trying to unzip.
                } else {
                    Log.d("WM", "AcceptFileHandler: success");
                    result = "success"; // normal completion.
                }
            } catch (Exception e) {
                Log.d("WM", "AcceptFileHandler: exception-B");
                e.printStackTrace();
            }
        } else {
            Log.d("WM", "AcceptFileHandler: null entity!");
        }
        response.setEntity(new StringEntity(result));
        if (listener != null) {
            Log.d("WM", "AcceptFileHandler: valid listener, path = " + path);
            listener.receivedFile(path, result == "success");
        } else {
            Log.d("WM", "AcceptFileHandler: null listener-B!");
        }
    }

    public interface IFileReceivedNotification {
        void receivingFile(String name);
        void receivedFile(String name, boolean success);
    }

    static IFileReceivedNotification listener;
    public static void requestFileReceivedNotification(IFileReceivedNotification newListener) {
        listener = newListener; // We only support notifying the most recent for now.
    }
}
