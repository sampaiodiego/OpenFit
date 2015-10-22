package com.solderbyte.openfit;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Calendar;

import com.solderbyte.openfit.R;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.provider.ContactsContract.PhoneLookup;
import android.support.v4.app.NotificationCompat;
import android.telephony.PhoneStateListener;
import android.telephony.TelephonyManager;
import android.media.ToneGenerator;
import android.media.AudioManager;
import android.util.Log;

@SuppressLint("HandlerLeak")
public class OpenFitService extends Service {
    private static final String LOG_TAG = "OpenFit:OpenFitService";

    private static final String INTENT_UI_BT = "com.solderbyte.openfit.ui.bt";
    private static final String INTENT_SERVICE_STOP = "com.solderbyte.openfit.service.stop";
    private static final String INTENT_NOTIFICATION = "com.solderbyte.openfit.notification";
    private static final String INTENT_SERVICE_BT = "com.solderbyte.openfit.service.bt";
    private static final String INTENT_SERVICE_SMS = "com.solderbyte.openfit.service.sms";
    private static final String INTENT_SERVICE_MMS = "com.solderbyte.openfit.service.mms";
    private static final String INTENT_SERVICE_PHONE = "com.solderbyte.openfit.service.phone";
    private static final String INTENT_SERVICE_PHONE_IDLE = "com.solderbyte.openfit.service.phone.idle";
    private static final String INTENT_SERVICE_PHONE_OFFHOOK = "com.solderbyte.openfit.service.phone.offhook";
    private static final String INTENT_SERVICE_WEATHER = "com.solderbyte.openfit.service.weather";
    private static final String INTENT_SERVICE_LOCATION = "com.solderbyte.openfit.service.location";
    private static final String INTENT_SERVICE_CRONJOB = "com.solderbyte.openfit.service.cronjob";

    // services
    private BluetoothLeService bluetoothLeService;
    private  Handler mHandler;
    private PackageManager pManager;
    private static ReconnectBluetoothThread reconnectThread;
    private static FindSoundThread findSoundThread;

    private int notificationId = 28518;
    private boolean smsEnabled = false;
    private boolean phoneEnabled = false;
    private boolean weatherEnabled = false;
    private boolean isReconnect = false;
    private boolean reconnecting = false;
    private boolean isStopping = false;
    private boolean isFinding = false;
    private SmsListener smsListener;
    private MmsListener mmsListener;
    private TelephonyManager telephony;
    private DialerListener dailerListener;
    private Notification notification;

    @Override
    public IBinder onBind(Intent intent) {
        Log.d(LOG_TAG, "onBind");
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(LOG_TAG, "onStartCommand: " + intent);
        // register receivers
        this.registerReceiver(serviceStopReceiver, new IntentFilter(INTENT_SERVICE_STOP));
        this.registerReceiver(btReceiver, new IntentFilter(INTENT_SERVICE_BT));
        this.registerReceiver(notificationReceiver, new IntentFilter(INTENT_NOTIFICATION));
        this.registerReceiver(smsReceiver, new IntentFilter(INTENT_SERVICE_SMS));
        this.registerReceiver(mmsReceiver, new IntentFilter(INTENT_SERVICE_MMS));
        this.registerReceiver(phoneReceiver, new IntentFilter(INTENT_SERVICE_PHONE));
        this.registerReceiver(phoneIdleReceiver, new IntentFilter(INTENT_SERVICE_PHONE_IDLE));
        this.registerReceiver(phoneOffhookReceiver, new IntentFilter(INTENT_SERVICE_PHONE_OFFHOOK));
        this.registerReceiver(mediaReceiver, MediaController.getIntentFilter());
        this.registerReceiver(alarmReceiver, Alarm.getIntentFilter());
        this.registerReceiver(weatherReceiver, new IntentFilter(INTENT_SERVICE_WEATHER));
        this.registerReceiver(locationReceiver, new IntentFilter(INTENT_SERVICE_LOCATION));
        this.registerReceiver(cronReceiver, new IntentFilter(INTENT_SERVICE_CRONJOB));

        pManager = this.getPackageManager();
        MediaController.init(this);
        LocationInfo.init(this);
        Weather.init(this);
        Cronjob.init(this);

        // start service
        this.createNotification(false);
        this.startBluetoothHandler();
        this.startBluetoothService();
        this.startNotificationListenerService();
        this.startForeground(notificationId, notification);
        return super.onStartCommand(intent, flags, startId);
    }

    @Override
    public void onDestroy() {
        Log.d(LOG_TAG, "onDestroy");
        unregisterReceiver(serviceStopReceiver);
        super.onDestroy();
    }

    public void sendServiceStarted() {
        Log.d(LOG_TAG, "sendServiceStarted");
        Intent i = new Intent(INTENT_UI_BT);
        i.putExtra("message", "OpenFitService");
        sendBroadcast(i);
    }

    public void startBluetoothService() {
        // initialize BluetoothLE
        Intent gattServiceIntent = new Intent(this, BluetoothLeService.class);
        this.bindService(gattServiceIntent, mServiceConnection, Context.BIND_AUTO_CREATE);
        Log.d(LOG_TAG, "Starting bluetooth service");
    }

    public void reconnectBluetoothService() {
        Log.d(LOG_TAG, "starting reconnect thread");
        if(isReconnect) {
            reconnectThread = new ReconnectBluetoothThread();
            reconnectThread.start();
            reconnecting = true;
        }
    }

    public void reconnectBluetoothStop() {
        Log.d(LOG_TAG, "stopping reconnect thread");
        reconnecting = false;
        if(reconnectThread != null) {
            reconnectThread.close();
            reconnectThread = null;
            Log.d(LOG_TAG, "stopped reconnect thread");
        }
    }

    protected ServiceConnection mServiceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName componentName, IBinder service) {
            Log.d(LOG_TAG, "onService Connected");
            bluetoothLeService = ((BluetoothLeService.LocalBinder)service).getService();
            if(!bluetoothLeService.initialize()) {
                Log.e(LOG_TAG, "Unable to initialize BluetoothLE");
            }
            bluetoothLeService.setHandler(mHandler);
            sendServiceStarted();
            sendBluetoothStatus();

            // Automatically connects to the device upon successful start-up initialization.
            //bluetoothLeService.connect(mDeviceAddress);
        }

        @Override
        public void onServiceDisconnected(ComponentName componentName) {
            Log.d(LOG_TAG, "Bluetooth onServiceDisconnected");
            bluetoothLeService = null;
        }
    };

    public void startBluetoothHandler() {
        // setup message handler
        Log.d(LOG_TAG, "Setting up bluetooth Service handler");
        mHandler = new Handler() {
            @Override
            public void handleMessage(Message msg) {
                Log.d(LOG_TAG, "handleMessage: "+msg.getData());
                String bluetoothMessage = msg.getData().getString("bluetooth");
                String bluetoothDevice = msg.getData().getString("bluetoothDevice");
                String bluetoothDevicesList = msg.getData().getString("bluetoothDevicesList");
                String bluetoothData = msg.getData().getString("bluetoothData");
                if(bluetoothMessage != null && !bluetoothMessage.isEmpty()) {
                    Intent i = new Intent(INTENT_UI_BT);
                    i.putExtra("message", bluetoothMessage);
                    sendBroadcast(i);
                }
                if(bluetoothDevice != null && !bluetoothDevice.isEmpty()) {
                    String[] sDevice = bluetoothDevice.split(",");
                    String sDeviceName = sDevice[0];
                    String sDeviceAddress = sDevice[1];
                    Intent i = new Intent(INTENT_UI_BT);
                    i.putExtra("message", bluetoothDevice);
                    i.putExtra("deviceName", sDeviceName);
                    i.putExtra("deviceAddress", sDeviceAddress);
                    sendBroadcast(i);
                }
                if(bluetoothDevicesList != null && !bluetoothDevicesList.isEmpty()) {
                    CharSequence[] bluetoothEntries = msg.getData().getCharSequenceArray("bluetoothEntries");
                    CharSequence[] bluetoothEntryValues = msg.getData().getCharSequenceArray("bluetoothEntryValues");
                    Intent i = new Intent(INTENT_UI_BT);
                    i.putExtra("message", bluetoothDevicesList);
                    i.putExtra("bluetoothEntries", bluetoothEntries);
                    i.putExtra("bluetoothEntryValues", bluetoothEntryValues);
                    sendBroadcast(i);
                }
                if(bluetoothData != null && !bluetoothData.isEmpty()) {
                    byte[] byteArray = msg.getData().getByteArray("data");
                    handleBluetoothData(byteArray);
                }
                if(bluetoothMessage != null && bluetoothMessage.equals("isConnectedRfcomm")) {
                    if(reconnecting) {
                        reconnectBluetoothStop();
                    }
                    if(!isStopping) {
                        createNotification(true);
                    }
                }
                if(bluetoothMessage != null && bluetoothMessage.equals("isDisconnectedRfComm")) {
                    if(isReconnect) {
                        reconnectBluetoothService();
                    }
                    if(!isStopping) {
                        createNotification(false);
                    }
                }
            }
        };
    }

    public void handleUIMessage(String message, Intent intent) {
        if(message != null && !message.isEmpty() && bluetoothLeService != null) {
            if(message.equals("enable")) {
                bluetoothLeService.enableBluetooth();
            }
            if(message.equals("disable")) {
                bluetoothLeService.disableBluetooth();
            }
            if(message.equals("scan")) {
                bluetoothLeService.scanLeDevice();
            }
            if(message.equals("connect")) {
                bluetoothLeService.connectRfcomm();
                isReconnect = true;
            }
            if(message.equals("disconnect")) {
                bluetoothLeService.disconnectRfcomm();
                isReconnect = false;
            }
            if(message.equals("setEntries")) {
                bluetoothLeService.setEntries();
            }
            if(message.equals("setDevice")) {
                String deviceMac = intent.getStringExtra("data");
                bluetoothLeService.setDevice(deviceMac);
            }
            if(message.equals("time")) {
                String s = intent.getStringExtra("data");
                boolean value = Boolean.parseBoolean(s);
                sendTime(value);
            }
            if(message.equals("weather")) {
                String unit = intent.getStringExtra("data");
                if(unit.equals("none")) {
                    weatherEnabled = false;
                }
                else {
                    Weather.setUnits(unit);
                    weatherEnabled = true;
                }
                startWeatherCronJob();
            }
            if(message.equals("fitness")) {
                sendFitnessRequest();
            }
            if(message.equals("phone")) {
                String s = intent.getStringExtra("data");
                phoneEnabled = Boolean.parseBoolean(s);
                startDailerListener();
            }
            if(message.equals("sms")) {
                String s = intent.getStringExtra("data");
                smsEnabled = Boolean.parseBoolean(s);
                startSmsListener();
            }
            if(message.equals("status")) {
                sendBluetoothStatus();
            }
        }
    }

    public void handleBluetoothData(byte[] data) {
        Log.d(LOG_TAG, "Service received: " + OpenFitApi.byteArrayToHexString(data));
        if(Arrays.equals(data, OpenFitApi.getReady())) {
            Log.d(LOG_TAG, "Recieved ready message");
            bluetoothLeService.write(OpenFitApi.getUpdate());
            bluetoothLeService.write(OpenFitApi.getUpdateFollowUp());
            bluetoothLeService.write(OpenFitApi.getFotaCommand());
            bluetoothLeService.write(OpenFitApi.getCurrentTimeInfo(false));
        }

        if(Arrays.equals(data, OpenFitApi.getFindStart())) {
            sendFindStart();
        }
        if(Arrays.equals(data, OpenFitApi.getFindStop())) {
            sendFindStop();
        }
        if(Arrays.equals(data, OpenFitApi.getMediaPrev())) {
            sendMediaPrev();
        }
        if(Arrays.equals(data, OpenFitApi.getMediaNext())) {
            sendMediaNext();
        }
        if(Arrays.equals(data, OpenFitApi.getMediaPlay())) {
            sendMediaPlay();
        }
        if(OpenFitApi.byteArrayToHexString(data).contains(OpenFitApi.byteArrayToHexString(OpenFitApi.getMediaVolume()))) {
            byte vol = data[data.length - 1];
            sendMediaVolume(vol, false);
        }
        if(Arrays.equals(data, OpenFitApi.getMediaReqStart())) {
            sendMediaRes();
        }
        if(Arrays.equals(data, OpenFitApi.getOpenAlarmCleared())) {
            //DismissAlarm();
        }
        if(Arrays.equals(data, OpenFitApi.getOpenAlarmSnoozed())) {
            //snoozeAlarm();
        }
        if(Arrays.equals(data, OpenFitApi.getFitnessSyncRes())) {
            sendFitnessRequest();
        }
        if(Fitness.isPendingData()) {
            handleFitnessData(data);
        }
        if(OpenFitApi.byteArrayToHexString(data).startsWith(OpenFitApi.byteArrayToHexString(OpenFitApi.getFitness()))) {
            Log.d(LOG_TAG, "Fitness Received: " + data.length);
            if(Fitness.isFitnessData(data)) {
                Log.d(LOG_TAG, "Fitness data found setting listener");
                handleFitnessData(data);
            }
            else {
                Log.d(LOG_TAG, "Fitness data false");
            }
        }
        if(OpenFitApi.byteArrayToHexString(data).contains(OpenFitApi.byteArrayToHexString(OpenFitApi.getOpenRejectCall()))) {
            endCall();
        }
    }

    public void handleFitnessData(byte[] data) {
        Fitness.addData(data);
        if(!Fitness.isPendingData()) {
            Log.d(LOG_TAG, "Fitness data complete");
            Fitness.parseData();
            Intent i = new Intent(INTENT_UI_BT);
            i.putExtra("message", "fitness");
            i.putExtra("pedometerTotal", Fitness.getPedometerTotal());
            i.putExtra("pedometerList", Fitness.getPedometerList());
            i.putParcelableArrayListExtra("pedometerArrayList", Fitness.getPedometerList());
            i.putParcelableArrayListExtra("pedometerDailyArrayList", Fitness.getPedometerDailyList());

            sendBroadcast(i);
        }
    }

    public void startNotificationListenerService() {
        Intent notificationIntent = new Intent(this, NotificationService.class);
        this.startService(notificationIntent);
        Log.d(LOG_TAG, "Starting notification service");
    }

    public void startDailerListener() {
        Log.d(LOG_TAG, "Starting Phone Listeners");
        if(phoneEnabled) {
            dailerListener = new DialerListener(this);
            telephony = (TelephonyManager) this.getSystemService(Context.TELEPHONY_SERVICE);
            telephony.listen(dailerListener, PhoneStateListener.LISTEN_CALL_STATE);
            Log.d(LOG_TAG, "phone listening");
        }
        else {
            if(telephony != null) {
                telephony.listen(dailerListener, PhoneStateListener.LISTEN_NONE);
                dailerListener.destroy();
            }
        }
    }

    public void startSmsListener() {
        Log.d(LOG_TAG, "Starting SMS/MMS Listeners");
        // register listeners
        if(smsEnabled) {
            if(smsListener == null) {
                smsListener = new SmsListener(this);
                IntentFilter smsFilter = new IntentFilter("android.provider.Telephony.SMS_RECEIVED");
                this.registerReceiver(smsListener, smsFilter);
                mmsListener = new MmsListener(this);
                IntentFilter mmsFilter = new IntentFilter("android.provider.Telephony.WAP_PUSH_RECEIVED");
                this.registerReceiver(mmsListener, mmsFilter);
            }
        }
        else {
            if(smsListener != null) {
                this.unregisterReceiver(smsListener);
                smsListener = null;
                this.unregisterReceiver(mmsListener);
                mmsListener = null;
            }
        }
    }

    public void createNotification(boolean connected) {
        Log.d(LOG_TAG, "Creating Notification: " + connected);
        Intent stopService =  new Intent(INTENT_SERVICE_STOP);
        //Intent startActivity = new Intent(this, OpenFitActivity.class);
        //PendingIntent startIntent = PendingIntent.getActivity(this, 0,startActivity, PendingIntent.FLAG_NO_CREATE);
        PendingIntent stopIntent = PendingIntent.getBroadcast(this, 0, stopService, PendingIntent.FLAG_UPDATE_CURRENT);

        NotificationCompat.Builder nBuilder = new NotificationCompat.Builder(this);
        nBuilder.setSmallIcon(R.drawable.open_fit_notification);
        nBuilder.setContentTitle(getString(R.string.notification_title));
        if(connected) {
            nBuilder.setContentText(getString(R.string.notification_connected));
        }
        else {
            nBuilder.setContentText(getString(R.string.notification_disconnected));
        }
        //nBuilder.setContentIntent(startIntent);
        nBuilder.setAutoCancel(true);
        nBuilder.setOngoing(true);
        nBuilder.addAction(R.drawable.open_off_noti, getString(R.string.notification_button_close), stopIntent);
        if(connected) {
            Intent cIntent = new Intent(INTENT_SERVICE_BT);
            cIntent.putExtra("message", "disconnect");
            PendingIntent pConnect = PendingIntent.getBroadcast(this, 0, cIntent, PendingIntent.FLAG_UPDATE_CURRENT);
            nBuilder.addAction(R.drawable.open_btd, getString(R.string.notification_button_disconnect), pConnect);
        }
        else {
            Intent cIntent = new Intent(INTENT_SERVICE_BT);
            cIntent.putExtra("message", "connect");
            PendingIntent pConnect = PendingIntent.getBroadcast(this, 0, cIntent, PendingIntent.FLAG_UPDATE_CURRENT);
            nBuilder.addAction(R.drawable.open_btc, getString(R.string.notification_button_connect), pConnect);
        }

        // Sets an ID for the notification
        NotificationManager nManager = (NotificationManager) this.getSystemService(NOTIFICATION_SERVICE);
        notification = nBuilder.build();
        nManager.notify(notificationId, notification);
    }

    public void clearNotification() {
        NotificationManager nManager = (NotificationManager) this.getSystemService(NOTIFICATION_SERVICE);
        nManager.cancel(notificationId);
    }

    public void sendBluetoothStatus() {
     // update bluetooth ui
        if(BluetoothLeService.isEnabled) {
            Intent i = new Intent(INTENT_UI_BT);
            i.putExtra("message", "isEnabled");
            sendBroadcast(i);
        }
        else {
            Intent i = new Intent(INTENT_UI_BT);
            i.putExtra("message", "isEnabledFailed");
            sendBroadcast(i);
        }
        if(BluetoothLeService.isConnected) {
            Intent i = new Intent(INTENT_UI_BT);
            i.putExtra("message", "isConnected");
            sendBroadcast(i);
            createNotification(true);
        }
        else {
            Intent i = new Intent(INTENT_UI_BT);
            i.putExtra("message", "isDisconnected");
            sendBroadcast(i);
            createNotification(false);
        }
    }

    public void sendTime(boolean is24Hour) {
        byte[] bytes = OpenFitApi.getCurrentTimeInfo(is24Hour);
        bluetoothLeService.write(bytes);
    }

    public void sendFitnessRequest() {
        Log.d(LOG_TAG, "sendFitnessRequest");
        byte[] bytes = OpenFitApi.getFitnessHeartBeat();
        bluetoothLeService.write(bytes);
    }

    public void sendMediaTrack() {
        byte[] bytes = OpenFitApi.getOpenMediaTrack(MediaController.getTrack());
        if(bluetoothLeService != null) {
            bluetoothLeService.write(bytes);
        }
    }

    public void sendMediaPrev() {
        Log.d(LOG_TAG, "Media Prev");
        sendOrderedBroadcast(MediaController.spotifyPrevious(), null);
        sendOrderedBroadcast(MediaController.prevTrackDown(), null);
        sendOrderedBroadcast(MediaController.prevTrackUp(), null);
    }

    public void sendMediaNext() {
        Log.d(LOG_TAG, "Media Next");
        //If using Spotify
        sendOrderedBroadcast(MediaController.spotifyNext(), null);
        //else
        sendOrderedBroadcast(MediaController.nextTrackDown(), null);
        sendOrderedBroadcast(MediaController.nextTrackUp(), null);
    }

    public void sendMediaPlay() {
        Log.d(LOG_TAG, "Media Play/Pause");
        sendOrderedBroadcast(MediaController.spotifyPlayPause(), null);
        sendOrderedBroadcast(MediaController.playTrackDown(), null);
        sendOrderedBroadcast(MediaController.playTrackUp(), null);
    }

    public void sendMediaVolume(byte vol, boolean req) {
        Log.d(LOG_TAG, "Media Volume: " + vol);
        byte offset = (byte) (MediaController.getActualVolume() - MediaController.getVolume());
        if(offset != 0) {
            vol = (byte) (vol + offset);
            if(!req) {
                if(offset > 0) {
                    vol += 1;
                }
                else {
                    vol -= 1;
                }
            }
        }
        MediaController.setVolume(vol);
        byte[] bytes = OpenFitApi.getMediaSetVolume(vol);
        bluetoothLeService.write(bytes);
    }

    public void sendMediaRes() {
        Log.d(LOG_TAG, "Media Request");
        sendMediaTrack();
        sendMediaVolume(MediaController.getVolume(), true);
    }

    public void sendFindStart() {
        Log.d(LOG_TAG, "Find Start");
        if(isFinding == false) {
            findSoundThread = new FindSoundThread();
            findSoundThread.start();
            isFinding = true;
        }
    }

    public void sendFindStop() {
        Log.d(LOG_TAG, "Find Stop");
        if(findSoundThread != null) {
            findSoundThread = null;
            Log.d(LOG_TAG, "stopped find thread");
        }
        isFinding = false;
    }

    public void sendAppNotification(String packageName, String sender, String title, String message, int id) {
        byte[] bytes = OpenFitApi.getOpenNotification(packageName, sender, title, message, id);
        bluetoothLeService.write(bytes);
    }

    public void sendEmailNotification(String packageName, String sender, String title, String message, int id) {
        byte[] bytes = OpenFitApi.getOpenEmail(sender, title, message, message, id);
        bluetoothLeService.write(bytes);
    }

    public void sendDialerNotification(String number) {
        long id = (long)(System.currentTimeMillis() / 1000L);
        String sender = number;
        String name = getContactName(number);
        if(name != null) {
            sender = name;
        }
        byte[] bytes = OpenFitApi.getOpenIncomingCall(sender, number, id);
        bluetoothLeService.write(bytes);
    }

    public void sendDialerEndNotification() {
        byte[] bytes = OpenFitApi.getOpenIncomingCallEnd();
        bluetoothLeService.write(bytes);
    }

    public void endCall() {
        Log.d(LOG_TAG, "Ending call");
        Class<?> telephonyClass = null;
        Method method = null;
        Method endCall = null;
        try {
            telephonyClass = Class.forName(telephony.getClass().getName());
            method = telephonyClass.getDeclaredMethod("getITelephony");
            method.setAccessible(true);
            Object iTelephony = null;
            iTelephony = method.invoke(telephony);
            endCall = iTelephony.getClass().getDeclaredMethod("endCall");
            endCall.invoke(iTelephony);
        }
        catch(Exception e) {
            Log.d(LOG_TAG, "Failed ending call");
            e.printStackTrace();
        }
    }

    public void sendSmsNotification(String number, String message) {
        long id = (long)(System.currentTimeMillis() / 1000L);
        String title = "Text Message";
        String sender = number;
        String name = getContactName(number);
        if(name != null) {
            sender = name;
        }
        byte[] bytes = OpenFitApi.getOpenNotification(sender, number, title, message, id);
        bluetoothLeService.write(bytes);
    }

    public String getAppName(String packageName) {
        ApplicationInfo appInfo = null;
        try {
            appInfo = pManager.getApplicationInfo(packageName, PackageManager.GET_META_DATA);
        }
        catch (NameNotFoundException e) {
            Log.d(LOG_TAG, "Cannot get application info");
        }
        String appName = (String) pManager.getApplicationLabel(appInfo);
        return appName;
    }

    public String getContactName(String phoneNumber) {
        ContentResolver cr = this.getContentResolver();
        Uri uri = Uri.withAppendedPath(PhoneLookup.CONTENT_FILTER_URI, Uri.encode(phoneNumber));
        Cursor cursor = cr.query(uri, new String[] {PhoneLookup.DISPLAY_NAME}, null, null, null);
        if(cursor == null) {
            return null;
        }
        String contactName = null;
        if(cursor.moveToFirst()) {
            contactName = cursor.getString(cursor.getColumnIndex(PhoneLookup.DISPLAY_NAME));
        }
        if(cursor != null && !cursor.isClosed()) {
            cursor.close();
        }
        return contactName;
    }

    public void sendAlarmStart() {
        long id = (long)(System.currentTimeMillis() / 1000L);
        byte[] bytes = OpenFitApi.getOpenAlarm(id);
        bluetoothLeService.write(bytes);
    }

    public void sendAlarmStop() {
        byte[] bytes = OpenFitApi.getOpenAlarmClear();
        bluetoothLeService.write(bytes);
    }

    public void sendWeatherNotifcation(String weather, String icon) {
        long id = (long)(System.currentTimeMillis() / 1000L);
        byte[] bytes = OpenFitApi.getOpenWeather(weather, icon, id);
        bluetoothLeService.write(bytes);
    }
    
    public void sendWeatherClock(String location, String tempCur, String tempUnit, String icon) {
        byte[] bytes = OpenFitApi.getOpenWeatherClock(location, tempCur, tempUnit, icon);
        bluetoothLeService.write(bytes);
    }

    public void startWeatherCronJob() {
        if(weatherEnabled) {
            Log.d(LOG_TAG, "Starting Weather Cronjob");
            Cronjob.start();
        }
        else {
            Log.d(LOG_TAG, "Stopping Weather Cronjob");
            Cronjob.stop();
        }
    }

    public void getWeather() {
        if(LocationInfo.getLat() != 0 && LocationInfo.getLon() != 0) {
            String query = "lat=" + LocationInfo.getLat() + "&lon=" + LocationInfo.getLon();
            String country = null;
            String location = null;
            if(LocationInfo.getCountryCode() != null) {
                country = LocationInfo.getCountryCode();
            }
            else if(LocationInfo.getCountryName() != null) {
                country = LocationInfo.getCountryName();
            }
            if(country != null) {
                location = LocationInfo.getCityName() + ", " + country;
            }
            else {
                location = LocationInfo.getCityName();
            }
            if(weatherEnabled) {
                Weather.getWeather(query, location);
            }
        }
    }

    // Does not work
    /*public void snoozeAlarm() {
        Log.d(LOG_TAG, "Snoozing alarm");
        Intent intent = Alarm.snoozeAlarm();
        sendBroadcast(intent);
    }

    public void DismissAlarm() {
        Log.d(LOG_TAG, "Dismissing alarm");
        Intent intent = Alarm.dismissAlarm();
        sendBroadcast(intent);
    }*/

    private BroadcastReceiver serviceStopReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            Log.d(LOG_TAG, "Stopping Service");
            reconnecting = false;
            isReconnect = false;
            isStopping = true;
            isFinding = false;
            mHandler = null;
            Log.d(LOG_TAG, "Stopping" + smsEnabled +" : " + phoneEnabled);
            if(smsEnabled) {
                unregisterReceiver(smsListener);
                unregisterReceiver(mmsListener);
            }
            if(phoneEnabled) {
                telephony.listen(dailerListener, PhoneStateListener.LISTEN_NONE);
                dailerListener.destroy();
            }
            unregisterReceiver(btReceiver);
            unregisterReceiver(notificationReceiver);
            unregisterReceiver(smsReceiver);
            unregisterReceiver(mmsReceiver);
            unregisterReceiver(phoneReceiver);
            unregisterReceiver(phoneIdleReceiver);
            unregisterReceiver(phoneOffhookReceiver);
            unregisterReceiver(mediaReceiver);
            unregisterReceiver(alarmReceiver);
            unregisterReceiver(weatherReceiver);
            unregisterReceiver(locationReceiver);
            unregisterReceiver(cronReceiver);
            unbindService(mServiceConnection);
            Cronjob.stop();
            clearNotification();
            reconnectBluetoothStop();
            Log.d(LOG_TAG, "stopSelf");
            stopForeground(true);
            stopSelf();
        }
    };

    private BroadcastReceiver btReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String message = intent.getStringExtra("message");
            handleUIMessage(message, intent);
            Log.d(LOG_TAG, "Received UI Command: " + message);
        }
    };

    private BroadcastReceiver notificationReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String packageName = intent.getStringExtra("packageName");
            final String ticker = intent.getStringExtra("ticker");
            final String title = intent.getStringExtra("title");
            final String message = intent.getStringExtra("message");
            //long time = intent.getLongExtra("time", 0);
            final int id = intent.getIntExtra("id", 0);
            final String appName = getAppName(packageName);

            if(packageName.equals("com.google.android.gm")) {
                Log.d(LOG_TAG, "Received email:" + appName + " title:" + title + " ticker:" + ticker + " message:" + message);
                sendEmailNotification(appName, title, ticker, message, id);
            }
            else {
                Log.d(LOG_TAG, "Received notification appName:" + appName + " title:" + title + " ticker:" + ticker + " message:" + message);
                sendAppNotification(appName, title, ticker, message, id);
            }
        }
    };

    private BroadcastReceiver smsReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String message = intent.getStringExtra("message");
            String sender = intent.getStringExtra("sender");
            Log.d(LOG_TAG, "Recieved SMS message: "+sender+" - "+message);
            sendSmsNotification(sender, message);
        }
    };

    private BroadcastReceiver mmsReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String message = "MMS received";
            String sender = intent.getStringExtra("sender");
            Log.d(LOG_TAG, "Recieved MMS message: "+sender+" - "+message);
            sendSmsNotification(sender, message);
        }
    };

    private BroadcastReceiver phoneReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String sender = intent.getStringExtra("sender");
            Log.d(LOG_TAG, "Recieved PHONE: "+sender);
            sendDialerNotification(sender);
        }
    };

    private BroadcastReceiver phoneIdleReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String sender = intent.getStringExtra("sender");
            Log.d(LOG_TAG, "Recieved Idle: "+sender);
            sendDialerEndNotification();
        }
    };

    private BroadcastReceiver phoneOffhookReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String sender = intent.getStringExtra("sender");
            Log.d(LOG_TAG, "Recieved Offhook: "+sender);
            sendDialerEndNotification();
        }
    };

    private BroadcastReceiver mediaReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String artist = MediaController.getArtist(intent);
            //String album = MediaController.getAlbum(intent);
            String track = MediaController.getTrack(intent);
            String mediaTrack = artist + " - " + track;
            Log.d(LOG_TAG, "Media sending: " + mediaTrack);
            MediaController.setTrack(mediaTrack);
            sendMediaTrack();
        }
    };

    private BroadcastReceiver alarmReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = Alarm.getAction(intent);
            Log.d(LOG_TAG, "Alarm Action: " + action);
            if(action.equals("START")) {
                sendAlarmStart();
            }
            else if(action.equals("SNOOZE")) {
                sendAlarmStop();
            }
            else if(action.equals("STOP")) {
                sendAlarmStop();
            }
        }
    };

    private BroadcastReceiver weatherReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            Log.d(LOG_TAG, "weatherReceiver updated: ");
            //String name = intent.getStringExtra("name");
            //String weather = intent.getStringExtra("weather");
            String description = intent.getStringExtra("description");
            String tempCur = intent.getStringExtra("tempCur");
            //String tempMin = intent.getStringExtra("tempMin");
            //String tempMax = intent.getStringExtra("tempMax");
            String tempUnit = intent.getStringExtra("tempUnit");
            //String humidity = intent.getStringExtra("humidity");
            //String pressure = intent.getStringExtra("pressure");
            String icon = intent.getStringExtra("icon");
            String location = intent.getStringExtra("location");

            /*Log.d(LOG_TAG, "City Name: " + name);
            Log.d(LOG_TAG, "Weather: " + weather);
            Log.d(LOG_TAG, "Description: " + description);
            Log.d(LOG_TAG, "Temperature Current: " + tempCur);
            Log.d(LOG_TAG, "Temperature Min: " + tempMin);
            Log.d(LOG_TAG, "Temperature Max: " + tempMax);
            Log.d(LOG_TAG, "Temperature Unit: " + tempUnit);
            Log.d(LOG_TAG, "Humidity: " + humidity);
            Log.d(LOG_TAG, "Pressure: " + pressure);
            Log.d(LOG_TAG, "icon: " + icon);*/

            String weatherInfo = location + ": " + tempCur + tempUnit + "\nWeather: " + description;
            Log.d(LOG_TAG, weatherInfo);
            sendWeatherNotifcation(weatherInfo, icon);
            sendWeatherClock(location, tempCur, tempUnit, icon);
        }
    };

    private BroadcastReceiver locationReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            Log.d(LOG_TAG, "locationReceiver updated");
            getWeather();
        }
    };

    private BroadcastReceiver cronReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            Log.d(LOG_TAG, "#####CronJob#####");
            if(weatherEnabled) {
                //LocationInfo.updateLastKnownLocation();
                //getWeather();
                LocationInfo.listenForLocation();
                
            }
        }
    };

    private class ReconnectBluetoothThread extends Thread {
        public void run() {
            long timeStart = Calendar.getInstance().getTimeInMillis();
            Log.d(LOG_TAG, "Reconnecting Bluetooth: "+timeStart);

            while(reconnecting) {
                try {
                    long timeDiff =  Calendar.getInstance().getTimeInMillis() - timeStart;
                    Log.d(LOG_TAG, "Reconnecting Elapsed time: " + timeDiff/1000);
                    bluetoothLeService.connectRfcomm();
                    Thread.sleep(10000L);
                }
                catch(InterruptedException ie) {
                    // unexpected interruption while enabling bluetooth
                    Thread.currentThread().interrupt(); // restore interrupted flag
                    return;
                }
            }
        }

        public void close() {
            reconnecting = false;
        }
    }

    private class FindSoundThread extends Thread {
        public void run() {
            long timeStart = Calendar.getInstance().getTimeInMillis();
            Log.d(LOG_TAG, "FindSound Start: "+timeStart);
            ToneGenerator toneG = new ToneGenerator(AudioManager.STREAM_ALARM, ToneGenerator.MAX_VOLUME);

            while(isFinding) {
                try {
                    long timeDiff =  Calendar.getInstance().getTimeInMillis() - timeStart;
                    Log.d(LOG_TAG, "Sound time: " + timeDiff/1000);

                    toneG.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 200); // 200 ms tone
                    Thread.sleep(600L);
                }
                catch(InterruptedException ie) {
                    // unexpected interruption while enabling bluetooth
                    Thread.currentThread().interrupt(); // restore interrupted flag
                    return;
                }
            }
        }
    };
}
