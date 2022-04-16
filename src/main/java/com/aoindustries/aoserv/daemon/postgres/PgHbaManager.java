/*
 * aoserv-daemon - Server management daemon for the AOServ Platform.
 * Copyright (C) 2003-2013, 2015, 2016, 2017, 2018, 2019, 2020, 2021, 2022  AO Industries, Inc.
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

package com.aoindustries.aoserv.daemon.postgres;

import com.aoapps.collections.AoCollections;
import com.aoapps.encoding.ChainWriter;
import com.aoapps.io.posix.PosixFile;
import com.aoapps.net.InetAddress;
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
import com.aoindustries.aoserv.daemon.posix.linux.LinuxProcess;
import com.aoindustries.aoserv.daemon.util.BuilderThread;
import com.aoindustries.aoserv.daemon.util.DaemonFileUtils;
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
import java.util.logging.Logger;

/**
 * Controls the pg_hba.conf files for the PostgreSQL installations.
 *
 * @author  AO Industries, Inc.
 */
public final class PgHbaManager extends BuilderThread {

	private static final Logger logger = Logger.getLogger(PgHbaManager.class.getName());

	private PgHbaManager() {
		// Do nothing
	}

	private static boolean writeList(Iterable<?> list, ChainWriter out) {
		boolean didOne = false;
		for(Object element : list) {
			if(didOne) out.print(',');
			else didOne = true;
			out.print('"').print(element).print('"');
		}
		return didOne;
	}

	private static boolean writeList(Iterable<?> list, Iterable<?> whitespace, ChainWriter out) {
		boolean didOne = writeList(list, out);
		// Whitespace placeholder for alignment
		for(Object element : whitespace) {
			if(didOne) out.print(' '); // ,
			else didOne = true;
			out.print(' '); // "
			for(int i = 0, len = element.toString().length(); i < len; i++) {
				out.print(' ');
			}
			out.print(' '); // "
		}
		return didOne;
	}

	private static final Object rebuildLock = new Object();
	@Override
	@SuppressWarnings({"UseSpecificCatch", "TooBroadCatch"})
	protected boolean doRebuild() {
		try {
			AOServConnector connector=AOServDaemon.getConnector();
			com.aoindustries.aoserv.client.linux.Server thisServer = AOServDaemon.getThisServer();
			OperatingSystemVersion osv = thisServer.getHost().getOperatingSystemVersion();
			int osvId = osv.getPkey();
			if(
				osvId != OperatingSystemVersion.CENTOS_5_I686_AND_X86_64
				&& osvId != OperatingSystemVersion.CENTOS_7_X86_64
			) throw new AssertionError("Unsupported OperatingSystemVersion: " + osv);

			synchronized(rebuildLock) {
				Set<PosixFile> restorecon = new LinkedHashSet<>();
				try {
					for(Server ps : thisServer.getPostgresServers()) {
						List<UserServer> users = ps.getPostgresServerUsers();
						if(users.isEmpty()) {
							logger.severe("No users; refusing to rebuild config: " + ps);
						} else {
							List<Database> pds = ps.getPostgresDatabases();
							if(pds.isEmpty()) {
								logger.severe("No databases; refusing to rebuild config: " + ps);
							} else {
								String version = ps.getVersion().getTechnologyVersion(connector).getVersion();
								int postgresUID = thisServer.getLinuxServerAccount(com.aoindustries.aoserv.client.linux.User.POSTGRES).getUid().getId();
								int postgresGID = thisServer.getLinuxServerGroup(Group.POSTGRES).getGid().getId();
								Server.Name serverName = ps.getName();
								File serverDir = new File(PostgresServerManager.pgsqlDirectory, serverName.toString());
								ByteArrayOutputStream bout = new ByteArrayOutputStream();
								try (ChainWriter out = new ChainWriter(bout)) {
									if(
										version.startsWith(Version.VERSION_7_1 + '.')
										|| version.startsWith(Version.VERSION_7_2 + '.')
									) {
										out.print("local all trust\n"
												+ "host all 127.0.0.1 255.255.255.255 ident sameuser\n"
												+ "host all 0.0.0.0 0.0.0.0 password\n");
									} else if(version.startsWith(Version.VERSION_7_3 + '.')) {
										out.print("local all all trust\n");
										for(Database db : pds) {
											out.print("host \"").print(db.getName()).print("\" ");
											boolean didOne = false;
											for(UserServer psu : users) {
												User pu = psu.getPostgresUser();
												if(
													// Allow postgres to all databases
													pu.getKey().equals(User.POSTGRES)
													// Allow database admin
													|| psu.equals(db.getDatDBA())
													// Allow in same account
													|| pu.getUsername().getPackage().getAccount().equals(
														// TODO: Allow access to subaccounts?
														db.getDatDBA().getPostgresUser().getUsername().getPackage().getAccount()
													)
												) {
													if(didOne) out.print(',');
													else didOne = true;
													out.print('"').print(pu).print('"');
												}
											}
											if(!didOne) throw new SQLException("No users for database " + db);
											out.print(" 127.0.0.1 255.255.255.255 ident sameuser\n"
													+ "host \"").print(db.getName()).print("\" ");
											didOne = false;
											for(UserServer psu : users) {
												User pu = psu.getPostgresUser();
												if(
													// Allow postgres to all databases
													pu.getKey().equals(User.POSTGRES)
													// Allow database admin
													|| psu.equals(db.getDatDBA())
													// Allow in same account
													|| pu.getUsername().getPackage().getAccount().equals(
														// TODO: Allow access to subaccounts?
														db.getDatDBA().getPostgresUser().getUsername().getPackage().getAccount()
													)
												) {
													if(didOne) out.print(',');
													else didOne = true;
													out.print('"').print(pu).print('"');
												}
											}
											if(!didOne) throw new SQLException("No users for database " + db);
											out.print(" 0.0.0.0 0.0.0.0 password\n");
										}
									} else if(
										version.startsWith(Version.VERSION_8_1 + '.')
										|| version.startsWith(Version.VERSION_8_3 + '.')
										|| version.startsWith(Version.VERSION_8_3 + 'R')
									) {
										for(Database db : pds) {
											// ident used from local
											out.print("local \"").print(db.getName()).print("\" ");
											boolean didOne = false;
											for(UserServer psu : users) {
												User pu = psu.getPostgresUser();
												if(
													// Allow postgres to all databases
													pu.getKey().equals(User.POSTGRES)
													// Allow database admin
													|| psu.equals(db.getDatDBA())
													// Allow in same account
													|| pu.getUsername().getPackage().getAccount().equals(
														// TODO: Allow access to subaccounts?
														db.getDatDBA().getPostgresUser().getUsername().getPackage().getAccount()
													)
												) {
													if(didOne) out.print(',');
													else didOne = true;
													out.print('"').print(pu).print('"');
												}
											}
											if(!didOne) throw new SQLException("No users for database " + db);
											out.print(" ident sameuser\n");

											// ident used from 127.0.0.1
											out.print("host \"").print(db.getName()).print("\" ");
											didOne = false;
											for(UserServer psu : users) {
												User pu = psu.getPostgresUser();
												if(
													// Allow postgres to all databases
													pu.getKey().equals(User.POSTGRES)
													// Allow database admin
													|| psu.equals(db.getDatDBA())
													// Allow in same account
													|| pu.getUsername().getPackage().getAccount().equals(
														// TODO: Allow access to subaccounts?
														db.getDatDBA().getPostgresUser().getUsername().getPackage().getAccount()
													)
												) {
													if(didOne) out.print(',');
													else didOne = true;
													out.print('"').print(pu).print('"');
												}
											}
											if(!didOne) throw new SQLException("No users for database " + db);
											out.print(" 127.0.0.1/32 ident sameuser\n");

											// ident used from ::1/128
											out.print("host \"").print(db.getName()).print("\" ");
											didOne = false;
											for(UserServer psu : users) {
												User pu = psu.getPostgresUser();
												if(
													// Allow postgres to all databases
													pu.getKey().equals(User.POSTGRES)
													// Allow database admin
													|| psu.equals(db.getDatDBA())
													// Allow in same account
													|| pu.getUsername().getPackage().getAccount().equals(
														// TODO: Allow access to subaccounts?
														db.getDatDBA().getPostgresUser().getUsername().getPackage().getAccount()
													)
												) {
													if(didOne) out.print(',');
													else didOne = true;
													out.print('"').print(pu).print('"');
												}
											}
											if(!didOne) throw new SQLException("No users for database " + db);
											out.print(" ::1/128 ident sameuser\n");

											// md5 used for other connections
											out.print("host \"").print(db.getName()).print("\" ");
											didOne = false;
											for(UserServer psu : users) {
												User pu = psu.getPostgresUser();
												if(
													// Allow postgres to all databases
													pu.getKey().equals(User.POSTGRES)
													// Allow database admin
													|| psu.equals(db.getDatDBA())
													// Allow in same account
													|| pu.getUsername().getPackage().getAccount().equals(
														// TODO: Allow access to subaccounts?
														db.getDatDBA().getPostgresUser().getUsername().getPackage().getAccount()
													)
												) {
													if(didOne) out.print(',');
													else didOne = true;
													out.print('"').print(pu).print('"');
												}
											}
											if(!didOne) throw new SQLException("No users for database " + db);
											out.print(" 0.0.0.0 0.0.0.0 md5\n");
										}
									} else if(
										version.startsWith(Version.VERSION_9_4 + '.')
										|| version.startsWith(Version.VERSION_9_4 + 'R')
										|| version.startsWith(Version.VERSION_9_5 + '.')
										|| version.startsWith(Version.VERSION_9_5 + 'R')
										|| version.startsWith(Version.VERSION_9_6 + '.')
										|| version.startsWith(Version.VERSION_9_6 + 'R')
										|| version.startsWith(Version.VERSION_10 + '.')
										|| version.startsWith(Version.VERSION_10 + 'R')
										|| version.startsWith(Version.VERSION_11 + '.')
										|| version.startsWith(Version.VERSION_11 + 'R')
										|| version.startsWith(Version.VERSION_12 + '.')
										|| version.startsWith(Version.VERSION_12 + 'R')
										|| version.startsWith(Version.VERSION_13 + '.')
										|| version.startsWith(Version.VERSION_13 + 'R')
										|| version.startsWith(Version.VERSION_14 + '.')
										|| version.startsWith(Version.VERSION_14 + 'R')
									) {
										// TODO: PostgreSQL 14 allows multi-line configs with trailing backslash line continuation
										// scram-sha-256 as of PostgreSQL 10
										boolean isScramSha256 = Version.isScramSha256(version);
										// Find all non-system users on this server, used to limit "peer" and "ident" records
										Set<com.aoindustries.aoserv.client.account.User.Name> postgresIdentUsers = new HashSet<>();
										for(com.aoindustries.aoserv.client.linux.UserServer lsa : thisServer.getLinuxServerAccounts()) {
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

										// aoserv database
										UserServer postgresUser = ps.getPostgresServerUser(User.POSTGRES);
										if(postgresUser == null) throw new SQLException("User not found: " + User.POSTGRES + " on " + serverName);
										Database aoservDatabase = ps.getPostgresDatabase(Database.AOSERV);
										if(aoservDatabase == null) {
											throw new SQLException("Database not found: " + Database.AOSERV + " on " + serverName);
										}
										UserServer aoservDatdba = aoservDatabase.getDatDBA();
										if(!aoservDatdba.equals(postgresUser)) {
											throw new SQLException(
												Database.AOSERV + " on " + serverName
													+ " does not have the expected owner: expected "
													+ User.POSTGRES + ", got " + aoservDatdba.getPostgresUser_username());
										}
										out.print("# AOServ Daemon: authenticate localhost TCP, only to " + Database.AOSERV + " database\n");
										if(family.equals(StandardProtocolFamily.INET)) {
											out.print("hostnossl \"" + Database.AOSERV + "\" \"" + User.POSTGRES + "\" 127.0.0.1/32 ");
											out.print(isScramSha256 ? "scram-sha-256" : "md5");
											out.print('\n');
										} else if(family.equals(StandardProtocolFamily.INET6)) {
											out.print("hostnossl \"" + Database.AOSERV + "\" \"" + User.POSTGRES + "\" ::1/128 ");
											out.print(isScramSha256 ? "scram-sha-256" : "md5");
											out.print('\n');
										} else {
											throw new AssertionError("Unexpected family: " + family);
										}
										out.print('\n');

										// postgres user
										out.print("# Super user: local peer-only, to all databases\n");
										out.print("local all \"" + User.POSTGRES + "\"     peer\n");
										//out.print("hostnossl all \"" + User.POSTGRES + "\" 127.0.0.1/32 ident\n");
										//out.print("hostnossl all \"" + User.POSTGRES + "\" ::1/128      ident\n");
										out.print("host  all \"" + User.POSTGRES + "\" all reject\n");

										// postgresmon database
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
														+ User.POSTGRESMON + ", got " + datdba.getPostgresUser_username());
											}
											out.print('\n');
											if(isLocalhost) {
												out.print("# Monitoring: authenticate localhost TCP, only to " + User.POSTGRESMON + " database\n");
												out.print("hostnossl \"" + Database.POSTGRESMON + "\" \"" + User.POSTGRESMON + "\" 127.0.0.1/32 ");
												out.print(isScramSha256 ? "scram-sha-256" : "md5");
												out.print('\n');
												out.print("hostnossl \"" + Database.POSTGRESMON + "\" \"" + User.POSTGRESMON + "\" ::1/128      ");
												out.print(isScramSha256 ? "scram-sha-256" : "md5");
												out.print('\n');
											} else {
												out.print("# Monitoring: authenticate all TCP, only to " + User.POSTGRESMON + " database\n");
												// TODO: hostssl for Let's Encrypt-enabled servers, or selectable per database
												out.print("host \"" + Database.POSTGRESMON + "\" \"" + User.POSTGRESMON + "\" all ");
												out.print(isScramSha256 ? "scram-sha-256" : "md5");
												out.print('\n');
											}
										} else {
											// Check for database exists without expected user
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
															// Allow in same account as database admin
															|| pu.getUsername().getPackage().getAccount().equals(
																// TODO: Allow access to subaccounts?
																db.getDatDBA().getPostgresUser().getUsername().getPackage().getAccount()
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
												Set<User.Name> dbUsers = AoCollections.newLinkedHashSet(identDbUsers.size() + noIdentDbUsers.size());
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
														out.print("local     \"").print(name).print("\" ");
														writeList(identDbUsers, noIdentDbUsers, out);
														out.print("              peer\n");
													}

													// md5 used from 127.0.0.1/32
													out.print("hostnossl \"").print(name).print("\" ");
													writeList(dbUsers, out);
													out.print(" 127.0.0.1/32 md5\n");

													// md5 used from ::1/128
													out.print("hostnossl \"").print(db.getName()).print("\" ");
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
														out.print("local \"").print(name).print("\" ");
														writeList(identDbUsers, noIdentDbUsers, out);
														out.print("              peer\n");
													}

													// ident used from 127.0.0.1/32
													if(!identDbUsers.isEmpty()) {
														out.print("host  \"").print(name).print("\" ");
														writeList(identDbUsers, noIdentDbUsers, out);
														out.print(" 127.0.0.1/32 ident\n");
													}

													// ident used from ::1/128
													if(!identDbUsers.isEmpty()) {
														out.print("host  \"").print(db.getName()).print("\" ");
														writeList(identDbUsers, noIdentDbUsers, out);
														out.print(" ::1/128      ident\n");
													}

													// md5 used for other connections
													out.print("host  \"").print(db.getName()).print("\" ");
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
									new PosixFile(serverDir, "pg_hba.conf"),
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
										String pid;
										try (BufferedReader in = new BufferedReader(new InputStreamReader(new FileInputStream(pidFile)))) {
											pid = in.readLine();
										}
										// Must be all 0-9
										for(int d = 0; d < pid.length(); d++) {
											char ch = pid.charAt(d);
											if(ch < '0' || ch > '9') throw new IOException("Invalid character in postmaster.pid first line: " + ch);
										}
										new LinuxProcess(Integer.parseInt(pid)).signal("HUP");
									} else {
										logger.log(
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
		} catch(ThreadDeath td) {
			throw td;
		} catch(Throwable t) {
			logger.log(Level.SEVERE, null, t);
			return false;
		}
	}

	private static PgHbaManager pgHbaManager;
	@SuppressWarnings("UseOfSystemOutOrSystemErr")
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
				&& AOServDaemonConfiguration.isManagerEnabled(PgHbaManager.class)
				&& pgHbaManager == null
			) {
				System.out.print("Starting PgHbaManager: ");
				// Must be a supported operating system
				if(
					osvId == OperatingSystemVersion.CENTOS_5_I686_AND_X86_64
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
