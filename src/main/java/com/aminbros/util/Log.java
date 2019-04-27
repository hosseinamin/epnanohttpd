package com.aminbros.util;

import java.io.PrintStream;

import java.lang.reflect.Method;

/*
 * for all methods try to use android Log first
 */
public class Log {
  public static void d (String tag, String message) {
    try {
      Class<?> lcls = Class.forName("android.util.Log");
      Method m = lcls.getMethod("d", String.class, String.class);
      m.invoke(lcls, tag, message);
    } catch (Throwable e) {
      System.out.println(tag + "[Dbg]: " + message);
    }
  }
  public static void d (String tag, String message, Exception ex) {
    try {
      Class<?> lcls = Class.forName("android.util.Log");
      Method m = lcls.getMethod("d", String.class, String.class, Throwable.class);
      m.invoke(lcls, tag, message, ex);
    } catch (Throwable e) {
      System.out.println(tag + "[Dbg]: " + message);
      ex.printStackTrace();
    }
  }
  public static void w (String tag, String message) {
    try {
      Class<?> lcls = Class.forName("android.util.Log");
      Method m = lcls.getMethod("w", String.class, String.class);
      m.invoke(lcls, tag, message);
    } catch (Throwable e) {
      System.out.println(tag + "[Warn]: " + message);
    }
  }
  public static void w (String tag, String message, Exception ex) {
    try {
      Class<?> lcls = Class.forName("android.util.Log");
      Method m = lcls.getMethod("w", String.class, String.class, Throwable.class);
      m.invoke(lcls, tag, message, ex);
    } catch (Throwable e) {
      System.out.println(tag + "[Warn]: " + message);
      ex.printStackTrace();
    }
  }
  public static void e (String tag, String message) {
    try {
      Class<?> lcls = Class.forName("android.util.Log");
      Method m = lcls.getMethod("e", String.class, String.class);
      m.invoke(lcls, tag, message);
    } catch (Throwable e) {
      System.out.println(tag + "[Error]: " + message);
    }
  }
  public static void e (String tag, String message, Exception ex) {
    try {
      Class<?> lcls = Class.forName("android.util.Log");
      Method m = lcls.getMethod("e", String.class, String.class, Throwable.class);
      m.invoke(lcls, tag, message, ex);
    } catch (Throwable e) {
      System.out.println(tag + "[Error]: " + message);
      ex.printStackTrace();
    }
  }
}
