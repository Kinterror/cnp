package nl.vu.cs.cn;

import java.io.IOException;

import nl.vu.cs.cn.IP.IpAddress;
import nl.vu.cs.cn.TCP.Socket;
import android.app.Activity;
import android.os.Bundle;
import android.text.method.ScrollingMovementMethod;
import android.util.Log;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

public class Chat extends Activity{

	//The views of the application
	Button btnTop, btnBottom;
	TextView tvTop, tvBottom;
	EditText etTop, etBottom;
	
	int maxlen = 50;	//max length of a message

	//Server's variables
	Thread serverThread;
	TCP serverStack;
	Socket serverSocket;
	int serverIP = 1;
	int serverPort = 4444;
	byte[] serverBuf = new byte[maxlen];

	//Client's variables
	Thread clientThread;
	TCP clientStack;
	Socket clientSocket;
	int clientIP = 2;
	byte[] clientBuf = new byte[maxlen];


	/**
	 * Initialization of the views. Handle the user's actions when he presses the Send button.
	 * The server will be the upper part and the client will be the bottom part of the application.
	 */
	protected void initVariables(){

		// The graphical items of the server
		btnTop = (Button)findViewById(R.id.btnTop);
		tvTop = (TextView)findViewById(R.id.tvTop);
		tvTop.setMovementMethod(new ScrollingMovementMethod());

		etTop = (EditText)findViewById(R.id.etTop);
		// What happens when the user clicks on Send
		btnTop.setOnClickListener(new OnClickListener() {

			public void onClick(View v) {
				// Retrieving the message the user wrote
				String message = etTop.getText().toString();
				Log.d("server onCick()", "message in the EditText view: "+message);
				// Convert the string into a byte array 
				byte[] tmp = message.getBytes();
				// Send the message
				serverSocket.write(tmp, 0, tmp.length);
				// Clear the EditText view
				etTop.setText("");
			}
		});
		
		//The graphical items of the client
		btnBottom = (Button)findViewById(R.id.btnBottom);
		tvBottom = (TextView)findViewById(R.id.tvBottom);
		tvBottom.setMovementMethod(new ScrollingMovementMethod());
		etBottom = (EditText)findViewById(R.id.etBottom);
		//What happens when the user clicks on Send
		btnBottom.setOnClickListener(new OnClickListener() {

			public void onClick(View v) {
				//Retrieving the message the user wrote
				String message = etBottom.getText().toString();
				Log.d("client onCick()", "message in the EditText view: "+message);
				// Convert the string into a byte array 
				byte[] tmp = message.getBytes();
				// Send the message
				clientSocket.write(tmp, 0, tmp.length);
				// Clear the EditText view
				etBottom.setText("");
			}
		});
	}


	/**
	 * When the application starts. Sets the layout and call initVariables() to initialize the views.
	 * Starts the server and client threads.
	 */
	protected void onCreate(Bundle savedInstanceState) {

		super.onCreate(savedInstanceState);
		setContentView(R.layout.main);
		initVariables();

		serverThread = new Thread(new Runnable() {

			public void run() {
				Log.d("serverThread's run()","serverThread creates a new TCP stack with the address 192.168.0."+serverIP);
				try {
					serverStack = new TCP(serverIP);		// Creating a new TCP stack
					serverSocket = serverStack.socket(serverPort);		// Setting the socket to the right port
					Log.d("serverThread's run()", "Server starts to accept");
					serverSocket.accept();			// Starts listening for incoming connection
					handlingMessages(serverSocket, serverBuf, tvTop);

				} catch (IOException e) {
					e.printStackTrace();
				}
			}
		});

		clientThread = new Thread(new Runnable() {

			public void run() {
				Log.d("clientThread's run()","clientThread creates a new TCP stack with the address 192.168.0."+clientIP);
				try {
					clientStack = new TCP(clientIP);
					clientSocket = clientStack.socket();
					Log.d("clientThread's run()", "Client tries to connect on 192.168.0."+serverIP);
					if (!clientSocket.connect(IpAddress.getAddress("192.168.0."+serverIP), serverPort)){
						Log.e("clientThread","Unable to connect");
						return;
					}
					handlingMessages(clientSocket, clientBuf, tvBottom);

				} catch (IOException e) {
					e.printStackTrace();
				}
			}
		});

		Log.d("onCreate()", "threadServ starts");
		serverThread.start();
		Log.d("onCreate()", "threadClient starts");
		clientThread.start();

	}

	private void handlingMessages(Socket soc, byte[] buf, final TextView tv){
		int n;
		// if it receives a message, converts it into a string and display in the TextView
		while((n = soc.read(buf, 0, maxlen)) > 0){
			final String messageReceived = new String(buf, 0, n);
			Log.d("Thread ", "Received message: "+messageReceived);
			// To be able to modify the TextView. Only the thread which created a view can modify it.
			// Here the main thread created tvTop, so we do that to allow serverThread to change it.
			runOnUiThread(new Runnable() {
				public void run() {
					tv.append(messageReceived+"\n");
					// find the amount we need to scroll. This works by
				    // asking the TextView's internal layout for the position
				    // of the final line and then subtracting the TextView's height
					final int scrollAmount = tv.getLayout().getLineTop(tv.getLineCount())-
							tv.getHeight();
				    // if there is no need to scroll, scrollAmount will be <=0
				    if (scrollAmount > 0)
				    	tv.scrollTo(0, scrollAmount);
				    else
				    	tv.scrollTo(0, 0);
				}
			});
		}
	}
	
	
	/**
	 * When the user press return to exit the application, close the connection and clean.
	 */
	protected  void onPause() {
		super.onPause();
		serverSocket.close();
		clientSocket.close();
		finish();
	}

}
