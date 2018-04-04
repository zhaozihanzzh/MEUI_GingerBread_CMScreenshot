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
import android.util.*;
import android.view.*;
import android.widget.*;
import java.io.*;

/**
 *
 * Copyright 2010, Koushik Dutta
 * Copyright 2011, The CyanogenMod Project 
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); 
 * you may not use this file except in compliance with the License. 
 * You may obtain a copy of the License at 
 *
 *       http://www.apache.org/licenses/LICENSE-2.0 
 *
 * Unless required by applicable law or agreed to in writing, software 
 * distributed under the License is distributed on an "AS IS" BASIS, 
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. 
 * See the License for the specific language governing permissions and 
 * limitations under the License.
 */

public final class ScreenshotActivity extends Activity {
    private static Bitmap mBitmap = null;
    private Handler mHander = new Handler();
    private String mScreenshotFile;
	private ContentResolver CR;
	private static final String TAG = ScreenshotActivity.class.getSimpleName();

    private static String mSSBucketName =
	Environment.getExternalStorageDirectory().toString()
	+ "/DCIM/Screenshots";

    MediaScannerConnection mConnection;
    MediaScannerConnection.MediaScannerConnectionClient mMediaScannerConnectionClient = new MediaScannerConnection.MediaScannerConnectionClient() {
        public void onScanCompleted(String path, Uri uri) {
            mConnection.disconnect();
            finish();
        }
        public void onMediaScannerConnected() {}
    };

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
        mConnection.connect();
		// 等待
		final int waitTime = Settings.System.getInt(CR, "screenshot_delay", 1);
		takeScreenshot(waitTime);
    }
    private void takeScreenshot() {
        final String mRawScreenshot = String.format("%s/tmpshot.bmp", Environment.getExternalStorageDirectory().toString());
        File dir = null;
        try {
            java.lang.Process p = Runtime.getRuntime().exec("/system/bin/screenshot");
            Log.d(TAG, "Ran helper");
            p.waitFor();
            mBitmap = BitmapFactory.decodeFile(mRawScreenshot);

            // What are they doing?
            // File tmpshot = new File(mRawScreenshot);
            // tmpshot.delete();

            if (mBitmap == null) {
                throw new Exception("Unable to save screenshot: mBitmap = " + mBitmap);
            }

            // valid values for ro.sf.hwrotation are 0, 90, 180 & 270
            int rot = 360 - SystemProperties.getInt("ro.sf.hwrotation", 0);

            final Display display = ((WindowManager) getSystemService(WINDOW_SERVICE)).getDefaultDisplay();

            // First round, natural device rotation
            if (rot > 0 && rot < 360) {
                Log.d(TAG, "rotation=" + rot);
                Matrix matrix = new Matrix();
                matrix.postRotate(rot);
                mBitmap = Bitmap.createBitmap(mBitmap, 0, 0, mBitmap.getWidth(), mBitmap.getHeight(), matrix, true);
            }

            // Second round, device orientation:
            // getOrientation returns 0-3 for 0, 90, 180, 270, relative
            // to the natural position of the device
            rot = (display.getOrientation() * 90);
            rot %= 360;
            if (rot > 0) {
                Log.d(TAG, "rotation=" + rot);
                Matrix matrix = new Matrix();
                matrix.postRotate((rot * -1));
                mBitmap = Bitmap.createBitmap(mBitmap, 0, 0, mBitmap.getWidth(), mBitmap.getHeight(), matrix, true);
            }
            try {
                dir = new File(mSSBucketName);
                if (!dir.exists()) dir.mkdirs();

				String mFormat = Settings.System.getString(CR, "screenshot_format");

                mScreenshotFile = String.format("%s/screenshot-%d." + mFormat, mSSBucketName, System.currentTimeMillis());
                FileOutputStream fout = new FileOutputStream(mScreenshotFile);

				int quality = Settings.System.getInt(CR, "screenshot_quality", 100);
				boolean isSucceeded = true;

				if (mFormat == null || mFormat.equals("") || mFormat.equals("png")) {
                    isSucceeded = mBitmap.compress(CompressFormat.PNG, quality, fout);
                } else {
                    isSucceeded = mBitmap.compress(CompressFormat.JPEG, quality, fout);
                }
                fout.close();
			    if (!isSucceeded) Toast.makeText(this, "保存失败!", Toast.LENGTH_LONG).show();
				final boolean shareScreenshot = Settings.System.getInt(CR, "share_screenshot", 0) == 1;
                if (shareScreenshot) {
                    Intent intent = new Intent(Intent.ACTION_SEND);
                    intent.setType("image/" + mFormat);
                    intent.putExtra(Intent.EXTRA_STREAM, Uri.fromFile(new File(mScreenshotFile)));
                    startActivity(Intent.createChooser(intent, getString(R.string.share_message)));
                }
            } catch (android.content.ActivityNotFoundException ex) {
                Toast.makeText(ScreenshotActivity.this,
							   R.string.no_way_to_share,
							   Toast.LENGTH_SHORT).show();
            } catch (Exception ex) {
                finish();
                throw new Exception("Unable to save screenshot: " + ex);
            }
        } catch (Exception ex) { 
            Toast.makeText(ScreenshotActivity.this, getString(R.string.toast_error) + " " + ex.getMessage(), Toast.LENGTH_LONG).show();
        }

		mConnection.scanFile(mScreenshotFile, null);
        mConnection.disconnect();

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
        finish();
    }

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
