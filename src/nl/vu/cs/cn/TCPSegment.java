package nl.vu.cs.cn;

import nl.vu.cs.cn.IP.IpAddress;

/**
 * this class represents the TCP header fields and encode and decode operations
 */

class TCPSegment{
	//ports
	int src_port, dest_port;
	//unused flags. See RFC 793 for more details.
	//these fields' semantics are not supported in this implementation. They exist just for the sake of completeness.
	byte ns, cwr, ece, urg, rst;
	int windowSize, urgent_pointer;
	byte reserved;
	//used flags.
	int ack, psh, syn, fin;
	short checksum;
	//sequence numbers
	long seq_nr, ack_nr;
	//data offset
	byte dataOffset;
	
	byte[] data;
	
	/**
	 * all possible TCP segment types
	 */
	enum TCPSegmentType{
		SYN, ACK, SYNACK, FIN, FINACK, DATA, INVALID
	}
	
	//these fields are not formally a part of the TCP header; however, in our implementation they are used. 
	static final int HEADER_LENGTH = 20;
	static final int CHECKSUM_OFFSET = 16;
	IpAddress source_ip;
	
	/**constructor without checksum*/
	TCPSegment(int src_port, int dest_port, long seq_nr, long ack_nr,
			TCPSegmentType st, byte[] data){
		this(src_port, dest_port, seq_nr, ack_nr,
    			st, data, (short) 0);
	}
	
	/**constructor for a segment with specified checksum*/
	TCPSegment(int src_port, int dest_port, long seq_nr, long ack_nr,
			TCPSegmentType st, byte[] data, short checksum){
		this(src_port, dest_port, seq_nr, ack_nr, 0, 0, 0, data, checksum);
		setControlFlags(st);
	}
	
	/** constructor with specified flags */
	private TCPSegment(int src_port, int dest_port, long seq_nr, long ack_nr,
			int syn, int ack, int fin, byte[] data, short checksum){

		//set other flags unused by this implementation
		cwr = 0; ece = 0; urg = 0; psh = 1; rst = 0; ns = 0; 
		windowSize = 1; urgent_pointer = 0; dataOffset = 0x05; reserved = 0;
		
		//set other fields
		this.src_port = src_port;
		this.dest_port = dest_port;
		this.seq_nr = seq_nr;
		this.ack_nr = ack_nr;
		
		this.syn = syn;
		this.ack = ack;
		this.fin = fin;
		
		this.checksum = checksum;
		
		//add data
		if(data != null){
			this.data = new byte[data.length];
			for(int i = 0; i < data.length; i++){
				this.data[i] = data[i];
			}
		}
	}
	
	/**
	 * sets the right control flags according to the specified segment type.
	 */
	void setControlFlags(TCPSegmentType st){
		//set flags
		syn = ack = fin = 0;
		
		switch(st){
		case INVALID:
			fin = 1; //should never happen
		case SYNACK:
			ack = 1;
		case SYN:
			syn = 1;
		case DATA:
			break;
		case FINACK:
			fin = 1;
		case ACK:
			ack = 1;
			break;
		case FIN:
			fin = 1;
			break;			
		}
	}
	
	/**
	 * Encode a TCP segment into a byte array.
	 * 
	 * @return the byte array
	 */
	byte[] encode(){
		
		//check if there is any data. If data = null: skip this.
		int dataLength = data.length;
		
		byte[] result = new byte[HEADER_LENGTH + dataLength];
		
		//add source port
		result[0] = (byte) (src_port>>8);
		result[1] = (byte) src_port;
    	//add destination port
		result[2] = (byte) (dest_port>>8);
		result[3] = (byte) dest_port;
		//add sequence number
		for(int i = 4; i < 8; i++){
			result[i] = (byte) (seq_nr>>(24 - (i - 4) * 8));
		}
		//add acknowledgment number
		for(int i = 8; i < 12; i++){
			result[i] = (byte) (ack_nr>>(24 - (i - 8) * 8));
		}
		//add flags
		result[12] = (byte) ((dataOffset << 4) |
				((reserved & 0x07) <<1) |
				(ns & 0x01));
		result[13] = (byte) (((byte) (cwr <<7)) |
				((byte) (ece <<6)) |
				((byte) (urg <<5)) |
				((byte) (ack <<4)) |
				((byte) (psh <<3)) |
				((byte) (rst <<2)) |
				((byte) (syn <<1)) |
				((byte) fin));
		//add window size
		result[14] = (byte) (windowSize >>8);
		result[15] = (byte) windowSize;
		
		//add checksum
		result[16] = (byte) (checksum >>8);
		result[17] = (byte) checksum;
		
		//add urgent pointer
		result[18] = (byte) (urgent_pointer >>8);
		result[19] = (byte) urgent_pointer;
		
		//copy data
		for(int i = 0; i < dataLength; i++){
			result[i + HEADER_LENGTH] = data[i];
		}
		
		return result;
	}
	
	/**
	 * Decode a byte array representing a TCP segment.
	 * 
	 * @param array
	 * @param length of the array (might be smaller than array.length), which is at least HEADER_LENGTH
	 * @return TCPSegment deserialized from array
	 */
	static TCPSegment decode(byte[] array, int length){
		//src port
		int src_port = (((int) array[0] & 0xFF) <<8) | ((int)array[1] & 0xFF);
		
		//dest port
		int dest_port = (((int) array[2] & 0xFF) <<8) | ((int)array[3] & 0xFF);
		
		//seqnr
		long seq_nr = 0;
		for(int i = 4; i < 8; i++){
			//convert the four bytes into one long (not int because of signedness
			int shift = 24 - 8 * (i - 4); //number of positions to shift the byte
			
			long temp = (long) array[i] & 0xFF; //get the byte
			
			seq_nr |= (temp<<shift);
		}
		//wrap seqnr
		seq_nr = seq_nr % (TCPControlBlock.UINT_32_MAX + 1);
		
		//acknr
		long ack_nr = 0;
		for(int i = 8; i < 12; i++){
			//here be dragons
			ack_nr |= ((long) (array[i]) & 0xFF) <<(24 - 8 * (i - 8));
		}
		//wrap acknr
		ack_nr = ack_nr % (TCPControlBlock.UINT_32_MAX + 1);
		
		//offset, reserved and ns flag
		byte offset = (byte) ((array[12] & 0xF0) >>4);
		byte res = (byte) ((array[12] & 0x07) >>1);
		byte ns = (byte) (array[12] & 0x01);
		
		//flags
		byte cwr = (byte) ((array[13] & 0x80) >>7);
		byte ece = (byte) ((array[13] & 0x40) >>6);
		byte urg = (byte) ((array[13] & 0x20) >>5);
		int ack = (array[13] & 0x10) >>4;
		
		int psh = (array[13] & 0x08) >>3;
		byte rst = (byte)((array[13] & 0x04) >>2);
		int syn = (array[13] & 0x02) >>1;
		int fin = array[13] & 0x01;
		
		//window size
		int windowSize = (((int) array[14]) & 0xff) | (((int) array[15]) & 0xff);
		
		//left part of checksum, useful for debugging (Java Signedness is bothersome)
		int c1 = ((int) array[16]) & 0xFF;
		//right part
		int c2 = ((int) array[17]) & 0xFF;
		//add them together in a short
		short checksum = (short) (c1 <<8 | c2);
		
		
		/*check if there is any data. Otherwise, data is an empty array*/
		byte [] data = new byte[length - HEADER_LENGTH];
		for(int i = 0; i < data.length; i++){
    		data[i] = array[i + HEADER_LENGTH];
    	}
		
		//construct new segment
		TCPSegment ret =  new TCPSegment(src_port, dest_port, seq_nr, ack_nr,
    			syn, ack, fin, data, checksum);
		
		//add other flags
		ret.dataOffset = offset;
		ret.reserved = res;
		ret.ns = ns;
		ret.cwr = cwr;
		ret.ece = ece;
		ret.urg = urg;
		ret.psh = psh;
		ret.rst = rst;
		ret.windowSize = windowSize;
		
		return ret;
	}
	
	/**
	 * converts big-endian network order address to little-endian
	 * @param address
	 * @return
	 */
	public static int ntohl(int address){
		return ((address & 0x000000ff) <<24) |
				((address & 0x0000ff00) <<8) |
				((address & 0x00ff0000) >>8) |
				((address & 0xff000000)>>24);
	}
	
	/**
	 * Compute the checksum of a TCP segment. 
	 * 
	 * @param source
	 * @param dest
	 * @param protocol
	 * @return the checksum
	 */
	static short calculateChecksum(int source, int dest, int length, byte[] pck){
		int calcCs = 0;
		
		//get left part of checksum, useful for debugging (Java Signedness is bothersome)
		byte c1 = pck[CHECKSUM_OFFSET];
		//get right part
		byte c2 = pck[CHECKSUM_OFFSET + 1];
		
		//zero out old checksum
		pck[CHECKSUM_OFFSET] = 0; pck[CHECKSUM_OFFSET + 1] = 0;
		
		/*
		 *pseudo header 
		 */
		
		//add source address
		int srcLE = ntohl(source);
		calcCs += (srcLE>>16) & 0xffff;
		calcCs += (srcLE & 0xffff);
		
		//add destination address
		int destLE = ntohl(dest);
		calcCs += (destLE>>16) & 0xffff;
		calcCs += (destLE & 0xffff);
				
		//add protocol (and zeroes)
		calcCs += IP.TCP_PROTOCOL & 0xff;
				
		//add length
		calcCs += length & 0xffff;
		
		/*
		 * packet
		 */
		
		int i = 0;
		int tcplen;
		for(tcplen = length; tcplen > 1; tcplen-=2){
			calcCs += ((short)pck[i] & 0x00ff)<<8 | ((short)pck[i+1] & 0x00ff);
			i += 2;
		}
		
		if (tcplen > 0){
			//add zero padding byte
			calcCs += ((short) pck[i] & 0x00ff)<<8;
		}
		
		//wrap around if sum is too large
		while(calcCs>>16 != 0){
			calcCs = (calcCs>>16) + (calcCs & 0x0000FFFF);
		}
		
		//one's complement
		calcCs = ((calcCs ^ 0xffff) & 0xffff);
		
		//write back old checksum
		pck[CHECKSUM_OFFSET] = (byte) c1; pck[CHECKSUM_OFFSET + 1] = (byte) c2;
		
		return (short) calcCs;
	}
	
	/**
	 * method to check what kind of packet this is
	 */
	TCPSegmentType getSegmentType(){
		if (syn == 0 && fin == 0 && ack == 0){
			return TCPSegmentType.DATA;
		}
		if (syn == 1 && fin == 0 && ack == 0){
			return TCPSegmentType.SYN;
		}
		if (fin == 1 && syn == 0 && ack == 0){
			return TCPSegmentType.FIN;
		}
		if (ack == 1 && syn == 0 && fin == 0){
			return TCPSegmentType.ACK;
		}
		if (syn == 1 && ack == 1 && fin == 0){
			return TCPSegmentType.SYNACK;
		}
		if (syn == 0 && ack == 1 && fin == 1){
			return TCPSegmentType.FINACK;
		}
		return TCPSegmentType.INVALID;
	}
	
	int getDataLength(){
		return data.length;
	}
	
	long getSeqNr(){
		return seq_nr;
	}
	
	long getAckNr(){
		return ack_nr;
	}
	
	void setSeqNr(long nr){
		seq_nr = nr;
	}
	
	void setAckNr(long nr){
		ack_nr = nr;
	}
	
	void setDestPort(int port){
		this.dest_port = port;
	}
	
	void setSrcPort(int port){
		this.src_port = port;
	}
	
	/**
	 * @param port
	 * @return true if the packet contains the destination port equal to port
	 */
	boolean hasDestPort(int port){
		return dest_port == port;
	}
	
	boolean hasSrcPort(int port){
		return src_port == port;
	}
	
	boolean hasSrcIp(IpAddress ip){
		return source_ip.getAddress() == ip.getAddress();
	}
	
	SocketAddress getSrcSocketAddress(){
		return new SocketAddress(source_ip, src_port);
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