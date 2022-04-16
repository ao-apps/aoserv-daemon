/*
 * aoserv-daemon - Server management daemon for the AOServ Platform.
 * Copyright (C) 2000-2013, 2016, 2017, 2018, 2019, 2020, 2021, 2022  AO Industries, Inc.
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

package com.aoindustries.aoserv.daemon.httpd;

import com.aoapps.io.posix.PosixFile;
import com.aoindustries.aoserv.client.AOServConnector;
import com.aoindustries.aoserv.client.distribution.OperatingSystemVersion;
import com.aoindustries.aoserv.client.linux.Server;
import com.aoindustries.aoserv.client.web.HttpdServer;
import com.aoindustries.aoserv.client.web.Site;
import com.aoindustries.aoserv.client.web.tomcat.SharedTomcat;
import com.aoindustries.aoserv.daemon.AOServDaemon;
import com.aoindustries.aoserv.daemon.AOServDaemonConfiguration;
import com.aoindustries.aoserv.daemon.backup.BackupManager;
import com.aoindustries.aoserv.daemon.httpd.tomcat.HttpdSharedTomcatManager;
import com.aoindustries.aoserv.daemon.posix.linux.PackageManager;
import com.aoindustries.aoserv.daemon.util.BuilderThread;
import com.aoindustries.aoserv.daemon.util.DaemonFileUtils;
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
import java.util.logging.Logger;

/**
 * Manages all configuration and control over HTTP-related systems.
 *
 * @author  AO Industries, Inc.
 */
public final class HttpdManager extends BuilderThread {

	private static final Logger logger = Logger.getLogger(HttpdManager.class.getName());

	private static HttpdManager httpdManager;

	private HttpdManager() {
		// Do nothing
	}

	private static final Object rebuildLock = new Object();
	@Override
	protected boolean doRebuild() {
		try {
			synchronized(rebuildLock) {
				Set<PosixFile> restorecon = new LinkedHashSet<>();
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
		} catch(ThreadDeath td) {
			throw td;
		} catch(Throwable t) {
			logger.log(Level.SEVERE, null, t);
			return false;
		}
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
					connector.getWeb().getHttpdBind().addTableListener(httpdManager, 0);
					connector.getWeb().getHttpdServer().addTableListener(httpdManager, 0);
					connector.getWeb_jboss().getSite().addTableListener(httpdManager, 0);
					connector.getWeb_tomcat().getSharedTomcat().addTableListener(httpdManager, 0);
					connector.getWeb().getSite().addTableListener(httpdManager, 0);
					connector.getWeb().getLocation().addTableListener(httpdManager, 0);
					connector.getWeb().getVirtualHost().addTableListener(httpdManager, 0);
					connector.getWeb().getHeader().addTableListener(httpdManager, 0);
					connector.getWeb().getRewriteRule().addTableListener(httpdManager, 0);
					connector.getWeb().getVirtualHostName().addTableListener(httpdManager, 0);
					connector.getWeb().getStaticSite().addTableListener(httpdManager, 0);
					connector.getWeb_tomcat().getContext().addTableListener(httpdManager, 0);
					connector.getWeb_tomcat().getContextDataSource().addTableListener(httpdManager, 0);
					connector.getWeb_tomcat().getContextParameter().addTableListener(httpdManager, 0);
					connector.getWeb_tomcat().getJkMount().addTableListener(httpdManager, 0);
					connector.getWeb_tomcat().getSite().addTableListener(httpdManager, 0);
					connector.getWeb_tomcat().getSharedTomcatSite().addTableListener(httpdManager, 0);
					connector.getWeb_tomcat().getPrivateTomcatSite().addTableListener(httpdManager, 0);
					connector.getWeb_tomcat().getWorker().addTableListener(httpdManager, 0);
					connector.getNet().getIpAddress().addTableListener(httpdManager, 0);
					connector.getLinux().getUserServer().addTableListener(httpdManager, 0);
					connector.getNet().getBind().addTableListener(httpdManager, 0);
					connector.getPki().getCertificate().addTableListener(httpdManager, 0);
					connector.getPki().getCertificateName().addTableListener(httpdManager, 0);
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
		return 15L * 60 * 1000;
	}
}
