package nl.vu.cs.cn;

import java.io.IOException;

import nl.vu.cs.cn.IP.IpAddress;

/**
 * This class represents a TCP stack. It should be built on top of the IP stack
 * which is bound to a given IP address.
 */
public class TCP {

	/** The underlying IP stack for this TCP stack. */
	// adding a simple comment
    private IP ip;

    /**
     * This class represents a TCP socket.
     *
     */
    public class Socket {

    	/* Hint: You probably need some socket specific data. */

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
			// TODO Auto-generated constructor stub
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
     * encode data in a TCP packet, add header, calculate checksum and sent the
     * packet through the IP layer
     * @return -
     * @param data bytes
     * @param destination port
     * @param destination IP
     */
    private void send_tcp_packet(){
    	
    }

}
