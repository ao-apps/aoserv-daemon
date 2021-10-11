/*
 * aoserv-daemon - Server management daemon for the AOServ Platform.
 * Copyright (C) 2007-2013, 2014, 2015, 2016, 2017, 2018, 2019, 2020, 2021  AO Industries, Inc.
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

import com.aoapps.encoding.ChainWriter;
import com.aoapps.io.posix.PosixFile;
import com.aoindustries.aoserv.client.AOServConnector;
import com.aoindustries.aoserv.client.linux.Server;
import com.aoindustries.aoserv.client.web.tomcat.Context;
import com.aoindustries.aoserv.client.web.tomcat.JkProtocol;
import com.aoindustries.aoserv.client.web.tomcat.SharedTomcatSite;
import com.aoindustries.aoserv.client.web.tomcat.Worker;
import com.aoindustries.aoserv.daemon.AOServDaemon;
import com.aoindustries.aoserv.daemon.util.DaemonFileUtils;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.sql.SQLException;
import java.util.List;
import java.util.Set;

/**
 * Manages SharedTomcatSite version 3.X configurations.
 *
 * @author  AO Industries, Inc.
 */
abstract class HttpdTomcatSharedSiteManager_3_X<TC extends TomcatCommon_3_X> extends HttpdTomcatSharedSiteManager<TC> {

	HttpdTomcatSharedSiteManager_3_X(SharedTomcatSite tomcatSharedSite) throws SQLException, IOException {
		super(tomcatSharedSite);
	}

	/**
	 * Shared Tomcat 3.X sites have worker directly attached like standard sites,
	 * they also only support ajp12.
	 */
	@Override
	protected Worker getHttpdWorker() throws IOException, SQLException {
		AOServConnector conn = AOServDaemon.getConnector();
		List<Worker> workers = tomcatSite.getHttpdWorkers();

		// Only ajp12 supported
		for(Worker hw : workers) {
			if(hw.getHttpdJKProtocol(conn).getProtocol(conn).getProtocol().equals(JkProtocol.AJP12)) return hw;
		}

		throw new SQLException("Couldn't find ajp12 for httpd_tomcat_shared_site="+tomcatSharedSite);
	}

	@Override
	protected void buildSiteDirectoryContents(String optSlash, PosixFile siteDirectory, boolean isUpgrade) throws IOException, SQLException {
		if(isUpgrade) throw new IllegalArgumentException("In-place upgrade not supported");
		// Resolve and allocate stuff used throughout the method
		final TomcatCommon_3_X tomcatCommon = getTomcatCommon();
		final String siteDir = siteDirectory.getPath();
		final int uid = httpdSite.getLinuxServerAccount().getUid().getId();
		final int gid = httpdSite.getLinuxServerGroup().getGid().getId();

		/*
		 * Create the skeleton of the site, the directories and links.
		 */
		DaemonFileUtils.mkdir(siteDir+"/bin", 0770, uid, gid);
		DaemonFileUtils.mkdir(siteDir+"/conf", 0775, uid, gid);
		DaemonFileUtils.mkdir(siteDir+"/daemon", 0770, uid, gid);
		DaemonFileUtils.ln("webapps/"+Context.ROOT_DOC_BASE, siteDir+"/htdocs", uid, gid);
		DaemonFileUtils.mkdir(siteDir+"/lib", 0770, uid, gid);
		DaemonFileUtils.ln("webapps/"+Context.ROOT_DOC_BASE+"/WEB-INF/classes", siteDir+"/servlet", uid, gid);
		DaemonFileUtils.mkdir(siteDir+"/var", 0770, uid, gid);
		DaemonFileUtils.mkdir(siteDir+"/var/log", 0770, uid, gid);
		DaemonFileUtils.mkdir(siteDir+"/webapps", 0775, uid, gid);
		DaemonFileUtils.mkdir(siteDir+"/webapps/"+Context.ROOT_DOC_BASE, 0775, uid, gid);
		DaemonFileUtils.mkdir(siteDir+"/webapps/"+Context.ROOT_DOC_BASE+"/META-INF", 0775, uid, gid);
		DaemonFileUtils.mkdir(siteDir+"/webapps/"+Context.ROOT_DOC_BASE+"/WEB-INF", 0775, uid, gid);
		DaemonFileUtils.mkdir(siteDir+"/webapps/"+Context.ROOT_DOC_BASE+"/WEB-INF/classes", 0770, uid, gid);
		DaemonFileUtils.mkdir(siteDir+"/webapps/"+Context.ROOT_DOC_BASE+"/WEB-INF/cocoon", 0770, uid, gid);
		DaemonFileUtils.mkdir(siteDir+"/webapps/"+Context.ROOT_DOC_BASE+"/WEB-INF/conf", 0770, uid, gid);
		DaemonFileUtils.mkdir(siteDir+"/webapps/"+Context.ROOT_DOC_BASE+"/WEB-INF/lib", 0770, uid, gid);

		Server thisServer = AOServDaemon.getThisServer();
		int uid_min = thisServer.getUidMin().getId();
		int gid_min = thisServer.getGidMin().getId();

		/*
		 * Write the manifest.servlet file.
		 */
		String confManifestServlet=siteDir+"/conf/manifest.servlet";
		try (
			ChainWriter out = new ChainWriter(
				new BufferedOutputStream(
					new PosixFile(confManifestServlet).getSecureOutputStream(uid, gid, 0660, false, uid_min, gid_min)
				)
			)
		) {
			out.print("Manifest-version: 1.0\n"
					  + "Name: javax/servlet\n"
					  + "Sealed: true\n"
					  + "Specification-Title: \"Java Servlet API\"\n"
					  + "Specification-Version: \"2.1.1\"\n"
					  + "Specification-Vendor: \"Sun Microsystems, Inc.\"\n"
					  + "Implementation-Title: \"javax.servlet\"\n"
					  + "Implementation-Version: \"2.1.1\"\n"
					  + "Implementation-Vendor: \"Sun Microsystems, Inc.\"\n"
					  + "\n"
					  + "Name: javax/servlet/http\n"
					  + "Sealed: true\n"
					  + "Specification-Title: \"Java Servlet API\"\n"
					  + "Specification-Version: \"2.1.1\"\n"
					  + "Specification-Vendor: \"Sun Microsystems, Inc.\"\n"
					  + "Implementation-Title: \"javax.servlet\"\n"
					  + "Implementation-Version: \"2.1.1\"\n"
					  + "Implementation-Vendor: \"Sun Microsystems, Inc.\"\n"
					  );
		}

		/*
		 * Create the conf/server.dtd file.
		 */
		String confServerDTD=siteDir+"/conf/server.dtd";
		try (
			ChainWriter out = new ChainWriter(
				new BufferedOutputStream(
					new PosixFile(confServerDTD).getSecureOutputStream(uid, gid, 0660, false, uid_min, gid_min)
				)
			)
		) {
			out.print("<?xml version=\"1.0\" encoding=\"ISO-8859-1\"?>\n"
					  + "\n"
					  + "<!ELEMENT Host (ContextManager+)>\n"
					  + "<!ATTLIST Host\n"
					  + "    adminPort NMTOKEN \"-1\"\n"
					  + "    workDir CDATA \"work\">\n"
					  + "\n"
					  + "<!ELEMENT ContextManager (Context+, Interceptor*, Connector+)>\n"
					  + "<!ATTLIST ContextManager\n"
					  + "    port NMTOKEN \"8080\"\n"
					  + "    hostName NMTOKEN \"\"\n"
					  + "    inet NMTOKEN \"\">\n"
					  + "\n"
					  + "<!ELEMENT Context EMPTY>\n"
					  + "<!ATTLIST Context\n"
					  + "    path CDATA #REQUIRED\n"
					  + "    docBase CDATA #REQUIRED\n"
					  + "    defaultSessionTimeOut NMTOKEN \"30\"\n"
					  + "    isWARExpanded (true | false) \"true\"\n"
					  + "    isWARValidated (false | true) \"false\"\n"
					  + "    isInvokerEnabled (true | false) \"true\"\n"
					  + "    isWorkDirPersistent (false | true) \"false\">\n"
					  + "\n"
					  + "<!ELEMENT Interceptor EMPTY>\n"
					  + "<!ATTLIST Interceptor\n"
					  + "    className NMTOKEN #REQUIRED\n"
					  + "    docBase   CDATA #REQUIRED>\n"
					  + "\n"
					  + "<!ELEMENT Connector (Parameter*)>\n"
					  + "<!ATTLIST Connector\n"
					  + "    className NMTOKEN #REQUIRED>\n"
					  + "\n"
					  + "<!ELEMENT Parameter EMPTY>\n"
					  + "<!ATTLIST Parameter\n"
					  + "    name CDATA #REQUIRED\n"
					  + "    value CDATA \"\">\n"
					  );
		}

		// Create the test-tomcat.xml file.
		tomcatCommon.createTestTomcatXml(siteDir+"/conf", uid, gid, 0660, uid_min, gid_min);

		/*
		 * Create the tomcat-users.xml file
		 */
		String confTomcatUsers=siteDir+"/conf/tomcat-users.xml";
		try (
			ChainWriter out = new ChainWriter(
				new BufferedOutputStream(
					new PosixFile(confTomcatUsers).getSecureOutputStream(uid, gid, 0660, false, uid_min, gid_min)
				)
			)
		) {
			tomcatCommon.printTomcatUsers(out);
		}

		/*
		 * Create the web.dtd file.
		 */
		tomcatCommon.createWebDtd(siteDir+"/conf", uid, gid, 0660, uid_min, gid_min);

		/*
		 * Create the web.xml file.
		 */
		tomcatCommon.createWebXml(siteDir+"/conf", uid, gid, 0660, uid_min, gid_min);

		/*
		 * Create the empty log files.
		 */
		for(String logFile : TomcatCommon_3_X.tomcatLogFiles) {
			String filename=siteDir+"/var/log/"+logFile;
			new PosixFile(filename).getSecureOutputStream(uid, gid, 0660, false, uid_min, gid_min).close();
		}

		/*
		 * Create the manifest file.
		 */
		String manifestFile=siteDir+"/webapps/"+Context.ROOT_DOC_BASE+"/META-INF/MANIFEST.MF";
		try (
			ChainWriter out = new ChainWriter(
				new PosixFile(manifestFile).getSecureOutputStream(
					uid,
					gid,
					0664,
					false,
					uid_min,
					gid_min
				)
			)
		) {
			out.print("Manifest-Version: 1.0");
		}

		/*
		 * Write the cocoon.properties file.
		 */
		String cocoonProps=siteDir+"/webapps/"+Context.ROOT_DOC_BASE+"/WEB-INF/conf/cocoon.properties";
		try (OutputStream fileOut = new BufferedOutputStream(new PosixFile(cocoonProps).getSecureOutputStream(uid, gid, 0660, false, uid_min, gid_min))) {
			tomcatCommon.copyCocoonProperties1(fileOut);
			try (ChainWriter out = new ChainWriter(fileOut)) {
				out.print("processor.xsp.repository = ").print(siteDir).print("/webapps/"+Context.ROOT_DOC_BASE+"/WEB-INF/cocoon\n");
				out.flush();
				tomcatCommon.copyCocoonProperties2(fileOut);
			}
		}

		/*
		 * Write the ROOT/WEB-INF/web.xml file.
		 */
		String webXML=siteDir+"/webapps/"+Context.ROOT_DOC_BASE+"/WEB-INF/web.xml";
		try (
			ChainWriter out = new ChainWriter(
				new BufferedOutputStream(
					new PosixFile(webXML).getSecureOutputStream(uid, gid, 0664, false, uid_min, gid_min)
				)
			)
		) {
			out.print("<?xml version=\"1.0\" encoding=\"ISO-8859-1\"?>\n"
					  + "\n"
					  + "<!DOCTYPE web-app\n"
					  + "    PUBLIC \"-//Sun Microsystems, Inc.//DTD Web Application 2.2//EN\"\n"
					  + "    \"http://java.sun.com/j2ee/dtds/web-app_2.2.dtd\">\n"
					  + "\n"
					  + "<web-app>\n"
					  + "\n"
					  + " <servlet>\n"
					  + "  <servlet-name>org.apache.cocoon.Cocoon</servlet-name>\n"
					  + "  <servlet-class>org.apache.cocoon.Cocoon</servlet-class>\n"
					  + "  <init-param>\n"
					  + "   <param-name>properties</param-name>\n"
					  + "   <param-value>\n"
					  + "    WEB-INF/conf/cocoon.properties\n"
					  + "   </param-value>\n"
					  + "  </init-param>\n"
					  + " </servlet>\n"
					  + "\n"
					  + " <servlet-mapping>\n"
					  + "  <servlet-name>org.apache.cocoon.Cocoon</servlet-name>\n"
					  + "  <url-pattern>*.xml</url-pattern>\n"
					  + " </servlet-mapping>\n"
					  + "\n"
					  + "</web-app>\n");
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
		PosixFile conf = new PosixFile(siteDir + "/conf");
		if(
			!httpdSite.isManual()
			// conf directory may not exist while in manual mode
			|| conf.getStat().exists()
		) {
			// Rebuild the server.xml
			String autoWarning = getAutoWarningXml();
			String autoWarningOld = getAutoWarningXmlOld();

			Server thisServer = AOServDaemon.getThisServer();
			int uid_min = thisServer.getUidMin().getId();
			int gid_min = thisServer.getGidMin().getId();

			PosixFile confServerXML = new PosixFile(conf, "server.xml", false);
			if(!httpdSite.isManual() || !confServerXML.getStat().exists()) {
				// Only write to the actual file when missing or changed
				if(
					DaemonFileUtils.atomicWrite(
						confServerXML,
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
						confServerXML,
						autoWarningOld,
						uid_min,
						gid_min
					);
					DaemonFileUtils.stripFilePrefix(
						confServerXML,
						autoWarning,
						uid_min,
						gid_min
					);
				} catch(IOException err) {
					// Errors OK because this is done in manual mode and they might have symbolic linked stuff
				}
			}
		}
		return needsRestart;
	}

	@Override
	protected boolean upgradeSiteDirectoryContents(String optSlash, PosixFile siteDirectory) throws IOException, SQLException {
		// Nothing to do
		return false;
	}
}
