package org.croixrouge.minutis;

import android.os.Bundle;

interface MinutisServiceListener {

    void onAuthenticated();

    void onAuthFailed(String origin);

    void onAccessTokenChanged(String accessToken);

    void onNotificationData(Bundle params);
}
