package com.aoindustries.aoserv.daemon.util;

/*
 * Copyright 2001-2006 by AO Industries, Inc.,
 * 816 Azalea Rd, Mobile, Alabama, 36693, U.S.A.
 * All rights reserved.
 */
import com.aoindustries.aoserv.daemon.*;
import com.aoindustries.io.AOPool;
import com.aoindustries.profiler.*;
import java.io.*;
import java.net.*;

/**
 * Handles incoming connections on one <code>ServerSocket</code>.
 *
 * @author  AO Industries, Inc.
 */
abstract public class SocketServerThread extends Thread {

    final String ipAddress;
    final int port;

    public SocketServerThread(String name, String ipAddress, int port) {
        super(name+" on "+ipAddress+":"+port);
        Profiler.startProfile(Profiler.INSTANTANEOUS, SocketServerThread.class, "<init>(String,int)", null);
        try {
            this.ipAddress=ipAddress;
            this.port=port;
        } finally {
            Profiler.endProfile(Profiler.INSTANTANEOUS);
        }
    }

    public String getIPAddress() {
        Profiler.startProfile(Profiler.INSTANTANEOUS, SocketServerThread.class, "getIPAddress()", null);
        try {
            return ipAddress;
        } finally {
            Profiler.endProfile(Profiler.INSTANTANEOUS);
        }
    }
    
    public int getPort() {
        Profiler.startProfile(Profiler.INSTANTANEOUS, SocketServerThread.class, "getPort()", null);
        try {
            return port;
        } finally {
            Profiler.endProfile(Profiler.INSTANTANEOUS);
        }
    }

    boolean runMore=true;

    private ServerSocket SS;

    public void run() {
        Profiler.startProfile(Profiler.UNKNOWN, SocketServerThread.class, "run()", null);
        try {
            while(runMore) {
                try {
                    SS=new ServerSocket(port, 50, InetAddress.getByName(ipAddress));
                    try {
                        while(runMore) {
                            Socket socket=SS.accept();
                            socket.setKeepAlive(true);
                            socket.setSoLinger(true, AOPool.DEFAULT_SOCKET_SO_LINGER);
                            //socket.setTcpNoDelay(true);
                            socketConnected(socket);
                        }
                    } finally {
                        SS.close();
                    }
                } catch(ThreadDeath TD) {
                    throw TD;
                } catch(Throwable T) {
                    AOServDaemon.reportError(T, null);
                    try {
                        Thread.sleep(60000);
                    } catch(InterruptedException err) {
                        AOServDaemon.reportWarning(err, null);
                    }
                }
            }
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }

    final public void close() {
        Profiler.startProfile(Profiler.UNKNOWN, SocketServerThread.class, "close()", null);
        try {
            runMore=false;
            try {
                SS.close();
            } catch(IOException err) {
                AOServDaemon.reportError(err, null);
            }
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }

    protected abstract void socketConnected(Socket socket) throws IOException;
}