package nl.vu.cs.cn;

import java.io.IOException;

import android.util.Log;
import nl.vu.cs.cn.IP.*;
import nl.vu.cs.cn.TCPControlBlock.ConnectionState;
import nl.vu.cs.cn.TCPSegment.TCPSegmentType;

/**
 * This class represents a TCP stack. It should be built on top of the IP stack
 * which is bound to a given IP address.
 */
public class TCP {
	
	/** The port a client socket will bind to automatically. Since only one connection is allowed at a time,
	 * only one port is needed for this. */
	public static final int DEFAULT_CLIENT_PORT = 12345;
	
	/**MSL (maximum segment lifetime) (seconds) used in the connection termination timer*/
	public static final int MSL = 10;
	
	/**maximum number of tries waiting for an ack*/
	public static final int MAX_TRIES = 10;
	
	/**the length of TCP headers used by our implementation. Options are not supported.*/
	public static final int IP_HEADER_LENGTH = 20;
	
	/**the maximum size (bytes) of any packet sent through the ip layer*/
	public static final int MAX_TCPIP_SEGMENT_SIZE = 8192;

	/** Size of the send and receive buffer */
	public static final int BUFFER_SIZE = 1048576; // 1MB
	
	/**the default for receiving packets*/
	public static final int DEFAULT_TIMEOUT = 1;
	
	/**the timeout in seconds for receiving packets. Initially set to DEFAULT_TIMEOUT. */
	public int timeout;
	
	/** The underlying IP stack for this TCP stack. */
	private IP ip;

	/**packet ID which is initially zero and is incremented each time a packet is sent through the IP layer*/
	private int ip_packet_id;
	
    /**
     * This class represents a TCP socket.
     */
    public class Socket {

    	private boolean isClientSocket;
    	
    	/**
    	 * transmission control block, shared between the sender and receiver threads
    	 */
		private volatile TCPControlBlock tcb;
		private volatile BoundedByteBuffer recv_buf;
		private volatile BoundedByteBuffer send_buf;
		
		private volatile boolean isWaitingForAck;
		private Object waitingForAckMonitor;
		
    	/** Construct a client socket. */
    	private Socket() {
    		this(DEFAULT_CLIENT_PORT);
    		isClientSocket = true;
    	}

    	/**
    	 * Construct a server socket bound to the given local port.
    	 * @param port the local port to use
    	 */
        private Socket(int port) {
        	isClientSocket = false;
        	isWaitingForAck = false;
        	waitingForAckMonitor = new Object();
        	tcb = new TCPControlBlock();
        	tcb.setLocalSocketAddress(new SocketAddress(ip.getLocalAddress(), port));
        	
        	recv_buf = new BoundedByteBuffer(BUFFER_SIZE);
        	send_buf = new BoundedByteBuffer(BUFFER_SIZE);
        }

		/**
         * Connect this socket to the specified destination and port.
         *
         * @param IpAddress dst - the destination to connect to
         * @param int port - the TCP port to connect to
         * @return true if the connect succeeded.
         */
        public boolean connect(IpAddress dst, int port) {
        	
        	//can't connect with a server socket or a socket with an already open connection
        	if (!isClientSocket || tcb.getState() != ConnectionState.S_CLOSED || port < 0 || port > 65535){
        		return false;
        	}
        	
            // Implement the connection side of the three-way handshake here.
        	tcb.setRemoteSocketAddress(new SocketAddress(dst, port));
        	
        	//generate sequence number
        	tcb.generateSeqnr();
        	
        	//construct a syn packet
        	TCPSegment syn_pck = new TCPSegment(
        			tcb.getLocalSocketAddress().getPort(),
        			port, tcb.getSeqnr(), 
        			0,
        			TCPSegmentType.SYN, new byte[0]
        	);
        	
        	//send it and wait for a synack
        	tcb.setState(ConnectionState.S_SYN_SENT);
        	if (sendAndWaitAck(syn_pck, true)){
        		tcb.getAndIncrementSeqnr();
        		
        		//send ack
        		TCPSegment ack = new TCPSegment(TCPSegmentType.ACK);
        		sockSend(ack);
        		
        		tcb.setState(ConnectionState.S_ESTABLISHED);
        		//start sender and receiver threads
        		new Thread(new ReceiverThread()).start();
            	new Thread(new SenderThread()).start();
        		return true;
        	} else {
        		Log.e("accept()", "failed to receive a SYN_ACK message from server");
        		tcb.setState(ConnectionState.S_CLOSED);
        		return false;
        	}
        }	

        /**
         * Accept a connection on this socket.
         * This call blocks until a connection is made.
         */
        public void accept() {
        	
        	//client sockets and sockets with an already open connection cannot call accept
        	if(isClientSocket){
        		Log.e("accept() error","Called accept() on a client socket");
        		System.exit(-1);
        	} else if (tcb.getState() != ConnectionState.S_CLOSED){
        		Log.e("accept() error","Called accept() on an opened socket");
        		System.exit(-1);
        	}
        	
        	//receive stuff
        	while (tcb.getState() != ConnectionState.S_ESTABLISHED){
	        	
        		//listen for incoming connections
        		tcb.setState(ConnectionState.S_LISTEN);
	        	
	        	TCPSegment syn_pck;
	        	
	            while (tcb.getState() == ConnectionState.S_LISTEN){
	            	//receive a packet from the network
					try {
						syn_pck = recv_tcp_segment(0);
					} catch (Exception e){
						continue;
					}
					if(syn_pck.getSegmentType() == TCPSegmentType.SYN)
					{
						//initialize the connection state to S_SYN_RCVD
						tcb.initServer(syn_pck);
					}
					//else, discard it and listen again.
	            }
	            
	            //compose a synack message
	            TCPSegment syn_ack = new TCPSegment(TCPSegmentType.SYNACK);
	            
	            //try to send it
	            if (sendAndWaitAck(syn_ack, false)){
	            	tcb.setState(ConnectionState.S_ESTABLISHED);
	            	new Thread(new ReceiverThread()).start();
	            	new Thread(new SenderThread()).start();
	            	return;
	            }
	            
	            /*either:
	             *tries expired. Go back to listening state and try to listen again.
	             *or:
	             *established. In this case, return.
	            */
        	}
        }
        
        /**
         * buffer the received data and send an ack if the buffer is not full
         */
        private void handleData(TCPSegment seg){
        	try {
            	//put the data at the end of the buffer
				recv_buf.buffer(seg.data);
				
				//send ack
				TCPSegment ack = new TCPSegment(TCPSegmentType.ACK);
				sockSend(ack);
				
			} catch (FullCollectionException e) {
				e.printStackTrace();
			}
        	
        }
        
        /**here, the process received a fin packet. handle it according to the current state.*/
        private void handleIncomingFin(){
        	TCPSegment ack;
        	
        	/*
        	 * if we were in established state, go to close_wait state and send an ack.
        	 * if we were already in close_wait, and receive a fin again, resend the ack since it was apparently lost.
        	 */
        	switch(tcb.getState()){
        	case S_ESTABLISHED:
        		tcb.setState(ConnectionState.S_CLOSE_WAIT);
        	case S_CLOSE_WAIT:
        		//send ack
	        	ack = new TCPSegment(TCPSegmentType.ACK);
				sockSend(ack);
				break;
        	case S_FIN_WAIT_1:
        		//simultaneous closing
        	case S_FIN_WAIT_2:
        		//send last ack
        		ack = new TCPSegment(TCPSegmentType.ACK);
				sockSend(ack);
				tcb.setState(ConnectionState.S_TIME_WAIT);
				
				//start timed receive for 10 seconds
				
				long timeExpired = 0;
				long initialTime = System.currentTimeMillis();
				
				//decrease timer until it hits zero
				for (int timer = 2 * MSL; timer >= 0; timer -= (timeExpired / 1000)){
					try {
						TCPSegment segment = sockRecv(timer);
						if(segment.getSegmentType() == TCPSegmentType.FIN){
							//send ack again
							ack = new TCPSegment(TCPSegmentType.ACK);
							sockSend(ack);
						}
					} catch (InterruptedException e) {
						break;
					} finally {
						//increment the timer
						long time = System.currentTimeMillis();
						timeExpired += (time - initialTime);
						initialTime = time;
					}
				}
				//close the connection
				tcb.setState(ConnectionState.S_CLOSED);
				break;
        	default:
        		//do nothing
        	}
        }
        
        private synchronized TCPSegment sockRecv(int timeout) throws InterruptedException{
        	TCPSegment pck;
        	
        	while(true){
	        	try{
	        		pck = recv_tcp_segment(timeout);
		        	if(tcb.checkValidAddress(pck) && tcb.isInOrderPacket(pck)){
		        		
		        		//increment acknr if received non-ack packet
		        		switch(pck.getSegmentType()){
		        		case ACK:
		        			break;
		        		default:
			        		tcb.getAndIncrementAcknr(pck.data.length);
		        		}
		        			
		        		break;
		        	/*
		        	 * case of lost ack: we receive an old non-ack packet with the previous sequence number
		        	 */
		        	} else if (tcb.checkValidAddress(pck) && pck.seq_nr == tcb.getPreviousExpectedSeqnr()){
		        		switch(pck.getSegmentType()){
		        		case SYNACK:
		        		case DATA:
		        		case FIN:
		        			//resend lost ack
		        			TCPSegment ack = new TCPSegment(TCPSegmentType.ACK);
		        			
		        			//set address/port fields right
		        			SocketAddress remoteAddr = tcb.getRemoteSocketAddress();
		        			ack.setDestPort(remoteAddr.getPort());
		        			ack.setSrcPort(tcb.getLocalSocketAddress().getPort());
		                	
		        			//set sequence numbers right
		        			ack.setSeqNr(tcb.getSeqnr());
		        			ack.setAckNr(tcb.getExpectedSeqnr());
		        			
		                	try{
		                		send_tcp_segment(remoteAddr.getIp(), ack);
		                	} catch (IOException e) {
		                		continue;
		                	}
		        		case SYN:
		        			//now the SYNACK was lost. 
		        			break;
		        		default:
		        			continue;
		        		}
		        		break;
		        	} else if (tcb.checkValidAddress(pck)){
		        		Log.d("sockRecv", "received incorrect (out of order) packet with sequence number " + pck.seq_nr + 
		        				" and acknowledgement number " + pck.ack_nr + ". Expected seqnr: "+ tcb.getExpectedSeqnr() +
		        				" or prev:" + tcb.getPreviousExpectedSeqnr()+ ", acknr" + tcb.getNextSeqnr());
		        	} else {
		        		Log.d("sockRecv", "received packet with invalid address or port");
		        	}
	        	} catch (InvalidPacketException e) {
	        		//discard it
	        	}
        	}
        	return pck;
        }
        
        private synchronized TCPSegment sockRecv() {
        	try{
        		return sockRecv(0);
        	} catch (InterruptedException e){
        		//never happens
        		return null;
        	}
        }
        
        /**
         * Sets the Seq/Ack number and dest port right.
         * @param pck
         */
        
        private synchronized boolean sockSend(TCPSegment pck) {
        	SocketAddress remoteAddr = tcb.getRemoteSocketAddress();
        	
        	pck.setSeqNr(tcb.getSeqnr());
        	pck.setAckNr(tcb.getExpectedSeqnr());
        	pck.setDestPort(remoteAddr.getPort());
        	pck.setSrcPort(tcb.getLocalSocketAddress().getPort());
        	
        	try{
        		send_tcp_segment(remoteAddr.getIp(), pck);
        	} catch (IOException e) {
        		e.printStackTrace();
        		return false;
        	}
        	return true;
        }
        
        
        /**
         * wrapper for sockSend. try to send a packet and wait for the acknowledgment.
         */
        private boolean sendAndWaitAck(TCPSegment pck, boolean doWaitForSynAck) {
        	for (int ntried = 0; ntried < MAX_TRIES; ntried++) {
        		//try to send packet with right port and sequence numbers.
        		if (!sockSend(pck))
        			return false;
        		
        		//wait for ack
        		boolean hasReceived = false;
        		
	        	if(!doWaitForSynAck){	
	        		hasReceived = waitForAck();
	        	} else {
	        		hasReceived = waitForSynAck();
	        	}
        		if (hasReceived){
        			return true;
        		}
        	}
        	return false;
        }
        
        /**
         * method that waits for an acknowledgement. Data and Fin segments are handled accordingly.
         * @return false if we received nothing or we received a corrupted packet.
         * @return true if we received an ack.
         */
        private boolean waitForAck(){
        	while(true){
        		try {
					TCPSegment seg = sockRecv(timeout);
					
					switch(seg.getSegmentType()){
					case ACK:
						return true;
					case DATA:
						handleData(seg);
						break;
					case FIN:
						handleIncomingFin();
						break;
					case SYN:
						return false;
					default:
						//continue
					}
				} catch (InterruptedException e) {
					return false;
				}
        	}
        }
        
        /**
         * same as waitForAck, only waits for a synack and discards data and fin packets
         */
        private boolean waitForSynAck(){
        	while(true){
        		try {
					TCPSegment seg = recv_tcp_segment(timeout);
					//check if it has the right socket address, acknr, and the right type
					if (seg.getSegmentType() == TCPSegmentType.SYNACK &&
							tcb.checkValidAddress(seg) &&
							seg.ack_nr == tcb.getNextSeqnr()){ //no data expected, so 1 corresponds to a control packet
						//initialize acknr of client
						tcb.initClient(seg);
						return true;
					} else if (seg.ack_nr != tcb.getSeqnr() + 1){
						Log.e("waitForSynAck()", "received acknr " + seg.ack_nr + ". Expected: " + tcb.getSeqnr());
					}
        		} catch (InterruptedException e) {
        			return false;
        		} catch (InvalidPacketException e) {
        			//do nothing
        		}
        	}
        }
        
        /**
         * Reads bytes from the socket into the buffer.
         * This call is not required to return maxlen bytes
         * every time it returns.
         *
         * @param buf the buffer to read into
         * @param offset the offset to begin reading data into
         * @param maxlen the maximum number of bytes to read
         * @return the number of bytes read, or -1 if an error occurs.
         */
        public int read(byte[] buf, int offset, int maxlen) {
        	switch(tcb.getState()){
        	case S_ESTABLISHED:
        	case S_FIN_WAIT_1:
        	case S_FIN_WAIT_2:
        		synchronized(recv_buf){
	        		/*check if there are maxlen bytes in the buffer. If not, block until enough data is available.*/
	        		while(recv_buf.length() < maxlen){
	        			//wait for the buffer to become filled again
	        			try {
							recv_buf.wait(1000);
						} catch (InterruptedException e) { e.printStackTrace(); }
	        			
	        			//check if the connection wasn't closed in the meantime
	        			if (tcb.getState() == ConnectionState.S_CLOSE_WAIT ||
	        					tcb.getState() == ConnectionState.S_CLOSED){
	        				break;
	        			}
	        		}
	        		return recv_buf.deBuffer(buf, offset, maxlen);
        		}
        	case S_CLOSE_WAIT: //they have already closed the connection, or we received enough data
        	case S_CLOSED:
        		return recv_buf.deBuffer(buf, offset, maxlen);
        	default:
        		return -1;
        	}
        }
        
        /**
         * Writes to the socket from the buffer.
         *
         * @param buf the buffer to
         * @param offset the offset to begin writing data from
         * @param len the number of bytes to write
         * @return the number of bytes written or -1 if an error occurs.
         */
        public int write(byte[] buf, int offset, int len) {
        	switch(tcb.getState()){
        	case S_ESTABLISHED:
        	case S_CLOSE_WAIT:
        		//maximum data length
        		int max_data_len = MAX_TCPIP_SEGMENT_SIZE - IP_HEADER_LENGTH - TCPSegment.HEADER_LENGTH;
        		
        		//maybe the buffer is not long enough
        		if((buf.length - offset) < len){
        			len = buf.length - offset;
        		}
        		int bytesRemaining = len;
        		
        		//while there are still bytes to write, split data into processable sizes and buffer them.
        		while(bytesRemaining > 0){
        			byte[] data;
        			if(bytesRemaining >= max_data_len){
        				//send a packet of max size
        				data = new byte[max_data_len];
        				bytesRemaining -= max_data_len;
        			} else {
        				data = new byte[bytesRemaining];
        				bytesRemaining = 0;
        			}
        			//copy the bytes into a new buffer
        			for(int i = 0; i < data.length; i++){
        				data[i] = buf[offset];
        				offset++;
        			}
        			try {
						send_buf.buffer(data);
						synchronized(send_buf) {send_buf.notify(); }
					} catch (FullCollectionException e) {
						//no more bytes are written
						break;
					}
        		}
        		return len;
        	default:
        		return -1;
        	}
        }

        /**
         * Closes the connection for this socket.
         * Blocks until the connection is closed.
         *
         * @return true unless no connection was open.
         */
        public boolean close() {
        	switch(tcb.getState()){
        	case S_ESTABLISHED:
        		//send fin
        		TCPSegment fin = new TCPSegment(TCPSegmentType.FIN);
        		tcb.setState(ConnectionState.S_FIN_WAIT_1);
        		if (sendAndWaitAck(fin, false)){
        			tcb.setState(ConnectionState.S_FIN_WAIT_2);
        			return true;
        		}
        		//if we fail to receive a fin ack, force close
        		tcb.setState(ConnectionState.S_CLOSED);
        		return true;
            	
        	case S_CLOSE_WAIT:
        		//send fin
        		fin = new TCPSegment(TCPSegmentType.FIN);
        		tcb.setState(ConnectionState.S_LAST_ACK);
        		sendAndWaitAck(fin, false);
        		
        		//if fin sending fails, close anyway
        		tcb.setState(ConnectionState.S_CLOSED);
            	return true;
        	default:
        		Log.e("close() error", "can't close a non-open socket");
        		return false;
        	}
        }
        
        private class SenderThread implements Runnable {
        	public void run() {
				//only send packets it the connections hasn't been closed yet
				while(tcb.getState() == ConnectionState.S_ESTABLISHED || 
						tcb.getState() == ConnectionState.S_CLOSE_WAIT){
					if (!send_buf.isEmpty()){
						
						//create a new segment from the buffer
						byte[] temp = new byte[MAX_TCPIP_SEGMENT_SIZE];
						int size = send_buf.deBuffer(temp, 0, MAX_TCPIP_SEGMENT_SIZE);
						byte[] data = new byte[size];
						TCPSegment seg = new TCPSegment(TCPSegmentType.DATA, data);
						
						//increment the next sequence number before sending
						tcb.increaseNextSeqnr(size);
						
						int ntries = 0;
						isWaitingForAck = true;
						do {
							sockSend(seg);
							/*there's an innocent race condition here that makes us lose one second
							 * if this thread gets preempted here and the receive thread receives the
							 * packet and calls notify before this thread has entered wait state.
							 */
							try {
								//wait until the receiver thread receives an ack
								synchronized(waitingForAckMonitor){
									waitingForAckMonitor.wait(1000);
								}
							} catch (InterruptedException e) { e.printStackTrace(); }
							ntries++;
						} 
						while (ntries < MAX_TRIES && isWaitingForAck);
						
						//increment seqnr
						tcb.getAndIncrementSeqnr();
						
						//check if ack was received or timeout
						if (isWaitingForAck) {
							Log.e("Connection broken", "number of retries expired for ack");
							tcb.setState(ConnectionState.S_CLOSED);
							System.exit(-1);
						}
					} else {
						try {
							synchronized (send_buf) {
								send_buf.wait(1000);
							}
						} catch (InterruptedException e) { e.printStackTrace(); }
					}
				}
			}
        }
        
        private class ReceiverThread implements Runnable {
			
        	public void run() {
				while (tcb.getState() == ConnectionState.S_ESTABLISHED || 
						tcb.getState() == ConnectionState.S_FIN_WAIT_1 ||
						tcb.getState() == ConnectionState.S_FIN_WAIT_2){ 
					TCPSegment seg = sockRecv();
					switch(seg.getSegmentType()){
					case DATA:
						handleData(seg);
						recv_buf.notifyAll();
						break;
					case ACK:
						//notify the sender thread waiting for an ACK
						if (isWaitingForAck){
							isWaitingForAck = false;
							waitingForAckMonitor.notify();
						}
						break;
					case FIN:
						handleIncomingFin();
						break;
					default:
						//do nothing
					}
				}
			}
        }
    }

    /**
     * Constructs a TCP stack for the given virtual address.
     * The virtual address for this TCP stack is then
     * 192.168.1.address.
     *
     * @param address The last octet of the virtual IP address 1-254.
     * @throws IOException if the IP stack fails to initialize.
     */
    public TCP(int address) throws IOException {
        ip = new IP(address);
        ip_packet_id = 0;
        timeout = DEFAULT_TIMEOUT;
    }

    /**
     * @return a new socket for this stack
     */
    public Socket socket() {
        return new Socket();
    }
    
    /**
     * @return the IP address of the TCP stack
     */
    public String getIPAddress(){
    	
    	return this.ip.getLocalAddress().toString();
    	
    }

    /**
     * @return a new server socket for this stack bound to the given port
     * @param port the port to bind the socket to.
     */
    public Socket socket(int port) {
        return new Socket(port);
    }
    
    
    /**
     * encode data in a TCP packet, add header, calculate checksum and send the
     * packet through the IP layer
     * @return -
     * @param destination IP
     * @param packet id
     * @param packet
     * @throws IOException 
     */
	void send_tcp_segment(IpAddress destination, TCPSegment p) throws IOException{
    	//get integer value of IPAddress
		int destIpInt = destination.getAddress();
		
    	//calculate and set checksum
    	int source = ip.getLocalAddress().getAddress();
    	p.checksum = p.calculate_checksum(source, destIpInt, IP.TCP_PROTOCOL);
    	    	
    	//encode tcp packet
    	byte[] bytes = p.encode();
    	
    	    	
    	//create new packet and increment ID counter
    	Packet ip_packet = new Packet(destIpInt, IP.TCP_PROTOCOL, ip_packet_id,
    			bytes, bytes.length);
    	ip_packet.source = source;
    	ip_packet_id++;
    	
    	//log for debugging details
    	Log.d("send_tcp_segment()","Packet to be sent: " + p.toString());
    	Log.d("send_tcp_segment()","to IP : " + destination.toString() + " at port : " + p.dest_port);
    	
    	//send packet
    	ip.ip_send(ip_packet);
		
    }
    
	
	/**
     * receive a packet within a given time
     * @param timeout the timeout (seconds) to wait for a packet. If set to a value <= 0, it waits indefinitely.
	 * @throws InterruptedException
	 * @throws InvalidPacketException
     */
    TCPSegment recv_tcp_segment(int timeout) throws InvalidPacketException, InterruptedException{
    	Packet ip_packet = new Packet();
    	try {
	    	if(timeout > 0){
	    		ip.ip_receive_timeout(ip_packet, timeout);
	    	} else {
		    	ip.ip_receive(ip_packet);			
	    	}
    	} catch (IOException e) {
			Log.e("IP Receive Fail", "Failed receiving IP packet", e);
			e.printStackTrace();
			return null;
		}
    	
    	if(ip_packet.protocol != IP.TCP_PROTOCOL){
			//not for me
			return null;
		}
		//packet is too short to parse
		if(ip_packet.length < TCPSegment.HEADER_LENGTH || ip_packet.data.length < TCPSegment.HEADER_LENGTH 
				|| ip_packet.data.length < ip_packet.length){
			throw new InvalidPacketException("Packet too short");
		}
		
		//parse packet
		TCPSegment tcp_packet = TCPSegment.decode(ip_packet.data, ip_packet.length);
		
		//get source IP address which is used by the higher layers
		tcp_packet.source_ip = IpAddress.getAddress(ip_packet.source);
		
		Log.d("recv_tcp_segment()", "received packet: " + tcp_packet.toString());
		
		//validate checksum
		if (!tcp_packet.validateChecksum(ip_packet.source, ip_packet.destination)){
			//packet was corrupted because checksum is not correct
			throw new InvalidPacketException("Invalid checksum");
		}
		
		return tcp_packet;
    }
}
