package org.eclipse.remote.proxy.tests;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.remote.core.IRemoteConnection;
import org.eclipse.remote.core.IRemoteConnectionType;
import org.eclipse.remote.core.IRemoteConnectionWorkingCopy;
import org.eclipse.remote.core.IRemoteProcess;
import org.eclipse.remote.core.IRemoteProcessBuilder;
import org.eclipse.remote.core.IRemoteProcessService;
import org.eclipse.remote.core.IRemoteProxyService;
import org.eclipse.remote.core.IRemoteServicesManager;
import org.eclipse.remote.core.RemoteProcessAdapter;

import junit.framework.TestCase;

public class ProcessTests extends TestCase {
	private static final String CONNECTION_NAME = "test_connection";
	private static final int NUM_THREADS = 1;

	private IRemoteConnectionType connType;
	private IRemoteConnection connection;
	private IRemoteProcessService processService;
	private Process server;

	public void xtestConcurrentProcess() {
		Thread[] threads = new Thread[NUM_THREADS];

		for (int t = 0; t < NUM_THREADS; t++) {
			final String threadNum = Integer.toString(t);
			Thread thread = new Thread("test thread " + t) {
				@Override
				public void run() {
					final Set<String> results = Collections.synchronizedSet(new HashSet<String>());
					IRemoteProcessBuilder builder = processService.getProcessBuilder("perl", "-v", threadNum); //$NON-NLS-1$
					assertNotNull(builder);
					builder.redirectErrorStream(true);
					for (int i = 0; i < 10; i++) {
						try {
							IRemoteProcess proc = builder.start();
							BufferedReader stdout = new BufferedReader(new InputStreamReader(proc.getInputStream()));
							String line;
							while ((line = stdout.readLine()) != null) {
								results.add(line);
								results.add("\n");
							}
							try {
								proc.waitFor();
							} catch (InterruptedException e) {
								e.printStackTrace();
							}
							assertTrue(results.toString().contains("Larry Wall"));
						} catch (IOException e) {
							e.printStackTrace();
							fail(e.getLocalizedMessage());
						}
					}
				}

			};
			thread.start();
			threads[t] = thread;
		}
		for (Thread t : threads) {
			try {
				t.join();
			} catch (InterruptedException e) {
			}
		}
	}

	public void testEnv() {
		IRemoteProcessBuilder builder = processService.getProcessBuilder("printenv"); //$NON-NLS-1$
		assertNotNull(builder);
		builder.redirectErrorStream(true);
		String path = builder.environment().get("PATH");
		builder.environment().clear();
		builder.environment().put("PATH", path);
		try {
			IRemoteProcess proc = builder.start();
			BufferedReader stdout = new BufferedReader(new InputStreamReader(proc.getInputStream()));
			String line;
			String result = null;
			while ((line = stdout.readLine()) != null) {
				assertNull(result);
				result = line;
				break;
			}
			assertEquals(result, "PATH=" + path);
		} catch (IOException e) {
			e.printStackTrace();
			fail(e.getLocalizedMessage());
		}
	}

	public void testEcho() {
		IRemoteProcessBuilder builder = processService.getProcessBuilder("cat"); //$NON-NLS-1$
		assertNotNull(builder);
		builder.redirectErrorStream(true);
		final StringBuffer result = new StringBuffer();
		try {
			final IRemoteProcess proc = builder.start();
			Thread readerThread = new Thread("echo reader thread") {
				@Override
				public void run() {
					try {
						BufferedReader stdout = new BufferedReader(new InputStreamReader(proc.getInputStream()));
						String line;
						while ((line = stdout.readLine()) != null) {
							result.append(line);
						}
						try {
							proc.destroy();
							proc.waitFor();
						} catch (InterruptedException e) {
							e.printStackTrace();
						}
					} catch (IOException e) {
						e.printStackTrace();
						fail(e.getLocalizedMessage());
					}
				}

			};
			Thread writerThread = new Thread("echo writer thread") {
				@Override
				public void run() {
					try {
						BufferedWriter stdin = new BufferedWriter(new OutputStreamWriter(proc.getOutputStream()));
						for (int i = 0; i < 10; i++) {
							String line = i + "\n";
							stdin.append(line);
							stdin.flush();
						}
						proc.getOutputStream().close();
						try {
							proc.waitFor();
						} catch (InterruptedException e) {
							e.printStackTrace();
						}
					} catch (IOException e) {
						e.printStackTrace();
						fail(e.getLocalizedMessage());
					}
				}

			};
			writerThread.start();
			readerThread.start();
			writerThread.join();
			readerThread.join();
		} catch (IOException e) {
			e.printStackTrace();
			fail(e.getLocalizedMessage());
		} catch (InterruptedException e) {
			e.printStackTrace();
			fail(e.getLocalizedMessage());
		}

		assertEquals("0123456789", result.toString());
	}

	public void testExitValue() {
		IRemoteProcessBuilder builder = processService.getProcessBuilder(new String[]{"sleep","60"}); //$NON-NLS-1$
		assertNotNull(builder);
		IRemoteProcess rp = null;
		try {
			rp = builder.start();
		} catch (IOException e) {
			e.printStackTrace();
			fail(e.getLocalizedMessage());
		}
		assertNotNull(rp);
		Process p = new RemoteProcessAdapter(rp);
		try {
			p.exitValue();
			fail("Process has not exited. Should throws an IllegalThreadStateException exception");
		} catch(IllegalThreadStateException e) {
			e.printStackTrace();
		}
	}
	
	/*
	 * (non-Javadoc)
	 * 
	 * @see junit.framework.TestCase#setUp()
	 */
	@Override
	protected void setUp() throws Exception {
		IRemoteServicesManager manager = Activator.getService(IRemoteServicesManager.class);
		connType = manager.getConnectionType("org.eclipse.remote.Proxy"); //$NON-NLS-1$
		assertNotNull(connType);

		IRemoteConnectionWorkingCopy wc = connType.newConnection(CONNECTION_NAME);
		connection = wc.save();
		assertNotNull(connection);
		
		server = Runtime.getRuntime().exec("java"
				+ " -cp ../../releng/org.eclipse.remote.proxy.server.product/target/products/proxy.server/macosx/cocoa/x86_64/Proxy.app/Contents/Eclipse/plugins/org.eclipse.equinox.launcher_1.3.200.v20160318-1642.jar"
				+ " org.eclipse.equinox.launcher.Main"
				+ " -application org.eclipse.remote.proxy.server.core.application"
				+ " -noExit");
		assertTrue(server.isAlive());
		
		Thread err = new Thread("stderr") {
			private byte[] buf = new byte[1024];
			@Override
			public void run() {
				int n;
				BufferedInputStream err = new BufferedInputStream(server.getErrorStream());
				try {
					while ((n = err.read(buf)) >= 0) {
						if (n > 0) {
							System.err.println("server: " + new String(buf, 0, n));
						}
					}
				} catch (IOException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}				
			}
			
		};
		err.start();

		IRemoteProxyService proxy = connection.getService(IRemoteProxyService.class);
		assertNotNull(proxy);
		proxy.setStreams(server.getInputStream(), server.getOutputStream());
		
		connection.open(new NullProgressMonitor());
		assertTrue(connection.isOpen());
		
		processService = connection.getService(IRemoteProcessService.class);
		assertNotNull(processService);
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see junit.framework.TestCase#tearDown()
	 */
	@Override
	protected void tearDown() throws Exception {
		server.destroyForcibly();
		connType.removeConnection(connection);
	}

}
