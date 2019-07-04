/*
 * Copyright 2001-2013, 2014, 2016, 2017, 2018, 2019 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
package com.aoindustries.aoserv.daemon.postgres;

import com.aoindustries.aoserv.client.AOServConnector;
import com.aoindustries.aoserv.client.distribution.OperatingSystemVersion;
import com.aoindustries.aoserv.client.postgresql.Server;
import com.aoindustries.aoserv.client.postgresql.User;
import com.aoindustries.aoserv.client.postgresql.UserServer;
import com.aoindustries.aoserv.client.postgresql.Version;
import com.aoindustries.aoserv.daemon.AOServDaemon;
import com.aoindustries.aoserv.daemon.AOServDaemonConfiguration;
import com.aoindustries.aoserv.daemon.LogFactory;
import com.aoindustries.aoserv.daemon.util.BuilderThread;
import com.aoindustries.sql.AOConnectionPool;
import com.aoindustries.sql.WrappedSQLException;
import com.aoindustries.validation.ValidationException;
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
import org.apache.commons.lang3.NotImplementedException;

/**
 * Controls the PostgreSQL server.
 *
 * @author  AO Industries, Inc.
 */
final public class PostgresUserManager extends BuilderThread {

	private static final boolean DEBUG = false;

	private static void debug(String message) {
		if(DEBUG) {
			System.err.println("DEBUG: [" + PostgresUserManager.class.getName() +"]: " + message);
		}
	}

	private PostgresUserManager() {
	}

	/**
	 * Gets the system roles for a given version of PostgreSQL.
	 */
	private static Set<User.Name> getSystemRoles(String version) {
		if(
			version.startsWith(Version.VERSION_7_1+'.')
			|| version.startsWith(Version.VERSION_7_2+'.')
			|| version.startsWith(Version.VERSION_7_3+'.')
			|| version.startsWith(Version.VERSION_8_0+'.')
			|| version.startsWith(Version.VERSION_8_1+'.')
			|| version.startsWith(Version.VERSION_8_3+'.')
			|| version.startsWith(Version.VERSION_8_3+'R')
			|| version.startsWith(Version.VERSION_9_4+'.')
			|| version.startsWith(Version.VERSION_9_4+'R')
		) {
			return Collections.singleton(User.POSTGRES);
		}
		if(
			version.startsWith(Version.VERSION_9_5+'.')
			|| version.startsWith(Version.VERSION_9_5+'R')
		) {
			throw new NotImplementedException("TODO: Implement for version " + version);
		}
		if(
			version.startsWith(Version.VERSION_9_6+'.')
			|| version.startsWith(Version.VERSION_9_6+'R')
		) {
			throw new NotImplementedException("TODO: Implement for version " + version);
		}
		if(
			version.startsWith(Version.VERSION_10+'.')
			|| version.startsWith(Version.VERSION_10+'R')
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
			version.startsWith(Version.VERSION_11+'.')
			|| version.startsWith(Version.VERSION_11+'R')
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

	private static final Object rebuildLock = new Object();
	@Override
	protected boolean doRebuild() {
		try {
			com.aoindustries.aoserv.client.linux.Server thisAOServer=AOServDaemon.getThisAOServer();
			OperatingSystemVersion osv = thisAOServer.getServer().getOperatingSystemVersion();
			int osvId = osv.getPkey();
			if(
				osvId != OperatingSystemVersion.MANDRIVA_2006_0_I586
				&& osvId != OperatingSystemVersion.REDHAT_ES_4_X86_64
				&& osvId != OperatingSystemVersion.CENTOS_5_I686_AND_X86_64
				&& osvId != OperatingSystemVersion.CENTOS_7_X86_64
			) throw new AssertionError("Unsupported OperatingSystemVersion: " + osv);

			AOServConnector connector = AOServDaemon.getConnector();
			synchronized (rebuildLock) {
				for (Server ps : thisAOServer.getPostgresServers()) {
					String version=ps.getVersion().getTechnologyVersion(connector).getVersion();
					Set<User.Name> systemRoles = getSystemRoles(version);

					// Get the connection to work through
					AOConnectionPool pool=PostgresServerManager.getPool(ps);
					Connection conn=pool.getConnection(false);
					// Get the list of all users that should exist
					List<UserServer> users=ps.getPostgresServerUsers();
					if(users.isEmpty()) {
						LogFactory.getLogger(PostgresUserManager.class).severe("No users; refusing to rebuild config: " + ps);
					} else {
						boolean disableEnableDone = false;
						try {
							// Get the list of all existing users
							Set<User.Name> existing = new HashSet<>();
							try (Statement stmt = conn.createStatement()) {
								String sqlString =
									version.startsWith(Version.VERSION_7_1+'.')
									|| version.startsWith(Version.VERSION_7_2+'.')
									|| version.startsWith(Version.VERSION_7_3+'.')
									|| version.startsWith(Version.VERSION_8_0+'.')
									? "select usename from pg_user"
									: "select rolname from pg_authid"
									;
								try {
									try (ResultSet results = stmt.executeQuery(sqlString)) {
										while (results.next()) {
											String username = results.getString(1);
											if(DEBUG) debug("Found user " + username);
											try {
												User.Name usename = User.Name.valueOf(username);
												if(!existing.add(usename)) throw new SQLException("Duplicate username: " + usename);
											} catch(ValidationException e) {
												throw new SQLException(e);
											}
										}
									}
								} catch(SQLException err) {
									throw new WrappedSQLException(err, sqlString);
								}

								// Find the users that do not exist and should be added
								List<UserServer> needAdded=new ArrayList<>();
								for (UserServer psu : users) {
									User pu=psu.getPostgresUser();
									User.Name username=pu.getKey();
									if(!existing.remove(username)) needAdded.add(psu);
								}

								// Remove the extra users before adding to avoid usesysid or usename conflicts
								for (User.Name username : existing) {
									if(!systemRoles.contains(username)) {
										if(
											username.equals(User.POSTGRES)
											|| username.equals(User.AOADMIN)
											|| username.equals(User.AOSERV_APP)
											|| username.equals(User.AOWEB_APP)
										) throw new SQLException("AOServ Daemon will not automatically drop user, please drop manually: "+username+" on "+ps.getName());
										sqlString = "DROP USER "+username;
										try {
											if(DEBUG) debug("Dropping user: " + sqlString);
											stmt.executeUpdate(sqlString);
											//conn.commit();
										} catch(SQLException err) {
											throw new WrappedSQLException(err, sqlString);
										}
									}
								}

								// Add the new users
								for(UserServer psu : needAdded) {
									User pu = psu.getPostgresUser();
									User.Name username=pu.getKey();
									if(!systemRoles.contains(username)) {
										// Add the user
										StringBuilder sql=new StringBuilder();
										sql
											.append(
												(
													version.startsWith(Version.VERSION_8_1+'.')
														|| version.startsWith(Version.VERSION_8_3+'.')
														|| version.startsWith(Version.VERSION_8_3+'R')
														|| version.startsWith(Version.VERSION_9_4+'.')
														|| version.startsWith(Version.VERSION_9_4+'R')
														|| version.startsWith(Version.VERSION_9_5+'.')
														|| version.startsWith(Version.VERSION_9_5+'R')
														|| version.startsWith(Version.VERSION_9_6+'.')
														|| version.startsWith(Version.VERSION_9_6+'R')
														|| version.startsWith(Version.VERSION_10+'.')
														|| version.startsWith(Version.VERSION_10+'R')
														|| version.startsWith(Version.VERSION_11+'.')
														|| version.startsWith(Version.VERSION_11+'R')
													)
													? "CREATE ROLE "
													: "CREATE USER "
											)
											.append(username)
											//.append(
											//	(
											//		version.startsWith(Version.VERSION_7_1+'.')
											//	)
											//	? " PASSWORD '"
											//	: " UNENCRYPTED PASSWORD '"
											//)
											//.append(User.NO_PASSWORD_DB_VALUE)
											//.append("' ")
											.append(pu.canCreateDB()?" CREATEDB":" NOCREATEDB")
											.append(
												(
													version.startsWith(Version.VERSION_8_1+'.')
														|| version.startsWith(Version.VERSION_8_3+'.')
														|| version.startsWith(Version.VERSION_8_3+'R')
														|| version.startsWith(Version.VERSION_9_4+'.')
														|| version.startsWith(Version.VERSION_9_4+'R')
														|| version.startsWith(Version.VERSION_9_5+'.')
														|| version.startsWith(Version.VERSION_9_5+'R')
														|| version.startsWith(Version.VERSION_9_6+'.')
														|| version.startsWith(Version.VERSION_9_6+'R')
														|| version.startsWith(Version.VERSION_10+'.')
														|| version.startsWith(Version.VERSION_10+'R')
														|| version.startsWith(Version.VERSION_11+'.')
														|| version.startsWith(Version.VERSION_11+'R')
													)
													? (pu.canCatUPD()?" CREATEROLE":" NOCREATEROLE")
													: (pu.canCatUPD()?" CREATEUSER":" NOCREATEUSER")
											).append(
												(
													version.startsWith(Version.VERSION_8_1+'.')
														|| version.startsWith(Version.VERSION_8_3+'.')
														|| version.startsWith(Version.VERSION_8_3+'R')
														|| version.startsWith(Version.VERSION_9_4+'.')
														|| version.startsWith(Version.VERSION_9_4+'R')
														|| version.startsWith(Version.VERSION_9_5+'.')
														|| version.startsWith(Version.VERSION_9_5+'R')
														|| version.startsWith(Version.VERSION_9_6+'.')
														|| version.startsWith(Version.VERSION_9_6+'R')
														|| version.startsWith(Version.VERSION_10+'.')
														|| version.startsWith(Version.VERSION_10+'R')
														|| version.startsWith(Version.VERSION_11+'.')
														|| version.startsWith(Version.VERSION_11+'R')
													)
													? " LOGIN"
													: ""
											)
											;
										sqlString = sql.toString();
										try {
											if(DEBUG) debug("Adding user: " + sqlString);
											stmt.executeUpdate(sqlString);
											//conn.commit();
										} catch(SQLException err) {
											throw new WrappedSQLException(err, sqlString);
										}
									}
								}
								if(
									!(
									version.startsWith(Version.VERSION_7_1+'.')
									|| version.startsWith(Version.VERSION_7_2+'.')
									|| version.startsWith(Version.VERSION_7_3+'.')
									|| version.startsWith(Version.VERSION_8_0+'.')
									)
								) {
									// Enable/disable using rolcanlogin
									for (UserServer psu : users) {
										User.Name username=psu.getPostgresUser().getKey();
										if(!systemRoles.contains(username)) {
											// Get the current login state
											boolean rolcanlogin;
											// TODO: We should be using PreparedStatement instead of relying on usernames to be safe, which they currently are but no guarantees in the future
											sqlString = "select rolcanlogin from pg_authid where rolname='"+username+"'";
											try {
												try (ResultSet results = stmt.executeQuery(sqlString)) {
													if(results.next()) {
														rolcanlogin = results.getBoolean(1);
													} else {
														throw new SQLException("Unable to find pg_authid entry for rolname='"+username+"'");
													}
												}
											} catch(SQLException err) {
												throw new WrappedSQLException(err, sqlString);
											}
											if(!psu.isDisabled()) {
												// Enable if needed
												if(!rolcanlogin) {
													sqlString = "alter role "+username+" login";
													try {
														if(DEBUG) debug("Adding login role: " + sqlString);
														stmt.executeUpdate(sqlString);
													} catch(SQLException err) {
														throw new WrappedSQLException(err, sqlString);
													}
												}
											} else {
												// Disable if needed
												if(rolcanlogin) {
													sqlString = "alter role "+username+" nologin";
													try {
														if(DEBUG) debug("Removing login role: " + sqlString);
														stmt.executeUpdate(sqlString);
													} catch(SQLException err) {
														throw new WrappedSQLException(err, sqlString);
													}
												}
											}
										}
									}
									disableEnableDone=true;
								}
							}
						} finally {
							pool.releaseConnection(conn);
						}

						if(!disableEnableDone) {
							// Disable/enable using password value
							for (UserServer psu : users) {
								String prePassword=psu.getPredisablePassword();
								if(!psu.isDisabled()) {
									if(prePassword!=null) {
										setPassword(psu, prePassword, true);
										psu.setPredisablePassword(null);
									}
								} else {
									if(prePassword==null) {
										psu.setPredisablePassword(getPassword(psu));
										setPassword(psu, User.NO_PASSWORD,true);
									}
								}
							}
						}
					}
				}
			}
			return true;
		} catch(ThreadDeath TD) {
			throw TD;
		} catch(Throwable T) {
			LogFactory.getLogger(PostgresUserManager.class).log(Level.SEVERE, null, T);
			return false;
		}
	}

	public static String getPassword(UserServer psu) throws IOException, SQLException {
		Server ps=psu.getPostgresServer();
		String version=ps.getVersion().getTechnologyVersion(AOServDaemon.getConnector()).getVersion();
		AOConnectionPool pool=PostgresServerManager.getPool(ps);
		Connection conn=pool.getConnection(true);
		try {
			try (PreparedStatement pstmt = conn.prepareStatement(
				version.startsWith(Version.VERSION_7_1+'.')
				|| version.startsWith(Version.VERSION_7_2+'.')
				|| version.startsWith(Version.VERSION_7_3+'.')
				|| version.startsWith(Version.VERSION_8_0+'.')
				? "select passwd from pg_shadow where usename=?"
				: "select rolpassword from pg_authid where rolname=?"
			)) {
				try {
					pstmt.setString(1, psu.getPostgresUser().toString());
					try (ResultSet result = pstmt.executeQuery()) {
						if(result.next()) {
							return result.getString(1);
						} else throw new SQLException("No rows returned.");
					}
				} catch(SQLException err) {
					throw new WrappedSQLException(err, pstmt);
				}
			}
		} finally {
			pool.releaseConnection(conn);
		}
	}

	public static void setPassword(UserServer psu, String password, boolean forceUnencrypted) throws IOException, SQLException {
		// Get the connection to work through
		AOServConnector aoservConn=AOServDaemon.getConnector();
		Server ps=psu.getPostgresServer();
		User.Name username=psu.getPostgresUser().getKey();
		AOConnectionPool pool=PostgresServerManager.getPool(ps);
		Connection conn = pool.getConnection(false);
		try {
			String version=ps.getVersion().getTechnologyVersion(aoservConn).getVersion();
			if(version.startsWith(Version.VERSION_7_1+'.')) {
				if(Objects.equals(password, User.NO_PASSWORD)) {
					// Remove the password
					Statement stmt = conn.createStatement();
					String sqlString = "alter user " + username + " with password '"+User.NO_PASSWORD_DB_VALUE+'\'';
					try {
						stmt.executeUpdate(sqlString);
					} catch(SQLException err) {
						throw new WrappedSQLException(err, sqlString);
					} finally {
						stmt.close();
					}
				} else {
					// Reset the password
					try (PreparedStatement pstmt = conn.prepareStatement("alter user " + username + " with password ?")) {
						try {
							pstmt.setString(1, password);
							pstmt.executeUpdate();
						} catch(SQLException err) {
							throw new WrappedSQLException(err, pstmt);
						}
					}
				}
			} else if(version.startsWith(Version.VERSION_7_2+'.') || version.startsWith(Version.VERSION_7_3+'.')) {
				if(Objects.equals(password, User.NO_PASSWORD)) {
					// Remove the password
					Statement stmt = conn.createStatement();
					String sqlString = "alter user " + username + " with unencrypted password '"+User.NO_PASSWORD_DB_VALUE+'\'';
					try {
						stmt.executeUpdate(sqlString);
					} catch(SQLException err) {
						throw new WrappedSQLException(err, sqlString);
					} finally {
						stmt.close();
					}
				} else {
					// Reset the password
					try (PreparedStatement pstmt = conn.prepareStatement("alter user " + username + " with unencrypted password ?")) {
						try {
							pstmt.setString(1, password);
							pstmt.executeUpdate();
						} catch(SQLException err) {
							throw new WrappedSQLException(err, pstmt);
						}
					}
				}
			} else if(
				version.startsWith(Version.VERSION_8_1+'.')
				|| version.startsWith(Version.VERSION_8_3+'.')
				|| version.startsWith(Version.VERSION_8_3+'R')
				|| version.startsWith(Version.VERSION_9_4+'.')
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
				if(Objects.equals(password, User.NO_PASSWORD)) {
					// Remove the password
					try (Statement stmt = conn.createStatement()) {
						String sqlString = "alter role " + username + " with unencrypted password '"+User.NO_PASSWORD_DB_VALUE+'\'';
						try {
							stmt.executeUpdate(sqlString);
						} catch(SQLException err) {
							throw new WrappedSQLException(err, sqlString);
						}
					}
				} else {
					// TODO: Find a way to use PreparedStatement here for PostgreSQL 8.1 and PostgreSQL 8.3
					checkPasswordChars(password);
					if(forceUnencrypted) {
						// Reset the password (unencrypted)
						try (Statement stmt = conn.createStatement()) {
							String sqlString = "alter role " + username + " with unencrypted password '"+password+'\'';
							try {
								stmt.executeUpdate(sqlString);
							} catch(SQLException err) {
								throw new WrappedSQLException(err, sqlString);
							}
						}
					} else {
						// Reset the password (encrypted)
						try (Statement stmt = conn.createStatement()) {
							String sqlString = "alter role " + username + " with password '"+password+'\'';
							try {
								stmt.executeUpdate(sqlString);
							} catch(SQLException err) {
								throw new WrappedSQLException(err, sqlString);
							}
						}
					}
				}
			} else {
				throw new SQLException("Unsupported version of PostgreSQL: "+version);
			}
		} finally {
			pool.releaseConnection(conn);
		}
	}

	/**
	 * Makes sure a password is safe for direct concatenation for PostgreSQL 8.1.
	 *
	 * Throw SQLException if not acceptable
	 */
	private static void checkPasswordChars(String password) throws SQLException {
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
	}

	private static PostgresUserManager postgresUserManager;
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
				&& AOServDaemonConfiguration.isManagerEnabled(PostgresUserManager.class)
				&& postgresUserManager == null
			) {
				System.out.print("Starting PostgresUserManager: ");
				// Must be a supported operating system
				if(
					osvId == OperatingSystemVersion.MANDRIVA_2006_0_I586
					|| osvId == OperatingSystemVersion.REDHAT_ES_4_X86_64
					|| osvId == OperatingSystemVersion.CENTOS_5_I686_AND_X86_64
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
		if(postgresUserManager!=null) postgresUserManager.waitForBuild();
	}

	@Override
	public String getProcessTimerDescription() {
		return "Rebuild PostgresSQL Users";
	}
}
