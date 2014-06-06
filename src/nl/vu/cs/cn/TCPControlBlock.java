package nl.vu.cs.cn;

import java.util.Random;

import nl.vu.cs.cn.IP.IpAddress;

/**definition of the Connection states*/
class TCPControlBlock{
	
	//maximum value of uint32 for sequence numbers
	private static final long UINT_32_MAX = (2 * ((long) Integer.MAX_VALUE) + 1);
	
	//random number generator for sequence numbers
	private Random rand;
	
	enum ConnectionState
	{
		S_CLOSED, S_LISTEN, S_SYN_SENT, S_SYN_RCVD, S_ESTABLISHED,
		S_FIN_WAIT_1, S_FIN_WAIT_2, S_CLOSE_WAIT, S_LAST_ACK, S_TIME_WAIT
	}
		
	private ConnectionState state;
	private IpAddress local_ip_addr;
	private IpAddress remote_ip_addr;
	private int local_port;
	private int remote_port;
	private long our_sequence_num;
	private long our_expected_ack;

	TCPControlBlock(){
		state = ConnectionState.S_CLOSED;
		our_sequence_num = our_expected_ack = 0;
		local_port = 0;
		remote_port = 0;

		rand = new Random();
	}
	
	void initServer(TCPSegment s){
		//update connection source
		setRemoteSocketAddress(s.getSrcSocketAddress());
		//generate sequence number and update state and acknowledgment number.
		generate_seqnr();
		set_acknr(s.seq_nr);
		setState(ConnectionState.S_SYN_RCVD);
	}
	
	void initClient(TCPSegment s){
		set_acknr(s.seq_nr);
	}
		
	void setState(ConnectionState s){
		this.state = s;
	}
	
	ConnectionState getState(){
		return state;
	}
	
	//not really a socket.bind() method, but rather a change in the TCB local variables
	void setLocalSocketAddress(SocketAddress addr){
		this.local_ip_addr = addr.getIp();
		this.local_port = addr.getPort();
	}
	
	void setRemoteSocketAddress(SocketAddress addr){
		remote_port = addr.getPort();
		remote_ip_addr = addr.getIp();
	}
	
	SocketAddress getLocalSocketAddress(){
		return new SocketAddress(local_ip_addr, local_port);
	}
	
	SocketAddress getRemoteSocketAddress(){
		return new SocketAddress(remote_ip_addr, remote_port);
	}
	/**
	 * generates a new sequence number for this tcb  and updates it in the TCB
	 * @return the sequence number
	 */
	long generate_seqnr(){
		return our_sequence_num = Math.abs(rand.nextLong() % UINT_32_MAX);
	}
	
	/**
	 * increments the sequence number by size, and do it modulo the maximum sequence number. Then update the value
	 * @param size
	 * @return the old sequence number
	 */
	long getAndIncrement_seqnr(long size){
		long tmp = our_sequence_num;
		//this sequence number never becomes negative
		our_sequence_num = (our_sequence_num + size) % UINT_32_MAX;
		return tmp;
	}
	
	void set_acknr(long acknr){
		our_expected_ack = acknr;
	}
	
	long getAndIncrement_acknr(long size){
		long tmp = our_expected_ack;
		our_expected_ack = (our_expected_ack + size) % UINT_32_MAX;
		return tmp;
	}

	long get_acknr(){
		return our_expected_ack;
	}
	
	boolean isValidSegment(TCPSegment pck) {
		//TODO check sequence number
		return 	pck.hasDestPort(local_port) &&
				pck.hasSrcIp(remote_ip_addr) &&
				pck.hasSrcPort(remote_port);
	}
}
