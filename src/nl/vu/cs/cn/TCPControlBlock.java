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
	private long current_seqnr;
	private long next_seqnr;
	private long our_ack_nr;
	private long previous_acknr;

	TCPControlBlock(){
		state = ConnectionState.S_CLOSED;
		current_seqnr = our_ack_nr = previous_acknr = 0;
		local_port = 0;
		remote_port = 0;

		rand = new Random();
	}
	
	void initServer(TCPSegment s){
		//update connection source
		setRemoteSocketAddress(s.getSrcSocketAddress());
		//generate sequence number and update state and acknowledgment number.
		generateSeqnr();
		setAcknr(s.seq_nr + 1);
		setState(ConnectionState.S_SYN_RCVD);
	}
	
	void initClient(TCPSegment s){
		previous_acknr = s.seq_nr;
		our_ack_nr = previous_acknr + 1;
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
	long generateSeqnr(){
		current_seqnr = Math.abs(rand.nextLong() % UINT_32_MAX);
		next_seqnr = current_seqnr + 1;
		return current_seqnr;
	}
	
	/**
	 * increments the sequence number by size, and do it modulo the maximum sequence number. Then update the value
	 * @param size
	 * @return the old sequence number
	 */
	long getAndIncrementSeqnr(){
		long previous_seqnr = current_seqnr;
		current_seqnr = next_seqnr;
		//this sequence number never becomes negative
		
		return previous_seqnr;
	}
	
	long getNextSeqnr(){
		return next_seqnr;
	}
	
	void increaseNextSeqnr(long size){
		next_seqnr += (size > 0 ? size : 1) % UINT_32_MAX;
	}
	
	void setAcknr(long acknr){
		our_ack_nr = acknr;
	}
	
	long getAndIncrementAcknr(long size){
		previous_acknr = our_ack_nr;
		our_ack_nr = (our_ack_nr + (size > 0 ? size : 1)) % UINT_32_MAX;
		return previous_acknr;
	}

	long getExpectedSeqnr(){
		return our_ack_nr;
	}
	
	long getSeqnr(){
		return current_seqnr;
	}

	long getPreviousExpectedSeqnr(){
		return previous_acknr;
	}
	
	boolean checkValidAddress(TCPSegment pck) {
		return 	pck.hasDestPort(local_port) &&
				pck.hasSrcIp(remote_ip_addr) &&
				pck.hasSrcPort(remote_port);
	}
	
	boolean isInOrderPacket(TCPSegment pck) {
		return pck.seq_nr == our_ack_nr &&
				pck.ack_nr == next_seqnr;
	}
}
