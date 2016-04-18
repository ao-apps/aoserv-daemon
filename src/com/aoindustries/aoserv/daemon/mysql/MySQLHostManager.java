/*
 * Copyright 2002-2013, 2016 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
package com.aoindustries.aoserv.daemon.mysql;

import com.aoindustries.aoserv.client.AOServConnector;
import com.aoindustries.aoserv.client.AOServer;
import com.aoindustries.aoserv.client.IPAddress;
import com.aoindustries.aoserv.client.MySQLServer;
import com.aoindustries.aoserv.client.NetDevice;
import com.aoindustries.aoserv.client.OperatingSystemVersion;
import com.aoindustries.aoserv.client.validator.InetAddress;
import com.aoindustries.aoserv.daemon.AOServDaemon;
import com.aoindustries.aoserv.daemon.AOServDaemonConfiguration;
import com.aoindustries.aoserv.daemon.LogFactory;
import com.aoindustries.aoserv.daemon.util.BuilderThread;
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

/**
 * Controls the MySQL Hosts.
 *
 * @author  AO Industries, Inc.
 */
final public class MySQLHostManager extends BuilderThread {

	private MySQLHostManager() {
	}

	private static final Object rebuildLock = new Object();
	@Override
	protected boolean doRebuild() {
		try {
			AOServConnector connector = AOServDaemon.getConnector();
			AOServer thisAOServer=AOServDaemon.getThisAOServer();

			int osv=thisAOServer.getServer().getOperatingSystemVersion().getPkey();
			if(
				osv != OperatingSystemVersion.MANDRIVA_2006_0_I586
				&& osv != OperatingSystemVersion.REDHAT_ES_4_X86_64
				&& osv != OperatingSystemVersion.CENTOS_5_I686_AND_X86_64
				&& osv != OperatingSystemVersion.CENTOS_7_X86_64
			) throw new AssertionError("Unsupported OperatingSystemVersion: "+osv);

			synchronized (rebuildLock) {
				for(MySQLServer mysqlServer : connector.getMysqlServers()) {
					String version=mysqlServer.getVersion().getVersion();
					// hosts no longer exists in MySQL 5.6.7+
					if(!version.startsWith(MySQLServer.VERSION_5_6_PREFIX)) {
						boolean modified = false;
						// Get the connection to work through
						AOConnectionPool pool = MySQLServerManager.getPool(mysqlServer);
						Connection conn = pool.getConnection();
						try {
							// Get the list of all existing hosts
							Set<String> existing = new HashSet<>();
							try (
								Statement stmt = conn.createStatement();
								ResultSet results = stmt.executeQuery("select host from host")
							) {
								while (results.next()) existing.add(results.getString(1));
							}

							// Get the list of all hosts that should exist
							Set<String> hosts=new HashSet<>();
							// Always include loopback, just in case of data errors
							hosts.add(IPAddress.LOOPBACK_IP);
							hosts.add("localhost");
							hosts.add("localhost.localdomain");
							// Include all of the local IP addresses
							for(NetDevice nd : thisAOServer.getServer().getNetDevices()) {
								for(IPAddress ia : nd.getIPAddresses()) {
									InetAddress ip = ia.getInetAddress();
									if(!ip.isUnspecified()) {
										String ipString = ip.toString();
										if(!hosts.contains(ipString)) hosts.add(ipString);
									}
								}
							}

							// Add the hosts that do not exist and should
							String insertSQL;
							if(version.startsWith(MySQLServer.VERSION_4_0_PREFIX))      insertSQL="insert into host values(?, '%', 'Y', 'Y', 'Y', 'Y', 'Y', 'Y', 'N', 'N', 'Y', 'Y', 'Y', 'Y')";
							else if(version.startsWith(MySQLServer.VERSION_4_1_PREFIX)) insertSQL="insert into host values(?, '%', 'Y', 'Y', 'Y', 'Y', 'Y', 'Y', 'N', 'N', 'Y', 'Y', 'Y', 'Y')";
							else if(version.startsWith(MySQLServer.VERSION_5_0_PREFIX)) insertSQL="insert into host values(?, '%', 'Y', 'Y', 'Y', 'Y', 'Y', 'Y', 'N', 'N', 'Y', 'Y', 'Y', 'Y', 'Y', 'Y', 'Y', 'Y', 'Y')";
							else if(version.startsWith(MySQLServer.VERSION_5_1_PREFIX)) insertSQL="insert into host values(?, '%', 'Y', 'Y', 'Y', 'Y', 'Y', 'Y', 'N', 'N', 'Y', 'Y', 'Y', 'Y', 'Y', 'Y', 'Y', 'Y', 'Y', 'Y')";
							else throw new SQLException("Unsupported MySQL version: "+version);

							try (PreparedStatement pstmt = conn.prepareStatement(insertSQL)) {
								for (String hostname : hosts) {
									if (existing.contains(hostname)) existing.remove(hostname);
									else {
										// Add the host
										pstmt.setString(1, hostname);
										pstmt.executeUpdate();

										modified = true;
									}
								}
							}

							// Remove the extra hosts
							if (!existing.isEmpty()) {
								try (PreparedStatement pstmt = conn.prepareStatement("delete from host where host=?")) {
									for (String dbName : existing) {
										// Remove the extra host entry
										pstmt.setString(1, dbName);
										pstmt.executeUpdate();

										modified = true;
									}
								}
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
			LogFactory.getLogger(MySQLHostManager.class).log(Level.SEVERE, null, T);
			return false;
		}
	}

	private static MySQLHostManager mysqlHostManager;
	public static void start() throws IOException, SQLException {
		AOServer thisAOServer=AOServDaemon.getThisAOServer();
		int osv=thisAOServer.getServer().getOperatingSystemVersion().getPkey();

		synchronized(System.out) {
			if(
				// Nothing is done for these operating systems
				osv != OperatingSystemVersion.CENTOS_5_DOM0_I686
				&& osv != OperatingSystemVersion.CENTOS_5_DOM0_X86_64
				&& osv != OperatingSystemVersion.CENTOS_7_DOM0_X86_64
				// Check config after OS check so config entry not needed
				&& AOServDaemonConfiguration.isManagerEnabled(MySQLHostManager.class)
				&& mysqlHostManager==null
			) {
				System.out.print("Starting MySQLHostManager: ");
				AOServConnector conn=AOServDaemon.getConnector();
				mysqlHostManager=new MySQLHostManager();
				conn.getIpAddresses().addTableListener(mysqlHostManager, 0);
				conn.getMysqlServers().addTableListener(mysqlHostManager, 0);
				System.out.println("Done");
			}
		}
	}

	@Override
	public String getProcessTimerDescription() {
		return "Rebuild MySQL Hosts";
	}
}
