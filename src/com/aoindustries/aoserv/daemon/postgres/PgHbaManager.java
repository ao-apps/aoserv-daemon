/*
 * Copyright 2003-2013, 2015, 2016 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
package com.aoindustries.aoserv.daemon.postgres;

import com.aoindustries.aoserv.client.AOServConnector;
import com.aoindustries.aoserv.client.AOServer;
import com.aoindustries.aoserv.client.LinuxAccount;
import com.aoindustries.aoserv.client.LinuxGroup;
import com.aoindustries.aoserv.client.OperatingSystemVersion;
import com.aoindustries.aoserv.client.PostgresDatabase;
import com.aoindustries.aoserv.client.PostgresServer;
import com.aoindustries.aoserv.client.PostgresServerUser;
import com.aoindustries.aoserv.client.PostgresUser;
import com.aoindustries.aoserv.client.PostgresVersion;
import com.aoindustries.aoserv.client.Username;
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
			AOServer thisAOServer=AOServDaemon.getThisAOServer();


			int osv=thisAOServer.getServer().getOperatingSystemVersion().getPkey();
			if(
				osv != OperatingSystemVersion.MANDRIVA_2006_0_I586
				&& osv != OperatingSystemVersion.REDHAT_ES_4_X86_64
				&& osv != OperatingSystemVersion.CENTOS_5_I686_AND_X86_64
				&& osv != OperatingSystemVersion.CENTOS_7_X86_64
			) throw new AssertionError("Unsupported OperatingSystemVersion: "+osv);

			synchronized(rebuildLock) {
				for(PostgresServer ps : thisAOServer.getPostgresServers()) {
					String version=ps.getPostgresVersion().getTechnologyVersion(connector).getVersion();
					int postgresUID=thisAOServer.getLinuxServerAccount(LinuxAccount.POSTGRES).getUid().getID();
					int postgresGID=thisAOServer.getLinuxServerGroup(LinuxGroup.POSTGRES).getGid().getID();
					String serverName=ps.getName();
					File serverDir=new File(PostgresServerManager.pgsqlDirectory, serverName);
					UnixFile newHbaUF=new UnixFile(serverDir, "pg_hba.conf.new");
					ChainWriter out=new ChainWriter(new FileOutputStream(newHbaUF.getFile()));
					try {
						newHbaUF.setMode(0600).chown(postgresUID, postgresGID);

						if(
							version.startsWith(PostgresVersion.VERSION_7_1+'.')
							|| version.startsWith(PostgresVersion.VERSION_7_2+'.')
						) {
							out.print("local all trust\n"
									+ "host all 127.0.0.1 255.255.255.255 ident sameuser\n"
									+ "host all 0.0.0.0 0.0.0.0 password\n");
						} else if(version.startsWith(PostgresVersion.VERSION_7_3+'.')) {
							out.print("local all all trust\n");
							List<PostgresServerUser> users=ps.getPostgresServerUsers();
							for(PostgresDatabase db : ps.getPostgresDatabases()) {
								out.print("host ").print(db.getName()).print(' ');
								boolean didOne=false;
								for(PostgresServerUser psu : users) {
									Username un=psu.getPostgresUser().getUsername();

									if(
										// Allow postgres to all databases
										un.getUsername().equals(PostgresUser.POSTGRES)

										// Allow database admin
										|| psu.equals(db.getDatDBA())

										// Allow in same business
										|| un.getPackage().getBusiness().equals(db.getDatDBA().getPostgresUser().getUsername().getPackage().getBusiness())
									) {
										if(didOne) out.print(',');
										else didOne=true;
										out.print(un.getUsername());
									}
								}
								out.print(" 127.0.0.1 255.255.255.255 ident sameuser\n"
										+ "host ").print(db.getName()).print(' ');
								didOne=false;
								for(PostgresServerUser psu : users) {
									Username un=psu.getPostgresUser().getUsername();

									if(
										// Allow postgres to all databases
										un.getUsername().equals(PostgresUser.POSTGRES)

										// Allow database admin
										|| psu.equals(db.getDatDBA())

										// Allow in same business
										|| un.getPackage().getBusiness().equals(db.getDatDBA().getPostgresUser().getUsername().getPackage().getBusiness())
									) {
										if(didOne) out.print(',');
										else didOne=true;
										out.print(un.getUsername());
									}
								}
								out.print(" 0.0.0.0 0.0.0.0 password\n");
							}
						} else if(
							version.startsWith(PostgresVersion.VERSION_8_1+'.')
							|| version.startsWith(PostgresVersion.VERSION_8_3+'.')
							|| version.startsWith(PostgresVersion.VERSION_8_3+'R')
						) {
							List<PostgresServerUser> users=ps.getPostgresServerUsers();
							for(PostgresDatabase db : ps.getPostgresDatabases()) {
								// ident used from local
								out.print("local ").print(db.getName()).print(' ');
								boolean didOne=false;
								for(PostgresServerUser psu : users) {
									Username un=psu.getPostgresUser().getUsername();

									if(
										// Allow postgres to all databases
										un.getUsername().equals(PostgresUser.POSTGRES)

										// Allow database admin
										|| psu.equals(db.getDatDBA())

										// Allow in same business
										|| un.getPackage().getBusiness().equals(db.getDatDBA().getPostgresUser().getUsername().getPackage().getBusiness())
									) {
										if(didOne) out.print(',');
										else didOne=true;
										out.print(un.getUsername());
									}
								}
								out.print(" ident sameuser\n");

								// ident used from 127.0.0.1
								out.print("host ").print(db.getName()).print(' ');
								didOne=false;
								for(PostgresServerUser psu : users) {
									Username un=psu.getPostgresUser().getUsername();

									if(
										// Allow postgres to all databases
										un.getUsername().equals(PostgresUser.POSTGRES)

										// Allow database admin
										|| psu.equals(db.getDatDBA())

										// Allow in same business
										|| un.getPackage().getBusiness().equals(db.getDatDBA().getPostgresUser().getUsername().getPackage().getBusiness())
									) {
										if(didOne) out.print(',');
										else didOne=true;
										out.print(un.getUsername());
									}
								}
								out.print(" 127.0.0.1/32 ident sameuser\n");

								// ident used from ::1/128
								out.print("host ").print(db.getName()).print(' ');
								didOne=false;
								for(PostgresServerUser psu : users) {
									Username un=psu.getPostgresUser().getUsername();

									if(
										// Allow postgres to all databases
										un.getUsername().equals(PostgresUser.POSTGRES)

										// Allow database admin
										|| psu.equals(db.getDatDBA())

										// Allow in same business
										|| un.getPackage().getBusiness().equals(db.getDatDBA().getPostgresUser().getUsername().getPackage().getBusiness())
									) {
										if(didOne) out.print(',');
										else didOne=true;
										out.print(un.getUsername());
									}
								}
								out.print(" ::1/128 ident sameuser\n");

								// md5 used for other connections
								out.print("host ").print(db.getName()).print(' ');
								didOne=false;
								for(PostgresServerUser psu : users) {
									Username un=psu.getPostgresUser().getUsername();

									if(
										// Allow postgres to all databases
										un.getUsername().equals(PostgresUser.POSTGRES)

										// Allow database admin
										|| psu.equals(db.getDatDBA())

										// Allow in same business
										|| un.getPackage().getBusiness().equals(db.getDatDBA().getPostgresUser().getUsername().getPackage().getBusiness())
									) {
										if(didOne) out.print(',');
										else didOne=true;
										out.print(un.getUsername());
									}
								}
								out.print(" 0.0.0.0 0.0.0.0 md5\n");
							}
						} else if(
							version.startsWith(PostgresVersion.VERSION_9_4+'.')
							|| version.startsWith(PostgresVersion.VERSION_9_4+'R')
						) {
							List<PostgresServerUser> users=ps.getPostgresServerUsers();
							for(PostgresDatabase db : ps.getPostgresDatabases()) {
								// peer used from local
								out.print("local ").print(db.getName()).print(' ');
								boolean didOne=false;
								for(PostgresServerUser psu : users) {
									Username un=psu.getPostgresUser().getUsername();

									if(
										// Allow postgres to all databases
										un.getUsername().equals(PostgresUser.POSTGRES)

										// Allow database admin
										|| psu.equals(db.getDatDBA())

										// Allow in same business
										|| un.getPackage().getBusiness().equals(db.getDatDBA().getPostgresUser().getUsername().getPackage().getBusiness())
									) {
										if(didOne) out.print(',');
										else didOne=true;
										out.print(un.getUsername());
									}
								}
								out.print(" peer\n");

								// ident used from 127.0.0.1
								out.print("host ").print(db.getName()).print(' ');
								didOne=false;
								for(PostgresServerUser psu : users) {
									Username un=psu.getPostgresUser().getUsername();

									if(
										// Allow postgres to all databases
										un.getUsername().equals(PostgresUser.POSTGRES)

										// Allow database admin
										|| psu.equals(db.getDatDBA())

										// Allow in same business
										|| un.getPackage().getBusiness().equals(db.getDatDBA().getPostgresUser().getUsername().getPackage().getBusiness())
									) {
										if(didOne) out.print(',');
										else didOne=true;
										out.print(un.getUsername());
									}
								}
								out.print(" 127.0.0.1/32 ident\n");

								// ident used from ::1/128
								out.print("host ").print(db.getName()).print(' ');
								didOne=false;
								for(PostgresServerUser psu : users) {
									Username un=psu.getPostgresUser().getUsername();

									if(
										// Allow postgres to all databases
										un.getUsername().equals(PostgresUser.POSTGRES)

										// Allow database admin
										|| psu.equals(db.getDatDBA())

										// Allow in same business
										|| un.getPackage().getBusiness().equals(db.getDatDBA().getPostgresUser().getUsername().getPackage().getBusiness())
									) {
										if(didOne) out.print(',');
										else didOne=true;
										out.print(un.getUsername());
									}
								}
								out.print(" ::1/128 ident\n");

								// md5 used for other connections
								out.print("host ").print(db.getName()).print(' ');
								didOne=false;
								for(PostgresServerUser psu : users) {
									Username un=psu.getPostgresUser().getUsername();

									if(
										// Allow postgres to all databases
										un.getUsername().equals(PostgresUser.POSTGRES)

										// Allow database admin
										|| psu.equals(db.getDatDBA())

										// Allow in same business
										|| un.getPackage().getBusiness().equals(db.getDatDBA().getPostgresUser().getUsername().getPackage().getBusiness())
									) {
										if(didOne) out.print(',');
										else didOne=true;
										out.print(un.getUsername());
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
		AOServer thisAOServer=AOServDaemon.getThisAOServer();
		int osv=thisAOServer.getServer().getOperatingSystemVersion().getPkey();

		synchronized(System.out) {
			if(
				// Nothing is done for these operating systems
				osv != OperatingSystemVersion.CENTOS_5_DOM0_I686
				&& osv != OperatingSystemVersion.CENTOS_5_DOM0_X86_64
				&& osv != OperatingSystemVersion.CENTOS_7_DOM0_X86_64
				// Check config after OS check so config entry not needed
				&& AOServDaemonConfiguration.isManagerEnabled(PgHbaManager.class)
				&& pgHbaManager==null
			) {
				System.out.print("Starting PgHbaManager: ");
				AOServConnector conn=AOServDaemon.getConnector();
				pgHbaManager=new PgHbaManager();
				conn.getPostgresDatabases().addTableListener(pgHbaManager, 0);
				conn.getPostgresServerUsers().addTableListener(pgHbaManager, 0);
				System.out.println("Done");
			}
		}
	}

	@Override
	public String getProcessTimerDescription() {
		return "Rebuild PostgreSQL pg_hba.conf";
	}
}
