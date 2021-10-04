/*
 * aoserv-daemon - Server management daemon for the AOServ Platform.
 * Copyright (C) 2001-2013, 2017, 2018, 2020, 2021  AO Industries, Inc.
 *     support@aoindustries.com
 *     7262 Bull Pen Cir
 *     Mobile, AL 36695
 *
 * This file is part of aoserv-daemon.
 *
 * aoserv-daemon is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * aoserv-daemon is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with aoserv-daemon.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.aoindustries.aoserv.daemon.util;

import com.aoapps.hodgepodge.io.AOPool;
import com.aoapps.net.InetAddress;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Handles incoming connections on one <code>ServerSocket</code>.
 *
 * @author  AO Industries, Inc.
 */
public abstract class SocketServerThread extends Thread {

	private static final Logger logger = Logger.getLogger(SocketServerThread.class.getName());

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
				logger.log(Level.SEVERE, null, T);
				try {
					Thread.sleep(60000);
				} catch(InterruptedException err) {
					logger.log(Level.WARNING, null, err);
				}
			}
		}
	}

	public final void close() {
		runMore=false;
		try {
			SS.close();
		} catch(IOException err) {
			logger.log(Level.SEVERE, null, err);
		}
	}

	protected abstract void socketConnected(Socket socket) throws IOException;
}
