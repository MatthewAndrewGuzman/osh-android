/*************************** BEGIN LICENSE BLOCK ***************************

The contents of this file are subject to the Mozilla Public License, v. 2.0.
If a copy of the MPL was not distributed with this file, You can obtain one
at http://mozilla.org/MPL/2.0/.

Software distributed under the License is distributed on an "AS IS" basis,
WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
for the specific language governing rights and limitations under the License.
 
Copyright (C) 2012-2015 Sensia Software LLC. All Rights Reserved.
 
******************************* END LICENSE BLOCK ***************************/

package org.sensorhub.android;

import java.net.MalformedURLException;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import org.sensorhub.android.comm.BluetoothCommProvider;
import org.sensorhub.android.comm.BluetoothCommProviderConfig;
import org.sensorhub.android.comm.ble.BleConfig;
import org.sensorhub.android.comm.ble.BleNetwork;
import org.sensorhub.api.common.Event;
import org.sensorhub.api.common.IEventListener;
import org.sensorhub.api.module.IModuleConfigRepository;
import org.sensorhub.api.module.ModuleConfig;
import org.sensorhub.api.module.ModuleEvent;
import org.sensorhub.api.sensor.ISensorDataInterface;
import org.sensorhub.api.sensor.SensorConfig;
import org.sensorhub.impl.client.sost.SOSTClient;
import org.sensorhub.impl.client.sost.SOSTClient.StreamInfo;
import org.sensorhub.impl.client.sost.SOSTClientConfig;
import org.sensorhub.impl.common.EventBus;
import org.sensorhub.impl.driver.flir.FlirOneCameraConfig;
import org.sensorhub.impl.module.InMemoryConfigDb;
import org.sensorhub.impl.sensor.android.AndroidSensorsConfig;
import org.sensorhub.impl.sensor.angel.AngelSensorConfig;
import org.sensorhub.impl.sensor.trupulse.TruPulseConfig;
import org.sensorhub.test.sensor.trupulse.SimulatedDataStream;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.preference.PreferenceManager;
import android.text.Html;
import android.view.Menu;
import android.view.MenuItem;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.widget.EditText;
import android.widget.TextView;


public class MainActivity extends Activity implements SurfaceHolder.Callback, IEventListener
{
    TextView textArea;
    SensorHubService boundService;
    IModuleConfigRepository sensorhubConfig;
    Handler displayHandler;
    StringBuffer displayText = new StringBuffer();
    SurfaceHolder camPreviewSurfaceHolder;    
    ArrayList<SOSTClient> sostClients = new ArrayList<SOSTClient>();
    URL sosUrl = null;
    
    
    private ServiceConnection sConn = new ServiceConnection()
    {
        public void onServiceConnected(ComponentName className, IBinder service)
        {
            boundService = ((SensorHubService.LocalBinder) service).getService();
        }


        public void onServiceDisconnected(ComponentName className)
        {
            boundService = null;
        }
    };


    @SuppressLint("HandlerLeak")
    @Override
    protected void onCreate(Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        textArea = (TextView) findViewById(R.id.text);
        SurfaceView camPreview = (SurfaceView) findViewById(R.id.textureView1);
        camPreview.getHolder().addCallback(this);

        displayHandler = new Handler(Looper.getMainLooper())
        {
            public void handleMessage(Message msg)
            {
                String displayText = (String)msg.obj;
                textArea.setText(Html.fromHtml(displayText));
            }
        };
    }


    @Override
    protected void onStart()
    {
        super.onStart();
        
        // bind to SensorHub service
        Intent intent = new Intent(this, SensorHubService.class);
        bindService(intent, sConn, Context.BIND_AUTO_CREATE);
    }


    protected void updateConfig(SharedPreferences prefs, String runName)
    {
        sensorhubConfig = new InMemoryConfigDb();
        
        // get SOS URL from config
        String sosUriConfig = prefs.getString("sos_uri", "");
        if (sosUriConfig != null && sosUriConfig.trim().length() > 0)
        {
            try
            {
                sosUrl = new URL(sosUriConfig);
            }
            catch (MalformedURLException e)
            {
                e.printStackTrace();
            }
        }
        
        // get device name
        String deviceName = prefs.getString("device_name", null);
        if (deviceName == null || deviceName.length() < 2)
            deviceName = Build.SERIAL;
        
        // Android sensors
        AndroidSensorsConfig sensorsConfig = new AndroidSensorsConfig();
        sensorsConfig.name = "Android Sensors [" + deviceName + "]";
        sensorsConfig.id = "ANDROID_SENSORS";
        sensorsConfig.autoStart = true;
        sensorsConfig.activateAccelerometer = prefs.getBoolean("accel_enabled", false);
        sensorsConfig.activateGyrometer = prefs.getBoolean("gyro_enabled", false);
        sensorsConfig.activateMagnetometer = prefs.getBoolean("mag_enabled", false);
        sensorsConfig.activateOrientationQuat = prefs.getBoolean("orient_quat_enabled", false);
        sensorsConfig.activateOrientationEuler = prefs.getBoolean("orient_euler_enabled", false);
        sensorsConfig.activateGpsLocation = prefs.getBoolean("gps_enabled", false);
        sensorsConfig.activateNetworkLocation = prefs.getBoolean("netloc_enabled", false);
        sensorsConfig.activateBackCamera = prefs.getBoolean("cam_enabled", false);
        sensorsConfig.videoCodec = prefs.getString("video_codec", AndroidSensorsConfig.JPEG_CODEC);
        sensorsConfig.androidContext = this.getApplicationContext();
        sensorsConfig.camPreviewSurfaceHolder = this.camPreviewSurfaceHolder;
        sensorsConfig.runName = runName;
        sensorhubConfig.add(sensorsConfig);
        addSosTConfig(sensorsConfig);
                
        // TruPulse sensor
        boolean enabled = prefs.getBoolean("trupulse_enabled", false);
        if (enabled)
        {
            TruPulseConfig trupulseConfig = new TruPulseConfig();
            trupulseConfig.id = "TRUPULSE_SENSOR";
            trupulseConfig.name = "TruPulse Range Finder [" + deviceName + "]";
            trupulseConfig.autoStart = true;
            BluetoothCommProviderConfig btConf = new BluetoothCommProviderConfig();
            btConf.protocol.deviceName = "TP360RB.*";
            if (prefs.getBoolean("trupulse_simu", false))
                btConf.moduleClass = SimulatedDataStream.class.getCanonicalName();
            else
                btConf.moduleClass = BluetoothCommProvider.class.getCanonicalName();
            trupulseConfig.commSettings = btConf;
            sensorhubConfig.add(trupulseConfig);
            addSosTConfig(trupulseConfig);
        }
        
        // AngelSensor
        enabled = prefs.getBoolean("angel_enabled", false);
        if (enabled)
        {
            BleConfig bleConf = new BleConfig();
            bleConf.id = "BLE";
            bleConf.moduleClass = BleNetwork.class.getCanonicalName();
            bleConf.androidContext = this.getApplicationContext();
            bleConf.autoStart = true;
            sensorhubConfig.add(bleConf);
            
            AngelSensorConfig angelConfig = new AngelSensorConfig();
            angelConfig.id = "ANGEL_SENSOR";
            angelConfig.name = "Angel Sensor [" + deviceName + "]";
            angelConfig.autoStart = true;
            angelConfig.networkID = bleConf.id;
            //angelConfig.btAddress = "00:07:80:79:04:AF";
            angelConfig.btAddress = "00:07:80:03:0E:0A";
            sensorhubConfig.add(angelConfig);
            addSosTConfig(angelConfig);
        }
        
        // FLIR One sensor
        enabled = prefs.getBoolean("flirone_enabled", false);
        if (enabled)
        {
            FlirOneCameraConfig flironeConfig = new FlirOneCameraConfig();
            flironeConfig.id = "FLIRONE_SENSOR";
            flironeConfig.name = "FLIR One Camera [" + deviceName + "]";
            flironeConfig.autoStart = true;
            flironeConfig.androidContext = this.getApplicationContext();
            flironeConfig.camPreviewSurfaceHolder = this.camPreviewSurfaceHolder;            
            sensorhubConfig.add(flironeConfig);
            addSosTConfig(flironeConfig);
        }
    }
    
    
    protected void addSosTConfig(SensorConfig sensorConf)
    {
        if (sosUrl == null)
            return;
        
        SOSTClientConfig sosConfig = new SOSTClientConfig();
        sosConfig.id = sensorConf.id.replace("SENSOR", "SOST");
        sosConfig.name = sensorConf.name.replaceAll("\\[.*\\]", "") + "SOS-T Client";
        sosConfig.autoStart = true;
        sosConfig.sensorID = sensorConf.id;
        sosConfig.sos.remoteHost = sosUrl.getHost();
        sosConfig.sos.remotePort = sosUrl.getPort();
        sosConfig.sos.resourcePath = sosUrl.getPath();
        sosConfig.connection.connectTimeout = 5000;
        sosConfig.connection.usePersistentConnection = true;
        sosConfig.connection.reconnectAttempts = 9;
        sensorhubConfig.add(sosConfig);
    }


    @Override
    public boolean onCreateOptionsMenu(Menu menu)
    {
        getMenuInflater().inflate(R.menu.main, menu);
        return true;
    }


    @Override
    public boolean onOptionsItemSelected(MenuItem item)
    {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        int id = item.getItemId();
        if (id == R.id.action_settings)
        {
            startActivity(new Intent(this, UserSettingsActivity.class));
            return true;
        }
        else if (id == R.id.action_start)
        {
            if (boundService != null)
            {
                boundService.stopSensorHub();
                showRunNamePopup();
            }
            return true;
        }
        else if (id == R.id.action_stop)
        {
            sostClients.clear();
            if (boundService != null)
                boundService.stopSensorHub();
            newStatusMessage("SensorHub Stopped");
            return true;
        }

        return super.onOptionsItemSelected(item);
    }
    
    
    protected void showRunNamePopup()
    {
        AlertDialog.Builder alert = new AlertDialog.Builder(this);

        alert.setTitle("Run Name");
        alert.setMessage("Please enter the name for this run");
        
        // Set an EditText view to get user input
        final EditText input = new EditText(this);
        input.getText().append("Run-");
        SimpleDateFormat formatter = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US);
        input.getText().append(formatter.format(new Date()));
        alert.setView(input);

        alert.setPositiveButton("Ok", new DialogInterface.OnClickListener()
        {
            public void onClick(DialogInterface dialog, int whichButton)
            {
                String runName = input.getText().toString();
                updateConfig(PreferenceManager.getDefaultSharedPreferences(MainActivity.this), runName);
                newStatusMessage("Waiting for SensorHub service to start...");
                sostClients.clear();
                boundService.startSensorHub(sensorhubConfig);
                startListeningForEvents();                
            }
        });

        alert.setNegativeButton("Cancel", new DialogInterface.OnClickListener() {
          public void onClick(DialogInterface dialog, int whichButton) {
          }
        });

        alert.show();
    }
    
    
    protected void newStatusMessage(String msg)
    {
        displayText.setLength(0);
        appendStatusMessage(msg);
    }
    
    
    protected void appendStatusMessage(String msg)
    {
        displayText.append(msg);
        displayHandler.obtainMessage(1, displayText.toString()).sendToTarget();
    }
    
    
    protected void displaySosStatus()
    {
        displayText.setLength(0);
        
        for (SOSTClient client: sostClients)
        {
            displayText.append("<p>" + client.getName() + ":<br/>");
            
            Map<ISensorDataInterface, StreamInfo> dataStreams = client.getDataStreams();
            if (dataStreams.size() == 0 && client.getStatusMessage() != null)
                displayText.append(client.getStatusMessage() + "<br/>");
            if (client.getCurrentError() != null)
                displayText.append("<font color='red'>" + client.getCurrentError().getMessage() + "</font><br/>");
                        
            long now = System.currentTimeMillis();            
            for (Entry<ISensorDataInterface, StreamInfo> stream : dataStreams.entrySet())
            {
                displayText.append("<b>" + stream.getKey().getName() + " : </b>");

                if (now - stream.getValue().lastEventTime > 2000)
                    displayText.append("<font color='red'>NOK</font>");
                else
                    displayText.append("<font color='green'>OK</font>");

                if (stream.getValue().errorCount > 0)
                {
                    displayText.append("<font color='red'> (");
                    displayText.append(stream.getValue().errorCount);
                    displayText.append(")</font>");
                }

                displayText.append("<br/>");
            }
            
            displayText.append("</p>");
        }
        
        displayHandler.obtainMessage(1, displayText.toString()).sendToTarget();
    }
    
    
    @Override
    public void handleEvent(Event<?> e)
    {
        if (e instanceof ModuleEvent)
        {
            // when SOS-T are connected
            if (e.getSource() instanceof SOSTClient)
            {
                SOSTClient client = (SOSTClient)e.getSource();
                
                // whenever the SOS-T client is connected
                if (((ModuleEvent)e).getType() == ModuleEvent.Type.STATE_CHANGED)
                {
                    switch (((ModuleEvent)e).getNewState())
                    {
                        case STARTING:
                            sostClients.add(client);
                            break;
                            
                        case STARTED:                            
                            displaySosStatus();
                            break;
                            
                        default:
                            return;
                    }
                }
                
                else if (((ModuleEvent)e).getType() == ModuleEvent.Type.ERROR)
                {
                    displaySosStatus();
                }
            }
        }        
    }
    
    
    protected void startListeningForEvents()
    {
        if (boundService == null || boundService.getSensorHub() == null)
            return;
        
        EventBus eventBus = boundService.getSensorHub().getEventBus();
        for (ModuleConfig config: sensorhubConfig.getAllModulesConfigurations())
            eventBus.registerListener(config.id, EventBus.MAIN_TOPIC, this);
    }
    
    
    protected void stopListeningForEvents()
    {
        if (boundService == null || boundService.getSensorHub() == null)
            return;
        
        EventBus eventBus = boundService.getSensorHub().getEventBus();
        for (ModuleConfig config: sensorhubConfig.getAllModulesConfigurations())
            eventBus.unregisterListener(config.id, EventBus.MAIN_TOPIC, this);
    }


    @Override
    protected void onResume()
    {
        super.onResume();        
        startListeningForEvents();       
    }


    @Override
    protected void onPause()
    {
        stopListeningForEvents();
        super.onPause();
    }


    @Override
    protected void onDestroy()
    {
        stopListeningForEvents();
        stopService(new Intent(this, SensorHubService.class));
        super.onDestroy();
    }


    @Override
    public void surfaceCreated(SurfaceHolder holder)
    {
        this.camPreviewSurfaceHolder = holder;

        /*SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        SharedPreferences.Editor editor = prefs.edit();
        editor.clear();
        editor.commit();
        PreferenceManager.setDefaultValues(this, R.xml.pref_general, true);*/
    }


    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height)
    {
    }


    @Override
    public void surfaceDestroyed(SurfaceHolder holder)
    {
    }    
}
