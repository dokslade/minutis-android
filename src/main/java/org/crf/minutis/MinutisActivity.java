package org.crf.minutis;

import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.graphics.drawable.Drawable;
import android.graphics.PorterDuff;
import android.net.Uri;
import android.support.design.widget.Snackbar;
import android.support.v7.app.AlertDialog;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.Toolbar;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.widget.BaseAdapter;
import android.widget.ListView;
import android.widget.TextView;

import java.util.ArrayList;



/* TODO
 * - reset screen when disconnected
 * - selector for edit, direction and connection
 * - check @ and phone number when connection
 */
public class MinutisActivity extends AppCompatActivity {

	private boolean isConnected;
	private ArrayList<Message> messages;
	private ListView lv;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);
		Toolbar toolbar = (Toolbar) findViewById(R.id.toolbar);
		setSupportActionBar(toolbar);

		messages = new ArrayList<Message>();

		lv = (ListView) findViewById(R.id.list_messages);
		lv.setEmptyView(findViewById(R.id.empty_list));
		MessagesAdapter adapter = new MessagesAdapter(this, messages);
		lv.setAdapter(adapter);
    }

	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		MenuInflater inflater = getMenuInflater();
		inflater.inflate(R.menu.menu, menu);

		// TODO doesn't work
		// Drawable d = menu.findItem(R.id.connect).getIcon();
		// d.mutate();
		// d.setTint(R.color.text_primary);
		// d.setColorFilter(R.color.text_primary,
		// 				 PorterDuff.Mode.SRC_ATOP);
		// menu.findItem(R.id.connect).setIcon(d);
		return true;
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		// Handle item selection
		switch (item.getItemId()) {
        case R.id.connect:
			showConnect();
            return true;
		case R.id.settings:
			startSettings();
			return true;
        case R.id.lorem_ipsum:
			addLoremIpsum();
            return true;
        default:
            return super.onOptionsItemSelected(item);
		}
	}

	private void showConnect() {
		String message = isConnected ? "Voulez-vous vous déconnecter du serveur ?" :
			"Voulez-vous vous connecter au serveur ?";
		AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setMessage(message)
			.setPositiveButton(R.string.all_yes, new DialogInterface.OnClickListener() {
					public void onClick(DialogInterface dialog, int id) {
						isConnected = !isConnected;
					}
				})
			.setNegativeButton(R.string.all_cancel, null);
        builder.create().show();

	}

	private void startSettings() {
		Intent intent = new Intent(this, SettingsActivity.class);
		startActivity(intent);
	}

	public void setStatus(View v) {
		AlertDialog.Builder builder = new AlertDialog.Builder(this);
		builder.setTitle(R.string.main_select_state)
			.setItems(R.array.state_values, new DialogInterface.OnClickListener() {
					public void onClick(DialogInterface dialog, int which) {
						TextView tv = (TextView) findViewById (R.id.state_value);
						tv.setText(getResources().getStringArray(R.array.state_values)[which]);
					}
				});
		builder.create().show();
	}

	public void startNavigation(View v) {
		int position = lv.getPositionForView(v);
		String address = messages.get(position).address;
		Intent intent = new Intent(Intent.ACTION_VIEW);
		intent.setData(Uri.parse("geo:0,0?q=" + address));
		if (intent.resolveActivity(getPackageManager()) != null) {
			startActivity(intent);
		} else {
			Snackbar snackbar = Snackbar.make(lv, R.string.no_navigation_app, Snackbar.LENGTH_LONG);
			int snackbarTextId = android.support.design.R.id.snackbar_text;
			TextView tv = (TextView) snackbar.getView().findViewById(snackbarTextId);
			tv.setTextColor(getResources().getColor(R.color.accent));
			tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
			snackbar.show();
		}
	}

	private void addLoremIpsum() {
		((BaseAdapter) lv.getAdapter()).notifyDataSetChanged();
	}
}