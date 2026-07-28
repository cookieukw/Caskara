package com.cookie.caskara.utils;

import java.lang.reflect.Method;

public class CaskaraLogger {

    // The Hytale logger is resolved reflectively so Caskara also works in plain JUnit
    // runs. Doing that lookup on every log call was pure overhead, so the outcome
    // (including "not available") is resolved once and cached.
    private static volatile Object cachedLogger;
    private static volatile boolean loggerResolved = false;

    private static Object getLogger() {
        if (!loggerResolved) {
            synchronized (CaskaraLogger.class) {
                if (!loggerResolved) {
                    try {
                        Class<?> loggerClass = Class.forName("com.hypixel.hytale.logger.HytaleLogger");
                        Method method = loggerClass.getMethod("forEnclosingClass");
                        cachedLogger = method.invoke(null);
                    } catch (Throwable t) {
                        cachedLogger = null;
                    }
                    loggerResolved = true;
                }
            }
        }
        return cachedLogger;
    }

    public static void info(String message) {
        Object logger = getLogger();
        if (logger != null) {
            try {
                Method atInfoMethod = logger.getClass().getMethod("atInfo");
                Object fluentLog = atInfoMethod.invoke(logger);
                Method logMethod = fluentLog.getClass().getMethod("log", String.class);
                logMethod.invoke(fluentLog, message);
                return;
            } catch (Throwable ignored) {}
        }
        System.out.println("[Caskara-INFO] " + message);
    }

    public static void warn(String message) {
        Object logger = getLogger();
        if (logger != null) {
            try {
                Method atWarningMethod = logger.getClass().getMethod("atWarning");
                Object fluentLog = atWarningMethod.invoke(logger);
                Method logMethod = fluentLog.getClass().getMethod("log", String.class);
                logMethod.invoke(fluentLog, message);
                return;
            } catch (Throwable ignored) {}
        }
        System.err.println("[Caskara-WARNING] " + message);
    }

    public static void error(String message, Throwable cause) {
        Object logger = getLogger();
        if (logger != null) {
            try {
                Method atErrorMethod = logger.getClass().getMethod("atError");
                Object fluentLog = atErrorMethod.invoke(logger);
                Method logMethod = fluentLog.getClass().getMethod("log", String.class);
                logMethod.invoke(fluentLog, message + (cause != null ? " - Cause: " + cause.getMessage() : ""));
                return;
            } catch (Throwable ignored) {}
        }
        System.err.println("[Caskara-ERROR] " + message);
        if (cause != null) {
            cause.printStackTrace();
        }
    }
}
