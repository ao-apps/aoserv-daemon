/*
 * Copyright 2003-2013, 2015, 2016, 2017, 2018 by AO Industries, Inc.,
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
import com.aoindustries.aoserv.client.validator.PostgresServerName;
import com.aoindustries.aoserv.daemon.AOServDaemon;
import com.aoindustries.aoserv.daemon.AOServDaemonConfiguration;
import com.aoindustries.aoserv.daemon.LogFactory;
import com.aoindustries.aoserv.daemon.unix.linux.LinuxProcess;
import com.aoindustries.aoserv.daemon.util.BuilderThread;
import com.aoindustries.encoding.ChainWriter;
import com.aoindustries.io.unix.UnixFile;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.sql.SQLException;
import java.util.List;
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
				for(Server ps : thisAOServer.getPostgresServers()) {
					List<UserServer> users = ps.getPostgresServerUsers();
					if(users.isEmpty()) {
						LogFactory.getLogger(PgHbaManager.class).severe("No users; refusing to rebuild config: " + ps);
					} else {
						List<Database> pds = ps.getPostgresDatabases();
						if(pds.isEmpty()) {
							LogFactory.getLogger(PgHbaManager.class).severe("No databases; refusing to rebuild config: " + ps);
						} else {
							String version=ps.getVersion().getTechnologyVersion(connector).getVersion();
							int postgresUID=thisAOServer.getLinuxServerAccount(com.aoindustries.aoserv.client.linux.User.POSTGRES).getUid().getId();
							int postgresGID=thisAOServer.getLinuxServerGroup(Group.POSTGRES).getGid().getId();
							PostgresServerName serverName=ps.getName();
							File serverDir=new File(PostgresServerManager.pgsqlDirectory, serverName.toString());
							UnixFile newHbaUF=new UnixFile(serverDir, "pg_hba.conf.new");
							ChainWriter out=new ChainWriter(new FileOutputStream(newHbaUF.getFile()));
							try {
								newHbaUF.setMode(0600).chown(postgresUID, postgresGID);
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
								) {
									for(Database db : pds) {
										// peer used from local
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
										out.print(" peer\n");

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
													db.getDatDBA().getPostgresUser().getUsername().getPackage().getBusiness()
												)
											) {
												if(didOne) out.print(',');
												else didOne=true;
												out.print(pu);
											}
										}
										out.print(" 127.0.0.1/32 ident\n");

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
													db.getDatDBA().getPostgresUser().getUsername().getPackage().getBusiness()
												)
											) {
												if(didOne) out.print(',');
												else didOne=true;
												out.print(pu);
											}
										}
										out.print(" ::1/128 ident\n");

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
								} else throw new RuntimeException("Unexpected version of PostgreSQL: "+version);
							} finally {
								out.flush();
								out.close();
							}

							// Move the new file into place
							UnixFile hbaUF=new UnixFile(serverDir, "pg_hba.conf");
							if(hbaUF.getStat().exists() && newHbaUF.contentEquals(hbaUF)) newHbaUF.delete();
							else {
								// Move the new file into place
								newHbaUF.renameTo(hbaUF);

								// Signal reload on PostgreSQL
								BufferedReader in=new BufferedReader(new InputStreamReader(new FileInputStream(new File(serverDir, "postmaster.pid"))));
								String pid=in.readLine();
								// Must be all 0-9
								for(int d=0;d<pid.length();d++) {
									char ch=pid.charAt(d);
									if(ch<'0' || ch>'9') throw new IOException("Invalid character in postmaster.pid first line: "+ch);
								}
								new LinuxProcess(Integer.parseInt(pid)).signal("HUP");
							}
						}
					}
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
