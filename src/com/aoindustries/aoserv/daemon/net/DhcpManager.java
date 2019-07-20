/*
 * Copyright 2006-2013, 2017, 2018, 2019 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
package com.aoindustries.aoserv.daemon.net;

import com.aoindustries.aoserv.client.net.Device;
import com.aoindustries.aoserv.client.net.IpAddress;
import com.aoindustries.aoserv.daemon.AOServDaemon;
import com.aoindustries.aoserv.daemon.AOServDaemonConfiguration;
import com.aoindustries.aoserv.daemon.LogFactory;
import com.aoindustries.aoserv.daemon.unix.linux.PackageManager;
import com.aoindustries.net.InetAddress;
import com.aoindustries.validation.ValidationException;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.sql.SQLException;
import java.util.logging.Level;

/**
 * Watches the IP address of the server and tells the master when the IP address changes.
 */
final public class DhcpManager implements Runnable {

	public static final int POLL_INTERVAL=5*60*1000;

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
				Process P=Runtime.getRuntime().exec(cmd);
				try {
					P.getOutputStream().close();
					try (BufferedReader in = new BufferedReader(new InputStreamReader(P.getInputStream()))) {
						ip = in.readLine();
					}
				} finally {
					AOServDaemon.waitFor(P, cmd);
				}
				if(ip==null || (ip=ip.trim()).length()==0) throw new IOException("Unable to find IP address for device: "+device);
			}
			return InetAddress.valueOf(ip);
		} catch(ValidationException e) {
			throw new IOException(e.getLocalizedMessage(), e);
		}
	}

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
	public void run() {
		while(true) {
			try {
				while(true) {
					try {
						Thread.sleep(POLL_INTERVAL);
					} catch(InterruptedException err) {
						Thread.currentThread().interrupt();
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
				}
			} catch(ThreadDeath TD) {
				throw TD;
			} catch(Throwable T) {
				LogFactory.getLogger(this.getClass()).log(Level.SEVERE, null, T);
				try {
					Thread.sleep(POLL_INTERVAL);
				} catch(InterruptedException err) {
					LogFactory.getLogger(this.getClass()).log(Level.WARNING, null, err);
				}
			}
		}
	}
}
