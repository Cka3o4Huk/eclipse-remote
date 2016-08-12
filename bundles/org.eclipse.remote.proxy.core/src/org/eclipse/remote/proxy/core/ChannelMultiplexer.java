/*******************************************************************************
 * Copyright (c) 2016 Oak Ridge National Laboratory and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 *******************************************************************************/
package org.eclipse.remote.proxy.core;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

public class ChannelMultiplexer {
    public interface IChannelListener {
        public void newChannel(MultiplexedChannel chan);
        public void closeChannel(MultiplexedChannel chan);
    }
    
    private class Sender implements Runnable {
        private OutputStream out;
        private BlockingQueue<ByteArrayOutputStream> queue = new LinkedBlockingQueue<ByteArrayOutputStream>();
        private boolean running = true;
        
        public Sender(OutputStream out) {
            this.out = out;
        }
        
        public void sendOpenCmd(int id) throws IOException {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream data = new DataOutputStream(bytes);
            data.writeByte(CMD_OPEN);
            data.writeByte(id);
            try {
                queue.put(bytes);
            } catch (InterruptedException e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
            }
        }
        
        public void sendTransmitCmd(int id, byte buf[], int off, int len) throws IOException {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream data = new DataOutputStream(bytes);
            data.writeByte(CMD_TRANSMIT);
            data.writeByte(id);
            data.writeInt(len);
            data.write(buf, off, len);
            try {
                queue.put(bytes);
            } catch (InterruptedException e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
            }
        }

        public void sendCloseCmd(int id) throws IOException {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream data = new DataOutputStream(bytes);
            data.writeByte(CMD_CLOSE);
            data.writeByte(id);
            try {
                queue.put(bytes);
            } catch (InterruptedException e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
            }
        }
     
        public void sendCloseAckCmd(int id) throws IOException {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream data = new DataOutputStream(bytes);
            data.writeByte(CMD_CLOSEACK);
            data.writeByte(id);
            try {
                queue.put(bytes);
            } catch (InterruptedException e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
            }
        }
        
        public void sendRequestCmd(int id, int len) throws IOException {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream data = new DataOutputStream(bytes);
            data.writeByte(CMD_REQUEST);
            data.writeByte(id);
            data.writeInt(len);
            try {
                queue.put(bytes);
            } catch (InterruptedException e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
            }
        }
        
        public void shutdown() {
            running = false;
            try {
                queue.put(new ByteArrayOutputStream());
            } catch (InterruptedException e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
            }
        }

        @Override
        public void run() {
           try {
                while (running) {
                    ByteArrayOutputStream bytes = queue.take();
                    if (bytes != null) {
                    	bytes.writeTo(out);
                        out.flush();
                    }
                }
            } catch (InterruptedException e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
            } catch (IOException e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
            }
        }
        
    }
    
    private class Receiver implements Runnable {
        private DataInputStream dataIn;
        
        public Receiver(InputStream in) {
            this.dataIn = new DataInputStream(in);
        }

		@Override
	    public void run() {
	        MultiplexedChannel chan;
	        running = true;
	        try {
	            while (true) {
	                int cmd = dataIn.readByte() & 0xff;
	                int id = dataIn.readByte() & 0xff;
	     
	                switch (cmd) {
	                case CMD_OPEN:
	                    debugPrint("received cmd=OPEN id="+id);
	                    chan = channels.get(id);
	                    if (chan != null) {
	                        throw new IOException("Channel already exists");
	                    }
	                    if (channels.size() >= MAX_CHANNELS) {
	                        throw new IOException("Maximum number of channels exceeded");
	                    }
	                    chan = new MultiplexedChannel(ChannelMultiplexer.this, id);
	                    channels.put(id, chan);
	                    newChannelCallback(chan);
	                    break;
	                    
	                case CMD_CLOSE:
	                    debugPrint("received cmd=CLOSE id="+id);
	                    chan = channels.get(id);
	                    if (chan == null) {
	                        throw new IOException("CLOSE: Invalid channel id: " + id);
	                    }
	                    chan.disconnect();
	                    if (chan.isOpen()) {
	                        sendCloseAckCmd(chan);
	                    }
	                    closeChannelCallback(chan);
	                    channels.remove(id);
	                    break;
	                    
	                case CMD_CLOSEACK:
	                    debugPrint("received cmd=CLOSEACK id="+id);
	                    chan = channels.get(id);
	                    if (chan == null) {
	                        throw new IOException("CLOSEACK: Invalid channel id");
	                    }
	                    if (chan.isOpen()) {
	                        throw new IOException("Channel is still open");
	                    }
	                    chan.disconnect();
	                    channels.remove(id);
	                    break;
	    
	                case CMD_TRANSMIT:
	                    debugPrint("received cmd=TRANSMIT id="+id);
	                    chan = channels.get(id);
	                    if (chan == null) {
	                        throw new IOException("TRANSMIT: Invalid channel id: " + id);
	                    }
	                    int len = dataIn.readInt();
	                    byte[] buf = new byte[len];
	                    dataIn.readFully(buf, 0, len);
//	                    synchronized (sync) {
//	                        System.err.print(" len="+len+" ");
//	                        dump_buf("", buf, 0, len);
//	                    }
	                    chan.receive(buf, len);
	                    break;
	                    
	                case CMD_REQUEST:
	                    chan = channels.get(id);
	                    if (chan == null) {
	                        throw new IOException("REQUEST: Invalid channel id: " + id);
	                    }
	                    int req = dataIn.readInt();
	                    debugPrint("received cmd=REQUEST id="+id+ " len="+req);
	                    chan.request(req);
	                    break;
	    
	                default:
	                    synchronized (sync) {
	                        System.err.print(name + ": invalid command: "+ dump_byte((byte)cmd) + dump_byte((byte)id));
	                    }
	                    try {
	                        while (true) {
	                            byte b = dataIn.readByte();
	                            System.err.print(dump_byte(b));
	                        }
	                    } catch (Exception e) {
	                        // TODO Auto-generated catch block
	                        e.printStackTrace();
	                    }
	                    throw new IOException("Invalid command: " + cmd);
	                }
	            }
	        } catch (EOFException e) {
	            // Finish
	        } catch (Exception e) {
	            e.printStackTrace();
	            debugPrint("run got exception:" + e.getMessage());
	        } finally {
	            shutdown();
	        }
	    }
    }
    
    private final static int CMD_OPEN     = 0xE1;
    private final static int CMD_CLOSE    = 0xE2;
    private final static int CMD_CLOSEACK = 0xE3;
    private final static int CMD_REQUEST  = 0xE4;
    private final static int CMD_TRANSMIT = 0xE5;
    
    private final static int MAX_CHANNELS = 256;
    
    private final Map<Integer, MultiplexedChannel> channels = (Map<Integer, MultiplexedChannel>) Collections.synchronizedMap(new HashMap<Integer,MultiplexedChannel>());
    private final List<IChannelListener> listeners = (List<IChannelListener>)Collections.synchronizedList(new ArrayList<IChannelListener>());
    
    private String name;
    private Object sync;
    
    private volatile boolean running = true;
    
    private Sender sender;
    private Receiver receiver;
    
    private static int nextChannelId;

    public ChannelMultiplexer(String name, Object sync, InputStream in, OutputStream out) {
        this.name = name;
        this.sync = sync;
        sender = new Sender(new BufferedOutputStream(out));
        receiver = new Receiver(new BufferedInputStream(in));
    }
    
    // TODO: Need to fix this. 1) use int to allow more channels; 2) recycle channel ids.
    int getUnusedId() {
        while (channels.containsKey(nextChannelId)) {
            nextChannelId = ++nextChannelId & (MAX_CHANNELS - 1);
        }
        return nextChannelId;
    }
    
    public void dump_buf(String pref, byte[] b, int off, int len) {
        System.err.print(pref + ": ");
        for (int i = off; i < len+off; i++) {
            if (b[i] <= 32 || b[i] > 126) {
                System.err.print(String.format(" 0x%02x ", b[i]));
            } else {
                System.err.print((char)b[i]);
            }
        }
        System.err.println();
    }
    
    /**
     * Start the channel multiplexer
     */
    public void start() {
    	System.err.println("mux starting");
        new Thread(sender, name + " mux sender").start();
        new Thread(receiver, name + " mux receiver").start();
    }
    
    public void addListener(IChannelListener listener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener);
        }
    }
    
    public void removeListener(IChannelListener listener) {
        if (listeners.contains(listener)) {
            listeners.remove(listener);
        }
    }
    
    protected void newChannelCallback(MultiplexedChannel chan) {
        for (IChannelListener listener : listeners.toArray(new IChannelListener[listeners.size()])) {
            listener.newChannel(chan);
        }
    }
    
    protected void closeChannelCallback(MultiplexedChannel chan) {
        for (IChannelListener listener : listeners.toArray(new IChannelListener[listeners.size()])) {
            listener.closeChannel(chan);
        }
    }

    public String dump_byte(byte b) {
        if (b <= 32 || b > 126) {
            return String.format(" 0x%02x ", b);
        } 
        
        return String.valueOf((char)b);
    }
    
    public synchronized MultiplexedChannel openChannel() throws IOException {
        if (channels.size() >= MAX_CHANNELS) {
            throw new IOException("Maximum number of channels exceeded");
        }
        if (!running) {
            throw new IOException("Multiplexer is not running");
        }
        
        MultiplexedChannel chan = new MultiplexedChannel(this);
        channels.put(chan.getId(), chan);
        
        synchronized (this) {
            debugPrint("send cmd=OPEN id="+chan.getId());
            sender.sendOpenCmd(chan.getId());
        }

        return chan;
    }

    synchronized void sendTransmitCmd(MultiplexedChannel chan, byte buf[], int off, int len) throws IOException {
        if (running && chan.isOpen()) {
            synchronized (sync) {
                System.err.print(name + ": send cmd=TRANSMIT id="+chan.getId()+" len="+len+" ");
//                dump_buf("", buf, off, len);
            }
            sender.sendTransmitCmd(chan.getId(), buf, off, len);
        }
    }

    synchronized void sendCloseCmd(MultiplexedChannel chan) throws IOException {
        if (running && chan.isOpen()) {
            debugPrint("send cmd=CLOSE id="+chan.getId());
            chan.disconnect();
            sender.sendCloseCmd(chan.getId());
            chan.setClosed();
        }
    }
 
    synchronized void sendCloseAckCmd(MultiplexedChannel chan) throws IOException {
        if (running && chan.isOpen()) {
            debugPrint("send cmd=CLOSEACK id="+chan.getId());
            sender.sendCloseAckCmd(chan.getId());
            chan.setClosed();
        }
    }
    
    synchronized void sendRequestCmd(MultiplexedChannel chan, int len) throws IOException {
        if (running && chan.isOpen()) {
            debugPrint("send cmd=REQUEST id="+chan.getId()+" len="+len);
            sender.sendRequestCmd(chan.getId(), len);
        }
    }
    
    public void debugPrint(String x) {
        synchronized (sync) {
            System.err.println(name + ": " + x);
        }
    }
    
    public void shutdown() {
        if (!running) {
            return;
        }
        running = false;
        
        synchronized (channels) {
            for (MultiplexedChannel c : channels.values()) {
                c.disconnect();
            }
        }
        channels.clear();
        
        sender.shutdown();
        debugPrint("chan mpx stopped");
    }

    private String asString(int v) {
        switch (v) {
        case CMD_OPEN:
            return "OPEN";

        case CMD_CLOSE:
            return "CLOSE";

        case CMD_CLOSEACK:
            return "CLOSEACK";

        case CMD_TRANSMIT:
            return "TRANSMIT";
            
        case CMD_REQUEST:
            return "REQUEST";
        }
        return "<UNKNOWN>";
    }
}
