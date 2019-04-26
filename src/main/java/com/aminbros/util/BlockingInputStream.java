package com.aminbros.util;

import java.io.IOException;
import java.io.InputStream;
import java.lang.UnsupportedOperationException;
import java.util.concurrent.BlockingQueue;

public class BlockingInputStream extends InputStream {

  boolean mClosed;
  BlockingQueue<byte[]> mBlockingQueue;
  byte[] mInQueue;
  int mInQueueOff;

  public BlockingInputStream (BlockingQueue<byte[]> blockingQueue) {
    super();
    mClosed = false;
    mBlockingQueue = blockingQueue;
    mInQueue = null;
    mInQueueOff = 0;
  }
  
  @Override
  public int available () {
    if (mInQueue != null) {
      return mInQueue.length;
    }
    byte[] data = mBlockingQueue.peek();
    return data == null ? 0 : data.length;
  }

  @Override
  public void	close () throws IOException {
    mClosed = true;
  }

  @Override
  public boolean markSupported () {
    return false;
  }
  
  public int read () throws IOException {
    if (mInQueue == null || mInQueueOff >= mInQueue.length) {
      try {
        mInQueue = mBlockingQueue.take();
      } catch (InterruptedException e) {
        throw new IOException(e);
      }
      mInQueueOff = 0;
    }
    return (int)mInQueue[mInQueueOff++];
  }
  
  @Override
  public int read (byte[] b) throws IOException {
    return read(b, 0, b.length);
  }
  
  @Override
  public int read (byte[] b, int off, int len) throws IOException {
    if (mClosed) {
      throw new IOException("stream has closed");
    }
    if (mInQueue != null) {
      int size = Math.min(len, mInQueue.length - mInQueueOff);
      for (int i = 0; i < size; i++) {
        b[i] = mInQueue[mInQueueOff + i];
      }
      if (mInQueue.length + mInQueueOff - size > 0) {
        setInQueue(mInQueue, mInQueueOff + size, mInQueue.length - size - mInQueueOff);
      }
      return size;
    }
    try {
      if (mBlockingQueue == null) {
        return 0;
      }
      byte[] data = mBlockingQueue.take();
      if (data.length == 0) {
        mBlockingQueue = null;
        return 0;
      }
      int size = Math.min(len, data.length);
      for (int i = 0; i < size; i++) {
        b[i] = data[i];
      }
      if (data.length - size > 0) {
        setInQueue(data, size, data.length - size);
      }
      return size;
    } catch (InterruptedException e) {
      throw new IOException(e);
    }
  }

  protected void setInQueue (byte[] b, int off, int len) {
    if (len > 0) {
      mInQueue = new byte[len];
      for (int i = 0; i < len; i++) {
        mInQueue[i] = b[off + i];
      }
    } else {
      mInQueue = null;
    }
    mInQueueOff = 0;
  }
  
}
