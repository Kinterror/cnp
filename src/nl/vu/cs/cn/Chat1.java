package nl.vu.cs.cn;

import java.io.IOException;

import nl.vu.cs.cn.IP.IpAddress;
import nl.vu.cs.cn.TCPSegment.TCPSegmentType;
import android.app.Activity;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

public class Chat1 extends Activity implements OnClickListener{

	/**
	 * Variables which the layout needs
	 */
	EditText IPAddressSrc;
	EditText messageToSend;
	Button okButton;
	Button sendButton;
	TextView ipInfoSrc;
	TextView ipInfoDst;
	int addressSrc;
	int addressDest;
	TCP tcpLayerSrc;
	TCP tcpLayerDest;
	
	
	
	/** Called when the activity is first created. */
	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.main);
		//Initializing the variables and graphic components.
		initialize();
		// Connect various GUI components to the networking stack.
		okButton.setOnClickListener(this);
		sendButton.setOnClickListener(this);
		
	}

	private void initialize() {
		IPAddressSrc = (EditText) findViewById(R.id.edIPAddress);
		messageToSend = (EditText) findViewById(R.id.edMessageToSend);
		okButton = (Button) findViewById(R.id.bSendIPAddress);
		sendButton = (Button) findViewById(R.id.bSendMessage);
		ipInfoSrc = (TextView)findViewById(R.id.tvIpAddressSrc);
		ipInfoDst = (TextView)findViewById(R.id.tvIpAddressDst);
	}

	public void onClick(View v) {

		switch (v.getId()) {
		case R.id.bSendIPAddress:
			addressSrc = Integer.parseInt(IPAddressSrc.getText().toString());
			//Setting the destination IP address to (value of the source's address + 1)
			addressDest = addressSrc+1;
			try {
				setStacks();
			} catch (IOException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
			break;
		case R.id.bSendMessage:
			String message = messageToSend.getText().toString();
			byte[] data = message.getBytes();
			Log.i("text message", message);
			TCPSegment tcpPkt = new TCPSegment(1024, 1024, 1, 1, TCPSegmentType.DATA, data);
			
//			byte[] pkt = tcpPkt.encode();
			
			String tmpAddress = tcpLayerDest.getIPAddress();
			IpAddress tmpIpAddress = IpAddress.getAddress(tmpAddress);
			int tmpIntAddress = tmpIpAddress.getAddress();
			
//			Packet ipPkt = new Packet(tmpIntAddress, IP.TCP_PROTOCOL, 1, pkt, pkt.length);
			int sourceAddr = IpAddress.getAddress(tcpLayerSrc.getIPAddress()).getAddress();
			Log.i("packet before sending", tcpPkt.toString());
			Log.i("checksum before sending", Integer.toString(tcpPkt.calculate_checksum(sourceAddr, tmpIntAddress, IP.TCP_PROTOCOL)));
			
			try {
				tcpLayerSrc.send_tcp_segment(tmpIpAddress, tcpPkt);
			} catch (IOException e1) {
				// TODO Auto-generated catch block
				e1.printStackTrace();
			}
			
			try {
				TCPSegment received = tcpLayerDest.recv_tcp_segment(0);
				Log.i("received tcp packet", received.toString());
				Log.i("checksum after sending", Integer.toString(received.calculate_checksum(sourceAddr, tmpIntAddress, IP.TCP_PROTOCOL)));
				
			} catch (Exception e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
			
			break;
		}
	}
	
	public void setStacks() throws IOException{
		//setting the source's components
		tcpLayerSrc = new TCP(addressSrc);
		ipInfoSrc.setText("Source's IP address: " + tcpLayerSrc.getIPAddress());
		//setting the destination's components
		tcpLayerDest = new TCP(addressSrc+1);
		ipInfoDst.setText("Destination's IP address: " + tcpLayerDest.getIPAddress());
		
	}
	public void sendMessage(String message){
		
	}

}