/*
 **
 ** Copyright 2010, Koushik Dutta
 ** Copyright 2011, The CyanogenMod Project
 **
 ** Licensed under the Apache License, Version 2.0 (the "License");
 ** you may not use this file except in compliance with the License.
 ** You may obtain a copy of the License at
 **
 **       http://www.apache.org/licenses/LICENSE-2.0
 **
 ** Unless required by applicable law or agreed to in writing, software
 ** distributed under the License is distributed on an "AS IS" BASIS,
 ** WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 ** See the License for the specific language governing permissions and
 ** limitations under the License.
 */

package com.cyanogenmod.screenshot;
import android.app.*;
import android.content.*;
import android.database.*;
import android.graphics.*;
import android.graphics.Bitmap.*;
import android.media.*;
import android.net.*;
import android.os.*;
import android.provider.*;
import android.text.format.DateFormat;
import android.util.*;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.util.Date;

public final class ScreenshotActivity extends Activity {
	private ContentResolver CR;
	private static final String TAG = "CMScreenshot";

    private static String mSSBucketName =
	Environment.getExternalStorageDirectory().toString()
	+ "/DCIM/Screenshots";

	private static final String DATEFORMAT_12 = "yyyyMMdd-hhmmssaa";
	private static final String DATEFORMAT_24 = "yyyyMMdd-kkmmss";

	Handler mHander = new Handler();
	String mScreenshotFile;
	MediaScannerConnection mConnection;

    /** Called when the activity is first created. */
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
		CR = getContentResolver();
		String PATH=Settings.System.getString(CR, "path");
		if (PATH == null || PATH.equals("")) {
            PATH = mSSBucketName;
		}
		mSSBucketName = Environment.getExternalStorageDirectory().toString() + "/" + PATH;

        if (! Environment.getExternalStorageState().equals(Environment.MEDIA_MOUNTED)) {
            Toast.makeText(ScreenshotActivity.this, getString(R.string.not_mounted), Toast.LENGTH_LONG).show();
            finish();
        }
        mConnection = new MediaScannerConnection(ScreenshotActivity.this, mMediaScannerConnectionClient);

		final int waitTime = Settings.System.getInt(CR, "screenshot_delay", 1);
		takeScreenshot(waitTime);
    }
    private void takeScreenshot() {
        final String rawScreenshot = String.format("%s/tmpshot.bmp", Environment.getExternalStorageDirectory().toString());
        File dir = null;
        try {
            java.lang.Process p = Runtime.getRuntime().exec("/system/bin/screenshot");
            Log.d(TAG, "Ran helper");
            p.waitFor();
			Bitmap bitmap = BitmapFactory.decodeFile(rawScreenshot);
			File tmpshot = new File(rawScreenshot);
            tmpshot.delete();

            if (bitmap == null) {
                throw new Exception("Unable to save screenshot");
            }

			int hwRotation = (360 - SystemProperties.getInt("ro.sf.hwrotation", 0)) % 360;

			Display display = ((WindowManager) getSystemService(WINDOW_SERVICE)).getDefaultDisplay();
			int deviceRotation = (display.getOrientation() * 90) % 360;
			int finalRotation = hwRotation - deviceRotation;
			Log.d(TAG, "Hardware rotation " + hwRotation + ", device rotation " +
				  deviceRotation + " -> rotate by " + finalRotation);

			if (finalRotation != 0) {
				Matrix matrix = new Matrix();
				matrix.postRotate(finalRotation);
				bitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
			}

			dir = new File(mSSBucketName);
			if (!dir.exists()) dir.mkdirs();

			CharSequence date = DateFormat.format(
				DateFormat.is24HourFormat(this) ? DATEFORMAT_24 : DATEFORMAT_12, new Date());

			mScreenshotFile = String.format("%s/screenshot-%s.png", mSSBucketName, date);
			FileOutputStream fout = new FileOutputStream(mScreenshotFile);
			String mFormat=Settings.System.getString(CR, "screenshot_format");
			int quality = Settings.System.getInt(CR, "screenshot_quality", 100);
			boolean isSucceeded = true;

			if (mFormat == null || mFormat.equals("") || mFormat.equals("png")) {
				isSucceeded = bitmap.compress(CompressFormat.PNG, quality, fout);
			} else {
				isSucceeded = bitmap.compress(CompressFormat.JPEG, quality, fout);
			}
			fout.close();
			if (!isSucceeded) Toast.makeText(this, "保存失败!", Toast.LENGTH_LONG).show();
			final boolean shareScreenshot = Settings.System.getInt(CR, "share_screenshot", 0) == 1;
			if (shareScreenshot) {
				try {
                    Intent intent = new Intent(Intent.ACTION_SEND);
                    intent.setType("image/" + mFormat);
                    intent.putExtra(Intent.EXTRA_STREAM, Uri.fromFile(new File(mScreenshotFile)));
                    startActivity(Intent.createChooser(intent, getString(R.string.share_message)));
				} catch (ActivityNotFoundException e) {
					Toast.makeText(ScreenshotActivity.this, R.string.no_way_to_share, Toast.LENGTH_SHORT).show();
				}
			}
		} catch (Exception e) {
			Log.e(TAG, "Couldn't save screenshot", e);
			Toast.makeText(ScreenshotActivity.this, getString(R.string.toast_error), Toast.LENGTH_LONG).show();
			finish();
			return;
        }

        mConnection.connect();

		//MEUI发送通知
		Notification notif = new Notification(android.R.drawable.sym_def_app_icon, getString(R.string.save_title), System.currentTimeMillis());
		notif.flags = Notification.FLAG_AUTO_CANCEL;
		final Uri uri=getImageContentUri(ScreenshotActivity.this, dir);

		Intent intent = new Intent(Intent.ACTION_VIEW);
	    intent.setDataAndType(uri, "image/*");
        final PendingIntent launchIntent = PendingIntent.getActivity(ScreenshotActivity.this, 0, intent, 0);
		notif.setLatestEventInfo(ScreenshotActivity.this, getString(R.string.save_title), getString(R.string.click_view), launchIntent);
        final NotificationManager nm=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);
		nm.cancel(1);// 先移除旧的通知
		nm.notify(1, notif);
    }

    MediaScannerConnection.MediaScannerConnectionClient mMediaScannerConnectionClient = new MediaScannerConnection.MediaScannerConnectionClient() {
        public void onScanCompleted(String path, Uri uri) {
            mConnection.disconnect();
            finish();
        }
        public void onMediaScannerConnected() {
			mConnection.scanFile(mScreenshotFile, null);
		}
    };

    private void takeScreenshot(final int delay) {
        mHander.postDelayed(new Runnable() {
				@Override
				public void run() {
					takeScreenshot();
				}
			}, delay * 1000);
    }
    /**
	 * Gets the content:// URI from the given corresponding path to a file
	 * @param context 
	 * @param imageFile
	 * @return content Uri
	 */
	private final Uri getImageContentUri(final Context context, final java.io.File imageFile) { 
		final String filePath = imageFile.getAbsolutePath();
		final Cursor cursor = context.getContentResolver().query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, new String[] { MediaStore.Images.Media._ID }, MediaStore.Images.Media.DATA + "=? ", new String[] { filePath }, null); 
		if (cursor != null && cursor.moveToFirst()) {
			final int id = cursor.getInt(cursor.getColumnIndex(MediaStore.MediaColumns._ID));
			final Uri baseUri = Uri.parse("content://media/external/images/media"); 
			return Uri.withAppendedPath(baseUri, "" + id); 
		} else {
			if (imageFile.exists()) {
                ContentValues values = new ContentValues();
				values.put(MediaStore.Images.Media.DATA, filePath);
				return context.getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
			}
			return null;
		} 
	}	
}
