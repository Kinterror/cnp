package nl.vu.cs.cn;

/**
 * this class represents the TCP header fields and encode and decode operations
 */

public class TCPSegment{
	//ports
	int src_port, dest_port;
	//unused flags. See RFC 793 for more details.
	//these fields' semantics are not supported in this implementation. They exist just for the sake of completeness.
	int[] unused_flags = {0, 0, 0};
	int ns, cwr, ece, urg, rst;
	int window_size, urgent_pointer;
	//used flags.
	int ack, psh, syn, fin;
	short checksum;
	//sequence numbers
	long seq_nr, ack_nr;
	public static final byte DATA_OFFSET = 0x05;
	
	byte[] data;
	
	//these fields are not formally a part of the TCP header; however, in our implementation they are used. 
	public static final int HEADER_LENGTH = 20;
	int source_ip;
	
	//constructor for a packet without specified checksum
	TCPSegment(int src_port, int dest_port, long seq_nr, long ack_nr,
			int ack, int syn, int fin, byte[] data){
		
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
		this.data = new byte[data.length];
		for(int i = 0; i < data.length; i++){
			this.data[i] = data[i];
		}
		
		
	}
	
	//constructor with checksum
	TCPSegment(int src_port, int dest_port, long seq_nr, long ack_nr,
			int ack, int syn, int fin, byte[] data, short checksum){
		this(src_port, dest_port, seq_nr, ack_nr,
    			ack, syn, fin, data);
		this.checksum = checksum;
	}
	
	/**
	 * Encode a TCP segment into a byte array.
	 * 
	 * @return the byte array
	 */
	public byte[] encode(){
		byte[] result = new byte[HEADER_LENGTH + data.length];
		
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
		
		//copy data
		for(int i = 0; i < data.length; i++){
			result[i + HEADER_LENGTH] = data[i];
		}
		
		return result;
	}
	
	/**
	 * Decode a byte array representing a TCP segment.
	 * 
	 * @param array
	 * @param length of the array (might be smaller than array.length), which is at least HEADER_LENGTH
	 * @return TCPpacket deserialized from array
	 */
	public static TCPSegment decode(byte[] array, int length){
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
		
		//left part of checksum, useful for debugging (Java Signedness is bothersome)
		int c1 = ((int) array[16]) & 0x000000FF;
		//right part
		int c2 = ((int) array[17]) & 0x000000FF;
		//add them together in a short
		short checksum = (short) (c1 <<8 | c2);
		
		
		byte[] data = new byte[length - HEADER_LENGTH];
		for(int i = 0; i < data.length; i++){
    		data[i] = array[i + HEADER_LENGTH];
    	}
		
		return new TCPSegment(src_port, dest_port, seq_nr, ack_nr,
    			ack, syn, fin, data, checksum);
	}
	
	
	/**
	 * Compute the checksum of a TCP segment. 
	 * 
	 * @param source
	 * @param dest
	 * @param protocol
	 * @return the checksum
	 */
	short calculate_checksum(int source, int dest, int protocol){
		int sum = 0;
		
		//add source address
		sum += (source>>16) & 0xffff;
		sum += (source & 0xffff);
		
		//add destination address
		sum += (dest>>16) & 0xffff;
		sum += (dest & 0xffff);
		
		//add protocol (and zeroes)
		sum += protocol & 0xff;
		
		//add length
		sum += (data.length + HEADER_LENGTH) & 0xffff;
		
		//temporarily write away the checksum field
		short temp = checksum;
		checksum = 0;
		
		//add tcp header. For readability and making the computation of the checksum easier, we encode it here.
		byte[] temp_array = encode();
		
		//set back checksum field
		checksum = temp;
		
		int i = 0;
		int tcplen;
		for(tcplen = temp_array.length; tcplen > 1; tcplen-=2){
			sum += ((char)temp_array[i])<<8 | ((char)temp_array[i+1]);
			i += 2;
		}
		if (tcplen > 0){
			//add zero padding byte
			sum += temp_array[i]<<8;
		}
		while(sum>>16 != 0){
			sum = (sum>>16) + (sum & 0x0000FFFF);
		}
		
		//one's complement
		return (short) ~sum;
	}
	
	/**
	 * Compare two checksums to see if they match.
	 * 
	 * @param source
	 * @param dest
	 * @return true if the checksums match
	 */
	public boolean validateChecksum(int source, int dest){
		return (checksum == calculate_checksum(source, dest, IP.TCP_PROTOCOL));
	}
	
	
	/**
	 * Convert the data byte array of a TCP segment into a String object.
	 * 
	 * @return the string object representing the data.
	 */
	private String arrayString() {
    	StringBuffer dataString = new StringBuffer("[");
    	for (int i = 0; i < data.length; i++) {
    		if (i > 0) {
        		dataString.append(",");
    		}
    		dataString.append(data[i]);
    	}
    	dataString.append("]");
    	return dataString.toString();
    }

	
	/**
	 * Convert a TCP segment (header and data) into a String object.
	 * 
	 * @return the string object 
	 */
	public String toString(){
		return "Source port: " + src_port + " Dest port: " + dest_port + " ack: "
                + ack + " syn: " + syn + " fin: " + fin
                + " checksum: " + checksum + " seqnr: " + seq_nr + " acknr: " + ack_nr + " header length: " 
                + HEADER_LENGTH + " data: " + arrayString();
	}
}