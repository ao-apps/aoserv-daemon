/*
 * Copyright 2001-2013, 2017 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
package com.aoindustries.aoserv.daemon.util;

import com.aoindustries.aoserv.daemon.LogFactory;
import com.aoindustries.io.AOPool;
import com.aoindustries.net.InetAddress;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.logging.Level;

/**
 * Handles incoming connections on one <code>ServerSocket</code>.
 *
 * @author  AO Industries, Inc.
 */
abstract public class SocketServerThread extends Thread {

    final InetAddress ipAddress;
    final int port;

    public SocketServerThread(String name, InetAddress ipAddress, int port) {
        super(name+" on "+ipAddress.toBracketedString()+":"+port);
        this.ipAddress=ipAddress;
        this.port=port;
    }

    public InetAddress getIPAddress() {
        return ipAddress;
    }
    
    public int getPort() {
        return port;
    }

    boolean runMore=true;

    private ServerSocket SS;

    @Override
    public void run() {
        while(runMore) {
            try {
                SS=new ServerSocket(port, 50, java.net.InetAddress.getByName(ipAddress.toString()));
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
                LogFactory.getLogger(this.getClass()).log(Level.SEVERE, null, T);
                try {
                    Thread.sleep(60000);
                } catch(InterruptedException err) {
                    LogFactory.getLogger(this.getClass()).log(Level.WARNING, null, err);
					// Restore the interrupted status
					Thread.currentThread().interrupt();
                }
            }
        }
    }

    final public void close() {
        runMore=false;
        try {
            SS.close();
        } catch(IOException err) {
            LogFactory.getLogger(this.getClass()).log(Level.SEVERE, null, err);
        }
    }

    protected abstract void socketConnected(Socket socket) throws IOException;
}