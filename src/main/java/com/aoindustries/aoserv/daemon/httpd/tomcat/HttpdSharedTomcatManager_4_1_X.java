/*
 * aoserv-daemon - Server management daemon for the AOServ Platform.
 * Copyright (C) 2008-2013, 2014, 2015, 2016, 2017, 2018, 2019, 2020, 2021, 2022  AO Industries, Inc.
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

import com.aoapps.collections.SortedArrayList;
import com.aoapps.encoding.ChainWriter;
import com.aoapps.io.posix.PosixFile;
import com.aoapps.io.posix.Stat;
import com.aoindustries.aoserv.client.linux.GroupServer;
import com.aoindustries.aoserv.client.linux.PosixPath;
import com.aoindustries.aoserv.client.linux.Server;
import com.aoindustries.aoserv.client.linux.UserServer;
import com.aoindustries.aoserv.client.net.Bind;
import com.aoindustries.aoserv.client.net.IpAddress;
import com.aoindustries.aoserv.client.web.VirtualHost;
import com.aoindustries.aoserv.client.web.VirtualHostName;
import com.aoindustries.aoserv.client.web.tomcat.Context;
import com.aoindustries.aoserv.client.web.tomcat.ContextDataSource;
import com.aoindustries.aoserv.client.web.tomcat.ContextParameter;
import com.aoindustries.aoserv.client.web.tomcat.SharedTomcat;
import com.aoindustries.aoserv.client.web.tomcat.SharedTomcatSite;
import com.aoindustries.aoserv.client.web.tomcat.Site;
import com.aoindustries.aoserv.client.web.tomcat.Worker;
import com.aoindustries.aoserv.daemon.AOServDaemon;
import com.aoindustries.aoserv.daemon.OperatingSystemConfiguration;
import com.aoindustries.aoserv.daemon.httpd.HttpdOperatingSystemConfiguration;
import com.aoindustries.aoserv.daemon.posix.linux.LinuxAccountManager;
import com.aoindustries.aoserv.daemon.util.DaemonFileUtils;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.IOException;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Manages SharedTomcat version 4.1.X configurations.
 *
 * @author  AO Industries, Inc.
 */
class HttpdSharedTomcatManager_4_1_X extends HttpdSharedTomcatManager<TomcatCommon_4_1_X> {

  private static final Logger logger = Logger.getLogger(HttpdSharedTomcatManager_4_1_X.class.getName());

  HttpdSharedTomcatManager_4_1_X(SharedTomcat sharedTomcat) {
    super(sharedTomcat);
  }

  @Override
  TomcatCommon_4_1_X getTomcatCommon() {
    return TomcatCommon_4_1_X.getInstance();
  }

  @Override
  void buildSharedTomcatDirectory(String optSlash, PosixFile sharedTomcatDirectory, List<File> deleteFileList, Set<SharedTomcat> sharedTomcatsNeedingRestarted) throws IOException, SQLException {
    /*
     * Get values used in the rest of the loop.
     */
    final OperatingSystemConfiguration osConfig = OperatingSystemConfiguration.getOperatingSystemConfiguration();
    final HttpdOperatingSystemConfiguration httpdConfig = osConfig.getHttpdOperatingSystemConfiguration();
    final Server thisServer = AOServDaemon.getThisServer();
    int uid_min = thisServer.getUidMin().getId();
    int gid_min = thisServer.getGidMin().getId();
    final TomcatCommon_4_1_X tomcatCommon = getTomcatCommon();
    final UserServer lsa = sharedTomcat.getLinuxServerAccount();
    final int lsaUID = lsa.getUid().getId();
    final GroupServer lsg = sharedTomcat.getLinuxServerGroup();
    final int lsgGID = lsg.getGid().getId();
    final String wwwGroupDir = sharedTomcatDirectory.getPath();
    final PosixFile bin = new PosixFile(sharedTomcatDirectory, "bin", false);
    final PosixFile tomcatUF = new PosixFile(bin, "tomcat", false);
    final PosixPath wwwDirectory = httpdConfig.getHttpdSitesDirectory();
    final PosixFile sitesFile = new PosixFile(bin, "profile.sites", false);
    final PosixFile conf = new PosixFile(sharedTomcatDirectory, "conf", false);
    final PosixFile daemonUF = new PosixFile(sharedTomcatDirectory, "daemon", false);

    // Create and fill in the directory if it does not exist or is owned by root.
    PosixFile workUF = new PosixFile(sharedTomcatDirectory, "work", false);
    PosixFile innerWorkUF = new PosixFile(workUF, "Tomcat-Apache", false);

    boolean needRestart=false;
    Stat sharedTomcatStat = sharedTomcatDirectory.getStat();
    if (!sharedTomcatStat.exists() || sharedTomcatStat.getUid() == PosixFile.ROOT_GID) {

      // Create the /wwwgroup/name/...

      // 001
      if (!sharedTomcatStat.exists()) {
        sharedTomcatDirectory.mkdir();
      }
      sharedTomcatDirectory.setMode(0770);
      bin.mkdir().chown(lsaUID, lsgGID).setMode(0770);
      conf.mkdir().chown(lsaUID, lsgGID).setMode(0770);
      daemonUF.mkdir().chown(lsaUID, lsgGID).setMode(0770);
      DaemonFileUtils.ln("var/log", wwwGroupDir+"/logs", lsaUID, lsgGID);
      DaemonFileUtils.mkdir(wwwGroupDir+"/temp", 0770, lsaUID, lsgGID);
      PosixFile varUF = new PosixFile(sharedTomcatDirectory, "var", false).mkdir().chown(lsaUID, lsgGID).setMode(0770);
      new PosixFile(varUF, "log", false).mkdir().chown(lsaUID, lsgGID).setMode(0770);
      new PosixFile(varUF, "run", false).mkdir().chown(lsaUID, lsgGID).setMode(0700);

      workUF.mkdir().chown(lsaUID, lsgGID).setMode(0750);
      DaemonFileUtils.mkdir(innerWorkUF.getPath(), 0750, lsaUID, lsgGID);

      DaemonFileUtils.ln("../" + optSlash + "apache-tomcat-4.1/bin/bootstrap.jar", wwwGroupDir+"/bin/bootstrap.jar", lsaUID, lsgGID);
      DaemonFileUtils.ln("../" + optSlash + "apache-tomcat-4.1/bin/catalina.sh", wwwGroupDir+"/bin/catalina.sh", lsaUID, lsgGID);
      DaemonFileUtils.ln("../" + optSlash + "apache-tomcat-4.1/bin/commons-daemon.jar", wwwGroupDir+"/bin/commons-daemon.jar", lsaUID, lsgGID);
      DaemonFileUtils.ln("../" + optSlash + "apache-tomcat-4.1/bin/jasper.sh", wwwGroupDir+"/bin/jasper.sh", lsaUID, lsgGID);
      DaemonFileUtils.ln("../" + optSlash + "apache-tomcat-4.1/bin/jspc.sh", wwwGroupDir+"/bin/jspc.sh", lsaUID, lsgGID);
      DaemonFileUtils.ln("../" + optSlash + "apache-tomcat-4.1/bin/digest.sh", wwwGroupDir+"/bin/digest.sh", lsaUID, lsgGID);

      PosixFile profileUF = new PosixFile(bin, "profile", false);
      LinuxAccountManager.setBashProfile(lsa, profileUF.getPath());

      try (
        ChainWriter out = new ChainWriter(
          new BufferedOutputStream(
            profileUF.getSecureOutputStream(lsaUID, lsgGID, 0750, false, uid_min, gid_min)
          )
        )
      ) {
        out.print("#!/bin/sh\n"
            + "\n"
            + ". /etc/profile\n"
            + ". ").print(osConfig.getJdk17ProfileSh()).print('\n');
        //if (postgresServerMinorVersion != null) {
        //  out.print(". /opt/postgresql-"+postgresServerMinorVersion+"-i686/setenv.sh\n");
        //}
        //out.print(". ").print(osConfig.getAOServClientScriptInclude()).print("\n"
        out.print("\n"
            + "umask 002\n"
            + "\n"
            + "export CATALINA_BASE=\"").print(wwwGroupDir).print("\"\n"
            + "export CATALINA_HOME=\"").print(wwwGroupDir).print("\"\n"
            + "export CATALINA_TEMP=\"").print(wwwGroupDir).print("/temp\"\n"
            + "\n"
            + "export PATH=\"${PATH}:").print(bin).print("\"\n"
            + "\n"
            + "export JAVA_OPTS='-server -Djava.awt.headless=true -Xmx128M -Djdk.disableLastUsageTracking=true'\n"
            + "\n"
            + ". ").print(sitesFile).print("\n"
            + "\n"
            + "for SITE in $SITES\n"
            + "do\n"
            + "    export PATH=\"${PATH}:").print(wwwDirectory).print("/${SITE}/bin\"\n"
            + "done\n");
      }

      // 004

      try (
        ChainWriter out = new ChainWriter(
          new BufferedOutputStream(
            tomcatUF.getSecureOutputStream(lsaUID, lsgGID, 0700, false, uid_min, gid_min)
          )
        )
      ) {
        out.print("#!/bin/sh\n"
            + "\n"
            + "TOMCAT_HOME=\"").print(wwwGroupDir).print("\"\n"
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
            + "        . \"$TOMCAT_HOME/bin/profile\"\n"
            + "        if [ \"$SITES\" != \"\" ]; then\n"
            + "            cd \"$TOMCAT_HOME\"\n"
            + "            \"${TOMCAT_HOME}/bin/catalina.sh\" stop 2>&1 >>\"${TOMCAT_HOME}/var/log/tomcat_err\"\n"
            + "        fi\n"
            + "        kill `cat \"${TOMCAT_HOME}/var/run/java.pid\"` &>/dev/null\n"
            + "        rm -f \"${TOMCAT_HOME}/var/run/java.pid\"\n"
            + "    fi\n"
            + "elif [ \"$1\" = \"daemon\" ]; then\n"
            + "    cd \"$TOMCAT_HOME\"\n"
            + "    . \"$TOMCAT_HOME/bin/profile\"\n"
            + "\n"
            + "    if [ \"$SITES\" != \"\" ]; then\n"
            + "        while [ 1 ]; do\n"
            + "            mv -f \"${TOMCAT_HOME}/var/log/tomcat_err\" \"${TOMCAT_HOME}/var/log/tomcat_err_$(date +%Y%m%d_%H%M%S)\"\n"
            + "            \"${TOMCAT_HOME}/bin/catalina.sh\" run >&\"${TOMCAT_HOME}/var/log/tomcat_err\" &\n"
            + "            echo \"$!\" >\"${TOMCAT_HOME}/var/run/java.pid\"\n"
            + "            wait\n"
            + "            RETCODE=\"$?\"\n"
            + "            echo \"`date`: JVM died with a return code of $RETCODE, restarting in 5 seconds\" >>\"${TOMCAT_HOME}/var/log/jvm_crashes.log\"\n"
            + "            sleep 5\n"
            + "        done\n"
            + "    fi\n"
            + "    rm -f \"${TOMCAT_HOME}/var/run/tomcat.pid\"\n"
            + "else\n"
            + "    echo \"Usage:\"\n"
            + "    echo \"tomcat {start|stop}\"\n"
            + "    echo \"        start - start tomcat\"\n"
            + "    echo \"        stop  - stop tomcat\"\n"
            + "fi\n"
        );
      }

      DaemonFileUtils.ln("../" + optSlash + "apache-tomcat-4.1/bin/setclasspath.sh", wwwGroupDir+"/bin/setclasspath.sh", lsaUID, lsgGID);

      PosixFile shutdown = new PosixFile(bin, "shutdown.sh", false);
      try (ChainWriter out = new ChainWriter(shutdown.getSecureOutputStream(lsaUID, lsgGID, 0700, true, uid_min, gid_min))) {
        out.print("#!/bin/sh\n"
            + "exec \"").print(tomcatUF).print("\" stop\n");
      }

      PosixFile startup = new PosixFile(bin, "startup.sh", false);
      try (ChainWriter out = new ChainWriter(startup.getSecureOutputStream(lsaUID, lsgGID, 0700, true, uid_min, gid_min))) {
        out.print("#!/bin/sh\n"
            + "exec \"").print(tomcatUF).print("\" start\n");
      }

      DaemonFileUtils.ln("../" + optSlash + "apache-tomcat-4.1/bin/tomcat-jni.jar", wwwGroupDir+"/bin/tomcat-jni.jar", lsaUID, lsgGID);
      DaemonFileUtils.ln("../" + optSlash + "apache-tomcat-4.1/bin/tool-wrapper.sh", wwwGroupDir+"/bin/tool-wrapper.sh", lsaUID, lsgGID);

      // Create the common directory and all contents
      DaemonFileUtils.mkdir(wwwGroupDir+"/common", 0770, lsaUID, lsgGID);
      DaemonFileUtils.mkdir(wwwGroupDir+"/common/classes", 0770, lsaUID, lsgGID);
      DaemonFileUtils.mkdir(wwwGroupDir+"/common/endorsed", 0770, lsaUID, lsgGID);
      DaemonFileUtils.lnAll("../../" + optSlash + "apache-tomcat-4.1/common/endorsed/", wwwGroupDir+"/common/endorsed/", lsaUID, lsgGID);
      DaemonFileUtils.mkdir(wwwGroupDir+"/common/i18n", 0770, lsaUID, lsgGID);
      DaemonFileUtils.mkdir(wwwGroupDir+"/common/lib", 0770, lsaUID, lsgGID);
      DaemonFileUtils.lnAll("../../" + optSlash + "apache-tomcat-4.1/common/lib/", wwwGroupDir+"/common/lib/", lsaUID, lsgGID);

      // Write the conf/ files
      {
        PosixFile cp = new PosixFile(conf, "catalina.policy", false);
        new PosixFile("/opt/apache-tomcat-4.1/conf/catalina.policy").copyTo(cp, false);
        cp.chown(lsaUID, lsgGID).setMode(0660);
      }
      {
        PosixFile tuUF = new PosixFile(conf, "tomcat-users.xml", false);
        new PosixFile("/opt/apache-tomcat-4.1/conf/tomcat-users.xml").copyTo(tuUF, false);
        tuUF.chown(lsaUID, lsgGID).setMode(0660);
      }
      {
        PosixFile webUF = new PosixFile(conf, "web.xml", false);
        new PosixFile("/opt/apache-tomcat-4.1/conf/web.xml").copyTo(webUF, false);
        webUF.chown(lsaUID, lsgGID).setMode(0660);
      }

      DaemonFileUtils.mkdir(wwwGroupDir+"/server", 0770, lsaUID, lsgGID);
      DaemonFileUtils.mkdir(wwwGroupDir+"/server/classes", 0770, lsaUID, lsgGID);
      DaemonFileUtils.mkdir(wwwGroupDir+"/server/lib", 0770, lsaUID, lsgGID);
      DaemonFileUtils.lnAll("../../" + optSlash + "apache-tomcat-4.1/server/lib/", wwwGroupDir+"/server/lib/", lsaUID, lsgGID);

      DaemonFileUtils.mkdir(wwwGroupDir+"/server/webapps", 0770, lsaUID, lsgGID);

      // The shared directory
      DaemonFileUtils.mkdir(wwwGroupDir+"/shared", 0770, lsaUID, lsgGID);
      DaemonFileUtils.mkdir(wwwGroupDir+"/shared/classes", 0770, lsaUID, lsgGID);
      DaemonFileUtils.mkdir(wwwGroupDir+"/shared/lib", 0770, lsaUID, lsgGID);

      // Set the ownership to avoid future rebuilds of this directory
      sharedTomcatDirectory.chown(lsaUID, lsgGID);

      needRestart=true;
    }

    // always rebuild profile.sites file
    List<SharedTomcatSite> sites = sharedTomcat.getHttpdTomcatSharedSites();
    if (
      !sharedTomcat.isManual()
      // bin directory may not exist while in manual mode
      || bin.getStat().exists()
    ) {
      PosixFile newSitesFile = new PosixFile(bin, "profile.sites.new", false);
      try (
        ChainWriter out = new ChainWriter(
          new BufferedOutputStream(
            newSitesFile.getSecureOutputStream(lsaUID, lsgGID, 0750, true, uid_min, gid_min)
          )
        )
      ) {
        out.print("export SITES=\"");
        boolean didOne=false;
        for (SharedTomcatSite site : sites) {
          com.aoindustries.aoserv.client.web.Site hs = site.getHttpdTomcatSite().getHttpdSite();
          if (!hs.isDisabled()) {
            if (didOne) {
              out.print(' ');
            } else {
              didOne=true;
            }
            out.print(hs.getName());
          }
        }
        out.print("\"\n");
      }
      // flag as needing a restart if this file is different than any existing
      Stat sitesStat = sitesFile.getStat();
      if (!sitesStat.exists() || !newSitesFile.contentEquals(sitesFile)) {
        needRestart=true;
        if (sitesStat.exists()) {
          PosixFile backupFile = new PosixFile(bin, "profile.sites.old", false);
          sitesFile.renameTo(backupFile);
        }
        newSitesFile.renameTo(sitesFile);
      } else {
        newSitesFile.delete();
      }
    }

    // make work directories and remove extra work dirs
    if (
      !sharedTomcat.isManual()
      // work directory may not exist while in manual mode
      || innerWorkUF.getStat().exists()
    ) {
      List<String> workFiles = new SortedArrayList<>();
      String[] wlist = innerWorkUF.getFile().list();
      if (wlist != null) {
        workFiles.addAll(Arrays.asList(wlist));
      }
      for (SharedTomcatSite site : sites) {
        com.aoindustries.aoserv.client.web.Site hs = site.getHttpdTomcatSite().getHttpdSite();
        if (!hs.isDisabled()) {
          String subwork = hs.getPrimaryHttpdSiteURL().getHostname().toString();
          workFiles.remove(subwork);
          PosixFile workDir = new PosixFile(innerWorkUF, subwork, false);
          if (!workDir.getStat().exists()) {
            workDir
              .mkdir()
              .chown(
                lsaUID,
                hs.getLinuxServerGroup().getGid().getId()
              )
              .setMode(0750)
            ;
          }
        }
      }
      for (String workFile : workFiles) {
        File toDelete = new File(innerWorkUF.getFile(), workFile);
        if (logger.isLoggable(Level.INFO)) {
          logger.info("Scheduling for removal: " + toDelete);
        }
        deleteFileList.add(toDelete);
      }
    }

    if (
      !sharedTomcat.isManual()
      // conf directory may not exist while in manual mode
      || conf.getStat().exists()
    ) {
      // Rebuild the server.xml
      String autoWarning = getAutoWarningXml();
      String autoWarningOld = getAutoWarningXmlOld();
      PosixFile confServerXML = new PosixFile(conf, "server.xml", false);
      if (!sharedTomcat.isManual() || !confServerXML.getStat().exists()) {
        PosixFile newConfServerXML = new PosixFile(conf, "server.xml.new", false);
        try (
          ChainWriter out = new ChainWriter(
            new BufferedOutputStream(
              newConfServerXML.getSecureOutputStream(lsaUID, lsgGID, 0660, true, uid_min, gid_min)
            )
          )
        ) {
          Worker hw=sharedTomcat.getTomcat4Worker();
          if (!sharedTomcat.isManual()) {
            out.print(autoWarning);
          }
          Bind shutdownPort = sharedTomcat.getTomcat4ShutdownPort();
          if (shutdownPort == null) {
            throw new SQLException("Unable to find shutdown key for SharedTomcat: "+sharedTomcat);
          }
          String shutdownKey=sharedTomcat.getTomcat4ShutdownKey();
          if (shutdownKey == null) {
            throw new SQLException("Unable to find shutdown key for SharedTomcat: "+sharedTomcat);
          }
          out.print("<Server port=\"").textInXmlAttribute(shutdownPort.getPort().getPort()).print("\" shutdown=\"").textInXmlAttribute(shutdownKey).print("\" debug=\"0\">\n");
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
              + "  </GlobalNamingResources>\n"
              + "  <Service name=\"Tomcat-Apache\">\n"
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
              + "      className=\"org.apache.ajp.tomcat4.Ajp13Connector\"\n"
              + "      port=\"").textInXmlAttribute(hw.getBind().getPort().getPort()).print("\"\n"
              + "      minProcessors=\"2\"\n"
              + "      maxProcessors=\"200\"\n"
              + "      address=\"").textInXmlAttribute(IpAddress.LOOPBACK_IP).print("\"\n"
              + "      acceptCount=\"10\"\n"
              + "      debug=\"0\"\n"
              + "      maxPostSize=\"").textInXmlAttribute(sharedTomcat.getMaxPostSize()).print("\"\n"
              + "      protocol=\"AJP/1.3\"\n");
          // Do not include when is default "true"
          if (!sharedTomcat.getTomcatAuthentication()) {
            out.print("      tomcatAuthentication=\"false\"\n");
          }
          out.print("    />\n"
              + "    <Engine name=\"Tomcat-Apache\" defaultHost=\"localhost\" debug=\"0\">\n"
              + "      <Logger\n"
              + "        className=\"org.apache.catalina.logger.FileLogger\"\n"
              + "        directory=\"var/log\"\n"
              + "        prefix=\"catalina_log.\"\n"
              + "        suffix=\".txt\"\n"
              + "        timestamp=\"true\"\n"
              + "      />\n"
              + "      <Realm className=\"org.apache.catalina.realm.UserDatabaseRealm\" debug=\"0\" resourceName=\"UserDatabase\" />\n");
          for (SharedTomcatSite site : sites) {
            com.aoindustries.aoserv.client.web.Site hs = site.getHttpdTomcatSite().getHttpdSite();
            if (!hs.isDisabled()) {
              String primaryHostname=hs.getPrimaryHttpdSiteURL().getHostname().toString();
              out.print("      <Host\n"
                  + "        name=\"").textInXmlAttribute(primaryHostname).print("\"\n"
                  + "        debug=\"0\"\n"
                  + "        appBase=\"").textInXmlAttribute(wwwDirectory).print('/').textInXmlAttribute(hs.getName()).print("/webapps\"\n"
                  + "        unpackWARs=\"").textInXmlAttribute(sharedTomcat.getUnpackWARs()).print("\"\n"
                  + "        autoDeploy=\"").textInXmlAttribute(sharedTomcat.getAutoDeploy()).print("\"\n"
                  + "      >\n");
              List<String> usedHostnames=new SortedArrayList<>();
              usedHostnames.add(primaryHostname);
              List<VirtualHost> binds=hs.getHttpdSiteBinds();
              for (VirtualHost bind : binds) {
                List<VirtualHostName> urls=bind.getHttpdSiteURLs();
                for (VirtualHostName url : urls) {
                  String hostname = url.getHostname().toString();
                  if (!usedHostnames.contains(hostname)) {
                    out.print("        <Alias>").textInXhtml(hostname).print("</Alias>\n");
                    usedHostnames.add(hostname);
                  }
                }
                // When listed first, also include the IP addresses as aliases
                if (hs.getListFirst()) {
                  String ip=bind.getHttpdBind().getNetBind().getIpAddress().getInetAddress().toString();
                  if (!usedHostnames.contains(ip)) {
                    out.print("        <Alias>").textInXhtml(ip).print("</Alias>\n");
                    usedHostnames.add(ip);
                  }
                }
              }
              out.print("        <Logger\n"
                  + "          className=\"org.apache.catalina.logger.FileLogger\"\n"
                  + "          directory=\"").textInXmlAttribute(wwwDirectory).print('/').textInXmlAttribute(hs.getName()).print("/var/log\"\n"
                  + "          prefix=\"").textInXmlAttribute(primaryHostname).print("_log.\"\n"
                  + "          suffix=\".txt\"\n"
                  + "          timestamp=\"true\"\n"
                  + "        />\n");
              Site tomcatSite=hs.getHttpdTomcatSite();
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
                List<ContextParameter> parameters=htc.getHttpdTomcatParameters();
                List<ContextDataSource> dataSources=htc.getHttpdTomcatDataSources();
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
              out.print("      </Host>\n");
            }
          }
          out.print("    </Engine>\n"
              + "  </Service>\n"
              + "</Server>\n");
        }

        // Must restart JVM if this file has changed
        if (
          !confServerXML.getStat().exists()
          || !newConfServerXML.contentEquals(confServerXML)
        ) {
          needRestart=true;
          newConfServerXML.renameTo(confServerXML);
        } else {
          newConfServerXML.delete();
        }
      } else {
        try {
          DaemonFileUtils.stripFilePrefix(
            confServerXML,
            autoWarningOld,
            uid_min,
            gid_min
          );
          DaemonFileUtils.stripFilePrefix(
            confServerXML,
            autoWarning,
            uid_min,
            gid_min
          );
        } catch (IOException err) {
          // Errors OK because this is done in manual mode and they might have symbolic linked stuff
        }
      }
    }

    // Enable/Disable
    boolean hasEnabledSite = false;
    for (SharedTomcatSite htss : sharedTomcat.getHttpdTomcatSharedSites()) {
      if (!htss.getHttpdTomcatSite().getHttpdSite().isDisabled()) {
        hasEnabledSite = true;
        break;
      }
    }
    PosixFile daemonSymlink = new PosixFile(daemonUF, "tomcat", false);
    if (
      !sharedTomcat.isDisabled()
      && hasEnabledSite
      && (
        !sharedTomcat.isManual()
        // Script may not exist while in manual mode
        || tomcatUF.getStat().exists()
      )
    ) {
      // Enabled
      if (!daemonSymlink.getStat().exists()) {
        daemonSymlink
          .symLink("../bin/tomcat")
          .chown(lsaUID, lsgGID);
      }
      // Start if needed
      if (needRestart) {
        sharedTomcatsNeedingRestarted.add(sharedTomcat);
      }
    } else {
      // Disabled
      if (daemonSymlink.getStat().exists()) {
        daemonSymlink.delete();
      }
    }
  }

  @Override
  protected boolean upgradeSharedTomcatDirectory(String optSlash, PosixFile siteDirectory) throws IOException, SQLException {
    // Upgrade Tomcat
    boolean needsRestart = getTomcatCommon().upgradeTomcatDirectory(
      optSlash,
      siteDirectory,
      sharedTomcat.getLinuxServerAccount().getUid().getId(),
      sharedTomcat.getLinuxServerGroup().getGid().getId()
    );
    return needsRestart;
  }
}
