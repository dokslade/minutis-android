package org.croixrouge.minutis;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.os.Build;
import android.os.IBinder;
import android.net.Uri;
import android.preference.PreferenceManager;
import android.support.annotation.NonNull;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.support.v7.app.AlertDialog;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.util.Log;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

public class MainActivity extends AppCompatActivity implements MinutisServiceListener {
    private static final String TAG = "Minutis.MainActivity";

    private static final String PREF_WEBVIEW_URL = "webViewUrl";
    private static final String URL_WEBVIEW_ERROR = "file:///android_asset/error.html";

    public static final int REQUEST_PERMISSIONS_GEOLOCATION = 0;

    private WebView mWebView;
    private MinutisService mService;

    private final ServiceConnection mConnection = new ServiceConnection() {
        public void onServiceConnected(ComponentName className, IBinder service) {
            MinutisService.LocalBinder binder = (MinutisService.LocalBinder) service;
            mService = binder.getService();

            if (mService.isAuthenticated()) {
                // Register service listener
                mService.registerListener(MainActivity.this);

                // Start geolocation
                startGeolocation(MainActivity.this);

                // Load Minutis mobile web page
                final SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(MainActivity.this);
                final String defaultURL = getMinutisBaseURL(sharedPreferences.getBoolean(SettingsFragment.PREF_LOCAL_SERVER, false)) + "/mobile/";
                mWebView.clearCache(true); // FIXME: This could be removed when mobile web site has been released
                mWebView.loadUrl(sharedPreferences.getString(PREF_WEBVIEW_URL, defaultURL));
            }
            else {
                // Redirect to LoginActivity if not authenticated
                startActivity(new Intent(MainActivity.this, LoginActivity.class));
            }
        }

        public void onServiceDisconnected(ComponentName className) {
            Log.d(TAG, "onServiceDisconnected()");
            mService = null;
        }
    };

    @Override
    @SuppressLint("SetJavaScriptEnabled")
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_minutis);

        // Load preferences default values (if not already loaded)
        PreferenceManager.setDefaultValues(this, R.xml.preferences, false);

        mWebView = (WebView)findViewById(R.id.activity_minutis_webview);
        final WebSettings webSettings = mWebView.getSettings();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            webSettings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        }
        webSettings.setJavaScriptEnabled(true);
        webSettings.setSupportZoom(false);
        webSettings.setGeolocationEnabled(false);
        mWebView.clearCache(true);
        mWebView.setWebViewClient(new MinutisWebViewClient());
    }

    @Override
    protected void onStart() {
        super.onStart();

        // Start persistent Minutis Service
        startService(new Intent(this, MinutisService.class));

        // Bind to the Minutis service
        Intent intent = new Intent(this, MinutisService.class);
        bindService(intent, mConnection, Context.BIND_AUTO_CREATE);
    }

    @Override
    protected void onStop() {
        super.onStop();

        // Save current URL
        String currentURL = mWebView.getUrl();
        final SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this);
        if ((currentURL != null) && (currentURL.startsWith(getMinutisBaseURL(sharedPreferences.getBoolean(SettingsFragment.PREF_LOCAL_SERVER, false))))) {
            sharedPreferences.edit().putString(PREF_WEBVIEW_URL, currentURL).apply();
        }

        // Load empty page to stop current page execution
        mWebView.loadUrl("about:blank");

        // Unbind from the Minutis service
        if (mService != null) {
            // Unregister listener from service
            mService.unregisterListener(this);

            unbindService(mConnection);
            mService = null;
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String permissions[], @NonNull int[] grantResults) {
        switch(requestCode) {
            case REQUEST_PERMISSIONS_GEOLOCATION:
                if ((grantResults.length > 0) && (grantResults[0] == PackageManager.PERMISSION_GRANTED)) {
                    startGeolocation(this);
                } else {
                    // Disable geolocation
                    final SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this);
                    sharedPreferences.edit().putBoolean(SettingsFragment.PREF_GEOLOCATION_ENABLED, false).apply();

                    stopGeolocation(this);
                }
                break;

            default:
                Log.e(TAG, "Received unknown permissions result (requestCode=" + requestCode + ')');
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        final MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.menu, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.settings:
                // Start SettingsActivity
                startActivity(new Intent(this, SettingsActivity.class));
                return true;

            case R.id.logout:
                final AlertDialog.Builder builder = new AlertDialog.Builder(this);
                builder.setTitle(R.string.dialog_logout_title)
                        .setMessage(R.string.dialog_logout_message)
                        .setPositiveButton(R.string.yes, new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int id) {
                                // Reset default URL
                                final SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(MainActivity.this);
                                sharedPreferences.edit().remove(PREF_WEBVIEW_URL).apply();

                                // Ask Minutis service to logout
                                startService(new Intent(MinutisService.INTENT_LOGOUT, null, MainActivity.this, MinutisService.class));
                            }
                        })
                        .setNegativeButton(R.string.no, null)
                        .create()
                        .show();
                return true;

            case R.id.contacts:
                final SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this);
                mWebView.loadUrl(getMinutisBaseURL(sharedPreferences.getBoolean(SettingsFragment.PREF_LOCAL_SERVER, false)) + "/mobile/contacts.html");
                return true;

            default:
                return super.onOptionsItemSelected(item);
        }
    }

    public static void startGeolocation (Activity activity) {
        // Is geolocation enabled
        final SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(activity);
        if (sharedPreferences.getBoolean(SettingsFragment.PREF_GEOLOCATION_ENABLED, false)) {

            // Check permissions
            if (ContextCompat.checkSelfPermission(activity, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {

                // Check if GPS is globally enabled
                final LocationManager locationManager = (LocationManager) activity.getSystemService(Context.LOCATION_SERVICE);
                if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                    // Start geolocation
                    activity.startService(new Intent(MinutisService.INTENT_LOCATION_START, null, activity, MinutisService.class));
                }
            }
            else {

                // Request geolocation permissions
                ActivityCompat.requestPermissions(activity, new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, REQUEST_PERMISSIONS_GEOLOCATION);
            }
        }
    }

    public static void stopGeolocation (Activity activity) {
        activity.startService(new Intent(MinutisService.INTENT_LOCATION_STOP, null, activity, MinutisService.class));
    }

    public static String getMinutisBaseURL(boolean localServer) {
        if (localServer) {
            return "https://mobile.minutis.local";
        }
        else {
            return "https://preprod.minutis.crfidf.net";
        }
    }

    @Override
    public void onAuthenticated() {
        // Nothing to do (should not happen: authentication is handled by LoginActivity)
    }

    @Override
    public void onAuthFailed(String origin) {
        // Redirect to LoginActivity
        startActivity(new Intent(this, LoginActivity.class));
    }

    @Override
    public void onAccessTokenChanged(String accessToken) {
        // Notify the webview
        mWebView.loadUrl("javascript:setAuthAccessToken('" + accessToken + "');");
    }

    @Override
    public void onNotificationData(Bundle params) {
        //TODO
    }

    private class MinutisWebViewClient extends WebViewClient {
        @Override
        public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
            // Load a custom error page
            view.loadUrl(URL_WEBVIEW_ERROR);
        }

        @Override
        public void onPageFinished(WebView webView, String url) {
            Log.d(TAG, "onPageFinished " + url); // FIXME

            final SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(MainActivity.this);
            if ((url != null) && (url.startsWith(getMinutisBaseURL(sharedPreferences.getBoolean(SettingsFragment.PREF_LOCAL_SERVER, false))))) {
		    if (mService != null) {
		        // Send the authentication token to the javascript application
		        final String accessToken = mService.getAccessToken();
		        if ((accessToken != null) && !accessToken.isEmpty()) {
		            mWebView.loadUrl("javascript:setAuthAccessToken('" + accessToken + "');");
		        }
		    }
            }
        }

	@Override
	public boolean shouldOverrideUrlLoading(WebView view, String url) {
            if (url.startsWith("tel:")) {
                MainActivity.this.startActivity(new Intent(Intent.ACTION_DIAL, Uri.parse(url)));
                return true;
            }
            else if (url.contains("mailto:")) {
                MainActivity.this.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
                return true;
            }
            else {
                view.loadUrl(url);
                return true;
            }
	}
    }
}
