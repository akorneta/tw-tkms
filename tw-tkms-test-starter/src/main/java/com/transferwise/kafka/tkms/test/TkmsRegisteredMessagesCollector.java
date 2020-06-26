package com.transferwise.kafka.tkms.test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.transferwise.common.baseutils.ExceptionUtils;
import com.transferwise.kafka.tkms.api.ITkmsEventsListener;
import com.transferwise.kafka.tkms.api.ShardPartition;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.apache.commons.lang3.tuple.Pair;
import org.springframework.beans.factory.annotation.Autowired;

public class TkmsRegisteredMessagesCollector implements ITkmsRegisteredMessagesCollector, ITkmsEventsListener {

  @Autowired
  private ObjectMapper objectMapper;
  @Autowired
  private TkmsTestProperties tkmsTestProperties;

  private Map<String, Map<Pair<ShardPartition, Long>, RegisteredMessage>> messages = new ConcurrentHashMap<>();

  private AtomicInteger messagesCount = new AtomicInteger();

  @Override
  public void messageRegistered(MessageRegisteredEvent event) {
    if (messagesCount.get() >= tkmsTestProperties.getMaxCollectedMessages()) {
      throw new IllegalStateException(
          "Collected " + messagesCount.get() + " messages, while the limit is " + tkmsTestProperties.getMaxCollectedMessages());
    }
    messagesCount.incrementAndGet();
    messages.computeIfAbsent(event.getMessage().getTopic(), (k) -> Collections.synchronizedMap(new LinkedHashMap<>()))
        .put(Pair.of(event.getShardPartition(), event.getStorageId()),
            new RegisteredMessage().setStorageId(event.getStorageId()).setMessage(event.getMessage()));
  }

  // Not fully atomic, but we don't care for high precision here.
  @Override
  public void clear() {
    messages = new ConcurrentHashMap<>();
    messagesCount.set(0);
  }

  @Override
  public <T> List<T> getRegisteredJsonMessages(String topic, Class<T> clazz) {
    return getRegisteredMessages(topic).stream().map(sm -> ExceptionUtils.doUnchecked(() -> objectMapper.readValue(sm.getMessage().getValue(),
        clazz))).collect(Collectors.toList());
  }

  @Override
  public List<RegisteredMessage> getRegisteredMessages(String topic) {
    Map<Pair<ShardPartition, Long>, RegisteredMessage> messagesInTopic = messages.get(topic);

    if (messagesInTopic == null) {
      return new ArrayList<>();
    }

    return new ArrayList<>(messagesInTopic.values());
  }

}
