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
	
	/** The underlying IP stack for this TCP stack. */
	private IP ip;

	/**packet ID which is initially zero and is incremented each time a packet is sent through the IP layer*/
	int ip_packet_id;
	
	/**the timeout for receiving packets*/
	public int timeout;
	
	/**the default for receiving packets*/
	public static final int DEFAULT_TIMEOUT = 1;
	
	/**MSL time (seconds) used in the connection termination timer*/
	public static final int MSL = 10;
	
	/**maximum number of tries waiting for an ack*/
	public static final int MAX_TRIES = 10;
	
	public static final int IP_HEADER_LENGTH = 20;
	
	/**the maximum size (bytes) of any packet sent through the ip layer*/
	public static final int MAX_TCPIP_SEGMENT_SIZE = 8192;

	/** Size of the send and receive buffer */
	public static final int BUFFER_SIZE = 128;
	
    /**
     * This class represents a TCP socket.
     *
     */
    public class Socket {

    	/* Hint: You probably need some socket specific data. */
    	boolean isClientSocket;
    	
		TCPControlBlock tcb;
		private BoundedByteBuffer sock_buf;
		
    	/**
    	 * Construct a client socket.
    	 */
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
        	tcb = new TCPControlBlock();
        	tcb.setLocalSocketAddress(new SocketAddress(ip.getLocalAddress(), port));
        	
        	sock_buf = new BoundedByteBuffer(BUFFER_SIZE);
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
        	
        	//construct a syn packet
        	TCPSegment syn_pck = new TCPSegment(
        			tcb.getLocalSocketAddress().getPort(),
        			port, tcb.generate_seqnr(), 
        			0,
        			TCPSegmentType.SYN, new byte[0]
        	);
        	
        	//send it and wait for a synack
        	tcb.setState(ConnectionState.S_SYN_SENT);
        	if (sendAndWaitAck(syn_pck, true)){
        		//send ack
        		TCPSegment ack = new TCPSegment(TCPSegmentType.ACK);
        		sockSend(ack);
        		
        		tcb.setState(ConnectionState.S_ESTABLISHED);
        		return true;
        	} else {
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
		        	try {
		            	//receive a packet from the network
						syn_pck = sockRecv();
						if(syn_pck.getSegmentType() == TCPSegmentType.SYN)
						{
							//initialize the connection state to S_SYN_RCVD
							tcb.initServer(syn_pck);
						}
						//else, discard it and listen again.
					} catch (InvalidPacketException e) {
						//wait for packet to arrive again.
					}
	            }
	            
	            //compose a synack message
	            TCPSegment syn_ack = new TCPSegment(TCPSegmentType.SYNACK);
	            
	            //try to send it
	            if (sendAndWaitAck(syn_ack, false)){
	            	tcb.setState(ConnectionState.S_ESTABLISHED);
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
				sock_buf.buffer(seg.data);
				
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
					} catch (InvalidPacketException e) {
						continue;
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
        
        private synchronized TCPSegment sockRecv(int timeout) throws InvalidPacketException, InterruptedException{
        	TCPSegment pck;
        	
        	while(true){
	        	pck = recv_tcp_segment(timeout);
	        	if(tcb.isValidSegment(pck)){
	        		break;
	        	}
        	}
        	
        	//TODO tcb.getAndIncrement_acknr(pck.getDataLength());
        	return pck;
        }
        
        private synchronized TCPSegment sockRecv() throws InvalidPacketException{
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
        	
        	pck.setSeqNr(tcb.getAndIncrement_seqnr(pck.getDataLength()));
        	pck.setAckNr(tcb.get_acknr());
        	pck.setDestPort(remoteAddr.getPort());
        	pck.setSrcPort(tcb.getLocalSocketAddress().getPort());
        	
        	try{
        		send_tcp_segment(remoteAddr.getIp(), pck);
        	} catch (IOException e) {
        		e.printStackTrace();
        		return false;
        	}
        	//TODO increment sequence number
        	return true;
        }
        
        
        /**
         * wrapper for sockSend. try to send a packet and wait for the acknowledgment.
         */
        private boolean sendAndWaitAck(TCPSegment pck, boolean doWaitForSynAck) {
        	int ntried = 0;

        	while (true){
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
        		if (!hasReceived){
        			ntried++;
					if (ntried >= MAX_TRIES)
						return false;
        		} else {
        			return true;
        		}
        		//wait for synack
        	}
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
					case SYNACK:
						//TODO what if ack of 3wh was lost? Compare current expected sequence number to the initial.
					default:
						//continue
					}
				} catch (InterruptedException e) {
					return false;
				} catch (InvalidPacketException i){
					//Discard it
				}
        	}
        }
        
        /**
         * same as waitForAck, only waits for a synack and discards data and fin packets
         */
        private boolean waitForSynAck(){
        	while(true){
        		try {
					TCPSegment seg = sockRecv(timeout);
					if ( seg.getSegmentType() == TCPSegmentType.SYNACK){
						tcb.initClient(seg);
						return true;
					}
        		} catch (InterruptedException e) {
        			return false;
        		} catch (InvalidPacketException i) {
        			continue;
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
        		
        		/*check if there are maxlen bytes in the buffer. If not, receive packets until we have enough.*/
        		while(sock_buf.length() < maxlen){
        		
	        		try {
						//receive a packet
	        			TCPSegment seg = sockRecv();
	        			
	        			switch(seg.getSegmentType()){
						case DATA:
							//we got what we wanted
							handleData(seg);
							break;
						case FIN: /*handle closing*/
							handleIncomingFin();
							//in case they also have closed the connection, return the last bytes in the buffer
							if (tcb.getState() == ConnectionState.S_CLOSED){
								return sock_buf.deBuffer(buf, offset, maxlen);
							}							
							break;
						case SYNACK:
							//TODO was the ack lost?
						case ACK:
							//TODO check seqnr, 
						default:
							//discard the packet
							continue;
						}
	        			
					} catch (InvalidPacketException e) {
						try {
							Thread.sleep(1000);
						} catch (InterruptedException e1) {e1.printStackTrace();} //do nothing
						//wait for packet to arrive again
						continue;
					}
        		}
        	case S_CLOSE_WAIT: //they have already closed the connection, or we received enough data
        		return sock_buf.deBuffer(buf, offset, maxlen);
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
        		
        		//while there are still bytes to write, split data into packets and send them.
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
        			for(int i = 0; i < data.length; i++){
        				data[i] = buf[offset];
        				offset++;
        			}
        			//create new packet
        			TCPSegment pck = new TCPSegment(TCPSegmentType.DATA, data);
        			
        			//send packet
        			sendAndWaitAck(pck, false);
        			
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
		
		//validate checksum
		if (!tcp_packet.validateChecksum(ip_packet.source, ip_packet.destination)){
			//packet was corrupted because checksum is not correct
			throw new InvalidPacketException("Invalid checksum");
		}
		
		return tcp_packet;
    }
}