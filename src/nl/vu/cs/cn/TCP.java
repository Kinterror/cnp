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
	public static final int DEFAULT_CLIENT_PORT = 12345;

	/**MSL (maximum segment lifetime) (seconds) used in the connection termination timer*/
	public static final int MSL = 2;

	/**maximum number of tries waiting for an ack*/
	public static final int MAX_TRIES = 10;

	/**the length of TCP headers used by our implementation. Options are not supported.*/
	public static final int IP_HEADER_LENGTH = 20;

	/**the maximum size (bytes) of any packet sent through the ip layer*/
	public static final int MAX_TCPIP_SEGMENT_SIZE = 8192;

	/** Size of the send and receive buffer */
	public static final int BUFFER_SIZE = 1048576; // 1MB

	/**the default for receiving packets*/
	public static final int DEFAULT_TIMEOUT = 1;

	/**the timeout in seconds for receiving packets. Initially set to DEFAULT_TIMEOUT. */
	public int timeout;

	/** The underlying IP stack for this TCP stack. */
	private IP ip;

	/**packet ID which is initially zero and is incremented each time a packet is sent through the IP layer*/
	private int ip_packet_id;

	/**
	 * This class represents a TCP socket.
	 */
	public class Socket {

		private boolean isClientSocket;

		/**
		 * transmission control block, shared between the sender and receiver threads
		 */
		private volatile TCPControlBlock tcb;
		private volatile BoundedByteBuffer recv_buf;
		private volatile BoundedByteBuffer send_buf;

		/**
		 * used to indicate the data arrived has a previous acknowledgement number; thus, both sides of the connection called
		 * send() at the same time 
		 */
		private volatile boolean isWaitingForAck = false;
		private volatile boolean closePending = false;
		private Object waitingForAckMonitor;
		private Object waitingForCloseMonitor;
		private Thread recvt;
		private Thread sendt;

		/** Construct a client socket. */
		private Socket() {
			this(DEFAULT_CLIENT_PORT);
			isClientSocket = true;
		}

		/**
		 * Construct a server socket bound to the given local port.
		 * @param port the local port to use
		 */
		private Socket(int port) {
			isClientSocket = false;
			waitingForAckMonitor = new Object();
			waitingForCloseMonitor = new Object();
			tcb = new TCPControlBlock();
			tcb.setLocalSocketAddress(new SocketAddress(ip.getLocalAddress(), port));

			recv_buf = new BoundedByteBuffer(BUFFER_SIZE);
			send_buf = new BoundedByteBuffer(BUFFER_SIZE);
		}

		/**
		 * Connect this socket to the specified destination and port.
		 *
		 * @param IpAddress dst - the destination to connect to
		 * @param int port - the TCP port to connect to
		 * @return true if the connect succeeded.
		 */
		public boolean connect(IpAddress dst, int port) {

			//can't connect with a server socket or a socket with an already open connection
			if (!isClientSocket || tcb.getState() != ConnectionState.S_CLOSED || port < 0 || port > 65535){
				return false;
			}

			send_buf.init();
			recv_buf.init();

			// Implement the connection side of the three-way handshake here.
			tcb.setRemoteSocketAddress(new SocketAddress(dst, port));

			//generate sequence number
			tcb.generateSeqnr();

			//construct a syn packet
			TCPSegment syn_pck = tcb.createControlSegment(TCPSegmentType.SYN);

			//send it and wait for a synack
			tcb.setState(ConnectionState.S_SYN_SENT);
			if (sendAndWaitAck(syn_pck)){
				//send ack
				TCPSegment ack = tcb.createControlSegment(TCPSegmentType.ACK);
				sockSend(ack);

				tcb.setState(ConnectionState.S_ESTABLISHED);
				Log.d("connect()", "Client: connection established");
				//start sender and receiver threads
				recvt = new Thread(new ReceiverThread());
				recvt.start();
				sendt = new Thread(new SenderThread());
				sendt.start();
				
				return true;
			} else {
				Log.d("connect()", "failed to receive a SYN_ACK message from server");
				tcb.setState(ConnectionState.S_CLOSED);
				return false;
			}
		}	

		/**
		 * Accept a connection on this socket.
		 * This call blocks until a connection is made.
		 */
		public void accept() {

			//client sockets and sockets with an already open connection cannot call accept
			if(isClientSocket){
				Log.e("accept() error","Called accept() on a client socket");
				System.exit(-1);
			} else if (tcb.getState() != ConnectionState.S_CLOSED){
				Log.e("accept() error","Called accept() on an opened socket");
				System.exit(-1);
			}

			send_buf.init();
			recv_buf.init();

			//receive incoming packets
			while (tcb.getState() != ConnectionState.S_ESTABLISHED){

				//listen for incoming connections
				tcb.setState(ConnectionState.S_LISTEN);

				TCPSegment syn_pck;

				while (tcb.getState() == ConnectionState.S_LISTEN){
					//receive a packet from the network
					try {
						syn_pck = recv_tcp_segment(0);
					} catch(InvalidPacketException e){
						Log.d("accept", "Received invalid packet: " + e.getMessage());
						continue;
					} catch (Exception e1){
						continue;
					}
					if(syn_pck.getSegmentType() == TCPSegmentType.SYN)
					{
						//initialize the tcb with the right sequence numbers, ports and IP addresses
						tcb.initServer(syn_pck);
						tcb.setState(ConnectionState.S_SYN_RCVD);
					} else {
						//else, discard it and listen again.
						Log.d("accept", "Received invalid packet type: " + syn_pck.getSegmentType().name() + 
								" instead of SYN");
					}

				}

				//compose a synack message
				TCPSegment syn_ack = tcb.createControlSegment(TCPSegmentType.SYNACK);

				//try to send it
				if (sendAndWaitAck(syn_ack)){
					Log.d("accept()", "Server: Connection established.");
					recvt = new Thread(new ReceiverThread());
					recvt.start();
					sendt = new Thread(new SenderThread());
					sendt.start();
					return;
				}

				/*either:
				 *tries expired. Go back to listening state and try to listen again.
				 *or:
				 *established. In this case, return.
				 */
			}
		}

		/**
		 * waits for a segment to arrive. Checks the sequence number and ack number and resends lost acks.
		 * @param timeout
		 * @return the packet that was received.
		 * @throws InterruptedException if timeout expired.
		 */
		private synchronized TCPSegment sockRecv(int timeout) throws InterruptedException{
			TCPSegment pck;

			while(true){
				try{
					pck = recv_tcp_segment(timeout);

					//are the ports correct?
					if(!tcb.checkValidAddress(pck)){
						Log.d("sockRecv", "received packet with invalid address or port");
						continue;
					}
					return pck;
				} catch (InvalidPacketException e) {
					Log.d("sockRecv", "Invalid packet: " + e.getMessage());
				}
			}    	
		}

		/**
		 * handles the packet based on the sequence numbers
		 * @param seg
		 */
		private void handlePacket(TCPSegment seg){
			/*
			 * received a packet with the correct sequence number
			 */
			if(tcb.isInOrderPacket(seg)){
				//increment acknr
				handleInOrderPacket(seg);
			}
			/*
			 * received data packet just after having sent a data packet by yourself
			 * therefore, the acknr is one too old.
			 */
			else if (seg.seq_nr == tcb.getExpectedSeqnr() &&
					seg.ack_nr == tcb.getPreviousSeqnr())
			{
				Log.d("sockRecv","received data packet with old acknr");
				handleOldAcknr(seg);
			}

			/* 
			 * case of lost ack: we receive an old data packet with the previous sequence number
			 */
			else if (seg.seq_nr == tcb.getPreviousExpectedSeqnr() && 
					(seg.ack_nr == tcb.getSeqnr() || seg.ack_nr == tcb.getPreviousSeqnr())){
				handleOldSeqnr(seg);
			}
			/*
			 * packet completely out of order
			 */
			else {
				Log.d("sockRecv", "received incorrect (out of order) packet with sequence number " + seg.seq_nr + 
						" and acknowledgement number " + seg.ack_nr + ". Expected seqnr: "+ tcb.getExpectedSeqnr() +
						" or prev:" + tcb.getPreviousExpectedSeqnr()+ ", acknr" + tcb.getSeqnr());
			}
		}

		/**
		 * handle the case of an old sequence number
		 * @param seg
		 */
		private void handleOldSeqnr(TCPSegment seg) {

			switch(seg.getSegmentType()){

			case SYN:
				Log.d("sockRecv", "received SYN with old seqnr.");
				//now the SYNACK was lost. Let the caller handle the lost ack now.
				return;
			case ACK:
				Log.d("sockRecv", "received duplicate ACK.");
			case DATA:
				if(seg.data.length <= 0){
					//discard it
					return;
				}
				//fall through and send the ack to the data
			default:
				//resend lost ack
				TCPSegment ack = tcb.generateAck(seg);
				sockSend(ack);
			}
		}

		/**
		 * simultaneous data/fin sending
		 * @param seg
		 */
		private void handleOldAcknr(TCPSegment seg) {
			if (seg.data.length > 0){
				handleData(seg);
			}

			if(seg.getSegmentType() == TCPSegmentType.FIN){
				handleIncomingFin(seg);
			}
		}

		private void handleInOrderPacket(TCPSegment seg) {
			switch(seg.getSegmentType()){
			case FINACK:
			case ACK:
			case DATA:
				//notify the sender thread waiting for an ACK, even if in order data is received
				synchronized(waitingForAckMonitor){
					if (isWaitingForAck){
						isWaitingForAck = false;
						waitingForAckMonitor.notify();
					}
				}

				//handle the data
				if(seg.data.length > 0 && tcb.getState() != ConnectionState.S_CLOSE_WAIT){
					//put the data in the buffer and send an ack
					handleData(seg);

					//notify the application thread of having received data
					synchronized (recv_buf) {
						recv_buf.notify();
					}
				}
				if(seg.getSegmentType() != TCPSegmentType.FINACK){
					break;
				}
				//else handle the fin flag
			case FIN:
				//go to close_wait state and ack the fin
				handleIncomingFin(seg);
				break;
			default:
				Log.d("receiverThread", "received unexpected packet type: " + seg.getSegmentType().name());
			}

		}

		/**
		 * Sets the src and dest port right and calls send_tcp_segment().
		 * @param pck
		 */
		private boolean sockSend(TCPSegment pck) {
			SocketAddress remoteAddr = tcb.getRemoteSocketAddress();

			try{
				send_tcp_segment(remoteAddr.getIp(), pck);
			} catch (IOException e) {
				e.printStackTrace();
				return false;
			}
			return true;
		}

		/**
		 * sends packet and waits for an ack or resends (max. 10 times) in the sender thread
		 */
		private boolean sendAndWaitAckEstablished(TCPSegment pck) {
			int ntries = 0;
			synchronized(waitingForAckMonitor){
				isWaitingForAck = true;
				do {
					sockSend(pck);
					/*there's an innocent race condition here that makes us lose one second
					 * if this thread gets preempted here and the receive thread receives the
					 * packet and calls notify before this thread has entered wait state.
					 */
					try {
						//wait until the receiver thread receives an ack
						waitingForAckMonitor.wait(1000);

					} catch (InterruptedException e) { e.printStackTrace(); }
					ntries++;
				} while (ntries < MAX_TRIES && isWaitingForAck);
			}
			return !isWaitingForAck;
		}

		/**
		 * wrapper for sockSend. try to send a packet and wait for the acknowledgment.
		 */
		private boolean sendAndWaitAck(TCPSegment pck) {
			for (int ntried = 0; ntried < MAX_TRIES; ntried++) {
				//try to send packet with right port and sequence numbers.
				if (!sockSend(pck))
					return false;

				//wait for ack
				boolean hasReceived = false;

				switch(pck.getSegmentType()){
				case SYN:
					hasReceived = waitForSynAck();
					break;
				case SYNACK:
					hasReceived = waitForAckToSynAck();
					break;
				default:
					return false;
				}
				if (hasReceived){
					return true;
				}
			}
			return false;
		}

		/**
		 * method that waits for an acknowledgement for a sent fin. This is only called in the S_LAST_ACK state
		 * @return false if we received nothing.
		 * @return true if we received an ack.
		 */
//		private boolean waitForAckToFin(){
//			if(tcb.getState() != ConnectionState.S_LAST_ACK)
//				return false;
//
//			while(true){
//				try {
//					TCPSegment seg = sockRecv(timeout);
//
//					//wrong sequence numbers
//					if(!tcb.isInOrderPacket(seg)){
//
//						/* ack got lost
//						 */
//						if(seg.getSegmentType() == TCPSegmentType.FIN){
//							//resend lost ack
//							sockSend(tcb.generateAck(seg));
//						} else {
//							Log.d("waitForAckToFin", "received out of order non-fin packet in S_LAST_ACK");
//						}
//						continue;
//					}
//					//right sequence numbers
//					else {
//						switch(seg.getSegmentType()){
//						case FINACK:
//							handleIncomingFin(seg);
//						case ACK:
//							return true;
//						default:
//							//discard it
//						}
//					}
//				} catch (InterruptedException e) {
//					return false;
//				}
//			}
//		}

		/**
		 * method that waits for an acknowledgement for a sent syn,ack. This is only called in the S_SYN_RCVD state.
		 * @return false if we received nothing.
		 * @return true if we received an ack.
		 */
		private boolean waitForAckToSynAck(){
			if(tcb.getState() != ConnectionState.S_SYN_RCVD)
				return false;

			while(true){
				try {
					TCPSegment seg = sockRecv(timeout);

					/* received out of order packet instead of ack to synack
					 */
					if(!tcb.isInOrderPacket(seg)){
						//let the caller handle it
						return false;
					}

					if(seg.data.length > 0){
						handleData(seg);
					}

					switch(seg.getSegmentType()){
					case FIN:
					case FINACK:
						handleIncomingFin(seg);

						return true;
					case ACK:
					case DATA:
						tcb.setState(ConnectionState.S_ESTABLISHED);
						return true;
						/*
						 * in case the ACK of the three way handshake was lost, regard the received data as an ACK and establish
						 * the connection
						 */
					case SYN:
						return false;
					default:
						//continue
					}
				} catch (InterruptedException e) {
					return false;
				}
			}
		}

		/**
		 * method that waits for an acknowledgement for a sent syn. This is only called in the S_SYN_SENT state.
		 * @return false if we received nothing.
		 * @return true if we received a synack.
		 */
		private boolean waitForSynAck(){
			if(tcb.getState() != ConnectionState.S_SYN_SENT)
				return false;

			while(true){
				try {
					TCPSegment seg = recv_tcp_segment(timeout);
					//check if it has the right socket address, acknr, and the right type
					TCPSegmentType type = seg.getSegmentType();
					if (type == TCPSegmentType.SYNACK &&
							tcb.checkValidAddress(seg) &&
							seg.ack_nr == tcb.getSeqnr()){ //no data expected, so 1 corresponds to a control packet
						//initialize acknr of client
						tcb.initClient(seg);
						return true;
					} else {
						if (seg.ack_nr != tcb.getSeqnr()){
							Log.e("waitForSynAck()", "received acknr " + seg.ack_nr + ". Expected: " + tcb.getSeqnr());
						} 
						if (!tcb.checkValidAddress(seg)){
							Log.e("waitForSynAck()", "incorrect address/port: got port " + seg.dest_port + " on address "
									+ seg.source_ip.toString());
						} 
						if (type != TCPSegmentType.SYNACK) {
							Log.e("waitForSynAck()", "received unexpected packet: " + type.name());
						}
					}
				} catch (InterruptedException e) {
					return false;
				} catch (InvalidPacketException e1) {
					Log.e("waitForSynack", "Invalid packet: " + e1.getMessage());
				}
			}
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
			case S_FIN_WAIT_1:
			case S_FIN_WAIT_2:
			case S_CLOSE_WAIT: //they have already closed the connection, or we received enough data
				synchronized(recv_buf){
					/*check if there are maxlen bytes in the buffer. If not, block until data comes in from the network.*/
					if(recv_buf.length() <= 0 && 
							//check if the connection wasn't closed in the meantime
							tcb.getState() != ConnectionState.S_CLOSE_WAIT &&
							tcb.getState() != ConnectionState.S_CLOSED)
					{
						try {
							recv_buf.wait();
						} catch (InterruptedException e) { e.printStackTrace(); }
					}
					return recv_buf.deBuffer(buf, offset, maxlen);
				}
			default:
				Log.e("write()", "can't read: no connection established");
				return -1;
			}
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
				//maximum data length
				int max_data_len = MAX_TCPIP_SEGMENT_SIZE - IP_HEADER_LENGTH - TCPSegment.HEADER_LENGTH;

				//maybe the buffer is not long enough
				if((buf.length - offset) < len){
					len = buf.length - offset;
				}
				int bytesRemaining = len;

				//while there are still bytes to write, split data into processable sizes and buffer them.
				while(bytesRemaining > 0){
					byte[] data;
					if(bytesRemaining >= max_data_len){
						//send a packet of max size
						data = new byte[max_data_len];
						bytesRemaining -= max_data_len;
					} else {
						data = new byte[bytesRemaining];
						bytesRemaining = 0;
					}
					//copy the bytes into a new buffer
					for(int i = 0; i < data.length; i++){
						data[i] = buf[offset];
						offset++;
					}
					try {
						send_buf.buffer(data);
						synchronized(send_buf) {send_buf.notify(); }
					} catch (FullCollectionException e) {
						//no more bytes are written
						break;
					}
				}
				return len;
			default:
				Log.e("write()", "can't write: no connection established");
				return -1;
			}
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
				tcb.setState(ConnectionState.S_FIN_WAIT_1);
				closePending = true;
				return true;
			case S_CLOSE_WAIT:
				tcb.setState(ConnectionState.S_LAST_ACK);
				closePending = true;
				synchronized (waitingForCloseMonitor) {
					waitingForCloseMonitor.notifyAll();
				}
				return true;
			default:
				Log.e("close() error", "can't close a non-open socket");
				return false;
			}
		}

		private void gotoTimeWait(){
			TCPSegment ack = tcb.createControlSegment(TCPSegmentType.ACK);
			sockSend(ack);

			tcb.setState(ConnectionState.S_TIME_WAIT);

			//start timed receive for 10 seconds

			long timeExpired = 0;
			long initialTime = System.currentTimeMillis();

			//decrease timer until it hits zero
			for (int timer = 2 * MSL; timer >= 0; timer -= (timeExpired / 1000)){
				try {
					TCPSegment segment = sockRecv(timer);
					if(segment.getSegmentType() == TCPSegmentType.FIN){
						//send ack again
						ack = tcb.generateAck(segment);
						sockSend(ack);
					}
				} catch (InterruptedException e) {
					break;
				} finally {
					//increment the timer
					long time = System.currentTimeMillis();
					timeExpired += (time - initialTime);
					initialTime = time;
				}
			}
			//close the connection
			tcb.setState(ConnectionState.S_CLOSED);
		}

		/**
		 * buffer the received data and send an ack if the buffer is not full
		 */
		private void handleData(TCPSegment seg){
			switch(tcb.getState()){
			case S_CLOSE_WAIT:
			case S_CLOSED:
			case S_CLOSING:
			case S_TIME_WAIT:
			case S_LAST_ACK:
				//in all these cases, the FIN has already been received
				break;
			default:
				tcb.getAndIncrementAcknr(seg.data.length);

				try {
					//put the data at the end of the buffer
					recv_buf.buffer(seg.data);
				} catch (FullCollectionException e) {
					e.printStackTrace();
				}
			}
			//send ack
			TCPSegment ack = tcb.generateAck(seg);
			sockSend(ack);
		}

		/**
		 * here, the process received a fin packet. handle it according to the current state.
		 */
		private void handleIncomingFin(TCPSegment seg){
			TCPSegment ack;

			/*
			 * if we were in established state, go to close_wait state and send an ack.
			 * if we were already in close_wait, and receive a fin again, resend the ack since it was apparently lost.
			 */
			switch(tcb.getState()){
			case S_FIN_WAIT_1:
				//simultaneous closing
				tcb.setState(ConnectionState.S_CLOSING);
				tcb.getAndIncrementAcknr(1);
				break;
			case S_ESTABLISHED:
			case S_SYN_RCVD:
				//other side closed first
				tcb.setState(ConnectionState.S_CLOSE_WAIT);
				tcb.getAndIncrementAcknr(1);
				break;
			case S_CLOSE_WAIT:
			case S_LAST_ACK:
			case S_CLOSING:
				//ack to fin was lost
				break;
			case S_FIN_WAIT_2:
				//our side closed first
				tcb.getAndIncrementAcknr(1);
				gotoTimeWait();
				return;
			default:
				Log.d("handleIncomingFin", "received FIN packet in state: " + tcb.getState().name());
				return;
			}
			//send ack
			ack = tcb.generateAck(seg);
			sockSend(ack);
		}

		private void waitForClose() {
			//wait for the application to close the connection
			while (!closePending){
				synchronized(waitingForCloseMonitor){
					try {
						waitingForCloseMonitor.wait(1000);
					} catch (InterruptedException e) {
						e.printStackTrace();
					}
				}
			}

			//if the app closed the connection:
			switch(tcb.getState()){
			case S_LAST_ACK:
				//received the other side's fin. send own fin and wait for ack.
				TCPSegment fin = tcb.createControlSegment(TCPSegmentType.FIN);
				Log.d("waitForClose", "sending fin and waiting for ack in LAST_ACK");
				sendAndWaitAckEstablished(fin);

				//even if fin sending fails, close anyway
				tcb.setState(ConnectionState.S_CLOSED);
				break;
			case S_CLOSING:
				gotoTimeWait();
				break;
			default:
				tcb.setState(ConnectionState.S_CLOSED);
			}
		}

		private class SenderThread implements Runnable {
			public void run() {
				//only send packets it the connections hasn't been closed yet
				while(tcb.getState() == ConnectionState.S_ESTABLISHED || 
						tcb.getState() == ConnectionState.S_CLOSE_WAIT ||
						tcb.getState() == ConnectionState.S_FIN_WAIT_1){
					//there are packets due to be sent
					if (!send_buf.isEmpty()){

						//create a new segment from the buffer
						byte[] temp = new byte[MAX_TCPIP_SEGMENT_SIZE];
						int size = send_buf.deBuffer(temp, 0, MAX_TCPIP_SEGMENT_SIZE);

						//copy data into smaller array to be sent
						byte[] data = new byte[size];

						for (int i = 0; i < size; i++) {
							data[i] = temp[i];
						}			
						TCPSegment seg = tcb.createDataSegment(data);

						//check if ack was received or timeout
						if (!sendAndWaitAckEstablished(seg)) {
							Log.e("Connection broken", "number of retries expired for ack");
							tcb.setState(ConnectionState.S_CLOSED);
							System.exit(0);
						}
					} else if (closePending && tcb.getState() == ConnectionState.S_FIN_WAIT_1){
						//send fin
						TCPSegment fin = tcb.createControlSegment(TCPSegmentType.FIN);

						if (sendAndWaitAckEstablished(fin)){
							switch(tcb.getState()){
							case S_FIN_WAIT_1:
								tcb.setState(ConnectionState.S_FIN_WAIT_2);
								return;
							default:
							}
							//get out of the sending loop.
							break;
						}
						//if we fail to receive a fin ack, force close
						tcb.setState(ConnectionState.S_CLOSED);
						return;
					} else {
						//no packet due
						try {
							synchronized (send_buf) {
								send_buf.wait(1000);
							}
						} catch (InterruptedException e) { e.printStackTrace(); }
					}
				}
				//exited the main loop, do closing
				waitForClose();
			}
		}

		private class ReceiverThread implements Runnable {

			public void run() {
				TCPSegment seg;

				while (tcb.getState() == ConnectionState.S_ESTABLISHED || 
						tcb.getState() == ConnectionState.S_FIN_WAIT_1 ||
						tcb.getState() == ConnectionState.S_FIN_WAIT_2 ||
						tcb.getState() == ConnectionState.S_CLOSING ||
						tcb.getState() == ConnectionState.S_CLOSE_WAIT ||
						tcb.getState() == ConnectionState.S_LAST_ACK)
				{ 
					try {
						seg = sockRecv(2000);
						handlePacket(seg);
					} catch (InterruptedException e) {
						//continue
					}
				}
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
	 * @param the TCP packet to be sent
	 * @throws IOException 
	 */
	void send_tcp_segment(IpAddress destination, TCPSegment p) throws IOException{
		//get integer value of IPAddress
		int destIpInt = destination.getAddress();

		//encode tcp packet
		byte[] bytes = p.encode();

		//calculate checksum
		int source = ip.getLocalAddress().getAddress();
		short checksum = p.checksum = TCPSegment.calculateChecksum(source, destIpInt, bytes.length, bytes);

		//add checksum to packet bytes
		bytes[TCPSegment.CHECKSUM_OFFSET] = (byte) ((checksum >>8) & 0x00ff);
		bytes[TCPSegment.CHECKSUM_OFFSET + 1] = (byte) ((checksum) & 0x00ff);

		//hexdump the packet for debugging
		StringBuilder sb = new StringBuilder();
		for (byte b : bytes) {
			sb.append(String.format("%02X ", b));
		}
		Log.d("send_tcp_segment()","Packet bytes: " + sb.toString());

		//create new packet and increment ID counter
		Packet ip_packet = new Packet(destIpInt, IP.TCP_PROTOCOL, ip_packet_id,
				bytes, bytes.length);
		ip_packet.source = source;
		ip_packet_id++;

		//log for debugging details
		Log.d("send_tcp_segment()","Packet to be sent: " + p.toString());
		Log.d("send_tcp_segment()","to IP : " + destination.toString() + " at port : " + p.dest_port + " From IP: " +
				ip.getLocalAddress().toString() + " at port " + p.src_port);
		Log.d("send_tcp_segment()","Other pseudo header fields - length: " + bytes.length + " protocol: " + IP.TCP_PROTOCOL);

		//send packet
		ip.ip_send(ip_packet);
	}


	/**
	 * receive a packet within a given time
	 * @param timeout the timeout (seconds) to wait for a packet. If set to a value <= 0, it waits indefinitely.
	 * @throws InterruptedException
	 * @throws InvalidPacketException
	 */
	TCPSegment recv_tcp_segment(int timeout) throws InvalidPacketException, InterruptedException{
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

		//hexdump the packet for debugging
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < ip_packet.length; i++) {
			sb.append(String.format("%02X ", ip_packet.data[i]));
		}
		Log.d("recv_tcp_segment()","Packet bytes: " + sb.toString());

		//wrong protocol
		if(ip_packet.protocol != IP.TCP_PROTOCOL){
			//not for me
			throw new InvalidPacketException("Wrong protocol. Ecpected TCP");
		}
		//packet is too short to parse
		if(ip_packet.length < TCPSegment.HEADER_LENGTH || ip_packet.data.length < TCPSegment.HEADER_LENGTH 
				|| ip_packet.data.length < ip_packet.length){
			throw new InvalidPacketException("Packet too short");
		}

		//parse packet
		TCPSegment tcp_packet = TCPSegment.decode(ip_packet.data, ip_packet.length);

		//validate checksum
		int sourceip = ip_packet.source;
		int destip = ip_packet.destination;
		int checksum = TCPSegment.calculateChecksum(sourceip, destip, ip_packet.length, ip_packet.data);

		if (checksum != tcp_packet.checksum){
			//packet was corrupted because checksum is not correct
			throw new InvalidPacketException("Invalid checksum. Expected: " + checksum + ". Received: " + tcp_packet.checksum);
		} else if (tcp_packet.getSegmentType() == TCPSegmentType.INVALID){
			throw new InvalidPacketException("Invalid flags");
		}

		//get source IP address which is used by the higher layers
		tcp_packet.source_ip = IpAddress.getAddress(ip_packet.source);

		Log.d("recv_tcp_segment()", "received packet: " + tcp_packet.toString());

		return tcp_packet;
	}
}