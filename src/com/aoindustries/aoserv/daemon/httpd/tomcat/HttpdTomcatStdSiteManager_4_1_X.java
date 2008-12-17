package com.aoindustries.aoserv.daemon.httpd.tomcat;

/*
 * Copyright 2007-2008 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
import com.aoindustries.aoserv.client.AOServer;
import com.aoindustries.aoserv.client.HttpdSharedTomcat;
import com.aoindustries.aoserv.client.HttpdSite;
import com.aoindustries.aoserv.client.HttpdTomcatStdSite;
import com.aoindustries.aoserv.client.LinuxServerAccount;
import com.aoindustries.aoserv.client.LinuxServerGroup;
import com.aoindustries.aoserv.client.PostgresServer;
import com.aoindustries.aoserv.client.Server;
import com.aoindustries.aoserv.daemon.AOServDaemon;
import com.aoindustries.io.unix.UnixFile;
import java.io.IOException;
import java.sql.SQLException;
import java.util.Set;

/**
 * Manages HttpdTomcatStdSite version 4.1.X configurations.
 *
 * @author  AO Industries, Inc.
 */
class HttpdTomcatStdSiteManager_4_1_X extends HttpdTomcatStdSiteManager {

    HttpdTomcatStdSiteManager_4_1_X(HttpdTomcatStdSite tomcatStdSite) {
        super(tomcatStdSite);
    }

    /**
     * Builds a standard install for Tomcat 4.1.X
     */
    protected void buildSiteDirectory(UnixFile siteDirectory, Set<HttpdSite> sitesNeedingRestarted, Set<HttpdSharedTomcat> sharedTomcatsNeedingRestarted) throws IOException, SQLException {
        /*
         * Resolve and allocate stuff used throughout the method
         */
        final LinuxServerAccount lsa = httpdSite.getLinuxServerAccount();
        final LinuxServerGroup lsg = httpdSite.getLinuxServerGroup();
        final int uid = lsa.getUID().getID();
        final int gid = lsg.getGID().getID();
        final String tomcatDirectory=tomcatSite.getHttpdTomcatVersion().getInstallDirectory();
        final AOServer thisAOServer = AOServDaemon.getThisAOServer();
        final Server server = thisAOServer.getServer();
        final int osv = server.getOperatingSystemVersion().getPkey();
        final PostgresServer postgresServer=thisAOServer.getPreferredPostgresServer();
        final String postgresServerMinorVersion=postgresServer==null?null:postgresServer.getPostgresVersion().getMinorVersion();

        /*
         * Create the skeleton of the site, the directories and links.
         */
        mkdir(siteDir+"/bin", 0770, lsa, lsg);
        mkdir(siteDir+"/conf", 0775, lsa, lsg);
        mkdir(siteDir+"/daemon", 0770, lsa, lsg);
        if (httpdSite.getDisableLog()==null) ln("../bin/tomcat", siteDir+"/daemon/tomcat", uid, gid);
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
                    + "export PATH=${PATH}:").print(siteDir).print("/bin\n"
                    + "\n"
                    + "export JAVA_OPTS='-server -Djava.awt.headless=true'\n");
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
