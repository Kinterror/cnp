package nl.vu.cs.cn;

import java.util.LinkedList;

/**
 * class which represents the receive buffer. Internally represented using a linked list, it is possible to read or
 * write any number of bytes from the buffer and return them in a byte array.
 * @author Boris Mulder
 */
public class BoundedByteBuffer {
	
	private LinkedList<byte[]> list;
	
	private int length;
	private int max_len;

	public BoundedByteBuffer(int max_len){
		list = new LinkedList<byte[]>();
		this.max_len = max_len;
		this.length = 0;
	}
	
	/**
	 * Adds bytes to the buffer
	 * @param arr
	 * @throws FullCollectionException
	 */
	public synchronized void buffer(byte[] arr) throws FullCollectionException {
		if (arr.length + length > max_len){
			throw new FullCollectionException("BoundedByteBuffer");
		}
		
		list.add(arr);
		length += arr.length;
	}
	
	/**
	 * tries to read n bytes from the socket buffer. If it contains less than n bytes, all bytes are returned
	 * and the number of bytes actually read.
	 * @param array
	 * @param offset the offset on which to start writing to the array
	 * @param nBytes
	 * @return the number of bytes read from the buffer
	 * @throws EmptyCollectionException 
	 */
	public synchronized int deBuffer(byte[] array, int offset, int nBytes) {
		int i = offset, nread = 0;
		
		//do not bother to read 0 or less bytes
		if(nBytes <= 0 || length <= 0){
			return 0;
		}
		
		//check if the array is too small. If so, fill the buffer instead of putting in nBytes bytes.
		int n = array.length - offset;
		if (n < nBytes){
			nBytes = n;
		}
		
		byte[] in;
		int j;
		
		do{
			//remove head of list
			in = list.removeFirst();
			length -= in.length;
			
			//copy data into array
			for(j = 0; j < in.length && j < nBytes; j++){
				array[i] = in[j];
				i++;
			}
			
			nBytes -= j;
			nread += j;
			
			//if there are no bytes left
			if (length <= 0){
				break;
			}
			
		}
		while (nBytes > 0); //while there are still bytes to be read from the buffer
		
		//if we didn't copy all bytes
		if (in.length > j){
			//add the remaining bytes back to the list
			byte[] temp = new byte[in.length - j];
			for(int k = 0; k < temp.length; k++){
				temp[k] = in[k + j];
			}
			//add the array as first element
			list.addFirst(temp);
			length += temp.length;
		
		}
		return nread;
	}
	
	public synchronized int length(){
		return length;
	}
	
	public synchronized boolean isEmpty(){
		return (length == 0);
	}
}
