package com.aoindustries.aoserv.daemon;

/*
 * Copyright 2000-2011 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
import com.aoindustries.aoserv.client.Protocol;
import com.aoindustries.aoserv.client.validator.NetPort;
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
    public final com.aoindustries.aoserv.client.validator.InetAddress serverBind;

    /**
     * The port that this server will listen on.
     */
    public final NetPort serverPort;

    /**
     * The protocol to support.
     */
    public final String protocol;

    /**
     * Creates a new, running <code>AOServServer</code>.
     */
    public AOServDaemonServer(com.aoindustries.aoserv.client.validator.InetAddress serverBind, NetPort serverPort, String protocol) {
	super(AOServDaemonServer.class.getName()+"?address="+serverBind+"&port="+serverPort+"&protocol="+protocol);
        this.serverBind = serverBind;
        this.serverPort = serverPort;
        this.protocol = protocol;

        start();
    }

    private static final Map<Long,DaemonAccessEntry> accessKeys=new HashMap<Long,DaemonAccessEntry>();
    private static long lastAccessKeyCleaning=-1;

    public static void grantDaemonAccess(long key, int command, String param1, String param2, String param3) {
        synchronized(accessKeys) {
            // Cleanup old data in the keys table
            if(lastAccessKeyCleaning==-1) lastAccessKeyCleaning=System.currentTimeMillis();
            else {
                long timeSince=System.currentTimeMillis()-lastAccessKeyCleaning;
                if(timeSince<0 || timeSince>=(5L*60*1000)) {
                    // Build a list of keys that should be removed
                    List<Long> removeKeys=new ArrayList<Long>();
                    Iterator I=accessKeys.keySet().iterator();
                    while(I.hasNext()) {
                        Long keyLong=(Long)I.next();
                        DaemonAccessEntry entry=accessKeys.get(keyLong);
                        timeSince=System.currentTimeMillis()-entry.created;
                        if(timeSince<0 || timeSince>=(60L*60*1000)) {
                            removeKeys.add(keyLong);
                        }
                    }

                    // Remove the keys
                    for(int c=0;c<removeKeys.size();c++) {
                        accessKeys.remove(removeKeys.get(c));
                    }

                    // Reset the clean time
                    lastAccessKeyCleaning=System.currentTimeMillis();
                }
            }

            // Add to the table
            accessKeys.put(Long.valueOf(key), new DaemonAccessEntry(key, command, param1, param2, param3));
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
                    System.out.print(address.getHostAddress());
                    System.out.print(':');
                    System.out.print(serverPort);
                    System.out.print(" (");
                    System.out.print(protocol);
                    System.out.println(')');
                }
                if(protocol.equals(Protocol.AOSERV_DAEMON)) {
                    ServerSocket SS = new ServerSocket(serverPort.getPort(), 50, address.getHostAddress().equals("0.0.0.0") ? null : address);
                    try {
                        while(true) {
                            Socket socket=SS.accept();
                            socket.setKeepAlive(true);
                            socket.setSoLinger(true, AOPool.DEFAULT_SOCKET_SO_LINGER);
                            socket.setTcpNoDelay(true);
                            new AOServDaemonServerThread(this, socket);
                        }
                    } finally {
                        SS.close();
                    }
                } else if(protocol.equals(Protocol.AOSERV_DAEMON_SSL)) {
                    SSLServerSocketFactory factory = (SSLServerSocketFactory)SSLServerSocketFactory.getDefault();
                    SSLServerSocket SS=(SSLServerSocket)factory.createServerSocket(serverPort.getPort(), 50, address);

                    try {
                        while (true) {
                            Socket socket=SS.accept();
                            try {
                                socket.setKeepAlive(true);
                                socket.setSoLinger(true, AOPool.DEFAULT_SOCKET_SO_LINGER);
                                socket.setTcpNoDelay(true);
                                new AOServDaemonServerThread(this, socket);
                            } catch(ThreadDeath TD) {
                                throw TD;
                            } catch(Throwable T) {
                                LogFactory.getLogger(AOServDaemonServer.class).log(Level.SEVERE, null, T);
                            }
                        }
                    } finally {
                        SS.close();
                    }

                } else throw new IllegalArgumentException("Unsupported protocol: "+protocol);
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