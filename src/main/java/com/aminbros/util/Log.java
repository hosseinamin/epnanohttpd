package com.aminbros.util;

import java.io.PrintStream;

public class Log {
  public static void w (String tag, String message) {
    System.out.println(tag + "[Warn]: " + message);
  }
  public static void w (String tag, String message, Exception ex) {
    System.out.println(tag + "[Warn]: " + message);
    ex.printStackTrace();
  }
  public static void e (String tag, String message) {
    System.out.println(tag + "[Error]: " + message);
  }
  public static void e (String tag, String message, Exception ex) {
    System.out.println(tag + "[Error]: " + message);
    ex.printStackTrace();
  }
}
