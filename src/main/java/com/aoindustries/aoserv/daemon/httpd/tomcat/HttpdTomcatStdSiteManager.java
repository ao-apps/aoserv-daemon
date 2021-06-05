/*
 * aoserv-daemon - Server management daemon for the AOServ Platform.
 * Copyright (C) 2007-2013, 2014, 2015, 2016, 2017, 2018, 2019, 2021  AO Industries, Inc.
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
import com.aoindustries.aoserv.client.linux.Server;
import com.aoindustries.aoserv.client.linux.User;
import com.aoindustries.aoserv.client.web.Site;
import com.aoindustries.aoserv.client.web.tomcat.JkProtocol;
import com.aoindustries.aoserv.client.web.tomcat.PrivateTomcatSite;
import com.aoindustries.aoserv.client.web.tomcat.SharedTomcat;
import com.aoindustries.aoserv.client.web.tomcat.Version;
import com.aoindustries.aoserv.client.web.tomcat.Worker;
import com.aoindustries.aoserv.daemon.AOServDaemon;
import com.aoindustries.aoserv.daemon.httpd.HttpdOperatingSystemConfiguration;
import com.aoindustries.aoserv.daemon.util.DaemonFileUtils;
import java.io.File;
import java.io.IOException;
import java.sql.SQLException;
import java.util.List;
import java.util.Set;

/**
 * Manages PrivateTomcatSite configurations.
 *
 * @author  AO Industries, Inc.
 */
abstract class HttpdTomcatStdSiteManager<TC extends TomcatCommon> extends HttpdTomcatSiteManager<TC> {

	/**
	 * Gets the specific manager for one type of web site.
	 */
	static HttpdTomcatStdSiteManager<? extends TomcatCommon> getInstance(PrivateTomcatSite stdSite) throws IOException, SQLException {
		AOServConnector connector=AOServDaemon.getConnector();
		Version htv=stdSite.getHttpdTomcatSite().getHttpdTomcatVersion();
		if(htv.isTomcat3_1(connector))    return new HttpdTomcatStdSiteManager_3_1(stdSite);
		if(htv.isTomcat3_2_4(connector))  return new HttpdTomcatStdSiteManager_3_2_4(stdSite);
		if(htv.isTomcat4_1_X(connector))  return new HttpdTomcatStdSiteManager_4_1_X(stdSite);
		if(htv.isTomcat5_5_X(connector))  return new HttpdTomcatStdSiteManager_5_5_X(stdSite);
		if(htv.isTomcat6_0_X(connector))  return new HttpdTomcatStdSiteManager_6_0_X(stdSite);
		if(htv.isTomcat7_0_X(connector))  return new HttpdTomcatStdSiteManager_7_0_X(stdSite);
		if(htv.isTomcat8_0_X(connector))  return new HttpdTomcatStdSiteManager_8_0_X(stdSite);
		if(htv.isTomcat8_5_X(connector))  return new HttpdTomcatStdSiteManager_8_5_X(stdSite);
		if(htv.isTomcat9_0_X(connector))  return new HttpdTomcatStdSiteManager_9_0_X(stdSite);
		if(htv.isTomcat10_0_X(connector)) return new HttpdTomcatStdSiteManager_10_0_X(stdSite);
		throw new SQLException("Unsupported version of standard Tomcat: " + htv.getTechnologyVersion(connector).getVersion() + " on " + stdSite);
	}

	final protected PrivateTomcatSite tomcatStdSite;

	HttpdTomcatStdSiteManager(PrivateTomcatSite tomcatStdSite) throws SQLException, IOException {
		super(tomcatStdSite.getHttpdTomcatSite());
		this.tomcatStdSite = tomcatStdSite;
	}

	/**
	 * Standard sites always have worker directly attached.
	 */
	@Override
	protected Worker getHttpdWorker() throws IOException, SQLException {
		AOServConnector conn = AOServDaemon.getConnector();
		List<Worker> workers = tomcatSite.getHttpdWorkers();

		// Prefer ajp13
		for(Worker hw : workers) {
			if(hw.getHttpdJKProtocol(conn).getProtocol(conn).getProtocol().equals(JkProtocol.AJP13)) return hw;
		}
		// Try ajp12 next
		for(Worker hw : workers) {
			if(hw.getHttpdJKProtocol(conn).getProtocol(conn).getProtocol().equals(JkProtocol.AJP12)) return hw;
		}
		throw new SQLException("Couldn't find either ajp13 or ajp12");
	}

	@Override
	public PosixFile getPidFile() throws IOException, SQLException {
		return new PosixFile(
			HttpdOperatingSystemConfiguration.getHttpOperatingSystemConfiguration().getHttpdSitesDirectory()
			+ "/"
			+ httpdSite.getName()
			+ "/var/run/tomcat.pid"
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
				+ "/bin/tomcat"
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
	protected void flagNeedsRestart(Set<Site> sitesNeedingRestarted, Set<SharedTomcat> sharedTomcatsNeedingRestarted) {
		sitesNeedingRestarted.add(httpdSite);
	}

	@Override
	protected void enableDisable(PosixFile siteDirectory) throws IOException, SQLException {
		PosixFile daemonUF = new PosixFile(siteDirectory, "daemon", false);
		PosixFile daemonSymlink = new PosixFile(daemonUF, "tomcat", false);
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
	protected abstract byte[] buildServerXml(PosixFile siteDirectory, String autoWarning) throws IOException, SQLException;

	@Override
	protected boolean rebuildConfigFiles(PosixFile siteDirectory, Set<PosixFile> restorecon) throws IOException, SQLException {
		final String siteDir = siteDirectory.getPath();
		boolean needsRestart = false;
		String autoWarning = getAutoWarningXml();
		String autoWarningOld = getAutoWarningXmlOld();

		String confServerXML=siteDir+"/conf/server.xml";
		PosixFile confServerXMLFile=new PosixFile(confServerXML);
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
				Server thisServer = AOServDaemon.getThisServer();
				int uid_min = thisServer.getUidMin().getId();
				int gid_min = thisServer.getGidMin().getId();
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
