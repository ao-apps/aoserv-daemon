/*
 * Copyright 2002-2013, 2016, 2017, 2018, 2019 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
package com.aoindustries.aoserv.daemon.postgres;

import com.aoindustries.aoserv.client.AOServConnector;
import com.aoindustries.aoserv.client.distribution.OperatingSystemVersion;
import com.aoindustries.aoserv.client.net.AppProtocol;
import com.aoindustries.aoserv.client.net.Bind;
import com.aoindustries.aoserv.client.postgresql.Database;
import com.aoindustries.aoserv.client.postgresql.Server;
import com.aoindustries.aoserv.client.postgresql.User;
import com.aoindustries.aoserv.client.postgresql.Version;
import com.aoindustries.aoserv.daemon.AOServDaemon;
import com.aoindustries.aoserv.daemon.AOServDaemonConfiguration;
import com.aoindustries.aoserv.daemon.LogFactory;
import com.aoindustries.aoserv.daemon.server.ServerManager;
import com.aoindustries.aoserv.daemon.unix.linux.PackageManager;
import com.aoindustries.aoserv.daemon.util.BuilderThread;
import com.aoindustries.cron.CronDaemon;
import com.aoindustries.cron.CronJob;
import com.aoindustries.cron.CronJobScheduleMode;
import com.aoindustries.cron.Schedule;
import com.aoindustries.io.unix.UnixFile;
import com.aoindustries.net.InetAddress;
import com.aoindustries.net.Port;
import com.aoindustries.selinux.SEManagePort;
import com.aoindustries.sql.AOConnectionPool;
import java.io.File;
import java.io.IOException;
import java.net.ProtocolFamily;
import java.net.StandardProtocolFamily;
import java.sql.SQLException;
import java.util.Calendar;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;

/**
 * Controls the PostgreSQL servers.
 *
 * @author  AO Industries, Inc.
 */
final public class PostgresServerManager extends BuilderThread implements CronJob {

	/**
	 * The SELinux type for PostgreSQL.
	 */
	private static final String SELINUX_TYPE = "postgresql_port_t";

	public static final File pgsqlDirectory = new File(Server.DATA_BASE_DIR.toString());

	private PostgresServerManager() {
	}

	private static final Object rebuildLock = new Object();
	@Override
	protected boolean doRebuild() {
		try {
			com.aoindustries.aoserv.client.linux.Server thisAOServer = AOServDaemon.getThisAOServer();
			OperatingSystemVersion osv = thisAOServer.getServer().getOperatingSystemVersion();
			int osvId = osv.getPkey();
			if(
				osvId != OperatingSystemVersion.CENTOS_5_I686_AND_X86_64
				&& osvId != OperatingSystemVersion.CENTOS_7_X86_64
			) throw new AssertionError("Unsupported OperatingSystemVersion: " + osv);

			synchronized(rebuildLock) {
				Set<Port> postgresqlPorts = new HashSet<>();
				for(Server postgresServer : thisAOServer.getPostgresServers()) {
					postgresqlPorts.add(postgresServer.getBind().getPort());
					// TODO: Add and initialize any missing /var/lib/pgsql/name
					// TODO: Add/update any /etc/rc.d/init.d/postgresql-name
				}
				// Add any other local MySQL port (such as tunneled)
				for(Bind nb : thisAOServer.getServer().getNetBinds()) {
					String protocol = nb.getAppProtocol().getProtocol();
					if(AppProtocol.POSTGRESQL.equals(protocol)) {
						postgresqlPorts.add(nb.getPort());
					}
				}

				// Set postgresql_port_t SELinux ports.
				switch(osvId) {
					case OperatingSystemVersion.CENTOS_5_I686_AND_X86_64 :
						// SELinux left in Permissive state, not configured here
						break;
					case OperatingSystemVersion.CENTOS_7_X86_64 : {
						// Install /usr/bin/semanage if missing
						PackageManager.installPackage(PackageManager.PackageName.POLICYCOREUTILS_PYTHON);
						// Reconfigure SELinux ports
						if(SEManagePort.configure(postgresqlPorts, SELINUX_TYPE)) {
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
			LogFactory.getLogger(PostgresServerManager.class).log(Level.SEVERE, null, T);
			return false;
		}
	}

	private static final Map<Integer,AOConnectionPool> pools = new HashMap<>();
	static AOConnectionPool getPool(Server ps) throws IOException, SQLException {
		AOServConnector connector  =AOServDaemon.getConnector();
		String version = ps.getVersion().getTechnologyVersion(connector).getVersion();
		synchronized(pools) {
			Integer pkeyObj = ps.getPkey();
			AOConnectionPool pool = pools.get(pkeyObj);
			if(pool == null) {
				Database pd = ps.getPostgresDatabase(Database.AOSERV);
				if(pd == null) throw new SQLException("Unable to find Database: " + Database.AOSERV + " on "+ps.toString());
				String jdbcUrl;
				if(
					version.startsWith(Version.VERSION_7_1+'.')
					|| version.startsWith(Version.VERSION_7_2+'.')
					|| version.startsWith(Version.VERSION_7_3+'.')
					|| version.startsWith(Version.VERSION_8_1+'.')
					|| version.startsWith(Version.VERSION_8_3+'.')
					|| version.startsWith(Version.VERSION_8_3+'R')
				) {
					// Connect to IP (no 127.0.0.1/::1-only support)
					jdbcUrl = pd.getJdbcUrl(true);
				} else if(
					version.startsWith(Version.VERSION_9_4+'.')
					|| version.startsWith(Version.VERSION_9_4+'R')
					|| version.startsWith(Version.VERSION_9_5+'.')
					|| version.startsWith(Version.VERSION_9_5+'R')
					|| version.startsWith(Version.VERSION_9_6+'.')
					|| version.startsWith(Version.VERSION_9_6+'R')
					|| version.startsWith(Version.VERSION_10+'.')
					|| version.startsWith(Version.VERSION_10+'R')
					|| version.startsWith(Version.VERSION_11+'.')
					|| version.startsWith(Version.VERSION_11+'R')
				) {
					// Connect to 127.0.0.1 or ::1
					com.aoindustries.aoserv.client.linux.Server ao = ps.getAoServer();
					StringBuilder jdbcUrlSB = new StringBuilder();
					jdbcUrlSB.append("jdbc:postgresql://");
					Bind nb = ps.getBind();
					InetAddress ia = nb.getIpAddress().getInetAddress();
					ProtocolFamily family = ia.getProtocolFamily();
					if(family.equals(StandardProtocolFamily.INET)) {
						jdbcUrlSB.append(InetAddress.LOOPBACK_IPV4.toBracketedString());
					} else if(family.equals(StandardProtocolFamily.INET6)) {
						jdbcUrlSB.append(InetAddress.LOOPBACK_IPV6.toBracketedString());
					} else {
						throw new AssertionError("Unexpected family: " + family);
					}
					Port port = nb.getPort();
					if(!port.equals(Server.DEFAULT_PORT)) {
						jdbcUrlSB
							.append(':')
							.append(port.getPort());
					}
					jdbcUrlSB
						.append('/')
						.append(pd.getName());
					jdbcUrl = jdbcUrlSB.toString();
				} else {
					throw new RuntimeException("Unexpected version of PostgreSQL: " + version);
				}
				pool = new AOConnectionPool(
					pd.getJdbcDriver(),
					jdbcUrl,
					User.POSTGRES.toString(),
					AOServDaemonConfiguration.getPostgresPassword(),
					AOServDaemonConfiguration.getPostgresConnections(),
					AOServDaemonConfiguration.getPostgresMaxConnectionAge(),
					LogFactory.getLogger(PostgresServerManager.class)
				);
				pools.put(pkeyObj, pool);
			}
			return pool;
		}
	}

	private static PostgresServerManager postgresServerManager;
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
				&& AOServDaemonConfiguration.isManagerEnabled(PostgresServerManager.class)
				&& postgresServerManager == null
			) {
				System.out.print("Starting PostgresServerManager: ");
				// Must be a supported operating system
				if(
					osvId == OperatingSystemVersion.CENTOS_5_I686_AND_X86_64
					|| osvId == OperatingSystemVersion.CENTOS_7_X86_64
				) {
					AOServConnector conn = AOServDaemon.getConnector();
					postgresServerManager = new PostgresServerManager();
					conn.getPostgresql().getServer().addTableListener(postgresServerManager, 0);
					// Register in CronDaemon
					CronDaemon.addCronJob(postgresServerManager, LogFactory.getLogger(PostgresServerManager.class));
					System.out.println("Done");
				} else {
					System.out.println("Unsupported OperatingSystemVersion: " + osv);
				}
			}
		}
	}

	public static void waitForRebuild() {
		if(postgresServerManager != null) postgresServerManager.waitForBuild();
	}

	@Override
	public String getProcessTimerDescription() {
		return "Rebuild PostgreSQL Servers";
	}

	public static void restartPostgreSQL(Server ps) throws IOException, SQLException {
		ServerManager.controlProcess("postgresql-" + ps.getName(), "restart");
	}

	public static void startPostgreSQL(Server ps) throws IOException, SQLException {
		ServerManager.controlProcess("postgresql-" + ps.getName(), "start");
	}

	public static void stopPostgreSQL(Server ps) throws IOException, SQLException {
		ServerManager.controlProcess("postgresql-" + ps.getName(), "stop");
	}

	@Override
	public String getCronJobName() {
		return PostgresServerManager.class.getName();
	}

	@Override
	public CronJobScheduleMode getCronJobScheduleMode() {
		return CronJobScheduleMode.SKIP;
	}

	/**
	 * This task will be ran once per day at 1:30am.
	 */
	private static final Schedule schedule = (int minute, int hour, int dayOfMonth, int month, int dayOfWeek, int year)
		-> minute==30 && hour==1;

	@Override
	public Schedule getCronJobSchedule() {
		return schedule;
	}

	@Override
	public int getCronJobThreadPriority() {
		return Thread.NORM_PRIORITY - 2;
	}

	/**
	 * Rotates PostgreSQL log files.  Those older than one month are removed.
	 *
	 * TODO: Should use standard log file rotation, so configuration still works if aoserv-daemon disabled or removed.
	 */
	@Override
	public void runCronJob(int minute, int hour, int dayOfMonth, int month, int dayOfWeek, int year) {
		try {
			AOServConnector conn = AOServDaemon.getConnector();
			for(Server postgresServer : AOServDaemon.getThisAOServer().getPostgresServers()) {
				String version=postgresServer.getVersion().getTechnologyVersion(conn).getVersion();
				if(
					!version.startsWith(Version.VERSION_7_1+'.')
					&& !version.startsWith(Version.VERSION_7_2+'.')
					&& !version.startsWith(Version.VERSION_7_3+'.')
					&& !version.startsWith(Version.VERSION_8_0+'.')
				) {
					// Is 8.1 or newer, need to compress and rotate logs
					File logDirectory=new File("/var/log/postgresql", postgresServer.getName().toString());
					String[] list=logDirectory.list();
					if(list!=null) {
						for(String filename : list) {
							if(
								!filename.equals("stderr")
								&& !filename.equals("stdout")
							) {
								// Must be in postgresql-2006-02-14_011332.log format
								if(
									filename.length()!=32
									|| !filename.substring(0, 11).equals("postgresql-")
									|| filename.charAt(11)<'0' || filename.charAt(11)>'9'
									|| filename.charAt(12)<'0' || filename.charAt(12)>'9'
									|| filename.charAt(13)<'0' || filename.charAt(13)>'9'
									|| filename.charAt(14)<'0' || filename.charAt(14)>'9'
									|| filename.charAt(15)!='-'
									|| filename.charAt(16)<'0' || filename.charAt(16)>'9'
									|| filename.charAt(17)<'0' || filename.charAt(17)>'9'
									|| filename.charAt(18)!='-'
									|| filename.charAt(19)<'0' || filename.charAt(19)>'9'
									|| filename.charAt(20)<'0' || filename.charAt(20)>'9'
									|| filename.charAt(21)!='_'
									|| filename.charAt(22)<'0' || filename.charAt(22)>'9'
									|| filename.charAt(23)<'0' || filename.charAt(23)>'9'
									|| filename.charAt(24)<'0' || filename.charAt(24)>'9'
									|| filename.charAt(25)<'0' || filename.charAt(25)>'9'
									|| filename.charAt(26)<'0' || filename.charAt(26)>'9'
									|| filename.charAt(27)<'0' || filename.charAt(27)>'9'
									|| filename.charAt(28)!='.'
									|| filename.charAt(29)!='l'
									|| filename.charAt(30)!='o'
									|| filename.charAt(31)!='g'
								) {
									LogFactory.getLogger(PostgresServerManager.class).log(Level.WARNING, null, new IOException("Warning, unexpected filename, will not remove: "+logDirectory.getPath()+"/"+filename));
								} else {
									// Determine the timestamp of the file
									Calendar fileDate=Calendar.getInstance();
									fileDate.set(Calendar.YEAR, Integer.parseInt(filename.substring(11, 15)));
									fileDate.set(Calendar.MONTH, Integer.parseInt(filename.substring(16, 18))-1);
									fileDate.set(Calendar.DAY_OF_MONTH, Integer.parseInt(filename.substring(19, 21)));
									fileDate.set(Calendar.HOUR_OF_DAY, 0);
									fileDate.set(Calendar.MINUTE, 0);
									fileDate.set(Calendar.SECOND, 0);
									fileDate.set(Calendar.MILLISECOND, 0);

									Calendar monthAgo=Calendar.getInstance();
									monthAgo.add(Calendar.MONTH, -1);

									if(fileDate.compareTo(monthAgo)<0) {
										new UnixFile(logDirectory, filename).delete();
									}
								}
							}
						}
					}
				}
			}
		} catch(ThreadDeath TD) {
			throw TD;
		} catch(Throwable T) {
			LogFactory.getLogger(PostgresServerManager.class).log(Level.SEVERE, null, T);
		}
	}
}
