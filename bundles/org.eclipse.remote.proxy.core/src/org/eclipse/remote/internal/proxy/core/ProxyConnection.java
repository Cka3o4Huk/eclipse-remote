/*******************************************************************************
 * Copyright (c) 2016 Oak Ridge National Laboratory and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *******************************************************************************/
package org.eclipse.remote.internal.proxy.core;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.SubMonitor;
import org.eclipse.remote.core.IRemoteConnection;
import org.eclipse.remote.core.IRemoteConnectionChangeListener;
import org.eclipse.remote.core.IRemoteConnectionControlService;
import org.eclipse.remote.core.IRemoteConnectionPropertyService;
import org.eclipse.remote.core.IRemoteProcessBuilder;
import org.eclipse.remote.core.IRemoteProcessService;
import org.eclipse.remote.core.IRemoteProxyService;
import org.eclipse.remote.core.RemoteConnectionChangeEvent;
import org.eclipse.remote.core.RemoteServicesUtils;
import org.eclipse.remote.core.exception.RemoteConnectionException;
import org.eclipse.remote.internal.proxy.core.commands.ExecCommand;
import org.eclipse.remote.internal.proxy.core.commands.GetCwdCommand;
import org.eclipse.remote.internal.proxy.core.commands.GetEnvCommand;
import org.eclipse.remote.internal.proxy.core.commands.GetPropertiesCommand;
import org.eclipse.remote.proxy.core.ChannelMultiplexer;
import org.eclipse.remote.proxy.core.MultiplexedChannel;
import org.eclipse.remote.proxy.core.exceptions.ProxyException;

/**
 * @since 5.0
 */
public class ProxyConnection implements IRemoteConnectionControlService,
		IRemoteConnectionChangeListener, IRemoteProxyService, IRemoteProcessService {

	private final boolean logging = false;

	public static final String EMPTY_STRING = ""; //$NON-NLS-1$

	private String fWorkingDir;
	private ChannelMultiplexer channelMux;
	private MultiplexedChannel commandChannel;
	private boolean isOpen;
	
	private final IRemoteConnection fRemoteConnection;

	private final Map<String, String> fEnv = new HashMap<>();
	private final Map<String, String> fProperties = new HashMap<>();

	private static final Map<IRemoteConnection, ProxyConnection> connectionMap = new HashMap<>();

	public ProxyConnection(IRemoteConnection connection) {
		fRemoteConnection = connection;
		connection.addConnectionChangeListener(this);
	}

	@Override
	public void connectionChanged(RemoteConnectionChangeEvent event) {
		if (event.getType() == RemoteConnectionChangeEvent.CONNECTION_REMOVED) {
			synchronized (connectionMap) {
				connectionMap.remove(event.getConnection());
			}
		}
	}

	/*
	 * (non-Javadoc)
	 *
	 * @see org.eclipse.remote.core.IRemoteConnection.Service#getRemoteConnection()
	 */
	@Override
	public IRemoteConnection getRemoteConnection() {
		return fRemoteConnection;
	}

	public static class Factory implements IRemoteConnection.Service.Factory {
		/*
		 * (non-Javadoc)
		 *
		 * @see org.eclipse.remote.core.IRemoteConnection.Service.Factory#getService(org.eclipse.remote.core.IRemoteConnection,
		 * java.lang.Class)
		 */
		@Override
		@SuppressWarnings("unchecked")
		public <T extends IRemoteConnection.Service> T getService(IRemoteConnection connection, Class<T> service) {
			// This little trick creates an instance of this class for a connection
			// then for each interface it implements, it returns the same object.
			// This works because the connection caches the service so only one gets created.
			// As a side effect, it makes this class a service too which can be used
			// by the this plug-in
			if (ProxyConnection.class.equals(service)) {
				synchronized (connectionMap) {
					ProxyConnection conn = connectionMap.get(connection);
					if (conn == null) {
						conn = new ProxyConnection(connection);
						connectionMap.put(connection, conn);
					}
					return (T) conn;
				}
			} else if (IRemoteConnectionControlService.class.equals(service)
					|| IRemoteConnectionPropertyService.class.equals(service) 
					|| IRemoteProcessService.class.equals(service)
					|| IRemoteProxyService.class.equals(service)) {
				return (T) connection.getService(ProxyConnection.class);
			} else {
				return null;
			}
		}
	}
	
	

	@Override
	public void setStreams(InputStream in, OutputStream out) {
		channelMux = new ChannelMultiplexer("", this, in, out);
	}

	/*
	 * (non-Javadoc)
	 *
	 * @see org.eclipse.remote.core.IRemoteConnectionControlService#close()
	 */
	@Override
	public synchronized void close() {
		fRemoteConnection.fireConnectionChangeEvent(RemoteConnectionChangeEvent.CONNECTION_CLOSED);
	}

	/*
	 * (non-Javadoc)
	 *
	 * @see org.eclipse.remote.core.IRemoteConnectionControlService#isOpen()
	 */
	@Override
	public boolean isOpen() {
		return isOpen;
	}

	/*
	 * (non-Javadoc)
	 *
	 * @see org.eclipse.remote.core.IRemoteConnectionControlService#open(org.eclipse.core.runtime.IProgressMonitor)
	 */
	@Override
	public void open(IProgressMonitor monitor) throws RemoteConnectionException {
		if (!isOpen && channelMux != null) {
			channelMux.start();
			try {
				commandChannel = channelMux.openChannel();
				initialize(monitor);
			} catch (RemoteConnectionException | IOException e) {
				try {
					commandChannel.close();
				} catch (IOException e1) {
					// Ignore
				}
				channelMux.shutdown();
				throw new RemoteConnectionException(e.getMessage());
			}
			isOpen = true;
			fRemoteConnection.fireConnectionChangeEvent(RemoteConnectionChangeEvent.CONNECTION_OPENED);
		}
	}
	
	private void initialize(IProgressMonitor monitor) throws RemoteConnectionException {
		SubMonitor subMon = SubMonitor.convert(monitor, 30);
		fWorkingDir = getCwd(subMon.newChild(10));
		if (subMon.isCanceled()) {
			throw new RemoteConnectionException("User canceled opening connection");
		}
		fEnv.putAll(loadEnv(subMon.newChild(10)));
		if (subMon.isCanceled()) {
			throw new RemoteConnectionException("User canceled opening connection");
		}
		fProperties.putAll(loadProperties(subMon.newChild(10)));
		if (subMon.isCanceled()) {
			throw new RemoteConnectionException("User canceled opening connection");
		}
	}

	private String getCwd(IProgressMonitor monitor) throws RemoteConnectionException {
		try {
			GetCwdCommand cmd = new GetCwdCommand(this);
			return cmd.exec(monitor);
		} catch (ProxyException e) {
			throw new RemoteConnectionException(e.getMessage());
		}
	}
	
	private Map<String, String> loadEnv(IProgressMonitor monitor) throws RemoteConnectionException {
		try {
			GetEnvCommand cmd = new GetEnvCommand(this);
			return cmd.exec(monitor);
		} catch (ProxyException e) {
			throw new RemoteConnectionException(e.getMessage());
		}
	}
	
	private Map<String, String> loadProperties(IProgressMonitor monitor) throws RemoteConnectionException {
		try {
			GetPropertiesCommand cmd = new GetPropertiesCommand(this);
			return cmd.exec(monitor);
		} catch (ProxyException e) {
			throw new RemoteConnectionException(e.getMessage());
		}
	}
	
	public Map<String, String> getEnv() {
		return Collections.unmodifiableMap(fEnv);
	}
	
	public MultiplexedChannel getCommandChannel() {
		return commandChannel;
	}
	
	public MultiplexedChannel openChannel() throws IOException {
		return channelMux.openChannel();
	}
	
	private StringBuffer stdout = new StringBuffer();
	private StringBuffer stderr = new StringBuffer();
	
	private String executeCommand(List<String> command, IProgressMonitor monitor) throws ProxyException {
		try {
			final MultiplexedChannel chanA = channelMux.openChannel();
			final MultiplexedChannel chanB = channelMux.openChannel();
			final MultiplexedChannel chanC = channelMux.openChannel();
			new Thread("cmd stdin reader") {
				@Override
				public void run() {
					byte[] buf = new byte[1024];
					int n;
					try {
						while ((n = chanA.getInputStream().read(buf)) >= 0) {
							stdout.append(new String(buf, 0, n));
						}
					} catch (IOException e) {
						// Finish
					}
				}
			}.start();
			new Thread("cmd stderr reader") {
				@Override
				public void run() {
					byte[] buf = new byte[1024];
					int n;
					try {
						while ((n = chanB.getInputStream().read(buf)) >= 0) {
							stderr.append(new String(buf, 0, n));
						}
					} catch (IOException e) {
						// Finish
					}
				}
			}.start();
			ExecCommand cmd = new ExecCommand(this, command, getEnv(), getWorkingDirectory(), false, false, chanA.getId(), chanB.getId(), chanC.getId());
			cmd.exec(monitor);
			DataInputStream status = new DataInputStream(chanC.getInputStream());
			int stat = status.readInt();
			if (stat == 0) {
				return stdout.toString();
			}
			return stderr.toString();
		} catch (IOException e) {
			throw new ProxyException(e.getMessage());
		}
	}

	@Override
	public String getEnv(String name) {
		return getEnv().get(name);
	}

	@Override
	public IRemoteProcessBuilder getProcessBuilder(List<String> command) {
		return new ProxyProcessBuilder(this, command);
	}

	@Override
	public IRemoteProcessBuilder getProcessBuilder(String... command) {
		return new ProxyProcessBuilder(this, command);
	}

	@Override
	public String getWorkingDirectory() {
		return fWorkingDir;
	}

	@Override
	public void setWorkingDirectory(String path) {
		if (RemoteServicesUtils.posixPath(path).isAbsolute()) {
			fWorkingDir = path;
		}
	}
}
