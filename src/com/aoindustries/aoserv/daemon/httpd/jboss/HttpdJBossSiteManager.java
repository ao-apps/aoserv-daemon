/*
 * Copyright 2007-2013, 2017, 2018 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
package com.aoindustries.aoserv.daemon.httpd.jboss;

import com.aoindustries.aoserv.client.AOServConnector;
import com.aoindustries.aoserv.client.linux.PosixPath;
import com.aoindustries.aoserv.client.linux.User;
import com.aoindustries.aoserv.client.web.jboss.Site;
import com.aoindustries.aoserv.client.web.tomcat.SharedTomcat;
import com.aoindustries.aoserv.daemon.AOServDaemon;
import com.aoindustries.aoserv.daemon.httpd.HttpdOperatingSystemConfiguration;
import com.aoindustries.aoserv.daemon.httpd.tomcat.HttpdTomcatSiteManager;
import com.aoindustries.aoserv.daemon.httpd.tomcat.TomcatCommon;
import com.aoindustries.io.unix.UnixFile;
import com.aoindustries.validation.ValidationException;
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
	public UnixFile getPidFile() throws IOException, SQLException {
		return new UnixFile(
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
	protected void flagNeedsRestart(Set<com.aoindustries.aoserv.client.web.Site> sitesNeedingRestarted, Set<SharedTomcat> sharedTomcatsNeedingRestarted) {
		sitesNeedingRestarted.add(httpdSite);
	}
}
