//  Irene Wachirawutthichai
//   CSS 532, Winter 2021
//
//  This file is part of an IoT project source code for UW Bothell, CSS 532 w/ Professor Peng Yang.
//
//  AdaptiveV or Dynamic Volume (DyVo) is an IoT project where the user's distance from an
//  access point dynamically changes the master volume level of the companion PC. The two
//  client devices communicate through AWS MQTT services.
//
//  This android application is a partner application to be run in conjunction with the
//  python script on a windows computer with access to internet. This application requires
//  the device running it and its access point to support IEEE 802.11mc. The application
//  connects to the AWS services using user credentials from a user/identity pool serviced by
//  AWS Cognito. When connected to AWS resources, the application publishes the round-trip time
//  ranging results to IoT Core MQTT service and subscribes to the volume level from the companion
//  computer. Users can adjust the values for the dynamic scaling directly from the UI, and
//  changes to the settings will live locally on the device.

package com.example.adaptivev;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

import android.Manifest;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.net.wifi.rtt.RangingRequest;
import android.net.wifi.rtt.RangingResult;
import android.net.wifi.rtt.RangingResultCallback;
import android.net.wifi.rtt.WifiRttManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

//AWS services
import com.amazonaws.mobileconnectors.cognitoidentityprovider.CognitoDevice;
import com.amazonaws.mobileconnectors.cognitoidentityprovider.CognitoUser;
import com.amazonaws.mobileconnectors.cognitoidentityprovider.CognitoUserPool;
import com.amazonaws.mobileconnectors.cognitoidentityprovider.CognitoUserSession;
import com.amazonaws.mobileconnectors.cognitoidentityprovider.continuations.AuthenticationContinuation;
import com.amazonaws.mobileconnectors.cognitoidentityprovider.continuations.AuthenticationDetails;
import com.amazonaws.mobileconnectors.cognitoidentityprovider.continuations.ChallengeContinuation;
import com.amazonaws.mobileconnectors.cognitoidentityprovider.continuations.MultiFactorAuthenticationContinuation;
import com.amazonaws.mobileconnectors.cognitoidentityprovider.handlers.AuthenticationHandler;
import com.amazonaws.mobileconnectors.iot.AWSIotMqttClientStatusCallback;
import com.amazonaws.mobileconnectors.iot.AWSIotMqttManager;
import com.amazonaws.mobileconnectors.iot.AWSIotMqttNewMessageCallback;
import com.amazonaws.mobileconnectors.iot.AWSIotMqttQos;
import com.amazonaws.auth.CognitoCachingCredentialsProvider;
import com.amazonaws.regions.Regions;

import org.json.JSONObject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class MainActivity extends AppCompatActivity {
    //Application managers
    private WifiRttManager wifiRttManager;
    private WifiManager wifiManager;
    private AWSIotMqttManager mqttManager;

    private RttRangingResultCallback appRttRangingResultCallback;
    private int rangeRequestCount;
    private final int rangingRequestDelay = 500; //in milliseconds
    private String awsToken;
    private boolean repeatReq = false;
    private int prevRange = 0;
    private int mqttSent = 0;

    //UI elements
    private TextView rtt_range;
    private TextView rtt_request_count;
    private Button makeRequest;
    private Button stopRequest;
    private TextView volume;
    private Button applySettings;
    private ScanResult connectedScanResult;
    private SharedPreferences sharedPreferences;




    @RequiresApi(api = Build.VERSION_CODES.R)
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        //UI element fetch IDs
        TextView networkSSID = findViewById(R.id.network_ssid);
        EditText minDistance = findViewById(R.id.min_distance);
        EditText minVolume = findViewById(R.id.min_volume);
        EditText maxDistance = findViewById(R.id.max_distance);
        EditText maxVolume = findViewById(R.id.max_volume);
        Switch settingsLock = findViewById(R.id.settings_lock);
        makeRequest = findViewById(R.id.rtt_request);
        stopRequest = findViewById(R.id.rtt_request_stop);
        rtt_range = findViewById(R.id.rtt_range);
        rtt_request_count = findViewById(R.id.rtt_request_count);
        volume = findViewById(R.id.audio_volume);
        applySettings = findViewById(R.id.apply_settings);

        //Initial app requirements analog on start up
        AlertDialog.Builder builder = new AlertDialog.Builder(MainActivity.this);
        builder.setTitle("Application requirements");
        builder.setMessage("This application may not function if ALL conditions below are not met: \n- Wifi is enabled\n- Location permissions are granted\n- Internet is available\n- Connected wifi and device supports IEEE 802.11mc");
        builder.setCancelable(true);
        builder.setPositiveButton("Got it", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which)
            {
                dialog.cancel();
            }
        });
        AlertDialog alertDialog = builder.create();
        alertDialog.show();

        //Toggle to lock/unlock settings
        settingsLock.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if (isChecked) {
                    applySettings.setVisibility(View.GONE);
                    minDistance.setEnabled(false);
                    maxDistance.setEnabled(false);
                    minVolume.setEnabled(false);
                    maxVolume.setEnabled(false);
                } else {
                    minDistance.setEnabled(true);
                    maxDistance.setEnabled(true);
                    minVolume.setEnabled(true);
                    maxVolume.setEnabled(true);
                    applySettings.setVisibility(View.VISIBLE);
                }
            }
        });

        //Device permissions check
        //Log.d("Permissions [ACCESS_WIFI_STATE]:", String.valueOf(checkSelfPermission(Manifest.permission.ACCESS_WIFI_STATE) == PackageManager.PERMISSION_GRANTED));
        //Log.d("Permissions [ACCESS_NETWORK_STATE]:", String.valueOf(checkSelfPermission(Manifest.permission.ACCESS_NETWORK_STATE) == PackageManager.PERMISSION_GRANTED));
        //Log.d("Permissions [ACCESS_FINE_LOCATION]:", String.valueOf(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED));
        //Log.d("Permissions [ACCESS_COARSE_LOCATION]:", String.valueOf(checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED));

        //Initialize or load saved settings values
        sharedPreferences = getPreferences(Context.MODE_PRIVATE);
        minDistance.setText("" + sharedPreferences.getInt("minDistance", 1000));
        minVolume.setText("" + sharedPreferences.getInt("minVolume", 20));
        maxDistance.setText("" + sharedPreferences.getInt("maxDistance", 7000));
        maxVolume.setText("" + sharedPreferences.getInt("maxVolume", 100));
        if (sharedPreferences.getAll().isEmpty()) {
            SharedPreferences.Editor editor = sharedPreferences.edit();
            editor.putInt("minDistance", 1000);
            editor.putInt("minVolume", 20);
            editor.putInt("maxDistance", 7000);
            editor.putInt("maxVolume", 100);
            editor.apply();
        }

        //Declare app managers
        wifiRttManager = (WifiRttManager) getSystemService(Context.WIFI_RTT_RANGING_SERVICE);
        wifiManager = (WifiManager) getSystemService(Context.WIFI_SERVICE);

        //Check if WifiManager and WifiRTTManager is available and enabled
        //if (wifiRttManager.isAvailable()) {
        //    Log.d("Wifi [RTT Manager]", "Available");
        //} else {
        //    Log.d("Wifi [RTT Manager]", "NOT available");
        //}

        //if (wifiManager.isWifiEnabled()) {
        //    Log.d("Wifi [Manager]", "Available");
        //} else {
        //    Log.d("Wifi [Manager]", "NOT available");
        //}

        //Check and request FINE_LOCATION permission for indoor position
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            //No explanation needed, we can request the permission.
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.ACCESS_FINE_LOCATION},99);
        }

        //Get currently connected access point information
        WifiInfo wifiInfo = wifiManager.getConnectionInfo();
        Log.d("Wifi [Info]", wifiInfo.toString());
        String currentAPssid = wifiInfo.getSSID();
        String currentAPbssid = wifiInfo.getBSSID();
        networkSSID.setText(currentAPssid);

        //Scan nearby wifi, this is to retrieve a ScanResult object of the connected AP >>>>>>>>>>>>
        List<ScanResult> scanResults =  wifiManager.getScanResults();
        int scanMaxAttempt = 5;
        while (scanResults == null && scanMaxAttempt > 0) {
            Toast.makeText(getApplicationContext(), "Could not detect nearby access points. Retrying...", Toast.LENGTH_SHORT)
                    .show();
            scanMaxAttempt--;
        }
        if (scanResults == null) {
            Toast.makeText(getApplicationContext(), "Could not detect nearby access points. Application will exit.", Toast.LENGTH_SHORT)
                    .show();
            finish();
        } else {
            Log.d("List<ScanResult> ", scanResults.toString());
        }
        // >>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>

        List<ScanResult> targetScanResult = new ArrayList<ScanResult>();
        for (ScanResult sr : scanResults) {
            if (sr.BSSID.equals(currentAPbssid)) {
                targetScanResult.add(sr);
            }
        }
        connectedScanResult = targetScanResult.get(0);
        //Log.d("Target Access Point", connectedScanResult.toString());

        //AWS Authentication
        //Initialize the Amazon Cognito credentials provider
        CognitoUserPool userPool = new CognitoUserPool(
                getApplicationContext(),
                "us-east-2_U3dgk4tQp",
                "57gieknu2dsu5jfou5j3kjvicr",
                "",
                Regions.US_EAST_2
                );
        CognitoUser cognitoUser = userPool.getUser("pixel3"); //select this user

        AuthenticationHandler authHandler = new AuthenticationHandler() {
            @Override
            public void onSuccess(CognitoUserSession userSession, CognitoDevice newDevice) {
                Log.d("MQTT", "success");
                awsToken = userSession.getIdToken().getJWTToken();
            }

            @Override
            public void getAuthenticationDetails(AuthenticationContinuation authenticationContinuation, String userId) {
                Log.d("MQTT", "Auth details");
                AuthenticationDetails authDetails = new AuthenticationDetails("pixel3", "google", null);
                // Pass the user sign-in credentials to the continuation
                authenticationContinuation.setAuthenticationDetails(authDetails);
                // Allow the sign-in to continue
                authenticationContinuation.continueTask();
            }

            @Override
            public void getMFACode(MultiFactorAuthenticationContinuation continuation) {}

            @Override
            public void authenticationChallenge(ChallengeContinuation continuation) {}

            @Override
            public void onFailure(Exception exception) {
                // Sign-in failed, check exception for the cause
                Log.d("MQTT sign-in failed", exception.toString());
            }
        };

        cognitoUser.getSessionInBackground(authHandler);

        //Initialize the AWSIotMqttManager with the configuration
        mqttManager = new AWSIotMqttManager(
                "pixel3",
                "a1mf7ud1h3g6uv-ats.iot.us-east-2.amazonaws.com");

        //Initialize the Amazon Cognito credentials provider
        CognitoCachingCredentialsProvider credentialsProvider = new CognitoCachingCredentialsProvider(
                getApplicationContext(),
                "us-east-2:497b8858-59f9-4d2d-ac4b-03c7dddc3eba", // Identity pool ID
                Regions.US_EAST_2 // Region
        );

        Map<String, String> login = new HashMap<String, String>();
        login.put("cognito-idp.us-east-1.amazonaws.com/us-east-2_U3dgk4tQp", awsToken);
        credentialsProvider.setLogins(login);

        //Connect to AWS Iot MQTT client
        mqttManager.connect(credentialsProvider, new AWSIotMqttClientStatusCallback(){
            @Override
            public void onStatusChanged(AWSIotMqttClientStatus status, Throwable throwable) {
                Log.i("MQTT Connection Status",  status.toString());
            }
        });

        appRttRangingResultCallback = new RttRangingResultCallback();

        //Buttons listeners
        makeRequest.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                JSONObject settings = new JSONObject(sharedPreferences.getAll());
                publishTo("adaptivev/settings", settings.toString());
                subscribeTo("adaptivev/active/volume");
                repeatReq = true;
                startRangingRequest();
                makeRequest.setVisibility(View.INVISIBLE);
                stopRequest.setVisibility(View.VISIBLE);
            }
        });

        stopRequest.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                repeatReq = false;
                mqttManager.unsubscribeTopic("adaptivev/active/volume");
                stopRequest.setVisibility(View.INVISIBLE);
                makeRequest.setVisibility(View.VISIBLE);
                //reset the view
                Handler handler = new Handler();
                handler.postDelayed(new Runnable() {
                    public void run() {
                        rtt_range.setText("n/a");
                        volume.setText("n/a");
                        rangeRequestCount = 0;
                        mqttSent = 0;
                        rtt_request_count.setText("[Request count: 0]");
                    }
                }, rangingRequestDelay + 500);
                Log.i("mqtt sent count", "" + mqttSent);
            }
        });

        applySettings.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                //update settings in SharedPreferences
                SharedPreferences.Editor editor = sharedPreferences.edit();
                editor.putInt("minDistance", Integer.parseInt(minDistance.getText().toString()));
                editor.putInt("minVolume", Integer.parseInt(minVolume.getText().toString()));
                editor.putInt("maxDistance", Integer.parseInt(maxDistance.getText().toString()));
                editor.putInt("maxVolume", Integer.parseInt(maxVolume.getText().toString()));
                editor.apply();
                //publish updated settings to audio source
                JSONObject settings = new JSONObject(sharedPreferences.getAll());
                publishTo("adaptivev/settings", settings.toString());
            }
        });
    }

    //Subscribe to the specified MQTT topic
    private void subscribeTo(String topic) {
        try {
            mqttManager.subscribeToTopic(topic, AWSIotMqttQos.QOS0 /* Quality of Service */,
                    new AWSIotMqttNewMessageCallback() {
                        @Override
                        public void onMessageArrived(final String topic, final byte[] data) {
                            if (topic.equals("adaptivev/active/volume")) {
                                String message = new String(data);
                                volume.setText(message);
                            }
                        }
                    });
        } catch (Exception e) {
            Log.e("MQTT", "Subscription error: ", e);
        }
    }

    //Publish to the specified MQTT topic with QoS = 0
    private void publishTo(String topic, String msgValue) {
        mqttManager.publishString(msgValue, topic, AWSIotMqttQos.QOS0);
    }

    //Starting ranging requests with the connected access point
    private void startRangingRequest() {
        //Perform permission check again before attempting to make ranging requests
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            onPause();
            stopRequest.performClick();
        }
        rangeRequestCount++;
        RangingRequest rangingRequest = new RangingRequest.Builder().addAccessPoint(connectedScanResult).build();
        wifiRttManager.startRanging(rangingRequest, getApplication().getMainExecutor(), appRttRangingResultCallback);
    }

     //Class that handles callbacks for all RangingRequests and issues new RangingRequests.
    private class RttRangingResultCallback extends RangingResultCallback {
        private static final String TAG = "RTT Result Callback";

         private void queueNextRangingRequest() {
             Handler handler = new Handler();
             handler.postDelayed(new Runnable() {
                 public void run() {
                     startRangingRequest();
                 }
             }, rangingRequestDelay);
         }

        @Override
        public void onRangingFailure(int code) {
            //Log.d(TAG, "onRangingFailure() code: " + code);
            queueNextRangingRequest();
        }

        @Override
        public void onRangingResults(@NonNull List<RangingResult> list) {
            // Because we are only requesting RangingResult for one access point (not multiple
            // access points), this will only ever be one. (Use loops when requesting RangingResults
            // for multiple access points.)
            if (list.size() == 1) {
                RangingResult rangingResult = list.get(0);
                int mmDistance = rangingResult.getDistanceMm();
                int mmStdDev = rangingResult.getDistanceStdDevMm();
                try {
                    //send range distance as MQTT message (mm)

//                    if (prevRange <= mmDistance - mmStdDev || prevRange >= mmDistance + mmStdDev) {
                        publishTo("adaptivev/active/distance", String.valueOf(mmDistance));
                        publishTo("adaptivev/active/distanceSD", String.valueOf(mmStdDev));
                        prevRange = mmDistance;
                        mqttSent++;
//                    }
                } catch (Exception e) {}

                //update UI
                rtt_range.setText((mmDistance / 1000f) + "");
                rtt_request_count.setText(new StringBuilder().append("[Request count: ").append(rangeRequestCount).append("]").toString());
            } else {
                Toast.makeText(
                        getApplicationContext(),
                        "R.string.mac_mismatch_message_activity_access_point_ranging_results",
                        Toast.LENGTH_LONG)
                        .show();
            }
            if (repeatReq) {
                queueNextRangingRequest();
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try {
            mqttManager.disconnect();
        } catch (Exception e) {}
    }
}


