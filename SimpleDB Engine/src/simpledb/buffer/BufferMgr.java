package simpledb.buffer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import simpledb.file.*;
import simpledb.log.LogMgr;

/**
 * Manages the pinning and unpinning of buffers to blocks.
 * @author Edward Sciore
 *
 */
public class BufferMgr {
	public static Map<BlockId, Buffer> bufferPoolMap;
	public static List<Buffer> unpinBufferPool;
	private FileMgr fm;
	private LogMgr lm;
   	private int numAvailable;
   	public int original;
   	private static final long MAX_TIME = 10000; // 10 seconds
   
   /**
    * Creates a buffer manager having the specified number 
    * of buffer slots.
    * This constructor depends on a {@link FileMgr} and
    * {@link simpledb.log.LogMgr LogMgr} object.
    * @param numbuffs the number of buffer slots to allocate
    */
   public BufferMgr(FileMgr fm, LogMgr lm, int numbuffs) {
	   bufferPoolMap = new HashMap<BlockId, Buffer>();
	   unpinBufferPool = new ArrayList<>();
      numAvailable = numbuffs;
      original = numbuffs;
      this.fm = fm;
      this.lm = lm;
      //for (int i=0; i<numbuffs; i++)
    	  //unpinBufferPool.add(new Buffer(fm, lm, i));
   }
   
   /**
    * Returns the number of available (i.e. unpinned) buffers.
    * @return the number of available buffers
    */
   public synchronized int available() {
      return numAvailable;
   }
   
   /**
    * Flushes the dirty buffers modified by the specified transaction.
    * @param txnum the transaction's id number
    */
   public synchronized void flushAll(int txnum) {
	   for (Map.Entry<BlockId,Buffer> entry : bufferPoolMap.entrySet()) {
		   Buffer buff = entry.getValue();
		   if (buff.modifyingTx() == txnum)
			   buff.flush();
	   }
   }
   
   
   /**
    * Unpins the specified data buffer. If its pin count
    * goes to zero, then notify any waiting threads.
    * @param buff the buffer to be unpinned
    */
   public synchronized void unpin(Buffer buff) {
      buff.unpin();
      if (!buff.isPinned()) {
         numAvailable++;
         if (buff.getLsn() > -1) {
        	 unpinBufferPool.add(buff);
        	 Set<Buffer> set = new HashSet<>(unpinBufferPool);
        	 unpinBufferPool.clear();
        	 unpinBufferPool.addAll(set);
         }
         notifyAll();
      }
      bufferPoolMap.put(buff.block(), buff);
   }
   
   /**
    * Pins a buffer to the specified block, potentially
    * waiting until a buffer becomes available.
    * If no buffer becomes available within a fixed 
    * time period, then a {@link BufferAbortException} is thrown.
    * @param blk a reference to a disk block
    * @return the buffer pinned to that block
    */
   public synchronized Buffer pin(BlockId blk) {
      try {
         long timestamp = System.currentTimeMillis();
         Buffer buff = tryToPin(blk);
         while (buff == null && !waitingTooLong(timestamp)) {
            wait(MAX_TIME);
            buff = tryToPin(blk);
         }
         if (buff == null) {
            throw new BufferAbortException();
         }
         return buff;
      }
      catch(InterruptedException e) {
         throw new BufferAbortException();
      }
   }  
   
   private boolean waitingTooLong(long starttime) {
      return System.currentTimeMillis() - starttime > MAX_TIME;
   }
   
   /**
    * Tries to pin a buffer to the specified block. 
    * If there is already a buffer assigned to that block
    * then that buffer is used;  
    * otherwise, an unpinned buffer from the pool is chosen.
    * Returns a null value if there are no available buffers.
    * @param blk a reference to a disk block
    * @return the pinned buffer
    */
   private Buffer tryToPin(BlockId blk) {
      Buffer buff = findExistingBuffer(blk);
      if (buff == null) {
    	  if (bufferPoolMap.size() < original)
    		  buff = new Buffer(fm, lm, bufferPoolMap.size());
    	  else {
    		  buff = chooseUnpinnedBuffer();
    		  if (buff == null)
    			  return null;
    		  BlockId prev = buff.block();
    		  if (prev != null)
    			  bufferPoolMap.remove(buff.block());
    	  }
         buff.assignToBlock(blk);
      }
      if (!buff.isPinned())
         numAvailable--;
      buff.pin();
      bufferPoolMap.put(blk, buff);
      return buff;
   }
   
   private Buffer findExistingBuffer(BlockId blk) {
	   /*
      for (Buffer buff : bufferpool) {
         BlockId b = buff.block();
         if (b != null && b.equals(blk))
            return buff;
      }
      return null;
      */
	   if (bufferPoolMap.containsKey(blk)) {
		   Buffer ret = bufferPoolMap.get(blk);
		   return ret;
	   }
	   return null;
   }
   
   private Buffer chooseUnpinnedBuffer() {
	   if (unpinBufferPool.isEmpty())
		   return null;
	   Collections.sort(unpinBufferPool, new sortByLsn());
	   Buffer ub = unpinBufferPool.remove(0);
	   Buffer buff = findExistingBuffer(ub.block());
	   return buff;
   }
   
   public static void printStatus() {
	   System.out.println();
	   System.out.println("Allocated Buffers:");
	   for (Map.Entry<BlockId,Buffer> entry : bufferPoolMap.entrySet()) {
		   
		   Buffer value = entry.getValue();
		   BlockId key = entry.getKey();
		   System.out.print("Buffer " + value.getId() + ": [File " + key.fileName() + ", block " + key.number() + "] " + (value.isPinned() ? "pinned" : "unpinned"));
		   System.out.println(" lsn" + value.getLsn());
	   }
	   System.out.println();
	   System.out.println("Unpinned Buffers in LRM order: " );
	   Collections.sort(unpinBufferPool, new sortByLsn());
	   for (Buffer buf: unpinBufferPool) {
		   System.out.print(buf.getId() + "(" + "lsn" + buf.getLsn() + ")" + " ");
	   }
	   System.out.println("\n");
   }
   
}
class sortByLsn implements Comparator<Buffer> {  
	@Override
	public int compare(Buffer a, Buffer b)  {  
		return a.getLsn() < b.getLsn() ? -1 : a.getLsn() == b.getLsn() ? 0 : 1;
	}  
}  
