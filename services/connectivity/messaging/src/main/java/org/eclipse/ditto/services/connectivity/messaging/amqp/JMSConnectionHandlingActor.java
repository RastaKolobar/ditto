/*
 * Copyright (c) 2017 Contributors to the Eclipse Foundation
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information regarding copyright ownership.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.eclipse.ditto.services.connectivity.messaging.amqp;

import static org.eclipse.ditto.model.base.common.ConditionChecker.checkNotNull;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import javax.annotation.Nullable;
import javax.jms.Destination;
import javax.jms.ExceptionListener;
import javax.jms.JMSException;
import javax.jms.MessageConsumer;
import javax.jms.Session;
import javax.naming.NamingException;

import org.apache.qpid.jms.JmsConnection;
import org.apache.qpid.jms.JmsQueue;
import org.eclipse.ditto.model.connectivity.Connection;
import org.eclipse.ditto.model.connectivity.Source;
import org.eclipse.ditto.services.connectivity.messaging.internal.ImmutableConnectionFailure;
import org.eclipse.ditto.services.utils.akka.LogUtil;
import org.eclipse.ditto.signals.commands.connectivity.exceptions.ConnectionFailedException;

import akka.actor.AbstractActor;
import akka.actor.ActorRef;
import akka.actor.ActorSystem;
import akka.actor.Props;
import akka.actor.Status;
import akka.dispatch.MessageDispatcher;
import akka.event.DiagnosticLoggingAdapter;

/**
 * This actor executes operations (connect/disconnect) on JMS Connection/Session. It is separated into an actor
 * because the JMS Client is blocking which makes it impossible to e.g. cancel a pending connection attempts with
 * another actor message when done in the same actor.
 * <p>
 * WARNING: This actor blocks! Start with its own dispatcher!
 * </p>
 */
public final class JMSConnectionHandlingActor extends AbstractActor {

    /**
     * The Actor name prefix.
     */
    static final String ACTOR_NAME_PREFIX = "jmsConnectionHandling-";

    /**
     * Config key of the dispatcher for this actor.
     */
    private static final String DISPATCHER_NAME = "jms-connection-handling-dispatcher";

    private final DiagnosticLoggingAdapter log = LogUtil.obtain(this);

    private final Connection connection;
    private final ExceptionListener exceptionListener;
    private final JmsConnectionFactory jmsConnectionFactory;

    @Nullable private Session currentSession = null;

    @SuppressWarnings("unused")
    private JMSConnectionHandlingActor(final Connection connection, final ExceptionListener exceptionListener,
            final JmsConnectionFactory jmsConnectionFactory) {

        this.connection = checkNotNull(connection, "connection");
        this.exceptionListener = exceptionListener;
        this.jmsConnectionFactory = jmsConnectionFactory;
    }

    /**
     * Creates Akka configuration object {@link Props} for this {@code JMSConnectionHandlingActor}.
     *
     * @param connection the connection
     * @param exceptionListener the exception listener
     * @param jmsConnectionFactory the jms connection factory
     * @return the Akka configuration Props object.
     */
    static Props props(final Connection connection, final ExceptionListener exceptionListener,
            final JmsConnectionFactory jmsConnectionFactory) {

        return Props.create(JMSConnectionHandlingActor.class, connection, exceptionListener, jmsConnectionFactory);
    }

    static Props propsWithOwnDispatcher(final Connection connection, final ExceptionListener exceptionListener,
            final JmsConnectionFactory jmsConnectionFactory) {
        return props(connection, exceptionListener, jmsConnectionFactory)
                .withDispatcher(DISPATCHER_NAME);
    }

    /**
     * Get dispatcher of this actor, which should be good for blocking operations.
     *
     * @param actorSystem actor system where this actor is configured.
     * @return the dispatcher.
     */
    static MessageDispatcher getOwnDispatcher(final ActorSystem actorSystem) {
        return actorSystem.dispatchers().lookup(DISPATCHER_NAME);
    }

    @Override
    public Receive createReceive() {
        return receiveBuilder()
                .match(AmqpClientActor.JmsConnect.class, this::handleConnect)
                .match(AmqpClientActor.JmsRecoverSession.class, this::handleRecoverSession)
                .match(AmqpClientActor.JmsCloseSession.class, this::handleCloseSession)
                .match(AmqpClientActor.JmsDisconnect.class, this::handleDisconnect)
                .match(AmqpConsumerActor.CreateMessageConsumer.class, this::createMessageConsumer)
                .build();
    }

    private void createMessageConsumer(final AmqpConsumerActor.CreateMessageConsumer command) {
        final Throwable error;
        if (currentSession != null) {
            // create required consumer
            final ConsumerData consumerData = command.getConsumerData();
            final ConsumerData newConsumerData =
                    createJmsConsumer(currentSession, new HashMap<>(), consumerData.getSource(),
                            consumerData.getAddress(), consumerData.getAddressWithIndex());
            if (newConsumerData != null) {
                final Object response = command.toResponse(newConsumerData.getMessageConsumer());
                getSender().tell(response, getSelf());
                error = null;
            } else {
                error = new IllegalStateException("Failed to create message consumer");
            }
        } else {
            error = new IllegalStateException("No session");
        }
        if (error != null) {
            getSender().tell(new Status.Failure(error), getSelf());
        }
    }

    private void handleCloseSession(final AmqpClientActor.JmsCloseSession closeSession) {
        log.debug("Processing JmsCloseSession message.");
        final Session session = closeSession.getSession();
        try {
            safelyExecuteJmsOperation(null, "close session", () -> {
                session.close();
                return null;
            });
            if (Objects.equals(session, currentSession)) {
                currentSession = null;
            }
        } catch (final Exception e) {
            log.debug("Closing session failed: {}", e.getMessage());
        }
    }

    private void handleRecoverSession(final AmqpClientActor.JmsRecoverSession recoverSession) {

        log.debug("Processing JmsRecoverSession message.");
        final ActorRef sender = getSender();
        final ActorRef origin = recoverSession.getOrigin().orElse(null);
        final ActorRef self = getSelf();

        // try to close an existing session first
        recoverSession.getSession().ifPresent((session) -> {
            try {
                session.close();
            } catch (final JMSException e) {
                log.debug("Failed to close previous session, ignore.");
            }
        });

        final Optional<javax.jms.Connection> connectionOptional = recoverSession.getConnection();

        if (connectionOptional.isPresent()) {
            final JmsConnection jmsConnection = (JmsConnection) connectionOptional.get();
            try {
                log.debug("Creating new JMS session.");
                final Session session = createSession(jmsConnection);
                log.debug("Creating consumers for new session.");
                final List<ConsumerData> consumers = createConsumers(session);
                final AmqpClientActor.JmsSessionRecovered r =
                        new AmqpClientActor.JmsSessionRecovered(origin, session, consumers);
                sender.tell(r, self);
                log.debug("Session of connection <{}> recovered successfully.", this.connection.getId());
            } catch (final ConnectionFailedException e) {
                sender.tell(new ImmutableConnectionFailure(origin, e, e.getMessage()), self);
                log.warning(e.getMessage());
            } catch (final Exception e) {
                sender.tell(new ImmutableConnectionFailure(origin, e, e.getMessage()), self);
                log.error("Unexpected error: {}", e.getMessage());
            }
        } else {
            log.info("Recovering session failed, no connection available.");
            sender.tell(new ImmutableConnectionFailure(origin, null,
                    "Session recovery failed, no connection available."), self);
        }
    }

    private void handleConnect(final AmqpClientActor.JmsConnect connect) {
        maybeConnectAndTell(getSender(), connect.getOrigin().orElse(null));
    }

    private void handleDisconnect(final AmqpClientActor.JmsDisconnect disconnect) {
        final Optional<javax.jms.Connection> connectionOpt = disconnect.getConnection();
        if (connectionOpt.isPresent()) {
            disconnectAndTell(connectionOpt.get(), disconnect.getOrigin().orElse(null));
        } else {
            final Object answer = new AmqpClientActor.JmsDisconnected(disconnect.getOrigin().orElse(null));
            getSender().tell(answer, getSelf());
        }
    }

    /*
     * This method should be thread-safe.
     */
    private void maybeConnectAndTell(final ActorRef sender, @Nullable final ActorRef origin) {
        final ActorRef self = getSelf(); // getSelf() is thread-safe
        try {
            final AmqpClientActor.JmsConnected connectedMessage = tryConnect(origin);
            sender.tell(connectedMessage, self);
            log.debug("Connection <{}> established successfully.", connection.getId());
        } catch (final ConnectionFailedException e) {
            sender.tell(new ImmutableConnectionFailure(origin, e, e.getMessage()), self);
            log.warning(e.getMessage());
        } catch (final Exception e) {
            sender.tell(new ImmutableConnectionFailure(origin, e, e.getMessage()), self);
            log.error("Unexpected error: {}", e.getMessage());
        }
    }

    private AmqpClientActor.JmsConnected tryConnect(@Nullable final ActorRef origin) {
        final JmsConnection jmsConnection = createJmsConnection();
        try {
            startConnection(jmsConnection);
            final Session session = createSession(jmsConnection);
            final List<ConsumerData> consumers = createConsumers(session);
            return new AmqpClientActor.JmsConnected(origin, jmsConnection, session, consumers);
        } catch (final ConnectionFailedException e) {
            // thrown by createConsumers
            terminateConnection(jmsConnection);
            throw e;
        } catch (final RuntimeException e) {
            log.error(e, "An unexpected exception occurred. Terminating JMS connection.");
            terminateConnection(jmsConnection);
            throw e;
        }
    }

    private void startConnection(final JmsConnection jmsConnection) {
        safelyExecuteJmsOperation(jmsConnection, "connect JMS client", () -> {
            jmsConnection.start();
            log.debug("Connection started successfully");
            return null;
        });
    }

    private Session createSession(final JmsConnection jmsConnection) {
        final Session session = safelyExecuteJmsOperation(jmsConnection, "create session",
                () -> (jmsConnection.createSession(Session.CLIENT_ACKNOWLEDGE)));
        currentSession = session;
        return session;
    }

    private <T> T safelyExecuteJmsOperation(@Nullable final JmsConnection jmsConnection,
            final String task, final ThrowingSupplier<T> jmsOperation) {
        try {
            return jmsOperation.get();
        } catch (final JMSException | NamingException e) {
            terminateConnection(jmsConnection);
            throw ConnectionFailedException.newBuilder(connection.getId())
                    .message("Failed to " + task + ":" + e.getMessage())
                    .cause(e)
                    .build();
        }
    }

    /**
     * Uses the given session to create the specified count of message consumers for every sources addresses.
     *
     * @param session the session
     * @return the consumers
     * @throws org.eclipse.ditto.signals.commands.connectivity.exceptions.ConnectionFailedException if creation of one
     * or more consumers failed
     */
    private List<ConsumerData> createConsumers(final Session session) {
        final Map<String, Exception> failedSources = new HashMap<>();
        final List<ConsumerData> consumers = connection.getSources().stream().flatMap(source ->
                source.getAddresses().stream().flatMap(sourceAddress ->
                        IntStream.range(0, source.getConsumerCount())
                                .mapToObj(i -> sourceAddress + "-" + i)
                                .map(addressWithIndex -> createJmsConsumer(session, failedSources, source,
                                        sourceAddress, addressWithIndex))
                                .filter(Objects::nonNull)
                                .collect(Collectors.toList()).stream()
                ).collect(Collectors.toList()).stream()
        ).collect(Collectors.toList());

        if (!failedSources.isEmpty()) {
            throw buildConnectionFailedException(failedSources);
        }
        return consumers;
    }

    @Nullable
    private ConsumerData createJmsConsumer(final Session session, final Map<String, Exception> failedSources,
            final Source source, final String sourceAddress, final String addressWithIndex) {
        log.debug("Creating AMQP Consumer for <{}>", addressWithIndex);
        final Destination destination = new JmsQueue(sourceAddress);
        final MessageConsumer messageConsumer;
        try {
            messageConsumer = session.createConsumer(destination);
            return ConsumerData.of(source, sourceAddress, addressWithIndex, messageConsumer);
        } catch (final JMSException jmsException) {
            failedSources.put(addressWithIndex, jmsException);
            return null;
        }
    }


    /**
     * @return The JmsConnection
     */
    private JmsConnection createJmsConnection() {
        return safelyExecuteJmsOperation(null, "create JMS connection", () -> {
            if (log.isDebugEnabled()) {
                log.debug("Attempt to create connection {} for URI [{}]", connection.getId(),
                        ConnectionBasedJmsConnectionFactory.buildAmqpConnectionUriFromConnection(connection));
            }
            return jmsConnectionFactory.createConnection(connection, exceptionListener);
        });
    }

    private ConnectionFailedException buildConnectionFailedException(final Map<String, Exception> failedSources) {
        return ConnectionFailedException
                .newBuilder(connection.getId())
                .message("Failed to consume sources: " + failedSources.keySet())
                .description(() -> failedSources.entrySet()
                        .stream()
                        .map(e -> e.getKey() + ": " + e.getValue().getMessage())
                        .collect(Collectors.joining(", ")))
                .build();
    }

    private void terminateConnection(@Nullable final javax.jms.Connection jmsConnection) {
        if (jmsConnection != null) {
            try {
                jmsConnection.stop();
            } catch (final JMSException e) {
                log.debug("Stopping connection <{}> failed, probably it was already stopped: {}",
                        this.connection.getId(), e.getMessage());
            }
            try {
                jmsConnection.close();
            } catch (final JMSException e) {
                log.debug("Closing connection <{}> failed, probably it was already closed: {}",
                        this.connection.getId(), e.getMessage());
            }
        }
    }

    private void disconnectAndTell(final javax.jms.Connection connection, @Nullable final ActorRef origin) {
        log.debug("Closing JMS connection {}", this.connection.getId());
        terminateConnection(connection);
        log.info("Connection <{}> closed.", this.connection.getId());

        getSender().tell(new AmqpClientActor.JmsDisconnected(origin), getSelf());
    }


    /**
     * Supplier that may throw a {@link JMSException} or {@link NamingException}.
     *
     * @param <T> Type of supplied values.
     */
    @FunctionalInterface
    public interface ThrowingSupplier<T> {

        /**
         * Try to obtain a value.
         *
         * @return the value.
         * @throws JMSException if the supplier throws a {@link JMSException}.
         * @throws NamingException if the identifier of connection could not be found in the Context.
         */
        T get() throws JMSException, NamingException;
    }
}
