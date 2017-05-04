/*
 * Copyright 2000-2013, 2016, 2017 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
package com.aoindustries.aoserv.daemon.httpd;

import com.aoindustries.aoserv.client.AOServConnector;
import com.aoindustries.aoserv.client.AOServer;
import com.aoindustries.aoserv.client.HttpdServer;
import com.aoindustries.aoserv.client.HttpdSharedTomcat;
import com.aoindustries.aoserv.client.HttpdSite;
import com.aoindustries.aoserv.client.OperatingSystemVersion;
import com.aoindustries.aoserv.daemon.AOServDaemon;
import com.aoindustries.aoserv.daemon.AOServDaemonConfiguration;
import com.aoindustries.aoserv.daemon.LogFactory;
import com.aoindustries.aoserv.daemon.backup.BackupManager;
import com.aoindustries.aoserv.daemon.httpd.tomcat.HttpdSharedTomcatManager;
import com.aoindustries.aoserv.daemon.util.BuilderThread;
import java.io.File;
import java.io.IOException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;

/**
 * Manages all configuration and control over HTTP-related systems.
 *
 * @author  AO Industries, Inc.
 */
final public class HttpdManager extends BuilderThread {

	private static HttpdManager httpdManager;

	private HttpdManager() {
	}

	private static final Object rebuildLock = new Object();
	@Override
	protected boolean doRebuild() {
		try {
			synchronized(rebuildLock) {
				// Get some variables that will be used throughout the method
				List<File> deleteFileList=new ArrayList<>();
				Set<HttpdSharedTomcat> sharedTomcatsNeedingRestarted = new HashSet<>();
				Set<HttpdSite> sitesNeedingRestarted = new HashSet<>();
				Set<HttpdServer> serversNeedingReloaded = new HashSet<>();

				// Rebuild file system objects
				HttpdLogManager.doRebuild(deleteFileList, serversNeedingReloaded);
				HttpdSharedTomcatManager.doRebuild(deleteFileList, sharedTomcatsNeedingRestarted);
				HttpdSiteManager.doRebuild(deleteFileList, sitesNeedingRestarted, sharedTomcatsNeedingRestarted);
				HttpdServerManager.doRebuild(deleteFileList, serversNeedingReloaded);

				// stop, disable, enable, start, and restart necessary processes
				HttpdSharedTomcatManager.stopStartAndRestart(sharedTomcatsNeedingRestarted);
				HttpdSiteManager.stopStartAndRestart(sitesNeedingRestarted);

				// Reload the Apache server configs
				HttpdServerManager.reloadConfigs(serversNeedingReloaded);

				// Back-up and delete the files scheduled for removal
				BackupManager.backupAndDeleteFiles(deleteFileList);
			}
			return true;
		} catch(ThreadDeath TD) {
			throw TD;
		} catch(Throwable T) {
			LogFactory.getLogger(HttpdManager.class).log(Level.SEVERE, null, T);
			return false;
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
				&& AOServDaemonConfiguration.isManagerEnabled(HttpdManager.class)
				&& httpdManager == null
			) {
				System.out.print("Starting HttpdManager: ");
				// Must be a supported operating system
				if(
					osvId == OperatingSystemVersion.REDHAT_ES_4_X86_64
					|| osvId == OperatingSystemVersion.CENTOS_5_I686_AND_X86_64
					|| osvId == OperatingSystemVersion.CENTOS_7_X86_64
				) {
					AOServConnector connector = AOServDaemon.getConnector();
					httpdManager = new HttpdManager();
					connector.getHttpdBinds().addTableListener(httpdManager, 0);
					connector.getHttpdServers().addTableListener(httpdManager, 0);
					connector.getHttpdJBossSites().addTableListener(httpdManager, 0);
					connector.getHttpdSharedTomcats().addTableListener(httpdManager, 0);
					connector.getHttpdSites().addTableListener(httpdManager, 0);
					connector.getHttpdSiteAuthenticatedLocationTable().addTableListener(httpdManager, 0);
					connector.getHttpdSiteBinds().addTableListener(httpdManager, 0);
					connector.getHttpdSiteURLs().addTableListener(httpdManager, 0);
					connector.getHttpdStaticSites().addTableListener(httpdManager, 0);
					connector.getHttpdTomcatContexts().addTableListener(httpdManager, 0);
					connector.getHttpdTomcatDataSources().addTableListener(httpdManager, 0);
					connector.getHttpdTomcatParameters().addTableListener(httpdManager, 0);
					connector.getHttpdTomcatSites().addTableListener(httpdManager, 0);
					connector.getHttpdTomcatSharedSites().addTableListener(httpdManager, 0);
					connector.getHttpdTomcatStdSites().addTableListener(httpdManager, 0);
					connector.getHttpdWorkers().addTableListener(httpdManager, 0);
					connector.getIpAddresses().addTableListener(httpdManager, 0);
					connector.getLinuxServerAccounts().addTableListener(httpdManager, 0);
					connector.getNetBinds().addTableListener(httpdManager, 0);
					System.out.println("Done");
				} else {
					System.out.println("Unsupported OperatingSystemVersion: " + osv);
				}
			}
		}
	}

	public static void waitForRebuild() {
		if(httpdManager!=null) httpdManager.waitForBuild();
	}

	@Override
	public String getProcessTimerDescription() {
		return "Rebuild HTTPD";
	}

	@Override
	public long getProcessTimerMaximumTime() {
		return 15L*60*1000;
	}
}
