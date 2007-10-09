package com.aoindustries.aoserv.daemon.httpd;

/*
 * Copyright 2000-2007 by AO Industries, Inc.,
 * 816 Azalea Rd, Mobile, Alabama, 36693, U.S.A.
 * All rights reserved.
 */
import com.aoindustries.aoserv.client.AOSHCommand;
import com.aoindustries.aoserv.client.AOServConnector;
import com.aoindustries.aoserv.client.AOServer;
import com.aoindustries.aoserv.client.HttpdBind;
import com.aoindustries.aoserv.client.HttpdJBossSite;
import com.aoindustries.aoserv.client.HttpdJKProtocol;
import com.aoindustries.aoserv.client.HttpdServer;
import com.aoindustries.aoserv.client.HttpdServerTable;
import com.aoindustries.aoserv.client.HttpdSharedTomcat;
import com.aoindustries.aoserv.client.HttpdSite;
import com.aoindustries.aoserv.client.HttpdSiteAuthenticatedLocation;
import com.aoindustries.aoserv.client.HttpdSiteBind;
import com.aoindustries.aoserv.client.HttpdSiteURL;
import com.aoindustries.aoserv.client.HttpdStaticSite;
import com.aoindustries.aoserv.client.HttpdTomcatContext;
import com.aoindustries.aoserv.client.HttpdTomcatDataSource;
import com.aoindustries.aoserv.client.HttpdTomcatParameter;
import com.aoindustries.aoserv.client.HttpdTomcatSharedSite;
import com.aoindustries.aoserv.client.HttpdTomcatSite;
import com.aoindustries.aoserv.client.HttpdTomcatStdSite;
import com.aoindustries.aoserv.client.HttpdTomcatVersion;
import com.aoindustries.aoserv.client.HttpdWorker;
import com.aoindustries.aoserv.client.IPAddress;
import com.aoindustries.aoserv.client.LinuxAccount;
import com.aoindustries.aoserv.client.LinuxGroup;
import com.aoindustries.aoserv.client.LinuxServerAccount;
import com.aoindustries.aoserv.client.LinuxServerGroup;
import com.aoindustries.aoserv.client.NetBind;
import com.aoindustries.aoserv.client.OperatingSystemVersion;
import com.aoindustries.aoserv.client.PostgresServer;
import com.aoindustries.aoserv.client.Server;
import com.aoindustries.aoserv.client.TechnologyVersion;
import com.aoindustries.aoserv.client.Username;
import com.aoindustries.aoserv.daemon.AOServDaemon;
import com.aoindustries.aoserv.daemon.AOServDaemonConfiguration;
import com.aoindustries.aoserv.daemon.backup.BackupManager;
import com.aoindustries.aoserv.daemon.ftp.FTPManager;
import com.aoindustries.aoserv.daemon.unix.linux.LinuxAccountManager;
import com.aoindustries.aoserv.daemon.util.BuilderThread;
import com.aoindustries.io.ChainWriter;
import com.aoindustries.io.unix.Stat;
import com.aoindustries.io.unix.UnixFile;
import com.aoindustries.profiler.Profiler;
import com.aoindustries.sql.SQLUtility;
import com.aoindustries.util.BufferManager;
import com.aoindustries.util.IntList;
import com.aoindustries.util.SortedArrayList;
import com.aoindustries.util.SortedIntArrayList;
import com.aoindustries.util.UnixCrypt;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The configuration for all AOServ processes is stored in a properties file.
 *
 * @author  AO Industries, Inc.
 */
final public class HttpdManager extends BuilderThread {

    /**
     * The default PHP version.
     */
    public static final String DEFAULT_MANDRIVA_2006_0_PHP_VERSION="4";
    public static final String DEFAULT_REDHAT_ES_4_PHP_VERSION="5";

    /**
     * The version of PostgreSQL minor version used by the default PHP version.
     */
    public static final String MANDRIVA_2006_0_PHP_POSTGRES_MINOR_VERSION="7.3";
    public static final String REDHAT_ES_4_PHP_POSTGRES_MINOR_VERSION="8.1";

    /**
     * The default JDK version.
     */
    public static final String DEFAULT_MANDRIVA_2006_0_JDK_VERSION="jdk1.5.0";
    public static final String DEFAULT_REDHAT_ES_4_JDK_VERSION="jdk1.5.0";
    public static final String DEFAULT_TOMCAT_5_JDK_VERSION="jdk1.5.0";

    /**
     * The default JDK paths.
     */
    public static final String DEFAULT_MANDRIVA_2006_0_JDK_PATH="/usr/jdk/1.5.0";
    public static final String DEFAULT_REDHAT_ES_4_JDK_PATH="/opt/jdk1.5.0";
    public static final String DEFAULT_TOMCAT_5_JDK_PATH="/usr/jdk/1.5.0";

    /**
     * The directory that HTTPD configs are located in.
     */
    public static final String CONFIG_DIRECTORY="/etc/httpd";

    /**
     * The directory that individual host and bind configurations are in.
     */
    public static final String CONF_HOSTS=CONFIG_DIRECTORY+"/conf/hosts";

    /**
     * The directory containing wwwgroup config files
     */
    public static final String CONF_WWWGROUPS=CONFIG_DIRECTORY+"/conf/wwwgroup";
	
    /**
     * The worker files.
     */
    public static final String
        WORKERS_BEGINNING=CONFIG_DIRECTORY+"/conf/workers",
        WORKERS_ENDING=".properties",
        WORKERS_ENDING_NEW=WORKERS_ENDING+".new",
        WORKERS_ENDING_OLD=WORKERS_ENDING+".old",

        HTTPD_CONF_BEGINNING=CONFIG_DIRECTORY+"/conf/httpd",
        HTTPD_CONF_ENDING=".conf",
        HTTPD_CONF_ENDING_NEW=HTTPD_CONF_ENDING+".new",
        HTTPD_CONF_ENDING_OLD=HTTPD_CONF_ENDING+".old"
    ;

    /**
     * The directory that logs are stored in.
     */
    public static final String LOG_DIR="/logs";

    /**
     * The directory that contains the log rotation scripts.
     */
    public static final String LOG_ROTATION_DIR="/etc/httpd/conf/logrotate.d";

    /**
     * The list of files that are contained in /www/{site}/var/log directories.
     */
    private static final String[] tomcatLogFiles={
        "jasper.log",
        "jvm_crashes.log",
        "servlet.log",
        "servlet_err",
        "tomcat.log"
    };

    private static final String[] standardProtectedURLs={
        ".*/CVS/Entries$",
        ".*/CVS/Entries~$",
        ".*/CVS/Entries.Repository$",
        ".*/CVS/Entries.Repository~$",
        ".*/CVS/Entries.Root$",
        ".*/CVS/Entries.Root~$",
        ".*/CVS/Entries.Static$",
        ".*/CVS/Entries.Static~$"
    };

    /**
     * Keeps track of the last start times for each Java VM.
     */
    private static final Map<String,Long> startJVMTimes=new HashMap<String,Long>();

    private static HttpdManager httpdManager;

    private HttpdManager() {
        Profiler.startProfile(Profiler.INSTANTANEOUS, HttpdManager.class, "<init>()", null);
        Profiler.endProfile(Profiler.INSTANTANEOUS);
    }

    private static void buildStaticSite(HttpdStaticSite staticSite, LinuxServerAccount lsa, LinuxServerGroup lsg) throws IOException, SQLException {
        Profiler.startProfile(Profiler.FAST, HttpdManager.class, "buildStaticSite(HttpdStaticSite,LinuxServerAccount,LinuxServerGroup)", null);
        try {
            throw new SQLException("Static site creation not yet supported.");
        } finally {
            Profiler.endProfile(Profiler.FAST);
        }
    }

    private static void buildTomcatSite(HttpdTomcatSite tomcatSite, LinuxServerAccount lsa, LinuxServerGroup lsg) throws IOException, SQLException {
        Profiler.startProfile(Profiler.UNKNOWN, HttpdManager.class, "buildTomcatSite(HttpdTomcatSite,LinuxServerAccount,LinuxServerGroup)", null);
        try {
            AOServConnector connector=AOServDaemon.getConnector();

            HttpdTomcatVersion htv=tomcatSite.getHttpdTomcatVersion();
            String tomcatVersion=htv.getTechnologyVersion(connector).getVersion();

            HttpdTomcatStdSite stdSite=tomcatSite.getHttpdTomcatStdSite();
            HttpdTomcatSharedSite shrSite = tomcatSite.getHttpdTomcatSharedSite();
            HttpdJBossSite jbossSite = tomcatSite.getHttpdJBossSite();
            if(stdSite!=null) {
                if(tomcatVersion.equals("3.1")) buildHttpdTomcatStdSite_3_1(stdSite);
                else if(tomcatVersion.equals("3.2.4")) buildHttpdTomcatStdSite_3_2_4(stdSite);
                else if(tomcatVersion.equals("4.1.31")) buildHttpdTomcatStdSite_4_1_X(stdSite);
                else if(tomcatVersion.equals("4.1.34")) buildHttpdTomcatStdSite_4_1_X(stdSite);
                else if(tomcatVersion.equals("4.1.Newest")) buildHttpdTomcatStdSite_4_1_X(stdSite);
                else if(tomcatVersion.equals("5.5.17")) buildHttpdTomcatStdSite_5_5_X(stdSite);
                else if(tomcatVersion.equals("5.5.20")) buildHttpdTomcatStdSite_5_5_X(stdSite);
                else if(tomcatVersion.equals("5.5.Newest")) buildHttpdTomcatStdSite_5_5_X(stdSite);
                else throw new SQLException("Unsupported version of standard Tomcat: "+tomcatVersion+" on "+stdSite);
            } else if(jbossSite!=null) {
                String jbossVersion = jbossSite.getHttpdJBossVersion().getTechnologyVersion(connector).getVersion();
                if(jbossVersion.equals("2.2.2")) buildHttpdJBossSite_2_2_2(jbossSite);
            } else if(shrSite!=null) {
                if(tomcatVersion.equals("3.1")) buildHttpdTomcatSharedSite_3_1(shrSite);
                else if(tomcatVersion.equals("3.2.4")) buildHttpdTomcatSharedSite_3_2_4(shrSite);
                else if(tomcatVersion.equals("4.1.31")) buildHttpdTomcatSharedSite_4_1_X(shrSite);
                else if(tomcatVersion.equals("4.1.34")) buildHttpdTomcatSharedSite_4_1_X(shrSite);
                else if(tomcatVersion.equals("4.1.Newest")) buildHttpdTomcatSharedSite_4_1_X(shrSite);
                else if(tomcatVersion.equals("5.5.17")) buildHttpdTomcatSharedSite_5_5_X(shrSite);
                else if(tomcatVersion.equals("5.5.20")) buildHttpdTomcatSharedSite_5_5_X(shrSite);
                else if(tomcatVersion.equals("5.5.Newest")) buildHttpdTomcatSharedSite_5_5_X(shrSite);
                else throw new SQLException("Unsupported version of shared Tomcat: "+tomcatVersion+" on "+stdSite);
            } else throw new SQLException("HttpdTomcatSite must be one of HttpdTomcatStdSite, HttpdTomcatSharedSite, or HttpdJBossSite: "+tomcatSite);
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }

    /**
     * Builds a standard install for Tomcat 3.1
     */
    private static void buildHttpdTomcatStdSite_3_1(HttpdTomcatStdSite stdSite) throws IOException, SQLException {
        Profiler.startProfile(Profiler.UNKNOWN, HttpdManager.class, "buildHttpdTomcatStdSite_3_1(HttpdTomcatStdSite)", null);
        try {
            /*
             * Resolve and allocate stuff used throughout the method
             */
            final AOServConnector connector=AOServDaemon.getConnector();
            final HttpdTomcatSite tomcatSite = stdSite.getHttpdTomcatSite();
            final HttpdSite site = tomcatSite.getHttpdSite();
            final LinuxServerAccount lsa = site.getLinuxServerAccount();
            final LinuxServerGroup lsg = site.getLinuxServerGroup();
            final int uid = lsa.getUID().getID();
            final int gid = lsg.getGID().getID();
            final String laUsername=lsa.getLinuxAccount().getUsername().getUsername();
            final String laGroupname = lsg.getLinuxGroup().getName();
            final String siteName=site.getSiteName();
            final String siteDir=HttpdSite.WWW_DIRECTORY+'/'+siteName;
            final String tomcatDirectory=tomcatSite.getHttpdTomcatVersion().getInstallDirectory();
            final String primaryUrl=site.getPrimaryHttpdSiteURL().getHostname();
            final AOServer thisAOServer = AOServDaemon.getThisAOServer();
            final Server server = thisAOServer.getServer();
            final String hostname = server.getHostname();
            final int osv = server.getOperatingSystemVersion().getPkey();
            final PostgresServer postgresServer=thisAOServer.getPreferredPostgresServer();
            final String postgresServerMinorVersion=postgresServer==null?null:postgresServer.getPostgresVersion().getMinorVersion();

            /*
             * Create the skeleton of the site, the directories and links.
             */
            mkdir(siteDir+"/bin", 0770, lsa, lsg);
            ln("webapps/"+HttpdTomcatContext.ROOT_DOC_BASE+"/cgi-bin", siteDir+"/cgi-bin", uid, gid);
            mkdir(siteDir+"/conf", 0775, lsa, lsg);
            mkdir(siteDir+"/daemon", 0770, lsa, lsg);
            if (site.getDisableLog()==null) ln("../bin/tomcat", siteDir+"/daemon/tomcat", uid, gid);
            ln("webapps/"+HttpdTomcatContext.ROOT_DOC_BASE, siteDir+"/htdocs", uid, gid);
            mkdir(siteDir+"/lib", 0770, lsa, lsg);
            ln("webapps/"+HttpdTomcatContext.ROOT_DOC_BASE+"/WEB-INF/classes", siteDir+"/servlet", uid, gid);
            mkdir(siteDir+"/var", 0770, lsa, lsg);
            mkdir(siteDir+"/var/log", 0770, lsa, lsg);
            mkdir(siteDir+"/var/run", 0770, lsa, lsg);
            mkdir(siteDir+"/webapps", 0775, lsa, lsg);
            final String rootDir = siteDir+"/webapps/"+HttpdTomcatContext.ROOT_DOC_BASE;
            mkdir(rootDir, 0775, lsa, lsg);
            mkdir(siteDir+"/webapps/"+HttpdTomcatContext.ROOT_DOC_BASE+"/META-INF", 0775, lsa, lsg);
            mkdir(siteDir+"/webapps/"+HttpdTomcatContext.ROOT_DOC_BASE+"/WEB-INF", 0775, lsa, lsg);
            mkdir(siteDir+"/webapps/"+HttpdTomcatContext.ROOT_DOC_BASE+"/WEB-INF/classes", 0770, lsa, lsg);
            mkdir(siteDir+"/webapps/"+HttpdTomcatContext.ROOT_DOC_BASE+"/WEB-INF/cocoon", 0770, lsa, lsg);
            mkdir(siteDir+"/webapps/"+HttpdTomcatContext.ROOT_DOC_BASE+"/WEB-INF/conf", 0770, lsa, lsg);
            mkdir(siteDir+"/webapps/"+HttpdTomcatContext.ROOT_DOC_BASE+"/WEB-INF/lib", 0770, lsa, lsg);	
            mkdir(siteDir+"/webapps/"+HttpdTomcatContext.ROOT_DOC_BASE+"/cgi-bin", 0755, lsa, lsg);
            mkdir(siteDir+"/work", 0750, lsa, lsg);
            final String profileFile=siteDir+"/bin/profile";
            LinuxAccountManager.setBashProfile(lsa, profileFile);
            ChainWriter out=new ChainWriter(
                new BufferedOutputStream(
                    new UnixFile(profileFile).getSecureOutputStream(
                        uid,
                        gid,
                        0750,
                        false
                    )
                )
            );
            try {
                out.print("#!/bin/sh\n"
                        + "\n"
                        + ". /etc/profile\n"
                        + ". /usr/aoserv/etc/").print(getDefaultJdkVersion(osv)).print(".sh\n"
                        + ". /usr/aoserv/etc/jakarta-oro-2.0.sh\n"
                        + ". /usr/aoserv/etc/jakarta-regexp-1.1.sh\n"
                        + ". /usr/aoserv/etc/jakarta-servletapi-3.1.sh\n"
                        + ". /usr/aoserv/etc/jakarta-tomcat-3.1.sh\n"
                        + ". /usr/aoserv/etc/jetspeed-1.1.sh\n"
                        + ". /usr/aoserv/etc/cocoon-1.8.2.sh\n"
                        + ". /usr/aoserv/etc/xerces-1.2.0.sh\n"
                        + ". /usr/aoserv/etc/ant-1.6.2.sh\n"
                        + ". /usr/aoserv/etc/xalan-1.2.d02.sh\n"
                        + ". /usr/aoserv/etc/php-").print(getDefaultPhpVersion(osv)).print(".sh\n");
                if(postgresServerMinorVersion!=null) out.print(". /usr/aoserv/etc/postgresql-").print(postgresServerMinorVersion).print(".sh\n");
                out.print(". /usr/aoserv/etc/profile\n"
                        + "export \"CLASSPATH=/usr/aoserv/lib-1.3/aocode-public.jar:$CLASSPATH\"\n"
                        + ". /usr/aoserv/etc/fop-0.15.0.sh\n"
                        + "\n"
                        + "export PATH=${PATH}:").print(siteDir).print("/bin\n"
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
                out.flush();
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
                        + "        ulimit -S -m 196608 -v 400000\n"
                        + "        ulimit -H -m 196608 -v 400000\n"
                        + "        java -Dmail.smtp.host=").print(hostname).print(" org.apache.tomcat.startup.Tomcat -f ${TOMCAT_HOME}/conf/server.xml -stop &>/dev/null\n"
                        + "        kill `cat ${TOMCAT_HOME}/var/run/java.pid` &>/dev/null\n"
                        + "        rm -f ${TOMCAT_HOME}/var/run/java.pid\n"
                        + "    fi\n"
                        + "elif [ \"$1\" = \"daemon\" ]; then\n"
                        + "    cd $TOMCAT_HOME\n"
                        + "    . $TOMCAT_HOME/bin/profile\n"
                        + "\n"
                        + "    while [ 1 ]; do\n"
                        + "        ulimit -S -m 196608 -v 400000\n"
                        + "        ulimit -H -m 196608 -v 400000\n"
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
                        + "fi\n"
                );
            } finally {
                out.flush();
                out.close();
            }
            mkdir(siteDir+"/classes", 0770, lsa, lsg);
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
                        + "Implementation-Vendor: \"Sun Microsystems, Inc.\"\n"
                );
            } finally {
                out.flush();
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
                        + "    value CDATA \"\">\n"
                );
            } finally {
                out.flush();
                out.close();
            }
            copyResource("test-tomcat.xml", siteDir+"/conf/test-tomcat.xml", uid, gid, 0660);
            out=new ChainWriter(
                new BufferedOutputStream(
                    new UnixFile(siteDir+"/conf/tomcat-users.xml").getSecureOutputStream(uid, gid, 0660, false)
                )
            );
            try {
                out.print("<tomcat-users>\n"
                        + "  <user name=\"tomcat\" password=\"tomcat\" roles=\"tomcat\" />\n"
                        + "</tomcat-users>\n"
                );
            } finally {
                out.flush();
                out.close();
            }
            copyResource("web.dtd-3.1", siteDir+"/conf/web.dtd", uid, gid, 0660);
            copyResource("web.xml-3.1", siteDir+"/conf/web.xml", uid, gid, 0660);
            for(int c=0;c<tomcatLogFiles.length;c++) {
                String filename=siteDir+"/var/log/"+tomcatLogFiles[c];
                new UnixFile(filename).getSecureOutputStream(uid, gid, 0660, false).close();
            }
            new ChainWriter(
                new UnixFile(siteDir+"/webapps/"+HttpdTomcatContext.ROOT_DOC_BASE+"/META-INF/MANIFEST.MF").getSecureOutputStream(uid, gid, 0664, false)
            ).print("Manifest-Version: 1.0").flush().close();

            /*
             * Write the cocoon.properties file.
             */
            OutputStream fileOut=new BufferedOutputStream(new UnixFile(siteDir+"/webapps/"+HttpdTomcatContext.ROOT_DOC_BASE+"/WEB-INF/conf/cocoon.properties").getSecureOutputStream(uid, gid, 0660, false));
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
            out=new ChainWriter(new BufferedOutputStream(new UnixFile(siteDir+"/webapps/"+HttpdTomcatContext.ROOT_DOC_BASE+"/WEB-INF/web.xml").getSecureOutputStream(uid, gid, 0664, false)));
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

            createCgiPhpScript(site);
            createTestCGI(site);
            createTestIndex(site);

            /*
             * Create the test.php file.
             */
            new ChainWriter(new UnixFile(siteDir+"/webapps/"+HttpdTomcatContext.ROOT_DOC_BASE+"/test.php")
                .getSecureOutputStream(uid, gid, 0664, false))
                .print("<?phpinfo()?>\n")
                .flush()
                .close();
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }

    /**
     * Builds a standard install for Tomcat 3.2.4
     */
    private static void buildHttpdTomcatStdSite_3_2_4(HttpdTomcatStdSite stdSite) throws IOException, SQLException {
        Profiler.startProfile(Profiler.UNKNOWN, HttpdManager.class, "buildHttpdTomcatStdSite_3_2_4(HttpdTomcatStdSite)", null);
        try {
            /*
             * Resolve and allocate stuff used throughout the method
             */
            final AOServConnector connector=AOServDaemon.getConnector();
            final HttpdTomcatSite tomcatSite = stdSite.getHttpdTomcatSite();
            final HttpdSite site = tomcatSite.getHttpdSite();
            final LinuxServerAccount lsa = site.getLinuxServerAccount();
            final LinuxServerGroup lsg = site.getLinuxServerGroup();
            final int uid = lsa.getUID().getID();
            final int gid = lsg.getGID().getID();
            final String laUsername=lsa.getLinuxAccount().getUsername().getUsername();
            final String laGroupname = lsg.getLinuxGroup().getName();
            final String siteName=site.getSiteName();
            final String siteDir=HttpdSite.WWW_DIRECTORY+'/'+siteName;
            final String tomcatDirectory=tomcatSite.getHttpdTomcatVersion().getInstallDirectory();
            final String primaryUrl=site.getPrimaryHttpdSiteURL().getHostname();
            final AOServer thisAOServer = AOServDaemon.getThisAOServer();
            final Server server = thisAOServer.getServer();
            final String hostname = server.getHostname();
            final int osv = server.getOperatingSystemVersion().getPkey();
            final PostgresServer postgresServer=thisAOServer.getPreferredPostgresServer();
            final String postgresServerMinorVersion=postgresServer==null?null:postgresServer.getPostgresVersion().getMinorVersion();

            /*
             * Create the skeleton of the site, the directories and links.
             */
            mkdir(siteDir+"/bin", 0770, lsa, lsg);
            ln("webapps/"+HttpdTomcatContext.ROOT_DOC_BASE+"/cgi-bin", siteDir+"/cgi-bin", uid, gid);
            mkdir(siteDir+"/conf", 0775, lsa, lsg);
            mkdir(siteDir+"/daemon", 0770, lsa, lsg);
            if (site.getDisableLog()==null) ln("../bin/tomcat", siteDir+"/daemon/tomcat", uid, gid);
            ln("webapps/"+HttpdTomcatContext.ROOT_DOC_BASE, siteDir+"/htdocs", uid, gid);
            mkdir(siteDir+"/lib", 0770, lsa, lsg);
            ln("webapps/"+HttpdTomcatContext.ROOT_DOC_BASE+"/WEB-INF/classes", siteDir+"/servlet", uid, gid);
            mkdir(siteDir+"/var", 0770, lsa, lsg);
            mkdir(siteDir+"/var/log", 0770, lsa, lsg);
            mkdir(siteDir+"/var/run", 0770, lsa, lsg);
            mkdir(siteDir+"/webapps", 0775, lsa, lsg);
            mkdir(siteDir+"/webapps/"+HttpdTomcatContext.ROOT_DOC_BASE, 0775, lsa, lsg);
            mkdir(siteDir+"/webapps/"+HttpdTomcatContext.ROOT_DOC_BASE+"/META-INF", 0775, lsa, lsg);
            mkdir(siteDir+"/webapps/"+HttpdTomcatContext.ROOT_DOC_BASE+"/WEB-INF", 0775, lsa, lsg);
            mkdir(siteDir+"/webapps/"+HttpdTomcatContext.ROOT_DOC_BASE+"/WEB-INF/classes", 0770, lsa, lsg);
            mkdir(siteDir+"/webapps/"+HttpdTomcatContext.ROOT_DOC_BASE+"/WEB-INF/cocoon", 0770, lsa, lsg);
            mkdir(siteDir+"/webapps/"+HttpdTomcatContext.ROOT_DOC_BASE+"/WEB-INF/conf", 0770, lsa, lsg);
            mkdir(siteDir+"/webapps/"+HttpdTomcatContext.ROOT_DOC_BASE+"/WEB-INF/lib", 0770, lsa, lsg);	
            mkdir(siteDir+"/webapps/"+HttpdTomcatContext.ROOT_DOC_BASE+"/cgi-bin", 0755, lsa, lsg);
            mkdir(siteDir+"/work", 0750, lsa, lsg);
            final String profileFile=siteDir+"/bin/profile";
            LinuxAccountManager.setBashProfile(lsa, profileFile);

            /*
             * Write the profile script for standard only
             */
            ChainWriter out=new ChainWriter(
                new BufferedOutputStream(
                    new UnixFile(profileFile).getSecureOutputStream(uid, gid, 0750, false)
                )
            );
            try {
                out.print("#!/bin/sh\n"
                        + "\n"
                        + ". /etc/profile\n"
                        + ". /usr/aoserv/etc/").print(getDefaultJdkVersion(osv)).print(".sh\n"
                        + ". /usr/aoserv/etc/jakarta-oro-2.0.sh\n"
                        + ". /usr/aoserv/etc/jakarta-regexp-1.1.sh\n"
                        + ". /usr/aoserv/etc/jakarta-servletapi-3.2.sh\n"
                        + ". /usr/aoserv/etc/jakarta-tomcat-3.2.sh\n"
                        + ". /usr/aoserv/etc/jetspeed-1.1.sh\n"
                        + ". /usr/aoserv/etc/cocoon-1.8.2.sh\n"
                        + ". /usr/aoserv/etc/xerces-1.2.0.sh\n"
                        + ". /usr/aoserv/etc/ant-1.6.2.sh\n"
                        + ". /usr/aoserv/etc/xalan-1.2.d02.sh\n"
                        + ". /usr/aoserv/etc/php-").print(getDefaultPhpVersion(osv)).print(".sh\n");
                if(postgresServerMinorVersion!=null) out.print(". /usr/aoserv/etc/postgresql-").print(postgresServerMinorVersion).print(".sh\n");
                out.print(". /usr/aoserv/etc/profile\n"
                        + ". /usr/aoserv/etc/fop-0.15.0.sh\n"
                        + "\n"
                        + "export PATH=${PATH}:").print(siteDir).print("/bin\n"
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
                out.flush();
                out.close();
            }

            /*
             * Write the bin/tomcat script.
             */
            out=new ChainWriter(
                new BufferedOutputStream(
                    new UnixFile(siteDir+"/bin/tomcat").getSecureOutputStream(uid, gid, 0700, false)
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
                        + "        ulimit -S -m 196608 -v 400000\n"
                        + "        ulimit -H -m 196608 -v 400000\n"
                        + "        java -Dmail.smtp.host=").print(hostname).print(" org.apache.tomcat.startup.Tomcat -f ${TOMCAT_HOME}/conf/server.xml -stop &>/dev/null\n"
                        + "        kill `cat ${TOMCAT_HOME}/var/run/java.pid` &>/dev/null\n"
                        + "        rm -f ${TOMCAT_HOME}/var/run/java.pid\n"
                        + "    fi\n"
                        + "elif [ \"$1\" = \"daemon\" ]; then\n"
                        + "    cd $TOMCAT_HOME\n"
                        + "    . $TOMCAT_HOME/bin/profile\n"
                        + "\n"
                        + "    while [ 1 ]; do\n"
                        + "        ulimit -S -m 196608 -v 400000\n"
                        + "        ulimit -H -m 196608 -v 400000\n"
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
                out.flush();
                out.close();
            }

            /*
             * The classes directory
             */
            mkdir(siteDir+"/classes", 0770, lsa, lsg);

            /*
             * Write the manifest.servlet file.
             */
            out=new ChainWriter(
                new BufferedOutputStream(
                    new UnixFile(siteDir+"/conf/manifest.servlet").getSecureOutputStream(uid, gid, 0660, false)
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
                out.flush();
                out.close();
            }

            /*
             * Create the conf/server.dtd file.
             */
            out=new ChainWriter(
                new BufferedOutputStream(
                    new UnixFile(siteDir+"/conf/server.dtd").getSecureOutputStream(uid, gid, 0660, false)
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
                out.flush();
                out.close();
            }

            copyResource("test-tomcat.xml", siteDir+"/conf/test-tomcat.xml", uid, gid, 0660);

            /*
             * Create the tomcat-users.xml file
             */
            out=new ChainWriter(
                new BufferedOutputStream(
                    new UnixFile(siteDir+"/conf/tomcat-users.xml").getSecureOutputStream(uid, gid, 0660, false)
                )
            );
            try {
                out.print("<tomcat-users>\n"
                        + "  <user name=\"tomcat\" password=\"tomcat\" roles=\"tomcat\" />\n"
                        + "  <user name=\"role1\"  password=\"tomcat\" roles=\"role1\" />\n"
                        + "  <user name=\"both\"   password=\"tomcat\" roles=\"tomcat,role1\" />\n"
                        + "</tomcat-users>\n");
            } finally {
                out.flush();
                out.close();
            }

            copyResource("web.dtd-3.2.4", siteDir+"/conf/web.dtd", uid, gid, 0660);
            copyResource("web.xml-3,2,4", siteDir+"/conf/web.xml", uid, gid, 0660);

            /*
             * Create the empty log files.
             */
            for(int c=0;c<tomcatLogFiles.length;c++) {
                String filename=siteDir+"/var/log/"+tomcatLogFiles[c];
                new UnixFile(filename).getSecureOutputStream(uid, gid, 0660, false).close();
            }

            /*
             * Create the manifest file.
             */
            final String manifestFile=siteDir+"/webapps/"+HttpdTomcatContext.ROOT_DOC_BASE+"/META-INF/MANIFEST.MF";
            new ChainWriter(new UnixFile(manifestFile).getSecureOutputStream(uid, gid, 0664, false)).print("Manifest-Version: 1.0").flush().close();

            /*
             * Write the cocoon.properties file.
             */
            OutputStream fileOut=new BufferedOutputStream(new UnixFile(siteDir+"/webapps/"+HttpdTomcatContext.ROOT_DOC_BASE+"/WEB-INF/conf/cocoon.properties").getSecureOutputStream(uid, gid, 0660, false));
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
                out.flush();
                out.close();
            }

            createCgiPhpScript(site);
            createTestCGI(site);
            createTestIndex(site);

            /*
             * Create the test.php file.
             */
            new ChainWriter(new UnixFile(siteDir+"/webapps/"+HttpdTomcatContext.ROOT_DOC_BASE+"/test.php").getSecureOutputStream(uid, gid, 0664, false))
                .print("<?phpinfo()?>\n")
                .flush()
                .close()
            ;
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }

    /**
     * Builds a standard install for Tomcat 4.1.X
     */
    private static void buildHttpdTomcatStdSite_4_1_X(HttpdTomcatStdSite stdSite) throws IOException, SQLException {
        Profiler.startProfile(Profiler.UNKNOWN, HttpdManager.class, "buildHttpdTomcatStdSite_4_1_X(HttpdTomcatStdSite)", null);
        try {
            /*
             * Resolve and allocate stuff used throughout the method
             */
            final AOServConnector connector=AOServDaemon.getConnector();
            final HttpdTomcatSite tomcatSite = stdSite.getHttpdTomcatSite();
            final HttpdSite site = tomcatSite.getHttpdSite();
            final LinuxServerAccount lsa = site.getLinuxServerAccount();
            final LinuxServerGroup lsg = site.getLinuxServerGroup();
            final int uid = lsa.getUID().getID();
            final int gid = lsg.getGID().getID();
            final String laUsername=lsa.getLinuxAccount().getUsername().getUsername();
            final String laGroupname = lsg.getLinuxGroup().getName();
            final String siteName=site.getSiteName();
            final String siteDir=HttpdSite.WWW_DIRECTORY+'/'+siteName;
            final String tomcatDirectory=tomcatSite.getHttpdTomcatVersion().getInstallDirectory();
            final String primaryUrl=site.getPrimaryHttpdSiteURL().getHostname();
            final AOServer thisAOServer = AOServDaemon.getThisAOServer();
            final Server server = thisAOServer.getServer();
            final String hostname = server.getHostname();
            final int osv = server.getOperatingSystemVersion().getPkey();
            final PostgresServer postgresServer=thisAOServer.getPreferredPostgresServer();
            final String postgresServerMinorVersion=postgresServer==null?null:postgresServer.getPostgresVersion().getMinorVersion();
            /*
             * Create the skeleton of the site, the directories and links.
             */
            mkdir(siteDir+"/bin", 0770, lsa, lsg);
            mkdir(siteDir+"/conf", 0775, lsa, lsg);
            mkdir(siteDir+"/daemon", 0770, lsa, lsg);
            if (site.getDisableLog()==null) ln("../bin/tomcat", siteDir+"/daemon/tomcat", uid, gid);
            mkdir(siteDir+"/temp", 0770, lsa, lsg);
            ln("var/log", siteDir+"/logs", uid, gid);
            mkdir(siteDir+"/var", 0770, lsa, lsg);
            mkdir(siteDir+"/var/log", 0770, lsa, lsg);
            mkdir(siteDir+"/var/run", 0770, lsa, lsg);
            mkdir(siteDir+"/webapps", 0775, lsa, lsg);
            mkdir(siteDir+"/webapps/"+HttpdTomcatContext.ROOT_DOC_BASE, 0775, lsa, lsg);
            mkdir(siteDir+"/webapps/"+HttpdTomcatContext.ROOT_DOC_BASE+"/WEB-INF", 0775, lsa, lsg);
            mkdir(siteDir+"/webapps/"+HttpdTomcatContext.ROOT_DOC_BASE+"/WEB-INF/classes", 0770, lsa, lsg);
            mkdir(siteDir+"/webapps/"+HttpdTomcatContext.ROOT_DOC_BASE+"/WEB-INF/lib", 0770, lsa, lsg);	
            mkdir(siteDir+"/webapps/"+HttpdTomcatContext.ROOT_DOC_BASE+"/cgi-bin", 0755, lsa, lsg);
            mkdir(siteDir+"/work", 0750, lsa, lsg);
            ln("../../.."+tomcatDirectory+"/bin/bootstrap.jar", siteDir+"/bin/bootstrap.jar", uid, gid);
            ln("../../.."+tomcatDirectory+"/bin/catalina.sh", siteDir+"/bin/catalina.sh", uid, gid);
            ln("../../.."+tomcatDirectory+"/bin/commons-daemon.jar", siteDir+"/bin/commons-daemon.jar", uid, gid);
            ln("../../.."+tomcatDirectory+"/bin/digest.sh", siteDir+"/bin/digest.sh", uid, gid);
            ln("../../.."+tomcatDirectory+"/bin/jasper.sh", siteDir+"/bin/jasper.sh", uid, gid);
            ln("../../.."+tomcatDirectory+"/bin/jspc.sh", siteDir+"/bin/jspc.sh", uid, gid);

            /*
             * Set up the bash profile
             */
            final String profileFile=siteDir+"/bin/profile";
            LinuxAccountManager.setBashProfile(lsa, profileFile);
            ChainWriter out=new ChainWriter(
                new BufferedOutputStream(
                    new UnixFile(profileFile).getSecureOutputStream(uid, gid, 0750, false )
                )
            );
            try {
                out.print("#!/bin/sh\n"
                        + "\n"
                        + ". /etc/profile\n"
                        + ". /usr/aoserv/etc/").print(getDefaultJdkVersion(osv)).print(".sh\n");
                final String defaultPhpVersion = getDefaultPhpVersion(osv);
                if(defaultPhpVersion!=null) out.print(". /usr/aoserv/etc/php-").print(defaultPhpVersion).print(".sh\n");
                if(postgresServerMinorVersion!=null) {
                    if(osv==OperatingSystemVersion.REDHAT_ES_4_X86_64) {
                        out.print(". /opt/aoserv-client/scripts/postgresql-").print(postgresServerMinorVersion).print(".sh\n");
                    } else {
                        out.print(". /usr/aoserv/etc/postgresql-").print(postgresServerMinorVersion).print(".sh\n");
                    }
                }
                if(osv==OperatingSystemVersion.REDHAT_ES_4_X86_64) {
                    out.print(". /opt/aoserv-client/scripts/aoserv-client.sh\n");
                } else {
                    out.print(". /usr/aoserv/etc/aoserv.sh\n");
                }
                out.print("\n"
                        + "umask 002\n"
                        + "export DISPLAY=:0.0\n"
                        + "\n"
                        + "export CATALINA_HOME=\"").print(siteDir).print("\"\n"
                        + "export CATALINA_BASE=\"").print(siteDir).print("\"\n"
                        + "export CATALINA_TEMP=\"").print(siteDir).print("/temp\"\n"
                        + "\n"
                        + "export PATH=${PATH}:").print(siteDir).print("/bin\n");
            } finally {
                out.flush();
                out.close();
            }

            /*
             * Write the bin/tomcat script.
             */
            out=new ChainWriter(
                new BufferedOutputStream(
                    new UnixFile(siteDir+"/bin/tomcat").getSecureOutputStream(uid, gid, 0700, false)
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
                        + "        ${TOMCAT_HOME}/bin/catalina.sh stop 2>&1 >>${TOMCAT_HOME}/var/log/tomcat_err\n"
                        + "        kill `cat ${TOMCAT_HOME}/var/run/java.pid` &>/dev/null\n"
                        + "        rm -f ${TOMCAT_HOME}/var/run/java.pid\n"
                        + "    fi\n"
                        + "elif [ \"$1\" = \"daemon\" ]; then\n"
                        + "    cd $TOMCAT_HOME\n"
                        + "    . $TOMCAT_HOME/bin/profile\n"
                        + "\n"
                        + "    while [ 1 ]; do\n"
                        + "        umask 002\n"
                        + "        export DISPLAY=:0.0\n"
                        + "        mv -f ${TOMCAT_HOME}/var/log/tomcat_err ${TOMCAT_HOME}/var/log/tomcat_err.old\n"
                        + "        ${TOMCAT_HOME}/bin/catalina.sh run >&${TOMCAT_HOME}/var/log/tomcat_err &\n"
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
                        + "fi\n"
                );
            } finally {
                out.flush();
                out.close();
            }

            ln("../../.."+tomcatDirectory+"/bin/setclasspath.sh", siteDir+"/bin/setclasspath.sh", uid, gid);

            out=new ChainWriter(new UnixFile(siteDir+"/bin/shutdown.sh").getSecureOutputStream(uid, gid, 0700, true));
            try {
                out.print("#!/bin/sh\n"
                        + "exec ").print(siteDir).print("/bin/tomcat stop\n");
            } finally {
                out.flush();
                out.close();
            }

            out=new ChainWriter(new UnixFile(siteDir+"/bin/startup.sh").getSecureOutputStream(uid, gid, 0700, true));
            try {
                out.print("#!/bin/sh\n"
                        + "exec ").print(siteDir).print("/bin/tomcat start\n");
            } finally {
                out.flush();
                out.close();
            }

            ln("../../.."+tomcatDirectory+"/bin/tomcat-jni.jar", siteDir+"/bin/tomcat-jni.jar", uid, gid);
            ln("../../.."+tomcatDirectory+"/bin/tool-wrapper.sh", siteDir+"/bin/tool-wrapper.sh", uid, gid);
            mkdir(siteDir+"/common", 0775, lsa, lsg);
            mkdir(siteDir+"/common/classes", 0775, lsa, lsg);
            mkdir(siteDir+"/common/endorsed", 0775, lsa, lsg);
            lnAll("../../../.."+tomcatDirectory+"/common/endorsed/", siteDir+"/common/endorsed/", uid, gid);
            mkdir(siteDir+"/common/lib", 0775, lsa, lsg);
            lnAll("../../../.."+tomcatDirectory+"/common/lib/", siteDir+"/common/lib/", uid, gid);

            if(postgresServerMinorVersion!=null) {
                if(osv==OperatingSystemVersion.MANDRIVA_2006_0_I586) {
                    ln("../../../../usr/postgresql/"+postgresServerMinorVersion+"/share/java/postgresql.jar", siteDir+"/common/lib/postgresql.jar", uid, gid);
                } else if(osv==OperatingSystemVersion.REDHAT_ES_4_X86_64) {
                    ln("../../../../opt/postgresql-"+postgresServerMinorVersion+"/share/java/postgresql.jar", siteDir+"/common/lib/postgresql.jar", uid, gid);
                } else throw new SQLException("Unsupported OperatingSystemVersion: "+osv);
            }
            if(osv==OperatingSystemVersion.MANDRIVA_2006_0_I586) {
                ln("../../../../usr/mysql-connector-java/3.1.12/mysql-connector-java-3.1.12-bin.jar", siteDir+"/common/lib/mysql-connector-java-3.1.12-bin.jar", uid, gid);
            } else if(osv==OperatingSystemVersion.REDHAT_ES_4_X86_64) {
                ln("../../../../opt/mysql-connector-java-3.1.12/mysql-connector-java-3.1.12-bin.jar", siteDir+"/common/lib/mysql-connector-java-3.1.12-bin.jar", uid, gid);
            } else throw new SQLException("Unsupported OperatingSystemVersion: "+osv);

            /*
             * Write the conf/catalina.policy file
             */
            {
                UnixFile cp=new UnixFile(siteDir+"/conf/catalina.policy");
                new UnixFile(tomcatDirectory+"/conf/catalina.policy").copyTo(cp, false);
                cp.chown(uid, gid).setMode(0660);
            }

            /*
             * Create the tomcat-users.xml file
             */
            final UnixFile tu=new UnixFile(siteDir+"/conf/tomcat-users.xml");
            new UnixFile(tomcatDirectory+"/conf/tomcat-users.xml").copyTo(tu, false);
            tu.chown(uid, gid).setMode(0660);

            final UnixFile wx=new UnixFile(siteDir+"/conf/web.xml");
            new UnixFile(tomcatDirectory+"/conf/web.xml").copyTo(wx, false);
            wx.chown(uid, gid).setMode(0660);

            mkdir(siteDir+"/server", 0775, lsa, lsg);
            mkdir(siteDir+"/server/classes", 0775, lsa, lsg);
            mkdir(siteDir+"/server/lib", 0775, lsa, lsg);
            lnAll("../../../.."+tomcatDirectory+"/server/lib/", siteDir+"/server/lib/", uid, gid);
            mkdir(siteDir+"/server/webapps", 0775, lsa, lsg);
            mkdir(siteDir+"/shared", 0775, lsa, lsg);
            mkdir(siteDir+"/shared/classes", 0775, lsa, lsg);
            mkdir(siteDir+"/shared/lib", 0775, lsa, lsg);

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

            createCgiPhpScript(site);
            createTestCGI(site);
            createTestIndex(site);

            /*
             * Create the test.php file.
             */
            String testPHP=siteDir+"/webapps/"+HttpdTomcatContext.ROOT_DOC_BASE+"/test.php";
            new ChainWriter(
                new UnixFile(testPHP).getSecureOutputStream(uid, gid, 0664, false)
            ).print("<?phpinfo()?>\n").flush().close();
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }

    /**
     * Builds a standard install for Tomcat 5.5.X
     */
    private static void buildHttpdTomcatStdSite_5_5_X(HttpdTomcatStdSite stdSite) throws IOException, SQLException {
        Profiler.startProfile(Profiler.UNKNOWN, HttpdManager.class, "buildHttpdTomcatStdSite_5_5_X(HttpdTomcatStdSite)", null);
        try {
            /*
             * Resolve and allocate stuff used throughout the method
             */
            final AOServConnector connector=AOServDaemon.getConnector();
            final HttpdTomcatSite tomcatSite = stdSite.getHttpdTomcatSite();
            final HttpdSite site = tomcatSite.getHttpdSite();
            final LinuxServerAccount lsa = site.getLinuxServerAccount();
            final LinuxServerGroup lsg = site.getLinuxServerGroup();
            final int uid = lsa.getUID().getID();
            final int gid = lsg.getGID().getID();
            final String laUsername=lsa.getLinuxAccount().getUsername().getUsername();
            final String laGroupname = lsg.getLinuxGroup().getName();
            final String siteName=site.getSiteName();
            final String siteDir=HttpdSite.WWW_DIRECTORY+'/'+siteName;
            final String tomcatDirectory=tomcatSite.getHttpdTomcatVersion().getInstallDirectory();
            final String primaryUrl=site.getPrimaryHttpdSiteURL().getHostname();
            final AOServer thisAOServer = AOServDaemon.getThisAOServer();
            final Server server = thisAOServer.getServer();
            final String hostname = server.getHostname();
            final int osv = server.getOperatingSystemVersion().getPkey();
            final PostgresServer postgresServer=thisAOServer.getPreferredPostgresServer();
            final String postgresServerMinorVersion=postgresServer==null?null:postgresServer.getPostgresVersion().getMinorVersion();

            /*
             * Create the skeleton of the site, the directories and links.
             */
            mkdir(siteDir+"/bin", 0770, lsa, lsg);
            mkdir(siteDir+"/conf", 0775, lsa, lsg);
            mkdir(siteDir+"/daemon", 0770, lsa, lsg);
            if (site.getDisableLog()==null) ln("../bin/tomcat", siteDir+"/daemon/tomcat", uid, gid);
            mkdir(siteDir+"/temp", 0770, lsa, lsg);
            ln("var/log", siteDir+"/logs", uid, gid);
            mkdir(siteDir+"/var", 0770, lsa, lsg);
            mkdir(siteDir+"/var/log", 0770, lsa, lsg);
            mkdir(siteDir+"/var/run", 0770, lsa, lsg);
            mkdir(siteDir+"/webapps", 0775, lsa, lsg);
            mkdir(siteDir+"/webapps/"+HttpdTomcatContext.ROOT_DOC_BASE, 0775, lsa, lsg);
            mkdir(siteDir+"/webapps/"+HttpdTomcatContext.ROOT_DOC_BASE+"/WEB-INF", 0775, lsa, lsg);
            mkdir(siteDir+"/webapps/"+HttpdTomcatContext.ROOT_DOC_BASE+"/WEB-INF/classes", 0770, lsa, lsg);
            mkdir(siteDir+"/webapps/"+HttpdTomcatContext.ROOT_DOC_BASE+"/WEB-INF/lib", 0770, lsa, lsg);	
            mkdir(siteDir+"/webapps/"+HttpdTomcatContext.ROOT_DOC_BASE+"/cgi-bin", 0755, lsa, lsg);
            mkdir(siteDir+"/work", 0750, lsa, lsg);
            ln("../../.."+tomcatDirectory+"/bin/bootstrap.jar", siteDir+"/bin/bootstrap.jar", uid, gid);
            ln("../../.."+tomcatDirectory+"/bin/catalina.sh", siteDir+"/bin/catalina.sh", uid, gid);
            ln("../../.."+tomcatDirectory+"/bin/commons-daemon.jar", siteDir+"/bin/commons-daemon.jar", uid, gid);
            ln("../../.."+tomcatDirectory+"/bin/commons-logging-api.jar", siteDir+"/bin/commons-logging-api.jar", uid, gid);
            ln("../../.."+tomcatDirectory+"/bin/digest.sh", siteDir+"/bin/digest.sh", uid, gid);

            /*
             * Set up the bash profile source
             */
            String profileFile=siteDir+"/bin/profile";
            LinuxAccountManager.setBashProfile(lsa, profileFile);
            ChainWriter out=new ChainWriter(
                new BufferedOutputStream(
                    new UnixFile(profileFile).getSecureOutputStream(
                        uid,
                        gid,
                        0750,
                        false
                    )
                )
            );
            try {
                out.print("#!/bin/sh\n"
                        + "\n"
                        + ". /etc/profile\n");
                if(osv==OperatingSystemVersion.REDHAT_ES_4_X86_64) {
                    out.print(". /opt/aoserv-client/scripts/").print(DEFAULT_TOMCAT_5_JDK_VERSION).print(".sh\n");
                } else {
                    out.print(". /usr/aoserv/etc/").print(DEFAULT_TOMCAT_5_JDK_VERSION).print(".sh\n");
                }
                String defaultPhpVersion = getDefaultPhpVersion(osv);
                if(defaultPhpVersion!=null) out.print(". /usr/aoserv/etc/php-").print(defaultPhpVersion).print(".sh\n");
                if(postgresServerMinorVersion!=null) {
                    if(osv==OperatingSystemVersion.REDHAT_ES_4_X86_64) {
                        out.print(". /opt/aoserv-client/scripts/postgresql-").print(postgresServerMinorVersion).print(".sh\n");
                    } else {
                        out.print(". /usr/aoserv/etc/postgresql-").print(postgresServerMinorVersion).print(".sh\n");
                    }
                }
                if(osv==OperatingSystemVersion.REDHAT_ES_4_X86_64) {
                    out.print(". /opt/aoserv-client/scripts/aoserv-client.sh\n");
                } else {
                    out.print(". /usr/aoserv/etc/aoserv.sh\n");
                }
                out.print("\n"
                        + "umask 002\n"
                        + "export DISPLAY=:0.0\n"
                        + "\n"
                        + "export CATALINA_HOME=\"").print(siteDir).print("\"\n"
                        + "export CATALINA_BASE=\"").print(siteDir).print("\"\n"
                        + "export CATALINA_TEMP=\"").print(siteDir).print("/temp\"\n"
                        + "\n"
                        + "export PATH=${PATH}:").print(siteDir).print("/bin\n");
            } finally {
                out.flush();
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
                        + "        ${TOMCAT_HOME}/bin/catalina.sh stop 2>&1 >>${TOMCAT_HOME}/var/log/tomcat_err\n"
                        + "        kill `cat ${TOMCAT_HOME}/var/run/java.pid` &>/dev/null\n"
                        + "        rm -f ${TOMCAT_HOME}/var/run/java.pid\n"
                        + "    fi\n"
                        + "elif [ \"$1\" = \"daemon\" ]; then\n"
                        + "    cd $TOMCAT_HOME\n"
                        + "    . $TOMCAT_HOME/bin/profile\n"
                        + "\n"
                        + "    while [ 1 ]; do\n"
                        + "        umask 002\n"
                        + "        export DISPLAY=:0.0\n"
                        + "        mv -f ${TOMCAT_HOME}/var/log/tomcat_err ${TOMCAT_HOME}/var/log/tomcat_err.old\n"
                        + "        ${TOMCAT_HOME}/bin/catalina.sh run >&${TOMCAT_HOME}/var/log/tomcat_err &\n"
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
                        + "fi\n"
                );
            } finally {
                out.flush();
                out.close();
            }
            ln("../../.."+tomcatDirectory+"/bin/setclasspath.sh", siteDir+"/bin/setclasspath.sh", uid, gid);

            out=new ChainWriter(new UnixFile(siteDir+"/bin/shutdown.sh").getSecureOutputStream(uid, gid, 0700, true));
            try {
                out.print("#!/bin/sh\n"
                        + "exec ").print(siteDir).print("/bin/tomcat stop\n");
            } finally {
                out.flush();
                out.close();
            }

            out=new ChainWriter(new UnixFile(siteDir+"/bin/startup.sh").getSecureOutputStream(uid, gid, 0700, true));
            try {
                out.print("#!/bin/sh\n"
                        + "exec ").print(siteDir).print("/bin/tomcat start\n");
            } finally {
                out.flush();
                out.close();
            }

            ln("../../.."+tomcatDirectory+"/bin/tomcat-juli.jar", siteDir+"/bin/tomcat-juli.jar", uid, gid);
            ln("../../.."+tomcatDirectory+"/bin/tool-wrapper.sh", siteDir+"/bin/tool-wrapper.sh", uid, gid);
            ln("../../.."+tomcatDirectory+"/bin/version.sh", siteDir+"/bin/version.sh", uid, gid);
            mkdir(siteDir+"/common", 0775, lsa, lsg);
            mkdir(siteDir+"/common/classes", 0775, lsa, lsg);
            mkdir(siteDir+"/common/endorsed", 0775, lsa, lsg);
            lnAll("../../../.."+tomcatDirectory+"/common/endorsed/", siteDir+"/common/endorsed/", uid, gid);
            mkdir(siteDir+"/common/i18n", 0775, lsa, lsg);
            lnAll("../../../.."+tomcatDirectory+"/common/i18n/", siteDir+"/common/i18n/", uid, gid);
            mkdir(siteDir+"/common/lib", 0775, lsa, lsg);
            lnAll("../../../.."+tomcatDirectory+"/common/lib/", siteDir+"/common/lib/", uid, gid);

            if(postgresServerMinorVersion!=null) {
                if(osv==OperatingSystemVersion.MANDRIVA_2006_0_I586) {
                    ln("../../../../usr/postgresql/"+postgresServerMinorVersion+"/share/java/postgresql.jar", siteDir+"/common/lib/postgresql.jar", uid, gid);
                } else if(osv==OperatingSystemVersion.REDHAT_ES_4_X86_64) {
                    ln("../../../../opt/postgresql-"+postgresServerMinorVersion+"/share/java/postgresql.jar", siteDir+"/common/lib/postgresql.jar", uid, gid);
                } else throw new SQLException("Unsupported OperatingSystemVersion: "+osv);
            }
            if(osv==OperatingSystemVersion.MANDRIVA_2006_0_I586) {
                ln("../../../../usr/mysql-connector-java/3.1.12/mysql-connector-java-3.1.12-bin.jar", siteDir+"/common/lib/mysql-connector-java-3.1.12-bin.jar", uid, gid);
            } else if(osv==OperatingSystemVersion.REDHAT_ES_4_X86_64) {
                ln("../../../../opt/mysql-connector-java-3.1.12/mysql-connector-java-3.1.12-bin.jar", siteDir+"/common/lib/mysql-connector-java-3.1.12-bin.jar", uid, gid);
            } else throw new SQLException("Unsupported OperatingSystemVersion: "+osv);

            /*
             * Write the conf/catalina.policy file
             */
            {
                UnixFile cp=new UnixFile(siteDir+"/conf/catalina.policy");
                new UnixFile(tomcatDirectory+"/conf/catalina.policy").copyTo(cp, false);
                cp.chown(uid, gid).setMode(0660);
            }

            {
                UnixFile cp=new UnixFile(siteDir+"/conf/catalina.properties");
                new UnixFile(tomcatDirectory+"/conf/catalina.properties").copyTo(cp, false);
                cp.chown(uid, gid).setMode(0660);
            }

            {
                UnixFile cp=new UnixFile(siteDir+"/conf/context.xml");
                new UnixFile(tomcatDirectory+"/conf/context.xml").copyTo(cp, false);
                cp.chown(uid, gid).setMode(0660);
            }

            {
                UnixFile cp=new UnixFile(siteDir+"/conf/logging.properties");
                new UnixFile(tomcatDirectory+"/conf/logging.properties").copyTo(cp, false);
                cp.chown(uid, gid).setMode(0660);
            }

            /*
             * Create the tomcat-users.xml file
             */
            UnixFile tu=new UnixFile(siteDir+"/conf/tomcat-users.xml");
            new UnixFile(tomcatDirectory+"/conf/tomcat-users.xml").copyTo(tu, false);
            tu.chown(uid, gid).setMode(0660);

            /*
             * Create the web.xml file.
             */
            UnixFile wx=new UnixFile(siteDir+"/conf/web.xml");
            new UnixFile(tomcatDirectory+"/conf/web.xml").copyTo(wx, false);
            wx.chown(uid, gid).setMode(0660);

            mkdir(siteDir+"/server", 0775, lsa, lsg);
            mkdir(siteDir+"/server/classes", 0775, lsa, lsg);
            mkdir(siteDir+"/server/lib", 0775, lsa, lsg);
            lnAll("../../../.."+tomcatDirectory+"/server/lib/", siteDir+"/server/lib/", uid, gid);
            mkdir(siteDir+"/server/webapps", 0775, lsa, lsg);
            mkdir(siteDir+"/shared", 0775, lsa, lsg);
            mkdir(siteDir+"/shared/classes", 0775, lsa, lsg);
            mkdir(siteDir+"/shared/lib", 0775, lsa, lsg);

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
                out.flush();
                out.close();
            }

            createCgiPhpScript(site);
            createTestCGI(site);
            createTestIndex(site);

            /*
             * Create the test.php file.
             */
            String testPHP=siteDir+"/webapps/"+HttpdTomcatContext.ROOT_DOC_BASE+"/test.php";
            new ChainWriter(
                new UnixFile(testPHP).getSecureOutputStream(uid, gid, 0664, false)
            ).print("<?phpinfo()?>\n").flush().close();
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }

    /**
     * Builds a JBoss 2.2.2 installation
     */
    private static void buildHttpdJBossSite_2_2_2(HttpdJBossSite jbossSite) throws IOException, SQLException {
        Profiler.startProfile(Profiler.UNKNOWN, HttpdManager.class, "buildHttpdJBossSite_2_2_2(HttpdJBossSite)", null);
        try {
            /*
             * Resolve and allocate stuff used throughout the method
             */
            final AOServConnector connector=AOServDaemon.getConnector();
            final HttpdTomcatSite tomcatSite = jbossSite.getHttpdTomcatSite();
            final HttpdSite site = tomcatSite.getHttpdSite();
            final LinuxServerAccount lsa = site.getLinuxServerAccount();
            final LinuxServerGroup lsg = site.getLinuxServerGroup();
            final int uid = lsa.getUID().getID();
            final int gid = lsg.getGID().getID();
            final String laUsername=lsa.getLinuxAccount().getUsername().getUsername();
            final String laGroupname = lsg.getLinuxGroup().getName();
            final String siteName=site.getSiteName();
            final String siteDir=HttpdSite.WWW_DIRECTORY+'/'+siteName;
            final String tomcatDirectory=tomcatSite.getHttpdTomcatVersion().getInstallDirectory();
            final String primaryUrl=site.getPrimaryHttpdSiteURL().getHostname();
            final AOServer thisAOServer = AOServDaemon.getThisAOServer();
            final Server server = thisAOServer.getServer();
            final String hostname = server.getHostname();
            final int osv = server.getOperatingSystemVersion().getPkey();
            final PostgresServer postgresServer=thisAOServer.getPreferredPostgresServer();
            final String postgresServerMinorVersion=postgresServer==null?null:postgresServer.getPostgresVersion().getMinorVersion();

            /*
             * Create the skeleton of the site, the directories and links.
             */
            mkdir(siteDir+"/bin", 0770, lsa, lsg);
            ln("webapps/"+HttpdTomcatContext.ROOT_DOC_BASE+"/cgi-bin", siteDir+"/cgi-bin", uid, gid);
            mkdir(siteDir+"/conf", 0775, lsa, lsg);
            mkdir(siteDir+"/daemon", 0770, lsa, lsg);
            ln("webapps/"+HttpdTomcatContext.ROOT_DOC_BASE, siteDir+"/htdocs", uid, gid);
            mkdir(siteDir+"/lib", 0770, lsa, lsg);
            ln("var/log", siteDir+"/logs", uid, gid);
            ln("webapps/"+HttpdTomcatContext.ROOT_DOC_BASE+"/WEB-INF/classes", siteDir+"/servlet", uid, gid);
            mkdir(siteDir+"/var", 0770, lsa, lsg);
            mkdir(siteDir+"/var/log", 0770, lsa, lsg);
            mkdir(siteDir+"/var/run", 0770, lsa, lsg);
            mkdir(siteDir+"/webapps", 0775, lsa, lsg);

            String templateDir = jbossSite.getHttpdJBossVersion().getTemplateDirectory();
            File f = new File(templateDir);
            String[] contents = f.list();
            String[] command = new String[contents.length+3];
            command[0] = "/bin/cp";
            command[1] = "-rdp";
            command[command.length-1] = siteDir;
            for (int i = 0; i < contents.length; i++) command[i+2] = templateDir+"/"+contents[i];
            AOServDaemon.exec(command);
            String[] command2 = {
                "/bin/chown", 
                "-R", 
                laUsername+"."+laGroupname,
                siteDir+"/jboss",
                siteDir+"/bin",
                siteDir+"/lib",
                siteDir+"/daemon"
            };
            AOServDaemon.exec(command2);

            String jbossConfDir = siteDir+"/jboss/conf/tomcat";
            File f2 = new File(jbossConfDir);
            String[] f2contents = f2.list();

            String[] command3 = new String[5];
            command3[0] = "/usr/mysql/4.1/bin/replace";
            command3[3] = "--";
            for (int i = 0; i < 5; i++) {
                for (int j = 0; j < f2contents.length; j++) {
                    switch (i) {
                        case 0: command3[1] = "2222"; command3[2] = String.valueOf(jbossSite.getJnpBind().getPort().getPort()); break;
                        case 1: command3[1] = "3333"; command3[2] = String.valueOf(jbossSite.getWebserverBind().getPort().getPort()); break;
                        case 2: command3[1] = "4444"; command3[2] = String.valueOf(jbossSite.getRmiBind().getPort().getPort()); break;
                        case 3: command3[1] = "5555"; command3[2] = String.valueOf(jbossSite.getHypersonicBind().getPort().getPort()); break;
                        case 4: command3[1] = "6666"; command3[2] = String.valueOf(jbossSite.getJmxBind().getPort().getPort()); break;
                    }
                    command3[4] = jbossConfDir+"/"+f2contents[j];
                    AOServDaemon.exec(command3);
                }
            }
            String[] command4 = {
                "/usr/mysql/4.1/bin/replace",
                "site_name",
                siteName,
                "--",
                siteDir+"/bin/jboss",
                siteDir+"/bin/profile.jboss",
                siteDir+"/bin/profile.user"
            };
            AOServDaemon.exec(command4);
            ln(".", siteDir+"/tomcat", uid, gid);

            mkdir(siteDir+"/webapps/"+HttpdTomcatContext.ROOT_DOC_BASE, 0775, lsa, lsg);
            mkdir(siteDir+"/webapps/"+HttpdTomcatContext.ROOT_DOC_BASE+"/META-INF", 0775, lsa, lsg);
            mkdir(siteDir+"/webapps/"+HttpdTomcatContext.ROOT_DOC_BASE+"/WEB-INF", 0775, lsa, lsg);
            mkdir(siteDir+"/webapps/"+HttpdTomcatContext.ROOT_DOC_BASE+"/WEB-INF/classes", 0770, lsa, lsg);
            mkdir(siteDir+"/webapps/"+HttpdTomcatContext.ROOT_DOC_BASE+"/WEB-INF/cocoon", 0770, lsa, lsg);
            mkdir(siteDir+"/webapps/"+HttpdTomcatContext.ROOT_DOC_BASE+"/WEB-INF/conf", 0770, lsa, lsg);
            mkdir(siteDir+"/webapps/"+HttpdTomcatContext.ROOT_DOC_BASE+"/WEB-INF/lib", 0770, lsa, lsg);	
            mkdir(siteDir+"/webapps/"+HttpdTomcatContext.ROOT_DOC_BASE+"/cgi-bin", 0755, lsa, lsg);
            mkdir(siteDir+"/work", 0750, lsa, lsg);

            /*
             * Set up the bash profile source
             */
            String profileFile=siteDir+"/bin/profile.jboss";
            LinuxAccountManager.setBashProfile(lsa, profileFile);

            /*
             * The classes directory
             */
            mkdir(siteDir+"/classes", 0770, lsa, lsg);

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

            createCgiPhpScript(site);
            createTestCGI(site);
            createTestIndex(site);

            /*
             * Create the test.php file.
             */
            String testPHP=siteDir+"/webapps/"+HttpdTomcatContext.ROOT_DOC_BASE+"/test.php";
            new ChainWriter(
                new UnixFile(testPHP).getSecureOutputStream(uid, gid, 0664, false)
            ).print("<?phpinfo()?>\n").flush().close();
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }

    /**
     * Builds a shared site for Tomcat 3.1
     */
    private static void buildHttpdTomcatSharedSite_3_1(HttpdTomcatSharedSite sharedSite) throws IOException, SQLException {
        Profiler.startProfile(Profiler.UNKNOWN, HttpdManager.class, "buildHttpdTomcatSharedSite_3_1(HttpdTomcatSharedSite)", null);
        try {
            /*
             * We no longer support secure JVMs.
             */
            final HttpdSharedTomcat sharedTomcat = sharedSite.getHttpdSharedTomcat();
            if(sharedTomcat.isSecure()) throw new SQLException("We no longer support secure Multi-Site Tomcat installations: "+sharedTomcat);

            /*
             * Resolve and allocate stuff used throughout the method
             */
            final AOServConnector connector=AOServDaemon.getConnector();
            final HttpdTomcatSite tomcatSite = sharedSite.getHttpdTomcatSite();
            final HttpdSite site = tomcatSite.getHttpdSite();
            final LinuxServerAccount lsa = site.getLinuxServerAccount();
            final LinuxServerGroup lsg = site.getLinuxServerGroup();
            final int uid = lsa.getUID().getID();
            final int gid = lsg.getGID().getID();
            final String laUsername=lsa.getLinuxAccount().getUsername().getUsername();
            final String laGroupname = lsg.getLinuxGroup().getName();
            final String siteName=site.getSiteName();
            final String siteDir=HttpdSite.WWW_DIRECTORY+'/'+siteName;
            final String tomcatDirectory=tomcatSite.getHttpdTomcatVersion().getInstallDirectory();
            final String primaryUrl=site.getPrimaryHttpdSiteURL().getHostname();
            final AOServer thisAOServer = AOServDaemon.getThisAOServer();
            final Server server = thisAOServer.getServer();
            final String hostname = server.getHostname();
            final int osv = server.getOperatingSystemVersion().getPkey();
            final PostgresServer postgresServer=thisAOServer.getPreferredPostgresServer();
            final String postgresServerMinorVersion=postgresServer==null?null:postgresServer.getPostgresVersion().getMinorVersion();

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
                        + "</tomcat-users>\n");
            } finally {
                out.flush();
                out.close();
            }

            /*
             * Create the web.dtd file.
             */
            copyResource("web.dtd-3.1", siteDir+"/conf/web.dtd", uid, gid, 0660);

            /*
             * Create the web.xml file.
             */
            copyResource("web.xml-3.1", siteDir+"/conf/web.xml", uid, gid, 0660);

            /*
             * Create the empty log files.
             */
            for(int c=0;c<tomcatLogFiles.length;c++) {
                String filename=siteDir+"/var/log/"+tomcatLogFiles[c];
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

            createCgiPhpScript(site);
            createTestCGI(site);
            createTestIndex(site);

            /*
             * Create the test.php file.
             */
            String testPHP=siteDir+"/webapps/"+HttpdTomcatContext.ROOT_DOC_BASE+"/test.php";
            new ChainWriter(
                new UnixFile(testPHP).getSecureOutputStream(uid, gid, 0664, false)
            ).print("<?phpinfo()?>\n").flush().close();
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }

    /**
     * Builds a shared site for Tomcat 3.2.4
     */
    private static void buildHttpdTomcatSharedSite_3_2_4(HttpdTomcatSharedSite sharedSite) throws IOException, SQLException {
        Profiler.startProfile(Profiler.UNKNOWN, HttpdManager.class, "buildHttpdTomcatSharedSite_3_2_4(HttpdTomcatSharedSite)", null);
        try {
            /*
             * We no longer support secure JVMs.
             */
            final HttpdSharedTomcat sharedTomcat = sharedSite.getHttpdSharedTomcat();
            if(sharedTomcat.isSecure()) throw new SQLException("We no longer support secure Multi-Site Tomcat installations: "+sharedTomcat);

            /*
             * Resolve and allocate stuff used throughout the method
             */
            final AOServConnector connector=AOServDaemon.getConnector();
            final HttpdTomcatSite tomcatSite = sharedSite.getHttpdTomcatSite();
            final HttpdSite site = tomcatSite.getHttpdSite();
            final LinuxServerAccount lsa = site.getLinuxServerAccount();
            final LinuxServerGroup lsg = site.getLinuxServerGroup();
            final int uid = lsa.getUID().getID();
            final int gid = lsg.getGID().getID();
            final String laUsername=lsa.getLinuxAccount().getUsername().getUsername();
            final String laGroupname = lsg.getLinuxGroup().getName();
            final String siteName=site.getSiteName();
            final String siteDir=HttpdSite.WWW_DIRECTORY+'/'+siteName;
            final String tomcatDirectory=tomcatSite.getHttpdTomcatVersion().getInstallDirectory();
            final String primaryUrl=site.getPrimaryHttpdSiteURL().getHostname();
            final AOServer thisAOServer = AOServDaemon.getThisAOServer();
            final Server server = thisAOServer.getServer();
            final String hostname = server.getHostname();
            final int osv = server.getOperatingSystemVersion().getPkey();
            final PostgresServer postgresServer=thisAOServer.getPreferredPostgresServer();
            final String postgresServerMinorVersion=postgresServer==null?null:postgresServer.getPostgresVersion().getMinorVersion();

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

            createCgiPhpScript(site);
            createTestCGI(site);
            createTestIndex(site);

            /*
             * Create the test.php file.
             */
            String testPHP=siteDir+"/webapps/"+HttpdTomcatContext.ROOT_DOC_BASE+"/test.php";
            new ChainWriter(
                new UnixFile(testPHP).getSecureOutputStream(uid, gid, 0664, false)
            ).print("<?phpinfo()?>\n").flush().close();
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }
    
    /**
     * Builds a shared site for Tomcat 4.1.X
     */
    private static void buildHttpdTomcatSharedSite_4_1_X(HttpdTomcatSharedSite sharedSite) throws IOException, SQLException {
        Profiler.startProfile(Profiler.UNKNOWN, HttpdManager.class, "buildHttpdTomcatSharedSite_4_1_X(HttpdTomcatSharedSite,LinuxServerAccount,LinuxServerGroup)", null);
        try {
            /*
             * We no longer support secure JVMs.
             */
            final HttpdSharedTomcat sharedTomcat = sharedSite.getHttpdSharedTomcat();
            if(sharedTomcat.isSecure()) throw new SQLException("We no longer support secure Multi-Site Tomcat installations: "+sharedTomcat);

            /*
             * Resolve and allocate stuff used throughout the method
             */
            final AOServConnector connector=AOServDaemon.getConnector();
            final HttpdTomcatSite tomcatSite = sharedSite.getHttpdTomcatSite();
            final HttpdSite site = tomcatSite.getHttpdSite();
            final LinuxServerAccount lsa = site.getLinuxServerAccount();
            final LinuxServerGroup lsg = site.getLinuxServerGroup();
            final int uid = lsa.getUID().getID();
            final int gid = lsg.getGID().getID();
            final String laUsername=lsa.getLinuxAccount().getUsername().getUsername();
            final String laGroupname = lsg.getLinuxGroup().getName();
            final String siteName=site.getSiteName();
            final String siteDir=HttpdSite.WWW_DIRECTORY+'/'+siteName;
            final String tomcatDirectory=tomcatSite.getHttpdTomcatVersion().getInstallDirectory();
            final String primaryUrl=site.getPrimaryHttpdSiteURL().getHostname();
            final AOServer thisAOServer = AOServDaemon.getThisAOServer();
            final Server server = thisAOServer.getServer();
            final String hostname = server.getHostname();
            final int osv = server.getOperatingSystemVersion().getPkey();
            final PostgresServer postgresServer=thisAOServer.getPreferredPostgresServer();
            final String postgresServerMinorVersion=postgresServer==null?null:postgresServer.getPostgresVersion().getMinorVersion();

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

            createCgiPhpScript(site);
            createTestCGI(site);
            createTestIndex(site);

            /*
             * Create the test.php file.
             */
            String testPHP=siteDir+"/webapps/"+HttpdTomcatContext.ROOT_DOC_BASE+"/test.php";
            new ChainWriter(
                new UnixFile(testPHP).getSecureOutputStream(uid, gid, 0664, false)
            ).print("<?phpinfo()?>\n").flush().close();
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }

    /**
     * Builds a shared site for Tomcat 5.5.X
     */
    private static void buildHttpdTomcatSharedSite_5_5_X(HttpdTomcatSharedSite sharedSite) throws IOException, SQLException {
        Profiler.startProfile(Profiler.UNKNOWN, HttpdManager.class, "buildHttpdTomcatSharedSite_5_5_X(HttpdTomcatSharedSite)", null);
        try {
            /*
             * We no longer support secure JVMs.
             */
            final HttpdSharedTomcat sharedTomcat = sharedSite.getHttpdSharedTomcat();
            if(sharedTomcat.isSecure()) throw new SQLException("We no longer support secure Multi-Site Tomcat installations: "+sharedTomcat);

            /*
             * Resolve and allocate stuff used throughout the method
             */
            final AOServConnector connector=AOServDaemon.getConnector();
            final HttpdTomcatSite tomcatSite = sharedSite.getHttpdTomcatSite();
            final HttpdSite site = tomcatSite.getHttpdSite();
            final LinuxServerAccount lsa = site.getLinuxServerAccount();
            final LinuxServerGroup lsg = site.getLinuxServerGroup();
            final int uid = lsa.getUID().getID();
            final int gid = lsg.getGID().getID();
            final String laUsername=lsa.getLinuxAccount().getUsername().getUsername();
            final String laGroupname = lsg.getLinuxGroup().getName();
            final String siteName=site.getSiteName();
            final String siteDir=HttpdSite.WWW_DIRECTORY+'/'+siteName;
            final String tomcatDirectory=tomcatSite.getHttpdTomcatVersion().getInstallDirectory();
            final String primaryUrl=site.getPrimaryHttpdSiteURL().getHostname();
            final AOServer thisAOServer = AOServDaemon.getThisAOServer();
            final Server server = thisAOServer.getServer();
            final String hostname = server.getHostname();
            final int osv = server.getOperatingSystemVersion().getPkey();
            final PostgresServer postgresServer=thisAOServer.getPreferredPostgresServer();
            final String postgresServerMinorVersion=postgresServer==null?null:postgresServer.getPostgresVersion().getMinorVersion();

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
                out.flush();
                out.close();
            }

            createCgiPhpScript(site);
            createTestCGI(site);
            createTestIndex(site);

            /*
             * Create the test.php file.
             */
            String testPHP=siteDir+"/webapps/"+HttpdTomcatContext.ROOT_DOC_BASE+"/test.php";
            new ChainWriter(
                new UnixFile(testPHP).getSecureOutputStream(uid, gid, 0664, false)
            ).print("<?phpinfo()?>\n").flush().close();
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }

    private static final void copyResource(String resource, OutputStream out) throws IOException {
        Profiler.startProfile(Profiler.UNKNOWN, HttpdManager.class, "copyResource(String,OutputStream)", null);
        try {
            InputStream in=HttpdManager.class.getResourceAsStream(resource);
	    try {
		if(in==null) throw new IOException("Unable to find resource: "+resource);
		byte[] buff=BufferManager.getBytes();
		try {
		    int ret;
		    while((ret=in.read(buff, 0, BufferManager.BUFFER_SIZE))!=-1) out.write(buff, 0, ret);
		} finally {
		    BufferManager.release(buff);
		}
	    } finally {
		in.close();
	    }
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }

    private static final void copyResource(String resource, String filename, int uid, int gid, int mode) throws IOException {
        Profiler.startProfile(Profiler.UNKNOWN, HttpdManager.class, "copyResource(String,String,int,int,int)", null);
        try {
            OutputStream out=new UnixFile(filename).getSecureOutputStream(uid, gid, mode, false);
            try {
                copyResource(resource, out);
            } finally {
		out.flush();
                out.close();
            }
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }

    /**
     * Creates the CGI php script.
     */
    private static void createCgiPhpScript(HttpdSite hs) throws IOException, SQLException {
        int osv = AOServDaemon.getThisAOServer().getServer().getOperatingSystemVersion().getPkey();
        String defaultPhpVersion = getDefaultPhpVersion(osv);
        if(defaultPhpVersion!=null) {
            ChainWriter out=new ChainWriter(
                new UnixFile(
                    "/www/"+hs.getSiteName()+"/webapps/"+HttpdTomcatContext.ROOT_DOC_BASE+"/cgi-bin/php"
                ).getSecureOutputStream(
                    hs.getLinuxServerAccount().getUID().getID(),
                    hs.getLinuxServerGroup().getGID().getID(),
                    0755,
                    false
                )
            );
            try {
                if(osv==OperatingSystemVersion.REDHAT_ES_4_X86_64) {
                    out.print("#!/bin/sh\n"
                              + ". /opt/aoserv-client/scripts/postgresql-"+REDHAT_ES_4_PHP_POSTGRES_MINOR_VERSION+".sh\n"
                              + "exec /opt/php-").print(defaultPhpVersion).print("/bin/php-cgi \"$@\"\n");
                } else if(osv==OperatingSystemVersion.MANDRIVA_2006_0_I586) {
                    out.print("#!/bin/sh\n"
                              + ". /usr/aoserv/etc/postgresql-"+MANDRIVA_2006_0_PHP_POSTGRES_MINOR_VERSION+".sh\n"
                              + "exec /usr/php/").print(defaultPhpVersion).print("/bin/php \"$@\"\n");
                } else throw new SQLException("Unsupported OperatingSystemVersion: "+osv);
            } finally {
                out.flush();
                out.close();
            }
        }
    }

    /**
     * Creates the test CGI script.
     */
    private static void createTestCGI(HttpdSite hs) throws IOException, SQLException {
        ChainWriter out=new ChainWriter(
            new BufferedOutputStream(
                new UnixFile(
                    "/www/"+hs.getSiteName()+"/webapps/"+HttpdTomcatContext.ROOT_DOC_BASE+"/cgi-bin/test"
                ).getSecureOutputStream(
                    hs.getLinuxServerAccount().getUID().getID(),
                    hs.getLinuxServerGroup().getGID().getID(),
                    0755,
                    false
                )
            )
        );
        try {
            out.print("#!/usr/bin/perl\n"
                    + "print \"Content-type: text/html\\n\";\n"
                    + "print \"\\n\";\n"
                    + "print \"<HTML>\\n\";\n"
                    + "print \"  <BODY>\\n\";\n"
                    + "print \"    <H1>Test CGI Script for ").print(hs.getPrimaryHttpdSiteURL().getHostname()).print("</H1>\\n\";\n"
                    + "print \"  </BODY>\\n\";\n"
                    + "print \"</HTML>\\n\";\n"
            );
        } finally {
            out.flush();
            out.close();
        }
    }

    /**
     * Creates the test index.html file.
     */
    private static void createTestIndex(HttpdSite hs) throws IOException, SQLException {
        String primaryUrl = hs.getPrimaryHttpdSiteURL().getHostname();
        ChainWriter out=new ChainWriter(
            new BufferedOutputStream(
                new UnixFile(
                    "/www/"+hs.getSiteName()+"/webapps/"+HttpdTomcatContext.ROOT_DOC_BASE+"/index.html"
                ).getSecureOutputStream(
                    hs.getLinuxServerAccount().getUID().getID(),
                    hs.getLinuxServerGroup().getGID().getID(),
                    0664,
                    false
                )
            )
        );
        try {
            out.print("<HTML>\n"
                    + "  <HEAD><TITLE>Test HTML Page for ").print(primaryUrl).print("</TITLE></HEAD>\n"
                    + "  <BODY>\n"
                    + "    Test HTML Page for ").print(primaryUrl).print("\n"
                    + "  </BODY>\n"
                    + "</HTML>\n"
            );
        } finally {
            out.flush();
            out.close();
        }
    }

    private static final Object rebuildLock=new Object();
    protected void doRebuild() throws IOException, SQLException {
        Profiler.startProfile(Profiler.UNKNOWN, HttpdManager.class, "doRebuild()", null);
        try {
            AOServConnector connector=AOServDaemon.getConnector();
            AOServer aoServer=AOServDaemon.getThisAOServer();

            int osv=aoServer.getServer().getOperatingSystemVersion().getPkey();
            // Currently only Mandriva 2006.0 and RedHat ES4 supported
            if(
                osv!=OperatingSystemVersion.MANDRIVA_2006_0_I586
                && osv!=OperatingSystemVersion.REDHAT_ES_4_X86_64
            ) throw new SQLException("Unsupported OperatingSystemVersion: "+osv);

            final Stat tempStat = new Stat();

            synchronized(rebuildLock) {
                // Get some variables that will be used throughout the method
                List<File> deleteFileList=new ArrayList<File>();
                IntList serversNeedingRestarted=new SortedIntArrayList();
                IntList sharedTomcatsNeedingRestarted=new SortedIntArrayList();
                IntList sitesNeedingRestarted=new SortedIntArrayList();

                doRebuildSharedTomcats(aoServer, deleteFileList, connector, sharedTomcatsNeedingRestarted, tempStat);
                doRebuildHttpdSites(aoServer, deleteFileList, connector, serversNeedingRestarted, sitesNeedingRestarted, sharedTomcatsNeedingRestarted, tempStat);
                doRebuildHttpdServers(aoServer, deleteFileList, connector, serversNeedingRestarted, tempStat);
                disableAndEnableSharedTomcats(aoServer, connector, sharedTomcatsNeedingRestarted, tempStat);
                disableAndEnableHttpdSites(aoServer, connector, sitesNeedingRestarted, tempStat);
                disableAndEnableSiteBinds(aoServer, connector);

                // Control the /etc/logrotate.d and /etc/httpd/conf/logrotate.d files
                doRebuildLogrotate(osv, aoServer, deleteFileList, connector, tempStat);

                // Reload the server configs
                reload(serversNeedingRestarted);

                /*
                 * Back up the files scheduled for removal.
                 */
                int deleteFileListLen=deleteFileList.size();
                if(deleteFileListLen>0) {
                    // Get the next backup filename
                    File backupFile=BackupManager.getNextBackupFile();
                    BackupManager.backupFiles(deleteFileList, backupFile);

                    /*
                     * Remove the files that have been backed up.
                     */
                    for(int c=0;c<deleteFileListLen;c++) {
                        File file=deleteFileList.get(c);
                        new UnixFile(file).secureDeleteRecursive();
                    }
                }
            }
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }

    /**
     * Only called by the internally synchronized <code>doRebuild()</code> method.
     */
    private static void doRebuildHttpdServers(AOServer aoServer, List<File> deleteFileList, AOServConnector conn, IntList serversNeedingRestarted, Stat tempStat) throws IOException, SQLException {
        Profiler.startProfile(Profiler.UNKNOWN, HttpdManager.class, "doRebuildHttpdServers(AOServer,List<File>,AOServConnector,IntList,Stat)", null);
        try {
            for(HttpdServer hs : aoServer.getHttpdServers()) {
                final int serverNum=hs.getNumber();
                final int osv = aoServer.getServer().getOperatingSystemVersion().getPkey();

                /*
                 * Rebuild the httpd.conf file
                 */
                UnixFile httpdConfFileNew=new UnixFile(HTTPD_CONF_BEGINNING+serverNum+HTTPD_CONF_ENDING_NEW);
                ChainWriter out=new ChainWriter(
                    new BufferedOutputStream(
                        httpdConfFileNew.getSecureOutputStream(
                            UnixFile.ROOT_UID,
                            UnixFile.ROOT_GID,
                            0644,
                            true
                        )
                    )
                );
		try {
                    LinuxServerAccount lsa=hs.getLinuxServerAccount();
                    boolean isEnabled=lsa.getDisableLog()==null;
                    // The version of PHP module to run
                    TechnologyVersion phpVersion=hs.getModPhpVersion();
                    if(osv==OperatingSystemVersion.MANDRIVA_2006_0_I586) {
                        out.print("ServerRoot \"/etc/httpd\"\n"
                                + "Include conf/modules_conf/core\n"
                                + "PidFile /var/run/httpd").print(serverNum).print(".pid\n"
                                + "Timeout ").print(hs.getTimeOut()).print("\n"
                                + "CoreDumpDirectory /var/log/httpd").print(serverNum).print("\n"
                                + "LockFile /var/log/httpd").print(serverNum).print("/accept.lock\n"
                                + "\n"
                                + "Include conf/modules_conf/prefork\n"
                                + "Include conf/modules_conf/worker\n"
                                + "\n"
                                + "LoadModule access_module modules/mod_access.so\n"
                                + "LoadModule auth_module modules/mod_auth.so\n"
                                + "# LoadModule auth_anon_module modules/mod_auth_anon.so\n"
                                + "# LoadModule auth_dbm_module modules/mod_auth_dbm.so\n"
                                + "# LoadModule auth_digest_module modules/mod_auth_digest.so\n"
                                + "# LoadModule file_cache_module modules/mod_file_cache.so\n"
                                + "# LoadModule charset_lite_module modules/mod_charset_lite.so\n"
                                + "# LoadModule cache_module modules/mod_cache.so\n"
                                + "# LoadModule disk_cache_module modules/mod_disk_cache.so\n"
                                + "# LoadModule mem_cache_module modules/mod_mem_cache.so\n"
                                + "# LoadModule case_filter_module modules/mod_case_filter.so\n"
                                + "# LoadModule case_filter_in_module modules/mod_case_filter_in.so\n"
                                + "# LoadModule dumpio_module modules/mod_dumpio.so\n"
                                + "# LoadModule ldap_module modules/mod_ldap.so\n"
                                + "# LoadModule auth_ldap_module modules/mod_auth_ldap.so\n"
                                + "# LoadModule ext_filter_module modules/mod_ext_filter.so\n"
                                + "LoadModule include_module modules/mod_include.so\n"
                                + "LoadModule deflate_module modules/mod_deflate.so\n"
                                + "LoadModule log_config_module modules/mod_log_config.so\n"
                                + "# LoadModule log_forensic_module modules/mod_log_forensic.so\n"
                                + "# LoadModule logio_module modules/mod_logio.so\n"
                                + "LoadModule env_module modules/mod_env.so\n"
                                + "LoadModule mime_magic_module modules/mod_mime_magic.so\n"
                                + "# LoadModule cern_meta_module modules/mod_cern_meta.so\n"
                                + "LoadModule expires_module modules/mod_expires.so\n"
                                + "LoadModule headers_module modules/mod_headers.so\n"
                                + "# LoadModule usertrack_module modules/mod_usertrack.so\n"
                                + "# LoadModule unique_id_module modules/mod_unique_id.so\n"
                                + "LoadModule setenvif_module modules/mod_setenvif.so\n"
                                + "LoadModule proxy_module modules/mod_proxy.so\n"
                                + "# LoadModule proxy_connect_module modules/mod_proxy_connect.so\n"
                                + "# LoadModule proxy_ftp_module modules/mod_proxy_ftp.so\n"
                                + "LoadModule proxy_http_module modules/mod_proxy_http.so\n"
                                + "LoadModule mime_module modules/mod_mime.so\n"
                                + "# LoadModule dav_module modules/mod_dav.so\n"
                                + "# LoadModule status_module modules/mod_status.so\n"
                                + "LoadModule autoindex_module modules/mod_autoindex.so\n"
                                + "LoadModule asis_module modules/mod_asis.so\n"
                                + "# LoadModule info_module modules/mod_info.so\n"
                                + "LoadModule cgi_module modules/mod_cgi.so\n"
                                + "# LoadModule cgid_module modules/mod_cgid.so\n"
                                + "# LoadModule dav_fs_module modules/mod_dav_fs.so\n"
                                + "# LoadModule vhost_alias_module modules/mod_vhost_alias.so\n"
                                + "LoadModule negotiation_module modules/mod_negotiation.so\n"
                                + "LoadModule dir_module modules/mod_dir.so\n"
                                + "LoadModule imap_module modules/mod_imap.so\n"
                                + "LoadModule actions_module modules/mod_actions.so\n"
                                + "# LoadModule speling_module modules/mod_speling.so\n"
                                + "# LoadModule userdir_module modules/mod_userdir.so\n"
                                + "LoadModule alias_module modules/mod_alias.so\n"
                                + "LoadModule rewrite_module modules/mod_rewrite.so\n"
                                + "LoadModule jk_module modules/mod_jk-apache-2.0.49-linux-i686.so\n"
                                + "LoadModule ssl_module extramodules/mod_ssl.so\n");
                        if(hs.useSuexec()) out.print("LoadModule suexec_module extramodules/mod_suexec.so\n");
                        if(isEnabled && phpVersion!=null) {
                            String version = phpVersion.getVersion();
                            out.print("\n"
                                    + "# Enable mod_php\n"
                                    + "LoadModule php").print(version.charAt(0)).print("_module /usr/php/").print(getMajorPhpVersion(version)).print("/lib/apache/").print(getPhpLib(phpVersion)).print("\n"
                                    + "AddType application/x-httpd-php .php4 .php3 .phtml .php\n"
                                    + "AddType application/x-httpd-php-source .phps\n");
                        }
                        out.print("\n"
                                + "Include conf/modules_conf/mod_log_config\n"
                                + "Include conf/modules_conf/mod_mime_magic\n"
                                + "Include conf/modules_conf/mod_setenvif\n"
                                + "Include conf/modules_conf/mod_proxy\n"
                                + "Include conf/modules_conf/mod_mime\n"
                                + "Include conf/modules_conf/mod_dav\n"
                                + "Include conf/modules_conf/mod_status\n"
                                + "Include conf/modules_conf/mod_autoindex\n"
                                + "Include conf/modules_conf/mod_negotiation\n"
                                + "Include conf/modules_conf/mod_dir\n"
                                + "Include conf/modules_conf/mod_userdir\n"
                                + "Include conf/modules_conf/mod_ssl\n"
                                + "Include conf/modules_conf/mod_jk\n"
                                + "\n"
                                + "SSLSessionCache shmcb:/var/cache/httpd/mod_ssl/ssl_scache").print(serverNum).print("(512000)\n"
                                + "\n");
                        // Use apache if the account is disabled
                        if(isEnabled) {
                            out.print("User ").print(lsa.getLinuxAccount().getUsername().getUsername()).print("\n"
                                    + "Group ").print(hs.getLinuxServerGroup().getLinuxGroup().getName()).print("\n");
                        } else {
                            out.print("User "+LinuxAccount.APACHE+"\n"
                                    + "Group "+LinuxGroup.APACHE+"\n");
                        }
                        out.print("\n"
                                + "ServerName ").print(hs.getAOServer().getServer().getHostname()).print("\n"
                                + "\n"
                                + "ErrorLog /var/log/httpd").print(serverNum).print("/error_log\n"
                                + "CustomLog /var/log/httpd").print(serverNum).print("/access_log combined\n"
                                + "\n"
                                + "<IfModule mod_dav_fs.c>\n"
                                + "    DAVLockDB /var/lib/dav").print(serverNum).print("/lockdb\n"
                                + "</IfModule>\n"
                                + "\n"
                                + "<IfModule mod_jk.c>\n"
                                + "    JkWorkersFile /etc/httpd/conf/workers").print(serverNum).print(".properties\n"
                                + "    JkLogFile /var/log/httpd").print(serverNum).print("/mod_jk.log\n"
                                + "</IfModule>\n"
                                + "\n"
                                + "Include conf/fileprotector.conf\n"
                                + "\n"
                        );
                    } else if(osv==OperatingSystemVersion.REDHAT_ES_4_X86_64) {
                        out.print("ServerRoot \"/etc/httpd\"\n"
                                + "Include conf/modules_conf/core\n"
                                + "PidFile /var/run/httpd").print(serverNum).print(".pid\n"
                                + "Timeout ").print(hs.getTimeOut()).print("\n"
                                + "CoreDumpDirectory /var/log/httpd").print(serverNum).print("\n"
                                + "LockFile /var/log/httpd").print(serverNum).print("/accept.lock\n"
                                + "\n"
                                + "Include conf/modules_conf/prefork\n"
                                + "Include conf/modules_conf/worker\n"
                                + "\n"
                                + "LoadModule access_module modules/mod_access.so\n"
                                + "LoadModule auth_module modules/mod_auth.so\n"
                                + "# LoadModule auth_anon_module modules/mod_auth_anon.so\n"
                                + "# LoadModule auth_dbm_module modules/mod_auth_dbm.so\n"
                                + "# LoadModule auth_digest_module modules/mod_auth_digest.so\n"
                                + "# LoadModule ldap_module modules/mod_ldap.so\n"
                                + "# LoadModule auth_ldap_module modules/mod_auth_ldap.so\n"
                                + "LoadModule include_module modules/mod_include.so\n"
                                + "LoadModule log_config_module modules/mod_log_config.so\n"
                                + "LoadModule env_module modules/mod_env.so\n"
                                + "LoadModule mime_magic_module modules/mod_mime_magic.so\n"
                                + "# LoadModule cern_meta_module modules/mod_cern_meta.so\n"
                                + "LoadModule expires_module modules/mod_expires.so\n"
                                + "LoadModule deflate_module modules/mod_deflate.so\n"
                                + "LoadModule headers_module modules/mod_headers.so\n"
                                + "# LoadModule usertrack_module modules/mod_usertrack.so\n"
                                + "LoadModule setenvif_module modules/mod_setenvif.so\n"
                                + "LoadModule mime_module modules/mod_mime.so\n"
                                + "# LoadModule dav_module modules/mod_dav.so\n"
                                + "# LoadModule status_module modules/mod_status.so\n"
                                + "LoadModule autoindex_module modules/mod_autoindex.so\n"
                                + "LoadModule asis_module modules/mod_asis.so\n"
                                + "# LoadModule info_module modules/mod_info.so\n"
                                + "# LoadModule dav_fs_module modules/mod_dav_fs.so\n"
                                + "# LoadModule vhost_alias_module modules/mod_vhost_alias.so\n"
                                + "LoadModule negotiation_module modules/mod_negotiation.so\n"
                                + "LoadModule dir_module modules/mod_dir.so\n"
                                + "LoadModule imap_module modules/mod_imap.so\n"
                                + "LoadModule actions_module modules/mod_actions.so\n"
                                + "# LoadModule speling_module modules/mod_speling.so\n"
                                + "# LoadModule userdir_module modules/mod_userdir.so\n"
                                + "LoadModule alias_module modules/mod_alias.so\n"
                                + "LoadModule rewrite_module modules/mod_rewrite.so\n"
                                + "LoadModule proxy_module modules/mod_proxy.so\n"
                                + "# LoadModule proxy_ftp_module modules/mod_proxy_ftp.so\n"
                                + "LoadModule proxy_http_module modules/mod_proxy_http.so\n"
                                + "# LoadModule proxy_connect_module modules/mod_proxy_connect.so\n"
                                + "# LoadModule cache_module modules/mod_cache.so\n");
                        if(hs.useSuexec()) out.print("LoadModule suexec_module modules/mod_suexec.so\n");
                        if(isEnabled && phpVersion!=null) {
                            String version = phpVersion.getVersion();
                            out.print("\n"
                                    + "# Enable mod_php\n"
                                    + "LoadModule php").print(version.charAt(0)).print("_module /opt/php-").print(getMajorPhpVersion(version)).print("/lib/apache/").print(getPhpLib(phpVersion)).print("\n"
                                    + "AddType application/x-httpd-php .php4 .php3 .phtml .php\n"
                                    + "AddType application/x-httpd-php-source .phps\n");
                        }
                        out.print("# LoadModule disk_cache_module modules/mod_disk_cache.so\n"
                                + "# LoadModule file_cache_module modules/mod_file_cache.so\n"
                                + "# LoadModule mem_cache_module modules/mod_mem_cache.so\n"
                                + "LoadModule cgi_module modules/mod_cgi.so\n"
                                + "LoadModule ssl_module modules/mod_ssl.so\n"
                                + "LoadModule jk_module modules/mod_jk-apache-2.0.52-linux-x86_64.so\n"
                                + "\n"
                                + "Include conf/modules_conf/mod_log_config\n"
                                + "Include conf/modules_conf/mod_mime_magic\n"
                                + "Include conf/modules_conf/mod_setenvif\n"
                                + "Include conf/modules_conf/mod_mime\n"
                                + "Include conf/modules_conf/mod_status\n"
                                + "Include conf/modules_conf/mod_autoindex\n"
                                + "Include conf/modules_conf/mod_negotiation\n"
                                + "Include conf/modules_conf/mod_dir\n"
                                + "Include conf/modules_conf/mod_userdir\n"
                                + "Include conf/modules_conf/mod_proxy\n"
                                + "Include conf/modules_conf/mod_ssl\n"
                                + "Include conf/modules_conf/mod_jk\n"
                                + "\n"
                                + "SSLSessionCache shmcb:/var/cache/mod_ssl/scache").print(serverNum).print("(512000)\n"
                                + "\n");
                        // Use apache if the account is disabled
                        if(isEnabled) {
                            out.print("User ").print(lsa.getLinuxAccount().getUsername().getUsername()).print("\n"
                                    + "Group ").print(hs.getLinuxServerGroup().getLinuxGroup().getName()).print("\n");
                        } else {
                            out.print("User "+LinuxAccount.APACHE+"\n"
                                    + "Group "+LinuxGroup.APACHE+"\n");
                        }
                        out.print("\n"
                                + "ServerName ").print(hs.getAOServer().getServer().getHostname()).print("\n"
                                + "\n"
                                + "ErrorLog /var/log/httpd").print(serverNum).print("/error_log\n"
                                + "CustomLog /var/log/httpd").print(serverNum).print("/access_log combined\n"
                                + "\n"
                                + "<IfModule mod_dav_fs.c>\n"
                                + "    DAVLockDB /var/lib/dav").print(serverNum).print("/lockdb\n"
                                + "</IfModule>\n"
                                + "\n"
                                + "<IfModule mod_jk.c>\n"
                                + "    JkWorkersFile /etc/httpd/conf/workers").print(serverNum).print(".properties\n"
                                + "    JkLogFile /var/log/httpd").print(serverNum).print("/mod_jk.log\n"
                                + "</IfModule>\n"
                                + "\n"
                        );
                    } else throw new SQLException("Unsupported OperatingSystemVersion: "+osv);

                    // List of binds
                    for(HttpdBind hb : hs.getHttpdBinds()) {
                        NetBind nb=hb.getNetBind();
                        String ip=nb.getIPAddress().getIPAddress();
                        int port=nb.getPort().getPort();
                        out.print("Listen ").print(ip).print(':').print(port).print("\n"
                                + "NameVirtualHost ").print(ip).print(':').print(port).print('\n');
                    }

                    // The list of sites to include
                    List<HttpdSite> sites=hs.getHttpdSites();
                    for(int d=0;d<2;d++) {
                        boolean listFirst=d==0;
                        out.print("\n");
                        for(HttpdSite site : sites) {
                            if(site.listFirst()==listFirst) {
                                for(HttpdSiteBind bind : site.getHttpdSiteBinds(hs)) {
                                    NetBind nb=bind.getHttpdBind().getNetBind();
                                    String ipAddress=nb.getIPAddress().getIPAddress();
                                    int port=nb.getPort().getPort();
                                    out.print("Include conf/hosts/").print(site.getSiteName()).print('_').print(ipAddress).print('_').print(port).print('\n');
                                }
                            }
                        }
                    }
		} finally {
		    out.flush();
		    out.close();
		}

                /*
                 * Rebuild the workers.properties file
                 */
                List<HttpdWorker> workers=hs.getHttpdWorkers();
                int workerCount=workers.size();

                UnixFile workersFileNew=new UnixFile(WORKERS_BEGINNING+serverNum+WORKERS_ENDING_NEW);
                out=new ChainWriter(
                    new BufferedOutputStream(
                        workersFileNew.getSecureOutputStream(
                            UnixFile.ROOT_UID,
                            UnixFile.ROOT_GID,
                            0644,
                            false
                        )
                    )
                );
		try {
		    out.print("worker.list=");
		    for(int d=0;d<workerCount;d++) {
			if(d>0) out.print(',');
			out.print(workers.get(d).getCode().getCode());
		    }
		    out.print('\n');
		    for(int d=0;d<workerCount;d++) {
			HttpdWorker worker=workers.get(d);
			String code=worker.getCode().getCode();
			out.print("\n"
                                + "worker.").print(code).print(".type=").print(worker.getHttpdJKProtocol(conn).getProtocol(conn).getProtocol()).print("\n"
                                + "worker.").print(code).print(".port=").print(worker.getNetBind().getPort().getPort()).print('\n');
		    }
		} finally {
		    out.flush();
		    out.close();
		}

                /*
                 * Move the new files into place
                 */
                UnixFile httpdConfFile=new UnixFile(HTTPD_CONF_BEGINNING+serverNum+HTTPD_CONF_ENDING);
                boolean httpdConfFileExists=httpdConfFile.getStat(tempStat).exists();
                UnixFile httpdConfFileOld=new UnixFile(HTTPD_CONF_BEGINNING+serverNum+HTTPD_CONF_ENDING_OLD);
                if(!httpdConfFileExists || !httpdConfFile.contentEquals(httpdConfFileNew)) {
                    int hsPKey=hs.getPkey();
                    if(!serversNeedingRestarted.contains(hsPKey)) serversNeedingRestarted.add(hsPKey);
                    if(httpdConfFileExists) httpdConfFile.renameTo(httpdConfFileOld);
                    httpdConfFileNew.renameTo(httpdConfFile);
                } else httpdConfFileNew.delete();

                UnixFile workersFile=new UnixFile(WORKERS_BEGINNING+serverNum+WORKERS_ENDING);
                boolean workersFileExists=workersFile.getStat(tempStat).exists();
                UnixFile workersFileOld=new UnixFile(WORKERS_BEGINNING+serverNum+WORKERS_ENDING_OLD);
                if(!workersFileExists || !workersFile.contentEquals(workersFileNew)) {
                    int hsPKey=hs.getPkey();
                    if(!serversNeedingRestarted.contains(hsPKey)) serversNeedingRestarted.add(hsPKey);
                    if(workersFileExists) workersFile.renameTo(workersFileOld);
                    workersFileNew.renameTo(workersFile);
                } else workersFileNew.delete();
            }
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }

    /**
     * Only called by the internally synchronized <code>doRebuild()</code> method.
     */
    private static void doRebuildHttpdSites(
        AOServer aoServer,
        List<File> deleteFileList,
        AOServConnector conn,
        IntList serversNeedingRestarted,
        IntList sitesNeedingRestarted,
        IntList sharedTomcatsNeedingRestarted,
        Stat tempStat
    ) throws IOException, SQLException {
        Profiler.startProfile(Profiler.UNKNOWN, HttpdManager.class, "doRebuildHttpdSites(AOServer,List<File>,AOServConnector,IntList,IntList,IntList,Stat)", null);
        try {
            int osv=aoServer.getServer().getOperatingSystemVersion().getPkey();

            /*
             * Get values used in the rest of the method.
             */
            final String username = (osv==OperatingSystemVersion.REDHAT_ES_4_X86_64 || osv==OperatingSystemVersion.MANDRIVA_2006_0_I586) ? LinuxAccount.APACHE : LinuxAccount.HTTPD;

            Username httpdUsername=conn.usernames.get(username);
            if(httpdUsername==null) throw new SQLException("Unable to find Username: "+username);
            LinuxAccount httpdLA=httpdUsername.getLinuxAccount();
            if(httpdLA==null) throw new SQLException("Unable to find LinuxAccount: "+httpdUsername);
            LinuxServerAccount httpdLSA=httpdLA.getLinuxServerAccount(aoServer);
            if(httpdLSA==null) throw new SQLException("Unable to find LinuxServerAccount: "+httpdLA+" on "+aoServer.getServer().getHostname());
            int httpdUID=httpdLSA.getUID().getID();
            LinuxServerAccount awstatsLSA=aoServer.getLinuxServerAccount(LinuxAccount.AWSTATS);
            if(awstatsLSA==null) throw new SQLException("Unable to find LinuxServerAccount: "+LinuxAccount.AWSTATS+" on "+aoServer.getServer().getHostname());
            int awstatsUID=awstatsLSA.getUID().getID();

            // Iterate through each site
            List<HttpdSite> sites=aoServer.getHttpdSites();

            // The log directories that exist but are not used will be removed
            String[] list=new File(LOG_DIR).list();
            List<String> logDirectories=new SortedArrayList<String>(list.length);
            for(int c=0;c<list.length;c++) {
                String dirname=list[c];
                if(
                    !dirname.equals("lost+found")
                    && !dirname.equals(".backup")
                ) logDirectories.add(dirname);
            }

            // The www directories that exist but are not used will be removed
            list=new File(HttpdSite.WWW_DIRECTORY).list();
            List<String> wwwDirectories=new SortedArrayList<String>(list.length);
            for(int c=0;c<list.length;c++) {
                String dirname=list[c];
                if(
                    !dirname.equals("lost+found")
                    && !dirname.equals(".backup")
                    && !dirname.equals("aquota.user")
                ) {
                    wwwDirectories.add(dirname);
                }
            }

            // The config directory should only contain files referenced in the database
            list=new File(CONF_HOSTS).list();
            List<String> hostConfFiles=new SortedArrayList<String>(list.length);
            for(int c=0;c<list.length;c++) hostConfFiles.add(list[c]);

            // Each site gets its own shared FTP space
            list=new File(FTPManager.SHARED_FTP_DIRECTORY).list();
            List<String> ftpDirectories=new SortedArrayList<String>(list.length);
            for(int c=0;c<list.length;c++) ftpDirectories.add(list[c]);

            int len=sites.size();
            for(int c=0;c<len;c++) {
                /*
                 * Get values used in the rest of the loop.
                 */
                HttpdSite site=sites.get(c);
                List<HttpdSiteBind> binds=site.getHttpdSiteBinds();
                final String siteName=site.getSiteName();
                String siteDir=HttpdSite.WWW_DIRECTORY+'/'+siteName;

                LinuxServerAccount lsa=site.getLinuxServerAccount();
                int lsaUID=lsa.getUID().getID();

                LinuxServerGroup lsg=site.getLinuxServerGroup();
                int lsgGID=lsg.getGID().getID();

                /*
                 * Take care of the log files and their rotation.
                 */
                UnixFile uf=new UnixFile(LOG_DIR+'/'+siteName);
                uf.getStat(tempStat);
                if(!tempStat.exists()) {
                    uf.mkdir();
                    uf.getStat(tempStat);
                }
                if(tempStat.getUID()!=awstatsUID || tempStat.getGID()!=lsgGID) uf.chown(awstatsUID, lsgGID);
                if(tempStat.getMode()!=0750) uf.setMode(0750);

                // Make sure the newly created or existing log directories are not removed
                logDirectories.remove(siteName);

                /*
                 * Create and fill in the directory if it does not exist or is owned by root.
                 */
                HttpdTomcatSite tomcatSite=site.getHttpdTomcatSite();
                UnixFile wwwDirUF=new UnixFile(siteDir);
                wwwDirUF.getStat(tempStat);
                if(!tempStat.exists() || tempStat.getUID()==UnixFile.ROOT_GID) {
                    HttpdStaticSite staticSite=site.getHttpdStaticSite();

                    if(!tempStat.exists()) wwwDirUF.mkdir();
                    wwwDirUF.setMode(
                        (
                            tomcatSite!=null
                            && tomcatSite.getHttpdTomcatSharedSite()!=null
                            && !tomcatSite.getHttpdTomcatVersion().isTomcat4(conn)
                            && tomcatSite.getHttpdTomcatSharedSite().getHttpdSharedTomcat().isSecure()
                        ) ? 01770 : 0770
                    );

                    boolean isCreated;
                    if(staticSite!=null) {
                        buildStaticSite(staticSite, lsa, lsg);
                        isCreated=true;
                    } else {
                        if(tomcatSite!=null) {
                            buildTomcatSite(tomcatSite, lsa, lsg);
                            isCreated=true;
                        } else {
                            // If caught in the middle of account creation, this might occur
                            isCreated=false;
                        }
                    }
                    // Now that the contents are all successfully created, update the directory
                    // owners to make sure we don't cover up this directory in the future
                    if(isCreated) wwwDirUF.chown(httpdUID, lsgGID);
                }

                // Make sure the newly created or existing web site is not removed
                wwwDirectories.remove(siteName);

                /*
                 * Add or rebuild the server.xml files for standard or Tomcat sites.
                 */
                if(tomcatSite!=null) {
                    HttpdTomcatVersion htv=tomcatSite.getHttpdTomcatVersion();
                    boolean isTomcat4=htv.isTomcat4(conn);
                    boolean isTomcat55=htv.isTomcat55(conn);
                    HttpdTomcatStdSite stdSite=tomcatSite.getHttpdTomcatStdSite();
                    boolean isStandard=stdSite!=null;
                    boolean isJBoss=tomcatSite.getHttpdJBossSite()!=null;
                    if(
                        !isTomcat4
                        || isStandard
                        || isJBoss
                    ) {
                        HttpdTomcatSharedSite shrSite=tomcatSite.getHttpdTomcatSharedSite();
                        String autoWarning=getAutoWarning(site);
                        boolean isShared=shrSite!=null;

                        String confServerXML=siteDir+"/conf/server.xml";
                        UnixFile confServerXMLFile=new UnixFile(confServerXML);
                        if(!site.isManual() || !confServerXMLFile.getStat(tempStat).exists()) {
                            UnixFile newConfServerXML=new UnixFile(confServerXML+".new");
                            try {
                                ChainWriter out=new ChainWriter(
                                    new BufferedOutputStream(
                                        newConfServerXML.getSecureOutputStream(
                                            isShared && shrSite.getHttpdSharedTomcat().isSecure()
                                            ?UnixFile.ROOT_UID
                                            :site.getLinuxServerAccount().getUID().getID(),
                                            site.getLinuxServerGroup().getGID().getID(),
                                            isShared && shrSite.getHttpdSharedTomcat().isSecure()
                                            ?0640
                                            :0660,
                                            true
                                        )
                                    )
                                );
				try {
                                    String tomcatVersion=tomcatSite.getHttpdTomcatVersion().getTechnologyVersion(conn).getVersion();
                                    if(tomcatVersion.equals("3.1") || tomcatVersion.startsWith(HttpdTomcatVersion.VERSION_3_1_PREFIX)) {
                                        out.print("<?xml version=\"1.0\" encoding=\"ISO-8859-1\"?>\n");
                                        if(!site.isManual()) out.print(autoWarning);
                                        out.print("<Server>\n"
                                                + "    <xmlmapper:debug level=\"0\" />\n"
                                                + "    <Logger name=\"tc_log\"\n"
                                                + "            path=\"").print(siteDir).print("/var/log/tomcat.log\"\n"
                                                + "            customOutput=\"yes\" />\n"
                                                + "    <Logger name=\"servlet_log\"\n"
                                                + "            path=\"").print(siteDir).print("/var/log/servlet.log\"\n"
                                                + "            customOutput=\"yes\" />\n"
                                                + "    <Logger name=\"JASPER_LOG\"\n"
                                                + "            path=\"").print(siteDir).print("/var/log/jasper.log\"\n"
                                                + "            verbosityLevel = \"INFORMATION\" />\n"
                                                + "    <ContextManager debug=\"0\" home=\"").print(siteDir).print("\" workDir=\"");
                                        if (isStandard) out.print(siteDir).print("/work");
                                        else if (isShared) out.print(HttpdSharedTomcat.WWW_GROUP_DIR+'/')
                                                .print(shrSite.getHttpdSharedTomcat().getName())
                                                .print("/work/").print(siteName);
                                        out.print("\" >\n"
                                                + "        <ContextInterceptor className=\"org.apache.tomcat.context.DefaultCMSetter\" />\n"
                                                + "        <ContextInterceptor className=\"org.apache.tomcat.context.WorkDirInterceptor\" />\n"
                                                + "        <ContextInterceptor className=\"org.apache.tomcat.context.WebXmlReader\" />\n"
                                                + "        <ContextInterceptor className=\"org.apache.tomcat.context.LoadOnStartupInterceptor\" />\n"
                                                + "        <RequestInterceptor className=\"org.apache.tomcat.request.SimpleMapper\" debug=\"0\" />\n"
                                                + "        <RequestInterceptor className=\"org.apache.tomcat.request.SessionInterceptor\" />\n"
                                                + "        <RequestInterceptor className=\"org.apache.tomcat.request.SecurityCheck\" />\n"
                                                + "        <RequestInterceptor className=\"org.apache.tomcat.request.FixHeaders\" />\n"
                                        );
                                        for(HttpdWorker worker : tomcatSite.getHttpdWorkers()) {
                                            NetBind netBind=worker.getNetBind();
                                            String protocol=worker.getHttpdJKProtocol(conn).getProtocol(conn).getProtocol();

                                            out.print("        <Connector className=\"org.apache.tomcat.service.PoolTcpConnector\">\n"
                                                    + "            <Parameter name=\"handler\" value=\"");
                                            if(protocol.equals(HttpdJKProtocol.AJP12)) out.print("org.apache.tomcat.service.connector.Ajp12ConnectionHandler");
                                            else if(protocol.equals(HttpdJKProtocol.AJP13)) throw new IllegalArgumentException("Tomcat Version "+tomcatVersion+" does not support AJP version: "+protocol);
                                            else throw new IllegalArgumentException("Unknown AJP version: "+tomcatVersion);
                                            out.print("\"/>\n"
                                                    + "            <Parameter name=\"port\" value=\"").print(netBind.getPort()).print("\"/>\n");
                                            IPAddress ip=netBind.getIPAddress();
                                            if(!ip.isWildcard()) out.print("            <Parameter name=\"inet\" value=\"").print(ip.getIPAddress()).print("\"/>\n");
                                            out.print("            <Parameter name=\"max_threads\" value=\"30\"/>\n"
                                                    + "            <Parameter name=\"max_spare_threads\" value=\"10\"/>\n"
                                                    + "            <Parameter name=\"min_spare_threads\" value=\"1\"/>\n"
                                                    + "        </Connector>\n"
                                            );
                                        }
                                        for(HttpdTomcatContext htc : tomcatSite.getHttpdTomcatContexts()) {
                                            out.print("    <Context path=\"").print(htc.getPath()).print("\" docBase=\"").print(htc.getDocBase()).print("\" debug=\"").print(htc.getDebugLevel()).print("\" reloadable=\"").print(htc.isReloadable()).print("\" />\n");
                                        }
                                        out.print("  </ContextManager>\n"
                                                + "</Server>\n");
                                    } else if(tomcatVersion.startsWith(HttpdTomcatVersion.VERSION_3_2_PREFIX)) {
                                        out.print("<?xml version=\"1.0\" encoding=\"ISO-8859-1\"?>\n");
                                        if(!site.isManual()) out.print(autoWarning);
                                        out.print("<Server>\n"
                                                + "  <xmlmapper:debug level=\"0\" />\n"
                                                + "  <Logger name=\"tc_log\" verbosityLevel = \"INFORMATION\" path=\"").print(siteDir).print("/var/log/tomcat.log\" />\n"
                                                + "  <Logger name=\"servlet_log\" path=\"").print(siteDir).print("/var/log/servlet.log\" />\n"
                                                + "  <Logger name=\"JASPER_LOG\" path=\"").print(siteDir).print("/var/log/jasper.log\" verbosityLevel = \"INFORMATION\" />\n"
                                                + "\n"
                                                + "  <ContextManager debug=\"0\" home=\"").print(siteDir).print("\" workDir=\"");
                                        if(isStandard) out.print(siteDir).print("/work");
                                        else if(isShared) out.print(HttpdSharedTomcat.WWW_GROUP_DIR+'/')
                                                .print(shrSite.getHttpdSharedTomcat().getName())
                                                .print("/work/").print(siteName);
                                        out.print("\" showDebugInfo=\"true\" >\n"
                                                + "    <ContextInterceptor className=\"org.apache.tomcat.context.WebXmlReader\" />\n"
                                                + "    <ContextInterceptor className=\"org.apache.tomcat.context.LoaderInterceptor\" />\n"
                                                + "    <ContextInterceptor className=\"org.apache.tomcat.context.DefaultCMSetter\" />\n"
                                                + "    <ContextInterceptor className=\"org.apache.tomcat.context.WorkDirInterceptor\" />\n");
                                        if(isJBoss) out.print("    <RequestInterceptor className=\"org.apache.tomcat.request.Jdk12Interceptor\" />\n");
                                        out.print("    <RequestInterceptor className=\"org.apache.tomcat.request.SessionInterceptor\"");
                                        if(isJBoss) out.print(" noCookies=\"false\"");
                                        out.print(" />\n"
                                                + "    <RequestInterceptor className=\"org.apache.tomcat.request.SimpleMapper1\" debug=\"0\" />\n"
                                                + "    <RequestInterceptor className=\"org.apache.tomcat.request.InvokerInterceptor\" debug=\"0\" ");
                                        if(isJBoss) out.print("prefix=\"/servlet/\" ");
                                        out.print("/>\n"
                                                + "    <RequestInterceptor className=\"org.apache.tomcat.request.StaticInterceptor\" debug=\"0\" ");
                                        if(isJBoss) out.print("suppress=\"false\" ");
                                        out.print("/>\n"
                                                + "    <RequestInterceptor className=\"org.apache.tomcat.session.StandardSessionInterceptor\" />\n"
                                                + "    <RequestInterceptor className=\"org.apache.tomcat.request.AccessInterceptor\" debug=\"0\" />\n");
                                        if(isJBoss) out.print("    <RequestInterceptor className=\"org.jboss.tomcat.security.JBossSecurityMgrRealm\" />\n");
                                        else out.print("    <RequestInterceptor className=\"org.apache.tomcat.request.SimpleRealm\" debug=\"0\" />\n");
                                        out.print("    <ContextInterceptor className=\"org.apache.tomcat.context.LoadOnStartupInterceptor\" />\n");

                                        for(HttpdWorker worker : tomcatSite.getHttpdWorkers()) {
                                            NetBind netBind=worker.getNetBind();
                                            String protocol=worker.getHttpdJKProtocol(conn).getProtocol(conn).getProtocol();

                                            out.print("    <Connector className=\"org.apache.tomcat.service.PoolTcpConnector\">\n"
                                                    + "      <Parameter name=\"handler\" value=\"");
                                            if(protocol.equals(HttpdJKProtocol.AJP12)) out.print("org.apache.tomcat.service.connector.Ajp12ConnectionHandler");
                                            else if(protocol.equals(HttpdJKProtocol.AJP13)) out.print("org.apache.tomcat.service.connector.Ajp13ConnectionHandler");
                                            else throw new IllegalArgumentException("Unknown AJP version: "+tomcatVersion);
                                            out.print("\"/>\n"
                                                    + "      <Parameter name=\"port\" value=\"").print(netBind.getPort()).print("\"/>\n");
                                            IPAddress ip=netBind.getIPAddress();
                                            if(!ip.isWildcard()) out.print("      <Parameter name=\"inet\" value=\"").print(ip.getIPAddress()).print("\"/>\n");
                                            out.print("      <Parameter name=\"max_threads\" value=\"30\"/>\n"
                                                    + "      <Parameter name=\"max_spare_threads\" value=\"10\"/>\n"
                                                    + "      <Parameter name=\"min_spare_threads\" value=\"1\"/>\n"
                                                    + "    </Connector>\n"
                                            );
                                        }
                                        for(HttpdTomcatContext htc : tomcatSite.getHttpdTomcatContexts()) {
                                            out.print("    <Context path=\"").print(htc.getPath()).print("\" docBase=\"").print(htc.getDocBase()).print("\" debug=\"").print(htc.getDebugLevel()).print("\" reloadable=\"").print(htc.isReloadable()).print("\" />\n");
                                        }
                                        out.print("  </ContextManager>\n"
                                                + "</Server>\n");
                                    } else if(isTomcat4) {
                                        List<HttpdWorker> hws=tomcatSite.getHttpdWorkers();
                                        if(hws.size()!=1) throw new SQLException("Expected to only find one HttpdWorker for HttpdTomcatStdSite #"+site.getPkey()+", found "+hws.size());
                                        HttpdWorker hw=hws.get(0);
                                        String hwProtocol=hw.getHttpdJKProtocol(conn).getProtocol(conn).getProtocol();
                                        if(!hwProtocol.equals(HttpdJKProtocol.AJP13)) {
                                            throw new SQLException("HttpdWorker #"+hw.getPkey()+" for HttpdTomcatStdSite #"+site.getPkey()+" must be AJP13 but it is "+hwProtocol);
                                        }
                                        if(!site.isManual()) out.print(autoWarning);
                                        NetBind shutdownPort=stdSite.getTomcat4ShutdownPort();
                                        if(shutdownPort==null) throw new SQLException("Unable to find shutdown port for HttpdTomcatStdSite="+stdSite);
                                        String shutdownKey=stdSite.getTomcat4ShutdownKey();
                                        if(shutdownKey==null) throw new SQLException("Unable to find shutdown key for HttpdTomcatStdSite="+stdSite);
                                        out.print("<Server port=\"").print(shutdownPort.getPort().getPort()).print("\" shutdown=\"").print(shutdownKey).print("\" debug=\"0\">\n");
                                        if(isTomcat55) {
                                            out.print("  <GlobalNamingResources>\n"
                                                    + "    <Resource name=\"UserDatabase\" auth=\"Container\"\n"
                                                    + "              type=\"org.apache.catalina.UserDatabase\"\n"
                                                    + "       description=\"User database that can be updated and saved\"\n"
                                                    + "           factory=\"org.apache.catalina.users.MemoryUserDatabaseFactory\"\n"
                                                    + "          pathname=\"conf/tomcat-users.xml\" />\n"
                                                    + "   </GlobalNamingResources>\n");
                                        } else if(tomcatVersion.startsWith(HttpdTomcatVersion.VERSION_4_1_PREFIX)) {
                                            out.print("  <Listener className=\"org.apache.catalina.mbeans.ServerLifecycleListener\" debug=\"0\"/>\n"
                                                    + "  <Listener className=\"org.apache.catalina.mbeans.GlobalResourcesLifecycleListener\" debug=\"0\"/>\n"
                                                    + "  <GlobalNamingResources>\n"
                                                    + "    <Resource name=\"UserDatabase\" auth=\"Container\" type=\"org.apache.catalina.UserDatabase\" description=\"User database that can be updated and saved\"/>\n"
                                                    + "    <ResourceParams name=\"UserDatabase\">\n"
                                                    + "      <parameter>\n"
                                                    + "        <name>factory</name>\n"
                                                    + "        <value>org.apache.catalina.users.MemoryUserDatabaseFactory</value>\n"
                                                    + "      </parameter>\n"
                                                    + "      <parameter>\n"
                                                    + "        <name>pathname</name>\n"
                                                    + "        <value>conf/tomcat-users.xml</value>\n"
                                                    + "      </parameter>\n"
                                                    + "    </ResourceParams>\n"
                                                    + "  </GlobalNamingResources>\n");
                                        }
                                        out.print("  <Service name=\"").print(isTomcat55 ? "Catalina" : "Tomcat-Apache").print("\">\n"
                                                + "    <Connector\n"
                                                  /*
                                                + "      className=\"org.apache.coyote.tomcat4.CoyoteConnector\"\n"
                                                + "      port=\"").print(hw.getNetBind().getPort().getPort()).print("\"\n"
                                                + "      minProcessors=\"2\"\n"
                                                + "      maxProcessors=\"200\"\n"
                                                + "      enableLookups=\"true\"\n"
                                                + "      redirectPort=\"443\"\n"
                                                + "      acceptCount=\"10\"\n"
                                                + "      debug=\"0\"\n"
                                                + "      connectionTimeout=\"20000\"\n"
                                                + "      useURIValidationHack=\"false\"\n"
                                                + "      protocolHandlerClassName=\"org.apache.jk.server.JkCoyoteHandler\"\n"
                                                  */);
                                        if (tomcatVersion.startsWith(HttpdTomcatVersion.VERSION_4_1_PREFIX)) {
                                            out.print("      className=\"org.apache.ajp.tomcat4.Ajp13Connector\"\n");
                                        }
                                        out.print("      port=\"").print(hw.getNetBind().getPort().getPort()).print("\"\n");
                                        if(isTomcat55) out.print("      enableLookups=\"false\"\n");
                                        out.print("      minProcessors=\"2\"\n"
                                                + "      maxProcessors=\"200\"\n"
                                                + "      address=\""+IPAddress.LOOPBACK_IP+"\"\n"
                                                + "      acceptCount=\"10\"\n"
                                                + "      debug=\"0\"\n"
                                                + "      protocol=\"AJP/1.3\"\n"
                                                + "    />\n"
                                                + "    <Engine name=\"").print(isTomcat55 ? "Catalina" : "Tomcat-Apache").print("\" defaultHost=\"localhost\" debug=\"0\">\n");
                                        if(isTomcat55) {
                                            out.print("      <Realm className=\"org.apache.catalina.realm.UserDatabaseRealm\"\n"
                                                    + "          resourceName=\"UserDatabase\" />\"\n");
                                        } else if(tomcatVersion.startsWith(HttpdTomcatVersion.VERSION_4_1_PREFIX)) {
                                            out.print("      <Logger\n"
                                                    + "        className=\"org.apache.catalina.logger.FileLogger\"\n"
                                                    + "        directory=\"var/log\"\n"
                                                    + "        prefix=\"catalina_log.\"\n"
                                                    + "        suffix=\".txt\"\n"
                                                    + "        timestamp=\"true\"\n"
                                                    + "      />\n");
                                            out.print("      <Realm className=\"org.apache.catalina.realm.UserDatabaseRealm\" debug=\"0\" resourceName=\"UserDatabase\" />\n");
                                        } else {
                                            out.print("      <Realm className=\"org.apache.catalina.realm.MemoryRealm\" />\n");
                                        }
                                        out.print("      <Host\n"
                                                + "        name=\"localhost\"\n"
                                                + "        debug=\"0\"\n"
                                                + "        appBase=\"webapps\"\n"
                                                + "        unpackWARs=\"true\"\n");
                                        if(
                                            tomcatVersion.startsWith(HttpdTomcatVersion.VERSION_4_1_PREFIX)
                                            || isTomcat55
                                        ) {
                                            out.print("        autoDeploy=\"true\"\n");
                                        }
                                        if(isTomcat55) {
                                            out.print("        xmlValidation=\"false\"\n"
                                                    + "        xmlNamespaceAware=\"false\"\n");
                                        }
                                        out.print("      >\n");
                                        if(tomcatVersion.startsWith(HttpdTomcatVersion.VERSION_4_1_PREFIX)) {
                                            out.print("        <Logger\n"
                                                    + "          className=\"org.apache.catalina.logger.FileLogger\"\n"
                                                    + "          directory=\"var/log\"\n"
                                                    + "          prefix=\"localhost_log.\"\n"
                                                    + "          suffix=\".txt\"\n"
                                                    + "          timestamp=\"true\"\n"
                                                    + "        />\n");
                                        }
                                        for(HttpdTomcatContext htc : tomcatSite.getHttpdTomcatContexts()) {
                                            out.print("        <Context\n");
                                            if(htc.getClassName()!=null) out.print("          className=\"").print(htc.getClassName()).print("\"\n");
                                            out.print("          cookies=\"").print(htc.useCookies()).print("\"\n"
                                                    + "          crossContext=\"").print(htc.allowCrossContext()).print("\"\n"
                                                    + "          docBase=\"").print(htc.getDocBase()).print("\"\n"
                                                    + "          override=\"").print(htc.allowOverride()).print("\"\n"
                                                    + "          path=\"").print(htc.getPath()).print("\"\n"
                                                    + "          privileged=\"").print(htc.isPrivileged()).print("\"\n"
                                                    + "          reloadable=\"").print(htc.isReloadable()).print("\"\n"
                                                    + "          useNaming=\"").print(htc.useNaming()).print("\"\n");
                                            if(htc.getWrapperClass()!=null) out.print("          wrapperClass=\"").print(htc.getWrapperClass()).print("\"\n");
                                            out.print("          debug=\"").print(htc.getDebugLevel()).print("\"\n");
                                            if(htc.getWorkDir()!=null) out.print("          workDir=\"").print(htc.getWorkDir()).print("\"\n");
                                            List<HttpdTomcatParameter> parameters=htc.getHttpdTomcatParameters();
                                            List<HttpdTomcatDataSource> dataSources=htc.getHttpdTomcatDataSources();
                                            if(parameters.isEmpty() && dataSources.isEmpty()) {
                                                out.print("        />\n");
                                            } else {
                                                out.print("        >\n");
                                                // Parameters
                                                for(HttpdTomcatParameter parameter : parameters) {
                                                    writeHttpdTomcatParameter(tomcatVersion, parameter, out);
                                                }
                                                // Data Sources
                                                for(HttpdTomcatDataSource dataSource : dataSources) {
                                                    writeHttpdTomcatDataSource(tomcatVersion, dataSource, out);
                                                }
                                                out.print("        </Context>\n");
                                            }
                                        }
                                        out.print("      </Host>\n"
                                                + "    </Engine>\n"
                                                + "  </Service>\n"
                                                + "</Server>\n");
                                    } else throw new SQLException("Unsupported version of Tomcat: "+tomcatVersion);
				} finally {
				    out.flush();
				    out.close();
				}

                                // Flag the JVM as needing a restart if the server.xml file has been modified
                                if(site.getDisableLog()==null) {
                                    boolean needsRestart;
                                    if(confServerXMLFile.getStat(tempStat).exists()) {
                                        needsRestart=!confServerXMLFile.contentEquals(newConfServerXML);
                                    } else needsRestart=true;
                                    if(needsRestart) {
                                        int pkey=site.getPkey();
                                        if(isShared) {
                                            if(!sharedTomcatsNeedingRestarted.contains(pkey)) sharedTomcatsNeedingRestarted.add(shrSite.getHttpdSharedTomcat().getPkey());
                                        } else {
                                            if(!sitesNeedingRestarted.contains(pkey)) sitesNeedingRestarted.add(pkey);
                                        }
                                    }
                                }

                                // Move the file into place
                                newConfServerXML.renameTo(confServerXMLFile);
                            } catch(IOException err) {
                                System.err.println("Error on file: "+newConfServerXML.getFilename());
                                throw err;
                            }
                        } else {
                            try {
                                stripFilePrefix(
                                    confServerXMLFile,
                                    autoWarning,
                                    tempStat
                                );
                            } catch(IOException err) {
                                // Errors OK because this is done in manual mode and they might have symbolic linked stuff
                            }
                        }
                    }
                }
                
                /*
                 * Add the web server config files if they do not exist.
                 */
                // The shared config part
                String sharedConfig=CONF_HOSTS+'/'+siteName;
                hostConfFiles.remove(siteName);
                UnixFile sharedFile=new UnixFile(sharedConfig);
                if(!site.isManual() || !sharedFile.getStat(tempStat).exists()) {
                    String newSharedConfig=sharedConfig+".new";
                    UnixFile newSharedFile=new UnixFile(newSharedConfig);
                    ChainWriter out=new ChainWriter(
                        new BufferedOutputStream(
                            newSharedFile.getSecureOutputStream(
                                UnixFile.ROOT_UID,
                                lsgGID,
                                0640,
                                true
                            )
                        )
                    );
		    try {
			out.print("    ServerAdmin ").print(site.getServerAdmin()).print("\n"
                                + "\n");
			
			if(tomcatSite!=null) {
			    HttpdTomcatVersion htv=tomcatSite.getHttpdTomcatVersion();
			    boolean isTomcat4=htv.isTomcat4(conn);
			    String tomcatVersion=htv.getTechnologyVersion(conn).getVersion();
			    HttpdTomcatStdSite stdSite=tomcatSite.getHttpdTomcatStdSite();
			    HttpdJBossSite jbossSite=tomcatSite.getHttpdJBossSite();
			    HttpdTomcatSharedSite sharedSite=tomcatSite.getHttpdTomcatSharedSite();
			    
			    boolean useApache=tomcatSite.useApache();
                            if(osv==OperatingSystemVersion.MANDRIVA_2006_0_I586 || osv==OperatingSystemVersion.REDHAT_ES_4_X86_64) {
                                out.print("    <IfModule !sapi_apache2.c>\n"
                                        + "        <IfModule !mod_php5.c>\n"
                                        + "            Action php4-script /cgi-bin/php\n"
                                        + "            AddHandler php4-script .php .php3 .php4\n"
                                        + "        </IfModule>\n"
                                        + "    </IfModule>\n");
                            } else {
                                out.print("    <IfModule !mod_php4.c>\n"
                                        + "        <IfModule !mod_php5.c>\n"
                                        + "            Include conf/options/cgi_php4\n"
                                        + "        </IfModule>\n"
                                        + "    </IfModule>\n");
                            }

                            if(useApache) {
                                if(osv!=OperatingSystemVersion.MANDRIVA_2006_0_I586 && osv!=OperatingSystemVersion.REDHAT_ES_4_X86_64) {
                                    out.print("    Include conf/options/shtml_standard\n"
                                              + "    Include conf/options/mod_rewrite\n"
                                              + "\n");
                                }
                            }
			    if(stdSite!=null || jbossSite!=null) {
				if(!isTomcat4) {
				    out.print("    <IfModule mod_jserv.c>\n"
                                            + "        ApJServDefaultProtocol ajpv12\n"
                                            + "        ApJServSecretKey DISABLED\n"
                                            + "        ApJServMountCopy on\n"
                                            + "        ApJServLogLevel notice\n"
                                            + "        ApJServMount default /root\n"
                                            + "        AddType text/do .do\n"
                                            + "        AddHandler jserv-servlet .do\n"
                                            + "        AddType text/jsp .jsp\n"
                                            + "        AddHandler jserv-servlet .jsp\n"
                                            + "        AddType text/xml .xml\n"
                                            + "        AddHandler jserv-servlet .xml\n"
                                            + "        <IfModule mod_rewrite.c>\n"
                                            + "            # Support jsessionid\n"
                                            + "            RewriteEngine on\n"
                                            + "            RewriteRule ^(/.*;jsessionid=.*)$ $1 [T=jserv-servlet]\n"
                                            + "        </IfModule>\n"
                                            + "    </IfModule>\n"
                                            + "\n");
				}
			    } else if(sharedSite!=null) {
                                if(osv!=OperatingSystemVersion.REDHAT_ES_4_X86_64 && osv!=OperatingSystemVersion.MANDRIVA_2006_0_I586) {
                                    out.print("    Include conf/wwwgroup/").print(sharedSite.getHttpdSharedTomcat().getName()).print("\n"
                                            + "\n");
                                }
			    } else throw new IllegalArgumentException("Unsupported HttpdTomcatSite type: "+site.getPkey());
			    
			    // The CGI user info
                            out.print("    <IfModule mod_suexec.c>\n"
                                    + "        SuexecUserGroup ").print(lsa.getLinuxAccount().getUsername()).print(' ').print(lsg.getLinuxGroup().getName()).print("\n"
                                    + "    </IfModule>\n"
                                    + "\n");

                            // Write the standard restricted URL patterns
                            out.print("    # Standard protected URLs\n");
                            for(int d=0;d<standardProtectedURLs.length;d++) {
                                out.print("    <LocationMatch \"").print(standardProtectedURLs[d]).print("\">\n"
                                        + "\tAllowOverride None\n"
                                        + "\tDeny from All\n"
                                        + "    </LocationMatch>\n"
                                );
                            }
                            out.print("    # Protect automatic backups\n"
                                    + "    <IfModule mod_rewrite.c>\n"
                                    + "        RewriteEngine on\n"
                                    + "\n"
                                    + "        # Redirect past automatic backups\n"
                                    + "        RewriteRule ^(.*).do~$ $1.do [L,R=permanent]\n"
                                    + "        RewriteRule ^(.*).jsp~$ $1.jsp [L,R=permanent]\n"
                                    + "        RewriteRule ^(.*).jspa~$ $1.jspa [L,R=permanent]\n"
                                    + "        RewriteRule ^(.*).php~$ $1.php [L,R=permanent]\n"
                                    + "        RewriteRule ^(.*).shtml~$ $1.shtml [L,R=permanent]\n"
                                    + "        RewriteRule ^(.*).vm~$ $1.vm [L,R=permanent]\n"
                                    + "        RewriteRule ^(.*).xml~$ $1.xml [L,R=permanent]\n"
                                    + "\n"
                                    + "        # Protect dangerous request methods\n"
                                    + "        RewriteCond %{REQUEST_METHOD} ^(TRACE|TRACK)\n"
                                    + "        RewriteRule .* - [F]\n"
                                    + "    </IfModule>\n");
                            // Write the authenticated locations
                            List<HttpdSiteAuthenticatedLocation> hsals = site.getHttpdSiteAuthenticatedLocations();
                            if(!hsals.isEmpty()) {
                                out.print("    # Authenticated Locations\n");
                                for(HttpdSiteAuthenticatedLocation hsal : hsals) {
                                    out.print("    <").print(hsal.getIsRegularExpression()?"LocationMatch":"Location").print(" \"").print(hsal.getPath()).print("\">\n");
                                    if(hsal.getAuthUserFile().length()>0 || hsal.getAuthGroupFile().length()>0) out.print("        AuthType Basic\n");
                                    if(hsal.getAuthName().length()>0) out.print("        AuthName \"").print(hsal.getAuthName()).print("\"\n");
                                    if(hsal.getAuthUserFile().length()>0) out.print("        AuthUserFile \"").print(hsal.getAuthUserFile()).print("\"\n");
                                    if(hsal.getAuthGroupFile().length()>0) out.print("        AuthGroupFile \"").print(hsal.getAuthGroupFile()).print("\"\n");
                                    if(hsal.getRequire().length()>0) out.print("        require ").print(hsal.getRequire()).print('\n');
                                    out.print("    </").print(hsal.getIsRegularExpression()?"LocationMatch":"Location").print(">\n");
                                }
                            }

                            // Set up all of the webapps
			    for(HttpdTomcatContext htc : tomcatSite.getHttpdTomcatContexts()) {
				String path=htc.getPath();
				String docBase=htc.getDocBase();
				if(path.length()==0) {
                                    out.print("    # Set up the default webapp\n"
                                            + "    DocumentRoot ").print(docBase).print("\n"
                                            + "    <Directory ").print(docBase).print(">\n"
                                            + "        Allow from All\n"
                                            + "        AllowOverride All\n"
                                            + "        Order allow,deny\n"
                                            + "        Options Indexes IncludesNOEXEC\n"
                                            + "    </Directory>\n");
				} else if(useApache) {
                                    out.print("    # Set up the ").print(path).print(" webapp\n"
                                            + "    AliasMatch ^").print(path).print("$ ").print(docBase).print("\n"
                                            + "    AliasMatch ^").print(path).print("/(.*) ").print(docBase).print("/$1\n"
                                            + "    <Directory \"").print(docBase).print("\">\n"
                                            + "        Allow from All\n"
                                            + "        AllowOverride All\n"
                                            + "        Order allow,deny\n"
                                            + "        Options Indexes IncludesNOEXEC\n"
                                            + "    </Directory>\n");
				}
				if(useApache) {
                                    if(osv==OperatingSystemVersion.REDHAT_ES_4_X86_64 || osv==OperatingSystemVersion.MANDRIVA_2006_0_I586) {
                                        out.print("    <Directory \"").print(docBase).print("/cgi-bin\">\n"
                                                + "        <IfModule mod_ssl.c>\n"
                                                + "            SSLOptions +StdEnvVars\n"
                                                + "        </IfModule>\n"
                                                + "        SetHandler cgi-script\n"
                                                + "        Options ExecCGI\n"
                                                + "    </Directory>\n");
                                    } else {
                                        out.print("    ScriptAlias ").print(path).print("/cgi-bin/ ").print(docBase).print("/cgi-bin/\n"
                                                + "    <Directory ").print(docBase).print("/cgi-bin>\n"
                                                + "        Options ExecCGI\n"
                                                + "        <IfModule mod_ssl.c>\n"
                                                + "            SSLOptions +StdEnvVars\n"
                                                + "        </IfModule>\n"
                                                + "        Allow from All\n"
                                                + "        Order allow,deny\n"
                                                + "    </Directory>\n");
                                    }
				} else {
                                    out.print("    <IfModule mod_jk.c>\n"
                                            + "        ScriptAlias ").print(path).print("/cgi-bin/ ").print(docBase).print("/cgi-bin/\n"
                                            + "        <Directory ").print(docBase).print("/cgi-bin>\n"
                                            + "            Options ExecCGI\n"
                                            + "            <IfModule mod_ssl.c>\n"
                                            + "                SSLOptions +StdEnvVars\n"
                                            + "            </IfModule>\n"
                                            + "            Allow from All\n"
                                            + "            Order allow,deny\n"
                                            + "        </Directory>\n"
                                            + "    </IfModule>\n");
				}
				if(useApache) {
				    out.print("    <Location ").print(path).print("/META-INF/>\n"
                                            + "        AllowOverride None\n"
                                            + "        Deny from All\n"
                                            + "    </Location>\n"
                                            + "    <Location ").print(path).print("/WEB-INF/>\n"
                                            + "        AllowOverride None\n"
                                            + "        Deny from All\n"
                                            + "    </Location>\n");
				}
				out.print('\n');
			    }
			    
			    List<HttpdWorker> workers=tomcatSite.getHttpdWorkers();
			    String ajp12Code=null;
			    if(!isTomcat4) {
				// Look for the first AJP12 worker
				int ajp12Port=-1;
				for(HttpdWorker hw : workers) {
				    if(hw.getHttpdJKProtocol(conn).getProtocol(conn).getProtocol().equals(HttpdJKProtocol.AJP12)) {
					ajp12Port=hw.getNetBind().getPort().getPort();
					ajp12Code=hw.getCode().getCode();
					break;
				    }
				}
				if(ajp12Port!=-1) {
				    out.print("    <IfModule mod_jserv.c>\n"
					      + "        ApJServDefaultPort ").print(ajp12Port).print("\n");
				    for(HttpdTomcatContext htc : tomcatSite.getHttpdTomcatContexts()) {
					String path=htc.getPath();
					String relPath=useApache?path+"/servlet":path;
					out.print("        ApJServMount ").print(relPath.length()==0?"/":relPath).print(' ').print(path.length()==0?"/"+HttpdTomcatContext.ROOT_DOC_BASE:path).print("\n");
				    }
				    out.print("    </IfModule>\n"
					      + "\n");
				}
			    }
			    
			    int ajp13Port=-1;
			    String ajp13Code=null;
			    if(isTomcat4 && sharedSite!=null) {
				HttpdWorker hw=sharedSite.getHttpdSharedTomcat().getTomcat4Worker();
				ajp13Port=hw.getNetBind().getPort().getPort();
				ajp13Code=hw.getCode().getCode();
			    } else {
				for(HttpdWorker hw : workers) {
				    if(hw.getHttpdJKProtocol(conn).getProtocol(conn).getProtocol().equals(HttpdJKProtocol.AJP13)) {
					ajp13Port=hw.getNetBind().getPort().getPort();
					ajp13Code=hw.getCode().getCode();
					break;
				    }
				}
			    }
			    String jkCode=ajp13Code!=null?ajp13Code:ajp12Code;
			    out.print("    <IfModule mod_jk.c>\n");
                                    //+ "        # Redirect past automatic backups\n"
                                    //+ "        <IfModule mod_rewrite.c>\n"
                                    //+ "            RewriteEngine on\n"
                                    //+ "            RewriteRule ^(.*).do~$ $1.do [L,R=permanent]\n"
                                    //+ "            RewriteRule ^(.*).jsp~$ $1.jsp [L,R=permanent]\n"
                                    //+ "            RewriteRule ^(.*).jspa~$ $1.jspa [L,R=permanent]\n"
                                    //+ "            RewriteRule ^(.*).vm~$ $1.vm [L,R=permanent]\n"
                                    //+ "            RewriteRule ^(.*).xml~$ $1.xml [L,R=permanent]\n"
                                    //+ "        </IfModule>\n");
			    if(useApache) {
				for(HttpdTomcatContext context : tomcatSite.getHttpdTomcatContexts()) {
				    String path=context.getPath();
                                    out.print("        JkMount ").print(path).print("/j_security_check ").print(jkCode).print("\n"
                                    + "        JkMount ").print(path).print("/servlet/* ").print(jkCode).print("\n"
                                    + "        JkMount ").print(path).print("/*.do ").print(jkCode).print("\n"
                                    + "        JkMount ").print(path).print("/*.jsp ").print(jkCode).print("\n"
                                    + "        JkMount ").print(path).print("/*.jspa ").print(jkCode).print("\n"
                                    + "        JkMount ").print(path).print("/*.vm ").print(jkCode).print("\n"
                                    + "        JkMount ").print(path).print("/*.xml ").print(jkCode).print("\n");
				}
			    } else {
				out.print("        JkMount /* ").print(jkCode).print("\n");
                                if(osv==OperatingSystemVersion.REDHAT_ES_4_X86_64 || osv==OperatingSystemVersion.MANDRIVA_2006_0_I586) {
                                    out.print("        JkUnMount /*.php ").print(jkCode).print("\n"
                                            + "        JkUnMount /cgi-bin/* ").print(jkCode).print("\n");
                                }
			    }
                            out.print("        # Remove jsessionid for non-jk requests\n"
                                    + "        <IfModule mod_rewrite.c>\n"
                                    + "            RewriteEngine On\n"
                                    + "            RewriteRule ^(.*);jsessionid=.*$ $1\n"
                                    + "        </IfModule>\n"
			            + "    </IfModule>\n"
                                    + "\n");
			} else throw new IllegalArgumentException("Unsupported HttpdSite type for HttpdSite #"+site.getPkey());
		    } finally {
			out.flush();
			out.close();
		    }
                    
                    if(!sharedFile.getStat(tempStat).exists() || !sharedFile.contentEquals(newSharedFile)) {
                        for(HttpdSiteBind hsb : site.getHttpdSiteBinds()) {
                            int hsPKey=hsb.getHttpdBind().getHttpdServer().getPkey();
                            if(!serversNeedingRestarted.contains(hsPKey)) serversNeedingRestarted.add(hsPKey);
                        }
                        newSharedFile.renameTo(sharedFile);
                    } else newSharedFile.delete();
                }
                // Each of the binds
                for(HttpdSiteBind bind : binds) {
                    NetBind netBind=bind.getHttpdBind().getNetBind();
                    String ipAddress=netBind.getIPAddress().getIPAddress();
                    int port=netBind.getPort().getPort();
                    String bindConfig=CONF_HOSTS+'/'+siteName+'_'+ipAddress+'_'+port;
                    hostConfFiles.remove(siteName+'_'+ipAddress+'_'+port);
                    UnixFile bindFile=new UnixFile(bindConfig);
                    if(!bind.isManual() || !bindFile.getStat(tempStat).exists()) {
                        String newBindConfig=bindConfig+".new";
                        UnixFile newBindFile=new UnixFile(newBindConfig);
                        writeHttpdSiteBindFile(bind, newBindFile, bind.getDisableLog()!=null?HttpdSite.DISABLED:siteName);
                        if(!bindFile.getStat(tempStat).exists() || !bindFile.contentEquals(newBindFile)) {
                            int hsPKey=bind.getHttpdBind().getHttpdServer().getPkey();
                            if(!serversNeedingRestarted.contains(hsPKey)) serversNeedingRestarted.add(hsPKey);
                            newBindFile.renameTo(bindFile);
                        } else newBindFile.delete();
                    }
                }

                /*
                 * Make the private FTP space, if needed.
                 */
                String ftpPath=FTPManager.SHARED_FTP_DIRECTORY+'/'+siteName;
                ftpDirectories.remove(siteName);
                UnixFile ftpDir=new UnixFile(ftpPath);
                if(!ftpDir.getStat(tempStat).exists()) ftpDir.mkdir().chown(lsaUID, lsgGID).setMode(0775);
            }

            /*
             * Delete any extra files once the rebuild is done
             */
            for(int c=0;c<hostConfFiles.size();c++) deleteFileList.add(new File(CONF_HOSTS, hostConfFiles.get(c)));
            for(int c=0;c<logDirectories.size();c++) deleteFileList.add(new File(LOG_DIR, logDirectories.get(c)));
            for(int c=0;c<wwwDirectories.size();c++) {
                String siteName=wwwDirectories.get(c);
                stopHttpdSite(siteName, tempStat);
                disableHttpdSite(siteName, tempStat);
                String fullPath=HttpdSite.WWW_DIRECTORY+'/'+siteName;
                if(!aoServer.isHomeUsed(fullPath)) {
                    deleteFileList.add(new File(fullPath));
                }
            }
            for(int c=0;c<ftpDirectories.size();c++) deleteFileList.add(new File(FTPManager.SHARED_FTP_DIRECTORY, ftpDirectories.get(c)));
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }

    private static void writeHttpdSiteBindFile(HttpdSiteBind bind, UnixFile file, String siteInclude) throws IOException, SQLException {
        Profiler.startProfile(Profiler.UNKNOWN, HttpdManager.class, "writeHttpdSiteBindFile(HttpdSiteBind,UnixFile,String)", null);
        try {
            NetBind netBind=bind.getHttpdBind().getNetBind();
            int port=netBind.getPort().getPort();
            int lsgGID=bind.getHttpdSite().getLinuxServerGroup().getGID().getID();
            int osv=netBind.getAOServer().getServer().getOperatingSystemVersion().getPkey();
            String ipAddress = netBind.getIPAddress().getIPAddress();
            HttpdSiteURL primaryHSU = bind.getPrimaryHttpdSiteURL();
            String primaryHostname = primaryHSU.getHostname();

            ChainWriter out=new ChainWriter(
                new BufferedOutputStream(
                    file.getSecureOutputStream(UnixFile.ROOT_UID, lsgGID, 0640, true)
                )
            );
	    try {
                out.print("<VirtualHost ").print(ipAddress).print(':').print(port).print(">\n"
                        + "    ServerName ").print(primaryHostname).print('\n'
                );
                List<HttpdSiteURL> altURLs=bind.getAltHttpdSiteURLs();
                if(!altURLs.isEmpty()) {
                    out.print("    ServerAlias");
                    for(HttpdSiteURL altURL : altURLs) {
                        out.print(' ').print(altURL.getHostname());
                    }
                    out.print('\n');
                }
                out.print("\n");
                if(osv==OperatingSystemVersion.REDHAT_ES_4_X86_64 || osv==OperatingSystemVersion.MANDRIVA_2006_0_I586) {
                    out.print("    CustomLog ").print(bind.getAccessLog()).print(" combined\n");
                } else {
                    out.print("    CustomLog ").print(bind.getAccessLog()).print(" common\n");
                }
                out.print("    ErrorLog ").print(bind.getErrorLog()).print("\n"
                        + "\n");
                String sslCert=bind.getSSLCertFile();
                if(sslCert!=null) {
                    out.print("    <IfModule mod_ssl.c>\n"
                            + "        SSLCertificateFile ").print(sslCert).print("\n"
                            + "        SSLCertificateKeyFile ").print(bind.getSSLCertKeyFile()).print("\n");
                    if(osv!=OperatingSystemVersion.REDHAT_ES_4_X86_64) {
                        out.print("        SSLCACertificateFile /etc/ssl/CA/ca.txt\n");
                    }
                    out.print("        <Files ~ \"\\.(.cgi|shtml|phtml|php3?)$\">\n"
                            + "            SSLOptions +StdEnvVars\n"
                            + "        </Files>\n"
                            + "        SSLEngine On\n"
                            + "    </IfModule>\n"
                            + "\n"
                    );
                }
                if(bind.getRedirectToPrimaryHostname()) {
                    out.print("    # Redirect requests that are not to either the IP address or the primary hostname to the primary hostname\n"
                            + "    RewriteEngine on\n"
                            + "    RewriteCond %{HTTP_HOST} !=").print(primaryHostname).print(" [NC]\n"
                            + "    RewriteCond %{HTTP_HOST} !=").print(ipAddress).print("\n"
                            + "    RewriteRule ^(.*)$ ").print(primaryHSU.getURLNoSlash()).print("$1 [L,R=permanent]\n"
                            + "    \n");
                }
                out.print("    Include conf/hosts/").print(siteInclude).print("\n"
                        + "\n"
                        + "</VirtualHost>\n");
	    } finally {
		out.flush();
		out.close();
	    }
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }

    /**
     * Only called by the internally synchronized <code>doRebuild()</code> method.
     */
    private static void doRebuildSharedTomcats(
	AOServer aoServer,
	List<File> deleteFileList,
	AOServConnector conn,
        IntList sharedTomcatsNeedingRestarted,
        Stat tempStat
    ) throws IOException, SQLException {
        Profiler.startProfile(Profiler.UNKNOWN, HttpdManager.class, "doRebuildSharedTomcats(AOServer,List<File>,AOServConnector,IntList,Stat)", null);
        try {
            int osv=aoServer.getServer().getOperatingSystemVersion().getPkey();

            // get shared tomcats
            List<HttpdSharedTomcat> tomcats = aoServer.getHttpdSharedTomcats();

            // The www group directories that exist but are not used will be removed
            String[] list = new File(HttpdSharedTomcat.WWW_GROUP_DIR).list();
            List<String> wwwGroupDirectories = new SortedArrayList<String>(list.length);
            for (int c = 0; c < list.length; c++) {
                String dirname = list[c];
                if(
                    !dirname.equals("lost+found")
                    && !dirname.equals(".backup")
                )
                wwwGroupDirectories.add(dirname);
            }

            // The config directory should only contain files referenced in the database
            List<String> groupConfFiles;
            if(osv!=OperatingSystemVersion.REDHAT_ES_4_X86_64 && osv!=OperatingSystemVersion.MANDRIVA_2006_0_I586) {
                list = new File(CONF_WWWGROUPS).list();
                groupConfFiles = new SortedArrayList<String>(list.length);
                for (int c = 0; c < list.length; c++) groupConfFiles.add(list[c]);
            } else {
                groupConfFiles = null;
            }

            int len = tomcats.size();
            for (int i = 0; i < len; i++) {
                /*
                 * Get values used in the rest of the loop.
                 */
                final HttpdSharedTomcat tomcat = tomcats.get(i);
                final HttpdTomcatVersion htv=tomcat.getHttpdTomcatVersion();
                final boolean isTomcat4=htv.isTomcat4(conn);
                final boolean isTomcat55=htv.isTomcat55(conn);
                final String tomcatDirectory=htv.getInstallDirectory();
                String version = htv.getTechnologyVersion(conn).getVersion();
                
                final LinuxServerAccount lsa = tomcat.getLinuxServerAccount();
                final int lsaUID = lsa.getUID().getID();

                final LinuxServerGroup lsg = tomcat.getLinuxServerGroup();
                final int lsgGID = lsg.getGID().getID();

                /*
                 * Create and fill in the directory if it does not exist or is owned by root.
                 */
                final UnixFile wwwGroupDirUF = new UnixFile(HttpdSharedTomcat.WWW_GROUP_DIR, tomcat.getName());
                UnixFile workUF = new UnixFile(wwwGroupDirUF, "work", false);
                UnixFile innerWorkUF;
                if(isTomcat4) {
                    innerWorkUF=new UnixFile(workUF, "Tomcat-Apache", false);
                } else {
                    innerWorkUF=null;
                }

                boolean needRestart=false;
                if (!wwwGroupDirUF.getStat(tempStat).exists() || wwwGroupDirUF.getStat(tempStat).getUID() == UnixFile.ROOT_GID) {

                    final String wwwGroupDir = wwwGroupDirUF.getFilename();
                    final int mgroup = (tomcat.isSecure() ? 0750 : 0770);
                    final int mprivate = (tomcat.isSecure() ? 0700 : 0770);

                    // Create the /wwwgroup/name/...

                    // 001
                    if (!wwwGroupDirUF.getStat(tempStat).exists()) wwwGroupDirUF.mkdir();
                    wwwGroupDirUF.setMode(tomcat.isSecure() ? 0750 : 0770);
                    new UnixFile(wwwGroupDirUF, "bin", false).mkdir().chown(lsaUID, lsgGID).setMode(mgroup);
                    new UnixFile(wwwGroupDirUF, "conf", false).mkdir().chown(lsaUID, lsgGID).setMode(tomcat.isSecure() ? 0750 : 0770);
                    UnixFile daemonUF = new UnixFile(wwwGroupDirUF, "daemon", false).mkdir().chown(lsaUID, lsgGID).setMode(mprivate);
                    if(tomcat.getDisableLog()==null) new UnixFile(daemonUF, "tomcat", false).symLink("../bin/tomcat").chown(lsaUID, lsgGID);
                    if(isTomcat4) {
                        ln("var/log", wwwGroupDir+"/logs", lsaUID, lsgGID);
                        mkdir(wwwGroupDir+"/temp", mprivate, lsa, lsg);
                    }
                    UnixFile varUF = new UnixFile(wwwGroupDirUF, "var", false).mkdir().chown(lsaUID, lsgGID).setMode(isTomcat4?mgroup:mprivate);
                    new UnixFile(varUF, "log", false).mkdir().chown(lsaUID, lsgGID).setMode(isTomcat4?mgroup:mprivate);
                    new UnixFile(varUF, "run", false).mkdir().chown(lsaUID, lsgGID).setMode(0700);
                    
                    workUF.mkdir().chown(lsaUID, lsgGID).setMode(0750);
                    if(innerWorkUF!=null) {
                        mkdir(innerWorkUF.getFilename(), 0750, lsa, lsg);
                    }

                    PostgresServer postgresServer=aoServer.getPreferredPostgresServer();
                    String postgresServerMinorVersion=postgresServer==null?null:postgresServer.getPostgresVersion().getMinorVersion();

                    if(isTomcat4) {
                        ln("../../.."+tomcatDirectory+"/bin/bootstrap.jar", wwwGroupDir+"/bin/bootstrap.jar", lsaUID, lsgGID);
                        ln("../../.."+tomcatDirectory+"/bin/catalina.sh", wwwGroupDir+"/bin/catalina.sh", lsaUID, lsgGID);
                        //UnixFile catalinaUF=new UnixFile(wwwGroupDir+"/bin/catalina.sh");
                        //new UnixFile(tomcatDirectory+"/bin/catalina.sh").copyTo(catalinaUF, false);
                        //catalinaUF.chown(lsaUID, lsgGID).setMode(tomcat.isSecure()?0700:0740);
                        if(
                            version.startsWith(HttpdTomcatVersion.VERSION_4_1_PREFIX)
                            || isTomcat55
                        ) {
                            ln("../../.."+tomcatDirectory+"/bin/commons-daemon.jar", wwwGroupDir+"/bin/commons-daemon.jar", lsaUID, lsgGID);
                            if(isTomcat55) ln("../../.."+tomcatDirectory+"/bin/commons-logging-api.jar", wwwGroupDir+"/bin/commons-logging-api.jar", lsaUID, lsgGID);
                            if(!isTomcat55) ln("../../.."+tomcatDirectory+"/bin/jasper.sh", wwwGroupDir+"/bin/jasper.sh", lsaUID, lsgGID);
                            if(!isTomcat55) ln("../../.."+tomcatDirectory+"/bin/jspc.sh", wwwGroupDir+"/bin/jspc.sh", lsaUID, lsgGID);
                        }
                        ln("../../.."+tomcatDirectory+"/bin/digest.sh", wwwGroupDir+"/bin/digest.sh", lsaUID, lsgGID);
                    }

                    String profileFile = wwwGroupDir + "/bin/profile";
                    LinuxAccountManager.setBashProfile(lsa, profileFile);

                    UnixFile profileUF = new UnixFile(profileFile);
                    ChainWriter out = new ChainWriter(
                        new BufferedOutputStream(
                            profileUF.getSecureOutputStream(lsaUID, lsgGID, 0750, false)
                        )
                    );
		    try {
			out.print("#!/bin/sh\n"
				  + "\n");

			if(isTomcat4) {
			    out.print(". /etc/profile\n");
                            if (version.startsWith(HttpdTomcatVersion.VERSION_5_5_PREFIX)) {
                                if(osv==OperatingSystemVersion.REDHAT_ES_4_X86_64) {
                                    out.print(". /opt/aoserv-client/scripts/").print(DEFAULT_TOMCAT_5_JDK_VERSION).print(".sh\n");
                                } else {
                                    out.print(". /usr/aoserv/etc/").print(DEFAULT_TOMCAT_5_JDK_VERSION).print(".sh\n");
                                }
                            } else {
                                // Note: Pre Tomcat 5.5 not installed on RedHat ES4
                                out.print(". /usr/aoserv/etc/").print(getDefaultJdkVersion(osv)).print(".sh\n");
                            }
                            String defaultPhpVersion = getDefaultPhpVersion(osv);
                            if(defaultPhpVersion!=null) {
                                if(osv==OperatingSystemVersion.REDHAT_ES_4_X86_64) {
                                    out.print(". /opt/aoserv-client/scripts/php-").print(defaultPhpVersion).print(".sh\n");
                                } else {
                                    out.print(". /usr/aoserv/etc/php-").print(defaultPhpVersion).print(".sh\n");
                                }
                            }
			    if(postgresServerMinorVersion!=null) {
                                if(osv==OperatingSystemVersion.REDHAT_ES_4_X86_64) {
                                    out.print(". /opt/aoserv-client/scripts/postgresql-").print(postgresServerMinorVersion).print(".sh\n");
                                } else {
                                    out.print(". /usr/aoserv/etc/postgresql-").print(postgresServerMinorVersion).print(".sh\n");
                                }
                            }
                            if(osv==OperatingSystemVersion.REDHAT_ES_4_X86_64) {
                                out.print(". /opt/aoserv-client/scripts/aoserv-client.sh\n");
                            } else {
                                out.print(". /usr/aoserv/etc/aoserv.sh\n");
                            }
                            out.print("\n"
				      + "umask 002\n"
				      + "export DISPLAY=:0.0\n"
				      + "\n"
				      + "export CATALINA_BASE=\"").print(wwwGroupDir).print("\"\n"
                                      + "export CATALINA_HOME=\"").print(wwwGroupDir).print("\"\n"
                                      + "export CATALINA_TEMP=\"").print(wwwGroupDir).print("/temp\"\n"
																		  );
			} else if (version.startsWith(HttpdTomcatVersion.VERSION_3_2_PREFIX) || version.startsWith(HttpdTomcatVersion.VERSION_3_1_PREFIX)) {
                            out.print(". /etc/profile\n");
                            if(tomcat.isSecure()) {
                                out.print(". /usr/aoserv/etc/jdk1.3.1_03.sh\n");
                            } else {
                                out.print(". /usr/aoserv/etc/").print(getDefaultJdkVersion(osv)).print(".sh\n");
                            }
                            out.print(". /usr/aoserv/etc/jakarta-oro-2.0.1.sh\n"
                                    + ". /usr/aoserv/etc/jakarta-regexp-1.2.sh\n"
                                    + ". /usr/aoserv/etc/jakarta-servletapi-3.2.sh\n"
                                    + ". /usr/aoserv/etc/jakarta-tomcat-3.2.sh\n"
                                    + ". /usr/aoserv/etc/jetspeed-1.1.sh\n"
                                    + ". /usr/aoserv/etc/cocoon-1.8.2.sh\n"
                                    + ". /usr/aoserv/etc/xerces-1.2.0.sh\n"
                                    + ". /usr/aoserv/etc/ant-1.6.2.sh\n"
                                    + ". /usr/aoserv/etc/xalan-1.2.d02.sh\n");
                            String defaultPhpVersion = getDefaultPhpVersion(osv);
                            if(defaultPhpVersion!=null) {
                                if(osv==OperatingSystemVersion.REDHAT_ES_4_X86_64) {
                                    out.print(". /opt/aoserv-client/scripts/php-").print(defaultPhpVersion).print(".sh\n");
                                } else {
                                    out.print(". /usr/aoserv/etc/php-").print(defaultPhpVersion).print(".sh\n");
                                }
                            }
                            if(postgresServerMinorVersion!=null) out.print(". /usr/aoserv/etc/postgresql-").print(postgresServerMinorVersion).print(".sh\n");
                            out.print(". /usr/aoserv/etc/profile\n"
                                    + ". /usr/aoserv/etc/fop-0.15.0.sh\n");
                        } else throw new IllegalArgumentException("Unknown version of Tomcat: " + version);

                        out.print("\n"
                                + "export PATH=${PATH}:").print(wwwGroupDir).print("/bin\n"
                                + "\n");
                        if(!isTomcat4 && !isTomcat55) {
                            out.print("# Add site group classes\n"
                                    + "CLASSPATH=${CLASSPATH}:").print(wwwGroupDir).print("/classes\n"
                                    + "for i in ").print(wwwGroupDir).print("/lib/* ; do\n"
                                    + "    if [ -f $i ]; then\n"
                                    + "        CLASSPATH=${CLASSPATH}:$i\n"
                                    + "    fi\n"
                                    + "done\n"
                                    + "\n");
                        }
                        out.print(". "+HttpdSharedTomcat.WWW_GROUP_DIR+'/').print(tomcat.getName()).print("/bin/profile.sites\n"
                                + "\n"
                                + "for SITE in $SITES ; do\n"
                                + "    export PATH=${PATH}:"+HttpdSite.WWW_DIRECTORY+"/${SITE}/bin\n");
                        if(!isTomcat4) {
                            out.print("    CLASSPATH=${CLASSPATH}:"+HttpdSite.WWW_DIRECTORY+"/${SITE}/classes\n"
                                    + "\n"
                                    + "    for i in "+HttpdSite.WWW_DIRECTORY+"/${SITE}/lib/* ; do\n"
                                    + "        if [ -f $i ]; then\n"
                                    + "            CLASSPATH=${CLASSPATH}:$i\n"
                                    + "        fi\n"
                                    + "    done\n");
                        }
                        out.print("done\n"
                                + "export CLASSPATH\n");
		    } finally {
			out.flush();
			out.close();
		    }

                    // 004

                    UnixFile tomcatUF = new UnixFile(wwwGroupDir + "/bin/tomcat");
                    out=new ChainWriter(
                        new BufferedOutputStream(
                            tomcatUF.getSecureOutputStream(lsaUID, lsgGID, 0700, false)
                        )
                    );
		    try {
                    out.print("#!/bin/sh\n"
                            + "\n"
                            + "TOMCAT_HOME=").print(wwwGroupDir).print("\n"
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
                            + "        . $TOMCAT_HOME/bin/profile\n"
                            + "        if [ \"$SITES\" != \"\" ]; then\n"
                            + "            cd $TOMCAT_HOME\n"
                    );
                    if(isTomcat4) {
                        out.print("            ${TOMCAT_HOME}/bin/catalina.sh stop 2>&1 >>${TOMCAT_HOME}/var/log/tomcat_err\n");
                    } else if(tomcat.isSecure()) {
                        out.print("            java com.aoindustries.apache.tomcat.SecureVirtualTomcat stop $SITES &>/dev/null\n");
                    } else out.print("            java com.aoindustries.apache.tomcat.VirtualTomcat stop $SITES &>/dev/null\n");
                    out.print("        fi\n"
                            + "        kill `cat ${TOMCAT_HOME}/var/run/java.pid` &>/dev/null\n"
                            + "        rm -f ${TOMCAT_HOME}/var/run/java.pid\n"
                            + "    fi\n"
                            + "elif [ \"$1\" = \"daemon\" ]; then\n"
                            + "    cd $TOMCAT_HOME\n"
                            + "    . $TOMCAT_HOME/bin/profile\n"
                            + "\n"
                            + "    # Get rid of sites without servlet container content\n"
                            + "    SITES=`/usr/aoserv/sbin/filtersites $SITES`\n"
                            + "\n"
                            + "    if [ \"$SITES\" != \"\" ]; then\n"
                            + "        while [ 1 ]; do\n"
                    );
                    if(isTomcat4) {
                        if(tomcat.isSecure()) {
                            out.print("            ${TOMCAT_HOME}/bin/build_catalina_policy >${TOMCAT_HOME}/conf/catalina.policy\n"
                                    + "            chmod 600 ${TOMCAT_HOME}/conf/catalina.policy\n"
                                    + "            mv -f ${TOMCAT_HOME}/var/log/tomcat_err ${TOMCAT_HOME}/var/log/tomcat_err.old\n"
                                    + "            ${TOMCAT_HOME}/bin/catalina.sh run -security >&${TOMCAT_HOME}/var/log/tomcat_err &\n");
                        } else {
                            out.print("            mv -f ${TOMCAT_HOME}/var/log/tomcat_err ${TOMCAT_HOME}/var/log/tomcat_err.old\n"
                                    + "            ${TOMCAT_HOME}/bin/catalina.sh run >&${TOMCAT_HOME}/var/log/tomcat_err &\n");
                        }
                    } else {
                        if(tomcat.isSecure()) out.print("            java com.aoindustries.apache.tomcat.SecureVirtualTomcat start $SITES &>var/log/servlet_err &\n");
                        else out.print("            java com.aoindustries.apache.tomcat.VirtualTomcat start $SITES &>var/log/servlet_err &\n");
                    }
                    out.print("            echo $! >var/run/java.pid\n"
                            + "            wait\n"
                            + "            RETCODE=$?\n"
                            + "            echo \"`date`: JVM died with a return code of $RETCODE, restarting in 5 seconds\" >>${TOMCAT_HOME}/var/log/jvm_crashes.log\n"
                            + "            sleep 5\n"
                            + "        done\n"
                            + "    fi\n"
                            + "    rm -f ${TOMCAT_HOME}/var/run/tomcat.pid\n"
                            + "else\n"
                            + "    echo \"Usage:\"\n"
                            + "    echo \"tomcat {start|stop}\"\n"
                            + "    echo \"        start - start tomcat\"\n"
                            + "    echo \"        stop  - stop tomcat\"\n"
                            + "fi\n"
                    );
		    } finally {
			out.flush();
			out.close();
		    }

                    if(isTomcat4) {
                        ln("../../.."+tomcatDirectory+"/bin/setclasspath.sh", wwwGroupDir+"/bin/setclasspath.sh", lsaUID, lsgGID);

                        UnixFile shutdown=new UnixFile(wwwGroupDir+"/bin/shutdown.sh");
                        out=new ChainWriter(shutdown.getSecureOutputStream(lsaUID, lsgGID, 0700, true));
			try {
			    out.print("#!/bin/sh\n"
				      + "exec ").print(wwwGroupDir).print("/bin/tomcat stop\n");
			} finally {
			    out.flush();
			    out.close();
			}

                        UnixFile startup=new UnixFile(wwwGroupDir+"/bin/startup.sh");
                        out=new ChainWriter(startup.getSecureOutputStream(lsaUID, lsgGID, 0700, true));
			try {
			    out.print("#!/bin/sh\n"
				      + "exec ").print(wwwGroupDir).print("/bin/tomcat start\n");
			} finally {
			    out.flush();
			    out.close();
			}

                        if(
                            version.startsWith(HttpdTomcatVersion.VERSION_4_1_PREFIX)
                            || isTomcat55
                        ) {
                            if(!isTomcat55) ln("../../.."+tomcatDirectory+"/bin/tomcat-jni.jar", wwwGroupDir+"/bin/tomcat-jni.jar", lsaUID, lsgGID);
                            if(isTomcat55) ln("../../.."+tomcatDirectory+"/bin/tomcat-juli.jar", wwwGroupDir+"/bin/tomcat-juli.jar", lsaUID, lsgGID);
                            ln("../../.."+tomcatDirectory+"/bin/tool-wrapper.sh", wwwGroupDir+"/bin/tool-wrapper.sh", lsaUID, lsgGID);
                            if(isTomcat55) ln("../../.."+tomcatDirectory+"/bin/version.sh", wwwGroupDir+"/bin/version.sh", lsaUID, lsgGID);
                        }

                        /*
                         * The classes directory
                         */
                        if(
                            !version.startsWith(HttpdTomcatVersion.VERSION_4_1_PREFIX)
                            && !isTomcat55
                        ) new UnixFile(wwwGroupDirUF, "classes", false).mkdir().chown(lsaUID, lsgGID).setMode(mgroup);

                        /*
                         * Create the common directory and all contents
                         */
                        mkdir(wwwGroupDir+"/common", tomcat.isSecure()?0750:0770, lsa, lsg);
                        mkdir(wwwGroupDir+"/common/classes", tomcat.isSecure()?0750:0770, lsa, lsg);
                        mkdir(wwwGroupDir+"/common/endorsed", tomcat.isSecure()?0750:0770, lsa, lsg);
                        lnAll("../../../.."+tomcatDirectory+"/common/endorsed/", wwwGroupDir+"/common/endorsed/", lsaUID, lsgGID);
                        mkdir(wwwGroupDir+"/common/i18n", tomcat.isSecure()?0750:0770, lsa, lsg);
                        if(isTomcat55) lnAll("../../../.."+tomcatDirectory+"/common/i18n/", wwwGroupDir+"/common/i18n/", lsaUID, lsgGID);
                        mkdir(wwwGroupDir+"/common/lib", tomcat.isSecure()?0750:0770, lsa, lsg);
                        lnAll("../../../.."+tomcatDirectory+"/common/lib/", wwwGroupDir+"/common/lib/", lsaUID, lsgGID);
                        
                        if(postgresServerMinorVersion!=null) {
                            if(osv==OperatingSystemVersion.MANDRIVA_2006_0_I586) {
                                ln("../../../../usr/postgresql/"+postgresServerMinorVersion+"/share/java/postgresql.jar", wwwGroupDir+"/common/lib/postgresql.jar", lsaUID, lsgGID);
                            } else if(osv==OperatingSystemVersion.REDHAT_ES_4_X86_64) {
                                ln("../../../../opt/postgresql-"+postgresServerMinorVersion+"/share/java/postgresql.jar", wwwGroupDir+"/common/lib/postgresql.jar", lsaUID, lsgGID);
                            } else throw new SQLException("Unsupported OperatingSystemVersion: "+osv);
                        }
                        if(osv==OperatingSystemVersion.MANDRIVA_2006_0_I586) {
                            ln("../../../../usr/mysql-connector-java/3.1.12/mysql-connector-java-3.1.12-bin.jar", wwwGroupDir+"/common/lib/mysql-connector-java-3.1.12-bin.jar", lsaUID, lsgGID);
                        } else if(osv==OperatingSystemVersion.REDHAT_ES_4_X86_64) {
                            ln("../../../../opt/mysql-connector-java-3.1.12/mysql-connector-java-3.1.12-bin.jar", wwwGroupDir+"/common/lib/mysql-connector-java-3.1.12-bin.jar", lsaUID, lsgGID);
                        } else throw new SQLException("Unsupported OperatingSystemVersion: "+osv);

                        /*
                         * Write the conf/catalina.policy file
                         */
                        if(!tomcat.isSecure()) {
                            {
                                UnixFile cp=new UnixFile(wwwGroupDir+"/conf/catalina.policy");
                                new UnixFile(tomcatDirectory+"/conf/catalina.policy").copyTo(cp, false);
                                cp.chown(lsaUID, lsgGID).setMode(0660);
                            }
                            if(isTomcat55) {
                                UnixFile cp=new UnixFile(wwwGroupDir+"/conf/catalina.properties");
                                new UnixFile(tomcatDirectory+"/conf/catalina.properties").copyTo(cp, false);
                                cp.chown(lsaUID, lsgGID).setMode(0660);
                            }
                            if(isTomcat55) {
                                UnixFile cp=new UnixFile(wwwGroupDir+"/conf/context.xml");
                                new UnixFile(tomcatDirectory+"/conf/context.xml").copyTo(cp, false);
                                cp.chown(lsaUID, lsgGID).setMode(0660);
                            }
                            if(isTomcat55) {
                                UnixFile cp=new UnixFile(wwwGroupDir+"/conf/logging.properties");
                                new UnixFile(tomcatDirectory+"/conf/logging.properties").copyTo(cp, false);
                                cp.chown(lsaUID, lsgGID).setMode(0660);
                            }
                        }

                        /*
                         * Create the tomcat-users.xml file
                         */
                        UnixFile tuUF=new UnixFile(wwwGroupDir+"/conf/tomcat-users.xml");
                        new UnixFile(tomcatDirectory+"/conf/tomcat-users.xml").copyTo(tuUF, false);
                        tuUF.chown(lsaUID, lsgGID).setMode(tomcat.isSecure()?0640:0660);

                        /*
                         * Create the web.xml file.
                         */
                        UnixFile webUF=new UnixFile(wwwGroupDir+"/conf/web.xml");
                        new UnixFile(tomcatDirectory+"/conf/web.xml").copyTo(webUF, false);
                        webUF.chown(lsaUID, lsgGID).setMode(tomcat.isSecure()?0640:0660);

                        /*
                         * Create /lib
                         */
                        if(!isTomcat4 && !isTomcat55) {
                            new UnixFile(wwwGroupDirUF, "lib", false).mkdir().chown(lsaUID, lsgGID).setMode(mgroup);
                            lnAll("../../.."+tomcatDirectory+"/lib/", wwwGroupDir+"/lib/", lsaUID, lsgGID);
                            ln("../../.."+tomcatDirectory+"/lib/jasper-runtime.jar", wwwGroupDir+"/lib/jasper-runtime.jar", lsaUID, lsgGID);
                            if(postgresServerMinorVersion!=null) {
                                if(osv==OperatingSystemVersion.MANDRIVA_2006_0_I586) {
                                    ln("../../../usr/postgresql/"+postgresServerMinorVersion+"/share/java/postgresql.jar", wwwGroupDir+"/lib/postgresql.jar", lsaUID, lsgGID);
                                } else if(osv==OperatingSystemVersion.REDHAT_ES_4_X86_64) {
                                    ln("../../../opt/postgresql-"+postgresServerMinorVersion+"/share/java/postgresql.jar", wwwGroupDir+"/lib/postgresql.jar", lsaUID, lsgGID);
                                } else throw new SQLException("Unsupported OperatingSystemVersion: "+osv);
                            }
                            if(osv==OperatingSystemVersion.MANDRIVA_2006_0_I586) {
                                ln("../../../usr/mysql-connector-java/3.1.12/mysql-connector-java-3.1.12-bin.jar", wwwGroupDir+"/lib/mysql-connector-java-3.1.12-bin.jar", lsaUID, lsgGID);
                            } else if(osv==OperatingSystemVersion.REDHAT_ES_4_X86_64) {
                                ln("../../../../opt/mysql-connector-java-3.1.12/mysql-connector-java-3.1.12-bin.jar", wwwGroupDir+"/common/lib/mysql-connector-java-3.1.12-bin.jar", lsaUID, lsgGID);
                            }
                            else throw new SQLException("Unsupported OperatingSystemVersion: "+osv);
                        }
                        mkdir(wwwGroupDir+"/server", tomcat.isSecure()?0750:0770, lsa, lsg);
                        mkdir(wwwGroupDir+"/server/classes", tomcat.isSecure()?0750:0770, lsa, lsg);
                        mkdir(wwwGroupDir+"/server/lib", tomcat.isSecure()?0750:0770, lsa, lsg);
                        lnAll("../../../.."+tomcatDirectory+"/server/lib/", wwwGroupDir+"/server/lib/", lsaUID, lsgGID);

                        if(
                            version.startsWith(HttpdTomcatVersion.VERSION_4_1_PREFIX)
                            || isTomcat55
                        ) {
                            mkdir(wwwGroupDir+"/server/webapps", tomcat.isSecure()?0750:0770, lsa, lsg);
                        }
                    }

                    /*
                     * The shared directory
                     */
                    if(
                        version.startsWith(HttpdTomcatVersion.VERSION_4_1_PREFIX)
                        || isTomcat55
                    ) {
                        mkdir(wwwGroupDir+"/shared", tomcat.isSecure()?0750:0770, lsa, lsg);
                        mkdir(wwwGroupDir+"/shared/classes", tomcat.isSecure()?0750:0770, lsa, lsg);
                        mkdir(wwwGroupDir+"/shared/lib", tomcat.isSecure()?0750:0770, lsa, lsg);
                    }

                    //  008
                    if(!isTomcat4) {
                        UnixFile servErrUF = new UnixFile(varUF, "log/servlet_err", false);
                        servErrUF.getSecureOutputStream(lsaUID, lsgGID, 0640, false).close();
                    }

                    // Set the ownership to avoid future rebuilds of this directory
                    wwwGroupDirUF.chown(lsaUID, lsgGID);
                    
                    needRestart=true;
                }

                // always rebuild profile.sites file
                UnixFile newSitesFileUF = new UnixFile(wwwGroupDirUF, "bin/profile.sites.new", false);
                ChainWriter out = new ChainWriter(
                    new BufferedOutputStream(
                        newSitesFileUF.getSecureOutputStream(lsaUID, lsgGID, 0750, true)
                    )
                );
		List<HttpdTomcatSharedSite> sites = tomcat.getHttpdTomcatSharedSites();
		try {
		    out.print("export SITES=\"");
		    boolean didOne=false;
		    for(int j = 0; j< sites.size(); j++) {
			HttpdSite hs=sites.get(j).getHttpdTomcatSite().getHttpdSite();
			if(hs.getDisableLog()==null) {
			    if (didOne) out.print(' ');
			    else didOne=true;
			    out.print(hs.getSiteName());
			}
		    }
		    out.print("\"\n");
		} finally {
		    out.flush();
		    out.close();
		}
                // flag as needing a restart if this file is different than any existing
                UnixFile sitesFile = new UnixFile(wwwGroupDirUF, "bin/profile.sites", false);
                if(!sitesFile.getStat(tempStat).exists() || !newSitesFileUF.contentEquals(sitesFile)) {
                    needRestart=true;
                    UnixFile backupFile=new UnixFile(wwwGroupDirUF, "bin/profile.sites.old", false);
                    if(sitesFile.getStat(tempStat).exists()) sitesFile.renameTo(backupFile);
                    newSitesFileUF.renameTo(sitesFile);
                } else newSitesFileUF.delete();

                if(isTomcat4 && tomcat.isSecure()) {
                    UnixFile buildPolicy=new UnixFile(wwwGroupDirUF, "bin/build_catalina_policy", false);
                    if(!tomcat.isManual() || buildPolicy.getStat(tempStat).exists()) {
                        // always rebuild the bin/build_catalina_policy script for secured and auto JVMs
                        UnixFile newBuildPolicy=new UnixFile(wwwGroupDirUF, "bin/build_catalina_policy.new", false);
                        out=new ChainWriter(
                            new BufferedOutputStream(
                                newBuildPolicy.getSecureOutputStream(lsaUID, lsgGID, 0700, true)
                            )
                        );
			try {
			    out.print("#!/bin/sh\n"
				      + ". /usr/aoserv/sbin/build_catalina_policy\n"
				      + "buildCatalinaPolicy '"+HttpdSharedTomcat.WWW_GROUP_DIR+'/').print(tomcat.getName()).print("' ")
                                .print(tomcat.getTomcat4Worker().getNetBind().getPort().getPort()).print(' ')
                                .print(tomcat.getTomcat4ShutdownPort().getPort().getPort());
			    for (int j = 0; j< sites.size(); j++) {
				HttpdSite hs=sites.get(j).getHttpdTomcatSite().getHttpdSite();
				if(hs.getDisableLog()==null) out.print(" '").print(hs.getSiteName()).print('\'');
			    }
			    out.print('\n');
			} finally {
			    out.flush();
			    out.close();
			}

                        // flag as needing a restart if this file is different than any existing
                        if(!buildPolicy.getStat(tempStat).exists() || !newBuildPolicy.contentEquals(buildPolicy)) {
                            needRestart=true;
                            UnixFile backupBuildPolicy = new UnixFile(wwwGroupDirUF, "bin/build_catalina_policy.old", false);
                            if(buildPolicy.getStat(tempStat).exists()) buildPolicy.renameTo(backupBuildPolicy);
                            newBuildPolicy.renameTo(buildPolicy);
                        } else newBuildPolicy.delete();
                    }
                }

                // make work directories and remove extra work dirs
                List<String> workFiles = new SortedArrayList<String>();
                String[] wlist = (innerWorkUF==null?workUF:innerWorkUF).getFile().list();
		if(wlist!=null) {
		    for (int j = 0; j<wlist.length; j++) {
			workFiles.add(wlist[j]);
		    }
		}
                for (int j = 0; j< sites.size(); j++) {
                    HttpdSite hs=sites.get(j).getHttpdTomcatSite().getHttpdSite();
                    if(hs.getDisableLog()==null) {
                        String subwork= isTomcat4?hs.getPrimaryHttpdSiteURL().getHostname():hs.getSiteName();
                        workFiles.remove(subwork);
                        UnixFile workDir = new UnixFile((innerWorkUF==null?workUF:innerWorkUF), subwork, false);
                        if (!workDir.getStat(tempStat).exists()) {
                            workDir
                                .mkdir()
                                .chown(
                                    lsaUID, 
                                    sites.get(j)
                                        .getHttpdTomcatSite()
                                        .getHttpdSite()
                                        .getLinuxServerGroup()
                                        .getGID()
                                        .getID()
                                )
                                .setMode(0750)
                            ;
                        }
                    }
                }
                for (int c = 0; c < workFiles.size(); c++) {
                    deleteFileList.add(new File((innerWorkUF==null?workUF:innerWorkUF).getFile(), workFiles.get(c)));
                }

                // Make sure the newly created or existing web site is not removed
                wwwGroupDirectories.remove(tomcat.getName());

                /*
                 * Add the web server config files if they do not exist.
                 */
                if(osv!=OperatingSystemVersion.REDHAT_ES_4_X86_64 && osv!=OperatingSystemVersion.MANDRIVA_2006_0_I586) {
                    // The shared config part
                    String config = CONF_WWWGROUPS + '/' + tomcat.getName();
                    if(groupConfFiles!=null) groupConfFiles.remove(tomcat.getName());
                    UnixFile configFile = new UnixFile(config);
                    if (!configFile.getStat(tempStat).exists()) {
                        // Add the /etc/httpd/conf/wwwgroup/shared_tomcat_name file
                        out=new ChainWriter(
                            configFile.getSecureOutputStream(UnixFile.ROOT_UID, lsgGID, 0640, false)
                        );
                        try {
                            if(!isTomcat4) {
                                out.print("<IfModule mod_jserv.c>\n"
                                          + "    ApJServDefaultProtocol ajpv12\n"
                                          + "    ApJServSecretKey DISABLED\n"
                                          + "    ApJServMountCopy on\n"
                                          + "    ApJServLogLevel notice\n"
                                          + "    ApJServMount default /root\n"
                                          + "    AddType text/do .do\n"
                                          + "    AddHandler jserv-servlet .do\n"
                                          + "    AddType text/jsp .jsp\n"
                                          + "    AddHandler jserv-servlet .jsp\n"
                                          + "    AddType text/xml .xml\n"
                                          + "    AddHandler jserv-servlet .xml\n"
                                          + "    <IfModule mod_rewrite.c>\n"
                                          + "        RewriteEngine on\n"
                                          + "        RewriteRule ^(/.*;jsessionid=.*)$ $1 [T=jserv-servlet]\n"
                                          + "    </IfModule>\n"
                                          + "</IfModule>\n"
                                          + "\n");
                            }
                        } finally {
                            out.flush();
                            out.close();
                        }
                    }
                }

                // Rebuild the server.xml for Tomcat 4 and Tomcat 5 JVMs
                if(isTomcat4) {
                    String autoWarning=getAutoWarning(tomcat);
                    String confServerXML=HttpdSharedTomcat.WWW_GROUP_DIR+'/'+tomcat.getName()+"/conf/server.xml";
                    UnixFile confServerXMLUF=new UnixFile(confServerXML);
                    if(!tomcat.isManual() || !confServerXMLUF.getStat(tempStat).exists()) {
                        String newConfServerXML=HttpdSharedTomcat.WWW_GROUP_DIR+'/'+tomcat.getName()+"/conf/server.xml.new";
                        UnixFile newConfServerXMLUF=new UnixFile(newConfServerXML);
                        out=new ChainWriter(
                            new BufferedOutputStream(
                                newConfServerXMLUF.getSecureOutputStream(lsaUID, lsgGID, tomcat.isSecure()?0600:0660, true)
                            )
                        );
			try {
                            HttpdWorker hw=tomcat.getTomcat4Worker();
                            if(!tomcat.isManual()) out.print(autoWarning);
                            NetBind shutdownPort = tomcat.getTomcat4ShutdownPort();
                            if(shutdownPort==null) throw new SQLException("Unable to find shutdown key for HttpdSharedTomcat: "+tomcat);
                            String shutdownKey=tomcat.getTomcat4ShutdownKey();
                            if(shutdownKey==null) throw new SQLException("Unable to find shutdown key for HttpdSharedTomcat: "+tomcat);
                            out.print("<Server port=\"").print(shutdownPort.getPort().getPort()).print("\" shutdown=\"").print(shutdownKey).print("\" debug=\"0\">\n");
                            if(isTomcat55) {
                                out.print("  <GlobalNamingResources>\n"
                                        + "    <Resource name=\"UserDatabase\" auth=\"Container\"\n"
                                        + "              type=\"org.apache.catalina.UserDatabase\"\n"
                                        + "       description=\"User database that can be updated and saved\"\n"
                                        + "           factory=\"org.apache.catalina.users.MemoryUserDatabaseFactory\"\n"
                                        + "          pathname=\"conf/tomcat-users.xml\" />\n"
                                        + "   </GlobalNamingResources>\n");
                            } else if(version.startsWith(HttpdTomcatVersion.VERSION_4_1_PREFIX)) {
                                out.print("  <Listener className=\"org.apache.catalina.mbeans.ServerLifecycleListener\" debug=\"0\"/>\n"
                                        + "  <Listener className=\"org.apache.catalina.mbeans.GlobalResourcesLifecycleListener\" debug=\"0\"/>\n"
                                        + "  <GlobalNamingResources>\n"
                                        + "    <Resource name=\"UserDatabase\" auth=\"Container\" type=\"org.apache.catalina.UserDatabase\" description=\"User database that can be updated and saved\"/>\n"
                                        + "    <ResourceParams name=\"UserDatabase\">\n"
                                        + "      <parameter>\n"
                                        + "        <name>factory</name>\n"
                                        + "        <value>org.apache.catalina.users.MemoryUserDatabaseFactory</value>\n"
                                        + "      </parameter>\n"
                                        + "      <parameter>\n"
                                        + "        <name>pathname</name>\n"
                                        + "        <value>conf/tomcat-users.xml</value>\n"
                                        + "      </parameter>\n"
                                        + "    </ResourceParams>\n"
                                        + "  </GlobalNamingResources>\n");
                            }
                            out.print("  <Service name=\"").print(isTomcat55 ? "Catalina" : "Tomcat-Apache").print("\">\n"
                                    + "    <Connector\n");
                                      /*
                                    + "      className=\"org.apache.coyote.tomcat4.CoyoteConnector\"\n"
                                    + "      port=\"").print(hw.getNetBind().getPort().getPort()).print("\"\n"
                                    + "      minProcessors=\"2\"\n"
                                    + "      maxProcessors=\"200\"\n"
                                    + "      enableLookups=\"true\"\n"
                                    + "      redirectPort=\"443\"\n"
                                    + "      acceptCount=\"10\"\n"
                                    + "      debug=\"0\"\n"
                                    + "      connectionTimeout=\"20000\"\n"
                                    + "      useURIValidationHack=\"false\"\n"
                                    + "      protocolHandlerClassName=\"org.apache.jk.server.JkCoyoteHandler\"\n"
                                      */
                            if (version.startsWith(HttpdTomcatVersion.VERSION_4_1_PREFIX)) {
                                out.print("      className=\"org.apache.ajp.tomcat4.Ajp13Connector\"\n");
                            }
                            out.print("      port=\"").print(hw.getNetBind().getPort().getPort()).print("\"\n");
                            if(isTomcat55) out.print("      enableLookups=\"false\"\n");
                            out.print("      minProcessors=\"2\"\n"
                                    + "      maxProcessors=\"200\"\n"
                                    + "      address=\""+IPAddress.LOOPBACK_IP+"\"\n"
                                    + "      acceptCount=\"10\"\n"
                                    + "      debug=\"0\"\n"
                                    + "      protocol=\"AJP/1.3\"\n"
                                    + "    />\n"
                                    + "    <Engine name=\"").print(isTomcat55 ? "Catalina" : "Tomcat-Apache").print("\" defaultHost=\"localhost\" debug=\"0\">\n");
                            if(isTomcat55) {
                                out.print("      <Realm className=\"org.apache.catalina.realm.UserDatabaseRealm\"\n"
                                        + "          resourceName=\"UserDatabase\" />\"\n");
                            } else if(version.startsWith(HttpdTomcatVersion.VERSION_4_1_PREFIX)) {
                                out.print("      <Logger\n"
                                        + "        className=\"org.apache.catalina.logger.FileLogger\"\n"
                                        + "        directory=\"var/log\"\n"
                                        + "        prefix=\"catalina_log.\"\n"
                                        + "        suffix=\".txt\"\n"
                                        + "        timestamp=\"true\"\n"
                                        + "      />\n"
                                        + "      <Realm className=\"org.apache.catalina.realm.UserDatabaseRealm\" debug=\"0\" resourceName=\"UserDatabase\" />\n");
                            } else {
                                out.print("      <Realm className=\"org.apache.catalina.realm.MemoryRealm\" />\n");
                            }
                            for(int c=0;c<sites.size();c++) {
                                HttpdSite hs=sites.get(c).getHttpdTomcatSite().getHttpdSite();
                                if(hs.getDisableLog()==null) {
                                    String primaryHostname=hs.getPrimaryHttpdSiteURL().getHostname();
                                    out.print("      <Host\n"
                                            + "        name=\"").print(primaryHostname).print("\"\n"
                                            + "        debug=\"0\"\n"
                                            + "        appBase=\""+HttpdSite.WWW_DIRECTORY+'/').print(hs.getSiteName()).print("/webapps\"\n"
                                            + "        unpackWARs=\"true\"\n");
                                    if(
                                        version.startsWith(HttpdTomcatVersion.VERSION_4_1_PREFIX)
                                        || isTomcat55
                                    ) {
                                        out.print("        autoDeploy=\"true\"\n");
                                    }
                                    if(isTomcat55) {
                                        out.print("        xmlValidation=\"false\"\n"
                                                + "        xmlNamespaceAware=\"false\"\n");
                                    }
                                    out.print("      >\n");
                                    List<String> usedHostnames=new SortedArrayList<String>();
                                    usedHostnames.add(primaryHostname);
                                    List<HttpdSiteBind> binds=hs.getHttpdSiteBinds();
                                    for(int d=0;d<binds.size();d++) {
                                        HttpdSiteBind bind=binds.get(d);
                                        List<HttpdSiteURL> urls=bind.getHttpdSiteURLs();
                                        for(int e=0;e<urls.size();e++) {
                                            String hostname=urls.get(e).getHostname();
                                            if(!usedHostnames.contains(hostname)) {
                                                out.print("        <Alias>").print(hostname).print("</Alias>\n");
                                                usedHostnames.add(hostname);
                                            }
                                        }
                                        // When listed first, also include the IP addresses as aliases
                                        if(hs.listFirst()) {
                                            String ip=bind.getHttpdBind().getNetBind().getIPAddress().getIPAddress();
                                            if(!usedHostnames.contains(ip)) {
                                                out.print("        <Alias>").print(ip).print("</Alias>\n");
                                                usedHostnames.add(ip);
                                            }
                                        }
                                    }
                                    if (version.startsWith(HttpdTomcatVersion.VERSION_4_1_PREFIX)){
                                        out.print("        <Logger\n"
                                                + "          className=\"org.apache.catalina.logger.FileLogger\"\n"
                                                + "          directory=\""+HttpdSite.WWW_DIRECTORY+'/').print(hs.getSiteName()).print("/var/log\"\n"
                                                + "          prefix=\"").print(primaryHostname).print("_log.\"\n"
                                                + "          suffix=\".txt\"\n"
                                                + "          timestamp=\"true\"\n"
                                                + "        />\n");
                                    }
                                    HttpdTomcatSite tomcatSite=hs.getHttpdTomcatSite();
                                    for(HttpdTomcatContext htc : tomcatSite.getHttpdTomcatContexts()) {
                                        out.print("        <Context\n");
                                        if(htc.getClassName()!=null) out.print("          className=\"").print(htc.getClassName()).print("\"\n");
                                        out.print("          cookies=\"").print(htc.useCookies()).print("\"\n"
                                                + "          crossContext=\"").print(htc.allowCrossContext()).print("\"\n"
                                                + "          docBase=\"").print(htc.getDocBase()).print("\"\n"
                                                + "          override=\"").print(htc.allowOverride()).print("\"\n"
                                                + "          path=\"").print(htc.getPath()).print("\"\n"
                                                + "          privileged=\"").print(htc.isPrivileged()).print("\"\n"
                                                + "          reloadable=\"").print(htc.isReloadable()).print("\"\n"
                                                + "          useNaming=\"").print(htc.useNaming()).print("\"\n");
                                        if(htc.getWrapperClass()!=null) out.print("          wrapperClass=\"").print(htc.getWrapperClass()).print("\"\n");
                                        out.print("          debug=\"").print(htc.getDebugLevel()).print("\"\n");
                                        if(htc.getWorkDir()!=null) out.print("          workDir=\"").print(htc.getWorkDir()).print("\"\n");
                                        List<HttpdTomcatParameter> parameters=htc.getHttpdTomcatParameters();
                                        List<HttpdTomcatDataSource> dataSources=htc.getHttpdTomcatDataSources();
                                        if(parameters.isEmpty() && dataSources.isEmpty()) {
                                            out.print("        />\n");
                                        } else {
                                            out.print("        >\n");
                                            // Parameters
                                            for(HttpdTomcatParameter parameter : parameters) {
                                                writeHttpdTomcatParameter(version, parameter, out);
                                            }
                                            // Data Sources
                                            for(HttpdTomcatDataSource dataSource : dataSources) {
                                                writeHttpdTomcatDataSource(version, dataSource, out);
                                            }
                                            out.print("        </Context>\n");
                                        }
                                    }
                                    out.print("      </Host>\n");
                                }
                            }
                            out.print("    </Engine>\n"
                                    + "  </Service>\n"
                                    + "</Server>\n");
			} finally {
			    out.flush();
			    out.close();
			}

                        // Must restart JVM if this file has changed
                        if(
                            !confServerXMLUF.getStat(tempStat).exists()
                            || !newConfServerXMLUF.contentEquals(confServerXMLUF)
                        ) {
                            needRestart=true;
                            newConfServerXMLUF.renameTo(confServerXMLUF);
                        } else newConfServerXMLUF.delete();
                    } else {
                        try {
                            stripFilePrefix(
                                confServerXMLUF,
                                autoWarning,
                                tempStat
                            );
                        } catch(IOException err) {
                            // Errors OK because this is done in manual mode and they might have symbolic linked stuff
                        }
                    }
                }
                
                // Start if needed
                if(needRestart && tomcat.getDisableLog()==null) {
                    int pkey=tomcat.getPkey();
                    if(!sharedTomcatsNeedingRestarted.contains(pkey)) sharedTomcatsNeedingRestarted.add(pkey);
                }
            }

            /*
             * Delete any extra files once the rebuild is done
             */
            if(groupConfFiles!=null) {
                for (int c = 0; c < groupConfFiles.size(); c++)
                    deleteFileList.add(new File(CONF_WWWGROUPS, groupConfFiles.get(c)));
            }
            for (int c = 0; c < wwwGroupDirectories.size(); c++) {
                String tomcatName=wwwGroupDirectories.get(c);
                stopSharedTomcat(tomcatName, tempStat);
                disableSharedTomcat(tomcatName, tempStat);
                String fullPath = HttpdSharedTomcat.WWW_GROUP_DIR + '/' + tomcatName;
                if (!aoServer.isHomeUsed(fullPath)) deleteFileList.add(new File(fullPath));
            }
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }

    public static void ln(String destination, String filename, int uid, int gid) throws IOException {
        Profiler.startProfile(Profiler.IO, HttpdManager.class, "ln(String,String,int,int)", null);
        try {
            new UnixFile(filename).symLink(destination).chown(uid, gid);
        } catch (IOException e) {
            System.err.println("ln: filename: "+filename+"   destination: "+destination);
            throw e;
        } finally {
            Profiler.endProfile(Profiler.IO);
        }
    }
    
    public static void lnAll(String dstBase, String srcBase, int uid, int gid) throws IOException {
        Profiler.startProfile(Profiler.IO, HttpdManager.class, "lnAll(String,String,int,int)", null);
        try {
            String[] destinations=new UnixFile(dstBase).list();
            for (int i=0;i<destinations.length;i++) {
                new UnixFile(srcBase+destinations[i]).symLink(dstBase+destinations[i]).chown(uid, gid);
            }
        } finally {
            Profiler.endProfile(Profiler.IO);
        }
    }

    public static void mkdir(String dirName, int mode, LinuxServerAccount owner, LinuxServerGroup group) throws IOException, SQLException {
        Profiler.startProfile(Profiler.IO, HttpdManager.class, "mkdir(String,int,LinuxServerAccount,LinuxServerGroup)", null);
        try {
            UnixFile uf=new UnixFile(dirName);
            Stat ufStat = uf.getStat();
            if(!ufStat.exists()) {
                uf.mkdir();
                uf.getStat(ufStat);
            } else if(!ufStat.isDirectory()) throw new IOException("File exists and is not a directory: "+dirName);
            int uid = owner.getUID().getID();
            int gid = group.getGID().getID();
            if(ufStat.getUID()!=uid || ufStat.getGID()!=gid) uf.chown(uid, gid);
            if(ufStat.getMode()!=mode) uf.setMode(mode);
        } finally {
            Profiler.endProfile(Profiler.IO);
        }
    }

    public static boolean passwordMatches(String password, String crypted) throws IOException {
        Profiler.startProfile(Profiler.FAST, HttpdManager.class, "passwordMatches(String,String)", null);
        try {
            if(crypted.length()<=2) return false;
            String salt=crypted.substring(0,2);
            return UnixCrypt.crypt(password, salt).equals(crypted);
        } finally {
            Profiler.endProfile(Profiler.FAST);
        }
    }

    private static final Object reloadLock=new Object();
    private static void reload(IntList serversNeedingRestarted) throws IOException, SQLException {
        Profiler.startProfile(Profiler.UNKNOWN, HttpdManager.class, "reload(IntList)", null);
        try {
            HttpdServerTable hst=AOServDaemon.getConnector().httpdServers;
            for(int c=0;c<serversNeedingRestarted.size();c++) {
                reload(hst.get(serversNeedingRestarted.getInt(c)));
            }
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }
    private static void reload(HttpdServer hs) throws IOException {
        Profiler.startProfile(Profiler.UNKNOWN, HttpdManager.class, "reload(HttpdServer)", null);
        try {
            synchronized(reloadLock) {
                String[] cmd={
                    "/etc/rc.d/init.d/httpd"+hs.getNumber(),
                    "reload"
                };
                AOServDaemon.exec(cmd);
            }
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }

    private static void controlApache(String command) throws IOException, SQLException {
        Profiler.startProfile(Profiler.UNKNOWN, HttpdManager.class, "controlApache(String)", null);
        try {
            synchronized(reloadLock) {
                for(HttpdServer hs : AOServDaemon.getThisAOServer().getHttpdServers()) {
                    String[] cmd={
                        "/etc/rc.d/init.d/httpd"+hs.getNumber(),
                        command
                    };
                    AOServDaemon.exec(cmd);
                }
            }
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }

    public static void restartApache() throws IOException, SQLException {
        Profiler.startProfile(Profiler.INSTANTANEOUS, HttpdManager.class, "restartApache()", null);
        try {
            controlApache("restart");
        } finally {
            Profiler.endProfile(Profiler.INSTANTANEOUS);
        }
    }

    public static void startApache() throws IOException, SQLException {
        Profiler.startProfile(Profiler.INSTANTANEOUS, HttpdManager.class, "startApache()", null);
        try {
            controlApache("start");
        } finally {
            Profiler.endProfile(Profiler.INSTANTANEOUS);
        }
    }

    public static void stopApache() throws IOException, SQLException {
        Profiler.startProfile(Profiler.INSTANTANEOUS, HttpdManager.class, "stopApache()", null);
        try {
            controlApache("stop");
        } finally {
            Profiler.endProfile(Profiler.INSTANTANEOUS);
        }
    }

    public static void start() throws IOException, SQLException {
        Profiler.startProfile(Profiler.UNKNOWN, HttpdManager.class, "start()", null);
        try {
            if(AOServDaemonConfiguration.isManagerEnabled(HttpdManager.class) && httpdManager==null) {
                synchronized(System.out) {
                    if(httpdManager==null) {
                        System.out.print("Starting HttpdManager: ");
                        AOServConnector connector=AOServDaemon.getConnector();
                        httpdManager=new HttpdManager();
                        connector.httpdBinds.addTableListener(httpdManager, 0);
                        connector.httpdServers.addTableListener(httpdManager, 0);
                        connector.httpdJBossSites.addTableListener(httpdManager, 0);
                        connector.httpdSharedTomcats.addTableListener(httpdManager, 0);
                        connector.httpdSites.addTableListener(httpdManager, 0);
                        connector.httpdSiteAuthenticatedLocationTable.addTableListener(httpdManager, 0);
                        connector.httpdSiteBinds.addTableListener(httpdManager, 0);
                        connector.httpdSiteURLs.addTableListener(httpdManager, 0);
                        connector.httpdStaticSites.addTableListener(httpdManager, 0);
                        connector.httpdTomcatContexts.addTableListener(httpdManager, 0);
                        connector.httpdTomcatDataSources.addTableListener(httpdManager, 0);
                        connector.httpdTomcatParameters.addTableListener(httpdManager, 0);
                        connector.httpdTomcatSites.addTableListener(httpdManager, 0);
                        connector.httpdTomcatSharedSites.addTableListener(httpdManager, 0);
                        connector.httpdTomcatStdSites.addTableListener(httpdManager, 0);
                        connector.httpdWorkers.addTableListener(httpdManager, 0);
                        connector.ipAddresses.addTableListener(httpdManager, 0);
                        connector.linuxServerAccounts.addTableListener(httpdManager, 0);
                        connector.netBinds.addTableListener(httpdManager, 0);
                        System.out.println("Done");
                    }
                }
            }
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }

    public static String startJVM(int sitePKey) throws IOException, SQLException {
        Profiler.startProfile(Profiler.UNKNOWN, HttpdManager.class, "startJVM(int)", null);
        try {
            synchronized(startJVMTimes) {
                AOServConnector conn=AOServDaemon.getConnector();

                HttpdSite httpdSite=conn.httpdSites.get(sitePKey);
                AOServer thisAOServer=AOServDaemon.getThisAOServer();
                if(!httpdSite.getAOServer().equals(thisAOServer)) throw new SQLException("HttpdSite #"+sitePKey+" has server of "+httpdSite.getAOServer().getServer().getHostname()+", which is not this server ("+thisAOServer.getServer().getHostname()+')');

                HttpdTomcatSite tomcatSite=httpdSite.getHttpdTomcatSite();
                if(tomcatSite==null) throw new SQLException("Unable to find HttpdTomcatSite for HttpdSite #"+sitePKey);

                HttpdTomcatStdSite stdSite=tomcatSite.getHttpdTomcatStdSite();
                if(stdSite!=null) {
                    String key="httpd_tomcat_std_site.tomcat_site="+sitePKey;

                    // Throw an exception if the site was started recently.
                    Long L=startJVMTimes.get(key);
                    if(L!=null) {
                        long span=System.currentTimeMillis()-L.longValue();
                        if(span<HttpdTomcatSite.MINIMUM_START_JVM_DELAY && span>=0) return
                            "Must wait "
                            +SQLUtility.getMilliDecimal(HttpdTomcatSite.MINIMUM_START_JVM_DELAY)
                            +" seconds between Java VM starts, only "
                            +SQLUtility.getMilliDecimal((int)span)
                            +" seconds have passed."
                        ;
                    }

                    LinuxServerAccount lsa=httpdSite.getLinuxServerAccount();
                    AOServDaemon.suexec(
                        lsa.getLinuxAccount().getUsername().getUsername(),
                        HttpdSite.WWW_DIRECTORY+'/'+httpdSite.getSiteName()+"/bin/tomcat start"
                    );

                    // Make sure we don't start too quickly
                    startJVMTimes.put(key, Long.valueOf(System.currentTimeMillis()));
                } else {
                    HttpdTomcatSharedSite shrSite=tomcatSite.getHttpdTomcatSharedSite();
                    if(shrSite!=null) {
                        HttpdSharedTomcat shrTomcat=shrSite.getHttpdSharedTomcat();
                        String name=shrTomcat.getName();
                        String key="httpd_shared_tomcats.name="+name;

                        // Throw an exception if the site was started recently.
                        Long L=startJVMTimes.get(key);
                        if(L!=null) {
                            long span=System.currentTimeMillis()-L.longValue();
                            if(span<HttpdTomcatSite.MINIMUM_START_JVM_DELAY && span>=0) return
                                "Must wait "
                                +SQLUtility.getMilliDecimal(HttpdTomcatSite.MINIMUM_START_JVM_DELAY)
                                +" seconds between Java VM starts, only "
                                +SQLUtility.getMilliDecimal((int)span)
                                +" seconds have passed."
                            ;
                        }

                        startSharedTomcat(shrTomcat);

                        // Make sure we don't start too quickly
                        startJVMTimes.put(key, Long.valueOf(System.currentTimeMillis()));
                    } else {
                        HttpdJBossSite jbossSite=tomcatSite.getHttpdJBossSite();
                        if(jbossSite!=null) {
                            String key="httpd_jboss_site.tomcat_site="+sitePKey;

                            // Throw an exception if the site was started recently.
                            Long L=startJVMTimes.get(key);
                            if(L!=null) {
                                long span=System.currentTimeMillis()-L.longValue();
                                if(span<HttpdTomcatSite.MINIMUM_START_JVM_DELAY && span>=0) return
                                    "Must wait "
                                    +SQLUtility.getMilliDecimal(HttpdTomcatSite.MINIMUM_START_JVM_DELAY)
                                    +" seconds between Java VM starts, only "
                                    +SQLUtility.getMilliDecimal((int)span)
                                    +" seconds have passed."
                                ;
                            }

                            LinuxServerAccount lsa=httpdSite.getLinuxServerAccount();
                            AOServDaemon.suexec(
                                lsa.getLinuxAccount().getUsername().getUsername(),
                                HttpdSite.WWW_DIRECTORY+'/'+httpdSite.getSiteName()+"/bin/jboss start"
                            );

                            // Make sure we don't start too quickly
                            startJVMTimes.put(key, Long.valueOf(System.currentTimeMillis()));
                        } else throw new SQLException("Unable to find HttpdTomcatStdSite, HttpdTomcatSharedSite or HttpdJBossSite for HttpdSite #"+sitePKey);
                    }
                }

                // Null means all went well
                return null;
            }
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }

    public static String stopJVM(int sitePKey) throws IOException, SQLException {
        Profiler.startProfile(Profiler.UNKNOWN, HttpdManager.class, "stopJVM(int)", null);
        try {
            final Stat tempStat = new Stat();
            synchronized(startJVMTimes) {
                AOServConnector conn=AOServDaemon.getConnector();

                HttpdSite httpdSite=conn.httpdSites.get(sitePKey);
                AOServer thisAOServer=AOServDaemon.getThisAOServer();
                if(!httpdSite.getAOServer().equals(thisAOServer)) throw new SQLException("HttpdSite #"+sitePKey+" has server of "+httpdSite.getAOServer().getServer().getHostname()+", which is not this server ("+thisAOServer.getServer().getHostname()+')');

                HttpdTomcatSite tomcatSite=httpdSite.getHttpdTomcatSite();
                if(tomcatSite==null) throw new SQLException("Unable to find HttpdTomcatSite for HttpdSite #"+sitePKey);

                HttpdTomcatStdSite stdSite=tomcatSite.getHttpdTomcatStdSite();
                if(stdSite!=null) {
                    String key="httpd_tomcat_std_site.tomcat_site="+sitePKey;

                    // Throw an exception if the site was started recently.
                    Long L=startJVMTimes.get(key);
                    if(L!=null) {
                        long span=System.currentTimeMillis()-L.longValue();
                        if(span<HttpdTomcatSite.MINIMUM_START_JVM_DELAY && span>=0) return
                            "Must wait "
                            +SQLUtility.getMilliDecimal(HttpdTomcatSite.MINIMUM_START_JVM_DELAY)
                            +" seconds between a Java VM start and stop, only "
                            +SQLUtility.getMilliDecimal((int)span)
                            +" seconds have passed."
                        ;
                    }

                    LinuxServerAccount lsa=httpdSite.getLinuxServerAccount();
                    AOServDaemon.suexec(
                        lsa.getLinuxAccount().getUsername().getUsername(),
                        HttpdSite.WWW_DIRECTORY+'/'+httpdSite.getSiteName()+"/bin/tomcat stop"
                    );

                    // Make sure we don't start too quickly
                    startJVMTimes.put(key, Long.valueOf(System.currentTimeMillis()));
                } else {
                    HttpdTomcatSharedSite shrSite=tomcatSite.getHttpdTomcatSharedSite();
                    if(shrSite!=null) {
                        HttpdSharedTomcat shrTomcat=shrSite.getHttpdSharedTomcat();
                        String name=shrTomcat.getName();
                        String key="httpd_shared_tomcats.name="+name;

                        // Throw an exception if the site was started recently.
                        Long L=startJVMTimes.get(key);
                        if(L!=null) {
                            long span=System.currentTimeMillis()-L.longValue();
                            if(span<HttpdTomcatSite.MINIMUM_START_JVM_DELAY && span>=0) return
                                "Must wait "
                                +SQLUtility.getMilliDecimal(HttpdTomcatSite.MINIMUM_START_JVM_DELAY)
                                +" seconds between a Java VM start and stop, only "
                                +SQLUtility.getMilliDecimal((int)span)
                                +" seconds have passed."
                            ;
                        }

                        stopSharedTomcat(shrTomcat, tempStat);

                        // Make sure we don't start too quickly
                        startJVMTimes.put(key, Long.valueOf(System.currentTimeMillis()));
                    } else {
                        HttpdJBossSite jbossSite=tomcatSite.getHttpdJBossSite();
                        if(jbossSite!=null) {
                            String key="httpd_jboss_site.tomcat_site="+sitePKey;

                            // Throw an exception if the site was started recently.
                            Long L=startJVMTimes.get(key);
                            if(L!=null) {
                                long span=System.currentTimeMillis()-L.longValue();
                                if(span<HttpdTomcatSite.MINIMUM_START_JVM_DELAY && span>=0) return
                                    "Must wait "
                                    +SQLUtility.getMilliDecimal(HttpdTomcatSite.MINIMUM_START_JVM_DELAY)
                                    +" seconds between a Java VM start and stop, only "
                                    +SQLUtility.getMilliDecimal((int)span)
                                    +" seconds have passed."
                                ;
                            }

                            LinuxServerAccount lsa=httpdSite.getLinuxServerAccount();
                            AOServDaemon.suexec(
                                lsa.getLinuxAccount().getUsername().getUsername(),
                                HttpdSite.WWW_DIRECTORY+'/'+httpdSite.getSiteName()+"/bin/jboss stop"
                            );

                            // Make sure we don't start too quickly
                            startJVMTimes.put(key, Long.valueOf(System.currentTimeMillis()));
                        } else throw new SQLException("Unable to find HttpdTomcatStdSite, HttpdTomcatSharedSite or HttpdJBossSite for HttpdSite #"+sitePKey);
                    }
                }

                // Null means all went well
                return null;
            }
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }

    public static void waitForRebuild() {
        Profiler.startProfile(Profiler.INSTANTANEOUS, HttpdManager.class, "waitForRebuild()", null);
        try {
            if(httpdManager!=null) httpdManager.waitForBuild();
        } finally {
            Profiler.endProfile(Profiler.INSTANTANEOUS);
        }
    }
    
    private static void disableAndEnableSharedTomcats(AOServer aoServer, AOServConnector connector, IntList needStarted, Stat tempStat) throws IOException, SQLException {
        Profiler.startProfile(Profiler.UNKNOWN, HttpdManager.class, "disableAndEnableSharedTomcats(AOServer,AOServConnector,IntList,Stat)", null);
        try {
            List<HttpdSharedTomcat> hsts=aoServer.getHttpdSharedTomcats();
            for(int c=0;c<hsts.size();c++) {
                HttpdSharedTomcat hst=hsts.get(c);
                String tomcatName=hst.getName();
                UnixFile pidUF=new UnixFile(HttpdSharedTomcat.WWW_GROUP_DIR+'/'+tomcatName+"/var/run/tomcat.pid");
                UnixFile daemonUF=new UnixFile(HttpdSharedTomcat.WWW_GROUP_DIR+'/'+tomcatName+"/daemon/tomcat");
                if(hst.getDisableLog()==null) {
                    // Enabled, make sure running and auto
                    if(needStarted.contains(hst.getPkey()) || !pidUF.getStat(tempStat).exists()) startSharedTomcat(hst);
                    if(!daemonUF.getStat(tempStat).exists()) enableSharedTomcat(hst, tempStat);
                } else {
                    // Disabled, make sure stopped and not auto
                    if(daemonUF.getStat(tempStat).exists()) disableSharedTomcat(hst, tempStat);
                    if(pidUF.getStat(tempStat).exists()) stopSharedTomcat(hst, tempStat);
                }
            }
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }
    
    private static void disableAndEnableHttpdSites(AOServer aoServer, AOServConnector connector, IntList needStarted, Stat tempStat) throws IOException, SQLException {
        Profiler.startProfile(Profiler.UNKNOWN, HttpdManager.class, "disableAndEnableHttpdSites(AOServer,AOServConnector,IntList,Stat)", null);
        try {
            List<HttpdSite> hss=aoServer.getHttpdSites();
            for(int c=0;c<hss.size();c++) {
                HttpdSite hs=hss.get(c);
                String siteName=hs.getSiteName();
                try {
                    UnixFile ftpUF=new UnixFile("/var/ftp/pub/"+siteName);
                    if(hs.getDisableLog()==null) {
                        // Enabled, make sure running and auto
                        if(ftpUF.getStat(tempStat).exists() && tempStat.getUID()==UnixFile.ROOT_UID) ftpUF.chown(
                            hs.getLinuxServerAccount().getUID().getID(),
                            hs.getLinuxServerGroup().getGID().getID()
                        ).setMode(0775);
                        UnixFile pidUF=getPIDUnixFile(hs);
                        UnixFile daemonUF=getDaemonUnixFile(hs);
                        if(pidUF!=null && (needStarted.contains(hs.getPkey()) || !pidUF.getStat(tempStat).exists())) {
                            startHttpdSite(hs);
                        }
                        if(daemonUF!=null && !daemonUF.getStat(tempStat).exists()) enableHttpdSite(hs, tempStat);
                    } else {
                        // Disabled, make sure stopped and not auto
                        if(ftpUF.getStat(tempStat).exists() && tempStat.getUID()!=UnixFile.ROOT_UID) ftpUF.chown(
                            UnixFile.ROOT_UID,
                            UnixFile.ROOT_GID
                        ).setMode(0700);
                        UnixFile daemonUF=getDaemonUnixFile(hs);
                        UnixFile pidUF=getPIDUnixFile(hs);
                        if(daemonUF!=null && daemonUF.getStat(tempStat).exists()) disableHttpdSite(hs, tempStat);
                        if(pidUF!=null && pidUF.getStat(tempStat).exists()) stopHttpdSite(hs, tempStat);
                    }
                } catch(IOException err) {
                    System.err.println("disableAndEnableHttpdSites error on site: "+siteName);
                    throw err;
                }
            }
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }

    private static void startSharedTomcat(HttpdSharedTomcat hst) throws IOException, SQLException {
        Profiler.startProfile(Profiler.SLOW, HttpdManager.class, "startSharedTomcat()", null);
        try {
            LinuxServerAccount lsa=hst.getLinuxServerAccount();
            AOServDaemon.suexec(
                lsa.getLinuxAccount().getUsername().getUsername(),
                HttpdSharedTomcat.WWW_GROUP_DIR+'/'+hst.getName()+"/bin/tomcat start"
            );
        } finally {
            Profiler.endProfile(Profiler.SLOW);
        }
    }

    private static void stopSharedTomcat(HttpdSharedTomcat hst, Stat tempStat) throws IOException, SQLException {
        Profiler.startProfile(Profiler.FAST, HttpdManager.class, "stopSharedTomcat(HttpdSharedTomcat,Stat)", null);
        try {
            stopSharedTomcat(hst.getLinuxServerAccount(), hst.getName(), tempStat);
        } finally {
            Profiler.endProfile(Profiler.FAST);
        }
    }
    
    private static void stopSharedTomcat(String tomcatName, Stat tempStat) throws IOException, SQLException {
        Profiler.startProfile(Profiler.UNKNOWN, HttpdManager.class, "stopSharedTomcat(String,Stat)", null);
        try {
            UnixFile uf=new UnixFile(HttpdSharedTomcat.WWW_GROUP_DIR+'/'+tomcatName+"/daemon");
            uf.getStat(tempStat);
            if(tempStat.exists()) {
                int uid=tempStat.getUID();
                LinuxServerAccount lsa=AOServDaemon.getThisAOServer().getLinuxServerAccount(uid);
                if(lsa!=null) {
                    stopSharedTomcat(lsa, tomcatName, tempStat);
                }
            }
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }

    private static void stopSharedTomcat(LinuxServerAccount lsa, String tomcatName, Stat tempStat) throws IOException, SQLException {
        Profiler.startProfile(Profiler.UNKNOWN, HttpdManager.class, "stopSharedTomcat(LinuxServerAccount,String,Stat)", null);
        try {
            UnixFile pidUF=new UnixFile(HttpdSharedTomcat.WWW_GROUP_DIR+'/'+tomcatName+"/var/run/tomcat.pid");
            if(pidUF.getStat(tempStat).exists()) {
                AOServDaemon.suexec(
                    lsa.getLinuxAccount().getUsername().getUsername(),
                    HttpdSharedTomcat.WWW_GROUP_DIR+'/'+tomcatName+"/bin/tomcat stop"
                );
            }
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }

    private static void enableSharedTomcat(HttpdSharedTomcat hst, Stat tempStat) throws IOException, SQLException {
        Profiler.startProfile(Profiler.UNKNOWN, HttpdManager.class, "enableSharedTomcat(HttpdSharedTomcat,Stat)", null);
        try {
            UnixFile uf=new UnixFile(HttpdSharedTomcat.WWW_GROUP_DIR+'/'+hst.getName()+"/daemon/tomcat");
            if(!uf.getStat(tempStat).exists()) uf.symLink("../bin/tomcat").chown(
                hst.getLinuxServerAccount().getUID().getID(),
                hst.getLinuxServerGroup().getGID().getID()
            );
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }

    private static void disableSharedTomcat(HttpdSharedTomcat hst, Stat tempStat) throws IOException {
        Profiler.startProfile(Profiler.FAST, HttpdManager.class, "disableSharedTomcat(HttpdSharedTomcat,Stat)", null);
        try {
            disableSharedTomcat(hst.getName(), tempStat);
        } finally {
            Profiler.endProfile(Profiler.FAST);
        }
    }

    private static void disableSharedTomcat(String tomcatName, Stat tempStat) throws IOException {
        Profiler.startProfile(Profiler.UNKNOWN, HttpdManager.class, "disableSharedTomcat(String,Stat)", null);
        try {
            UnixFile uf=new UnixFile(HttpdSharedTomcat.WWW_GROUP_DIR+'/'+tomcatName+"/daemon/tomcat");
            if(uf.getStat(tempStat).exists()) uf.delete();
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }

    private static UnixFile getPIDUnixFile(HttpdSite hs) throws IOException, SQLException {
        Profiler.startProfile(Profiler.UNKNOWN, HttpdManager.class, "getPIDUnixFile(HttpdSite)", null);
        try {
            HttpdTomcatSite hts=hs.getHttpdTomcatSite();
            if(hts!=null) {
                HttpdJBossSite hjs=hts.getHttpdJBossSite();
                if(hjs!=null) return new UnixFile(hs.getInstallDirectory()+"/var/run/jboss.pid");
                HttpdTomcatStdSite htss=hts.getHttpdTomcatStdSite();
                if(htss!=null) return new UnixFile(hs.getInstallDirectory()+"/var/run/tomcat.pid");
            }
            return null;
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }

    private static UnixFile getDaemonUnixFile(HttpdSite hs) throws IOException, SQLException {
        Profiler.startProfile(Profiler.UNKNOWN, HttpdManager.class, "getDaemonUnixFile(HttpdSite)", null);
        try {
            HttpdTomcatSite hts=hs.getHttpdTomcatSite();
            if(hts!=null) {
                HttpdJBossSite hjs=hts.getHttpdJBossSite();
                if(hjs!=null) return new UnixFile(hs.getInstallDirectory()+"/daemon/jboss");
                HttpdTomcatStdSite htss=hts.getHttpdTomcatStdSite();
                if(htss!=null) return new UnixFile(hs.getInstallDirectory()+"/daemon/tomcat");
            }
            return null;
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }

    private static void startHttpdSite(HttpdSite hs) throws IOException, SQLException {
        Profiler.startProfile(Profiler.INSTANTANEOUS, HttpdManager.class, "startHttpdSite(HttpdSite)", null);
        try {
            controlHttpdSite(hs, "start");
        } finally {
            Profiler.endProfile(Profiler.INSTANTANEOUS);
        }
    }
    
    private static void stopHttpdSite(HttpdSite hs, Stat tempStat) throws IOException, SQLException {
        Profiler.startProfile(Profiler.UNKNOWN, HttpdManager.class, "stopHttpdSite(HttpdSite,Stat)", null);
        try {
            UnixFile pidUF=getPIDUnixFile(hs);
            if(pidUF!=null && pidUF.getStat(tempStat).exists()) controlHttpdSite(hs, "stop");
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }

    private static void controlHttpdSite(HttpdSite hs, String action) throws IOException, SQLException {
        Profiler.startProfile(Profiler.UNKNOWN, HttpdManager.class, "controlHttpdSite(HttpdSite,String)", null);
        try {
            HttpdTomcatSite hst=hs.getHttpdTomcatSite();
            if(hst!=null) {
                HttpdJBossSite hjs=hst.getHttpdJBossSite();
                if(hjs!=null) {
                    LinuxServerAccount lsa=hs.getLinuxServerAccount();
                    AOServDaemon.suexec(
                        lsa.getLinuxAccount().getUsername().getUsername(),
                        HttpdSite.WWW_DIRECTORY+'/'+hs.getSiteName()+"/bin/jboss "+action
                    );
                } else {
                    HttpdTomcatStdSite htss=hst.getHttpdTomcatStdSite();
                    if(htss!=null) {
                        LinuxServerAccount lsa=hs.getLinuxServerAccount();
                        AOServDaemon.suexec(
                            lsa.getLinuxAccount().getUsername().getUsername(),
                            HttpdSite.WWW_DIRECTORY+'/'+hs.getSiteName()+"/bin/tomcat "+action
                        );
                    }
                }
            }
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }

    private static void stopHttpdSite(String siteName, Stat tempStat) throws IOException, SQLException {
        Profiler.startProfile(Profiler.UNKNOWN, HttpdManager.class, "stopHttpdSite(String,Stat)", null);
        try {
            UnixFile uf=new UnixFile(HttpdSite.WWW_DIRECTORY+'/'+siteName+"/daemon");
            uf.getStat(tempStat);
            if(tempStat.exists()) {
                int uid=tempStat.getUID();
                LinuxServerAccount lsa=AOServDaemon.getThisAOServer().getLinuxServerAccount(uid);
                if(lsa!=null) {
                    UnixFile jbossUF=new UnixFile(HttpdSite.WWW_DIRECTORY+'/'+siteName+"/bin/jboss");
                    if(jbossUF.getStat(tempStat).exists()) {
                        UnixFile jbossPID=new UnixFile(HttpdSite.WWW_DIRECTORY+'/'+siteName+"/var/run/jboss.pid");
                        if(jbossPID.getStat(tempStat).exists()) {
                            AOServDaemon.suexec(
                                lsa.getLinuxAccount().getUsername().getUsername(),
                                HttpdSite.WWW_DIRECTORY+'/'+siteName+"/bin/jboss stop"
                            );
                        }
                    }
                    UnixFile tomcatUF=new UnixFile(HttpdSite.WWW_DIRECTORY+'/'+siteName+"/bin/tomcat");
                    if(tomcatUF.getStat(tempStat).exists()) {
                        UnixFile tomcatPID=new UnixFile(HttpdSite.WWW_DIRECTORY+'/'+siteName+"/var/run/tomcat.pid");
                        if(tomcatPID.getStat(tempStat).exists()) {
                            AOServDaemon.suexec(
                                lsa.getLinuxAccount().getUsername().getUsername(),
                                HttpdSite.WWW_DIRECTORY+'/'+siteName+"/bin/tomcat stop"
                            );
                        }
                    }
                }
            }
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }

    private static void enableHttpdSite(HttpdSite hs,Stat tempStat) throws IOException, SQLException {
        Profiler.startProfile(Profiler.UNKNOWN, HttpdManager.class, "enableHttpdSite(HttpdSite,Stat)", null);
        try {
            HttpdTomcatSite hst=hs.getHttpdTomcatSite();
            if(hst!=null) {
                HttpdJBossSite hjs=hst.getHttpdJBossSite();
                if(hjs!=null) {
                    UnixFile uf=new UnixFile(hs.getInstallDirectory()+"/daemon/jboss");
                    if(!uf.getStat(tempStat).exists()) uf.symLink("../bin/jboss").chown(
                        hs.getLinuxServerAccount().getUID().getID(),
                        hs.getLinuxServerGroup().getGID().getID()
                    );
                } else {
                    HttpdTomcatStdSite htss=hst.getHttpdTomcatStdSite();
                    if(htss!=null) {
                        UnixFile uf=new UnixFile(hs.getInstallDirectory()+"/daemon/tomcat");
                        if(!uf.getStat(tempStat).exists()) uf.symLink("../bin/tomcat").chown(
                            hs.getLinuxServerAccount().getUID().getID(),
                            hs.getLinuxServerGroup().getGID().getID()
                        );
                    }
                }
            }
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }

    private static void disableHttpdSite(HttpdSite hs, Stat tempStat) throws IOException {
        Profiler.startProfile(Profiler.FAST, HttpdManager.class, "disableHttpdSite(HttpdSite,Stat)", null);
        try {
            disableHttpdSite(hs.getSiteName(), tempStat);
        } finally {
            Profiler.endProfile(Profiler.FAST);
        }
    }

    private static void disableHttpdSite(String siteName, Stat tempStat) throws IOException {
        Profiler.startProfile(Profiler.UNKNOWN, HttpdManager.class, "disableHttpdSite(String,Stat)", null);
        try {
            UnixFile jbossUF=new UnixFile(HttpdSite.WWW_DIRECTORY+'/'+siteName+"/daemon/jboss");
            if(jbossUF.getStat(tempStat).exists()) jbossUF.delete();

            UnixFile tomcatUF=new UnixFile(HttpdSite.WWW_DIRECTORY+'/'+siteName+"/daemon/tomcat");
            if(tomcatUF.getStat(tempStat).exists()) tomcatUF.delete();
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }

    private static void disableAndEnableSiteBinds(AOServer aoServer, AOServConnector connector) throws IOException, SQLException {
        Profiler.startProfile(Profiler.UNKNOWN, HttpdManager.class, "disableAndEnableSiteBinds(AOServer,AOServConnector)", null);
        try {
            List<HttpdSite> hss=aoServer.getHttpdSites();
            for(int c=0;c<hss.size();c++) {
                HttpdSite hs=hss.get(c);
                List<HttpdSiteBind> hsbs=hs.getHttpdSiteBinds();
                for(int e=0;e<hsbs.size();e++) {
                    HttpdSiteBind hsb=hsbs.get(e);
                    String predisableConfig=hsb.getPredisableConfig();
                    if(hsb.getDisableLog()==null) {
                        if(predisableConfig!=null) {
                            if(hsb.isManual()) {
                                NetBind nb=hsb.getHttpdBind().getNetBind();
                                String bindConfig=CONF_HOSTS+'/'+hs.getSiteName()+'_'+nb.getIPAddress().getIPAddress()+'_'+nb.getPort().getPort();
                                //String bindConfig=CONF_HOSTS+'/'+hs.getSiteName()+'.'+hsb.getHttpdBind().getNetBind().getPort().getPort();
                                UnixFile bindFile=new UnixFile(bindConfig);
                                String newConfig=bindConfig+".new";
                                UnixFile newFile=new UnixFile(newConfig);
                                PrintWriter out=new PrintWriter(
                                    newFile.getSecureOutputStream(
                                        UnixFile.ROOT_UID,
                                        hs.getLinuxServerGroup().getGID().getID(),
                                        0640,
                                        false
                                    )
                                );
				try {
				    out.print(predisableConfig);
				    out.print('\n');
				} finally {
				    out.flush();
				    out.close();
				}
                                newFile.renameTo(bindFile);
                            }
                            hsb.setPredisableConfig(null);
                        }
                    } else {
                        if(hsb.isManual() && predisableConfig==null) {
                            NetBind nb=hsb.getHttpdBind().getNetBind();
                            String bindConfig=CONF_HOSTS+'/'+hs.getSiteName()+'_'+nb.getIPAddress().getIPAddress()+'_'+nb.getPort().getPort();
                            //String bindConfig=CONF_HOSTS+'/'+hs.getSiteName()+'.'+hsb.getHttpdBind().getNetBind().getPort().getPort();
                            UnixFile bindFile=new UnixFile(bindConfig);
                            String newConfig=bindConfig+".new";
                            UnixFile newFile=new UnixFile(newConfig);
                            // Get the existing config
                            StringBuilder SB=new StringBuilder();
                            InputStream in=new BufferedInputStream(bindFile.getSecureInputStream());
			    try {
				int ch;
				while((ch=in.read())!=-1) SB.append((char)ch);
			    } finally {
				in.close();
			    }
                            hsb.setPredisableConfig(SB.toString());
                            // Overwrite the config with the disabled include
                            writeHttpdSiteBindFile(hsb, newFile, HttpdSite.DISABLED);
                            newFile.renameTo(bindFile);
                        }
                    }
                }
            }
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }

    public String getProcessTimerDescription() {
        Profiler.startProfile(Profiler.INSTANTANEOUS, HttpdManager.class, "getProcessTimerDescription()", null);
        try {
            return "Rebuild HTTPD";
        } finally {
            Profiler.endProfile(Profiler.INSTANTANEOUS);
        }
    }
    
    public long getProcessTimerMaximumTime() {
        Profiler.startProfile(Profiler.INSTANTANEOUS, HttpdManager.class, "getProcessTimerMaximumTime()", null);
        try {
            return 15L*60*1000;
        } finally {
            Profiler.endProfile(Profiler.INSTANTANEOUS);
        }
    }
    
    public static String getAutoWarning(HttpdSite hs) throws IOException, SQLException {
        Profiler.startProfile(Profiler.FAST, HttpdManager.class, "getAutoWarning(HttpdSite)", null);
        try {
            return
                "<!--\n"
                + "  Warning: This file is automatically created by HttpdManager.  Any manual changes\n"
                + "  to this file will be overwritten.  Please set the is_manual flag for this website\n"
                + "  to be able to make permanent changes to this file.\n"
                + "\n"
                + "  Control Panel: https://secure.aoindustries.com/clientarea/control/httpd/HttpdSiteCP?pkey="+hs.getPkey()+"\n"
                + "\n"
                + "  AOSH: "+AOSHCommand.SET_HTTPD_SITE_IS_MANUAL+" "+hs.getSiteName()+' '+hs.getAOServer().getServer().getHostname()+" true\n"
                + "\n"
                + "  support@aoindustries.com\n"
                + "  (866) 270-6195\n"
                + "-->\n"
            ;
        } finally {
            Profiler.endProfile(Profiler.FAST);
        }
    }

    public static String getAutoWarning(HttpdSharedTomcat hst) throws IOException, SQLException {
        Profiler.startProfile(Profiler.FAST, HttpdManager.class, "getAutoWarning(HttpdSharedTomcat)", null);
        try {
            return
                "<!--\n"
                + "  Warning: This file is automatically created by HttpdManager.  Any manual changes\n"
                + "  to this file will be overwritten.  Please set the is_manual flag for this multi-site\n"
                + "  JVM to be able to make permanent changes to this file.\n"
                + "\n"
                + "  Control Panel: https://secure.aoindustries.com/clientarea/control/httpd/HttpdSharedTomcatCP?pkey="+hst.getPkey()+"\n"
                + "\n"
                + "  AOSH: "+AOSHCommand.SET_HTTPD_SHARED_TOMCAT_IS_MANUAL+" "+hst.getName()+' '+hst.getAOServer().getServer().getHostname()+" true\n"
                + "\n"
                + "  support@aoindustries.com\n"
                + "  (866) 270-6195\n"
                + "-->\n"
            ;
        } finally {
            Profiler.endProfile(Profiler.FAST);
        }
    }
    
    public static void stripFilePrefix(UnixFile uf, String prefix, Stat tempStat) throws IOException {
        Profiler.startProfile(Profiler.UNKNOWN, HttpdManager.class, "stripFilePrefix(UnixFile,String,Stat)", null);
        try {
            // Remove the auto warning if the site has recently become manual
            int prefixLen=prefix.length();
            uf.getStat(tempStat);
            if(tempStat.getSize()>=prefixLen) {
                UnixFile newUF=null;
                InputStream in=new BufferedInputStream(uf.getSecureInputStream());
                try {
                    StringBuilder SB=new StringBuilder(prefixLen);
                    int ch;
                    while(SB.length()<prefixLen && (ch=in.read())!=-1) {
                        SB.append((char)ch);
                    }
                    if(SB.toString().equals(prefix)) {
                        newUF=UnixFile.mktemp(uf.getFilename()+'.');
                        OutputStream out=new BufferedOutputStream(
                            newUF.getSecureOutputStream(
                                tempStat.getUID(),
                                tempStat.getGID(),
                                tempStat.getMode(),
                                true
                            )
                        );
                        try {
                            byte[] buff=BufferManager.getBytes();
                            try {
                                int ret;
                                while((ret=in.read(buff, 0, BufferManager.BUFFER_SIZE))!=-1) out.write(buff, 0, ret);
                            } finally {
                                BufferManager.release(buff);
                            }
                        } finally {
			    out.flush();
                            out.close();
                        }
                    }
                } finally {
                    in.close();
                }
                if(newUF!=null) newUF.renameTo(uf);
            }
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }
    
    private static final String getDefaultPhpVersion(int operatingSystemVersion) {
        Profiler.startProfile(Profiler.FAST, HttpdManager.class, "getDefaultPhpVersion(int)", null);
        try {
            if(operatingSystemVersion==OperatingSystemVersion.MANDRIVA_2006_0_I586) return DEFAULT_MANDRIVA_2006_0_PHP_VERSION;
            else if(operatingSystemVersion==OperatingSystemVersion.REDHAT_ES_4_X86_64) return DEFAULT_REDHAT_ES_4_PHP_VERSION;
            else throw new RuntimeException("Unsupported OperatingSystemVersion: "+operatingSystemVersion);
        } finally {
            Profiler.endProfile(Profiler.FAST);
        }
    }

    private static final String getDefaultJdkVersion(int operatingSystemVersion) {
        Profiler.startProfile(Profiler.FAST, HttpdManager.class, "getDefaultJdkVersion(int)", null);
        try {
            if(operatingSystemVersion==OperatingSystemVersion.MANDRIVA_2006_0_I586) return DEFAULT_MANDRIVA_2006_0_JDK_VERSION;
            else if(operatingSystemVersion==OperatingSystemVersion.REDHAT_ES_4_X86_64) return DEFAULT_REDHAT_ES_4_JDK_VERSION;
            else throw new RuntimeException("Unsupported OperatingSystemVersion: "+operatingSystemVersion);
        } finally {
            Profiler.endProfile(Profiler.FAST);
        }
    }

    private static final String getDefaultJdkPath(int operatingSystemVersion) {
        Profiler.startProfile(Profiler.FAST, HttpdManager.class, "getDefaultJdkPath(int)", null);
        try {
            if(operatingSystemVersion==OperatingSystemVersion.MANDRIVA_2006_0_I586) return DEFAULT_MANDRIVA_2006_0_JDK_PATH;
            else if(operatingSystemVersion==OperatingSystemVersion.REDHAT_ES_4_X86_64) return DEFAULT_REDHAT_ES_4_JDK_PATH;
            else throw new RuntimeException("Unsupported OperatingSystemVersion: "+operatingSystemVersion);
        } finally {
            Profiler.endProfile(Profiler.FAST);
        }
    }

    private static final String getPhpLib(TechnologyVersion phpVersion) {
        Profiler.startProfile(Profiler.FAST, HttpdManager.class, "getPhpLib(TechnologyVersion)", null);
        try {
            String version=phpVersion.getVersion();
            if(version.length()>0) {
                char first=version.charAt(0);
                if(first=='4') return "libphp4.so";
                if(first=='5') return "libphp5.so";
            }
            throw new RuntimeException("Unsupported PHP version: "+version);
        } finally {
            Profiler.endProfile(Profiler.FAST);
        }
    }
    
    private static final void doRebuildLogrotate(
        int osv,
        AOServer aoServer,
        List<File> deleteFileList,
        AOServConnector conn,
        Stat tempStat
    ) throws IOException, SQLException {
        Profiler.startProfile(Profiler.UNKNOWN, HttpdManager.class, "doRebuildLogrotate(int,AOServer,List<File>,AOServConnector,Stat)", null);
        try {
            // The log rotations that exist but are not used will be removed
            String[] list=new File(LOG_ROTATION_DIR).list();
            List<String> logRotationFiles=new SortedArrayList<String>(list.length);
            for(int c=0;c<list.length;c++) logRotationFiles.add(list[c]);

            // Each log file will be only rotated at most once
            Map<String,Object> completedPaths=new HashMap<String,Object>();

            // For each site, build/rebuild the logrotate.d file as necessary and create any necessary log files
            ByteArrayOutputStream byteOut=new ByteArrayOutputStream();
            ChainWriter chainOut=new ChainWriter(byteOut);
            Set<HttpdServer> httpdServers = new HashSet<HttpdServer>();
            List<HttpdSite> sites=aoServer.getHttpdSites();
            for(int c=0;c<sites.size();c++) {
                HttpdSite site=sites.get(c);

                // Write the new file to RAM first
                byteOut.reset();
                boolean wroteOne = false;
                // Build the list of all related httpdservers
                httpdServers.clear();
                List<HttpdSiteBind> binds=site.getHttpdSiteBinds();
                for(int d=0;d<binds.size();d++) {
                    HttpdSiteBind bind=binds.get(d);
                    HttpdServer hs = bind.getHttpdBind().getHttpdServer();
                    if(!httpdServers.contains(hs)) httpdServers.add(hs);
                    String access_log = bind.getAccessLog();
                    // Each unique path is only rotated once
                    if(!completedPaths.containsKey(access_log)) {
                        completedPaths.put(access_log, null);
                        // Add to the site log rotation
                        if(osv==OperatingSystemVersion.MANDRIVA_2006_0_I586 || osv==OperatingSystemVersion.REDHAT_ES_4_X86_64) {
                            if(wroteOne) chainOut.print(' ');
                            else wroteOne = true;
                            chainOut.print(access_log);
                        } else throw new SQLException("Unsupported OperatingSystemVersion: "+osv);
                        makeLogFiles(site, bind, access_log, deleteFileList, completedPaths, tempStat);
                    }
                    String error_log = bind.getErrorLog();
                    if(!completedPaths.containsKey(error_log)) {
                        completedPaths.put(error_log, null);
                        // Add to the site log rotation
                        if(osv==OperatingSystemVersion.MANDRIVA_2006_0_I586 || osv==OperatingSystemVersion.REDHAT_ES_4_X86_64) {
                            if(wroteOne) chainOut.print(' ');
                            else wroteOne = true;
                            chainOut.print(error_log);
                        } else throw new SQLException("Unsupported OperatingSystemVersion: "+osv);
                        makeLogFiles(site, bind, error_log, deleteFileList, completedPaths, tempStat);
                    }
                }
                // Finish the file
                if(osv==OperatingSystemVersion.MANDRIVA_2006_0_I586 || osv==OperatingSystemVersion.REDHAT_ES_4_X86_64) {
                    if(wroteOne) {
                        chainOut.print(" {\n"
                                     + "    daily\n"
                                     + "    rotate 379\n");
                        /*
                                     + "    sharedscripts\n");
                        if(osv==OperatingSystemVersion.MANDRIVA_2006_0_I586) {
                            chainOut.print("    prerotate\n");
                            for(HttpdServer httpdServer : httpdServers) {
                                chainOut.print("        /bin/kill -HUP `cat /var/run/httpd").print(httpdServer.getNumber()).print(".pid 2>/dev/null` 2> /dev/null || true\n");
                            }
                            chainOut.print("    endscript\n");
                        }
                        chainOut.print("    postrotate\n");
                        for(HttpdServer httpdServer : httpdServers) {
                            chainOut.print("        /bin/kill -HUP `cat /var/run/httpd").print(httpdServer.getNumber()).print(".pid 2>/dev/null` 2> /dev/null || true\n");
                        }
                        chainOut.print("    endscript\n"*/
                        chainOut.print("}\n");
                    }
                } else throw new SQLException("Unsupported OperatingSystemVersion: "+osv);
                chainOut.flush();
                byte[] newFileContent=byteOut.toByteArray();

                // Write to disk if file missing or doesn't match
                UnixFile logRotation=new UnixFile(LOG_ROTATION_DIR, site.getSiteName());
                if(!logRotation.getStat(tempStat).exists() || !logRotation.contentEquals(newFileContent)) {
                    OutputStream out=logRotation.getSecureOutputStream(
                        UnixFile.ROOT_UID,
                        site.getLinuxServerGroup().getGID().getID(),
                        0640,
                        true
                    );
		    try {
                        out.write(newFileContent);
		    } finally {
			out.close();
		    }
                }

                // Make sure the newly created or replaced log rotation file is not removed
                logRotationFiles.remove(site.getSiteName());
            }

            for(int c=0;c<logRotationFiles.size();c++) deleteFileList.add(new File(LOG_ROTATION_DIR, logRotationFiles.get(c)));
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }
    
    private static void makeLogFiles(
        HttpdSite site,
        HttpdSiteBind siteBind,
        String path,
        List<File> deleteFileList,
        Map<String,Object> completedPaths,
        Stat tempStat
    ) throws IOException, SQLException {
        Profiler.startProfile(Profiler.UNKNOWN, HttpdManager.class, "makeLogFiles(HttpdSite,HttpdSiteBind,String,List<File>,Map<String,Object>,Stat)", null);
        try {
            AOServer aoServer=AOServDaemon.getThisAOServer();
            LinuxServerAccount awstatsLSA=aoServer.getLinuxServerAccount(LinuxAccount.AWSTATS);
            if(awstatsLSA==null) throw new SQLException("Unable to find LinuxServerAccount: "+LinuxAccount.AWSTATS+" on "+aoServer.getServer().getHostname());
            int awstatsUID=awstatsLSA.getUID().getID();
            int lsgGID=site.getLinuxServerGroup().getGID().getID();

            UnixFile logFile=new UnixFile(path);
            UnixFile logFileParent=logFile.getParent();

            // Make sure the parent directory exists and has the correct permissions
            logFileParent.getStat(tempStat);
            if(!tempStat.exists()) logFileParent.mkdir(true, 0750, awstatsUID, lsgGID);
            else {
                if(tempStat.getMode()!=0750) logFileParent.setMode(0750);
                if(
                    tempStat.getUID()!=awstatsUID
                    || tempStat.getGID()!=lsgGID
                ) logFileParent.chown(awstatsUID, lsgGID);
            }

            // Make sure the log file exists
            if(!logFile.getStat(tempStat).exists()) logFile.getSecureOutputStream(awstatsUID, lsgGID, 0640, false).close();
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }
    
    private static String getMajorPhpVersion(String version) {
        int pos=version.indexOf('.');
        return pos<=0?version:version.substring(0, pos);
    }
    
    private static void writeHttpdTomcatParameter(String tomcatVersion, HttpdTomcatParameter parameter, ChainWriter out) {
        out.print("          <Parameter\n"
                + "            name=\"").printXmlAttribute(parameter.getName()).print("\"\n"
                + "            value=\"").printXmlAttribute(parameter.getValue()).print("\"\n"
                + "            override=\"").print(parameter.getOverride()).print("\"\n");
        if(parameter.getDescription()!=null) out.print("            description=\"").printXmlAttribute(parameter.getDescription()).print("\"\n");
        out.print("          />\n");
    }
    
    private static void writeHttpdTomcatDataSource(String tomcatVersion, HttpdTomcatDataSource dataSource, ChainWriter out) throws SQLException {
        if(tomcatVersion.startsWith(HttpdTomcatVersion.VERSION_4_1_PREFIX)) {
            out.print("          <Resource\n"
                    + "            name=\"").printXmlAttribute(dataSource.getName()).print("\"\n"
                    + "            auth=\"Container\"\n"
                    + "            type=\"javax.sql.DataSource\"\n"
                    + "          />\n"
                    + "          <ResourceParams name=\"").printXmlAttribute(dataSource.getName()).print("\">\n"
                    + "            <parameter><name>factory</name><value>org.apache.commons.dbcp.BasicDataSourceFactory</value></parameter>\n"
                    + "            <parameter><name>username</name><value>").printXmlBody(dataSource.getUsername()).print("</value></parameter>\n"
                    + "            <parameter><name>password</name><value>").printXmlBody(dataSource.getPassword()).print("</value></parameter>\n"
                    + "            <parameter><name>driverClassName</name><value>").printXmlBody(dataSource.getDriverClassName()).print("</value></parameter>\n"
                    + "            <parameter><name>url</name><value>").printXmlBody(dataSource.getUrl()).print("</value></parameter>\n"
                    + "            <parameter><name>maxActive</name><value>").print(dataSource.getMaxActive()).print("</value></parameter>\n"
                    + "            <parameter><name>maxIdle</name><value>").print(dataSource.getMaxIdle()).print("</value></parameter>\n"
                    + "            <parameter><name>maxWait</name><value>").print(dataSource.getMaxWait()).print("</value></parameter>\n");
            if(dataSource.getValidationQuery()!=null) {
                out.print("            <parameter><name>validationQuery</name><value>").printXmlBody(dataSource.getValidationQuery()).print("</value></parameter>\n");
            }
            out.print("            <parameter><name>removeAbandoned</name><value>true</value></parameter>\n"
                    + "            <parameter><name>removeAbandonedTimeout</name><value>300</value></parameter>\n"
                    + "            <parameter><name>logAbandoned</name><value>true</value></parameter>\n"
                    + "          </ResourceParams>\n");
        } else if(tomcatVersion.startsWith(HttpdTomcatVersion.VERSION_5_5_PREFIX)) {
            out.print("          <Resource\n"
                    + "            name=\"").printXmlAttribute(dataSource.getName()).print("\"\n"
                    + "            auth=\"Container\"\n"
                    + "            type=\"javax.sql.DataSource\"\n"
                    + "            username=\"").printXmlAttribute(dataSource.getUsername()).print("\"\n"
                    + "            password=\"").printXmlAttribute(dataSource.getPassword()).print("\"\n"
                    + "            driverClassName=\"").printXmlAttribute(dataSource.getDriverClassName()).print("\"\n"
                    + "            url=\"").printXmlAttribute(dataSource.getUrl()).print("\"\n"
                    + "            maxActive=\"").print(dataSource.getMaxActive()).print("\"\n"
                    + "            maxIdle=\"").print(dataSource.getMaxIdle()).print("\"\n"
                    + "            maxWait=\"").print(dataSource.getMaxWait()).print("\"\n");
            if(dataSource.getValidationQuery()!=null) {
                out.print("            validationQuery=\"").printXmlAttribute(dataSource.getValidationQuery()).print("\"\n");
            }
            out.print("            removeAbandoned=\"true\"\n"
                    + "            removeAbandonedTimeout=\"300\"\n"
                    + "            logAbandoned=\"true\"\n"
                    + "          />\n");
        } else throw new SQLException("Unexpected Tomcat version: "+tomcatVersion);
    }
}
