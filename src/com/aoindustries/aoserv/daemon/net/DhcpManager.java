/*
 * Copyright 2006-2013, 2017 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
package com.aoindustries.aoserv.daemon.net;

import com.aoindustries.aoserv.client.IPAddress;
import com.aoindustries.aoserv.client.NetDevice;
import com.aoindustries.aoserv.client.validator.InetAddress;
import com.aoindustries.aoserv.client.validator.ValidationException;
import com.aoindustries.aoserv.daemon.AOServDaemon;
import com.aoindustries.aoserv.daemon.AOServDaemonConfiguration;
import com.aoindustries.aoserv.daemon.LogFactory;
import com.aoindustries.aoserv.daemon.unix.linux.PackageManager;
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
					BufferedReader in=new BufferedReader(new InputStreamReader(P.getInputStream()));
					try {
						ip=in.readLine();
					} finally {
						in.close();
					}
				} finally {
					AOServDaemon.waitFor(P, cmd);
				}
				if(ip==null || (ip=ip.trim()).length()==0) throw new IOException("Unable to find IP address for device: "+device);
			}
			return InetAddress.valueOf(ip);
		} catch(ValidationException e) {
			IOException exc = new IOException(e.getLocalizedMessage());
			exc.initCause(e);
			throw exc;
		}
	}

	public static void start() throws IOException, SQLException {
		if(AOServDaemonConfiguration.isManagerEnabled(DhcpManager.class)) {
			synchronized(System.out) {
				if(thread == null) {
					// Only start if at least one IP Address on the server is DHCP-enabled
					boolean hasDhcp = false;
					for(IPAddress ia : AOServDaemon.getThisAOServer().getServer().getIPAddresses()) {
						if(ia.isDHCP()) {
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
						LogFactory.getLogger(this.getClass()).log(Level.WARNING, null, err);
						// Restore the interrupted status
						Thread.currentThread().interrupt();
					}
					for(NetDevice nd : AOServDaemon.getThisAOServer().getServer().getNetDevices()) {
						IPAddress primaryIP=nd.getPrimaryIPAddress();
						if(primaryIP.isDHCP()) {
							InetAddress dhcpAddress=getDhcpAddress(nd.getNetDeviceID().getName());
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
					// Restore the interrupted status
					Thread.currentThread().interrupt();
				}
			}
		}
	}
}
