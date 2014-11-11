/*
 * Copyright 2007-2013, 2014 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
package com.aoindustries.aoserv.daemon.httpd.tomcat;

import com.aoindustries.aoserv.client.HttpdTomcatContext;
import com.aoindustries.aoserv.client.HttpdTomcatSharedSite;
import com.aoindustries.aoserv.daemon.util.DaemonFileUtils;
import com.aoindustries.io.ChainWriter;
import com.aoindustries.io.unix.UnixFile;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.sql.SQLException;

/**
 * Manages HttpdTomcatSharedSite version 5.5.X configurations.
 *
 * @author  AO Industries, Inc.
 */
class HttpdTomcatSharedSiteManager_5_5_X extends HttpdTomcatSharedSiteManager<TomcatCommon_5_5_X> {

    HttpdTomcatSharedSiteManager_5_5_X(HttpdTomcatSharedSite tomcatSharedSite) throws SQLException, IOException {
        super(tomcatSharedSite);
    }

    /**
     * Builds a shared site for Tomcat 5.5.X
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
        DaemonFileUtils.ln("var/log", siteDir+"/logs", uid, gid);
        DaemonFileUtils.mkdir(siteDir+"/var", 0770, uid, gid);
        DaemonFileUtils.mkdir(siteDir+"/var/log", 0770, uid, gid);
        DaemonFileUtils.mkdir(siteDir+"/webapps", 0775, uid, gid);
        DaemonFileUtils.mkdir(siteDir+"/webapps/"+HttpdTomcatContext.ROOT_DOC_BASE, 0775, uid, gid);
        DaemonFileUtils.mkdir(siteDir+"/webapps/"+HttpdTomcatContext.ROOT_DOC_BASE+"/WEB-INF", 0775, uid, gid);
        DaemonFileUtils.mkdir(siteDir+"/webapps/"+HttpdTomcatContext.ROOT_DOC_BASE+"/WEB-INF/classes", 0770, uid, gid);
        DaemonFileUtils.mkdir(siteDir+"/webapps/"+HttpdTomcatContext.ROOT_DOC_BASE+"/WEB-INF/lib", 0770, uid, gid);

        /*
         * Write the ROOT/WEB-INF/web.xml file.
         */
        String webXML=siteDir+"/webapps/"+HttpdTomcatContext.ROOT_DOC_BASE+"/WEB-INF/web.xml";
        ChainWriter out=new ChainWriter(
            new BufferedOutputStream(
                new UnixFile(webXML).getSecureOutputStream(uid, gid, 0664, false)
            )
        );
        try {
            out.print("<?xml version=\"1.0\" encoding=\"ISO-8859-1\"?>\n"
                    + "<web-app xmlns=\"http://java.sun.com/xml/ns/j2ee\"\n"
                    + "    xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"\n"
                    + "    xsi:schemaLocation=\"http://java.sun.com/xml/ns/j2ee http://java.sun.com/xml/ns/j2ee/web-app_2_4.xsd\"\n"
                    + "    version=\"2.4\">\n"
                    + "  <display-name>Welcome to Tomcat</display-name>\n"
                    + "  <description>\n"
                    + "     Welcome to Tomcat\n"
                    + "  </description>\n"
                    + "</web-app>\n");
        } finally {
            out.close();
        }
    }

	@Override
    public TomcatCommon_5_5_X getTomcatCommon() {
        return TomcatCommon_5_5_X.getInstance();
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
