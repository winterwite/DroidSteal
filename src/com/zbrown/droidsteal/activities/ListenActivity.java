/*
 * ListenActivity.java is the starting Activity, listening for cookies Copyright
 * (C) 2013-2014 Zach Brown <Zbob75x@gmail.com>
 * 
 * This software was supported by the University of Trier
 * 
 * This program is free software; you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation; either version 3 of the License, or (at your option) any later
 * version.
 * 
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU General Public License for more
 * details.
 * 
 * You should have received a copy of the GNU General Public License along with
 * this program; if not, write to the Free Software Foundation, Inc., 51
 * Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA.
 */

package com.zbrown.droidsteal.activities;

import android.annotation.SuppressLint;
import android.app.*;
import android.app.ActivityManager.RunningServiceInfo;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.text.format.Formatter;
import android.util.Log;
import android.view.*;
import android.view.ContextMenu.ContextMenuInfo;
import android.view.View.OnClickListener;
import android.view.View.OnCreateContextMenuListener;
import android.widget.*;
import android.widget.AdapterView.OnItemClickListener;
import android.widget.AdapterView.OnItemLongClickListener;
import android.widget.CompoundButton.OnCheckedChangeListener;
import com.zbrown.droidsteal.R;
import com.zbrown.droidsteal.auth.Auth;
import com.zbrown.droidsteal.auth.AuthHelper;
import com.zbrown.droidsteal.helper.*;
import com.zbrown.droidsteal.objects.SessionListView;
import com.zbrown.droidsteal.objects.WifiChangeChecker;
import com.zbrown.droidsteal.services.ArpspoofService;
import com.zbrown.droidsteal.services.DroidSheepService;

import java.io.Serializable;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.*;

public class ListenActivity extends Activity implements OnClickListener, OnItemClickListener, OnItemLongClickListener,
        OnCreateContextMenuListener, OnCheckedChangeListener, Constants {

    private static final int MENU_ABOUT_ID = 5;
    private static ArrayList<Auth> authListUnsynchronized = new ArrayList<Auth>();
    public static List<Auth> authList = Collections.synchronizedList(authListUnsynchronized);

    private SessionListView sessionListView;
    private TextView tstatus;
    private TextView tnetworkName;
    private ProgressBar pbrunning;
    private CheckBox cbgeneric;

    private int sessionListViewSelected;

    private boolean networkEncryptionWPA = false;
    private String gatewayIP = "";

    //	public static boolean unrooted = false;

    private int lastNotification = 0;
    private NotificationManager mNotificationManager = null;

    public static StringBuffer debugBuffer = null;
    public static boolean debugging = false;

    public static boolean generic = true;
    private Handler handler = new Handler() {
        @Override
        public synchronized void handleMessage(Message msg) {
            String type = msg.getData().getString(BUNDLE_KEY_TYPE);
            if (type != null && type.equals(BUNDLE_TYPE_WIFICHANGE)) {
                if (!isListening())
                    return;
                Toast t = Toast.makeText(getApplicationContext(), getString(R.string.toast_wifi_lost), Toast.LENGTH_SHORT);
                t.show();
                stopListening();
                stopSpoofing();
                cleanup();
                updateNetworkSettings();
            } else if (type != null && type.equals(BUNDLE_TYPE_NEWAUTH)) {
                Serializable serializable = msg.getData().getSerializable(BUNDLE_KEY_AUTH);
                if (serializable == null || !(serializable instanceof Auth)) {
                    Log.e(APPLICATION_TAG, "ERROR with serializable. Null or not an instance!");
                    return;
                }
                Auth a = (Auth) serializable;
                if (!authList.contains(a)) {
                    if (!a.isGeneric()) {
                        ListenActivity.authList.add(0, a);
                    } else {
                        ListenActivity.authList.add(a);
                    }
                } else {
                    int pos = authList.indexOf(a);
                    if (!authList.get(pos).isSaved()) {
                        authList.remove(pos);
                    }
                    authList.add(pos, a);
                }
                ListenActivity.this.refresh();
                ListenActivity.this.notifyUser(false, a);
            } else if (type != null && type.equals(BUNDLE_TYPE_LOADAUTH)) {
                Serializable serializable = msg.getData().getSerializable(BUNDLE_KEY_AUTH);
                if (serializable == null || !(serializable instanceof Auth)) {
                    Log.e(APPLICATION_TAG, "ERROR with serializable. Null or not an instance!");
                    return;
                }
                Auth a = (Auth) serializable;
                if (!authList.contains(a)) {
                    ListenActivity.authList.add(0, a);
                }
                ListenActivity.this.refresh();
                ListenActivity.this.notifyUser(false, a);
            } else if (type != null && type.equals(BUNDLE_TYPE_START)) {
                Button button = (Button) findViewById(R.id.bstartstop);
                button.setEnabled(false);
                if (!isListening() && isSpoofing()) {
                    stopSpoofing();
                }

                if (!isListening()) {
                    CheckBox cbSpoof = (CheckBox) findViewById(R.id.cbarpspoof);
                    if (cbSpoof.isChecked()) {
                        startSpoofing();
                    } else {
                        stopSpoofing();
                    }
                    startListening();
                    notifyUser(true, null);
                    refreshHandler.sleep();
                }
                button.setEnabled(true);
                handler.removeMessages(0);
            } else if (type != null && type.equals(BUNDLE_TYPE_STOP)) {
                stopListening();
                stopSpoofing();
                refreshHandler.stop();
                refresh();
                if (debugging) {
                    MailHelper.sendStringByMail(ListenActivity.this, debugBuffer.toString());
                    debugging = false;
                    debugBuffer = null;
                }
            }
        }
    };

    RefreshHandler refreshHandler = new RefreshHandler();

    class RefreshHandler extends Handler {
        @Override
        public void handleMessage(Message msg) {
            ListenActivity.this.refreshStatus();
            sleep();
        }

        public void sleep() {
            this.removeMessages(0);
            sendMessageDelayed(obtainMessage(0), 1000);
        }

        public void stop() {
            this.removeMessages(0);
        }
    }

    // ############################################################################
    //                           START LIFECYCLE METHODS
    // ############################################################################

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);


        if (DEBUG)
            Log.d(APPLICATION_TAG, "ONCREATE");

        SetupHelper.checkPrerequisites(this.getApplicationContext());

        AuthHelper.init(this.getApplicationContext(), handler);
        WifiChangeChecker wi = new WifiChangeChecker(handler);
        this.getApplicationContext().registerReceiver(wi, new IntentFilter(WifiManager.WIFI_STATE_CHANGED_ACTION));
        if (!SetupHelper.checkSu()) {
            DialogHelper.showUnrooted(this);
        }
        if (!SetupHelper.checkCommands()) {
            DialogHelper.installBusyBox(this);
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (DEBUG)
            Log.d(APPLICATION_TAG, "ONSTART");
        setContentView(R.layout.listen);

        Button button = (Button) findViewById(R.id.bstartstop);

        button.setOnClickListener(this);
        if (isListening()) {
            button.setText(getString(R.string.button_stop));
        } else {
            button.setText(getString(R.string.button_start));
        }
        tstatus = (TextView) findViewById(R.id.status);
        tnetworkName = (TextView) findViewById(R.id.networkname);
        pbrunning = (ProgressBar) findViewById(R.id.progressBar1);
        cbgeneric = (CheckBox) findViewById(R.id.cbgeneric);
        cbgeneric.setOnCheckedChangeListener(this);

        this.sessionListView = ((SessionListView) findViewById(R.id.sessionlist));
        this.sessionListView.setOnItemClickListener(this);
        this.sessionListView.setOnCreateContextMenuListener(this);

        mNotificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        ListenActivity.generic = DBHelper.getGeneric(this);
        cbgeneric.setChecked(ListenActivity.generic);
        showUpdate();
        DialogHelper.showDisclaimer(this);
    }

    private void showUpdate() {
        long last = DBHelper.getLastUpdateMessage(this);
        long now = System.currentTimeMillis();
        long dif = now - last;
        try {
            if (dif > (1 * 24 * 60 * 60 * 1000)) { // show once per day. (I think)
                // Update Checker
                String VERSION_URL = "https://raw.github.com/Zbob750/DroidSteal/master/update_version.php";
                String REMOTE_APK_URL = " "; // I need to figure out how I want to do this still. :(
                int ALERT_ICON = R.drawable.droidsteal_square;
                UpdateChecker uc = new UpdateChecker(this, VERSION_URL,
                        REMOTE_APK_URL, ALERT_ICON);
                uc.startUpdateChecker();
                DBHelper.setLastUpdateCheck(this, System.currentTimeMillis());
            }
        } catch (Exception e) {
            Log.e(APPLICATION_TAG, "Error accessing updater DB", e);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        SystemHelper.readAuthFiles(this, handler);
        refresh();
    }

    @Override
    protected void onDestroy() {
        authList.clear();
        mNotificationManager.cancelAll();
        stopListening();
        stopSpoofing();
        finish();
        try {
            cleanup();
        } catch (Exception e) {
            Log.e(APPLICATION_TAG, "Error while onDestroy", e);
        }
        super.onDestroy();
    }

    // ############################################################################
    //                           END LIFECYCLE METHODS
    // ############################################################################

    // ############################################################################
    //                           START LISTENER METHODS
    // ############################################################################

    public void onItemClick(AdapterView<?> parent, View view, int position,
                            long id) {
        if (view == null) {
            return;
        }

        if (sessionListView == null) {
            sessionListView = (SessionListView) findViewById(R.id.sessionlist);
        }

        sessionListViewSelected = position;
        // SpoofURL =
        // ((TextView)view.findViewById(R.id.listtext1)).getText().toString();
        // auth = ListenActivity.authList.get(position);
        try {
            sessionListView.showContextMenuForChild(view);
        } catch (Exception e) {
            // VERY BAD, but actually cant find out how the NPE happens...
            // :-(
            Log.d(APPLICATION_TAG,
                    "error on click: " + e.getLocalizedMessage());
        }
    }

    public boolean onItemLongClick(AdapterView<?> parent, View view, int position, long id) {
        onItemClick(parent, view, position, id);
        return false;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case MENU_WIFILIST_ID:
                startActivity(new Intent(WifiManager.ACTION_PICK_WIFI_NETWORK));
                break;
            case MENU_CLEAR_SESSIONLIST_ID:
                authList.clear();
                refresh();
                mNotificationManager.cancelAll();
                break;
            case MENU_DEBUG_ID:
                askDebug();
                break;
            case MENU_CLEAR_BLACKLIST_ID:
                DialogHelper.clearBlacklist(this);
                break;
            case MENU_ABOUT_ID:
                DialogHelper.about(this);
                break;
        }
        return false;
    }

    public boolean onContextItemSelected(MenuItem item) {
        Auth a;
        switch (item.getItemId()) {
            case ID_MOBILE:
                click(sessionListViewSelected, true);
                break;
            case ID_NORMAL:
                click(sessionListViewSelected, false);
                break;
            case ID_REMOVEFROMLIST:
                authList.remove(sessionListViewSelected);
                refresh();
                break;
            case ID_BLACKLIST:
                a = authList.get(sessionListViewSelected);
                AuthHelper.addToBlackList(this, a.getName());
                authList.remove(a);
                refresh();
                break;
            case ID_SAVE:
                a = authList.get(sessionListViewSelected);
                SystemHelper.saveAuthToFile(this, a);
                refresh();
                break;
            case ID_DELETE:
                a = authList.get(sessionListViewSelected);
                SystemHelper.deleteAuthFile(this, a);
                refresh();
                break;
            case ID_EXPORT:
                a = authList.get(sessionListViewSelected);
                MailHelper.sendAuthByMail(this, a);
                break;
        }
        return true;
    }

    public void onClick(View v) {
        if (v.getId() == R.id.bstartstop) {
            Message m = handler.obtainMessage();
            Bundle b = new Bundle();
            if (!isListening()) {
                b.putString(BUNDLE_KEY_TYPE, BUNDLE_TYPE_START);
            } else {
                b.putString(BUNDLE_KEY_TYPE, BUNDLE_TYPE_STOP);
            }
            m.setData(b);
            handler.sendMessage(m);
        }
    }

    @Override
    public void onCreateContextMenu(ContextMenu menu, View v, ContextMenuInfo menuInfo) {
        Auth actElem;
        if (sessionListViewSelected >= authList.size())
            return;
        actElem = authList.get(sessionListViewSelected);
        menu.setHeaderTitle(getString(R.string.menu_choose_page_title));
        menu.add(Menu.NONE, ID_NORMAL, Menu.NONE, getString(R.string.menu_open_normal));
        if (actElem.getMobileUrl() != null) {
            menu.add(Menu.NONE, ID_MOBILE, Menu.NONE, getString(R.string.menu_open_mobile));
        }
        menu.add(Menu.NONE, ID_REMOVEFROMLIST, Menu.NONE, getString(R.string.menu_remove_from_list));
        menu.add(Menu.NONE, ID_BLACKLIST, Menu.NONE, getString(R.string.menu_black_list));
        menu.add(Menu.NONE, ID_EXPORT, Menu.NONE, getString(R.string.menu_export));

        if (actElem.isSaved()) {
            menu.add(Menu.NONE, ID_DELETE, Menu.NONE, getString(R.string.menu_delete));
        } else {
            menu.add(Menu.NONE, ID_SAVE, Menu.NONE, getString(R.string.menu_save));
        }

    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        super.onCreateOptionsMenu(menu);
        menu.clear();

        MenuItem menu0 = menu.add(0, MENU_CLEAR_SESSIONLIST_ID, 0, getString(R.string.menu_clear_sessionlist));
        menu0.setIcon(R.drawable.ic_action_remove);
        menu0.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);

        MenuItem menu1 = menu.add(0, MENU_WIFILIST_ID, 0, getString(R.string.menu_wifilist));
        menu1.setIcon(R.drawable.ic_action_network_wifi);
        menu1.setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM);

        menu.add(0, MENU_CLEAR_BLACKLIST_ID, 0, getString(R.string.menu_blacklist_clear));
        menu.add(0, MENU_DEBUG_ID, 0, getString(R.string.menu_debug));
        menu.add(0, MENU_ABOUT_ID, 0, getString(R.string.menu_about));
        return true;
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            AlertDialog.Builder builder = new AlertDialog.Builder(this);
            builder.setMessage(R.string.popup_exit).setCancelable(false)
                    .setPositiveButton(R.string.button_ok, new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface dialog, int id) {
                            ListenActivity.this.finish();
                        }
                    }).setNegativeButton(R.string.button_abort, new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface dialog, int id) {
                    dialog.cancel();
                }
            });
            AlertDialog alert = builder.create();
            alert.show();
        }
        return super.onKeyDown(keyCode, event);
    }

    public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
        if (buttonView.equals(cbgeneric)) {
            ListenActivity.generic = isChecked;
            DBHelper.setGeneric(this, isChecked);
        }
    }

    // ############################################################################
    //                           END LISTENER METHODS
    // ############################################################################

    private void startSpoofing() {
        if (DEBUG)
            Log.d(APPLICATION_TAG, "START SPOOFING");
        WifiManager wManager = (WifiManager) getSystemService(Context.WIFI_SERVICE);
        WifiInfo wInfo = wManager.getConnectionInfo();

        //Check to see if we're connected to wifi
        int localhost = wInfo.getIpAddress();
        if (localhost != 0) {
            wManager.getConnectionInfo();
            gatewayIP = Formatter.formatIpAddress(wManager.getDhcpInfo().gateway);
            String localhostIP = Formatter.formatIpAddress(localhost);
            //If nothing was entered for the ip address use the gateway
            if (gatewayIP.trim().equals(""))
                gatewayIP = Formatter.formatIpAddress(wManager.getDhcpInfo().gateway);

            //determining wifi network interface
            InetAddress localInet;
            String interfaceName = null;
            try {
                localInet = InetAddress.getByName(localhostIP);
                NetworkInterface wifiInterface = NetworkInterface.getByInetAddress(localInet);
                interfaceName = wifiInterface.getDisplayName();
            } catch (UnknownHostException e) {
                Log.e(APPLICATION_TAG, "error getting localhost's InetAddress", e);
            } catch (SocketException e) {
                Log.e(APPLICATION_TAG, "error getting wifi network interface", e);
            }

            Intent intent = new Intent(this, ArpspoofService.class);
            Bundle mBundle = new Bundle();
            mBundle.putString("gateway", gatewayIP);
            mBundle.putString("localBin", SystemHelper.getARPSpoofBinaryPath(this));
            mBundle.putString("interface", interfaceName);
            intent.putExtras(mBundle);

            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Log.e(APPLICATION_TAG, "Error with Thread.sleep(500)", e);
            }
            startService(intent);
        } else {
            CharSequence text = "Must be connected to wireless network.";
            Toast.makeText(getApplicationContext(), text, Toast.LENGTH_LONG).show();
        }
    }

    public void stopSpoofing() {
        if (DEBUG)
            Log.d(APPLICATION_TAG, "STOP SPOOFING");
        Intent intent = new Intent(this, ArpspoofService.class);
        stopService(intent);
        try {
            Thread.sleep(200);
        } catch (InterruptedException e) {
            Log.e(APPLICATION_TAG, "Error with Thread.sleep(200)", e);
        }
    }

    public void stopListening() {
        if (DEBUG)
            Log.d(APPLICATION_TAG, "STOP LISTENING");
        Intent intent = new Intent(this, DroidSheepService.class);
        stopService(intent);
        try {
            Thread.sleep(200);
        } catch (InterruptedException e) {
            Log.e(APPLICATION_TAG, "Error with Thread.sleep(200)", e);
        }
    }

    private boolean isSpoofing() {
        ActivityManager manager = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
        for (RunningServiceInfo service : manager.getRunningServices(Integer.MAX_VALUE)) {
            if (ArpspoofService.class.getName().equals(service.service.getClassName())) {
                return true;
            }
        }
        return false;
    }

    private boolean isListening() {
        ActivityManager manager = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
        for (RunningServiceInfo service : manager.getRunningServices(Integer.MAX_VALUE)) {
            if (DroidSheepService.class.getName().equals(service.service.getClassName())) {
                return true;
            }
        }
        return false;
    }

    public void click(int id, boolean mobilePage) {
        if (authList.isEmpty()) {
            Toast.makeText(this.getApplicationContext(), "No Auth available...", Toast.LENGTH_SHORT).show();
            return;
        }
        Auth a;
        if (id < authList.size() && authList.get(id) != null) {
            a = authList.get(id);
        } else {
            return;
        }

        Bundle b = new Bundle();
        b.putSerializable(BUNDLE_KEY_AUTH, a);
        b.putBoolean(BUNDLE_KEY_MOBILE, mobilePage);

        Intent intent = new Intent(ListenActivity.this, HijackActivity.class);
        intent.putExtras(b);
        startActivity(intent);
    }

    private void startListening() {
        if (DEBUG)
            Log.d(APPLICATION_TAG, "START SPOOFING");
        SystemHelper.execSUCommand(CLEANUP_COMMAND_DROIDSHEEP, debugging);
        updateNetworkSettings();

        if (networkEncryptionWPA && !isSpoofing()) {
            Toast.makeText(this.getApplicationContext(),
                    "This network is WPA encrypted. Without ARP-Spoofing you won�t find sessions...!", Toast.LENGTH_LONG).show();
        }

        Button bstartstop = (Button) findViewById(R.id.bstartstop);

        if (!isListening()) {
            Intent intent = new Intent(this, DroidSheepService.class);
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Log.e(APPLICATION_TAG, "Error with Thread.sleep(500)", e);
            }
            startService(intent);
            bstartstop.setText("Stop");
        } else {
            Toast t = Toast.makeText(this.getApplicationContext(), getString(R.string.toast_process_running_text),
                    Toast.LENGTH_SHORT);
            t.show();
        }
        refresh();
    }

    private void cleanup() {
        tstatus.setText(getString(R.string.label_not_running));
        tstatus.setTextColor(Color.BLACK);
        pbrunning.setVisibility(View.INVISIBLE);
        Button button = ((Button) findViewById(R.id.bstartstop));
        button.setText("Start");
        stopSpoofing();
        stopListening();
        SystemHelper.execNewSUCommand(CLEANUP_COMMAND_ARPSPOOF, false);
        SystemHelper.execNewSUCommand(CLEANUP_COMMAND_DROIDSHEEP, false);
    }

    private void refresh() {
        boolean listening = isListening();

        refreshStatus();

        Button bstartstop = (Button) findViewById(R.id.bstartstop);
        if (listening) {
            if (debugging) {
                bstartstop.setText("Stop debugging");
            } else {
                bstartstop.setText("Stop");
            }
        } else {
            bstartstop.setText("Start");
            mNotificationManager.cancelAll();
            refreshHandler.stop();
        }

        updateNetworkSettings();
        sessionListView.refresh();
    }

    public void refreshStatus() {
        boolean listening = isListening();
        boolean spoofing = isSpoofing();

        if (listening && !spoofing) {
            tstatus.setText(getString(R.string.label_running));
            tstatus.setTextColor(Color.DKGRAY);
            tstatus.setTextSize(15);
            pbrunning.setVisibility(View.VISIBLE);
        } else if (listening) {
            tstatus.setText(getString(R.string.label_running_and_spoofing));
            tstatus.setTextColor(Color.GREEN);
            tstatus.setTextSize(15);
            pbrunning.setVisibility(View.VISIBLE);
        } else if (spoofing) {
            tstatus.setText(getString(R.string.label_not_running_and_spoofing));
            tstatus.setTextColor(Color.RED); //This shouldn't occur usually
            tstatus.setTextSize(15);
            pbrunning.setVisibility(View.VISIBLE);
        } else {
            tstatus.setText(getString(R.string.label_not_running));
            tstatus.setTextColor(Color.DKGRAY);
            tstatus.setTextSize(15);
            pbrunning.setVisibility(View.INVISIBLE);
        }

    }

    private void updateNetworkSettings() {
        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
        WifiInfo wifiInfo = wm.getConnectionInfo();

        if (wifiInfo == null) {
            networkEncryptionWPA = false;
            String networkName = "- None -";
            tnetworkName.setText(getString(R.string.label_networkname_pref) + networkName.toUpperCase(Locale.getDefault()));
        } else {
            String networkName = wifiInfo.getSSID() != null ? " " + wifiInfo.getSSID() : "";
            tnetworkName.setText(getString(R.string.label_networkname_pref) + networkName.toUpperCase(Locale.getDefault()));
        }
        TextView tspoof = (TextView) findViewById(R.id.spoofaddress);
        if (isSpoofing()) {
            tspoof.setText(getString(R.string.spoofingip_pref) + gatewayIP);
        } else {
            tspoof.setText(getString(R.string.notspoofingip));
        }
    }

    @SuppressLint("NewApi") // if statement should handle these API issues
    private void notifyUser(boolean persistent, Auth auth) {
        if (lastNotification >= authList.size())
            return;
        lastNotification = authList.size();

        int icon = R.drawable.droidsteal_notification;
        long when = System.currentTimeMillis();

        Context context = getApplicationContext();
        Intent notificationIntent = new Intent(ListenActivity.this, ListenActivity.class);
        PendingIntent contentIntent = PendingIntent.getActivity(this, 0, notificationIntent, 0);

        String notificationTitle = "DroidSteal is listening for sessions";
        String notificationText = auth != null ? auth.getUrl() : getString(R.string.notification_text);

        if (persistent) {
            notificationTitle = getString(R.string.notification_persistent);
        } else {
            notificationTitle = getString(R.string.notification_title);
        }

        Notification notification = null;
        int currentapiVersion = android.os.Build.VERSION.SDK_INT;

        if (currentapiVersion >= Build.VERSION_CODES.JELLY_BEAN) {
            notification = new Notification.Builder(context)
                    .setContentTitle(notificationTitle)
                    .setContentText(notificationText)
                    .setSmallIcon(icon)
                    .setContentIntent(contentIntent)
                    .build(); // Above if statement should handle API issues
            mNotificationManager.notify(NOTIFICATION_ID, notification);
        } else {
            Notification notificationICS = new Notification(icon, getString(R.string.notification_title), when);
            if (persistent) {
                notificationICS.setLatestEventInfo(context, notificationTitle,
                        getString(R.string.notification_text), contentIntent);
            } else {
                notificationICS.setLatestEventInfo(context, getString(R.string.notification_title),
                        getString(R.string.notification_text), contentIntent);
            }
            mNotificationManager.notify(NOTIFICATION_ID, notificationICS);
        }
    }

    private void askDebug() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setMessage(R.string.popup_debug).setCancelable(false)
                .setPositiveButton(R.string.button_ok, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int id) {
                        ListenActivity.this.startDebug();
                    }
                }).setNegativeButton(R.string.button_abort, new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface dialog, int id) {
                dialog.cancel();
            }
        });
        AlertDialog alert = builder.create();
        alert.show();
    }

    private void startDebug() {
        debugBuffer = new StringBuffer();
        debugging = true;
        debugBuffer.append("DEBUG SESSION START! ");
        debugBuffer.append(new Date());
        debugBuffer.append("\n");

        SystemHelper.debugInformation(this);
        SetupHelper.debugInformation(this);

        stopListening();
        stopSpoofing();

        SetupHelper.checkPrerequisites(this);

        Message m = handler.obtainMessage();
        Bundle b = new Bundle();
        b.putString(BUNDLE_KEY_TYPE, BUNDLE_TYPE_START);
        m.setData(b);
        handler.sendMessage(m);
    }

}