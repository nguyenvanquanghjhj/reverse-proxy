package com.example.proxy.handler;

import java.io.EOFException;
import java.io.IOException;
import java.net.ProtocolException;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/** Handler-level outcome counters and their existing JSON/Prometheus contract. Owns no request resources. */
final class ProxyRequestMetrics {
    private final LongAdder requests = new LongAdder(), failed = new LongAdder(), rejected = new LongAdder();
    private final LongAdder timeouts = new LongAdder(), upstreamErrors = new LongAdder();
    private final LongAdder successful = new LongAdder(), connectionErrors = new LongAdder(), responseErrors = new LongAdder();
    private final LongAdder limitRejections = new LongAdder(), chunkMetadataRejections = new LongAdder();

    void requestStarted() { requests.increment(); }
    void requestCompleted(int status, boolean exception, boolean backendSelected) {
        if (status < 0 || status >= 400 || exception) failed.increment();
        else if (status >= 200 && status < 300) successful.increment();
        if (status >= 400 && !backendSelected) rejected.increment();
    }
    void upstreamResponseError() { responseErrors.increment(); upstreamErrors.increment(); }
    void upstreamLimitRejected(boolean chunkMetadata) {
        upstreamErrors.increment(); responseErrors.increment();
        limitRejections.increment();
        if (chunkMetadata) chunkMetadataRejections.increment();
    }
    void upstreamTimeout() { timeouts.increment(); upstreamErrors.increment(); }
    void upstreamFailure(IOException failure) {
        upstreamErrors.increment();
        if (failure instanceof ProtocolException || failure instanceof EOFException) responseErrors.increment();
        else connectionErrors.increment();
    }
    // Suppliers keep owner/quota sampling at the same position as the original exports.
    String countersJson(IntSupplier activeHandlers, Supplier<String> rateLimitJson) {
        return "\"requests_total\":" + requests.sum() + ",\"requests_failed\":" + failed.sum()
                + ",\"requests_rejected\":" + rejected.sum() + ",\"upstream_timeouts\":" + timeouts.sum()
                + ",\"requests_successful\":" + successful.sum()
                + ",\"upstream_connection_errors\":" + connectionErrors.sum() + ",\"upstream_response_errors\":" + responseErrors.sum()
                + ",\"upstream_limit_rejections\":" + limitRejections.sum() + ",\"upstream_chunk_metadata_rejections\":" + chunkMetadataRejections.sum()
                + ",\"upstream_errors\":" + upstreamErrors.sum() + ",\"in_flight\":" + activeHandlers.getAsInt() + "," + rateLimitJson.get();
    }
    String prometheus(IntSupplier activeHandlers, Supplier<String> rateLimitPrometheus) {
        return metric("requests_total", requests.sum(), "counter") + metric("requests_failed_total", failed.sum(), "counter")
                + metric("requests_rejected_total", rejected.sum(), "counter") + metric("upstream_timeouts_total", timeouts.sum(), "counter")
                + metric("requests_successful_total", successful.sum(), "counter")
                + metric("upstream_connection_errors_total", connectionErrors.sum(), "counter")
                + metric("upstream_response_errors_total", responseErrors.sum(), "counter")
                + metric("upstream_limit_rejections_total", limitRejections.sum(), "counter")
                + metric("upstream_chunk_metadata_rejections_total", chunkMetadataRejections.sum(), "counter")
                + metric("upstream_errors_total", upstreamErrors.sum(), "counter") + metric("in_flight", activeHandlers.getAsInt(), "gauge") + rateLimitPrometheus.get();
    }
    private static String metric(String name, long value, String type) {
        return "# TYPE proxy_" + name + " " + type + "\nproxy_" + name + " " + value + "\n";
    }
}
