package com.aoindustries.aoserv.daemon;

/*
 * Copyright 2000-2008 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
import com.aoindustries.aoserv.client.*;
import com.aoindustries.io.AOPool;
import com.aoindustries.profiler.*;
import com.aoindustries.util.StringUtility;
import java.io.*;
import java.net.*;
import java.security.*;
import java.sql.*;
import java.util.*;
import javax.net.ssl.*;

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
    public final String serverBind;

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
    public AOServDaemonServer(String serverBind, int serverPort, String protocol) {
	super(AOServDaemonServer.class.getName()+"?address="+serverBind+"&port="+serverPort+"&protocol="+protocol);
        Profiler.startProfile(Profiler.FAST, AOServDaemonServer.class, "<init>(String,int,String)", null);
        try {
            this.serverBind = serverBind;
            this.serverPort = serverPort;
            this.protocol = protocol;

            start();
        } finally {
            Profiler.endProfile(Profiler.FAST);
        }
    }

    private static final Map<Long,DaemonAccessEntry> accessKeys=new HashMap<Long,DaemonAccessEntry>();
    private static long lastAccessKeyCleaning=-1;

    public static void grantDaemonAccess(long key, int command, String param1, String param2, String param3) {
        Profiler.startProfile(Profiler.UNKNOWN, AOServDaemonServer.class, "grantDaemonAccess(long,int,String,String,String)", null);
        try {
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
                            DaemonAccessEntry entry=(DaemonAccessEntry)accessKeys.get(keyLong);
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
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }

    public static DaemonAccessEntry getDaemonAccessEntry(long key) throws IOException {
        Profiler.startProfile(Profiler.FAST, AOServDaemonServer.class, "getDaemonAccessEntry(long)", null);
        try {
            DaemonAccessEntry dae=accessKeys.remove(Long.valueOf(key));
            if(dae==null) throw new IOException("Unable to find DaemonAccessEntry: "+key);
            return dae;
        } finally {
            Profiler.endProfile(Profiler.FAST);
        }
    }

    public void run() {
        Profiler.startProfile(Profiler.UNKNOWN, AOServDaemonServer.class, "run()", null);
        try {
            while (true) {
                try {
                    InetAddress address=InetAddress.getByName(serverBind);
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
                        ServerSocket SS = new ServerSocket(serverPort, 50, address.getHostAddress().equals("0.0.0.0") ? null : address);
                        try {
                            while(true) {
                                Socket socket=SS.accept();
                                socket.setKeepAlive(true);
                                socket.setSoLinger(true, AOPool.DEFAULT_SOCKET_SO_LINGER);
                                //socket.setTcpNoDelay(true);
                                new AOServDaemonServerThread(this, socket);
                            }
                        } finally {
                            SS.close();
                        }
                    } else if(protocol.equals(Protocol.AOSERV_DAEMON_SSL)) {
                        SSLServerSocketFactory factory = (SSLServerSocketFactory)SSLServerSocketFactory.getDefault();
                        SSLServerSocket SS=(SSLServerSocket)factory.createServerSocket(serverPort, 50, address);

                        try {
                            while (true) {
                                Socket socket=SS.accept();
                                try {
                                    socket.setKeepAlive(true);
                                    socket.setSoLinger(true, AOPool.DEFAULT_SOCKET_SO_LINGER);
                                    //socket.setTcpNoDelay(true);
                                    new AOServDaemonServerThread(this, socket);
                                } catch(ThreadDeath TD) {
                                    throw TD;
                                } catch(Throwable T) {
                                    AOServDaemon.reportError(T, null);
                                }
                            }
                        } finally {
                            SS.close();
                        }

                    } else throw new IllegalArgumentException("Unsupported protocol: "+protocol);
                } catch (ThreadDeath TD) {
                    throw TD;
                } catch (Throwable T) {
                    AOServDaemon.reportError(T, null);
                }
                try {
                    sleep(60000);
                } catch (InterruptedException err) {
                    AOServDaemon.reportWarning(err, null);
                }
            }
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }
}