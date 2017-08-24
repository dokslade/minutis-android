package org.croixrouge.minutis;

import android.os.Bundle;
import android.preference.PreferenceFragment;

public class SettingsFragment extends PreferenceFragment {

    public static final String PREF_LOCAL_SERVER = "local.server";
    public static final String PREF_GEOLOCATION_ENABLED = "geolocation.enabled";

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Load the preferences from an XML resource
        addPreferencesFromResource(R.xml.preferences);
    }
}
