package com.aminbros.epnanohttpd;

import static org.junit.Assert.assertEquals;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.lang.InterruptedException;
import java.lang.RuntimeException;
import java.lang.AssertionError;
import java.lang.Exception;
import java.util.ArrayList;
import java.util.HashMap;

import com.aminbros.epnanohttpd.EPNanoHTTPD;
import com.aminbros.epnanohttpd.EPProxyThread;

import java.io.InputStream;
import java.io.File;
import java.io.IOException;

import java.net.URL;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import com.aminbros.util.Log;
// import android.util.Log;

/**
 * Unit test for partial load
 */
public class TestPartialGet01
{
  public final static String HOST = "127.0.0.1";
  public final static int PORT = 8080;
  
  protected String mFileUrl;
  protected String mFileSha256;
  protected long mFileSize;
  protected File mCacheDir;
  protected EPNanoHTTPD mHttpd;

  @Before
  public void setUp() throws Exception {
    mFileUrl = System.getenv("TEST_FILE_URL");
    mFileSha256 = System.getenv("TEST_FILE_SHA256");
    String cachedir = System.getenv("TEST_CACHE_DIR");
    String sizestr = System.getenv("TEST_FILE_SIZE");
    if (mFileUrl == null || mFileUrl.isEmpty() || mFileSha256 == null ||
        mFileSha256.isEmpty() || sizestr == null || sizestr.isEmpty()) {
      throw new AssertionError("env TEST_FILE_URL/TEST_FILE_SIZE/TEST_FILE_SHA256/TEST_CACHE_DIR is empty");
    }
    mFileSize = Long.valueOf(sizestr);
    mCacheDir = new File(cachedir);
    // initiate the daemon
    mHttpd = new EPNanoHTTPD(HOST, PORT, mCacheDir, mFileUrl);
    mHttpd.start(EPNanoHTTPD.SOCKET_READ_TIMEOUT, true);
  }
  
  /**
   * get file with partial requests
   */
  @Test
  public void testPartialLoad()
  {
    try {
      URL url = new URL("http://127.0.0.1:8080/");
      ArrayList<long[]> arrdata = new ArrayList();
      final long TSIZE = 12;
      final long MAX_DELAY = 20 * 1000;
      long start = 0;
      for (int i = 0; i < TSIZE; i++) {
        long []data = new long[3];
        // delay
        data[0] = (long)(Math.random() * MAX_DELAY);
        // range
        data[1] = start;
        data[2] = i + 1 == TSIZE ? mFileSize - 1 :
          Math.min(mFileSize - 1,
                   start + (long)((0.7 + Math.random() * 0.6) *
                                  (double)mFileSize / TSIZE));
        arrdata.add(data);
        start = data[2] + 1;
        if (start == mFileSize) {
          break;
        }
      }
      String digest = digestGetRequests(url, arrdata);
      assertEquals(mFileSha256.toLowerCase(), digest.toLowerCase());
    } catch (MalformedURLException e) {
      throw new RuntimeException(e);
    }
  }


  protected String digestGetRequests (URL url, ArrayList<long[]> arrdata) {
    ArrayList<GetRequestThread> threads = new ArrayList();
    for (int i = 0; i < arrdata.size(); i++) {
      long []data = arrdata.get(i);
      GetRequestThread thread = new GetRequestThread(data[0], url, data[1], data[2]);
      thread.start();
      threads.add(thread);
    }
    // wait for all threads
    try {
      for (int i = 0; i < threads.size(); i++) {
        threads.get(i).join(0);
      }
    } catch (InterruptedException e) {
      throw new RuntimeException(e);
    }
    // digest the outputs
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      for (int i = 0; i < threads.size(); i++) {
        long []data = arrdata.get(i);
        GetRequestThread thread = threads.get(i);
        byte []result = thread.getResult();
        if (result == null) {
          throw new AssertionError("No result for req thread " +
                                   String.valueOf(i) + ", " +
                                   String.valueOf(data[0]) + ", " +
                                   String.valueOf(data[1]) + ", " +
                                   String.valueOf(data[2]));
                                 
        }
        digest.update(result);
      }
      byte[] hash = digest.digest();
      String ret = "";
      for (int i = 0; i < 32; i++) {
        ret += String.format("%02x", hash[i]);
      }
      return ret;
    } catch (NoSuchAlgorithmException e) {
      throw new RuntimeException(e);
    }
  }
  
  class GetRequestThread implements Runnable {
    long mDelay;
    URL mUrl;
    long mStart;
    long mEnd;
    byte []mResult;
    Thread mThread;
    GetRequestThread (long delay, URL url, long start, long end) {
      mDelay = delay;
      mUrl = url;
      mStart = start;
      mEnd = end;
      mResult = null;
      mThread = new Thread(this, "GetRequestThread");
    }

    public void start () {
      mThread.start();
    }

    public void join (long milis) throws InterruptedException {
      mThread.join(milis);
    }

    public byte []getResult () {
      return mResult;
    }
    
    public void run () {
      try {
        Thread.sleep(mDelay);
        HashMap<String, String> headers = new HashMap();
        HttpURLConnection conn = EPProxyThread.httprequest("GET", mUrl, headers, null, mStart, mEnd);
        if (conn == null) {
          throw new RuntimeException("Unexpected range response from proxy!");
        }
        InputStream instream = conn.getInputStream();
        ArrayList<Byte> result = new ArrayList<Byte>();
        byte []buffer = new byte[1024*8];
        int size;
        while ((size = instream.read(buffer)) > 0) {
          for (int i = 0; i < size; i++) {
            result.add(buffer[i]);
          }
        }
        byte []resultbytes = new byte[result.size()];
        for (int i = 0; i < resultbytes.length; i++) {
          resultbytes[i] = result.get(i);
        }
        mResult = resultbytes;
      } catch (IOException e) {
        Log.e("GetRequestThread", "IOExce", e);
        mResult = null;
      } catch (InterruptedException e) {
        mResult = null;
      }
    }
  }
}
