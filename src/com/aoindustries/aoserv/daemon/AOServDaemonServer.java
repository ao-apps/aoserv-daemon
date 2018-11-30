/*
 * Copyright 2012-2013, 2015, 2017, 2018 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
package com.aoindustries.aoserv.daemon;

import com.aoindustries.aoserv.client.net.AppProtocol;
import com.aoindustries.io.AOPool;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLServerSocketFactory;

/**
 * The <code>AOServServer</code> accepts connections from an <code>SimpleAOClient</code>.
 * Once the connection is accepted and authenticated, the server carries out all actions requested
 * by the client.
 * <p>
 * This server is completely threaded to handle multiple, simultaneous clients.
 * </p>
 * @author  AO Industries, Inc.
 */
final public class AOServDaemonServer extends Thread {

	/**
	 * The address that this server will bind to.
	 */
	private final com.aoindustries.net.InetAddress serverBind;

	/**
	 * The port that this server will listen on.
	 */
	public final int serverPort;

	/**
	 * The protocol to support.
	 */
	public final String protocol;

	/**
	 * Creates a new, running <code>AOServServer</code>.
	 */
	public AOServDaemonServer(com.aoindustries.net.InetAddress serverBind, int serverPort, String protocol) {
		super(AOServDaemonServer.class.getName()+"?address="+serverBind+"&port="+serverPort+"&protocol="+protocol);
		this.serverBind = serverBind;
		this.serverPort = serverPort;
		this.protocol = protocol;
	}

	private static final Map<Long,DaemonAccessEntry> accessKeys=new HashMap<>();
	private static long lastAccessKeyCleaning=-1;

	public static void grantDaemonAccess(long key, int command, String param1, String param2, String param3, String param4) {
		synchronized(accessKeys) {
			// Cleanup old data in the keys table
			if(lastAccessKeyCleaning == -1) lastAccessKeyCleaning = System.currentTimeMillis();
			else {
				long timeSince = System.currentTimeMillis() - lastAccessKeyCleaning;
				if(timeSince < 0 || timeSince >= (5L*60*1000)) {
					// Build a list of keys that should be removed
					List<Long> removeKeys=new ArrayList<>();
					Iterator<Long> I=accessKeys.keySet().iterator();
					while(I.hasNext()) {
						Long keyLong=I.next();
						DaemonAccessEntry entry=accessKeys.get(keyLong);
						timeSince=System.currentTimeMillis()-entry.created;
						if(timeSince<0 || timeSince>=(60L*60*1000)) {
							removeKeys.add(keyLong);
						}
					}

					// Remove the keys
					for (Long removeKey : removeKeys) {
						accessKeys.remove(removeKey);
					}

					// Reset the clean time
					lastAccessKeyCleaning=System.currentTimeMillis();
				}
			}

			// Add to the table
			accessKeys.put(key, new DaemonAccessEntry(key, command, param1, param2, param3, param4));
		}
	}

	public static DaemonAccessEntry getDaemonAccessEntry(long key) throws IOException {
		DaemonAccessEntry dae=accessKeys.remove(Long.valueOf(key));
		if(dae==null) throw new IOException("Unable to find DaemonAccessEntry: "+key);
		return dae;
	}

	@Override
	public void run() {
		while (true) {
			try {
				InetAddress address=InetAddress.getByName(serverBind.toString());
				synchronized(System.out) {
					System.out.print("Accepting connections on ");
					System.out.print(serverBind.toBracketedString());
					System.out.print(':');
					System.out.print(serverPort);
					System.out.print(" (");
					System.out.print(protocol);
					System.out.println(')');
				}
				switch (protocol) {
					case AppProtocol.AOSERV_DAEMON:
						try (ServerSocket SS = new ServerSocket(serverPort, 50, serverBind.isUnspecified() ? null : address)) {
							while(true) {
								Socket socket=SS.accept();
								socket.setKeepAlive(true);
								socket.setSoLinger(true, AOPool.DEFAULT_SOCKET_SO_LINGER);
								//socket.setTcpNoDelay(true);
								AOServDaemonServerThread thread = new AOServDaemonServerThread(this, socket);
								thread.start();
							}
						}
						// break;
					case AppProtocol.AOSERV_DAEMON_SSL:
						SSLServerSocketFactory factory = (SSLServerSocketFactory)SSLServerSocketFactory.getDefault();
						SSLServerSocket SS=(SSLServerSocket)factory.createServerSocket(serverPort, 50, address);
						try {
							while (true) {
								Socket socket=SS.accept();
								try {
									socket.setKeepAlive(true);
									socket.setSoLinger(true, AOPool.DEFAULT_SOCKET_SO_LINGER);
									//socket.setTcpNoDelay(true);
									AOServDaemonServerThread thread = new AOServDaemonServerThread(this, socket);
									thread.start();
								} catch(ThreadDeath TD) {
									throw TD;
								} catch(Throwable T) {
									LogFactory.getLogger(AOServDaemonServer.class).log(Level.SEVERE, null, T);
								}
							}
						} finally {
							SS.close();
						}
						// break;
					default:
						throw new IllegalArgumentException("Unsupported protocol: "+protocol);
				}
			} catch (ThreadDeath TD) {
				throw TD;
			} catch (Throwable T) {
				LogFactory.getLogger(AOServDaemonServer.class).log(Level.SEVERE, null, T);
			}
			try {
				sleep(60000);
			} catch (InterruptedException err) {
				LogFactory.getLogger(AOServDaemonServer.class).log(Level.WARNING, null, err);
			}
		}
	}
}
