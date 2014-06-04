package nl.vu.cs.cn;

import java.util.Iterator;
import java.util.LinkedList;

/**
 * class which represents the receive buffer. Internally represented using a linked list, it is possible to read or
 * write any number of bytes from the buffer and return them in a byte array.
 * @author boris
 *
 */
public class UnboundedByteBuffer {
	
	private LinkedList<byte[]> list;

	/**
	 * adds data to the buffer
	 */
	public synchronized void buffer(byte[] arr){
		list.add(arr);
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
	
		while (in.length <= nBytes){
			//we want more bytes then there are in the first element, so get the first element and check again
			for(int j = 0; j < in.length; j++){
				arr[i] = in[j];
				i++;
			}
			nBytes -= in.length;
			nread += in.length;
			in = list.remove();
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
	
		return nread;
	}
	
	public synchronized int length(){
		int res = 0;
		
		Iterator<byte[]> in = list.descendingIterator();
		while(in.hasNext()){
			byte[] temp = in.next();
			res += temp.length;
		}
		
		return res;
	}
}
