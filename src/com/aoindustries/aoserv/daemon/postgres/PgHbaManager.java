/*
 * Copyright 2003-2013, 2015, 2016, 2017, 2018, 2019 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
package com.aoindustries.aoserv.daemon.postgres;

import com.aoindustries.aoserv.client.AOServConnector;
import com.aoindustries.aoserv.client.distribution.OperatingSystemVersion;
import com.aoindustries.aoserv.client.linux.Group;
import com.aoindustries.aoserv.client.postgresql.Database;
import com.aoindustries.aoserv.client.postgresql.Server;
import com.aoindustries.aoserv.client.postgresql.User;
import com.aoindustries.aoserv.client.postgresql.UserServer;
import com.aoindustries.aoserv.client.postgresql.Version;
import com.aoindustries.aoserv.daemon.AOServDaemon;
import com.aoindustries.aoserv.daemon.AOServDaemonConfiguration;
import com.aoindustries.aoserv.daemon.LogFactory;
import com.aoindustries.aoserv.daemon.unix.linux.LinuxProcess;
import com.aoindustries.aoserv.daemon.util.BuilderThread;
import com.aoindustries.aoserv.daemon.util.DaemonFileUtils;
import com.aoindustries.encoding.ChainWriter;
import com.aoindustries.io.unix.UnixFile;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.sql.SQLException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;

/**
 * Controls the pg_hba.conf files for the PostgreSQL installations.
 *
 * @author  AO Industries, Inc.
 */
public final class PgHbaManager extends BuilderThread {

	private PgHbaManager() {
	}

	private static final Object rebuildLock = new Object();
	@Override
	protected boolean doRebuild() {
		try {
			AOServConnector connector=AOServDaemon.getConnector();
			com.aoindustries.aoserv.client.linux.Server thisAOServer=AOServDaemon.getThisAOServer();
			OperatingSystemVersion osv = thisAOServer.getServer().getOperatingSystemVersion();
			int osvId = osv.getPkey();
			if(
				osvId != OperatingSystemVersion.MANDRIVA_2006_0_I586
				&& osvId != OperatingSystemVersion.REDHAT_ES_4_X86_64
				&& osvId != OperatingSystemVersion.CENTOS_5_I686_AND_X86_64
				&& osvId != OperatingSystemVersion.CENTOS_7_X86_64
			) throw new AssertionError("Unsupported OperatingSystemVersion: " + osv);

			synchronized(rebuildLock) {
				Set<UnixFile> restorecon = new LinkedHashSet<>();
				try {
					for(Server ps : thisAOServer.getPostgresServers()) {
						List<UserServer> users = ps.getPostgresServerUsers();
						if(users.isEmpty()) {
							LogFactory.getLogger(PgHbaManager.class).severe("No users; refusing to rebuild config: " + ps);
						} else {
							List<Database> pds = ps.getPostgresDatabases();
							if(pds.isEmpty()) {
								LogFactory.getLogger(PgHbaManager.class).severe("No databases; refusing to rebuild config: " + ps);
							} else {
								// InetAddress bind = ps.getBind().getIpAddress().getInetAddress();
								String version=ps.getVersion().getTechnologyVersion(connector).getVersion();
								int postgresUID=thisAOServer.getLinuxServerAccount(com.aoindustries.aoserv.client.linux.User.POSTGRES).getUid().getId();
								int postgresGID=thisAOServer.getLinuxServerGroup(Group.POSTGRES).getGid().getId();
								Server.Name serverName=ps.getName();
								File serverDir=new File(PostgresServerManager.pgsqlDirectory, serverName.toString());
								ByteArrayOutputStream bout = new ByteArrayOutputStream();
								try (ChainWriter out = new ChainWriter(bout)) {
									if(
										version.startsWith(Version.VERSION_7_1+'.')
										|| version.startsWith(Version.VERSION_7_2+'.')
									) {
										out.print("local all trust\n"
												+ "host all 127.0.0.1 255.255.255.255 ident sameuser\n"
												+ "host all 0.0.0.0 0.0.0.0 password\n");
									} else if(version.startsWith(Version.VERSION_7_3+'.')) {
										out.print("local all all trust\n");
										for(Database db : pds) {
											out.print("host ").print(db.getName()).print(' ');
											boolean didOne=false;
											for(UserServer psu : users) {
												User pu = psu.getPostgresUser();
												if(
													// Allow postgres to all databases
													pu.getKey().equals(User.POSTGRES)
													// Allow database admin
													|| psu.equals(db.getDatDBA())
													// Allow in same business
													|| pu.getUsername().getPackage().getBusiness().equals(
														// TODO: Allow access to subaccounts?
														db.getDatDBA().getPostgresUser().getUsername().getPackage().getBusiness()
													)
												) {
													if(didOne) out.print(',');
													else didOne=true;
													out.print(pu);
												}
											}
											out.print(" 127.0.0.1 255.255.255.255 ident sameuser\n"
													+ "host ").print(db.getName()).print(' ');
											didOne=false;
											for(UserServer psu : users) {
												User pu = psu.getPostgresUser();
												if(
													// Allow postgres to all databases
													pu.getKey().equals(User.POSTGRES)
													// Allow database admin
													|| psu.equals(db.getDatDBA())
													// Allow in same business
													|| pu.getUsername().getPackage().getBusiness().equals(
														// TODO: Allow access to subaccounts?
														db.getDatDBA().getPostgresUser().getUsername().getPackage().getBusiness()
													)
												) {
													if(didOne) out.print(',');
													else didOne=true;
													out.print(pu);
												}
											}
											out.print(" 0.0.0.0 0.0.0.0 password\n");
										}
									} else if(
										version.startsWith(Version.VERSION_8_1+'.')
										|| version.startsWith(Version.VERSION_8_3+'.')
										|| version.startsWith(Version.VERSION_8_3+'R')
									) {
										for(Database db : pds) {
											// ident used from local
											out.print("local ").print(db.getName()).print(' ');
											boolean didOne=false;
											for(UserServer psu : users) {
												User pu = psu.getPostgresUser();
												if(
													// Allow postgres to all databases
													pu.getKey().equals(User.POSTGRES)
													// Allow database admin
													|| psu.equals(db.getDatDBA())
													// Allow in same business
													|| pu.getUsername().getPackage().getBusiness().equals(
														// TODO: Allow access to subaccounts?
														db.getDatDBA().getPostgresUser().getUsername().getPackage().getBusiness()
													)
												) {
													if(didOne) out.print(',');
													else didOne=true;
													out.print(pu);
												}
											}
											out.print(" ident sameuser\n");

											// ident used from 127.0.0.1
											out.print("host ").print(db.getName()).print(' ');
											didOne=false;
											for(UserServer psu : users) {
												User pu = psu.getPostgresUser();
												if(
													// Allow postgres to all databases
													pu.getKey().equals(User.POSTGRES)
													// Allow database admin
													|| psu.equals(db.getDatDBA())
													// Allow in same business
													|| pu.getUsername().getPackage().getBusiness().equals(
														// TODO: Allow access to subaccounts?
														db.getDatDBA().getPostgresUser().getUsername().getPackage().getBusiness()
													)
												) {
													if(didOne) out.print(',');
													else didOne=true;
													out.print(pu);
												}
											}
											out.print(" 127.0.0.1/32 ident sameuser\n");

											// ident used from ::1/128
											out.print("host ").print(db.getName()).print(' ');
											didOne=false;
											for(UserServer psu : users) {
												User pu = psu.getPostgresUser();
												if(
													// Allow postgres to all databases
													pu.getKey().equals(User.POSTGRES)
													// Allow database admin
													|| psu.equals(db.getDatDBA())
													// Allow in same business
													|| pu.getUsername().getPackage().getBusiness().equals(
														// TODO: Allow access to subaccounts?
														db.getDatDBA().getPostgresUser().getUsername().getPackage().getBusiness()
													)
												) {
													if(didOne) out.print(',');
													else didOne=true;
													out.print(pu);
												}
											}
											out.print(" ::1/128 ident sameuser\n");

											// md5 used for other connections
											out.print("host ").print(db.getName()).print(' ');
											didOne=false;
											for(UserServer psu : users) {
												User pu = psu.getPostgresUser();
												if(
													// Allow postgres to all databases
													pu.getKey().equals(User.POSTGRES)
													// Allow database admin
													|| psu.equals(db.getDatDBA())
													// Allow in same business
													|| pu.getUsername().getPackage().getBusiness().equals(
														// TODO: Allow access to subaccounts?
														db.getDatDBA().getPostgresUser().getUsername().getPackage().getBusiness()
													)
												) {
													if(didOne) out.print(',');
													else didOne=true;
													out.print(pu);
												}
											}
											out.print(" 0.0.0.0 0.0.0.0 md5\n");
										}
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
										out.print("# AOServ Daemon: authenticated local TCP only, only to " + Database.AOSERV + " database\n");
										out.print("hostnossl " + Database.AOSERV + " " + User.POSTGRES + " 127.0.0.1/32 md5\n");
										out.print("hostnossl " + Database.AOSERV + " " + User.POSTGRES + " ::1/128      md5\n");
										out.print("\n");
										out.print("# Super user: local only, to all databases\n");
										out.print("local     all " + User.POSTGRES + "              peer\n");
										out.print("hostnossl all " + User.POSTGRES + " 127.0.0.1/32 ident\n");
										out.print("hostnossl all " + User.POSTGRES + " ::1/128      ident\n");
										if(ps.getPostgresServerUser(User.POSTGRESMON) != null) {
											out.print("\n");
											out.print("# Monitoring: authenticated from anywhere, only to monitoring database\n");
											if(ps.getPostgresDatabase(Database.POSTGRESMON) != null) {
												// Has monitoring database
												out.print("host " + Database.POSTGRESMON + " " + User.POSTGRESMON + " all md5\n");
											} else {
												// Compatibility: Allow connection to aoserv database
												out.print("host " + Database.AOSERV + " " + User.POSTGRESMON + " all md5\n");
											}
										}
										out.print("\n");
										out.print("# Other databases: ident local, authenticate all others, to all databases in same account\n");
										for(Database db : pds) {
											Database.Name name = db.getName();
											if(
												// Templates handled above: only postgres may connect to it
												!name.equals(Database.TEMPLATE0)
												&& !name.equals(Database.TEMPLATE1)
												// aoserv handled above: only postgres and possibly postgresmon may connect to it
												&& !name.equals(Database.AOSERV)
												// Monitoring handled above: only postgres and postgresmon may connect to it
												&& !name.equals(Database.POSTGRESMON)
											) {
												Set<User.Name> dbUsers = new LinkedHashSet<>();
												for(UserServer psu : users) {
													User pu = psu.getPostgresUser();
													User.Name username = pu.getUsername_username_id();
													if(
														// Super user handled above
														!username.equals(User.POSTGRES)
														// Monitoring handled above
														&& !username.equals(User.POSTGRESMON)
														&& (
															// Allow database admin
															psu.equals(db.getDatDBA())
															// Allow in same business as database admin
															|| pu.getUsername().getPackage().getBusiness().equals(
																// TODO: Allow access to subaccounts?
																db.getDatDBA().getPostgresUser().getUsername().getPackage().getBusiness()
															)
														)
													) {
														dbUsers.add(username);
													}
												}
												if(dbUsers.isEmpty()) throw new SQLException("No users found for database (should always have at least datdba): " + db);
												// peer used from local
												out.print("local ").print(name).print(' ');
												boolean didOne = false;
												for(User.Name dbUser : dbUsers) {
													if(didOne) out.print(',');
													else didOne = true;
													out.print(dbUser);
												}
												out.print("              peer\n");

												// ident used from 127.0.0.1/32
												out.print("host  ").print(name).print(' ');
												didOne = false;
												for(User.Name dbUser : dbUsers) {
													if(didOne) out.print(',');
													else didOne = true;
													out.print(dbUser);
												}
												out.print(" 127.0.0.1/32 ident\n");

												// ident used from ::1/128
												out.print("host  ").print(db.getName()).print(' ');
												didOne = false;
												for(User.Name dbUser : dbUsers) {
													if(didOne) out.print(',');
													else didOne = true;
													out.print(dbUser);
												}
												out.print(" ::1/128      ident\n");

												// md5 used for other connections
												out.print("host  ").print(db.getName()).print(' ');
												didOne = false;
												for(User.Name dbUser : dbUsers) {
													if(didOne) out.print(',');
													else didOne = true;
													out.print(dbUser);
												}
												out.print(" all          md5\n");
											}
										}
									} else {
										throw new RuntimeException("Unexpected version of PostgreSQL: " + version);
									}
								}

								// Move the new file into place
								boolean needsReload = DaemonFileUtils.atomicWrite(
									new UnixFile(serverDir, "pg_hba.conf"),
									bout.toByteArray(),
									0600,
									postgresUID,
									postgresGID,
									null,
									restorecon
								);

								// SELinux before next steps
								DaemonFileUtils.restorecon(restorecon);
								restorecon.clear();

								if(needsReload) {
									// Signal reload on PostgreSQL
									File pidFile = new File(serverDir, "postmaster.pid");
									if(pidFile.exists()) {
										BufferedReader in=new BufferedReader(new InputStreamReader(new FileInputStream(pidFile)));
										String pid=in.readLine();
										// Must be all 0-9
										for(int d=0;d<pid.length();d++) {
											char ch=pid.charAt(d);
											if(ch<'0' || ch>'9') throw new IOException("Invalid character in postmaster.pid first line: "+ch);
										}
										new LinuxProcess(Integer.parseInt(pid)).signal("HUP");
									} else {
										LogFactory.getLogger(PgHbaManager.class).log(
											Level.WARNING,
											"PID file not found for PostgreSQL server \""
												+ serverName
												+ "\", server running? "
												+ pidFile
										);
									}
								}
							}
						}
					}
				} finally {
					DaemonFileUtils.restorecon(restorecon);
				}
			}
			return true;
		} catch(ThreadDeath TD) {
			throw TD;
		} catch(Throwable T) {
			LogFactory.getLogger(PgHbaManager.class).log(Level.SEVERE, null, T);
			return false;
		}
	}

	private static PgHbaManager pgHbaManager;
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
				&& AOServDaemonConfiguration.isManagerEnabled(PgHbaManager.class)
				&& pgHbaManager == null
			) {
				System.out.print("Starting PgHbaManager: ");
				// Must be a supported operating system
				if(
					osvId == OperatingSystemVersion.MANDRIVA_2006_0_I586
					|| osvId == OperatingSystemVersion.REDHAT_ES_4_X86_64
					|| osvId == OperatingSystemVersion.CENTOS_5_I686_AND_X86_64
					|| osvId == OperatingSystemVersion.CENTOS_7_X86_64
				) {
					AOServConnector conn = AOServDaemon.getConnector();
					pgHbaManager = new PgHbaManager();
					conn.getPostgresql().getDatabase().addTableListener(pgHbaManager, 0);
					conn.getPostgresql().getUserServer().addTableListener(pgHbaManager, 0);
					System.out.println("Done");
				} else {
					System.out.println("Unsupported OperatingSystemVersion: " + osv);
				}
			}
		}
	}

	@Override
	public String getProcessTimerDescription() {
		return "Rebuild PostgreSQL pg_hba.conf";
	}
}
