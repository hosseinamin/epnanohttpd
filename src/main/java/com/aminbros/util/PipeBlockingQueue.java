package com.aminbros.util;

import java.util.concurrent.ArrayBlockingQueue;

public class PipeBlockingQueue extends ArrayBlockingQueue implements IPipeStatus {
  public PipeBlockingQueue (int capacity) {
    super(capacity);
  }
  private boolean mIsListening;
  public synchronized boolean isListening() {
    return mIsListening;
  }
  public synchronized void setListening(boolean listening) {
    mIsListening = listening;
  }
}
