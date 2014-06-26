package nl.vu.cs.cn;

import java.io.IOException;

import nl.vu.cs.cn.IP.IpAddress;
import nl.vu.cs.cn.TCP.Socket;
import android.app.Activity;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

public class Chat extends Activity{

	Button btnTop, btnBottom;
	TextView tvTop, tvBottom;
	EditText etTop, etBottom;
	int maxlen = 50;

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
	 * 
	 */
	protected void initVariables(){

		//The graphical items of the server
		btnTop = (Button)findViewById(R.id.btnTop);
		tvTop = (TextView)findViewById(R.id.tvTop);
		etTop = (EditText)findViewById(R.id.etTop);
		btnTop.setOnClickListener(new OnClickListener() {

			public void onClick(View v) {
				//retrieving the message the user wrote
				String message = etTop.getText().toString();
				Log.d("server onCick()", "message in the EditText view: "+message);
				byte[] tmp = message.getBytes();
				serverSocket.write(tmp, 0, tmp.length);
				//clear the EditText view
				etTop.setText("");
				
				

			}
		});
		
		//The graphical items of the client
		btnBottom = (Button)findViewById(R.id.btnBottom);
		tvBottom = (TextView)findViewById(R.id.tvBottom);
		etBottom = (EditText)findViewById(R.id.etBottom);
		btnBottom.setOnClickListener(new OnClickListener() {

			public void onClick(View v) {
				//retrieving the message the user wrote
				String message = etBottom.getText().toString();
				Log.d("client onCick()", "message in the EditText view: "+message);
				byte[] tmp = message.getBytes();
				clientSocket.write(tmp, 0, tmp.length);
				//clear the EditText view
				etBottom.setText("");
			}
		});

	}


	/**
	 * 
	 */
	protected void onCreate(Bundle savedInstanceState) {

		super.onCreate(savedInstanceState);
		setContentView(R.layout.main);
		initVariables();

		serverThread = new Thread(new Runnable() {

			public void run() {
				Log.d("serverThread's run()","serverThread creates a new TCP stack with the address 192.168.0."+serverIP);
				try {
					serverStack = new TCP(serverIP);
					serverSocket = serverStack.socket(serverPort);
					Log.d("serverThread's run()", "Server starts to accept");
					serverSocket.accept();
					int n;
					while((n = serverSocket.read(serverBuf, 0, maxlen)) > 0){
						final String messageReceived = new String(serverBuf, 0, n);
						Log.d("serverThread's run()", "Received message: "+messageReceived);
						runOnUiThread(new Runnable() {
							public void run() {
								tvTop.append(messageReceived+"\n");
							}
						});
					}
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
					int m;
					while((m = clientSocket.read(clientBuf, 0, maxlen)) > 0){
						final String messageReceived = new String(clientBuf, 0, m);
						Log.d("clientThread's run()", "Received message: "+messageReceived);
						runOnUiThread(new Runnable() {
							public void run() {
								tvBottom.append(messageReceived+"\n");
							}
						});
					}
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

	protected void onClose() {

	}

	/**
	 * 
	 */
	protected  void onPause() {
		super.onPause();
		serverSocket.close();
		//clientSocket.close();
		finish();
	}

}
