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
package org.apache.qpid.server.jmx;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.security.AccessControlContext;
import java.security.AccessController;
import java.security.PrivilegedActionException;
import java.security.PrivilegedExceptionAction;
import java.util.Arrays;

import javax.management.Attribute;
import javax.management.JMException;
import javax.management.MBeanException;
import javax.management.MBeanInfo;
import javax.management.MBeanOperationInfo;
import javax.management.MBeanServer;
import javax.management.ObjectName;
import javax.management.OperationsException;
import javax.management.RuntimeErrorException;
import javax.management.RuntimeMBeanException;
import javax.management.remote.MBeanServerForwarder;
import javax.security.auth.Subject;

import org.apache.qpid.server.configuration.IllegalConfigurationException;
import org.apache.qpid.server.model.AbstractConfiguredObject;
import org.apache.qpid.server.model.IllegalStateTransitionException;
import org.apache.qpid.server.model.IntegrityViolationException;
import org.apache.qpid.server.util.ConnectionScopedRuntimeException;
import org.apache.qpid.server.util.ServerScopedRuntimeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.qpid.server.configuration.BrokerProperties;
import org.apache.qpid.server.model.Broker;
import org.apache.qpid.server.model.VirtualHost;
import org.apache.qpid.server.security.SecurityManager;
import org.apache.qpid.server.security.access.Operation;
import org.apache.qpid.server.security.auth.AuthenticatedPrincipal;

/**
 * This class can be used by the JMXConnectorServer as an InvocationHandler for the mbean operations. It delegates
 * JMX access decisions to the SecurityPlugin.
 */
public class MBeanInvocationHandlerImpl implements InvocationHandler
{
    private static final Logger _logger = LoggerFactory.getLogger(MBeanInvocationHandlerImpl.class);

    private final static String DELEGATE = "JMImplementation:type=MBeanServerDelegate";
    private final Thread.UncaughtExceptionHandler _uncaughtExceptionHandler;
    private MBeanServer _mbs;

    private final boolean _managementRightsInferAllAccess;
    private final Broker<?> _broker;

    MBeanInvocationHandlerImpl(Broker<?> broker)
    {
        _managementRightsInferAllAccess = Boolean.valueOf(System.getProperty(BrokerProperties.PROPERTY_MANAGEMENT_RIGHTS_INFER_ALL_ACCESS, "true"));
        _broker = broker;
        _uncaughtExceptionHandler = Thread.getDefaultUncaughtExceptionHandler();
        if (_uncaughtExceptionHandler == null)
        {
            throw new IllegalStateException("no uncaught exception handler set");
        }
    }

    public static MBeanServerForwarder newProxyInstance(Broker<?> broker)
    {
        final InvocationHandler handler = new MBeanInvocationHandlerImpl(broker);
        final Class<?>[] interfaces = new Class[] { MBeanServerForwarder.class };

        Object proxy = Proxy.newProxyInstance(MBeanServerForwarder.class.getClassLoader(), interfaces, handler);
        return MBeanServerForwarder.class.cast(proxy);
    }

    private boolean invokeDirectly(String methodName, Object[] args, Subject subject)
    {
        // Allow operations performed locally on behalf of the connector server itself
        if (subject == null)
        {
            return true;
        }

        if (args == null || DELEGATE.equals(args[0]))
        {
            return true;
        }

        // Allow querying available object names and mbeans
        if (methodName.equals("queryNames") || methodName.equals("queryMBeans"))
        {
            return true;
        }

        if (args[0] instanceof ObjectName)
        {
            ObjectName mbean = (ObjectName) args[0];

            if(!ManagedObject.DOMAIN.equalsIgnoreCase(mbean.getDomain()))
            {
                return true;
            }
        }

        return false;
    }

    public Object invoke(final Object proxy, final Method method, final Object[] args) throws Throwable
    {
        final String methodName = method.getName();

        if (methodName.equals("getMBeanServer"))
        {
            return _mbs;
        }

        if (methodName.equals("setMBeanServer"))
        {
            if (args[0] == null)
            {
                throw new IllegalArgumentException("Null MBeanServer");
            }
            if (_mbs != null)
            {
                throw new IllegalArgumentException("MBeanServer object already initialized");
            }
            _mbs = (MBeanServer) args[0];
            return null;
        }

        // Restrict access to "createMBean" and "unregisterMBean" to any user
        if (methodName.equals("createMBean") || methodName.equals("unregisterMBean"))
        {
            _logger.debug("User trying to create or unregister an MBean");
            throw new SecurityException("Access denied: " + methodName);
        }

        // Retrieve Subject from current AccessControlContext
        AccessControlContext acc = AccessController.getContext();
        Subject subject = Subject.getSubject(acc);

        try
        {
            if(invokeDirectly(methodName, args, subject))
            {
                return method.invoke(_mbs, args);
            }

            try
            {
                AuthenticatedPrincipal.getAuthenticatedPrincipalFromSubject(subject);
            }
            catch(Exception e)
            {
                throw new SecurityException("Access denied: no authenticated principal", e);
            }

            return authoriseAndInvoke(method, args);
        }
        catch (InvocationTargetException e)
        {
            handleTargetException(method, args, e.getCause());
            throw e.getCause();
        }
    }

    private void handleTargetException(Method method, Object[] args, Throwable originalException)
    {
        Throwable t = originalException;
        String argsAsString = Arrays.toString(args);

        // Unwrap the underlying from the special javax.management exception types
        if (originalException instanceof RuntimeErrorException || originalException instanceof RuntimeMBeanException)
        {
            t = originalException.getCause();
        }

        if (t instanceof ConnectionScopedRuntimeException ||
            t instanceof AbstractConfiguredObject.DuplicateIdException ||
            t instanceof AbstractConfiguredObject.DuplicateNameException ||
            t instanceof IntegrityViolationException ||
            t instanceof IllegalStateTransitionException ||
            t instanceof OperationsException ||
            t instanceof MBeanException)
        {
            if (_logger.isDebugEnabled())
            {
                _logger.debug("Exception was thrown on invoking of {} with arguments {}",
                              method, argsAsString, t);
            }
            else
            {
                _logger.info("Exception was thrown on invoking of {} with arguments {} : {}",
                             method, argsAsString, t.getMessage());
            }
        }
        else
        {
            _logger.error("Unexpected exception was thrown on invoking of {} with arguments {}",
                          method, argsAsString, t);
        }

        if (t instanceof Error || t instanceof ServerScopedRuntimeException)
        {
            _uncaughtExceptionHandler.uncaughtException(Thread.currentThread(), t);
        }
    }

    private Object authoriseAndInvoke(final Method method, final Object[] args) throws Exception
    {
        String methodName;
        // Get the component, type and impact, which may be null
        String type = getType(method, args);
        String virtualHostName = getVirtualHost(method, args);
        int impact = getImpact(method, args);

        if (virtualHostName != null)
        {
            VirtualHost<?,?,?> virtualHost = _broker.findVirtualHostByName(virtualHostName);
            if (virtualHost == null)
            {
                throw new IllegalArgumentException("Virtual host with name '" + virtualHostName + "' is not found.");
            }
        }

        methodName = getMethodName(method, args);
        Operation operation = (isAccessMethod(methodName) || impact == MBeanOperationInfo.INFO) ? Operation.ACCESS : Operation.UPDATE;

        SecurityManager security = _broker.getSecurityManager();
        security.authoriseMethod(operation, type, methodName, virtualHostName);

        if (_managementRightsInferAllAccess)
        {
            try
            {
                return Subject.doAs(SecurityManager.getSubjectWithAddedSystemRights(), new PrivilegedExceptionAction<Object>()
                {
                    @Override
                    public Object run() throws IllegalAccessException, InvocationTargetException
                    {
                        return method.invoke(_mbs, args);
                    }
                });
            }
            catch (PrivilegedActionException e)
            {
                throw (Exception) e.getCause();
            }
        }
        else
        {
            return method.invoke(_mbs, args);
        }

    }

    private String getType(Method method, Object[] args)
    {
        if (args[0] instanceof ObjectName)
        {
            ObjectName object = (ObjectName) args[0];
            String type = object.getKeyProperty("type");

            return type;
        }
        return null;
    }

    private String getVirtualHost(Method method, Object[] args)
    {
        if (args[0] instanceof ObjectName)
        {
            ObjectName object = (ObjectName) args[0];
            String vhost = object.getKeyProperty("VirtualHost");

            if(vhost != null)
            {
                try
                {
                    //if the name is quoted in the ObjectName, unquote it
                    vhost = ObjectName.unquote(vhost);
                }
                catch(IllegalArgumentException e)
                {
                    //ignore, this just means the name is not quoted
                    //and can be left unchanged
                }
            }

            return vhost;
        }
        return null;
    }

    private String getMethodName(Method method, Object[] args)
    {
        String methodName = method.getName();

        // if arguments are set, try and work out real method name
        if (args != null && args.length >= 1 && args[0] instanceof ObjectName)
        {
            if (methodName.equals("getAttribute"))
            {
                methodName = "get" + (String) args[1];
            }
            else if (methodName.equals("setAttribute"))
            {
                methodName = "set" + ((Attribute) args[1]).getName();
            }
            else if (methodName.equals("invoke"))
            {
                methodName = (String) args[1];
            }
        }

        return methodName;
    }

    private int getImpact(Method method, Object[] args)
    {
        //handle invocation of other methods on mbeans
        if ((args[0] instanceof ObjectName) && (method.getName().equals("invoke")))
        {
            //get invoked method name
            String mbeanMethod = (args.length > 1) ? (String) args[1] : null;
            if (mbeanMethod == null)
            {
                return -1;
            }

            try
            {
                //Get the impact attribute
                MBeanInfo mbeanInfo = _mbs.getMBeanInfo((ObjectName) args[0]);
                if (mbeanInfo != null)
                {
                    MBeanOperationInfo[] opInfos = mbeanInfo.getOperations();
                    for (MBeanOperationInfo opInfo : opInfos)
                    {
                        if (opInfo.getName().equals(mbeanMethod))
                        {
                            return opInfo.getImpact();
                        }
                    }
                }
            }
            catch (JMException ex)
            {
                _logger.error("Unable to determine mbean impact for method : " + mbeanMethod, ex);
            }
        }

        return -1;
    }

    private boolean isAccessMethod(String methodName)
    {
        //handle standard get/query/is methods from MBeanServer
        return (methodName.startsWith("query") || methodName.startsWith("get") || methodName.startsWith("is"));
    }

}

