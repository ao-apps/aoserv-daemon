/*
 * aoserv-daemon - Server management daemon for the AOServ Platform.
 * Copyright (C) 2016, 2017, 2018, 2019, 2020, 2021, 2022, 2023, 2025  AO Industries, Inc.
 *     support@aoindustries.com
 *     7262 Bull Pen Cir
 *     Mobile, AL 36695
 *
 * This file is part of aoserv-daemon.
 *
 * aoserv-daemon is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * aoserv-daemon is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with aoserv-daemon.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.aoindustries.aoserv.daemon.httpd.tomcat;

import com.aoapps.encoding.ChainWriter;
import com.aoapps.io.posix.PosixFile;
import com.aoindustries.aoserv.client.AoservConnector;
import com.aoindustries.aoserv.client.linux.Server;
import com.aoindustries.aoserv.client.linux.UserServer;
import com.aoindustries.aoserv.client.net.Bind;
import com.aoindustries.aoserv.client.net.IpAddress;
import com.aoindustries.aoserv.client.web.tomcat.Context;
import com.aoindustries.aoserv.client.web.tomcat.ContextDataSource;
import com.aoindustries.aoserv.client.web.tomcat.ContextParameter;
import com.aoindustries.aoserv.client.web.tomcat.JkProtocol;
import com.aoindustries.aoserv.client.web.tomcat.PrivateTomcatSite;
import com.aoindustries.aoserv.client.web.tomcat.Worker;
import com.aoindustries.aoserv.daemon.AoservDaemon;
import com.aoindustries.aoserv.daemon.OperatingSystemConfiguration;
import com.aoindustries.aoserv.daemon.posix.linux.LinuxAccountManager;
import com.aoindustries.aoserv.daemon.util.DaemonFileUtils;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.sql.SQLException;
import java.util.List;

/**
 * Manages PrivateTomcatSite version 8.0.X configurations.
 *
 * @author  AO Industries, Inc.
 */
class HttpdTomcatStdSiteManager_8_0_X extends HttpdTomcatStdSiteManager<TomcatCommon_8_0_X> {

  HttpdTomcatStdSiteManager_8_0_X(PrivateTomcatSite tomcatStdSite) throws SQLException, IOException {
    super(tomcatStdSite);
  }

  @Override
  protected void buildSiteDirectoryContents(String optSlash, PosixFile siteDirectory, boolean isUpgrade) throws IOException, SQLException {
    if (isUpgrade) {
      throw new IllegalArgumentException("In-place upgrade not supported");
    }
    // Resolve and allocate stuff used throughout the method
    final OperatingSystemConfiguration osConfig = OperatingSystemConfiguration.getOperatingSystemConfiguration();
    final String siteDir = siteDirectory.getPath();
    final UserServer lsa = httpdSite.getLinuxServerAccount();
    final int uid = lsa.getUid().getId();
    final int gid = httpdSite.getLinuxServerGroup().getGid().getId();
    final Server thisServer = AoservDaemon.getThisServer();
    int uidMin = thisServer.getUidMin().getId();
    int gidMin = thisServer.getGidMin().getId();
    //final Server postgresServer=thisServer.getPreferredPostgresServer();
    //final String postgresServerMinorVersion=postgresServer == null?null:postgresServer.getPostgresVersion().getMinorVersion();

    /*
     * Create the skeleton of the site, the directories and links.
     */
    DaemonFileUtils.mkdir(siteDir + "/bin", 0770, uid, gid);
    DaemonFileUtils.mkdir(siteDir + "/conf", 0775, uid, gid);
    DaemonFileUtils.mkdir(siteDir + "/conf/Catalina", 0770, uid, gid);
    DaemonFileUtils.mkdir(siteDir + "/daemon", 0770, uid, gid);
    if (!httpdSite.isDisabled()) {
      DaemonFileUtils.ln("../bin/tomcat", siteDir + "/daemon/tomcat", uid, gid);
    }
    DaemonFileUtils.mkdir(siteDir + "/temp", 0770, uid, gid);
    DaemonFileUtils.ln("var/log", siteDir + "/logs", uid, gid);
    DaemonFileUtils.mkdir(siteDir + "/var", 0770, uid, gid);
    DaemonFileUtils.mkdir(siteDir + "/var/log", 0770, uid, gid);
    DaemonFileUtils.mkdir(siteDir + "/var/run", 0770, uid, gid);
    DaemonFileUtils.mkdir(siteDir + "/webapps", 0775, uid, gid);
    DaemonFileUtils.mkdir(siteDir + "/webapps/" + Context.ROOT_DOC_BASE, 0775, uid, gid);
    DaemonFileUtils.mkdir(siteDir + "/webapps/" + Context.ROOT_DOC_BASE + "/WEB-INF", 0775, uid, gid);
    DaemonFileUtils.mkdir(siteDir + "/webapps/" + Context.ROOT_DOC_BASE + "/WEB-INF/classes", 0770, uid, gid);
    DaemonFileUtils.mkdir(siteDir + "/webapps/" + Context.ROOT_DOC_BASE + "/WEB-INF/lib", 0770, uid, gid);
    DaemonFileUtils.mkdir(siteDir + "/work", 0750, uid, gid);
    DaemonFileUtils.ln("../" + optSlash + "apache-tomcat-8.0/bin/bootstrap.jar", siteDir + "/bin/bootstrap.jar", uid, gid);
    DaemonFileUtils.ln("../" + optSlash + "apache-tomcat-8.0/bin/catalina.sh", siteDir + "/bin/catalina.sh", uid, gid);
    DaemonFileUtils.ln("../" + optSlash + "apache-tomcat-8.0/bin/commons-daemon.jar", siteDir + "/bin/commons-daemon.jar", uid, gid);
    DaemonFileUtils.ln("../" + optSlash + "apache-tomcat-8.0/bin/digest.sh", siteDir + "/bin/digest.sh", uid, gid);

    /*
     * Set up the bash profile source
     */
    String profileFile = siteDir + "/bin/profile";
    LinuxAccountManager.setBashProfile(lsa, profileFile);
    try (
        ChainWriter out = new ChainWriter(
            new BufferedOutputStream(
                new PosixFile(profileFile).getSecureOutputStream(
                    uid,
                    gid,
                    0750,
                    false,
                    uidMin,
                    gidMin
                )
            )
        )
        ) {
      out.print("#!/bin/sh\n"
          + "\n"
          + ". /etc/profile\n"
          + ". ").print(osConfig.getDefaultJdkProfileSh()).print('\n');
      //if (enablePhp()) {
      //  out.print(". /opt/php-").print(httpdConfig.getDefaultPhpMinorVersion()).print("-i686/setenv.sh\n");
      //}
      //if (postgresServerMinorVersion != null) {
      //  out.print(". /opt/postgresql-"+postgresServerMinorVersion+"-i686/setenv.sh\n");
      //}
      //out.print(". ").print(osConfig.getAOServClientScriptInclude()).print("\n"
      out.print("\n"
          + "umask 002\n"
          + "\n"
          + "export CATALINA_HOME=\"").print(siteDir).print("\"\n"
          + "export CATALINA_BASE=\"").print(siteDir).print("\"\n"
          + "export CATALINA_TEMP=\"").print(siteDir).print("/temp\"\n"
          + "\n"
          + "export PATH=\"${PATH}:").print(siteDir).print("/bin\"\n"
          + "\n"
          + "export JAVA_OPTS='-server -Djava.awt.headless=true -Xmx128M -Djdk.disableLastUsageTracking=true'\n");
    }

    /*
     * Write the bin/tomcat script.
     */
    String tomcatScript = siteDir + "/bin/tomcat";
    try (
        ChainWriter out = new ChainWriter(
            new BufferedOutputStream(
                new PosixFile(tomcatScript).getSecureOutputStream(
                    uid,
                    gid,
                    0700,
                    false,
                    uidMin,
                    gidMin
                )
            )
        )
        ) {
      out.print("#!/bin/sh\n"
          + "\n"
          + "TOMCAT_HOME=\"").print(siteDir).print("\"\n"
          + "\n"
          + "if [ \"$1\" = \"start\" ]; then\n"
          + "    # Stop any running Tomcat\n"
          + "    \"$0\" stop\n"
          + "    # Start Tomcat wrapper in the background\n"
          + "    nohup \"$0\" daemon </dev/null >&/dev/null &\n"
          + "    echo \"$!\" >\"${TOMCAT_HOME}/var/run/tomcat.pid\"\n"
          + "elif [ \"$1\" = \"stop\" ]; then\n"
          + "    if [ -f \"${TOMCAT_HOME}/var/run/tomcat.pid\" ]; then\n"
          + "        kill `cat \"${TOMCAT_HOME}/var/run/tomcat.pid\"`\n"
          + "        rm -f \"${TOMCAT_HOME}/var/run/tomcat.pid\"\n"
          + "    fi\n"
          + "    if [ -f \"${TOMCAT_HOME}/var/run/java.pid\" ]; then\n"
          + "        cd \"$TOMCAT_HOME\"\n"
          + "        . \"$TOMCAT_HOME/bin/profile\"\n"
          + "        umask 002\n"
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
          + "        mv -f \"${TOMCAT_HOME}/var/log/tomcat_err\" \"${TOMCAT_HOME}/var/log/tomcat_err_$(date +%Y%m%d_%H%M%S)\"\n"
          + "        \"${TOMCAT_HOME}/bin/catalina.sh\" run >&\"${TOMCAT_HOME}/var/log/tomcat_err\" &\n"
          + "        echo \"$!\" >\"${TOMCAT_HOME}/var/run/java.pid\"\n"
          + "        wait\n"
          + "        RETCODE=\"$?\"\n"
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
    }
    DaemonFileUtils.ln("../" + optSlash + "apache-tomcat-8.0/bin/setclasspath.sh", siteDir + "/bin/setclasspath.sh", uid, gid);

    try (ChainWriter out = new ChainWriter(new PosixFile(siteDir + "/bin/shutdown.sh").getSecureOutputStream(uid, gid, 0700, true, uidMin, gidMin))) {
      out.print("#!/bin/sh\n"
          + "exec \"").print(siteDir).print("/bin/tomcat\" stop\n");
    }

    try (ChainWriter out = new ChainWriter(new PosixFile(siteDir + "/bin/startup.sh").getSecureOutputStream(uid, gid, 0700, true, uidMin, gidMin))) {
      out.print("#!/bin/sh\n"
          + "exec \"").print(siteDir).print("/bin/tomcat\" start\n");
    }

    DaemonFileUtils.ln("../" + optSlash + "apache-tomcat-8.0/bin/tomcat-juli.jar", siteDir + "/bin/tomcat-juli.jar", uid, gid);
    DaemonFileUtils.ln("../" + optSlash + "apache-tomcat-8.0/bin/tool-wrapper.sh", siteDir + "/bin/tool-wrapper.sh", uid, gid);
    DaemonFileUtils.ln("../" + optSlash + "apache-tomcat-8.0/bin/version.sh", siteDir + "/bin/version.sh", uid, gid);

    // Create the lib directory and all contents
    DaemonFileUtils.mkdir(siteDir + "/lib", 0770, uid, gid);
    DaemonFileUtils.lnAll("../" + optSlash + "apache-tomcat-8.0/lib/", siteDir + "/lib/", uid, gid);

    //if (postgresServerMinorVersion != null) {
    //  String postgresPath = osConfig.getPostgresPath(postgresServerMinorVersion);
    //  if (postgresPath != null) {
    //    FileUtils.ln("../../.."+postgresPath+"/share/java/postgresql.jar", siteDir+"/lib/postgresql.jar", uid, gid);
    //  }
    //}
    //String mysqlConnectorPath = osConfig.getMySQLConnectorJavaJarPath();
    //if (mysqlConnectorPath != null) {
    //  String filename = new PosixFile(mysqlConnectorPath).getFile().getName();
    //  FileUtils.ln("../../.."+mysqlConnectorPath, siteDir+"/lib/"+filename, uid, gid);
    //}

    /*
     * Write the conf/catalina.policy file
     */
    {
      PosixFile cp = new PosixFile(siteDir + "/conf/catalina.policy");
      new PosixFile("/opt/apache-tomcat-8.0/conf/catalina.policy").copyTo(cp, false);
      cp.chown(uid, gid).setMode(0660);
    }

    {
      PosixFile cp = new PosixFile(siteDir + "/conf/catalina.properties");
      new PosixFile("/opt/apache-tomcat-8.0/conf/catalina.properties").copyTo(cp, false);
      cp.chown(uid, gid).setMode(0660);
    }

    {
      PosixFile cp = new PosixFile(siteDir + "/conf/context.xml");
      new PosixFile("/opt/apache-tomcat-8.0/conf/context.xml").copyTo(cp, false);
      cp.chown(uid, gid).setMode(0660);
    }

    {
      PosixFile cp = new PosixFile(siteDir + "/conf/logging.properties");
      new PosixFile("/opt/apache-tomcat-8.0/conf/logging.properties").copyTo(cp, false);
      cp.chown(uid, gid).setMode(0660);
    }
    {
      PosixFile tu = new PosixFile(siteDir + "/conf/tomcat-users.xml");
      new PosixFile("/opt/apache-tomcat-8.0/conf/tomcat-users.xml").copyTo(tu, false);
      tu.chown(uid, gid).setMode(0660);
    }
    DaemonFileUtils.ln("../" + optSlash + "apache-tomcat-8.0/conf/tomcat-users.xsd", siteDir + "/conf/tomcat-users.xsd", uid, gid);
    {
      // TODO: As symbolic link, what about other files copied?
      PosixFile wx = new PosixFile(siteDir + "/conf/web.xml");
      new PosixFile("/opt/apache-tomcat-8.0/conf/web.xml").copyTo(wx, false);
      wx.chown(uid, gid).setMode(0660);
    }

    /*
     * Write the ROOT/WEB-INF/web.xml file.
     */
    String webXml = siteDir + "/webapps/" + Context.ROOT_DOC_BASE + "/WEB-INF/web.xml";
    try (
        ChainWriter out = new ChainWriter(
            new BufferedOutputStream(
                new PosixFile(webXml).getSecureOutputStream(uid, gid, 0664, false, uidMin, gidMin)
            )
        )
        ) {
      out.print("<?xml version=\"1.0\" encoding=\"ISO-8859-1\"?>\n"
          + "<web-app xmlns=\"http://xmlns.jcp.org/xml/ns/javaee\"\n"
          + "  xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"\n"
          + "  xsi:schemaLocation=\"http://xmlns.jcp.org/xml/ns/javaee\n"
          + "                      http://xmlns.jcp.org/xml/ns/javaee/web-app_3_1.xsd\"\n"
          + "  version=\"3.1\"\n"
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
  public TomcatCommon_8_0_X getTomcatCommon() {
    return TomcatCommon_8_0_X.getInstance();
  }

  @Override
  protected byte[] buildServerXml(PosixFile siteDirectory, String autoWarning) throws IOException, SQLException {
    final TomcatCommon_8_0_X tomcatCommon = getTomcatCommon();
    AoservConnector conn = AoservDaemon.getConnector();

    // Build to RAM first
    ByteArrayOutputStream bout = new ByteArrayOutputStream();
    try (ChainWriter out = new ChainWriter(bout)) {
      List<Worker> hws = tomcatSite.getHttpdWorkers();
      if (hws.size() != 1) {
        throw new SQLException("Expected to only find one Worker for PrivateTomcatSite #" + httpdSite.getPkey() + ", found " + hws.size());
      }
      Worker hw = hws.get(0);
      String hwProtocol = hw.getHttpdJkProtocol(conn).getProtocol(conn).getProtocol();
      if (!hwProtocol.equals(JkProtocol.AJP13)) {
        throw new SQLException("Worker #" + hw.getPkey() + " for PrivateTomcatSite #" + httpdSite.getPkey() + " must be AJP13 but it is " + hwProtocol);
      }
      if (!httpdSite.isManual()) {
        out.print(autoWarning);
      }
      Bind shutdownPort = tomcatStdSite.getTomcat4ShutdownPort();
      if (shutdownPort == null) {
        throw new SQLException("Unable to find shutdown port for PrivateTomcatSite=" + tomcatStdSite);
      }
      String shutdownKey = tomcatStdSite.getTomcat4ShutdownKey();
      if (shutdownKey == null) {
        throw new SQLException("Unable to find shutdown key for PrivateTomcatSite=" + tomcatStdSite);
      }
      out.print(//"<?xml version='1.0' encoding='utf-8'?>\n"
          "<Server port=\"").textInXmlAttribute(shutdownPort.getPort().getPort()).print("\" shutdown=\"").textInXmlAttribute(shutdownKey).print("\">\n"
          + "  <Listener className=\"org.apache.catalina.startup.VersionLoggerListener\" />\n"
          + "  <!-- Security listener. Documentation at /docs/config/listeners.html\n"
          + "  <Listener className=\"org.apache.catalina.security.SecurityListener\" />\n"
          + "  -->\n"
          + "  <!--APR library loader. Documentation at /docs/apr.html -->\n"
          + "  <Listener className=\"org.apache.catalina.core.AprLifecycleListener\" SSLEngine=\"on\" />\n"
          + "  <!-- Prevent memory leaks due to use of particular java/javax APIs-->\n"
          + "  <Listener className=\"org.apache.catalina.core.JreMemoryLeakPreventionListener\" />\n"
          + "  <Listener className=\"org.apache.catalina.mbeans.GlobalResourcesLifecycleListener\" />\n"
          + "  <Listener className=\"org.apache.catalina.core.ThreadLocalLeakPreventionListener\" />\n"
          + "  <!-- Global JNDI resources\n"
          + "       Documentation at /docs/jndi-resources-howto.html\n"
          + "  -->\n"
          + "  <GlobalNamingResources>\n"
          + "    <!-- Editable user database that can also be used by\n"
          + "         UserDatabaseRealm to authenticate users\n"
          + "    -->\n"
          + "    <Resource name=\"UserDatabase\" auth=\"Container\"\n"
          + "              type=\"org.apache.catalina.UserDatabase\"\n"
          + "              description=\"User database that can be updated and saved\"\n"
          + "              factory=\"org.apache.catalina.users.MemoryUserDatabaseFactory\"\n"
          + "              pathname=\"conf/tomcat-users.xml\" />\n"
          + "  </GlobalNamingResources>\n"
          + "  <Service name=\"Catalina\">\n"
          + "    <Connector\n"
          + "      port=\"").textInXmlAttribute(hw.getBind().getPort().getPort()).print("\"\n"
          + "      address=\"").textInXmlAttribute(IpAddress.LOOPBACK_IP).print("\"\n"
          + "      maxParameterCount=\"").textInXmlAttribute(tomcatStdSite.getMaxParameterCount()).print("\"\n"
          + "      maxPostSize=\"").textInXmlAttribute(tomcatStdSite.getMaxPostSize()).print("\"\n"
          + "      protocol=\"AJP/1.3\"\n"
          + "      redirectPort=\"8443\"\n"
          + "      secretRequired=\"false\"\n"
          + "      URIEncoding=\"UTF-8\"\n");
      // Do not include when is default "true"
      if (!tomcatStdSite.getTomcatAuthentication()) {
        out.print("      tomcatAuthentication=\"false\"\n"
            + "      tomcatAuthorization=\"true\"\n");
      }
      out.print("    />\n"
          + "    <Engine name=\"Catalina\" defaultHost=\"localhost\">\n"
          + "      <Realm className=\"org.apache.catalina.realm.LockOutRealm\">\n"
          + "        <Realm className=\"org.apache.catalina.realm.UserDatabaseRealm\" resourceName=\"UserDatabase\"/>\n"
          + "      </Realm>\n"
          + "      <Host\n"
          + "        name=\"localhost\"\n"
          + "        appBase=\"webapps\"\n"
          + "        unpackWARs=\"").textInXmlAttribute(tomcatStdSite.getUnpackWars()).print("\"\n"
          + "        autoDeploy=\"").textInXmlAttribute(tomcatStdSite.getAutoDeploy()).print("\"\n"
          + "        undeployOldVersions=\"").textInXmlAttribute(tomcatStdSite.getUndeployOldVersions()).print("\"\n"
          + "      >\n");
      for (Context htc : tomcatSite.getHttpdTomcatContexts()) {
        if (!htc.isServerXmlConfigured()) {
          out.print("        <!--\n");
        }
        out.print("        <Context\n");
        if (htc.getClassName() != null) {
          out.print("          className=\"").textInXmlAttribute(htc.getClassName()).print("\"\n");
        }
        out.print("          cookies=\"").textInXmlAttribute(htc.useCookies()).print("\"\n"
            + "          crossContext=\"").textInXmlAttribute(htc.allowCrossContext()).print("\"\n"
            + "          docBase=\"").textInXmlAttribute(htc.getDocBase()).print("\"\n"
            + "          override=\"").textInXmlAttribute(htc.allowOverride()).print("\"\n"
            + "          path=\"").textInXmlAttribute(htc.getPath()).print("\"\n"
            + "          privileged=\"").textInXmlAttribute(htc.isPrivileged()).print("\"\n"
            + "          reloadable=\"").textInXmlAttribute(htc.isReloadable()).print("\"\n"
            + "          useNaming=\"").textInXmlAttribute(htc.useNaming()).print("\"\n");
        if (htc.getWrapperClass() != null) {
          out.print("          wrapperClass=\"").textInXmlAttribute(htc.getWrapperClass()).print("\"\n");
        }
        out.print("          debug=\"").textInXmlAttribute(htc.getDebugLevel()).print("\"\n");
        if (htc.getWorkDir() != null) {
          out.print("          workDir=\"").textInXmlAttribute(htc.getWorkDir()).print("\"\n");
        }
        List<ContextParameter> parameters = htc.getHttpdTomcatParameters();
        List<ContextDataSource> dataSources = htc.getHttpdTomcatDataSources();
        if (parameters.isEmpty() && dataSources.isEmpty()) {
          out.print("        />\n");
        } else {
          out.print("        >\n");
          // Parameters
          for (ContextParameter parameter : parameters) {
            tomcatCommon.writeHttpdTomcatParameter(parameter, out);
          }
          // Data Sources
          for (ContextDataSource dataSource : dataSources) {
            tomcatCommon.writeHttpdTomcatDataSource(dataSource, out);
          }
          out.print("        </Context>\n");
        }
        if (!htc.isServerXmlConfigured()) {
          out.print("        -->\n");
        }
      }
      out.print("      </Host>\n"
          + "    </Engine>\n"
          + "  </Service>\n"
          + "</Server>\n");
    }
    return bout.toByteArray();
  }

  @Override
  protected boolean upgradeSiteDirectoryContents(String optSlash, PosixFile siteDirectory) throws IOException, SQLException {
    // The only thing that needs to be modified is the included Tomcat
    return getTomcatCommon().upgradeTomcatDirectory(
        optSlash,
        siteDirectory,
        httpdSite.getLinuxServerAccount().getUid().getId(),
        httpdSite.getLinuxServerGroup().getGid().getId()
    );
  }

  /**
   * Does not use any README.txt for change detection.
   */
  @Override
  protected byte[] generateReadmeTxt(String optSlash, String apacheTomcatDir, PosixFile installDir) throws IOException, SQLException {
    return null;
  }
}
