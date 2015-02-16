/*
 * Copyright 2007-2013, 2014, 2015 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
package com.aoindustries.aoserv.daemon.httpd.tomcat;

import com.aoindustries.aoserv.client.HttpdTomcatContext;
import com.aoindustries.aoserv.client.HttpdTomcatSharedSite;
import com.aoindustries.aoserv.daemon.util.DaemonFileUtils;
import com.aoindustries.encoding.ChainWriter;
import com.aoindustries.io.unix.UnixFile;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.sql.SQLException;

/**
 * Manages HttpdTomcatSharedSite version 6.0.X configurations.
 *
 * @author  AO Industries, Inc.
 */
class HttpdTomcatSharedSiteManager_6_0_X extends HttpdTomcatSharedSiteManager<TomcatCommon_6_0_X> {

    HttpdTomcatSharedSiteManager_6_0_X(HttpdTomcatSharedSite tomcatSharedSite) throws SQLException, IOException {
        super(tomcatSharedSite);
    }

    /**
     * Builds a shared site for Tomcat 6.0.X
     */
	@Override
    protected void buildSiteDirectoryContents(UnixFile siteDirectory) throws IOException, SQLException {
        /*
         * Resolve and allocate stuff used throughout the method
         */
        final String siteDir = siteDirectory.getPath();
        final int uid = httpdSite.getLinuxServerAccount().getUid().getID();
        final int gid = httpdSite.getLinuxServerGroup().getGid().getID();

        /*
         * Create the skeleton of the site, the directories and links.
         */
        DaemonFileUtils.mkdir(siteDir+"/bin", 0770, uid, gid);
        DaemonFileUtils.mkdir(siteDir+"/conf", 0775, uid, gid);
        DaemonFileUtils.mkdir(siteDir+"/daemon", 0770, uid, gid);
        DaemonFileUtils.mkdir(siteDir+"/webapps", 0775, uid, gid);
        DaemonFileUtils.mkdir(siteDir+"/webapps/"+HttpdTomcatContext.ROOT_DOC_BASE, 0775, uid, gid);
        DaemonFileUtils.mkdir(siteDir+"/webapps/"+HttpdTomcatContext.ROOT_DOC_BASE+"/WEB-INF", 0775, uid, gid);
        DaemonFileUtils.mkdir(siteDir+"/webapps/"+HttpdTomcatContext.ROOT_DOC_BASE+"/WEB-INF/classes", 0770, uid, gid);
        DaemonFileUtils.mkdir(siteDir+"/webapps/"+HttpdTomcatContext.ROOT_DOC_BASE+"/WEB-INF/lib", 0770, uid, gid);

        /*
         * Write the ROOT/WEB-INF/web.xml file.
         */
        String webXML=siteDir+"/webapps/"+HttpdTomcatContext.ROOT_DOC_BASE+"/WEB-INF/web.xml";
        try (
			ChainWriter out = new ChainWriter(
				new BufferedOutputStream(
					new UnixFile(webXML).getSecureOutputStream(uid, gid, 0664, false)
				)
			)
		) {
            out.print("<?xml version=\"1.0\" encoding=\"ISO-8859-1\"?>\n"
                    + "<web-app xmlns=\"http://java.sun.com/xml/ns/javaee\"\n"
                    + "   xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"\n"
                    + "   xsi:schemaLocation=\"http://java.sun.com/xml/ns/javaee http://java.sun.com/xml/ns/javaee/web-app_2_5.xsd\"\n"
                    + "   version=\"2.5\">\n"
                    + "  <display-name>Welcome to Tomcat</display-name>\n"
                    + "  <description>\n"
                    + "     Welcome to Tomcat\n"
                    + "  </description>\n"
                    + "</web-app>\n");
        }
    }

	@Override
    public TomcatCommon_6_0_X getTomcatCommon() {
        return TomcatCommon_6_0_X.getInstance();
    }

	@Override
    protected boolean rebuildConfigFiles(UnixFile siteDirectory) {
        // No configs to rebuild
        return false;
    }

	@Override
    protected boolean upgradeSiteDirectoryContents(UnixFile siteDirectory) throws IOException, SQLException {
        // Nothing to do
        return false;
    }
}
