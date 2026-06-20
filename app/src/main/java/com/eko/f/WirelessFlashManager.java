package com.eko.f;

import android.content.Context;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.SocketAddress;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.Collections;

public final class WirelessFlashManager {

    private static final long   ACCEPT_POLL_MS    = 200;
    private static final long   CONNECTION_POLL_MS = 200;
    private static final int    LISTENER_BACKLOG   = 4;
    private static final String WILDCARD_HOST      = "0.0.0.0";
    private static final int    WIRELESS_PORT      = 13579;

    private final Context    appContext;
    private final EkoStore   store;
    private final Object     stateLock = new Object();

    private boolean          enabled;
    private boolean          pausedForFlash;
    private String           listenerAddress;
    private ServerSocketChannel listenerChannel;
    private Selector         listenerSelector;
    private EkoTcpTransport  connectedTransport;
    private Thread           listenerThread;

    public WirelessFlashManager(Context context, EkoStore store) {
        if (context == null) throw new NullPointerException("context == null");
        if (store    == null) throw new NullPointerException("store == null");
        this.appContext = context.getApplicationContext();
        this.store      = store;
    }

    // ── Public API ────────────────────────────────────────────────────────────

    public void setEnabled(boolean enable) {
        if (enable) startListening();
        else        stopListening();
    }

    public void close() {
        stopListening();
    }

    public void finishFlashSession() {
        EkoTcpTransport transport;
        synchronized (stateLock) {
            pausedForFlash = false;
            transport = connectedTransport;
        }
        if (transport != null && transport.isConnected()) {
            publishConnectedState(transport);
            return;
        }
        synchronized (stateLock) {
            if (connectedTransport == transport) connectedTransport = null;
        }
        boolean isEnabled;
        synchronized (stateLock) { isEnabled = enabled; }
        if (isEnabled) publishListeningState();
    }

    public EkoTcpTransport takeConnectedTransportForFlash() {
        EkoTcpTransport transport;
        synchronized (stateLock) {
            transport = connectedTransport;
            if (transport == null) {
                return null;
            } else if (transport.isConnected()) {
                pausedForFlash = true;
            } else {
                connectedTransport = null;
                return null;
            }
        }
        store.setWirelessStatus(listenerAddress,
                appContext.getString(R.string.wireless_status_session_active,
                        transport.getPeerLabel()));
        return transport;
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private void startListening() {
        synchronized (stateLock) {
            enabled = true;
            if (listenerThread != null && listenerThread.isAlive()) {
                EkoTcpTransport t = connectedTransport;
                if (t == null || !t.isConnected()) publishListeningState();
                else                                publishConnectedState(t);
                return;
            }
            listenerThread = new Thread(this::runListenerLoop, "eko-wireless-listener");
            listenerThread.setDaemon(true);
            listenerThread.start();
        }
    }

    private void stopListening() {
        EkoTcpTransport transport;
        Selector        selector;
        ServerSocketChannel channel;
        Thread          thread;

        synchronized (stateLock) {
            enabled        = false;
            pausedForFlash = false;
            transport      = connectedTransport; connectedTransport = null;
            selector       = listenerSelector;   listenerSelector   = null;
            channel        = listenerChannel;    listenerChannel    = null;
            listenerAddress = null;
            thread         = listenerThread;     listenerThread     = null;
        }

        if (thread   != null) thread.interrupt();
        if (selector != null) selector.wakeup();
        closeQuietly(selector);
        closeQuietly(channel);
        if (transport != null) transport.close();

        store.clearDevices();
        store.setWirelessStatus(null, null);
    }

    private void runListenerLoop() {
        try {
            ServerSocketChannel ssc = ServerSocketChannel.open();
            ssc.configureBlocking(false);
            ssc.bind(new InetSocketAddress(WIRELESS_PORT), LISTENER_BACKLOG);

            Selector sel = Selector.open();
            ssc.register(sel, SelectionKey.OP_ACCEPT);

            synchronized (stateLock) {
                if (!enabled) { closeQuietly(sel); closeQuietly(ssc); return; }
                listenerChannel  = ssc;
                listenerSelector = sel;
                listenerAddress  = resolveListenerAddress(ssc);
            }
            publishListeningState();

            while (true) {
                synchronized (stateLock) { if (!enabled) break; }
                sel.select(ACCEPT_POLL_MS);

                for (SelectionKey key : sel.selectedKeys()) {
                    if (key.isAcceptable()) handleAccept(ssc, sel);
                }
                sel.selectedKeys().clear();

                // Poll existing connection
                EkoTcpTransport existing;
                synchronized (stateLock) { existing = connectedTransport; }
                if (existing != null && !existing.isConnected()) {
                    synchronized (stateLock) {
                        if (connectedTransport == existing) connectedTransport = null;
                    }
                    existing.close();
                    publishListeningState();
                }
            }
        } catch (Throwable ignored) {
        } finally {
            synchronized (stateLock) {
                closeQuietly(listenerSelector); listenerSelector = null;
                closeQuietly(listenerChannel);  listenerChannel  = null;
            }
        }
    }

    private void handleAccept(ServerSocketChannel ssc, Selector sel) {
        try {
            SocketChannel sc = ssc.accept();
            if (sc == null) return;
            sc.configureBlocking(true);
            String host = ((java.net.InetSocketAddress) sc.getRemoteAddress()).getHostString();
            int    port = ((java.net.InetSocketAddress) sc.getRemoteAddress()).getPort();
            android.os.ParcelFileDescriptor pfd =
                    android.os.ParcelFileDescriptor.fromSocket(sc.socket());
            EkoTcpTransport transport = new EkoTcpTransport(sc, pfd, host, port, null);

            EkoTcpTransport old;
            synchronized (stateLock) {
                old = connectedTransport;
                connectedTransport = transport;
            }
            if (old != null) old.close();
            publishConnectedState(transport);
        } catch (Throwable ignored) {}
    }

    private void publishListeningState() {
        String addr;
        synchronized (stateLock) { addr = listenerAddress; }
        store.clearDevices();
        String msg = (addr != null)
                ? appContext.getString(R.string.wireless_status_listening, addr)
                : appContext.getString(R.string.wireless_status_starting);
        store.setWirelessStatus(addr, msg);
    }

    private void publishConnectedState(EkoTcpTransport transport) {
        String deviceId    = "wireless:" + transport.getPeerLabel();
        String deviceLabel = appContext.getString(R.string.wireless_device_label);
        store.setWirelessDevice(deviceId, deviceLabel, transport.getPeerLabel());
        String addr;
        synchronized (stateLock) { addr = listenerAddress; }
        store.setWirelessStatus(addr,
                appContext.getString(R.string.wireless_status_connected, transport.getPeerLabel()));
    }

    private String resolveListenerAddress(ServerSocketChannel ssc) throws IOException {
        SocketAddress local = ssc.getLocalAddress();
        int port = WIRELESS_PORT;
        if (local instanceof InetSocketAddress)
            port = ((InetSocketAddress) local).getPort();
        String host = firstWirelessHostAddress();
        if (host == null) host = WILDCARD_HOST;
        return host + ":" + port;
    }

    private String firstWirelessHostAddress() {
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (java.net.InetAddress addr : Collections.list(ni.getInetAddresses())) {
                    if (addr instanceof Inet4Address) {
                        Inet4Address a4 = (Inet4Address) addr;
                        if (!a4.isLoopbackAddress() && !a4.isLinkLocalAddress())
                            return a4.getHostAddress();
                    }
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static void closeQuietly(java.io.Closeable c) {
        if (c == null) return;
        try { c.close(); } catch (Throwable ignored) {}
    }
}
