package com.eko.f;

import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructPollfd;
import android.system.StructTimeval;

import java.io.FileDescriptor;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;

public final class EkoTcpTransport {
    private static final int CONNECTION_ERROR_MASK;
    private static final int DEFAULT_TIMEOUT_MS = 1000;
    private static final int EXTRA_POLL_OK_MASK;
    private static final short POLL_IN;
    private static final short POLL_OUT;

    private final SocketChannel channel;
    private volatile boolean closed;
    private final Object ioLock;
    private final String peerHost;
    private final int peerPort;
    private final byte[] probeBuffer;
    private final ParcelFileDescriptor probeDescriptor;

    static {
        int pollErr = OsConstants.POLLERR;
        int pollHup = OsConstants.POLLHUP;
        CONNECTION_ERROR_MASK = pollErr | pollHup | OsConstants.POLLNVAL;
        POLL_IN = (short) OsConstants.POLLIN;
        POLL_OUT = (short) OsConstants.POLLOUT;
        EXTRA_POLL_OK_MASK = pollHup;
    }

    private EkoTcpTransport(SocketChannel socketChannel, ParcelFileDescriptor parcelFileDescriptor,
                            String host, int port) {
        this.channel = socketChannel;
        this.probeDescriptor = parcelFileDescriptor;
        this.peerHost = host;
        this.peerPort = port;
        this.probeBuffer = new byte[1];
        this.ioLock = new Object();
    }

    public EkoTcpTransport(SocketChannel socketChannel, ParcelFileDescriptor parcelFileDescriptor,
                           String host, int port, Object unused) {
        this(socketChannel, parcelFileDescriptor, host, port);
    }

    private void applySocketOptions(int timeoutMs) {
        try {
            FileDescriptor fd = this.probeDescriptor.getFileDescriptor();
            Os.setsockoptInt(fd, OsConstants.SOL_SOCKET, OsConstants.SO_KEEPALIVE, 1);
        } catch (Throwable ignored) {}

        if (Build.VERSION.SDK_INT >= 29) {
            StructTimeval tv = StructTimeval.fromMillis(timeoutMs);
            try {
                Os.setsockoptTimeval(this.probeDescriptor.getFileDescriptor(),
                        OsConstants.SOL_SOCKET, OsConstants.SO_RCVTIMEO, tv);
            } catch (Throwable ignored) {}
            try {
                Os.setsockoptTimeval(this.probeDescriptor.getFileDescriptor(),
                        OsConstants.SOL_SOCKET, OsConstants.SO_SNDTIMEO, tv);
            } catch (Throwable ignored) {}
        }

        if (Build.VERSION.SDK_INT >= 26) {
            try {
                Os.setsockoptInt(this.probeDescriptor.getFileDescriptor(),
                        OsConstants.IPPROTO_TCP, OsConstants.TCP_USER_TIMEOUT, timeoutMs);
            } catch (Throwable ignored) {}
        }
    }

    private void closeLocked() {
        if (this.closed) return;
        this.closed = true;
        try { this.channel.close(); } catch (Throwable ignored) {}
        try { this.probeDescriptor.close(); } catch (Throwable ignored) {}
    }

    private int failLocked() {
        closeLocked();
        return -1;
    }

    private Integer remainingTimeout(long deadlineRealtime) {
        long remaining = deadlineRealtime - SystemClock.elapsedRealtime();
        if (remaining > Integer.MAX_VALUE) remaining = Integer.MAX_VALUE;
        int remainingInt = (int) remaining;
        return (remainingInt > 0) ? remainingInt : null;
    }

    private boolean waitUntilReady(short events, int timeoutMillis) {
        if (this.closed) return false;
        StructPollfd pollFd = new StructPollfd();
        pollFd.fd = this.probeDescriptor.getFileDescriptor();
        pollFd.events = events;
        try {
            int ret = Os.poll(new StructPollfd[]{pollFd}, timeoutMillis);
            if (ret > 0) {
                short revents = pollFd.revents;
                if ((events | EXTRA_POLL_OK_MASK) != 0 && (revents & CONNECTION_ERROR_MASK) == 0) {
                    return true;
                }
            }
        } catch (ErrnoException ignored) {
            // ErrnoException from Os.poll
        }
        return false;
    }

    public void close() {
        if (this.closed) return;
        this.closed = true;
        try { this.channel.close(); } catch (Throwable ignored) {}
        try { this.probeDescriptor.close(); } catch (Throwable ignored) {}
    }

    public String getId() {
        return this.peerHost;
    }

    public String getPeerLabel() {
        return this.peerHost + ":" + this.peerPort;
    }

    public int getTransportKind() {
        return 1;
    }

    public boolean isConnected() {
        synchronized (this.ioLock) {
            if (this.closed || !this.channel.isOpen()) return false;
            StructPollfd pollFd = new StructPollfd();
            pollFd.fd = this.probeDescriptor.getFileDescriptor();
            pollFd.events = POLL_IN;
            try {
                Os.poll(new StructPollfd[]{pollFd}, 0);
                short revents = pollFd.revents;
                if ((revents & CONNECTION_ERROR_MASK) != 0) {
                    failLocked();
                    return false;
                }
                if ((revents & POLL_IN) == 0) {
                    return true;
                }
                try {
                    int n = Os.recvfrom(this.probeDescriptor.getFileDescriptor(),
                            this.probeBuffer, 0, 1, OsConstants.MSG_PEEK, null);
                    if (n != 0) return true;
                    failLocked();
                    return false;
                } catch (ErrnoException e) {
                    if (e.errno == OsConstants.EAGAIN) return true;
                    failLocked();
                    return false;
                } catch (IOException e) {
                    failLocked();
                    return false;
                }
            } catch (ErrnoException e) {
                return failLocked() < 0;
            }
        }
    }

    public int receiveChunk(byte[] buffer, int size, int timeoutMs, int flagsIgnored) {
        if (buffer == null) throw new IllegalArgumentException("buffer == null");
        if (size <= 0) {
            return receiveZlp(timeoutMs);
        }
        synchronized (this.ioLock) {
            if (this.closed || !this.channel.isOpen()) return -1;

            long deadline = SystemClock.elapsedRealtime() + (timeoutMs < 1 ? 1 : timeoutMs);
            ByteBuffer byteBuffer = ByteBuffer.wrap(buffer, 0, size);
            while (true) {
                Integer remaining = remainingTimeout(deadline);
                if (remaining == null) return failLocked();
                if (!waitUntilReady(POLL_IN, remaining)) return failLocked();
                try {
                    int read = this.channel.read(byteBuffer);
                    if (read > 0) return read;
                    if (read == 0) {
                        deadline = SystemClock.elapsedRealtime() + (timeoutMs < 1 ? 1 : timeoutMs);
                        continue;
                    }
                    return failLocked();
                } catch (IOException e) {
                    return failLocked();
                }
            }
        }
    }

    public int receiveZlp(int timeoutMs) {
        synchronized (this.ioLock) {
            if (this.closed || !this.channel.isOpen()) {
                return -1;
            }
            return 0;
        }
    }

    public int sendChunk(byte[] buffer, int size, int timeoutMs, int flagsIgnored) {
        if (buffer == null) throw new IllegalArgumentException("buffer == null");
        if (size <= 0) return 0;

        synchronized (this.ioLock) {
            if (this.closed || !this.channel.isOpen()) return -1;

            long deadline = SystemClock.elapsedRealtime() + (timeoutMs < 1 ? 1 : timeoutMs);
            ByteBuffer byteBuffer = ByteBuffer.wrap(buffer, 0, size);
            int totalSent = 0;
            while (totalSent < size) {
                Integer remaining = remainingTimeout(deadline);
                if (remaining == null) return failLocked();
                if (!waitUntilReady(POLL_OUT, remaining)) return failLocked();
                try {
                    int written = this.channel.write(byteBuffer);
                    if (written > 0) {
                        totalSent += written;
                        deadline = SystemClock.elapsedRealtime() + (timeoutMs < 1 ? 1 : timeoutMs);
                    } else if (written == 0) {
                        // continue waiting
                    } else {
                        return failLocked();
                    }
                } catch (IOException e) {
                    return failLocked();
                }
            }
            return totalSent;
        }
    }

    public void setTimeoutMs(int timeoutMs) {
        synchronized (this.ioLock) {
            if (this.closed || !this.channel.isOpen()) return;
            if (timeoutMs < 1) timeoutMs = 1;
            applySocketOptions(timeoutMs);
        }
    }

    public void setPacketSizeHint(int hint) {
        // Not used in this implementation, kept for compatibility
    }
}
