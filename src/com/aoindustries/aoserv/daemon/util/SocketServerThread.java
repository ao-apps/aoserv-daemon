package com.aoindustries.aoserv.daemon.util;

/*
 * Copyright 2001-2009 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
import com.aoindustries.aoserv.client.validator.NetPort;
import com.aoindustries.aoserv.daemon.LogFactory;
import com.aoindustries.io.AOPool;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.logging.Level;

/**
 * Handles incoming connections on one <code>ServerSocket</code>.
 *
 * @author  AO Industries, Inc.
 */
abstract public class SocketServerThread extends Thread {

    final com.aoindustries.aoserv.client.validator.InetAddress ipAddress;
    final NetPort port;

    public SocketServerThread(String name, com.aoindustries.aoserv.client.validator.InetAddress ipAddress, NetPort port) {
        super(name+" on "+(ipAddress.getAddress().indexOf(':')==-1 ? ipAddress.getAddress() : ("["+ipAddress.getAddress()+"]"))+":"+port);
        this.ipAddress=ipAddress;
        this.port=port;
    }

    public com.aoindustries.aoserv.client.validator.InetAddress getIPAddress() {
        return ipAddress;
    }
    
    public NetPort getPort() {
        return port;
    }

    boolean runMore=true;

    private ServerSocket SS;

    @Override
    public void run() {
        while(runMore) {
            try {
                SS=new ServerSocket(port.getPort(), 50, InetAddress.getByName(ipAddress.getAddress()));
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