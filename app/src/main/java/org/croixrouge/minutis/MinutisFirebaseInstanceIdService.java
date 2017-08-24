package org.croixrouge.minutis;

import android.content.Intent;

import com.google.firebase.iid.FirebaseInstanceId;
import com.google.firebase.iid.FirebaseInstanceIdService;

public class MinutisFirebaseInstanceIdService extends FirebaseInstanceIdService {
    @Override
    public void onTokenRefresh() {

        // Send new token to Minutis service
        final Intent service = new Intent(MinutisService.INTENT_FIREBASE_TOKEN, null, this, MinutisService.class);
        service.putExtra("token", FirebaseInstanceId.getInstance().getToken());
        startService(service);
    }
}
