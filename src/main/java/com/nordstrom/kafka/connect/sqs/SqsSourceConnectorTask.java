/*
 * Copyright 2019 Nordstrom, Inc.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.nordstrom.kafka.connect.sqs ;

import java.math.BigDecimal;
import java.util.Collections ;
import java.util.HashMap ;
import java.util.LinkedList;
import java.util.List ;
import java.util.Map ;
import java.util.stream.Collectors ;

import org.apache.kafka.connect.data.Schema ;
import org.apache.kafka.connect.header.ConnectHeaders;
import org.apache.kafka.connect.header.Header;
import org.apache.kafka.connect.header.Headers;
import org.apache.kafka.connect.source.SourceRecord ;
import org.apache.kafka.connect.source.SourceTask ;
import org.slf4j.Logger ;
import org.slf4j.LoggerFactory ;

import com.amazonaws.services.sqs.model.Message ;
import com.amazonaws.services.sqs.model.MessageAttributeValue;
import com.nordstrom.kafka.connect.About ;

public class SqsSourceConnectorTask extends SourceTask {
  private final Logger log = LoggerFactory.getLogger( this.getClass() ) ;

  private SqsClient client ;
  private SqsSourceConnectorConfig config ;

  /*
   * (non-Javadoc)
   * 
   * @see org.apache.kafka.connect.connector.Task#version()
   */
  @Override
  public String version() {
    return About.CURRENT_VERSION ;
  }

  /*
   * (non-Javadoc)
   * 
   * @see org.apache.kafka.connect.source.SourceTask#start(java.util.Map)
   */
  @Override
  public void start( Map<String, String> props ) {
    log.info( "task.start" ) ;
    Guard.verifyNotNull( props, "Task properties" ) ;

    config = new SqsSourceConnectorConfig( props ) ;
    client = new SqsClient(config) ;

    log.info( "task.start.OK, sqs.queue.url={}, topics={}", config.getQueueUrl(), config.getTopics() ) ;
  }

  /*
   * (non-Javadoc)
   * 
   * @see org.apache.kafka.connect.source.SourceTask#poll()
   */
  @Override
  public List<SourceRecord> poll() throws InterruptedException {
    log.debug( ".poll:valid-state={}", isValidState() ) ;

    if ( !isValidState() ) {
      throw new IllegalStateException( "Task is not properly initialized" ) ;
    }

    // Read messages from the queue.
    List<Message> messages = client.receive( config.getQueueUrl(), config.getMaxMessages(),
        config.getWaitTimeSeconds() ) ;
    log.debug( ".poll:url={}, max={}, wait={}, size={}", config.getQueueUrl(), config.getMaxMessages(),
        config.getWaitTimeSeconds(), messages.size() ) ;

    // Create a SourceRecord for each message in the queue.
    return messages.stream().map( message -> {

      Map<String, String> sourcePartition = Collections.singletonMap( SqsConnectorConfigKeys.SQS_QUEUE_URL.getValue(),
          config.getQueueUrl() ) ;
      Map<String, String> sourceOffset = new HashMap<>() ;
      // Save the message id and receipt-handle. receipt-handle is needed to delete
      // the message once the record is committed.
      sourceOffset.put( SqsConnectorConfigKeys.SQS_MESSAGE_ID.getValue(), message.getMessageId() ) ;
      sourceOffset.put( SqsConnectorConfigKeys.SQS_MESSAGE_RECEIPT_HANDLE.getValue(), message.getReceiptHandle() ) ;
      log.trace( ".poll:source-partition={}", sourcePartition ) ;
      log.trace( ".poll:source-offset={}", sourceOffset ) ;

      final String body = message.getBody() ;
      final String key = message.getMessageId() ;
      final String topic = config.getTopics() ;
      
      // SourceRecord record = new SourceRecord(sourcePartition, sourceOffset, topic, partition, keySchema, key, valueSchema, value, timestamp, headers)
      final SourceRecord record = new SourceRecord( sourcePartition, sourceOffset, topic, Schema.STRING_SCHEMA, key, Schema.STRING_SCHEMA,
      body );

      extractHeaders(message).forEach((header) -> {
        record.headers().add(header);
      });

      return record;
    } ).collect( Collectors.toList() ) ;
  }

  private Headers extractHeaders(Message message){
    Headers headers = new ConnectHeaders();
    Map<String, MessageAttributeValue> messageAttributes = message.getMessageAttributes();
    messageAttributes.entrySet().forEach((attribute) -> {
      String fieldName = attribute.getKey();
      MessageAttributeValue sqsHeaderValue = attribute.getValue();
      switch(sqsHeaderValue.getDataType()) {
        case "Number":
        BigDecimal decimal = new BigDecimal(sqsHeaderValue.getStringValue());
        headers.addDecimal(fieldName, decimal);
        break;
        
        case "String":
        headers.addString(fieldName, sqsHeaderValue.getStringValue());
        break;


        case "Binary":
        headers.addBytes(fieldName, sqsHeaderValue.getBinaryValue().array());
        break;

        // In question whether we want to do anything more specific about custom attribute type labels
        default:
        headers.addString(fieldName, sqsHeaderValue.getStringValue());
        break;
      }
      
    } );
    return headers;

  }

  /* (non-Javadoc)
   * @see org.apache.kafka.connect.source.SourceTask#commitRecord(org.apache.kafka.connect.source.SourceRecord)
   */
  @Override
  public void commitRecord( SourceRecord record ) throws InterruptedException {
    Guard.verifyNotNull( record, "record" ) ;
    final String receipt = record.sourceOffset().get( SqsConnectorConfigKeys.SQS_MESSAGE_RECEIPT_HANDLE.getValue() )
        .toString() ;
    log.debug( ".commit-record:url={}, receipt-handle={}", config.getQueueUrl(), receipt ) ;
    client.delete( config.getQueueUrl(), receipt ) ;
    super.commitRecord( record ) ;
  }

  /*
   * (non-Javadoc)
   * 
   * @see org.apache.kafka.connect.source.SourceTask#stop()
   */
  @Override
  public void stop() {
    log.info( "task.stop:OK" ) ;
  }

  /**
   * Test that we have both the task configuration and SQS client properly
   * initialized.
   * 
   * @return true if task is in a valid state.
   */
  private boolean isValidState() {
    return null != config && null != client ;
  }

}
