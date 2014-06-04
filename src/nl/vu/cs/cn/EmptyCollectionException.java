package nl.vu.cs.cn;

//********************************************************************
//EmptyCollectionException.java     Authors: Lewis/Chase
//
//Represents the situation in which a collection is empty.
//********************************************************************

public class EmptyCollectionException extends RuntimeException
{
	private static final long serialVersionUID = 6771728668176760251L;

//-----------------------------------------------------------------
//  Sets up this exception with an appropriate message.
//-----------------------------------------------------------------
	public EmptyCollectionException (String collection)
	{
		super ("The " + collection + " is empty.");
	}
}
