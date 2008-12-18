package com.aoindustries.aoserv.daemon.httpd.tomcat;

/*
 * Copyright 2007-2008 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
import com.aoindustries.aoserv.client.AOServConnector;
import com.aoindustries.aoserv.client.AOServer;
import com.aoindustries.aoserv.client.HttpdJKProtocol;
import com.aoindustries.aoserv.client.HttpdTomcatContext;
import com.aoindustries.aoserv.client.HttpdTomcatStdSite;
import com.aoindustries.aoserv.client.HttpdWorker;
import com.aoindustries.aoserv.client.LinuxServerAccount;
import com.aoindustries.aoserv.client.PostgresServer;
import com.aoindustries.aoserv.daemon.AOServDaemon;
import com.aoindustries.aoserv.daemon.OperatingSystemConfiguration;
import com.aoindustries.aoserv.daemon.httpd.HttpdOperatingSystemConfiguration;
import com.aoindustries.aoserv.daemon.unix.linux.LinuxAccountManager;
import com.aoindustries.aoserv.daemon.util.FileUtils;
import com.aoindustries.io.ChainWriter;
import com.aoindustries.io.unix.UnixFile;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.sql.SQLException;
import java.util.List;

/**
 * Manages HttpdTomcatStdSite version 3.X configurations.
 *
 * @author  AO Industries, Inc.
 */
abstract class HttpdTomcatStdSiteManager_3_X<TC extends TomcatCommon_3_X> extends HttpdTomcatStdSiteManager<TC> {

    HttpdTomcatStdSiteManager_3_X(HttpdTomcatStdSite tomcatStdSite) {
        super(tomcatStdSite);
    }

    /**
     * Only supports ajp12
     */
    @Override
    protected HttpdWorker getHttpdWorker() throws IOException, SQLException {
        AOServConnector conn = AOServDaemon.getConnector();
        List<HttpdWorker> workers = tomcatSite.getHttpdWorkers();

        // Try ajp12 next
        for(HttpdWorker hw : workers) {
            if(hw.getHttpdJKProtocol(conn).getProtocol(conn).getProtocol().equals(HttpdJKProtocol.AJP12)) return hw;
        }
        throw new SQLException("Couldn't ajp12");
    }

    /**
     * Builds a standard install for Tomcat 3.X
     */
    protected void buildSiteDirectoryContents(UnixFile siteDirectory) throws IOException, SQLException {
        // Resolve and allocate stuff used throughout the method
        final TomcatCommon_3_X tomcatCommon = getTomcatCommon();
        final OperatingSystemConfiguration osConfig = OperatingSystemConfiguration.getOperatingSystemConfiguration();
        final HttpdOperatingSystemConfiguration httpdConfig = osConfig.getHttpdOperatingSystemConfiguration();
        final String siteDir = siteDirectory.getPath();
        final LinuxServerAccount lsa = httpdSite.getLinuxServerAccount();
        final int uid = lsa.getUID().getID();
        final int gid = httpdSite.getLinuxServerGroup().getGID().getID();
        final AOServer thisAOServer = AOServDaemon.getThisAOServer();
        final String hostname = thisAOServer.getHostname();
        final PostgresServer postgresServer=thisAOServer.getPreferredPostgresServer();
        final String postgresServerMinorVersion=postgresServer==null?null:postgresServer.getPostgresVersion().getMinorVersion();

        /*
         * Create the skeleton of the site, the directories and links.
         */
        FileUtils.mkdir(siteDir+"/bin", 0770, uid, gid);
        FileUtils.mkdir(siteDir+"/conf", 0775, uid, gid);
        FileUtils.mkdir(siteDir+"/daemon", 0770, uid, gid);
        if (httpdSite.getDisableLog()==null) FileUtils.ln("../bin/tomcat", siteDir+"/daemon/tomcat", uid, gid);
        FileUtils.ln("webapps/"+HttpdTomcatContext.ROOT_DOC_BASE, siteDir+"/htdocs", uid, gid);
        FileUtils.mkdir(siteDir+"/lib", 0770, uid, gid);
        FileUtils.ln("webapps/"+HttpdTomcatContext.ROOT_DOC_BASE+"/WEB-INF/classes", siteDir+"/servlet", uid, gid);
        FileUtils.mkdir(siteDir+"/var", 0770, uid, gid);
        FileUtils.mkdir(siteDir+"/var/log", 0770, uid, gid);
        FileUtils.mkdir(siteDir+"/var/run", 0770, uid, gid);
        FileUtils.mkdir(siteDir+"/webapps", 0775, uid, gid);
        final String rootDir = siteDir+"/webapps/"+HttpdTomcatContext.ROOT_DOC_BASE;
        FileUtils.mkdir(rootDir, 0775, uid, gid);
        FileUtils.mkdir(siteDir+"/webapps/"+HttpdTomcatContext.ROOT_DOC_BASE+"/META-INF", 0775, uid, gid);
        FileUtils.mkdir(siteDir+"/webapps/"+HttpdTomcatContext.ROOT_DOC_BASE+"/WEB-INF", 0775, uid, gid);
        FileUtils.mkdir(siteDir+"/webapps/"+HttpdTomcatContext.ROOT_DOC_BASE+"/WEB-INF/classes", 0770, uid, gid);
        FileUtils.mkdir(siteDir+"/webapps/"+HttpdTomcatContext.ROOT_DOC_BASE+"/WEB-INF/cocoon", 0770, uid, gid);
        FileUtils.mkdir(siteDir+"/webapps/"+HttpdTomcatContext.ROOT_DOC_BASE+"/WEB-INF/conf", 0770, uid, gid);
        FileUtils.mkdir(siteDir+"/webapps/"+HttpdTomcatContext.ROOT_DOC_BASE+"/WEB-INF/lib", 0770, uid, gid);	
        FileUtils.mkdir(siteDir+"/work", 0750, uid, gid);
        final String profileFile=siteDir+"/bin/profile";
        LinuxAccountManager.setBashProfile(lsa, profileFile);
        ChainWriter out=new ChainWriter(
            new BufferedOutputStream(
                new UnixFile(profileFile).getSecureOutputStream(uid, gid, 0750, false)
            )
        );
        try {
            out.print("#!/bin/sh\n"
                    + "\n"
                    + ". /etc/profile\n"
                    + ". /usr/aoserv/etc/jdk").print(osConfig.getDefaultJdkVersion()).print(".sh\n"
                    + ". /usr/aoserv/etc/jakarta-oro-2.0.sh\n"
                    + ". /usr/aoserv/etc/jakarta-regexp-1.1.sh\n"
                    + ". /usr/aoserv/etc/jakarta-servletapi-").print(tomcatCommon.getServletApiVersion()).print(".sh\n"
                    + ". /usr/aoserv/etc/jakarta-tomcat-").print(tomcatCommon.getTomcatApiVersion()).print(".sh\n"
                    + ". /usr/aoserv/etc/jetspeed-1.1.sh\n"
                    + ". /usr/aoserv/etc/cocoon-1.8.2.sh\n"
                    + ". /usr/aoserv/etc/xerces-1.2.0.sh\n"
                    + ". /usr/aoserv/etc/ant-1.6.2.sh\n"
                    + ". /usr/aoserv/etc/xalan-1.2.d02.sh\n");
            if(enablePhp()) out.print(". /usr/aoserv/etc/php-").print(httpdConfig.getDefaultPhpVersion()).print(".sh\n");
            if(postgresServerMinorVersion!=null) out.print(". /usr/aoserv/etc/postgresql-").print(postgresServerMinorVersion).print(".sh\n");
            out.print(". /usr/aoserv/etc/profile\n"
                    + "export \"CLASSPATH=/usr/aoserv/lib-1.3/aocode-public.jar:$CLASSPATH\"\n"
                    + ". /usr/aoserv/etc/fop-0.15.0.sh\n"
                    + "\n"
                    + "export PATH=${PATH}:").print(siteDir).print("/bin\n"
                    + "\n"
                    + "export JAVA_OPTS='-server -Djava.awt.headless=true'\n"
                    + "\n"
                    + "CLASSPATH=${CLASSPATH}:").print(siteDir).print("/classes\n"
                    + "\n"
                    + "for i in ").print(siteDir).print("/lib/* ; do\n"
                    + "    if [ -f $i ]; then\n"
                    + "        CLASSPATH=${CLASSPATH}:$i\n"
                    + "    fi\n"
                    + "done\n"
                    + "\n"
                    + "export CLASSPATH\n");
        } finally {
            out.close();
        }

        /*
         * Write the bin/tomcat script.
         */
        String tomcatScript=siteDir+"/bin/tomcat";
        out=new ChainWriter(
            new BufferedOutputStream(
                new UnixFile(tomcatScript).getSecureOutputStream(
                    uid,
                    gid,
                    0700,
                    false
                )
            )
        );
        try {
            out.print("#!/bin/sh\n"
                    + "\n"
                    + "TOMCAT_HOME=").print(siteDir).print("\n"
                    + "\n"
                    + "if [ \"$1\" = \"start\" ]; then\n"
                    + "    $0 stop\n"
                    + "    $0 daemon &\n"
                    + "    echo $! >${TOMCAT_HOME}/var/run/tomcat.pid\n"
                    + "elif [ \"$1\" = \"stop\" ]; then\n"
                    + "    if [ -f ${TOMCAT_HOME}/var/run/tomcat.pid ]; then\n"
                    + "        kill `cat ${TOMCAT_HOME}/var/run/tomcat.pid`\n"
                    + "        rm -f ${TOMCAT_HOME}/var/run/tomcat.pid\n"
                    + "    fi\n"
                    + "    if [ -f ${TOMCAT_HOME}/var/run/java.pid ]; then\n"
                    + "        cd $TOMCAT_HOME\n"
                    + "        . $TOMCAT_HOME/bin/profile\n"
                    + "        umask 002\n"
                    + "        export DISPLAY=:0.0\n"
                    //+ "        ulimit -S -m 196608 -v 400000\n"
                    //+ "        ulimit -H -m 196608 -v 400000\n"
                    + "        java -Dmail.smtp.host=").print(hostname).print(" org.apache.tomcat.startup.Tomcat -f ${TOMCAT_HOME}/conf/server.xml -stop &>/dev/null\n"
                    + "        kill `cat ${TOMCAT_HOME}/var/run/java.pid` &>/dev/null\n"
                    + "        rm -f ${TOMCAT_HOME}/var/run/java.pid\n"
                    + "    fi\n"
                    + "elif [ \"$1\" = \"daemon\" ]; then\n"
                    + "    cd $TOMCAT_HOME\n"
                    + "    . $TOMCAT_HOME/bin/profile\n"
                    + "\n"
                    + "    while [ 1 ]; do\n"
                    //+ "        ulimit -S -m 196608 -v 400000\n"
                    //+ "        ulimit -H -m 196608 -v 400000\n"
                    + "        umask 002\n"
                    + "        export DISPLAY=:0.0\n"
                    + "        java -Dmail.smtp.host=").print(hostname).print(" org.apache.tomcat.startup.Tomcat -f ${TOMCAT_HOME}/conf/server.xml &>var/log/servlet_err &\n"
                    + "        echo $! >${TOMCAT_HOME}/var/run/java.pid\n"
                    + "        wait\n"
                    + "        RETCODE=$?\n"
                    + "        echo \"`date`: JVM died with a return code of $RETCODE, restarting in 5 seconds\" >>${TOMCAT_HOME}/var/log/jvm_crashes.log\n"
                    + "        sleep 5\n"
                    + "    done\n"
                    + "else\n"
                    + "    echo \"Usage:\"\n"
                    + "    echo \"tomcat {start|stop}\"\n"
                    + "    echo \"        start - start tomcat\"\n"
                    + "    echo \"        stop  - stop tomcat\"\n"
                    + "fi\n");
        } finally {
            out.close();
        }
        FileUtils.mkdir(siteDir+"/classes", 0770, uid, gid);
        String confManifestServlet=siteDir+"/conf/manifest.servlet";
        out=new ChainWriter(
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
                    + "Implementation-Vendor: \"Sun Microsystems, Inc.\"\n");
        } finally {
            out.close();
        }
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
                    + "    value CDATA \"\">\n");
        } finally {
            out.close();
        }
        tomcatCommon.createTestTomcatXml(siteDir+"/conf", uid, gid, 0660);
        out=new ChainWriter(
            new BufferedOutputStream(
                new UnixFile(siteDir+"/conf/tomcat-users.xml").getSecureOutputStream(uid, gid, 0660, false)
            )
        );
        try {
            tomcatCommon.printTomcatUsers(out);
        } finally {
            out.close();
        }
        tomcatCommon.createWebDtd(siteDir+"/conf", uid, gid, 0660);
        tomcatCommon.createWebXml(siteDir+"/conf", uid, gid, 0660);
        for(int c=0;c<TomcatCommon_3_X.tomcatLogFiles.length;c++) {
            String filename=siteDir+"/var/log/"+TomcatCommon_3_X.tomcatLogFiles[c];
            new UnixFile(filename).getSecureOutputStream(uid, gid, 0660, false).close();
        }
        final String manifestFile=siteDir+"/webapps/"+HttpdTomcatContext.ROOT_DOC_BASE+"/META-INF/MANIFEST.MF";
        new ChainWriter(new UnixFile(manifestFile).getSecureOutputStream(uid, gid, 0664, false)).print("Manifest-Version: 1.0").close();

        /*
         * Write the cocoon.properties file.
         */
        OutputStream fileOut=new BufferedOutputStream(new UnixFile(siteDir+"/webapps/"+HttpdTomcatContext.ROOT_DOC_BASE+"/WEB-INF/conf/cocoon.properties").getSecureOutputStream(uid, gid, 0660, false));
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
        out=new ChainWriter(
            new BufferedOutputStream(
                new UnixFile(siteDir+"/webapps/"+HttpdTomcatContext.ROOT_DOC_BASE+"/WEB-INF/web.xml").getSecureOutputStream(uid, gid, 0664, false)
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

        // CGI
        UnixFile rootDirectory = new UnixFile(siteDir+"/webapps/"+HttpdTomcatContext.ROOT_DOC_BASE);
        if(enableCgi()) {
            UnixFile cgibinDirectory = new UnixFile(rootDirectory, "cgi-bin", false);
            FileUtils.mkdir(cgibinDirectory, 0755, uid, gid);
            FileUtils.ln("webapps/"+HttpdTomcatContext.ROOT_DOC_BASE+"/cgi-bin", siteDir+"/cgi-bin", uid, gid);
            createTestCGI(cgibinDirectory);
            createCgiPhpScript(cgibinDirectory);
        }
        
        // index.html
        UnixFile indexFile = new UnixFile(rootDirectory, "index.html", false);
        createTestIndex(indexFile);

        // PHP
        createTestPHP(rootDirectory);
    }

    protected boolean rebuildConfigFiles(UnixFile siteDirectory) throws IOException, SQLException {
        throw new RuntimeException("TODO: Implement method");
    }
}
