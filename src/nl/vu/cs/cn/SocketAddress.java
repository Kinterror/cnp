package nl.vu.cs.cn;

import nl.vu.cs.cn.IP.IpAddress;

public class SocketAddress {
	private IpAddress ip;
	private int port;
	
	public SocketAddress(IpAddress ip, int port){
		this.ip = ip;
		this.port = port;
	}

	public IpAddress getIp() {
		return ip;
	}

	public int getPort() {
		return port;
	}
}
