/*
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
 */

package org.apache.qpid.server.transport;

import java.io.IOException;
import java.net.SocketAddress;
import java.nio.channels.CancelledKeyException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


class SelectorThread extends Thread
{
    private static final Logger LOGGER = LoggerFactory.getLogger(SelectorThread.class);

    static final String IO_THREAD_NAME_PREFIX  = "IO-";
    private final Queue<Runnable> _tasks = new ConcurrentLinkedQueue<>();

    private final AtomicBoolean _closed = new AtomicBoolean();
    private final NetworkConnectionScheduler _scheduler;

    private final BlockingQueue<Runnable> _workQueue = new LinkedBlockingQueue<>();
    private final  AtomicInteger _nextSelectorTaskIndex = new AtomicInteger();

    public final class SelectionTask implements Runnable
    {
        private final Selector _selector;
        private final AtomicBoolean _selecting = new AtomicBoolean();
        private final AtomicBoolean _inSelect = new AtomicBoolean();
        private final AtomicInteger _wakeups = new AtomicInteger();
        private long _nextTimeout;

        /**
         * Queue of connections that are not currently scheduled and not registered with the selector.
         * These need to go back into the Selector.
         */
        private final Queue<NonBlockingConnection> _unregisteredConnections = new ConcurrentLinkedQueue<>();

        /** Set of connections that are currently being selected upon */
        private final Set<NonBlockingConnection> _unscheduledConnections = new HashSet<>();



        private SelectionTask() throws IOException
        {
            _selector = Selector.open();
        }

        @Override
        public void run()
        {
            performSelect();
        }

        public boolean acquireSelecting()
        {
            return _selecting.compareAndSet(false,true);
        }

        public void clearSelecting()
        {
            _selecting.set(false);
        }

        public Selector getSelector()
        {
            return _selector;
        }

        public Queue<NonBlockingConnection> getUnregisteredConnections()
        {
            return _unregisteredConnections;
        }

        public Set<NonBlockingConnection> getUnscheduledConnections()
        {
            return _unscheduledConnections;
        }

        private List<NonBlockingConnection> processUnscheduledConnections()
        {
            _nextTimeout = Integer.MAX_VALUE;
            if (getUnscheduledConnections().isEmpty())
            {
                return Collections.emptyList();
            }

            List<NonBlockingConnection> toBeScheduled = new ArrayList<>();

            long currentTime = System.currentTimeMillis();
            Iterator<NonBlockingConnection> iterator = getUnscheduledConnections().iterator();
            while (iterator.hasNext())
            {
                NonBlockingConnection connection = iterator.next();

                int period = connection.getTicker().getTimeToNextTick(currentTime);

                if (period <= 0 || connection.isStateChanged())
                {
                    toBeScheduled.add(connection);
                    try
                    {
                        connection.getSocketChannel().register(_selector, 0);
                    }
                    catch (ClosedChannelException | CancelledKeyException e)
                    {
                        LOGGER.debug("Failed to register with selector for connection " + connection +
                                     ". Connection is probably being closed by peer.", e);
                    }
                    iterator.remove();
                }
                else
                {
                    _nextTimeout = Math.min(period, _nextTimeout);
                }
            }

            return toBeScheduled;
        }

        private List<NonBlockingConnection> processSelectionKeys()
        {
            Set<SelectionKey> selectionKeys = _selector.selectedKeys();
            if (selectionKeys.isEmpty())
            {
                return Collections.emptyList();
            }

            List<NonBlockingConnection> toBeScheduled = new ArrayList<>();
            for (SelectionKey key : selectionKeys)
            {
                if(key.isAcceptable())
                {
                    final NonBlockingNetworkTransport transport = (NonBlockingNetworkTransport) key.attachment();
                    final ServerSocketChannel channel = (ServerSocketChannel) key.channel();
                    final SocketAddress localSocketAddress = channel.socket().getLocalSocketAddress();

                    try
                    {
                        channel.register(_selector, 0);
                    }
                    catch (ClosedChannelException e)
                    {
                        LOGGER.error("Failed to register selector on accepting port {} ",
                                     localSocketAddress, e);
                    }

                    _workQueue.add(new Runnable()
                    {
                        @Override
                        public void run()
                        {
                            try
                            {
                                _scheduler.incrementRunningCount();
                                transport.acceptSocketChannel(channel);
                            }
                            finally
                            {
                                try
                                {
                                    channel.register(_selector, SelectionKey.OP_ACCEPT, transport);
                                    wakeup();
                                }
                                catch (ClosedChannelException e)
                                {
                                    LOGGER.error("Failed to register selector on accepting port {}",
                                                 localSocketAddress, e);
                                }
                                finally
                                {
                                    _scheduler.decrementRunningCount();
                                }
                            }
                        }
                    });
                }
                else
                {
                    NonBlockingConnection connection = (NonBlockingConnection) key.attachment();
                    if(connection != null)
                    {
                        try
                        {
                            key.channel().register(_selector, 0);
                        }
                        catch (ClosedChannelException e)
                        {
                            // Ignore - we will schedule the connection anyway
                        }

                        toBeScheduled.add(connection);
                        getUnscheduledConnections().remove(connection);
                    }
                }

            }
            selectionKeys.clear();

            return toBeScheduled;
        }

        private List<NonBlockingConnection> reregisterUnregisteredConnections()
        {
            if (getUnregisteredConnections().isEmpty())
            {
                return Collections.emptyList();
            }
            List<NonBlockingConnection> unregisterableConnections = new ArrayList<>();

            NonBlockingConnection unregisteredConnection;
            while ((unregisteredConnection = getUnregisteredConnections().poll()) != null)
            {
                getUnscheduledConnections().add(unregisteredConnection);


                final int ops = (unregisteredConnection.wantsRead() ? SelectionKey.OP_READ : 0)
                                | (unregisteredConnection.wantsWrite() ? SelectionKey.OP_WRITE : 0);
                try
                {
                    unregisteredConnection.getSocketChannel().register(_selector, ops, unregisteredConnection);
                }
                catch (ClosedChannelException e)
                {
                    unregisterableConnections.add(unregisteredConnection);
                }
            }

            return unregisterableConnections;
        }

        private void performSelect()
        {
            _scheduler.incrementRunningCount();
            try
            {
                while (!_closed.get())
                {
                    if (acquireSelecting())
                    {
                        List<ConnectionProcessor> connections = new ArrayList<>();
                        try
                        {
                            if (!_closed.get())
                            {
                                Thread.currentThread().setName("Selector-" + _scheduler.getName());
                                _inSelect.set(true);
                                try
                                {
                                    if (_wakeups.getAndSet(0) > 0)
                                    {
                                        _selector.selectNow();
                                    }
                                    else
                                    {
                                        _selector.select(_nextTimeout);
                                    }
                                }
                                catch (IOException e)
                                {
                                    // TODO Inform the model object
                                    LOGGER.error("Failed to trying to select()", e);
                                    closeSelector();
                                    return;
                                }
                                finally
                                {
                                    _inSelect.set(false);
                                }
                                runTasks();
                                for (NonBlockingConnection connection : processSelectionKeys())
                                {
                                    if (connection.setScheduled())
                                    {
                                        connections.add(new ConnectionProcessor(_scheduler, connection));
                                    }
                                }
                                for (NonBlockingConnection connection : reregisterUnregisteredConnections())
                                {
                                    if (connection.setScheduled())
                                    {
                                        connections.add(new ConnectionProcessor(_scheduler, connection));
                                    }
                                }
                                for (NonBlockingConnection connection : processUnscheduledConnections())
                                {
                                    if (connection.setScheduled())
                                    {
                                        connections.add(new ConnectionProcessor(_scheduler, connection));
                                    }
                                }

                            }
                        }
                        finally
                        {
                            clearSelecting();
                        }
                        _workQueue.addAll(connections);
                        _workQueue.add(this);
                        for (ConnectionProcessor connectionProcessor : connections)
                        {
                            connectionProcessor.processConnection();
                        }

                    }
                    else
                    {
                        break;
                    }
                }

                if (_closed.get() && acquireSelecting())
                {
                    closeSelector();
                }
            }
            finally
            {
                _scheduler.decrementRunningCount();
            }
        }

        private void closeSelector()
        {
            try
            {
                if(_selector.isOpen())
                {
                    _selector.close();
                }
            }
            catch (IOException e)

            {
                LOGGER.debug("Failed to close selector", e);
            }
        }

        public void wakeup()
        {
            _wakeups.compareAndSet(0, 1);
            if(_inSelect.get() && _wakeups.get() != 0)
            {
                _selector.wakeup();
            }
        }
    }

    private SelectionTask[] _selectionTasks;

    SelectorThread(final NetworkConnectionScheduler scheduler, final int numberOfSelectors) throws IOException
    {
        _scheduler = scheduler;
        _selectionTasks = new SelectionTask[numberOfSelectors];
        for(int i = 0; i < numberOfSelectors; i++)
        {
            _selectionTasks[i] = new SelectionTask();
        }
        for(SelectionTask task : _selectionTasks)
        {
            _workQueue.add(task);
        }
    }

    public void addAcceptingSocket(final ServerSocketChannel socketChannel,
                                   final NonBlockingNetworkTransport nonBlockingNetworkTransport)
    {
        _tasks.add(new Runnable()
        {
            @Override
            public void run()
            {

                try
                {
                    if (LOGGER.isDebugEnabled())
                    {
                        LOGGER.debug("Registering selector on accepting port {} ",
                                     socketChannel.socket().getLocalSocketAddress());
                    }
                    socketChannel.register(_selectionTasks[0].getSelector(), SelectionKey.OP_ACCEPT, nonBlockingNetworkTransport);
                }
                catch (IllegalStateException | ClosedChannelException e)
                {
                    // TODO Communicate condition back to model object to make it go into the ERROR state
                    LOGGER.error("Failed to register selector on accepting port {} ",
                                 socketChannel.socket().getLocalSocketAddress(), e);
                }
            }
        });
        _selectionTasks[0].wakeup();
    }

    public void cancelAcceptingSocket(final ServerSocketChannel socketChannel)
    {
        _tasks.add(new Runnable()
        {
            @Override
            public void run()
            {
                SelectionKey selectionKey = socketChannel.keyFor(_selectionTasks[0].getSelector());
                if (selectionKey != null)
                {
                    selectionKey.cancel();
                }
            }
        });
        _selectionTasks[0].wakeup();
    }

    @Override
    public void run()
    {

        final String name = Thread.currentThread().getName();
        try
        {
            do
            {
                Thread.currentThread().setName(name);
                Runnable task = _workQueue.take();
                task.run();

            } while (!_closed.get());
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
        }

    }

    private static final class ConnectionProcessor implements Runnable
    {

        private final NetworkConnectionScheduler _scheduler;
        private final NonBlockingConnection _connection;
        private AtomicBoolean _running = new AtomicBoolean();

        public ConnectionProcessor(final NetworkConnectionScheduler scheduler, final NonBlockingConnection connection)
        {
            _scheduler = scheduler;
            _connection = connection;
        }

        @Override
        public void run()
        {
            _scheduler.incrementRunningCount();
            try
            {
                processConnection();
            }
            finally
            {
                _scheduler.decrementRunningCount();
            }
        }

        public void processConnection()
        {
            if (_running.compareAndSet(false, true))
            {
                _scheduler.processConnection(_connection);
            }
        }
    }

    private void unregisterConnection(final NonBlockingConnection connection) throws ClosedChannelException
    {
        SelectionKey register = connection.getSocketChannel().register(connection.getSelectionTask().getSelector(), 0);
        register.cancel();
    }

    private void runTasks()
    {
        while(_tasks.peek() != null)
        {
            Runnable task = _tasks.poll();
            task.run();
        }
    }

    private boolean selectionInterestRequiresUpdate(NonBlockingConnection connection)
    {
        SelectionTask selectionTask = connection.getSelectionTask();
        if(selectionTask != null)
        {
            final SelectionKey selectionKey = connection.getSocketChannel().keyFor(selectionTask.getSelector());
            int expectedOps = (connection.wantsRead() ? SelectionKey.OP_READ : 0)
                              | (connection.wantsWrite() ? SelectionKey.OP_WRITE : 0);

            try
            {
                return selectionKey == null || !selectionKey.isValid() || selectionKey.interestOps() != expectedOps;
            }
            catch (CancelledKeyException e)
            {
                return true;
            }
        }
        else
        {
            return true;
        }
    }

    public void addConnection(final NonBlockingConnection connection)
    {
        if(selectionInterestRequiresUpdate(connection))
        {
            SelectionTask selectionTask = getNextSelectionTask();
            connection.setSelectionTask(selectionTask);
            selectionTask.getUnregisteredConnections().add(connection);
            selectionTask.wakeup();
        }

    }

    public void returnConnectionToSelector(final NonBlockingConnection connection)
    {
        if(selectionInterestRequiresUpdate(connection))
        {
            SelectionTask selectionTask = connection.getSelectionTask();
            if(selectionTask == null)
            {
                throw new IllegalStateException("returnConnectionToSelector should only be called with connections that are currently assigned a selector task");
            }
            selectionTask.getUnregisteredConnections().add(connection);
            selectionTask.wakeup();
        }

    }

    private SelectionTask getNextSelectionTask()
    {
        int index;
        do
        {
            index = _nextSelectorTaskIndex.get();
        }
        while(!_nextSelectorTaskIndex.compareAndSet(index, (index + 1) % _selectionTasks.length));
        return _selectionTasks[index];
    }

    void removeConnection(NonBlockingConnection connection)
    {
        try
        {
            unregisterConnection(connection);
        }
        catch (ClosedChannelException e)
        {
            LOGGER.debug("Failed to unregister with selector for connection " + connection +
                         ". Connection is probably being closed by peer.", e);

        }
    }

    public void close()
    {
        Runnable goodNight = new Runnable()
        {
            @Override
            public void run()
            {
                // Make sure take awakes so it can observe _closed
            }
        };
        _closed.set(true);

        int count = _scheduler.getPoolSize();
        while(count-- > 0)
        {
            _workQueue.offer(goodNight);
        }

        for(SelectionTask task : _selectionTasks)
        {
            task.wakeup();
        }

    }

     public void addToWork(final NonBlockingConnection connection)
     {
         if(connection.setScheduled())
         {
             _workQueue.add(new ConnectionProcessor(_scheduler, connection));
         }
         SelectionTask selectionTask = connection.getSelectionTask();
         if (selectionTask != null)
         {
             selectionTask.wakeup();
         }
     }
}
