/*******************************************************************************
 * Copyright (c) 2016 Oak Ridge National Laboratory and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *******************************************************************************/
package org.eclipse.remote.internal.proxy.core.commands;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.remote.internal.proxy.core.ProxyConnection;
import org.eclipse.remote.proxy.core.MultiplexedChannel;
import org.eclipse.remote.proxy.core.Protocol;
import org.eclipse.remote.proxy.core.exceptions.ProxyException;

public class GetPropertiesCommand extends AbstractCommand<Map<String, String>> {

	private final ProxyConnection conn;
	private final DataOutputStream out;
	private final DataInputStream in;

	public GetPropertiesCommand(ProxyConnection conn) {
		this.conn = conn;
		this.out = new DataOutputStream(conn.getCommandChannel().getOutputStream());
		this.in = new DataInputStream(conn.getCommandChannel().getInputStream());
	}

	public Map<String, String> exec(IProgressMonitor monitor) throws ProxyException {
		try {
			final MultiplexedChannel chan = conn.openChannel();
			DataInputStream resultStream = new DataInputStream(chan.getInputStream());
			
			out.writeByte(Protocol.PROTO_COMMAND);
			out.writeShort(Protocol.CMD_GETPROPERTIES);
			out.writeByte(chan.getId());
			out.flush();
			
			byte res = in.readByte();
			if (res != Protocol.PROTO_OK) {
				String errMsg = in.readUTF();
				System.err.println("getproperties command failed:" + errMsg);
				throw new ProxyException(errMsg);
			}
			
			int len = resultStream.readInt();
			Map<String, String> props = new HashMap<String, String>(len);
			for (int i = 0; i < len; i++) {
				String key = resultStream.readUTF();
				String value = resultStream.readUTF();
				props.put(key, value);
			}
			resultStream.close();
			return props;
		} catch (IOException e) {
			throw new ProxyException(e.getMessage());
		}
	}
}
