package com.signalfx.metrics.connection;

import com.signalfx.endpoint.SignalFxReceiverEndpoint;
import com.signalfx.metrics.SignalFxMetricsException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.apache.hc.client5.http.io.HttpClientConnectionManager;

import static com.signalfx.connection.RetryDefaults.DEFAULT_MAX_RETRIES;
import static com.signalfx.connection.RetryDefaults.DEFAULT_NON_RETRYABLE_EXCEPTIONS;

public class HttpDataPointProtobufReceiverFactory implements DataPointReceiverFactory {
    public static final int DEFAULT_TIMEOUT_MS = 2000;

    private final SignalFxReceiverEndpoint endpoint;
    private HttpClientConnectionManager httpClientConnectionManager;
    private HttpClientConnectionManager explicitHttpClientConnectionManager;
    private int timeoutMs = DEFAULT_TIMEOUT_MS;
    private int maxRetries = DEFAULT_MAX_RETRIES;
    private List<Class<? extends IOException>> nonRetryableExceptions = DEFAULT_NON_RETRYABLE_EXCEPTIONS;

    public HttpDataPointProtobufReceiverFactory(SignalFxReceiverEndpoint endpoint) {
        this.endpoint = endpoint;
        this.httpClientConnectionManager =
            HttpClientConnectionManagerFactory.withTimeoutMs(DEFAULT_TIMEOUT_MS);
        this.explicitHttpClientConnectionManager = null;
    }

    public HttpDataPointProtobufReceiverFactory setTimeoutMs(int timeoutMs) {
        this.timeoutMs = timeoutMs;
        this.httpClientConnectionManager =
            HttpClientConnectionManagerFactory.withTimeoutMs(timeoutMs);
        return this;
    }

    @Deprecated
    public HttpDataPointProtobufReceiverFactory setVersion(int version) {
        return this;
    }

    public HttpDataPointProtobufReceiverFactory setMaxRetries(int maxRetries) {
        this.maxRetries = maxRetries;
        return this;
    }

    public HttpDataPointProtobufReceiverFactory setNonRetryableExceptions(List<Class<? extends IOException>> clazzes) {
        this.nonRetryableExceptions = Collections.unmodifiableList(new ArrayList<>(clazzes));
        return this;
    }

    public void setHttpClientConnectionManager(
            HttpClientConnectionManager httpClientConnectionManager) {
        this.explicitHttpClientConnectionManager = httpClientConnectionManager;
    }

    @Override
    public DataPointReceiver createDataPointReceiver() throws
            SignalFxMetricsException {
        return new HttpDataPointProtobufReceiverConnectionV2(
                endpoint,
                this.timeoutMs,
                this.maxRetries,
                resolveHttpClientConnectionManager(),
                this.nonRetryableExceptions);
    }

    private HttpClientConnectionManager resolveHttpClientConnectionManager() {
        if (explicitHttpClientConnectionManager != null) {
            return explicitHttpClientConnectionManager;
        }
        if (httpClientConnectionManager != null) {
            return httpClientConnectionManager;
        }
        throw new IllegalStateException("Both explicitHttpClientConnectionManager and httpClientConnectionManager are null");
    }
}
