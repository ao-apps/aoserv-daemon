/*
 * Copyright 2007-2013, 2014, 2015, 2016, 2017, 2018 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
package com.aoindustries.aoserv.daemon.httpd.tomcat;

import com.aoindustries.aoserv.client.AOServConnector;
import com.aoindustries.aoserv.client.AOServer;
import com.aoindustries.aoserv.client.HttpdJKProtocol;
import com.aoindustries.aoserv.client.HttpdSharedTomcat;
import com.aoindustries.aoserv.client.HttpdSite;
import com.aoindustries.aoserv.client.HttpdTomcatStdSite;
import com.aoindustries.aoserv.client.HttpdTomcatVersion;
import com.aoindustries.aoserv.client.HttpdWorker;
import com.aoindustries.aoserv.client.validator.UnixPath;
import com.aoindustries.aoserv.client.validator.UserId;
import com.aoindustries.aoserv.daemon.AOServDaemon;
import com.aoindustries.aoserv.daemon.httpd.HttpdOperatingSystemConfiguration;
import com.aoindustries.aoserv.daemon.util.DaemonFileUtils;
import com.aoindustries.io.unix.UnixFile;
import com.aoindustries.validation.ValidationException;
import java.io.IOException;
import java.sql.SQLException;
import java.util.List;
import java.util.Set;

/**
 * Manages HttpdTomcatStdSite configurations.
 *
 * @author  AO Industries, Inc.
 */
abstract class HttpdTomcatStdSiteManager<TC extends TomcatCommon> extends HttpdTomcatSiteManager<TC> {

	/**
	 * Gets the specific manager for one type of web site.
	 */
	static HttpdTomcatStdSiteManager<? extends TomcatCommon> getInstance(HttpdTomcatStdSite stdSite) throws IOException, SQLException {
		AOServConnector connector=AOServDaemon.getConnector();
		HttpdTomcatVersion htv=stdSite.getHttpdTomcatSite().getHttpdTomcatVersion();
		if(htv.isTomcat3_1(connector)) return new HttpdTomcatStdSiteManager_3_1(stdSite);
		if(htv.isTomcat3_2_4(connector)) return new HttpdTomcatStdSiteManager_3_2_4(stdSite);
		if(htv.isTomcat4_1_X(connector)) return new HttpdTomcatStdSiteManager_4_1_X(stdSite);
		if(htv.isTomcat5_5_X(connector)) return new HttpdTomcatStdSiteManager_5_5_X(stdSite);
		if(htv.isTomcat6_0_X(connector)) return new HttpdTomcatStdSiteManager_6_0_X(stdSite);
		if(htv.isTomcat7_0_X(connector)) return new HttpdTomcatStdSiteManager_7_0_X(stdSite);
		if(htv.isTomcat8_0_X(connector)) return new HttpdTomcatStdSiteManager_8_0_X(stdSite);
		if(htv.isTomcat8_5_X(connector)) return new HttpdTomcatStdSiteManager_8_5_X(stdSite);
		if(htv.isTomcat9_0_X(connector)) return new HttpdTomcatStdSiteManager_9_0_X(stdSite);
		throw new SQLException("Unsupported version of standard Tomcat: " + htv.getTechnologyVersion(connector).getVersion() + " on " + stdSite);
	}

	final protected HttpdTomcatStdSite tomcatStdSite;

	HttpdTomcatStdSiteManager(HttpdTomcatStdSite tomcatStdSite) throws SQLException, IOException {
		super(tomcatStdSite.getHttpdTomcatSite());
		this.tomcatStdSite = tomcatStdSite;
	}

	/**
	 * Standard sites always have worker directly attached.
	 */
	@Override
	protected HttpdWorker getHttpdWorker() throws IOException, SQLException {
		AOServConnector conn = AOServDaemon.getConnector();
		List<HttpdWorker> workers = tomcatSite.getHttpdWorkers();

		// Prefer ajp13
		for(HttpdWorker hw : workers) {
			if(hw.getHttpdJKProtocol(conn).getProtocol(conn).getProtocol().equals(HttpdJKProtocol.AJP13)) return hw;
		}
		// Try ajp12 next
		for(HttpdWorker hw : workers) {
			if(hw.getHttpdJKProtocol(conn).getProtocol(conn).getProtocol().equals(HttpdJKProtocol.AJP12)) return hw;
		}
		throw new SQLException("Couldn't find either ajp13 or ajp12");
	}

	@Override
	public UnixFile getPidFile() throws IOException, SQLException {
		return new UnixFile(
			HttpdOperatingSystemConfiguration.getHttpOperatingSystemConfiguration().getHttpdSitesDirectory()
			+ "/"
			+ httpdSite.getSiteName()
			+ "/var/run/tomcat.pid"
		);
	}

	@Override
	public boolean isStartable() {
		return !httpdSite.isDisabled();
	}

	@Override
	public UnixPath getStartStopScriptPath() throws IOException, SQLException {
		try {
			return UnixPath.valueOf(
				HttpdOperatingSystemConfiguration.getHttpOperatingSystemConfiguration().getHttpdSitesDirectory()
				+ "/"
				+ httpdSite.getSiteName()
				+ "/bin/tomcat"
			);
		} catch(ValidationException e) {
			throw new IOException(e);
		}
	}

	@Override
	public UserId getStartStopScriptUsername() throws IOException, SQLException {
		return httpdSite.getLinuxServerAccount().getLinuxAccount().getUsername().getUsername();
	}

	@Override
	protected void flagNeedsRestart(Set<HttpdSite> sitesNeedingRestarted, Set<HttpdSharedTomcat> sharedTomcatsNeedingRestarted) {
		sitesNeedingRestarted.add(httpdSite);
	}

	@Override
	protected void enableDisable(UnixFile siteDirectory) throws IOException, SQLException {
		UnixFile daemonUF = new UnixFile(siteDirectory, "daemon", false);
		UnixFile daemonSymlink = new UnixFile(daemonUF, "tomcat", false);
		if(!httpdSite.isDisabled()) {
			// Enabled
			if(!daemonSymlink.getStat().exists()) {
				daemonSymlink
					.symLink("../bin/tomcat")
					.chown(
						httpdSite.getLinuxServerAccount().getUid().getId(),
						httpdSite.getLinuxServerGroup().getGid().getId()
					);
			}
		} else {
			// Disabled
			if(daemonSymlink.getStat().exists()) daemonSymlink.delete();
		}
	}

	/**
	 * Builds the server.xml file.
	 */
	protected abstract byte[] buildServerXml(UnixFile siteDirectory, String autoWarning) throws IOException, SQLException;

	@Override
	protected boolean rebuildConfigFiles(UnixFile siteDirectory, Set<UnixFile> restorecon) throws IOException, SQLException {
		final String siteDir = siteDirectory.getPath();
		boolean needsRestart = false;
		String autoWarning = getAutoWarningXml();
		String autoWarningOld = getAutoWarningXmlOld();

		AOServer thisAoServer = AOServDaemon.getThisAOServer();
		int uid_min = thisAoServer.getUidMin().getId();
		int gid_min = thisAoServer.getGidMin().getId();

		String confServerXML=siteDir+"/conf/server.xml";
		UnixFile confServerXMLFile=new UnixFile(confServerXML);
		if(!httpdSite.isManual() || !confServerXMLFile.getStat().exists()) {
			// Only write to the actual file when missing or changed
			if(
				DaemonFileUtils.atomicWrite(
					confServerXMLFile,
					buildServerXml(siteDirectory, autoWarning),
					0660,
					httpdSite.getLinuxServerAccount().getUid().getId(),
					httpdSite.getLinuxServerGroup().getGid().getId(),
					null,
					restorecon
				)
			) {
				// Flag as needing restarted
				needsRestart = true;
			}
		} else {
			try {
				DaemonFileUtils.stripFilePrefix(
					confServerXMLFile,
					autoWarningOld,
					uid_min,
					gid_min
				);
				// This will not be necessary once all are Tomcat 8.5 and newer
				DaemonFileUtils.stripFilePrefix(
					confServerXMLFile,
					autoWarning,
					uid_min,
					gid_min
				);
				DaemonFileUtils.stripFilePrefix(
					confServerXMLFile,
					"<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" + autoWarning,
					uid_min,
					gid_min
				);
			} catch(IOException err) {
				// Errors OK because this is done in manual mode and they might have symbolic linked stuff
			}
		}
		return needsRestart;
	}
}
