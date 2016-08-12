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

import org.eclipse.core.filesystem.IFileInfo;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.remote.internal.proxy.core.ProxyConnection;
import org.eclipse.remote.proxy.core.MultiplexedChannel;
import org.eclipse.remote.proxy.core.Protocol;
import org.eclipse.remote.proxy.core.SerializableFileInfo;
import org.eclipse.remote.proxy.core.exceptions.ProxyException;

public class ChildInfosCommand extends AbstractCommand<IFileInfo[]> {

	private final ProxyConnection conn;
	private final DataOutputStream out;
	private final DataInputStream in;
	private final String path;

	public ChildInfosCommand(ProxyConnection conn, String path) {
		this.conn = conn;
		this.out = new DataOutputStream(conn.getCommandChannel().getOutputStream());
		this.in = new DataInputStream(conn.getCommandChannel().getInputStream());
		this.path = path;
	}

	public IFileInfo[] exec(IProgressMonitor monitor) throws ProxyException {
		try {
			final MultiplexedChannel chan = conn.openChannel();
			
			out.writeByte(Protocol.PROTO_COMMAND);
			out.writeShort(Protocol.CMD_CHILDINFOS);
			out.writeByte(chan.getId());
			out.writeUTF(path);
			out.flush();
			
			byte res = in.readByte();
			if (res != Protocol.PROTO_OK) {
				String errMsg = in.readUTF();
				System.err.println("childinfos command failed:" + errMsg);
				throw new ProxyException(errMsg);
			}
			
			DataInputStream resultStream = new DataInputStream(chan.getInputStream());
			int length = resultStream.readInt();
			SerializableFileInfo sInfo = new SerializableFileInfo();
			IFileInfo[] infos = new IFileInfo[length];
			for (int i = 0; i < length; i++) {
				sInfo.readObject(resultStream);
				infos[i] = sInfo.getIFileInfo();
			}
			resultStream.close();
			return infos;
		} catch (IOException e) {
			throw new ProxyException(e.getMessage());
		}
	}
}
