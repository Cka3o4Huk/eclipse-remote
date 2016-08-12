/*******************************************************************************
 * Copyright (c) 2016 Oak Ridge National Laboratory and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *******************************************************************************/
package org.eclipse.remote.internal.proxy.server.core;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import org.eclipse.remote.proxy.core.ChannelMultiplexer;
import org.eclipse.remote.proxy.core.ChannelMultiplexer.IChannelListener;
import org.eclipse.remote.proxy.core.MultiplexedChannel;

public class Server {
	private volatile boolean running;
	private Thread serverThread;
	private MultiplexedChannel cmdChannel;
	private Map<Integer, MultiplexedChannel> auxChannel = Collections.synchronizedMap(new HashMap<Integer, MultiplexedChannel>());
	
	
	public void start() {
		final ChannelMultiplexer mux = new ChannelMultiplexer("S", this, System.in, System.out);
		mux.addListener(new IChannelListener() {

			@Override
			public void newChannel(MultiplexedChannel chan) {
				Runnable runnable;
				// First channel opened becomes command channel
				if (cmdChannel == null) {
					cmdChannel = chan;
					runnable = new CommandServer(chan, Server.this);
					new Thread(runnable).start();
				} else {
					auxChannel.put(chan.getId(), chan);
				}
			}

			@Override
			public void closeChannel(MultiplexedChannel chan) {
				System.err.println("close channel: " + chan.getId());
				
			}
			
		});
		serverThread = new Thread() {
			@Override
			public void run() {
				running = true;
				mux.start();
				running = false;
			}
		};
		serverThread.start();
	}
	
	public MultiplexedChannel getChannel(int id) {
		return auxChannel.get(id);
	}
	
	public void stop() {
		if (running && serverThread != null) {
			try {
				serverThread.join();
			} catch (InterruptedException e) {
				// Ignore
			}
		}
		running = false;
	}
}
