package com.signalfx.codahale.reporter;

import java.util.Collections;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import com.codahale.metrics.Metric;
import com.codahale.metrics.MetricRegistry;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.signalfx.codahale.metrics.MetricBuilder;
import com.signalfx.metrics.protobuf.SignalFxProtocolBuffers.MetricType;

public final class MetricMetadataImpl implements MetricMetadata {
    private final ConcurrentMap<Metric, Metadata> metaDataCollection;

    public MetricMetadataImpl() {
        // This map must be thread safe
        metaDataCollection = new ConcurrentHashMap<Metric, Metadata>();
    }

    @Override
    public Map<String, String> getTags(Metric metric) {
        Metadata existingMetaData = metaDataCollection.get(metric);
        if (existingMetaData == null) {
            return Collections.emptyMap();
        } else {
            return Collections.unmodifiableMap(existingMetaData.tags);
        }
    }

    @Override
    public Optional<MetricType> getMetricType(Metric metric) {
        Metadata existingMetaData = metaDataCollection.get(metric);
        if (existingMetaData == null || existingMetaData.metricType == null) {
            return Optional.absent();
        } else {
            return Optional.of(existingMetaData.metricType);
        }
    }

    @Override
    public <M extends Metric> Tagger<M> tagMetric(M metric) {
        return forMetric(metric);
    }

    @Override
    public <M extends Metric> Tagger<M> forMetric(M metric) {
        Metadata metadata = metaDataCollection.get(metric);
        if (metadata == null) {
            synchronized (this) {
                metadata = metaDataCollection.get(metric);
                if (metadata == null) {
                    metadata = new Metadata();
                    Metadata oldMetaData = metaDataCollection.put(metric, metadata);
                    Preconditions.checkArgument(oldMetaData == null,
                            "Concurrency issue adding metadata");
                }
            }
        }
        return new TaggerImpl<M>(metric, metadata);
    }

    @Override
    public <M extends Metric> BuilderTagger<M> forBuilder(
            MetricBuilder<M> metricBuilder) {
        return new BuilderTaggerImpl<M>(metricBuilder, metaDataCollection, new Metadata());
    }

    @Override
    public <M extends Metric> boolean removeMetric(M metric, MetricRegistry metricRegistry) {
        Metadata metadata = metaDataCollection.remove(metric);
        if (metadata == null) {
            return false;
        }
        metricRegistry.remove(metadata.getCodahaleName());
        return true;
    }

    private static abstract class TaggerBaseImpl<M extends Metric, T extends TaggerBase<M, T>>
            implements TaggerBase<M, T>{
        protected final Metadata thisMetricsMetadata;

        @Override
        public T withDimension(String key, String value) {
            thisMetricsMetadata.tags.put(key, value);
            return (T) this;
        }

        TaggerBaseImpl(Metadata thisMetricsMetadata) {
            this.thisMetricsMetadata = thisMetricsMetadata;
        }

        @Override
        public T withSourceName(String sourceName) {
            thisMetricsMetadata.tags.put(SOURCE, sourceName);
            return (T) this;
        }

        @Override
        public T withMetricName(String metricName) {
            thisMetricsMetadata.tags.put(METRIC, metricName);
            return (T) this;
        }

        @Override
        public T withMetricType(MetricType metricType) {
            thisMetricsMetadata.metricType = metricType;
            return (T) this;
        }

        protected String createCodahaleName() {
            return thisMetricsMetadata.getCodahaleName();
        }
    }

    private static final class TaggerImpl<M extends Metric> extends TaggerBaseImpl<M, Tagger<M>>
            implements Tagger<M> {
        private final M metric;

        TaggerImpl(M metric, Metadata thisMetricsMetadata) {
            super(thisMetricsMetadata);
            this.metric = metric;
        }

        @Override
        public M register(MetricRegistry metricRegistry) {
            String compositeName = createCodahaleName();
            return metricRegistry.register(compositeName, metric);
        }

        @Override public M metric() {
            return metric;
        }
    }

    private class BuilderTaggerImpl<M extends Metric> extends TaggerBaseImpl<M, BuilderTagger<M>>
            implements BuilderTagger<M> {
        private final MetricBuilder<M> metricBuilder;
        private final ConcurrentMap<Metric, Metadata> metaDataCollection;

        public BuilderTaggerImpl(MetricBuilder<M> metricBuilder,
                                 ConcurrentMap<Metric, Metadata> metaDataCollection,
                                 Metadata thisMetricsMetadata) {
            super(thisMetricsMetadata);
            this.metricBuilder = metricBuilder;
            this.metaDataCollection = metaDataCollection;
        }

        @Override
        public M createOrGet(MetricRegistry metricRegistry) {
            String compositeName = createCodahaleName();
            Metric existingMetric = metricRegistry.getMetrics().get(compositeName);
            if (existingMetric != null && metaDataCollection.get(existingMetric) != null) {
                return validateMetric(existingMetric, compositeName);
            }
            // Lock on an object that is shared by the metadata tagger, not *this* which is not.
            synchronized (metaDataCollection) {
                existingMetric = metricRegistry.getMetrics().get(compositeName);
                if (existingMetric != null) {
                    return validateMetric(existingMetric, compositeName);
                }
                // This could throw a IllegalArgumentException.  That would only happen if another
                // metric was made with our name, but not constructed by the metadata tagger.  This
                // is super strange and deserves an exception.
                Metric newMetric = metricRegistry.register(compositeName,
                        metricBuilder.newMetric());
                Preconditions.checkArgument(
                        null == metaDataCollection.put(newMetric, thisMetricsMetadata));
                return (M) newMetric;
            }
        }

        private M validateMetric(Metric existingMetric, String compositeName) {
            if (!metricBuilder.isInstance(existingMetric)) {
                throw new IllegalArgumentException(
                        String.format("The metric %s is not of the correct type",
                                compositeName));
            }
            if (!thisMetricsMetadata.equals(metaDataCollection.get(existingMetric))) {
                throw new IllegalArgumentException(String.format(
                        "Existing metric has different tags.  Unable to differentiate " +
                                "metrics: %s",
                        compositeName));
            }
            return (M) existingMetric;
        }
    }

    private static final class Metadata {
        private final Map<String, String> tags;
        private MetricType metricType;

        private Metadata() {
            tags = new ConcurrentHashMap<String, String>(6);
        }

        public String getCodahaleName() {
            final String existingMetricName = Preconditions.checkNotNull(
                    tags.get(MetricMetadata.METRIC),
                    "The register helper needs a base metric name to build a readable "
                            + "metric.  use withMetricName or codahale directly");

            // The names should be unique so we sort each parameter by the tag key.
            SortedMap<String, String> extraParameters = new TreeMap<String, String>();
            for (Map.Entry<String, String> entry : tags.entrySet()) {
                // Don't re-add the metric name
                if (!MetricMetadata.METRIC.equals(entry.getKey())) {
                    extraParameters.put(entry.getKey(), entry.getValue());
                }
            }
            StringBuilder compositeName = new StringBuilder();
            // Add each entry in sorted order
            for (Map.Entry<String, String> entry : extraParameters.entrySet()) {
                compositeName.append(entry.getValue()).append('.');
            }
            compositeName.append(existingMetricName);
            return compositeName.toString();
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof Metadata)) {
                return false;
            }

            Metadata metadata = (Metadata) o;

            if (metricType != metadata.metricType) {
                return false;
            }
            if (tags != null ? !tags.equals(metadata.tags) : metadata.tags != null) {
                return false;
            }

            return true;
        }

        @Override
        public int hashCode() {
            int result = tags != null ? tags.hashCode() : 0;
            result = 31 * result + (metricType != null ? metricType.hashCode() : 0);
            return result;
        }
    }
}
