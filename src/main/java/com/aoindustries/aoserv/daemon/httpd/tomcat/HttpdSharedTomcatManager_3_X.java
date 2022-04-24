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
import com.aoindustries.aoserv.client.web.Site;
import com.aoindustries.aoserv.client.web.tomcat.SharedTomcat;
import com.aoindustries.aoserv.client.web.tomcat.SharedTomcatSite;
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
 * Manages SharedTomcat version 3.X configurations.
 *
 * TODO: Replace all uses of "replace" with a read file then call replace only if one of the "from" values is found.  Should be faster
 *       be eliminating unnecessary subprocesses.
 *
 * @author  AO Industries, Inc.
 */
abstract class HttpdSharedTomcatManager_3_X<TC extends TomcatCommon_3_X> extends HttpdSharedTomcatManager<TC> {

  private static final Logger logger = Logger.getLogger(HttpdSharedTomcatManager_3_X.class.getName());

  HttpdSharedTomcatManager_3_X(SharedTomcat sharedTomcat) {
    super(sharedTomcat);
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
    final String optDir = getOptDir();
    final TC tomcatCommon = getTomcatCommon();
    final UserServer lsa = sharedTomcat.getLinuxServerAccount();
    final int lsaUID = lsa.getUid().getId();
    final GroupServer lsg = sharedTomcat.getLinuxServerGroup();
    final int lsgGID = lsg.getGid().getId();
    final String wwwGroupDir = sharedTomcatDirectory.getPath();
    final PosixFile bin = new PosixFile(sharedTomcatDirectory, "bin", false);
    final PosixFile tomcatUF = new PosixFile(bin, "tomcat", false);
    final PosixPath wwwDirectory = httpdConfig.getHttpdSitesDirectory();
    final PosixFile sitesFile = new PosixFile(bin, "profile.sites", false);
    final PosixFile daemonUF = new PosixFile(sharedTomcatDirectory, "daemon", false);
    // Create and fill in the directory if it does not exist or is owned by root.
    final PosixFile workUF = new PosixFile(sharedTomcatDirectory, "work", false);

    boolean needRestart = false;
    Stat sharedTomcatStat = sharedTomcatDirectory.getStat();
    if (!sharedTomcatStat.exists() || sharedTomcatStat.getUid() == PosixFile.ROOT_GID) {

      // Create the /wwwgroup/name/...

      // 001
      if (!sharedTomcatStat.exists()) {
        sharedTomcatDirectory.mkdir();
      }
      sharedTomcatDirectory.setMode(0770);
      bin.mkdir().chown(lsaUID, lsgGID).setMode(0770);
      new PosixFile(sharedTomcatDirectory, "conf", false).mkdir().chown(lsaUID, lsgGID).setMode(0770);
      daemonUF.mkdir().chown(lsaUID, lsgGID).setMode(0770);
      PosixFile varUF = new PosixFile(sharedTomcatDirectory, "var", false).mkdir().chown(lsaUID, lsgGID).setMode(0770);
      new PosixFile(varUF, "log", false).mkdir().chown(lsaUID, lsgGID).setMode(0770);
      new PosixFile(varUF, "run", false).mkdir().chown(lsaUID, lsgGID).setMode(0700);

      workUF.mkdir().chown(lsaUID, lsgGID).setMode(0750);

      //Server postgresServer=thisServer.getPreferredPostgresServer();
      //String postgresServerMinorVersion=postgresServer == null?null:postgresServer.getPostgresVersion().getMinorVersion();

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
            + ". /opt/mm.mysql-2.0/setenv.sh\n"
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
            + "export PATH=\"${PATH}:").print(bin).print("\"\n"
            + "\n"
            + "export JAVA_OPTS='-server -Djava.awt.headless=true -Xmx128M -Djdk.disableLastUsageTracking=true'\n"
            + "\n"
            + "# Add site group classes\n"
            + "CLASSPATH=\"${CLASSPATH}:").print(wwwGroupDir).print("/classes\"\n"
            + "for i in ").print(wwwGroupDir).print("/lib/* ; do\n"
            + "    if [ -f \"$i\" ]; then\n"
            + "        CLASSPATH=\"${CLASSPATH}:$i\"\n"
            + "    fi\n"
            + "done\n"
            + "\n"
            + ". ").print(sitesFile).print("\n"
            + "\n"
            + "for SITE in $SITES\n"
            + "do\n"
            + "    export PATH=\"${PATH}:").print(wwwDirectory).print("/${SITE}/bin\"\n"
            + "    CLASSPATH=\"${CLASSPATH}:").print(wwwDirectory).print("/${SITE}/classes\"\n"
            + "\n"
            + "    for i in ").print(wwwDirectory).print("/${SITE}/lib/* ; do\n"
            + "        if [ -f \"$i\" ]; then\n"
            + "            CLASSPATH=\"${CLASSPATH}:$i\"\n"
            + "        fi\n"
            + "    done\n"
            + "done\n"
            + "export CLASSPATH\n");
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
            + "        umask 002\n"
            //+ "        ulimit -S -m 196608 -v 400000\n"
            //+ "        ulimit -H -m 196608 -v 400000\n"
            + "        if [ \"$SITES\" != \"\" ]; then\n"
            + "            cd \"$TOMCAT_HOME\"\n"
            + "            java com.aoindustries.apache.tomcat.VirtualTomcat stop $SITES &>/dev/null\n"
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
            //+ "            ulimit -S -m 196608 -v 400000\n"
            //+ "            ulimit -H -m 196608 -v 400000\n"
            + "            umask 002\n"
            + "            java com.aoindustries.apache.tomcat.VirtualTomcat start $SITES &>var/log/servlet_err &\n"
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

      // The classes directory
      new PosixFile(sharedTomcatDirectory, "classes", false).mkdir().chown(lsaUID, lsgGID).setMode(0770);

      // Create /lib
      new PosixFile(sharedTomcatDirectory, "lib", false).mkdir().chown(lsaUID, lsgGID).setMode(0770);
      DaemonFileUtils.lnAll("../" + optSlash + optDir + "/lib/", wwwGroupDir + "/lib/", lsaUID, lsgGID);
      DaemonFileUtils.ln("../" + optSlash + optDir + "/lib/jasper-runtime.jar", wwwGroupDir + "/lib/jasper-runtime.jar", lsaUID, lsgGID);
      //if (postgresServerMinorVersion != null) {
      //  String postgresPath = osConfig.getPostgresPath(postgresServerMinorVersion);
      //  if (postgresPath != null) {
      //  FileUtils.ln("../../.."+postgresPath+"/share/java/postgresql.jar", wwwGroupDir+"/lib/postgresql.jar", lsaUID, lsgGID);
      //  }
      //}
      //String mysqlConnectorPath = osConfig.getMySQLConnectorJavaJarPath();
      //if (mysqlConnectorPath != null) {
      //  String filename = new PosixFile(mysqlConnectorPath).getFile().getName();
      //  FileUtils.ln("../../.."+mysqlConnectorPath, wwwGroupDir+"/lib/"+filename, lsaUID, lsgGID);
      //}
      PosixFile servErrUF = new PosixFile(varUF, "log/servlet_err", false);
      servErrUF.getSecureOutputStream(lsaUID, lsgGID, 0640, false, uid_min, gid_min).close();

      // Set the ownership to avoid future rebuilds of this directory
      sharedTomcatDirectory.chown(lsaUID, lsgGID);

      needRestart = true;
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
        boolean didOne = false;
        for (SharedTomcatSite site : sites) {
          Site hs = site.getHttpdTomcatSite().getHttpdSite();
          if (!hs.isDisabled()) {
            if (didOne) {
              out.print(' ');
            } else {
              didOne = true;
            }
            out.print(hs.getName());
          }
        }
        out.print("\"\n");
      }
      // flag as needing a restart if this file is different than any existing
      Stat sitesStat = sitesFile.getStat();
      if (!sitesStat.exists() || !newSitesFile.contentEquals(sitesFile)) {
        needRestart = true;
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
            || workUF.getStat().exists()
    ) {
      List<String> workFiles = new SortedArrayList<>();
      String[] wlist = workUF.getFile().list();
      if (wlist != null) {
        workFiles.addAll(Arrays.asList(wlist));
      }
      for (SharedTomcatSite site : sites) {
        Site hs = site.getHttpdTomcatSite().getHttpdSite();
        if (!hs.isDisabled()) {
          String subwork = hs.getName();
          workFiles.remove(subwork);
          PosixFile workDir = new PosixFile(workUF, subwork, false);
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
        File toDelete = new File(workUF.getFile(), workFile);
        if (logger.isLoggable(Level.INFO)) {
          logger.info("Scheduling for removal: " + toDelete);
        }
        deleteFileList.add(toDelete);
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
    // Nothing to do
    return false;
  }

  /**
   * Gets the package's directory name under /opt, not including /opt itself.
   */
  protected abstract String getOptDir();
}
