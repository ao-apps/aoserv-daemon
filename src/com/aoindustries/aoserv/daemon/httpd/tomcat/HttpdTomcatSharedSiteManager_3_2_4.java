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
 * Manages HttpdTomcatSharedSite version 3.2.4 configurations.
 *
 * @author  AO Industries, Inc.
 */
class HttpdTomcatSharedSiteManager_3_2_4 extends HttpdTomcatSharedSiteManager_3_X {

    HttpdTomcatSharedSiteManager_3_2_4(HttpdTomcatSharedSite tomcatSharedSite) {
        super(tomcatSharedSite);
    }

    /**
     * Builds a shared site for Tomcat 3.2.4
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
        ln("webapps/"+HttpdTomcatContext.ROOT_DOC_BASE+"/cgi-bin", siteDir+"/cgi-bin", uid, gid);
        mkdir(siteDir+"/conf", 0775, lsa, lsg);
        mkdir(siteDir+"/daemon", 0770, lsa, lsg);
        ln("webapps/"+HttpdTomcatContext.ROOT_DOC_BASE, siteDir+"/htdocs", uid, gid);
        mkdir(siteDir+"/lib", 0770, lsa, lsg);
        ln("webapps/"+HttpdTomcatContext.ROOT_DOC_BASE+"/WEB-INF/classes", siteDir+"/servlet", uid, gid);
        mkdir(siteDir+"/var", 0770, lsa, lsg);
        mkdir(siteDir+"/var/log", 0770, lsa, lsg);
        mkdir(siteDir+"/webapps", 0775, lsa, lsg);
        mkdir(siteDir+"/webapps/"+HttpdTomcatContext.ROOT_DOC_BASE, 0775, lsa, lsg);
        mkdir(siteDir+"/webapps/"+HttpdTomcatContext.ROOT_DOC_BASE+"/META-INF", 0775, lsa, lsg);
        mkdir(siteDir+"/webapps/"+HttpdTomcatContext.ROOT_DOC_BASE+"/WEB-INF", 0775, lsa, lsg);
        mkdir(siteDir+"/webapps/"+HttpdTomcatContext.ROOT_DOC_BASE+"/WEB-INF/classes", 0770, lsa, lsg);
        mkdir(siteDir+"/webapps/"+HttpdTomcatContext.ROOT_DOC_BASE+"/WEB-INF/cocoon", 0770, lsa, lsg);
        mkdir(siteDir+"/webapps/"+HttpdTomcatContext.ROOT_DOC_BASE+"/WEB-INF/conf", 0770, lsa, lsg);
        mkdir(siteDir+"/webapps/"+HttpdTomcatContext.ROOT_DOC_BASE+"/WEB-INF/lib", 0770, lsa, lsg);	
        mkdir(siteDir+"/webapps/"+HttpdTomcatContext.ROOT_DOC_BASE+"/cgi-bin", 0755, lsa, lsg);

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
            out.flush();
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
            out.flush();
            out.close();
        }

        /*
         * Create the test-tomcat.xml file.
         */
        copyResource("test-tomcat.xml", siteDir+"/conf/test-tomcat.xml", uid, gid, 0660);

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
            out.print("<tomcat-users>\n"
                      + "  <user name=\"tomcat\" password=\"tomcat\" roles=\"tomcat\" />\n"
                      + "  <user name=\"role1\"  password=\"tomcat\" roles=\"role1\" />\n"
                      + "  <user name=\"both\"   password=\"tomcat\" roles=\"tomcat,role1\" />\n"
                      + "</tomcat-users>\n"
                      );
        } finally {
            out.flush();
            out.close();
        }

        /*
         * Create the web.dtd file.
         */
        copyResource("web.dtd-3.2.4", siteDir+"/conf/web.dtd", uid, gid, 0660);

        /*
         * Create the web.xml file.
         */
        copyResource("web.xml-3.2.4", siteDir+"/conf/web.xml", uid, gid, 0660);

        /*
         * Create the empty log files.
         */
        for(int c=0;c<tomcatLogFiles.length;c++) {
            String filename=siteDir+"/var/log/"+tomcatLogFiles[c];
            new UnixFile(filename).getSecureOutputStream(
                uid,
                gid,
                0660,
                false
            ).close();
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
        ).print("Manifest-Version: 1.0").flush().close();

        /*
         * Write the cocoon.properties file.
         */
        String cocoonProps=siteDir+"/webapps/"+HttpdTomcatContext.ROOT_DOC_BASE+"/WEB-INF/conf/cocoon.properties";
        OutputStream fileOut=new BufferedOutputStream(new UnixFile(cocoonProps).getSecureOutputStream(uid, gid, 0660, false));
        try {
            copyResource("cocoon.properties.1", fileOut);
            out=new ChainWriter(fileOut);
            try {
                out.print("processor.xsp.repository = ").print(siteDir).print("/webapps/"+HttpdTomcatContext.ROOT_DOC_BASE+"/WEB-INF/cocoon\n");
                out.flush();
                copyResource("cocoon.properties.2", fileOut);
            } finally {
                out.flush();
            }
        } finally {
            fileOut.flush();
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
        return TomcatCommon_3_2_4.getInstance();
    }
}
