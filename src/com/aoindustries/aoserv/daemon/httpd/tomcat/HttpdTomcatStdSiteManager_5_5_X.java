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
import com.aoindustries.aoserv.client.HttpdTomcatDataSource;
import com.aoindustries.aoserv.client.HttpdTomcatParameter;
import com.aoindustries.aoserv.client.HttpdTomcatStdSite;
import com.aoindustries.aoserv.client.HttpdWorker;
import com.aoindustries.aoserv.client.IPAddress;
import com.aoindustries.aoserv.client.LinuxServerAccount;
import com.aoindustries.aoserv.client.NetBind;
import com.aoindustries.aoserv.client.PostgresServer;
import com.aoindustries.aoserv.client.Server;
import com.aoindustries.aoserv.daemon.AOServDaemon;
import com.aoindustries.aoserv.daemon.OperatingSystemConfiguration;
import com.aoindustries.aoserv.daemon.httpd.HttpdOperatingSystemConfiguration;
import com.aoindustries.aoserv.daemon.unix.linux.LinuxAccountManager;
import com.aoindustries.aoserv.daemon.util.FileUtils;
import com.aoindustries.io.ChainWriter;
import com.aoindustries.io.unix.Stat;
import com.aoindustries.io.unix.UnixFile;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.sql.SQLException;
import java.util.List;

/**
 * Manages HttpdTomcatStdSite version 5.5.X configurations.
 *
 * @author  AO Industries, Inc.
 */
class HttpdTomcatStdSiteManager_5_5_X extends HttpdTomcatStdSiteManager<TomcatCommon_5_5_X> {

    HttpdTomcatStdSiteManager_5_5_X(HttpdTomcatStdSite tomcatStdSite) {
        super(tomcatStdSite);
    }

    /**
     * Builds a standard install for Tomcat 5.5.X
     */
    protected void buildSiteDirectoryContents(UnixFile siteDirectory) throws IOException, SQLException {
        // Resolve and allocate stuff used throughout the method
        final OperatingSystemConfiguration osConfig = OperatingSystemConfiguration.getOperatingSystemConfiguration();
        final HttpdOperatingSystemConfiguration httpdConfig = osConfig.getHttpdOperatingSystemConfiguration();
        final TomcatCommon tomcatCommon = getTomcatCommon();
        final String siteDir = siteDirectory.getPath();
        final LinuxServerAccount lsa = httpdSite.getLinuxServerAccount();
        final int uid = lsa.getUID().getID();
        final int gid = httpdSite.getLinuxServerGroup().getGID().getID();
        final String tomcatDirectory=tomcatSite.getHttpdTomcatVersion().getInstallDirectory();
        final AOServer thisAOServer = AOServDaemon.getThisAOServer();
        final Server server = thisAOServer.getServer();
        final int osv = server.getOperatingSystemVersion().getPkey();
        final PostgresServer postgresServer=thisAOServer.getPreferredPostgresServer();
        final String postgresServerMinorVersion=postgresServer==null?null:postgresServer.getPostgresVersion().getMinorVersion();

        /*
         * Create the skeleton of the site, the directories and links.
         */
        FileUtils.mkdir(siteDir+"/bin", 0770, uid, gid);
        FileUtils.mkdir(siteDir+"/conf", 0775, uid, gid);
        FileUtils.mkdir(siteDir+"/daemon", 0770, uid, gid);
        if (httpdSite.getDisableLog()==null) FileUtils.ln("../bin/tomcat", siteDir+"/daemon/tomcat", uid, gid);
        FileUtils.mkdir(siteDir+"/temp", 0770, uid, gid);
        FileUtils.ln("var/log", siteDir+"/logs", uid, gid);
        FileUtils.mkdir(siteDir+"/var", 0770, uid, gid);
        FileUtils.mkdir(siteDir+"/var/log", 0770, uid, gid);
        FileUtils.mkdir(siteDir+"/var/run", 0770, uid, gid);
        FileUtils.mkdir(siteDir+"/webapps", 0775, uid, gid);
        FileUtils.mkdir(siteDir+"/webapps/"+HttpdTomcatContext.ROOT_DOC_BASE, 0775, uid, gid);
        FileUtils.mkdir(siteDir+"/webapps/"+HttpdTomcatContext.ROOT_DOC_BASE+"/WEB-INF", 0775, uid, gid);
        FileUtils.mkdir(siteDir+"/webapps/"+HttpdTomcatContext.ROOT_DOC_BASE+"/WEB-INF/classes", 0770, uid, gid);
        FileUtils.mkdir(siteDir+"/webapps/"+HttpdTomcatContext.ROOT_DOC_BASE+"/WEB-INF/lib", 0770, uid, gid);	
        FileUtils.mkdir(siteDir+"/work", 0750, uid, gid);
        FileUtils.ln("../../.."+tomcatDirectory+"/bin/bootstrap.jar", siteDir+"/bin/bootstrap.jar", uid, gid);
        FileUtils.ln("../../.."+tomcatDirectory+"/bin/catalina.sh", siteDir+"/bin/catalina.sh", uid, gid);
        FileUtils.ln("../../.."+tomcatDirectory+"/bin/commons-daemon.jar", siteDir+"/bin/commons-daemon.jar", uid, gid);
        FileUtils.ln("../../.."+tomcatDirectory+"/bin/commons-logging-api.jar", siteDir+"/bin/commons-logging-api.jar", uid, gid);
        FileUtils.ln("../../.."+tomcatDirectory+"/bin/digest.sh", siteDir+"/bin/digest.sh", uid, gid);

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
                    + ". /etc/profile\n"
                    + ". ").print(osConfig.getScriptInclude("jdk"+tomcatCommon.getDefaultJdkVersion()+".sh")).print("\n"
                    + ". ").print(osConfig.getScriptInclude("php-"+httpdConfig.getDefaultPhpVersion()+".sh")).print('\n');
            if(postgresServerMinorVersion!=null) out.print(". ").print(osConfig.getScriptInclude("postgresql-"+postgresServerMinorVersion+".sh")).print('\n');
            out.print(". ").print(osConfig.getAOServClientScriptInclude()).print("\n"
                    + "\n"
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
                    + "TOMCAT_HOME=\"").print(siteDir).print("\"\n"
                    + "\n"
                    + "if [ \"$1\" = \"start\" ]; then\n"
                    + "    \"$0\" stop\n"
                    + "    \"$0\" daemon &\n"
                    + "    echo $! >\"${TOMCAT_HOME}/var/run/tomcat.pid\"\n"
                    + "elif [ \"$1\" = \"stop\" ]; then\n"
                    + "    if [ -f \"${TOMCAT_HOME}/var/run/tomcat.pid\" ]; then\n"
                    + "        kill `cat \"${TOMCAT_HOME}/var/run/tomcat.pid\"`\n"
                    + "        rm -f \"${TOMCAT_HOME}/var/run/tomcat.pid\"\n"
                    + "    fi\n"
                    + "    if [ -f \"${TOMCAT_HOME}/var/run/java.pid\" ]; then\n"
                    + "        cd \"$TOMCAT_HOME\"\n"
                    + "        . \"$TOMCAT_HOME/bin/profile\"\n"
                    + "        umask 002\n"
                    + "        export DISPLAY=:0.0\n"
                    + "        \"${TOMCAT_HOME}/bin/catalina.sh\" stop 2>&1 >>\"${TOMCAT_HOME}/var/log/tomcat_err\"\n"
                    + "        kill `cat \"${TOMCAT_HOME}/var/run/java.pid\"` &>/dev/null\n"
                    + "        rm -f \"${TOMCAT_HOME}/var/run/java.pid\"\n"
                    + "    fi\n"
                    + "elif [ \"$1\" = \"daemon\" ]; then\n"
                    + "    cd \"$TOMCAT_HOME\"\n"
                    + "    . \"$TOMCAT_HOME/bin/profile\"\n"
                    + "\n"
                    + "    while [ 1 ]; do\n"
                    + "        umask 002\n"
                    + "        export DISPLAY=:0.0\n"
                    + "        mv -f \"${TOMCAT_HOME}/var/log/tomcat_err\" \"${TOMCAT_HOME}/var/log/tomcat_err.old\"\n"
                    + "        \"${TOMCAT_HOME}/bin/catalina.sh\" run >&\"${TOMCAT_HOME}/var/log/tomcat_err\" &\n"
                    + "        echo $! >\"${TOMCAT_HOME}/var/run/java.pid\"\n"
                    + "        wait\n"
                    + "        RETCODE=$?\n"
                    + "        echo \"`date`: JVM died with a return code of $RETCODE, restarting in 5 seconds\" >>\"${TOMCAT_HOME}/var/log/jvm_crashes.log\"\n"
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
            out.close();
        }
        FileUtils.ln("../../.."+tomcatDirectory+"/bin/setclasspath.sh", siteDir+"/bin/setclasspath.sh", uid, gid);

        out=new ChainWriter(new UnixFile(siteDir+"/bin/shutdown.sh").getSecureOutputStream(uid, gid, 0700, true));
        try {
            out.print("#!/bin/sh\n"
                    + "exec ").print(siteDir).print("/bin/tomcat stop\n");
        } finally {
            out.close();
        }

        out=new ChainWriter(new UnixFile(siteDir+"/bin/startup.sh").getSecureOutputStream(uid, gid, 0700, true));
        try {
            out.print("#!/bin/sh\n"
                    + "exec ").print(siteDir).print("/bin/tomcat start\n");
        } finally {
            out.close();
        }

        FileUtils.ln("../../.."+tomcatDirectory+"/bin/tomcat-juli.jar", siteDir+"/bin/tomcat-juli.jar", uid, gid);
        FileUtils.ln("../../.."+tomcatDirectory+"/bin/tool-wrapper.sh", siteDir+"/bin/tool-wrapper.sh", uid, gid);
        FileUtils.ln("../../.."+tomcatDirectory+"/bin/version.sh", siteDir+"/bin/version.sh", uid, gid);
        FileUtils.mkdir(siteDir+"/common", 0775, uid, gid);
        FileUtils.mkdir(siteDir+"/common/classes", 0775, uid, gid);
        FileUtils.mkdir(siteDir+"/common/endorsed", 0775, uid, gid);
        FileUtils.lnAll("../../../.."+tomcatDirectory+"/common/endorsed/", siteDir+"/common/endorsed/", uid, gid);
        FileUtils.mkdir(siteDir+"/common/i18n", 0775, uid, gid);
        FileUtils.lnAll("../../../.."+tomcatDirectory+"/common/i18n/", siteDir+"/common/i18n/", uid, gid);
        FileUtils.mkdir(siteDir+"/common/lib", 0775, uid, gid);
        FileUtils.lnAll("../../../.."+tomcatDirectory+"/common/lib/", siteDir+"/common/lib/", uid, gid);

        if(postgresServerMinorVersion!=null) {
            String postgresPath = osConfig.getPostgresPath(postgresServerMinorVersion);
            if(postgresPath!=null) FileUtils.ln("../../../.."+postgresPath+"/share/java/postgresql.jar", siteDir+"/common/lib/postgresql.jar", uid, gid);
        }
        String mysqlConnectorPath = osConfig.getMySQLConnectorJavaJarPath();
        if(mysqlConnectorPath!=null) {
            String filename = new UnixFile(mysqlConnectorPath).getFile().getName();
            FileUtils.ln("../../../.."+mysqlConnectorPath, siteDir+"/common/lib/"+filename, uid, gid);
        }

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

        FileUtils.mkdir(siteDir+"/server", 0775, uid, gid);
        FileUtils.mkdir(siteDir+"/server/classes", 0775, uid, gid);
        FileUtils.mkdir(siteDir+"/server/lib", 0775, uid, gid);
        FileUtils.lnAll("../../../.."+tomcatDirectory+"/server/lib/", siteDir+"/server/lib/", uid, gid);
        FileUtils.mkdir(siteDir+"/server/webapps", 0775, uid, gid);
        FileUtils.mkdir(siteDir+"/shared", 0775, uid, gid);
        FileUtils.mkdir(siteDir+"/shared/classes", 0775, uid, gid);
        FileUtils.mkdir(siteDir+"/shared/lib", 0775, uid, gid);

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
            out.close();
        }
    }

    public TomcatCommon_5_5_X getTomcatCommon() {
        return TomcatCommon_5_5_X.getInstance();
    }

    protected byte[] buildServerXml(UnixFile siteDirectory, String autoWarning) throws IOException, SQLException {
        final TomcatCommon tomcatCommon = getTomcatCommon();
        AOServConnector conn = AOServDaemon.getConnector();

        // Build to RAM first
        ByteArrayOutputStream bout = new ByteArrayOutputStream();
        ChainWriter out = new ChainWriter(bout);
        try {
            List<HttpdWorker> hws=tomcatSite.getHttpdWorkers();
            if(hws.size()!=1) throw new SQLException("Expected to only find one HttpdWorker for HttpdTomcatStdSite #"+httpdSite.getPkey()+", found "+hws.size());
            HttpdWorker hw=hws.get(0);
            String hwProtocol=hw.getHttpdJKProtocol(conn).getProtocol(conn).getProtocol();
            if(!hwProtocol.equals(HttpdJKProtocol.AJP13)) {
                throw new SQLException("HttpdWorker #"+hw.getPkey()+" for HttpdTomcatStdSite #"+httpdSite.getPkey()+" must be AJP13 but it is "+hwProtocol);
            }
            if(!httpdSite.isManual()) out.print(autoWarning);
            NetBind shutdownPort=tomcatStdSite.getTomcat4ShutdownPort();
            if(shutdownPort==null) throw new SQLException("Unable to find shutdown port for HttpdTomcatStdSite="+tomcatStdSite);
            String shutdownKey=tomcatStdSite.getTomcat4ShutdownKey();
            if(shutdownKey==null) throw new SQLException("Unable to find shutdown key for HttpdTomcatStdSite="+tomcatStdSite);
            out.print("<Server port=\"").print(shutdownPort.getPort().getPort()).print("\" shutdown=\"").print(shutdownKey).print("\" debug=\"0\">\n");
            out.print("  <GlobalNamingResources>\n"
                    + "    <Resource name=\"UserDatabase\" auth=\"Container\"\n"
                    + "              type=\"org.apache.catalina.UserDatabase\"\n"
                    + "       description=\"User database that can be updated and saved\"\n"
                    + "           factory=\"org.apache.catalina.users.MemoryUserDatabaseFactory\"\n"
                    + "          pathname=\"conf/tomcat-users.xml\" />\n"
                    + "   </GlobalNamingResources>\n");
            out.print("  <Service name=\"Catalina\">\n"
                    + "    <Connector\n"
                    //+ "      className=\"org.apache.coyote.tomcat4.CoyoteConnector\"\n"
                    //+ "      port=\"").print(hw.getNetBind().getPort().getPort()).print("\"\n"
                    //+ "      minProcessors=\"2\"\n"
                    //+ "      maxProcessors=\"200\"\n"
                    //+ "      enableLookups=\"true\"\n"
                    //+ "      redirectPort=\"443\"\n"
                    //+ "      acceptCount=\"10\"\n"
                    //+ "      debug=\"0\"\n"
                    //+ "      connectionTimeout=\"20000\"\n"
                    //+ "      useURIValidationHack=\"false\"\n"
                    //+ "      protocolHandlerClassName=\"org.apache.jk.server.JkCoyoteHandler\"\n"
                    );
            out.print("      port=\"").print(hw.getNetBind().getPort().getPort()).print("\"\n");
            out.print("      enableLookups=\"false\"\n");
            out.print("      minProcessors=\"2\"\n"
                    + "      maxProcessors=\"200\"\n"
                    + "      address=\""+IPAddress.LOOPBACK_IP+"\"\n"
                    + "      acceptCount=\"10\"\n"
                    + "      debug=\"0\"\n"
                    + "      protocol=\"AJP/1.3\"\n"
                    + "    />\n"
                    + "    <Engine name=\"Catalina\" defaultHost=\"localhost\" debug=\"0\">\n");
            out.print("      <Realm className=\"org.apache.catalina.realm.UserDatabaseRealm\"\n"
                    + "          resourceName=\"UserDatabase\" />\"\n");
            out.print("      <Host\n"
                    + "        name=\"localhost\"\n"
                    + "        debug=\"0\"\n"
                    + "        appBase=\"webapps\"\n"
                    + "        unpackWARs=\"true\"\n");
            out.print("        autoDeploy=\"true\"\n");
            out.print("        xmlValidation=\"false\"\n"
                    + "        xmlNamespaceAware=\"false\"\n");
            out.print("      >\n");
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
                        tomcatCommon.writeHttpdTomcatParameter(parameter, out);
                    }
                    // Data Sources
                    for(HttpdTomcatDataSource dataSource : dataSources) {
                        tomcatCommon.writeHttpdTomcatDataSource(dataSource, out);
                    }
                    out.print("        </Context>\n");
                }
            }
            out.print("      </Host>\n"
                    + "    </Engine>\n"
                    + "  </Service>\n"
                    + "</Server>\n");
        } finally {
            out.close();
        }
        return bout.toByteArray();
    }

    static class UpgradeSymlink {
        final String linkPath;
        final String oldLinkTarget;
        final String newLinkTarget;
        
        UpgradeSymlink(String linkPath, String oldLinkTarget, String newLinkTarget) {
            this.linkPath = linkPath;
            this.oldLinkTarget = oldLinkTarget;
            this.newLinkTarget = newLinkTarget;
        }
    }

    private static final UpgradeSymlink[] upgradeSymlinks_5_5_Newest = {
        // Upgrade from Mandriva 2006.0 to CentOS 5
        new UpgradeSymlink(
            "common/i18n/tomcat-i18n-es.jar",
            "../../../../usr/apache/jakarta/tomcat/5.5/common/i18n/tomcat-i18n-es.jar",
            "../../../../opt/apache-tomcat-5.5/common/i18n/tomcat-i18n-es.jar"
        ),
        new UpgradeSymlink(
            "common/i18n/tomcat-i18n-en.jar",
            "../../../../usr/apache/jakarta/tomcat/5.5/common/i18n/tomcat-i18n-en.jar",
            "../../../../opt/apache-tomcat-5.5/common/i18n/tomcat-i18n-en.jar"
        ),
        new UpgradeSymlink(
            "common/i18n/tomcat-i18n-fr.jar",
            "../../../../usr/apache/jakarta/tomcat/5.5/common/i18n/tomcat-i18n-fr.jar",
            "../../../../opt/apache-tomcat-5.5/common/i18n/tomcat-i18n-fr.jar"
        ),
        new UpgradeSymlink(
            "common/i18n/tomcat-i18n-ja.jar",
            "../../../../usr/apache/jakarta/tomcat/5.5/common/i18n/tomcat-i18n-ja.jar",
            "../../../../opt/apache-tomcat-5.5/common/i18n/tomcat-i18n-ja.jar"
        ),
        new UpgradeSymlink(
            "common/lib/mysql-connector-java-3.1.12-bin.jar",
            "../../../../usr/mysql-connector-java/3.1.12/mysql-connector-java-3.1.12-bin.jar",
            "../../../../opt/mysql-connector-java-3.1.12/mysql-connector-java-3.1.12-bin.jar"
        ),
        new UpgradeSymlink(
            "common/lib/jsp-api.jar",
            "../../../../usr/apache/jakarta/tomcat/5.5/common/lib/jsp-api.jar",
            "../../../../opt/apache-tomcat-5.5/common/lib/jsp-api.jar"
        ),
        new UpgradeSymlink(
            "common/lib/naming-resources.jar",
            "../../../../usr/apache/jakarta/tomcat/5.5/common/lib/naming-resources.jar",
            "../../../../opt/apache-tomcat-5.5/common/lib/naming-resources.jar"
        ),
        new UpgradeSymlink(
            "common/lib/commons-el.jar",
            "../../../../usr/apache/jakarta/tomcat/5.5/common/lib/commons-el.jar",
            "../../../../opt/apache-tomcat-5.5/common/lib/commons-el.jar"
        ),
        new UpgradeSymlink(
            "common/lib/naming-factory-dbcp.jar",
            "../../../../usr/apache/jakarta/tomcat/5.5/common/lib/naming-factory-dbcp.jar",
            "../../../../opt/apache-tomcat-5.5/common/lib/naming-factory-dbcp.jar"
        ),
        new UpgradeSymlink(
            "common/lib/jasper-runtime.jar",
            "../../../../usr/apache/jakarta/tomcat/5.5/common/lib/jasper-runtime.jar",
            "../../../../opt/apache-tomcat-5.5/common/lib/jasper-runtime.jar"
        ),
        new UpgradeSymlink(
            "common/lib/naming-factory.jar",
            "../../../../usr/apache/jakarta/tomcat/5.5/common/lib/naming-factory.jar",
            "../../../../opt/apache-tomcat-5.5/common/lib/naming-factory.jar"
        ),
        new UpgradeSymlink(
            "common/lib/jasper-compiler-jdt.jar",
            "../../../../usr/apache/jakarta/tomcat/5.5/common/lib/jasper-compiler-jdt.jar",
            "../../../../opt/apache-tomcat-5.5/common/lib/jasper-compiler-jdt.jar"
        ),
        new UpgradeSymlink(
            "common/lib/servlet-api.jar",
            "../../../../usr/apache/jakarta/tomcat/5.5/common/lib/servlet-api.jar",
            "../../../../opt/apache-tomcat-5.5/common/lib/servlet-api.jar"
        ),
        new UpgradeSymlink(
            "common/lib/jasper-compiler.jar",
            "../../../../usr/apache/jakarta/tomcat/5.5/common/lib/jasper-compiler.jar",
            "../../../../opt/apache-tomcat-5.5/common/lib/jasper-compiler.jar"
        ),
        new UpgradeSymlink(
            "bin/version.sh",
            "../../../usr/apache/jakarta/tomcat/5.5/bin/version.sh",
            "../../../opt/apache-tomcat-5.5/bin/version.sh"
        ),
        new UpgradeSymlink(
            "bin/digest.sh",
            "../../../usr/apache/jakarta/tomcat/5.5/bin/digest.sh",
            "../../../opt/apache-tomcat-5.5/bin/digest.sh"
        ),
        new UpgradeSymlink(
            "bin/bootstrap.jar",
            "../../../usr/apache/jakarta/tomcat/5.5/bin/bootstrap.jar",
            "../../../opt/apache-tomcat-5.5/bin/bootstrap.jar"
        ),
        new UpgradeSymlink(
            "bin/commons-logging-api.jar",
            "../../../usr/apache/jakarta/tomcat/5.5/bin/commons-logging-api.jar",
            "../../../opt/apache-tomcat-5.5/bin/commons-logging-api.jar"
        ),
        new UpgradeSymlink(
            "bin/setclasspath.sh",
            "../../../usr/apache/jakarta/tomcat/5.5/bin/setclasspath.sh",
            "../../../opt/apache-tomcat-5.5/bin/setclasspath.sh"
        ),
        new UpgradeSymlink(
            "bin/tomcat-juli.jar",
            "../../../usr/apache/jakarta/tomcat/5.5/bin/tomcat-juli.jar",
            "../../../opt/apache-tomcat-5.5/bin/tomcat-juli.jar"
        ),
        new UpgradeSymlink(
            "bin/tool-wrapper.sh",
            "../../../usr/apache/jakarta/tomcat/5.5/bin/tool-wrapper.sh",
            "../../../opt/apache-tomcat-5.5/bin/tool-wrapper.sh"
        ),
        new UpgradeSymlink(
            "bin/catalina.sh",
            "../../../usr/apache/jakarta/tomcat/5.5/bin/catalina.sh",
            "../../../opt/apache-tomcat-5.5/bin/catalina.sh"
        ),
        new UpgradeSymlink(
            "bin/commons-daemon.jar",
            "../../../usr/apache/jakarta/tomcat/5.5/bin/commons-daemon.jar",
            "../../../opt/apache-tomcat-5.5/bin/commons-daemon.jar"
        ),
        new UpgradeSymlink(
            "server/lib/servlets-default.jar",
            "../../../../usr/apache/jakarta/tomcat/5.5/server/lib/servlets-default.jar",
            "../../../../opt/apache-tomcat-5.5/server/lib/servlets-default.jar"
        ),
        new UpgradeSymlink(
            "server/lib/catalina-optional.jar",
            "../../../../usr/apache/jakarta/tomcat/5.5/server/lib/catalina-optional.jar",
            "../../../../opt/apache-tomcat-5.5/server/lib/catalina-optional.jar"
        ),
        new UpgradeSymlink(
            "server/lib/servlets-invoker.jar",
            "../../../../usr/apache/jakarta/tomcat/5.5/server/lib/servlets-invoker.jar",
            "../../../../opt/apache-tomcat-5.5/server/lib/servlets-invoker.jar"
        ),
        new UpgradeSymlink(
            "server/lib/tomcat-apr.jar",
            "../../../../usr/apache/jakarta/tomcat/5.5/server/lib/tomcat-apr.jar",
            "../../../../opt/apache-tomcat-5.5/server/lib/tomcat-apr.jar"
        ),
        new UpgradeSymlink(
            "server/lib/servlets-ssi.renametojar",
            "../../../../usr/apache/jakarta/tomcat/5.5/server/lib/servlets-ssi.renametojar",
            "../../../../opt/apache-tomcat-5.5/server/lib/servlets-ssi.renametojar"
        ),
        new UpgradeSymlink(
            "server/lib/commons-modeler.jar",
            "../../../../usr/apache/jakarta/tomcat/5.5/server/lib/commons-modeler.jar",
            "../../../../opt/apache-tomcat-5.5/server/lib/commons-modeler.jar"
        ),
        new UpgradeSymlink(
            "server/lib/servlets-cgi.renametojar",
            "../../../../usr/apache/jakarta/tomcat/5.5/server/lib/servlets-cgi.renametojar",
            "../../../../opt/apache-tomcat-5.5/server/lib/servlets-cgi.renametojar"
        ),
        new UpgradeSymlink(
            "server/lib/tomcat-http.jar",
            "../../../../usr/apache/jakarta/tomcat/5.5/server/lib/tomcat-http.jar",
            "../../../../opt/apache-tomcat-5.5/server/lib/tomcat-http.jar"
        ),
        new UpgradeSymlink(
            "server/lib/servlets-webdav.jar",
            "../../../../usr/apache/jakarta/tomcat/5.5/server/lib/servlets-webdav.jar",
            "../../../../opt/apache-tomcat-5.5/server/lib/servlets-webdav.jar"
        ),
        new UpgradeSymlink(
            "server/lib/catalina.jar",
            "../../../../usr/apache/jakarta/tomcat/5.5/server/lib/catalina.jar",
            "../../../../opt/apache-tomcat-5.5/server/lib/catalina.jar"
        ),
        new UpgradeSymlink(
            "server/lib/catalina-ant-jmx.jar",
            "../../../../usr/apache/jakarta/tomcat/5.5/server/lib/catalina-ant-jmx.jar",
            "../../../../opt/apache-tomcat-5.5/server/lib/catalina-ant-jmx.jar"
        ),
        new UpgradeSymlink(
            "server/lib/tomcat-jkstatus-ant.jar",
            "../../../../usr/apache/jakarta/tomcat/5.5/server/lib/tomcat-jkstatus-ant.jar",
            "../../../../opt/apache-tomcat-5.5/server/lib/tomcat-jkstatus-ant.jar"
        ),
        new UpgradeSymlink(
            "server/lib/catalina-cluster.jar",
            "../../../../usr/apache/jakarta/tomcat/5.5/server/lib/catalina-cluster.jar",
            "../../../../opt/apache-tomcat-5.5/server/lib/catalina-cluster.jar"
        ),
        new UpgradeSymlink(
            "server/lib/catalina-ant.jar",
            "../../../../usr/apache/jakarta/tomcat/5.5/server/lib/catalina-ant.jar",
            "../../../../opt/apache-tomcat-5.5/server/lib/catalina-ant.jar"
        ),
        new UpgradeSymlink(
            "server/lib/tomcat-coyote.jar",
            "../../../../usr/apache/jakarta/tomcat/5.5/server/lib/tomcat-coyote.jar",
            "../../../../opt/apache-tomcat-5.5/server/lib/tomcat-coyote.jar"
        ),
        new UpgradeSymlink(
            "server/lib/tomcat-ajp.jar",
            "../../../../usr/apache/jakarta/tomcat/5.5/server/lib/tomcat-ajp.jar",
            "../../../../opt/apache-tomcat-5.5/server/lib/tomcat-ajp.jar"
        ),
        new UpgradeSymlink(
            "server/lib/tomcat-util.jar",
            "../../../../usr/apache/jakarta/tomcat/5.5/server/lib/tomcat-util.jar",
            "../../../../opt/apache-tomcat-5.5/server/lib/tomcat-util.jar"
        ),
        new UpgradeSymlink(
            "server/lib/catalina-storeconfig.jar",
            "../../../../usr/apache/jakarta/tomcat/5.5/server/lib/catalina-storeconfig.jar",
            "../../../../opt/apache-tomcat-5.5/server/lib/catalina-storeconfig.jar"
        )
    };

    protected boolean upgradeSiteDirectoryContents(UnixFile siteDirectory) throws IOException, SQLException {
        boolean needsRestart = false;
        OperatingSystemConfiguration osConfig = OperatingSystemConfiguration.getOperatingSystemConfiguration();
        if(osConfig==OperatingSystemConfiguration.CENTOS_5_I686_AND_X86_64) {
            Stat tempStat = new Stat();
            int uid = httpdSite.getLinuxServerAccount().getUID().getID();
            int gid = httpdSite.getLinuxServerGroup().getGID().getID();
            // Tomcat 5.5.Latest
            for(UpgradeSymlink upgradeSymlink : upgradeSymlinks_5_5_Newest) {
                UnixFile link = new UnixFile(siteDirectory.getPath()+"/"+upgradeSymlink.linkPath);
                if(link.getStat(tempStat).exists()) {
                    if(tempStat.isSymLink()) {
                        String target = link.readLink();
                        if(target.equals(upgradeSymlink.oldLinkTarget)) {
                            link.delete();
                            link.symLink(upgradeSymlink.newLinkTarget);
                            needsRestart = true;
                            link.getStat(tempStat);
                        }
                    }
                    // Check ownership
                    if(tempStat.getUID()!=uid || tempStat.getGID()!=gid) link.chown(uid, gid);
                }
            }
            // Replace /usr/aoserv/etc in bin/profile
            String results = AOServDaemon.execAndCapture(
                new String[] {
                    osConfig.getReplaceCommand(),
                    "/usr/aoserv/etc/",
                    "/opt/aoserv-client/scripts/",
                    "/aoserv.sh",
                    "/aoserv-client.sh",
                    "--",
                    siteDirectory.getPath()+"/bin/profile"
                }
            );
            if(results.length()>0) needsRestart = true;
        }
        return needsRestart;
    }
}
