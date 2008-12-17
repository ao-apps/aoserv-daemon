package com.aoindustries.aoserv.daemon.httpd.tomcat;

/*
 * Copyright 2007-2008 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
import com.aoindustries.aoserv.client.HttpdSharedTomcat;
import com.aoindustries.aoserv.client.HttpdSite;
import com.aoindustries.aoserv.client.HttpdTomcatSharedSite;
import com.aoindustries.aoserv.client.LinuxServerAccount;
import com.aoindustries.aoserv.client.LinuxServerGroup;
import com.aoindustries.io.unix.UnixFile;
import java.io.IOException;
import java.sql.SQLException;
import java.util.Set;

/**
 * Manages HttpdTomcatSharedSite version 4.1.X configurations.
 *
 * @author  AO Industries, Inc.
 */
class HttpdTomcatSharedSiteManager_4_1_X extends HttpdTomcatSharedSiteManager {

    HttpdTomcatSharedSiteManager_4_1_X(HttpdTomcatSharedSite tomcatSharedSite) {
        super(tomcatSharedSite);
    }

    /**
     * Builds a shared site for Tomcat 4.1.X
     */
    protected void buildSiteDirectory(UnixFile siteDirectory, Set<HttpdSite> sitesNeedingRestarted, Set<HttpdSharedTomcat> sharedTomcatsNeedingRestarted) throws IOException, SQLException {
        /*
         * We no longer support secure JVMs.
         */
        final HttpdSharedTomcat sharedTomcat = tomcatSharedSite.getHttpdSharedTomcat();
        if(sharedTomcat.isSecure()) throw new SQLException("We no longer support secure Multi-Site Tomcat installations: "+sharedTomcat);

        /*
         * Resolve and allocate stuff used throughout the method
         */
        final LinuxServerAccount lsa = httpdSite.getLinuxServerAccount();
        final LinuxServerGroup lsg = httpdSite.getLinuxServerGroup();
        final int uid = lsa.getUID().getID();
        final int gid = lsg.getGID().getID();

        /*
         * Create the skeleton of the site, the directories and links.
         */
        mkdir(siteDir+"/bin", 0770, lsa, lsg);
        mkdir(siteDir+"/conf", 0775, lsa, lsg);
        mkdir(siteDir+"/daemon", 0770, lsa, lsg);
        ln("var/log", siteDir+"/logs", uid, gid);
        mkdir(siteDir+"/var", 0770, lsa, lsg);
        mkdir(siteDir+"/var/log", 0770, lsa, lsg);
        mkdir(siteDir+"/webapps", 0775, lsa, lsg);
        mkdir(siteDir+"/webapps/"+HttpdTomcatContext.ROOT_DOC_BASE, 0775, lsa, lsg);
        mkdir(siteDir+"/webapps/"+HttpdTomcatContext.ROOT_DOC_BASE+"/WEB-INF", 0775, lsa, lsg);
        mkdir(siteDir+"/webapps/"+HttpdTomcatContext.ROOT_DOC_BASE+"/WEB-INF/classes", 0770, lsa, lsg);
        mkdir(siteDir+"/webapps/"+HttpdTomcatContext.ROOT_DOC_BASE+"/WEB-INF/lib", 0770, lsa, lsg);	
        mkdir(siteDir+"/webapps/"+HttpdTomcatContext.ROOT_DOC_BASE+"/cgi-bin", 0755, lsa, lsg);

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
                        + "\n"
                        + "<!DOCTYPE web-app\n"
                        + "    PUBLIC \"-//Sun Microsystems, Inc.//DTD Web Application 2.3//EN\"\n"
                        + "    \"http://java.sun.com/j2ee/dtds/web-app_2_3.dtd\">\n"
                        + "\n"
                        + "<web-app>\n"
                        + "  <display-name>Welcome to Tomcat</display-name>\n"
                        + "  <description>\n"
                        + "    Welcome to Tomcat\n"
                        + "  </description>\n"
                        + "</web-app>\n");
        } finally {
            out.flush();
            out.close();
        }

        createCgiPhpScript(httpdSite);
        createTestCGI(httpdSite);
        createTestIndex(httpdSite);

        /*
         * Create the test.php file.
         */
        String testPHP=siteDir+"/webapps/"+HttpdTomcatContext.ROOT_DOC_BASE+"/test.php";
        new ChainWriter(
            new UnixFile(testPHP).getSecureOutputStream(uid, gid, 0664, false)
        ).print("<?phpinfo()?>\n").flush().close();
    }

    public TomcatCommon getTomcatCommon() {
        return TomcatCommon_4_1_X.getInstance();
    }
}
