/*******************************************************************************
 * Copyright (c) 2016 Oak Ridge National Laboratory and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *******************************************************************************/
package org.eclipse.remote.internal.proxy.server.commands;

import java.io.DataOutputStream;
import java.io.IOException;

import org.eclipse.remote.proxy.core.MultiplexedChannel;
import org.eclipse.remote.proxy.core.exceptions.ProxyException;

public class ServerGetCwdCommand extends AbstractServerCommand {

	private String cwd;
	private final DataOutputStream result;
	
	private class CommandRunner implements Runnable {
		@Override
		public void run() {
			try {
				result.writeUTF(cwd);
				result.flush();
			} catch (IOException e) {
				// Failed
			}
		}
	}
	
	public ServerGetCwdCommand(MultiplexedChannel chan) {
		this.result = new DataOutputStream(chan.getOutputStream());
	}

	public void exec() throws ProxyException {
		cwd = System.getProperty("user.dir");
		new Thread(new CommandRunner()).start();
	}
}
