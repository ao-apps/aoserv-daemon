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
import com.aoindustries.net.InetAddress;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.ProtocolFamily;
import java.net.StandardProtocolFamily;
import java.sql.SQLException;
import java.util.HashSet;
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

	private static boolean writeList(Iterable<?> list, ChainWriter out) {
		boolean didOne = false;
		for(Object element : list) {
			if(didOne) out.print(',');
			else didOne = true;
			out.print(element);
		}
		return didOne;
	}

	private static boolean writeList(Iterable<?> list, Iterable<?> whitespace, ChainWriter out) {
		boolean didOne = writeList(list, out);
		// Whitespace placeholder for alignment
		for(Object element : whitespace) {
			if(didOne) out.print(' ');
			else didOne = true;
			for(int i = 0, len = element.toString().length(); i < len; i++) {
				out.print(' ');
			}
		}
		return didOne;
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
								String version=ps.getVersion().getTechnologyVersion(connector).getVersion();
								int postgresUID=thisAOServer.getLinuxServerAccount(com.aoindustries.aoserv.client.linux.User.POSTGRES).getUid().getId();
								int postgresGID=thisAOServer.getLinuxServerGroup(Group.POSTGRES).getGid().getId();
								Server.Name serverName = ps.getName();
								File serverDir = new File(PostgresServerManager.pgsqlDirectory, serverName.toString());
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
										// Find all non-system users on this server, used to limit "peer" and "ident" records
										Set<com.aoindustries.aoserv.client.account.User.Name> postgresIdentUsers = new HashSet<>();
										for(com.aoindustries.aoserv.client.linux.UserServer lsa : thisAOServer.getLinuxServerAccounts()) {
											com.aoindustries.aoserv.client.linux.User la = lsa.getLinuxAccount();
											if(la.getType().canPostgresIdent()) {
												postgresIdentUsers.add(la.getUsername_id());
											}
										}
										// When bind is either 127.0.0.1 or ::1, the server is configured to listen on "localhost",
										// which will listen on both.  The 127.0.0.1 / ::1 distiction only affects which family the
										// AOServDaemon connects to.
										InetAddress bind = ps.getBind().getIpAddress().getInetAddress();
										ProtocolFamily family = bind.getProtocolFamily();
										boolean isLocalhost = bind.equals(InetAddress.LOOPBACK_IPV4) || bind.equals(InetAddress.LOOPBACK_IPV6);
										out.print("# AOServ Daemon: authenticate localhost TCP, only to " + Database.AOSERV + " database\n");
										out.print("hostnossl " + Database.AOSERV + " " + User.POSTGRES + " 127.0.0.1/32 ");
										out.print(family.equals(StandardProtocolFamily.INET) ? "md5" : "reject");
										out.print('\n');
										out.print("hostnossl " + Database.AOSERV + " " + User.POSTGRES + " ::1/128      ");
										out.print(family.equals(StandardProtocolFamily.INET6) ? "md5" : "reject");
										out.print('\n');
										out.print('\n');
										out.print("# Super user: local peer, ident localhost TCP, to all databases\n");
										out.print("local     all " + User.POSTGRES + "              peer\n");
										out.print("hostnossl all " + User.POSTGRES + " 127.0.0.1/32 ident\n");
										out.print("hostnossl all " + User.POSTGRES + " ::1/128      ident\n");
										UserServer postgresmonUser = ps.getPostgresServerUser(User.POSTGRESMON);
										Database postgresmonDB = ps.getPostgresDatabase(Database.POSTGRESMON);
										if(postgresmonUser != null) {
											if(postgresmonDB == null) {
												throw new SQLException("Database not found: " + Database.POSTGRESMON + " on " + serverName);
											}
											UserServer datdba = postgresmonDB.getDatDBA();
											if(!datdba.equals(postgresmonUser)) {
												throw new SQLException(
													Database.POSTGRESMON + " on " + serverName
														+ " does not have the expected owner: expected "
														+ User.POSTGRESMON + ", got " + datdba.getPostresUser_username());
											}
											out.print('\n');
											if(isLocalhost) {
												out.print("# Monitoring: authenticate localhost TCP, only to " + User.POSTGRESMON + " database\n");
												out.print("hostnossl " + Database.POSTGRESMON + " " + User.POSTGRESMON + " 127.0.0.1/32 md5\n");
												out.print("hostnossl " + Database.POSTGRESMON + " " + User.POSTGRESMON + " ::1/128      md5\n");
											} else {
												out.print("# Monitoring: authenticate all TCP, only to " + User.POSTGRESMON + " database\n");
												out.print("host " + Database.POSTGRESMON + " " + User.POSTGRESMON + " all md5\n");
											}
										} else {
											if(postgresmonDB != null) throw new SQLException("User not found: " + User.POSTGRESMON + " on " + serverName);
											// Neither postgresmon database nor use exists - OK and skipped
										}
										boolean didComment = false;
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
												Set<User.Name> identDbUsers = new LinkedHashSet<>();
												Set<User.Name> noIdentDbUsers = new LinkedHashSet<>();
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
														if(postgresIdentUsers.contains(username)) {
															identDbUsers.add(username);
														} else {
															noIdentDbUsers.add(username);
														}
													}
												}
												// Merge identDbUsers then noIdentDbUsers into total list
												Set<User.Name> dbUsers = new LinkedHashSet<>((identDbUsers.size() + noIdentDbUsers.size())*4/3+1);
												dbUsers.addAll(identDbUsers);
												dbUsers.addAll(noIdentDbUsers);
												assert dbUsers.size() == identDbUsers.size() + noIdentDbUsers.size();
												if(dbUsers.isEmpty()) throw new SQLException("No users found for database (should always have at least datdba): " + db);
												if(isLocalhost) {
													if(!didComment) {
														out.print('\n');
														out.print("# Other databases: local peer, authenticate localhost TCP, to all databases in same account\n");
														didComment = true;
													}
													// peer used from local
													if(!identDbUsers.isEmpty()) {
														out.print("local     ").print(name).print(' ');
														writeList(identDbUsers, noIdentDbUsers, out);
														out.print("              peer\n");
													}

													// md5 used from 127.0.0.1/32
													out.print("hostnossl ").print(name).print(' ');
													writeList(dbUsers, out);
													out.print(" 127.0.0.1/32 md5\n");

													// md5 used from ::1/128
													out.print("hostnossl ").print(db.getName()).print(' ');
													writeList(dbUsers, out);
													out.print(" ::1/128      md5\n");
												} else {
													if(!didComment) {
														out.print('\n');
														out.print("# Other databases: local peer, ident localhost TCP, authenticate other TCP, to all databases in same account\n");
														didComment = true;
													}
													// peer used from local
													if(!identDbUsers.isEmpty()) {
														out.print("local ").print(name).print(' ');
														writeList(identDbUsers, noIdentDbUsers, out);
														out.print("              peer\n");
													}

													// ident used from 127.0.0.1/32
													if(!identDbUsers.isEmpty()) {
														out.print("host  ").print(name).print(' ');
														writeList(identDbUsers, noIdentDbUsers, out);
														out.print(" 127.0.0.1/32 ident\n");
													}

													// ident used from ::1/128
													if(!identDbUsers.isEmpty()) {
														out.print("host  ").print(db.getName()).print(' ');
														writeList(identDbUsers, noIdentDbUsers, out);
														out.print(" ::1/128      ident\n");
													}

													// md5 used for other connections
													out.print("host  ").print(db.getName()).print(' ');
													writeList(dbUsers, out);
													out.print(" all          md5\n");
												}
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
					conn.getAccount().getUser().addTableListener(pgHbaManager, 0);
					conn.getLinux().getUser().addTableListener(pgHbaManager, 0);
					conn.getLinux().getUserServer().addTableListener(pgHbaManager, 0);
					conn.getNet().getBind().addTableListener(pgHbaManager, 0);
					conn.getNet().getIpAddress().addTableListener(pgHbaManager, 0);
					conn.getPostgresql().getDatabase().addTableListener(pgHbaManager, 0);
					conn.getPostgresql().getServer().addTableListener(pgHbaManager, 0);
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
