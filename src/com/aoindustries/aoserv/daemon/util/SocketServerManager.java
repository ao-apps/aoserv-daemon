package com.aoindustries.aoserv.daemon.util;

/*
 * Copyright 2002-2009 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
import com.aoindustries.aoserv.client.IPAddress;
import com.aoindustries.aoserv.client.NetBind;
import com.aoindustries.aoserv.daemon.AOServDaemon;
import java.io.IOException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Manages a group of <code>SocketServer</code>.  Servers may be
 * added and removed on the fly.
 *
 * @author  AO Industries, Inc.
 */
abstract public class SocketServerManager {

    /** All of the servers that are currently running */
    private final List<SocketServerThread> socketServers=new ArrayList<SocketServerThread>();

    public SocketServerManager() {
    }

    private boolean started=false;
    final public void start() throws IOException, SQLException {
        if(!started) {
            synchronized(System.out) {
                if(!started) {
                    System.out.print("Starting ");
                    System.out.print(getManagerName());
                    System.out.println(":");
                    startImpl();
                    boolean done=false;
                    while(!done) {
                        try {
                            verifySocketServers();
                            done=true;
                        } catch(ThreadDeath TD) {
                            throw TD;
                        } catch(Throwable T) {
                            AOServDaemon.reportError(T, null);
                            try {
                                Thread.sleep(15000);
                            } catch(InterruptedException err) {
                                AOServDaemon.reportWarning(err, null);
                            }
                        }
                    }
                    started=true;
                }
            }
        }
    }
    
    protected void startImpl() throws IOException, SQLException {
    }

    protected void verifySocketServers() throws IOException, SQLException {
        synchronized(this) {
            NetBind[] nbs=getNetBinds();

            // Create the existing list
            List<SocketServerThread> existing=new ArrayList<SocketServerThread>(socketServers.size());
            for(int c=0;c<socketServers.size();c++) existing.add(socketServers.get(c));

            for(int c=0;c<nbs.length;c++) {
                NetBind nb=nbs[c];
                String nbIP=nb.getIPAddress().getIPAddress();
                if(
                    !nbIP.equals(IPAddress.LOOPBACK_IP)
                    && !nbIP.equals(IPAddress.WILDCARD_IP)
                ) {
                    int nbPort=nb.getPort().getPort();

                    // Find in the existing list
                    boolean found=false;
                    for(int d=0;d<existing.size();d++) {
                        SocketServerThread socketServer=(SocketServerThread)existing.get(d);
                        if(socketServer.runMore && socketServer.ipAddress.equals(nbIP) && socketServer.port==nbPort) {
                            existing.remove(d);
                            found=true;
                            break;
                        }
                    }
                    if(!found) {
                        // Add a new one
                        synchronized(System.out) {
                            System.out.print("Starting ");
                            System.out.print(getServiceName());
                            System.out.print(" on ");
                            System.out.print(nbIP);
                            System.out.print(':');
                            System.out.print(nbPort);
                            System.out.print(": ");
                            SocketServerThread newServer=createSocketServerThread(nbIP, nbPort);
                            socketServers.add(newServer);
                            newServer.start();
                            System.out.println("Done");
                        }
                    }
                }
            }
            // Shut down the extra ones
            for(int c=0;c<existing.size();c++) {
                SocketServerThread socketServer=(SocketServerThread)existing.get(c);
                synchronized(System.out) {
                    System.out.print("Stopping ");
                    System.out.print(getServiceName());
                    System.out.print(" on ");
                    System.out.print(socketServer.ipAddress);
                    System.out.print(':');
                    System.out.print(socketServer.port);
                    System.out.print(": ");
                    socketServer.close();
                    for(int d=0;d<socketServers.size();d++) {
                        if(socketServers.get(d)==socketServer) {
                            socketServers.remove(d);
                            break;
                        }
                    }
                    System.out.println("Done");
                }
            }
        }
    }
    
    public abstract NetBind[] getNetBinds() throws IOException, SQLException;
    
    public abstract String getManagerName();

    public abstract String getServiceName();
    
    public abstract SocketServerThread createSocketServerThread(String ipAddress, int port);
}
