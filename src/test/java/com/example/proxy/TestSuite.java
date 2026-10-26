package com.example.proxy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/** Dependency-free test launcher. Every *Test.java must provide a main; each gets its own JVM. */
public final class TestSuite {
    public static void main(String[] args) throws Exception {
        Path sources = Path.of("src/test/java"), reports = Path.of("target/test-reports");
        Files.createDirectories(reports);
        int failures = 0, count = 0;
        try (var files = Files.walk(sources)) {
            for (Path source : files.filter(p -> p.toString().endsWith("Test.java")).sorted().toList()) {
                String name = sources.relativize(source).toString().replace('\\', '.').replace('/', '.').replaceFirst("\\.java$", "");
                Path output = reports.resolve(name + ".log");
                long start = System.nanoTime();
                Process child = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                        "-ea", "-cp", System.getProperty("java.class.path"), name)
                        .redirectErrorStream(true).redirectOutput(output.toFile()).start();
                boolean finished;
                try { finished = child.waitFor(60, TimeUnit.SECONDS); }
                finally {
                    if (child.isAlive()) { child.destroyForcibly(); child.waitFor(5, TimeUnit.SECONDS); }
                }
                boolean passed = finished && child.exitValue() == 0;
                double seconds = (System.nanoTime() - start) / 1_000_000_000.0;
                // Report one test per executable suite; scenario assertions remain in its captured log.
                String failure = passed ? "" : "<failure message=\"" + (finished ? "Nonzero exit; see sibling log" : "Timeout after 60 seconds") + "\"/>";
                Files.writeString(reports.resolve("TEST-" + name + ".xml"),
                        "<?xml version=\"1.0\" encoding=\"UTF-8\"?><testsuite name=\"" + name
                        + "\" tests=\"1\" failures=\"" + (passed ? 0 : 1) + "\" errors=\"0\" skipped=\"0\" time=\"" + seconds
                        + "\"><testcase classname=\"" + name + "\" name=\"main\" time=\"" + seconds + "\">" + failure + "</testcase></testsuite>");
                System.out.println((passed ? "PASS " : "FAIL ") + name + " (" + output + ")");
                if (!passed) {
                    failures++;
                    String log = Files.readString(output);
                    System.err.println(log);
                    if ("true".equals(System.getenv("GITHUB_ACTIONS"))) {
                        // Keep the suite name and stack visible in the public check annotations,
                        // even when downloading the full Actions log requires extra permissions.
                        String detail = name + "\n" + log.substring(Math.max(0, log.length() - 20000));
                        System.out.println("::error title=" + name + "::" + detail.replace("%", "%25")
                                .replace("\r", "%0D").replace("\n", "%0A"));
                    }
                }
                count++;
            }
        }
        if (count == 0 || failures > 0) throw new AssertionError("Test mains=" + count + ", failures=" + failures);
        System.out.println("Passed " + count + " test mains; XML reports: " + reports);
    }
}
