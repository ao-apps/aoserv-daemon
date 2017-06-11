/*
 * Copyright 2000-2013, 2015, 2016, 2017 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
package com.aoindustries.aoserv.daemon.email;

import com.aoindustries.aoserv.client.AOServConnector;
import com.aoindustries.aoserv.client.AOServer;
import com.aoindustries.aoserv.client.EmailDomain;
import com.aoindustries.aoserv.client.OperatingSystemVersion;
import com.aoindustries.aoserv.daemon.AOServDaemon;
import com.aoindustries.aoserv.daemon.AOServDaemonConfiguration;
import com.aoindustries.aoserv.daemon.LogFactory;
import com.aoindustries.aoserv.daemon.util.BuilderThread;
import com.aoindustries.aoserv.daemon.util.DaemonFileUtils;
import com.aoindustries.encoding.ChainWriter;
import com.aoindustries.io.unix.UnixFile;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.sql.SQLException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;

/**
 * @author  AO Industries, Inc.
 */
public final class EmailDomainManager extends BuilderThread {

	/**
	 * email configs.
	 */
	private static final UnixFile configFile = new UnixFile("/etc/mail/local-host-names");

	private static EmailDomainManager emailDomainManager;

	private EmailDomainManager() {
	}

	private static final Object rebuildLock = new Object();
	@Override
	protected boolean doRebuild() {
		try {
			AOServer thisAoServer=AOServDaemon.getThisAOServer();
			OperatingSystemVersion osv = thisAoServer.getServer().getOperatingSystemVersion();
			int osvId = osv.getPkey();
			if(
				osvId != OperatingSystemVersion.MANDRIVA_2006_0_I586
				&& osvId != OperatingSystemVersion.REDHAT_ES_4_X86_64
				&& osvId != OperatingSystemVersion.CENTOS_5_I686_AND_X86_64
			) throw new AssertionError("Unsupported OperatingSystemVersion: " + osv);

			int uid_min = thisAoServer.getUidMin().getId();
			int gid_min = thisAoServer.getGidMin().getId();

			synchronized(rebuildLock) {
				Set<UnixFile> restorecon = new LinkedHashSet<>();
				try {
					// Grab the list of domains from the database
					List<EmailDomain> domains = thisAoServer.getEmailDomains();

					// Create the new file
					byte[] newBytes;
					{
						ByteArrayOutputStream bout = new ByteArrayOutputStream();
						try (ChainWriter out = new ChainWriter(bout)) {
							for(EmailDomain domain : domains) out.println(domain.getDomain());
						}
						newBytes = bout.toByteArray();
					}
					// Write new file only when needed
					if(
						DaemonFileUtils.atomicWrite(
							configFile,
							newBytes,
							0644,
							UnixFile.ROOT_UID,
							UnixFile.ROOT_GID,
							null,
							restorecon
						)
					) {
						// SELinux before reload
						DaemonFileUtils.restorecon(restorecon);
						restorecon.clear();
						// Reload the MTA
						reloadMTA();
					}
				} finally {
					DaemonFileUtils.restorecon(restorecon);
				}
			}
			return true;
		} catch(ThreadDeath TD) {
			throw TD;
		} catch(Throwable T) {
			LogFactory.getLogger(EmailDomainManager.class).log(Level.SEVERE, null, T);
			return false;
		}
	}

	private static final Object reloadLock=new Object();
	private static final String[] reloadSendmailCommandCentOs={
		"/usr/bin/killall",
		"-HUP",
		"sendmail"
	};
	private static final String[] reloadSendmailCommandMandriva={
		"/usr/bin/killall",
		"-HUP",
		"sendmail.sendmail"
	};
	public static void reloadMTA() throws IOException, SQLException {
		synchronized(reloadLock) {
			OperatingSystemVersion osv = AOServDaemon.getThisAOServer().getServer().getOperatingSystemVersion();
			int osvId = osv.getPkey();
			String[] cmd;
			if(osvId == OperatingSystemVersion.MANDRIVA_2006_0_I586) {
				cmd = reloadSendmailCommandMandriva;
			} else if(
				osvId == OperatingSystemVersion.REDHAT_ES_4_X86_64
				|| osvId == OperatingSystemVersion.CENTOS_5_I686_AND_X86_64
			) {
				cmd = reloadSendmailCommandCentOs;
			} else throw new AssertionError("Unsupported OperatingSystemVersion: " + osv);
			AOServDaemon.exec(cmd);
		}
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
				&& AOServDaemonConfiguration.isManagerEnabled(EmailDomainManager.class)
				&& emailDomainManager == null
			) {
				System.out.print("Starting EmailDomainManager: ");
				// Must be a supported operating system
				if(
					osvId == OperatingSystemVersion.MANDRIVA_2006_0_I586
					|| osvId == OperatingSystemVersion.REDHAT_ES_4_X86_64
					|| osvId == OperatingSystemVersion.CENTOS_5_I686_AND_X86_64
				) {
					AOServConnector conn = AOServDaemon.getConnector();
					emailDomainManager = new EmailDomainManager();
					conn.getEmailDomains().addTableListener(emailDomainManager, 0);
					System.out.println("Done");
				} else {
					System.out.println("Unsupported OperatingSystemVersion: " + osv);
				}
			}
		}
	}

	@Override
	public String getProcessTimerDescription() {
		return "Rebuild Email Domains";
	}
}
