package com.signalfx.connection;

import com.signalfx.endpoint.SignalFxReceiverEndpoint;
import java.nio.charset.StandardCharsets;

import org.apache.hc.client5.http.HttpRequestRetryStrategy;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.entity.GzipCompressingEntity;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.client5.http.impl.classic.HttpClientBuilder;
import org.apache.hc.client5.http.io.HttpClientConnectionManager;
import org.apache.hc.core5.http.*;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;
import java.util.regex.Pattern;

import static com.signalfx.connection.RetryDefaults.DEFAULT_MAX_RETRIES;
import static com.signalfx.connection.RetryDefaults.DEFAULT_NON_RETRYABLE_EXCEPTIONS;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

public abstract class AbstractHttpReceiverConnection {

    protected static final Logger log = LoggerFactory.getLogger(AbstractHttpReceiverConnection.class);

    // Do not modify this line.  It is auto replaced to a version number.
    public static final String VERSION_NUMBER = "1.0.40";
    public static final String USER_AGENT = "SignalFx-java-client/" + VERSION_NUMBER;
    public static final String DISABLE_COMPRESSION_PROPERTY = "com.signalfx.public.java.disableHttpCompression";

    protected static final ContentType JSON_TYPE = ContentType.APPLICATION_JSON;

    protected final CloseableHttpClient client;
    protected final HttpHost host;
    protected final RequestConfig requestConfig;

    protected AbstractHttpReceiverConnection(SignalFxReceiverEndpoint endpoint, int timeoutMs,
                                             HttpClientConnectionManager httpClientConnectionManager) {
        this(endpoint, timeoutMs, DEFAULT_MAX_RETRIES, httpClientConnectionManager);
    }

    protected AbstractHttpReceiverConnection(SignalFxReceiverEndpoint endpoint, int timeoutMs, int maxRetries,
                                             HttpClientConnectionManager httpClientConnectionManager) {
        this(endpoint, timeoutMs, DEFAULT_MAX_RETRIES, httpClientConnectionManager, DEFAULT_NON_RETRYABLE_EXCEPTIONS);
    }

    protected AbstractHttpReceiverConnection(SignalFxReceiverEndpoint endpoint, int timeoutMs, int maxRetries,
                                             HttpClientConnectionManager httpClientConnectionManager, List<Class<? extends IOException>> nonRetryableExceptions) {
        HttpRequestRetryStrategy retryStrategy = new RetryHandler(maxRetries, nonRetryableExceptions);
        this.client = HttpClientBuilder.create()
                .setConnectionManager(httpClientConnectionManager)
                .setRetryStrategy(retryStrategy)
                .build();
        this.host = new HttpHost(endpoint.getScheme(), endpoint.getHostname(), endpoint.getPort());

        HttpHost proxy = createHttpProxyFromSystemProperties(endpoint.getHostname());
        this.requestConfig = RequestConfig.custom()
                .setResponseTimeout(timeoutMs, MILLISECONDS)
                .setConnectionRequestTimeout(timeoutMs, MILLISECONDS)
                .setConnectTimeout(timeoutMs, MILLISECONDS)
                .setProxy(proxy)
                .build();
    }

    protected CloseableHttpResponse postToEndpoint(String auth, HttpEntity entity, String endpoint,
                                                   boolean compress)
            throws IOException {
        if (compress) {
            entity = new GzipCompressingEntity(entity);
        }

        HttpPost post = new HttpPost(String.format("%s%s", host.toURI(), endpoint));
        post.setConfig(requestConfig);
        if (auth != null) {
            post.setHeader("X-SF-TOKEN", auth);
        }
        post.setHeader("User-Agent", USER_AGENT);
        post.setEntity(entity);

        try {
            log.trace("Talking to endpoint {}", post);
            return client.execute(post);
        } catch (IOException e) {
            log.trace("Exception trying to execute {}", post, e);
            throw e;
        }
    }

    protected void checkHttpResponse(CloseableHttpResponse resp) {
        final String body;
        try {
            body = EntityUtils.toString(resp.getEntity(), StandardCharsets.UTF_8);
        } catch (IOException | ParseException e) {
            throw new RuntimeException("Unable to get response content", e);
        }
        if (resp.getCode() != HttpStatus.SC_OK) {
            throw new RuntimeException("Invalid status code "
                    + resp.getCode() + ": " + body);
        }
        if (!"\"OK\"".equals(body)) {
            throw new RuntimeException("Invalid response body: " + body);
        }
    }

    /**
     * method to create a httphost object based on java network proxy system properties
     *
     * http.proxyHost: the host name of the proxy server
     * http.proxyPort: the port number, the default value being 80
     * http.nonProxyHosts: a list of hosts that should be reached directly, bypassing the proxy.
     *                     This is a list of patterns separated by '|'.
     *                     The patterns may start or end with a '*' for wildcards.
     *                     Any host matching one of these patterns will be reached through a
     *                     direct connection instead of through a proxy.
     *
     * @param endpointHostname  the signalfx endpoint hostname
     *
     * @return an instance of HttpHost based on the java system properties
     *         unless the http proxy host is not configured
     *         OR if the nonProxyHosts rules include this endpoint
     *         then null will be returned instead
     **/
    protected HttpHost createHttpProxyFromSystemProperties(String endpointHostname) {

        String proxyHost = System.getProperty("http.proxyHost");
        if ((proxyHost != null) && (proxyHost.trim().length() > 0)) {

            String nonProxyHosts = System.getProperty("http.nonProxyHosts");
            if (nonProxyHosts != null) {

                // set host strings as regular expressions based on
                // nonProxyHosts rules
                nonProxyHosts = nonProxyHosts.replaceAll("\\.", "\\\\.").replaceAll("\\*", ".*?");

                // set groups and alternations
                nonProxyHosts = "(" + nonProxyHosts.replaceAll("\\|", ")|(") + ")";

                final Pattern pattern = Pattern.compile(nonProxyHosts);
                if (pattern.matcher(endpointHostname).find()) {
                    // http proxy is not configured for this endpoint
                    return null;
                }
            }

            String proxyPort = System.getProperty("http.proxyPort");
            if ((proxyPort == null) || (proxyPort.trim().length() == 0)) {
                // port 80 is the default in java networking/proxy documentation
                proxyPort = "80";
            }

            // return http proxy host
            return new HttpHost("http", proxyHost.trim(), Integer.parseInt(proxyPort.trim()));
        }

        // http proxy is not configured
        return null;
    }


}
