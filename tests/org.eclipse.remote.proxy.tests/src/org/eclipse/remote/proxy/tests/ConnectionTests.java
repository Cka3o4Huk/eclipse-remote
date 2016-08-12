package org.eclipse.remote.proxy.tests;

import java.io.BufferedInputStream;
import java.io.IOException;

import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.remote.core.IRemoteConnection;
import org.eclipse.remote.core.IRemoteConnectionType;
import org.eclipse.remote.core.IRemoteProxyService;
import org.eclipse.remote.core.IRemoteServicesManager;
import org.eclipse.remote.core.exception.RemoteConnectionException;

import junit.framework.TestCase;

public class ConnectionTests extends TestCase {

	private IRemoteConnectionType connType;
	
	public void testProxyConnection() {
		try {
			final Process proc = Runtime.getRuntime().exec("java"
					+ " -cp /Users/gw6/Work/git/org.eclipse.remote/releng/org.eclipse.remote.proxy.server.product/target/products/proxy.server/macosx/cocoa/x86_64/Proxy.app/Contents/Eclipse/plugins/org.eclipse.equinox.launcher_1.3.200.v20160318-1642.jar"
					+ " org.eclipse.equinox.launcher.Main"
					+ " -application org.eclipse.remote.proxy.server.core.application"
					+ " -noExit");
			assertTrue(proc.isAlive());
			
			new Thread("stderr") {
				private byte[] buf = new byte[1024];
				@Override
				public void run() {
					int n;
					BufferedInputStream err = new BufferedInputStream(proc.getErrorStream());
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
				
			}.start();
			
			IRemoteConnection conn = connType.newConnection("test");
			assertNotNull(conn);
			IRemoteProxyService proxy = conn.getService(IRemoteProxyService.class);
			assertNotNull(proxy);
			proxy.setStreams(proc.getInputStream(), proc.getOutputStream());
			conn.open(new NullProgressMonitor());
			conn.close();
			
			proc.destroy();
			proc.waitFor();
			assertEquals(false, proc.isAlive());
		} catch (IOException | RemoteConnectionException | InterruptedException e) {
			fail(e.getMessage());
		}
	}
	
	@Override
	protected void setUp() throws Exception {
		IRemoteServicesManager manager = Activator.getService(IRemoteServicesManager.class);
		connType = manager.getConnectionType("org.eclipse.remote.Proxy"); //$NON-NLS-1$
		assertNotNull(connType);
	}
	
	@Override
	protected void tearDown() throws Exception {
	}
}
