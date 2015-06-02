/*
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * 
 *   http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 *
 */
package org.apache.qpid.client.message;

import java.util.List;

import javax.jms.JMSException;

import org.apache.qpid.AMQException;
import org.apache.qpid.client.AMQQueue;
import org.apache.qpid.client.AMQSession_0_8;
import org.apache.qpid.client.AMQTopic;
import org.apache.qpid.framing.ContentHeaderBody;
import org.apache.qpid.transport.DeliveryProperties;
import org.apache.qpid.transport.MessageProperties;


public interface MessageFactory
{
    AbstractJMSMessage createMessage(long deliveryTag,
                                     boolean redelivered,
                                     ContentHeaderBody contentHeader,
                                     String exchange,
                                     String routingKey,
                                     List bodies,
                                     AMQSession_0_8.DestinationCache<AMQQueue> queueDestinationCache,
                                     AMQSession_0_8.DestinationCache<AMQTopic> topicDestinationCache,
                                     final int addressType)
        throws JMSException, AMQException;

     AbstractJMSMessage createMessage(long deliveryTag, boolean redelivered,
                                      MessageProperties msgProps,
                                      DeliveryProperties deliveryProps,
                                      java.nio.ByteBuffer body)
        throws JMSException, AMQException;

    AbstractJMSMessage createMessage(AMQMessageDelegateFactory delegateFactory) throws JMSException;
}
