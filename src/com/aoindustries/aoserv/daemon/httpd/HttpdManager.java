/*
 * Copyright 2000-2013, 2016, 2017, 2018 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
package com.aoindustries.aoserv.daemon.httpd;

import com.aoindustries.aoserv.client.AOServConnector;
import com.aoindustries.aoserv.client.distribution.OperatingSystemVersion;
import com.aoindustries.aoserv.client.linux.Server;
import com.aoindustries.aoserv.client.web.HttpdServer;
import com.aoindustries.aoserv.client.web.Site;
import com.aoindustries.aoserv.client.web.tomcat.SharedTomcat;
import com.aoindustries.aoserv.daemon.AOServDaemon;
import com.aoindustries.aoserv.daemon.AOServDaemonConfiguration;
import com.aoindustries.aoserv.daemon.LogFactory;
import com.aoindustries.aoserv.daemon.backup.BackupManager;
import com.aoindustries.aoserv.daemon.httpd.tomcat.HttpdSharedTomcatManager;
import com.aoindustries.aoserv.daemon.unix.linux.PackageManager;
import com.aoindustries.aoserv.daemon.util.BuilderThread;
import com.aoindustries.aoserv.daemon.util.DaemonFileUtils;
import com.aoindustries.io.unix.UnixFile;
import java.io.File;
import java.io.IOException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashSet;
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
				Set<UnixFile> restorecon = new LinkedHashSet<>();
				try {
					// Get some variables that will be used throughout the method
					List<File> deleteFileList = new ArrayList<>();
					Set<SharedTomcat> sharedTomcatsNeedingRestarted = new HashSet<>();
					Set<Site> sitesNeedingRestarted = new HashSet<>();
					Set<HttpdServer> serversNeedingReloaded = new HashSet<>();
					Set<PackageManager.PackageName> usedPackages = EnumSet.noneOf(PackageManager.PackageName.class);

					// Rebuild file system objects
					HttpdLogManager.doRebuild(deleteFileList, serversNeedingReloaded, restorecon);
					HttpdSharedTomcatManager.doRebuild(deleteFileList, sharedTomcatsNeedingRestarted, usedPackages);
					HttpdSiteManager.doRebuild(deleteFileList, sitesNeedingRestarted, sharedTomcatsNeedingRestarted, usedPackages, restorecon);
					HttpdServerManager.doRebuild(deleteFileList, serversNeedingReloaded, restorecon);

					// restorecon before using any new files
					DaemonFileUtils.restorecon(restorecon);
					restorecon.clear();

					// stop, disable, enable, start, and restart necessary processes
					HttpdSharedTomcatManager.stopStartAndRestart(sharedTomcatsNeedingRestarted);
					HttpdSiteManager.stopStartAndRestart(sitesNeedingRestarted);

					// Back-up and delete the files scheduled for removal
					// This is done before Apache reload because config files might have been removed
					BackupManager.backupAndDeleteFiles(deleteFileList);

					// Reload the Apache server configs
					HttpdServerManager.reloadConfigs(serversNeedingReloaded);

					// Remove any Apache Tomcat packages that are installed and no longer needed
					if(AOServDaemonConfiguration.isPackageManagerUninstallEnabled()) {
						for(PackageManager.PackageName name : PackageManager.PackageName.values()) {
							if(
								name.getRpmName().startsWith(PackageManager.APACHE_TOMCAT_PREFIX)
								// TODO: Remove any PHP packages that are installed and no longer needed
								&& !usedPackages.contains(name)
							) {
								PackageManager.removePackage(name);
							}
						}
					}
				} finally {
					DaemonFileUtils.restorecon(restorecon);
				}
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
		Server thisAOServer = AOServDaemon.getThisAOServer();
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
					osvId == OperatingSystemVersion.CENTOS_5_I686_AND_X86_64
					|| osvId == OperatingSystemVersion.CENTOS_7_X86_64
				) {
					AOServConnector connector = AOServDaemon.getConnector();
					httpdManager = new HttpdManager();
					connector.getWeb().getHttpdBinds().addTableListener(httpdManager, 0);
					connector.getWeb().getHttpdServers().addTableListener(httpdManager, 0);
					connector.getWeb_jboss().getHttpdJBossSites().addTableListener(httpdManager, 0);
					connector.getWeb_tomcat().getHttpdSharedTomcats().addTableListener(httpdManager, 0);
					connector.getWeb().getHttpdSites().addTableListener(httpdManager, 0);
					connector.getWeb().getHttpdSiteAuthenticatedLocationTable().addTableListener(httpdManager, 0);
					connector.getWeb().getHttpdSiteBinds().addTableListener(httpdManager, 0);
					connector.getWeb().getHttpdSiteBindHeaders().addTableListener(httpdManager, 0);
					connector.getWeb().getRewriteRuleTable().addTableListener(httpdManager, 0);
					connector.getWeb().getHttpdSiteURLs().addTableListener(httpdManager, 0);
					connector.getWeb().getHttpdStaticSites().addTableListener(httpdManager, 0);
					connector.getWeb_tomcat().getHttpdTomcatContexts().addTableListener(httpdManager, 0);
					connector.getWeb_tomcat().getHttpdTomcatDataSources().addTableListener(httpdManager, 0);
					connector.getWeb_tomcat().getHttpdTomcatParameters().addTableListener(httpdManager, 0);
					connector.getWeb_tomcat().getHttpdTomcatSiteJkMounts().addTableListener(httpdManager, 0);
					connector.getWeb_tomcat().getHttpdTomcatSites().addTableListener(httpdManager, 0);
					connector.getWeb_tomcat().getHttpdTomcatSharedSites().addTableListener(httpdManager, 0);
					connector.getWeb_tomcat().getHttpdTomcatStdSites().addTableListener(httpdManager, 0);
					connector.getWeb_tomcat().getHttpdWorkers().addTableListener(httpdManager, 0);
					connector.getNet().getIpAddresses().addTableListener(httpdManager, 0);
					connector.getLinux().getLinuxServerAccounts().addTableListener(httpdManager, 0);
					connector.getNet().getNetBinds().addTableListener(httpdManager, 0);
					connector.getPki().getSslCertificates().addTableListener(httpdManager, 0);
					connector.getPki().getSslCertificateNames().addTableListener(httpdManager, 0);
					PackageManager.addPackageListener(httpdManager);
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
