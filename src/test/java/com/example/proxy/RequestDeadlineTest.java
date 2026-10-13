package com.example.proxy;

import com.example.proxy.backend.BackendServer;
import com.example.proxy.util.Json;
import com.example.proxy.util.RequestDeadline;
import java.util.concurrent.*;

public final class RequestDeadlineTest {
    public static void main(String[] args) throws Exception {
        long deadline = RequestDeadline.afterMillis(1000);
        RequestDeadline.run(deadline, () -> {
            check(RequestDeadline.currentOrNew(5000) == deadline, "no budget reset");
            try {
                RequestDeadline.run(deadline - 10, () -> { throw new IllegalStateException("test"); });
            } catch (IllegalStateException expected) { }
            check(RequestDeadline.currentOrNew(100) == deadline, "nested failure restores budget");
        });
        check(RequestDeadline.currentOrNew(1) != deadline, "thread-local removed");
        try (var tasks = Executors.newVirtualThreadPerTaskExecutor()) {
            check(tasks.submit(() -> RequestDeadline.currentOrNew(1) != deadline).get(1,TimeUnit.SECONDS), "no cross-request budget");
        }
        check(Json.quote("\"\\\n\r\t\u0000").equals("\"\\\"\\\\\\n\\r\\t\\u0000\""), "JSON control characters");
        check(Json.quote("Tiếng Việt 😀").equals("\"Tiếng Việt \\ud83d\\ude00\""), "Unicode JSON");
        for (String host : new String[]{"bad\"name", "bad\\name", "bad\nname"}) {
            try { new BackendServer(host,9001,1); throw new AssertionError("unsafe backend name accepted"); }
            catch (IllegalArgumentException expected) { }
        }
        System.out.println("RequestDeadlineTest PASS: scope, exceptions, isolation, JSON, backend names");
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
