package com.example.proxy.util;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Logger đơn giản, in ra console kèm timestamp.
 * Trong đồ án thật, bạn có thể thay bằng SLF4J + Logback.
 */
public class AppLogger {

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    private final String tag;

    public AppLogger(String tag) {
        this.tag = tag;
    }

    public static AppLogger of(Class<?> clazz) {
        return new AppLogger(clazz.getSimpleName());
    }

    public void info(String msg) {
        log("INFO", msg);
    }

    public void warn(String msg) {
        log("WARN", msg);
    }

    public void error(String msg) {
        log("ERROR", msg);
    }

    public void error(String msg, Throwable t) {
        log("ERROR", msg + " | " + t.getClass().getSimpleName() + ": " + t.getMessage());
    }

    private void log(String level, String msg) {
        String time = LocalDateTime.now().format(FORMATTER);
        System.out.printf("[%s] [%-5s] [%s] %s%n", time, level, tag, msg);
    }
}
