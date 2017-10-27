/*
 * Copyright 2017 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
package com.aoindustries.aoserv.daemon.email;

import com.aoindustries.aoserv.client.AOServConnector;
import com.aoindustries.aoserv.client.AOServer;
import com.aoindustries.aoserv.client.OperatingSystemVersion;
import com.aoindustries.aoserv.daemon.AOServDaemon;
import com.aoindustries.aoserv.daemon.AOServDaemonConfiguration;
import com.aoindustries.aoserv.daemon.LogFactory;
import com.aoindustries.aoserv.daemon.unix.linux.PackageManager;
import com.aoindustries.aoserv.daemon.util.BuilderThread;
import com.aoindustries.aoserv.daemon.util.DaemonFileUtils;
import com.aoindustries.encoding.ChainWriter;
import com.aoindustries.io.unix.UnixFile;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.sql.SQLException;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Manages the saslauthd service.
 *
 * @author  AO Industries, Inc.
 */
final public class SaslauthdManager extends BuilderThread {

	private static final Logger logger = Logger.getLogger(SaslauthdManager.class.getName());

	private static SaslauthdManager saslauthdManager;

	private static final UnixFile saslauthConfig = new UnixFile("/etc/sysconfig/saslauthd");

	private static final String SYSTEMCTL = "/usr/bin/systemctl";

	private static final String SERVICE = "saslauthd.service";

	private SaslauthdManager() {
	}

	private static final Object rebuildLock = new Object();
	@Override
	protected boolean doRebuild() {
		try {
			AOServer thisAoServer = AOServDaemon.getThisAOServer();
			OperatingSystemVersion osv = thisAoServer.getServer().getOperatingSystemVersion();
			int osvId = osv.getPkey();
			if(osvId != OperatingSystemVersion.CENTOS_7_X86_64) throw new AssertionError("Unsupported OperatingSystemVersion: " + osv);

			synchronized(rebuildLock) {
				// Do nothing when package not installed
				if(PackageManager.getInstalledPackage(PackageManager.PackageName.CYRUS_SASL) != null) {
					Set<UnixFile> restorecon = new LinkedHashSet<>();
					try {
						ByteArrayOutputStream bout = new ByteArrayOutputStream();
						try (ChainWriter out = new ChainWriter(bout)) {
							out.print("# Generated by ").print(SaslauthdManager.class.getName()).print("\n"
								+ "\n"
								+ "# Directory in which to place saslauthd's listening socket, pid file, and so\n"
								+ "# on.  This directory must already exist.\n"
								+ "SOCKETDIR=/run/saslauthd\n"
								+ "\n"
								+ "# Mechanism to use when checking passwords.  Run \"saslauthd -v\" to get a list\n"
								+ "# of which mechanism your installation was compiled with the ablity to use.\n"
								+ "MECH=pam\n"
								+ "\n"
								+ "# Additional flags to pass to saslauthd on the command line.  See saslauthd(8)\n"
								+ "# for the list of accepted flags.\n"
								+ "\n"
								+ "# -r is added to support \"username@domain\" format usernames while avoiding\n"
								+ "#    possible domain mismatch with \"username@default\" used by Cyrus IMAPD for\n"
								+ "#    no-domain usernames.\n"
								+ "FLAGS=-r\n");
						}

						// Only write to disk if changed
						boolean changed = DaemonFileUtils.atomicWrite(
							saslauthConfig,
							bout.toByteArray(),
							0644,
							UnixFile.ROOT_UID,
							UnixFile.ROOT_GID,
							null,
							restorecon
						);

						// SELinux before next steps
						DaemonFileUtils.restorecon(restorecon);
						restorecon.clear();

						if(
							SendmailCFManager.isSendmailEnabled()
							|| ImapManager.isCyrusImapdEnabled()
						) {
							// Enable when sendmail or cyrus-imapd expected to be running
							logger.fine("Enabling " + SERVICE);
							try {
								AOServDaemon.exec(SYSTEMCTL, "is-enabled", "--quiet", SERVICE);
							} catch(IOException e) {
								// Non-zero response indicates not enabled
								AOServDaemon.exec(SYSTEMCTL, "enable", SERVICE);
							}
							// Reload/start when changed
							if(changed) {
								logger.fine("Reloading or restarting " + SERVICE);
								AOServDaemon.exec(SYSTEMCTL, "reload-or-restart", SERVICE);
							} else {
								logger.fine("Starting " + SERVICE);
								AOServDaemon.exec(SYSTEMCTL, "start", SERVICE);
							}
						} else {
							// Disable when sendmail not expected to be running
							logger.fine("Stopping " + SERVICE);
							AOServDaemon.exec(SYSTEMCTL, "stop", SERVICE);
							logger.fine("Disabling " + SERVICE);
							AOServDaemon.exec(SYSTEMCTL, "disable", SERVICE);
						}
					} finally {
						DaemonFileUtils.restorecon(restorecon);
					}
				} else {
					if(logger.isLoggable(Level.FINE)) {
						logger.fine(
							PackageManager.PackageName.CYRUS_SASL
							+ " package not installed, skipping configuration of saslauthd."
						);
					}
				}
			}
			return true;
		} catch(ThreadDeath TD) {
			throw TD;
		} catch(Throwable T) {
			LogFactory.getLogger(SaslauthdManager.class).log(Level.SEVERE, null, T);
			return false;
		}
	}

	public static void start() throws IOException, SQLException {
		AOServer thisAOServer = AOServDaemon.getThisAOServer();
		OperatingSystemVersion osv = thisAOServer.getServer().getOperatingSystemVersion();
		int osvId = osv.getPkey();

		synchronized(System.out) {
			if(
				osvId != OperatingSystemVersion.MANDRIVA_2006_0_I586
				&& osvId != OperatingSystemVersion.REDHAT_ES_4_X86_64
				&& osvId != OperatingSystemVersion.CENTOS_5_I686_AND_X86_64
				&& osvId != OperatingSystemVersion.CENTOS_5_DOM0_I686
				&& osvId != OperatingSystemVersion.CENTOS_5_DOM0_X86_64
				&& osvId != OperatingSystemVersion.CENTOS_7_DOM0_X86_64
				// Check config after OS check so config entry not needed
				&& AOServDaemonConfiguration.isManagerEnabled(SaslauthdManager.class)
				&& saslauthdManager == null
			) {
				System.out.print("Starting SaslauthdManager: ");
				// Must be a supported operating system
				if(osvId == OperatingSystemVersion.CENTOS_7_X86_64) {
					AOServConnector conn = AOServDaemon.getConnector();
					saslauthdManager = new SaslauthdManager();
					conn.getLinuxServerAccounts().addTableListener(saslauthdManager, 0);
					conn.getNetBinds().addTableListener(saslauthdManager, 0);
					PackageManager.addPackageListener(saslauthdManager);
					System.out.println("Done");
				} else {
					System.out.println("Unsupported OperatingSystemVersion: " + osv);
				}
			}
		}
	}

	@Override
	public String getProcessTimerDescription() {
		return "Rebuild saslauthd";
	}
}
