package nl.vu.cs.cn;

import java.util.LinkedList;

/**
 * class which represents the receive buffer. Internally represented using a linked list, it is possible to read or
 * write any number of bytes from the buffer and return them in a byte array.
 * @author boris
 *
 */
public class BoundedArrayBuffer {
	
	private LinkedList<byte[]> list;
	
	private int length;
	private int max_len;

	public BoundedArrayBuffer(int max_len){
		list = new LinkedList<byte[]>();
		this.max_len = max_len;
		this.length = 0;
	}
	
	
	/**
	 * adds data to the buffer
	 */
	public synchronized void buffer(byte[] arr) throws FullCollectionException {
		if (arr.length + length > max_len){
			throw new FullCollectionException("BoundedArrayBuffer");
		}
		
		list.add(arr);
		length += arr.length;
	}
	
	/**
	 * tries to read n bytes from the socket buffer. If it contains less than n bytes, all bytes are returned
	 * and the number of bytes actually read.
	 * @param arr
	 * @param nBytes
	 * @return the number of bytes read from the buffer
	 */
	public synchronized int deBuffer(byte[] arr, int offset, int nBytes){
		int i = offset, nread = 0;
		
		//check if the buffer is too small. If so, fill the buffer instead of putting in nBytes bytes.
		int n;
		if (( n = arr.length - offset) < nBytes){
			nBytes = n;
		}
		
		//remove head of list
		byte[] in = list.remove();
		length -= in.length;
	
		while (in.length <= nBytes){
			//we want more bytes then there are in the first element, so get the first element and check again
			for(int j = 0; j < in.length; j++){
				arr[i] = in[j];
				i++;
			}
			nBytes -= in.length;
			nread += in.length;
			in = list.remove();
			length -= in.length;
		}
		
		//do we still have to read?
		if (nBytes == 0){
			return nread;
		}
		
		//copy remaining bytes into array
		for(int j = 0; j < nBytes; j++){
			arr[i] = in[j];
			i++;
		}
		nread += nBytes;
		
		//add the remaining bytes to the list
		byte[] temp = new byte[in.length - nBytes];
		for(int j = 0; j < temp.length; j++){
			temp[j] = in[j + nBytes];
		}
		//replace the first element with the new array
		list.addFirst(temp);
		length += temp.length;
	
		return nread;
	}
	
	public synchronized int length(){
		return length;
	}
	
	public synchronized boolean isEmpty(){
		return (length == 0);
	}
}
