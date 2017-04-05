/*
 * Copyright 2006-2013, 2015, 2016, 2017 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
package com.aoindustries.aoserv.daemon.timezone;

import com.aoindustries.aoserv.client.AOServConnector;
import com.aoindustries.aoserv.client.AOServer;
import com.aoindustries.aoserv.client.OperatingSystemVersion;
import com.aoindustries.aoserv.daemon.AOServDaemon;
import com.aoindustries.aoserv.daemon.AOServDaemonConfiguration;
import com.aoindustries.aoserv.daemon.LogFactory;
import com.aoindustries.aoserv.daemon.util.BuilderThread;
import com.aoindustries.encoding.ChainWriter;
import com.aoindustries.io.unix.Stat;
import com.aoindustries.io.unix.UnixFile;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.sql.SQLException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Configures the timezone settings in /etc/localtime and /etc/sysconfig/clock based on the
 * settings in the ao_servers table.
 *
 * @author  AO Industries, Inc.
 */
public class TimeZoneManager extends BuilderThread {

	private static final Logger logger = Logger.getLogger(TimeZoneManager.class.getName());

	private static TimeZoneManager timeZoneManager;

	private static final UnixFile ETC_LOCALTIME = new UnixFile("/etc/localtime");

	private TimeZoneManager() {
	}

	public static void start() throws IOException, SQLException {
		AOServer thisAOServer = AOServDaemon.getThisAOServer();
		OperatingSystemVersion osv = thisAOServer.getServer().getOperatingSystemVersion();
		int osvId = osv.getPkey();

		synchronized(System.out) {
			if(
				// Nothing is done for these operating systems
				osvId != OperatingSystemVersion.CENTOS_5_DOM0_I686
				&& osvId != OperatingSystemVersion.CENTOS_5_DOM0_X86_64
				&& osvId != OperatingSystemVersion.CENTOS_7_DOM0_X86_64
				// Check config after OS check so config entry not needed
				&& AOServDaemonConfiguration.isManagerEnabled(TimeZoneManager.class)
				&& timeZoneManager == null
			) {
				System.out.print("Starting TimeZoneManager: ");
				// Must be a supported operating system
				if(
					osvId == OperatingSystemVersion.MANDRIVA_2006_0_I586
					|| osvId == OperatingSystemVersion.REDHAT_ES_4_X86_64
					|| osvId == OperatingSystemVersion.CENTOS_5_I686_AND_X86_64
					|| osvId == OperatingSystemVersion.CENTOS_7_X86_64
				) {
					AOServConnector conn = AOServDaemon.getConnector();
					timeZoneManager = new TimeZoneManager();
					conn.getAoServers().addTableListener(timeZoneManager, 0);
					conn.getTimeZones().addTableListener(timeZoneManager, 0);
					System.out.println("Done");
				} else {
					System.out.println("Unsupported OperatingSystemVersion: " + osv);
				}
			}
		}
	}

	private static final Object rebuildLock = new Object();
	@Override
	protected boolean doRebuild() {
		try {
			AOServer thisAoServer = AOServDaemon.getThisAOServer();
			OperatingSystemVersion osv = thisAoServer.getServer().getOperatingSystemVersion();
			int osvId = osv.getPkey();
			String timeZone = thisAoServer.getTimeZone().getName();
			synchronized(rebuildLock) {
				if(
					osvId == OperatingSystemVersion.MANDRIVA_2006_0_I586
					|| osvId == OperatingSystemVersion.REDHAT_ES_4_X86_64
					|| osvId == OperatingSystemVersion.CENTOS_5_I686_AND_X86_64
				) {
					/*
					 * Control the /etc/localtime symbolic link
					 */
					String correctTarget = "../usr/share/zoneinfo/" + timeZone;
					Stat localtimeStat = ETC_LOCALTIME.getStat();
					if(!localtimeStat.exists()) ETC_LOCALTIME.symLink(correctTarget);
					else {
						if(localtimeStat.isSymLink()) {
							String currentTarget = ETC_LOCALTIME.readLink();
							if(!currentTarget.equals(correctTarget)) {
								ETC_LOCALTIME.delete();
								ETC_LOCALTIME.symLink(correctTarget);
							}
						} else {
							ETC_LOCALTIME.delete();
							ETC_LOCALTIME.symLink(correctTarget);
						}
					}

					/*
					 * Control /etc/sysconfig/clock
					 */
					// Build the new file contents to RAM
					ByteArrayOutputStream bout = new ByteArrayOutputStream();
					try (ChainWriter newOut = new ChainWriter(bout)) {
						if(osvId == OperatingSystemVersion.CENTOS_5_I686_AND_X86_64) {
							newOut.print("ZONE=\"").print(timeZone).print("\"\n"
									   + "UTC=true\n"
									   + "ARC=false\n");
						} else if(
							osvId == OperatingSystemVersion.MANDRIVA_2006_0_I586
							|| osvId == OperatingSystemVersion.REDHAT_ES_4_X86_64
						) {
							newOut.print("ARC=false\n"
									   + "ZONE=").print(timeZone).print("\n"
									   + "UTC=false\n");
						} else {
							throw new AssertionError("Unsupported OperatingSystemVersion: " + osv);
						}
					}
					byte[] newBytes = bout.toByteArray();

					// Only update the file when it has changed
					UnixFile clockConfig = new UnixFile("/etc/sysconfig/clock");
					if(!clockConfig.getStat().exists() || !clockConfig.contentEquals(newBytes)) {
						// Write to temp file
						UnixFile newClockConfig = new UnixFile("/etc/sysconfig/clock.new");
						try (
							OutputStream newClockOut = newClockConfig.getSecureOutputStream(
								UnixFile.ROOT_UID,
								UnixFile.ROOT_GID,
								0755,
								true,
								thisAoServer.getUidMin().getId(),
								thisAoServer.getGidMin().getId()
							)
						) {
							newClockOut.write(newBytes);
						}
						// Atomically move into place
						newClockConfig.renameTo(clockConfig);
					}
				} else if(osvId == OperatingSystemVersion.CENTOS_7_X86_64) {
					// Check symlink at /etc/localtime, use systemd to set timezone
					String correctTarget = "../usr/share/zoneinfo/" + timeZone;
					Stat localtimeStat = ETC_LOCALTIME.getStat();
					if(
						!localtimeStat.exists()
						|| !localtimeStat.isSymLink()
						|| !correctTarget.equals(ETC_LOCALTIME.readLink())
					) {
						if(logger.isLoggable(Level.INFO)) logger.info("Setting time zone: " + timeZone);
						AOServDaemon.exec(
							"/usr/bin/timedatectl",
							"set-timezone",
							timeZone
						);
					}
				} else {
					throw new AssertionError("Unsupported OperatingSystemVersion: " + osv);
				}
			}
			return true;
		} catch(ThreadDeath TD) {
			throw TD;
		} catch(Throwable T) {
			LogFactory.getLogger(TimeZoneManager.class).log(Level.SEVERE, null, T);
			return false;
		}
	}

	@Override
	public String getProcessTimerDescription() {
		return "Rebuild time zones";
	}

	@Override
	public long getProcessTimerMaximumTime() {
		return (long)30*60*1000;
	}
}
