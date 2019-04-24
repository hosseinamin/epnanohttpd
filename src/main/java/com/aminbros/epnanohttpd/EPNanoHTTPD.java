package com.aminbros.epnanohttpd;
    
import java.io.IOException;
import java.lang.RuntimeException;

import java.io.File;

import java.net.URLEncoder;
import java.net.URL;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.io.UnsupportedEncodingException;

import java.util.Map;

import fi.iki.elonen.NanoHTTPD;
import fi.iki.elonen.NanoHTTPD.Response;
import fi.iki.elonen.NanoHTTPD.Response.Status;
import fi.iki.elonen.NanoHTTPD.Response.IStatus;

import com.aminbros.util.Log;
// import android.util.Log;

/**
 * EPNanoHTTPD, Is an attempt to create a http proxy server to reduce
 * network usage by using http cache ETag cache capability for 
 * Ranged/non-ranged requests.
 */
public class EPNanoHTTPD extends NanoHTTPD {
  public final static String TAG = "EPNanoHTTPD";
  
  private File mCacheDir;
  private String mProxyUrl;
  
  public EPNanoHTTPD (String hostname, int port,
                      File cachedir, String proxyurl) throws IOException {
    super(hostname, port);
    mCacheDir = cachedir;
    mProxyUrl = proxyurl;
    if (mCacheDir == null || (mCacheDir.exists() && !mCacheDir.isDirectory())) {
      throw new IOException("cachedir is not a directory or is null");
    }
    if (!mCacheDir.exists()) {
      if (!mCacheDir.mkdirs()) {
        throw new IOException("Could not create cachedir directory at " + mCacheDir.getAbsolutePath());
      }
    }
    // strip slash at end
    if (mProxyUrl != null && mProxyUrl.endsWith("/")) {
      mProxyUrl = mProxyUrl.substring(0, mProxyUrl.length()-1);
    }
    // start(NanoHTTPD.SOCKET_READ_TIMEOUT, true);
    // System.out.println("\nRunning! Point your browsers to http://localhost:8080/ \n");
  }
   
  @Override
  public Response serve (IHTTPSession session) {
    try {
      String path = session.getUri();
      Map<String, String> headers = session.getHeaders();
      Map<String, String> parms = session.getParms();
      String method = session.getMethod().name();
      // prefix with slash, if not exists
      if (!path.startsWith("/")) {
        path = "/" + path;
      }
      if (path.equals("/")) {
        path = ""; // special case, no change to mProxyUrl
      }
      // remove, unwanted headers
      headers.remove("host");
      headers.remove("http-client-ip");
      headers.remove("remote-addr");
      headers.remove("connection");
      headers.remove("accept-encoding");
      String explicit_url = parms.get("__u__");
      URL url;
      if (path.equals("") && explicit_url != null) {
        url = new URL(explicit_url);
      } else {
        if (mProxyUrl == null) {
          return NanoHTTPD.newFixedLengthResponse(Status.NOT_FOUND, NanoHTTPD.MIME_PLAINTEXT, "Not Found!");
        }
        String query = encodeParms(parms);
        url = new URL(mProxyUrl + path + (query.length() == 0 ? "" : "?" + query));
      }
      InputStream instream = session.getInputStream();
      EPProxyThread thread = new EPProxyThread(method, url, headers, instream, mCacheDir);
      return thread.start();
    } catch (RuntimeException e) {
      Log.w(TAG, "RuntimeException at serve", e);
      return NanoHTTPD.newFixedLengthResponse(Status.INTERNAL_ERROR, NanoHTTPD.MIME_PLAINTEXT, "Proxy Internal Error");
    } catch (MalformedURLException e) {
      Log.w(TAG, "MalformedURLExc at serve", e);
      return NanoHTTPD.newFixedLengthResponse(Status.INTERNAL_ERROR, NanoHTTPD.MIME_PLAINTEXT, "Proxy Internal Error");
    } catch (UnsupportedEncodingException e) {
      Log.w(TAG, "UnsupportedEncodingExc at serve", e); 
      return NanoHTTPD.newFixedLengthResponse(Status.INTERNAL_ERROR, NanoHTTPD.MIME_PLAINTEXT, "Proxy Internal Error");
    }
  }
  
  protected String encodeParms (Map<String, String> parms) throws UnsupportedEncodingException {
    String ret = "";
    for (Map.Entry<String, String> entry : parms.entrySet()) {
      ret += URLEncoder.encode(entry.getKey(), "utf-8") + "=" +
        URLEncoder.encode(entry.getValue(), "utf-8") + "&";
    }
    if (ret.length() > 0) {
      ret = ret.substring(0, ret.length() - 1);
    }
    return ret;
  }

  
  public static Response newChunkedResponse(IStatus status, String mimeType, InputStream data, Map<String, String> headers) {
    Response resp = newChunkedResponse(status, mimeType, data);
    addHeadersToResponse(resp, headers);
    return resp;
  }

  public static Response newFixedLengthResponse(IStatus status, String mimeType, InputStream data, long totalBytes, Map<String, String> headers) {
    Response resp = newFixedLengthResponse(status, mimeType, data, totalBytes);
    addHeadersToResponse(resp, headers);
    return resp;
  }

  public static Response newFixedLengthResponse(IStatus status, String mimeType, String txt, Map<String, String> headers) {
    Response resp = newFixedLengthResponse(status, mimeType, txt);
    addHeadersToResponse(resp, headers);
    return resp;
  }
  
  public static Response newFixedLengthResponse(String msg, Map<String, String> headers) {
    Response resp = newFixedLengthResponse(msg);
    addHeadersToResponse(resp, headers);
    return resp;
  }

  public static void addHeadersToResponse (Response resp, Map<String, String> headers) {
    for (Map.Entry<String, String> entry : headers.entrySet()) {
      if (entry.getKey() != null) {
        resp.addHeader(entry.getKey(), entry.getValue());
      }
    }
  }

  public static IStatus getIStatusFromCode (int code) {
    IStatus respStatus = null;
    switch (code) {
    case 400: respStatus = Status.BAD_REQUEST; break;
    case 401: respStatus = Status.UNAUTHORIZED; break;
    case 403: respStatus = Status.FORBIDDEN; break;
    case 404: respStatus = Status.NOT_FOUND; break;
    case 405: respStatus = Status.METHOD_NOT_ALLOWED; break;
    case 406: respStatus = Status.NOT_ACCEPTABLE; break;
    case 408: respStatus = Status.REQUEST_TIMEOUT; break;
    case 409: respStatus = Status.CONFLICT; break;
    case 410: respStatus = Status.GONE; break;
    case 411: respStatus = Status.LENGTH_REQUIRED; break;
    case 412: respStatus = Status.PRECONDITION_FAILED; break;
    case 413: respStatus = Status.PAYLOAD_TOO_LARGE; break;
    case 415: respStatus = Status.UNSUPPORTED_MEDIA_TYPE; break;
    case 416: respStatus = Status.RANGE_NOT_SATISFIABLE; break;
    case 417: respStatus = Status.EXPECTATION_FAILED; break;
    case 429: respStatus = Status.TOO_MANY_REQUESTS; break;
    case 500: respStatus = Status.INTERNAL_ERROR; break;
    case 501: respStatus = Status.NOT_IMPLEMENTED; break;
    case 503: respStatus = Status.SERVICE_UNAVAILABLE; break;
    case 505: respStatus = Status.UNSUPPORTED_HTTP_VERSION; break;
    case 101: respStatus = Status.SWITCH_PROTOCOL; break;
    case 200: respStatus = Status.OK; break;
    case 201: respStatus = Status.CREATED; break;
    case 202: respStatus = Status.ACCEPTED; break;
    case 204: respStatus = Status.NO_CONTENT; break;
    case 206: respStatus = Status.PARTIAL_CONTENT; break;
    case 207: respStatus = Status.MULTI_STATUS; break;
    case 301: respStatus = Status.REDIRECT; break;
    }
    return respStatus;
  }

}
