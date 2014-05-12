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
	
	public int maxTries;
	
	public static final int DEFAULT_MAX_TRIES = 10;
	
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
        	
        	//generate new sequence number
        	long seq_nr = tcb.generate_seqnr();
        	
        	TCPSegment syn_pck = new TCPSegment(tcb.getLocalSocketAddress().getPort(), port, seq_nr, 0,
        			TCPSegmentType.SYN, null);
        	
        	
        	
        	while(tcb.getState() != ConnectionState.S_ESTABLISHED){
        		//send syn_pck
        		try {
					send_tcp_segment(dst, syn_pck);
					tcb.setState(ConnectionState.S_SYN_SENT);
				} catch (IOException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
        		        		
        		//try to receive synack in timeout.
        		
        		
        	}
        	//send ack.
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
        	
        	tcb.setState(ConnectionState.S_LISTEN);
        	
        	TCPSegment syn_pck = null;
        	
            while (tcb.getState() == ConnectionState.S_LISTEN){
	        	try {
	            	//receive a packet from the network
					syn_pck = sockRecv();
					if(tcb.isValidSegment(syn_pck) &&  syn_pck.getSegmentType() == TCPSegmentType.SYN)
					{
						//update connection source
						tcb.setRemoteSocketAddress(syn_pck.getSrcSocketAddress());
						//generate sequence number, update state, and acknowledgment number.
						
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
            long seq_nr = tcb.generate_seqnr();
            SocketAddress srcAddr = tcb.getLocalSocketAddress(), destAddr = tcb.getRemoteSocketAddress();
            TCPSegment syn_ack = new TCPSegment(srcAddr.getPort(), destAddr.getPort(), seq_nr, syn_pck.seq_nr, 
        			TCPSegmentType.SYNACK, null);
            
            //try to send the packet
            while(tcb.getState() == ConnectionState.S_SYN_RCVD){            	
            	//try to send a synack, resend if it fails
            	try {
					sockSend(destAddr.getIp(), syn_ack);
					//start timed wait for ack
				} catch (IOException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
            	
            	//if an ack is received:
            	tcb.setState(ConnectionState.S_ESTABLISHED);
            }
        }
        
        private void handleData(TCPSegment seg){
        	//put data in buffer, send ack
        }
        
        private TCPSegment sockRecv(int timeout) throws InvalidPacketException, InterruptedException{
        	TCPSegment pck = recv_tcp_segment(timeout);
        	if(!tcb.isValidSegment(pck)){
        		throw new InvalidPacketException("Received a packet not for us");
        	}
        	tcb.getAndIncrement_acknr(pck.getDataLength());
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
        
        private void sockSend(IpAddress destination, TCPSegment pck) throws IOException{
        	pck.setSeqNr(tcb.getAndIncrement_seqnr(pck.getDataLength()));
        	pck.setAckNr(tcb.get_acknr());
        	send_tcp_segment(destination, pck);
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
        	case S_CLOSE_WAIT:
        	case S_FIN_WAIT_1:
        	case S_FIN_WAIT_2:
        		//check if there are maxlen bytes in the buffer
        		//if so, return bytes in the buffer
        		
        		//if not: receive data from the network???
        		boolean ToReceived = true;
        		
        		while(ToReceived){
        		
	        		try {
						//receive a packet
	        			TCPSegment seg = sockRecv(1);
	        			
	        			switch(seg.getSegmentType()){
						case DATA:
							//we got what we received
							handleData(seg);
							break;
						default:
							
						}
	        			
					} catch (InvalidPacketException e) {
						try {
							Thread.sleep(1000);
						} catch (InterruptedException e1) {e.printStackTrace();} //do nothing
						//wait for packet to arrive again
						continue;
					} catch (InterruptedException e) {
						break;
					}
        		}
        		
        		break;
        	default:
        		return -1;
        	}
        	//read data
        	
        	
        	
            return -1;
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
        		break;
        	default:
        		return -1;
        	}
            // Write to the socket.
        	
            return -1;
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
            	//TODO send close message
            	tcb.setState(ConnectionState.S_FIN_WAIT_1);
            	
            	//wait for ack.
            	TCPSegment segment;
            	int tries = 0;
            	while(tcb.getState() == ConnectionState.S_FIN_WAIT_1){
					try {
						segment = sockRecv(timeout);
						switch(segment.getSegmentType()){
		            	case ACK:
		            		tcb.setState(ConnectionState.S_FIN_WAIT_2);
		            		break;
		            	case FIN:
		            		//do simultaneous closing procedure
		            		break;
		            	case DATA:
		            		//handle data and wait again
		            		break;
		            	default:
		            		Log.e("close() error", "received unexpected packet");
		            	}
					} catch (Exception e) {
						if (++tries >= maxTries){
							Log.e("close() error", "exceeded maximum number of receive tries");
							return false;
						}
					}
            	}
            	return true;
        	case S_CLOSE_WAIT:
        		//send fin ack
        		tcb.setState(ConnectionState.S_LAST_ACK);
        		
        		//wait for ack. if not, resend. else:
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
        maxTries = DEFAULT_MAX_TRIES;
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
	public void send_tcp_segment(IpAddress destination, TCPSegment p) throws IOException{
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
    public TCPSegment recv_tcp_segment(int timeout) throws InvalidPacketException, InterruptedException{
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