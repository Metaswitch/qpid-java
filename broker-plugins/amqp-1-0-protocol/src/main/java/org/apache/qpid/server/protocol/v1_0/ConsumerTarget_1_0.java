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
package org.apache.qpid.server.protocol.v1_0;

import java.nio.ByteBuffer;
import java.util.Collection;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.qpid.amqp_1_0.codec.ValueHandler;
import org.apache.qpid.amqp_1_0.messaging.SectionEncoder;
import org.apache.qpid.amqp_1_0.messaging.SectionEncoderImpl;
import org.apache.qpid.amqp_1_0.transport.SendingLinkEndpoint;
import org.apache.qpid.amqp_1_0.type.AmqpErrorException;
import org.apache.qpid.amqp_1_0.type.Binary;
import org.apache.qpid.amqp_1_0.type.DeliveryState;
import org.apache.qpid.amqp_1_0.type.Outcome;
import org.apache.qpid.amqp_1_0.type.UnsignedInteger;
import org.apache.qpid.amqp_1_0.type.codec.AMQPDescribedTypeRegistry;
import org.apache.qpid.amqp_1_0.type.messaging.Accepted;
import org.apache.qpid.amqp_1_0.type.messaging.Header;
import org.apache.qpid.amqp_1_0.type.messaging.Modified;
import org.apache.qpid.amqp_1_0.type.messaging.Released;
import org.apache.qpid.amqp_1_0.type.transaction.TransactionalState;
import org.apache.qpid.amqp_1_0.type.transport.SenderSettleMode;
import org.apache.qpid.amqp_1_0.type.transport.Transfer;
import org.apache.qpid.bytebuffer.QpidByteBuffer;
import org.apache.qpid.server.transport.ProtocolEngine;
import org.apache.qpid.server.consumer.AbstractConsumerTarget;
import org.apache.qpid.server.consumer.ConsumerImpl;
import org.apache.qpid.server.message.MessageInstance;
import org.apache.qpid.server.message.ServerMessage;
import org.apache.qpid.server.plugin.MessageConverter;
import org.apache.qpid.server.protocol.AMQSessionModel;
import org.apache.qpid.server.protocol.LinkRegistry;
import org.apache.qpid.server.protocol.MessageConverterRegistry;
import org.apache.qpid.server.txn.ServerTransaction;
import org.apache.qpid.server.util.ConnectionScopedRuntimeException;

class ConsumerTarget_1_0 extends AbstractConsumerTarget
{
    private static final Logger LOGGER = LoggerFactory.getLogger(ConsumerTarget_1_0.class);
    private final boolean _acquires;
    private SendingLink_1_0 _link;

    private long _deliveryTag = 0L;

    private Binary _transactionId;
    private final AMQPDescribedTypeRegistry _typeRegistry;
    private final SectionEncoder _sectionEncoder;
    private ConsumerImpl _consumer;
    private boolean _queueEmpty;

    public ConsumerTarget_1_0(final SendingLink_1_0 link,
                              boolean acquires)
    {
        super(State.SUSPENDED);
        _link = link;
        _typeRegistry = link.getEndpoint().getSession().getConnection().getDescribedTypeRegistry();
        _sectionEncoder = new SectionEncoderImpl(_typeRegistry);
        _acquires = acquires;
    }

    public ConsumerImpl getConsumer()
    {
        return _consumer;
    }

    private SendingLinkEndpoint getEndpoint()
    {
        return _link.getEndpoint();
    }

    @Override
    public boolean isFlowSuspended()
    {
        return _link.getSession().getAMQPConnection().isConnectionStopped() || getState() != State.ACTIVE;

    }

    @Override
    protected void afterCloseInternal()
    {

    }

    @Override
    protected void doCloseInternal()
    {

    }

    public void doSend(final ConsumerImpl consumer, final MessageInstance entry, boolean batch)
    {
        // TODO
        ServerMessage serverMessage = entry.getMessage();
        Message_1_0 message;
        if(serverMessage instanceof Message_1_0)
        {
            message = (Message_1_0) serverMessage;
        }
        else
        {
            final MessageConverter converter = MessageConverterRegistry.getConverter(serverMessage.getClass(), Message_1_0.class);
            message = (Message_1_0) converter.convert(serverMessage, _link.getVirtualHost());
        }

        Transfer transfer = new Transfer();
        try
        {
            QpidByteBuffer payload = null;
            //TODO
            Collection<QpidByteBuffer> fragments = message.getFragments();
            if(fragments.size() == 1)
            {
                payload = fragments.iterator().next();
            }
            else
            {
                int size = 0;
                for(QpidByteBuffer fragment : fragments)
                {
                    size += fragment.remaining();
                }

                payload = QpidByteBuffer.allocateDirect(size);

                for(QpidByteBuffer fragment : fragments)
                {
                    payload.put(fragment);
                    fragment.dispose();
                }

                payload.flip();
            }

            if(entry.getDeliveryCount() != 0)
            {
                ValueHandler valueHandler = new ValueHandler(_typeRegistry);

                Header oldHeader = null;
                try
                {
                    Object value = valueHandler.parse(payload);
                    if(value instanceof Header)
                    {
                        oldHeader = (Header) value;
                    }
                    else
                    {
                        payload.position(0);
                    }
                }
                catch (AmqpErrorException e)
                {
                    //TODO
                    throw new ConnectionScopedRuntimeException(e);
                }

                Header header = new Header();
                if(oldHeader != null)
                {
                    header.setDurable(oldHeader.getDurable());
                    header.setPriority(oldHeader.getPriority());
                    header.setTtl(oldHeader.getTtl());
                }
                header.setDeliveryCount(UnsignedInteger.valueOf(entry.getDeliveryCount()));
                _sectionEncoder.reset();
                _sectionEncoder.encodeObject(header);
                Binary encodedHeader = _sectionEncoder.getEncoding();

                QpidByteBuffer oldPayload = payload;
                payload = QpidByteBuffer.allocateDirect(oldPayload.remaining() + encodedHeader.getLength());
                payload.put(encodedHeader.getArray(),encodedHeader.getArrayOffset(),encodedHeader.getLength());
                payload.put(oldPayload);
                oldPayload.dispose();
                payload.flip();
            }

            transfer.setPayload(payload);
            byte[] data = new byte[8];
            ByteBuffer.wrap(data).putLong(_deliveryTag++);
            final Binary tag = new Binary(data);

            transfer.setDeliveryTag(tag);

            synchronized(_link.getLock())
            {
                if(_link.isAttached())
                {
                    if(SenderSettleMode.SETTLED.equals(getEndpoint().getSendingSettlementMode()))
                    {
                        transfer.setSettled(true);
                    }
                    else
                    {
                        UnsettledAction action = _acquires
                                                 ? new DispositionAction(tag, entry)
                                                 : new DoNothingAction(tag, entry);

                        _link.addUnsettled(tag, action, entry);
                    }

                    if(_transactionId != null)
                    {
                        TransactionalState state = new TransactionalState();
                        state.setTxnId(_transactionId);
                        transfer.setState(state);
                    }
                    // TODO - need to deal with failure here
                    if(_acquires && _transactionId != null)
                    {
                        ServerTransaction txn = _link.getTransaction(_transactionId);
                        if(txn != null)
                        {
                            txn.addPostTransactionAction(new ServerTransaction.Action(){

                                public void postCommit()
                                {
                                }

                                public void onRollback()
                                {
                                    if(entry.isAcquiredBy(getConsumer()))
                                    {
                                        entry.release();
                                        _link.getEndpoint().updateDisposition(tag, (DeliveryState)null, true);


                                    }
                                }
                            });
                        }

                    }
                    getSession().getAMQPConnection().registerMessageDelivered(message.getSize());
                    getEndpoint().transfer(transfer, false);
                }
                else
                {
                    entry.release();
                }
            }
        }
        finally
        {
            transfer.dispose();
        }
    }

    public void flushBatched()
    {
        // TODO
    }

    public void queueDeleted()
    {
        //TODO
        getEndpoint().setSource(null);
        getEndpoint().close();

        final LinkRegistry linkReg = getSession().getConnection()
                .getVirtualHost()
                .getLinkRegistry(getEndpoint().getSession().getConnection().getRemoteContainerId());
        linkReg.unregisterSendingLink(getEndpoint().getName());
    }

    public boolean allocateCredit(final ServerMessage msg)
    {
        synchronized (_link.getLock())
        {

            ProtocolEngine protocolEngine = getSession().getConnection().getAmqpConnection();
            final boolean hasCredit = _link.isAttached() && getEndpoint().hasCreditToSend() && !protocolEngine.isTransportBlockedForWriting();
            if (!hasCredit && getState() == State.ACTIVE)
            {
                suspend();
            }

            if (hasCredit)
            {
                SendingLinkEndpoint linkEndpoint = _link.getEndpoint();
                linkEndpoint.setLinkCredit(linkEndpoint.getLinkCredit().subtract(UnsignedInteger.ONE));
            }

            return hasCredit;
        }
    }


    public void suspend()
    {
        synchronized(_link.getLock())
        {
            updateState(State.ACTIVE, State.SUSPENDED);
        }
    }


    public void restoreCredit(final ServerMessage message)
    {
        synchronized (_link.getLock())
        {
            final SendingLinkEndpoint endpoint = _link.getEndpoint();
            endpoint.setLinkCredit(endpoint.getLinkCredit().add(UnsignedInteger.ONE));
        }
    }

    public void queueEmpty()
    {
        synchronized(_link.getLock())
        {
            _queueEmpty = true;
        }
    }

    public void flowStateChanged()
    {
        synchronized(_link.getLock())
        {
            ProtocolEngine protocolEngine = getSession().getConnection().getAmqpConnection();
            if(isFlowSuspended() && getEndpoint() != null && !protocolEngine.isTransportBlockedForWriting())
            {
                updateState(State.SUSPENDED, State.ACTIVE);
                _transactionId = _link.getTransactionId();
            }
        }
    }

    public Session_1_0 getSession()
    {
        return _link.getSession();
    }

    public void flush()
    {
        _consumer.flush();
    }

    private class DispositionAction implements UnsettledAction
    {

        private final MessageInstance _queueEntry;
        private final Binary _deliveryTag;

        public DispositionAction(Binary tag, MessageInstance queueEntry)
        {
            _deliveryTag = tag;
            _queueEntry = queueEntry;
        }

        public boolean process(DeliveryState state, final Boolean settled)
        {

            Binary transactionId = null;
            final Outcome outcome;
            // If disposition is settled this overrides the txn?
            if(state instanceof TransactionalState)
            {
                transactionId = ((TransactionalState)state).getTxnId();
                outcome = ((TransactionalState)state).getOutcome();
            }
            else if (state instanceof Outcome)
            {
                outcome = (Outcome) state;
            }
            else
            {
                outcome = null;
            }


            ServerTransaction txn = _link.getTransaction(transactionId);

            if(outcome instanceof Accepted)
            {
                _queueEntry.lockAcquisition();
                txn.dequeue(_queueEntry.getEnqueueRecord(),
                        new ServerTransaction.Action()
                        {

                            public void postCommit()
                            {
                                if(_queueEntry.isAcquiredBy(getConsumer()))
                                {
                                    _queueEntry.delete();
                                }
                            }

                            public void onRollback()
                            {

                            }
                        });
                txn.addPostTransactionAction(new ServerTransaction.Action()
                    {
                        public void postCommit()
                        {
                            //_link.getEndpoint().settle(_deliveryTag);
                            _link.getEndpoint().updateDisposition(_deliveryTag, (DeliveryState)outcome, true);
                            _link.getEndpoint().sendFlowConditional();
                        }

                        public void onRollback()
                        {
                            if(Boolean.TRUE.equals(settled))
                            {
                                final Modified modified = new Modified();
                                modified.setDeliveryFailed(true);
                                _link.getEndpoint().updateDisposition(_deliveryTag, modified, true);
                                _link.getEndpoint().sendFlowConditional();
                                _queueEntry.incrementDeliveryCount();
                                _queueEntry.release();
                            }
                        }
                    });
            }
            else if(outcome instanceof Released)
            {
                txn.addPostTransactionAction(new ServerTransaction.Action()
                {
                    public void postCommit()
                    {

                        _queueEntry.release();
                        _link.getEndpoint().settle(_deliveryTag);
                    }

                    public void onRollback()
                    {
                        _link.getEndpoint().settle(_deliveryTag);
                    }
                });
            }

            else if(outcome instanceof Modified)
            {
                txn.addPostTransactionAction(new ServerTransaction.Action()
                {
                    public void postCommit()
                    {

                        _queueEntry.release();
                        if(Boolean.TRUE.equals(((Modified)outcome).getDeliveryFailed()))
                        {
                            _queueEntry.incrementDeliveryCount();
                        }
                        _link.getEndpoint().settle(_deliveryTag);
                    }

                    public void onRollback()
                    {
                        if(Boolean.TRUE.equals(settled))
                        {
                            final Modified modified = new Modified();
                            modified.setDeliveryFailed(true);
                            _link.getEndpoint().updateDisposition(_deliveryTag, modified, true);
                            _link.getEndpoint().sendFlowConditional();
                        }
                    }
                });
            }

            return (transactionId == null && outcome != null);
        }
    }

    private class DoNothingAction implements UnsettledAction
    {
        public DoNothingAction(final Binary tag,
                               final MessageInstance queueEntry)
        {
        }

        public boolean process(final DeliveryState state, final Boolean settled)
        {
            Binary transactionId = null;
            Outcome outcome = null;
            // If disposition is settled this overrides the txn?
            if(state instanceof TransactionalState)
            {
                transactionId = ((TransactionalState)state).getTxnId();
                outcome = ((TransactionalState)state).getOutcome();
            }
            else if (state instanceof Outcome)
            {
                outcome = (Outcome) state;
            }
            return true;
        }
    }

    @Override
    public AMQSessionModel getSessionModel()
    {
        return getSession();
    }

    @Override
    public void acquisitionRemoved(final MessageInstance node)
    {
    }

    @Override
    public void consumerAdded(final ConsumerImpl sub)
    {
        _consumer = sub;
    }

    @Override
    public void consumerRemoved(final ConsumerImpl sub)
    {
        close();
    }

    @Override
    public long getUnacknowledgedBytes()
    {
        // TODO
        return 0;
    }

    @Override
    public long getUnacknowledgedMessages()
    {
        // TODO
        return 0;
    }

    @Override
    protected void processClosed()
    {

    }

    @Override
    protected void processStateChanged()
    {
        synchronized (_link.getLock())
        {
            if(_queueEmpty)
            {
                _queueEmpty = false;

                if(_link.drained())
                {
                    updateState(State.ACTIVE, State.SUSPENDED);
                }
            }
        }
    }

    @Override
    protected boolean hasStateChanged()
    {
        synchronized (_link.getLock())
        {
            return _queueEmpty;
        }
    }

    @Override
    protected boolean hasClosed()
    {
        return false;
    }

    @Override
    public String toString()
    {
        return "ConsumerTarget_1_0[linkSession=" + _link.getSession().toLogString() + "]";
    }
}
