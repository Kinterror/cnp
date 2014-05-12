package nl.vu.cs.cn;

public class InvalidPacketException extends Exception {
	
	private static final long serialVersionUID = 1L;
	
	public InvalidPacketException(String s){
		super(s);
	}
}
