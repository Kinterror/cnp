package nl.vu.cs.cn;

import java.util.Random;

import android.util.Log;
import nl.vu.cs.cn.IP.IpAddress;
import nl.vu.cs.cn.TCPSegment.TCPSegmentType;

/**
 * Keeps track of the internal state and variables required for a TCP connection, such as
 * -The state of the connection
 * -The IP addresses and port numbers of both sides of the connection
 * -The sequence and acknowledgment numbers
 */
class TCPControlBlock{
	
	/**maximum value of uint32 for sequence numbers*/
	private static final long UINT_32_MAX = (2 * ((long) Integer.MAX_VALUE) + 1);
	
	/**random number generator for sequence numbers*/
	private Random rand;
	
	/**
	 * Denotes the states of the connection based on the TCP finite state machine.
	 */
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
	
	/**
	 * indicates a connection has been made on this socket. Set to false before the connection was established the first time.
	 * Set to true after some connection has been established on the socket
	 */
	boolean hasConnection;

	TCPControlBlock(){
		state = ConnectionState.S_CLOSED;
		current_seqnr = current_acknr = previous_acknr = previous_seqnr = 0;
		local_port = 0;
		remote_port = 0;
		hasConnection = false;

		rand = new Random();
	}
	
	/**
	 * initializes the server's TCB given a certain SYN packet
	 * @param s the SYN packet
	 */
	void initServer(TCPSegment s){
		//update connection source
		setRemoteSocketAddress(s.getSrcSocketAddress());
		//generate sequence number and update state and acknowledgment number.
		generateSeqnr();
		previous_acknr = s.seq_nr;
		current_acknr = s.seq_nr + 1;
	}
	
	/**
	 * initializes the server's TCB given a certain SYN+ACK packet
	 * @param s the SYNACK packet
	 */
	void initClient(TCPSegment s){
		previous_acknr = s.seq_nr;
		current_acknr = s.seq_nr + 1;
	}
	
	/**
	 * Update the connection state
	 * @param s
	 */
	void setState(ConnectionState s){
		if (s == ConnectionState.S_ESTABLISHED){
			hasConnection = true;
		}
		
		Log.d("TCB", "state set to: " + s.name());
		this.state = s;
	}
	
	/**
	 * @return the TCP connection state of this connection
	 */
	ConnectionState getState(){
		return state;
	}
	
	/**
	 * specifies the local socket address for this connection
	 * @param addr
	 */
	void setLocalSocketAddress(SocketAddress addr){
		this.local_ip_addr = addr.getIp();
		this.local_port = addr.getPort();
	}
	
	/**
	 * specifies the remote socket address for this connection
	 * @param addr
	 */
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

	/**
	 * @return the current acknowledgement number; that is, the next sequence number we expect an incoming TCP packet to have.
	 */
	long getExpectedSeqnr(){
		return current_acknr;
	}
	
	long getSeqnr(){
		return current_seqnr;
	}

	
	long getPreviousSeqnr(){
		return previous_seqnr;
	}
	
	/**
	 * @return the previous acknowledgement number; that is, the sequence number of the previous incoming TCP packet.
	 */
	long getPreviousExpectedSeqnr(){
		return previous_acknr;
	}
	
	/**
	 * @param pck
	 * @return true if the packet belongs to the current connection (and it contains the addresses corresponding to our socket)
	 */
	boolean checkValidAddress(TCPSegment pck) {
		return 	pck.hasDestPort(local_port) &&
				pck.hasSrcIp(remote_ip_addr) &&
				pck.hasSrcPort(remote_port);
	}
	
	/**
	 * @param pck
	 * @return true if the seq/ack numbers are what we expected them to be
	 */
	boolean isInOrderPacket(TCPSegment pck) {
		return pck.seq_nr == current_acknr &&
				pck.ack_nr == current_seqnr;
	}
	
	/**
	 * creates a new packet with a specified type and without data.
	 * Also increments the sequence number by 1 if it is a packet with the SYN or FIN flag.
	 * @param st
	 * @return
	 */
	TCPSegment createControlSegment(TCPSegmentType st){
		return new TCPSegment(local_port, remote_port, 
				st == TCPSegmentType.SYN || st == TCPSegmentType.SYNACK || st == TCPSegmentType.FIN || st == TCPSegmentType.FINACK
						? getAndIncrementSeqnr(1) : getSeqnr(),
				current_acknr,
				st, new byte[0]);
	}
	
	/**
	 * creates a new packet with the DATA type and containing the byte array data.
	 * Also increments the sequence number by the length of the data.
	 * @param data
	 * @return
	 */
	TCPSegment createDataSegment(byte[] data){
		return new TCPSegment(local_port, remote_port, getAndIncrementSeqnr(data.length), current_acknr,
				TCPSegmentType.DATA, data);
	}
	
	/**
	 * generates an ACK packet which acknowledges the packet specified by pck.
	 * This method does not increase the sequence number of the TCB.
	 * @param pck
	 * @return the new ACK packet
	 */
	TCPSegment generateAck(TCPSegment pck){
		return new TCPSegment(local_port, remote_port, pck.ack_nr, pck.seq_nr + (pck.data.length > 0 ? pck.data.length : 1), TCPSegmentType.ACK, new byte[0]);
	}
}
