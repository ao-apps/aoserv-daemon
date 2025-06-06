/*
 * aoserv-daemon - Server management daemon for the AOServ Platform.
 * Copyright (C) 2018, 2019, 2020, 2021, 2022, 2023, 2024, 2025  AO Industries, Inc.
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

import static com.aoindustries.aoserv.daemon.httpd.tomcat.VersionedTomcatCommon.BACKUP_EXTENSION;
import static com.aoindustries.aoserv.daemon.httpd.tomcat.VersionedTomcatCommon.BACKUP_SEPARATOR;

import com.aoapps.collections.SortedArrayList;
import com.aoapps.encoding.ChainWriter;
import com.aoapps.io.posix.PosixFile;
import com.aoapps.io.posix.Stat;
import com.aoapps.lang.io.IoUtils;
import com.aoindustries.aoserv.client.aosh.Command;
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
import com.aoindustries.aoserv.daemon.AoservDaemon;
import com.aoindustries.aoserv.daemon.OperatingSystemConfiguration;
import com.aoindustries.aoserv.daemon.httpd.HttpdOperatingSystemConfiguration;
import com.aoindustries.aoserv.daemon.httpd.tomcat.Install.Delete;
import com.aoindustries.aoserv.daemon.httpd.tomcat.Install.Generated;
import com.aoindustries.aoserv.daemon.util.DaemonFileUtils;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Manages shared aspects of SharedTomcat version 8.5 and above.
 *
 * @author  AO Industries, Inc.
 */
public abstract class VersionedSharedTomcatManager<T extends VersionedTomcatCommon> extends HttpdSharedTomcatManager<T> {

  private static final Logger logger = Logger.getLogger(VersionedSharedTomcatManager.class.getName());

  /**
   * The name of the file generated for version change detection.
   */
  private static final String README_TXT = "README.txt";

  VersionedSharedTomcatManager(SharedTomcat sharedTomcat) {
    super(sharedTomcat);
  }

  /**
   * Writes the server.xml file.
   *
   * @param out  Is in UTF-8 encoding.
   */
  protected void writeServerXml(
      String autoWarning,
      ChainWriter out,
      SharedTomcat sharedTomcat,
      List<SharedTomcatSite> sites
  ) throws IOException, SQLException {
    final T tomcatCommon = getTomcatCommon();
    final OperatingSystemConfiguration osConfig = OperatingSystemConfiguration.getOperatingSystemConfiguration();
    final HttpdOperatingSystemConfiguration httpdConfig = osConfig.getHttpdOperatingSystemConfiguration();
    final PosixPath wwwDirectory = httpdConfig.getHttpdSitesDirectory();

    final Worker hw = sharedTomcat.getTomcat4Worker();
    final Bind shutdownPort = sharedTomcat.getTomcat4ShutdownPort();
    if (shutdownPort == null) {
      throw new SQLException("Unable to find shutdown key for SharedTomcat: " + sharedTomcat);
    }
    final String shutdownKey = sharedTomcat.getTomcat4ShutdownKey();
    if (shutdownKey == null) {
      throw new SQLException("Unable to find shutdown key for SharedTomcat: " + sharedTomcat);
    }
    out.print("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
    if (!sharedTomcat.isManual()) {
      out.print(autoWarning);
    }
    out.print("<Server port=\"").textInXmlAttribute(shutdownPort.getPort().getPort()).print("\" shutdown=\"").textInXmlAttribute(shutdownKey).print("\">\n"
        + "  <Listener className=\"org.apache.catalina.startup.VersionLoggerListener\" />\n"
        + "  <!-- Security listener. Documentation at /docs/config/listeners.html\n"
        + "  <Listener className=\"org.apache.catalina.security.SecurityListener\" />\n"
        + "  -->\n");
    tomcatCommon.getOpenSslLifecycleType().printOpenSslLifecycleListener(out);
    out.print("  <!-- Prevent memory leaks due to use of particular java/javax APIs-->\n"
        + "  <Listener className=\"org.apache.catalina.core.JreMemoryLeakPreventionListener\" />\n"
        + "  <Listener className=\"org.apache.catalina.mbeans.GlobalResourcesLifecycleListener\" />\n"
        + "  <Listener className=\"org.apache.catalina.core.ThreadLocalLeakPreventionListener\" />\n"
        + "\n"
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
        + "\n"
        + "  <Service name=\"Catalina\">\n"
        + "    <Connector\n"
        + "      port=\"").textInXmlAttribute(hw.getBind().getPort().getPort()).print("\"\n"
        + "      address=\"").textInXmlAttribute(IpAddress.LOOPBACK_IP).print("\"\n"
        + "      maxParameterCount=\"").textInXmlAttribute(sharedTomcat.getMaxParameterCount()).print("\"\n"
        + "      maxPostSize=\"").textInXmlAttribute(sharedTomcat.getMaxPostSize()).print("\"\n"
        + "      protocol=\"AJP/1.3\"\n"
        + "      redirectPort=\"8443\"\n"
        + "      secretRequired=\"false\"\n"
        + "      URIEncoding=\"UTF-8\"\n");
    // Do not include when is default "true"
    if (!sharedTomcat.getTomcatAuthentication()) {
      out.print("      tomcatAuthentication=\"false\"\n"
          + "      tomcatAuthorization=\"true\"\n");
    }
    out.print("    />\n"
        + "\n");
    // Find the first host (same order as hosts added below)
    String defaultHostPrimaryHostname = null;
    FIND_FIRST:
    for (boolean listFirst : new boolean[]{true, false}) {
      for (SharedTomcatSite site : sites) {
        com.aoindustries.aoserv.client.web.Site hs = site.getHttpdTomcatSite().getHttpdSite();
        if (hs.getListFirst() == listFirst && !hs.isDisabled()) {
          defaultHostPrimaryHostname = hs.getPrimaryVirtualHostName().getHostname().toLowerCase();
          break FIND_FIRST;
        }
      }
    }
    out.print("    <Engine name=\"Catalina\" defaultHost=\"").print((defaultHostPrimaryHostname == null) ? "localhost" : defaultHostPrimaryHostname).print("\">\n"
        + "      <!-- Use the LockOutRealm to prevent attempts to guess user passwords\n"
        + "           via a brute-force attack -->\n"
        + "      <Realm className=\"org.apache.catalina.realm.LockOutRealm\">\n"
        + "        <!-- This Realm uses the UserDatabase configured in the global JNDI\n"
        + "             resources under the key \"UserDatabase\".  Any edits\n"
        + "             that are performed against this UserDatabase are immediately\n"
        + "             available for use by the Realm.  -->\n"
        + "        <Realm className=\"org.apache.catalina.realm.UserDatabaseRealm\"\n"
        + "               resourceName=\"UserDatabase\"/>\n"
        + "      </Realm>\n");
    for (boolean listFirst : new boolean[]{true, false}) {
      for (SharedTomcatSite site : sites) {
        com.aoindustries.aoserv.client.web.Site hs = site.getHttpdTomcatSite().getHttpdSite();
        if (hs.getListFirst() == listFirst && !hs.isDisabled()) {
          String primaryHostname = hs.getPrimaryVirtualHostName().getHostname().toLowerCase();
          out.print("\n"
              + "      <Host\n"
              + "        name=\"").textInXmlAttribute(primaryHostname).print("\"\n"
              + "        appBase=\"").textInXmlAttribute(wwwDirectory).print('/').textInXmlAttribute(hs.getName()).print("/webapps\"\n"
              + "        unpackWARs=\"").textInXmlAttribute(sharedTomcat.getUnpackWars()).print("\"\n"
              + "        autoDeploy=\"").textInXmlAttribute(sharedTomcat.getAutoDeploy()).print("\"\n"
              + "        undeployOldVersions=\"").textInXmlAttribute(sharedTomcat.getUndeployOldVersions()).print("\"\n"
              + "      >\n");
          List<String> usedHostnames = new SortedArrayList<>();
          usedHostnames.add(primaryHostname);
          List<VirtualHost> binds = hs.getHttpdSiteBinds();
          for (VirtualHost bind : binds) {
            for (VirtualHostName url : bind.getVirtualHostNames()) {
              String hostname = url.getHostname().toLowerCase();
              if (!usedHostnames.contains(hostname)) {
                out.print("        <Alias>").textInXhtml(hostname).print("</Alias>\n");
                usedHostnames.add(hostname);
              }
            }
            // When listed first, also include the IP addresses as aliases
            if (hs.getListFirst()) {
              String ip = bind.getHttpdBind().getNetBind().getIpAddress().getInetAddress().toString();
              if (!usedHostnames.contains(ip)) {
                out.print("        <Alias>").textInXhtml(ip).print("</Alias>\n");
                usedHostnames.add(ip);
              }
            }
          }
          Site tomcatSite = hs.getHttpdTomcatSite();
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
            // Not present in Tomcat 8.5+: out.print("          debug=\"").textInXmlAttribute(htc.getDebugLevel()).print("\"\n");
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
          out.print("      </Host>\n");
        }
      }
    }
    out.print("    </Engine>\n"
        + "  </Service>\n"
        + "</Server>\n");
  }

  @Override
  void buildSharedTomcatDirectory(String optSlash, PosixFile sharedTomcatDirectory, List<File> deleteFileList, Set<SharedTomcat> sharedTomcatsNeedingRestarted) throws IOException, SQLException {
    /*
     * Get values used in the rest of the loop.
     */
    final OperatingSystemConfiguration osConfig = OperatingSystemConfiguration.getOperatingSystemConfiguration();
    final HttpdOperatingSystemConfiguration httpdConfig = osConfig.getHttpdOperatingSystemConfiguration();
    final UserServer lsa = sharedTomcat.getLinuxServerAccount();
    final int lsaUid = lsa.getUid().getId();
    final GroupServer lsg = sharedTomcat.getLinuxServerGroup();
    final int lsgGid = lsg.getGid().getId();
    final PosixPath wwwDirectory = httpdConfig.getHttpdSitesDirectory();

    final T tomcatCommon = getTomcatCommon();
    final String apacheTomcatDir = tomcatCommon.getApacheTomcatDir();

    final PosixFile bin           = new PosixFile(sharedTomcatDirectory, "bin", false);
    final PosixFile binProfileD   = new PosixFile(bin, "profile.d", false);
    final PosixFile conf          = new PosixFile(sharedTomcatDirectory, "conf", false);
    final PosixFile serverXml     = new PosixFile(conf, "server.xml", false);
    final PosixFile daemon        = new PosixFile(sharedTomcatDirectory, "daemon", false);
    final PosixFile work          = new PosixFile(sharedTomcatDirectory, "work", false);
    final PosixFile workCatalina  = new PosixFile(work, "Catalina", false);

    final String backupSuffix = VersionedTomcatCommon.getBackupSuffix();
    final ByteArrayOutputStream bout = new ByteArrayOutputStream();

    boolean needRestart = false;

    // Create and fill in the directory if it does not exist or is owned by root.
    final Stat sharedTomcatStat = sharedTomcatDirectory.getStat();
    final boolean isInstall =
        !sharedTomcatStat.exists()
            || sharedTomcatStat.getUid() == PosixFile.ROOT_UID;

    // Perform upgrade in-place when not doing a full install and the README.txt file missing or changed
    final byte[] readmeTxtContent = generateReadmeTxt(optSlash, apacheTomcatDir, sharedTomcatDirectory);
    final PosixFile readmeTxt = new PosixFile(sharedTomcatDirectory, README_TXT, false);
    final boolean isUpgrade;
    {
      final Stat readmeTxtStat;
      isUpgrade =
          !isInstall
              && !sharedTomcat.isManual()
              && !(
              (readmeTxtStat = readmeTxt.getStat()).exists()
                  && readmeTxtStat.isRegularFile()
                  && readmeTxt.contentEquals(readmeTxtContent)
          );
    }
    assert !(isInstall && isUpgrade);
    if (isInstall || isUpgrade) {

      // /var/opt/apache-tomcat/(name)/
      if (isInstall) {
        if (!sharedTomcatStat.exists()) {
          sharedTomcatDirectory.mkdir();
        }
        sharedTomcatDirectory.setMode(0770);
      }

      List<Install> installFiles = getInstallFiles(optSlash, sharedTomcatDirectory);
      for (Install installFile : installFiles) {
        installFile.install(optSlash, apacheTomcatDir, sharedTomcatDirectory, lsaUid, lsgGid, backupSuffix);
      }

      // Create or replace the README.txt
      DaemonFileUtils.atomicWrite(
          readmeTxt, readmeTxtContent, 0440, lsaUid, lsgGid,
          null, null
      );

      // Set the ownership to avoid future rebuilds of this directory
      if (isInstall) {
        sharedTomcatDirectory.chown(lsaUid, lsgGid);
      }

      needRestart = true;
    }

    // always rebuild bin/profile.d/httpd-sites.sh
    List<SharedTomcatSite> sites = sharedTomcat.getHttpdTomcatSharedSites();
    if (
        !sharedTomcat.isManual()
            // bin/profile.d directory may not exist while in manual mode
            || binProfileD.getStat().exists()
    ) {
      bout.reset();
      try (ChainWriter out = new ChainWriter(new OutputStreamWriter(bout, StandardCharsets.UTF_8))) {
        out.print("export SITES=\"");
        boolean didOne = false;
        for (SharedTomcatSite site : sites) {
          com.aoindustries.aoserv.client.web.Site hs = site.getHttpdTomcatSite().getHttpdSite();
          if (!hs.isDisabled()) {
            if (didOne) {
              out.print(' ');
            } else {
              didOne = true;
            }
            out.print(hs.getName());
          }
        }
        out.print("\"\n"
            + "\n"
            + "for SITE in $SITES\n"
            + "do\n"
            + "    export PATH=\"$PATH:").print(wwwDirectory).print("/$SITE/bin\"\n"
            + "done\n"
            + "unset SITE\n");
      }
      PosixFile httpdSitesSh = new PosixFile(binProfileD, "httpd-sites.sh", false);
      if (
          DaemonFileUtils.atomicWrite(
              httpdSitesSh, bout.toByteArray(), 0640, lsaUid, lsgGid,
              DaemonFileUtils.findUnusedBackup(httpdSitesSh + backupSuffix, BACKUP_SEPARATOR, BACKUP_EXTENSION),
              null
          )
      ) {
        needRestart = true;
      }
    }

    // make work directories and remove extra work dirs
    if (
        !sharedTomcat.isManual()
            // work directory may not exist while in manual mode
            || workCatalina.getStat().exists()
    ) {
      List<String> workFiles = new SortedArrayList<>();
      String[] wlist = workCatalina.getFile().list();
      if (wlist != null) {
        workFiles.addAll(Arrays.asList(wlist));
      }
      for (SharedTomcatSite site : sites) {
        com.aoindustries.aoserv.client.web.Site hs = site.getHttpdTomcatSite().getHttpdSite();
        if (!hs.isDisabled()) {
          String subwork = hs.getPrimaryVirtualHostName().getHostname().toString();
          workFiles.remove(subwork);
          if (
              DaemonFileUtils.mkdir(
                  new PosixFile(workCatalina, subwork, false), 0750, lsaUid, hs.getLinuxServerGroup().getGid().getId()
              )
          ) {
            needRestart = true;
          }
        }
      }
      for (String workFile : workFiles) {
        File toDelete = new File(workCatalina.getFile(), workFile);
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
      if (!sharedTomcat.isManual() || !serverXml.getStat().exists()) {
        bout.reset();
        try (ChainWriter out = new ChainWriter(new OutputStreamWriter(bout, StandardCharsets.UTF_8))) {
          writeServerXml(autoWarning, out, sharedTomcat, sites);
        }
        if (
            DaemonFileUtils.atomicWrite(
                serverXml, bout.toByteArray(), 0640, lsaUid, lsgGid,
                DaemonFileUtils.findUnusedBackup(serverXml + backupSuffix, BACKUP_SEPARATOR, BACKUP_EXTENSION),
                null
            )
        ) {
          // Must restart JVM if this file has changed
          needRestart = true;
        }
      } else {
        try {
          Server thisServer = AoservDaemon.getThisServer();
          int uidMin = thisServer.getUidMin().getId();
          int gidMin = thisServer.getGidMin().getId();
          DaemonFileUtils.stripFilePrefix(
              serverXml,
              autoWarningOld,
              uidMin,
              gidMin
          );
          DaemonFileUtils.stripFilePrefix(
              serverXml,
              "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" + autoWarning,
              uidMin,
              gidMin
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
    PosixFile tomcat = new PosixFile(bin, "tomcat", false);
    PosixFile daemonSymlink = new PosixFile(daemon, "tomcat", false);
    if (
        !sharedTomcat.isDisabled()
            && hasEnabledSite
            && (
            !sharedTomcat.isManual()
                // Script may not exist while in manual mode
                || tomcat.getStat().exists()
          )
    ) {
      // Enabled
      if (!daemonSymlink.getStat().exists()) {
        daemonSymlink
            .symLink("../bin/tomcat")
            .chown(lsaUid, lsgGid);
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

  protected static byte[] generateTomcatScript(String optSlash, String apacheTomcatDir, PosixFile installDir) throws IOException {
    ByteArrayOutputStream bout = new ByteArrayOutputStream();
    try (ChainWriter out = new ChainWriter(new OutputStreamWriter(bout, StandardCharsets.UTF_8))) {
      out.print("#!/bin/bash\n"
          + "#\n"
          + "# Generated by ").print(VersionedSharedTomcatManager.class.getName()).print("\n"
          + "#\n"
          + "\n"
          + "# Reset environment\n"
          + "if [ \"${AOSERV_PROFILE_RESET:-}\" != 'true' ]; then\n"
          + "    exec env -i AOSERV_PROFILE_RESET='true' \"$0\" \"$@\"\n"
          + "fi\n"
          + "unset AOSERV_PROFILE_RESET\n"
          + "\n"
          + "# Load application environment\n"
          + "export AOSERV_PROFILE_D='").print(installDir).print("/bin/profile.d'\n"
          + ". /etc/profile\n"
          + "\n"
          + "TOMCAT_HOME='").print(installDir).print("'\n"
          + "\n"
          + "if [ \"$1\" = 'start' ]; then\n"
          + "    # Stop any running Tomcat\n"
          + "    \"$0\" stop\n"
          + "    # Remove work files to force JSP recompilation on Tomcat restart\n"
          + "    find \"$TOMCAT_HOME/work/Catalina\" -type f \\( -path '*/org/apache/jsp/*.class' -or -path '*/org/apache/jsp/*.java' \\) -delete\n"
          + "    find \"$TOMCAT_HOME/work/Catalina\" -mindepth 1 -type d -empty -delete\n"
          + "    # Start Tomcat wrapper in the background\n"
          + "    nohup \"$0\" daemon </dev/null >&/dev/null &\n"
          + "    echo \"$!\" >\"$TOMCAT_HOME/var/run/tomcat.pid\"\n"
          + "elif [ \"$1\" = 'stop' ]; then\n"
          + "    if [ -f \"$TOMCAT_HOME/var/run/tomcat.pid\" ]; then\n"
          + "        pid=\"$(cat \"$TOMCAT_HOME/var/run/tomcat.pid\")\"\n"
          + "        if [ -d \"/proc/$pid\" ]; then\n"
          + "            kill \"$pid\"\n"
          + "            for attempt in {1..").print(VersionedTomcatCommon.KILL_DELAY_ATTEMPTS).print("}; do\n"
          + "                sleep ").print(VersionedTomcatCommon.KILL_DELAY_INTERVAL).print("\n"
          + "                if [ ! -d \"/proc/$pid\" ]; then\n"
          + "                    break\n"
          + "                fi\n"
          + "                if [ \"$attempt\" -eq ").print(VersionedTomcatCommon.KILL_DELAY_ATTEMPTS).print(" ]; then\n"
          + "                    echo \"Forcefully killing \\\"tomcat\\\" PID $pid\" 1>&2\n"
          + "                    kill -9 \"$pid\"\n"
          + "                fi\n"
          + "            done\n"
          + "        fi\n"
          + "        rm -f \"$TOMCAT_HOME/var/run/tomcat.pid\"\n"
          + "    fi\n"
          + "    if [ -f \"$TOMCAT_HOME/var/run/java.pid\" ]; then\n"
          + "        . \"$TOMCAT_HOME/bin/profile.d/httpd-sites.sh\"\n"
          + "        if [ \"$SITES\" != '' ]; then\n"
          + "            cd \"$TOMCAT_HOME\"\n"
          + "            \"$TOMCAT_HOME/bin/catalina.sh\" stop 2>&1 >>\"$TOMCAT_HOME/var/log/tomcat_err\"\n"
          + "        fi\n"
          + "        pid=\"$(cat \"$TOMCAT_HOME/var/run/java.pid\")\"\n"
          + "        for attempt in {1..").print(VersionedTomcatCommon.KILL_DELAY_ATTEMPTS).print("}; do\n"
          + "            sleep ").print(VersionedTomcatCommon.KILL_DELAY_INTERVAL).print("\n"
          + "            if [ ! -d \"/proc/$pid\" ]; then\n"
          + "                break\n"
          + "            fi\n"
          + "            if [ \"$attempt\" -eq ").print(VersionedTomcatCommon.KILL_DELAY_ATTEMPTS).print(" ]; then\n"
          + "                echo \"Forcefully killing \\\"java\\\" PID $pid\" 1>&2\n"
          + "                kill -9 \"$pid\"\n"
          + "            fi\n"
          + "        done\n"
          + "        rm -f \"$TOMCAT_HOME/var/run/java.pid\"\n"
          + "    fi\n"
          + "elif [ \"$1\" = 'daemon' ]; then\n"
          + "    . \"$TOMCAT_HOME/bin/profile.d/httpd-sites.sh\"\n"
          + "    if [ \"$SITES\" != '' ]; then\n"
          + "        cd \"$TOMCAT_HOME\"\n"
          + "        while [ 1 ]; do\n"
          + "            [ -e \"$TOMCAT_HOME/var/log/tomcat_err\" ] && mv -f \"$TOMCAT_HOME/var/log/tomcat_err\" \"$TOMCAT_HOME/var/log/tomcat_err_$(date +%Y%m%d_%H%M%S)\"\n"
          + "            \"$TOMCAT_HOME/bin/catalina.sh\" run >&\"$TOMCAT_HOME/var/log/tomcat_err\" &\n"
          + "            echo \"$!\" >\"$TOMCAT_HOME/var/run/java.pid\"\n"
          + "            wait\n"
          + "            RETCODE=\"$?\"\n"
          + "            echo \"$(date): JVM died with a return code of $RETCODE, restarting in 5 seconds\" >>\"$TOMCAT_HOME/var/log/jvm_crashes.log\"\n"
          + "            sleep 5\n"
          + "        done\n"
          + "    fi\n"
          + "    rm -f \"$TOMCAT_HOME/var/run/tomcat.pid\"\n"
          + "else\n"
          + "    echo 'Usage:' 1>&2\n"
          + "    echo 'tomcat {start|stop}' 1>&2\n"
          + "    echo '        start - start tomcat' 1>&2\n"
          + "    echo '        stop  - stop tomcat' 1>&2\n"
          + "    exit 64 # EX_USAGE in /usr/include/sysexits.h\n"
          + "fi\n"
      );
    }
    return bout.toByteArray();
  }

  /**
   * Generates the README.txt that is used to detect major version changes to rebuild the Tomcat installation.
   *
   * @see  VersionedTomcatStdSiteManager#generateReadmeTxt(java.lang.String, java.lang.String, com.aoapps.io.posix.PosixFile)
   * @see  #README_TXT
   */
  protected byte[] generateReadmeTxt(String optSlash, String apacheTomcatDir, PosixFile installDir) throws IOException, SQLException {
    ByteArrayOutputStream bout = new ByteArrayOutputStream();
    try (ChainWriter out = new ChainWriter(new OutputStreamWriter(bout, StandardCharsets.UTF_8))) {
      out.print(
          "Warning: This file is automatically created by VersionedSharedTomcatManager,\n"
              + "which is part of HttpdManager.\n"
              + "\n"
              + "This file is used to detect when a new version of Tomcat has been selected.\n"
              + "Alteration or removal of this file will trigger a major rebuild of this Tomcat\n"
              + "installation on the next configuration verification pass.\n"
              + "\n"
              + "To set the Tomcat major version, please use one of the following options:\n"
              + "\n"
              + "Control Panel: https://aoindustries.com/clientarea/control/httpd/HttpdSharedTomcatCP.ao?pkey=").print(sharedTomcat.getPkey()).print("\n"
          + "\n"
          + "AOSH: " + Command.SET_HTTPD_SHARED_TOMCAT_VERSION + " ").print(sharedTomcat.getName()).print(' ').print(sharedTomcat.getLinuxServer().getHostname()).print(" {series}.{major}\n"
          + "\n"
          + "Changing the major version will trigger a full rebuild of this Tomcat\n"
          + "installation.  During the major rebuild, any file altered is backed-up with\n"
          + "an extension of \".bak\".  These *.bak files will not interfere with the\n"
          + "operation of the Tomcat installation.  Once the applications are thoroughly\n"
          + "tested with the new major Tomcat version, the backup files may be removed with\n"
          + "the following:\n"
          + "\n"
          + "find \"").print(installDir).print("\" -mindepth 1 \\( -name '*.bak' -or -path '*.bak/*' \\) -print -delete\n"
          + "\n"
          + "Minor version upgrades are performed on a regular basis as updates to Tomcat\n"
          + "become available.  A minor rebuild differs from a major rebuild in that it only\n"
          + "touches the specific files changed in that specific minor update, which is\n"
          + "typically only the replacement of symbolic links within the lib/ directory.\n"
          + "\n"
          + "support@aoindustries.com\n"
          + "(205) 454-2556\n"
          + "\n"
          + "\n"
          + "*** Change Detection ***\n"
          + "Source: /opt/").print(apacheTomcatDir).print('\n');
    }
    return bout.toByteArray();
  }

  /**
   * Gets the set of files that are installed during install and upgrade/downgrade.
   * Each path is relative to CATALINA_HOME/CATALINA_BASE.
   *
   * @see  VersionedTomcatCommon#getInstallFiles(java.lang.String, com.aoapps.io.posix.PosixFile, int)
   */
  protected List<Install> getInstallFiles(String optSlash, PosixFile installDir) throws IOException, SQLException {
    List<Install> installFiles = new ArrayList<>();
    installFiles.addAll(getTomcatCommon().getInstallFiles(optSlash, installDir, 0770));
    // bin/profile.sites is now bin/profile.d/httpd-sites.sh as of Tomcat 8.5
    installFiles.add(new Delete   ("bin/profile.sites"));
    installFiles.add(new Delete   ("bin/profile.sites.new"));
    installFiles.add(new Delete   ("bin/profile.sites.old"));
    installFiles.add(new Generated("bin/tomcat", 0700, VersionedSharedTomcatManager::generateTomcatScript));
    return installFiles;
  }

  @Override
  protected boolean upgradeSharedTomcatDirectory(String optSlash, PosixFile siteDirectory) throws IOException, SQLException {
    T tomcatCommon = getTomcatCommon();
    int uid = sharedTomcat.getLinuxServerAccount().getUid().getId();
    int gid = sharedTomcat.getLinuxServerGroup().getGid().getId();
    String apacheTomcatDir = tomcatCommon.getApacheTomcatDir();
    // Upgrade Tomcat
    boolean needsRestart = getTomcatCommon().upgradeTomcatDirectory(
        optSlash,
        siteDirectory,
        uid,
        gid
    );
    // Verify RELEASE-NOTES, looking for any update that doesn't change symlinks
    PosixFile newReleaseNotes = new PosixFile(siteDirectory, "RELEASE-NOTES", true);
    ByteArrayOutputStream bout = new ByteArrayOutputStream();
    try (InputStream in = new FileInputStream("/opt/" + apacheTomcatDir + "/RELEASE-NOTES")) {
      IoUtils.copy(in, bout);
    }
    if (
        DaemonFileUtils.atomicWrite(
            newReleaseNotes, bout.toByteArray(), 0440, uid, gid,
            null, null
        )
    ) {
      needsRestart = true;
    }
    return needsRestart;
  }
}
