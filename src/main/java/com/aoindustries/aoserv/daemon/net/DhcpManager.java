/*
 * aoserv-daemon - Server management daemon for the AOServ Platform.
 * Copyright (C) 2006-2013, 2017, 2018, 2019, 2020, 2021  AO Industries, Inc.
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
 * along with aoserv-daemon.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.aoindustries.aoserv.daemon.net;

import com.aoapps.lang.validation.ValidationException;
import com.aoapps.net.InetAddress;
import com.aoindustries.aoserv.client.net.Device;
import com.aoindustries.aoserv.client.net.IpAddress;
import com.aoindustries.aoserv.daemon.AOServDaemon;
import com.aoindustries.aoserv.daemon.AOServDaemonConfiguration;
import com.aoindustries.aoserv.daemon.posix.linux.PackageManager;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.sql.SQLException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Watches the IP address of the server and tells the master when the IP address changes.
 */
public final class DhcpManager implements Runnable {

	private static final Logger logger = Logger.getLogger(DhcpManager.class.getName());

	public static final int POLL_INTERVAL = 5 * 60 * 1000;

	private static final String GET_DHCP_ADDRESS="/opt/aoserv-daemon/bin/get_dhcp_address";

	private static Thread thread;

	private DhcpManager() {
	}

	public static InetAddress getDhcpAddress(String device) throws IOException {
		try {
			String[] cmd={
				GET_DHCP_ADDRESS,
				device
			};
			// Make sure /sbin/ifconfig is installed as required by get_dhcp_address
			PackageManager.installPackage(PackageManager.PackageName.NET_TOOLS);
			String ip;
			{
				ip = AOServDaemon.execCall(
					stdout -> {
						try (BufferedReader in = new BufferedReader(new InputStreamReader(stdout))) {
							return in.readLine();
						}
					},
					cmd
				);
				if(ip==null || (ip=ip.trim()).length()==0) throw new IOException("Unable to find IP address for device: "+device);
			}
			return InetAddress.valueOf(ip);
		} catch(ValidationException e) {
			throw new IOException(e.getLocalizedMessage(), e);
		}
	}

	@SuppressWarnings("UseOfSystemOutOrSystemErr")
	public static void start() throws IOException, SQLException {
		if(AOServDaemonConfiguration.isManagerEnabled(DhcpManager.class)) {
			synchronized(System.out) {
				if(thread == null) {
					// Only start if at least one IP Address on the server is DHCP-enabled
					boolean hasDhcp = false;
					for(IpAddress ia : AOServDaemon.getThisServer().getHost().getIPAddresses()) {
						if(ia.isDhcp()) {
							hasDhcp = true;
							break;
						}
					}
					if(hasDhcp) {
						System.out.print("Starting DhcpManager: ");
						thread = new Thread(new DhcpManager());
						thread.setDaemon(true);
						thread.setName("DhcpManager");
						thread.start();
						System.out.println("Done");
					}
				}
			}
		}
	}

	@Override
	@SuppressWarnings("SleepWhileInLoop")
	public void run() {
		while(!Thread.currentThread().isInterrupted()) {
			try {
				try {
					Thread.sleep(POLL_INTERVAL);
				} catch(InterruptedException err) {
					// Restore the interrupted status
					Thread.currentThread().interrupt();
					break;
				}
				for(Device nd : AOServDaemon.getThisServer().getHost().getNetDevices()) {
					IpAddress primaryIP=nd.getPrimaryIPAddress();
					if(primaryIP.isDhcp()) {
						InetAddress dhcpAddress=getDhcpAddress(nd.getDeviceId().getName());
						if(!primaryIP.getInetAddress().equals(dhcpAddress)) {
							primaryIP.setDHCPAddress(dhcpAddress);
						}
					}
				}
			} catch(ThreadDeath td) {
				throw td;
			} catch(Throwable t) {
				logger.log(Level.SEVERE, null, t);
				try {
					Thread.sleep(POLL_INTERVAL);
				} catch(InterruptedException err) {
					logger.log(Level.WARNING, null, err);
					// Restore the interrupted status
					Thread.currentThread().interrupt();
				}
			}
		}
	}
}
