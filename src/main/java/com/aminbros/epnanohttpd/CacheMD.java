package com.aminbros.epnanohttpd;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONException;

import java.util.Iterator;
import java.util.Map;
import java.util.List;
import java.util.HashMap;
import java.util.ArrayList;

public class CacheMD {
  protected String mEtag;
  protected long mSize;
  protected ArrayList <Chunk>mChunks; // read-only
  protected ArrayList <Object>mSubscribers; // read-only, not saved
  public boolean mustRevalidate;
  public long lastUpdate;
  public Map<String,List<String>> headers;
  public boolean deleteonend;
  /**
   * size should be positive
   */
  public CacheMD (String etag, long size) {
    mEtag = etag;
    mSize = size;
    mChunks = new ArrayList();
    mSubscribers = new ArrayList();
    deleteonend = false;
    lastUpdate = 0;
    mustRevalidate = false;
    headers = null;
  }
  public String getETag () {
    return mEtag;
  }
  public long getSize () {
    return mSize;
  }
  public synchronized int getSubscribersLength () {
    return mSubscribers.size();
  }
  public synchronized void addSubscriber (Object obj) {
    if (mSubscribers.indexOf(obj) == -1) {
      mSubscribers.add(obj);
    }
  }
  public synchronized void removeSubscriber (Object obj) {
    int idx = mSubscribers.indexOf(obj);
    if (idx != -1) {
      mSubscribers.remove(idx);
    }
  }
  public synchronized Chunk getOptimalChunkForRange (long start, long end) {
    long chunklen = mChunks.size();
    Chunk bestchunk = null;
    for (int i = 0; i < chunklen; i++) {
      Chunk achunk = mChunks.get(i);
      if (achunk.start <= start) {
        if (achunk.end >= start) {
          if (bestchunk == null) {
            bestchunk = achunk;
          } else if (bestchunk.end < achunk.end) {
            bestchunk = achunk;
          }
        }
      } else {
        if (bestchunk == null) {
          bestchunk = achunk;
        }
        break;
      }
    }
    return bestchunk;
  }
  public synchronized List<Chunk> getChunks () {
    return new ArrayList(mChunks);
  }
  // UNUSED
  public synchronized Chunk getFirstChunkInRange (long start, long end) {
    long chunklen = mChunks.size();
    for (int i = 0; i < chunklen; i++) {
      Chunk achunk = mChunks.get(i);
      if ((achunk.start >= start && achunk.start < end) ||
          (achunk.start < start && achunk.end >= start)) {
        return achunk;
      }
    }
    return null;
  }
  // UNUSED
  public synchronized List<Chunk> getChunksInRange (long start, long end) {
    List<Chunk> ret = new ArrayList();
    long chunklen = mChunks.size();
    for (int i = 0; i < chunklen; i++) {
      Chunk achunk = mChunks.get(i);
      if ((achunk.start >= start && achunk.start < end) ||
          (achunk.start < start && achunk.end >= start)) {
        ret.add(achunk);
      }
    }
    return ret;
  }
  public synchronized Chunk getChunkAt (long start) {
    long chunklen = mChunks.size();
    for (int i = 0; i < chunklen; i++) {
      Chunk achunk = mChunks.get(i);
      if (achunk.start == start) {
        return achunk;
      }
    }
    return null;
  }
  public synchronized void removeChunk (Chunk chunk) {
    long chunklen = mChunks.size();
    for (int i = 0; i < chunklen; i++) {
      Chunk achunk = mChunks.get(i);
      if (achunk == chunk) {
        mChunks.remove(i);
        return;
      }
    }
  }
  public synchronized void addChunk (Chunk chunk) {
    // add sorted by chunk.start
    long chunklen = mChunks.size();
    for (int i = 0; i < chunklen; i++) {
      Chunk achunk = mChunks.get(i);
      if (achunk.start > chunk.start) {
        mChunks.add(i, chunk);
        return;
      }
    }
    mChunks.add(chunk);
  }
  public JSONObject toJSONObject () {
    JSONObject obj = new JSONObject();
    obj.put("etag", mEtag);
    obj.put("size", mSize);
    obj.put("mustrevalidate", mustRevalidate);
    obj.put("lastupdate", lastUpdate);
    JSONArray chunksArr = new JSONArray();
    for (Chunk chunk : mChunks) {
      if (chunk.active == false) {
        chunksArr.put(chunk.toJSONObject());
      }
    }
    obj.put("chunks", chunksArr);
    if (headers != null) {
      JSONObject headersobj = new JSONObject();
      for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
        JSONArray arr = new JSONArray();
        for (String value : entry.getValue()) {
          arr.put(value);
        }
        headersobj.put(entry.getKey(), arr);
      }
      obj.put("headers", headersobj);
    }
    return obj;
  }
  public static CacheMD fromJSONObject (JSONObject obj) {
    CacheMD cachemd = new CacheMD(obj.getString("etag"), obj.getLong("size"));
    cachemd.mustRevalidate = obj.getBoolean("mustrevalidate");
    cachemd.lastUpdate = obj.getLong("lastupdate");
    JSONArray chunksArr = obj.getJSONArray("chunks");
    for(int i = 0; i < chunksArr.length(); i++){
      cachemd.addChunk(Chunk.fromJSONObject(chunksArr.getJSONObject(i)));
    }
    if (obj.has("headers")) {
      JSONObject headersobj = obj.getJSONObject("headers");
      cachemd.headers = new HashMap();
      Iterator <String>iter = headersobj.keys();
      while (iter.hasNext()) {
        String key = iter.next();
        ArrayList <String>headerlist = new ArrayList();
        JSONArray arr = headersobj.getJSONArray(key);
        for (int i = 0; i < arr.length(); i++) {
          headerlist.add(arr.getString(i));
        }
        cachemd.headers.put(key, headerlist);
      }
    }
    return cachemd;
  }
  public static class Chunk {
    public long start;
    public long end; // last byte included
    public String filename;
    public boolean active; // not saved
    public Chunk () {
      start = 0;
      end = 0;
      filename = null;
      active = false;
    }
    public JSONObject toJSONObject () {
      JSONObject obj = new JSONObject();
      obj.put("start", start);
      obj.put("end", end);
      obj.put("filename", filename);
      return obj;
    }
    public static Chunk fromJSONObject (JSONObject obj) {
      Chunk chunk = new Chunk();
      chunk.start = obj.getLong("start");
      chunk.end = obj.getLong("end");
      chunk.filename = obj.getString("filename");
      return chunk;
    }
  }
}
