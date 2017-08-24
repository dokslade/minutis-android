package org.croixrouge.minutis;

import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.preference.CheckBoxPreference;
import android.preference.PreferenceFragment;
import android.preference.PreferenceManager;
import android.support.annotation.NonNull;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.util.Log;

public class SettingsActivity extends AppCompatActivity
        implements SharedPreferences.OnSharedPreferenceChangeListener {
    private static final String TAG = "Minutis.Settings";

    private PreferenceFragment mFragment;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        // Load preferences default values (if not already loaded)
        PreferenceManager.setDefaultValues(this, R.xml.preferences, false);

        // Display the fragment as the main content
        mFragment = new SettingsFragment();
        getFragmentManager().beginTransaction()
                .replace(R.id.content, mFragment)
                .commit();

    }

    @Override
    protected void onResume() {
        super.onResume();

        // Register preference change listener
        final SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this);
        sharedPreferences.registerOnSharedPreferenceChangeListener(this);
    }

    @Override
    protected void onPause() {
        super.onPause();

        // Unregister preference change listener
        final SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this);
        sharedPreferences.unregisterOnSharedPreferenceChangeListener(this);
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
        if (SettingsFragment.PREF_GEOLOCATION_ENABLED.equals(key)) {
            if (sharedPreferences.getBoolean(key, false)) {
                MainActivity.startGeolocation(this);
            }
            else {
                MainActivity.stopGeolocation(this);
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String permissions[], @NonNull int[] grantResults) {
        switch(requestCode) {
            case MainActivity.REQUEST_PERMISSIONS_GEOLOCATION:
                if ((grantResults.length > 0) && (grantResults[0] == PackageManager.PERMISSION_GRANTED)) {
                    MainActivity.startGeolocation(this);
                }
                else {
                    // Disable geolocation
                    final CheckBoxPreference preference = (CheckBoxPreference) mFragment.findPreference(SettingsFragment.PREF_GEOLOCATION_ENABLED);
                    if (preference != null) {
                        Log.d("Minutis", "Found preference");
                        preference.setChecked(false);
                    }

                    MainActivity.stopGeolocation(this);
                }
                break;

            default:
                Log.e(TAG, "Received unknown permissions result (requestCode=" + requestCode + ')');
        }
    }
}
