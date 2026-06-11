// SPDX-License-Identifier: GPL-3.0-or-later
package id.jawa.core;

import com.google.protobuf.ByteString;
import id.jawa.binary.BinaryDecoder;
import id.jawa.binary.BinaryEncoder;
import id.jawa.binary.BinaryNode;
import id.jawa.noise.FrameSocket;
import id.jawa.noise.NoiseHandshake;
import id.jawa.noise.NoiseTransport;
import id.jawa.pair.ClientPayloadBuilder;
import id.jawa.pair.PairingCodeHandler;
import id.jawa.pair.PairingHandler;
import id.jawa.proto.Wa;
import id.jawa.message.MessageEncoder;
import id.jawa.message.MessageSender;
import id.jawa.signal.InMemorySignalKeyStore;
import id.jawa.signal.JaWaProtocolStore;
import id.jawa.signal.PreKeyBundleFetcher;
import id.jawa.signal.PreKeyManager;
import id.jawa.signal.SessionBootstrap;
import id.jawa.signal.SignalKeyStore;
import id.jawa.store.AuthCreds;
import id.jawa.store.AuthStore;
import id.jawa.util.Bytes;
import id.jawa.util.crypto.Curve25519;
import id.jawa.util.crypto.KeyPair25519;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Top-level WhatsApp Web client.
 *
 * <p>Drives the connection lifecycle:
 * <ol>
 *   <li>Load (or generate) {@link AuthCreds}.
 *   <li>Open the WebSocket via {@link FrameSocket}.
 *   <li>Run the Noise XX handshake. On {@code clientFinish.payload}, send either
 *     a {@code RegisterPayload} (first pair) or {@code LoginPayload} (reconnect).
 *   <li>Enter the steady-state read loop. For first-pair, dispatch
 *     {@code <pair-device>} / {@code <pair-success>} IQs via {@link PairingHandler}.
 *   <li>Persist creds after pair-success.
 * </ol>
 */
public final class JaWaClient implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(JaWaClient.class);

    public interface Listener {
        /** Server sent QR refs. Each string is {@code ref,noisePub,identityPub,advSecret} — render as a QR. */
        default void onQr(List<String> qrStrings) {}
        /** Pairing completed; creds persisted. */
        default void onPaired(String jid, String pushName, String platform) {}
        /** Steady-state connection up. */
        default void onConnected() {}
        /**
         * A {@code <message>} stanza was successfully decrypted.
         *
         * @param decoded the decoded payload — {@code text} is non-null for plain
         *                conversation messages, null for media / reactions / etc.
         */
        default void onMessage(id.jawa.message.MessageReceiver.Decoded decoded) {}
        /** Inbound stanza after handshake/pairing. */
        default void onStanza(BinaryNode node) {}
        /** Fatal error. */
        default void onError(Throwable t) {}
    }

    private final AuthStore store;
    private final SignalKeyStore signalStore = new InMemorySignalKeyStore();
    private final id.jawa.signal.InMemorySenderKeyStore senderKeyStore = new id.jawa.signal.InMemorySenderKeyStore();
    private JaWaProtocolStore protocolStore;  // initialised in connect() once creds are loaded
    private PairingCodeHandler pairCodeHandler; // populated on demand for pair-code flow
    private java.util.concurrent.ScheduledExecutorService keepalive;
    private Listener listener = new Listener() {};
    private FrameSocket frame;
    private NoiseHandshake noise;
    private NoiseTransport transport;
    private AuthCreds creds;
    private Thread readerThread;
    private final AtomicBoolean closing = new AtomicBoolean(false);
    /** Set by the pair-success handler so the reader exits cleanly into a login-mode reconnect. */
    private final AtomicBoolean reconnectAfterPair = new AtomicBoolean(false);
    /** Counts down exactly once when {@link #close()} fires; lets {@link #join()} block across reconnects. */
    private final java.util.concurrent.CountDownLatch closeLatch = new java.util.concurrent.CountDownLatch(1);
    private static final int PRE_KEY_UPLOAD_COUNT = 30;

    public JaWaClient(AuthStore store) { this.store = store; }

    public JaWaClient listener(Listener l) { this.listener = l != null ? l : new Listener() {}; return this; }

    /** Open the WebSocket, run the handshake, and start dispatching stanzas. Blocks until handshake completes. */
    public synchronized void connect() throws Exception {
        if (frame != null) throw new IllegalStateException("already connected");

        creds = store.load();
        boolean isPairing = (creds == null || creds.account == null);
        if (creds == null) {
            creds = AuthCreds.generate();
            LOG.info("No creds found — generated fresh pair: regId={}", creds.registrationId);
        } else if (isPairing) {
            LOG.info("Found unpaired creds (regId={}) — resuming QR pairing", creds.registrationId);
        } else {
            LOG.info("Found paired creds — login mode (jid={})", creds.meJid);
        }

        frame = new FrameSocket(null);
        frame.connect();

        // ---- Noise XX, client side ----

        KeyPair25519 ephemeral = Curve25519.generateKeyPair();
        noise = new NoiseHandshake(ephemeral, WaConstants.WA_HEADER);

        // Message 1: ClientHello
        Wa.HandshakeMessage clientHello = Wa.HandshakeMessage.newBuilder()
                .setClientHello(Wa.HandshakeMessage.ClientHello.newBuilder()
                        .setEphemeral(ByteString.copyFrom(ephemeral.publicKey()))
                        .build())
                .build();
        frame.send(clientHello.toByteArray());

        // Message 2: ServerHello
        byte[] serverHelloBytes = frame.receive(15_000);
        if (serverHelloBytes == null) throw new IllegalStateException("ServerHello timeout");
        Wa.HandshakeMessage serverFrame = Wa.HandshakeMessage.parseFrom(serverHelloBytes);
        Wa.HandshakeMessage.ServerHello serverHello = serverFrame.getServerHello();

        byte[] encStatic = noise.processServerHello(
            serverHello.getEphemeral().toByteArray(),
            serverHello.getStatic().toByteArray(),
            serverHello.getPayload().toByteArray(),
            creds.noiseKey
        );

        // Message 3: ClientFinish — encrypt ClientPayload with the NEW handshake key
        Wa.ClientPayload payload = isPairing
                ? ClientPayloadBuilder.register(creds)
                : ClientPayloadBuilder.login(creds);
        byte[] encPayload = noise.encrypt(payload.toByteArray());

        Wa.HandshakeMessage clientFinish = Wa.HandshakeMessage.newBuilder()
                .setClientFinish(Wa.HandshakeMessage.ClientFinish.newBuilder()
                        .setStatic(ByteString.copyFrom(encStatic))
                        .setPayload(ByteString.copyFrom(encPayload))
                        .build())
                .build();
        frame.send(clientFinish.toByteArray());

        transport = noise.finish();
        protocolStore = new JaWaProtocolStore(creds);
        LOG.info("Noise handshake complete — steady state {}", isPairing ? "(pairing)" : "(login)");

        // ---- Start reader loop ----

        readerThread = Thread.ofVirtual().name("jawa-reader").start(this::readLoop);
        startKeepalive();
    }

    /** Schedule a periodic w:p keepalive ping so the server doesn't disconnect us for inactivity. */
    private void startKeepalive() {
        if (keepalive != null) return;
        keepalive = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = Thread.ofVirtual().name("jawa-keepalive").unstarted(r);
            t.setDaemon(true);
            return t;
        });
        // Whatsmeow rolls a random interval per tick in [20..30) sec. We do the same to avoid
        // any deterministic-traffic heuristic on the server side.
        Runnable tick = new Runnable() {
            @Override public void run() {
                if (closing.get() || frame == null || !frame.isOpen()) return;
                try {
                    String iqId = newIqId();
                    BinaryNode ping = new BinaryNode("iq",
                        java.util.Map.of(
                            "to",    id.jawa.util.Jid.SERVER_WHATSAPP,
                            "type",  "get",
                            "xmlns", "w:p",
                            "id",    iqId
                        ),
                        java.util.List.of(new BinaryNode("ping", java.util.Map.of(), null)));
                    sendIq(ping, resp -> LOG.trace("keepalive ack id={}", iqId));
                } catch (Throwable t) {
                    LOG.warn("Keepalive send failed", t);
                }
                // Random in [20..30) seconds — matches whatsmeow's KeepAliveIntervalMin/Max.
                long delay = 20 + java.util.concurrent.ThreadLocalRandom.current().nextInt(10);
                keepalive.schedule(this, delay, java.util.concurrent.TimeUnit.SECONDS);
            }
        };
        keepalive.schedule(tick, 20, java.util.concurrent.TimeUnit.SECONDS);
    }

    /**
     * Block until {@link #close()} fires. Survives reconnects — the underlying reader
     * thread may be swapped out mid-flight (e.g. post-pair auto-login) without unblocking
     * the caller.
     */
    public void join() throws InterruptedException {
        closeLatch.await();
    }

    private void readLoop() {
        PairingHandler pair = new PairingHandler(creds);
        try {
            while (!closing.get() && frame.isOpen()) {
                byte[] enc = frame.receive(60_000);
                if (enc == null) {
                    if (!frame.isOpen()) break;
                    continue;
                }
                byte[] plain = transport.decrypt(enc);
                BinaryNode node = BinaryDecoder.decode(plain);
                LOG.trace("recv: {}", node);

                try {
                    if (handleStanza(node, pair)) continue;
                    listener.onStanza(node);
                } catch (Throwable per) {
                    LOG.warn("Error handling stanza {} — continuing", node.tag(), per);
                }
            }
        } catch (Throwable t) {
            if (!closing.get() && !reconnectAfterPair.get()) listener.onError(t);
        } finally {
            if (reconnectAfterPair.compareAndSet(true, false)) {
                Thread.ofVirtual().name("jawa-reconnect").start(this::doReconnectPostPair);
            } else {
                close();
            }
        }
    }

    /**
     * Tear down the current pairing connection and reopen as login mode. Called from a
     * fresh virtual thread after pair-success — the server immediately follows pair-success
     * with {@code stream:error 515} and a TCP close, and revokes the new creds within ~30s
     * if we don't reconnect with a {@code LoginPayload}.
     */
    private void doReconnectPostPair() {
        if (frame != null) { try { frame.close(); } catch (Throwable ignored) {} frame = null; }
        if (keepalive != null) { keepalive.shutdownNow(); keepalive = null; }
        noise = null;
        transport = null;
        pendingIqResults.clear();
        // Brief settle so we don't race the server's own teardown of the pairing connection.
        try { Thread.sleep(500); }
        catch (InterruptedException ie) { Thread.currentThread().interrupt(); return; }
        if (closing.get()) return; // caller invoked close() during the settle window
        try {
            connect();
            LOG.info("Auto-reconnected post-pair → login mode");
        } catch (Exception e) {
            LOG.error("Auto-reconnect failed", e);
            listener.onError(e);
            close();
        }
    }

    private final java.util.Map<String, java.util.function.Consumer<BinaryNode>> pendingIqResults
        = new java.util.concurrent.ConcurrentHashMap<>();

    /** Random 16-char hex id for outgoing IQ stanzas. */
    private String newIqId() {
        return Bytes.toHex(Bytes.random(8));
    }

    /**
     * Send an IQ stanza and register a callback for the {@code <iq type=result|error>} response.
     * The callback runs on the reader thread.
     */
    public String sendIq(BinaryNode iq, java.util.function.Consumer<BinaryNode> onResponse) {
        String id = iq.attr("id");
        if (id == null) throw new IllegalArgumentException("iq has no id attribute");
        if (onResponse != null) pendingIqResults.put(id, onResponse);
        send(iq);
        return id;
    }

    /** Send an IQ stanza and return a future that completes with the response. */
    public java.util.concurrent.CompletableFuture<BinaryNode> sendIqAsync(BinaryNode iq) {
        var f = new java.util.concurrent.CompletableFuture<BinaryNode>();
        sendIq(iq, f::complete);
        return f;
    }

    /**
     * Switch the current pairing session to phone-number pairing-code mode.
     *
     * @param phoneNumber  the phone number to pair, in international E.164 form without {@code +} (e.g. "628123…")
     * @param customCode   optional fixed 8-char Crockford code; {@code null} = randomly generated
     * @return future completing with the 8-char code that should be displayed to the user
     */
    public java.util.concurrent.CompletableFuture<String> requestPairingCode(String phoneNumber, String customCode) {
        if (creds.account != null) {
            throw new IllegalStateException("already paired (creds.account is set) — cannot request a pairing code");
        }
        pairCodeHandler = new PairingCodeHandler(creds);
        String iqId = newIqId();
        BinaryNode iq = pairCodeHandler.buildCompanionHello(iqId, phoneNumber, customCode);
        try { store.save(creds); } catch (Exception e) { LOG.warn("save creds failed", e); }
        return sendIqAsync(iq).thenApply(resp -> {
            if ("error".equals(resp.attr("type"))) {
                throw new IllegalStateException("companion_hello rejected: " + resp);
            }
            return pairCodeHandler.pairingCode();
        });
    }

    /**
     * Send a text message to {@code toUser} (bare JID, e.g. {@code 628xxx@s.whatsapp.net}).
     * Returns the message id once the stanza has been transmitted.
     *
     * <p>Pipeline: query devices for recipient AND own user → bootstrap sessions for both
     * sets → encrypt per-device (DSM-wrap for own companions, bare for foreign) → send.
     *
     * <p>The own-user USync is what lets the user's other devices (notably the phone)
     * insert the outgoing message into their local chat history. Without it, the message
     * reaches the recipient but the sender's own phone shows no record of it.
     */
    public java.util.concurrent.CompletableFuture<String> sendText(String toUser, String text) {
        return sendDmMessage(toUser, MessageEncoder.text(text));
    }

    /**
     * Send any {@link id.jawa.proto.Wa.Message} (text, reaction, etc.) as a DM. Handles
     * USync device-list query, pre-key bundle fetch, Signal session install, per-device
     * encrypt + DSM fan-out to own companion devices.
     */
    public java.util.concurrent.CompletableFuture<String>
            sendDmMessage(String toUser, id.jawa.proto.Wa.Message msg) {
        id.jawa.util.Jid myJid = id.jawa.util.Jid.parse(creds.meJid);
        if (myJid == null) {
            return java.util.concurrent.CompletableFuture.failedFuture(
                new IllegalStateException("creds.meJid invalid"));
        }
        String ownBareJid = myJid.user() + "@" + id.jawa.util.Jid.SERVER_WHATSAPP;
        boolean isSelfSend = toUser.equals(ownBareJid);
        java.util.List<String> queryTargets = isSelfSend
            ? java.util.List.of(toUser)
            : java.util.List.of(toUser, ownBareJid);

        return queryDevices(queryTargets).thenCompose(devicesMap -> {
            java.util.List<String> allDeviceJids = new java.util.ArrayList<>();
            for (var entry : devicesMap.entrySet()) {
                id.jawa.util.Jid base = id.jawa.util.Jid.parse(entry.getKey());
                if (base == null) continue;
                for (var d : entry.getValue()) {
                    allDeviceJids.add(d.asJid(base.user(), base.server()).asString());
                }
            }
            return fetchBundlesAndInstallSessions(allDeviceJids).thenApply(addresses -> {
                String msgId = newIqId().toUpperCase();
                MessageSender.Result result = MessageSender.buildStanza(
                    protocolStore, creds, msgId, toUser, allDeviceJids, msg);
                send(result.stanza());
                int ownCount = countOwnDevices(allDeviceJids, myJid.user());
                LOG.info("Sent message id={} to={} ({} device(s) total, {} own-companion DSM)",
                    msgId, toUser, allDeviceJids.size(),
                    Math.max(0, ownCount - 1)); // -1 for the sender device, which is skipped
                return msgId;
            });
        });
    }

    private static int countOwnDevices(java.util.List<String> deviceJids, String ownUser) {
        int n = 0;
        for (String dj : deviceJids) {
            id.jawa.util.Jid j = id.jawa.util.Jid.parse(dj);
            if (j != null && ownUser.equals(j.user())) n++;
        }
        return n;
    }

    /** Fetch pre-key bundles for the given per-device JIDs and install Signal sessions for each. */
    public java.util.concurrent.CompletableFuture<java.util.List<org.whispersystems.libsignal.SignalProtocolAddress>>
            fetchBundlesAndInstallSessions(java.util.List<String> deviceJids) {
        String iqId = newIqId();
        BinaryNode q = PreKeyBundleFetcher.buildFetchStanza(iqId, deviceJids);
        LOG.debug("Sent pre-key fetch IQ id={} for {} devices", iqId, deviceJids.size());
        return sendIqAsync(q).thenApply(resp -> {
            if ("error".equals(resp.attr("type"))) {
                LOG.warn("Pre-key fetch error: {}", resp);
                return java.util.List.of();
            }
            var bundles = PreKeyBundleFetcher.parseResponse(resp);
            LOG.debug("Parsed {} bundles", bundles.size());
            return SessionBootstrap.installAll(protocolStore, bundles);
        });
    }

    /**
     * Send a text message to a group. Pipeline:
     * 1. Resolve participants by looking up the group in our joined-groups list.
     * 2. USync each participant's bare JID to enumerate every device they have.
     * 3. Bootstrap libsignal sessions for every participant device that doesn't have one.
     * 4. Hand off to {@link id.jawa.message.GroupSender} which builds + encrypts the stanza.
     */
    public java.util.concurrent.CompletableFuture<String>
            sendGroupText(String groupJid, String text) {
        return sendGroupMessage(groupJid, MessageEncoder.text(text));
    }

    /**
     * Send any {@link id.jawa.proto.Wa.Message} (text, reaction, etc.) to a group.
     * Handles participant resolution, USync, pre-key bundle fetch, session install,
     * and the SKDM-per-participant + single skmsg fan-out.
     */
    public java.util.concurrent.CompletableFuture<String>
            sendGroupMessage(String groupJid, id.jawa.proto.Wa.Message msg) {
        return queryJoinedGroups().thenCompose(groups -> {
            id.jawa.message.GroupListQuery.GroupInfo target = null;
            for (var g : groups) {
                if (g.jid().equals(groupJid)) { target = g; break; }
            }
            if (target == null) {
                return java.util.concurrent.CompletableFuture.failedFuture(
                    new IllegalStateException("not a member of group: " + groupJid));
            }
            // Group-list participant JIDs may be device-suffixed; strip to bare for USync.
            java.util.LinkedHashSet<String> bareUsers = new java.util.LinkedHashSet<>();
            for (String pj : target.participantJids()) {
                id.jawa.util.Jid j = id.jawa.util.Jid.parse(pj);
                if (j == null) continue;
                bareUsers.add(j.user() + "@" + j.server());
            }
            if (bareUsers.isEmpty()) {
                return java.util.concurrent.CompletableFuture.failedFuture(
                    new IllegalStateException("group has no participants"));
            }
            return queryDevices(bareUsers).thenCompose(devicesMap -> {
                java.util.List<String> allDeviceJids = new java.util.ArrayList<>();
                for (var entry : devicesMap.entrySet()) {
                    id.jawa.util.Jid base = id.jawa.util.Jid.parse(entry.getKey());
                    if (base == null) continue;
                    for (var d : entry.getValue()) {
                        allDeviceJids.add(d.asJid(base.user(), base.server()).asString());
                    }
                }
                return fetchBundlesAndInstallSessions(allDeviceJids).thenApply(addresses -> {
                    String msgId = newIqId().toUpperCase();
                    id.jawa.message.GroupSender.Result result =
                        id.jawa.message.GroupSender.buildStanza(
                            protocolStore, senderKeyStore, creds, msgId,
                            groupJid, allDeviceJids, msg);
                    send(result.stanza());
                    LOG.info("Sent group message id={} to={} ({} participant device(s), SKDM to {})",
                        msgId, groupJid, allDeviceJids.size(), result.skdmRecipients());
                    return msgId;
                });
            });
        });
    }

    /**
     * Send a reaction to an existing message. Routes to {@link #sendDmMessage} for DM
     * targets and {@link #sendGroupMessage} for group targets, based on {@code chatJid}
     * suffix.
     *
     * @param chatJid           where the original message lives — group JID
     *                          ({@code ...@g.us}) for group reactions, or the peer's
     *                          bare JID for DMs
     * @param targetMsgId       the original message's {@code id} attribute
     * @param targetParticipant for group reactions, the device JID of the original
     *                          message's sender; {@code null} for DMs
     * @param fromMe            {@code true} if the target message was sent by us
     * @param emoji             the reaction emoji; empty string removes our reaction
     * @return future resolving to the reaction message's id
     */
    public java.util.concurrent.CompletableFuture<String> sendReaction(
            String chatJid,
            String targetMsgId,
            String targetParticipant,
            boolean fromMe,
            String emoji) {
        long ts = System.currentTimeMillis();
        id.jawa.proto.Wa.Message msg = MessageEncoder.reaction(
            chatJid, targetMsgId, targetParticipant, fromMe, emoji, ts);
        if (chatJid.endsWith("@g.us")) {
            return sendGroupMessage(chatJid, msg);
        }
        return sendDmMessage(chatJid, msg);
    }

    /**
     * Send a text reply that quotes an existing message. Routes DM vs group based on
     * {@code chatJid} suffix.
     *
     * @param chatJid       chat where the reply lives (group JID or peer's bare JID)
     * @param text          the new reply text
     * @param quotedMsgId   id of the message being quoted
     * @param quotedSender  sender JID of the quoted message; {@code null} for DM
     * @param quotedText    short preview of the quoted text; used to render the
     *                      block-quote on the recipient's UI
     * @return future resolving to the reply message's id
     */
    public java.util.concurrent.CompletableFuture<String> sendReply(
            String chatJid,
            String text,
            String quotedMsgId,
            String quotedSender,
            String quotedText) {
        id.jawa.proto.Wa.Message msg = MessageEncoder.reply(
            text, quotedMsgId, quotedSender, quotedText);
        if (chatJid.endsWith("@g.us")) {
            return sendGroupMessage(chatJid, msg);
        }
        return sendDmMessage(chatJid, msg);
    }

    /**
     * Edit a previously-sent text message. WhatsApp's UI shows the replacement and an
     * "edited" tag. Subject to ~15-minute server-side edit window.
     *
     * @param chatJid       chat where the original was sent
     * @param targetMsgId   id returned by the original {@link #sendText} /
     *                      {@link #sendGroupText} call
     * @param newText       the replacement text
     * @return future resolving to the edit message's id (a new id, distinct from
     *         {@code targetMsgId})
     */
    public java.util.concurrent.CompletableFuture<String> sendEdit(
            String chatJid,
            String targetMsgId,
            String newText) {
        id.jawa.proto.Wa.Message inner = MessageEncoder.text(newText);
        id.jawa.proto.Wa.Message msg = MessageEncoder.edit(
            chatJid, targetMsgId, inner, System.currentTimeMillis());
        if (chatJid.endsWith("@g.us")) {
            return sendGroupMessage(chatJid, msg);
        }
        return sendDmMessage(chatJid, msg);
    }

    /**
     * Revoke (delete-for-everyone) a message.
     *
     * @param chatJid           chat where the original lives
     * @param targetMsgId       id of the message to revoke
     * @param targetParticipant for revoking someone else's message in a group (admin
     *                          action), the sender's device JID; {@code null} when
     *                          revoking your own
     * @param fromMe            {@code true} if the target message was sent by us
     */
    public java.util.concurrent.CompletableFuture<String> sendRevoke(
            String chatJid,
            String targetMsgId,
            String targetParticipant,
            boolean fromMe) {
        id.jawa.proto.Wa.Message msg = MessageEncoder.revoke(
            chatJid, targetMsgId, targetParticipant, fromMe);
        if (chatJid.endsWith("@g.us")) {
            return sendGroupMessage(chatJid, msg);
        }
        return sendDmMessage(chatJid, msg);
    }

    /**
     * Query the server for the list of groups this account participates in. Each entry
     * carries the group JID, subject, creator, timestamps, and the per-member device
     * list (one entry per participant, device-suffixed).
     */
    public java.util.concurrent.CompletableFuture<java.util.List<id.jawa.message.GroupListQuery.GroupInfo>>
            queryJoinedGroups() {
        String iqId = newIqId();
        BinaryNode iq = id.jawa.message.GroupListQuery.buildQuery(iqId);
        LOG.debug("Sent joined-groups query id={}", iqId);
        return sendIqAsync(iq).thenApply(resp -> {
            if ("error".equals(resp.attr("type"))) {
                LOG.warn("Group list query rejected: {}", resp);
                return java.util.List.of();
            }
            return id.jawa.message.GroupListQuery.parseResponse(resp);
        });
    }

    /**
     * Convenience: bootstrap Signal sessions for every active device of {@code targetUser}.
     * Runs USync, then fetches the pre-key bundle for each device, then installs sessions.
     */
    public java.util.concurrent.CompletableFuture<java.util.List<org.whispersystems.libsignal.SignalProtocolAddress>>
            bootstrapSessions(String targetUser) {
        return queryDevices(java.util.List.of(targetUser))
            .thenCompose(devicesMap -> {
                var devices = devicesMap.getOrDefault(targetUser, java.util.List.of());
                if (devices.isEmpty()) {
                    java.util.List<org.whispersystems.libsignal.SignalProtocolAddress> empty = java.util.List.of();
                    return java.util.concurrent.CompletableFuture.completedFuture(empty);
                }
                id.jawa.util.Jid base = id.jawa.util.Jid.parse(targetUser);
                String user = base.user();
                String server = base.server();
                java.util.List<String> deviceJids = new java.util.ArrayList<>(devices.size());
                for (var d : devices) deviceJids.add(d.asJid(user, server).asString());
                return fetchBundlesAndInstallSessions(deviceJids);
            });
    }

    /**
     * Query the device list for one or more JIDs (USync).
     * Returns {@code targetJid → ordered device list}.
     */
    public java.util.concurrent.CompletableFuture<java.util.Map<String, java.util.List<id.jawa.message.DeviceInfo>>>
            queryDevices(java.util.Collection<String> targetJids) {
        String iqId = newIqId();
        String sid = "jawa-" + Bytes.toHex(Bytes.random(4));
        BinaryNode q = id.jawa.message.UsyncQuery.buildDeviceListQuery(iqId, sid, targetJids);
        LOG.debug("Sent USync device-list query id={} sid={} for {}", iqId, sid, targetJids);
        return sendIqAsync(q).thenApply(resp -> {
            LOG.debug("USync response: {}", resp);
            return id.jawa.message.UsyncQuery.parseDeviceListResult(resp);
        });
    }

    /**
     * Tell the server we want real-time message delivery instead of having stanzas queued
     * for retrieval. Without this, WA holds messages until the client explicitly drains
     * them — manifests as "5-min idle then disconnect, no inbound &lt;message&gt; ever fires"
     * during testing. Mirrors {@code Client.sendPassive(false)} in whatsmeow.
     */
    private void sendActivePassive() {
        String iqId = newIqId();
        BinaryNode iq = new BinaryNode("iq",
            java.util.Map.of(
                "to",    id.jawa.util.Jid.SERVER_WHATSAPP,
                "xmlns", "passive",
                "type",  "set",
                "id",    iqId
            ),
            java.util.List.of(new BinaryNode("active", java.util.Map.of(), null)));
        sendIq(iq, resp -> {
            if ("error".equals(resp.attr("type"))) {
                LOG.warn("active/passive IQ rejected: {}", resp);
            } else {
                LOG.debug("Server acknowledged active mode (id={})", iqId);
            }
        });
    }

    /**
     * Tell the server this device is online and available for routing. Without this,
     * the server treats the device as background/idle: last-seen freezes at login
     * time and peers' messages don't get delivered as {@code <message>} stanzas to us.
     *
     * <p>Stanza shape: {@code <presence type="available" name="<pushName>"/>}. The
     * {@code name} attr is what other users see as our display name; we use
     * {@code creds.pushName} when available, falling back to {@code "JaWa"}.
     */
    private void sendPresenceAvailable() {
        String name = (creds != null && creds.pushName != null && !creds.pushName.isBlank())
            ? creds.pushName
            : "JaWa";
        try {
            send(new BinaryNode("presence", java.util.Map.of(
                "type", "available",
                "name", name
            ), null));
            LOG.debug("Sent presence available (name=\"{}\")", name);
        } catch (RuntimeException e) {
            LOG.warn("Failed to send presence available", e);
        }
    }

    /** Upload {@code PRE_KEY_UPLOAD_COUNT} one-time pre-keys to the server. */
    private void uploadPreKeys() {
        var preKeys = PreKeyManager.generate(creds, signalStore, PRE_KEY_UPLOAD_COUNT);
        if (preKeys.isEmpty()) {
            LOG.debug("No new pre-keys to upload");
            return;
        }
        // Mirror each pre-key into the libsignal protocolStore so inbound <enc type=pkmsg>
        // can resolve the one-time pre-key id the server handed out. signalStore is the
        // raw KeyPair25519 home; protocolStore is what SessionCipher.decrypt actually reads.
        for (var entry : preKeys.entrySet()) {
            try {
                byte[] prefixedPub = Curve25519.prependType(entry.getValue().publicKey());
                org.whispersystems.libsignal.ecc.ECKeyPair kp =
                    new org.whispersystems.libsignal.ecc.ECKeyPair(
                        org.whispersystems.libsignal.ecc.Curve.decodePoint(prefixedPub, 0),
                        org.whispersystems.libsignal.ecc.Curve.decodePrivatePoint(entry.getValue().privateKey()));
                protocolStore.storePreKey(entry.getKey(),
                    new org.whispersystems.libsignal.state.PreKeyRecord(entry.getKey(), kp));
            } catch (org.whispersystems.libsignal.InvalidKeyException e) {
                LOG.warn("Failed to seed pre-key {} into protocolStore", entry.getKey(), e);
            }
        }
        String iqId = newIqId();
        BinaryNode iq = PreKeyManager.buildUploadStanza(iqId, creds, preKeys);
        sendIq(iq, response -> {
            if ("error".equals(response.attr("type"))) {
                LOG.warn("Pre-key upload rejected: {}", response);
                return;
            }
            PreKeyManager.markUploaded(creds, preKeys);
            try { store.save(creds); } catch (Exception e) { LOG.warn("Failed to save creds after pre-key upload", e); }
            LOG.info("Uploaded {} pre-keys (ids {}..{})",
                preKeys.size(),
                preKeys.keySet().iterator().next(),
                preKeys.keySet().stream().reduce((a, b) -> b).get());
        });
        LOG.debug("Sent pre-key upload IQ id={} ({} keys)", iqId, preKeys.size());
    }

    private boolean handleStanza(BinaryNode node, PairingHandler pair) throws Exception {
        if ("stream:error".equals(node.tag())) {
            String code = node.attr("code");
            if ("515".equals(code)) {
                // Documented post-pair signal — server wants us to reconnect with login creds.
                LOG.info("Server requested restart (code=515) — expected after pairing; reconnect to enter steady state");
            } else {
                LOG.warn("Stream error: {}", node);
            }
            return true;
        }
        if ("xmlstreamend".equals(node.tag())) {
            LOG.debug("Server sent stream end");
            return true;
        }
        if ("success".equals(node.tag())) {
            LOG.info("Login successful — meJid={} lid={} platform={}",
                node.attr("jid", creds.meJid),
                node.attr("lid", creds.meLid),
                node.attr("platform", creds.platform));
            sendActivePassive();
            sendPresenceAvailable();
            listener.onConnected();
            uploadPreKeys();
            return true;
        }
        // Pair-code: primary device echoed back its ephemeral via a server-pushed notification
        if ("notification".equals(node.tag())
                && node.child("link_code_companion_reg") != null
                && pairCodeHandler != null) {
            try {
                BinaryNode finish = pairCodeHandler.buildCompanionFinish(newIqId(), node);
                send(finish);
                LOG.debug("Sent companion_finish IQ");
            } catch (Throwable t) {
                LOG.warn("Failed to handle link_code notification", t);
            }
            // fall through to ack + listener — pair-code notification still needs an ack
        }

        // Server-pushed notifications and receipts MUST be acked, otherwise the offline
        // queue piles up and the server eventually stops delivering <message> stanzas.
        // We ack first, then fall through to listener.onStanza so consumers can read them.
        if ("notification".equals(node.tag()) || "receipt".equals(node.tag())) {
            try { sendAck(node); }
            catch (Throwable t) { LOG.warn("Failed to ack {}", node.tag(), t); }
            return false;
        }

        if ("message".equals(node.tag())) {
            handleInboundMessage(node);
            return true;
        }

        if (!"iq".equals(node.tag())) return false;

        // Result / error for an IQ we sent — fire the pending callback if any.
        String type = node.attr("type");
        if ("result".equals(type) || "error".equals(type)) {
            String id = node.attr("id");
            var cb = id == null ? null : pendingIqResults.remove(id);
            if (cb != null) { cb.accept(node); return true; }
        }

        BinaryNode pairDevice  = node.child("pair-device");
        BinaryNode pairSuccess = node.child("pair-success");

        if (pairDevice != null) {
            List<String> qrs = pair.qrRefsFrom(pairDevice);
            send(pair.ackPairDevice(node.attr("id")));
            LOG.info("Got {} QR refs", qrs.size());
            listener.onQr(qrs);
            return true;
        }
        if (pairSuccess != null) {
            BinaryNode reply = pair.handlePairSuccess(node);
            send(reply);
            store.save(creds);
            listener.onPaired(creds.meJid, creds.pushName, creds.platform);
            // Server is about to send stream:error 515 and close the socket. Schedule a
            // login-mode reconnect so we land in steady state before the new creds expire.
            reconnectAfterPair.set(true);
            return true;
        }
        // Auto-reply to keepalive pings so the connection stays up
        if ("get".equals(node.attr("type"))
                && "urn:xmpp:ping".equals(node.attr("xmlns"))) {
            String pid = node.attr("id");
            if (pid != null) {
                send(new BinaryNode("iq",
                    java.util.Map.of("to", id.jawa.util.Jid.SERVER_WHATSAPP,
                                     "type", "result",
                                     "id", pid),
                    null));
            } else {
                LOG.warn("Ping IQ has no id; cannot ack: {}", node);
            }
            return true;
        }
        return false;
    }

    /** Send a binary stanza (encrypts + frames). */
    public void send(BinaryNode node) {
        byte[] plain = BinaryEncoder.encode(node);
        byte[] enc = transport.encrypt(plain);
        frame.send(enc);
    }

    /**
     * Decrypt an inbound {@code <message>}, fire {@code Listener.onMessage}, and emit
     * the {@code <ack>} + {@code <receipt>} the server expects. On decrypt failure we
     * still {@code <ack>} (otherwise the server keeps redelivering forever) and emit
     * a {@code <receipt type="retry">} so the peer knows to re-encrypt with fresh keys.
     */
    private void handleInboundMessage(BinaryNode message) {
        try {
            id.jawa.message.MessageReceiver.Decoded decoded =
                id.jawa.message.MessageReceiver.decode(message, protocolStore, senderKeyStore);
            try { listener.onMessage(decoded); }
            catch (Throwable t) { LOG.warn("listener.onMessage threw", t); }
            sendAck(message);
            sendDeliveryReceipt(message);
        } catch (id.jawa.message.MessageReceiver.DecryptException de) {
            LOG.warn("Decrypt failed for message id={} from={}: {}",
                message.attr("id"), message.attr("from"), de.getMessage());
            sendAck(message);
            sendRetryReceipt(message);
        }
    }

    /**
     * Transport-level acknowledgement. Without it the server keeps redelivering the
     * stanza on every reconnect and eventually throttles new deliveries.
     *
     * <p>Attrs mirrored from the source stanza: {@code id}, {@code to} (from
     * source's {@code from}), {@code class} (source's tag), and — when present —
     * {@code participant}, {@code recipient}. For non-message stanzas, the source's
     * {@code type} attribute is also preserved on the ack (e.g. {@code
     * <receipt type="read">} → ack must carry {@code type="read"}, otherwise the
     * server raises a stream:error and drops the connection).
     */
    private void sendAck(BinaryNode original) {
        String id = original.attr("id");
        String from = original.attr("from");
        if (id == null || from == null) return;
        java.util.Map<String, String> attrs = new java.util.LinkedHashMap<>();
        attrs.put("class", original.tag());
        attrs.put("id", id);
        attrs.put("to", from);
        String participant = original.attr("participant");
        if (participant != null) attrs.put("participant", participant);
        String recipient = original.attr("recipient");
        if (recipient != null) attrs.put("recipient", recipient);
        // <message> ack never carries type; everything else preserves it.
        if (!"message".equals(original.tag())) {
            String type = original.attr("type");
            if (type != null) attrs.put("type", type);
        }
        send(new BinaryNode("ack", attrs, null));
    }

    /** Application-level "delivered" receipt — what produces the grey single-tick on the sender. */
    private void sendDeliveryReceipt(BinaryNode message) {
        sendReceipt(message, null);
    }

    /** Retry-receipt counter — each id is incremented per outbound retry so peer/server know. */
    private final java.util.concurrent.ConcurrentMap<String, Integer> messageRetries
        = new java.util.concurrent.ConcurrentHashMap<>();
    private static final int MAX_RETRY_COUNT = 5;

    /**
     * "We couldn't decrypt — please re-encrypt for me." Sends a retry receipt with a
     * {@code <retry>} child carrying the count + a {@code <registration>} child with
     * our registration id, mirroring whatsmeow's {@code sendRetryReceipt}. The peer
     * uses these to refetch our pre-key bundle and re-encrypt the message as pkmsg.
     *
     * <p>Bare {@code <receipt type=retry>} (no children) is not enough — peer needs
     * the registration id to know which client to encrypt for.
     */
    private void sendRetryReceipt(BinaryNode message) {
        String id = message.attr("id");
        String from = message.attr("from");
        if (id == null || from == null) return;

        int retryCount = messageRetries.merge(id, 1, Integer::sum);
        if (retryCount > MAX_RETRY_COUNT) {
            LOG.warn("Giving up retry-receipt for id={} (count={} > {})", id, retryCount, MAX_RETRY_COUNT);
            return;
        }

        java.util.Map<String, String> attrs = new java.util.LinkedHashMap<>();
        attrs.put("id", id);
        attrs.put("to", from);
        attrs.put("type", "retry");
        String participant = message.attr("participant");
        if (participant != null) attrs.put("participant", participant);
        String recipient = message.attr("recipient");
        if (recipient != null) attrs.put("recipient", recipient);

        BinaryNode retry = new BinaryNode("retry", java.util.Map.of(
            "count", Integer.toString(retryCount),
            "id",    id,
            "t",     message.attr("t", "0"),
            "v",     "1"
        ), null);
        BinaryNode registration = new BinaryNode("registration", java.util.Map.of(),
            encodeUintBE(creds.registrationId, 4));

        send(new BinaryNode("receipt", attrs, java.util.List.of(retry, registration)));
        LOG.debug("Sent retry receipt id={} count={}", id, retryCount);
    }

    private static byte[] encodeUintBE(int value, int width) {
        byte[] out = new byte[width];
        for (int i = 0; i < width; i++) out[width - 1 - i] = (byte) ((value >>> (8 * i)) & 0xFF);
        return out;
    }

    private void sendReceipt(BinaryNode message, String type) {
        String id = message.attr("id");
        String from = message.attr("from");
        if (id == null || from == null) return;
        java.util.Map<String, String> attrs = new java.util.LinkedHashMap<>();
        attrs.put("id", id);
        attrs.put("to", from);
        if (type != null) attrs.put("type", type);
        String participant = message.attr("participant");
        if (participant != null) attrs.put("participant", participant);
        String recipient = message.attr("recipient");
        if (recipient != null) attrs.put("recipient", recipient);
        send(new BinaryNode("receipt", attrs, null));
    }

    @Override
    public void close() {
        if (!closing.compareAndSet(false, true)) return;
        if (keepalive != null) { keepalive.shutdownNow(); keepalive = null; }
        if (frame != null) frame.close();
        closeLatch.countDown();
    }

    public AuthCreds creds() { return creds; }
}
