/*
 * aoserv-daemon - Server management daemon for the AOServ Platform.
 * Copyright (C) 2007-2013, 2016, 2017, 2018, 2021  AO Industries, Inc.
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
 * along with aoserv-daemon.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.aoindustries.aoserv.daemon.httpd.tomcat;

import com.aoapps.io.posix.PosixFile;
import com.aoapps.lang.validation.ValidationException;
import com.aoindustries.aoserv.client.AOServConnector;
import com.aoindustries.aoserv.client.linux.PosixPath;
import com.aoindustries.aoserv.client.linux.User;
import com.aoindustries.aoserv.client.web.Site;
import com.aoindustries.aoserv.client.web.tomcat.SharedTomcat;
import com.aoindustries.aoserv.client.web.tomcat.SharedTomcatSite;
import com.aoindustries.aoserv.client.web.tomcat.Version;
import com.aoindustries.aoserv.client.web.tomcat.Worker;
import com.aoindustries.aoserv.daemon.AOServDaemon;
import com.aoindustries.aoserv.daemon.httpd.HttpdOperatingSystemConfiguration;
import java.io.File;
import java.io.IOException;
import java.sql.SQLException;
import java.util.Set;

/**
 * Manages SharedTomcatSite configurations.
 *
 * @author  AO Industries, Inc.
 */
abstract class HttpdTomcatSharedSiteManager<TC extends TomcatCommon> extends HttpdTomcatSiteManager<TC> {

	/**
	 * Gets the specific manager for one type of web site.
	 */
	static HttpdTomcatSharedSiteManager<? extends TomcatCommon> getInstance(SharedTomcatSite shrSite) throws IOException, SQLException {
		AOServConnector connector=AOServDaemon.getConnector();

		Version htv=shrSite.getHttpdTomcatSite().getHttpdTomcatVersion();
		if(htv.isTomcat3_1(connector))    return new HttpdTomcatSharedSiteManager_3_1(shrSite);
		if(htv.isTomcat3_2_4(connector))  return new HttpdTomcatSharedSiteManager_3_2_4(shrSite);
		if(htv.isTomcat4_1_X(connector))  return new HttpdTomcatSharedSiteManager_4_1_X(shrSite);
		if(htv.isTomcat5_5_X(connector))  return new HttpdTomcatSharedSiteManager_5_5_X(shrSite);
		if(htv.isTomcat6_0_X(connector))  return new HttpdTomcatSharedSiteManager_6_0_X(shrSite);
		if(htv.isTomcat7_0_X(connector))  return new HttpdTomcatSharedSiteManager_7_0_X(shrSite);
		if(htv.isTomcat8_0_X(connector))  return new HttpdTomcatSharedSiteManager_8_0_X(shrSite);
		if(htv.isTomcat8_5_X(connector))  return new HttpdTomcatSharedSiteManager_8_5_X(shrSite);
		if(htv.isTomcat9_0_X(connector))  return new HttpdTomcatSharedSiteManager_9_0_X(shrSite);
		if(htv.isTomcat10_0_X(connector)) return new HttpdTomcatSharedSiteManager_10_0_X(shrSite);
		throw new SQLException("Unsupported version of shared Tomcat: "+htv.getTechnologyVersion(connector).getVersion()+" on "+shrSite);
	}

	protected final SharedTomcatSite tomcatSharedSite;

	HttpdTomcatSharedSiteManager(SharedTomcatSite tomcatSharedSite) throws SQLException, IOException {
		super(tomcatSharedSite.getHttpdTomcatSite());
		this.tomcatSharedSite = tomcatSharedSite;
	}

	/**
	 * Worker is associated with the shared JVM.
	 */
	@Override
	protected Worker getHttpdWorker() throws IOException, SQLException {
		Worker hw = tomcatSharedSite.getHttpdSharedTomcat().getTomcat4Worker();
		if(hw==null) throw new SQLException("Unable to find shared Worker");
		return hw;
	}

	@Override
	public PosixFile getPidFile() throws IOException, SQLException {
		return new PosixFile(
			HttpdOperatingSystemConfiguration.getHttpOperatingSystemConfiguration().getHttpdSharedTomcatsDirectory()
			+ "/"
			+ tomcatSharedSite.getHttpdSharedTomcat().getName()
			+ "/var/run/tomcat.pid"
		);
	}

	@Override
	public boolean isStartable() throws IOException, SQLException {
		if(httpdSite.isDisabled()) return false;
		SharedTomcat sharedTomcat = tomcatSharedSite.getHttpdSharedTomcat();
		if(sharedTomcat.isDisabled()) return false;
		// Has at least one enabled site: this one
		return true;
	}

	@Override
	public PosixPath getStartStopScriptPath() throws IOException, SQLException {
		try {
			return PosixPath.valueOf(
				HttpdOperatingSystemConfiguration.getHttpOperatingSystemConfiguration().getHttpdSharedTomcatsDirectory()
				+ "/"
				+ tomcatSharedSite.getHttpdSharedTomcat().getName()
				+ "/bin/tomcat"
			);
		} catch(ValidationException e) {
			throw new IOException(e);
		}
	}

	@Override
	public User.Name getStartStopScriptUsername() throws IOException, SQLException {
		return tomcatSharedSite.getHttpdSharedTomcat().getLinuxServerAccount().getLinuxAccount_username_id();
	}

	@Override
	public File getStartStopScriptWorkingDirectory() throws IOException, SQLException {
		return new File(
			HttpdOperatingSystemConfiguration.getHttpOperatingSystemConfiguration().getHttpdSharedTomcatsDirectory()
			+ "/"
			+ tomcatSharedSite.getHttpdSharedTomcat().getName()
		);
	}

	@Override
	protected void flagNeedsRestart(Set<Site> sitesNeedingRestarted, Set<SharedTomcat> sharedTomcatsNeedingRestarted) throws SQLException, IOException {
		sharedTomcatsNeedingRestarted.add(tomcatSharedSite.getHttpdSharedTomcat());
	}

	/**
	 * Shared sites don't need to do anything to enable/disable.
	 * The SharedTomcat manager will update the profile.sites file
	 * and restart to take care of this.
	 */
	@Override
	protected void enableDisable(PosixFile siteDirectory) {
		// Do nothing
	}

	/**
	 * Does not use any README.txt for change detection.
	 */
	@Override
	protected byte[] generateReadmeTxt(String optSlash, String apacheTomcatDir, PosixFile installDir) throws IOException, SQLException {
		return null;
	}
}
