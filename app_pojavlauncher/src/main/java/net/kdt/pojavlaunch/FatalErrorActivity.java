package net.kdt.pojavlaunch;

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

		String bootstrapActivityClassName = getString(R.string.main_activity_class_name);
		Class<?> activityClass = LauncherActivity.class;
		try {
			activityClass = Class.forName(bootstrapActivityClassName);
		}catch (Throwable th) {
			th.printStackTrace();
		}
		Class<?> finalActivityClass = activityClass;
		new AlertDialog.Builder(this)
			.setTitle(R.string.error_fatal)
			.setMessage(errHeader + "\n\n" + stackTrace)
			.setPositiveButton(android.R.string.ok, (p1, p2) -> finish())
			.setNegativeButton(R.string.global_restart, (p1, p2) -> startActivity(new Intent(FatalErrorActivity.this, finalActivityClass)))
			.setNeutralButton(android.R.string.copy, (p1, p2) -> {
				ClipboardManager mgr = (ClipboardManager) FatalErrorActivity.this.getSystemService(CLIPBOARD_SERVICE);
				mgr.setPrimaryClip(ClipData.newPlainText("error", stackTrace));

				finish();
			})
			.setCancelable(false)
			.show();
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
