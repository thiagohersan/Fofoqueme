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
import android.os.Environment;
import android.speech.tts.TextToSpeech;
import android.speech.tts.TextToSpeech.Engine;
import android.telephony.SmsMessage;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.widget.Toast;

import java.io.FileOutputStream;
import java.io.OutputStream;
import java.io.InputStream;
import java.io.File;
import java.io.OutputStreamWriter;
import java.nio.charset.Charset;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.LinkedList;
import java.util.Locale;
import java.util.HashMap;
import java.util.Queue;
import java.util.Random;
import java.util.UUID;


public class FofoquemeActivity extends Activity implements TextToSpeech.OnInitListener {

	// UUID for serial connection
	private static final UUID SERIAL_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
	// BlueSmirf's address
	private static final String BLUE_SMIRF_MAC = "00:06:66:45:16:6C";
	// thiago's mac mini
	//private static final String BLUE_SMIRF_MAC = "10:9A:DD:B7:65:EB";

	// msg+number file name
	private static final String MSG_FILE_NAME = "FOFOQUEME";
	//
	private static final String[] PREPHRASE  = {"aiaiai aiai. ", "ui ui ui. ", "não acredito. ", "olha essa. ", "ouve só. ", "escuta essa. ", "meu deus. ", "relou. "};
	private static final String[] POSTPHRASE = {" . assim você me mata.", " . relou.", " . verdade.", " . nem me fale.", " . não me diga.", " . puts.", " . não não não.", " . que coisa.", " . pode creee.", " . pois é."};
	private static final String[] NONPHRASE  = {"só isso? ", "como assim? ", "aaaaaaiii que preguiça. "};

	// DEBUG variables
	private static final boolean DEBUG_VOICE = true;
	
	private TextToSpeech myTTS = null;
	private boolean isTTSReady = false;
	private SMSReceiver mySMS = null;
	private BluetoothSocket myBTSocket = null;
	private OutputStream myBTOutStream = null;
	private InputStream myBTInStream = null;
	private OutputStreamWriter myFileWriter = null;
	private Random myRandom = null;

	// for stream listening thread
	private Thread myStreamListenerThread = null;

	// queue for messages
	private Queue<String> msgQueue = null;

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
					if(phoneNum.length() > 5) {
						// clean up the @/# if it's there...
						message = message.replaceAll("[@#]?", "");

						// write number and msg to SD card
						try{
							if(myFileWriter != null){
								// setting up date strings
								Calendar calendar = Calendar.getInstance();
								SimpleDateFormat sdfD = new SimpleDateFormat("yyyyMMdd");
								SimpleDateFormat sdfT = new SimpleDateFormat("HHmmss");

								// log line format is yyyyMMdd:::HHmmss:::+551101234567:::msg
								String dateTime = new String(sdfD.format(calendar.getTime()));
								dateTime = dateTime.concat(":::");
								dateTime = dateTime.concat(new String(sdfT.format(calendar.getTime())));
								dateTime = dateTime.concat(":::");

								String t = dateTime.concat(new String(phoneNum+":::"+message+"\n"));
								myFileWriter.append(new String(t.getBytes("UTF-8"), "UTF-8"));
								myFileWriter.flush();
							}
						}
						catch(Exception e){}

						// if message is short
						String[] words = message.split(" ");
						if(words.length < 3){
							// if queue is empty
							//  only play the short message provocations if queue is empty !
							if((msgQueue.isEmpty() == true)&&(myTTS.isSpeaking() == false)){
								FofoquemeActivity.this.playMessage(NONPHRASE[myRandom.nextInt(NONPHRASE.length)].concat("diga mais. "));
							}
						}
						// message longer than 3 words
						else {
							// if nothing is already happening, start arduino
							if((msgQueue.isEmpty() == true)&&(myTTS.isSpeaking() == false)){
								FofoquemeActivity.this.sendSerialSignal();
							}
							// push all messages longer than 3 words onto queue
							msgQueue.offer(message);
						}
					}
				}
			}
		}
	}

	/** for creating a menu object */
	@Override
	public boolean onCreateOptionsMenu (Menu menu){
		MenuInflater inflater = getMenuInflater();
		inflater.inflate(com.hersan.fofoqueme.R.menu.menu, menu);
		return true;
	}

	/** for handling menu item clicks */
	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		// Handle item selection
		switch (item.getItemId()) {
		case com.hersan.fofoqueme.R.id.quitbutton:
			finish();
			return true;
		default: 
			return false;
		}
	}


	/** Called when the activity is first created. */
	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.main);

		// new text to speech, if needed
		myTTS = (myTTS == null)?(new TextToSpeech(this, this)):myTTS;
		myRandom = (myRandom == null)?(new Random()):myRandom;

		// new sms listener if needed
		mySMS = (mySMS == null)?(new SMSReceiver()):mySMS;

		// new message queue
		msgQueue = (msgQueue == null)?(new LinkedList<String>()):msgQueue;

		// register smsReceiver
		registerReceiver(mySMS, new IntentFilter("android.provider.Telephony.SMS_RECEIVED"));

		// start a file to save msg and phone numbers to
		try {
			File root = new File(Environment.getExternalStorageDirectory(), "Fofoqueme");
			if (!root.exists()) {
				root.mkdirs();
			}

			// setting up date strings
			Calendar calendar = Calendar.getInstance();
			SimpleDateFormat sdfD = new SimpleDateFormat("yyyyMMdd");
			String sdfdS = new String(sdfD.format(calendar.getTime()));

			myFileWriter = new OutputStreamWriter(new FileOutputStream(new File(root, MSG_FILE_NAME+sdfdS+".txt"), true), Charset.forName("UTF-8"));
		}
		catch(Exception e) {
			e.printStackTrace();
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
		else{
			this.bluetoothInitHelper(myBTAdapter);
		}
	}

	@Override
	public void onResume() {
		System.out.println("!!!: from onResume");
		super.onResume();
		//registerReceiver(mySMS, new IntentFilter("android.provider.Telephony.SMS_RECEIVED"));
	}

	@Override
	protected void onActivityResult(int requestCode, int resultCode, Intent data) {
		if (requestCode == 12345) {
			if (resultCode == RESULT_OK) {
				this.bluetoothInitHelper(BluetoothAdapter.getDefaultAdapter());
			}
		}
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
			// Add the test message to queue 
			msgQueue.offer("Ai, se eu te pego");

			// if arduino is idle (msg queue only has the message I just put there), start the dance
			if((msgQueue.size() == 1)&&(myTTS.isSpeaking() == false)){
				FofoquemeActivity.this.sendSerialSignal();
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

		// stop stream listener thread
		if(myStreamListenerThread != null){
			myStreamListenerThread.stop();
		}

		// close BT Socket
		try{
			if(myBTSocket != null){
				myBTSocket.close();
			}
			if(myFileWriter != null){
				myFileWriter.close();
			}
		}
		catch(Exception e){
		}
		super.onDestroy();
	}

	// from OnInitListener interface
	public void onInit(int status){
		System.out.println("!!!!! def eng: "+myTTS.getDefaultEngine());
		System.out.println("!!!!! def lang: "+myTTS.getLanguage().toString());

		// set the package and language for tts
		//   these are the values for Luciana
		myTTS.setEngineByPackageName("com.svox.classic");
		myTTS.setLanguage(new Locale("es_MX"));

		// slow her down a little...
		myTTS.setSpeechRate(0.66f);
		myTTS.setPitch(1.0f);

		// attach listener
		myTTS.setOnUtteranceCompletedListener(new TextToSpeech.OnUtteranceCompletedListener(){
			@Override
			public void onUtteranceCompleted (String utteranceId){
				// check if there are more messages to be said
				if(msgQueue.peek() != null){
					// if it's a short message, pop and provoke
					//   there shouldn't be any short messages on queue anymore... this is unnecessary
					// TODO test this
					String[] words = msgQueue.peek().split(" ");
					if(words.length < 3){
						msgQueue.poll();
						FofoquemeActivity.this.playMessage(NONPHRASE[myRandom.nextInt(NONPHRASE.length)].concat("diga mais. "));
					}
					else {
						FofoquemeActivity.this.sendSerialSignal();
					}
				}
			}
		});

		System.out.println("!!!!! set lang: "+myTTS.getLanguage().toString());
		Toast.makeText(this, "TTS Lang: "+myTTS.getLanguage().toString(), Toast.LENGTH_SHORT ).show();

		isTTSReady = true;
	}

	private void bluetoothInitHelper(BluetoothAdapter myBTA){
		System.out.println("!!! from BT init helper");
		// get a device
		BluetoothDevice myBTDevice = myBTA.getRemoteDevice(BLUE_SMIRF_MAC);
		// get a socket and stream
		try{
			// if there's a non-null socket... disconnect
			if(myBTSocket != null){
				myBTSocket.close();
			}

			// then (re)start the socket 
			myBTSocket = myBTDevice.createRfcommSocketToServiceRecord(SERIAL_UUID);
			myBTSocket.connect();
			myBTOutStream = myBTSocket.getOutputStream();
			myBTInStream = myBTSocket.getInputStream();

			// if there is a valid input stream,
			//   attach a thread to listen to input comming in
			if(myBTInStream != null){
				// if a listener thread has already been fired, kill it
				if(myStreamListenerThread != null){
					myStreamListenerThread.stop();
				}
				
				myStreamListenerThread = new Thread(new Runnable(){
					@Override
					public void run(){
						FofoquemeActivity.this.startStreamListener(myBTInStream);
					}
				});
				myStreamListenerThread.start();
			}
		}
		catch(Exception e){}

	}

	/////////////////////////////

	private void sendSerialSignal(){
		// get whether the BTDevice is bonded
		if((myBTSocket != null) && (myBTSocket.getRemoteDevice() != null)){
			// if the device is not bonded, try to bond again...
			while(myBTSocket.getRemoteDevice().getBondState() == BluetoothDevice.BOND_NONE){
				System.out.println("!!!: from sendSerialSignal: BTDevice not bonded, trying to re-bond");
				//
				bluetoothInitHelper(BluetoothAdapter.getDefaultAdapter());
			}
		}

		// no remote device
		else if(DEBUG_VOICE){
			FofoquemeActivity.this.playMessage();
		}

		try{
			// write an H 2 out of 10 times
			if(myRandom.nextInt(10) < 2){
				System.out.println("!!!: H");
				myBTOutStream.write('H');
			}
			else{
				System.out.println("!!!: G");
				myBTOutStream.write('G');
			}
		}
		catch(Exception e){}
	}

	// an input stream "listener" to be run on a thread
	// waits for the stop signal from the arduino serial connection
	private void startStreamListener(InputStream is){
		while(true){
			try{
				if((is != null)&&(is.available() > 0)){
					int b = is.read();
					// check if it's a S(top) signal
					//    and if there is something to say
					if(b == 'S'){
						if(msgQueue.isEmpty() == false){
							// play the next text message from queue
							FofoquemeActivity.this.playMessage();
							// the tts onComplete listener will deal with the arduino
						}
					}
				}
			}
			catch(Exception e){}
		}
	}

	// to be called when Arduino is done running its code
	//    assumes queue is not empty
	private void playMessage(){
		playMessage(null);
	}

	private void playMessage(String msg){
		// if pulling from the queue, modify message
		if(msg == null){
			msg = msgQueue.poll();
			// 2 in 10, add something to front
			int rInt = myRandom.nextInt(10); 
			if( rInt < 2){
				msg = PREPHRASE[myRandom.nextInt(PREPHRASE.length)].concat(msg);
			}
			// 2 in 10 add to back
			else if(rInt < 4){
				msg = msg.concat(POSTPHRASE[myRandom.nextInt(POSTPHRASE.length)]);
			}
			// 2 in 10, repeat longest word
			else if(rInt < 6){
				String foo = msg.replaceAll("[.!?]+", " ");
				String[] words = foo.split(" ");
				int longestWordInd = 0;
				for(int i=0; i<words.length; i++){
					if(words[i].length() > words[longestWordInd].length()){
						longestWordInd = i;
					}
				}
				msg = msg.replaceAll(words[longestWordInd], words[longestWordInd]+" "+words[longestWordInd]+" "+words[longestWordInd]);
			}
		}
		// else, msg = msg
		System.out.println("!!! speak: "+msg);

		myTTS.setPitch(1.5f*myRandom.nextFloat()+0.5f);  // [0.5, 2.0]
		HashMap<String,String> foo = new HashMap<String,String>();
		foo.put(Engine.KEY_PARAM_UTTERANCE_ID, "1234");
		myTTS.speak(msg, TextToSpeech.QUEUE_ADD, foo);
	}

}


