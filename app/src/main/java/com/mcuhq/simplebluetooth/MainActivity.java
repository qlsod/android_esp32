package com.mcuhq.simplebluetooth;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.UUID;

public class MainActivity extends AppCompatActivity {

    private final String TAG = MainActivity.class.getSimpleName();

    private static final UUID BT_MODULE_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB"); // "random" unique identifier

    // #defines for identifying shared types between calling functions
    private final static int REQUEST_ENABLE_BT = 1; // used to identify adding bluetooth names
    public final static int MESSAGE_READ = 2; // used in bluetooth handler to identify message update
    private final static int CONNECTING_STATUS = 3; // used in bluetooth handler to identify message status

    // GUI Components
    private TextView mBluetoothStatus;
    private TextView mReadBuffer, ledStateMessage, mReceive;
    private Button mScanBtn;
    private Button mOffBtn;
    private Button mListPairedDevicesBtn;
    private Button mDiscoverBtn, mLED2;
    private ListView mDevicesListView;
    private CheckBox mLED1;
    private EditText mLedSec;

    private BluetoothAdapter mBTAdapter;
    private Set<BluetoothDevice> mPairedDevices;
    private ArrayAdapter<String> mBTArrayAdapter;

    private Handler mHandler; // Our main handler that will receive callback notifications
    private ConnectedThread mConnectedThread; // bluetooth background worker thread to send and receive data
    private BluetoothSocket mBTSocket = null; // bi-directional client-to-client data path

    @SuppressLint("MissingInflatedId")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        mBluetoothStatus = (TextView)findViewById(R.id.bluetooth_status);
        mReadBuffer = (TextView) findViewById(R.id.read_buffer);
        mScanBtn = (Button)findViewById(R.id.scan);
        mOffBtn = (Button)findViewById(R.id.off);
        mDiscoverBtn = (Button)findViewById(R.id.discover);
        mListPairedDevicesBtn = (Button)findViewById(R.id.paired_btn);

        // 사용자에게 입력받은 값을 ESP32에게 전달하기 위한 버튼 layoutId 연결
        mLED2 = (Button)findViewById(R.id.btn_led_2);

        mLED1 = (CheckBox)findViewById(R.id.checkbox_led_1);

        // 사용자가 원하는 예약 시간을 입력 받을 수 있는 EditText layoutId 연결
        mLedSec = (EditText)findViewById(R.id.led_sec);

        // ESP32와 연결된 LED의 상태를 표시할 수 있는 TextView layoutId 연결
        ledStateMessage = (TextView)findViewById(R.id.connect_message);

        // ESP32가 보낸 난수를 표시하기 위한 TextView layoutId 연결
        mReceive = (TextView)findViewById(R.id.receivedMessage);

        mBTArrayAdapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1);
        mBTAdapter = BluetoothAdapter.getDefaultAdapter(); // get a handle on the bluetooth radio

        mDevicesListView = (ListView)findViewById(R.id.devices_list_view);
        mDevicesListView.setAdapter(mBTArrayAdapter); // assign model to view
        mDevicesListView.setOnItemClickListener(mDeviceClickListener);

        // Ask for location permission if not already allowed
        if(ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED)
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.ACCESS_COARSE_LOCATION}, 1);


        mHandler = new Handler(Looper.getMainLooper()){
            @Override
            public void handleMessage(Message msg){
                if(msg.what == MESSAGE_READ){
                    String readMessage = null;

                    // ESP가 보낸 난수를 읽어 readMessage에 저장
                    readMessage = new String((byte[]) msg.obj, StandardCharsets.UTF_8);
                    mReadBuffer.setText(readMessage);

                    // 난수를 표시하기 위해 TextView의 text 변경
                    mReceive.setText(readMessage);

                }

                if(msg.what == CONNECTING_STATUS){
                    char[] sConnected;
                    if(msg.arg1 == 1)
                        mBluetoothStatus.setText(getString(R.string.BTConnected) + msg.obj);
                    else
                        mBluetoothStatus.setText(getString(R.string.BTconnFail));
                }
            }
        };

        // 어플과 ESP32와의 블루투스 연결상태 확인
        if (mBTArrayAdapter == null) {
            mBluetoothStatus.setText(getString(R.string.sBTstaNF));
            Toast.makeText(getApplicationContext(),getString(R.string.sBTdevNF),Toast.LENGTH_SHORT).show();
        }
        else {
            // LED Control 버튼 클릭할 경우
            mLED1.setOnClickListener(new View.OnClickListener(){
                @Override
                public void onClick(View v){
                    /* 체크박스가 체크된 경우 mConnectedThread에 정의 된 write 사용하여
                       LED를 킬 수 있는 메시지인 "0"을 ESP32에게 전송 */
                    if (mLED1.isChecked()) {
                        mConnectedThread.write("0");
                        ledStateMessage.setText("LED가 켜졌습니다");
                    /* 체크 해제 시 LED를 끌 수 있는 메시지인 "-1"을 ESP32에게 전송*/
                    } else {
                        mConnectedThread.write("-1");
                        ledStateMessage.setText("LED가 꺼졌습니다");

                    }
                }
            });

            mLED2.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                        // 사용자가 입력한 값을 String 형식으로 받아와 변수 sec에 저장
                        String sec = mLedSec.getText().toString();
                        // 입력된 값이 존재할 경우
                        if (!sec.isEmpty()) {
                            try {
                                // 입력된 숫자를 받아 mLedSec에 값을 전송
                                int seconds = Integer.parseInt(sec);
                                mConnectedThread.write(sec);
                                ledStateMessage.setText(seconds + "초 후 LED가 꺼집니다.");

                                // 시간을 카운트하여 남은 시간을 다시 mLedSec에 표시, 1초마다 변경되어 표시
                                CountDownTimer timer = new CountDownTimer(seconds * 1000, 1000) {
                                    @Override
                                    public void onTick(long millisUntilFinished) {
                                        int remainingSeconds = (int) (millisUntilFinished / 1000);
                                        ledStateMessage.setText(remainingSeconds + "초 후 LED가 꺼집니다.");
                                    }
                                    // 예약 작업을 완료 시 LED를 끌 수 있도록 "-1" 전달
                                    @Override
                                    public void onFinish() {
                                        ledStateMessage.setText("LED가 꺼졌습니다.");
                                    }
                                };

                                timer.start();
                            } catch (NumberFormatException e) {
                                // 유효하지 않은 숫자 예외 처리
                                Toast.makeText(getApplicationContext(), "유효하지 않은 숫자입니다.", Toast.LENGTH_SHORT).show();
                            }
                        } else {
                            Toast.makeText(getApplicationContext(), "숫자를 입력하세요.", Toast.LENGTH_SHORT).show();
                        }

                }
            });


            mScanBtn.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    bluetoothOn();
                }
            });

            mOffBtn.setOnClickListener(new View.OnClickListener(){
                @Override
                public void onClick(View v){
                    bluetoothOff();
                }
            });

            mListPairedDevicesBtn.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v){
                    listPairedDevices();
                }
            });

            mDiscoverBtn.setOnClickListener(new View.OnClickListener(){
                @Override
                public void onClick(View v){
                    discover();
                }
            });
        }
    }

    private void bluetoothOn(){
        if (!mBTAdapter.isEnabled()) {
            Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT);
            mBluetoothStatus.setText(getString(R.string.BTEnable));
            Toast.makeText(getApplicationContext(),getString(R.string.sBTturON),Toast.LENGTH_SHORT).show();

        }
        else{
            Toast.makeText(getApplicationContext(),getString(R.string.BTisON), Toast.LENGTH_SHORT).show();
        }
    }

    // Enter here after user selects "yes" or "no" to enabling radio
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent Data){
        // Check which request we're responding to
        if (requestCode == REQUEST_ENABLE_BT) {
            // Make sure the request was successful
            if (resultCode == RESULT_OK) {
                // The user picked a contact.
                // The Intent's data Uri identifies which contact was selected.
                mBluetoothStatus.setText(getString(R.string.sEnabled));
            }
            else
                mBluetoothStatus.setText(getString(R.string.sDisabled));
        }
    }

    private void bluetoothOff(){
        mBTAdapter.disable(); // turn off
        mBluetoothStatus.setText(getString(R.string.sBTdisabl));
        Toast.makeText(getApplicationContext(),"Bluetooth turned Off", Toast.LENGTH_SHORT).show();
    }

    private void discover(){
        // Check if the device is already discovering
        if(mBTAdapter.isDiscovering()){
            mBTAdapter.cancelDiscovery();
            Toast.makeText(getApplicationContext(),getString(R.string.DisStop),Toast.LENGTH_SHORT).show();
        }
        else{
            if(mBTAdapter.isEnabled()) {
                mBTArrayAdapter.clear(); // clear items
                mBTAdapter.startDiscovery();
                Toast.makeText(getApplicationContext(), getString(R.string.DisStart), Toast.LENGTH_SHORT).show();
                registerReceiver(blReceiver, new IntentFilter(BluetoothDevice.ACTION_FOUND));
            }
            else{
                Toast.makeText(getApplicationContext(), getString(R.string.BTnotOn), Toast.LENGTH_SHORT).show();
            }
        }
    }

    final BroadcastReceiver blReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if(BluetoothDevice.ACTION_FOUND.equals(action)){
                BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                // add the name to the list
                mBTArrayAdapter.add(device.getName() + "\n" + device.getAddress());
                mBTArrayAdapter.notifyDataSetChanged();
            }
        }
    };

    private void listPairedDevices(){
        mBTArrayAdapter.clear();
        mPairedDevices = mBTAdapter.getBondedDevices();
        if(mBTAdapter.isEnabled()) {
            // put it's one to the adapter
            for (BluetoothDevice device : mPairedDevices)
                mBTArrayAdapter.add(device.getName() + "\n" + device.getAddress());

            Toast.makeText(getApplicationContext(), getString(R.string.show_paired_devices), Toast.LENGTH_SHORT).show();
        }
        else
            Toast.makeText(getApplicationContext(), getString(R.string.BTnotOn), Toast.LENGTH_SHORT).show();
    }

    private AdapterView.OnItemClickListener mDeviceClickListener = new AdapterView.OnItemClickListener() {
        @Override
        public void onItemClick(AdapterView<?> parent, View view, int position, long id) {

            if(!mBTAdapter.isEnabled()) {
                Toast.makeText(getBaseContext(), getString(R.string.BTnotOn), Toast.LENGTH_SHORT).show();
                return;
            }

            mBluetoothStatus.setText(getString(R.string.cConnet));
            // Get the device MAC address, which is the last 17 chars in the View
            String info = ((TextView) view).getText().toString();
            final String address = info.substring(info.length() - 17);
            final String name = info.substring(0,info.length() - 17);

            // Spawn a new thread to avoid blocking the GUI one
            new Thread()
            {
                @Override
                public void run() {
                    boolean fail = false;

                    BluetoothDevice device = mBTAdapter.getRemoteDevice(address);

                    try {
                        mBTSocket = createBluetoothSocket(device);
                    } catch (IOException e) {
                        fail = true;
                        Toast.makeText(getBaseContext(), getString(R.string.ErrSockCrea), Toast.LENGTH_SHORT).show();
                    }
                    // Establish the Bluetooth socket connection.
                    try {
                        mBTSocket.connect();
                    } catch (IOException e) {
                        try {
                            fail = true;
                            mBTSocket.close();
                            mHandler.obtainMessage(CONNECTING_STATUS, -1, -1)
                                    .sendToTarget();
                        } catch (IOException e2) {
                            //insert code to deal with this
                            Toast.makeText(getBaseContext(), getString(R.string.ErrSockCrea), Toast.LENGTH_SHORT).show();
                        }
                    }
                    if(!fail) {
                        mConnectedThread = new ConnectedThread(mBTSocket, mHandler);
                        mConnectedThread.start();

                        mHandler.obtainMessage(CONNECTING_STATUS, 1, -1, name)
                                .sendToTarget();
                    }
                }
            }.start();
        }
    };

    private BluetoothSocket createBluetoothSocket(BluetoothDevice device) throws IOException {
        try {
            final Method m = device.getClass().getMethod("createInsecureRfcommSocketToServiceRecord", UUID.class);
            return (BluetoothSocket) m.invoke(device, BT_MODULE_UUID);
        } catch (Exception e) {
            Log.e(TAG, "Could not create Insecure RFComm Connection",e);
        }
        return  device.createRfcommSocketToServiceRecord(BT_MODULE_UUID);
    }
}
