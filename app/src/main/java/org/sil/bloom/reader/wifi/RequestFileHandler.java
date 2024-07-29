package org.sil.bloom.reader.wifi;

import android.content.Context;
import android.net.Uri;

//import org.apache.http.HttpException;
//import org.apache.http.HttpRequest;
//import org.apache.http.HttpResponse;
//import org.apache.http.HttpStatus;
//import org.apache.http.entity.FileEntity;
//import org.apache.http.entity.StringEntity;
//import org.apache.http.protocol.HttpContext;
//import org.apache.http.protocol.HttpRequestHandler;
import cz.msebera.android.httpclient.HttpException;
import cz.msebera.android.httpclient.HttpRequest;
import cz.msebera.android.httpclient.HttpResponse;
import cz.msebera.android.httpclient.HttpStatus;
import cz.msebera.android.httpclient.entity.FileEntity;
import cz.msebera.android.httpclient.entity.StringEntity;
import cz.msebera.android.httpclient.protocol.HttpContext;
import cz.msebera.android.httpclient.protocol.HttpRequestHandler;

import java.io.File;
import java.io.IOException;

// WM -- this class should be removable because BloomReader already has file transfer logic in
//       place.

/**
 * Created by Thomson on 12/28/2014.
 */
public class RequestFileHandler implements HttpRequestHandler {
    Context _parent;
    public RequestFileHandler(Context parent)
    {
        _parent = parent;
    }
    @Override
    public void handle(HttpRequest request, HttpResponse response, HttpContext httpContext) throws HttpException, IOException {
        File baseDir = _parent.getExternalFilesDir(null);
        Uri uri = Uri.parse(request.getRequestLine().getUri());
        String filePath = uri.getQueryParameter("path");
        if (listener!= null)
            listener.sendingFile(filePath);
        String path = baseDir  + "/" + filePath;
        File file = new File(path);
        if (!file.exists()) {
            response.setStatusCode(HttpStatus.SC_NOT_FOUND);
            response.setEntity(new StringEntity(""));
            return;
        }
        FileEntity body = new FileEntity(file, "audio/mpeg");
        response.setHeader("Content-Type", "application/force-download");
        //response.setHeader("Content-Disposition","attachment; filename=" + );
        response.setEntity(body);
    }

    public interface IFileSentNotification {
        void sendingFile(String name);
    }

    static IFileSentNotification listener;
    public static void requestFileSentNotification(IFileSentNotification newListener) {
        listener = newListener; // We only support notifying the most recent for now.
    }
}
