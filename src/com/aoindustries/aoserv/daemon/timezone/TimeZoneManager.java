/*
 * Copyright 2006-2013, 2015, 2016 by AO Industries, Inc.,
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

/**
 * Configures the timezone settings in /etc/localtime and /etc/sysconfig/clock based on the
 * settings in the ao_servers table.
 *
 * @author  AO Industries, Inc.
 */
public class TimeZoneManager extends BuilderThread {

	private static TimeZoneManager timeZoneManager;

	private TimeZoneManager() {
	}

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
				&& AOServDaemonConfiguration.isManagerEnabled(TimeZoneManager.class)
				&& timeZoneManager==null
			) {
				System.out.print("Starting TimeZoneManager: ");
				AOServConnector connector=AOServDaemon.getConnector();
				timeZoneManager=new TimeZoneManager();
				connector.getAoServers().addTableListener(timeZoneManager, 0);
				System.out.println("Done");
			}
		}
	}

	private static final Object rebuildLock = new Object();
	@Override
	protected boolean doRebuild() {
		try {
			AOServer server=AOServDaemon.getThisAOServer();

			int osv=server.getServer().getOperatingSystemVersion().getPkey();
			if(
				osv!=OperatingSystemVersion.MANDRIVA_2006_0_I586
				&& osv!=OperatingSystemVersion.REDHAT_ES_4_X86_64
				&& osv!=OperatingSystemVersion.CENTOS_5_I686_AND_X86_64
			) throw new AssertionError("Unsupported OperatingSystemVersion: "+osv);

			String timeZone = server.getTimeZone().getName();

			synchronized(rebuildLock) {
				/*
				 * Control the /etc/localtime symbolic link
				 */
				String correctTarget = "../usr/share/zoneinfo/" + timeZone;
				UnixFile localtime = new UnixFile("/etc/localtime");
				Stat localtimeStat = localtime.getStat();
				if(!localtimeStat.exists()) localtime.symLink(correctTarget);
				else {
					if(localtimeStat.isSymLink()) {
						String currentTarget = localtime.readLink();
						if(!currentTarget.equals(correctTarget)) {
							localtime.delete();
							localtime.symLink(correctTarget);
						}
					} else {
						localtime.delete();
						localtime.symLink(correctTarget);
					}
				}

				/*
				 * Control /etc/sysconfig/clock
				 */
				// Build the new file contents to RAM
				ByteArrayOutputStream bout = new ByteArrayOutputStream();
				try (ChainWriter newOut = new ChainWriter(bout)) {
					if(osv==OperatingSystemVersion.CENTOS_5_I686_AND_X86_64) {
						newOut.print("ZONE=\"").print(timeZone).print("\"\n"
								   + "UTC=true\n"
								   + "ARC=false\n");
					} else if(
						osv==OperatingSystemVersion.MANDRIVA_2006_0_I586
						|| osv==OperatingSystemVersion.REDHAT_ES_4_X86_64
					) {
						newOut.print("ARC=false\n"
								   + "ZONE=").print(timeZone).print("\n"
								   + "UTC=false\n");
					} else {
						throw new AssertionError("Unsupported OperatingSystemVersion: "+osv);
					}
				}
				byte[] newBytes = bout.toByteArray();

				// Only update the file when it has changed
				UnixFile clockConfig = new UnixFile("/etc/sysconfig/clock");
				if(!clockConfig.getStat().exists() || !clockConfig.contentEquals(newBytes)) {
					// Write to temp file
					UnixFile newClockConfig = new UnixFile("/etc/sysconfig/clock.new");
					try (OutputStream newClockOut = newClockConfig.getSecureOutputStream(UnixFile.ROOT_UID, UnixFile.ROOT_GID, 0755, true)) {
						newClockOut.write(newBytes);
					}
					// Atomically move into place
					newClockConfig.renameTo(clockConfig);
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
