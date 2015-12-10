package com.signalfx.codahale.reporter;

import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.concurrent.TimeUnit;

import com.yammer.metrics.core.Counter;
import com.yammer.metrics.core.Gauge;
import com.yammer.metrics.core.Histogram;
import com.yammer.metrics.core.Meter;
import com.yammer.metrics.core.MetricPredicate;
import com.yammer.metrics.core.MetricsRegistry;
import com.yammer.metrics.core.Timer;
import com.yammer.metrics.core.MetricName;
import com.google.common.collect.ImmutableSet;
import com.signalfx.endpoint.SignalFxEndpoint;
import com.signalfx.endpoint.SignalFxReceiverEndpoint;
import com.signalfx.metrics.aws.AWSInstanceInfo;
import com.signalfx.metrics.SourceNameHelper;
import com.signalfx.metrics.auth.AuthToken;
import com.signalfx.metrics.auth.StaticAuthToken;
import com.signalfx.metrics.connection.DataPointReceiverFactory;
import com.signalfx.metrics.connection.HttpDataPointProtobufReceiverFactory;
import com.signalfx.metrics.endpoint.DataPointReceiverEndpoint;
import com.signalfx.metrics.errorhandler.OnSendErrorHandler;
import com.signalfx.metrics.flush.AggregateMetricSender;
import com.signalfx.metrics.protobuf.SignalFxProtocolBuffers;
import com.signalfx.codahale.reporter.CustomScheduledReporter;

/**
 * Reporter object for codahale metrics that reports values to com.signalfx.signalfx at some
 * interval.
 */
public class SignalFxReporter extends CustomScheduledReporter {
    private final AggregateMetricSender aggregateMetricSender;
    private final Set<MetricDetails> detailsToAdd;
    private final MetricMetadata metricMetadata;
    private final boolean useLocalTime;
    private final boolean enableAwsUniqueId;
    private boolean hasAwsInstanceInfo;
    private AWSInstanceInfo awsInstanceInfo;

    protected SignalFxReporter(MetricsRegistry registry, String name, MetricPredicate filter,
                                 TimeUnit rateUnit, TimeUnit durationUnit,
                                 AggregateMetricSender aggregateMetricSender,
                                 Set<MetricDetails> detailsToAdd,
                                 MetricMetadata metricMetadata) {
        this(registry, name, filter, rateUnit, durationUnit, aggregateMetricSender, detailsToAdd, metricMetadata, false, false);
    }

    public SignalFxReporter(MetricsRegistry registry, String name, MetricPredicate filter,
                              TimeUnit rateUnit, TimeUnit durationUnit,
                              AggregateMetricSender aggregateMetricSender,
                              Set<MetricDetails> detailsToAdd, MetricMetadata metricMetadata,
                              boolean useLocalTime, boolean enableAwsUniqueId) {
        super(registry, name, filter, rateUnit, durationUnit);
        this.aggregateMetricSender = aggregateMetricSender;
        this.useLocalTime = useLocalTime;
        this.detailsToAdd = detailsToAdd;
        this.metricMetadata = metricMetadata;
        this.enableAwsUniqueId = enableAwsUniqueId;
    }

    /**
     * 
     * Reports all given metrics here
     * 
     */
    
    @Override
    public void report(	SortedMap<MetricName, Gauge> gauges, 
    					SortedMap<MetricName, Counter> counters,
    					SortedMap<MetricName, Histogram> histograms, 
    					SortedMap<MetricName, Meter> meters,
    					SortedMap<MetricName, Timer> timers) {
    	
        // Obtain AWS unique id
    	// we need hasAwsInstanceInfo because awsInstanceInfo can be null after obtain call()
    	if(enableAwsUniqueId && !hasAwsInstanceInfo){
    		awsInstanceInfo = AWSInstanceInfo.obtain();
    		hasAwsInstanceInfo = true;
    	}
    	
        AggregateMetricSenderSessionWrapper session = new AggregateMetricSenderSessionWrapper(
                aggregateMetricSender.createSession(), Collections.unmodifiableSet(detailsToAdd), metricMetadata,
                aggregateMetricSender.getDefaultSourceName(), "sf_source", useLocalTime, awsInstanceInfo);
        try {
            for (Map.Entry<MetricName, Gauge> entry : gauges.entrySet()) {
                session.addMetric(entry.getValue(), entry.getKey(),
                        SignalFxProtocolBuffers.MetricType.GAUGE, entry.getValue().value());
            }
            for (Map.Entry<MetricName, Counter> entry : counters.entrySet()) {
                session.addMetric(entry.getValue(), entry.getKey(),
                        SignalFxProtocolBuffers.MetricType.CUMULATIVE_COUNTER,
                        entry.getValue().count());
            }
            for (Map.Entry<MetricName, Histogram> entry : histograms.entrySet()) {
                session.addHistogram(entry.getKey(), entry.getValue());
            }
            for (Map.Entry<MetricName, Meter> entry : meters.entrySet()) {
                session.addMetered(entry.getKey(), entry.getValue());
            }
            for (Map.Entry<MetricName, Timer> entry : timers.entrySet()) {
                session.addTimer(entry.getKey(), entry.getValue());
            }
        } finally {
            try {
                session.close();
            } catch (Exception e) {
                // Unable to register... these exceptions handled by AggregateMetricSender
            }
        }
    }

    public MetricMetadata getMetricMetadata() {
        return metricMetadata;
    }

    public enum MetricDetails {
        // For {@link com.codahale.metrics.Sampling}
        MEDIAN("median"),
        PERCENT_75("75th"),
        PERCENT_95("95th"),
        PERCENT_98("98th"),
        PERCENT_99("99th"),
        PERCENT_999("999th"),
        MAX("max"),
        MIN("min"),
        STD_DEV("stddev"),
        MEAN("mean"),

        // For {@link com.codahale.metrics.Counting}
        COUNT("count"),

        // For {@link com.codahale.metrics.Metered}
        RATE_MEAN("rate.mean"),
        RATE_1_MIN("rate.1min"),
        RATE_5_MIN("rate.5min"),
        RATE_15_MIN("rate.15min");
        public static final Set<MetricDetails> ALL = Collections.unmodifiableSet(EnumSet.allOf(MetricDetails.class));
        public static final Set<MetricDetails> DEFAULTS = ImmutableSet.of(COUNT, MIN, MEAN, MAX);

        private final String description;

        MetricDetails(String description) {
            this.description = description;
        }

        public String getDescription() {
            return description;
        }
    }

    public static final class Builder {
        private final MetricsRegistry registry;
        private String defaultSourceName;
        private AuthToken authToken;
        private SignalFxReceiverEndpoint endpoint = new SignalFxEndpoint();
        private String name = "signalfx-reporter";
        private int timeoutMs = HttpDataPointProtobufReceiverFactory.DEFAULT_TIMEOUT_MS;
        private DataPointReceiverFactory dataPointReceiverFactory = new
                HttpDataPointProtobufReceiverFactory(endpoint);
        private MetricPredicate filter = MetricPredicate.ALL;
        private TimeUnit rateUnit = TimeUnit.SECONDS;
        private TimeUnit durationUnit = TimeUnit.MILLISECONDS; // Maybe nano eventually?
        private Set<MetricDetails> detailsToAdd = MetricDetails.DEFAULTS;
        private Collection<OnSendErrorHandler> onSendErrorHandlerCollection = Collections.emptyList();
        private MetricMetadata metricMetadata = new MetricMetadataImpl();
        private int version = HttpDataPointProtobufReceiverFactory.DEFAULT_VERSION;
        private boolean useLocalTime = false;
        private boolean enableAwsUniqueId = false;

        public Builder(MetricsRegistry registry, String authToken) {
            this(registry, new StaticAuthToken(authToken));
        }

        public Builder(MetricsRegistry registry, AuthToken authToken) {
            this(registry, authToken, SourceNameHelper.getDefaultSourceName());
        }

        public Builder(MetricsRegistry registry, AuthToken authToken, String defaultSourceName) {
            this.registry = registry;
            this.authToken = authToken;
            this.defaultSourceName = defaultSourceName;
        }

        public Builder setDefaultSourceName(String defaultSourceName) {
            this.defaultSourceName = defaultSourceName;
            return this;
        }

        public Builder setAuthToken(AuthToken authToken) {
            this.authToken = authToken;
            return this;
        }

        public Builder setEndpoint(SignalFxReceiverEndpoint endpoint) {
            this.endpoint = endpoint;
            this.dataPointReceiverFactory =
                    new HttpDataPointProtobufReceiverFactory(endpoint)
                            .setTimeoutMs(this.timeoutMs)
                            .setVersion(this.version);
            return this;
        }

        @Deprecated
        public Builder setDataPointEndpoint(DataPointReceiverEndpoint dataPointEndpoint) {
            return setEndpoint(dataPointEndpoint);
        }

        public Builder setName(String name) {
            this.name = name;
            return this;
        }

        public Builder setTimeoutMs(int timeoutMs) {
            this.timeoutMs = timeoutMs;
            this.dataPointReceiverFactory =
                    new HttpDataPointProtobufReceiverFactory(endpoint)
                            .setVersion(this.version)
                            .setTimeoutMs(this.timeoutMs);
            return this;
        }

        public Builder setVersion(int version) {
            this.version = version;
            this.dataPointReceiverFactory =
                    new HttpDataPointProtobufReceiverFactory(endpoint)
                            .setVersion(this.version)
                            .setTimeoutMs(this.timeoutMs);
            return this;
        }

        public Builder setDataPointReceiverFactory(
                DataPointReceiverFactory dataPointReceiverFactory) {
            this.dataPointReceiverFactory = dataPointReceiverFactory;
            return this;
        }

        public Builder setFilter(MetricPredicate filter) {
            this.filter = filter;
            return this;
        }

        public Builder setRateUnit(TimeUnit rateUnit) {
            this.rateUnit = rateUnit;
            return this;
        }

        public Builder setDurationUnit(TimeUnit durationUnit) {
            this.durationUnit = durationUnit;
            return this;
        }

        public Builder setDetailsToAdd(Set<MetricDetails> detailsToAdd) {
            this.detailsToAdd = detailsToAdd;
            return this;
        }

        public Builder setOnSendErrorHandlerCollection(
                Collection<OnSendErrorHandler> onSendErrorHandlerCollection) {
            this.onSendErrorHandlerCollection = onSendErrorHandlerCollection;
            return this;
        }

        public Builder setMetricMetadata(MetricMetadata metricMetadata) {
            this.metricMetadata = metricMetadata;
            return this;
        }

        /**
         * Will use the local system time, rather than zero, on sent datapoints.
         * @param useLocalTime    If true, use local system time
         * @return this
         */
        public Builder useLocalTime(boolean useLocalTime) {
            this.useLocalTime = useLocalTime;
            return this;
        }
        
        public Builder enableAwsUniqueId(boolean enableAwsUniqueId){
        	this.enableAwsUniqueId = enableAwsUniqueId;
        	return this;
        }

        public SignalFxReporter build() {
            AggregateMetricSender aggregateMetricSender = new AggregateMetricSender(
                    defaultSourceName, dataPointReceiverFactory, authToken, onSendErrorHandlerCollection);
            return new SignalFxReporter(registry, name, filter, rateUnit, durationUnit,
                    aggregateMetricSender, detailsToAdd, metricMetadata, useLocalTime, enableAwsUniqueId);
        }
    }
}
