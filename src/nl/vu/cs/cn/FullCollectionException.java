package nl.vu.cs.cn;

public class FullCollectionException extends RuntimeException{

	/**
	 * 
	 */
	private static final long serialVersionUID = -5704041235053444701L;

	public FullCollectionException (String collection)
	{
		super ("The " + collection + " is full.");
	}
}
