package net.kdt.pojavlaunch;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

public class FatalErrorActivity extends AppCompatActivity {

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		
		Bundle extras = getIntent().getExtras();
		if(extras == null) {
			finish();
			return;
		}
		boolean storageAllow = extras.getBoolean("storageAllow", false);
		Throwable throwable = (Throwable) extras.getSerializable("throwable");
		final String stackTrace = throwable != null ? Tools.printToString(throwable) : "<null>";
		String strSavePath = extras.getString("savePath");
		String errHeader = storageAllow ?
			"Crash stack trace saved to " + strSavePath + "." :
			"Storage permission is required to save crash stack trace!";

		AlertDialog.Builder fatalDialog = new AlertDialog.Builder(this)
			.setTitle(R.string.error_fatal)
			.setMessage(throwable != null ? throwable.getLocalizedMessage() : "")
			.setNeutralButton(R.string.discord_support_title, (dialogInterface, i) -> Tools.showDiscordSupport(this))
			.setCancelable(false);

		if(storageAllow) fatalDialog.setPositiveButton(R.string.main_share_logs, (dialogInterface, which)-> Tools.shareCrashLog(this));

		fatalDialog.create().show();
	}

	public static void showError(Context ctx, String savePath, boolean storageAllow, Throwable th) {
		Intent fatalErrorIntent = new Intent(ctx, FatalErrorActivity.class);
		fatalErrorIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK);
		fatalErrorIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
		fatalErrorIntent.putExtra("throwable", th);
		fatalErrorIntent.putExtra("savePath", savePath);
		fatalErrorIntent.putExtra("storageAllow", storageAllow);
		ctx.startActivity(fatalErrorIntent);
	}
}
