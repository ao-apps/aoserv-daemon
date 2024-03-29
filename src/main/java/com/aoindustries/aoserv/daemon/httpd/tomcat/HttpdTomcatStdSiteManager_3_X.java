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

package com.aoindustries.aoserv.daemon.httpd.tomcat;

import com.aoapps.encoding.ChainWriter;
import com.aoapps.io.posix.PosixFile;
import com.aoapps.net.DomainName;
import com.aoindustries.aoserv.client.AoservConnector;
import com.aoindustries.aoserv.client.linux.Server;
import com.aoindustries.aoserv.client.linux.UserServer;
import com.aoindustries.aoserv.client.web.tomcat.Context;
import com.aoindustries.aoserv.client.web.tomcat.JkProtocol;
import com.aoindustries.aoserv.client.web.tomcat.PrivateTomcatSite;
import com.aoindustries.aoserv.client.web.tomcat.Worker;
import com.aoindustries.aoserv.daemon.AoservDaemon;
import com.aoindustries.aoserv.daemon.OperatingSystemConfiguration;
import com.aoindustries.aoserv.daemon.posix.linux.LinuxAccountManager;
import com.aoindustries.aoserv.daemon.util.DaemonFileUtils;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.sql.SQLException;
import java.util.List;

/**
 * Manages PrivateTomcatSite version 3.X configurations.
 *
 * @author  AO Industries, Inc.
 */
abstract class HttpdTomcatStdSiteManager_3_X<T extends TomcatCommon_3_X> extends HttpdTomcatStdSiteManager<T> {

  HttpdTomcatStdSiteManager_3_X(PrivateTomcatSite tomcatStdSite) throws SQLException, IOException {
    super(tomcatStdSite);
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
    throw new SQLException("Couldn't find " + JkProtocol.AJP12);
  }

  /**
   * Builds a standard install for Tomcat 3.X
   */
  @Override
  protected void buildSiteDirectoryContents(String optSlash, PosixFile siteDirectory, boolean isUpgrade) throws IOException, SQLException {
    if (isUpgrade) {
      throw new IllegalArgumentException("In-place upgrade not supported");
    }
    // Resolve and allocate stuff used throughout the method
    final TomcatCommon_3_X tomcatCommon = getTomcatCommon();
    final OperatingSystemConfiguration osConfig = OperatingSystemConfiguration.getOperatingSystemConfiguration();
    final String siteDir = siteDirectory.getPath();
    final UserServer lsa = httpdSite.getLinuxServerAccount();
    final int uid = lsa.getUid().getId();
    final int gid = httpdSite.getLinuxServerGroup().getGid().getId();
    final Server thisServer = AoservDaemon.getThisServer();
    int uidMin = thisServer.getUidMin().getId();
    int gidMin = thisServer.getGidMin().getId();
    final DomainName hostname = thisServer.getHostname();
    //final Server postgresServer=thisServer.getPreferredPostgresServer();
    //final String postgresServerMinorVersion=postgresServer == null?null:postgresServer.getPostgresVersion().getMinorVersion();

    /*
     * Create the skeleton of the site, the directories and links.
     */
    DaemonFileUtils.mkdir(siteDir + "/bin", 0770, uid, gid);
    DaemonFileUtils.mkdir(siteDir + "/conf", 0775, uid, gid);
    DaemonFileUtils.mkdir(siteDir + "/daemon", 0770, uid, gid);
    if (!httpdSite.isDisabled()) {
      DaemonFileUtils.ln("../bin/tomcat", siteDir + "/daemon/tomcat", uid, gid);
    }
    DaemonFileUtils.ln("webapps/" + Context.ROOT_DOC_BASE, siteDir + "/htdocs", uid, gid);
    DaemonFileUtils.mkdir(siteDir + "/lib", 0770, uid, gid);
    DaemonFileUtils.ln("webapps/" + Context.ROOT_DOC_BASE + "/WEB-INF/classes", siteDir + "/servlet", uid, gid);
    DaemonFileUtils.mkdir(siteDir + "/var", 0770, uid, gid);
    DaemonFileUtils.mkdir(siteDir + "/var/log", 0770, uid, gid);
    DaemonFileUtils.mkdir(siteDir + "/var/run", 0770, uid, gid);
    DaemonFileUtils.mkdir(siteDir + "/webapps", 0775, uid, gid);
    final String rootDir = siteDir + "/webapps/" + Context.ROOT_DOC_BASE;
    DaemonFileUtils.mkdir(rootDir, 0775, uid, gid);
    DaemonFileUtils.mkdir(siteDir + "/webapps/" + Context.ROOT_DOC_BASE + "/META-INF", 0775, uid, gid);
    DaemonFileUtils.mkdir(siteDir + "/webapps/" + Context.ROOT_DOC_BASE + "/WEB-INF", 0775, uid, gid);
    DaemonFileUtils.mkdir(siteDir + "/webapps/" + Context.ROOT_DOC_BASE + "/WEB-INF/classes", 0770, uid, gid);
    DaemonFileUtils.mkdir(siteDir + "/webapps/" + Context.ROOT_DOC_BASE + "/WEB-INF/cocoon", 0770, uid, gid);
    DaemonFileUtils.mkdir(siteDir + "/webapps/" + Context.ROOT_DOC_BASE + "/WEB-INF/conf", 0770, uid, gid);
    DaemonFileUtils.mkdir(siteDir + "/webapps/" + Context.ROOT_DOC_BASE + "/WEB-INF/lib", 0770, uid, gid);
    DaemonFileUtils.mkdir(siteDir + "/work", 0750, uid, gid);
    final String profileFile = siteDir + "/bin/profile";
    LinuxAccountManager.setBashProfile(lsa, profileFile);
    try (
        ChainWriter out = new ChainWriter(
            new BufferedOutputStream(
                new PosixFile(profileFile).getSecureOutputStream(uid, gid, 0750, false, uidMin, gidMin)
            )
        )
        ) {
      out.print("#!/bin/sh\n"
          + "\n"
          + ". /etc/profile\n"
          + ". ").print(osConfig.getJdk17ProfileSh()).print('\n'
          + ". /opt/jakarta-oro-2.0/setenv.sh\n"
          + ". /opt/jakarta-regexp-1/setenv.sh\n"
          //+ ". /opt/jakarta-servletapi-").print(tomcatCommon.getServletApiVersion()).print("/setenv.sh\n"
          + ". /opt/apache-tomcat-").print(tomcatCommon.getTomcatApiVersion()).print("/setenv.sh\n"
          + ". /opt/jetspeed-1.1/setenv.sh\n"
          + ". /opt/cocoon-1.8/setenv.sh\n"
          + ". /opt/xerces-1.2/setenv.sh\n"
          + ". /opt/ant-1/setenv.sh\n"
          + ". /opt/xalan-1.2/setenv.sh\n");
      //if (enablePhp()) {
      //  out.print(". /opt/php-").print(httpdConfig.getDefaultPhpMinorVersion()).print("-i686/setenv.sh\n");
      //}
      //if (postgresServerMinorVersion != null) {
      //  out.print(". /opt/postgresql-"+postgresServerMinorVersion+"-i686/setenv.sh\n");
      //}
      out.print(". /opt/castor-0.8/setenv.sh\n"
          + ". /opt/cos-27May2002/setenv.sh\n"
          + ". /opt/ecs-1.3/setenv.sh\n"
          + ". /opt/freemarker-1.5/setenv.sh\n"
          + ". /opt/gnu.regexp-1.0/setenv.sh\n"
          + ". /opt/jaf-1.0/setenv.sh\n"
          + ". /opt/slide-1.0/setenv.sh\n"
          + ". /opt/kavachart-3.1/setenv.sh\n"
          + ". /opt/javamail-1.1/setenv.sh\n"
          + ". /opt/jdbc-2.0/setenv.sh\n"
          + ". /opt/jsse-1.0/setenv.sh\n"
          + ". /opt/jyve-20000907/setenv.sh\n"
          + ". /opt/mm.mysql-2.0.7/setenv.sh\n"
          + ". /opt/openxml-1.2/setenv.sh\n"
          + ". /opt/pop3-1.1/setenv.sh\n"
          + ". /opt/soap-2.0/setenv.sh\n"
          + ". /opt/spfc-0.2/setenv.sh\n"
          + ". /opt/turbine-20000907/setenv.sh\n"
          + ". /opt/village-1.3/setenv.sh\n"
          + ". /opt/webmacro-27-08-2000/setenv.sh\n"
          + ". /opt/xang-0.0/setenv.sh\n"
          + ". /opt/xmlrpc-1.0/setenv.sh\n"
          + ". /opt/poolman-1.4/setenv.sh\n"
          + "\n"
          + "export PATH=\"${PATH}:").print(siteDir).print("/bin\"\n"
          + "\n"
          + "export JAVA_OPTS='-server -Djava.awt.headless=true -Xmx128M -Djdk.disableLastUsageTracking=true'\n"
          + "\n"
          + "CLASSPATH=\"${CLASSPATH}:").print(siteDir).print("/classes\"\n"
          + "\n"
          + "for i in ").print(siteDir).print("/lib/* ; do\n"
          + "    if [ -f \"$i\" ]; then\n"
          + "        CLASSPATH=\"${CLASSPATH}:$i\"\n"
          + "    fi\n"
          + "done\n"
          + "\n"
          + "export CLASSPATH\n");
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
          //+ "        ulimit -S -m 196608 -v 400000\n"
          //+ "        ulimit -H -m 196608 -v 400000\n"
          + "        java -Dmail.smtp.host=\"").print(hostname).print("\" org.apache.tomcat.startup.Tomcat -f \"${TOMCAT_HOME}/conf/server.xml\" -stop &>/dev/null\n"
          + "        kill `cat \"${TOMCAT_HOME}/var/run/java.pid\"` &>/dev/null\n"
          + "        rm -f \"${TOMCAT_HOME}/var/run/java.pid\"\n"
          + "    fi\n"
          + "elif [ \"$1\" = \"daemon\" ]; then\n"
          + "    cd \"$TOMCAT_HOME\"\n"
          + "    . \"$TOMCAT_HOME/bin/profile\"\n"
          + "\n"
          + "    while [ 1 ]; do\n"
          //+ "        ulimit -S -m 196608 -v 400000\n"
          //+ "        ulimit -H -m 196608 -v 400000\n"
          + "        umask 002\n"
          + "        java -Dmail.smtp.host=\"").print(hostname).print("\" org.apache.tomcat.startup.Tomcat -f \"${TOMCAT_HOME}/conf/server.xml\" &>var/log/servlet_err &\n"
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
          + "fi\n");
    }
    DaemonFileUtils.mkdir(siteDir + "/classes", 0770, uid, gid);
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
          + "Implementation-Vendor: \"Sun Microsystems, Inc.\"\n");
    }
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
          + "    value CDATA \"\">\n");
    }
    tomcatCommon.createTestTomcatXml(siteDir + "/conf", uid, gid, 0660, uidMin, gidMin);
    try (
        ChainWriter out = new ChainWriter(
            new BufferedOutputStream(
                new PosixFile(siteDir + "/conf/tomcat-users.xml").getSecureOutputStream(uid, gid, 0660, false, uidMin, gidMin)
            )
        )
        ) {
      tomcatCommon.printTomcatUsers(out);
    }
    tomcatCommon.createWebDtd(siteDir + "/conf", uid, gid, 0660, uidMin, gidMin);
    tomcatCommon.createWebXml(siteDir + "/conf", uid, gid, 0660, uidMin, gidMin);
    for (String tomcatLogFile : TomcatCommon_3_X.tomcatLogFiles) {
      String filename = siteDir + "/var/log/" + tomcatLogFile;
      new PosixFile(filename).getSecureOutputStream(uid, gid, 0660, false, uidMin, gidMin).close();
    }
    final String manifestFile = siteDir + "/webapps/" + Context.ROOT_DOC_BASE + "/META-INF/MANIFEST.MF";
    try (ChainWriter out = new ChainWriter(new PosixFile(manifestFile).getSecureOutputStream(uid, gid, 0664, false, uidMin, gidMin))) {
      out.print("Manifest-Version: 1.0");
    }

    /*
     * Write the cocoon.properties file.
     */
    try (OutputStream fileOut = new BufferedOutputStream(new PosixFile(siteDir + "/webapps/" + Context.ROOT_DOC_BASE
        + "/WEB-INF/conf/cocoon.properties").getSecureOutputStream(uid, gid, 0660, false, uidMin, gidMin))) {
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
    try (
        ChainWriter out = new ChainWriter(
            new BufferedOutputStream(
                new PosixFile(siteDir + "/webapps/" + Context.ROOT_DOC_BASE + "/WEB-INF/web.xml").getSecureOutputStream(uid, gid, 0664, false, uidMin, gidMin)
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
