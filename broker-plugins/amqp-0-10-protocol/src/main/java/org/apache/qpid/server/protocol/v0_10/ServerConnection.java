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
package org.apache.qpid.server.protocol.v0_10;

import static org.apache.qpid.transport.Connection.State.CLOSING;

import java.net.SocketAddress;
import java.security.AccessControlContext;
import java.security.AccessController;
import java.security.Principal;
import java.security.PrivilegedAction;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

import javax.security.auth.Subject;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.qpid.protocol.AMQConstant;
import org.apache.qpid.server.protocol.ConnectionClosingTicker;
import org.apache.qpid.server.logging.EventLogger;
import org.apache.qpid.server.logging.messages.ConnectionMessages;
import org.apache.qpid.server.model.Broker;
import org.apache.qpid.server.model.Transport;
import org.apache.qpid.server.model.port.AmqpPort;
import org.apache.qpid.server.protocol.AMQSessionModel;
import org.apache.qpid.server.security.AuthorizationHolder;
import org.apache.qpid.server.security.auth.AuthenticatedPrincipal;
import org.apache.qpid.server.util.Action;
import org.apache.qpid.server.util.ServerScopedRuntimeException;
import org.apache.qpid.server.virtualhost.VirtualHostImpl;
import org.apache.qpid.transport.Connection;
import org.apache.qpid.transport.ConnectionClose;
import org.apache.qpid.transport.ConnectionCloseCode;
import org.apache.qpid.transport.ConnectionCloseOk;
import org.apache.qpid.transport.ExecutionErrorCode;
import org.apache.qpid.transport.ExecutionException;
import org.apache.qpid.transport.Method;
import org.apache.qpid.transport.Option;
import org.apache.qpid.transport.ProtocolEvent;
import org.apache.qpid.transport.Session;

public class ServerConnection extends Connection implements AuthorizationHolder
{
    private static final Logger LOGGER = LoggerFactory.getLogger(ServerConnection.class);
    public static final long CLOSE_OK_TIMEOUT = 10000l;
    private final Broker<?> _broker;

    private Principal _authorizedPrincipal = null;
    private final long _connectionId;
    private final Object _reference = new Object();
    private VirtualHostImpl<?,?,?> _virtualHost;
    private final AmqpPort<?> _port;
    private final AtomicLong _lastIoTime = new AtomicLong();
    private boolean _blocking;
    private final Transport _transport;

    private final Queue<Action<? super ServerConnection>> _asyncTaskList =
            new ConcurrentLinkedQueue<>();

    private int _messageCompressionThreshold;
    private final int _maxMessageSize;

    private AMQPConnection_0_10 _amqpConnection;
    private boolean _ignoreFutureInput;
    private boolean _ignoreAllButConnectionCloseOk;

    public ServerConnection(final long connectionId,
                            Broker<?> broker,
                            final AmqpPort<?> port,
                            final Transport transport)
    {
        _connectionId = connectionId;
        _broker = broker;

        _port = port;
        _transport = transport;

        int maxMessageSize = port.getContextValue(Integer.class, AmqpPort.PORT_MAX_MESSAGE_SIZE);
        _maxMessageSize = (maxMessageSize > 0) ? maxMessageSize : Integer.MAX_VALUE;

    }

    public Object getReference()
    {
        return _reference;
    }

    public Broker<?> getBroker()
    {
        return _broker;
    }

    @Override
    protected void invoke(Method method)
    {
        super.invoke(method);
        if (method instanceof ConnectionClose)
        {
            _ignoreAllButConnectionCloseOk = true;
        }
    }

    EventLogger getEventLogger()
    {
        return _virtualHost == null ? _broker.getEventLogger() : _virtualHost.getEventLogger();
    }

    @Override
    protected void setState(State state)
    {
        super.setState(state);

        if (state == State.OPEN)
        {
            _amqpConnection.logConnectionOpen();
            _amqpConnection.virtualHostAssociated();
        }

        if(state == State.CLOSING)
        {
            getAmqpConnection().getAggregateTicker().addTicker(new ConnectionClosingTicker(System.currentTimeMillis() + CLOSE_OK_TIMEOUT, getNetworkConnection()));
        }
    }

    @Override
    public ServerConnectionDelegate getConnectionDelegate()
    {
        return (ServerConnectionDelegate) super.getConnectionDelegate();
    }

    public AMQPConnection_0_10 getAmqpConnection()
    {
        return _amqpConnection;
    }

    public void setAmqpConnection(final AMQPConnection_0_10 serverProtocolEngine)
    {
        _amqpConnection = serverProtocolEngine;
    }

    public VirtualHostImpl<?,?,?> getVirtualHost()
    {
        return _virtualHost;
    }

    public void setVirtualHost(VirtualHostImpl<?,?,?> virtualHost)
    {
        _virtualHost = virtualHost;
        _messageCompressionThreshold =
                virtualHost.getContextValue(Integer.class,
                                            Broker.MESSAGE_COMPRESSION_THRESHOLD_SIZE);

        if(_messageCompressionThreshold <= 0)
        {
            _messageCompressionThreshold = Integer.MAX_VALUE;
        }
        _amqpConnection.getSubject().getPrincipals().add(_virtualHost.getPrincipal());
        _amqpConnection.updateAccessControllerContext();
    }

    public AmqpPort<?> getPort()
    {
        return _port;
    }

    public Transport getTransport()
    {
        return _transport;
    }

    public void closeSessionAsync(final ServerSession session, final AMQConstant cause, final String message)
    {
        addAsyncTask(new Action<ServerConnection>()
        {

            @Override
            public void performAction(final ServerConnection conn)
            {
                if(!session.isClosing())
                {
                    ExecutionException ex = new ExecutionException();
                    ExecutionErrorCode code = ExecutionErrorCode.INTERNAL_ERROR;
                    try
                    {
                        code = ExecutionErrorCode.get(cause.getCode());
                    }
                    catch (IllegalArgumentException iae)
                    {
                        // Ignore, already set to INTERNAL_ERROR
                    }
                    ex.setErrorCode(code);
                    ex.setDescription(message);
                    session.invoke(ex);

                    session.close(cause, message);
                }
            }
        });

    }

    @Override
    public void exception(final Throwable t)
    {
        try
        {
            super.exception(t);
        }
        finally
        {
            if(t instanceof Error)
            {
                throw (Error) t;
            }
            if(t instanceof ServerScopedRuntimeException)
            {
                throw (ServerScopedRuntimeException) t;
            }
        }
    }

    @Override
    public void received(final ProtocolEvent event)
    {
        _lastIoTime.set(System.currentTimeMillis());
        AccessControlContext context;
        if (event.isConnectionControl())
        {
            context = _amqpConnection.getAccessControllerContext();
        }
        else
        {
            ServerSession channel = (ServerSession) getSession(event.getChannel());
            if (channel != null)
            {
                context = channel.getAccessControllerContext();
            }
            else
            {
                context = _amqpConnection.getAccessControllerContext();
            }
        }

        if(!_ignoreAllButConnectionCloseOk || (event instanceof ConnectionCloseOk))
        {
            AccessController.doPrivileged(new PrivilegedAction<Void>()
            {
                @Override
                public Void run()
                {
                    ServerConnection.super.received(event);
                    return null;
                }
            }, context);
        }
    }

    public void sendConnectionCloseAsync(final AMQConstant cause, final String message)
    {
        addAsyncTask(new Action<ServerConnection>()
        {
            @Override
            public void performAction(final ServerConnection object)
            {
                if(!isClosing())
                {
                    markAllSessionsClosed();

                    setState(CLOSING);
                    ConnectionCloseCode replyCode = ConnectionCloseCode.NORMAL;
                    try
                    {
                        replyCode = ConnectionCloseCode.get(cause.getCode());
                    }
                    catch (IllegalArgumentException iae)
                    {
                        // Ignore
                    }
                    sendConnectionClose(replyCode, message);
                }
            }
        });
    }

    @Override
    protected void sendConnectionClose(final ConnectionCloseCode replyCode,
                                       final String replyText,
                                       final Option... _options)
    {
        super.sendConnectionClose(replyCode, replyText, _options);
    }

    protected void performDeleteTasks()
    {
        _amqpConnection.performDeleteTasks();
    }

    public synchronized void block()
    {
        if(!_blocking)
        {
            _blocking = true;
            for(AMQSessionModel ssn : getSessionModels())
            {
                ssn.block();
            }
        }
    }

    public synchronized void unblock()
    {
        if(_blocking)
        {
            _blocking = false;
            for(AMQSessionModel ssn : getSessionModels())
            {
                ssn.unblock();
            }
        }
    }

    @Override
    public synchronized void registerSession(final Session ssn)
    {
        super.registerSession(ssn);
        _amqpConnection.sessionAdded((ServerSession) ssn);
        if(_blocking)
        {
            ((ServerSession)ssn).block();
        }
    }

    @Override
    public synchronized void removeSession(final Session ssn)
    {
        _amqpConnection.sessionRemoved((ServerSession) ssn);
        super.removeSession(ssn);
    }

    public List<ServerSession> getSessionModels()
    {
        List<ServerSession> sessions = new ArrayList<>();
        for (Session ssn : getChannels())
        {
            sessions.add((ServerSession) ssn);
        }
        return sessions;
    }


    /**
     * @return authorizedSubject
     */
    public Subject getAuthorizedSubject()
    {
        return _amqpConnection.getSubject();
    }

    /**
     * Sets the authorized subject.  It also extracts the UsernamePrincipal from the subject
     * and caches it for optimisation purposes.
     *
     * @param authorizedSubject
     */
    public void setAuthorizedSubject(final Subject authorizedSubject)
    {
        if (authorizedSubject == null)
        {
            _authorizedPrincipal = null;
        }
        else
        {
            getAuthorizedSubject().getPrincipals().addAll(authorizedSubject.getPrincipals());
            _amqpConnection.updateAccessControllerContext();
            _authorizedPrincipal = AuthenticatedPrincipal.getAuthenticatedPrincipalFromSubject(authorizedSubject);
        }
    }

    public Principal getAuthorizedPrincipal()
    {
        return _authorizedPrincipal;
    }

    public long getConnectionId()
    {
        return _connectionId;
    }

    public String getRemoteAddressString()
    {
        return String.valueOf(getRemoteSocketAddress());
    }

    @Override
    public void closed()
    {
        if(_virtualHost != null)
        {
            _virtualHost.deregisterConnection(_amqpConnection);
        }
        performDeleteTasks();
        closeSubscriptions();
        super.closed();

        getEventLogger().message(isConnectionLost() ? ConnectionMessages.DROPPED_CONNECTION() : ConnectionMessages.CLOSE());
    }

    private void closeSubscriptions()
    {
        for (Session ssn : getChannels())
        {
            ((ServerSession)ssn).unregisterSubscriptions();
        }
    }

    private void markAllSessionsClosed()
    {
        for (Session ssn :  getChannels())
        {
            final ServerSession session = (ServerSession) ssn;
            ((ServerSession) ssn).setClose(true);
            session.closed();
        }
    }

    public void receivedComplete()
    {
        for (Session ssn : getChannels())
        {
            ((ServerSession)ssn).receivedComplete();
        }
    }

    @Override
    public void send(ProtocolEvent event)
    {
        _lastIoTime.set(System.currentTimeMillis());
        super.send(event);
    }

    public String getClientId()
    {
        return getConnectionDelegate().getClientId();
    }

    public String getRemoteContainerName()
    {
        return getConnectionDelegate().getClientId();
    }


    public String getClientVersion()
    {
        return getConnectionDelegate().getClientVersion();
    }

    public String getClientProduct()
    {
        return getConnectionDelegate().getClientProduct();
    }

    public long getSessionCountLimit()
    {
        return getChannelMax();
    }

    public Principal getPeerPrincipal()
    {
        return getNetworkConnection().getPeerPrincipal();
    }

    @Override
    public void setRemoteAddress(SocketAddress remoteAddress)
    {
        super.setRemoteAddress(remoteAddress);
    }

    @Override
    public void setLocalAddress(SocketAddress localAddress)
    {
        super.setLocalAddress(localAddress);
    }

    public void doHeartBeat()
    {
        super.doHeartBeat();
    }

    private void addAsyncTask(final Action<ServerConnection> action)
    {
        _asyncTaskList.add(action);
        notifyWork();
    }

    public int getMessageCompressionThreshold()
    {
        return _messageCompressionThreshold;
    }

    public int getMaxMessageSize()
    {
        return _maxMessageSize;
    }

    public void transportStateChanged()
    {
        for (AMQSessionModel ssn : getSessionModels())
        {
            ssn.transportStateChanged();
        }
    }

    public void notifyWork()
    {
        _amqpConnection.notifyWork();
    }

    public Iterator<Runnable> processPendingIterator()
    {
        return new ProcessPendingIterator();
    }

    private class ProcessPendingIterator implements Iterator<Runnable>
    {
        private final List<? extends AMQSessionModel<?>> _sessionsWithPending;
        private Iterator<? extends AMQSessionModel<?>> _sessionIterator;
        private ProcessPendingIterator()
        {
            _sessionsWithPending = new ArrayList<>(getSessionModels());
            _sessionIterator = _sessionsWithPending.iterator();
        }

        @Override
        public boolean hasNext()
        {
            return !(_sessionsWithPending.isEmpty() && _asyncTaskList.isEmpty());
        }

        @Override
        public Runnable next()
        {
            if(!_sessionsWithPending.isEmpty())
            {
                if(!_sessionIterator.hasNext())
                {
                    _sessionIterator = _sessionsWithPending.iterator();
                }
                final AMQSessionModel<?> session = _sessionIterator.next();
                return new Runnable()
                {
                    @Override
                    public void run()
                    {
                        if(!session.processPending())
                        {
                            _sessionIterator.remove();
                        }
                    }
                };
            }
            else if(!_asyncTaskList.isEmpty())
            {
                final Action<? super ServerConnection> asyncAction = _asyncTaskList.poll();
                return new Runnable()
                {
                    @Override
                    public void run()
                    {
                        asyncAction.performAction(ServerConnection.this);
                    }
                };
            }
            else
            {
                throw new NoSuchElementException();
            }
        }

        @Override
        public void remove()
        {
            throw new UnsupportedOperationException();
        }
    }

    public void closeAndIgnoreFutureInput()
    {
        _ignoreFutureInput = true;
        getSender().close();
    }

    public boolean isIgnoreFutureInput()
    {
        return _ignoreFutureInput;
    }
}
