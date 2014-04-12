package nl.vu.cs.cn;

import java.io.IOException;

import android.util.Log;
import nl.vu.cs.cn.IP.IpAddress;
import nl.vu.cs.cn.IP.Packet;

/**
 * This class represents a TCP stack. It should be built on top of the IP stack
 * which is bound to a given IP address.
 */
public class TCP {

	/** The underlying IP stack for this TCP stack. */
	private IP ip;

    /**
     * This class represents a TCP socket.
     *
     */
    public class Socket {

    	/* Hint: You probably need some socket specific data. */
    	IpAddress dst;
		IpAddress src;
		int port;
		byte[] buf;
		int offset;
		int maxlen;
		int len;
    	/**
    	 * Construct a client socket.
    	 */
    	private Socket() {
    		
    	}

    	/**
    	 * Construct a server socket bound to the given local port.
		 *
    	 * @param port the local port to use
    	 */
        private Socket(int port) {
        	this.port = port;       
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
        	ip.getLocalAddress();

            return false;
        }

        /**
         * Accept a connection on this socket.
         * This call blocks until a connection is made.
         */
        public void accept() {

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
    }

    /**
     * @return a new socket for this stack
     */
    public Socket socket() {
        return new Socket();
    }

    /**
     * @return a new server socket for this stack bound to the given port
     * @param port the port to bind the socket to.
     */
    public Socket socket(int port) {
        return new Socket(port);
    }
    
    /**
     * this class represents a TCP header
     */
    public static class TCPHeader{
    	int src_port, dest_port;
    	int[] unused_flags = {0, 0, 0};
    	int ns, cwr, ece, urg, ack, psh, rst, syn, fin, window_size, checksum, urgent_pointer;
    	long seq_nr, ack_nr;
    	public static final int HEADER_LENGTH = 20;
    	public static final byte DATA_OFFSET = 0x05;
    	
    	TCPHeader(int src_port, int dest_port, long seq_nr, long ack_nr,
    			int ack, int syn, int fin
    			){
    		
    		//set other flags unused by this implementation
    		cwr = 0; ece = 0; urg = 0; psh = 1; rst = 0; ns = 0; 
    		window_size = 1; urgent_pointer = 0;
    		
    		//checksum is initially zero
    		checksum = 0;
    		
    		//set other fields
    		this.src_port = src_port;
    		this.dest_port = dest_port;
    		this.seq_nr = seq_nr;
    		this.ack_nr = ack_nr;
    		this.ack = ack;
    		this.syn = syn;
    		this.fin = fin;
    	}
    	
    	TCPHeader(int src_port, int dest_port, long seq_nr, long ack_nr,
    			int ack, int syn, int fin, int checksum){
    		this(src_port, dest_port, seq_nr, ack_nr,
        			ack, syn, fin);
    		this.checksum = checksum;
    	}
    	
    	public byte[] encode(){
    		byte[] result = new byte[HEADER_LENGTH];
    		
    		//add source port
    		result[0] = (byte) (src_port>>8);
    		result[1] = (byte) src_port;
        	//add destination port
    		result[2] = (byte) (dest_port>>8);
    		result[3] = (byte) dest_port;
    		//add sequence number
    		for(int i = 4; i < 8; i++){
    			result[i] = (byte) (seq_nr>>((i - 4) * 8));
    		}
    		//add acknowledgment number
    		for(int i = 8; i < 12; i++){
    			result[i] = (byte) (ack_nr>>((i - 8) * 8));
    		}
    		//add flags
    		result[12] = (byte) ((DATA_OFFSET << 4) | (byte) ns); //unused 3 bits are 0
    		result[13] = (byte) (((byte) (cwr <<7)) |
    				((byte) (ece <<6)) |
    				((byte) (urg <<5)) |
    				((byte) (ack <<4)) |
    				((byte) (psh <<3)) |
    				((byte) (rst <<2)) |
    				((byte) (syn <<1)) |
    				((byte) fin));
    		//add window size
    		result[14] = (byte) (window_size >>8);
    		result[15] = (byte) window_size;
    		
    		//add checksum
    		result[16] = (byte) (checksum >>8);
    		result[17] = (byte) checksum;
    		
    		//add urgent pointer
    		result[18] = (byte) (urgent_pointer >>8);
    		result[19] = (byte) urgent_pointer;
    		
    		return result;
    	}
    	
    	public static TCPHeader decode(byte[] array){
    		int src_port = (((int) array[0]) <<8) | (int)array[1];
    		
    		int dest_port = (((int) array[2]) <<8) | (int)array[3];
    		
    		long seq_nr = 0, ack_nr = 0;
    		for(int i = 4; i < 8; i++){
    			seq_nr |= (long) (array[i]);
    		}
    		for(int i = 8; i < 12; i++){
    			ack_nr |= (long) (array[i]);
    		}
    		
    		//skip result[12]
    		int ack = (int)((array[13] & 0x10) >>4);
    		int syn = (int)((array[13] & 0x02) >>1);
    		int fin = (int)(array[13] & 0x01);
    		
    		int checksum = (((int) array[16]) <<8) | ((int) array[17]);
    		
    		return new TCPHeader(src_port, dest_port, seq_nr, ack_nr,
        			ack, syn, fin, checksum);
    	}
    }
    /**
     * encode data in a TCP packet, add header, calculate checksum and send the
     * packet through the IP layer
     * @return -
     * @param destination IP
     * @param packet id
     * @param data bytes
     * @param header
     */
    @SuppressWarnings("unused")
	private void send_tcp_packet(int destination, int id, byte[] data, TCPHeader header){
    	
    	//encode header
    	byte[] headerbytes = header.encode();
    	//TODO calculate checksum
    	
    	//add header and body to packet
    	byte[] tcpdata = new byte[data.length + TCPHeader.HEADER_LENGTH];
    	    	
    	//add header to packet body
    	int i;
    	for (i = 0; i < TCPHeader.HEADER_LENGTH; i++){
    		tcpdata[i] = headerbytes[i]; 
    	}
    	//add data to packet header
    	for(int j = 0; j < data.length; j++){
    		tcpdata[i] = data[j];
    		i++;
    	}
    	//TODO calculate IP packet length
    	int length = 0;
    	
    	//create new packet
    	Packet ip_packet = new Packet(destination, IP.TCP_PROTOCOL, id,
                tcpdata, length);
    	
    	//send packet
    	try {
			ip.ip_send(ip_packet);
		} catch (IOException e) {
			Log.e("IPSendFail", "Failed sending IP packet", e);
			e.printStackTrace();
		}
    }
    
    /**
     * receives a packet
     */
    @SuppressWarnings("unused")
    private void recv_tcp_packet() throws CorruptedPacketException{
    	Packet p = new Packet();
    	try {
			ip.ip_receive(p);
			if(p.protocol != IP.TCP_PROTOCOL){
				//not for me
				return;
			}
			if(p.length < TCPHeader.HEADER_LENGTH){
				throw new CorruptedPacketException("Packet too short");
			}
			
			//extract header
			byte[] headerBytes = new byte[TCPHeader.HEADER_LENGTH];
			
			int i;
			for(i = 0; i < TCPHeader.HEADER_LENGTH; i++){
				headerBytes[i] = p.data[i];
			}
			
			//extract data
			byte[] dataBytes = new byte[p.length - TCPHeader.HEADER_LENGTH];
			for(; i < p.length; i++){
				dataBytes[i - TCPHeader.HEADER_LENGTH] = p.data[i];
			}
			
			//parse header
			TCPHeader header = TCPHeader.decode(headerBytes);
			
			//TODO validate checksum
			int checksum = header.checksum;
			/*
			 * if (computeChecksum(packet) != checksum){
			 * 		throw new CorruptedPacketException("Invalid checksum");
			 * }
			 */
			
			//TODO check destination port and send to specific socket
			
		} catch (IOException e) {
			Log.e("IPRcvFail", "Failed receiving IP packet", e);
			e.printStackTrace();
		}
    	
    }
}