/*******************************************************************************
 * Copyright (c) 2016 Oak Ridge National Laboratory and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *******************************************************************************/
package org.eclipse.remote.internal.proxy.server.commands;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.Map;

import org.eclipse.remote.proxy.core.MultiplexedChannel;
import org.eclipse.remote.proxy.core.exceptions.ProxyException;

/**
 * TODO: Fix hang if command fails...
 *
 */
public class ServerExecCommand extends AbstractServerCommand {

	private final List<String> command;
	private final Map<String, String> env;
	private final boolean redirect;
	private final boolean appendEnv;
	private final String directory;
	private final InputStream stdin;
	private final OutputStream stdout;
	private final OutputStream stderr;
	private final DataOutputStream result;
	private final DataInputStream cmd;
	
	private class CommandRunner implements Runnable {
		@Override
		public void run() {
			ProcessBuilder builder = new ProcessBuilder(command);
			try {
				if (!appendEnv) {
					builder.environment().clear();
					builder.environment().putAll(env);
				} else {
					for (Map.Entry<String, String> entry : env.entrySet()) {
						String val = builder.environment().get(entry.getKey());
						if (val == null || !val.equals(entry.getValue())) {
							builder.environment().put(entry.getKey(), entry.getValue());
						}
					}
				}
			} catch (UnsupportedOperationException | IllegalArgumentException  e) {
				// Leave environment untouched
			}
			File dir = new File(directory);
			if (dir.exists() && dir.isAbsolute()) {
				builder.directory(dir);
			}
			builder.redirectErrorStream(redirect);
			try {
				Process proc = builder.start();
				startForwarder("stdout", proc.getInputStream(), stdout);
				startForwarder("stderr", proc.getErrorStream(), stderr);
				startForwarder("stdin", stdin, proc.getOutputStream());
				System.err.println("waiting for proc");
				while (cmd.available() == 0 && proc.isAlive()) {
					Thread.sleep(500);
				}
				System.err.println("done waiting for proc");
				if (cmd.available() > 0) {
					cmd.readByte();
					proc.destroyForcibly();
				}
				int exit = proc.waitFor();
				System.err.println("exit status="+exit);
				result.writeInt(exit);
			} catch (IOException | InterruptedException e) {
				// Ignore?
			}
		}
	}
	
	private class Forwarder implements Runnable {
		private final InputStream in;
		private final OutputStream out;
		
		public Forwarder(InputStream in, OutputStream out) {
			this.in = in;
			this.out = out;
		}

		@Override
		public void run() {
			byte[] buf = new byte[8192];
			int n;
			try {
				while ((n = in.read(buf)) >= 0) {
					if (n > 0) {
						out.write(buf, 0, n);
						out.flush();
					}
				}
			} catch (IOException e) {
				// Finish
			}
		}
	}

	public ServerExecCommand(List<String> command, Map<String, String> env, String directory, boolean redirect, boolean appendEnv, MultiplexedChannel chanA, MultiplexedChannel chanB, MultiplexedChannel chanC) {
		this.command = command;
		this.env = env;
		this.directory = directory;
		this.redirect = redirect;
		this.appendEnv = appendEnv;
		this.stdin = chanA.getInputStream();
		this.stdout = chanA.getOutputStream();
		this.stderr = chanB.getOutputStream();
		this.result = new DataOutputStream(chanC.getOutputStream());
		this.cmd = new DataInputStream(chanC.getInputStream());
	}

	public void exec() throws ProxyException {
		new Thread(new CommandRunner(), command.get(0)).start();
	}
	
	private void startForwarder(String name, InputStream in, OutputStream out) {
		Forwarder forwarder = new Forwarder(in, out);
		new Thread(forwarder, command.get(0) + " " + name).start();
	}
}
