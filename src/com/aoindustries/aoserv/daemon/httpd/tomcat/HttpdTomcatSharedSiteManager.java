/*
 * Copyright 2007-2013, 2016, 2017, 2018 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
package com.aoindustries.aoserv.daemon.httpd.tomcat;

import com.aoindustries.aoserv.client.AOServConnector;
import com.aoindustries.aoserv.client.validator.UnixPath;
import com.aoindustries.aoserv.client.validator.UserId;
import com.aoindustries.aoserv.client.web.HttpdSite;
import com.aoindustries.aoserv.client.web.tomcat.HttpdSharedTomcat;
import com.aoindustries.aoserv.client.web.tomcat.HttpdTomcatSharedSite;
import com.aoindustries.aoserv.client.web.tomcat.HttpdTomcatVersion;
import com.aoindustries.aoserv.client.web.tomcat.HttpdWorker;
import com.aoindustries.aoserv.daemon.AOServDaemon;
import com.aoindustries.aoserv.daemon.httpd.HttpdOperatingSystemConfiguration;
import com.aoindustries.io.unix.UnixFile;
import com.aoindustries.validation.ValidationException;
import java.io.IOException;
import java.sql.SQLException;
import java.util.Set;

/**
 * Manages HttpdTomcatSharedSite configurations.
 *
 * @author  AO Industries, Inc.
 */
abstract class HttpdTomcatSharedSiteManager<TC extends TomcatCommon> extends HttpdTomcatSiteManager<TC> {

	/**
	 * Gets the specific manager for one type of web site.
	 */
	static HttpdTomcatSharedSiteManager<? extends TomcatCommon> getInstance(HttpdTomcatSharedSite shrSite) throws IOException, SQLException {
		AOServConnector connector=AOServDaemon.getConnector();

		HttpdTomcatVersion htv=shrSite.getHttpdTomcatSite().getHttpdTomcatVersion();
		if(htv.isTomcat3_1(connector)) return new HttpdTomcatSharedSiteManager_3_1(shrSite);
		if(htv.isTomcat3_2_4(connector)) return new HttpdTomcatSharedSiteManager_3_2_4(shrSite);
		if(htv.isTomcat4_1_X(connector)) return new HttpdTomcatSharedSiteManager_4_1_X(shrSite);
		if(htv.isTomcat5_5_X(connector)) return new HttpdTomcatSharedSiteManager_5_5_X(shrSite);
		if(htv.isTomcat6_0_X(connector)) return new HttpdTomcatSharedSiteManager_6_0_X(shrSite);
		if(htv.isTomcat7_0_X(connector)) return new HttpdTomcatSharedSiteManager_7_0_X(shrSite);
		if(htv.isTomcat8_0_X(connector)) return new HttpdTomcatSharedSiteManager_8_0_X(shrSite);
		if(htv.isTomcat8_5_X(connector)) return new HttpdTomcatSharedSiteManager_8_5_X(shrSite);
		if(htv.isTomcat9_0_X(connector)) return new HttpdTomcatSharedSiteManager_9_0_X(shrSite);
		throw new SQLException("Unsupported version of shared Tomcat: "+htv.getTechnologyVersion(connector).getVersion()+" on "+shrSite);
	}

	final protected HttpdTomcatSharedSite tomcatSharedSite;

	HttpdTomcatSharedSiteManager(HttpdTomcatSharedSite tomcatSharedSite) throws SQLException, IOException {
		super(tomcatSharedSite.getHttpdTomcatSite());
		this.tomcatSharedSite = tomcatSharedSite;
	}

	/**
	 * Worker is associated with the shared JVM.
	 */
	@Override
	protected HttpdWorker getHttpdWorker() throws IOException, SQLException {
		HttpdWorker hw = tomcatSharedSite.getHttpdSharedTomcat().getTomcat4Worker();
		if(hw==null) throw new SQLException("Unable to find shared HttpdWorker");
		return hw;
	}

	@Override
	public UnixFile getPidFile() throws IOException, SQLException {
		return new UnixFile(
			HttpdOperatingSystemConfiguration.getHttpOperatingSystemConfiguration().getHttpdSharedTomcatsDirectory()
			+ "/"
			+ tomcatSharedSite.getHttpdSharedTomcat().getName()
			+ "/var/run/tomcat.pid"
		);
	}

	@Override
	public boolean isStartable() throws IOException, SQLException {
		if(httpdSite.isDisabled()) return false;
		HttpdSharedTomcat sharedTomcat = tomcatSharedSite.getHttpdSharedTomcat();
		if(sharedTomcat.isDisabled()) return false;
		// Has at least one enabled site: this one
		return true;
	}

	@Override
	public UnixPath getStartStopScriptPath() throws IOException, SQLException {
		try {
			return UnixPath.valueOf(
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
	public UserId getStartStopScriptUsername() throws IOException, SQLException {
		return tomcatSharedSite.getHttpdSharedTomcat().getLinuxServerAccount().getLinuxAccount().getUsername().getUsername();
	}

	@Override
	protected void flagNeedsRestart(Set<HttpdSite> sitesNeedingRestarted, Set<HttpdSharedTomcat> sharedTomcatsNeedingRestarted) throws SQLException, IOException {
		sharedTomcatsNeedingRestarted.add(tomcatSharedSite.getHttpdSharedTomcat());
	}

	/**
	 * Shared sites don't need to do anything to enable/disable.
	 * The HttpdSharedTomcat manager will update the profile.sites file
	 * and restart to take care of this.
	 */
	@Override
	protected void enableDisable(UnixFile siteDirectory) {
		// Do nothing
	}

	/**
	 * Does not use any README.txt for change detection.
	 */
	@Override
	protected byte[] generateReadmeTxt(String optSlash, String apacheTomcatDir, UnixFile installDir) throws IOException, SQLException {
		return null;
	}
}
