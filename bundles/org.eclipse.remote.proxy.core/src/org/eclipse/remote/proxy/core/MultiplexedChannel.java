/*******************************************************************************
 * Copyright (c) 2016 Oak Ridge National Laboratory and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 *******************************************************************************/
package org.eclipse.remote.proxy.core;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class MultiplexedChannel {
    public static final int CAPACITY = 8192;
    
    private class MultiplexedInputStream extends InputStream {
        private final Lock lock = new ReentrantLock();
        private final Condition cond = lock.newCondition();
        private int currentPos;
        private int currentSize;
        private boolean connected = true;
        private int inputRequestCount;
        private byte[] buffer = new byte[CAPACITY];

        @Override
        public synchronized int read() throws IOException {
            if (!connected) {
                return -1;
            }
            byte[] b = new byte[1];
            if (read(b, 0, 1) != 1) {
                return -1;
            }
            return b[0] & 0xff;
        }
        
        @Override
        public int available() throws IOException {
            if (!connected) {
                return 0;
            }
            lock.lock();
            try {
                return currentSize - currentPos;
            } finally {
                lock.unlock();
            }
        }

        public synchronized int read(byte b[], int off, int len) throws IOException {
            if (!connected) {
                return -1;
            }
            if (len <= 0) {
                return 0;
            }
            int moreSpace;
            lock.lock();
            try {
                if (currentPos >= currentSize) {
                    currentSize = 0;
                } else if (currentPos >= CAPACITY/2) {
                    System.arraycopy(buffer, currentPos, buffer, 0, currentSize - currentPos);
                    currentSize -= currentPos;
                    currentPos = 0;
               }
                int freeSpace = CAPACITY - currentSize;
                moreSpace = Math.max(freeSpace - inputRequestCount, 0);
            } finally {
                lock.unlock();
            }
            if (moreSpace > 0) {
                mux.sendRequestCmd(MultiplexedChannel.this, moreSpace);
            }
            lock.lock();
            try {
                inputRequestCount += moreSpace;
                while (currentPos >= currentSize && connected) {
                    try {
                        cond.await();
                    } catch (InterruptedException e) {
                    }
                }
                if (!connected && currentPos >= currentSize) {
                    return -1;
                }

                int available = currentSize - currentPos;
                if (len < available) {
                    System.arraycopy(buffer, currentPos, b, off, len);
                    currentPos += len;
                    return len;
                } else {
                    System.arraycopy(buffer, currentPos, b, off, available);
                    currentPos = currentSize = 0;
                    return available;
                }
            } finally {
                lock.unlock();
            }
        }
        
        @Override
        public void close() throws IOException {
            MultiplexedChannel.this.close();
        }

        void receive(byte[] buf, int len) throws IOException {
            lock.lock();
            try {
//                if (len > (CAPACITY - currentSize - currentPos)) {
//                    throw new IOException("Receive buffer overflow");
//                }
                if (currentPos > 0 && (CAPACITY - currentSize) < len) {
                    System.arraycopy(buffer, currentPos, buffer, 0, currentSize - currentPos);
                    currentSize -= currentPos;
                    currentPos = 0;
                }
                if (CAPACITY - currentSize < len) {
                    throw new IOException("Receive buffer overflow");
                }
                System.arraycopy(buf, 0, buffer, currentSize, len);
                currentSize += len;
                inputRequestCount -= len;
                cond.signalAll();
            } finally {
                lock.unlock();
            }
        }

        void disconnect() {
            lock.lock();
            connected = false;
            cond.signalAll();
            lock.unlock();
        }
    }
    
    private class MultiplexedOutputStream extends OutputStream {
        private final Lock lock = new ReentrantLock();
        private final Condition cond = lock.newCondition();
        private int currentPos;
        private byte[] buffer = new byte[CAPACITY];
        private boolean connected = true;
        private int outputRequestCount;

        @Override
        public synchronized void write(int b) throws IOException {
            if (!connected) {
                return;
            }
            if (currentPos == CAPACITY) {
                flush();
            }
            buffer[currentPos++] = (byte)b;
        }

        @Override
        public synchronized void flush() throws IOException {
            if (!connected) {
                return;
            }
            while (currentPos > 0) {
                int n = send(buffer, 0, currentPos);
                currentPos -= n;
                if (currentPos > 0) {
                    System.arraycopy(buffer, n, buffer, 0, currentPos);
                }
            }
        }

        @Override
        public synchronized void write(byte[] b, int off, int len) throws IOException {
            if (!connected) {
                return;
            }
            if (len <= 0) {
                return;
            }
            if (len <= CAPACITY - currentPos) {
                System.arraycopy(b, off, buffer, currentPos, len);
                currentPos += len;
            } else {
                flush();
                while (len > 0) {
                    int n = send(b, off, len);
                    off += n;
                    len -= n;
                }
            }
        }
        
        int send(byte[] b, int off, int len) throws IOException {
            int canSend;
            lock.lock();
            try {
                while (connected && outputRequestCount == 0) {
                    try {
                        cond.await();
                    } catch (InterruptedException e) {
                    }
                }
                if (!connected) {
                    throw new IOException("channel closed");
                }
                canSend = outputRequestCount;
            } finally {
                lock.unlock();
            }
            if (canSend > len) {
                canSend = len;
            }
            mux.sendTransmitCmd(MultiplexedChannel.this, b, off, canSend);
            lock.lock();
            outputRequestCount -= canSend;
            lock.unlock();
            return canSend;
        }

        @Override
        public void close() throws IOException {
            MultiplexedChannel.this.close();
        }
        
        void request(int len) {
            lock.lock();
            outputRequestCount += len;
            cond.signalAll();
            lock.unlock();
        }
        
        void disconnect() {
            lock.lock();
            connected = false;
            cond.signalAll();
            lock.unlock();
        }
    }

    private final ChannelMultiplexer mux;
    private final int channelId;
    private final MultiplexedInputStream min = new MultiplexedInputStream();
    private final MultiplexedOutputStream mout = new MultiplexedOutputStream();
    
    private boolean open;

    public MultiplexedChannel(ChannelMultiplexer mux) {
        this.mux = mux;
        channelId = mux.getUnusedId();
        open = true;
    }
    
    public MultiplexedChannel(ChannelMultiplexer mux, int id) {
        this.mux = mux;
        channelId = id;
        open = true;
    }

    public int getId() {
        return channelId;
    }
    
    public InputStream getInputStream() {
        return min;
    }
    
    public OutputStream getOutputStream() {
        return mout;
    }
    
    public boolean isOpen() {
        return open;
    }
    
    public void close() throws IOException {
        mux.sendCloseCmd(MultiplexedChannel.this);
    }
    
    void receive(byte[] buf, int len) throws IOException {
        min.receive(buf, len);
    }
    
    void request(int len) {
        mout.request(len);
    }
    
    void disconnect() {
        min.disconnect();
        mout.disconnect();
    }
    
    void setClosed() {
        open = false;
    }
}
