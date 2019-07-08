/*
 * Copyright 2006-2013, 2016, 2017, 2018, 2019 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
package com.aoindustries.aoserv.daemon.mysql;

import com.aoindustries.aoserv.client.AOServConnector;
import com.aoindustries.aoserv.client.distribution.OperatingSystemVersion;
import com.aoindustries.aoserv.client.mysql.Database;
import com.aoindustries.aoserv.client.mysql.Server;
import com.aoindustries.aoserv.client.net.AppProtocol;
import com.aoindustries.aoserv.client.net.Bind;
import com.aoindustries.aoserv.daemon.AOServDaemon;
import com.aoindustries.aoserv.daemon.AOServDaemonConfiguration;
import com.aoindustries.aoserv.daemon.LogFactory;
import com.aoindustries.aoserv.daemon.server.ServerManager;
import com.aoindustries.aoserv.daemon.unix.linux.PackageManager;
import com.aoindustries.aoserv.daemon.util.BuilderThread;
import com.aoindustries.net.Port;
import com.aoindustries.selinux.SEManagePort;
import com.aoindustries.sql.AOConnectionPool;
import java.io.File;
import java.io.IOException;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;

/**
 * Controls the MySQL servers.
 *
 * @author  AO Industries, Inc.
 */
final public class MySQLServerManager extends BuilderThread {

	/**
	 * The SELinux type for MySQL.
	 */
	private static final String SELINUX_TYPE = "mysqld_port_t";

	public static final File mysqlDirectory=new File(Server.DATA_BASE_DIR.toString());

	private MySQLServerManager() {
	}

	private static final Object rebuildLock = new Object();
	@Override
	protected boolean doRebuild() {
		try {
			com.aoindustries.aoserv.client.linux.Server thisAOServer = AOServDaemon.getThisAOServer();
			OperatingSystemVersion osv = thisAOServer.getServer().getOperatingSystemVersion();
			int osvId = osv.getPkey();
			if(
				osvId != OperatingSystemVersion.MANDRIVA_2006_0_I586
				&& osvId != OperatingSystemVersion.REDHAT_ES_4_X86_64
				&& osvId != OperatingSystemVersion.CENTOS_5_I686_AND_X86_64
				&& osvId != OperatingSystemVersion.CENTOS_7_X86_64
			) throw new AssertionError("Unsupported OperatingSystemVersion: " + osv);

			synchronized(rebuildLock) {
				Set<Port> mysqlPorts = new HashSet<>();
				for(Server mysqlServer : thisAOServer.getMySQLServers()) {
					mysqlPorts.add(mysqlServer.getBind().getPort());
					// TODO: Add and initialize any missing /var/lib/mysql/name
					// TODO: Add/update any /etc/rc.d/init.d/mysql-name
				}
				// Add any other local MySQL port (such as tunneled)
				for(Bind nb : thisAOServer.getServer().getNetBinds()) {
					String protocol = nb.getAppProtocol().getProtocol();
					if(AppProtocol.MYSQL.equals(protocol)) {
						mysqlPorts.add(nb.getPort());
					}
				}

				// Set mysql_port_t SELinux ports.
				switch(osvId) {
					case OperatingSystemVersion.CENTOS_5_I686_AND_X86_64 :
						// SELinux left in Permissive state, not configured here
						break;
					case OperatingSystemVersion.CENTOS_7_X86_64 : {
						// Install /usr/bin/semanage if missing
						PackageManager.installPackage(PackageManager.PackageName.POLICYCOREUTILS_PYTHON);
						// Reconfigure SELinux ports
						if(SEManagePort.configure(mysqlPorts, SELINUX_TYPE)) {
							// TODO: serversNeedingReloaded.addAll(...);
						}
						break;
					}
					default :
						throw new AssertionError("Unsupported OperatingSystemVersion: " + osv);
				}

				// TODO: restart any that need started/restarted
			}
			return true;
		} catch(ThreadDeath TD) {
			throw TD;
		} catch(Throwable T) {
			LogFactory.getLogger(MySQLServerManager.class).log(Level.SEVERE, null, T);
			return false;
		}
	}

	private static final Map<Integer,AOConnectionPool> pools=new HashMap<>();
	static AOConnectionPool getPool(Server ms) throws IOException, SQLException {
		synchronized(pools) {
			Integer I=ms.getPkey();
			AOConnectionPool pool=pools.get(I);
			if(pool==null) {
				Database md=ms.getMySQLDatabase(Database.MYSQL);
				if(md==null) throw new SQLException("Unable to find Database: "+Database.MYSQL+" on "+ms.toString());
				Server.Name serverName = ms.getName();
				pool=new AOConnectionPool(
					AOServDaemonConfiguration.getMySqlDriver(),
					"jdbc:mysql://127.0.0.1:"+md.getMySQLServer().getBind().getPort().getPort()+"/"+md.getName(),
					AOServDaemonConfiguration.getMySqlUser(serverName),
					AOServDaemonConfiguration.getMySqlPassword(serverName),
					AOServDaemonConfiguration.getMySqlConnections(serverName),
					AOServDaemonConfiguration.getMySqlMaxConnectionAge(serverName),
					LogFactory.getLogger(MySQLServerManager.class)
				);
				pools.put(I, pool);
			}
			return pool;
		}
	}

	private static MySQLServerManager mysqlServerManager;
	public static void start() throws IOException, SQLException {
		com.aoindustries.aoserv.client.linux.Server thisAOServer = AOServDaemon.getThisAOServer();
		OperatingSystemVersion osv = thisAOServer.getServer().getOperatingSystemVersion();
		int osvId = osv.getPkey();

		synchronized(System.out) {
			if(
				// Nothing is done for these operating systems
				osvId != OperatingSystemVersion.CENTOS_5_DOM0_I686
				&& osvId != OperatingSystemVersion.CENTOS_5_DOM0_X86_64
				&& osvId != OperatingSystemVersion.CENTOS_7_DOM0_X86_64
				// Check config after OS check so config entry not needed
				&& AOServDaemonConfiguration.isManagerEnabled(MySQLServerManager.class)
				&& mysqlServerManager == null
			) {
				System.out.print("Starting MySQLServerManager: ");
				// Must be a supported operating system
				if(
					osvId == OperatingSystemVersion.MANDRIVA_2006_0_I586
					|| osvId == OperatingSystemVersion.REDHAT_ES_4_X86_64
					|| osvId == OperatingSystemVersion.CENTOS_5_I686_AND_X86_64
					|| osvId == OperatingSystemVersion.CENTOS_7_X86_64
				) {
					AOServConnector conn = AOServDaemon.getConnector();
					mysqlServerManager = new MySQLServerManager();
					conn.getMysql().getServer().addTableListener(mysqlServerManager, 0);
					System.out.println("Done");
				} else {
					System.out.println("Unsupported OperatingSystemVersion: " + osv);
				}
			}
		}
	}

	public static void waitForRebuild() {
		if(mysqlServerManager!=null) mysqlServerManager.waitForBuild();
	}

	@Override
	public String getProcessTimerDescription() {
		return "Rebuild MySQL Servers";
	}

	public static void restartMySQL(Server ms) throws IOException, SQLException {
		ServerManager.controlProcess("mysql-"+ms.getName(), "restart");
	}

	public static void startMySQL(Server ms) throws IOException, SQLException {
		ServerManager.controlProcess("mysql-"+ms.getName(), "start");
	}

	public static void stopMySQL(Server ms) throws IOException, SQLException {
		ServerManager.controlProcess("mysql-"+ms.getName(), "stop");
	}

	private static final Object flushLock=new Object();
	static void flushPrivileges(Server mysqlServer) throws IOException, SQLException {
		OperatingSystemVersion osv = AOServDaemon.getThisAOServer().getServer().getOperatingSystemVersion();
		int osvId = osv.getPkey();

		synchronized(flushLock) {
			/*
			This did not work properly, so we now invoke a native process instead.

			synchronized(flushLock) {
				Connection conn=getPool().getConnection();
				try {
					Statement stmt=conn.createStatement();
					try {
						stmt.executeUpdate("flush privileges");
					} finally {
						stmt.close();
					}
				} finally {
					getPool().releaseConnection(conn);
				}
			}
			*/
			String path;
			if(osvId == OperatingSystemVersion.MANDRIVA_2006_0_I586) {
				path="/usr/mysql/"+mysqlServer.getMinorVersion()+"/bin/mysqladmin";
			} else if(osvId == OperatingSystemVersion.CENTOS_5_I686_AND_X86_64) {
				path="/opt/mysql-"+mysqlServer.getMinorVersion()+"-i686/bin/mysqladmin";
			} else if(
				osvId == OperatingSystemVersion.REDHAT_ES_4_X86_64
				|| osvId == OperatingSystemVersion.CENTOS_7_X86_64
			) {
				path = "/opt/mysql-" + mysqlServer.getMinorVersion() + "/bin/mysqladmin";
			} else throw new AssertionError("Unsupported OperatingSystemVersion: " + osv);

			Server.Name serverName = mysqlServer.getName();
			AOServDaemon.exec(
				path,
				"-h",
				"127.0.0.1",
				"-P",
				Integer.toString(mysqlServer.getBind().getPort().getPort()),
				"-u",
				AOServDaemonConfiguration.getMySqlUser(serverName),
				"--password=" + AOServDaemonConfiguration.getMySqlPassword(serverName),
				"reload"
			);
		}
	}
}
