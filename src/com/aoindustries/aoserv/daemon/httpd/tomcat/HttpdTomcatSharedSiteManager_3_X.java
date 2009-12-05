package com.aoindustries.aoserv.daemon.httpd.tomcat;

/*
 * Copyright 2007-2009 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
import com.aoindustries.aoserv.client.AOServConnector;
import com.aoindustries.aoserv.client.HttpdJKProtocol;
import com.aoindustries.aoserv.client.HttpdTomcatContext;
import com.aoindustries.aoserv.client.HttpdTomcatSharedSite;
import com.aoindustries.aoserv.client.HttpdWorker;
import com.aoindustries.aoserv.daemon.AOServDaemon;
import com.aoindustries.aoserv.daemon.util.FileUtils;
import com.aoindustries.io.ChainWriter;
import com.aoindustries.io.unix.Stat;
import com.aoindustries.io.unix.UnixFile;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.sql.SQLException;
import java.util.List;

/**
 * Manages HttpdTomcatSharedSite version 3.X configurations.
 *
 * @author  AO Industries, Inc.
 */
abstract class HttpdTomcatSharedSiteManager_3_X<TC extends TomcatCommon_3_X> extends HttpdTomcatSharedSiteManager<TC> {

    HttpdTomcatSharedSiteManager_3_X(HttpdTomcatSharedSite tomcatSharedSite) throws SQLException, IOException {
        super(tomcatSharedSite);
    }

    /**
     * Shared Tomcat 3.X sites have worker directly attached like standard sites,
     * they also only support ajp12.
     */
    @Override
    protected HttpdWorker getHttpdWorker() throws IOException, SQLException {
        AOServConnector conn = AOServDaemon.getConnector();
        List<HttpdWorker> workers = tomcatSite.getHttpdWorkers();

        // Only ajp12 supported
        for(HttpdWorker hw : workers) {
            if(hw.getHttpdJKProtocol(conn).getProtocol(conn).getProtocol().equals(HttpdJKProtocol.AJP12)) return hw;
        }

        throw new SQLException("Couldn't find ajp12 for httpd_tomcat_shared_site="+tomcatSharedSite);
    }

    /**
     * Builds a shared site for Tomcat 3.X
     */
    protected void buildSiteDirectoryContents(UnixFile siteDirectory) throws IOException, SQLException {
        // Resolve and allocate stuff used throughout the method
        final TomcatCommon_3_X tomcatCommon = getTomcatCommon();
        final String siteDir = siteDirectory.getPath();
        final int uid = httpdSite.getLinuxServerAccount().getUID().getID();
        final int gid = httpdSite.getLinuxServerGroup().getGID().getID();

        /*
         * Create the skeleton of the site, the directories and links.
         */
        FileUtils.mkdir(siteDir+"/bin", 0770, uid, gid);
        FileUtils.mkdir(siteDir+"/conf", 0775, uid, gid);
        FileUtils.mkdir(siteDir+"/daemon", 0770, uid, gid);
        FileUtils.ln("webapps/"+HttpdTomcatContext.ROOT_DOC_BASE, siteDir+"/htdocs", uid, gid);
        FileUtils.mkdir(siteDir+"/lib", 0770, uid, gid);
        FileUtils.ln("webapps/"+HttpdTomcatContext.ROOT_DOC_BASE+"/WEB-INF/classes", siteDir+"/servlet", uid, gid);
        FileUtils.mkdir(siteDir+"/var", 0770, uid, gid);
        FileUtils.mkdir(siteDir+"/var/log", 0770, uid, gid);
        FileUtils.mkdir(siteDir+"/webapps", 0775, uid, gid);
        FileUtils.mkdir(siteDir+"/webapps/"+HttpdTomcatContext.ROOT_DOC_BASE, 0775, uid, gid);
        FileUtils.mkdir(siteDir+"/webapps/"+HttpdTomcatContext.ROOT_DOC_BASE+"/META-INF", 0775, uid, gid);
        FileUtils.mkdir(siteDir+"/webapps/"+HttpdTomcatContext.ROOT_DOC_BASE+"/WEB-INF", 0775, uid, gid);
        FileUtils.mkdir(siteDir+"/webapps/"+HttpdTomcatContext.ROOT_DOC_BASE+"/WEB-INF/classes", 0770, uid, gid);
        FileUtils.mkdir(siteDir+"/webapps/"+HttpdTomcatContext.ROOT_DOC_BASE+"/WEB-INF/cocoon", 0770, uid, gid);
        FileUtils.mkdir(siteDir+"/webapps/"+HttpdTomcatContext.ROOT_DOC_BASE+"/WEB-INF/conf", 0770, uid, gid);
        FileUtils.mkdir(siteDir+"/webapps/"+HttpdTomcatContext.ROOT_DOC_BASE+"/WEB-INF/lib", 0770, uid, gid);

        /*
         * Write the manifest.servlet file.
         */
        String confManifestServlet=siteDir+"/conf/manifest.servlet";
        ChainWriter out=new ChainWriter(
            new BufferedOutputStream(
                new UnixFile(confManifestServlet).getSecureOutputStream(uid, gid, 0660, false)
            )
        );
        try {
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
        } finally {
            out.close();
        }

        /*
         * Create the conf/server.dtd file.
         */
        String confServerDTD=siteDir+"/conf/server.dtd";
        out=new ChainWriter(
            new BufferedOutputStream(
                new UnixFile(confServerDTD).getSecureOutputStream(uid, gid, 0660, false)
            )
        );
        try {
            out.print("<?xml version=\"1.0\" encoding=\"ISO-8859-1\"?>\n"
                      + "\n"
                      + "<!ELEMENT Server (ContextManager+)>\n"
                      + "<!ATTLIST Server\n"
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
        } finally {
            out.close();
        }

        // Create the test-tomcat.xml file.
        tomcatCommon.createTestTomcatXml(siteDir+"/conf", uid, gid, 0660);

        /*
         * Create the tomcat-users.xml file
         */
        String confTomcatUsers=siteDir+"/conf/tomcat-users.xml";
        out=new ChainWriter(
            new BufferedOutputStream(
                new UnixFile(confTomcatUsers).getSecureOutputStream(uid, gid, 0660, false)
            )
        );
        try {
            tomcatCommon.printTomcatUsers(out);
        } finally {
            out.close();
        }

        /*
         * Create the web.dtd file.
         */
        tomcatCommon.createWebDtd(siteDir+"/conf", uid, gid, 0660);

        /*
         * Create the web.xml file.
         */
        tomcatCommon.createWebXml(siteDir+"/conf", uid, gid, 0660);

        /*
         * Create the empty log files.
         */
        for(String logFile : TomcatCommon_3_X.tomcatLogFiles) {
            String filename=siteDir+"/var/log/"+logFile;
            new UnixFile(filename).getSecureOutputStream(uid, gid, 0660, false).close();
        }

        /*
         * Create the manifest file.
         */
        String manifestFile=siteDir+"/webapps/"+HttpdTomcatContext.ROOT_DOC_BASE+"/META-INF/MANIFEST.MF";
        new ChainWriter(
            new UnixFile(manifestFile).getSecureOutputStream(
                uid,
                gid,
                0664,
                false
            )
        ).print("Manifest-Version: 1.0").close();

        /*
         * Write the cocoon.properties file.
         */
        String cocoonProps=siteDir+"/webapps/"+HttpdTomcatContext.ROOT_DOC_BASE+"/WEB-INF/conf/cocoon.properties";
        OutputStream fileOut=new BufferedOutputStream(new UnixFile(cocoonProps).getSecureOutputStream(uid, gid, 0660, false));
        try {
            tomcatCommon.copyCocoonProperties1(fileOut);
            out=new ChainWriter(fileOut);
            try {
                out.print("processor.xsp.repository = ").print(siteDir).print("/webapps/"+HttpdTomcatContext.ROOT_DOC_BASE+"/WEB-INF/cocoon\n");
                out.flush();
                tomcatCommon.copyCocoonProperties2(fileOut);
            } finally {
                out.flush();
            }
        } finally {
            fileOut.close();
        }

        /*
         * Write the ROOT/WEB-INF/web.xml file.
         */
        String webXML=siteDir+"/webapps/"+HttpdTomcatContext.ROOT_DOC_BASE+"/WEB-INF/web.xml";
        out=new ChainWriter(
            new BufferedOutputStream(
                new UnixFile(webXML).getSecureOutputStream(uid, gid, 0664, false)
            )
        );
        try {
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
        } finally {
            out.close();
        }
    }

    /**
     * Builds the server.xml file.
     */
    protected abstract byte[] buildServerXml(UnixFile siteDirectory, String autoWarning) throws IOException, SQLException;

    protected boolean rebuildConfigFiles(UnixFile siteDirectory) throws IOException, SQLException {
        final String siteDir = siteDirectory.getPath();
        final Stat tempStat = new Stat();
        boolean needsRestart = false;
        String autoWarning = getAutoWarningXml();
        String autoWarningOld = getAutoWarningXmlOld();

        String confServerXML=siteDir+"/conf/server.xml";
        UnixFile confServerXMLFile=new UnixFile(confServerXML);
        if(!httpdSite.isManual() || !confServerXMLFile.getStat(tempStat).exists()) {
            // Only write to the actual file when missing or changed
            if(
                FileUtils.writeIfNeeded(
                    buildServerXml(siteDirectory, autoWarning),
                    null,
                    confServerXMLFile,
                    httpdSite.getLinuxServerAccount().getUID().getID(),
                    httpdSite.getLinuxServerGroup().getGID().getID(),
                    0660
                )
            ) {
                // Flag as needing restarted
                needsRestart = true;
            }
        } else {
            try {
                FileUtils.stripFilePrefix(
                    confServerXMLFile,
                    autoWarningOld,
                    tempStat
                );
                FileUtils.stripFilePrefix(
                    confServerXMLFile,
                    autoWarning,
                    tempStat
                );
            } catch(IOException err) {
                // Errors OK because this is done in manual mode and they might have symbolic linked stuff
            }
        }
        return needsRestart;
    }

    protected boolean upgradeSiteDirectoryContents(UnixFile siteDirectory) throws IOException, SQLException {
        // TODO
        return false;
    }
}
