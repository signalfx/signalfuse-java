/**
* Copyright (C) 2015 SignalFx, Inc.
*/
package com.signalfx.metrics.aws;

import com.fasterxml.jackson.jr.ob.JSON;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.apache.hc.client5.http.classic.HttpClient;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.HttpClientBuilder;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AWSInstanceInfo {

    public static final String DIMENSION_NAME = "AWSUniqueId";

    private static final String URL = "http://169.254.169.254/latest/dynamic/instance-identity/document";
    private static final Logger log = LoggerFactory.getLogger(AWSInstanceInfo.class);

    private static final String INSTANCE_ID = "instanceId";
    private static final String REGION = "region";
    private static final String ACCOUNT_ID = "accountId";

    /**
     * Attempt to get the value for the AWSUniqueId dimension used in SignalFx AWS integrations.
     *
     * @param timeoutInMs
     *            how long to wait in milliseconds for AWS to respond
     * @return null if the value was not obtained for any reason
     */
    public static String get(int timeoutInMs) {
        RequestConfig requestConfig = RequestConfig.custom().setConnectTimeout(timeoutInMs, TimeUnit.MILLISECONDS).build();
        HttpClient client = HttpClientBuilder.create().setDefaultRequestConfig(requestConfig)
                .build();
        HttpGet request = new HttpGet(URL);

        try {
            ClassicHttpResponse response = client.executeOpen(null, request, null);
            try (InputStream inputStream = response.getEntity().getContent()) {
                return parse(inputStream);
            }
        } catch (Exception e) {
            log.trace("Exception trying to execute {}, Exception: {} ", request, e);
        }

        return null;
    }

    // visible for testing
    static String parse(InputStream inputStream) throws Exception {
        Map<String, Object> result = JSON.std.mapFrom(new InputStreamReader(inputStream, StandardCharsets.UTF_8));
        return result.get(INSTANCE_ID) + "_" + result.get(REGION) + "_" + result.get(ACCOUNT_ID);
    }

    /**
     * Attempt to get the value for the AWSUniqueId dimension used in SignalFx AWS integrations with
     * the default timeout of 1 second
     *
     * @return null if the value was not obtained for any reason
     */
    public static String get() {
        return get(1000);
    }

}
