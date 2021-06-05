/*
 * aoserv-daemon - Server management daemon for the AOServ Platform.
 * Copyright (C) 2001-2013, 2014, 2016, 2017, 2018, 2019, 2020, 2021  AO Industries, Inc.
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
 * along with aoserv-daemon.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.aoindustries.aoserv.daemon.postgres;

import com.aoapps.lang.util.ErrorPrinter;
import com.aoapps.lang.validation.ValidationException;
import com.aoindustries.aoserv.client.AOServConnector;
import com.aoindustries.aoserv.client.distribution.OperatingSystemVersion;
import com.aoindustries.aoserv.client.postgresql.Server;
import com.aoindustries.aoserv.client.postgresql.User;
import com.aoindustries.aoserv.client.postgresql.UserServer;
import com.aoindustries.aoserv.client.postgresql.Version;
import com.aoindustries.aoserv.daemon.AOServDaemon;
import com.aoindustries.aoserv.daemon.AOServDaemonConfiguration;
import com.aoindustries.aoserv.daemon.util.BuilderThread;
import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.apache.commons.lang3.NotImplementedException;

/**
 * Controls the PostgreSQL server.
 *
 * @author  AO Industries, Inc.
 */
final public class PostgresUserManager extends BuilderThread {

	private static final Logger logger = Logger.getLogger(PostgresUserManager.class.getName());

	private PostgresUserManager() {
	}

	/**
	 * Gets the system roles for a given version of PostgreSQL.
	 * Compare to <code>select rolname from pg_roles order by rolname;</code>
	 */
	private static Set<User.Name> getSystemRoles(String version) {
		if(
			version.startsWith(Version.VERSION_7_1 + '.')
			|| version.startsWith(Version.VERSION_7_2 + '.')
			|| version.startsWith(Version.VERSION_7_3 + '.')
			|| version.startsWith(Version.VERSION_8_0 + '.')
			|| version.startsWith(Version.VERSION_8_1 + '.')
			|| version.startsWith(Version.VERSION_8_3 + '.')
			|| version.startsWith(Version.VERSION_8_3 + 'R')
			|| version.startsWith(Version.VERSION_9_4 + '.')
			|| version.startsWith(Version.VERSION_9_4 + 'R')
		) {
			return Collections.singleton(User.POSTGRES);
		}
		if(
			version.startsWith(Version.VERSION_9_5 + '.')
			|| version.startsWith(Version.VERSION_9_5 + 'R')
		) {
			throw new NotImplementedException("TODO: Implement for version " + version);
		}
		if(
			version.startsWith(Version.VERSION_9_6 + '.')
			|| version.startsWith(Version.VERSION_9_6 + 'R')
		) {
			throw new NotImplementedException("TODO: Implement for version " + version);
		}
		if(
			version.startsWith(Version.VERSION_10 + '.')
			|| version.startsWith(Version.VERSION_10 + 'R')
		) {
			return new HashSet<>(Arrays.asList(
				User.POSTGRES,
				// PostgreSQL 10+
				User.PG_MONITOR,
				User.PG_READ_ALL_SETTINGS,
				User.PG_READ_ALL_STATS,
				User.PG_SIGNAL_BACKEND,
				User.PG_STAT_SCAN_TABLES
			));
		}
		if(
			version.startsWith(Version.VERSION_11 + '.')
			|| version.startsWith(Version.VERSION_11 + 'R')
			|| version.startsWith(Version.VERSION_12 + '.')
			|| version.startsWith(Version.VERSION_12 + 'R')
			|| version.startsWith(Version.VERSION_13 + '.')
			|| version.startsWith(Version.VERSION_13 + 'R')
		) {
			return new HashSet<>(Arrays.asList(
				User.POSTGRES,
				// PostgreSQL 10+
				User.PG_MONITOR,
				User.PG_READ_ALL_SETTINGS,
				User.PG_READ_ALL_STATS,
				User.PG_SIGNAL_BACKEND,
				User.PG_STAT_SCAN_TABLES,
				// PostgreSQL 11+
				User.PG_EXECUTE_SERVER_PROGRAM,
				User.PG_READ_SERVER_FILES,
				User.PG_WRITE_SERVER_FILES
			));
		}
		throw new NotImplementedException("TODO: Implement for version " + version);
	}

	private static boolean supportsRoles(String version) {
		return
			!version.startsWith(Version.VERSION_7_1 + '.')
			&& !version.startsWith(Version.VERSION_7_2 + '.')
			&& !version.startsWith(Version.VERSION_7_3 + '.')
			&& !version.startsWith(Version.VERSION_8_0 + '.');
	}

	private static final Object rebuildLock = new Object();
	@Override
	@SuppressWarnings({"UseSpecificCatch", "TooBroadCatch"})
	protected boolean doRebuild() {
		try {
			com.aoindustries.aoserv.client.linux.Server thisServer = AOServDaemon.getThisServer();
			OperatingSystemVersion osv = thisServer.getHost().getOperatingSystemVersion();
			int osvId = osv.getPkey();
			if(
				osvId != OperatingSystemVersion.CENTOS_5_I686_AND_X86_64
				&& osvId != OperatingSystemVersion.CENTOS_7_X86_64
			) throw new AssertionError("Unsupported OperatingSystemVersion: " + osv);

			AOServConnector connector = AOServDaemon.getConnector();
			synchronized (rebuildLock) {
				for (Server ps : thisServer.getPostgresServers()) {
					// Get the list of all users that should exist
					List<UserServer> users = ps.getPostgresServerUsers();
					if(users.isEmpty()) {
						logger.severe("No users; refusing to rebuild config: " + ps);
					} else {
						String version = ps.getVersion().getTechnologyVersion(connector).getVersion();
						Set<User.Name> systemRoles = getSystemRoles(version);
						boolean supportsRoles = supportsRoles(version);
						// Get the connection to work through
						boolean disableEnableDone = false;
						try (Connection conn = PostgresServerManager.getPool(ps).getConnection()) {
							try {
								// Get the list of all existing users
								Set<User.Name> existing = new HashSet<>();
								String currentSQL = null;
								try (Statement stmt = conn.createStatement()) {
									try (
										ResultSet results = stmt.executeQuery(currentSQL =
											supportsRoles
												? "SELECT rolname FROM pg_authid"
												: "SELECT usename FROM pg_user"
										)
									) {
										while (results.next()) {
											String username = results.getString(1);
											if(logger.isLoggable(Level.FINE)) logger.fine("Found user " + username);
											try {
												User.Name usename = User.Name.valueOf(username);
												if(!existing.add(usename)) throw new SQLException("Duplicate username: " + usename);
											} catch(ValidationException e) {
												throw new SQLException(e);
											}
										}
									}
								} catch(Error | RuntimeException | SQLException e) {
									ErrorPrinter.addSQL(e, currentSQL);
									throw e;
								}

								// Find the users that do not exist and should be added
								List<UserServer> needAdded = new ArrayList<>();
								for (UserServer psu : users) {
									User pu = psu.getPostgresUser();
									User.Name username = pu.getKey();
									if(!existing.remove(username)) needAdded.add(psu);
								}

								// Remove the extra users before adding to avoid usesysid or usename conflicts
								for (User.Name username : existing) {
									if(!systemRoles.contains(username)) {
										if(User.isSpecial(username)) {
											logger.log(
												Level.WARNING,
												null,
												new SQLException("Refusing to drop special user: " + username + " on " + ps.getName())
											);
										} else {
											if(logger.isLoggable(Level.FINE)) logger.fine("Dropping user: " + username);
											currentSQL = null;
											try (Statement stmt = conn.createStatement()) {
												stmt.executeUpdate(currentSQL = "DROP USER \"" + username + '"');
											} catch(Error | RuntimeException | SQLException e) {
												ErrorPrinter.addSQL(e, currentSQL);
												throw e;
											}
										}
									}
								}

								// Add the new users
								for(UserServer psu : needAdded) {
									User pu = psu.getPostgresUser();
									User.Name username=pu.getKey();
									if(!systemRoles.contains(username)) {
										// Add the user
										if(psu.isSpecial()) {
											logger.log(
												Level.WARNING,
												null,
												new SQLException("Refusing to create special user: " + username + " on " + ps.getName())
											);
										} else {
											if(logger.isLoggable(Level.FINE)) logger.fine("Adding user: " + username);
											StringBuilder sql = new StringBuilder();
											sql.append(
												supportsRoles
													? "CREATE ROLE "
													: "CREATE USER "
											);
											sql.append('"').append(username).append('"');
											//.append(
											//	(
											//		version.startsWith(Version.VERSION_7_1+'.')
											//	)
											//	? " PASSWORD '"
											//	: " UNENCRYPTED PASSWORD '"
											//)
											//.append(User.NO_PASSWORD_DB_VALUE)
											//.append("' ")
											if(pu.canCreateDB()) sql.append(" CREATEDB");
											if(pu.canCatUPD()) {
												sql.append(
													supportsRoles
														? " CREATEROLE"
														: " CREATEUSER"
												);
											}
											if(supportsRoles) {
												sql.append(" LOGIN");
											}
											currentSQL = null;
											try (Statement stmt = conn.createStatement()) {
												stmt.executeUpdate(currentSQL = sql.toString());
											} catch(Error | RuntimeException | SQLException e) {
												ErrorPrinter.addSQL(e, currentSQL);
												throw e;
											}
										}
									}
								}
								if(supportsRoles) {
									// Enable/disable using rolcanlogin
									for (UserServer psu : users) {
										if(!psu.isSpecial()) {
											User.Name username=psu.getPostgresUser().getKey();
											if(!systemRoles.contains(username)) {
												// Get the current login state
												boolean rolcanlogin;
												try (PreparedStatement pstmt = conn.prepareStatement("SELECT rolcanlogin FROM pg_authid WHERE rolname=?")) {
													try {
														pstmt.setString(1, username.toString());
														try (ResultSet results = pstmt.executeQuery()) {
															if(results.next()) {
																rolcanlogin = results.getBoolean(1);
															} else {
																throw new SQLException("Unable to find pg_authid entry for rolname='"+username+"'");
															}
														}
													} catch(Error | RuntimeException | SQLException e) {
														ErrorPrinter.addSQL(e, pstmt);
														throw e;
													}
												}
												if(!psu.isDisabled()) {
													// Enable if needed
													if(!rolcanlogin) {
														if(logger.isLoggable(Level.FINE)) logger.fine("Adding login role: " + username);
														currentSQL = null;
														try (Statement stmt = conn.createStatement()) {
															stmt.executeUpdate(currentSQL = "ALTER ROLE \"" + username + "\" LOGIN");
														} catch(Error | RuntimeException | SQLException e) {
															ErrorPrinter.addSQL(e, currentSQL);
															throw e;
														}
													}
												} else {
													// Disable if needed
													if(rolcanlogin) {
														if(logger.isLoggable(Level.FINE)) logger.fine("Removing login role: " + username);
														currentSQL = null;
														try (Statement stmt = conn.createStatement()) {
															stmt.executeUpdate(currentSQL = "ALTER ROLE \"" + username + "\" NOLOGIN");
														} catch(Error | RuntimeException | SQLException e) {
															ErrorPrinter.addSQL(e, currentSQL);
															throw e;
														}
													}
												}
											}
										}
									}
									disableEnableDone = true;
								}
							} catch(SQLException e) {
								conn.abort(AOServDaemon.executorService);
								throw e;
							}
						}

						if(!disableEnableDone) {
							// Disable/enable using password value
							// This will not be necessary once all supported PostgreSQL versions support roles
							for (UserServer psu : users) {
								if(!psu.isSpecial()) {
									String prePassword = psu.getPredisablePassword();
									if(!psu.isDisabled()) {
										if(prePassword != null) {
											setPassword(psu, prePassword, true);
											psu.setPredisablePassword(null);
										}
									} else {
										if(prePassword == null) {
											psu.setPredisablePassword(getPassword(psu));
											setPassword(psu, User.NO_PASSWORD, true);
										}
									}
								}
							}
						}
					}
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

	public static String getPassword(UserServer psu) throws IOException, SQLException {
		if(psu.isSpecial()) {
			throw new SQLException("Refusing to get the password for a special user: " + psu);
		}
		Server ps = psu.getPostgresServer();
		String version = ps.getVersion().getTechnologyVersion(AOServDaemon.getConnector()).getVersion();
		boolean supportsRoles = supportsRoles(version);
		try (Connection conn = PostgresServerManager.getPool(ps).getConnection(true)) {
			try (
				PreparedStatement pstmt = conn.prepareStatement(
					supportsRoles
						? "SELECT rolpassword FROM pg_authid WHERE rolname=?"
						: "SELECT passwd FROM pg_shadow WHERE usename=?"
				)
			) {
				try {
					pstmt.setString(1, psu.getPostgresUser_username().toString());
					try (ResultSet result = pstmt.executeQuery()) {
						if(result.next()) {
							return result.getString(1);
						} else {
							throw new SQLException("No rows returned.");
						}
					}
				} catch(Error | RuntimeException | SQLException e) {
					ErrorPrinter.addSQL(e, pstmt);
					throw e;
				}
			} catch(SQLException e) {
				conn.abort(AOServDaemon.executorService);
				throw e;
			}
		}
	}

	public static void setPassword(UserServer psu, String password, boolean forceUnencrypted) throws IOException, SQLException {
		if(psu.isSpecial()) {
			throw new SQLException("Refusing to set the password for a special user: " + psu);
		}
		// Get the connection to work through
		AOServConnector aoservConn = AOServDaemon.getConnector();
		Server ps = psu.getPostgresServer();
		String version = ps.getVersion().getTechnologyVersion(aoservConn).getVersion();
		boolean supportsRoles = supportsRoles(version);
		User.Name username = psu.getPostgresUser_username();
		try (Connection conn = PostgresServerManager.getPool(ps).getConnection()) {
			try {
				if(supportsRoles) {
					// TODO: Find a way to use PreparedStatement here for PostgreSQL 8.1+
					String currentSQL = null;
					try (Statement stmt = conn.createStatement()) {
						stmt.executeUpdate(currentSQL =
							"ALTER ROLE \"" + username + "\" WITH "
							+ (Objects.equals(password, User.NO_PASSWORD) || forceUnencrypted ? "UNENCRYPTED " : "")
							+ "PASSWORD '"
							+ (
								Objects.equals(password, User.NO_PASSWORD)
									// Remove the password
									? User.NO_PASSWORD_DB_VALUE
									// Reset the password
									: checkPasswordChars(password)
							) + '\'');
					} catch(Error | RuntimeException | SQLException e) {
						ErrorPrinter.addSQL(e, currentSQL);
						throw e;
					}
				} else {
					try (
						PreparedStatement pstmt = conn.prepareStatement(
							"ALTER USER \"" + username + "\" WITH "
								+ (
									// PostgreSQL 7.1 does not support encrypted passwords
									!version.startsWith(Version.VERSION_7_1 + '.')
									&& (Objects.equals(password, User.NO_PASSWORD) || forceUnencrypted)
										? "UNENCRYPTED "
										: ""
								)
								+ "PASSWORD ?"
						)
					) {
						try {
							pstmt.setString(
								1,
								Objects.equals(password, User.NO_PASSWORD)
									// Remove the password
									? User.NO_PASSWORD_DB_VALUE
									// Reset the password
									: password
							);
							pstmt.executeUpdate();
						} catch(Error | RuntimeException | SQLException e) {
							ErrorPrinter.addSQL(e, pstmt);
							throw e;
						}
					}
				}
			} catch(SQLException e) {
				conn.abort(AOServDaemon.executorService);
				throw e;
			}
		}
	}

	/**
	 * Makes sure a password is safe for direct concatenation for PostgreSQL 8.1.
	 *
	 * Throw SQLException if not acceptable
	 */
	private static String checkPasswordChars(String password) throws SQLException {
		if(password==null) throw new SQLException("password is null");
		if(password.length()==0) throw new SQLException("password is empty");
		for(int c=0;c<password.length();c++) {
			char ch = password.charAt(c);
			if(
				(ch<'a' || ch>'z')
				&& (ch<'A' || ch>'Z')
				&& (ch<'0' || ch>'9')
				&& ch!=' '
				&& ch!='!'
				&& ch!='@'
				&& ch!='#'
				&& ch!='$'
				&& ch!='%'
				&& ch!='^'
				&& ch!='&'
				&& ch!='*'
				&& ch!='('
				&& ch!=')'
				&& ch!='-'
				&& ch!='_'
				&& ch!='='
				&& ch!='+'
				&& ch!='['
				&& ch!=']'
				&& ch!='{'
				&& ch!='}'
				&& ch!='|'
				&& ch!=';'
				&& ch!=':'
				&& ch!='"'
				&& ch!=','
				&& ch!='.'
				&& ch!='<'
				&& ch!='>'
				&& ch!='/'
				&& ch!='?'
			) throw new SQLException("Invalid character in password, may only contain a-z, A-Z, 0-9, (space), !@#$%^&*()-_=+[]{}|;:\",.<>/?");
		}
		return password;
	}

	private static PostgresUserManager postgresUserManager;
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
				&& AOServDaemonConfiguration.isManagerEnabled(PostgresUserManager.class)
				&& postgresUserManager == null
			) {
				System.out.print("Starting PostgresUserManager: ");
				// Must be a supported operating system
				if(
					osvId == OperatingSystemVersion.CENTOS_5_I686_AND_X86_64
					|| osvId == OperatingSystemVersion.CENTOS_7_X86_64
				) {
					AOServConnector conn = AOServDaemon.getConnector();
					postgresUserManager = new PostgresUserManager();
					conn.getPostgresql().getUserServer().addTableListener(postgresUserManager, 0);
					System.out.println("Done");
				} else {
					System.out.println("Unsupported OperatingSystemVersion: " + osv);
				}
			}
		}
	}

	public static void waitForRebuild() {
		if(postgresUserManager != null) postgresUserManager.waitForBuild();
	}

	@Override
	public String getProcessTimerDescription() {
		return "Rebuild PostgresSQL Users";
	}
}
