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
package org.apache.qpid.server.model;

import java.io.FileReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.MalformedURLException;
import java.net.URL;
import java.security.PrivilegedAction;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import javax.security.auth.Subject;

import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.qpid.server.configuration.IllegalConfigurationException;
import org.apache.qpid.server.configuration.store.ManagementModeStoreHandler;
import org.apache.qpid.server.configuration.updater.TaskExecutor;
import org.apache.qpid.server.logging.CompositeStartupMessageLogger;
import org.apache.qpid.server.logging.EventLogger;
import org.apache.qpid.server.logging.MessageLogger;
import org.apache.qpid.server.logging.SystemOutMessageLogger;
import org.apache.qpid.server.logging.messages.BrokerMessages;
import org.apache.qpid.server.store.BrokerStoreUpgraderAndRecoverer;
import org.apache.qpid.server.store.ConfiguredObjectRecord;
import org.apache.qpid.server.store.ConfiguredObjectRecordConverter;
import org.apache.qpid.server.store.DurableConfigurationStore;
import org.apache.qpid.server.util.ServerScopedRuntimeException;

public abstract class AbstractSystemConfig<X extends SystemConfig<X>>
        extends AbstractConfiguredObject<X> implements SystemConfig<X>
{
    private static final Logger LOGGER = LoggerFactory.getLogger(AbstractSystemConfig.class);

    private static final UUID SYSTEM_ID = new UUID(0l, 0l);
    private static final long SHUTDOWN_TIMEOUT = 30000l;
    private final EventLogger _eventLogger;

    private volatile DurableConfigurationStore _configurationStore;

    @ManagedAttributeField
    private boolean _managementMode;

    @ManagedAttributeField
    private int _managementModeRmiPortOverride;

    @ManagedAttributeField
    private int _managementModeJmxPortOverride;

    @ManagedAttributeField
    private int _managementModeHttpPortOverride;

    @ManagedAttributeField
    private boolean _managementModeQuiesceVirtualHosts;

    @ManagedAttributeField
    private String _managementModePassword;

    @ManagedAttributeField
    private String _initialConfigurationLocation;

    @ManagedAttributeField
    private boolean _startupLoggedToSystemOut;

    private final Thread _shutdownHook = new Thread(new ShutdownService(), "QpidBrokerShutdownHook");

    public AbstractSystemConfig(final TaskExecutor taskExecutor,
                                final EventLogger eventLogger,
                                final Map<String, Object> attributes)
    {
        super(parentsMap(),
              updateAttributes(attributes),
              taskExecutor, BrokerModel.getInstance());
        _eventLogger = eventLogger;
        getTaskExecutor().start();
    }

    private static Map<String, Object> updateAttributes(Map<String, Object> attributes)
    {
        attributes = new HashMap<>(attributes);
        attributes.put(ConfiguredObject.NAME, "System");
        attributes.put(ID, SYSTEM_ID);
        return attributes;
    }

    @Override
    protected void setState(final State desiredState)
    {
        throw new IllegalArgumentException("Cannot change the state of the SystemContext object");
    }

    @Override
    public EventLogger getEventLogger()
    {
        return _eventLogger;
    }

    @Override
    protected void onClose()
    {
        final TaskExecutor taskExecutor = getTaskExecutor();
        try
        {
            try
            {
                boolean removed = Runtime.getRuntime().removeShutdownHook(_shutdownHook);
                LOGGER.debug("Removed shutdown hook : {}", removed);
            }
            catch(IllegalStateException ise)
            {
                //ignore, means the JVM is already shutting down
            }

            if (taskExecutor != null)
            {
                taskExecutor.stop();
            }

            if (_configurationStore != null)
            {
                _configurationStore.closeConfigurationStore();
            }

        }
        finally
        {
            if (taskExecutor != null)
            {
                taskExecutor.stopImmediately();
            }
        }

    }

    @Override
    public Broker getBroker()
    {
        Collection<Broker> children = getChildren(Broker.class);
        if(children == null || children.isEmpty())
        {
            return null;
        }
        else if(children.size() != 1)
        {
            throw new IllegalConfigurationException("More than one broker has been registered in a single context");
        }
        return children.iterator().next();
    }

    @Override
    protected void onOpen()
    {
        super.onOpen();

        Runtime.getRuntime().addShutdownHook(_shutdownHook);
        LOGGER.debug("Added shutdown hook");

        _configurationStore = createStoreObject();

        if (isManagementMode())
        {
            _configurationStore = new ManagementModeStoreHandler(_configurationStore, this);
        }

        try
        {
            _configurationStore.openConfigurationStore(this,
                                                       false,
                                                       convertToConfigurationRecords(getInitialConfigurationLocation(),
                                                                                     this));
            _configurationStore.upgradeStoreStructure();
        }
        catch (IOException e)
        {
            throw new IllegalArgumentException(e);
        }
    }

    @StateTransition(currentState = State.UNINITIALIZED, desiredState = State.ACTIVE)
    protected ListenableFuture<Void> activate()
    {
        final EventLogger eventLogger = _eventLogger;

        final EventLogger startupLogger;
        if (isStartupLoggedToSystemOut())
        {
            //Create the composite (logging+SystemOut MessageLogger to be used during startup
            MessageLogger[] messageLoggers = {new SystemOutMessageLogger(), eventLogger.getMessageLogger()};

            CompositeStartupMessageLogger startupMessageLogger = new CompositeStartupMessageLogger(messageLoggers);
            startupLogger = new EventLogger(startupMessageLogger);
        }
        else
        {
            startupLogger = eventLogger;
        }


        BrokerStoreUpgraderAndRecoverer upgrader = new BrokerStoreUpgraderAndRecoverer(this);
        upgrader.perform();

        final Broker broker = getBroker();

        broker.setEventLogger(startupLogger);
        final SettableFuture<Void> returnVal = SettableFuture.create();
        Futures.addCallback(broker.openAsync(), new FutureCallback()
                            {
                                @Override
                                public void onSuccess(final Object result)
                                {
                                    State state = broker.getState();
                                    if (state == State.ACTIVE)
                                    {
                                        startupLogger.message(BrokerMessages.READY());
                                        broker.setEventLogger(eventLogger);
                                        returnVal.set(null);
                                    }
                                    else
                                    {
                                        returnVal.setException(new ServerScopedRuntimeException("Broker failed reach ACTIVE state (state is " + state + ")"));
                                    }
                                }

                                @Override
                                public void onFailure(final Throwable t)
                                {
                                    returnVal.setException(t);
                                }
                            }, getTaskExecutor()
                           );

        return returnVal;
    }

    @Override
    protected final boolean rethrowRuntimeExceptionsOnOpen()
    {
        return true;
    }

    protected abstract DurableConfigurationStore createStoreObject();

    @Override
    public DurableConfigurationStore getConfigurationStore()
    {
        return _configurationStore;
    }

    private ConfiguredObjectRecord[] convertToConfigurationRecords(final String initialConfigurationLocation,
                                                                   final SystemConfig systemConfig) throws IOException
    {
        ConfiguredObjectRecordConverter converter = new ConfiguredObjectRecordConverter(BrokerModel.getInstance());

        Reader reader;

        try
        {
            URL url = new URL(initialConfigurationLocation);
            reader = new InputStreamReader(url.openStream());
        }
        catch (MalformedURLException e)
        {
            reader = new FileReader(initialConfigurationLocation);
        }

        try
        {
            Collection<ConfiguredObjectRecord> records =
                    converter.readFromJson(org.apache.qpid.server.model.Broker.class,
                                           systemConfig, reader);
            return records.toArray(new ConfiguredObjectRecord[records.size()]);
        }
        finally
        {
            reader.close();
        }


    }

    @Override
    public boolean isManagementMode()
    {
        return _managementMode;
    }

    @Override
    public int getManagementModeRmiPortOverride()
    {
        return _managementModeRmiPortOverride;
    }

    @Override
    public int getManagementModeJmxPortOverride()
    {
        return _managementModeJmxPortOverride;
    }

    @Override
    public int getManagementModeHttpPortOverride()
    {
        return _managementModeHttpPortOverride;
    }

    @Override
    public boolean isManagementModeQuiesceVirtualHosts()
    {
        return _managementModeQuiesceVirtualHosts;
    }

    @Override
    public String getManagementModePassword()
    {
        return _managementModePassword;
    }

    @Override
    public String getInitialConfigurationLocation()
    {
        return _initialConfigurationLocation;
    }

    @Override
    public boolean isStartupLoggedToSystemOut()
    {
        return _startupLoggedToSystemOut;
    }

    private class ShutdownService implements Runnable
    {
        public void run()
        {
            Subject.doAs(org.apache.qpid.server.security.SecurityManager.getSystemTaskSubject("Shutdown"),
                         new PrivilegedAction<Object>()
                         {
                             @Override
                             public Object run()
                             {
                                 LOGGER.debug("Shutdown hook initiating close");
                                 ListenableFuture<Void> closeResult = closeAsync();
                                 try
                                 {
                                     closeResult.get(SHUTDOWN_TIMEOUT, TimeUnit.MILLISECONDS);
                                 }
                                 catch (InterruptedException | ExecutionException  | TimeoutException e)
                                 {
                                     LOGGER.warn("Attempting to cleanly shutdown took too long, exiting immediately", e);
                                 }
                                 return null;
                             }
                         });
        }
    }

}
