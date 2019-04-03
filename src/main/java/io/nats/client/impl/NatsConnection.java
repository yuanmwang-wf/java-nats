// Copyright 2015-2018 The NATS Authors
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at:
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package io.nats.client.impl;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Predicate;

import io.nats.client.Connection;
import io.nats.client.ConnectionListener;
import io.nats.client.ConnectionListener.Events;
import io.nats.client.Consumer;
import io.nats.client.Dispatcher;
import io.nats.client.ErrorListener;
import io.nats.client.Message;
import io.nats.client.MessageHandler;
import io.nats.client.NUID;
import io.nats.client.Options;
import io.nats.client.Statistics;
import io.nats.client.Subscription;

class NatsConnection implements Connection {
    static final byte[] EMPTY_BODY = new byte[0];

    static final byte CR = 0x0D;
    static final byte LF = 0x0A;
    static final byte[] CRLF = { CR, LF };

    static final String OP_CONNECT = "CONNECT";
    static final String OP_INFO = "INFO";
    static final String OP_SUB = "SUB";
    static final String OP_PUB = "PUB";
    static final String OP_UNSUB = "UNSUB";
    static final String OP_MSG = "MSG";
    static final String OP_PING = "PING";
    static final String OP_PONG = "PONG";
    static final String OP_OK = "+OK";
    static final String OP_ERR = "-ERR";

    private Options options;

    private NatsStatistics statistics;

    private boolean connecting; // you can only connect in one thread
    private boolean disconnecting; // you can only disconnect in one thread
    private boolean closing; // respect a close call regardless
    private Exception exceptionDuringConnectChange; // an exception occurred in another thread while disconnecting or
    // connecting

    private Status status;
    private ReentrantLock statusLock;
    private Condition statusChanged;

    private CompletableFuture<DataPort> dataPortFuture;
    private DataPort dataPort;
    private String currentServerURI;
    private CompletableFuture<Boolean> reconnectWaiter;

    private NatsConnectionReader reader;
    private NatsConnectionWriter writer;

    private AtomicReference<NatsServerInfo> serverInfo;

    private Map<String, NatsSubscription> subscribers;
    private Map<String, NatsDispatcher> dispatchers; // use a concurrent map so we get more consistent iteration
    // behavior
    private Map<String, CompletableFuture<Message>> responses;
    private ConcurrentLinkedDeque<CompletableFuture<Boolean>> pongQueue;

    private String mainInbox;
    private AtomicReference<NatsDispatcher> inboxDispatcher;
    private Timer timer;

    private AtomicLong nextSid;
    private NUID nuid;

    private AtomicReference<String> lastError;
    private AtomicReference<CompletableFuture<Boolean>> draining;
    private AtomicBoolean blockPublishForDrain;

    private ExecutorService callbackRunner;
    private ExecutorService executor;
    private ExecutorService connectExecutor;

    NatsConnection(Options options) {
        this.options = options;

        this.statistics = new NatsStatistics(this.options.isTrackAdvancedStats());

        this.statusLock = new ReentrantLock();
        this.statusChanged = this.statusLock.newCondition();
        this.status = Status.DISCONNECTED;
        this.reconnectWaiter = new CompletableFuture<>();
        this.reconnectWaiter.complete(Boolean.TRUE);

        this.dispatchers = new ConcurrentHashMap<>();
        this.subscribers = new ConcurrentHashMap<>();
        this.responses = new ConcurrentHashMap<>();

        this.nextSid = new AtomicLong(1);
        this.nuid = new NUID();
        this.mainInbox = createInbox() + ".*";

        this.lastError = new AtomicReference<>();

        this.serverInfo = new AtomicReference<>();
        this.inboxDispatcher = new AtomicReference<>();
        this.pongQueue = new ConcurrentLinkedDeque<>();
        this.draining = new AtomicReference<>();
        this.blockPublishForDrain = new AtomicBoolean();

        this.reader = new NatsConnectionReader(this);
        this.writer = new NatsConnectionWriter(this);

        this.callbackRunner = Executors.newSingleThreadExecutor();
        this.connectExecutor = Executors.newSingleThreadExecutor();

        this.executor = options.getExecutor();
    }

    // Connect is only called after creation
    void connect(boolean reconnectOnConnect) throws InterruptedException, IOException {
        if (options.getServers().size() == 0) {
            throw new IllegalArgumentException("No servers provided in options");
        }

        for (String serverURI : getServers()) {

            if (isClosed()) {
                break;
            }

            updateStatus(Status.CONNECTING);

            tryToConnect(serverURI);

            if (isConnected()) {
                break;
            } else {
                updateStatus(Status.DISCONNECTED);
            }
        }

        if (!isConnected() && !isClosed()) {
            if (reconnectOnConnect) {
                reconnect();
            } else {
                close();
                throw new IOException("Unable to connect to gnatsd server.");
            }
        }
    }

    // Reconnect can only be called when the connection is disconnected
    void reconnect() throws InterruptedException {
        long maxTries = options.getMaxReconnect();
        long tries = 0;
        String lastServer = null;

        if (isClosed()) {
            return;
        }

        if (maxTries == 0) {
            this.close();
            return;
        }

        this.writer.setReconnectMode(true);

        while (!isConnected() && !isClosed() && !this.isClosing()) {
            Collection<String> serversToTry = buildReconnectList();

            for (String server : serversToTry) {
                if (isClosed()) {
                    break;
                }

                if (server.equals(lastServer)) {
                    this.reconnectWaiter = new CompletableFuture<>();
                    waitForReconnectTimeout();
                }

                if (isDisconnectingOrClosed() || this.isClosing()) {
                    break;
                }

                updateStatus(Status.RECONNECTING);

                tryToConnect(server);
                lastServer = server;
                tries++;

                if (maxTries > 0 && tries >= maxTries) {
                    break;
                } else if (isConnected()) {
                    this.statistics.incrementReconnects();
                    break;
                }
            }

            if (maxTries > 0 && tries >= maxTries) {
                break;
            }
        }

        if (!isConnected()) {
            this.close();
            return;
        }

        this.subscribers.forEach((sid, sub) -> {
            if (sub.getDispatcher() == null && !sub.isDraining()) {
                sendSubscriptionMessage(sub.getSID(), sub.getSubject(), sub.getQueueName(), true);
            }
        });

        this.dispatchers.forEach((nuid, d) -> {
            if (!d.isDraining()) {
                d.resendSubscriptions();
            }
        });

        try {
            this.flush(this.options.getConnectionTimeout());
        } catch (Exception exp) {
            this.processException(exp);
        }

        // When the flush returns we are done sending internal messages, so we can switch to the
        // non-reconnect queue
        this.writer.setReconnectMode(false);

        processConnectionEvent(Events.RESUBSCRIBED);
    }

    // is called from reconnect and connect
    // will wait for any previous attempt to complete, using the reader.stop and
    // writer.stop
    void tryToConnect(String serverURI) {
        try {
            statusLock.lock();
            try {
                if (this.connecting) {
                    return;
                }
                this.connecting = true;
                statusChanged.signalAll();
            } finally {
                statusLock.unlock();
            }

            Duration connectTimeout = options.getConnectionTimeout();

            // Create a new future for the dataport, the reader/writer will use this
            // to wait for the connect/failure.
            this.dataPortFuture = new CompletableFuture<>();

            // Make sure the reader and writer are stopped
            this.reader.stop().get();
            this.writer.stop().get();

            cleanUpPongQueue();

            DataPort newDataPort = this.options.buildDataPort();
            newDataPort.connect(serverURI, this);

            // Notify the any threads waiting on the sockets
            this.dataPort = newDataPort;
            this.dataPortFuture.complete(this.dataPort);

            // Wait for the INFO message manually
            // all other traffic will use the reader and writer
            Callable<Object> task = new Callable<Object>() {
                public Object call() throws IOException {
                    readInitialInfo();
                    checkVersionRequirements();
                    upgradeToSecureIfNeeded();
                    return null;
                }
            };

            Future<Object> future = this.connectExecutor.submit(task);
            try {
                future.get(this.options.getConnectionTimeout().toNanos(), TimeUnit.NANOSECONDS);
            } finally {
                future.cancel(true); // may or may not desire this
            }

            // start the reader and writer after we secured the connection, if necessary
            this.reader.start(this.dataPortFuture);
            this.writer.start(this.dataPortFuture);

            this.sendConnect(serverURI);
            Future<Boolean> pongFuture = sendPing();

            if (pongFuture != null) {
                pongFuture.get(connectTimeout.toNanos(), TimeUnit.NANOSECONDS);
            }

            if (this.timer == null) {
                this.timer = new Timer("Nats Connection Timer");

                long pingMillis = this.options.getPingInterval().toMillis();

                if (pingMillis > 0) {
                    this.timer.schedule(new TimerTask() {
                        public void run() {
                            if (isConnected()) {
                                softPing(); // The timer always uses the standard queue
                            }
                        }
                    }, pingMillis, pingMillis);
                }

                long cleanMillis = this.options.getRequestCleanupInterval().toMillis();

                if (cleanMillis > 0) {
                    this.timer.schedule(new TimerTask() {
                        public void run() {
                            cleanResponses(false);
                        }
                    }, cleanMillis, cleanMillis);
                }
            }

            // Set connected status
            statusLock.lock();
            try {
                this.connecting = false;

                if (this.exceptionDuringConnectChange != null) {
                    throw this.exceptionDuringConnectChange;
                }

                this.currentServerURI = serverURI;
                updateStatus(Status.CONNECTED); // will signal status change, we also signal in finally
            } finally {
                statusLock.unlock();
            }
        } catch (RuntimeException exp) { // runtime exceptions, like illegalArgs
            processException(exp);
            throw exp;
        } catch (Exception exp) { // every thing else
            processException(exp);
            try {
                this.closeSocket(false);
            } catch (InterruptedException e) {
                processException(e);
            }
        } finally {
            statusLock.lock();
            try {
                this.connecting = false;
                statusChanged.signalAll();
            } finally {
                statusLock.unlock();
            }
        }
    }

    void checkVersionRequirements() throws IOException {
        Options opts = getOptions();
        NatsServerInfo info = getInfo();

        if (opts.isNoEcho() && info.getProtocolVersion() < 1) {
            throw new IOException("Server does not support no echo.");
        }
    }

    void upgradeToSecureIfNeeded() throws IOException {
        Options opts = getOptions();
        NatsServerInfo info = getInfo();

        if (opts.isTLSRequired() && !info.isTLSRequired()) {
            throw new IOException("SSL connection wanted by client.");
        } else if (!opts.isTLSRequired() && info.isTLSRequired()) {
            throw new IOException("SSL required by server.");
        }

        if (opts.isTLSRequired()) {
            this.dataPort.upgradeToSecure();
        }
    }

    // Called from reader/writer thread
    void handleCommunicationIssue(Exception io) {
        // If we are connecting or disconnecting, note exception and leave
        statusLock.lock();
        try {
            if (this.connecting || this.disconnecting || this.status == Status.CLOSED || this.isDraining()) {
                this.exceptionDuringConnectChange = io;
                return;
            }
        } finally {
            statusLock.unlock();
        }

        processException(io);

        // Spawn a thread so we don't have timing issues with
        // waiting on read/write threads
        executor.submit(() -> {
            try {
                this.closeSocket(true);
            } catch (InterruptedException e) {
                processException(e);
            }
        });
    }

    // Close socket is called when another connect attempt is possible
    // Close is called when the connection should shutdown, period
    void closeSocket(boolean tryReconnectIfConnected) throws InterruptedException {
        boolean wasConnected = false;

        statusLock.lock();
        try {
            if (isDisconnectingOrClosed()) {
                waitForDisconnectOrClose(this.options.getConnectionTimeout());
                return;
            } else {
                this.disconnecting = true;
                this.exceptionDuringConnectChange = null;
                wasConnected = (this.status == Status.CONNECTED);
                statusChanged.signalAll();
            }
        } finally {
            statusLock.unlock();
        }

        closeSocketImpl();

        statusLock.lock();
        try {
            updateStatus(Status.DISCONNECTED);
            this.disconnecting = false;
            // Ignore exceptions thrown during closeSocketImpl
            this.exceptionDuringConnectChange = null;
            statusChanged.signalAll();
        } finally {
            statusLock.unlock();
        }

        if (isClosing()) { // Bit of a misname, but closing means we are in the close method or were asked
            // to be
            close();
        } else if (wasConnected && tryReconnectIfConnected) {
            reconnect();
        }
    }

    // Close socket is called when another connect attempt is possible
    // Close is called when the connection should shutdown, period
    public void close() throws InterruptedException {
        this.close(true);
    }

    void close(boolean checkDrainStatus) throws InterruptedException {
        statusLock.lock();
        try {
            if (checkDrainStatus && this.isDraining()) {
                waitForDisconnectOrClose(this.options.getConnectionTimeout());
                return;
            }

            this.closing = true;// We were asked to close, so do it
            if (isDisconnectingOrClosed()) {
                waitForDisconnectOrClose(this.options.getConnectionTimeout());
                return;
            } else {
                this.disconnecting = true;
                this.exceptionDuringConnectChange = null;
                statusChanged.signalAll();
            }
        } finally {
            statusLock.unlock();
        }

        // Stop the reconnect wait timer after we stop the writer/reader (only if we are
        // really closing, not on errors)
        if (this.reconnectWaiter != null) {
            this.reconnectWaiter.cancel(true);
        }

        closeSocketImpl();

        this.dispatchers.forEach((nuid, d) -> {
            d.stop(false);
        });

        this.subscribers.forEach((sid, sub) -> {
            sub.invalidate();
        });

        this.dispatchers.clear();
        this.subscribers.clear();

        if (timer != null) {
            timer.cancel();
            timer = null;
        }

        cleanResponses(true);

        cleanUpPongQueue();

        statusLock.lock();
        try {
            updateStatus(Status.CLOSED); // will signal, we also signal when we stop disconnecting

            /*
            if (exceptionDuringConnectChange != null) {
                processException(exceptionDuringConnectChange);
                exceptionDuringConnectChange = null;
            }*/
        } finally {
            statusLock.unlock();
        }

        // Stop the error handler code
        callbackRunner.shutdown();
        try {
            callbackRunner.awaitTermination(this.options.getConnectionTimeout().toNanos(), TimeUnit.NANOSECONDS);
        } finally {
            callbackRunner.shutdownNow();
        }

        statusLock.lock();
        try {
            this.disconnecting = false;
            statusChanged.signalAll();
        } finally {
            statusLock.unlock();
        }
    }

    // Should only be called from closeSocket or close
    void closeSocketImpl() {
        this.currentServerURI = null;

        // Signal the reader and writer
        this.reader.stop();
        this.writer.stop();

        // Close the current socket and cancel anyone waiting for it
        this.dataPortFuture.cancel(true);

        try {
            if (this.dataPort != null) {
                this.dataPort.close();
            }
        } catch (IOException ex) {
            processException(ex);
        }

        cleanUpPongQueue();

        // We signaled now wait for them to stop
        try {
            this.reader.stop().get(10, TimeUnit.SECONDS);
        } catch (Exception ex) {
            processException(ex);
        }
        try {
            this.writer.stop().get(10, TimeUnit.SECONDS);
        } catch (Exception ex) {
            processException(ex);
        }

    }

    void cleanUpPongQueue() {
        Future<Boolean> b;
        while ((b = pongQueue.poll()) != null) {
            try {
                b.cancel(true);
            } catch (CancellationException e) {
                if (!b.isDone() && !b.isCancelled()) {
                    processException(e);
                }
            }
        }
    }

    public void publish(String subject, byte[] body) {
        this.publish(subject, null, body);
    }

    public void publish(String subject, String replyTo, byte[] body) {

        if (isClosed()) {
            throw new IllegalStateException("Connection is Closed");
        } else if (blockPublishForDrain.get()) {
            throw new IllegalStateException("Connection is Draining"); // Ok to publish while waiting on subs
        }

        if (subject == null || subject.length() == 0) {
            throw new IllegalArgumentException("Subject is required in publish");
        }

        if (replyTo != null && replyTo.length() == 0) {
            throw new IllegalArgumentException("ReplyTo cannot be the empty string");
        }

        if (body == null) {
            body = EMPTY_BODY;
        } else if (body.length > this.getMaxPayload() && this.getMaxPayload() > 0) {
            throw new IllegalArgumentException(
                    "Message payload size exceed server configuration " + body.length + " vs " + this.getMaxPayload());
        }

        NatsMessage msg = new NatsMessage(subject, replyTo, body, options.supportUTF8Subjects());

        if ((this.status == Status.RECONNECTING || this.status == Status.DISCONNECTED)
                && !this.writer.canQueue(msg, options.getReconnectBufferSize())) {
            throw new IllegalStateException(
                    "Unable to queue any more messages during reconnect, max buffer is " + options.getReconnectBufferSize());
        }
        queueOutgoing(msg);
    }

    public Subscription subscribe(String subject) {
        if (subject == null || subject.length() == 0) {
            throw new IllegalArgumentException("Subject is required in subscribe");
        }

        return createSubscription(subject, null, null);
    }

    public Subscription subscribe(String subject, String queueName) {
        if (subject == null || subject.length() == 0) {
            throw new IllegalArgumentException("Subject is required in subscribe");
        }

        if (queueName == null || queueName.length() == 0) {
            throw new IllegalArgumentException("QueueName is required in subscribe");
        }

        return createSubscription(subject, queueName, null);
    }

    void invalidate(NatsSubscription sub) {
        CharSequence sid = sub.getSID();

        subscribers.remove(sid);

        if (sub.getNatsDispatcher() != null) {
            sub.getNatsDispatcher().remove(sub);
        }

        sub.invalidate();
    }

    void unsubscribe(NatsSubscription sub, int after) {
        if (isClosed()) { // last chance, usually sub will catch this
            throw new IllegalStateException("Connection is Closed");
        }

        if (after <= 0) {
            this.invalidate(sub); // Will clean it up
        } else {
            sub.setUnsubLimit(after);

            if (sub.reachedUnsubLimit()) {
                sub.invalidate();
            }
        }

        if (!isConnected()) {
            return;// We will setup sub on reconnect or ignore
        }

        sendUnsub(sub, after);
    }

    void sendUnsub(NatsSubscription sub, int after) {
        String sid = sub.getSID();
        StringBuilder protocolBuilder = new StringBuilder();
        protocolBuilder.append(OP_UNSUB);
        protocolBuilder.append(" ");
        protocolBuilder.append(sid);

        if (after > 0) {
            protocolBuilder.append(" ");
            protocolBuilder.append(String.valueOf(after));
        }
        NatsMessage unsubMsg = new NatsMessage(protocolBuilder.toString());
        queueInternalOutgoing(unsubMsg);
    }

    // Assumes the null/empty checks were handled elsewhere
    NatsSubscription createSubscription(String subject, String queueName, NatsDispatcher dispatcher) {
        if (isClosed()) {
            throw new IllegalStateException("Connection is Closed");
        } else if (isDraining() && (dispatcher == null || dispatcher != this.inboxDispatcher.get())) {
            throw new IllegalStateException("Connection is Draining");
        }

        NatsSubscription sub = null;
        long sidL = nextSid.getAndIncrement();
        String sid = String.valueOf(sidL);

        sub = new NatsSubscription(sid, subject, queueName, this, dispatcher);
        subscribers.put(sid, sub);

        sendSubscriptionMessage(sid, subject, queueName, false);
        return sub;
    }

    void sendSubscriptionMessage(CharSequence sid, String subject, String queueName, boolean treatAsInternal) {
        if (!isConnected()) {
            return;// We will setup sub on reconnect or ignore
        }

        StringBuilder protocolBuilder = new StringBuilder();
        protocolBuilder.append(OP_SUB);
        protocolBuilder.append(" ");
        protocolBuilder.append(subject);

        if (queueName != null) {
            protocolBuilder.append(" ");
            protocolBuilder.append(queueName);
        }

        protocolBuilder.append(" ");
        protocolBuilder.append(sid);
        NatsMessage subMsg = new NatsMessage(protocolBuilder.toString());

        if (treatAsInternal) {
            queueInternalOutgoing(subMsg);
        } else {
            queueOutgoing(subMsg);
        }
    }

    public String createInbox() {
        String prefix = options.getInboxPrefix();
        StringBuilder builder = new StringBuilder();
        builder.append(prefix);
        builder.append(this.nuid.next());
        return builder.toString();
    }

    int getRespInboxLength() {
        String prefix = options.getInboxPrefix();
        return prefix.length() + 22 + 1; // 22 for nuid, 1 for .
    }

    String createResponseInbox(String inbox) {
        StringBuilder builder = new StringBuilder();
        builder.append(inbox.substring(0, getRespInboxLength())); // Get rid of the *
        builder.append(this.nuid.next());
        return builder.toString();
    }

    // If the inbox is long enough, pull out the end part, otherwise, just use the
    // full thing
    String getResponseToken(String responseInbox) {
        int len = getRespInboxLength();
        if (responseInbox.length() <= len) {
            return responseInbox;
        }
        return responseInbox.substring(len);
    }

    void cleanResponses(boolean cancelIfRunning) {
        ArrayList<String> toRemove = new ArrayList<>();

        responses.forEach((token, f) -> {
            if (f.isDone() || cancelIfRunning) {
                try {
                    f.cancel(true); // does nothing if already done
                } catch (CancellationException e) {
                    // Expected
                }
                toRemove.add(token);
                statistics.decrementOutstandingRequests();
            }
        });

        for (String token : toRemove) {
            responses.remove(token);
        }
    }

    public Message request(String subject, byte[] body, Duration timeout) throws InterruptedException {
        Message reply = null;
        Future<Message> incoming = this.request(subject, body);
        try {
            reply = incoming.get(timeout.toNanos(), TimeUnit.NANOSECONDS);
        } catch (ExecutionException | TimeoutException e) {
            reply = null;
        }

        return reply;
    }

    public CompletableFuture<Message> request(String subject, byte[] body) {
        String responseInbox = null;
        boolean oldStyle = options.isOldRequestStyle();

        if (isClosed()) {
            throw new IllegalStateException("Connection is Closed");
        } else if (isDraining()) {
            throw new IllegalStateException("Connection is Draining");
        }

        if (subject == null || subject.length() == 0) {
            throw new IllegalArgumentException("Subject is required in publish");
        }

        if (body == null) {
            body = EMPTY_BODY;
        } else if (body.length > this.getMaxPayload() && this.getMaxPayload() > 0) {
            throw new IllegalArgumentException(
                    "Message payload size exceed server configuration " + body.length + " vs " + this.getMaxPayload());
        }

        if (inboxDispatcher.get() == null) {
            NatsDispatcher d = new NatsDispatcher(this, (msg) -> {
                deliverReply(msg);
            });

            if (inboxDispatcher.compareAndSet(null, d)) {
                String id = this.nuid.next();
                this.dispatchers.put(id, d);
                d.start(id);
                d.subscribe(this.mainInbox);
            }
        }

        if (oldStyle) {
            responseInbox = createInbox();
        } else {
            responseInbox = createResponseInbox(this.mainInbox);
        }

        String responseToken = getResponseToken(responseInbox);
        CompletableFuture<Message> future = new CompletableFuture<>();

        responses.put(responseToken, future);
        statistics.incrementOutstandingRequests();

        if (oldStyle) {
            this.inboxDispatcher.get().subscribe(responseInbox).unsubscribe(responseInbox, 1);
        }

        this.publish(subject, responseInbox, body);
        statistics.incrementRequestsSent();

        return future;
    }

    void deliverReply(Message msg) {
        String subject = msg.getSubject();
        String token = getResponseToken(subject);
        CompletableFuture<Message> f = null;

        f = responses.remove(token);

        if (f != null) {
            statistics.decrementOutstandingRequests();
            f.complete(msg);
            statistics.incrementRepliesReceived();
        }
    }

    public Dispatcher createDispatcher(MessageHandler handler) {
        if (isClosed()) {
            throw new IllegalStateException("Connection is Closed");
        } else if (isDraining()) {
            throw new IllegalStateException("Connection is Draining");
        }

        NatsDispatcher dispatcher = new NatsDispatcher(this, handler);
        String id = this.nuid.next();
        this.dispatchers.put(id, dispatcher);
        dispatcher.start(id);
        return dispatcher;
    }

    public void closeDispatcher(Dispatcher d) {
        if (isClosed()) {
            throw new IllegalStateException("Connection is Closed");
        } else if (!(d instanceof NatsDispatcher)) {
            throw new IllegalArgumentException("Connection can only manage its own dispatchers");
        }

        NatsDispatcher nd = ((NatsDispatcher) d);

        if (nd.isDraining()) {
            return; // No op while draining
        }

        if (!this.dispatchers.containsKey(nd.getId())) {
            throw new IllegalArgumentException("Dispatcher is already closed.");
        }

        cleanupDispatcher(nd);
    }

    void cleanupDispatcher(NatsDispatcher nd) {
        nd.stop(true);
        this.dispatchers.remove(nd.getId());
    }

    public void flush(Duration timeout) throws TimeoutException, InterruptedException {

        Instant start = Instant.now();
        waitForConnectOrClose(timeout);

        if (isClosed()) {
            throw new TimeoutException("Attempted to flush while closed");
        }

        if (timeout == null) {
            timeout = Duration.ZERO;
        }

        Instant now = Instant.now();
        Duration waitTime = Duration.between(start, now);

        if (!timeout.equals(Duration.ZERO) && waitTime.compareTo(timeout) >= 0) {
            throw new TimeoutException("Timeout out waiting for connection before flush.");
        }

        try {
            Future<Boolean> waitForIt = sendPing();

            if (waitForIt == null) { // error in the sendping code
                return;
            }

            long nanos = timeout.toNanos();

            if (nanos > 0) {

                nanos -= waitTime.toNanos();

                if (nanos <= 0) {
                    nanos = 1; // let the future timeout if it isn't resolved
                }

                waitForIt.get(nanos, TimeUnit.NANOSECONDS);
            } else {
                waitForIt.get();
            }

            this.statistics.incrementFlushCounter();
        } catch (ExecutionException | CancellationException e) {
            throw new TimeoutException(e.getMessage());
        }
    }

    void sendConnect(String serverURI) throws IOException {
        try {
            NatsServerInfo info = this.serverInfo.get();
            StringBuilder connectString = new StringBuilder();
            connectString.append(NatsConnection.OP_CONNECT);
            connectString.append(" ");
            String connectOptions = this.options.buildProtocolConnectOptionsString(serverURI, info.isAuthRequired(), info.getNonce());
            connectString.append(connectOptions);
            NatsMessage msg = new NatsMessage(connectString.toString());

            queueInternalOutgoing(msg);
        } catch (Exception exp) {
            exp.printStackTrace();
            throw new IOException("Error sending connect string", exp);
        }
    }

    CompletableFuture<Boolean> sendPing() {
        return this.sendPing(true);
    }

    CompletableFuture<Boolean> softPing() {
        return this.sendPing(false);
    }

    // Send a ping request and push a pong future on the queue.
    // futures are completed in order, keep this one if a thread wants to wait
    // for a specific pong. Note, if no pong returns the wait will not return
    // without setting a timeout.
    CompletableFuture<Boolean> sendPing(boolean treatAsInternal) {
        int max = this.options.getMaxPingsOut();

        if (!isConnectedOrConnecting()) {
            CompletableFuture<Boolean> retVal = new CompletableFuture<Boolean>();
            retVal.complete(Boolean.FALSE);
            return retVal;
        }

        if (max > 0 && pongQueue.size() + 1 > max) {
            handleCommunicationIssue(new IllegalStateException("Max outgoing Ping count exceeded."));
            return null;
        }

        CompletableFuture<Boolean> pongFuture = new CompletableFuture<>();
        NatsMessage msg = new NatsMessage(NatsConnection.OP_PING);
        pongQueue.add(pongFuture);

        if (treatAsInternal) {
            queueInternalOutgoing(msg);
        } else {
            queueOutgoing(msg);
        }

        this.statistics.incrementPingCount();
        return pongFuture;
    }

    void sendPong() {
        NatsMessage msg = new NatsMessage(NatsConnection.OP_PONG);
        queueInternalOutgoing(msg);
    }

    // Called by the reader
    void handlePong() {
        CompletableFuture<Boolean> pongFuture = pongQueue.pollFirst();
        if (pongFuture != null) {
            pongFuture.complete(Boolean.TRUE);
        }
    }

    void readInitialInfo() throws IOException {
        byte[] readBuffer = new byte[options.getBufferSize()];
        ByteBuffer protocolBuffer = ByteBuffer.allocate(options.getBufferSize());
        boolean gotCRLF = false;
        boolean gotCR = false;
        int read = 0;

        while (!gotCRLF) {
            read = this.dataPort.read(readBuffer, 0, readBuffer.length);

            if (read < 0) {
                break;
            }

            int i = 0;
            while (i < read) {
                byte b = readBuffer[i++];

                if (gotCR) {
                    if (b != LF) {
                        throw new IOException("Missed LF after CR waiting for INFO.");
                    } else if (i < read) {
                        throw new IOException("Read past initial info message.");
                    }

                    gotCRLF = true;
                    break;
                }

                if (b == CR) {
                    gotCR = true;
                } else {
                    if (!protocolBuffer.hasRemaining()) {
                        protocolBuffer = enlargeBuffer(protocolBuffer, 0); // just double it
                    }
                    protocolBuffer.put(b);
                }
            }

            if (gotCRLF) {
                break;
            }
        }

        if (!gotCRLF) {
            throw new IOException("Failed to read initial info message.");
        }

        protocolBuffer.flip();

        String infoJson = StandardCharsets.UTF_8.decode(protocolBuffer).toString();
        infoJson = infoJson.trim();
        String msg[] = infoJson.split("\\s");
        String op = msg[0].toUpperCase();

        if (!OP_INFO.equals(op)) {
            throw new IOException("Received non-info initial message.");
        }

        handleInfo(infoJson);
    }

    void handleInfo(String infoJson) {
        NatsServerInfo serverInfo = new NatsServerInfo(infoJson);
        this.serverInfo.set(serverInfo);

        String[] urls = this.serverInfo.get().getConnectURLs();
        if (urls != null && urls.length > 0) {
            processConnectionEvent(Events.DISCOVERED_SERVERS);
        }
    }

    void queueOutgoing(NatsMessage msg) {
        if (msg.getControlLineLength() > this.options.getMaxControlLine()) {
            throw new IllegalArgumentException("Control line is too long");
        }
        this.writer.queue(msg);
    }

    void queueInternalOutgoing(NatsMessage msg) {
        if (msg.getControlLineLength() > this.options.getMaxControlLine()) {
            throw new IllegalArgumentException("Control line is too long");
        }
        this.writer.queueInternalMessage(msg);
    }

    void deliverMessage(NatsMessage msg) {
        this.statistics.incrementInMsgs();
        this.statistics.incrementInBytes(msg.getSizeInBytes());

        NatsSubscription sub = subscribers.get(msg.getSID());

        if (sub != null) {
            msg.setSubscription(sub);

            NatsDispatcher d = sub.getNatsDispatcher();
            NatsConsumer c = (d == null) ? sub : d;
            MessageQueue q = ((d == null) ? sub.getMessageQueue() : d.getMessageQueue());

            if (c.hasReachedPendingLimits()) {
                // Drop the message and count it
                this.statistics.incrementDroppedCount();
                c.incrementDroppedCount();

                // Notify the first time
                if (!c.isMarkedSlow()) {
                    c.markSlow();
                    processSlowConsumer(c);
                }
            } else if (q != null) {
                c.markNotSlow();
                q.push(msg);
            }

        } else {
            // Drop messages we don't have a subscriber for (could be extras on an
            // auto-unsub for example)
        }
    }

    void processOK() {
        this.statistics.incrementOkCount();
    }

    void processSlowConsumer(Consumer consumer) {
        ErrorListener handler = this.options.getErrorListener();

        if (handler != null && !this.callbackRunner.isShutdown()) {
            try {
                this.callbackRunner.execute(() -> {
                    try {
                        handler.slowConsumerDetected(this, consumer);
                    } catch (Exception ex) {
                        this.statistics.incrementExceptionCount();
                    }
                });
            } catch (RejectedExecutionException re) {
                // Timing with shutdown, let it go
            }
        }
    }

    void processException(Exception exp) {
        ErrorListener handler = this.options.getErrorListener();

        this.statistics.incrementExceptionCount();

        if (handler != null && !this.callbackRunner.isShutdown()) {
            try {
                this.callbackRunner.execute(() -> {
                    try {
                        handler.exceptionOccurred(this, exp);
                    } catch (Exception ex) {
                        this.statistics.incrementExceptionCount();
                    }
                });
            } catch (RejectedExecutionException re) {
                // Timing with shutdown, let it go
            }
        }
    }

    void processError(String errorText) {
        ErrorListener handler = this.options.getErrorListener();

        this.statistics.incrementErrCount();

        this.lastError.set(errorText);

        if (handler != null && !this.callbackRunner.isShutdown()) {
            try {
                this.callbackRunner.execute(() -> {
                    try {
                        handler.errorOccurred(this, errorText);
                    } catch (Exception ex) {
                        this.statistics.incrementExceptionCount();
                    }
                });
            } catch (RejectedExecutionException re) {
                // Timing with shutdown, let it go
            }
        }
    }

    void processConnectionEvent(Events type) {
        ConnectionListener handler = this.options.getConnectionListener();

        if (handler != null && !this.callbackRunner.isShutdown()) {
            try {
                this.callbackRunner.execute(() -> {
                    try {
                        handler.connectionEvent(this, type);
                    } catch (Exception ex) {
                        this.statistics.incrementExceptionCount();
                    }
                });
            } catch (RejectedExecutionException re) {
                // Timing with shutdown, let it go
            }
        }
    }

    NatsServerInfo getInfo() {
        return this.serverInfo.get();
    }

    public Options getOptions() {
        return this.options;
    }

    public Statistics getStatistics() {
        return this.statistics;
    }

    NatsStatistics getNatsStatistics() {
        return this.statistics;
    }

    DataPort getDataPort() {
        return this.dataPort;
    }

    // Used for testing
    int getConsumerCount() {
        return this.subscribers.size() + this.dispatchers.size();
    }

    public long getMaxPayload() {
        NatsServerInfo info = this.serverInfo.get();

        if (info == null) {
            return -1;
        }

        return info.getMaxPayload();
    }

    public Collection<String> getServers() {
        NatsServerInfo info = this.serverInfo.get();
        HashSet<String> check = new HashSet<String>();
        ArrayList<String> servers = new ArrayList<>();

        options.getServers().stream().forEach(x -> {
            String uri = x.toString();
            if (!check.contains(uri)) {
                servers.add(uri);
                check.add(uri);
            }
        });

        if (info != null && info.getConnectURLs() != null) {
            for (String uri : info.getConnectURLs()) {
                if (!check.contains(uri)) {
                    servers.add(uri);
                    check.add(uri);
                }
            }
        }

        return servers;
    }

    public String getConnectedUrl() {
        return this.currentServerURI;
    }

    public Status getStatus() {
        return this.status;
    }

    public String getLastError() {
        return this.lastError.get();
    }

    ExecutorService getExecutor() {
        return executor;
    }

    void updateStatus(Status newStatus) {
        Status oldStatus = this.status;

        statusLock.lock();
        try {
            if (oldStatus == Status.CLOSED) {
                return;
            }
            this.status = newStatus;
        } finally {
            statusChanged.signalAll();
            statusLock.unlock();
        }

        if (this.status == Status.DISCONNECTED) {
            processConnectionEvent(Events.DISCONNECTED);
        } else if (this.status == Status.CLOSED) {
            processConnectionEvent(Events.CLOSED);
        } else if (oldStatus == Status.RECONNECTING && this.status == Status.CONNECTED) {
            processConnectionEvent(Events.RECONNECTED);
        } else if (this.status == Status.CONNECTED) {
            processConnectionEvent(Events.CONNECTED);
        }
    }

    boolean isClosing() {
        return this.closing;
    }

    boolean isClosed() {
        return this.status == Status.CLOSED;
    }

    boolean isConnected() {
        return this.status == Status.CONNECTED;
    }

    boolean isConnectedOrConnecting() {
        statusLock.lock();
        try {
            return this.status == Status.CONNECTED || this.connecting;
        } finally {
            statusLock.unlock();
        }
    }

    boolean isDisconnectingOrClosed() {
        statusLock.lock();
        try {
            return this.status == Status.CLOSED || this.disconnecting;
        } finally {
            statusLock.unlock();
        }
    }

    boolean isDisconnecting() {
        statusLock.lock();
        try {
            return this.disconnecting;
        } finally {
            statusLock.unlock();
        }
    }

    void waitForDisconnectOrClose(Duration timeout) throws InterruptedException {
        waitFor(timeout, (Void) -> {
            return this.isDisconnecting() && !this.isClosed();
        });
    }

    void waitForConnectOrClose(Duration timeout) throws InterruptedException {
        waitFor(timeout, (Void) -> {
            return !this.isConnected() && !this.isClosed();
        });
    }

    void waitFor(Duration timeout, Predicate<Void> test) throws InterruptedException {
        statusLock.lock();
        try {
            long currentWaitNanos = (timeout != null) ? timeout.toNanos() : -1;
            long start = System.nanoTime();
            while (currentWaitNanos >= 0 && test.test(null)) {
                if (currentWaitNanos > 0) {
                    statusChanged.await(currentWaitNanos, TimeUnit.NANOSECONDS);
                    long now = System.nanoTime();
                    currentWaitNanos = currentWaitNanos - (now - start);
                    start = now;

                    if (currentWaitNanos <= 0) {
                        break;
                    }
                } else {
                    statusChanged.await();
                }
            }
        } finally {
            statusLock.unlock();
        }
    }

    void waitForReconnectTimeout() {
        Duration waitTime = options.getReconnectWait();
        long currentWaitNanos = (waitTime != null) ? waitTime.toNanos() : -1;
        long start = System.nanoTime();

        while (currentWaitNanos > 0 && !isDisconnectingOrClosed() && !isConnected() && !this.reconnectWaiter.isDone()) {
            try {
                this.reconnectWaiter.get(currentWaitNanos, TimeUnit.NANOSECONDS);
            } catch (Exception exp) {
                // ignore, try to loop again
            }
            long now = System.nanoTime();
            currentWaitNanos = currentWaitNanos - (now - start);
            start = now;
        }

        this.reconnectWaiter.complete(Boolean.TRUE);
    }

    Collection<String> buildReconnectList() {
        ArrayList<String> reconnectList = new ArrayList<>();

        reconnectList.addAll(getServers());

        if (options.isNoRandomize()) {
            return reconnectList;
        }

        Collections.shuffle(reconnectList);

        return reconnectList;
    }

    ByteBuffer enlargeBuffer(ByteBuffer buffer, int atLeast) {
        int current = buffer.capacity();
        int newSize = Math.max(current * 2, atLeast);
        ByteBuffer newBuffer = ByteBuffer.allocate(newSize);
        buffer.flip();
        newBuffer.put(buffer);
        return newBuffer;
    }

    // For testing
    NatsConnectionReader getReader() {
        return this.reader;
    }

    // For testing
    NatsConnectionWriter getWriter() {
        return this.writer;
    }

    // For testing
    Future<DataPort> getDataPortFuture() {
        return this.dataPortFuture;
    }

    boolean isDraining() {
        return this.draining.get() != null;
    }

    boolean isDrained() {
        CompletableFuture<Boolean> tracker = this.draining.get();

        try {
            if (tracker != null && tracker.getNow(false)) {
                return true;
            }
        } catch (Exception e) {
            // These indicate the tracker was cancelled/timed out
        }

        return false;
    }

    public CompletableFuture<Boolean> drain(Duration timeout) throws TimeoutException, InterruptedException {

        if (isClosing() || isClosed()) {
            throw new IllegalStateException("A connection can't be drained during close.");
        }

        this.statusLock.lock();
        try {
            if (isDraining()) {
                return this.draining.get();
            }
            this.draining.set(new CompletableFuture<>());
        } finally {
            this.statusLock.unlock();
        }

        final CompletableFuture<Boolean> tracker = this.draining.get();
        Instant start = Instant.now();

        // Don't include subscribers with dispatchers
        HashSet<NatsSubscription> pureSubscribers = new HashSet<>();
        pureSubscribers.addAll(this.subscribers.values());
        pureSubscribers.removeIf((s) -> {
            return s.getDispatcher() != null;
        });

        final HashSet<NatsConsumer> consumers = new HashSet<>();
        consumers.addAll(pureSubscribers);
        consumers.addAll(this.dispatchers.values());

        NatsDispatcher inboxer = this.inboxDispatcher.get();

        if(inboxer != null) {
            consumers.add(inboxer);
        }

        // Stop the consumers NOW so that when this method returns they are blocked
        consumers.forEach((cons) -> {
            cons.markDraining(tracker);
            cons.sendUnsubForDrain();
        });

        this.flush(timeout); // Flush and wait up to the timeout, if this fails, let the caller know

        consumers.forEach((cons) -> {
            cons.markUnsubedForDrain();
        });

        // Wait for the timeout or the pending count to go to 0
        executor.submit(() -> {
            try {
                Instant now = Instant.now();

                while (timeout == null || timeout.equals(Duration.ZERO)
                        || Duration.between(start, now).compareTo(timeout) < 0) {
                    for (Iterator<NatsConsumer> i = consumers.iterator(); i.hasNext();) {
                        NatsConsumer cons = i.next();
                        if (cons.isDrained()) {
                            i.remove();
                        }
                    }

                    if (consumers.size() == 0) {
                        break;
                    }

                    Thread.sleep(1); // Sleep 1 milli

                    now = Instant.now();
                }

                // Stop publishing
                this.blockPublishForDrain.set(true);

                // One last flush
                if (timeout == null || timeout.equals(Duration.ZERO)) {
                    this.flush(Duration.ZERO);
                } else {
                    now = Instant.now();

                    Duration passed = Duration.between(start, now);
                    Duration newTimeout = timeout.minus(passed);

                    if (newTimeout.toNanos() > 0) {
                        this.flush(newTimeout);
                    }
                }

                this.close(false); // close the connection after the last flush
                tracker.complete(consumers.size() == 0);
            } catch (TimeoutException | InterruptedException e) {
                this.processException(e);
            } finally {
                try {
                    this.close();// close the connection after the last flush
                } catch (InterruptedException e) {
                    this.processException(e);
                }
                tracker.complete(false);
            }
        });

        return tracker;
    }
}