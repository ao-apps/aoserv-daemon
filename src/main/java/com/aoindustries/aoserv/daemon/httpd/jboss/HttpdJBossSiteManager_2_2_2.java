/*
 * aoserv-daemon - Server management daemon for the AOServ Platform.
 * Copyright (C) 2007-2013, 2014, 2015, 2016, 2017, 2018, 2019, 2020, 2021, 2022  AO Industries, Inc.
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

package com.aoindustries.aoserv.daemon.httpd.jboss;

import com.aoapps.encoding.ChainWriter;
import com.aoapps.io.posix.PosixFile;
import com.aoapps.net.InetAddress;
import com.aoindustries.aoserv.client.AoservConnector;
import com.aoindustries.aoserv.client.linux.Group;
import com.aoindustries.aoserv.client.linux.GroupServer;
import com.aoindustries.aoserv.client.linux.PosixPath;
import com.aoindustries.aoserv.client.linux.Server;
import com.aoindustries.aoserv.client.linux.User;
import com.aoindustries.aoserv.client.linux.UserServer;
import com.aoindustries.aoserv.client.net.Bind;
import com.aoindustries.aoserv.client.web.jboss.Site;
import com.aoindustries.aoserv.client.web.tomcat.Context;
import com.aoindustries.aoserv.client.web.tomcat.JkProtocol;
import com.aoindustries.aoserv.client.web.tomcat.Version;
import com.aoindustries.aoserv.client.web.tomcat.Worker;
import com.aoindustries.aoserv.daemon.AoservDaemon;
import com.aoindustries.aoserv.daemon.OperatingSystemConfiguration;
import com.aoindustries.aoserv.daemon.httpd.tomcat.TomcatCommon_3_2_4;
import com.aoindustries.aoserv.daemon.httpd.tomcat.TomcatCommon_3_X;
import com.aoindustries.aoserv.daemon.posix.linux.LinuxAccountManager;
import com.aoindustries.aoserv.daemon.posix.linux.PackageManager;
import com.aoindustries.aoserv.daemon.util.DaemonFileUtils;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.sql.SQLException;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Manages Site version 2.2.2 configurations.
 *
 * @author  AO Industries, Inc.
 */
class HttpdJBossSiteManager_2_2_2 extends HttpdJBossSiteManager<TomcatCommon_3_2_4> {

  HttpdJBossSiteManager_2_2_2(Site jbossSite) throws SQLException, IOException {
    super(jbossSite);
  }

  @Override
  protected Set<PackageManager.PackageName> getRequiredPackages() {
    return EnumSet.of(PackageManager.PackageName.JBOSS_2_2_2);
  }

  @Override
  protected void buildSiteDirectoryContents(String optSlash, PosixFile siteDirectory, boolean isUpgrade) throws IOException, SQLException {
    if (isUpgrade) {
      throw new IllegalArgumentException("In-place upgrade not supported");
    }
    /*
     * Resolve and allocate stuff used throughout the method
     */
    final OperatingSystemConfiguration osConfig = OperatingSystemConfiguration.getOperatingSystemConfiguration();
    final PosixPath replaceCommand = osConfig.getReplaceCommand();
    if (replaceCommand == null) {
      throw new IOException("OperatingSystem doesn't have replace command");
    }
    final TomcatCommon_3_2_4 tomcatCommon = getTomcatCommon();
    final String siteDir = siteDirectory.getPath();
    final UserServer lsa = httpdSite.getLinuxServerAccount();
    final GroupServer lsg = httpdSite.getLinuxServerGroup();
    final int uid = lsa.getUid().getId();
    final int gid = lsg.getGid().getId();
    final User.Name laUsername = lsa.getLinuxAccount_username_id();
    final Group.Name laGroupname = lsg.getLinuxGroup().getName();
    final String siteName = httpdSite.getName();

    /*
     * Create the skeleton of the site, the directories and links.
     */
    DaemonFileUtils.mkdir(new PosixFile(siteDirectory, "bin", false), 0770, uid, gid);
    DaemonFileUtils.mkdir(siteDir + "/conf", 0775, uid, gid);
    DaemonFileUtils.mkdir(siteDir + "/daemon", 0770, uid, gid);
    DaemonFileUtils.ln("webapps/" + Context.ROOT_DOC_BASE, siteDir + "/htdocs", uid, gid);
    DaemonFileUtils.mkdir(siteDir + "/lib", 0770, uid, gid);
    DaemonFileUtils.ln("var/log", siteDir + "/logs", uid, gid);
    DaemonFileUtils.ln("webapps/" + Context.ROOT_DOC_BASE + "/WEB-INF/classes", siteDir + "/servlet", uid, gid);
    DaemonFileUtils.mkdir(siteDir + "/var", 0770, uid, gid);
    DaemonFileUtils.mkdir(siteDir + "/var/log", 0770, uid, gid);
    DaemonFileUtils.mkdir(siteDir + "/var/run", 0770, uid, gid);
    DaemonFileUtils.mkdir(siteDir + "/webapps", 0775, uid, gid);

    String templateDir = jbossSite.getHttpdJbossVersion().getTemplateDirectory();
    File f = new File(templateDir);
    String[] contents = f.list();
    String[] command = new String[contents.length + 3];
    command[0] = "/bin/cp";
    command[1] = "-rdp";
    command[command.length - 1] = siteDir;
    for (int i = 0; i < contents.length; i++) {
      command[i + 2] = templateDir + "/" + contents[i];
    }
    AoservDaemon.exec(command);
    // chown
    AoservDaemon.exec(
        "/bin/chown",
        "-R",
        laUsername.toString(),
        siteDir + "/jboss",
        siteDir + "/bin",
        siteDir + "/lib",
        siteDir + "/daemon"
    );
    // chgrp
    AoservDaemon.exec(
        "/bin/chgrp",
        "-R",
        laGroupname.toString(),
        siteDir + "/jboss",
        siteDir + "/bin",
        siteDir + "/lib",
        siteDir + "/daemon"
    );

    String jbossConfDir = siteDir + "/jboss/conf/tomcat";
    File f2 = new File(jbossConfDir);
    String[] f2contents = f2.list();

    String[] command3 = new String[5];
    command3[0] = replaceCommand.toString();
    command3[3] = "--";
    for (int i = 0; i < 5; i++) {
      for (String f2content : f2contents) {
        switch (i) {
          case 0:
            command3[1] = "2222";
            command3[2] = String.valueOf(jbossSite.getJnpBind().getPort().getPort());
            break;
          case 1:
            command3[1] = "3333";
            command3[2] = String.valueOf(jbossSite.getWebserverBind().getPort().getPort());
            break;
          case 2:
            command3[1] = "4444";
            command3[2] = String.valueOf(jbossSite.getRmiBind().getPort().getPort());
            break;
          case 3:
            command3[1] = "5555";
            command3[2] = String.valueOf(jbossSite.getHypersonicBind().getPort().getPort());
            break;
          case 4:
            command3[1] = "6666";
            command3[2] = String.valueOf(jbossSite.getJmxBind().getPort().getPort());
            break;
          default:
            throw new AssertionError();
        }
        command3[4] = jbossConfDir + "/" + f2content;
        AoservDaemon.exec(command3);
      }
    }
    AoservDaemon.exec(
        replaceCommand.toString(),
        "site_name",
        siteName,
        "--",
        siteDir + "/bin/jboss",
        siteDir + "/bin/profile.jboss",
        siteDir + "/bin/profile.user"
    );
    DaemonFileUtils.ln(".", siteDir + "/tomcat", uid, gid);

    DaemonFileUtils.mkdir(siteDir + "/webapps/" + Context.ROOT_DOC_BASE, 0775, uid, gid);
    DaemonFileUtils.mkdir(siteDir + "/webapps/" + Context.ROOT_DOC_BASE + "/META-INF", 0775, uid, gid);
    DaemonFileUtils.mkdir(siteDir + "/webapps/" + Context.ROOT_DOC_BASE + "/WEB-INF", 0775, uid, gid);
    DaemonFileUtils.mkdir(siteDir + "/webapps/" + Context.ROOT_DOC_BASE + "/WEB-INF/classes", 0770, uid, gid);
    DaemonFileUtils.mkdir(siteDir + "/webapps/" + Context.ROOT_DOC_BASE + "/WEB-INF/cocoon", 0770, uid, gid);
    DaemonFileUtils.mkdir(siteDir + "/webapps/" + Context.ROOT_DOC_BASE + "/WEB-INF/conf", 0770, uid, gid);
    DaemonFileUtils.mkdir(siteDir + "/webapps/" + Context.ROOT_DOC_BASE + "/WEB-INF/lib", 0770, uid, gid);
    DaemonFileUtils.mkdir(siteDir + "/work", 0750, uid, gid);

    /*
     * Set up the bash profile source
     */
    String profileFile = siteDir + "/bin/profile.jboss";
    LinuxAccountManager.setBashProfile(lsa, profileFile);

    /*
     * The classes directory
     */
    DaemonFileUtils.mkdir(siteDir + "/classes", 0770, uid, gid);

    Server thisServer = AoservDaemon.getThisServer();
    int uidMin = thisServer.getUidMin().getId();
    int gidMin = thisServer.getGidMin().getId();
    /*
     * Write the manifest.servlet file.
     */
    String confManifestServlet = siteDir + "/conf/manifest.servlet";
    try (
        ChainWriter out = new ChainWriter(
            new BufferedOutputStream(
                new PosixFile(confManifestServlet).getSecureOutputStream(uid, gid, 0660, false, uidMin, gidMin)
            )
        )
        ) {
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
    }

    /*
     * Create the conf/server.dtd file.
     */
    String confServerDtd = siteDir + "/conf/server.dtd";
    try (
        ChainWriter out = new ChainWriter(
            new BufferedOutputStream(
                new PosixFile(confServerDtd).getSecureOutputStream(uid, gid, 0660, false, uidMin, gidMin)
            )
        )
        ) {
      out.print("<?xml version=\"1.0\" encoding=\"ISO-8859-1\"?>\n"
          + "\n"
          + "<!ELEMENT Host (ContextManager+)>\n"
          + "<!ATTLIST Host\n"
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
    }

    /*
     * Create the test-tomcat.xml file.
     */
    tomcatCommon.createTestTomcatXml(siteDir + "/conf", uid, gid, 0660, uidMin, gidMin);

    /*
     * Create the tomcat-users.xml file
     */
    String confTomcatUsers = siteDir + "/conf/tomcat-users.xml";
    try (
        ChainWriter out = new ChainWriter(
            new BufferedOutputStream(
                new PosixFile(confTomcatUsers).getSecureOutputStream(uid, gid, 0660, false, uidMin, gidMin)
            )
        )
        ) {
      tomcatCommon.printTomcatUsers(out);
    }

    /*
     * Create the web.dtd file.
     */
    tomcatCommon.createWebDtd(siteDir + "/conf", uid, gid, 0660, uidMin, gidMin);

    /*
     * Create the web.xml file.
     */
    tomcatCommon.createWebXml(siteDir + "/conf", uid, gid, 0660, uidMin, gidMin);

    /*
     * Create the empty log files.
     */
    for (String tomcatLogFile : TomcatCommon_3_X.tomcatLogFiles) {
      String filename = siteDir + "/var/log/" + tomcatLogFile;
      new PosixFile(filename).getSecureOutputStream(uid, gid, 0660, false, uidMin, gidMin).close();
    }

    /*
     * Create the manifest file.
     */
    String manifestFile = siteDir + "/webapps/" + Context.ROOT_DOC_BASE + "/META-INF/MANIFEST.MF";
    try (
        ChainWriter out = new ChainWriter(
            new PosixFile(manifestFile).getSecureOutputStream(
                uid,
                gid,
                0664,
                false,
                uidMin,
                gidMin
            )
        )
        ) {
      out.print("Manifest-Version: 1.0");
    }

    /*
     * Write the cocoon.properties file.
     */
    String cocoonProps = siteDir + "/webapps/" + Context.ROOT_DOC_BASE + "/WEB-INF/conf/cocoon.properties";
    try (OutputStream fileOut = new BufferedOutputStream(new PosixFile(cocoonProps).getSecureOutputStream(uid, gid, 0660, false, uidMin, gidMin))) {
      tomcatCommon.copyCocoonProperties1(fileOut);
      try (ChainWriter out = new ChainWriter(fileOut)) {
        out.print("processor.xsp.repository = ").print(siteDir).print("/webapps/" + Context.ROOT_DOC_BASE + "/WEB-INF/cocoon\n");
        out.flush();
        tomcatCommon.copyCocoonProperties2(fileOut);
      }
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
    }
  }

  @Override
  public TomcatCommon_3_2_4 getTomcatCommon() {
    return TomcatCommon_3_2_4.getInstance();
  }

  /**
   * Only supports ajp12.
   */
  @Override
  protected Worker getHttpdWorker() throws IOException, SQLException {
    AoservConnector conn = AoservDaemon.getConnector();
    List<Worker> workers = tomcatSite.getHttpdWorkers();

    // Try ajp12 next
    for (Worker hw : workers) {
      if (hw.getHttpdJkProtocol(conn).getProtocol(conn).getProtocol().equals(JkProtocol.AJP12)) {
        return hw;
      }
    }
    throw new SQLException("Couldn't find ajp12");
  }

  @Override
  protected void enableDisable(PosixFile siteDirectory) throws IOException, SQLException {
    PosixFile bin = new PosixFile(siteDirectory, "bin", false);
    PosixFile jboss = new PosixFile(bin, "jboss", false);
    PosixFile daemon = new PosixFile(siteDirectory, "daemon", false);
    PosixFile daemonSymlink = new PosixFile(daemon, "jboss", false);
    if (
        !httpdSite.isDisabled()
            && (
            !httpdSite.isManual()
                // Script may not exist while in manual mode
                || jboss.getStat().exists()
        )
    ) {
      // Enabled
      if (!daemonSymlink.getStat().exists()) {
        daemonSymlink.symLink("../bin/jboss").chown(
            httpdSite.getLinuxServerAccount().getUid().getId(),
            httpdSite.getLinuxServerGroup().getGid().getId()
        );
      }
    } else {
      // Disabled
      if (daemonSymlink.getStat().exists()) {
        daemonSymlink.delete();
      }
    }
  }

  /**
   * Builds the server.xml file.
   */
  protected byte[] buildServerXml(PosixFile siteDirectory, String autoWarning) throws IOException, SQLException {
    final String siteDir = siteDirectory.getPath();
    final AoservConnector conn = AoservDaemon.getConnector();
    final Version htv = tomcatSite.getHttpdTomcatVersion();

    ByteArrayOutputStream bout = new ByteArrayOutputStream();
    try (ChainWriter out = new ChainWriter(bout)) {
      out.print("<?xml version=\"1.0\" encoding=\"ISO-8859-1\"?>\n");
      if (!httpdSite.isManual()) {
        out.print(autoWarning);
      }
      out.print("<Host>\n"
          + "  <xmlmapper:debug level=\"0\" />\n"
          + "  <Logger name=\"tc_log\" verbosityLevel = \"INFORMATION\" path=\"").textInXmlAttribute(siteDir).print("/var/log/tomcat.log\" />\n"
          + "  <Logger name=\"servlet_log\" path=\"").textInXmlAttribute(siteDir).print("/var/log/servlet.log\" />\n"
          + "  <Logger name=\"JASPER_LOG\" path=\"").textInXmlAttribute(siteDir).print("/var/log/jasper.log\" verbosityLevel = \"INFORMATION\" />\n"
          + "\n"
          + "  <ContextManager debug=\"0\" home=\"").textInXmlAttribute(siteDir).print("\" workDir=\"").textInXmlAttribute(siteDir).print("/work\" showDebugInfo=\"true\" >\n"
          + "    <ContextInterceptor className=\"org.apache.tomcat.context.WebXmlReader\" />\n"
          + "    <ContextInterceptor className=\"org.apache.tomcat.context.LoaderInterceptor\" />\n"
          + "    <ContextInterceptor className=\"org.apache.tomcat.context.DefaultCMSetter\" />\n"
          + "    <ContextInterceptor className=\"org.apache.tomcat.context.WorkDirInterceptor\" />\n"
          + "    <RequestInterceptor className=\"org.apache.tomcat.request.Jdk12Interceptor\" />\n"
          + "    <RequestInterceptor className=\"org.apache.tomcat.request.SessionInterceptor\" noCookies=\"false\" />\n"
          + "    <RequestInterceptor className=\"org.apache.tomcat.request.SimpleMapper1\" debug=\"0\" />\n"
          + "    <RequestInterceptor className=\"org.apache.tomcat.request.InvokerInterceptor\" debug=\"0\" prefix=\"/servlet/\" />\n"
          + "    <RequestInterceptor className=\"org.apache.tomcat.request.StaticInterceptor\" debug=\"0\" suppress=\"false\" />\n"
          + "    <RequestInterceptor className=\"org.apache.tomcat.session.StandardSessionInterceptor\" />\n"
          + "    <RequestInterceptor className=\"org.apache.tomcat.request.AccessInterceptor\" debug=\"0\" />\n"
          + "    <RequestInterceptor className=\"org.jboss.tomcat.security.JBossSecurityMgrRealm\" />\n"
          + "    <ContextInterceptor className=\"org.apache.tomcat.context.LoadOnStartupInterceptor\" />\n");

      for (Worker worker : tomcatSite.getHttpdWorkers()) {
        Bind netBind = worker.getBind();
        String protocol = worker.getHttpdJkProtocol(conn).getProtocol(conn).getProtocol();

        out.print("    <Connector className=\"org.apache.tomcat.service.PoolTcpConnector\">\n"
            + "      <Parameter name=\"handler\" value=\"");
        switch (protocol) {
          case JkProtocol.AJP12:
            out.print("org.apache.tomcat.service.connector.Ajp12ConnectionHandler");
            break;
          case JkProtocol.AJP13:
            out.print("org.apache.tomcat.service.connector.Ajp13ConnectionHandler");
            break;
          default:
            throw new IllegalArgumentException("Unknown AJP version: " + htv);
        }
        out.print("\"/>\n"
            + "      <Parameter name=\"port\" value=\"").textInXmlAttribute(netBind.getPort().getPort()).print("\"/>\n");
        InetAddress ip = netBind.getIpAddress().getInetAddress();
        if (!ip.isUnspecified()) {
          out.print("      <Parameter name=\"inet\" value=\"").textInXmlAttribute(ip).print("\"/>\n");
        }
        out.print("      <Parameter name=\"max_threads\" value=\"30\"/>\n"
            + "      <Parameter name=\"max_spare_threads\" value=\"10\"/>\n"
            + "      <Parameter name=\"min_spare_threads\" value=\"1\"/>\n"
            + "    </Connector>\n"
        );
      }
      for (Context htc : tomcatSite.getHttpdTomcatContexts()) {
        out.print("    <Context path=\"").textInXmlAttribute(htc.getPath()).print("\" docBase=\"")
            .textInXmlAttribute(htc.getDocBase()).print("\" debug=\"").textInXmlAttribute(htc.getDebugLevel())
            .print("\" reloadable=\"").textInXmlAttribute(htc.isReloadable()).print("\" />\n");
      }
      out.print("  </ContextManager>\n"
          + "</Server>\n");
    }
    return bout.toByteArray();
  }

  @Override
  protected boolean rebuildConfigFiles(PosixFile siteDirectory, Set<PosixFile> restorecon) throws IOException, SQLException {
    final String siteDir = siteDirectory.getPath();
    boolean needsRestart = false;
    PosixFile conf = new PosixFile(siteDir + "/conf");
    if (
        !httpdSite.isManual()
            // conf directory may not exist while in manual mode
            || conf.getStat().exists()
    ) {
      // Rebuild the server.xml
      String autoWarning = getAutoWarningXml();
      String autoWarningOld = getAutoWarningXmlOld();

      Server thisServer = AoservDaemon.getThisServer();
      int uidMin = thisServer.getUidMin().getId();
      int gidMin = thisServer.getGidMin().getId();

      PosixFile confServerXml = new PosixFile(conf, "server.xml", false);
      if (!httpdSite.isManual() || !confServerXml.getStat().exists()) {
        // Only write to the actual file when missing or changed
        if (
            DaemonFileUtils.atomicWrite(
                confServerXml,
                buildServerXml(siteDirectory, autoWarning),
                0660,
                httpdSite.getLinuxServerAccount().getUid().getId(),
                httpdSite.getLinuxServerGroup().getGid().getId(),
                null,
                restorecon
            )
        ) {
          // Flag as needing restarted
          needsRestart = true;
        }
      } else {
        try {
          DaemonFileUtils.stripFilePrefix(
              confServerXml,
              autoWarningOld,
              uidMin,
              gidMin
          );
          DaemonFileUtils.stripFilePrefix(
              confServerXml,
              autoWarning,
              uidMin,
              gidMin
          );
        } catch (IOException err) {
          // Errors OK because this is done in manual mode and they might have symbolic linked stuff
        }
      }
    }
    return needsRestart;
  }

  @Override
  protected boolean upgradeSiteDirectoryContents(String optSlash, PosixFile siteDirectory) throws IOException, SQLException {
    // Nothing to do
    return false;
  }

  /**
   * Does not use any README.txt for change detection.
   */
  @Override
  protected byte[] generateReadmeTxt(String optSlash, String apacheTomcatDir, PosixFile installDir) throws IOException, SQLException {
    return null;
  }
}
