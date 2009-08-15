/*
 * JBoss, Home of Professional Open Source
 * Copyright 2005-2008, Red Hat Middleware LLC, and individual contributors
 * by the @authors tag. See the copyright.txt in the distribution for a
 * full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */

package org.jboss.messaging.jms.client;

import java.lang.ref.WeakReference;
import java.util.HashSet;
import java.util.Set;

import javax.jms.Connection;
import javax.jms.ConnectionConsumer;
import javax.jms.ConnectionMetaData;
import javax.jms.Destination;
import javax.jms.ExceptionListener;
import javax.jms.IllegalStateException;
import javax.jms.JMSException;
import javax.jms.Queue;
import javax.jms.QueueConnection;
import javax.jms.QueueSession;
import javax.jms.ServerSessionPool;
import javax.jms.Session;
import javax.jms.Topic;
import javax.jms.TopicConnection;
import javax.jms.TopicSession;
import javax.jms.XAConnection;
import javax.jms.XAQueueConnection;
import javax.jms.XAQueueSession;
import javax.jms.XASession;
import javax.jms.XATopicConnection;
import javax.jms.XATopicSession;

import org.jboss.messaging.core.client.ClientSession;
import org.jboss.messaging.core.client.ClientSessionFactory;
import org.jboss.messaging.core.exception.MessagingException;
import org.jboss.messaging.core.logging.Logger;
import org.jboss.messaging.core.remoting.FailureListener;
import org.jboss.messaging.core.version.Version;
import org.jboss.messaging.utils.SimpleString;
import org.jboss.messaging.utils.UUIDGenerator;
import org.jboss.messaging.utils.VersionLoader;

/**
 * @author <a href="mailto:ovidiu@feodorov.com">Ovidiu Feodorov</a>
 * @author <a href="mailto:tim.fox@jboss.com">Tim Fox</a>
 * @author <a href="mailto:ataylor@redhat.com">Andy Taylor</a>
 * @version <tt>$Revision$</tt>
 *          <p/>
 *          $Id$
 */
public class JBossConnection implements Connection, QueueConnection, TopicConnection, XAConnection, XAQueueConnection,
         XATopicConnection
{
   // Constants ------------------------------------------------------------------------------------

   private static final Logger log = Logger.getLogger(JBossConnection.class);

   public static final int TYPE_GENERIC_CONNECTION = 0;

   public static final int TYPE_QUEUE_CONNECTION = 1;

   public static final int TYPE_TOPIC_CONNECTION = 2;

   public static final SimpleString CONNECTION_ID_PROPERTY_NAME = new SimpleString("__JBM_CID");

   // Static ---------------------------------------------------------------------------------------

   // Attributes -----------------------------------------------------------------------------------

   private final int connectionType;

   private final Set<JBossSession> sessions = new org.jboss.messaging.utils.ConcurrentHashSet<JBossSession>();

   private final Set<SimpleString> tempQueues = new org.jboss.messaging.utils.ConcurrentHashSet<SimpleString>();

   private volatile boolean hasNoLocal;

   private volatile ExceptionListener exceptionListener;

   private volatile boolean justCreated = true;

   private volatile ConnectionMetaData metaData;

   private volatile boolean closed;

   private volatile boolean started;

   private String clientID;

   private final ClientSessionFactory sessionFactory;

   private final SimpleString uid;

   private final String username;

   private final String password;

   private final FailureListener listener = new JMSFailureListener(this);

   private final Version thisVersion;

   private final int dupsOKBatchSize;

   private final int transactionBatchSize;

   private ClientSession initialSession;

   // Constructors ---------------------------------------------------------------------------------

   public JBossConnection(final String username,
                          final String password,
                          final int connectionType,
                          final String clientID,
                          final int dupsOKBatchSize,
                          final int transactionBatchSize,
                          final ClientSessionFactory sessionFactory)
   {
      this.username = username;

      this.password = password;

      this.connectionType = connectionType;

      this.clientID = clientID;

      this.sessionFactory = sessionFactory;

      uid = UUIDGenerator.getInstance().generateSimpleStringUUID();

      thisVersion = VersionLoader.getVersion();

      this.dupsOKBatchSize = dupsOKBatchSize;

      this.transactionBatchSize = transactionBatchSize;
   }

   // Connection implementation --------------------------------------------------------------------

   public Session createSession(final boolean transacted, final int acknowledgeMode) throws JMSException
   {
      checkClosed();

      return createSessionInternal(transacted, acknowledgeMode, false, TYPE_GENERIC_CONNECTION);
   }

   public String getClientID() throws JMSException
   {
      checkClosed();

      justCreated = false;

      return clientID;
   }

   public void setClientID(final String clientID) throws JMSException
   {
      checkClosed();

      if (this.clientID != null)
      {
         throw new IllegalStateException("Client id has already been set");
      }

      if (!justCreated)
      {
         throw new IllegalStateException("setClientID can only be called directly after the connection is created");
      }

      this.clientID = clientID;

      justCreated = false;
   }

   public ConnectionMetaData getMetaData() throws JMSException
   {
      checkClosed();

      justCreated = false;

      if (metaData == null)
      {
         metaData = new JBossConnectionMetaData(thisVersion);
      }

      return metaData;
   }

   public ExceptionListener getExceptionListener() throws JMSException
   {
      checkClosed();

      justCreated = false;

      return exceptionListener;
   }

   public void setExceptionListener(final ExceptionListener listener) throws JMSException
   {
      checkClosed();

      exceptionListener = listener;
      justCreated = false;
   }

   public void start() throws JMSException
   {
      checkClosed();

      for (JBossSession session : sessions)
      {
         session.start();
      }

      justCreated = false;
      started = true;
   }

   public void stop() throws JMSException
   {
      checkClosed();

      for (JBossSession session : sessions)
      {
         session.stop();
      }

      justCreated = false;
      started = false;
   }

   public synchronized void close() throws JMSException
   {
      if (closed)
      {
         return;
      }

      try
      {
         for (JBossSession session : new HashSet<JBossSession>(sessions))
         {
            session.close();
         }

         // TODO may be a better way of doing this that doesn't involve creating a new session

         try
         {
            if (!tempQueues.isEmpty())
            {
               if (initialSession == null)
               {
                  initialSession = sessionFactory.createSession(username, password, false, true, true, false, 0);
               }

               // Remove any temporary queues

               for (SimpleString queueName : tempQueues)
               {
                  initialSession.deleteQueue(queueName);
               }
            }
         }
         finally
         {
            if (initialSession != null)
            {
               initialSession.close();
            }
         }

         closed = true;
      }
      catch (MessagingException e)
      {
         throw JMSExceptionHelper.convertFromMessagingException(e);
      }
   }

   public ConnectionConsumer createConnectionConsumer(final Destination destination,
                                                      final String messageSelector,
                                                      final ServerSessionPool sessionPool,
                                                      final int maxMessages) throws JMSException
   {
      checkClosed();
      return null;
   }

   public ConnectionConsumer createDurableConnectionConsumer(final Topic topic,
                                                             final String subscriptionName,
                                                             final String messageSelector,
                                                             final ServerSessionPool sessionPool,
                                                             final int maxMessages) throws JMSException
   {
      checkClosed();
      // As spec. section 4.11
      if (connectionType == TYPE_QUEUE_CONNECTION)
      {
         String msg = "Cannot create a durable connection consumer on a QueueConnection";
         throw new javax.jms.IllegalStateException(msg);
      }

      // TODO
      return null;
   }

   // QueueConnection implementation ---------------------------------------------------------------

   public QueueSession createQueueSession(final boolean transacted, final int acknowledgeMode) throws JMSException
   {
      checkClosed();
      return createSessionInternal(transacted, acknowledgeMode, false, JBossSession.TYPE_QUEUE_SESSION);
   }

   public ConnectionConsumer createConnectionConsumer(final Queue queue,
                                                      final String messageSelector,
                                                      final ServerSessionPool sessionPool,
                                                      final int maxMessages) throws JMSException
   {
      checkClosed();

      return null;
   }

   // TopicConnection implementation ---------------------------------------------------------------

   public TopicSession createTopicSession(final boolean transacted, final int acknowledgeMode) throws JMSException
   {
      checkClosed();
      return createSessionInternal(transacted, acknowledgeMode, false, JBossSession.TYPE_TOPIC_SESSION);
   }

   public ConnectionConsumer createConnectionConsumer(final Topic topic,
                                                      final String messageSelector,
                                                      final ServerSessionPool sessionPool,
                                                      final int maxMessages) throws JMSException
   {
      checkClosed();

      return null;
   }

   // XAConnection implementation ------------------------------------------------------------------

   public XASession createXASession() throws JMSException
   {
      checkClosed();
      return createSessionInternal(true, Session.SESSION_TRANSACTED, true, JBossSession.TYPE_GENERIC_SESSION);
   }

   // XAQueueConnection implementation -------------------------------------------------------------

   public XAQueueSession createXAQueueSession() throws JMSException
   {
      checkClosed();
      return createSessionInternal(true, Session.SESSION_TRANSACTED, true, JBossSession.TYPE_QUEUE_SESSION);

   }

   // XATopicConnection implementation -------------------------------------------------------------

   public XATopicSession createXATopicSession() throws JMSException
   {
      checkClosed();
      return createSessionInternal(true, Session.SESSION_TRANSACTED, true, JBossSession.TYPE_TOPIC_SESSION);

   }

   // Public ---------------------------------------------------------------------------------------

   public void addTemporaryQueue(final SimpleString queueAddress)
   {
      tempQueues.add(queueAddress);
   }

   public void removeTemporaryQueue(final SimpleString queueAddress)
   {
      tempQueues.remove(queueAddress);
   }

   public boolean containsTemporaryQueue(final SimpleString queueAddress)
   {
      return tempQueues.contains(queueAddress);
   }

   public boolean hasNoLocal()
   {
      return hasNoLocal;
   }

   public void setHasNoLocal()
   {
      this.hasNoLocal = true;
   }

   public SimpleString getUID()
   {
      return uid;
   }

   public void removeSession(final JBossSession session)
   {
      sessions.remove(session);
   }

   public ClientSession getInitialSession()
   {
      return initialSession;
   }

   // Package protected ----------------------------------------------------------------------------

   // Protected ------------------------------------------------------------------------------------

   // In case the user forgets to close the connection manually

   protected void finalize() throws Throwable
   {
      if (!closed)
      {
         log.warn("I'm closing a connection you left open. Please make sure you close all connections explicitly " +
                  "before letting them go out of scope!");
         
         close();
      }
   }

   protected JBossSession createSessionInternal(final boolean transacted,
                                                int acknowledgeMode,
                                                final boolean isXA,
                                                final int type) throws JMSException
   {
      if (transacted)
      {
         acknowledgeMode = Session.SESSION_TRANSACTED;
      }

      try
      {
         ClientSession session;

         if (acknowledgeMode == Session.SESSION_TRANSACTED)
         {
            session = sessionFactory.createSession(username, password, isXA, false, false, false, transactionBatchSize);
         }
         else if (acknowledgeMode == Session.AUTO_ACKNOWLEDGE)
         {
            session = sessionFactory.createSession(username, password, isXA, true, true, false, 0);
         }
         else if (acknowledgeMode == Session.DUPS_OK_ACKNOWLEDGE)
         {
            session = sessionFactory.createSession(username, password, isXA, true, true, false, dupsOKBatchSize);
         }
         else if (acknowledgeMode == Session.CLIENT_ACKNOWLEDGE)
         {
            session = sessionFactory.createSession(username, password, isXA, true, false, false, transactionBatchSize);
         }
         else if (acknowledgeMode == JBossSession.PRE_ACKNOWLEDGE)
         {
            session = sessionFactory.createSession(username, password, isXA, true, false, true, transactionBatchSize);
         }
         else
         {
            throw new IllegalArgumentException("Invalid ackmode: " + acknowledgeMode);
         }

         justCreated = false;

         // Setting multiple times on different sessions doesn't matter since RemotingConnection maintains
         // a set (no duplicates)
         session.addFailureListener(listener);

         JBossSession jbs = new JBossSession(this, transacted, isXA, acknowledgeMode, session, type);

         sessions.add(jbs);

         if (started)
         {
            session.start();
         }

         return jbs;
      }
      catch (MessagingException e)
      {
         throw JMSExceptionHelper.convertFromMessagingException(e);
      }
   }

   // Private --------------------------------------------------------------------------------------

   private void checkClosed() throws JMSException
   {
      if (closed)
      {
         throw new IllegalStateException("Connection is closed");
      }
   }

   public void authorize() throws JMSException
   {
      try
      {
         initialSession = sessionFactory.createSession(username, password, false, false, false, false, 0);

         initialSession.addFailureListener(listener);
      }
      catch (MessagingException me)
      {
         throw JMSExceptionHelper.convertFromMessagingException(me);
      }
   }

   // Inner classes --------------------------------------------------------------------------------

   private static class JMSFailureListener implements FailureListener
   {
      private WeakReference<JBossConnection> connectionRef;
      
      JMSFailureListener(final JBossConnection connection)
      {
         connectionRef = new WeakReference<JBossConnection>(connection);
      }
      
      public synchronized void connectionFailed(final MessagingException me)
      {
         if (me == null)
         {
            return;
         }
         
         JBossConnection conn = connectionRef.get();
         
         if (conn != null)
         {
            try
            {
               final ExceptionListener exceptionListener = conn.getExceptionListener();
               
               if (exceptionListener != null)
               {
                  final JMSException je = new JMSException(me.toString());
      
                  je.initCause(me);
      
                  new Thread(new Runnable()
                  {
                     public void run()
                     {
                        exceptionListener.onException(je);
                     }
                  }).start();
               }
            }
            catch (JMSException e)
            {
               log.error("Failed to get exception listener");
            }
         }
      }

   }
}
