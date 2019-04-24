package com.aminbros.epnanohttpd;
    
import java.lang.Exception;
import java.lang.RuntimeException;
import java.lang.InterruptedException;

import java.util.Map;
import java.util.HashMap;
import java.util.List;
import java.util.ArrayList;
import java.net.URLEncoder;
import java.io.PipedOutputStream;
import java.io.PipedInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;
import java.util.regex.Matcher;
import java.util.Date;

import fi.iki.elonen.NanoHTTPD;
import fi.iki.elonen.NanoHTTPD.Response;
import fi.iki.elonen.NanoHTTPD.Response.Status;
import fi.iki.elonen.NanoHTTPD.Response.IStatus;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;

import java.net.URL;
import java.net.HttpURLConnection;
import java.net.InetAddress;

import com.aminbros.util.Log;
// import android.util.Log;

import com.aminbros.epnanohttpd.CacheMD.Chunk;

class EPProxyThread implements Runnable {
  public final static String TAG = "EPProxyThread";
  public final static int BUFFSIZE = 1024 * 8;
  public final static long SKIP_VALIDATION_TIMEOUT = 5 * 60 * 60;

  protected boolean mStarted;
  protected Thread mThread;
  protected String mThreadName;
  protected String mMethod;
  protected URL mUrl;
  protected Map<String, String> mHeaders;
  protected File mCacheDir;
  protected PipedOutputStream mOutStreamPipe;
  protected HttpURLConnection mActiveConn;
  protected long []mRange;
  protected long mContentSize;
  protected CacheMD mCacheMD;
  protected InputStream mInStream; // POST/PUT stream
  protected long mSkipValidationTimeout;
  
  EPProxyThread(String method, URL url, Map<String, String> headers, InputStream instream, File cachedir) {
    mStarted = false;
    mThreadName = "EPProxyThread";
    mSkipValidationTimeout = SKIP_VALIDATION_TIMEOUT;
    mMethod = method;
    mUrl = url;
    mHeaders = headers;
    mCacheDir = new File(cachedir, EPProxyThread.digestHashStr(url.toString(), 16));
    mCacheMD = null;
    String rangestr = headers.get("range");
    if (rangestr != null) {
      if (rangestr.indexOf("bytes=") == 0) {
        rangestr = rangestr.substring(6);
      } else {
        throw new RuntimeException("Unexpected Range header, Prefixed with `bytes=` is accepted`");
      }
      String []rangeparts = rangestr.split("-");
      if (rangeparts.length == 2) {
        mRange = new long[2];
        mRange[0] = Long.valueOf(rangeparts[0]);
        mRange[1] = Long.valueOf(rangeparts[1]);
      } else if (rangeparts.length == 1) {
        mRange[0] = Long.valueOf(rangeparts[0]);
        mRange[1] = -1;
      } else {
        throw new RuntimeException("invalid header `range`: " + rangestr);
      }
    } else {
      mRange = null;
    }
    mInStream = instream;
  }

  public void run () {
    CacheMDManager cmanager = CacheMDManager.getInstance();
    long start = mRange == null ? 0 : mRange[0];
    long finalend = mRange == null && mRange[1] == -1 ?
      mContentSize - 1 : mRange[1];
    FileOutputStream fileOutStream = null;
    Chunk chunk = null;
    long writesize = 0;
    try {
      HttpURLConnection conn = mActiveConn;
      mActiveConn = null;
      while (start <= finalend) {
        if (Thread.interrupted()) {
          throw new InterruptedException();
        }
        long end = finalend;
        chunk = null;
        fileOutStream = null;
        if (conn == null) {
          if (mCacheMD != null) {
            chunk = mCacheMD.getOptimalChunkForRange(start, end);
          }
          if (chunk == null || chunk.start > start || chunk.active) {
            if (chunk != null) {
              end = Math.min(chunk.start - 1, end);
            }
            conn = EPProxyThread.httprequest(mMethod, mUrl, mHeaders, mInStream, start, end);
            if (conn == null || conn.getResponseCode() != 206) {
              // range request failed
              Log.w(TAG, "pipe thread http range request failed: " +
                    (conn != null ?
                     "[" +String.valueOf(conn.getResponseCode()) + "] " +
                     conn.getResponseMessage() : "[null]"));
              return;
            }
            chunk = mCacheMD == null ? null : mCacheMD.getChunkAt(start);
            if (mCacheMD != null && chunk == null) {
              // start caching
              String filename = "part_" + String.valueOf(start);
              try {
                if (!mCacheDir.exists()) {
                  if (!mCacheDir.mkdirs()) {
                    throw new IOException("Could not create cachedir directory at " + mCacheDir.getAbsolutePath());
                  }
                }
                fileOutStream = new FileOutputStream(new File(mCacheDir, filename));
              } catch (IOException e) {
                Log.w(TAG, "Cache chunk save IOExc", e);
              }
              chunk = new Chunk();
              chunk.start = start;
              chunk.end = end;
              chunk.filename = filename;
              chunk.active = true;
              mCacheMD.addChunk(chunk);
            }
          } else {
            FileInputStream fileInStream = null;
            try {
              fileInStream = new FileInputStream(new File(mCacheDir, chunk.filename));
              fileInStream.skip(start - chunk.start);
              byte []buffer = new byte[BUFFSIZE];
              int size;
              while ((size = fileInStream.read(buffer)) > 0) {
                size = Math.min(size, (int)(end - start + 1));
                mOutStreamPipe.write(buffer, 0, size);
                start += size;
                writesize += size;
                if (start > end) {
                  break; // done
                }
                if (Thread.interrupted()) {
                  throw new InterruptedException();
                }
              }
              fileInStream.close();
              if (Thread.interrupted()) {
                throw new InterruptedException();
              }
              continue; // go for remaining chunks
            } catch (IOException e) {
              if (fileInStream != null) {
                try {
                  fileInStream.close();
                } catch (IOException e2) {
                  // pass
                }
              }
              Log.w(TAG, "Cache chunk read IOExc", e);
              // fallback to http request
              if (start <= end) {
                conn = EPProxyThread.httprequest(mMethod, mUrl, mHeaders, mInStream, start, end);
                if (conn == null || conn.getResponseCode() != 206) {
                  // range request failed
                  Log.w(TAG, "pipe thread http range request failed: " +
                        (conn != null ?
                         "[" +String.valueOf(conn.getResponseCode()) + "] " +
                         conn.getResponseMessage() : "[null]"));
                  return;
                }
              } else {
                break;
              }
            }
          }
        }
        if (conn.getResponseCode() >= 400) {
          Log.w(TAG, "pipe thread http unexpected result: [" +
                String.valueOf(conn.getResponseCode()) + "] " +
                conn.getResponseMessage());
          // not successful response, die
          return;
        }
        InputStream instream = conn.getInputStream();
        byte []buffer = new byte[BUFFSIZE];
        int size;
        while ((size = instream.read(buffer)) > 0) {
          size = Math.min(size, (int)(end - start + 1));
          if (fileOutStream != null) {
            fileOutStream.write(buffer, 0, size);
          }
          mOutStreamPipe.write(buffer, 0, size);
          start += size;
          writesize += size;
          if (start > end) {
            break; // done
          }
          if (Thread.interrupted()) {
            throw new InterruptedException();
          }
        }
        if (fileOutStream != null) {
          fileOutStream.close();
        }
        if (chunk != null) {
          chunk.active = false;
          CacheMDManager.saveCacheMD(mCacheDir, mCacheMD);
        }
        conn = null;
      }
    } catch (InterruptedException e) {
      // thread interrupted
      Log.w(TAG, "InterruptedExc at pipe thread", e);
    } catch (FileNotFoundException e) {
      Log.e(TAG, "FileNotFoundExc at pipe thread", e);
    } catch (Exception e) {
      Log.e(TAG, "Caught Exception at pipe thread", e);
    } finally {
      if (fileOutStream != null) {
        try {
          fileOutStream.close();
        } catch (IOException e) {
          // pass
        }
      }
      if (chunk != null && chunk.active) {
        if (chunk.start < start - 1) {
          chunk.end = start - 1;
          chunk.active = false;
        } else {
          (new File(mCacheDir, chunk.filename)).delete();
          mCacheMD.removeChunk(chunk);
        }
      }
      if (mCacheMD != null) {
        cmanager.unsubscribe(mCacheMD, this);
      }
      try {
        mOutStreamPipe.close();
      } catch (IOException e) {
        // pass
      }
    }
  }
  
  public static HttpURLConnection httprequesthead (URL url, Map<String, String> headers) throws IOException {
    HttpURLConnection conn = httprequest("HEAD", url, headers, null, -1, -1);
    try {
      conn.getInputStream().close();
    } catch (Exception t) {
    }
    return conn;
  }

  public static HttpURLConnection httprequest (String method, URL url, Map<String, String> headers, InputStream instream, long start, long end) throws IOException {
    HttpURLConnection conn = (HttpURLConnection)url.openConnection();
    conn.setFollowRedirects(true);
    conn.setRequestMethod(method);
    for (Map.Entry<String, String> entry : headers.entrySet()) {
      conn.setRequestProperty(entry.getKey(), entry.getValue());
    }
    if (start >= 0) {
      conn.setRequestProperty("Range", "bytes=" + String.valueOf(start) + "-" +
                              (end < 0 ? "" : String.valueOf(end)));
    }
    conn.setUseCaches(false);
    conn.setDoInput(true);
    // header request sent, returns response code
    if (conn.getResponseCode() >= 400) {
      return conn;
    }
    if ((method.equals("POST") || method.equals("PUT")) && instream != null) {
      conn.setDoOutput(true);
      OutputStream outstream = conn.getOutputStream();
      byte []buffer = new byte[BUFFSIZE];
      int size;
      while ((size = instream.read(buffer)) > 0) {
        outstream.write(buffer, 0, size);
      }
    }
    if (start >= 0) {
      // has explicit range, check validity
      long []rangedata = EPProxyThread._getContentRangeData(conn.getHeaderField("Content-Range"));
      if (rangedata == null || rangedata[0] != start ||
          (end >= 0 && rangedata[1] != end)) {
        Log.w(TAG, "httprequest with range failed (" +
              String.valueOf(start) + ", " + String.valueOf(end) + "): " +
              (rangedata != null ?
               rangedata[0] + "-" + rangedata[1] + "/" + rangedata[2] :
               "(null)"));
        return null;
      }
    }
    return conn;
  }

  public Response start () {
    try {
      if (mStarted) {
        throw new RuntimeException("already started");
      }
      mStarted = true;
      CacheMDManager cmanager = CacheMDManager.getInstance();
      boolean keepActiveConn = false;
      if (mMethod == "GET" && mCacheDir != null) {
        mCacheMD = cmanager.subscribeIfExists(mCacheDir.getAbsolutePath(), this);
      }
      Map<String,List<String>> allHeaders = null;
      Map<String,String> headers = new HashMap();
      boolean revalidate = mCacheMD == null || mCacheMD.mustRevalidate ||
        (new Date().getTime()/1000) > mCacheMD.lastUpdate + mSkipValidationTimeout ||
        mCacheMD.headers == null;
      if (!revalidate) {
        // check, mCacheMD has requested range
        long []range = new long[2];
        if (mRange == null) {
          range[0] = 0;
          range[1] = mCacheMD.getSize() - 1;
        } else {
          range[0] = mRange[0];
          range[1] = mRange[1] < 0 ? mCacheMD.getSize() - 1 : mRange[1];
        }
        for (Chunk achunk : mCacheMD.getChunks()) {
          if (!achunk.active && achunk.start <= range[0] &&
              achunk.end >= range[0]) {
            range[0] = achunk.end + 1;
            if (range[0] > range[1]) {
              break;
            }
          }
        }
        revalidate = !(range[0] > range[1]);
      }
      // check for network connectivity, for skipping revalidation
      if (revalidate && mCacheMD != null && mCacheMD.headers != null) {
        int timeout = 2000;
        String hostname = mUrl.getHost();
        InetAddress[] addresses = InetAddress.getAllByName(hostname);
        for (InetAddress address : addresses) {
          if (!address.isReachable(timeout)) {
            revalidate = false;
          }
        }
      }
      if (mCacheMD != null && revalidate) {
        mActiveConn = httprequesthead(mUrl, mHeaders);
        allHeaders = mActiveConn.getHeaderFields(); 
        String etag = mActiveConn.getHeaderField("ETag");
        if (etag == null) {
          // treat Last-Modified as ETag
          etag = mActiveConn.getHeaderField("Last-Modified");
        }
        if (!(mActiveConn.getResponseCode() == 200 ||
              mActiveConn.getResponseCode() == 206) || etag == null ||
            !mCacheMD.getETag().equals(etag)) {
          mCacheMD.deleteonend = true;
          cmanager.unsubscribe(mCacheMD, this);
          mCacheMD = null;
          mActiveConn = null;
        }
      }
      if (mActiveConn == null && revalidate) {
        mActiveConn = httprequest(mMethod, mUrl, mHeaders, mInStream, -1, -1);
        keepActiveConn = true;
      }
      mContentSize = -1;
      if (revalidate) {
        allHeaders = mActiveConn.getHeaderFields();
        String clenstr = mActiveConn.getHeaderField("Content-Length");
        if (clenstr != null) {
          try {
            mContentSize = Long.valueOf(clenstr);
          } catch (NumberFormatException e) {
            Log.w(TAG, "Internal Error response at start", e);
            return EPNanoHTTPD.newFixedLengthResponse(Status.INTERNAL_ERROR, NanoHTTPD.MIME_PLAINTEXT, "Proxy Internal Error, Response Content-Length is not a number ");
          }
        }
        String etag = mActiveConn.getHeaderField("ETag");
        if (etag == null) {
          // treat Last-Modified as ETag
          etag = mActiveConn.getHeaderField("Last-Modified");
        }
        long []rangedata = _getContentRangeData(mActiveConn.getHeaderField("Content-Range"));
        if (mCacheMD == null && mContentSize != -1 && etag != null) {
          long filesize = rangedata != null ? rangedata[2] : mContentSize;
          mCacheMD = cmanager.subscribeIfNotExists(mCacheDir.getAbsolutePath(), etag, filesize, this);
        }
        if (mCacheMD != null) {
          long cachemd_csize = mCacheMD.getSize();
          if (!(mActiveConn.getResponseCode() == 200 ||
                mActiveConn.getResponseCode() == 206) || mContentSize == -1 ||
              (rangedata != null && rangedata[2] != cachemd_csize) ||
              (rangedata == null && mContentSize != cachemd_csize))  {
            mCacheMD.deleteonend = true;
            cmanager.unsubscribe(mCacheMD, this);
            mCacheMD = null;
          }
          boolean mustRevalidate = false;
          List <String>cachecontrollist = allHeaders.get("cache-control");
          if (cachecontrollist != null) {
            for (String cachecontrol : cachecontrollist) {
              if (cachecontrol.toLowerCase().equals("must-revalidate")) {
                mustRevalidate = true;
                break;
              }
            }
          }
          mCacheMD.mustRevalidate = mustRevalidate;
          mCacheMD.lastUpdate = (new Date().getTime()) / 1000;
          HashMap<String, List<String>> saveHeaders = new HashMap();
          for (Map.Entry<String, List<String>> entry : allHeaders.entrySet()) {
            if (entry.getKey() != null) { // exclude response line
              saveHeaders.put(entry.getKey().toLowerCase(), entry.getValue());
            }
          }
          mCacheMD.headers = saveHeaders;
        }
      } else {
        allHeaders = new HashMap(mCacheMD.headers);
        long filesize = mCacheMD.getSize();
        List<String> tmparr;
        allHeaders.remove("content-range");
        allHeaders.remove("content-length");
        if (mRange != null) {
          long end = mRange[1] < 0 ? filesize - 1 : mRange[1];
          mContentSize = end - mRange[0] + 1;
          tmparr = new ArrayList();
          tmparr.add("bytes " + mRange[0] + "-" + end + "/" + filesize);
          allHeaders.put("content-range", tmparr);
        } else {
          mContentSize = filesize;
        }
        tmparr = new ArrayList();
        tmparr.add(String.valueOf(mContentSize));
        allHeaders.put("content-length", tmparr);
      }
      for (Map.Entry<String, List<String>> entry : allHeaders.entrySet()) {
        if (entry.getKey() != null && entry.getValue().size() > 0) {
          headers.put(entry.getKey(), entry.getValue().get(0));
        }
      }
      IStatus respStatus = revalidate ?
        EPNanoHTTPD.getIStatusFromCode(mActiveConn.getResponseCode()) :
        (headers.get("content-range") != null ? Status.PARTIAL_CONTENT : Status.OK);
      if (respStatus == null) {
        Log.w(TAG, "Unknown response status: " + String.valueOf(mActiveConn.getResponseCode()));
        return EPNanoHTTPD.newFixedLengthResponse(Status.INTERNAL_ERROR, NanoHTTPD.MIME_PLAINTEXT, "Proxy Internal Error, Unknown response status: " + String.valueOf(mActiveConn.getResponseCode()));
      }
      if (revalidate && mActiveConn.getResponseCode() >= 400) {
        // not successful response
        return EPNanoHTTPD.newFixedLengthResponse(respStatus, NanoHTTPD.MIME_PLAINTEXT, respStatus.getDescription(), headers);
      }
      String mimeType = headers.get("content-type");
      if (mimeType == null) {
        mimeType = NanoHTTPD.MIME_PLAINTEXT;
      }
      if (mContentSize != -1) {
        if (!keepActiveConn) {
          mActiveConn = null;
        }
        PipedInputStream instream = new PipedInputStream();
        mOutStreamPipe = new PipedOutputStream(instream);
        mThread = new Thread(this, mThreadName);
        mThread.start();
        return EPNanoHTTPD.newFixedLengthResponse(respStatus, mimeType, instream, mContentSize, headers);
      } else {
        // mActiveConn != null, should not be null
        return EPNanoHTTPD.newChunkedResponse(respStatus, mimeType, mActiveConn.getInputStream(), headers);
      }
    } catch (FileNotFoundException e) {
      return EPNanoHTTPD.newFixedLengthResponse(Status.NOT_FOUND, NanoHTTPD.MIME_PLAINTEXT, "Not Found");
    } catch (Exception e) {
      Log.w(TAG, "Internal Error response at start", e);
      return EPNanoHTTPD.newFixedLengthResponse(Status.INTERNAL_ERROR, NanoHTTPD.MIME_PLAINTEXT, "Internal Error");
    }
  }

  protected static long []_getContentRangeData (String rangestr) {
    if (rangestr == null) {
      return null;
    } else {
      Pattern pttrn = Pattern.compile("^\\s*bytes\\s+([0-9]+-[0-9]+/[0-9]+)\\s*$", Pattern.CASE_INSENSITIVE);
      Matcher matcher = pttrn.matcher(rangestr);
      if (matcher.matches()) {
        rangestr = matcher.group(1);
      } else {
        throw new RuntimeException("Content-Range should start with `bytes`, given: " + rangestr);
      }
      String []range_part1 = rangestr.split("/");
      String []range_part2 = range_part1[0].split("-");
      if (range_part2.length != 2 || range_part1.length != 2) {
        throw new RuntimeException("Unexpected content-range data");
      }
      long size = Long.valueOf(range_part1[1]);
      long range_start = Long.valueOf(range_part2[0]);
      long range_end = Long.valueOf(range_part2[1]);
      return new long[] {range_start, range_end, size};
    }
  }

  public static String digestHashStr (String data, long maxsize) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] hash = digest.digest(data.getBytes(StandardCharsets.UTF_8));
      String ret = "";
      maxsize = Math.min(32, maxsize);
      for (int i = 0; i < maxsize; i++) {
        ret += String.format("%02x", hash[i]);
      }
      return ret;
    } catch (NoSuchAlgorithmException e) {
      throw new RuntimeException(e);
    }
  }

}
