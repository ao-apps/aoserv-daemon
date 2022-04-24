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

package com.aoindustries.aoserv.daemon.httpd;

import com.aoapps.encoding.ChainWriter;
import com.aoapps.io.posix.PosixFile;
import com.aoapps.io.posix.Stat;
import com.aoapps.lang.validation.ValidationException;
import com.aoapps.tempfiles.TempFile;
import com.aoapps.tempfiles.TempFileContext;
import com.aoindustries.aoserv.client.AOServConnector;
import com.aoindustries.aoserv.client.aosh.Command;
import com.aoindustries.aoserv.client.distribution.OperatingSystemVersion;
import com.aoindustries.aoserv.client.linux.LinuxId;
import com.aoindustries.aoserv.client.linux.PosixPath;
import com.aoindustries.aoserv.client.linux.Server;
import com.aoindustries.aoserv.client.linux.User;
import com.aoindustries.aoserv.client.linux.UserServer;
import com.aoindustries.aoserv.client.web.Site;
import com.aoindustries.aoserv.client.web.StaticSite;
import com.aoindustries.aoserv.client.web.VirtualHost;
import com.aoindustries.aoserv.client.web.VirtualHostName;
import com.aoindustries.aoserv.client.web.tomcat.SharedTomcat;
import com.aoindustries.aoserv.daemon.AOServDaemon;
import com.aoindustries.aoserv.daemon.ftp.FTPManager;
import static com.aoindustries.aoserv.daemon.httpd.HttpdServerManager.PHP_SESSION;
import com.aoindustries.aoserv.daemon.httpd.tomcat.HttpdTomcatSiteManager;
import com.aoindustries.aoserv.daemon.posix.linux.PackageManager;
import com.aoindustries.aoserv.daemon.util.DaemonFileUtils;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Manages Site configurations.
 *
 * @author  AO Industries, Inc.
 */
public abstract class HttpdSiteManager {

  private static final Logger logger = Logger.getLogger(HttpdSiteManager.class.getName());

  /**
   * The directories in /www or /var/www that will never be deleted.
   * <p>
   * Note: This matches the check constraint on the httpd_sites table.
   * Note: This matches isValidSiteName in Site.
   * </p>
   */
  private static final Set<String> keepWwwDirs = new HashSet<>(Arrays.asList(
      "disabled", // Provided by aoserv-httpd-site-disabled package
      // CentOS 5 only
      "cache", // nginx only?
      "fastcgi",
      "error",
      "icons",
      // CentOS 7
      "cgi-bin",
      "html",
      "mrtg",
      // Other filesystem patterns
      "lost+found",
      "aquota.group",
      "aquota.user"
  ));

  /**
   * The name of the PHP <a href="http://php.net/manual/en/configuration.file.php#configuration.file.scan">PHP_INI_SCAN_DIR</a>
   * when used as CGI: <code>/var/www/&lt;site_name&gt;/webapps/ROOT/cgi-bin/php.d</code>.
   */
  private static final String CGI_PHP_D = "php.d";

  /**
   * The per-site directory that contains variable data.
   */
  static final String VAR = "var";

  /**
   * The per-site directory that contains PHP variable data.
   *
   * @see  #VAR
   * @see  HttpdServerManager#PHP_SESSION
   */
  static final String VAR_PHP = "php";

  /**
   * Gets the specific manager for one type of web site.
   */
  public static HttpdSiteManager getInstance(Site site) throws IOException, SQLException {
    StaticSite staticSite = site.getHttpdStaticSite();
    if (staticSite != null) {
      return HttpdStaticSiteManager.getInstance(staticSite);
    }

    com.aoindustries.aoserv.client.web.tomcat.Site tomcatSite = site.getHttpdTomcatSite();
    if (tomcatSite != null) {
      return HttpdTomcatSiteManager.getInstance(tomcatSite);
    }

    throw new SQLException("Site must be either StaticSite and Site: " + site);
  }

  /**
   * Responsible for control of all things in [/var]/www
   *
   * Only called by the already synchronized <code>HttpdManager.doRebuild()</code> method.
   */
  static void doRebuild(
      List<File> deleteFileList,
      Set<Site> sitesNeedingRestarted,
      Set<SharedTomcat> sharedTomcatsNeedingRestarted,
      Set<PackageManager.PackageName> usedPackages,
      Set<PosixFile> restorecon
  ) throws IOException, SQLException {
    try {
      // Get values used in the rest of the method.
      HttpdOperatingSystemConfiguration osConfig = HttpdOperatingSystemConfiguration.getHttpOperatingSystemConfiguration();
      String optSlash = osConfig.getHttpdSitesOptSlash();
      Server thisServer = AOServDaemon.getThisServer();

      // The www directories that exist but are not used will be removed
      PosixFile wwwDirectory = new PosixFile(osConfig.getHttpdSitesDirectory().toString());
      Set<String> wwwRemoveList = new HashSet<>();
      {
        String[] list = wwwDirectory.list();
        if (list != null) {
          wwwRemoveList.addAll(Arrays.asList(list));
          wwwRemoveList.removeAll(keepWwwDirs);
        }
      }

      // Iterate through each site
      for (Site httpdSite : thisServer.getHttpdSites()) {
        final HttpdSiteManager manager = getInstance(httpdSite);

        // Install any required RPMs
        Set<PackageManager.PackageName> requiredPackages = manager.getRequiredPackages();
        PackageManager.installPackages(requiredPackages);
        usedPackages.addAll(requiredPackages);

        // Create and fill in any incomplete installations.
        final String siteName = httpdSite.getName();
        PosixFile siteDirectory = new PosixFile(wwwDirectory, siteName, false);
        manager.buildSiteDirectory(
            siteDirectory,
            optSlash,
            sitesNeedingRestarted,
            sharedTomcatsNeedingRestarted,
            restorecon
        );
        wwwRemoveList.remove(siteName);
      }

      // Stop, disable, and mark files for deletion
      for (String siteName : wwwRemoveList) {
        PosixFile removeFile = new PosixFile(wwwDirectory, siteName, false);
        // Stop and disable any daemons
        stopAndDisableDaemons(removeFile);
        // Only remove the directory when not used by a home directory
        if (!thisServer.isHomeUsed(PosixPath.valueOf(removeFile.getPath()))) {
          File toDelete = removeFile.getFile();
          if (logger.isLoggable(Level.INFO)) {
            logger.info("Scheduling for removal: " + toDelete);
          }
          deleteFileList.add(toDelete);
        }
      }
    } catch (ValidationException e) {
      throw new IOException(e);
    }
  }

  /**
   * Stops any daemons that should not be running.
   * Restarts any sites that need restarted.
   * Starts any daemons that should be running.
   *
   * Makes calls with a one-minute time-out.
   * Logs errors on calls as warnings, continues to next site.
   *
   * Only called by the already synchronized <code>HttpdManager.doRebuild()</code> method.
   */
  @SuppressWarnings("SleepWhileInLoop")
  static void stopStartAndRestart(Set<Site> sitesNeedingRestarted) throws IOException, SQLException {
    for (Site httpdSite : AOServDaemon.getThisServer().getHttpdSites()) {
      HttpdSiteManager manager = getInstance(httpdSite);
      if (manager instanceof StopStartable) {
        final StopStartable stopStartRestartable = (StopStartable) manager;
        Callable<Object> commandCallable;
        if (stopStartRestartable.isStartable()) {
          // Enabled, start or restart
          if (sitesNeedingRestarted.contains(httpdSite)) {
            commandCallable = () -> {
              Boolean stopped = stopStartRestartable.stop();
              if (stopped != null) {
                if (stopped) {
                  try {
                    Thread.sleep(5000);
                  } catch (InterruptedException err) {
                    logger.log(Level.WARNING, null, err);
                    // Restore the interrupted status
                    Thread.currentThread().interrupt();
                  }
                }
                stopStartRestartable.start();
              }
              return null;
            };
          } else {
            commandCallable = () -> {
              if (!new File("/var/run/aoserv-user-daemons.pid").exists()) {
                stopStartRestartable.start();
              } else {
                if (logger.isLoggable(Level.INFO)) {
                  logger.info("Skipping start because /var/run/aoserv-user-daemons.pid exists: " + httpdSite);
                }
              }
              return null;
            };
          }
        } else {
          // Disabled, can only stop if needed
          commandCallable = () -> {
            stopStartRestartable.stop();
            return null;
          };
        }
        try {
          Future<Object> commandFuture = AOServDaemon.executorService.submit(commandCallable);
          commandFuture.get(60, TimeUnit.SECONDS);
        } catch (InterruptedException err) {
          logger.log(Level.WARNING, null, err);
          // Restore the interrupted status
          Thread.currentThread().interrupt();
          break;
        } catch (ExecutionException | TimeoutException err) {
          logger.log(Level.WARNING, null, err);
        }
      }
    }
  }

  /**
   * Stops all daemons that may be running in the provided directory.  The
   * exact type of site is not known.  This is called during site clean-up
   * to shutdown processes that should no longer be running.  It is assumed
   * that all types of sites will use the "daemon" directory with symbolic
   * links that accept "start" and "stop" parameters.
   *
   * @see  #doRebuild
   */
  public static void stopAndDisableDaemons(PosixFile siteDirectory) throws IOException, SQLException {
    PosixFile daemonDirectory = new PosixFile(siteDirectory, "daemon", false);
    Stat daemonDirectoryStat = daemonDirectory.getStat();
    if (daemonDirectoryStat.exists()) {
      int daemonUid = daemonDirectoryStat.getUid();
      UserServer daemonLsa;
      try {
        daemonLsa = AOServDaemon.getThisServer().getLinuxServerAccount(LinuxId.valueOf(daemonUid));
      } catch (ValidationException e) {
        throw new IOException(e);
      }
      // If the account doesn't exist or is disabled, the process killer will kill any processes
      if (daemonLsa != null && !daemonLsa.isDisabled()) {
        String[] list = daemonDirectory.list();
        if (list != null) {
          for (String scriptName : list) {
            final PosixFile scriptFile = new PosixFile(daemonDirectory, scriptName, false);
            // Call stop with a one-minute time-out if not owned by root
            if (daemonUid != PosixFile.ROOT_UID) {
              final User.Name username = daemonLsa.getLinuxAccount_username_id();
              try {
                Future<Object> stopFuture = AOServDaemon.executorService.submit(() -> {
                  AOServDaemon.suexec(
                      username,
                      siteDirectory.getFile(),
                      scriptFile.getPath() + " stop",
                      0
                  );
                  return null;
                });
                stopFuture.get(60, TimeUnit.SECONDS);
              } catch (InterruptedException err) {
                logger.log(Level.WARNING, null, err);
                // Restore the interrupted status
                Thread.currentThread().interrupt();
                break;
              } catch (ExecutionException | TimeoutException err) {
                logger.log(Level.WARNING, null, err);
              }
            }
            // Remove the file
            scriptFile.delete();
          }
        }
      }
    }
  }

  /**
   * Starts the site if it is not running.  Restarts it if it is running.
   *
   * @return  <code>null</code> if successful or a user-readable reason if not successful
   */
  public static String startHttpdSite(int sitePKey) throws IOException, SQLException {
    AOServConnector conn = AOServDaemon.getConnector();

    Site httpdSite = conn.getWeb().getSite().get(sitePKey);
    Server thisServer = AOServDaemon.getThisServer();
    if (!httpdSite.getLinuxServer().equals(thisServer)) {
      return "Site #" + sitePKey + " has server of " + httpdSite.getLinuxServer().getHostname() + ", which is not this server (" + thisServer.getHostname() + ')';
    }

    HttpdSiteManager manager = getInstance(httpdSite);
    if (manager instanceof StopStartable) {
      StopStartable stopStartable = (StopStartable) manager;
      if (stopStartable.isStartable()) {
        Boolean stopped = stopStartable.stop();
        if (stopped == null) {
          return "Site stop status is unknown";
        } else if (stopped) {
          try {
            Thread.sleep(5000);
          } catch (InterruptedException err) {
            logger.log(Level.WARNING, null, err);
            // Restore the interrupted status
            Thread.currentThread().interrupt();
          }
        }
        Boolean started = stopStartable.start();
        return (started == null)
            ? "Site start status is unknown"
            // Null means all went well
            : null;
      } else {
        return "Site #" + sitePKey + " is not currently startable";
      }
    } else {
      return "Site #" + sitePKey + " is not a type of site that can be stopped and started";
    }
  }

  /**
   * Stops the site if it is running.  Will return a message if already stopped.
   *
   * @return  <code>null</code> if successful or a user-readable reason if not success.
   */
  public static String stopHttpdSite(int sitePKey) throws IOException, SQLException {
    AOServConnector conn = AOServDaemon.getConnector();

    Site httpdSite = conn.getWeb().getSite().get(sitePKey);
    Server thisServer = AOServDaemon.getThisServer();
    if (!httpdSite.getLinuxServer().equals(thisServer)) {
      return "Site #" + sitePKey + " has server of " + httpdSite.getLinuxServer().getHostname() + ", which is not this server (" + thisServer.getHostname() + ')';
    }

    HttpdSiteManager manager = getInstance(httpdSite);
    if (manager instanceof StopStartable) {
      StopStartable stopStartable = (StopStartable) manager;
      Boolean stopped = stopStartable.stop();
      if (stopped == null) {
        return "Site stop status is unknown";
      } else if (stopped) {
        // Null means all went well
        return null;
      } else {
        return "Site was already stopped";
      }
    } else {
      return "Site #" + sitePKey + " is not a type of site that can be stopped and started";
    }
  }

  protected final Site httpdSite;

  protected HttpdSiteManager(Site httpdSite) {
    this.httpdSite = httpdSite;
  }

  /**
   * Gets the auto-mode warning for this website for use in XML files.  This
   * may be used on any config files that a user would be tempted to change
   * directly.
   */
  public String getAutoWarningXmlOld() throws IOException, SQLException {
    return
        "<!--\n"
            + "  Warning: This file is automatically created by HttpdManager.  Any manual changes\n"
            + "  to this file will be overwritten.  Please set the is_manual flag for this website\n"
            + "  to be able to make permanent changes to this file.\n"
            + "\n"
            + "  Control Panel: https://www.aoindustries.com/clientarea/control/httpd/HttpdSiteCP.ao?pkey=" + httpdSite.getPkey() + "\n"
            + "\n"
            + "  AOSH: " + Command.SET_HTTPD_SITE_IS_MANUAL + " " + httpdSite.getName() + " " + httpdSite.getLinuxServer().getHostname() + " true\n"
            + "\n"
            + "  support@aoindustries.com\n"
            + "  (205) 454-2556\n"
            + "-->\n"
    ;
  }

  /**
   * Gets the auto-mode warning for this website for use in XML files.  This
   * may be used on any config files that a user would be tempted to change
   * directly.
   */
  public String getAutoWarningXml() throws IOException, SQLException {
    return
        "<!--\n"
            + "  Warning: This file is automatically created by HttpdManager.  Any manual changes\n"
            + "  to this file will be overwritten.  Please set the is_manual flag for this website\n"
            + "  to be able to make permanent changes to this file.\n"
            + "\n"
            + "  Control Panel: https://aoindustries.com/clientarea/control/httpd/HttpdSiteCP.ao?pkey=" + httpdSite.getPkey() + "\n"
            + "\n"
            + "  AOSH: " + Command.SET_HTTPD_SITE_IS_MANUAL + " " + httpdSite.getName() + " " + httpdSite.getLinuxServer().getHostname() + " true\n"
            + "\n"
            + "  support@aoindustries.com\n"
            + "  (205) 454-2556\n"
            + "-->\n"
    ;
  }

  /**
   * Gets the auto-mode warning using Unix-style comments (#).  This
   * may be used on any config files that a user would be tempted to change
   * directly.
   */
  /* public String getAutoWarningUnix() throws IOException, SQLException {
    return
      "#\n"
      + "# Warning: This file is automatically created by HttpdManager.  Any manual changes\n"
      + "# to this file will be overwritten.  Please set the is_manual flag for this website\n"
      + "# to be able to make permanent changes to this file.\n"
      + "#\n"
      + "# Control Panel: https://aoindustries.com/clientarea/control/httpd/HttpdSiteCP.ao?pkey="+httpdSite.getPkey()+"\n"
      + "#\n"
      + "# AOSH: "+Command.SET_HTTPD_SITE_IS_MANUAL+" "+httpdSite.getName()+' '+httpdSite.getAOServer().getHostname()+" true\n"
      + "#\n"
      + "# support@aoindustries.com\n"
      + "# (205) 454-2556\n"
      + "#\n"
    ;
  }*/

  /**
   * Gets any packages that must be installed for this site.
   *
   * By default, no specific packages are required.
   */
  protected Set<PackageManager.PackageName> getRequiredPackages() throws IOException, SQLException {
    return Collections.emptySet();
  }

  /**
   * (Re)builds the site directory, from scratch if it doesn't exist.
   * Creates, recreates, or removes resources as necessary.
   * Also performs an automatic upgrade of resources if appropriate for the site.
   * Also reconfigures any config files within the directory if appropriate for the site type and settings.
   * If this site or other sites needs to be restarted due to changes in the files, add to <code>sitesNeedingRestarted</code>.
   * If any shared Tomcat needs to be restarted due to changes in the files, add to <code>sharedTomcatsNeedingRestarted</code>.
   * Any files under siteDirectory that need to be updated to enable/disable this site should be changed.
   * Actual process start/stop will be performed later in <code>stopStartAndRestart</code>.
   *
   * <ol>
   *   <li>If <code>siteDirectory</code> doesn't exist, create it as root with mode 0700</li>
   *   <li>If <code>siteDirectory</code> owned by root, do full pass (this implies manual=false regardless of setting)</li>
   *   <li>Otherwise, make necessary config changes or upgrades while adhering to the manual flag</li>
   * </ol>
   */
  protected abstract void buildSiteDirectory(PosixFile siteDirectory, String optSlash, Set<Site> sitesNeedingRestarted, Set<SharedTomcat> sharedTomcatsNeedingRestarted, Set<PosixFile> restorecon) throws IOException, SQLException;

  /**
   * Determines if should have anonymous FTP area.
   */
  public boolean enableAnonymousFtp() {
    return httpdSite.getEnableAnonymousFtp();
  }

  /**
   * Configures the anonymous FTP directory associated with this site.
   * If the site is disabled, will make owner root, group root, mode 0700.
   * Will reset ownership and permissions as needed.
   * This will only be called when <code>enableAnonymousFtp</code> returns <code>true</code>.
   * Manual mode has no impact on the ownership and permissions set.
   *
   * @see  #enableAnonymousFtp()
   * @see  FTPManager#doRebuildSharedFtpDirectory()
   */
  public void configureFtpDirectory(PosixFile ftpDirectory, Set<PosixFile> restorecon) throws IOException, SQLException {
    if (httpdSite.isDisabled()) {
      // Disabled
      if (DaemonFileUtils.mkdir(ftpDirectory, 0700, PosixFile.ROOT_UID, PosixFile.ROOT_GID)) {
        restorecon.add(ftpDirectory);
      }
    } else {
      // Enabled
      if (DaemonFileUtils.mkdir(ftpDirectory, 0775, httpdSite.getLinuxServerAccount().getUid().getId(), httpdSite.getLinuxServerGroup().getGid().getId())) {
        restorecon.add(ftpDirectory);
      }
    }
  }

  /**
   * Determines if CGI should be enabled.
   *
   * @see  Site#getEnableCgi()
   */
  protected boolean enableCgi() {
    return httpdSite.getEnableCgi();
  }

  /**
   * Determines if PHP should be enabled.
   *
   * If this is enabled and CGI is disabled, then the HttpdServer for the
   * site must use mod_php.
   *
   * @see  Site#getPhpVersion()
   */
  protected boolean enablePhp() throws IOException, SQLException {
    return httpdSite.getPhpVersion() != null;
  }

  /**
   * Creates or updates the CGI php script, CGI must be enabled and PHP enabled.
   * If CGI is disabled or PHP is disabled, removes any php script.
   * Any existing file will be overwritten, even when in manual mode.
   */
  protected void createCgiPhpScript(PosixFile siteDirectory, PosixFile cgibinDirectory, Set<PosixFile> restorecon) throws IOException, SQLException {
    PosixFile phpFile = new PosixFile(cgibinDirectory, "php", false);
    // TODO: If every server this site runs as uses mod_php, then don't make the script? (and the config that refers to this script)
    if (enableCgi() && enablePhp()) {
      Server thisServer = AOServDaemon.getThisServer();
      final OperatingSystemVersion osv = thisServer.getHost().getOperatingSystemVersion();
      final int osvId = osv.getPkey();

      PackageManager.PackageName requiredPackage;
      String phpVersion = httpdSite.getPhpVersion().getVersion();
      PosixFile phpD;
      PosixFile varDir;
      PosixFile varPhpDir;
      PosixFile sessionDir;
      // Build to RAM first
      ByteArrayOutputStream bout = new ByteArrayOutputStream();
      try (ChainWriter out = new ChainWriter(bout)) {
        String phpMinorVersion;
        out.print("#!/bin/sh\n");
        if (osvId == OperatingSystemVersion.CENTOS_5_I686_AND_X86_64) {
          if (phpVersion.startsWith("4.4.")) {
            phpMinorVersion = "4.4";
            requiredPackage = null;
            out.print(". /opt/mysql-5.0-i686/setenv.sh\n");
            out.print(". /opt/postgresql-7.3-i686/setenv.sh\n");
          } else if (phpVersion.startsWith("5.2.")) {
            phpMinorVersion = "5.2";
            requiredPackage = PackageManager.PackageName.PHP_5_2_I686;
            out.print(". /opt/mysql-5.0-i686/setenv.sh\n");
            out.print(". /opt/postgresql-8.3-i686/setenv.sh\n");
          } else if (phpVersion.startsWith("5.3.")) {
            phpMinorVersion = "5.3";
            requiredPackage = PackageManager.PackageName.PHP_5_3_I686;
            out.print(". /opt/mysql-5.1-i686/setenv.sh\n");
            out.print(". /opt/postgresql-8.3-i686/setenv.sh\n");
          } else if (phpVersion.startsWith("5.4.")) {
            phpMinorVersion = "5.4";
            requiredPackage = PackageManager.PackageName.PHP_5_4_I686;
            out.print(". /opt/mysql-5.6-i686/setenv.sh\n");
            out.print(". /opt/postgresql-9.2-i686/setenv.sh\n");
          } else if (phpVersion.startsWith("5.5.")) {
            phpMinorVersion = "5.5";
            requiredPackage = PackageManager.PackageName.PHP_5_5_I686;
            out.print(". /opt/mysql-5.6-i686/setenv.sh\n");
            out.print(". /opt/postgresql-9.2-i686/setenv.sh\n");
          } else if (phpVersion.startsWith("5.6.")) {
            phpMinorVersion = "5.6";
            requiredPackage = PackageManager.PackageName.PHP_5_6_I686;
            out.print(". /opt/mysql-5.7-i686/setenv.sh\n");
            out.print(". /opt/postgresql-9.4-i686/setenv.sh\n");
          } else {
            throw new SQLException("Unexpected version for php: " + phpVersion);
          }
          phpD = null; // No cgi-bin/php.d directory
          varDir = null; // No per-site PHP data
          varPhpDir = null; // No per-site PHP data
          sessionDir = null; // No per-site PHP sessions
        } else if (osvId == OperatingSystemVersion.CENTOS_7_X86_64) {
          if (phpVersion.startsWith("5.3.")) {
            phpMinorVersion = "5.3";
            requiredPackage = PackageManager.PackageName.PHP_5_3;
            out.print(". /opt/mysql-5.1/setenv.sh\n");
            out.print(". /opt/postgresql-8.3/setenv.sh\n");
            phpD = null; // No cgi-bin/php.d directory
          } else if (phpVersion.startsWith("5.4.")) {
            phpMinorVersion = "5.4";
            requiredPackage = PackageManager.PackageName.PHP_5_4;
            out.print(". /opt/mysql-5.6/profile.sh\n");
            out.print(". /opt/postgresql-9.2/setenv.sh\n");
            phpD = null; // No cgi-bin/php.d directory
          } else if (phpVersion.startsWith("5.5.")) {
            phpMinorVersion = "5.5";
            requiredPackage = PackageManager.PackageName.PHP_5_5;
            out.print(". /opt/mysql-5.6/profile.sh\n");
            out.print(". /opt/postgresql-9.4/profile.sh\n");
            phpD = null; // No cgi-bin/php.d directory
          } else if (phpVersion.startsWith("5.6.")) {
            phpMinorVersion = "5.6";
            requiredPackage = PackageManager.PackageName.PHP_5_6;
            out.print(". /opt/mysql-5.7/profile.sh\n");
            out.print(". /opt/postgresql-9.4/profile.sh\n");
            phpD = null; // No cgi-bin/php.d directory
          } else {
            // All other versions 7+
            phpMinorVersion = HttpdServerManager.getMinorPhpVersion(phpVersion);
            requiredPackage = PackageManager.PackageName.valueOf("PHP_" + phpMinorVersion.replace('.', '_'));
            // Support PHP_INI_SCAN_DIR
            phpD = new PosixFile(cgibinDirectory, CGI_PHP_D, true);
            out.print("export PHP_INI_SCAN_DIR='").print(phpD.getPath()).print("'\n");
          }
          varDir = new PosixFile(siteDirectory, VAR, true);
          varPhpDir = new PosixFile(varDir, VAR_PHP, true);
          sessionDir = new PosixFile(varPhpDir, PHP_SESSION, true);
        } else {
          throw new AssertionError("Unsupported OperatingSystemVersion: " + osv);
        }
        out
            .print("exec ")
            .print(HttpdOperatingSystemConfiguration.getHttpOperatingSystemConfiguration().getPhpCgiPath(phpMinorVersion))
            .print(" -d session.save_path=\"")
            .print(sessionDir)
            .print("\" \"$@\"\n");
      }
      // Make sure required RPM is installed
      if (requiredPackage != null) {
        PackageManager.installPackage(requiredPackage);
      }

      // Only rewrite when needed
      int uid = httpdSite.getLinuxServerAccount().getUid().getId();
      int gid = httpdSite.getLinuxServerGroup().getGid().getId();
      int mode = 0755;
      // Create parent if missing
      PosixFile parent = cgibinDirectory.getParent();
      if (!parent.getStat().exists()) {
        parent.mkdir(true, 0775, uid, gid);
      }
      // Create cgi-bin if missing
      if (DaemonFileUtils.mkdir(cgibinDirectory, 0755, uid, gid)) {
        restorecon.add(cgibinDirectory);
      }
      // Create cgi-bin/php.d directory if missing
      if (phpD != null) {
        if (DaemonFileUtils.mkdir(phpD, 0750, uid, gid)) {
          restorecon.add(phpD);
        }
        // TODO: Create/update symlinks in php.d directory
      } else {
        // TODO: Remove auto symlinks from php.d if no longer needed
        // TODO: Remove php.d directory if now empty
      }
      // Create var directory if missing
      if (varDir != null) {
        if (DaemonFileUtils.mkdir(varDir, 0770, uid, gid)) {
          restorecon.add(varDir);
        }
        // Create var/php directory if missing
        if (varPhpDir != null) {
          if (DaemonFileUtils.mkdir(varPhpDir, 0770, uid, gid)) {
            restorecon.add(varPhpDir);
          }
          // Create var/php/session directory if missing
          if (sessionDir != null) {
            if (DaemonFileUtils.mkdir(sessionDir, 0770, uid, gid)) {
              restorecon.add(sessionDir);
            }
          } else {
            // TODO: Remove unused session directory if exists (and not needed by mod_php)?
          }
        } else {
          // TODO: Remove unused session directory if exists (and not needed by mod_php)?
        }
      }
      // TODO: Create/update a php.ini symlink in cgi-bin as a clean placeholder for where client can manage own config
      DaemonFileUtils.atomicWrite(
          phpFile,
          bout.toByteArray(),
          mode,
          uid,
          gid,
          null,
          restorecon
      );
      // Make sure permissions correct
      Stat phpStat = phpFile.getStat();
      if (phpStat.getUid() != uid || phpStat.getGid() != gid) {
        phpFile.chown(uid, gid);
      }
      if (phpStat.getMode() != mode) {
        phpFile.setMode(mode);
      }
    } else {
      if (phpFile.getStat().exists()) {
        phpFile.delete();
      }
      // Remove any php.ini, too
      PosixFile phpIniFile = new PosixFile(cgibinDirectory, "php.ini", false);
      if (phpIniFile.getStat().exists()) {
        // TODO: Backup-and-delete?
        phpIniFile.delete();
      }
    }
  }

  /**
   * Creates the test index.html file if it is missing.
   *
   * TODO: Generate proper disabled page automatically.
   *       Or, better, put into logic of static site rebuild.
   */
  protected void createTestIndex(PosixFile indexFile) throws IOException, SQLException {
    if (!indexFile.getStat().exists()) {
      VirtualHostName primaryHsu = httpdSite.getPrimaryHttpdSiteURL();
      String primaryUrl = primaryHsu == null ? httpdSite.getName() : primaryHsu.getHostname().toString();
      // Write to temp file first
      try (
        TempFileContext tempFileContext = new TempFileContext(indexFile.getFile().getParent());
        TempFile tempFile = tempFileContext.createTempFile(indexFile.getFile().getName())
          ) {
        try (ChainWriter out = new ChainWriter(new FileOutputStream(tempFile.getFile()))) {
          out.print("<html>\n"
              + "  <head><title>Test HTML Page for ").textInXhtml(primaryUrl).print("</title></head>\n"
              + "  <body>\n"
              + "    Test HTML Page for ").textInXhtml(primaryUrl).print("\n"
              + "  </body>\n"
              + "</html>\n");
        }
        // Set permissions and ownership
        PosixFile tempUF = new PosixFile(tempFile.getFile());
        tempUF.setMode(0664);
        tempUF.chown(httpdSite.getLinuxServerAccount().getUid().getId(), httpdSite.getLinuxServerGroup().getGid().getId());
        // Move into place
        tempUF.renameTo(indexFile);
      }
    }
  }

  /**
   * Gets the user ID that apache for this site runs as.
   * If this site runs as multiple UIDs on multiple Apache instances, will
   * return the "apache" user.
   * If the site has no binds, returns UID for "apache".
   * If the site is named <code>Site.DISABLED</code>, always returns UID for "apache".
   */
  public int getApacheUid() throws IOException, SQLException {
    int uid = -1;
    if (!Site.DISABLED.equals(httpdSite.getName())) {
      for (VirtualHost hsb : httpdSite.getHttpdSiteBinds()) {
        int hsUid = hsb.getHttpdBind().getHttpdServer().getLinuxServerAccount().getUid().getId();
        if (uid == -1) {
          uid = hsUid;
        } else if (uid != hsUid) {
          // uid mismatch, fall-through to use "apache"
          uid = -1;
          break;
        }
      }
    }
    if (uid == -1) {
      Server thisServer = AOServDaemon.getThisServer();
      UserServer apacheLsa = thisServer.getLinuxServerAccount(User.APACHE);
      if (apacheLsa == null) {
        throw new SQLException("Unable to find UserServer: " + User.APACHE + " on " + thisServer.getHostname());
      }
      uid = apacheLsa.getUid().getId();
    }
    return uid;
  }

  private static final List<Location> cvsRejectedLocations = Collections.unmodifiableList(
      Arrays.asList(
          new Location(true, ".*/\\.#.*"),
          new Location(true, ".*/CVS(/.*|$)"),
          new Location(true, ".*/CVSROOT(/.*|$)"),
          new Location(true, ".*/\\.cvsignore(/.*|$)")
      //standardRejectedLocations.add(new Location(true, "/CVS/Attic"));
      //standardRejectedLocations.add(new Location(true, "/CVS/Entries"));
      // Already covered by Entries: standardRejectedLocations.add(new Location(true, "/CVS/Entries\\.Static"));
      //standardRejectedLocations.add(new Location(true, "/CVS/Repository"));
      //standardRejectedLocations.add(new Location(true, "/CVS/RevisionCache"));
      //standardRejectedLocations.add(new Location(true, "/CVS/Root"));
      //standardRejectedLocations.add(new Location(true, "/CVS/\\.#merg"));
    )
  );

  private static final List<Location> subversionRejectedLocations = Collections.unmodifiableList(
      Arrays.asList(
          new Location(true, ".*/\\.svn(/.*|$)"),
          new Location(true, ".*/\\.svnignore(/.*|$)")
      )
  );

  private static final List<Location> gitRejectedLocations = Collections.unmodifiableList(
      Arrays.asList(
          new Location(true, ".*/\\.git(/.*|$)"),
          new Location(true, ".*/\\.gitignore(/.*|$)")
      )
  );

  private static final List<Location> coreDumpsRejectedLocations = Collections.singletonList(new Location(true, ".*/core\\.[0-9]{1,5}(/.*|$)"));

  private static final List<Location> emacsRejectedLocations = Collections.unmodifiableList(
      Arrays.asList(
          new Location(true, ".*/[^/]+~(/.*|$)"),
          new Location(true, ".*/#[^/]+#(/.*|$)")
      )
  );

  private static final List<Location> vimRejectedLocations = Collections.singletonList(new Location(true, ".*/\\.[^/]+\\.swp(/.*|$)"));

  public static class Location implements Comparable<Location> {

    private final boolean isRegularExpression;
    private final String location;

    public Location(boolean isRegularExpression, String location) {
      this.isRegularExpression = isRegularExpression;
      this.location = location;
    }

    public boolean isRegularExpression() {
      return isRegularExpression;
    }

    public String getLocation() {
      return location;
    }

    @Override
    public int hashCode() {
      int hashCode = location.hashCode();
      // Negate for regular expressions
      if (isRegularExpression) {
        hashCode = -hashCode;
      }
      return hashCode;
    }

    @Override
    public boolean equals(Object obj) {
      if (!(obj instanceof Location)) {
        return false;
      }
      Location other = (Location) obj;
      return
          isRegularExpression == other.isRegularExpression
              && location.equals(other.location)
      ;
    }

    @Override
    public int compareTo(Location other) {
      // Put non regular expressions first since they are (presumably) faster to process
      if (!isRegularExpression && other.isRegularExpression) {
        return -1;
      }
      if (isRegularExpression && !other.isRegularExpression) {
        return 1;
      }
      // Then compare by location
      return location.compareTo(other.location);
    }
  }

  /**
   * Gets an unmodifiable map of URL patterns that should be rejected.
   */
  public Map<String, List<Location>> getRejectedLocations() throws IOException, SQLException {
    OperatingSystemVersion osv = AOServDaemon.getThisServer().getHost().getOperatingSystemVersion();
    int osvId = osv.getPkey();
    if (osvId == OperatingSystemVersion.CENTOS_5_I686_AND_X86_64) {
      // Protection is built into the config files
      Map<String, List<Location>> rejectedLocations = new LinkedHashMap<>();
      if (httpdSite.getBlockScm()) {
        rejectedLocations.put("Protect CVS files", cvsRejectedLocations);
        rejectedLocations.put("Protect Subversion files", subversionRejectedLocations);
        rejectedLocations.put("Protect Git files", gitRejectedLocations);
      }
      if (httpdSite.getBlockCoreDumps()) {
        rejectedLocations.put("Protect core dumps", coreDumpsRejectedLocations);
      }
      if (httpdSite.getBlockEditorBackups()) {
        rejectedLocations.put("Protect emacs / kwrite auto-backups", emacsRejectedLocations);
        rejectedLocations.put("Protect vi / vim auto-backups", vimRejectedLocations);
        // TODO: nano .save files? https://askubuntu.com/questions/601985/what-are-save-files
      }
      return Collections.unmodifiableMap(rejectedLocations);
    } else if (osvId == OperatingSystemVersion.CENTOS_7_X86_64) {
      // Protection has been moved to include files in aoserv-httpd-config package.
      return Collections.emptyMap();
    } else {
      throw new AssertionError("Unsupported OperatingSystemVersion: " + osv);
    }
  }

  public static class PermanentRewriteRule {
    public final String pattern;
    public final String substitution;
    public final boolean noEscape;

    private PermanentRewriteRule(String pattern, String substitution, boolean noEscape) {
      this.pattern = pattern;
      this.substitution = substitution;
      this.noEscape = noEscape;
    }

    private PermanentRewriteRule(String pattern, String substitution) {
      this(pattern, substitution, true);
    }
  }

  //private static final List<PermanentRewriteRule> standardPermanentRewriteRules = new ArrayList<>();
  //private static final List<PermanentRewriteRule> unmodifiableStandardPermanentRewriteRules = Collections.unmodifiableList(standardPermanentRewriteRules);
  //static {
    // emacs / kwrite
    // Moved to rejected patterns: standardPermanentRewriteRules.add(new PermanentRewriteRule("^(.*)~$", "$1"));
    // Moved to rejected patterns: standardPermanentRewriteRules.add(new PermanentRewriteRule("^(.*)~/(.*)$", "$1/$2"));

    // vi / vim
    // .test.php.swp
    // Moved to rejected patterns: standardPermanentRewriteRules.add(new PermanentRewriteRule("^(.*/)\\.([^/]+)\\.swp$", "$1$2"));
    // Moved to rejected patterns: standardPermanentRewriteRules.add(new PermanentRewriteRule("^(.*/)\\.([^/]+)\\.swp/(.*)$", "$1$2/$3"));

    // Some other kind (seen as left-over #wp-config.php# in web root)
    // #wp-config.php#
    // Moved to rejected patterns: standardPermanentRewriteRules.add(new PermanentRewriteRule("^(.*/)#([^/]+)#$", "$1$2")); // TODO [NE]? % encoded?
    // Moved to rejected patterns: standardPermanentRewriteRules.add(new PermanentRewriteRule("^(.*/)#([^/]+)#/(.*)$", "$1$2/$3")); // TODO [NE]? % encoded?

    // TODO: nano .save files? https://askubuntu.com/questions/601985/what-are-save-files

    // Should we report these in the distro scans instead of using these rules?

    //standardPermanentRewriteRules.put("^(.*)\\.do~$", "$1.do");
    //standardPermanentRewriteRules.put("^(.*)\\.do~/(.*)$", "$1.do/$2");
    //standardPermanentRewriteRules.put("^(.*)\\.jsp~$", "$1.jsp");
    //standardPermanentRewriteRules.put("^(.*)\\.jsp~/(.*)$", "$1.jsp/$2");
    //standardPermanentRewriteRules.put("^(.*)\\.jspa~$", "$1.jspa");
    //standardPermanentRewriteRules.put("^(.*)\\.jspa~/(.*)$", "$1.jspa/$2");
    //standardPermanentRewriteRules.put("^(.*)\\.php~$", "$1.php");
    //standardPermanentRewriteRules.put("^(.*)\\.php~/(.*)$", "$1.php/$2");
    //standardPermanentRewriteRules.put("^(.*)\\.php3~$", "$1.php3");
    //standardPermanentRewriteRules.put("^(.*)\\.php3~/(.*)$", "$1.php3/$2");
    //standardPermanentRewriteRules.put("^(.*)\\.php4~$", "$1.php4");
    //standardPermanentRewriteRules.put("^(.*)\\.php4~/(.*)$", "$1.php4/$2");
    //standardPermanentRewriteRules.put("^(.*)\\.phtml~$", "$1.phtml");
    //standardPermanentRewriteRules.put("^(.*)\\.phtml~/(.*)$", "$1.phtml/$2");
    //standardPermanentRewriteRules.put("^(.*)\\.shtml~$", "$1.shtml");
    //standardPermanentRewriteRules.put("^(.*)\\.shtml~/(.*)$", "$1.shtml/$2");
    //standardPermanentRewriteRules.put("^(.*)\\.vm~$", "$1.vm");
    //standardPermanentRewriteRules.put("^(.*)\\.vm~/(.*)$", "$1.vm/$2");
    //standardPermanentRewriteRules.put("^(.*)\\.xml~$", "$1.xml");
    //standardPermanentRewriteRules.put("^(.*)\\.xml~/(.*)$", "$1.xml/$2");
  //}

  /**
   * Gets the set of permanent rewrite rules.  By default, no rules.
   */
  public List<PermanentRewriteRule> getPermanentRewriteRules() {
    //return unmodifiableStandardPermanentRewriteRules;
    return Collections.emptyList();
  }

  /**
   * By default, sites will block all TRACE and TRACK requests.
   *
   * Seriously consider security ramifications before enabling TRACK and TRACE.
   *
   * @see  Site#getBlockTraceTrack()
   */
  public boolean blockAllTraceAndTrackRequests() {
    return httpdSite.getBlockTraceTrack();
  }

  public static class JkSetting implements Comparable<JkSetting> {

    private final boolean isMount;
    private final String path;
    private final String jkCode;

    public JkSetting(boolean isMount, String path, String jkCode) {
      this.isMount = isMount;
      this.path = path;
      this.jkCode = jkCode;
    }

    public boolean isMount() {
      return isMount;
    }

    public String getPath() {
      return path;
    }

    public String getJkCode() {
      return jkCode;
    }

    @Override
    public int hashCode() {
      int hashCode = path.hashCode() * 31 + jkCode.hashCode();
      // Negate for mounts
      if (isMount) {
        hashCode = -hashCode;
      }
      return hashCode;
    }

    @Override
    public boolean equals(Object obj) {
      if (!(obj instanceof JkSetting)) {
        return false;
      }
      JkSetting other = (JkSetting) obj;
      return
          isMount == other.isMount
              && path.equals(other.path)
              && jkCode.equals(other.jkCode)
      ;
    }

    @Override
    public int compareTo(JkSetting other) {
      // Put mounts before unmounts
      if (isMount && !other.isMount) {
        return -1;
      }
      if (!isMount && other.isMount) {
        return 1;
      }
      // Then compare by path
      int diff = path.compareTo(other.path);
      if (diff != 0) {
        return diff;
      }
      // Finallyl by jkCode
      return jkCode.compareTo(other.jkCode);
    }
  }

  private static final SortedSet<JkSetting> emptyJkSettings = Collections.unmodifiableSortedSet(new TreeSet<>());

  /**
   * Gets the JkMount and JkUnmounts for this site.
   *
   * This default implementation returns an empty set.
   *
   * @return  An empty set if no Jk enabled.
   */
  public SortedSet<JkSetting> getJkSettings() throws IOException, SQLException {
    return emptyJkSettings;
  }

  public static class WebAppSettings {

    /** https://httpd.apache.org/docs/2.4/mod/core.html#options */
    public static String generateOptions(
        boolean enableSsi,
        boolean enableIndexes,
        boolean enableFollowSymlinks
    ) {
      StringBuilder options = new StringBuilder();
      if (enableFollowSymlinks) {
        options.append("FollowSymLinks");
      }
      if (enableSsi) {
        if (options.length() > 0) {
          options.append(' ');
        }
        options.append("IncludesNOEXEC");
      }
      if (enableIndexes) {
        if (options.length() > 0) {
          options.append(' ');
        }
        options.append("Indexes");
      }
      if (options.length() == 0) {
        return "None";
      } else {
        return options.toString();
      }
    }

    /** https://httpd.apache.org/docs/2.4/mod/core.html#options */
    public static String generateCgiOptions(
        boolean enableCgi,
        boolean enableFollowSymlinks
    ) {
      if (enableCgi) {
        if (enableFollowSymlinks) {
          return "ExecCGI FollowSymLinks";
        } else {
          return "ExecCGI";
        }
      } else {
        return "None";
      }
    }

    private final PosixPath docBase;
    private final String allowOverride;
    private final String options;
    private final boolean enableSsi;
    private final boolean enableCgi;
    private final String cgiOptions;

    public WebAppSettings(PosixPath docBase, String allowOverride, String options, boolean enableSsi, boolean enableCgi, String cgiOptions) {
      this.docBase = docBase;
      this.allowOverride = allowOverride;
      this.options = options;
      this.enableSsi = enableSsi;
      this.enableCgi = enableCgi;
      this.cgiOptions = cgiOptions;
    }

    public WebAppSettings(
        PosixPath docBase,
        String allowOverride,
        boolean enableSsi,
        boolean enableIndexes,
        boolean enableFollowSymlinks,
        boolean enableCgi
    ) {
      this(
          docBase,
          allowOverride,
          generateOptions(enableSsi, enableIndexes, enableFollowSymlinks),
          enableSsi,
          enableCgi,
          generateCgiOptions(enableCgi, enableFollowSymlinks)
      );
    }

    public PosixPath getDocBase() {
      return docBase;
    }

    public String getAllowOverride() {
      return allowOverride;
    }

    public String getOptions() {
      return options;
    }

    public boolean enableSsi() {
      return enableSsi;
    }

    public boolean enableCgi() {
      return enableCgi;
    }

    public String getCgiOptions() {
      return cgiOptions;
    }
  }

  /**
   * Gets all the webapps for this site.  The key is the webapp path
   * and the value is the settings for that path.  If any webapp enables
   * CGI, then this site overall must allow CGI.
   */
  public abstract SortedMap<String, WebAppSettings> getWebapps() throws IOException, SQLException;
}
