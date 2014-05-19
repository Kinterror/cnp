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
	public static final int defaultClientPort = 12345;
	
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
	
    /**
     * This class represents a TCP socket.
     *
     */
    public class Socket {

    	/* Hint: You probably need some socket specific data. */
    	boolean isClientSocket;
    	
		TCPControlBlock tcb;
		private UnboundedByteBuffer sock_buf;
		
    	/**
    	 * Construct a client socket.
    	 */
    	private Socket() {
    		this(defaultClientPort);
    		isClientSocket = true;
    	}

    	/**
    	 * Construct a server socket bound to the given local port.
		 *
    	 * @param port the local port to use
    	 */
        private Socket(int port) {
        	isClientSocket = false;
        	tcb = new TCPControlBlock();
        	tcb.setLocalSocketAddress(new SocketAddress(ip.getLocalAddress(), port));
        	
        	sock_buf = new UnboundedByteBuffer();
        }

		/**
         * Connect this socket to the specified destination and port.
         *
         * @param dst the destination to connect to
         * @param port the port to connect to
         * @return true if the connect succeeded.
         */
        public boolean connect(IpAddress dst, int port) {
        	
        	if (!isClientSocket || tcb.getState() != ConnectionState.S_CLOSED){
        		return false;
        	}
        	
            // Implement the connection side of the three-way handshake here.
        	tcb.setRemoteSocketAddress(new SocketAddress(dst, port));
        	
        	TCPSegment syn_pck = new TCPSegment(TCPSegmentType.SYN);
        	
        	//add port and sequence number fields
        	syn_pck.setSeqNr(tcb.generate_seqnr());
        	syn_pck.dest_port = port;
        	syn_pck.src_port = tcb.getLocalSocketAddress().getPort();
        	
        	int ntries = 0;
        	
        	while(tcb.getState() != ConnectionState.S_ESTABLISHED && ntries++ < MAX_TRIES){
        		//send syn_pck
        		try {
					send_tcp_segment(dst, syn_pck);
					tcb.setState(ConnectionState.S_SYN_SENT);
				} catch (IOException e) {
					e.printStackTrace();
				}
        		
        		//try to receive synack in timeout.
        		try {
					TCPSegment syn_ack = sockRecv(timeout);
					switch(syn_ack.getSegmentType()){
					case SYNACK:
						break;
					default:
						continue;
					}
				} catch (Exception e) {
					//resend
					continue;
				}
        		//send ack
        		TCPSegment ack = new TCPSegment(TCPSegmentType.ACK);
        		sockSend(ack);
        		tcb.setState(ConnectionState.S_ESTABLISHED);        		
        	}
        	
        	//if connect failed for MAX_TRIES times
        	if (ntries >= MAX_TRIES){
        		return false;
        	}
        	return true;
        }	

        /**
         * Accept a connection on this socket.
         * This call blocks until a connection is made.
         */
        public void accept() {
        	
        	//client sockets and opened cannot accept
        	if(isClientSocket){
        		Log.e("accept() error","Called accept() on a client socket");
        		System.exit(-1);
        	} else if (tcb.getState() != ConnectionState.S_CLOSED){
        		Log.e("accept() error","Called accept() on an opened socket");
        		System.exit(-1);
        	}
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
							//update connection source
							tcb.setRemoteSocketAddress(syn_pck.getSrcSocketAddress());
							//generate sequence number, update state, and acknowledgment number.
							tcb.generate_seqnr();
							tcb.set_acknr(syn_pck.seq_nr);
							tcb.setState(ConnectionState.S_SYN_RCVD);
				        	// Implement the receive side of the three-way handshake here.
						}
						//else, discard it and listen again.
					} catch (InvalidPacketException e) {
						//wait for packet to arrive again.
					}
	            }
	            
	            //compose a synack message
	            
	            TCPSegment syn_ack = new TCPSegment(TCPSegmentType.SYNACK);
	            
	            //try to send the synack packet and wait for ack MAX_TRIES times
	            for(int ntries = 0; ntries < MAX_TRIES; ntries++){            	
	            	//try to send a synack, resend if it fails
					sockSend(syn_ack);
					//start timed wait for ack
					
					try {
						TCPSegment ack = sockRecv(timeout);
						
						switch(ack.getSegmentType()){
						case ACK:
							tcb.setState(ConnectionState.S_ESTABLISHED);
							break;
						default:
							//received some other packet. Do nothing.
							continue;
						}
		            	break;
					} catch (Exception e) {
						//do nothing; continue.
					}
	            }
	            
	            /*either:
	             *tries expired. Go back to listening state and try to listen again.
	             *or:
	             *established. In this case, return.
	            */
        	}
        }
        
        /**
         * buffer the received data and send an ack
         */
        private void handleData(TCPSegment seg){
        	//put the data at the end of the buffer
        	sock_buf.buffer(seg.data);
        	
        	//send ack
        	TCPSegment ack = new TCPSegment(TCPSegmentType.ACK);
			sockSend(ack);
        }
        
        /**here, process received fin packet and ack it.*/
        private void handleIncomingFin(){
        	/*
        	 * if we were in established state, go to close_wait state and send an ack.
        	 * if we were already in close_wait, and receive a fin again, resend the ack since it was apparently lost.
        	 */
        	if (tcb.getState() == ConnectionState.S_ESTABLISHED || tcb.getState() == ConnectionState.S_CLOSE_WAIT){
	        	tcb.setState(ConnectionState.S_CLOSE_WAIT);
	        	//send ack
	        	TCPSegment ack = new TCPSegment(TCPSegmentType.ACK);
				sockSend(ack);				
        	} else if (tcb.getState() == ConnectionState.S_FIN_WAIT_2){
        		//send last ack
        		TCPSegment ack = new TCPSegment(TCPSegmentType.ACK);
				sockSend(ack);
				tcb.setState(ConnectionState.S_TIME_WAIT);
				
				//start timed receive for 10 seconds
				
				long timeExpired = 0;
				long initialTime = System.currentTimeMillis();
				
				for (int timer = MSL; timer > 0; timer -= (timeExpired / 1000)){
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
        	}
        	//else, do nothing
        }
        
        private TCPSegment sockRecv(int timeout) throws InvalidPacketException, InterruptedException{
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
        
        private TCPSegment sockRecv() throws InvalidPacketException{
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
        
        private boolean sockSend(TCPSegment pck) {
        	SocketAddress remoteAddr = tcb.getRemoteSocketAddress();
        	
        	pck.setSeqNr(tcb.getAndIncrement_seqnr(pck.getDataLength()));
        	pck.setAckNr(tcb.get_acknr());
        	pck.dest_port = remoteAddr.getPort();
        	
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
        private boolean sendAndWaitAck(TCPSegment pck) {
        	int ntried = 0;

        	while (true){
        		//try to send packet with right port and sequence numbers.
        		if (!sockSend(pck))
        			return false;
        		
        		//wait for ack
        		if (!waitForAck()){
        			ntried++;
					if (ntried >= MAX_TRIES)
						return false;
        		} else {
        			return true;
        		}
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
						break;
					default:
						continue;
					}
				} catch (Exception e) {
					return false;
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
        			sendAndWaitAck(pck);
        			
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
        	TCPSegment segment, fin;
        	
        	switch(tcb.getState()){
        	case S_ESTABLISHED:
        		//send fin
        		fin = new TCPSegment(TCPSegmentType.FIN);
        		tcb.setState(ConnectionState.S_FIN_WAIT_1);
        		if (sendAndWaitAck(fin)){
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
        		sendAndWaitAck(fin);
        		
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
	private void send_tcp_segment(IpAddress destination, TCPSegment p) throws IOException{
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
    private TCPSegment recv_tcp_segment(int timeout) throws InvalidPacketException, InterruptedException{
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