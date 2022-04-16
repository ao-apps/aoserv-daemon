/*
 * aoserv-daemon - Server management daemon for the AOServ Platform.
 * Copyright (C) 2006-2013, 2015, 2016, 2017, 2018, 2019, 2020, 2021, 2022  AO Industries, Inc.
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

package com.aoindustries.aoserv.daemon.timezone;

import com.aoapps.encoding.ChainWriter;
import com.aoapps.io.posix.PosixFile;
import com.aoapps.io.posix.Stat;
import com.aoindustries.aoserv.client.AOServConnector;
import com.aoindustries.aoserv.client.distribution.OperatingSystemVersion;
import com.aoindustries.aoserv.client.linux.Server;
import com.aoindustries.aoserv.daemon.AOServDaemon;
import com.aoindustries.aoserv.daemon.AOServDaemonConfiguration;
import com.aoindustries.aoserv.daemon.util.BuilderThread;
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
public final class TimeZoneManager extends BuilderThread {

	private static final Logger logger = Logger.getLogger(TimeZoneManager.class.getName());

	private static TimeZoneManager timeZoneManager;

	private static final PosixFile ETC_LOCALTIME = new PosixFile("/etc/localtime");

	private TimeZoneManager() {
		// Do nothing
	}

	@SuppressWarnings("UseOfSystemOutOrSystemErr")
	public static void start() throws IOException, SQLException {
		Server thisServer = AOServDaemon.getThisServer();
		OperatingSystemVersion osv = thisServer.getHost().getOperatingSystemVersion();
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
					osvId == OperatingSystemVersion.CENTOS_5_I686_AND_X86_64
					|| osvId == OperatingSystemVersion.CENTOS_7_X86_64
				) {
					AOServConnector conn = AOServDaemon.getConnector();
					timeZoneManager = new TimeZoneManager();
					conn.getLinux().getServer().addTableListener(timeZoneManager, 0);
					conn.getLinux().getTimeZone().addTableListener(timeZoneManager, 0);
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
			Server thisServer = AOServDaemon.getThisServer();
			OperatingSystemVersion osv = thisServer.getHost().getOperatingSystemVersion();
			int osvId = osv.getPkey();
			String timeZone = thisServer.getTimeZone().getName();
			synchronized(rebuildLock) {
				if(osvId == OperatingSystemVersion.CENTOS_5_I686_AND_X86_64) {
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
						} else {
							throw new AssertionError("Unsupported OperatingSystemVersion: " + osv);
						}
					}
					byte[] newBytes = bout.toByteArray();

					// Only update the file when it has changed
					PosixFile clockConfig = new PosixFile("/etc/sysconfig/clock");
					if(!clockConfig.getStat().exists() || !clockConfig.contentEquals(newBytes)) {
						// Write to temp file
						PosixFile newClockConfig = new PosixFile("/etc/sysconfig/clock.new");
						try (
							OutputStream newClockOut = newClockConfig.getSecureOutputStream(
								PosixFile.ROOT_UID,
								PosixFile.ROOT_GID,
								0755,
								true,
								thisServer.getUidMin().getId(),
								thisServer.getGidMin().getId()
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
		} catch(ThreadDeath td) {
			throw td;
		} catch(Throwable t) {
			logger.log(Level.SEVERE, null, t);
			return false;
		}
	}

	@Override
	public String getProcessTimerDescription() {
		return "Rebuild time zones";
	}

	@Override
	public long getProcessTimerMaximumTime() {
		return 30L * 60 * 1000;
	}
}
