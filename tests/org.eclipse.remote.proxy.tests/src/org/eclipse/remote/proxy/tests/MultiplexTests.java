package org.eclipse.remote.proxy.tests;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.eclipse.remote.proxy.core.ChannelMultiplexer;
import org.eclipse.remote.proxy.core.MultiplexedChannel;
import org.eclipse.remote.proxy.core.ChannelMultiplexer.IChannelListener;

import junit.framework.TestCase;

public class MultiplexTests extends TestCase {
	private static final int NUM_CHANS = 1;
	private static final int FINISH = -1;
	
	private class ChanReader implements Runnable {
		private DataInputStream in;
		private List<Integer> recvBufs;
		private String name;
		
		public ChanReader(MultiplexedChannel chan, List<Integer> recvBufs, String name) {
			this.in = new DataInputStream(chan.getInputStream());
			this.recvBufs = recvBufs;
			this.name = name;
		}
		
		public String getName() {
			return name;
		}
		
		@Override
		public void run() {
			try {
				synchronized (MultiplexTests.this) {
					System.out.println(getName() + " started");
				}
				try {
					while (true) {
						int val = in.readInt();
						if (val == FINISH) {
							break;
						}
						recvBufs.add(val);
					}
				} catch (EOFException e) {
					// Finish
				}
				synchronized (MultiplexTests.this) {
					System.out.println(getName() + " finished");
				}
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
	}
	
	private class ChanWriter implements Runnable {
		private DataOutputStream out;
		private List<Integer> sentBufs;
		private Random r = new Random();
		private String name;
		
		public ChanWriter(MultiplexedChannel chan, List<Integer> sentBufs, String name) {
			this.out = new DataOutputStream(chan.getOutputStream());
			this.sentBufs = sentBufs;
			this.name = name;
		}
		
		public String getName() {
			return name;
		}
		
		@Override
		public void run() {
			try{
				synchronized (MultiplexTests.this) {
					System.out.println(getName() + " started");
				}
				for (int i = 0; i < 100; i++) {
					int val = r.nextInt(1024);
					out.writeInt(val);
					out.flush();
					sentBufs.add(val);
					try {
						Thread.sleep(r.nextInt(100));
					} catch (InterruptedException e) {
						fail(e.getMessage());
					}
				}
				out.writeInt(FINISH);
				out.flush();
				synchronized (MultiplexTests.this) {
					System.out.println(getName() + " finished");
				}
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
	}
	
	private class ChanReaderWriter implements Runnable {
		private DataInputStream in;
		private DataOutputStream out;
		private String name;
		
		public ChanReaderWriter(MultiplexedChannel chan, String name) {
			this.in = new DataInputStream(chan.getInputStream());
			this.out = new DataOutputStream(chan.getOutputStream());
			this.name = name;
		}
		
		public String getName() {
			return name;
		}
		
		@Override
		public void run() {
			try{
				synchronized (MultiplexTests.this) {
					System.out.println(getName() + " started");
				}
				try {
					while (true) {
						int val = in.readInt();
						out.writeInt(val);
						out.flush();
					}
				} catch (EOFException e) {
					// Finish
				}
				synchronized (MultiplexTests.this) {
					System.out.println(getName() + " finished");
				}
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
	}


	public void testChannels() {
		try {
			final PipedInputStream inClnt = new PipedInputStream();
			final PipedInputStream inSvr = new PipedInputStream();
			final PipedOutputStream outClnt = new PipedOutputStream(inSvr);
			final PipedOutputStream outSvr = new PipedOutputStream(inClnt);
			
			final List<List<Integer>> clntSentBufs = new ArrayList<List<Integer>>();
			final List<List<Integer>> clntRecvBufs = new ArrayList<List<Integer>>();
			
			final Thread[] clntReaders = new Thread[NUM_CHANS];
			final Thread[] clntWriters = new Thread[NUM_CHANS];
			final Thread[] svrRW = new Thread[NUM_CHANS];
			
			for (int i = 0; i < NUM_CHANS; i++) {
				clntSentBufs.add(new ArrayList<Integer>());
				clntRecvBufs.add(new ArrayList<Integer>());
			}

			// Must start server first or it will miss the new channel message
			ChannelMultiplexer mpxSvr = startMpxServer(inSvr, outSvr, svrRW);
			
			ChannelMultiplexer mpxClnt = startMpxClient(inClnt, outClnt);
			
			List<MultiplexedChannel> channels = runChannelTest(mpxClnt, clntReaders, clntWriters, clntSentBufs, clntRecvBufs);
			
			for (int i = 0; i < NUM_CHANS; i++) {
				clntWriters[i].join();
				clntReaders[i].join();
			}
			
			for (MultiplexedChannel channel : channels) {
				channel.close();
			}

			for (int i = 0; i < NUM_CHANS; i++) {
				svrRW[i].join();
			}

			for (int i = 0; i < NUM_CHANS; i++) {
				assertEquals(clntSentBufs.get(i), clntRecvBufs.get(i));
			}
		} catch (IOException | InterruptedException e) {
			fail(e.getMessage());
		}
	}
	
	private List<MultiplexedChannel> runChannelTest(ChannelMultiplexer mpx, Thread[] readers, Thread[] writers, final List<List<Integer>> sentBufs, final List<List<Integer>> recvBufs) throws IOException {
		List<MultiplexedChannel> channels = new ArrayList<MultiplexedChannel>();
		for (int i = 0 ; i < NUM_CHANS; i++) {
			MultiplexedChannel chan = mpx.openChannel(); // needs to be in same thread as reader
			ChanReader reader = new ChanReader(chan, recvBufs.get(chan.getId()), "clnt reader thread " + chan.getId());
			readers[chan.getId()] = new Thread(reader, reader.getName());
			ChanWriter writer = new ChanWriter(chan, sentBufs.get(chan.getId()), "clnt writer thread " + chan.getId());
			writers[chan.getId()] = new Thread(writer, writer.getName());
			readers[chan.getId()].start();
			writers[chan.getId()].start();
			channels.add(chan);
		}
		return channels;
	}

	private ChannelMultiplexer startMpxClient(InputStream in, OutputStream out) {
		final ChannelMultiplexer mpx = new ChannelMultiplexer("C", MultiplexTests.this, in, out);
		mpx.start();
		return mpx;
	}
	
	private ChannelMultiplexer startMpxServer(InputStream in, OutputStream out, final Thread[] rws) throws IOException {
		final ChannelMultiplexer mpx = new ChannelMultiplexer("S", MultiplexTests.this, in, out);
		mpx.addListener(new IChannelListener() {
			@Override
			public void newChannel(final MultiplexedChannel chan) {
				synchronized (MultiplexTests.this) {
					System.err.println("newChannel "+chan.getId());
				}

				ChanReaderWriter rw = new ChanReaderWriter(chan, "svr rw thread " + chan.getId());
				rws[chan.getId()] = new Thread(rw, rw.getName());
				rws[chan.getId()].start();
			}

			@Override
			public void closeChannel(MultiplexedChannel chan) {
//					readers[idx].interrupt();
//					writers[idx].interrupt();
				synchronized (MultiplexTests.this) {
					System.err.println("closeChannel "+ chan.getId());
				}
			}
		});
		mpx.start();
		return mpx;
	}
	
	@Override
	protected void setUp() throws Exception {
	}
	
	@Override
	protected void tearDown() throws Exception {
	}
}
