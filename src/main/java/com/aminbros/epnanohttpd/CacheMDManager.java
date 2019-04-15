package com.aminbros.epnanohttpd;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;

import com.aminbros.util.Log;
// import android.util.Log;

class CacheMDManager {
  public final static String TAG = "CacheMDManager";
  protected static CacheMDManager sInstance = new CacheMDManager();
  
  protected HashMap <String, CacheMD> mCacheMap;

  public CacheMDManager () {
    mCacheMap = new HashMap();
  }

  public synchronized CacheMD subscribeIfExists (String cachedir, Object obj) {
    CacheMD cachemd = mCacheMap.get(cachedir);
    if (cachemd != null) {
      cachemd.addSubscriber(obj);
      return cachemd; // quick-search
    }
    File fcachedir = new File(cachedir);
    if (!fcachedir.exists()) {
      return null;
    }
    boolean deletecache = false;
    File mdfile = new File(cachedir, "metadata.json");
    try { 
      cachemd = CacheMD.fromJSONObject(new JSONObject(IOUtils.toString(new FileInputStream(mdfile))));
      mCacheMap.put(cachedir, cachemd);
      cachemd.addSubscriber(obj);
      return cachemd;
    } catch (JSONException e) {
      Log.w(TAG, "JSONExc at reading cache metadata.json", e);
      deletecache = true;
    } catch (IOException e) {
      Log.w(TAG, "IOExc at reading cache metadata.json", e);
      deletecache = true;
    } finally {
      if (deletecache) {
        try {
          deleteCacheDirectory(cachedir);
        } catch (IOException e) {
          // pass
        }
      }
    }
    return null;
  }
  
  public synchronized CacheMD subscribeIfNotExists (String cachedir, String etag, long size, Object obj) {
    CacheMD cachemd = mCacheMap.get(cachedir);
    if (cachemd == null) {
      cachemd = new CacheMD(etag, size);
      mCacheMap.put(cachedir, cachemd);
    } else if (!etag.equals(cachemd.getETag()) || size != cachemd.getSize()) {
      // cachemd's params do not match, return null as an indicator for failure
      return null;
    }
    cachemd.addSubscriber(obj);
    return cachemd;
  }

  public synchronized void unsubscribe (CacheMD cachemd, Object obj) {
    cachemd.removeSubscriber(obj);
    if (cachemd.getSubscribersLength() == 0) {
      // cleanup & save
      // find cachedir from map's value
      String cachedir = null;
      for (Map.Entry<String, CacheMD> entry : mCacheMap.entrySet()) {
        if (entry.getValue() == cachemd) {
          cachedir = entry.getKey();
        }
      }
      if (cachedir != null) {
        mCacheMap.remove(cachedir);
        try {
          if (cachemd.deleteonend) {
            deleteCacheDirectory(cachedir);
          } else {
            saveCacheMD(new File(cachedir), cachemd);
          }
        } catch (JSONException e) {
          Log.w(TAG, "JSONExc at writing cache metadata.json", e);
        } catch (IOException e) {
          Log.w(TAG, "IOExc at writing cache metadata.json", e);
        }
      }
    }
  }

  public static boolean saveCacheMD (File cachedir, CacheMD cachemd) {
    try {
      JSONObject jsonobj = cachemd.toJSONObject();
      FileUtils.writeStringToFile(new File(cachedir, "metadata.json"), jsonobj.toString());
      return true;
    } catch (JSONException e) {
      Log.w(TAG, "JSONExc at writing cache metadata.json", e);
    } catch (IOException e) {
      Log.w(TAG, "IOExc at writing cache metadata.json", e);
    }
    return false;
  }

  public void deleteCacheDirectory (String cachedir) throws IOException {
    FileUtils.deleteDirectory(new File(cachedir));
  }
  
  public static CacheMDManager getInstance () {
    if (sInstance == null) {
      sInstance = new CacheMDManager();
    }
    return sInstance;
  }
}

