package nl.vu.cs.cn;

public class CorruptedPacketException extends Exception {
	
	private static final long serialVersionUID = 1L;
	
	public CorruptedPacketException(String s){
		super(s);
	}
}
