package nl.vu.cs.cn;

import nl.vu.cs.cn.IP.IpAddress;

/**definition of the Connection states*/
class TcpControlBlock{
	
	public enum ConnectionState
	{
		S_CLOSED, S_LISTEN, S_SYN_SENT, S_SYN_RCVD, S_ESTABLISHED,
		S_FIN_WAIT_1, S_FIN_WAIT_2, S_CLOSE_WAIT
	}
	
	public static final int TCB_BUF_SIZE = 0;
	
	private ConnectionState state;
	IpAddress our_ip_addr;
	IpAddress their_ip_addr;
	int our_port;
	int their_port;
	int our_sequence_num;
	int our_expected_ack;
	byte tcb_data[];
	
	TcpControlBlock(){
		state = ConnectionState.S_CLOSED;
		tcb_data = new byte[TCB_BUF_SIZE];
		//our_sequence_num = generate_seqnr();
		our_sequence_num = 0;
		our_expected_ack = our_sequence_num;
		our_port = 0;
		their_port = 0;
	}
	
	void bind(IpAddress ip, int port){
		this.our_ip_addr = ip;
		this.our_port = port;
	}
	
	void setState(ConnectionState s){
		this.state = s;
	}
	
	ConnectionState getState(){
		return state;
	}
}
