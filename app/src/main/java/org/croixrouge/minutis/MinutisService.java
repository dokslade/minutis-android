package org.croixrouge.minutis;

import android.Manifest;
import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.graphics.BitmapFactory;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.AsyncTask;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Looper;
import android.preference.PreferenceManager;
import android.support.v4.app.NotificationCompat;
import android.support.v4.content.ContextCompat;
import android.util.Log;

import com.google.firebase.iid.FirebaseInstanceId;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public class MinutisService extends Service implements LocationListener /*, SharedPreferences.OnSharedPreferenceChangeListener*/ {
    private static final String TAG = "Minutis.MinutisService";

    private static final int NOTIFICATION_ID = 123456;

    public static final String INTENT_AUTHENTICATE = "intent.authenticate";
    public static final String INTENT_LOGOUT = "intent.logout";
    public static final String INTENT_FIREBASE_DATA = "intent.firebase.data";
    public static final String INTENT_FIREBASE_TOKEN = "intent.firebase.token";
    public static final String INTENT_LOCATION_START = "intent.geolocation.start";
    public static final String INTENT_LOCATION_STOP = "intent.geolocation.stop";

    private static final String MSG_LOCATION_PROVIDERS_CHANGED = "android.location.PROVIDERS_CHANGED";

    //FIXME: Find another method to send AUTH_FAILED to LoginActivity
    public static final String EXTRA_AUTH_FAILED_ORIGIN_AUTH = "authenticate";

    private static final int MIN_HTTP_ERROR_STATUS = 400;

    private final BroadcastReceiver mGlobalMessageReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent != null) {
                final String action = intent.getAction();
                if (action != null) {
                    switch (action) {
                        case MSG_LOCATION_PROVIDERS_CHANGED:
                            final LocationManager locationManager = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
                            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                                MinutisService.this.startGeolocation();
                            } else {
                                MinutisService.this.stopGeolocation();
                            }
                            break;

                        default:
                            Log.e(TAG, "Received unknown local broadcast message (action=" + action + ')');
                    }
                }
            }
        }
    };

    class LocalBinder extends Binder {
        MinutisService getService() {
            return MinutisService.this;
        }
    }

    private LocationManager mLocationManager;
    private final IBinder mBinder = new LocalBinder();
    private String mRefreshToken;
    private String mAccessToken;
    private final ScheduledExecutorService mScheduler = Executors.newScheduledThreadPool(1);
    private final Runnable mAuthenticator = new Authenticator();
    private ScheduledFuture mAuthenticatorFuture;
    private boolean mGeolocationActive = false;
    private MinutisServiceListener mServiceListener;
    private boolean mIsAuthenticated = false;

    public boolean isAuthenticated() {
        return mIsAuthenticated;
    }
    public String getAccessToken() { return mAccessToken; } // FIXME: Find another method avoiding to expose publicly the access token (ie. callback to listeners...)

    @Override
    public void onCreate() {
        super.onCreate();

        // Register global message receiver
        final IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(MSG_LOCATION_PROVIDERS_CHANGED);
        registerReceiver(mGlobalMessageReceiver, intentFilter);
    }

    @Override
    public void onDestroy() {
        // Ensure user is logged out
        doLogout(null);

        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            final String action = intent.getAction();
            if (action != null) {
                switch (action) {
                    case INTENT_AUTHENTICATE: {
                        // Async authenticate
                        final String username = intent.getStringExtra("username");
                        final String password = intent.getStringExtra("password");
                        new AsyncTask<Void, Void, Boolean>() {
                            @Override
                            protected Boolean doInBackground(Void... params) {
                                doAuthenticate(EXTRA_AUTH_FAILED_ORIGIN_AUTH, null, username, password);
                                return true;
                            }
                        }.execute((Void) null);
                    }
                    break;

                    case INTENT_LOGOUT: {
                        new AsyncTask<Void, Void, Boolean>() {
                            @Override
                            protected Boolean doInBackground(Void... params) {
                                doLogout(null);
                                return true;
                            }
                        }.execute((Void) null);
                    }
                    break;

                    case INTENT_LOCATION_START: {
                        new AsyncTask<Void, Void, Boolean>() {
                            @Override
                            protected Boolean doInBackground(Void... params) {
                                startGeolocation();
                                return true;
                            }
                        }.execute((Void) null);
                    }
                    break;

                    case INTENT_LOCATION_STOP: {
                        new AsyncTask<Void, Void, Boolean>() {
                            @Override
                            protected Boolean doInBackground(Void... params) {
                                stopGeolocation();
                                return true;
                            }
                        }.execute((Void) null);
                    }
                    break;

                    case INTENT_FIREBASE_DATA: {
                        // Notify the listener
                        if (mServiceListener != null) {
                            mServiceListener.onNotificationData(intent.getExtras());
                        }
                    }
                    break;

                    case INTENT_FIREBASE_TOKEN: {
                        final String token = intent.getStringExtra("token");
                        new AsyncTask<Void, Void, Boolean>() {
                            @Override
                            protected Boolean doInBackground(Void... params) {
                                if (mIsAuthenticated) {

                                    // Register to Minutis server with new token
                                    doRegister(token);
                                }
                                return true;
                            }
                        }.execute((Void) null);
                    }
                    break;

                    default:
                        Log.e(TAG, "Received unknown local broadcast message (action=" + action + ')');
                }
            }
        }
        return START_STICKY;
    }

    private void startForeground(String category, int defaults) {
        // Show notification
        final Intent minutisIntent = new Intent(this, MainActivity.class);
        final Resources resources = getResources();
        final NotificationCompat.Builder builder = new NotificationCompat.Builder(this)
                .setLargeIcon(BitmapFactory.decodeResource(resources, R.mipmap.ic_launcher))
                .setSmallIcon(mGeolocationActive ? R.drawable.ic_location_on_black_24dp : R.drawable.ic_location_off_black_24dp)
                .setContentTitle(getString(R.string.app_name))
                .setContentText("")
                .setDefaults(defaults)
                .setColor(ContextCompat.getColor(this, R.color.colorPrimary))
                .setContentIntent(PendingIntent.getActivity(this, 0, minutisIntent, PendingIntent.FLAG_UPDATE_CURRENT));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            builder.setCategory(category);
        }
        startForeground(NOTIFICATION_ID, builder.build());
    }

    public void registerListener(MinutisServiceListener listener) {
        mServiceListener = listener;
    }

    public void unregisterListener(MinutisServiceListener listener) {
        if (mServiceListener == listener) {
            mServiceListener = null;
        }
    }

    private void onAuthenticated() {
        if (!mIsAuthenticated) {
            mIsAuthenticated = true;

            // Register Firebase token
            doRegister(FirebaseInstanceId.getInstance().getToken());

            // Notify the listener
            if (mServiceListener != null) {
                mServiceListener.onAuthenticated();
            }

            startGeolocation();

            // Start foreground and show notification
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                startForeground(Notification.CATEGORY_SERVICE, 0);
            } else {
                startForeground(null, 0);
            }
        }
    }

    private void doLogout(String origin) {
        if (!mIsAuthenticated) {
            return;
        }
        mIsAuthenticated = false;

        // Cancel pending authenticator
        if (mAuthenticatorFuture != null) {
            mAuthenticatorFuture.cancel(true);
            mAuthenticatorFuture = null;
        }

        // Unregister Firebase token
        doUnregister();

        // Clear authentication tokens
        mRefreshToken = null;
        mAccessToken = null;

        stopGeolocation();

        // Stop foreground state and remove notification
        stopForeground(true);

        // Notify the listener
        if (mServiceListener != null) {
            mServiceListener.onAuthFailed(origin);
        }
    }

    private void doRegister(String token) {
        if ((token == null) || (token.isEmpty())) {
            // Invalid token
            return;
        }

        // Must be authenticated
        if (!mIsAuthenticated) {
            return;
        }

        HttpURLConnection connection = null;
        try {
            // Build JSON request
            final JSONObject jsonRequest = new JSONObject();
            jsonRequest.put("token", token);

            // Post HTTP authentication request
            final SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this);
            final URL url = new URL(MainActivity.getMinutisBaseURL(sharedPreferences.getBoolean(SettingsFragment.PREF_LOCAL_SERVER, false)) + "/api/mobile/register");
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("Authorization", "Bearer " + mAccessToken);
            connection.setDoOutput(true);
            final OutputStreamWriter writer = new OutputStreamWriter(connection.getOutputStream(), Charset.forName("UTF-8"));
            writer.write(jsonRequest.toString());
            writer.flush();
            writer.close();

            if (connection.getResponseCode() == 401) { // Unauthorized
                doLogout(null);
            }
            else if (connection.getResponseCode() >= MIN_HTTP_ERROR_STATUS) {
                Log.e(TAG, "Error while registering to Minutis server (HTTP status = " + Integer.toString(connection.getResponseCode()) + ')');
            }
        } catch (IOException | JSONException ex) {
            Log.e(TAG, "Error while registering to Minutis server", ex);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private void doUnregister() {
        // Must be authenticated
        if (!mIsAuthenticated) {
            return;
        }

        HttpURLConnection connection = null;
        try {
            // Build JSON request
            final JSONObject jsonRequest = new JSONObject();

            // Post HTTP authentication request
            final SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this);
            final URL url = new URL(MainActivity.getMinutisBaseURL(sharedPreferences.getBoolean(SettingsFragment.PREF_LOCAL_SERVER, false)) + "/api/mobile/unregister");
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("Authorization", "Bearer " + mAccessToken);
            connection.setDoOutput(true);
            final OutputStreamWriter writer = new OutputStreamWriter(connection.getOutputStream(), Charset.forName("UTF-8"));
            writer.write(jsonRequest.toString());
            writer.flush();
            writer.close();

            if (connection.getResponseCode() == 401) { // Unauthorized
                doLogout(null);
            }
            else if (connection.getResponseCode() >= MIN_HTTP_ERROR_STATUS) {
                Log.e(TAG, "Error while un-registering from Minutis server (HTTP status = " + Integer.toString(connection.getResponseCode()) + ')');
            }
        } catch (IOException ex) {
            Log.e(TAG, "Error while un-registering from Minutis server", ex);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private void doAuthenticate(String origin, String refreshToken, String username, String password) {
        HttpURLConnection connection = null;
        final SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this);
        try {
            // Build JSON request
            final JSONObject jsonRequest = new JSONObject();
            if ((refreshToken != null) && (!refreshToken.isEmpty())) {
                jsonRequest.put("refreshToken", refreshToken);
            } else {
                jsonRequest.put("username", username);
                jsonRequest.put("password", password);
            }

            // Post HTTP authentication request
            final URL url = new URL(MainActivity.getMinutisBaseURL(sharedPreferences.getBoolean(SettingsFragment.PREF_LOCAL_SERVER, false)) + "/api/auth");
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setDoOutput(true);
            final OutputStreamWriter writer = new OutputStreamWriter(connection.getOutputStream(), Charset.forName("UTF-8"));
            writer.write(jsonRequest.toString());
            writer.flush();
            writer.close();

            if (connection.getResponseCode() < MIN_HTTP_ERROR_STATUS) {
                // Read HTTP response
                String line;
                final StringBuilder response = new StringBuilder(1024);
                final BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream(), Charset.forName("UTF-8")));
                while ((line = reader.readLine()) != null) {
                    response.append(line).append('\n');
                }
                reader.close();

                // Convert response to JSONObject
                final JSONObject json = new JSONObject(response.toString());
                if (json.has("refreshToken") && json.has("accessToken") && json.has("accessTokenTimeout")) {
                    // Update accessToken and refreshToken
                    mRefreshToken = json.getString("refreshToken");
                    mAccessToken = json.getString("accessToken");

                    // Notify the listener
                    if (mServiceListener != null) {
                        mServiceListener.onAccessTokenChanged(mAccessToken);
                    }

                    // Schedule re-authentication (just before accessToken expires)
                    mAuthenticatorFuture = mScheduler.schedule(mAuthenticator, json.getLong("accessTokenTimeout") - 30, TimeUnit.SECONDS);

                    if (!mIsAuthenticated) {
                        onAuthenticated();
                    }

                } else {
                    MinutisService.this.doLogout(origin);
                }
            } else {
                MinutisService.this.doLogout(origin);
            }
        } catch (IOException | JSONException ex) {
            Log.e(TAG, "Error while authenticating to Minutis server", ex);
            MinutisService.this.doLogout(origin);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private void startGeolocation() {
        if (!mGeolocationActive && mIsAuthenticated) {
            // Check if geolocation is enabled
            final SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this);
            if (sharedPreferences.getBoolean(SettingsFragment.PREF_GEOLOCATION_ENABLED, false)) {

                // Check if permissions are granted
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {

                    if (mLocationManager == null) {
                        mLocationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
                    }

                    // Check if GPS is globally enabled
                    if (mLocationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {

                        // Start listening for location updates
                        mLocationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 5000L, 0.0f, this, Looper.getMainLooper());
                        mGeolocationActive = true;

                        // Update foreground notification
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                            startForeground(Notification.CATEGORY_SERVICE, 0);
                        } else {
                            startForeground(null, 0);
                        }
                    }
                }
            }
        }
    }

    private void stopGeolocation() {
        if (mGeolocationActive) {
            // Stop listening location updates
            if (mLocationManager != null) {
                mLocationManager.removeUpdates(this);
                mGeolocationActive = false;

                // Update foreground notification
                if (mIsAuthenticated) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        startForeground(Notification.CATEGORY_SERVICE, 0);
                    } else {
                        startForeground(null, 0);
                    }
                }
            }
        }
    }

    @Override
    public void onLocationChanged(final Location location) {
        new AsyncTask<Void, Void, Boolean>() {
            @Override
            protected Boolean doInBackground(Void... params) {

                // Must be authenticated
                if (!mIsAuthenticated) {
                    return false;
                }

                // Send location to Minutis server
                HttpURLConnection connection = null;
                try {
                    // Build JSON request
                    final JSONObject jsonRequest = new JSONObject();
                    jsonRequest.put("latitude", location.getLatitude());
                    jsonRequest.put("longitude", location.getLongitude());
                    jsonRequest.put("time", location.getTime());

                    // Post HTTP authentication request
                    final SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(MinutisService.this);
                    final URL url = new URL(MainActivity.getMinutisBaseURL(sharedPreferences.getBoolean(SettingsFragment.PREF_LOCAL_SERVER, false)) + "/api/mobile/location");
                    connection = (HttpURLConnection) url.openConnection();
                    connection.setRequestMethod("POST");
                    connection.setRequestProperty("Content-Type", "application/json");
                    connection.setRequestProperty("Authorization", "Bearer " + mAccessToken);
                    connection.setDoOutput(true);
                    final OutputStreamWriter writer = new OutputStreamWriter(connection.getOutputStream(), Charset.forName("UTF-8"));
                    writer.write(jsonRequest.toString());
                    writer.flush();
                    writer.close();

                    if (connection.getResponseCode() == 401) { // Unauthorized
                        doLogout(null);
                    }
                    else if (connection.getResponseCode() < MIN_HTTP_ERROR_STATUS) {
                        return true;
                    }
                    else {
                        Log.e(TAG, "Error while sending location to Minutis server (HTTP status = " + Integer.toString(connection.getResponseCode()) + ')');
                        return false;
                    }
                } catch (IOException | JSONException ex) {
                    Log.e(TAG, "Error while sending location to Minutis server", ex);
                } finally {
                    if (connection != null) {
                        connection.disconnect();
                    }
                }

                return false;
            }
        }.execute((Void) null);
    }

    @Override
    public void onProviderDisabled(String provider) {
    }


    @Override
    public void onProviderEnabled(String provider) {
        // This method can't be used to detect GPS activation because the listener may have been unregistered during previous GPS deactivation
    }

    @Override
    public void onStatusChanged(String provider, int status, Bundle extras) {
    }

    private class Authenticator implements Runnable {
        @Override
        public void run() {
            final SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(MinutisService.this);
            doAuthenticate("Authenticator", sharedPreferences.getString(MinutisService.this.mRefreshToken, ""), null, null);
        }
    }
}
