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

import com.aoapps.encoding.ChainWriter;
import com.aoapps.io.posix.PosixFile;
import com.aoapps.lang.io.IoUtils;
import com.aoindustries.aoserv.client.AoservConnector;
import com.aoindustries.aoserv.client.aosh.Command;
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
import com.aoindustries.aoserv.daemon.httpd.tomcat.Install.Copy;
import com.aoindustries.aoserv.daemon.httpd.tomcat.Install.Generated;
import com.aoindustries.aoserv.daemon.httpd.tomcat.Install.Mkdir;
import com.aoindustries.aoserv.daemon.util.DaemonFileUtils;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Manages shared aspects of PrivateTomcatSite version 8.5 and above.
 *
 * @author  AO Industries, Inc.
 */
abstract class VersionedTomcatStdSiteManager<T extends VersionedTomcatCommon> extends HttpdTomcatStdSiteManager<T> {

  VersionedTomcatStdSiteManager(PrivateTomcatSite tomcatStdSite) throws SQLException, IOException {
    super(tomcatStdSite);
  }

  /**
   * TODO: Support upgrades in-place.
   */
  @Override
  protected void buildSiteDirectoryContents(String optSlash, PosixFile siteDirectory, boolean isUpgrade) throws IOException, SQLException {
    // Resolve and allocate stuff used throughout the method
    final UserServer lsa = httpdSite.getLinuxServerAccount();
    final int uid = lsa.getUid().getId();
    final int gid = httpdSite.getLinuxServerGroup().getGid().getId();

    final T tomcatCommon = getTomcatCommon();
    final String apacheTomcatDir = tomcatCommon.getApacheTomcatDir();

    final String backupSuffix = VersionedTomcatCommon.getBackupSuffix();

    /*
     * Create the skeleton of the site, the directories and links.
     */
    List<Install> installFiles = getInstallFiles(optSlash, siteDirectory, isUpgrade);
    for (Install installFile : installFiles) {
      installFile.install(optSlash, apacheTomcatDir, siteDirectory, uid, gid, backupSuffix);
    }

    // daemon/
    {
      // TODO: Is this link updated elsewhere, thus this not needed here?
      PosixFile bin = new PosixFile(siteDirectory, "bin", false);
      PosixFile tomcat = new PosixFile(bin, "tomcat", false);
      PosixFile daemon = new PosixFile(siteDirectory, "daemon", false);
      if (
          !httpdSite.isDisabled()
              && (
              !httpdSite.isManual()
                  // Script may not exist while in manual mode
                  || tomcat.getStat().exists()
            )
      ) {
        DaemonFileUtils.ln(
            "../bin/tomcat",
            new PosixFile(daemon, "tomcat", false), uid, gid
        );
      }
    }
  }

  @Override
  protected byte[] buildServerXml(PosixFile siteDirectory, String autoWarning) throws IOException, SQLException {
    final T tomcatCommon = getTomcatCommon();
    AoservConnector conn = AoservDaemon.getConnector();

    // Build to RAM first
    ByteArrayOutputStream bout = new ByteArrayOutputStream();
    try (ChainWriter out = new ChainWriter(new OutputStreamWriter(bout, StandardCharsets.UTF_8))) {
      Worker hw;
      {
        List<Worker> hws = tomcatSite.getHttpdWorkers();
        if (hws.size() != 1) {
          throw new SQLException("Expected to only find one Worker for PrivateTomcatSite #" + httpdSite.getPkey() + ", found " + hws.size());
        }
        hw = hws.get(0);
        String hwProtocol = hw.getHttpdJkProtocol(conn).getProtocol(conn).getProtocol();
        if (!hwProtocol.equals(JkProtocol.AJP13)) {
          throw new SQLException("Worker #" + hw.getPkey() + " for PrivateTomcatSite #" + httpdSite.getPkey() + " must be AJP13 but it is " + hwProtocol);
        }
      }
      Bind shutdownPort = tomcatStdSite.getTomcat4ShutdownPort();
      if (shutdownPort == null) {
        throw new SQLException("Unable to find shutdown port for PrivateTomcatSite=" + tomcatStdSite);
      }
      String shutdownKey = tomcatStdSite.getTomcat4ShutdownKey();
      if (shutdownKey == null) {
        throw new SQLException("Unable to find shutdown key for PrivateTomcatSite=" + tomcatStdSite);
      }
      out.print("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
      if (!httpdSite.isManual()) {
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
          + "\n"
          + "    <Engine name=\"Catalina\" defaultHost=\"localhost\">\n"
          + "      <!-- Use the LockOutRealm to prevent attempts to guess user passwords\n"
          + "           via a brute-force attack -->\n"
          + "      <Realm className=\"org.apache.catalina.realm.LockOutRealm\">\n"
          + "        <!-- This Realm uses the UserDatabase configured in the global JNDI\n"
          + "             resources under the key \"UserDatabase\".  Any edits\n"
          + "             that are performed against this UserDatabase are immediately\n"
          + "             available for use by the Realm.  -->\n"
          + "        <Realm className=\"org.apache.catalina.realm.UserDatabaseRealm\"\n"
          + "               resourceName=\"UserDatabase\"/>\n"
          + "      </Realm>\n"
          + "\n"
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
      out.print("      </Host>\n"
          + "    </Engine>\n"
          + "  </Service>\n"
          + "</Server>\n");
    }
    return bout.toByteArray();
  }

  protected static byte[] generateTomcatScript(String optSlash, String apacheTomcatDir, PosixFile installDir) throws IOException {
    ByteArrayOutputStream bout = new ByteArrayOutputStream();
    try (ChainWriter out = new ChainWriter(new OutputStreamWriter(bout, StandardCharsets.UTF_8))) {
      out.print("#!/bin/bash\n"
          + "#\n"
          + "# Generated by ").print(VersionedTomcatStdSiteManager.class.getName()).print("\n"
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
          + "        cd \"$TOMCAT_HOME\"\n"
          + "        \"$TOMCAT_HOME/bin/catalina.sh\" stop 2>&1 >>\"$TOMCAT_HOME/var/log/tomcat_err\"\n"
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
          + "    cd \"$TOMCAT_HOME\"\n"
          + "    while [ 1 ]; do\n"
          + "        [ -e \"$TOMCAT_HOME/var/log/tomcat_err\" ] && mv -f \"$TOMCAT_HOME/var/log/tomcat_err\" \"$TOMCAT_HOME/var/log/tomcat_err_$(date +%Y%m%d_%H%M%S)\"\n"
          + "        \"$TOMCAT_HOME/bin/catalina.sh\" run >&\"$TOMCAT_HOME/var/log/tomcat_err\" &\n"
          + "        echo \"$!\" >\"$TOMCAT_HOME/var/run/java.pid\"\n"
          + "        wait\n"
          + "        RETCODE=\"$?\"\n"
          + "        echo \"$(date): JVM died with a return code of $RETCODE, restarting in 5 seconds\" >>\"$TOMCAT_HOME/var/log/jvm_crashes.log\"\n"
          + "        sleep 5\n"
          + "    done\n"
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

  @Override
  protected byte[] generateReadmeTxt(String optSlash, String apacheTomcatDir, PosixFile installDir) throws IOException, SQLException {
    ByteArrayOutputStream bout = new ByteArrayOutputStream();
    try (ChainWriter out = new ChainWriter(new OutputStreamWriter(bout, StandardCharsets.UTF_8))) {
      out.print(
          "Warning: This file is automatically created by VersionedTomcatStdSiteManager,\n"
              + "which is part of HttpdManager.\n"
              + "\n"
              + "This file is used to detect when a new version of Tomcat has been selected.\n"
              + "Alteration or removal of this file will trigger a major rebuild of this Tomcat\n"
              + "installation on the next configuration verification pass.\n"
              + "\n"
              + "To set the Tomcat major version, please use one of the following options:\n"
              + "\n"
              + "Control Panel: https://aoindustries.com/clientarea/control/httpd/HttpdSiteCP.ao?pkey=").print(httpdSite.getPkey()).print("\n"
          + "\n"
          + "AOSH: " + Command.SET_HTTPD_TOMCAT_STD_SITE_VERSION + " ").print(httpdSite.getName()).print(' ').print(httpdSite.getLinuxServer().getHostname()).print(" {series}.{major}\n"
          + "\n"
          + "Changing the major version will trigger a full rebuild of this Tomcat\n"
          + "installation.  During the major rebuild, any file altered is backed-up with\n"
          + "an extension of \".bak\".  These *.bak files will not interfere with the\n"
          + "operation of the Tomcat installation.  Once the applications are thoroughly\n"
          + "tested with the new major Tomcat version, the backup files may be removed with\n"
          + "the following:\n"
          + "\n"
          + "find \"").print(installDir).print("\" -mindepth 1 \\( -name '*.bak' -or -path '*.bak/*' \\) -not -path '*/webapps/*' -print -delete\n"
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
   * @see  VersionedTomcatCommon#getInstallFiles(com.aoapps.io.posix.PosixFile)
   */
  protected List<Install> getInstallFiles(String optSlash, PosixFile installDir, boolean isUpgrade) throws IOException, SQLException {
    List<Install> installFiles = new ArrayList<>();
    installFiles.addAll(getTomcatCommon().getInstallFiles(optSlash, installDir, 0775)); // 0775 to allow Apache to read any passwd/group files in the conf/ directory
    installFiles.add(new Generated("bin/tomcat", 0700, VersionedTomcatStdSiteManager::generateTomcatScript));
    if (!isUpgrade) {
      installFiles.add(new Mkdir("webapps", 0775));
      installFiles.add(new Mkdir("webapps/" + Context.ROOT_DOC_BASE, 0775));
      installFiles.add(new Mkdir("webapps/" + Context.ROOT_DOC_BASE + "/WEB-INF", 0770));
      installFiles.add(new Mkdir("webapps/" + Context.ROOT_DOC_BASE + "/WEB-INF/classes", 0770));
      installFiles.add(new Mkdir("webapps/" + Context.ROOT_DOC_BASE + "/WEB-INF/lib", 0770));
      installFiles.add(new Copy("webapps/" + Context.ROOT_DOC_BASE + "/WEB-INF/web.xml", 0660));
    }
    return installFiles;
  }

  @Override
  protected boolean upgradeSiteDirectoryContents(String optSlash, PosixFile siteDirectory) throws IOException, SQLException {
    T tomcatCommon = getTomcatCommon();
    int uid = httpdSite.getLinuxServerAccount().getUid().getId();
    int gid = httpdSite.getLinuxServerGroup().getGid().getId();
    String apacheTomcatDir = tomcatCommon.getApacheTomcatDir();
    // Update the included Tomcat
    boolean needsRestart = tomcatCommon.upgradeTomcatDirectory(
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
