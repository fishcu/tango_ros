/*
 * Copyright 2016 Intermodalics All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package eu.intermodalics.tangoxros;

import android.app.FragmentManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.net.wifi.WifiManager;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.IBinder;
import android.text.format.Formatter;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import org.ros.address.InetAddressFactory;
import org.ros.android.RosActivity;
import org.ros.node.NodeConfiguration;
import org.ros.node.NodeMain;
import org.ros.node.NodeMainExecutor;

import java.net.URI;

public class MainActivity extends RosActivity implements SetMasterUriDialog.CallbackListener,
        TryToReconnectToRosDialog.CallbackListener {
    private static final String TAG = MainActivity.class.getSimpleName();
    private static final String MASTER_URI_PREFIX = "__master:=";
    private static final String IP_PREFIX = "__ip:=";

    private JNIInterface mJniInterface;
    private String mMasterUri;
    private boolean mIsNodeInitialised = false;
    private PublisherConfiguration mPublishConfig;

    public MainActivity() {
        super("Example", "Example");
    }

    protected MainActivity(String notificationTicker, String notificationTitle) {
        super(notificationTicker, notificationTitle);
    }

    /**
     * Implements SetMasterUriDialog.CallbackListener.
     */
    @Override
    public void onMasterUriConnect(String uri) {
        mMasterUri = uri;
        // Update view.
        TextView uriTextView;
        uriTextView = (TextView) findViewById(R.id.master_uri);
        uriTextView.setText(mMasterUri);
        // Save URI preference.
        SharedPreferences sharedPref = this.getPreferences(Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = sharedPref.edit();
        editor.putString(getString(R.string.saved_uri_key), mMasterUri);
        editor.commit();

        // Start ROS and node.
        init();
        startNode();

        // Start sample node with RJ interface.
        initAndStartRJNode();
    }

    /**
     * Implements TryToReconnectToRosDialog.CallbackListener.
     */
    @Override
    public void onTryToReconnectToRos() {
        init();
        startNode();
    }

    /**
     * Shows a dialog for setting the Master URI.
     */
    private void showSetMasterUriDialog() {
        // Get URI preference or default value if does not exist.
        SharedPreferences sharedPref = this.getPreferences(Context.MODE_PRIVATE);
        String uriValue = sharedPref.getString(getString(R.string.saved_uri_key),
                getResources().getString(R.string.saved_uri_default));
        Bundle bundle = new Bundle();
        bundle.putString(getString(R.string.saved_uri_key), uriValue);
        FragmentManager manager = getFragmentManager();
        SetMasterUriDialog setMasterUriDialog = new SetMasterUriDialog();
        setMasterUriDialog.setArguments(bundle);
        setMasterUriDialog.show(manager, "MatserUriDialog");
    }

    /**
     * Shows a dialog for trying to reconnect to ros master.
     */
    private void showTryToReconnectToRosDialog() {
        SharedPreferences sharedPref = this.getPreferences(Context.MODE_PRIVATE);
        String uriValue = sharedPref.getString(getString(R.string.saved_uri_key),
                getResources().getString(R.string.saved_uri_default));
        Bundle bundle = new Bundle();
        bundle.putString(getString(R.string.saved_uri_key), uriValue);
        FragmentManager manager = getFragmentManager();
        TryToReconnectToRosDialog setTryToReconnectToRosDialog = new TryToReconnectToRosDialog();
        setTryToReconnectToRosDialog.setArguments(bundle);
        setTryToReconnectToRosDialog.show(manager, "TryToReconnectToRosDialog");
    }

    /**
     * Tango Service connection.
     */
    ServiceConnection mTangoServiceConnection = new ServiceConnection() {
        public void onServiceConnected(ComponentName name, IBinder service) {
            // Synchronization around MainActivity object is to avoid
            // Tango disconnect in the middle of the connecting operation.
            if(!mJniInterface.onTangoServiceConnected(service)) {
                Log.e(TAG, getResources().getString(R.string.tango_service_error));
                Toast.makeText(getApplicationContext(), R.string.tango_service_error, Toast.LENGTH_SHORT).show();
                onDestroy();
            }
        }

        public void onServiceDisconnected(ComponentName name) {
            // Handle this if you need to gracefully shutdown/retry
            // in the event that Tango itself crashes/gets upgraded while running.
        }
    };

    public boolean initNode() {
        // Update publisher configuration according to current preferences.
        PrefsFragment prefsFragment = (PrefsFragment) getFragmentManager().findFragmentById(R.id.preferencesFrame);
        mPublishConfig = prefsFragment.getPublisherConfigurationFromPreferences();
        if(!mJniInterface.initNode(this, mPublishConfig)) {
            Log.e(TAG, getResources().getString(R.string.tango_node_error));
            Toast.makeText(getApplicationContext(), R.string.tango_node_error, Toast.LENGTH_SHORT).show();
            return false;
        }
        return true;
    }

    public void init() {
        if (mMasterUri != null) {
            WifiManager wm = (WifiManager) getSystemService(WIFI_SERVICE);
            String ip_address = Formatter.formatIpAddress(wm.getConnectionInfo().getIpAddress());
            if (mJniInterface.initRos(MASTER_URI_PREFIX + mMasterUri, IP_PREFIX + ip_address)) {
                mIsNodeInitialised = initNode();
            } else {
                Log.e(TAG, getResources().getString(R.string.tango_ros_error));
                Toast.makeText(getApplicationContext(), R.string.tango_ros_error, Toast.LENGTH_SHORT).show();
                showTryToReconnectToRosDialog();
            }
        } else {
            Log.e(TAG, "Master URI is null");
        }
    }

    public void startNode() {
        if (mIsNodeInitialised) {
            TangoInitializationHelper.bindTangoService(this, mTangoServiceConnection);
            new Thread(new Runnable() {
                @Override
                public void run() {
                    while (mJniInterface.isRosOk()) {
                        mJniInterface.publish();
                    }
                }
            }).start();
        }
    }

    public void applySettings() {
        onPause();
        mIsNodeInitialised = initNode();
        if (mIsNodeInitialised) {
            startNode();
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main_activity);
        mPublishConfig = new PublisherConfiguration();
        getFragmentManager().beginTransaction().replace(R.id.preferencesFrame, new PrefsFragment()).commit();
        // Set callback for apply button.
        Button buttonApply = (Button)findViewById(R.id.apply);
        buttonApply.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v){
                applySettings();
            }
        });
        // Request master URI from user.
        showSetMasterUriDialog();
    }

    @Override
    protected void onResume() {
        super.onResume();
        startNode();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (mIsNodeInitialised) {
            mJniInterface.tangoDisconnect();
            unbindService(mTangoServiceConnection);
        }
    }

    @Override
    protected void init(NodeMainExecutor nodeMainExecutor) {

        // Create common configuration for nodes to be created
        NodeConfiguration nodeConfiguration = NodeConfiguration.newPublic(InetAddressFactory.newNonLoopback().getHostAddress());
        nodeConfiguration.setMasterUri(this.nodeMainExecutorService.getMasterUri());

        // Standard Rosjava node creation and execution
        nodeConfiguration.setNodeName("SampleNode");
        NodeMain node = new SimplePublisherNode();
        nodeMainExecutor.execute(node, nodeConfiguration);

        // TangoRos node creation and execution
        // Creation and execution of the TangoRosNode would be the same as a RosJava Node.
        // Native "execute" function shall deal with initializing Ros, the node itself, and then start a running loop.

        // nodeConfiguration.setNodeName("TangoRosNode");
        // NodeMain tangoNode = new TangoRosNode();
        // nodeMainExecutor.execute(tangoNode, nodeConfiguration);
    }

    // This function allows initialization of the node with RosJava interface without using MasterChooser,
    // and is compatible with current Master Uri setter interface.
    private void initAndStartRJNode() {
        Log.i(TAG, "Starting node with RosJava interface");

        if (mMasterUri != null) {
            URI masterUri;

            try {
                masterUri = URI.create(mMasterUri);
            } catch (IllegalArgumentException e){
                Log.e(TAG, "Wrong URI: " + e.getMessage());
                return;
            }

            this.nodeMainExecutorService.setMasterUri(masterUri);

            new AsyncTask<Void, Void, Void>() {
                @Override
                protected Void doInBackground(Void... params) {
                    MainActivity.this.init(nodeMainExecutorService);
                    return null;
                }
            }.execute();

        } else {
            Log.e(TAG, "Master URI is null");
        }
    }

    @Override
    public void startMasterChooser() {
        // onMasterUriConnect already connects to master; overriding this method with an empty one prevents MasterChooser from running.
    }
}