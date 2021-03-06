
package com.hlidskialf.android.bragi;


import android.app.PendingIntent;
import android.app.ProgressDialog;
import android.appwidget.AppWidgetManager;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.media.AudioManager;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build;
import android.provider.MediaStore;
import android.provider.Settings;
import android.util.Log;
import android.widget.RemoteViews;

import org.json.JSONObject;
import org.json.JSONArray;
import org.json.JSONException;


import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import my.android.util.Base64;

public class Bragi 
{
  public static final String PACKAGE="com.hlidskialf.android.bragi";


  public static final String PREFERENCES=PACKAGE+"_preferences";
  public static final String PREF_ACTIVE_PROFILE="active_profile";
  public static final long PREF_ACTIVE_PROFILE_DEFAULT=-1;
  public static final String PREF_SEEN_TUTORIAL="seen_tutorial";
  public static final boolean PREF_SEEN_TUTORIAL_DEFAULT=false;
  public static final String PREF_MAX_SLOT_SIZE="max_slot_size";
  public static final int PREF_MAX_SLOT_SIZE_DEFAULT=2096;
  public static final String PREF_CLEAR_SLOTS="clear_slots";
  public static final boolean PREF_CLEAR_SLOTS_DEFAULT=true;
  public static final String PREF_CIRCLE_CROP="circle_crop";
  public static final boolean PREF_CIRCLE_CROP_DEFAULT=false;

  public static final String EXTRA_PROFILE_ID=PACKAGE+".extra.PROFILE_ID";
  public static final String EXTRA_PROFILE_VALUES=PACKAGE+".extra.PROFILE_VALUES";
  public static final String EXTRA_SHOW_BRAGI_SLOTS=PACKAGE+".extra.SHOW_BRAGI_SLOTS";

  public static final String ACTION_CHOOSE_PROFILE = PACKAGE+".action.CHOOSE_PROFILE";

  public static Uri getUriForSlot(String slot_slug)
  {
    Uri ret = Uri.parse("file:///data/data/"+PACKAGE+"/files/slot_"+slot_slug);

    return ret;
  }




  public static class ActivateProfileTask extends AsyncTask<Void, Void, Boolean>
  {
    private Context mContext;
    private ContentResolver mResolver;
    private long mProfileId;
    private BragiDatabase mDb;
    private BragiDatabase.ProfileModel mProfile;
    private ProgressDialog mDialog;
    private int mMaxSlotSize;
    private boolean mClearSlots;
    private CompleteListener mCompleteListener;

    public interface CompleteListener {
      public void onComplete(boolean result);
    }

    public ActivateProfileTask(Context context, ContentResolver resolver, long profile_id)
    {
      mContext = context; 
      mProfileId = profile_id;
      mResolver = resolver;
    }

    public void setCompleteListener(CompleteListener listen)
    {
      mCompleteListener = listen;
    }

    protected void onPreExecute() {
      mDb = new BragiDatabase(mContext);
      mProfile = mDb.getProfile(mProfileId, false);

      mDialog = new ProgressDialog(mContext, R.style.Theme_Profile_ProgressDialog);
      mDialog.setMessage( mContext.getString(R.string.activate_dialog_message, mProfile.name) );
      mDialog.setIndeterminate(true);
      mDialog.setCancelable(false);
      mDialog.show();

      SharedPreferences prefs = mContext.getSharedPreferences(Bragi.PREFERENCES, 0);
      int slot_size = Bragi.PREF_MAX_SLOT_SIZE_DEFAULT;
      try {
        slot_size = prefs.getInt(Bragi.PREF_MAX_SLOT_SIZE, Bragi.PREF_MAX_SLOT_SIZE_DEFAULT);
        if (slot_size < 3) slot_size = 2096; // autofix old MB value
      } catch (java.lang.ClassCastException e) {
        // not sure why this happens...
      }
      mMaxSlotSize = 1024 * slot_size;
      mClearSlots = prefs.getBoolean(Bragi.PREF_CLEAR_SLOTS, Bragi.PREF_CLEAR_SLOTS_DEFAULT);
      prefs = null;

    }

    protected void onPostExecute(Boolean result) { 
      if (mCompleteListener != null) {
        mCompleteListener.onComplete(result);
      }
      mDialog.dismiss();
      mDb.close();
    }

    protected Boolean doInBackground(Void... nothing) 
    { 
      AudioManager audio = (AudioManager)mContext.getSystemService(Context.AUDIO_SERVICE);

      /* set default ringtone, notification, alarm */
      /* don't change default tones unless something is set */
      if (mProfile.default_ring != null && !mProfile.default_ring.equals(""))
        Settings.System.putString(mResolver, Settings.System.RINGTONE, mProfile.default_ring);
      if (mProfile.default_notify != null && !mProfile.default_notify.equals(""))
        Settings.System.putString(mResolver, Settings.System.NOTIFICATION_SOUND, mProfile.default_notify);
      if (Build.VERSION.SDK_INT >= 5) {
        if (mProfile.default_alarm != null && !mProfile.default_alarm.equals(""))
          Settings.System.putString(mResolver, Settings.System.ALARM_ALERT, mProfile.default_alarm);
      }

      /* set silent mode */
      if (mProfile.silent_mode == 0) audio.setRingerMode(AudioManager.RINGER_MODE_NORMAL);
      else if (mProfile.silent_mode == 1) audio.setRingerMode(AudioManager.RINGER_MODE_SILENT);
      else if (mProfile.silent_mode == 2) audio.setRingerMode(AudioManager.RINGER_MODE_VIBRATE);

      /* set vibration */
      if (mProfile.vibrate_ring == 0) audio.setVibrateSetting(AudioManager.VIBRATE_TYPE_RINGER, AudioManager.VIBRATE_SETTING_OFF);
      else if (mProfile.vibrate_ring == 1) audio.setVibrateSetting(AudioManager.VIBRATE_TYPE_RINGER, AudioManager.VIBRATE_SETTING_ON);
      else if (mProfile.vibrate_ring == 2) audio.setVibrateSetting(AudioManager.VIBRATE_TYPE_RINGER, AudioManager.VIBRATE_SETTING_ONLY_SILENT);
      if (mProfile.vibrate_notify == 0) audio.setVibrateSetting(AudioManager.VIBRATE_TYPE_NOTIFICATION, AudioManager.VIBRATE_SETTING_OFF);
      else if (mProfile.vibrate_notify == 1) audio.setVibrateSetting(AudioManager.VIBRATE_TYPE_NOTIFICATION, AudioManager.VIBRATE_SETTING_ON);
      else if (mProfile.vibrate_notify == 2) audio.setVibrateSetting(AudioManager.VIBRATE_TYPE_NOTIFICATION, AudioManager.VIBRATE_SETTING_ONLY_SILENT);

      /* set volumes */
      audio.setStreamVolume(AudioManager.STREAM_RING, mProfile.volume_ringer, 0);
      audio.setStreamVolume(AudioManager.STREAM_MUSIC, mProfile.volume_music, 0);
      audio.setStreamVolume(AudioManager.STREAM_VOICE_CALL, mProfile.volume_call, 0);
      audio.setStreamVolume(AudioManager.STREAM_SYSTEM, mProfile.volume_system, 0);
      audio.setStreamVolume(AudioManager.STREAM_ALARM, mProfile.volume_alarm, 0);
      audio.setStreamVolume(AudioManager.STREAM_NOTIFICATION, mProfile.volume_notify, 0);

      /* copy slots */

      HashMap<Long,Uri> profile_slots = mDb.getProfileSlotHash(mProfileId);
      Cursor slots = mDb.getAllSlots();
      int idx_slot_id = slots.getColumnIndex(BragiDatabase.SlotColumns._ID);
      int idx_slot_slug = slots.getColumnIndex(BragiDatabase.SlotColumns.SLUG);
      while (slots.moveToNext()) {
        long slot_id = slots.getLong(idx_slot_id);
        String slot_slug = slots.getString(idx_slot_slug);
        Uri uri = null;

        if (profile_slots.containsKey(slot_id)) {
          uri = profile_slots.get(slot_id);
        }

        if (uri == null)
        {
          if (mClearSlots) {
            /* erase old slot value */
            Log.v("Bragi/activateProfile", "erasing " + String.valueOf(slot_id));
            try {
              FileOutputStream fos = mContext.openFileOutput("slot_"+slot_slug, Context.MODE_WORLD_READABLE); 
              fos.close();
            } catch(java.io.IOException e) { 
            }
          }

          continue;
        }

        Cursor c = mResolver.query(uri, new String[] {
          MediaStore.Audio.Media._ID,
          MediaStore.Audio.Media.SIZE,
          MediaStore.Audio.Media.MIME_TYPE,
          MediaStore.Audio.Media.DATA,
          MediaStore.Audio.Media.TITLE,
        }, null, null, null);
        if (! c.moveToFirst()) {
          Log.v("Bragi/activateProfile", "moveToFirst failed");
          continue;
        }
        final int idx_data = c.getColumnIndex(MediaStore.Audio.Media.DATA);
        final int idx_size = c.getColumnIndex(MediaStore.Audio.Media.SIZE);
        final int idx_title = c.getColumnIndex(MediaStore.Audio.Media.TITLE);
        String data = c.getString(idx_data);
        int size = c.getInt(idx_size);
        String title = c.getString(idx_title);

        Log.v("Bragi/activateProfile", "Found data:" + data +" = "+title);

        try {
          FileInputStream fis = new FileInputStream(data);
          FileOutputStream fos = mContext.openFileOutput("slot_"+slot_slug, Context.MODE_WORLD_READABLE); 

          int bufsize = Math.min(size, mMaxSlotSize);
          byte[] buffer = new byte[bufsize];
          int red = fis.read(buffer, 0, bufsize);
          fis.close();
          fos.write(buffer, 0, red);
          fos.close();
        } catch(java.io.FileNotFoundException e) {
          Log.e("Bragi/activateProfile", "file not found: "+e.toString());
        } catch(java.io.IOException e) {
          Log.e("Bragi/activateProfile", "io exeption: "+e.toString());
        }

      }
      slots.close();

      Bragi.updateWidget(mContext);

    
      return true;
    }
  }

  public static void activateProfile(Context context, ContentResolver resolver, long profile_id)
  {
    activateProfile(context, resolver, profile_id, null);
  }
  public static void activateProfile(Context context, ContentResolver resolver, long profile_id, ActivateProfileTask.CompleteListener listen)
  {
    ActivateProfileTask task = new ActivateProfileTask(context,resolver,profile_id);
    task.setCompleteListener(listen);
    task.execute();

    SharedPreferences prefs = context.getSharedPreferences(Bragi.PREFERENCES, 0);
    prefs.edit().putLong(Bragi.PREF_ACTIVE_PROFILE, profile_id).commit();

  }

  public static void updateWidget(Context context) 
  {
    AppWidgetManager awm = AppWidgetManager.getInstance(context);
    int[] ids = awm.getAppWidgetIds(new ComponentName(context, BragiWidgetProvider.class));
    Bragi.updateWidget(context, awm, ids);
  }
  public static void updateWidget(Context context, AppWidgetManager appWidgetManager, int[] appWidgetIds) 
  {
    RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.bragi_appwidget);
    BragiDatabase.ProfileModel profile = null;
    SharedPreferences prefs = context.getSharedPreferences(Bragi.PREFERENCES, 0);
    long profile_id = prefs.getLong(Bragi.PREF_ACTIVE_PROFILE, -1);

    if (profile_id != -1) {
      BragiDatabase mDb = new BragiDatabase(context);
      profile = mDb.getProfile(profile_id, false);
      mDb = null;
    }

    Intent intent = new Intent(Bragi.ACTION_CHOOSE_PROFILE);
    PendingIntent pendingIntent = PendingIntent.getActivity(context, 0, intent, 0);
    views.setOnClickPendingIntent(android.R.id.icon1, pendingIntent);
    views.setOnClickPendingIntent(android.R.id.text1, pendingIntent);

    if (profile != null && profile.icon != null) {
      views.setImageViewBitmap(android.R.id.icon1, profile.icon);
    }
    else {
      views.setImageViewResource(android.R.id.icon1, R.drawable.ic_launcher_ringer);
    }

    views.setTextViewText(android.R.id.text1, profile == null ? context.getString(R.string.bragi_label) : profile.name);

    appWidgetManager.updateAppWidget(appWidgetIds, views);
  }


  public static Bitmap scaleBitmap(Bitmap src, int new_width, int new_height) {
    int width = src.getWidth();
    int height = src.getHeight();
    float scale_width = ((float) new_width) / width;
    float scale_height = ((float) new_height) / height;
    Matrix matrix = new Matrix();
    matrix.postScale(scale_width, scale_height);
    return Bitmap.createBitmap(src, 0, 0, width, height, matrix, true);
  }

  public static boolean unserializeFromJSON(Context context, String json_data)
  {
    boolean ret = false;
    BragiDatabase db = new BragiDatabase(context);
    int i;
    int N;
    HashMap<String,Long> slot_hash = new HashMap<String,Long>();

    try {
      JSONObject top = new JSONObject(json_data);

      // flush old data
      db.deleteAllData();

      // restore slots
      JSONArray slots = top.getJSONArray("slots");
      N = slots.length();
      for (i=0; i < N; i++) {
        JSONObject obj = slots.getJSONObject(i);
        String name = obj.getString("name");
        String slug = obj.getString("slug");
        long slot_id = db.addSlot(name, slug);
        slot_hash.put(slug, slot_id);
      }

     
      JSONArray profiles = top.getJSONArray("profiles");
      N = profiles.length();
      for (i=0; i < N; i++) {
        JSONObject obj = profiles.getJSONObject(i);
        String name = obj.getString(BragiDatabase.ProfileColumns.NAME);

        BragiDatabase.ProfileModel profile = new BragiDatabase.ProfileModel();
        profile.id = db.addProfile(name);
        profile.name = name;
        profile.default_ring = obj.optString(BragiDatabase.ProfileColumns.DEFAULT_RING);
        profile.default_notify = obj.optString(BragiDatabase.ProfileColumns.DEFAULT_NOTIFY);
        profile.default_alarm = obj.optString(BragiDatabase.ProfileColumns.DEFAULT_ALARM);
        profile.silent_mode = obj.optInt(BragiDatabase.ProfileColumns.SILENT_MODE);
        profile.vibrate_ring = obj.optInt(BragiDatabase.ProfileColumns.VIBRATE_RING);
        profile.vibrate_notify = obj.optInt(BragiDatabase.ProfileColumns.VIBRATE_NOTIFY);
        profile.volume_ringer = obj.optInt(BragiDatabase.ProfileColumns.VOLUME_RINGER);
        profile.volume_music = obj.optInt(BragiDatabase.ProfileColumns.VOLUME_MUSIC);
        profile.volume_call = obj.optInt(BragiDatabase.ProfileColumns.VOLUME_CALL);
        profile.volume_system = obj.optInt(BragiDatabase.ProfileColumns.VOLUME_SYSTEM);
        profile.volume_alarm = obj.optInt(BragiDatabase.ProfileColumns.VOLUME_ALARM);
        profile.volume_notify = obj.optInt(BragiDatabase.ProfileColumns.VOLUME_NOTIFY);
        ContentValues values = profile.contentValues();

        String icon_b64 = obj.optString(BragiDatabase.ProfileColumns.ICON);
        if (icon_b64 != null && icon_b64.length() > 0) {
          byte[] icon_data = Base64.decode(icon_b64, Base64.DEFAULT);
          values.put(BragiDatabase.ProfileColumns.ICON, icon_data);
        }
        
        db.updateProfile(profile.id, values);

        JSONObject profile_slots = obj.getJSONObject("slots");
        Iterator it = profile_slots.keys();
        while (it.hasNext()) {
          String slot = (String)it.next();
          if (!slot_hash.containsKey(slot)) 
            continue;
          long slot_id = slot_hash.get(slot);
          String uri = profile_slots.getString(slot);

          db.updateProfileSlot(profile.id, slot_id, Uri.parse(uri));
        }
      }


      JSONObject preferences = top.getJSONObject("preferences");
      SharedPreferences prefs = context.getSharedPreferences(Bragi.PREFERENCES, 0);
      SharedPreferences.Editor edit = prefs.edit();

      boolean seen_tutorial = Boolean.valueOf( preferences.getString(Bragi.PREF_SEEN_TUTORIAL) );
      edit.putBoolean(Bragi.PREF_SEEN_TUTORIAL, seen_tutorial);

      int max_slot_size = Integer.valueOf( preferences.getString(Bragi.PREF_MAX_SLOT_SIZE) );
      edit.putInt(Bragi.PREF_MAX_SLOT_SIZE, max_slot_size);

      boolean clear_slots = Boolean.valueOf( preferences.getString(Bragi.PREF_CLEAR_SLOTS) );
      edit.putBoolean(Bragi.PREF_CLEAR_SLOTS, clear_slots);

      boolean circle_crop = Boolean.valueOf( preferences.getString(Bragi.PREF_CIRCLE_CROP) );
      edit.putBoolean(Bragi.PREF_CIRCLE_CROP, circle_crop);


      ret = true;
    } catch (JSONException e) {
      Log.v("BragiRestore", e.toString());
      ret = false;
    }
    db.close();
    return ret;
  }

  public static String serializeToJSON(Context context)
  {
  /*
  ({
    'db_version': '901',
    'slots': [{'name':'Email','slug':'email'},{'name':'IM','slug':'im'}],
    'profiles': [
        {
            'name': 'Home',
            'icon': '***BASE64 OF BITMAP DATA***',
            'silent_mode': '',
            'volume_ringer': '',
            'default_ring': 'URI',
            'slots': {
                'email': 'URI',
                'im': 'URI'
            }
        }
    ]
    'preferences': {
        'active_profile': '-1',
        'seen_tutorial': true,
        'circle_crop': false
    }
  })
  */
    BragiDatabase db = new BragiDatabase(context);
    String ret = "";
    try {
      HashMap<Long,String> slot_hash = new HashMap<Long,String>();

      JSONObject top = new JSONObject();
      top.put("db_version", BragiDatabase.DATABASE_VERSION);

      Cursor slot_cursor = db.getAllSlots();
      final int cidx_name = slot_cursor.getColumnIndex(BragiDatabase.SlotColumns.NAME);
      final int cidx_slug = slot_cursor.getColumnIndex(BragiDatabase.SlotColumns.SLUG);
      final int cidx_id = slot_cursor.getColumnIndex(BragiDatabase.SlotColumns.SLUG);
      JSONArray slots = new JSONArray();
      while (slot_cursor.moveToNext()) {
        JSONObject obj = new JSONObject();
        String slug = slot_cursor.getString(cidx_slug); 
        obj.put(BragiDatabase.SlotColumns.NAME, slot_cursor.getString(cidx_name));
        obj.put(BragiDatabase.SlotColumns.SLUG, slug);
        slots.put(obj);

        slot_hash.put(slot_cursor.getLong(cidx_id),slug);
      }
      slot_cursor.close();
      top.put("slots", slots);

      Cursor profile_cursor = db.getAllProfiles();
      JSONArray profiles = new JSONArray();
      while (profile_cursor.moveToNext()) {
        JSONObject obj = new JSONObject();
        BragiDatabase.ProfileModel profile = new BragiDatabase.ProfileModel(profile_cursor);
        obj.put(BragiDatabase.ProfileColumns.NAME, profile.name);
        obj.put(BragiDatabase.ProfileColumns.DEFAULT_RING, profile.default_ring);
        obj.put(BragiDatabase.ProfileColumns.DEFAULT_NOTIFY, profile.default_notify);
        obj.put(BragiDatabase.ProfileColumns.DEFAULT_ALARM, profile.default_alarm);
        obj.put(BragiDatabase.ProfileColumns.SILENT_MODE, profile.silent_mode);
        obj.put(BragiDatabase.ProfileColumns.VIBRATE_RING, profile.vibrate_ring);
        obj.put(BragiDatabase.ProfileColumns.VIBRATE_NOTIFY, profile.vibrate_notify);
        obj.put(BragiDatabase.ProfileColumns.VOLUME_RINGER, profile.volume_ringer);
        obj.put(BragiDatabase.ProfileColumns.VOLUME_MUSIC, profile.volume_music);
        obj.put(BragiDatabase.ProfileColumns.VOLUME_CALL, profile.volume_call);
        obj.put(BragiDatabase.ProfileColumns.VOLUME_SYSTEM, profile.volume_system);
        obj.put(BragiDatabase.ProfileColumns.VOLUME_ALARM, profile.volume_alarm);
        obj.put(BragiDatabase.ProfileColumns.VOLUME_NOTIFY, profile.volume_notify);
        if (profile.icon_blob != null) {
          String base64 = Base64.encodeToString(profile.icon_blob, Base64.DEFAULT);
          obj.put(BragiDatabase.ProfileColumns.ICON, base64);
        }

        Cursor profile_slot_cursor = db.getProfileSlots(profile.id);
        JSONObject profile_slots = new JSONObject();
        final int cidx_uri = profile_slot_cursor.getColumnIndex(BragiDatabase.ProfileSlotColumns.URI);
        final int cidx_slot_id = profile_slot_cursor.getColumnIndex(BragiDatabase.ProfileSlotColumns.SLOT_ID);
        while (profile_slot_cursor.moveToNext()) {
          String uri = profile_slot_cursor.getString(cidx_uri);
          long slot_id = profile_slot_cursor.getLong(cidx_slot_id);
          if (slot_hash.containsKey(slot_id))
            profile_slots.put(slot_hash.get(slot_id), uri);
        }
        profile_slot_cursor.close();
        obj.put("slots", profile_slots);

        profiles.put(obj);
      }
      profile_cursor.close();
      top.put("profiles", profiles);

      SharedPreferences prefs = context.getSharedPreferences(Bragi.PREFERENCES, 0);
      Map pref_map = prefs.getAll();
      JSONObject pref_obj = new JSONObject();
      Iterator it = pref_map.entrySet().iterator();
      while (it.hasNext()) {
        Map.Entry entry = (Map.Entry)it.next();
        pref_obj.put((String)entry.getKey(), entry.getValue().toString());
      }
      top.put("preferences", pref_obj);

      ret = top.toString();
    } catch (JSONException e) {
    }
    db.close();
    return ret;
  }

}
