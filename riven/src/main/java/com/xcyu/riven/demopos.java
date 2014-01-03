package com.xcyu.riven;

import android.animation.ObjectAnimator;
import android.app.Activity;
import android.app.NotificationManager;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Intent;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Vibrator;
import android.support.v4.app.NotificationCompat;
import android.util.Log;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.UUID;

public class demopos extends Activity {
    //Bluetooth stuff;
    TextView myLabel, banner;
    ImageView aniView;
    EditText myTextbox;
    BluetoothAdapter mBluetoothAdapter;
    BluetoothSocket mmSocket;
    BluetoothDevice mmDevice;
    OutputStream mmOutputStream;
    InputStream mmInputStream;
    Thread workerThread;
    byte[] readBuffer;
    int readBufferPosition;
    int counter;
    short x, y, z;
    short temperature;//temperature sensor
    short gx, gy, gz;//gyroscope
    double[] rv = {0, 0, 1};// initial ideal pos refvec
    double[] rvreg = {0, 0, 1};//register for temparary ref vec
    volatile boolean stopWorker;
    boolean btFound = false;
    private static final int REQUEST_ENABLE_BT = 0;
    private static final int REQUEST_CONNECT_DEVICE = 1;
    private static final boolean D = true;
    private static final String TAG = "BluetoothSensor";
    static final float ALPHA = 0.15f; //this is used as parameter for low-pass filter
    double[] cvOld = null;

    enum posState {GOOD, BAD, DANGER, ERROR, INIT}

    ;
    posState ps, psOld;
    long[] vbPattern1 = {0, 200, 500};
    long[] vbPattern2 = {0, 400, 100, 200};


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        float dest = 0;//used by animation
        super.onCreate(savedInstanceState);
        setContentView(R.layout.demopos_main);
        aniView = (ImageView) findViewById(R.id.imageView1);
        Button openButton = (Button) findViewById(R.id.open);
        Button adjustButton = (Button) findViewById(R.id.adjust);
        Button closeButton = (Button) findViewById(R.id.close);
        myLabel = (TextView) findViewById(R.id.label);
        banner = (TextView) findViewById(R.id.banner);

//        myTextbox = (EditText)findViewById(R.id.entry);
        //Open Button
        openButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                findBT();
                if (btFound) {
                    myLabel.setText("Bluetooth Device Found");
                }
            }
        });
        //adjust Button
        adjustButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {

                adjust();

            }
        });
        //Close button
        closeButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                try {
                    closeBT();
                } catch (IOException ex) {
                }
            }
        });

    }

    //use vector angel method. a.b=\a\*\b\*cos(angle(a,b)) to calculate angle.
    double getAngle(double[] initialVec, double[] currentVec) {
        double m1, m2, d;//2 mode for both vector. and dot product between two vec：d;
        m1 = m2 = d = 0;
        int i = 0;
        while (i < initialVec.length) {
            m1 += Math.pow(initialVec[i], 2);
            m2 += Math.pow(currentVec[i], 2);
            d += initialVec[i] * currentVec[i];
            i++;
        }
        return Math.toDegrees(Math.acos(d / (Math.sqrt(m1 * m2))));
    }

    //
    // low-pass filter for accelarometer.
    // @see http://en.wikipedia.org/wiki/Low-pass_filter#Algorithmic_implementation
    // @see http://developer.android.com/reference/android/hardware/SensorEvent.html#values
    //
    protected double[] lowPass(double[] input, double[] output) {
        if (output == null) return input;

        for (int i = 0; i < input.length; i++) {
            output[i] = output[i] + ALPHA * (input[i] - output[i]);
        }
        return output;
    }

    void findBT() {
        Intent serverIntent = null;
        mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        if (mBluetoothAdapter == null) {
            myLabel.setText("No bluetooth adapter available");
        }
//
        if (!mBluetoothAdapter.isEnabled()) {
            Intent enableBluetooth = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            startActivityForResult(enableBluetooth, REQUEST_ENABLE_BT);
        } else {
            serverIntent = new Intent(this, DeviceListActivity.class);
            startActivityForResult(serverIntent, REQUEST_CONNECT_DEVICE);
        }

    }

    void adjust() {
        rv = rvreg;
        ps = posState.GOOD;//kick it into good state after adjust.
    }

    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (D) Log.d(TAG, "onActivityResult " + resultCode);
        switch (requestCode) {
            case REQUEST_CONNECT_DEVICE:
                // When DeviceListActivity returns with a device to connect
                if (resultCode == Activity.RESULT_OK) {
                    connectDevice(data, true);
                }
                break;
            case REQUEST_ENABLE_BT:
                // When the request to enable Bluetooth returns
                if (resultCode == Activity.RESULT_OK) {
                    // Bluetooth is now enabled, so set up a chat session
                    findBT();
                } else {
                    // User did not enable Bluetooth or an error occurred
                    Log.d(TAG, "BT not enabled");
                    Toast.makeText(this, R.string.bt_not_enabled_leaving, Toast.LENGTH_SHORT).show();
                    finish();
                }
        }
    }

    private void connectDevice(Intent data, boolean secure) {
        // Get the device MAC address
        String address = data.getExtras().getString(DeviceListActivity.EXTRA_DEVICE_ADDRESS);
        // Get the BluetoothDevice object

        mmDevice = mBluetoothAdapter.getRemoteDevice(address);

        /* Attempt to connect to the device */
        try {
            openBT();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    void openBT() throws IOException {

        UUID uuid = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB"); //Standard SerialPortService ID
        mmSocket = mmDevice.createRfcommSocketToServiceRecord(uuid);
        mmSocket.connect();
        mmOutputStream = mmSocket.getOutputStream();
        mmInputStream = mmSocket.getInputStream();
        banner.setText(R.string.initial);
        ps = posState.INIT;
        beginListenForData();

        myLabel.setText("Bluetooth Opened");
    }

    void requestTrans() throws IOException {
        String msg = "x";
//        msg += "\n";
        mmOutputStream.write(msg.getBytes());
    }

    void beginListenForData() {
        final Handler handler = new Handler();
        stopWorker = false;
        readBufferPosition = 0;
        readBuffer = new byte[1024];
        workerThread = new Thread(new Runnable() {
            public void run() {
                final ByteBuffer bb = ByteBuffer.allocate(14);
                bb.order(ByteOrder.LITTLE_ENDIAN);
                psOld = posState.INIT;//should equal to ps;
                final Vibrator vb = (Vibrator) getSystemService(VIBRATOR_SERVICE);
                final NotificationManager notificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
                final Uri notiySound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
                final Uri alarmSound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
                try {
                    requestTrans();
                } catch (IOException e) {
                    e.printStackTrace();
                }
                while (!Thread.currentThread().isInterrupted() && !stopWorker) {
                    try {

                        if (mmInputStream.available() >= 0) {
                            byte[] packetBytes = new byte[1];
                            mmInputStream.read(packetBytes);
                            bb.put(packetBytes[0]);
                            if (!bb.hasRemaining()) {
                                handler.post(new Runnable() {
                                    public void run() {
                                        bb.rewind();
                                        x = bb.getShort();
                                        y = bb.getShort();
                                        z = bb.getShort();
                                        rvreg = new double[]{x, y, z};//put temparary value to rvreg register.

                                        //keepon reading for temperature sensor MPU6055 only.
                                        temperature = bb.getShort();
                                        gx = bb.getShort();
                                        gy = bb.getShort();
                                        gz = bb.getShort();


                                        double[] cv = {x, y, z};
                                        //do the low-pass filtering on cv;
                                        cv = lowPass(cv, cvOld);
                                        //update the history of cv for next iteration.
                                        cvOld = cv;
                                        //get the angle between rv  and cv;
                                        double angle = getAngle(rv, cv);
                                        //start animation to show real-time tilting.
//                                            if(D) Log.d(TAG, "calcAngle " + angle);
//                                            ImageView aniView = (ImageView) findViewById(R.id.imageView1);
                                        ObjectAnimator animation1 = ObjectAnimator.ofFloat(aniView, "rotation", (x > 0 ? (float) angle : -(float) angle));
//                                            animation1.setDuration(180);
                                        animation1.start();
                                        //echo the debugging stuff such as angle at the bottom textview object.
//                                            myLabel.setText("x="+x+"y="+y+"z="+z+"angle:"+angle+"x="+rv[0]+"y="+rv[1]+"z="+rv[2]);
                                        //doing notification of current posture.
                                        if (ps != posState.INIT) {//init loop until it was kicked into good state.
                                            if (angle <= 30) {
                                                ps = posState.GOOD;


//                                            myLabel.setText("x="+x+"y="+y+"z="+z+"angle:"+angle+"x="+rv[0]+"y="+rv[1]+"z="+rv[2]);
                                            } else if (angle <= 60) {
                                                ps = posState.BAD;


                                            } else if (angle <= 90) {
                                                ps = posState.DANGER;

                                            } else {
                                                ps = posState.ERROR;


                                            }
                                        }
                                        //show Temperature:
                                        myLabel.setText("Temperature: " + String.format("%.2f", (temperature + 12412.0) / 340) + " degrees Celsius");
                                        //check if state changes, if yes start corresponding event.
                                        if (ps != psOld) {
//                                                myLabel.setText("x="+x+"y="+y+"z="+z+"angle:"+angle+"x="+rv[0]+"y="+rv[1]+"z="+rv[2]);

                                            switch (ps) {
                                                case INIT:
                                                    banner.setText(R.string.initial);//this case should never happen.
                                                    vb.cancel();
                                                    break;
                                                case GOOD:
                                                    banner.setText(R.string.goodPos);
                                                    vb.cancel();
                                                    aniView.setImageDrawable(getResources().getDrawable(R.drawable.line));
                                                    break;
                                                case BAD:
                                                    banner.setText(R.string.badPos);
                                                    vb.cancel();
                                                    vb.vibrate(vbPattern1, 0);
                                                    aniView.setImageDrawable(getResources().getDrawable(R.drawable.line_yellow));
                                                    NotificationCompat.Builder mBuilder = new NotificationCompat.Builder(getApplicationContext())
                                                            .setSmallIcon(R.drawable.ic_launcher)
                                                            .setContentTitle("Riven")
                                                            .setContentText("Keep Straight")
                                                            .setSound(notiySound); //This sets the sound to play
                                                    notificationManager.notify(0, mBuilder.build());
                                                    break;
                                                case DANGER:
                                                    banner.setText(R.string.dangerPos);
                                                    vb.cancel();
                                                    vb.vibrate(vbPattern2, 0);
                                                    aniView.setImageDrawable(getResources().getDrawable(R.drawable.line_red));
                                                    mBuilder = new NotificationCompat.Builder(getApplicationContext())
                                                            .setSmallIcon(R.drawable.ic_launcher)
                                                            .setContentTitle("Riven")
                                                            .setContentText("Danger")
                                                            .setSound(alarmSound); //This sets the sound to play
                                                    notificationManager.notify(0, mBuilder.build());
                                                    break;
                                                case ERROR:
                                                    banner.setText(R.string.systemError);
                                                    vb.cancel();
                                                    break;

                                            }
                                        }
                                        psOld = ps;
                                        //clear the bytebuffer for next read.

                                        bb.clear();

                                        try {
                                            requestTrans();
                                        } catch (IOException e) {
                                            e.printStackTrace();
                                        }
                                        //do the animation rotation here
                                    }
                                });
                            }

                        }
                    } catch (IOException ex) {
                        stopWorker = true;
                    }
                }
            }
        });

        workerThread.start();
    }

    void sendData() throws IOException {
        String msg = myTextbox.getText().toString();
//        msg += "\n";
        mmOutputStream.write(msg.getBytes());
        myLabel.setText("Data Sent");
    }

    void closeBT() throws IOException {
        stopWorker = true;
        mmOutputStream.close();
        mmInputStream.close();
        mmSocket.close();
        myLabel.setText("Bluetooth Closed");
        banner.setText(R.string.title_not_connected);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.option_menu, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        Intent serverIntent = null;
        switch (item.getItemId()) {
            case R.id.secure_connect_scan:
                // Launch the DeviceListActivity to see devices and do scan
//                serverIntent = new Intent(this, DeviceListActivity.class);
//                startActivityForResult(serverIntent, REQUEST_CONNECT_DEVICE);
                findBT();
                return true;
        }
        return false;
    }
}

