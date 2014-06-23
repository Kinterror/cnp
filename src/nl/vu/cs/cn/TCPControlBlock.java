package nl.vu.cs.cn;

import java.util.Random;

import android.util.Log;
import nl.vu.cs.cn.IP.IpAddress;
import nl.vu.cs.cn.TCPSegment.TCPSegmentType;

/**definition of the Connection states*/
class TCPControlBlock{
	
	//maximum value of uint32 for sequence numbers
	private static final long UINT_32_MAX = (2 * ((long) Integer.MAX_VALUE) + 1);
	
	//random number generator for sequence numbers
	private Random rand;
	
	enum ConnectionState
	{
		S_CLOSED, S_LISTEN, S_SYN_SENT, S_SYN_RCVD, S_ESTABLISHED,
		S_FIN_WAIT_1, S_FIN_WAIT_2, S_CLOSE_WAIT, S_LAST_ACK, S_TIME_WAIT, S_CLOSING
	}
		
	private ConnectionState state;
	private IpAddress local_ip_addr;
	private IpAddress remote_ip_addr;
	private int local_port;
	private int remote_port;
	private long current_seqnr;
	private long previous_seqnr;
	private long current_acknr;
	private long previous_acknr;

	TCPControlBlock(){
		state = ConnectionState.S_CLOSED;
		current_seqnr = current_acknr = previous_acknr = previous_seqnr = 0;
		local_port = 0;
		remote_port = 0;

		rand = new Random();
	}
	
	void initServer(TCPSegment s){
		//update connection source
		setRemoteSocketAddress(s.getSrcSocketAddress());
		//generate sequence number and update state and acknowledgment number.
		generateSeqnr();
		previous_acknr = s.seq_nr;
		current_acknr = s.seq_nr + 1;
	}
	
	void initClient(TCPSegment s){
		previous_acknr = s.seq_nr;
		current_acknr = s.seq_nr + 1;
	}
		
	void setState(ConnectionState s){
		Log.d("TCB", "state set to: " + s.name());
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
		previous_seqnr = 0;
		return current_seqnr;
	}
	
	/**
	 * increments the sequence number by size, and do it modulo the maximum sequence number. Then update the value
	 * @param size
	 * @return the old sequence number
	 */
	long getAndIncrementSeqnr(long size){
		previous_seqnr = current_seqnr;
		current_seqnr = (current_seqnr + size) % UINT_32_MAX;
		//this sequence number never becomes negative
		
		return previous_seqnr;
	}
	
	/**
	 * increments the ack number by size, and do it modulo the maximum ack number. Then update the value
	 * @param size
	 * @return the old ack number
	 */
	long getAndIncrementAcknr(long size){
		previous_acknr = current_acknr;
		current_acknr = (current_acknr + size) % UINT_32_MAX;
		return previous_acknr;
	}

	long getExpectedSeqnr(){
		return current_acknr;
	}
	
	long getSeqnr(){
		return current_seqnr;
	}

	long getPreviousSeqnr(){
		return previous_seqnr;
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
		return pck.seq_nr == current_acknr &&
				pck.ack_nr == current_seqnr;
	}
	
	TCPSegment createControlSegment(TCPSegmentType st){
		return new TCPSegment(local_port, remote_port, 
				st == TCPSegmentType.SYN || st == TCPSegmentType.SYNACK || st == TCPSegmentType.FIN ? getAndIncrementSeqnr(1) : getSeqnr(),
				current_acknr,
				st, new byte[0]);
	}
	
	TCPSegment createDataSegment(byte[] data){
		return new TCPSegment(local_port, remote_port, getAndIncrementSeqnr(data.length), current_acknr,
				TCPSegmentType.DATA, data);
	}
	
	TCPSegment generatePreviousAck(){
		return new TCPSegment(local_port, remote_port, previous_seqnr, current_acknr, TCPSegmentType.ACK, new byte[0]);
	}
}
