/*
 * Copyright 2013, 2014, 2015, 2016, 2017, 2018 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
package com.aoindustries.aoserv.daemon.httpd.tomcat;

import com.aoindustries.aoserv.client.linux.Server;
import com.aoindustries.aoserv.client.web.tomcat.Context;
import com.aoindustries.aoserv.client.web.tomcat.SharedTomcatSite;
import com.aoindustries.aoserv.daemon.AOServDaemon;
import com.aoindustries.aoserv.daemon.util.DaemonFileUtils;
import com.aoindustries.encoding.ChainWriter;
import com.aoindustries.io.unix.UnixFile;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.sql.SQLException;
import java.util.Set;

/**
 * Manages SharedTomcatSite version 7.0.X configurations.
 *
 * @author  AO Industries, Inc.
 */
class HttpdTomcatSharedSiteManager_7_0_X extends HttpdTomcatSharedSiteManager<TomcatCommon_7_0_X> {

	HttpdTomcatSharedSiteManager_7_0_X(SharedTomcatSite tomcatSharedSite) throws SQLException, IOException {
		super(tomcatSharedSite);
	}

	@Override
	protected void buildSiteDirectoryContents(String optSlash, UnixFile siteDirectory, boolean isUpgrade) throws IOException, SQLException {
		if(isUpgrade) throw new IllegalArgumentException("In-place upgrade not supported");
		/*
		 * Resolve and allocate stuff used throughout the method
		 */
		final String siteDir = siteDirectory.getPath();
		final int uid = httpdSite.getLinuxServerAccount().getUid().getId();
		final int gid = httpdSite.getLinuxServerGroup().getGid().getId();

		/*
		 * Create the skeleton of the site, the directories and links.
		 */
		DaemonFileUtils.mkdir(siteDir+"/bin", 0770, uid, gid);
		DaemonFileUtils.mkdir(siteDir+"/conf", 0775, uid, gid);
		DaemonFileUtils.mkdir(siteDir+"/daemon", 0770, uid, gid);
		DaemonFileUtils.mkdir(siteDir+"/webapps", 0775, uid, gid);
		DaemonFileUtils.mkdir(siteDir+"/webapps/"+Context.ROOT_DOC_BASE, 0775, uid, gid);
		DaemonFileUtils.mkdir(siteDir+"/webapps/"+Context.ROOT_DOC_BASE+"/WEB-INF", 0775, uid, gid);
		DaemonFileUtils.mkdir(siteDir+"/webapps/"+Context.ROOT_DOC_BASE+"/WEB-INF/classes", 0770, uid, gid);
		DaemonFileUtils.mkdir(siteDir+"/webapps/"+Context.ROOT_DOC_BASE+"/WEB-INF/lib", 0770, uid, gid);

		Server thisAoServer = AOServDaemon.getThisAOServer();
		int uid_min = thisAoServer.getUidMin().getId();
		int gid_min = thisAoServer.getGidMin().getId();

		/*
		 * Write the ROOT/WEB-INF/web.xml file.
		 */
		String webXML=siteDir+"/webapps/"+Context.ROOT_DOC_BASE+"/WEB-INF/web.xml";
		try (
			ChainWriter out = new ChainWriter(
				new BufferedOutputStream(
					new UnixFile(webXML).getSecureOutputStream(uid, gid, 0664, false, uid_min, gid_min)
				)
			)
		) {
			out.print("<?xml version=\"1.0\" encoding=\"ISO-8859-1\"?>\n"
					+ "<web-app xmlns=\"http://java.sun.com/xml/ns/javaee\"\n"
					+ "  xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"\n"
					+ "  xsi:schemaLocation=\"http://java.sun.com/xml/ns/javaee\n"
					+ "                      http://java.sun.com/xml/ns/javaee/web-app_3_0.xsd\"\n"
					+ "  version=\"3.0\"\n"
					+ "  metadata-complete=\"true\">\n"
					+ "\n"
					+ "  <display-name>Welcome to Tomcat</display-name>\n"
					+ "  <description>\n"
					+ "     Welcome to Tomcat\n"
					+ "  </description>\n"
					+ "\n"
					+ "</web-app>\n");
		}
	}

	@Override
	public TomcatCommon_7_0_X getTomcatCommon() {
		return TomcatCommon_7_0_X.getInstance();
	}

	@Override
	protected boolean rebuildConfigFiles(UnixFile siteDirectory, Set<UnixFile> restorecon) {
		// No configs to rebuild
		return false;
	}

	@Override
	protected boolean upgradeSiteDirectoryContents(String optSlash, UnixFile siteDirectory) throws IOException, SQLException {
		// Nothing to do
		return false;
	}
}
