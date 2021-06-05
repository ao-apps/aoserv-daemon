/*
 * aoserv-daemon - Server management daemon for the AOServ Platform.
 * Copyright (C) 2007-2013, 2017, 2018, 2021  AO Industries, Inc.
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
package com.aoindustries.aoserv.daemon.httpd.jboss;

import com.aoapps.io.posix.PosixFile;
import com.aoapps.lang.validation.ValidationException;
import com.aoindustries.aoserv.client.AOServConnector;
import com.aoindustries.aoserv.client.linux.PosixPath;
import com.aoindustries.aoserv.client.linux.User;
import com.aoindustries.aoserv.client.web.jboss.Site;
import com.aoindustries.aoserv.client.web.tomcat.SharedTomcat;
import com.aoindustries.aoserv.daemon.AOServDaemon;
import com.aoindustries.aoserv.daemon.httpd.HttpdOperatingSystemConfiguration;
import com.aoindustries.aoserv.daemon.httpd.tomcat.HttpdTomcatSiteManager;
import com.aoindustries.aoserv.daemon.httpd.tomcat.TomcatCommon;
import java.io.File;
import java.io.IOException;
import java.sql.SQLException;
import java.util.Set;

/**
 * Manages Site configurations.
 *
 * @author  AO Industries, Inc.
 */
public abstract class HttpdJBossSiteManager<TC extends TomcatCommon> extends HttpdTomcatSiteManager<TC> {

	/**
	 * Gets the specific manager for one type of web site.
	 */
	public static HttpdJBossSiteManager<? extends TomcatCommon> getInstance(Site jbossSite) throws IOException, SQLException {
		AOServConnector connector=AOServDaemon.getConnector();
		String jbossVersion = jbossSite.getHttpdJBossVersion().getTechnologyVersion(connector).getVersion();
		if(jbossVersion.equals("2.2.2")) return new HttpdJBossSiteManager_2_2_2(jbossSite);
		throw new SQLException("Unsupported version of standard JBoss: "+jbossVersion+" on "+jbossSite);
	}

	final protected Site jbossSite;

	HttpdJBossSiteManager(Site jbossSite) throws SQLException, IOException {
		super(jbossSite.getHttpdTomcatSite());
		this.jbossSite = jbossSite;
	}

	@Override
	public PosixFile getPidFile() throws IOException, SQLException {
		return new PosixFile(
			HttpdOperatingSystemConfiguration.getHttpOperatingSystemConfiguration().getHttpdSitesDirectory()
			+ "/"
			+ httpdSite.getName()
			+ "/var/run/jboss.pid"
		);
	}

	@Override
	public boolean isStartable() {
		return !httpdSite.isDisabled();
	}

	@Override
	public PosixPath getStartStopScriptPath() throws IOException, SQLException {
		try {
			return PosixPath.valueOf(
				HttpdOperatingSystemConfiguration.getHttpOperatingSystemConfiguration().getHttpdSitesDirectory()
				+ "/"
				+ httpdSite.getName()
				+ "/bin/jboss"
			);
		} catch(ValidationException e) {
			throw new IOException(e);
		}
	}

	@Override
	public User.Name getStartStopScriptUsername() throws IOException, SQLException {
		return httpdSite.getLinuxAccount_username();
	}

	@Override
	public File getStartStopScriptWorkingDirectory() throws IOException, SQLException {
		return new File(
			HttpdOperatingSystemConfiguration.getHttpOperatingSystemConfiguration().getHttpdSitesDirectory()
			+ "/"
			+ httpdSite.getName()
		);
	}

	@Override
	protected void flagNeedsRestart(Set<com.aoindustries.aoserv.client.web.Site> sitesNeedingRestarted, Set<SharedTomcat> sharedTomcatsNeedingRestarted) {
		sitesNeedingRestarted.add(httpdSite);
	}
}
