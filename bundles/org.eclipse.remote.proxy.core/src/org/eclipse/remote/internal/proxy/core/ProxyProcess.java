/*******************************************************************************
 * Copyright (c) 2016 Oak Ridge National Laboratory and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *******************************************************************************/
package org.eclipse.remote.internal.proxy.core;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import org.eclipse.remote.core.IRemoteConnection;
import org.eclipse.remote.core.IRemoteProcess;
import org.eclipse.remote.core.IRemoteProcessBuilder;
import org.eclipse.remote.core.IRemoteProcessControlService;
import org.eclipse.remote.core.IRemoteProcessTerminalService;
import org.eclipse.remote.internal.core.RemoteProcess;

public class ProxyProcess extends RemoteProcess implements IRemoteProcessControlService, IRemoteProcessTerminalService {
	private IRemoteProcess remoteProcess;
	private int width, height;
	private final InputStream procStdout;
	private final InputStream procStderr;
	private final OutputStream procStdin;
	private final DataOutputStream cmdStream;
	private final DataInputStream resultStream;
	private final Thread cmdThread;
	
	private volatile int exitValue;
	private volatile boolean isCompleted;

	public static class Factory implements IRemoteProcess.Service.Factory {
		/*
		 * (non-Javadoc)
		 * 
		 * @see org.eclipse.remote.core.IRemoteProcess.Service.Factory#getService(org.eclipse.remote.core.IRemoteProcess,
		 * java.lang.Class)
		 */
		@Override
		public <T extends IRemoteProcess.Service> T getService(IRemoteProcess remoteProcess, Class<T> service) {
			return null;
		}
	}

	public ProxyProcess(IRemoteConnection conn, IRemoteProcessBuilder builder, final InputStream stdout, final InputStream stderr, final OutputStream stdin, final InputStream result, final OutputStream cmd) {
		super(conn, builder);
		
		procStdout = stdout;
		procStderr = stderr;
		procStdin = stdin;
		cmdStream = new DataOutputStream(cmd);
		resultStream = new DataInputStream(result);
		isCompleted = false;
		exitValue = 0;
		
		cmdThread = new Thread("process result reader") {
			@Override
			public void run() {
				try {
					exitValue = resultStream.readInt();
				} catch (IOException e) {
					// Finish
				}
				isCompleted = true;
			}
		};
		cmdThread.start();
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see java.lang.Process#destroy()
	 */
	@Override
	public void destroy() {
		try {
			cmdStream.writeByte(0);
			cmdStream.flush();
		} catch (IOException e) {
			isCompleted = true;
		}
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see java.lang.Process#exitValue()
	 */
	@Override
	public int exitValue() {
		if (!isCompleted) {
			throw new IllegalStateException();
		}
		return exitValue;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see java.lang.Process#getErrorStream()
	 */
	@Override
	public InputStream getErrorStream() {
		return procStderr;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see java.lang.Process#getInputStream()
	 */
	@Override
	public InputStream getInputStream() {
		return procStdout;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see java.lang.Process#getOutputStream()
	 */
	@Override
	public OutputStream getOutputStream() {
		return procStdin;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see java.lang.Process#waitFor()
	 */
	@Override
	public int waitFor() throws InterruptedException {
		cmdThread.join();
		return exitValue;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.eclipse.remote.core.RemoteProcess#isCompleted()
	 */
	@Override
	public boolean isCompleted() {
		return isCompleted;
	}

	@Override
	public IRemoteProcess getRemoteProcess() {
		return remoteProcess;
	}

	@Override
	public void setTerminalSize(int cols, int rows, int pwidth, int pheight) {
	}
}
