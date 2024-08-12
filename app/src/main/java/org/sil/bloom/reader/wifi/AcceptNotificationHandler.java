package org.sil.bloom.reader.wifi;

//import android.content.Context;
import cz.msebera.android.httpclient.HttpException;
import cz.msebera.android.httpclient.HttpRequest;
import cz.msebera.android.httpclient.HttpResponse;
import cz.msebera.android.httpclient.entity.StringEntity;
import cz.msebera.android.httpclient.protocol.HttpContext;
import cz.msebera.android.httpclient.protocol.HttpRequestHandler;

import java.io.IOException;
import java.util.ArrayList;

/**
 * Created by Thomson on 1/18/2016.
 */
public class AcceptNotificationHandler implements HttpRequestHandler {

    public interface NotificationListener {
        void onNotification(String message);
    }
    static ArrayList<NotificationListener> notificationListeners= new ArrayList<NotificationListener>();

    public static void addNotificationListener(NotificationListener listener) {
        notificationListeners.add(listener);
    }

    public static void removeNotificationListener(NotificationListener listener) {
        notificationListeners.remove(listener);
    }

    @Override
    public void handle(HttpRequest request, HttpResponse response, HttpContext httpContext) throws HttpException, IOException {

        // Enhance: allow the notification to contain a message, and pass it on.
        // The copy is made because the onNotification calls may well remove listeners, leading to concurrent modification exceptions.
        for (NotificationListener listener: notificationListeners.toArray(new NotificationListener[notificationListeners.size()])) {
            listener.onNotification("");
        }
        response.setEntity(new StringEntity("success"));
    }
}
