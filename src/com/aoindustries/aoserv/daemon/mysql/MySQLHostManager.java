/*
 * Copyright 2002-2013, 2016, 2017, 2018, 2019, 2020 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
package com.aoindustries.aoserv.daemon.mysql;

import com.aoindustries.aoserv.client.AOServConnector;
import com.aoindustries.aoserv.client.distribution.OperatingSystemVersion;
import com.aoindustries.aoserv.client.mysql.Server;
import com.aoindustries.aoserv.client.net.Device;
import com.aoindustries.aoserv.client.net.IpAddress;
import com.aoindustries.aoserv.daemon.AOServDaemon;
import com.aoindustries.aoserv.daemon.AOServDaemonConfiguration;
import com.aoindustries.aoserv.daemon.util.BuilderThread;
import com.aoindustries.net.InetAddress;
import com.aoindustries.sql.AOConnectionPool;
import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Controls the MySQL Hosts.
 *
 * @author  AO Industries, Inc.
 */
final public class MySQLHostManager extends BuilderThread {

	private static final Logger logger = Logger.getLogger(MySQLHostManager.class.getName());

	private MySQLHostManager() {
	}

	private static final Object rebuildLock = new Object();
	@Override
	protected boolean doRebuild() {
		try {
			com.aoindustries.aoserv.client.linux.Server thisServer = AOServDaemon.getThisServer();
			OperatingSystemVersion osv = thisServer.getHost().getOperatingSystemVersion();
			int osvId = osv.getPkey();
			if(
				osvId != OperatingSystemVersion.MANDRIVA_2006_0_I586
				&& osvId != OperatingSystemVersion.REDHAT_ES_4_X86_64
				&& osvId != OperatingSystemVersion.CENTOS_5_I686_AND_X86_64
				&& osvId != OperatingSystemVersion.CENTOS_7_X86_64
			) throw new AssertionError("Unsupported OperatingSystemVersion: " + osv);

			AOServConnector connector = AOServDaemon.getConnector();
			synchronized (rebuildLock) {
				for(Server mysqlServer : connector.getMysql().getServer()) {
					String version=mysqlServer.getVersion().getVersion();
					// hosts no longer exists in MySQL 5.6.7+
					if(
						version.startsWith(Server.VERSION_4_0_PREFIX)
						|| version.startsWith(Server.VERSION_4_1_PREFIX)
						|| version.startsWith(Server.VERSION_5_0_PREFIX)
						|| version.startsWith(Server.VERSION_5_1_PREFIX)
					) {
						boolean modified = false;
						// Get the connection to work through
						AOConnectionPool pool = MySQLServerManager.getPool(mysqlServer);
						Connection conn = pool.getConnection();
						try {
							// Get the list of all existing hosts
							Set<String> existing = new HashSet<>();
							try (
								Statement stmt = conn.createStatement();
								ResultSet results = stmt.executeQuery("SELECT host FROM host")
							) {
								while (results.next()) {
									String host = results.getString(1);
									if(!existing.add(host)) throw new SQLException("Duplicate host: " + host);
								}
							}

							// Get the list of all hosts that should exist
							Set<String> hosts=new HashSet<>();
							// Always include loopback, just in case of data errors
							hosts.add(IpAddress.LOOPBACK_IP);
							hosts.add("localhost");
							hosts.add("localhost.localdomain");
							// Include all of the local IP addresses
							for(Device nd : thisServer.getHost().getNetDevices()) {
								for(IpAddress ia : nd.getIPAddresses()) {
									InetAddress ip = ia.getInetAddress();
									if(!ip.isUnspecified()) {
										String ipString = ip.toString();
										if(!hosts.contains(ipString)) hosts.add(ipString);
									}
								}
							}

							// Add the hosts that do not exist and should
							String insertSQL;
							if(version.startsWith(Server.VERSION_4_0_PREFIX))      insertSQL = "INSERT INTO host VALUES (?, '%', 'Y', 'Y', 'Y', 'Y', 'Y', 'Y', 'N', 'N', 'Y', 'Y', 'Y', 'Y')";
							else if(version.startsWith(Server.VERSION_4_1_PREFIX)) insertSQL = "INSERT INTO host VALUES (?, '%', 'Y', 'Y', 'Y', 'Y', 'Y', 'Y', 'N', 'N', 'Y', 'Y', 'Y', 'Y')";
							else if(version.startsWith(Server.VERSION_5_0_PREFIX)) insertSQL = "INSERT INTO host VALUES (?, '%', 'Y', 'Y', 'Y', 'Y', 'Y', 'Y', 'N', 'N', 'Y', 'Y', 'Y', 'Y', 'Y', 'Y', 'Y', 'Y', 'Y')";
							else if(version.startsWith(Server.VERSION_5_1_PREFIX)) insertSQL = "INSERT INTO host VALUES (?, '%', 'Y', 'Y', 'Y', 'Y', 'Y', 'Y', 'N', 'N', 'Y', 'Y', 'Y', 'Y', 'Y', 'Y', 'Y', 'Y', 'Y', 'Y')";
							else throw new SQLException("Unsupported MySQL version: "+version);

							try (PreparedStatement pstmt = conn.prepareStatement(insertSQL)) {
								for (String hostname : hosts) {
									if(!existing.remove(hostname)) {
										// Add the host
										pstmt.setString(1, hostname);
										pstmt.executeUpdate();
										modified = true;
									}
								}
							}

							// Remove the extra hosts
							if (!existing.isEmpty()) {
								try (PreparedStatement pstmt = conn.prepareStatement("DELETE FROM host WHERE host=?")) {
									for (String dbName : existing) {
										// Remove the extra host entry
										pstmt.setString(1, dbName);
										pstmt.executeUpdate();
									}
								}
								modified = true;
							}
						} finally {
							pool.releaseConnection(conn);
						}
						if(modified) MySQLServerManager.flushPrivileges(mysqlServer);
					}
				}
			}
			return true;
		} catch(ThreadDeath TD) {
			throw TD;
		} catch(Throwable T) {
			logger.log(Level.SEVERE, null, T);
			return false;
		}
	}

	private static MySQLHostManager mysqlHostManager;
	public static void start() throws IOException, SQLException {
		com.aoindustries.aoserv.client.linux.Server thisServer = AOServDaemon.getThisServer();
		OperatingSystemVersion osv = thisServer.getHost().getOperatingSystemVersion();
		int osvId = osv.getPkey();

		synchronized(System.out) {
			if(
				// Nothing is done for these operating systems
				osvId != OperatingSystemVersion.CENTOS_5_DOM0_I686
				&& osvId != OperatingSystemVersion.CENTOS_5_DOM0_X86_64
				&& osvId != OperatingSystemVersion.CENTOS_7_DOM0_X86_64
				// Check config after OS check so config entry not needed
				&& AOServDaemonConfiguration.isManagerEnabled(MySQLHostManager.class)
				&& mysqlHostManager == null
			) {
				System.out.print("Starting MySQLHostManager: ");
				// Must be a supported operating system
				if(
					osvId == OperatingSystemVersion.MANDRIVA_2006_0_I586
					|| osvId == OperatingSystemVersion.REDHAT_ES_4_X86_64
					|| osvId == OperatingSystemVersion.CENTOS_5_I686_AND_X86_64
					|| osvId == OperatingSystemVersion.CENTOS_7_X86_64
				) {
					AOServConnector conn = AOServDaemon.getConnector();
					mysqlHostManager = new MySQLHostManager();
					conn.getNet().getIpAddress().addTableListener(mysqlHostManager, 0);
					conn.getMysql().getServer().addTableListener(mysqlHostManager, 0);
					System.out.println("Done");
				} else {
					System.out.println("Unsupported OperatingSystemVersion: " + osv);
				}
			}
		}
	}

	@Override
	public String getProcessTimerDescription() {
		return "Rebuild MySQL Hosts";
	}
}
