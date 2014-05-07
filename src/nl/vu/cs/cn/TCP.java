package nl.vu.cs.cn;

import java.io.IOException;

import android.util.Log;
import nl.vu.cs.cn.IP.*;
import nl.vu.cs.cn.TCPControlBlock.ConnectionState;

/**
 * This class represents a TCP stack. It should be built on top of the IP stack
 * which is bound to a given IP address.
 */
public class TCP {
	
	/** The port a client socket will bind to automatically */
	public static final int defaultClientPort = 12345;
	
	/** the ip address used to indicate a server accepts connections from any IP addresses*/
	public static final IpAddress INADDR_ANY = IpAddress.getAddress(0);

	/** The underlying IP stack for this TCP stack. */
	private IP ip;

	
	
	
	
	/**packet ID which is initially zero and is incremented each time a packet is sent through the IP layer*/
	int ip_packet_id;
	
    /**
     * This class represents a TCP socket.
     *
     */
    public class Socket {

    	/* Hint: You probably need some socket specific data. */
    			
		TCPControlBlock tcb;
		
    	/**
    	 * Construct a client socket.
    	 */
    	private Socket() {
    		this(defaultClientPort);
    	}

    	/**
    	 * Construct a server socket bound to the given local port.
		 *
    	 * @param port the local port to use
    	 */
        private Socket(int port) {
        	tcb = new TCPControlBlock();
        	tcb.bind(INADDR_ANY, port);
        }

		/**
         * Connect this socket to the specified destination and port.
         *
         * @param dst the destination to connect to
         * @param port the port to connect to
         * @return true if the connect succeeded.
         */
        public boolean connect(IpAddress dst, int port) {

            // Implement the connection side of the three-way handshake here.
        	tcb.their_port = port;
        	tcb.their_ip_addr = dst;
        	
        	
        	return false;
        }	

        /**
         * Accept a connection on this socket.
         * This call blocks until a connection is made.
         */
        public void accept() {
        	
        	tcb.setState(ConnectionState.S_LISTEN);
            try {
            	//receive a packet from the network
				TCPSegment syn_pck = recv_tcp_segment();
				//update connection source
				tcb.setSource(syn_pck);
				//do stuff
			} catch (CorruptedPacketException e) {
				//wait for packet to arrive again.
				
			}
        	// Implement the receive side of the three-way handshake here.
            
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
//        	if(block.state != ConnectionState.S_ESTABLISHED){
//        		return -1;
//        	}
            // Read from the socket here.
        	
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
        	if(tcb.getState() != ConnectionState.S_ESTABLISHED){
        		return -1;
        	}
            // Write to the socket here.
        	
        	//TCPHeader header = new TCPHeader(src_port, dest_port, seq_nr, ack_nr, ack, syn, fin);
        	
        	
            return -1;
        }

        /**
         * Closes the connection for this socket.
         * Blocks until the connection is closed.
         *
         * @return true unless no connection was open.
         */
        public boolean close() {

            // Close the socket cleanly here.

            return false;
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
    	Packet ip_packet = new Packet(destIpInt, IP.TCP_PROTOCOL, ip_packet_id++,
    			bytes, bytes.length);
    	ip_packet.source = source;
    	
    	//send packet
    	ip.ip_send(ip_packet);
		
    }
    
	/**
     * receive a packet
	 * @throws CorruptedPacketException
     */
	public TCPSegment recv_tcp_segment() throws CorruptedPacketException{
		try {
			return recv_tcp_segment(0);
		} catch (InterruptedException e) {
			// never happens since there is no timeout
			return null;
		}
		
	}
	/**
     * receive a packet within a given time
     * @param timeout the timeout (seconds) to wait for a packet. If set to a value <= 0, it waits indefinitely.
	 * @throws InterruptedException
	 * @throws CorruptedPacketException
     */
    public TCPSegment recv_tcp_segment(int timeout) throws CorruptedPacketException, InterruptedException{
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
			throw new CorruptedPacketException("Packet too short");
		}
		
		//parse packet
		TCPSegment tcp_packet = TCPSegment.decode(ip_packet.data, ip_packet.length);
		//get source IP address which is used by the higher layers
		tcp_packet.source_ip = ip_packet.source;
		
		//validate checksum
		if (!tcp_packet.validateChecksum(ip_packet.source, ip_packet.destination)){
			//packet was corrupted because checksum is not correct
			throw new CorruptedPacketException("Invalid checksum");
		}
		
		return tcp_packet;
    }
}