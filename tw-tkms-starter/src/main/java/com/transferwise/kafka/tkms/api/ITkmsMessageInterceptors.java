package com.transferwise.kafka.tkms.api;

import org.apache.kafka.clients.producer.ProducerRecord;

public interface ITkmsMessageInterceptors {

  /**
   * Just an aggregator for {@link ITkmsMessageInterceptor}.
   */
  TkmsProxyDecision beforeProxy(ProducerRecord<String, byte[]> producerRecord);

  /**
   * Just an aggregator for {@link ITkmsMessageInterceptor}.
   */
  TkmsProxyDecision onError(Throwable t, ProducerRecord<String, byte[]> producerRecord);
}
