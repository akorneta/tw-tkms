package com.transferwise.kafka.tkms.metrics;

import com.transferwise.common.context.TwContext;
import com.transferwise.kafka.tkms.api.TkmsShardPartition;
import com.transferwise.kafka.tkms.config.TkmsProperties.Compression.Algorithm;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Meter.Type;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.core.instrument.distribution.DistributionStatisticConfig;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import javax.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;

/*
  A bit over engineering, but:
    - specify tags in ascending order, so micrometer has less work to do.
    - avoid `Tags.and()` to create less objects/arrays.
 */
@RequiredArgsConstructor
public class TkmsMetricsTemplate implements ITkmsMetricsTemplate {

  public static final String PREFIX = "tw.tkms";

  public static final String PREFIX_PROXY = PREFIX + ".proxy";
  public static final String PREFIX_INTERFACE = PREFIX + ".interface";
  public static final String PREFIX_DAO = PREFIX + ".dao";

  public static final String METRIC_LIBRARY_INFO = "tw.library.info";
  public static final String PROXY_POLL = PREFIX_PROXY + ".poll";
  public static final String PROXY_CYCLE = PREFIX_PROXY + ".cycle";
  public static final String PROXY_MESSAGE_SEND = PREFIX_PROXY + ".message.send";
  public static final String PROXY_KAFKA_MESSAGES_SEND = PREFIX_PROXY + ".kafka.messages.send";
  public static final String PROXY_MESSAGES_DELETION = PREFIX_PROXY + ".messages.delete";
  public static final String INTERFACE_MESSAGE_REGISTERED = PREFIX_INTERFACE + ".message.registration";
  public static final String DAO_MESSAGE_INSERT = PREFIX_DAO + ".message.insert";
  public static final String DAO_MESSAGES_DELETION = PREFIX_DAO + ".messages.delete";
  public static final String DAO_POLL_FIRST_RESULT = PREFIX_DAO + ".poll.first.result";
  public static final String DAO_POLL_GET_CONNECTION = PREFIX_DAO + ".poll.get.connection";
  public static final String DAO_POLL_ALL_RESULTS = PREFIX_DAO + ".poll.all.results";
  public static final String DAO_POLL_ALL_RESULTS_COUNT = PREFIX_DAO + ".poll.all.results.count";
  public static final String DAO_INVALID_GENERATED_KEYS_COUNT = PREFIX_DAO + ".insert.invalid.generated.keys.count";
  public static final String STORED_MESSAGE_PARSING = PREFIX + ".stored.message.parsing";
  public static final String MESSAGE_INSERT_TO_ACK = PREFIX + ".message.insert.to.ack";
  public static final String COMPRESSION_RATIO_ACHIEVED = PREFIX + ".compression.ratio.achieved";

  public static final Tag NA_SHARD_TAG = Tag.of("shard", "N/A");
  public static final Tag NA_PARTITION_TAG = Tag.of("partition", "N/A");
  public static final Tag TAG_SUCCESS_TRUE = Tag.of("success", "true");
  public static final Tag TAG_SUCCESS_FALSE = Tag.of("success", "false");
  public static final Tag TAG_POLL_RESULT_EMPTY = Tag.of("pollResult", "empty");
  public static final Tag TAG_POLL_RESULTS_NOT_EMPTY = Tag.of("pollResult", "not_empty");

  private final MeterRegistry meterRegistry;

  @PostConstruct
  public void init() {
    Map<String, double[]> slos = new HashMap<>();
    double[] defaultSlos = new double[]{1, 5, 25, 125, 625, 3125};
    slos.put(PROXY_POLL, defaultSlos);
    slos.put(PROXY_CYCLE, defaultSlos);
    slos.put(DAO_POLL_FIRST_RESULT, defaultSlos);
    slos.put(DAO_POLL_ALL_RESULTS, defaultSlos);
    slos.put(DAO_POLL_GET_CONNECTION, defaultSlos);
    slos.put(PROXY_KAFKA_MESSAGES_SEND, defaultSlos);
    slos.put(PROXY_MESSAGES_DELETION, defaultSlos);
    slos.put(STORED_MESSAGE_PARSING, defaultSlos);
    slos.put(MESSAGE_INSERT_TO_ACK, new double[]{1, 5, 25, 125, 625, 3125, 3125 * 5});
    slos.put(COMPRESSION_RATIO_ACHIEVED, new double[]{0.05, 0.1, 0.25, 0.5, 0.75, 1, 1.25, 2});

    meterRegistry.config().meterFilter(new MeterFilter() {
      @Override
      public DistributionStatisticConfig configure(Meter.Id id, DistributionStatisticConfig config) {
        double[] sloConfigValues = slos.get(id.getName());
        if (sloConfigValues != null) {
          double[] sloValues = Arrays.copyOf(sloConfigValues, sloConfigValues.length);
          for (int i = 0; i < sloValues.length; i++) {
            if (id.getType() == Type.TIMER) {
              sloValues[i] = sloValues[i] * 1_000_000L;
            } else {
              sloValues[i] = sloValues[i];
            }
          }
          return DistributionStatisticConfig.builder()
              .percentilesHistogram(false)
              .serviceLevelObjectives(sloValues)
              .build()
              .merge(config);
        }
        return config;
      }
    });
  }

  @Override
  public void recordProxyPoll(TkmsShardPartition shardPartition, int recordsCount, long startNanoTime) {
    meterRegistry
        .timer(PROXY_POLL, Tags.of(
            partitionTag(shardPartition),
            pollResultTag(recordsCount > 0),
            shardTag(shardPartition)))
        .record(System.nanoTime() - startNanoTime, TimeUnit.NANOSECONDS);
  }

  @Override
  public void recordProxyMessageSendSuccess(TkmsShardPartition shardPartition, String topic, Instant insertTime) {
    meterRegistry
        .counter(PROXY_MESSAGE_SEND, Tags.of(
            partitionTag(shardPartition),
            shardTag(shardPartition),
            successTag(true),
            topicTag(topic)))
        .increment();

    if (insertTime != null) {
      meterRegistry
          .timer(MESSAGE_INSERT_TO_ACK, Tags.of(
              partitionTag(shardPartition),
              shardTag(shardPartition),
              topicTag(topic)))
          .record(System.currentTimeMillis() - insertTime.toEpochMilli(), TimeUnit.MILLISECONDS);
    }
  }

  @Override
  public void recordProxyMessageSendFailure(TkmsShardPartition shardPartition, String topic) {
    meterRegistry
        .counter(PROXY_MESSAGE_SEND, Tags.of(
            partitionTag(shardPartition),
            shardTag(shardPartition),
            successTag(false),
            topicTag(topic)))
        .increment();
  }

  @Override
  public void recordMessageRegistering(String topic, TkmsShardPartition shardPartition) {
    TwContext currentContext = TwContext.current();
    meterRegistry
        .counter(INTERFACE_MESSAGE_REGISTERED, Tags.of(
            entryPointGroupTag(currentContext),
            entryPointNameTag(currentContext),
            entryPointOwnerTag(currentContext),
            partitionTag(shardPartition),
            shardTag(shardPartition),
            topicTag(topic)))
        .increment();
  }

  @Override
  public void recordDaoMessageInsert(TkmsShardPartition shardPartition, String topic) {
    TwContext currentContext = TwContext.current();
    meterRegistry
        .counter(DAO_MESSAGE_INSERT, Tags.of(
            entryPointGroupTag(currentContext),
            entryPointNameTag(currentContext),
            entryPointOwnerTag(currentContext),
            partitionTag(shardPartition),
            shardTag(shardPartition),
            topicTag(topic)))
        .increment();
  }

  @Override
  public void recordDaoPollFirstResult(TkmsShardPartition shardPartition, long startNanoTime) {
    meterRegistry
        .timer(DAO_POLL_FIRST_RESULT, Tags.of(
            partitionTag(shardPartition),
            shardTag(shardPartition)))
        .record(System.nanoTime() - startNanoTime, TimeUnit.NANOSECONDS);
  }

  @Override
  public void recordDaoPollAllResults(TkmsShardPartition shardPartition, int recordsCount, long startNanoTime) {
    meterRegistry
        .timer(DAO_POLL_ALL_RESULTS, Tags.of(
            partitionTag(shardPartition),
            shardTag(shardPartition)))
        .record(System.nanoTime() - startNanoTime, TimeUnit.NANOSECONDS);
    meterRegistry
        .summary(DAO_POLL_ALL_RESULTS_COUNT, Tags.of(
            partitionTag(shardPartition),
            shardTag(shardPartition)))
        .record(recordsCount);
  }

  @Override
  public void recordDaoPollGetConnection(TkmsShardPartition shardPartition, long startNanoTime) {
    meterRegistry
        .timer(DAO_POLL_GET_CONNECTION, Tags.of(
            partitionTag(shardPartition),
            shardTag(shardPartition)))
        .record(System.nanoTime() - startNanoTime, TimeUnit.NANOSECONDS);
  }

  @Override
  public void recordProxyCycle(TkmsShardPartition shardPartition, int recordsCount, long startNanoTime) {
    meterRegistry
        .timer(PROXY_CYCLE, Tags.of(
            partitionTag(shardPartition),
            pollResultTag(recordsCount > 0),
            shardTag(shardPartition)))
        .record(System.nanoTime() - startNanoTime, TimeUnit.NANOSECONDS);
  }

  @Override
  public void recordProxyKafkaMessagesSend(TkmsShardPartition shardPartition, long startNanoTime) {
    meterRegistry
        .timer(PROXY_KAFKA_MESSAGES_SEND, Tags.of(
            partitionTag(shardPartition),
            shardTag(shardPartition)))
        .record(System.nanoTime() - startNanoTime, TimeUnit.NANOSECONDS);
  }

  @Override
  public void recordProxyMessagesDeletion(TkmsShardPartition shardPartition, long startNanoTime) {
    meterRegistry
        .timer(PROXY_MESSAGES_DELETION, Tags.of(
            partitionTag(shardPartition),
            shardTag(shardPartition)))
        .record(System.nanoTime() - startNanoTime, TimeUnit.NANOSECONDS);
  }

  /**
   * The batchSize cardinality will be low.
   *
   * <p>batchSize tag allows to verify algorithmic correctness for deletions.
   */
  @Override
  public void recordDaoMessagesDeletion(TkmsShardPartition shardPartition, int batchSize) {
    meterRegistry
        .counter(DAO_MESSAGES_DELETION, Tags.of(
            batchSizeTag(batchSize),
            partitionTag(shardPartition),
            shardTag(shardPartition)))
        .increment();
  }

  @Override
  public void recordStoredMessageParsing(TkmsShardPartition shardPartition, long messageParsingStartNanoTime) {
    meterRegistry
        .timer(STORED_MESSAGE_PARSING, Tags.of(
            partitionTag(shardPartition),
            shardTag(shardPartition)))
        .record(System.nanoTime() - messageParsingStartNanoTime, TimeUnit.NANOSECONDS);
  }

  @Override
  public void recordMessageCompression(TkmsShardPartition shardPartition, Algorithm algorithm, double ratio) {
    meterRegistry
        .summary(COMPRESSION_RATIO_ACHIEVED, Tags.of(
            algorithmTag(algorithm),
            partitionTag(shardPartition),
            shardTag(shardPartition)))
        .record(ratio);
  }

  @Override
  public void recordDaoInvalidGeneratedKeysCount(TkmsShardPartition shardPartition) {
    meterRegistry
        .counter(DAO_INVALID_GENERATED_KEYS_COUNT, Tags.of(
            partitionTag(shardPartition),
            shardTag(shardPartition)))
        .increment();
  }

  @Override
  public void registerLibrary() {
    String version = this.getClass().getPackage().getImplementationVersion();
    if (version == null) {
      version = "Unknown";
    }

    Gauge.builder(METRIC_LIBRARY_INFO, () -> 1d).tags("version", version, "library", "tw-tkms")
        .description("Provides metadata about the library, for example the version.")
        .register(meterRegistry);
  }

  protected Tag batchSizeTag(int batchSize) {
    return Tag.of("batchSize", String.valueOf(batchSize));
  }

  protected Tag entryPointGroupTag(TwContext twContext) {
    return Tag.of("epGroup", twContext.getGroup());
  }

  protected Tag entryPointNameTag(TwContext twContext) {
    return Tag.of("epName", twContext.getName());
  }

  protected Tag entryPointOwnerTag(TwContext twContext) {
    return Tag.of("epOwner", twContext.getOwner());
  }

  protected Tag shardTag(TkmsShardPartition shardPartition) {
    if (shardPartition == null) {
      return NA_SHARD_TAG;
    }
    return shardPartition.getMicrometerShardTag();
  }

  protected Tag partitionTag(TkmsShardPartition shardPartition) {
    if (shardPartition == null) {
      return NA_PARTITION_TAG;
    }
    return shardPartition.getMicrometerPartitionTag();
  }

  protected Tag pollResultTag(boolean empty) {
    return empty ? TAG_POLL_RESULT_EMPTY : TAG_POLL_RESULTS_NOT_EMPTY;
  }

  protected Tag topicTag(String topic) {
    return Tag.of("topic", topic);
  }

  protected Tag successTag(boolean success) {
    return success ? TAG_SUCCESS_TRUE : TAG_SUCCESS_FALSE;
  }

  protected Tag algorithmTag(Algorithm algorithm) {
    return algorithm.getMicrometerTag();
  }
}
