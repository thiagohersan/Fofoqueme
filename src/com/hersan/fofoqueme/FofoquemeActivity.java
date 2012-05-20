// tgh !!!
package com.hersan.fofoqueme;

import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.telephony.SmsMessage;
import android.view.MotionEvent;
import android.widget.Toast;
import com.hersan.fofoqueme.R;

import java.io.OutputStream;
import java.io.InputStream;
import java.util.LinkedList;
import java.util.Locale;
import java.util.Queue;
import java.util.UUID;


public class FofoquemeActivity extends Activity implements TextToSpeech.OnInitListener {

	// UUID for serial connection
	private static final UUID SERIAL_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
	// BlueSmirf's address
	private static final String BLUE_SMIRF_MAC = "00:06:66:45:16:6C";

	private TextToSpeech myTTS = null;
	private boolean isTTSReady = false;
	private SMSReceiver mySMS = null;
	private BluetoothSocket myBTSocket = null;
	private OutputStream myBTOutStream = null;
	private InputStream myBTInStream = null;

	// queue for messages
	private Queue<String> msgQueue = null;
	private String sayMe = null;


	// listen for intent sent by broadcast of SMS signal
	// if it gets a new SMS
	//  clean it up a little bit and send to text reader
	public class SMSReceiver extends BroadcastReceiver {
		@Override
		public void onReceive(Context context, Intent intent) {
			Bundle bundle = intent.getExtras();
			SmsMessage[] msgs = null;

			if (bundle != null) {
				Object[] pdus = (Object[]) bundle.get("pdus");
				msgs = new SmsMessage[pdus.length];

				if (msgs.length > 0) {
					// read only the most recent
					msgs[0] = SmsMessage.createFromPdu((byte[]) pdus[0]);
					String message = msgs[0].getMessageBody().toString();
					String phoneNum = msgs[0].getOriginatingAddress().toString();
					System.out.println("!!! Client MainAction got sms: "+message);
					System.out.println("!!! from: "+phoneNum);

					// only write if it's from a real number
					// TEST THIS!!!!!
					if(phoneNum.length() > 5) {
						// clean up the @/# if it's there...
						message = message.replaceAll("[@#]?", "");
						message = message.replaceAll("[@#]?", "");
						// TODO: push onto queue (garbled message??)
						myTTS.speak(message, TextToSpeech.QUEUE_ADD, null);
						// send start signal to arduino
						try{
							// TODO: add a state check. Only start if it has stopped
							myBTOutStream.write('G');
						}
						catch(Exception e){
						}
					}
				}
			}
		}
	}

	/** Called when the activity is first created. */
	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.main);

		// new text to speech, if needed
		myTTS = (myTTS == null)?(new TextToSpeech(this, this)):myTTS;

		// new sms listener if needed
		mySMS = (mySMS == null)?(new SMSReceiver()):mySMS;

		// register smsReceiver
		registerReceiver(mySMS, new IntentFilter("android.provider.Telephony.SMS_RECEIVED"));

		// new message queue
		if(msgQueue == null){
			msgQueue = new LinkedList<String>();
		}
		
		// Bluetooth-ness
		Toast.makeText(this, "Starting Bluetooth Connection", Toast.LENGTH_SHORT ).show();
		// from : http://stackoverflow.com/questions/6565144/android-bluetooth-com-port
		BluetoothAdapter myBTAdapter = BluetoothAdapter.getDefaultAdapter();
		// this shouldn't happen...
		if (!myBTAdapter.isEnabled()) {
			//make sure the device's bluetooth is enabled
			Intent enableBluetooth = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
			startActivityForResult(enableBluetooth, 12345);
		}

		// get a device
		BluetoothDevice myBTDevice = myBTAdapter.getRemoteDevice(BLUE_SMIRF_MAC);

		// get a socket and stream
		try{
			myBTSocket = myBTDevice.createRfcommSocketToServiceRecord(SERIAL_UUID);
			myBTSocket.connect();
			myBTOutStream = myBTSocket.getOutputStream();
			myBTInStream = myBTSocket.getInputStream();
		}
		catch(Exception e){

		}
		
		// if there is a valid input stream,
		//   attach a thread to listen to input comming in
		if(myBTInStream != null){
			new Thread(new Runnable(){
				@Override
				public void run(){
					FofoquemeActivity.this.startStreamListener(myBTInStream);
				}
			}).start();
		}

		// TODO: start thread to empty queue?!
	}

	@Override
	public void onResume() {
		System.out.println("!!!: from onResume");
		super.onResume();
		//registerReceiver(mySMS, new IntentFilter("android.provider.Telephony.SMS_RECEIVED"));
	}

	@Override
	protected void onPause() {
		System.out.println("!!!: from onPause");
		//unregisterReceiver(mySMS);
		super.onPause();
	}

	@Override
	public boolean onTouchEvent(MotionEvent event){
		System.out.println("!!!: from onTouch");
		if((event.getAction() == MotionEvent.ACTION_UP) && (isTTSReady)){
			// TODO: Add to queue (and garble a test message)
			myTTS.speak("Comeando!", TextToSpeech.QUEUE_ADD, null);
			try{
				// TODO: add a state check. Only start if it has stopped
				myBTOutStream.write('G');
			}
			catch(Exception e){
			}
			return true;
		}
		return false;
	}

	/** Called when the activity is ending. */
	@Override
	public void onDestroy() {
		System.out.println("!!!: from onDestroy");
		if(myTTS != null){
			myTTS.shutdown();
		}
		// unregister sms Receiver
		unregisterReceiver(mySMS);
		super.onDestroy();

		// close BT Socket
		try{
			myBTSocket.close();
		}
		catch(Exception e){
		}
	}

	// from OnInitListener interface
	public void onInit(int status){
		System.out.println("!!!!! def eng: "+myTTS.getDefaultEngine());
		System.out.println("!!!!! def lang: "+myTTS.getLanguage().toString());

		myTTS.setEngineByPackageName("com.svox.classic");
		myTTS.setLanguage(new Locale("pt_BR"));

		System.out.println("!!!!! set lang: "+myTTS.getLanguage().toString());
		Toast.makeText(this, "TTS Lang: "+myTTS.getLanguage().toString(), Toast.LENGTH_SHORT ).show();

		isTTSReady = true;
	}
	
	/////////////////////////////
	
	// an input stream "listener" to be run on a thread
	// waits for the stop signal from the arduino serial connection
	private void startStreamListener(InputStream is){
		while(true){
			try{
				if(is.available() > 0){
					int b = is.read();
					// TODO: add a state check. Only stop if it started...
					if(b == 'S'){
						FofoquemeActivity.this.streamCallback();
					}
				}
			}
			catch(Exception e){
			}
		}
	}

	// to be called when Arduino is done running its code
	private void streamCallback(){
		// TODO: play garbled message here
		myTTS.speak("Fofoque-me. Ai se eu te pego.", TextToSpeech.QUEUE_ADD, null);
	}

}