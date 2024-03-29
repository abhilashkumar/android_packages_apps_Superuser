package com.noshufou.android.su;

import java.io.IOException;
import java.io.OutputStream;

import java.util.Date;
import java.text.SimpleDateFormat;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.AlertDialog.Builder;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.SharedPreferences.Editor;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.preference.PreferenceManager;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.CheckBox;

public class SuRequest extends Activity {
    private static final String TAG = "SuRequest";
    private static final String ALLOW = "ALLOW";
    private static final String DENY = "DENY";

    private DBHelper db;
    private DBHelper.AppStatus app_status;

    private String socketPath;
    private int callerUid = 0;
    private int desiredUid = 0;
    private String desiredCmd = "";

    SharedPreferences prefs;

    /** Called when the activity is first created. */
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        prefs = PreferenceManager.getDefaultSharedPreferences(this);

    	if (getCallingPackage() != null) {
            Log.e(TAG, "SuRequest must be started from su");
            finish();
            return;
        }

        db = new DBHelper(this);

        Intent in = getIntent();
        socketPath = in.getStringExtra("socket");
        callerUid = in.getIntExtra("caller_uid", 0);
        desiredUid = in.getIntExtra("desired_uid", 0);
        desiredCmd = in.getStringExtra("desired_cmd");

        app_status = db.checkApp(callerUid, desiredUid, desiredCmd);

        switch (app_status.permission) {
            case DBHelper.ALLOW: sendResult(ALLOW, false); break;
            case DBHelper.DENY:  sendResult(DENY,  false); break;
            case DBHelper.ASK:   prompt(); break;
            default: Log.e(TAG, "Bad response from database"); break;
        }
    }
    @Override
    public void onDestroy() {
        super.onDestroy();
        db.close();
    }

    private void prompt() {
        LayoutInflater inflater = LayoutInflater.from(this);
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        AlertDialog alert;
        
        View layout = inflater.inflate(R.layout.request, (ViewGroup) findViewById(R.id.requestLayout));
        TextView appNameView = (TextView) layout.findViewById(R.id.appName);
        TextView packageNameView = (TextView) layout.findViewById(R.id.packageName);
        TextView requestDetailView = (TextView) layout.findViewById(R.id.requestDetail);
        TextView commandView = (TextView) layout.findViewById(R.id.command);
        final CheckBox checkRemember = (CheckBox) layout.findViewById(R.id.checkRemember);

        appNameView.setText(Su.getAppName(this, callerUid, true));
        packageNameView.setText(Su.getAppPackage(this, callerUid));
        requestDetailView.setText(Su.getUidName(this, desiredUid, true));
        commandView.setText(desiredCmd);
        checkRemember.setChecked(prefs.getBoolean("last_remember_value", true));
        DialogInterface.OnClickListener listener = new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface dialog, int id) {
                int result;
                boolean remember = checkRemember.isChecked();
                if (id == DialogInterface.BUTTON_POSITIVE) {
                    sendResult(ALLOW, remember);
                    result = DBHelper.ALLOW;
                } else if (id == DialogInterface.BUTTON_NEGATIVE) {
                    sendResult(DENY, remember);
                    result = DBHelper.DENY;
                }
                SharedPreferences.Editor editor = prefs.edit();
                editor.putBoolean("last_remember_value", checkRemember.isChecked());
                editor.commit();
            }
        };

        builder.setTitle(R.string.app_name_request)
               .setIcon(R.drawable.icon)
               .setView(layout)
               .setPositiveButton(R.string.allow, listener)
               .setNegativeButton(R.string.deny, listener)
               .setCancelable(false);
        alert = builder.create();
        alert.show();
    }

    private void sendNotification() {
        if (prefs.contains("preference_notification")) {
            Editor editor = prefs.edit();
            String newPref = "";
            if (prefs.getBoolean("preference_notification", false)) {
                Log.d(TAG, "Old notification setting = true. New notification setting = notification");
                newPref = "notification";
            } else {
                Log.d(TAG, "Old notification setting = false. new notification setting = none");
                newPref = "none";
            }
            editor.putString("preference_notification_type", newPref);
            editor.remove("preference_notification");
            editor.commit();
        }

        String notification_type = prefs.getString("preference_notification_type", "toast");
        if (notification_type.equals("none"))
            return;

        String notification_message = getString(R.string.notification_text,
            Su.getAppName(this, callerUid, false));

        if (notification_type.equals("notification")) {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);

            Context context = getApplicationContext();
            Intent notificationIntent = new Intent(this, Su.class);
            PendingIntent contentIntent = PendingIntent.getActivity(this, 0, notificationIntent, 0);

            String title = getString(R.string.app_name_perms);

            Notification notification = new Notification(R.drawable.stat_su, notification_message, System.currentTimeMillis());
            notification.setLatestEventInfo(context, title, notification_message, contentIntent);
            notification.flags = Notification.FLAG_AUTO_CANCEL|Notification.FLAG_ONLY_ALERT_ONCE;

            nm.notify(TAG, callerUid, notification);
        } else if (notification_type.equals("toast")) {
            Toast.makeText(this, notification_message, Toast.LENGTH_SHORT).show();
        }
    }

    private void sendResult(String resultCode, boolean remember) {
        LocalSocket socket;
        if (remember) {
            db.insert(callerUid, desiredUid, desiredCmd, (resultCode.equals(ALLOW)) ? 1 : 0);
        }
        try {
            socket = new LocalSocket();
            socket.connect(new LocalSocketAddress(socketPath,
                LocalSocketAddress.Namespace.FILESYSTEM));

            Log.d(TAG, "Sending result: " + resultCode);
            if (socket != null) {
                OutputStream os = socket.getOutputStream();
                byte[] bytes = resultCode.getBytes("UTF-8");
                os.write(bytes);
                os.flush();
                os.close();
                socket.close();
            }
        } catch (IOException e) {
            Log.e(TAG, e.getMessage(), e);
        }

        if (resultCode.equals(ALLOW) && app_status.dateAccess + 60*1000 < System.currentTimeMillis()) {
            sendNotification();
        }
        
        finish();
    }
}
