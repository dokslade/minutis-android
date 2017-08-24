package org.croixrouge.minutis;

import android.content.Intent;

import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;

import java.util.Map;

public class MinutisFirebaseMessagingService extends FirebaseMessagingService {

    @Override
    public void onMessageReceived(RemoteMessage remoteMessage) {

        //TODO: Validate getFrom() entity

        // Send message to Minutis service
        final Intent service = new Intent(MinutisService.INTENT_FIREBASE_DATA, null, this, MinutisService.class);
        final Map<String,String> data = remoteMessage.getData();
        for (final Map.Entry<String,String> entry: data.entrySet()) {
            service.putExtra(entry.getKey(), entry.getValue());
        }
        startService(service);
    }
}
